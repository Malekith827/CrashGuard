package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin port of the Python 33-feature orientation-invariant extraction pipeline.
 * Exactly mirrors `features/feature_extractor.py` and `features/feature_schema.py`.
 */
object Stage1FeatureExtractor {

    const val FEATURE_COUNT = 33
    private const val EPS = 1e-6f

    val FEATURE_NAMES = listOf(
        // Acceleration magnitude statistics (11)
        "a_mag_mean",
        "a_mag_std",
        "a_mag_median",
        "a_mag_iqr",
        "a_mag_min",
        "a_mag_max",
        "a_mag_range",
        "a_mag_p10",
        "a_mag_p90",
        "a_mag_p95",
        "a_mag_p99",
        // Acceleration transient shape & energy distribution (5)
        "a_mag_peak_to_median_ratio",
        "a_mag_crest_factor",
        "a_mag_peak_prominence",
        "a_mag_temporal_concentration",
        "a_mag_fraction_above_relative_thresh",
        // Gyroscope magnitude statistics (11)
        "g_mag_mean",
        "g_mag_std",
        "g_mag_median",
        "g_mag_iqr",
        "g_mag_min",
        "g_mag_max",
        "g_mag_range",
        "g_mag_p10",
        "g_mag_p90",
        "g_mag_p95",
        "g_mag_p99",
        // Gyroscope transient shape & energy distribution (5)
        "g_mag_peak_to_median_ratio",
        "g_mag_crest_factor",
        "g_mag_peak_prominence",
        "g_mag_temporal_concentration",
        "g_mag_fraction_above_relative_thresh",
        // Inter-sensor relationship (1)
        "cross_correlation_ag"
    )

    /**
     * Extracts all 33 deployable orientation-invariant features from a window of [SensorSample].
     *
     * @param samples List of IMU samples representing the window (typically ~159 samples for 3.0s @ 53Hz).
     * @return FloatArray of length 33 with exact feature ordering.
     */
    fun extractFeatures(samples: List<SensorSample>): FloatArray {
        val n = samples.size
        val features = FloatArray(FEATURE_COUNT)
        if (n < 2) return features

        val aMag = FloatArray(n)
        val gMag = FloatArray(n)

        for (i in 0 until n) {
            val s = samples[i]
            aMag[i] = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            gMag[i] = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
        }

        // 1. Extract 16 features for Acceleration Magnitude [0..15]
        extractNormFeaturesInto(aMag, features, offset = 0)

        // 2. Extract 16 features for Gyroscope Magnitude [16..31]
        extractNormFeaturesInto(gMag, features, offset = 16)

        // 3. Extract Cross-Correlation [32]
        features[32] = computeCrossCorrelation(aMag, gMag)

        return features
    }

    /**
     * Extracts 16 statistical, shape, and transient energy features from a 1D norm signal.
     */
    private fun extractNormFeaturesInto(signal: FloatArray, out: FloatArray, offset: Int) {
        val n = signal.size
        if (n == 0) return

        var sum = 0.0
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE

        for (v in signal) {
            sum += v
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val meanVal = (sum / n).toFloat()

        var sumSqDiff = 0.0
        var sumSq = 0.0
        val energy = FloatArray(n)

        for (i in 0 until n) {
            val diff = signal[i] - meanVal
            sumSqDiff += diff * diff
            val sq = signal[i] * signal[i]
            sumSq += sq
            energy[i] = sq
        }

        val stdVal = sqrt(sumSqDiff / n).toFloat()
        val rangeVal = maxVal - minVal
        val rmsVal = sqrt(sumSq / n).toFloat()

        // Sorted copy for percentiles and energy concentration
        val sorted = signal.clone()
        Arrays.sort(sorted)

        val p10 = computePercentile(sorted, 10.0)
        val p25 = computePercentile(sorted, 25.0)
        val medianVal = computePercentile(sorted, 50.0)
        val p75 = computePercentile(sorted, 75.0)
        val p90 = computePercentile(sorted, 90.0)
        val p95 = computePercentile(sorted, 95.0)
        val p99 = computePercentile(sorted, 99.0)
        val iqrVal = p75 - p25

        val peakToMedian = if (medianVal + EPS > 0f) maxVal / (medianVal + EPS) else 1.0f
        val crestFactor = if (rmsVal + EPS > 0f) maxVal / (rmsVal + EPS) else 1.0f
        val peakProminence = maxVal - medianVal

        // Energy concentration in top 10% highest energy samples
        Arrays.sort(energy)
        val k = max(1, ceil(n * 0.10).toInt())
        var topKEnergy = 0.0
        for (i in n - k until n) {
            topKEnergy += energy[i]
        }
        val totalEnergy = sumSq
        val temporalConcentration = if (totalEnergy + EPS > 0.0) (topKEnergy / (totalEnergy + EPS)).toFloat() else 0.0f

        // Fraction of window exceeding relative threshold (2.0 * median)
        val relThresh = 2.0f * medianVal
        var countAbove = 0
        for (v in signal) {
            if (v > relThresh) countAbove++
        }
        val fractionAboveRel = countAbove.toFloat() / n

        // Store into output array
        out[offset + 0] = meanVal
        out[offset + 1] = stdVal
        out[offset + 2] = medianVal
        out[offset + 3] = iqrVal
        out[offset + 4] = minVal
        out[offset + 5] = maxVal
        out[offset + 6] = rangeVal
        out[offset + 7] = p10
        out[offset + 8] = p90
        out[offset + 9] = p95
        out[offset + 10] = p99
        out[offset + 11] = peakToMedian
        out[offset + 12] = crestFactor
        out[offset + 13] = peakProminence
        out[offset + 14] = temporalConcentration
        out[offset + 15] = fractionAboveRel
    }

    /**
     * Exact numpy linear-interpolation percentile implementation.
     * Equivalent to `np.percentile(sorted_array, p, method='linear')`.
     */
    fun computePercentile(sorted: FloatArray, percentile: Double): Float {
        val n = sorted.size
        if (n == 0) return 0f
        if (n == 1) return sorted[0]
        if (percentile <= 0.0) return sorted[0]
        if (percentile >= 100.0) return sorted[n - 1]

        val index = (n - 1) * (percentile / 100.0)
        val lowerIndex = index.toInt()
        val fraction = (index - lowerIndex).toFloat()

        if (lowerIndex >= n - 1) {
            return sorted[n - 1]
        }

        return sorted[lowerIndex] + fraction * (sorted[lowerIndex + 1] - sorted[lowerIndex])
    }

    /**
     * Pearson cross-correlation between two signals.
     */
    private fun computeCrossCorrelation(a: FloatArray, g: FloatArray): Float {
        val n = a.size
        if (n < 2) return 0f

        var sumA = 0.0
        var sumG = 0.0
        for (i in 0 until n) {
            sumA += a[i]
            sumG += g[i]
        }
        val meanA = sumA / n
        val meanG = sumG / n

        var varA = 0.0
        var varG = 0.0
        var cov = 0.0

        for (i in 0 until n) {
            val diffA = a[i] - meanA
            val diffG = g[i] - meanG
            varA += diffA * diffA
            varG += diffG * diffG
            cov += diffA * diffG
        }

        val stdA = sqrt(varA / n)
        val stdG = sqrt(varG / n)

        if (stdA > EPS && stdG > EPS) {
            val denom = sqrt(varA * varG)
            if (denom > 0.0) {
                return (cov / denom).toFloat()
            }
        }
        return 0f
    }
}
