package com.example.crashguard.data

/**
 * State representing a detected crash event from the two-stage hierarchical pipeline.
 *
 * @property timestampNs Monotonic sensor timestamp (nanoseconds) when the trigger occurred.
 * @property stage1Score The Stage 1 anomaly score in [0.0, 1.0] (threshold >= 0.571849).
 * @property stage2Score The Stage 2 causal verification score in [0.0, 1.0] (threshold >= 0.50).
 * @property isConfirmed True if both Stage 1 and Stage 2 criteria were satisfied.
 * @property details Contextual string summarizing the detection latency and sub-scores.
 */
data class CrashAlertState(
    val timestampNs: Long = 0L,
    val stage1Score: Float = 0f,
    val stage2Score: Float = 0f,
    val isConfirmed: Boolean = false,
    val details: String = ""
)
