package com.example.crashguard.vision

enum class VisionDecision {
    CRASH,
    NO_CRASH,
    UNCERTAIN
}

data class VisionClassificationResult(
    val decision: VisionDecision,
    val confidence: Float,
    val reason: String,
    val latencyMs: Long = 0L,
    val rawJson: String? = null
)
