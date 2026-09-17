package com.example.crashguard.camera

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.DetectionLogger
import com.example.crashguard.detection.RecoveryState
import java.util.Locale

/**
 * Deterministic State Machine for Camera Verification downstream of IMU Persistent Abnormal events.
 */
class CameraVerificationStateMachine {

    companion object {
        private const val TAG = "CrashGuardCamera"
        const val DEFAULT_COUNTDOWN_SECONDS = 10
    }

    var currentState: CameraState = CameraState.IDLE
        private set

    var currentSession: CameraVerificationSession? = null
        private set

    var onSessionStarted: ((CameraVerificationSession) -> Unit)? = null
    var onOutcomeResolved: ((CameraVerificationSession) -> Unit)? = null
    var onSessionDismissed: (() -> Unit)? = null

    /**
     * Triggered by IMU recovery layer result.
     * Starts camera verification ONLY when state is PERSISTENT_ABNORMAL.
     * Prevents duplicate launches if already in CAMERA_VERIFICATION.
     *
     * @return true if a new verification session was started, false otherwise.
     */
    fun onImuRecoveryState(recoveryState: RecoveryState, alert: CrashAlertState): Boolean {
        if (recoveryState != RecoveryState.PERSISTENT_ABNORMAL) {
            DetectionLogger.d(TAG, "IMU recovery state ${recoveryState.name} does not trigger camera verification.")
            return false
        }

        if (currentState != CameraState.IDLE) {
            DetectionLogger.w(TAG, "Duplicate PERSISTENT_ABNORMAL ignored: camera verification already active in state $currentState.")
            return false
        }

        val sessionId = "crash_session_${System.currentTimeMillis()}"
        val session = CameraVerificationSession(
            sessionId = sessionId,
            crashAlert = alert,
            countdownRemainingSeconds = DEFAULT_COUNTDOWN_SECONDS
        )

        currentState = CameraState.VERIFYING
        currentSession = session

        DetectionLogger.i(
            TAG,
            String.format(
                Locale.US,
                "Camera verification started for IMU candidate t=%d (S1=%.4f, S2=%.4f, Shock=%.4f, Delay=%.4fs, Observed=%.2fs)",
                alert.timestampNs,
                alert.stage1Score,
                alert.stage2Score,
                alert.shockScore,
                alert.temporalDelayS,
                alert.recoveryObservedS
            )
        )

        onSessionStarted?.invoke(session)
        return true
    }

    /**
     * Called when a manual user outcome or countdown timeout resolves the verification.
     */
    fun onOutcomeSelected(outcome: CameraVerificationOutcome, videoPath: String? = null, metaPath: String? = null) {
        val session = currentSession ?: return
        if (currentState != CameraState.VERIFYING) {
            DetectionLogger.w(TAG, "Cannot set outcome $outcome when in state $currentState")
            return
        }

        val resolvedSession = session.copy(
            outcome = outcome,
            videoFilePath = videoPath ?: session.videoFilePath,
            metadataFilePath = metaPath ?: session.metadataFilePath
        )

        currentState = CameraState.FINAL_RESULT
        currentSession = resolvedSession

        DetectionLogger.i(
            TAG,
            String.format(
                Locale.US,
                "Camera verification complete for candidate t=%d -> Outcome: %s (Video: %s, Meta: %s)",
                session.crashAlert.timestampNs,
                outcome.name,
                resolvedSession.videoFilePath ?: "none",
                resolvedSession.metadataFilePath ?: "none"
            )
        )

        onOutcomeResolved?.invoke(resolvedSession)
    }

    /**
     * Updates countdown tick.
     */
    fun onCountdownTick(remainingSeconds: Int) {
        val session = currentSession ?: return
        if (currentState == CameraState.VERIFYING) {
            currentSession = session.copy(countdownRemainingSeconds = remainingSeconds)
        }
    }

    /**
     * Dismisses the session and safely returns state to IDLE.
     */
    fun dismiss() {
        currentState = CameraState.IDLE
        currentSession = null
        DetectionLogger.d(TAG, "Camera verification session dismissed. State returned to IDLE.")
        onSessionDismissed?.invoke()
    }

    fun reset() {
        currentState = CameraState.IDLE
        currentSession = null
    }
}
