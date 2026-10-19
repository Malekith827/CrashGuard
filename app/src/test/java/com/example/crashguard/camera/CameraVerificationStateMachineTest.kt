package com.example.crashguard.camera

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.RecoveryState
import com.example.crashguard.vision.VisionClassificationResult
import com.example.crashguard.vision.VisionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraVerificationStateMachineTest {

    private lateinit var stateMachine: CameraVerificationStateMachine

    private fun sampleAlert(state: String = "PERSISTENT_ABNORMAL"): CrashAlertState {
        return CrashAlertState(
            timestampNs = 1_000_000_000L,
            stage1Score = 0.650f,
            stage2Score = 0.920f,
            shockScore = 1.0f,
            temporalScore = 0.95f,
            temporalDelayS = 0.05f,
            recoveryState = state,
            recoveryObservedS = 3.0f,
            recoveryHorizonS = 3.0f,
            isConfirmed = true,
            details = "S1=0.650, S2=0.920"
        )
    }

    @Before
    fun setUp() {
        stateMachine = CameraVerificationStateMachine()
    }

    @Test
    fun testRecovered_doesNotStartCamera() {
        val alert = sampleAlert(state = "RECOVERED")
        val started = stateMachine.onImuRecoveryState(RecoveryState.RECOVERED, alert)

        assertFalse("Expected camera NOT to start on RECOVERED state", started)
        assertEquals("Expected state to remain IDLE", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected currentSession to be null", stateMachine.currentSession)
    }

    @Test
    fun testPersistentAbnormal_startsCameraExactlyOnce() {
        val alert = sampleAlert(state = "PERSISTENT_ABNORMAL")
        val started = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        assertTrue("Expected camera to start on PERSISTENT_ABNORMAL state", started)
        assertEquals("Expected state to be VERIFYING", CameraState.VERIFYING, stateMachine.currentState)
        assertNotNull("Expected active session", stateMachine.currentSession)
        assertEquals(
            "Expected session alert timestamp",
            alert.timestampNs,
            stateMachine.currentSession?.crashAlert?.timestampNs
        )
        assertEquals(
            "Expected default outcome PENDING",
            CameraVerificationOutcome.PENDING,
            stateMachine.currentSession?.outcome
        )
    }

    @Test
    fun testDuplicatePersistentAbnormal_doesNotLaunchMultipleCameraSessions() {
        val alert1 = sampleAlert()
        val firstStart = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert1)
        assertTrue("Expected first launch to succeed", firstStart)

        val firstSessionId = stateMachine.currentSession?.sessionId

        val alert2 = sampleAlert().copy(timestampNs = 2_000_000_000L)
        val secondStart = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert2)

        assertFalse("Expected duplicate launch to be rejected", secondStart)
        assertEquals("Expected state to remain VERIFYING", CameraState.VERIFYING, stateMachine.currentState)
        assertEquals("Expected session ID not to change", firstSessionId, stateMachine.currentSession?.sessionId)
    }

    @Test
    fun testFalseAlarmCancel_returnsToIdle() {
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        stateMachine.onOutcomeSelected(CameraVerificationOutcome.FALSE_ALARM)
        assertEquals("Expected state to be FINAL_RESULT", CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)

        stateMachine.dismiss()
        assertEquals("Expected state to return to IDLE after dismissal", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected session to be cleared", stateMachine.currentSession)
    }

    @Test
    fun testCrashConfirmed_returnsToIdleAfterFinalResult() {
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        stateMachine.onOutcomeSelected(
            CameraVerificationOutcome.CRASH_CONFIRMED,
            videoPath = "/storage/emulated/0/crash_123.mp4",
            metaPath = "/storage/emulated/0/crash_123_meta.json"
        )

        assertEquals("Expected state to be FINAL_RESULT", CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.CRASH_CONFIRMED, stateMachine.currentSession?.outcome)
        assertEquals("/storage/emulated/0/crash_123.mp4", stateMachine.currentSession?.videoFilePath)

        stateMachine.dismiss()
        assertEquals("Expected state to return to IDLE", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected session to be cleared", stateMachine.currentSession)
    }

    @Test
    fun testUncertain_returnsToIdle() {
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        stateMachine.onOutcomeSelected(CameraVerificationOutcome.UNCERTAIN)
        assertEquals("Expected state to be FINAL_RESULT", CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.UNCERTAIN, stateMachine.currentSession?.outcome)

        stateMachine.dismiss()
        assertEquals("Expected state to return to IDLE", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected session to be cleared", stateMachine.currentSession)
    }

    // --- Race-Condition & In-Flight Vision Verification Tests ---

    @Test
    fun testVisionReturnsBeforeCountdownExpiry_resolvesNormally() {
        // Requirement A: Vision returns before countdown expiry -> resolves normally
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        stateMachine.onVisionAnalysisStarted("/recordings/crash_1.mp4")

        val visionResult = VisionClassificationResult(
            decision = VisionDecision.NO_CRASH,
            confidence = 0.98f,
            reason = "Rider riding upright"
        )

        stateMachine.onOutcomeSelected(
            outcome = CameraVerificationOutcome.FALSE_ALARM,
            videoPath = "/recordings/crash_1.mp4",
            visionResult = visionResult
        )

        assertEquals(CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)
        assertEquals(visionResult, stateMachine.currentSession?.visionResult)
        assertFalse(stateMachine.currentSession?.isAnalyzingVision ?: true)
    }

    @Test
    fun testVisionReturnsAfterOldCountdownPeriod_resolvesCorrectly() {
        // Requirement B & C: Countdown reached while Vision is in-flight does not close session; later Vision result resolves
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        stateMachine.onVisionAnalysisStarted("/recordings/crash_2.mp4")

        // Countdown ticks to 0 while Vision is active
        stateMachine.onCountdownTick(0)
        assertEquals(CameraState.VERIFYING, stateMachine.currentState)
        assertTrue(stateMachine.currentSession?.isAnalyzingVision == true)

        // Vision completes at t = 14s
        val visionResult = VisionClassificationResult(
            decision = VisionDecision.NO_CRASH,
            confidence = 1.0f,
            reason = "Normal riding"
        )
        stateMachine.onOutcomeSelected(
            outcome = CameraVerificationOutcome.FALSE_ALARM,
            videoPath = "/recordings/crash_2.mp4",
            visionResult = visionResult
        )

        assertEquals(CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)
    }

    @Test
    fun testVisionTimeout_resolvesToUncertainAndDismisses() {
        // Requirement D: Vision timeout -> UNCERTAIN -> FINAL_RESULT -> dismissal
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        stateMachine.onVisionAnalysisStarted("/recordings/crash_3.mp4")

        val fallbackResult = VisionClassificationResult(
            decision = VisionDecision.UNCERTAIN,
            confidence = 0.0f,
            reason = "Network or timeout error: timeout"
        )

        stateMachine.onOutcomeSelected(
            outcome = CameraVerificationOutcome.UNCERTAIN,
            videoPath = "/recordings/crash_3.mp4",
            visionResult = fallbackResult
        )

        assertEquals(CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.UNCERTAIN, stateMachine.currentSession?.outcome)

        stateMachine.dismiss()
        assertEquals(CameraState.IDLE, stateMachine.currentState)
        assertNull(stateMachine.currentSession)
    }

    @Test
    fun testManualOverrideWhileVisionProcessing_manualResultWins() {
        // Requirement E & F: Manual override while Vision processing -> manual result wins and Vision cannot overwrite
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        stateMachine.onVisionAnalysisStarted("/recordings/crash_4.mp4")

        // User manually taps FALSE ALARM
        stateMachine.onOutcomeSelected(CameraVerificationOutcome.FALSE_ALARM)
        assertEquals(CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)

        // Vision later finishes with CRASH_CONFIRMED -> must be ignored!
        val lateVisionResult = VisionClassificationResult(
            decision = VisionDecision.CRASH,
            confidence = 0.99f,
            reason = "Late result"
        )
        stateMachine.onOutcomeSelected(
            outcome = CameraVerificationOutcome.CRASH_CONFIRMED,
            visionResult = lateVisionResult
        )

        // State remains FALSE_ALARM from manual override
        assertEquals(CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)
    }

    @Test
    fun testDismissedSession_cannotBeResurrectedByLateOutcome() {
        // Requirement G: Once dismissed, late callbacks cannot resurrect state machine
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        stateMachine.onOutcomeSelected(CameraVerificationOutcome.FALSE_ALARM)
        stateMachine.dismiss()

        assertEquals(CameraState.IDLE, stateMachine.currentState)
        assertNull(stateMachine.currentSession)

        // Attempting to select outcome in IDLE state is rejected
        stateMachine.onOutcomeSelected(CameraVerificationOutcome.CRASH_CONFIRMED)
        assertEquals(CameraState.IDLE, stateMachine.currentState)
        assertNull(stateMachine.currentSession)
    }
}
