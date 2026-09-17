package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * State of post-event riding recovery evaluation.
 */
enum class RecoveryState {
    OBSERVING,
    RECOVERED,
    PERSISTENT_ABNORMAL
}

/**
 * Configuration for the experimental Riding Recovery State Machine.
 * Directly mirrors `anomaly/stage2_verifier.py`.
 */
data class RecoveryConfig(
    val enabled: Boolean = true,
    val horizonS: Float = 3.0f,
    val chunkS: Float = 0.25f,
    val requiredConsecutiveChunks: Int = 2,
    val logRatioThreshold: Float = ln(2.0f), // ~0.6931472f
    val targetSamplingRateHz: Float = 53.0f,
    val preBaselineS: Float = 1.0f,
    val impactHalfWindowS: Float = 0.25f,
    val eps: Float = 1e-6f
)

/**
 * Result of riding recovery evaluation across available post-trigger observations.
 */
data class RidingContinuityResult(
    val state: RecoveryState,
    val horizonS: Float,
    val observedS: Float,
    val chunksObserved: Int,
    val requiredConsecutiveChunks: Int,
    val referenceAccelStd: Float,
    val referenceGyroMean: Float,
    val chunkDistances: List<Float>
)

/**
 * Experimental Causal Riding-Recovery State Machine.
 * Evaluates whether post-impact motion returns to the pre-impact riding state.
 */
class RidingRecoveryStateMachine(val config: RecoveryConfig = RecoveryConfig()) {

    /**
     * Evaluates causal post-trigger recovery from a list of [SensorSample]s.
     *
     * @param samples Chronological sequence containing pre-baseline, trigger, and post-observations.
     * @param triggerTimestampNs Exact timestamp of the Stage 1 anomaly trigger.
     */
    fun evaluateRecovery(
        samples: List<SensorSample>,
        triggerTimestampNs: Long
    ): RidingContinuityResult {
        val n = samples.size
        if (n < 10) {
            return RidingContinuityResult(
                state = RecoveryState.OBSERVING,
                horizonS = config.horizonS,
                observedS = 0f,
                chunksObserved = 0,
                requiredConsecutiveChunks = config.requiredConsecutiveChunks,
                referenceAccelStd = 0f,
                referenceGyroMean = 0f,
                chunkDistances = emptyList()
            )
        }

        val aMag = FloatArray(n)
        val gMag = FloatArray(n)
        var triggerIdx = -1
        var minDiff = Long.MAX_VALUE

        for (i in 0 until n) {
            val s = samples[i]
            aMag[i] = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            gMag[i] = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            val diff = abs(s.timestampNs - triggerTimestampNs)
            if (diff < minDiff) {
                minDiff = diff
                triggerIdx = i
            }
        }

        if (triggerIdx < 0 || triggerIdx >= n) {
            triggerIdx = n / 2
        }

        return evaluateRecovery(aMag, gMag, samples, triggerIdx)
    }

    /**
     * Internal evaluation using precomputed magnitude arrays and trigger index.
     */
    fun evaluateRecovery(
        aMag: FloatArray,
        gMag: FloatArray,
        samples: List<SensorSample>,
        triggerIdx: Int
    ): RidingContinuityResult {
        val n = samples.size

        // Calculate sample interval dt_s
        var dtS = 1.0f / config.targetSamplingRateHz
        if (n > 1) {
            val diffs = DoubleArray(n - 1)
            for (i in 0 until n - 1) {
                val diffNs = samples[i + 1].timestampNs - samples[i].timestampNs
                diffs[i] = diffNs / 1e9
            }
            Arrays.sort(diffs)
            val medianDt = diffs[diffs.size / 2]
            if (medianDt > 0.001) {
                dtS = medianDt.toFloat()
            }
        }
        val samplesPerSec = max(1, (1.0f / dtS).roundToInt())

        // Pre-impact riding reference buffer
        val halfImpact = max(2, (config.impactHalfWindowS * samplesPerSec).roundToInt())
        val referenceLen = max(5, (config.preBaselineS * samplesPerSec).roundToInt())
        val referenceEnd = max(0, triggerIdx - halfImpact)
        val referenceStart = max(0, referenceEnd - referenceLen)
        val refCount = referenceEnd - referenceStart

        if (refCount < 5) {
            return RidingContinuityResult(
                state = RecoveryState.OBSERVING,
                horizonS = config.horizonS,
                observedS = 0f,
                chunksObserved = 0,
                requiredConsecutiveChunks = config.requiredConsecutiveChunks,
                referenceAccelStd = 0f,
                referenceGyroMean = 0f,
                chunkDistances = emptyList()
            )
        }

        var refASum = 0.0
        var refGSum = 0.0
        for (i in referenceStart until referenceEnd) {
            refASum += aMag[i]
            refGSum += gMag[i]
        }
        val refAMean = refASum / refCount
        val refGMean = (refGSum / refCount).toFloat()

        var refAVar = 0.0
        for (i in referenceStart until referenceEnd) {
            val d = aMag[i] - refAMean
            refAVar += d * d
        }
        val refAStd = sqrt(refAVar / refCount).toFloat()

        // Post-impact observation chunks
        val postStart = min(n, triggerIdx + halfImpact + 1)
        val chunkLen = max(2, (config.chunkS * samplesPerSec).roundToInt())
        val horizonLen = max(chunkLen, (config.horizonS * samplesPerSec).roundToInt())
        val availableLen = max(0, min(n - postStart, horizonLen))

        val distances = ArrayList<Float>()
        var closeRun = 0

        for (offset in 0..(availableLen - chunkLen) step chunkLen) {
            var cASum = 0.0
            var cGSum = 0.0
            for (i in (postStart + offset) until (postStart + offset + chunkLen)) {
                cASum += aMag[i]
                cGSum += gMag[i]
            }
            val cAMean = cASum / chunkLen
            val cGMean = (cGSum / chunkLen).toFloat()

            var cAVar = 0.0
            for (i in (postStart + offset) until (postStart + offset + chunkLen)) {
                val d = aMag[i] - cAMean
                cAVar += d * d
            }
            val cAStd = sqrt(cAVar / chunkLen).toFloat()

            val logRatioA = abs(ln((cAStd + config.eps) / (refAStd + config.eps)))
            val logRatioG = abs(ln((cGMean + config.eps) / (refGMean + config.eps)))
            val distance = 0.5f * (logRatioA + logRatioG)
            distances.add(distance)

            if (distance <= config.logRatioThreshold) {
                closeRun++
                if (closeRun >= config.requiredConsecutiveChunks) {
                    val observedS = (offset + chunkLen) * dtS
                    return RidingContinuityResult(
                        state = RecoveryState.RECOVERED,
                        horizonS = config.horizonS,
                        observedS = observedS,
                        chunksObserved = distances.size,
                        requiredConsecutiveChunks = config.requiredConsecutiveChunks,
                        referenceAccelStd = refAStd,
                        referenceGyroMean = refGMean,
                        chunkDistances = distances
                    )
                }
            } else {
                closeRun = 0
            }
        }

        val observedS = availableLen * dtS
        val state = if (availableLen >= horizonLen) {
            RecoveryState.PERSISTENT_ABNORMAL
        } else {
            RecoveryState.OBSERVING
        }

        return RidingContinuityResult(
            state = state,
            horizonS = config.horizonS,
            observedS = observedS,
            chunksObserved = distances.size,
            requiredConsecutiveChunks = config.requiredConsecutiveChunks,
            referenceAccelStd = refAStd,
            referenceGyroMean = refGMean,
            chunkDistances = distances
        )
    }
}
