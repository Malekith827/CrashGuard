package com.example.crashguard.camera

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.RecoveryState
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
        // Requirement 1: RECOVERED -> camera does NOT start
        val alert = sampleAlert(state = "RECOVERED")
        val started = stateMachine.onImuRecoveryState(RecoveryState.RECOVERED, alert)

        assertFalse("Expected camera NOT to start on RECOVERED state", started)
        assertEquals("Expected state to remain IDLE", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected currentSession to be null", stateMachine.currentSession)
    }

    @Test
    fun testPersistentAbnormal_startsCameraExactlyOnce() {
        // Requirement 2: PERSISTENT_ABNORMAL -> camera starts exactly once
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
        // Requirement 3: duplicate PERSISTENT_ABNORMAL events do not launch multiple camera sessions
        val alert1 = sampleAlert()
        val firstStart = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert1)
        assertTrue("Expected first launch to succeed", firstStart)

        val firstSessionId = stateMachine.currentSession?.sessionId

        // Simulate a duplicate / subsequent trigger arriving while verification is active
        val alert2 = sampleAlert().copy(timestampNs = 2_000_000_000L)
        val secondStart = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert2)

        assertFalse("Expected duplicate launch to be rejected", secondStart)
        assertEquals("Expected state to remain VERIFYING", CameraState.VERIFYING, stateMachine.currentState)
        assertEquals("Expected session ID not to change", firstSessionId, stateMachine.currentSession?.sessionId)
    }

    @Test
    fun testFalseAlarmCancel_returnsToIdle() {
        // Requirement 4: FALSE_ALARM/CANCEL -> returns to IDLE
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
        // Requirement 5: CRASH_CONFIRMED -> returns to IDLE after final result
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
        // Requirement 6: UNCERTAIN -> returns to IDLE
        val alert = sampleAlert()
        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        stateMachine.onOutcomeSelected(CameraVerificationOutcome.UNCERTAIN)
        assertEquals("Expected state to be FINAL_RESULT", CameraState.FINAL_RESULT, stateMachine.currentState)
        assertEquals(CameraVerificationOutcome.UNCERTAIN, stateMachine.currentSession?.outcome)

        stateMachine.dismiss()
        assertEquals("Expected state to return to IDLE", CameraState.IDLE, stateMachine.currentState)
        assertNull("Expected session to be cleared", stateMachine.currentSession)
    }
}
