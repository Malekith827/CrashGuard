package com.example.crashguard.camera

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.vision.VisionClassificationResult

enum class CameraVerificationOutcome {
    PENDING,
    CRASH_CONFIRMED,
    FALSE_ALARM,
    UNCERTAIN
}

enum class CameraState {
    IDLE,
    VERIFYING,
    FINAL_RESULT
}

data class CameraVerificationSession(
    val sessionId: String,
    val crashAlert: CrashAlertState,
    val startedAtMs: Long = System.currentTimeMillis(),
    val outcome: CameraVerificationOutcome = CameraVerificationOutcome.PENDING,
    val countdownRemainingSeconds: Int = 10,
    val videoFilePath: String? = null,
    val metadataFilePath: String? = null,
    val isRecordingActive: Boolean = false,
    val isAnalyzingVision: Boolean = false,
    val visionResult: VisionClassificationResult? = null,
    val errorMessage: String? = null
)
