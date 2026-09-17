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
)

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

data class MainUiState(
    val accel: SensorState = SensorState(),
    val gyro: SensorState = SensorState(),
    val recording: RecordingState = RecordingState()
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

    fun setCameraOutcome(outcome: CameraVerificationOutcome) {
        _uiState.update { currentState ->
            val existing = currentState.recording.cameraVerification ?: return@update currentState
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = existing.copy(outcome = outcome)
                )
            )
        }
    }

    fun dismissCameraVerification() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    cameraVerification = null
                )
            )
        }
    }

    fun stopRecordingState() {
        _uiState.update { currentState ->
            currentState.copy(
                recording = currentState.recording.copy(
                    isRecording = false
                )
            )
        }
    }
}
