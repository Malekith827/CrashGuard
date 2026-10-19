package com.example.crashguard.data

import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SensorState(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val isAvailable: Boolean = false,
    val rateHz: Double = 0.0
) {
    val magnitude: Float
        get() = kotlin.math.sqrt(x * x + y * y + z * z)
}

data class RecordingState(
    val isRecording: Boolean = false,
    val durationSeconds: Long = 0,
    val samplesCount: Int = 0,
    val eventMarkersCount: Int = 0,
    val lastMarkedEvent: String? = null,
    val lastMarkedTimestampNs: Long? = null,
    val selectedLabel: String = "NORMAL",
    val notes: String = "",
    val sessionId: String? = null,
    val filePath: String? = null,
    val eventsFilePath: String? = null,
    val crashAlert: CrashAlertState? = null,
    val cameraVerification: CameraVerificationSession? = null
)

data class PipelineDiagnosticsState(
    val pipelineState: String = "IDLE",
    // Stage 1
    val s1AnomalyScore: Float = 0f,
    val s1Threshold: Float = 0.571849f,
    val isS1Triggered: Boolean = false,
    val lastS1TriggerTimestampNs: Long = 0L,
    // Stage 2
    val s2ContinuousScore: Float = 0f,
    val s2Threshold: Float = 0.50f,
    val s2ShockScore: Float = 0f,
    val s2RotationScore: Float = 0f,
    val s2AccelDampingScore: Float = 0f,
    val s2GyroDampingScore: Float = 0f,
    val s2TemporalScore: Float = 0f,
    val s2TemporalDelayS: Float = 0f,
    val s2DetectionLatencyS: Float = 0f,
    val isS2Confirmed: Boolean = false,
    // Recovery
    val recoveryState: String = "IDLE",
    val recoveryObservedS: Float = 0f,
    val recoveryHorizonS: Float = 3.0f,
    val recoveryChunksObserved: Int = 0,
    val recoveryRefAccelStd: Float = 0f,
    val recoveryRefGyroMean: Float = 0f,
    // Camera & Vision
    val cameraState: String = "IDLE",
    val isCameraRecording: Boolean = false,
    val extractedFramesCount: Int = 0,
    val visionModel: String = "gemini-3-flash-preview",
    val visionState: String = "IDLE",
    val visionDecision: String? = null,
    val visionConfidence: Float? = null,
    val visionReason: String? = null,
    val visionLatencyMs: Long? = null
)

data class IncidentHistoryItem(
    val sessionId: String = "",
    val timestampIso: String = "",
    val outcome: String = "PENDING",
    val stage1Score: Float = 0f,
    val stage2Score: Float = 0f,
    val recoveryState: String = "",
    val visionDecision: String? = null,
    val visionConfidence: Float? = null,
    val visionReason: String? = null,
    val videoFilePath: String? = null,
    val metaFilePath: String? = null
)

data class MainUiState(
    val accel: SensorState = SensorState(),
    val gyro: SensorState = SensorState(),
    val recording: RecordingState = RecordingState(),
    val diagnostics: PipelineDiagnosticsState = PipelineDiagnosticsState(),
    val history: List<IncidentHistoryItem> = emptyList()
)

object RecordingStateHolder {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun updateSensorsAvailable(accelAvailable: Boolean, gyroAvailable: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                accel = currentState.accel.copy(isAvailable = accelAvailable),
                gyro = currentState.gyro.copy(isAvailable = gyroAvailable)
            )
        }
    }

    fun updateAccelerometer(x: Float, y: Float, z: Float, rateHz: Double) {
        _uiState.update { currentState ->
            currentState.copy(
                accel = currentState.accel.copy(x = x, y = y, z = z, rateHz = rateHz)
            )
        }
    }

    fun updateGyroscope(x: Float, y: Float, z: Float, rateHz: Double) {
        _uiState.update { currentState ->
            currentState.copy(
                gyro = currentState.gyro.copy(x = x, y = y, z = z, rateHz = rateHz)
            )
        }
    }

    fun updateLabel(label: String) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(selectedLabel = label)
            )
        }
    }

    fun updateNotes(notes: String) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(notes = notes)
            )
        }
    }

    fun startRecordingState(sessionId: String, filePath: String, eventsFilePath: String) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    isRecording = true,
                    durationSeconds = 0,
                    samplesCount = 0,
                    eventMarkersCount = 0,
                    lastMarkedEvent = null,
                    lastMarkedTimestampNs = null,
                    sessionId = sessionId,
                    filePath = filePath,
                    eventsFilePath = eventsFilePath,
                    crashAlert = null,
                    cameraVerification = null
                )
            )
        }
    }

    fun updateRecordingStats(durationSeconds: Long, samplesCount: Int, eventMarkersCount: Int) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    durationSeconds = durationSeconds,
                    samplesCount = samplesCount,
                    eventMarkersCount = eventMarkersCount
                )
            )
        }
    }

    fun markEvent(label: String, timestampNs: Long, totalEventsCount: Int) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    eventMarkersCount = totalEventsCount,
                    lastMarkedEvent = label,
                    lastMarkedTimestampNs = timestampNs
                )
            )
        }
    }

    fun clearLastMarkedEvent() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    lastMarkedEvent = null,
                    lastMarkedTimestampNs = null
                )
            )
        }
    }

    fun reportCrashAlert(alert: CrashAlertState) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    crashAlert = alert
                )
            )
        }
    }

    fun dismissCrashAlert() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    crashAlert = null
                )
            )
        }
    }

    fun startCameraVerification(session: CameraVerificationSession) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = session
                ),
                diagnostics = currentState.diagnostics.copy(
                    cameraState = "VERIFYING",
                    isCameraRecording = session.isRecordingActive,
                    visionState = if (session.isAnalyzingVision) "ANALYZING" else if (session.visionResult != null) "COMPLETED" else "IDLE",
                    visionDecision = session.visionResult?.decision?.name,
                    visionConfidence = session.visionResult?.confidence,
                    visionReason = session.visionResult?.reason,
                    visionLatencyMs = session.visionResult?.latencyMs
                )
            )
        }
    }

    fun setVisionAnalyzing(isAnalyzing: Boolean, videoFilePath: String?) {
        _uiState.update { currentState ->
            val existing = currentState.recording.cameraVerification ?: return@update currentState
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = existing.copy(
                        isAnalyzingVision = isAnalyzing,
                        videoFilePath = videoFilePath ?: existing.videoFilePath
                    )
                ),
                diagnostics = currentState.diagnostics.copy(
                    visionState = if (isAnalyzing) "ANALYZING" else currentState.diagnostics.visionState
                )
            )
        }
    }

    fun updateCameraCountdown(remainingSeconds: Int) {
        _uiState.update { currentState ->
            val existing = currentState.recording.cameraVerification ?: return@update currentState
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = existing.copy(countdownRemainingSeconds = remainingSeconds)
                )
            )
        }
    }

    fun setCameraOutcome(session: CameraVerificationSession) {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = session
                ),
                diagnostics = currentState.diagnostics.copy(
                    cameraState = "FINAL_RESULT",
                    isCameraRecording = false,
                    visionState = if (session.visionResult != null) "COMPLETED" else "IDLE",
                    visionDecision = session.visionResult?.decision?.name,
                    visionConfidence = session.visionResult?.confidence,
                    visionReason = session.visionResult?.reason,
                    visionLatencyMs = session.visionResult?.latencyMs
                )
            )
        }
    }

    fun setCameraOutcome(outcome: CameraVerificationOutcome) {
        _uiState.update { currentState ->
            val existing = currentState.recording.cameraVerification ?: return@update currentState
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = existing.copy(outcome = outcome)
                ),
                diagnostics = currentState.diagnostics.copy(
                    cameraState = "FINAL_RESULT"
                )
            )
        }
    }

    fun dismissCameraVerification() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = null
                ),
                diagnostics = currentState.diagnostics.copy(
                    cameraState = "IDLE",
                    isCameraRecording = false,
                    visionState = "IDLE"
                )
            )
        }
    }

    fun stopRecordingState() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    isRecording = false
                ),
                diagnostics = currentState.diagnostics.copy(
                    pipelineState = "IDLE"
                )
            )
        }
    }

    fun updatePipelineDiagnostics(transform: (PipelineDiagnosticsState) -> PipelineDiagnosticsState) {
        _uiState.update { currentState ->
            currentState.copy(
                diagnostics = transform(currentState.diagnostics)
            )
        }
    }

    fun setPipelineDiagnostics(diagnostics: PipelineDiagnosticsState) {
        _uiState.update { currentState ->
            currentState.copy(diagnostics = diagnostics)
        }
    }

    fun updateHistory(history: List<IncidentHistoryItem>) {
        _uiState.update { currentState ->
            currentState.copy(history = history)
        }
    }
}
