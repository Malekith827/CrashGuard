package com.example.crashguard.sms

import android.app.PendingIntent
import android.content.Context
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.data.CrashAlertState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencySmsManagerTest {

    private class FakeEmergencyContactRepository(
        private var contact: String? = null
    ) : EmergencyContactRepository {
        override fun getEmergencyContact(): String? = contact
        override fun saveEmergencyContact(phoneNumber: String): Boolean {
            if (!PhoneNumberValidator.isValid(phoneNumber)) return false
            contact = PhoneNumberValidator.normalize(phoneNumber)
            return true
        }
        override fun clearEmergencyContact(): Boolean {
            contact = null
            return true
        }
        override fun hasEmergencyContact(): Boolean = contact != null
    }

    private class FakeSmsSender : SmsSender {
        data class SentRecord(val recipient: String, val message: String)
        val sentMessages = mutableListOf<SentRecord>()
        var shouldThrow: Boolean = false

        override fun sendSms(
            destinationAddress: String,
            message: String,
            sentIntent: PendingIntent?,
            deliveryIntent: PendingIntent?
        ): Boolean {
            if (shouldThrow) {
                throw RuntimeException("Cellular radio unavailable")
            }
            sentMessages.add(SentRecord(destinationAddress, message))
            return true
        }
    }

    private fun sampleSession(
        sessionId: String,
        outcome: CameraVerificationOutcome
    ): CameraVerificationSession {
        val alert = CrashAlertState(
            timestampNs = 1_000_000_000L,
            stage1Score = 0.750f,
            stage2Score = 0.900f,
            shockScore = 1.0f,
            temporalScore = 0.85f,
            temporalDelayS = 0.05f,
            recoveryState = "PERSISTENT_ABNORMAL",
            recoveryObservedS = 3.0f,
            recoveryHorizonS = 3.0f,
            isConfirmed = true,
            details = "Test Alert"
        )
        return CameraVerificationSession(
            sessionId = sessionId,
            crashAlert = alert,
            outcome = outcome
        )
    }

    private class FakeContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.example.crashguard"
        override fun getApplicationContext(): Context = this
    }

    private lateinit var fakeContext: Context
    private lateinit var fakeRepository: FakeEmergencyContactRepository
    private lateinit var fakeSmsSender: FakeSmsSender

    @Before
    fun setUp() {
        EmergencySmsManager.reset()
        fakeContext = FakeContext()
        fakeRepository = FakeEmergencyContactRepository("+15551234567")
        fakeSmsSender = FakeSmsSender()
        // By default, permission check passes in tests
        EmergencySmsManager.permissionChecker = { _, _ -> true }
    }

    @After
    fun tearDown() {
        EmergencySmsManager.reset()
    }

    @Test
    fun testSavingAndRetrievingEmergencyContact() {
        val repo = FakeEmergencyContactRepository()
        assertFalse(repo.hasEmergencyContact())

        // Save invalid
        assertFalse(repo.saveEmergencyContact("123"))
        assertFalse(repo.hasEmergencyContact())

        // Save valid
        assertTrue(repo.saveEmergencyContact("+91 9876-543210"))
        assertTrue(repo.hasEmergencyContact())
        assertEquals("+919876543210", repo.getEmergencyContact())

        // Edit/replace
        assertTrue(repo.saveEmergencyContact("+1 555 999 8888"))
        assertEquals("+15559998888", repo.getEmergencyContact())

        // Clear
        assertTrue(repo.clearEmergencyContact())
        assertFalse(repo.hasEmergencyContact())
    }

    @Test
    fun testConfirmedCrash_sendsSmsSuccessfully() {
        val session = sampleSession("session_001", CameraVerificationOutcome.CRASH_CONFIRMED)

        val result = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )

        assertTrue("Expected Success, got $result", result is SmsSendResult.Success)
        assertEquals(1, fakeSmsSender.sentMessages.size)
        assertEquals("+15551234567", fakeSmsSender.sentMessages[0].recipient)
        assertTrue(fakeSmsSender.sentMessages[0].message.contains("CrashGuard alert"))
        assertTrue(EmergencySmsManager.isSessionAlerted("session_001"))
    }

    @Test
    fun testNonCrashOutcomes_doNotSendSms() {
        val outcomes = listOf(
            CameraVerificationOutcome.FALSE_ALARM,
            CameraVerificationOutcome.UNCERTAIN,
            CameraVerificationOutcome.PENDING
        )

        for (outcome in outcomes) {
            val session = sampleSession("session_$outcome", outcome)
            val result = EmergencySmsManager.sendCrashAlertSms(
                context = fakeContext,
                session = session,
                repository = fakeRepository,
                smsSender = fakeSmsSender
            )

            assertTrue("Outcome $outcome should be Skipped", result is SmsSendResult.Skipped)
            assertEquals(SmsSendStatus.SKIPPED_NOT_CONFIRMED, (result as SmsSendResult.Skipped).status)
        }

        assertEquals("No SMS should be sent for non-crash outcomes", 0, fakeSmsSender.sentMessages.size)
    }

    @Test
    fun testMissingEmergencyContact_skipsSms() {
        val emptyRepo = FakeEmergencyContactRepository(null)
        val session = sampleSession("session_no_contact", CameraVerificationOutcome.CRASH_CONFIRMED)

        val result = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = emptyRepo,
            smsSender = fakeSmsSender
        )

        assertTrue(result is SmsSendResult.Skipped)
        assertEquals(SmsSendStatus.SKIPPED_NO_CONTACT, (result as SmsSendResult.Skipped).status)
        assertEquals(0, fakeSmsSender.sentMessages.size)
    }

    @Test
    fun testPermissionDenied_skipsSms() {
        EmergencySmsManager.permissionChecker = { _, _ -> false }
        val session = sampleSession("session_no_perm", CameraVerificationOutcome.CRASH_CONFIRMED)

        val result = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )

        assertTrue(result is SmsSendResult.Skipped)
        assertEquals(SmsSendStatus.SKIPPED_NO_PERMISSION, (result as SmsSendResult.Skipped).status)
        assertEquals(0, fakeSmsSender.sentMessages.size)
    }

    @Test
    fun testDuplicateAlert_forSameIncident_suppressed() {
        val session = sampleSession("session_dup_100", CameraVerificationOutcome.CRASH_CONFIRMED)

        val firstResult = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )
        assertTrue(firstResult is SmsSendResult.Success)
        assertEquals(1, fakeSmsSender.sentMessages.size)

        // Attempt duplicate dispatch for the same session
        val secondResult = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )
        assertTrue(secondResult is SmsSendResult.Skipped)
        assertEquals(SmsSendStatus.SKIPPED_DUPLICATE, (secondResult as SmsSendResult.Skipped).status)
        assertEquals("Sender must not be invoked a second time", 1, fakeSmsSender.sentMessages.size)
    }

    @Test
    fun testDistinctIncident_canAlertAgain() {
        val session1 = sampleSession("session_incident_A", CameraVerificationOutcome.CRASH_CONFIRMED)
        val session2 = sampleSession("session_incident_B", CameraVerificationOutcome.CRASH_CONFIRMED)

        val result1 = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session1,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )
        val result2 = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session2,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )

        assertTrue(result1 is SmsSendResult.Success)
        assertTrue(result2 is SmsSendResult.Success)
        assertEquals(2, fakeSmsSender.sentMessages.size)
        assertTrue(EmergencySmsManager.isSessionAlerted("session_incident_A"))
        assertTrue(EmergencySmsManager.isSessionAlerted("session_incident_B"))
    }

    @Test
    fun testSmsException_returnsFailureResult() {
        fakeSmsSender.shouldThrow = true
        val session = sampleSession("session_throw", CameraVerificationOutcome.CRASH_CONFIRMED)

        val result = EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )

        assertTrue(result is SmsSendResult.Failure)
        assertEquals(SmsSendStatus.FAILED_EXCEPTION, (result as SmsSendResult.Failure).status)
        assertTrue(result.errorMessage.contains("Cellular radio unavailable"))
    }

    @Test
    fun testReset_clearsDeduplication() {
        val session = sampleSession("session_reset_test", CameraVerificationOutcome.CRASH_CONFIRMED)

        EmergencySmsManager.sendCrashAlertSms(
            context = fakeContext,
            session = session,
            repository = fakeRepository,
            smsSender = fakeSmsSender
        )
        assertTrue(EmergencySmsManager.isSessionAlerted("session_reset_test"))

        EmergencySmsManager.reset()
        assertFalse(EmergencySmsManager.isSessionAlerted("session_reset_test"))
    }
}
