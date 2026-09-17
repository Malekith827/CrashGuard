package com.example.crashguard.ui.main

import androidx.lifecycle.ViewModel
import com.example.crashguard.data.MainUiState
import com.example.crashguard.data.RecordingStateHolder
import kotlinx.coroutines.flow.StateFlow

class MainScreenViewModel : ViewModel() {

    // Expose the thread-safe singleton state flow directly to Compose
    val uiState: StateFlow<MainUiState> = RecordingStateHolder.uiState

    // Allowed event categories for marking while active recording
    val eventLabels = listOf(
        "POTHOLE",
        "SPEED_BREAKER",
        "HARD_BRAKE",
        "ACCELERATION",
        "ROUGH_ROAD",
        "OTHER"
    )

    // Allowed session setup labels
    val availableLabels = listOf(
        "NORMAL",
        "POTHOLE",
        "HARD_BRAKE",
        "SPEED_BREAKER",
        "ROUGH_ROAD",
        "ACCELERATION",
        "OTHER"
    )

    // Callbacks to request foreground service actions in MainActivity
    var startRecordingTrigger: ((label: String, notes: String) -> Unit)? = null
    var stopRecordingTrigger: (() -> Unit)? = null
    var markEventTrigger: ((label: String, notes: String) -> Unit)? = null

    fun updateLabel(label: String) {
        if (label in availableLabels) {
            RecordingStateHolder.updateLabel(label)
        }
    }

    fun updateNotes(notes: String) {
        RecordingStateHolder.updateNotes(notes)
    }

    fun startRecording() {
        val label = uiState.value.recording.selectedLabel
        val notes = uiState.value.recording.notes
        startRecordingTrigger?.invoke(label, notes)
    }

    fun stopRecording() {
        stopRecordingTrigger?.invoke()
    }

    fun markEvent(label: String, notes: String = "") {
        markEventTrigger?.invoke(label, notes)
    }

    fun clearLastMarkedEvent() {
        RecordingStateHolder.clearLastMarkedEvent()
    }

    fun dismissCrashAlert() {
        RecordingStateHolder.dismissCrashAlert()
    }
}
