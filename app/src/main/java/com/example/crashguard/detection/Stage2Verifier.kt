package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Configuration dataclass for Stage 2 Causal Crash Verifier.
 * Directly mirrors `anomaly/stage2_verifier.py`.
 */
data class Stage2Config(
    val targetSamplingRateHz: Float = 53.0f,
    val postObservationS: Float = 1.2f,        // 1.2 seconds post-trigger buffer
    val preBaselineS: Float = 1.0f,            // 1.0 second pre-impact baseline
    val impactHalfWindowS: Float = 0.25f,      // 0.25 second impact core half-window

    // Feature normalization bounds
    val shockRatioMin: Float = 1.2f,
    val shockRatioMax: Float = 3.0f,
    val peakAccelMin: Float = 15.0f,
    val peakAccelMax: Float = 35.0f,

    val peakGyroMin: Float = 1.5f,
    val peakGyroMax: Float = 4.0f,

    val postAccelStdCleanRest: Float = 1.5f,
    val postAccelStdRidingVibe: Float = 5.5f,

    val postGyroMeanStill: Float = 0.35f,
    val postGyroMeanActive: Float = 1.40f,

    // Soft evidence weights
    val wShock: Float = 0.30f,
    val wRotation: Float = 0.30f,
    val wAccelDamping: Float = 0.25f,
    val wGyroDamping: Float = 0.15f,

    // Decision thresholds
    val defaultCrashThreshold: Float = 0.50f,
    val minShockThreshold: Float = 0.25f,
    val temporalRotationThreshold: Float = 0.20f,
    val eps: Float = 1e-6f
)

data class Stage2Result(
    val isCrashConfirmed: Boolean,
    val continuousCrashScore: Float,
    val crashThreshold: Float,
    val aPeak: Float,
    val shockRatio: Float,
    val gPeak: Float,
    val postAStd: Float,
    val postGMean: Float,
    val temporalImpactRotationScore: Float,
    val impactPeakIndex: Int,
    val gyroPostPeak: Float,
    val temporalDelayS: Float,
    val scoreShock: Float,
    val scoreRotation: Float,
    val scoreAccelDamping: Float,
    val scoreGyroDamping: Float,
    val detectionLatencyS: Float
)

/**
 * Causal Stage 2 Crash-Signature Verifier.
 * Evaluates the tri-phasic fall sequence: Impact -> Rotation -> Post-Impact Motion Collapse.
 */
class Stage2Verifier(val config: Stage2Config = Stage2Config()) {

    /**
     * Evaluates a causal window assembled from pre-trigger history and newly collected post-trigger samples.
     *
     * @param samples Chronological list of [SensorSample] spanning pre-baseline, trigger, and post-observation.
     * @param triggerTimestampNs The exact timestamp recorded when Stage 1 fired (NOT derived via argmax).
     * @param threshold Optional override for crash threshold (default 0.50).
     */
    fun evaluateWindow(
        samples: List<SensorSample>,
        triggerTimestampNs: Long,
        threshold: Float = config.defaultCrashThreshold
    ): Stage2Result {
        val n = samples.size
        require(n >= 10) { "Insufficient samples for Stage 2 evaluation: $n" }

        val aMag = FloatArray(n)
        val gMag = FloatArray(n)

        var triggerIdx = -1
        var minTimeDiff = Long.MAX_VALUE

        for (i in 0 until n) {
            val s = samples[i]
            aMag[i] = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            gMag[i] = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)

            val diff = abs(s.timestampNs - triggerTimestampNs)
            if (diff < minTimeDiff) {
                minTimeDiff = diff
                triggerIdx = i
            }
        }

        if (triggerIdx < 0 || triggerIdx >= n) {
            triggerIdx = n / 2
        }

        // Compute sample interval dt
        var dtS = 1.0f / config.targetSamplingRateHz
        if (n > 1) {
            val spanNs = samples.last().timestampNs - samples.first().timestampNs
            val avgDt = (spanNs.toDouble() / (n - 1)) / 1e9
            if (avgDt > 0.001) {
                dtS = avgDt.toFloat()
            }
        }
        val samplesPerSec = (1.0f / dtS).toInt().coerceAtLeast(10)

        // Causal partitions
        val halfImpact = max(2, (config.impactHalfWindowS * samplesPerSec).toInt())
        val coreStart = max(0, triggerIdx - halfImpact)
        val coreEnd = min(n, triggerIdx + halfImpact + 1)

        val preLen = max(5, (config.preBaselineS * samplesPerSec).toInt())
        val preStart = max(0, coreStart - preLen)
        val preEnd = coreStart

        val postLen = (config.postObservationS * samplesPerSec).toInt()
        var postStart = coreEnd
        var postEnd = min(n, postStart + postLen)

        if (postEnd - postStart < 5) {
            postStart = max(0, n - postLen)
            postEnd = n
        }

        // 1. Extract 4 Physical Features
        var aPeak = 0f
        for (i in coreStart until coreEnd) {
            if (aMag[i] > aPeak) aPeak = aMag[i]
        }
        if (aPeak == 0f) {
            for (v in aMag) if (v > aPeak) aPeak = v
        }

        // Pre-impact baseline median
        val preCount = preEnd - preStart
        val aPreBaseline = if (preCount > 0) {
            val preCopy = FloatArray(preCount) { aMag[preStart + it] }
            Arrays.sort(preCopy)
            Stage1FeatureExtractor.computePercentile(preCopy, 50.0)
        } else {
            9.81f
        }

        val shockRatio = aPeak / (aPreBaseline + config.eps)

        var gPeak = 0f
        for (i in coreStart until coreEnd) {
            if (gMag[i] > gPeak) gPeak = gMag[i]
        }
        if (gPeak == 0f) {
            for (v in gMag) if (v > gPeak) gPeak = v
        }

        // Post-impact acceleration variance & gyro mean
        val postCount = postEnd - postStart
        var postASum = 0.0
        var postGSum = 0.0

        for (i in postStart until postEnd) {
            postASum += aMag[i]
            postGSum += gMag[i]
        }
        val postAMean = if (postCount > 0) (postASum / postCount).toFloat() else 9.81f
        val postGMean = if (postCount > 0) (postGSum / postCount).toFloat() else 0.0f

        var postASqDiff = 0.0
        for (i in postStart until postEnd) {
            val d = aMag[i] - postAMean
            postASqDiff += d * d
        }
        val postAStd = if (postCount > 1) sqrt(postASqDiff / postCount).toFloat() else 0.0f

        // 2. Soft Evidence Sub-Scores in [0.0, 1.0]
        val normPeak = clamp((aPeak - config.peakAccelMin) / (config.peakAccelMax - config.peakAccelMin + config.eps))
        val normRatio = clamp((shockRatio - config.shockRatioMin) / (config.shockRatioMax - config.shockRatioMin + config.eps))
        val scoreShock = 0.5f * normPeak + 0.5f * normRatio

        val scoreRotation = clamp((gPeak - config.peakGyroMin) / (config.peakGyroMax - config.peakGyroMin + config.eps))

        val vibeRange = config.postAccelStdRidingVibe - config.postAccelStdCleanRest
        val scoreAccelDamping = clamp((config.postAccelStdRidingVibe - postAStd) / (vibeRange + config.eps))

        val gyroRange = config.postGyroMeanActive - config.postGyroMeanStill
        val scoreGyroDamping = clamp((config.postGyroMeanActive - postGMean) / (gyroRange + config.eps))

        // 3. Temporal Impact -> Rotation Sequencing
        var relImpactIdx = 0
        var maxCoreA = -1f
        for (i in coreStart until coreEnd) {
            if (aMag[i] > maxCoreA) {
                maxCoreA = aMag[i]
                relImpactIdx = i - coreStart
            }
        }
        val impactIdx = coreStart + relImpactIdx
        val impactTimeNs = samples[impactIdx].timestampNs

        var gyroPostPeak = 0f
        for (i in impactIdx until postEnd) {
            if (gMag[i] > gyroPostPeak) {
                gyroPostPeak = gMag[i]
            }
        }

        var globalGIdx = preStart
        var maxGlobalG = -1f
        for (i in preStart until postEnd) {
            if (gMag[i] > maxGlobalG) {
                maxGlobalG = gMag[i]
                globalGIdx = i
            }
        }
        val globalDelayS = (samples[globalGIdx].timestampNs - impactTimeNs).toFloat() / 1e9f

        val normPostGyro = clamp((gyroPostPeak - config.peakGyroMin) / (config.peakGyroMax - config.peakGyroMin + config.eps))
        val sequencingFactor = if (globalDelayS >= -0.05f) {
            1.0f
        } else {
            clamp(1.0f + globalDelayS / 0.5f)
        }
        val scoreTemporal = normPostGyro * sequencingFactor

        // 4. Continuous Composite Crash Score
        val continuousScore = config.wShock * scoreShock +
                config.wRotation * scoreRotation +
                config.wAccelDamping * scoreAccelDamping +
                config.wGyroDamping * scoreGyroDamping

        val detectionLatencyS = (postEnd - triggerIdx) * dtS

        // A crash confirmation requires:
        // 1. Composite continuous score meets/exceeds the crash threshold (default 0.50).
        // 2. Meaningful impact shock evidence (scoreShock >= 0.25).
        // 3. Meaningful post-impact rotational destabilization (temporalImpactRotationScore >= 0.20).
        val isConfirmed = continuousScore >= threshold &&
                scoreShock >= config.minShockThreshold &&
                scoreTemporal >= config.temporalRotationThreshold

        return Stage2Result(
            isCrashConfirmed = isConfirmed,
            continuousCrashScore = continuousScore,
            crashThreshold = threshold,
            aPeak = aPeak,
            shockRatio = shockRatio,
            gPeak = gPeak,
            postAStd = postAStd,
            postGMean = postGMean,
            temporalImpactRotationScore = scoreTemporal,
            impactPeakIndex = impactIdx,
            gyroPostPeak = gyroPostPeak,
            temporalDelayS = globalDelayS,
            scoreShock = scoreShock,
            scoreRotation = scoreRotation,
            scoreAccelDamping = scoreAccelDamping,
            scoreGyroDamping = scoreGyroDamping,
            detectionLatencyS = detectionLatencyS
        )
    }

    private fun clamp(v: Float): Float = max(0.0f, min(1.0f, v))
}
