package com.example.crashguard.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.crashguard.camera.CameraRecorder
import com.example.crashguard.camera.CameraVerificationManager
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.data.IncidentHistoryItem
import com.example.crashguard.data.MainUiState
import com.example.crashguard.data.RecordingStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.crashguard.sms.EmergencyContactRepository
import com.example.crashguard.sms.EmergencySmsManager
import com.example.crashguard.sms.PhoneNumberValidator
import com.example.crashguard.sms.SharedPrefsEmergencyContactRepository
import com.example.crashguard.sms.SmsSendResult

class MainScreenViewModel : ViewModel() {

    companion object {
        const val TAB_MONITOR = 0
        const val TAB_DIAGNOSTICS = 1
        const val TAB_HISTORY = 2
        const val TAB_SETTINGS = 3
    }

    val uiState: StateFlow<MainUiState> = RecordingStateHolder.uiState

    private val _selectedTab = MutableStateFlow(TAB_MONITOR)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Emergency Contact Settings State
    private val _emergencyContact = MutableStateFlow<String?>(null)
    val emergencyContact: StateFlow<String?> = _emergencyContact.asStateFlow()

    private val _emergencyContactInput = MutableStateFlow("")
    val emergencyContactInput: StateFlow<String> = _emergencyContactInput.asStateFlow()

    private val _emergencyContactError = MutableStateFlow<String?>(null)
    val emergencyContactError: StateFlow<String?> = _emergencyContactError.asStateFlow()

    private val _emergencyContactSaveSuccess = MutableStateFlow(false)
    val emergencyContactSaveSuccess: StateFlow<Boolean> = _emergencyContactSaveSuccess.asStateFlow()

    private val _isSmsPermissionGranted = MutableStateFlow(false)
    val isSmsPermissionGranted: StateFlow<Boolean> = _isSmsPermissionGranted.asStateFlow()

    private val _testSmsStatus = MutableStateFlow<String?>(null)
    val testSmsStatus: StateFlow<String?> = _testSmsStatus.asStateFlow()

    val eventLabels = listOf(
        "POTHOLE",
        "SPEED_BREAKER",
        "HARD_BRAKE",
        "ACCELERATION",
        "ROUGH_ROAD",
        "OTHER"
    )

    val availableLabels = listOf(
        "NORMAL",
        "POTHOLE",
        "HARD_BRAKE",
        "SPEED_BREAKER",
        "ROUGH_ROAD",
        "ACCELERATION",
        "OTHER"
    )

    var startRecordingTrigger: ((label: String, notes: String) -> Unit)? = null
    var stopRecordingTrigger: (() -> Unit)? = null
    var markEventTrigger: ((label: String, notes: String) -> Unit)? = null

    fun selectTab(tabIndex: Int) {
        _selectedTab.value = tabIndex
    }

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

    fun resolveCameraOutcome(outcome: CameraVerificationOutcome, videoFilePath: String? = null) {
        CameraVerificationManager.resolveOutcome(outcome, videoFilePath)
    }

    fun dismissCameraVerification() {
        CameraVerificationManager.dismiss()
    }

    fun loadIncidentHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = CameraRecorder.getRecordingsDirectory(context)
                val metaFiles = dir.listFiles { file ->
                    file.isFile && file.name.startsWith("crash_") && file.name.endsWith("_meta.json")
                } ?: emptyArray()

                val items = ArrayList<IncidentHistoryItem>()
                for (file in metaFiles.sortedByDescending { it.lastModified() }) {
                    try {
                        val text = file.readText()
                        val json = JSONObject(text)
                        val sessionId = json.optString("session_id", file.name)
                        val timeIso = json.optString("created_at_iso", "")
                        val outcome = json.optString("outcome", "PENDING")
                        val s1 = json.optDouble("stage1_score", 0.0).toFloat()
                        val s2 = json.optDouble("stage2_score", 0.0).toFloat()
                        val recovery = json.optString("recovery_state", "")
                        val videoPath = json.optString("video_file_path", "").takeIf { it.isNotBlank() }

                        var visionDecision: String? = null
                        var visionConfidence: Float? = null
                        var visionReason: String? = null

                        if (json.has("vision")) {
                            val vObj = json.getJSONObject("vision")
                            visionDecision = vObj.optString("decision", "")
                            visionConfidence = vObj.optDouble("confidence", 0.0).toFloat()
                            visionReason = vObj.optString("reason", "")
                        }

                        items.add(
                            IncidentHistoryItem(
                                sessionId = sessionId,
                                timestampIso = timeIso,
                                outcome = outcome,
                                stage1Score = s1,
                                stage2Score = s2,
                                recoveryState = recovery,
                                visionDecision = visionDecision,
                                visionConfidence = visionConfidence,
                                visionReason = visionReason,
                                videoFilePath = videoPath,
                                metaFilePath = file.absolutePath
                            )
                        )
                    } catch (e: Exception) {
                        // Skip corrupted json file
                    }
                }

                withContext(Dispatchers.Main) {
                    RecordingStateHolder.updateHistory(items)
                }
            } catch (e: Exception) {
                // Ignore directory read errors
            }
        }
    }

    fun loadEmergencyContact(
        context: Context,
        repository: EmergencyContactRepository = SharedPrefsEmergencyContactRepository(context)
    ) {
        val saved = repository.getEmergencyContact()
        _emergencyContact.value = saved
        if (saved != null && _emergencyContactInput.value.isBlank()) {
            _emergencyContactInput.value = saved
        }
    }

    fun updateEmergencyContactInput(input: String) {
        _emergencyContactInput.value = input
        _emergencyContactError.value = null
        _emergencyContactSaveSuccess.value = false
    }

    fun saveEmergencyContact(
        context: Context,
        repository: EmergencyContactRepository = SharedPrefsEmergencyContactRepository(context)
    ): Boolean {
        val input = _emergencyContactInput.value.trim()
        if (input.isBlank()) {
            _emergencyContactError.value = "Phone number cannot be empty."
            _emergencyContactSaveSuccess.value = false
            return false
        }
        if (!PhoneNumberValidator.isValid(input)) {
            _emergencyContactError.value = "Invalid phone number. Include country code (e.g., +1234567890 or +919876543210) with 7-15 digits."
            _emergencyContactSaveSuccess.value = false
            return false
        }
        val normalized = PhoneNumberValidator.normalize(input)
        val success = repository.saveEmergencyContact(normalized)
        if (success) {
            _emergencyContact.value = normalized
            _emergencyContactInput.value = normalized
            _emergencyContactError.value = null
            _emergencyContactSaveSuccess.value = true
        } else {
            _emergencyContactError.value = "Failed to save contact. Please try again."
            _emergencyContactSaveSuccess.value = false
        }
        return success
    }

    fun clearEmergencyContact(
        context: Context,
        repository: EmergencyContactRepository = SharedPrefsEmergencyContactRepository(context)
    ): Boolean {
        val success = repository.clearEmergencyContact()
        if (success) {
            _emergencyContact.value = null
            _emergencyContactInput.value = ""
            _emergencyContactError.value = null
            _emergencyContactSaveSuccess.value = false
        }
        return success
    }

    fun setSmsPermissionGranted(granted: Boolean) {
        _isSmsPermissionGranted.value = granted
    }

    fun sendTestSms(
        context: Context,
        repository: EmergencyContactRepository = SharedPrefsEmergencyContactRepository(context)
    ) {
        val targetNumber = repository.getEmergencyContact()
        if (targetNumber == null) {
            _testSmsStatus.value = "Cannot send test: No emergency contact configured."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val message = "CrashGuard Test Alert: This is a verified test of the emergency SMS notification system."
            try {
                val sender = com.example.crashguard.sms.AndroidSmsSender(context)
                sender.sendSms(destinationAddress = targetNumber, message = message)
                withContext(Dispatchers.Main) {
                    _testSmsStatus.value = "Test SMS successfully dispatched to ${PhoneNumberValidator.mask(targetNumber)}"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _testSmsStatus.value = "Test SMS failed: ${t.message}"
                }
            }
        }
    }
}
