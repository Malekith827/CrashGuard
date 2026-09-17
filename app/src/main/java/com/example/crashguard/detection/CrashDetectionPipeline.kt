package com.example.crashguard.detection

import android.content.Context
import android.util.Log
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.SensorSample
import com.example.crashguard.service.SensorRingBuffer
import java.io.Closeable
import java.util.Locale

/**
 * High-level coordinator for the Two-Stage Real-Time Crash Detection Pipeline:
 *
 * Stage 1: Phone-Native Isolation Forest Anomaly Trigger (OOF p95 >= 0.571849)
 * Stage 2: Causal Crash-Signature Verification (Continuous score >= 0.50 after 1.2s post-trigger)
 */
class CrashDetectionPipeline(
    private val stage1Model: IsolationForestModel,
    private val stage2Verifier: Stage2Verifier = Stage2Verifier(),
    val onCrashDetected: ((CrashAlertState) -> Unit)? = null
) : Closeable {

    companion object {
        private const val TAG = "CrashGuardPipeline"

        // Locked ML Thresholds
        const val STAGE1_P95_THRESHOLD = 0.571849f
        const val STAGE2_CRASH_THRESHOLD = 0.50f

        // Timing constants (nanoseconds)
        private const val WINDOW_DURATION_NS = 3_000_000_000L    // 3.0 seconds
        private const val EVAL_STRIDE_NS = 500_000_000L          // 0.5 second evaluation stride
        private const val POST_TRIGGER_OBSERVATION_NS = 1_200_000_000L // 1.2 seconds causal observation
        private const val COOLDOWN_DURATION_NS = 5_000_000_000L  // 5.0 seconds alert cooldown
    }

    enum class PipelineState {
        IDLE,
        COLLECTING_POST_TRIGGER,
        COOLDOWN
    }

    val ringBuffer = SensorRingBuffer(capacity = 350)

    private var currentState = PipelineState.IDLE
    private var lastEvalTimestampNs = 0L

    // Pending trigger data
    private var triggerTimestampNs = 0L
    private var triggerStage1Score = 0f
    private var triggerWindowSnapshot: List<SensorSample> = emptyList()
    private var postTriggerSamples = ArrayList<SensorSample>()

    // Cooldown tracking
    private var cooldownUntilTimestampNs = 0L

    /**
     * Primary entry point called for each synchronized [SensorSample].
     */
    fun onSample(sample: SensorSample) {
        val currentTs = sample.timestampNs
        ringBuffer.add(sample)

        when (currentState) {
            PipelineState.COOLDOWN -> {
                if (currentTs >= cooldownUntilTimestampNs) {
                    currentState = PipelineState.IDLE
                    DetectionLogger.d(TAG, "Cooldown period elapsed. Pipeline returned to IDLE.")
                }
            }

            PipelineState.COLLECTING_POST_TRIGGER -> {
                if (currentTs > triggerTimestampNs) {
                    postTriggerSamples.add(sample)
                }

                val postElapsedNs = currentTs - triggerTimestampNs
                if (postElapsedNs >= POST_TRIGGER_OBSERVATION_NS) {
                    evaluateStage2Confirmation(currentTs)
                }
            }

            PipelineState.IDLE -> {
                // Check if buffer contains at least 3.0 seconds of data and stride interval elapsed
                val bufferDurationNs = ringBuffer.getDurationNs()
                if (bufferDurationNs >= WINDOW_DURATION_NS && (currentTs - lastEvalTimestampNs) >= EVAL_STRIDE_NS) {
                    lastEvalTimestampNs = currentTs
                    evaluateStage1Window(currentTs)
                }
            }
        }
    }

    /**
     * Evaluates Stage 1 Isolation Forest on a 3.0-second sliding window.
     */
    private fun evaluateStage1Window(currentTs: Long) {
        val window = ringBuffer.getSnapshotSpan(WINDOW_DURATION_NS)
        if (window.size < 30) return

        val features = Stage1FeatureExtractor.extractFeatures(window)
        val s1Score = stage1Model.scoreWindow(features)

        if (s1Score >= STAGE1_P95_THRESHOLD) {
            // Stage 1 Triggered!
            triggerTimestampNs = currentTs
            triggerStage1Score = s1Score
            triggerWindowSnapshot = window
            postTriggerSamples.clear()

            currentState = PipelineState.COLLECTING_POST_TRIGGER
            DetectionLogger.w(
                TAG,
                String.format(
                    Locale.US,
                    "[STAGE 1 TRIGGER] Anomaly Score = %.4f >= %.4f at timestamp %d. Starting 1.2s post-trigger observation.",
                    s1Score,
                    STAGE1_P95_THRESHOLD,
                    triggerTimestampNs
                )
            )
        }
    }

    /**
     * Evaluates Stage 2 after exactly 1.2 seconds of causal observation.
     */
    private fun evaluateStage2Confirmation(currentTs: Long) {
        // Assemble full contiguous sequence: trigger window + post-trigger observations
        val fullSequence = ArrayList<SensorSample>(triggerWindowSnapshot.size + postTriggerSamples.size)
        fullSequence.addAll(triggerWindowSnapshot)
        fullSequence.addAll(postTriggerSamples)

        val s2Result = stage2Verifier.evaluateWindow(
            samples = fullSequence,
            triggerTimestampNs = triggerTimestampNs,
            threshold = STAGE2_CRASH_THRESHOLD
        )

        DetectionLogger.i(
            TAG,
            String.format(
                Locale.US,
                "[STAGE 2 EVAL] Score = %.4f (Thresh = %.2f, Latency = %.2fs, Shock = %.2f, Rot = %.2f, ADamp = %.2f, GDamp = %.2f, Temporal = %.2f, Delay = %.2fs) -> Confirmed: %b",
                s2Result.continuousCrashScore,
                s2Result.crashThreshold,
                s2Result.detectionLatencyS,
                s2Result.scoreShock,
                s2Result.scoreRotation,
                s2Result.scoreAccelDamping,
                s2Result.scoreGyroDamping,
                s2Result.temporalImpactRotationScore,
                s2Result.temporalDelayS,
                s2Result.isCrashConfirmed
            )
        )

        if (s2Result.isCrashConfirmed) {
            val alert = CrashAlertState(
                timestampNs = triggerTimestampNs,
                stage1Score = triggerStage1Score,
                stage2Score = s2Result.continuousCrashScore,
                isConfirmed = true,
                details = String.format(
                    Locale.US,
                    "S1=%.3f, S2=%.3f, Latency=%.2fs",
                    triggerStage1Score,
                    s2Result.continuousCrashScore,
                    s2Result.detectionLatencyS
                )
            )

            // Notify listener (UI / notifications)
            onCrashDetected?.invoke(alert)

            // Enter 5.0s cooldown to suppress repeated cascade alarms for same physical crash
            currentState = PipelineState.COOLDOWN
            cooldownUntilTimestampNs = currentTs + COOLDOWN_DURATION_NS
        } else {
            // Stage 2 suppressed false alarm -> return directly to IDLE
            currentState = PipelineState.IDLE
        }

        // Clear temporary buffers
        postTriggerSamples.clear()
        triggerWindowSnapshot = emptyList()
    }

    fun reset() {
        currentState = PipelineState.IDLE
        ringBuffer.clear()
        postTriggerSamples.clear()
        triggerWindowSnapshot = emptyList()
        lastEvalTimestampNs = 0L
    }

    override fun close() {
        stage1Model.close()
    }
}
