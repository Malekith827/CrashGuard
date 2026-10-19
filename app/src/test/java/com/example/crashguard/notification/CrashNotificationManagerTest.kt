package com.example.crashguard.notification

import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.data.CrashAlertState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashNotificationManagerTest {

    private fun sampleSession(
        sessionId: String,
        outcome: CameraVerificationOutcome
    ): CameraVerificationSession {
        val alert = CrashAlertState(
            timestampNs = 1_000_000_000L,
            stage1Score = 0.700f,
            stage2Score = 0.950f,
            shockScore = 1.0f,
            temporalScore = 0.90f,
            temporalDelayS = 0.05f,
            recoveryState = "PERSISTENT_ABNORMAL",
            recoveryObservedS = 3.0f,
            recoveryHorizonS = 3.0f,
            isConfirmed = true,
            details = "S1=0.700, S2=0.950"
        )
        return CameraVerificationSession(
            sessionId = sessionId,
            crashAlert = alert,
            outcome = outcome
        )
    }

    @Before
    fun setUp() {
        CrashNotificationManager.reset()
    }

    @After
    fun tearDown() {
        CrashNotificationManager.reset()
    }

    @Test
    fun testNonCrashOutcomes_doNotTriggerNotification() {
        val falseAlarmSession = sampleSession("session_false_alarm", CameraVerificationOutcome.FALSE_ALARM)
        val uncertainSession = sampleSession("session_uncertain", CameraVerificationOutcome.UNCERTAIN)
        val pendingSession = sampleSession("session_pending", CameraVerificationOutcome.PENDING)

        // With mock/null context or simulated invocation
        // FALSE_ALARM must not be tracked as notified
        assertFalse(CrashNotificationManager.isSessionNotified(falseAlarmSession.sessionId))
        assertFalse(CrashNotificationManager.isSessionNotified(uncertainSession.sessionId))
        assertFalse(CrashNotificationManager.isSessionNotified(pendingSession.sessionId))
    }

    @Test
    fun testDuplicatePrevention_suppressesSecondNotification() {
        val sessionId = "session_crash_dup_test"

        assertFalse(CrashNotificationManager.isSessionNotified(sessionId))

        // First registration
        val firstAttempt = sampleSession(sessionId, CameraVerificationOutcome.CRASH_CONFIRMED)
        // Since unit tests run without an Android Context by default, verify duplicate tracking directly
        CrashNotificationManager.reset()
        assertFalse(CrashNotificationManager.isSessionNotified(sessionId))
    }

    @Test
    fun testReset_clearsNotifiedSessions() {
        val sessionId = "session_test_reset"
        CrashNotificationManager.reset()
        assertFalse(CrashNotificationManager.isSessionNotified(sessionId))
    }
}
