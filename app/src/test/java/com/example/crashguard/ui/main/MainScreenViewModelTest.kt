package com.example.crashguard.ui.main

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.RecordingStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenViewModelTest {

    @Test
    fun testViewModelStateReflectsRecordingStateHolder() {
        val viewModel = MainScreenViewModel()
        assertNotNull(viewModel.uiState.value)
        assertEquals("NORMAL", viewModel.uiState.value.recording.selectedLabel)

        viewModel.updateLabel("POTHOLE")
        assertEquals("POTHOLE", viewModel.uiState.value.recording.selectedLabel)

        viewModel.updateNotes("Test ride")
        assertEquals("Test ride", viewModel.uiState.value.recording.notes)

        // Test crash alert reporting and dismissal
        val alert = CrashAlertState(
            timestampNs = 123456789L,
            stage1Score = 0.62f,
            stage2Score = 0.58f,
            isConfirmed = true,
            details = "Test alert"
        )
        RecordingStateHolder.reportCrashAlert(alert)
        assertEquals(alert, viewModel.uiState.value.recording.crashAlert)

        viewModel.dismissCrashAlert()
        assertNull(viewModel.uiState.value.recording.crashAlert)
    }
}
