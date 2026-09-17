package com.example.crashguard.camera

import android.content.Context
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.RecordingStateHolder
import com.example.crashguard.detection.DetectionLogger
import com.example.crashguard.detection.RecoveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level coordinator managing the Camera Verification lifecycle downstream of IMU.
 */
object CameraVerificationManager {

    private const val TAG = "CrashGuardCamera"
    val stateMachine = CameraVerificationStateMachine()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var countdownJob: Job? = null
    private var activeContext: Context? = null

    init {
        stateMachine.onSessionStarted = { session ->
            RecordingStateHolder.startCameraVerification(session)
            startCountdownTimer(session)
        }

        stateMachine.onOutcomeResolved = { session ->
            RecordingStateHolder.setCameraOutcome(session.outcome)
        }

        stateMachine.onSessionDismissed = {
            stopCountdownTimer()
            RecordingStateHolder.dismissCameraVerification()
        }
    }

    /**
     * Primary entry point invoked when an IMU crash candidate is evaluated.
     */
    fun onCrashCandidateAlert(alert: CrashAlertState, context: Context) {
        activeContext = context.applicationContext
        // Camera verification is triggered ONLY for PERSISTENT_ABNORMAL
        val isStarted = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        if (isStarted) {
            DetectionLogger.i(TAG, "Launched camera verification for candidate at t=${alert.timestampNs}")
        }
    }

    /**
     * Resolves the camera verification outcome.
     */
    fun resolveOutcome(outcome: CameraVerificationOutcome, videoFilePath: String? = null) {
        stopCountdownTimer()
        val session = stateMachine.currentSession
        val metaFile = if (session != null && activeContext != null) {
            CameraRecorder.writeEventMetadata(
                context = activeContext!!,
                sessionId = session.sessionId,
                alert = session.crashAlert,
                outcome = outcome,
                videoFilePath = videoFilePath ?: session.videoFilePath
            )
        } else null

        stateMachine.onOutcomeSelected(
            outcome = outcome,
            videoPath = videoFilePath,
            metaPath = metaFile?.absolutePath
        )
    }

    /**
     * Dismisses the camera verification and returns to IDLE.
     */
    fun dismiss() {
        stopCountdownTimer()
        stateMachine.dismiss()
    }

    private fun startCountdownTimer(session: CameraVerificationSession) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = session.countdownRemainingSeconds
            while (isActive && remaining > 0) {
                delay(1000)
                remaining--
                stateMachine.onCountdownTick(remaining)
                RecordingStateHolder.updateCameraCountdown(remaining)
            }
            if (isActive && stateMachine.currentState == CameraState.VERIFYING) {
                // Auto-resolve on timeout (default to UNCERTAIN without performing emergency call)
                DetectionLogger.w(TAG, "Camera verification countdown timed out. Resolving to UNCERTAIN.")
                resolveOutcome(CameraVerificationOutcome.UNCERTAIN)
            }
        }
    }

    private fun stopCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = null
    }

    fun reset() {
        stopCountdownTimer()
        stateMachine.reset()
        RecordingStateHolder.dismissCameraVerification()
    }
}
