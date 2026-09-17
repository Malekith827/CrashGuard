package com.example.crashguard.data

/**
 * State representing a detected crash event from the hierarchical pipeline.
 *
 * @property timestampNs Monotonic sensor timestamp (nanoseconds) when the trigger occurred.
 * @property stage1Score The Stage 1 anomaly score in [0.0, 1.0] (threshold >= 0.571849).
 * @property stage2Score The Stage 2 causal verification score in [0.0, 1.0] (threshold >= 0.50).
 * @property shockScore Impact shock evidence score in [0.0, 1.0].
 * @property temporalScore Temporal impact -> rotation sequencing score in [0.0, 1.0].
 * @property temporalDelayS Measured time delta between impact peak and rotation peak (seconds).
 * @property recoveryState Post-event motion recovery classification ("PERSISTENT_ABNORMAL" or "RECOVERED").
 * @property recoveryObservedS Total duration of post-trigger observation (seconds).
 * @property recoveryHorizonS Full observation horizon (seconds).
 * @property isConfirmed True if all criteria were satisfied.
 * @property details Contextual string summarizing detection latency and sub-scores.
 */
data class CrashAlertState(
    val timestampNs: Long = 0L,
    val stage1Score: Float = 0f,
    val stage2Score: Float = 0f,
    val shockScore: Float = 0f,
    val temporalScore: Float = 0f,
    val temporalDelayS: Float = 0f,
    val recoveryState: String = "PERSISTENT_ABNORMAL",
    val recoveryObservedS: Float = 0f,
    val recoveryHorizonS: Float = 3.0f,
    val isConfirmed: Boolean = true,
    val details: String = ""
)
