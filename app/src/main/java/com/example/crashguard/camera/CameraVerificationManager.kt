package com.example.crashguard.camera

import android.content.Context
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.RecordingStateHolder
import com.example.crashguard.detection.DetectionLogger
import com.example.crashguard.detection.RecoveryState
import com.example.crashguard.vision.VisionClassificationResult
import com.example.crashguard.vision.VisionDecision
import com.example.crashguard.vision.VisionVerificationManager
import com.example.crashguard.notification.CrashNotificationManager
import com.example.crashguard.sms.EmergencySmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * High-level coordinator managing Camera Verification and Vision LLM inference downstream of IMU.
 */
object CameraVerificationManager {

    private const val TAG = "CrashGuardCamera"
    val stateMachine = CameraVerificationStateMachine()
    val visionManager = VisionVerificationManager()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var countdownJob: Job? = null
    private var activeContext: Context? = null

    init {
        stateMachine.onSessionStarted = { session ->
            RecordingStateHolder.startCameraVerification(session)
            startCountdownTimer(session)
        }

        stateMachine.onOutcomeResolved = { session ->
            RecordingStateHolder.setCameraOutcome(session)
            activeContext?.let { ctx ->
                CrashNotificationManager.showCrashConfirmedNotification(ctx, session)
                EmergencySmsManager.sendCrashAlertSms(ctx, session)
            }
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
        val isStarted = stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)
        if (isStarted) {
            DetectionLogger.i(TAG, "Launched camera verification for candidate at t=${alert.timestampNs}")
        }
    }

    /**
     * Called when camera recording finishes to start Vision LLM analysis.
     */
    fun onVideoRecorded(videoFile: File) {
        val session = stateMachine.currentSession ?: return
        val ctx = activeContext ?: return
        if (stateMachine.currentState != CameraState.VERIFYING) {
            DetectionLogger.w(TAG, "Video recorded but state machine is in state ${stateMachine.currentState}. Ignoring.")
            return
        }

        DetectionLogger.i(TAG, "Video captured: ${videoFile.name}. Triggering Vision LLM verification.")
        stateMachine.onVisionAnalysisStarted(videoFile.absolutePath)
        RecordingStateHolder.setVisionAnalyzing(isAnalyzing = true, videoFilePath = videoFile.absolutePath)

        visionManager.analyzeRecordingAsync(
            context = ctx,
            session = session,
            videoFile = videoFile,
            onComplete = { visionResult ->
                DetectionLogger.i(TAG, "Vision LLM completed: ${visionResult.decision} (${visionResult.confidence * 100}%)")
                // Only resolve if session is still verifying (user has not manually overridden)
                if (stateMachine.currentState == CameraState.VERIFYING) {
                    val outcome = when (visionResult.decision) {
                        VisionDecision.CRASH -> CameraVerificationOutcome.CRASH_CONFIRMED
                        VisionDecision.NO_CRASH -> CameraVerificationOutcome.FALSE_ALARM
                        VisionDecision.UNCERTAIN -> CameraVerificationOutcome.UNCERTAIN
                    }
                    resolveOutcome(
                        outcome = outcome,
                        videoFilePath = videoFile.absolutePath,
                        visionResult = visionResult
                    )
                } else {
                    DetectionLogger.i(TAG, "Vision LLM finished but session was already resolved to ${stateMachine.currentState}. Not overwriting.")
                }
            }
        )
    }

    /**
     * Resolves the camera verification outcome (Vision LLM, manual user action, or countdown timeout).
     */
    fun resolveOutcome(
        outcome: CameraVerificationOutcome,
        videoFilePath: String? = null,
        visionResult: VisionClassificationResult? = null
    ) {
        if (stateMachine.currentState != CameraState.VERIFYING) {
            DetectionLogger.w(TAG, "resolveOutcome called with $outcome but state machine is already in ${stateMachine.currentState}. Ignoring.")
            return
        }

        stopCountdownTimer()
        val session = stateMachine.currentSession
        if (session != null && activeContext != null) {
            val ctx = activeContext!!
            val sId = session.sessionId
            val alert = session.crashAlert
            val vPath = videoFilePath ?: session.videoFilePath
            val vRes = visionResult ?: session.visionResult
            scope.launch(Dispatchers.IO) {
                CameraRecorder.writeEventMetadata(
                    context = ctx,
                    sessionId = sId,
                    alert = alert,
                    outcome = outcome,
                    videoFilePath = vPath,
                    visionResult = vRes
                )
            }
        }

        stateMachine.onOutcomeSelected(
            outcome = outcome,
            videoPath = videoFilePath,
            metaPath = null,
            visionResult = visionResult
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
                val current = stateMachine.currentSession
                // If Vision analysis is in-flight, DO NOT auto-resolve or dismiss!
                // The session must remain active until Vision finishes or user manually overrides.
                if (current?.isAnalyzingVision == true) {
                    DetectionLogger.i(TAG, "Countdown reached 0 but Vision LLM is actively analyzing. Keeping verification session alive.")
                } else {
                    val visionResult = current?.visionResult
                    val defaultOutcome = when (visionResult?.decision) {
                        VisionDecision.CRASH -> CameraVerificationOutcome.CRASH_CONFIRMED
                        VisionDecision.NO_CRASH -> CameraVerificationOutcome.FALSE_ALARM
                        else -> CameraVerificationOutcome.UNCERTAIN
                    }
                    DetectionLogger.w(TAG, "Camera verification countdown timed out. Resolving to $defaultOutcome.")
                    resolveOutcome(defaultOutcome)
                }
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
        CrashNotificationManager.reset()
        EmergencySmsManager.reset()
    }
}
