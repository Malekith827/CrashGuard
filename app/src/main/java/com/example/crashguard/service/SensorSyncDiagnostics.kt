package com.example.crashguard.service

import com.example.crashguard.detection.DetectionLogger
import java.util.ArrayList
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Diagnostic tracker and logger for Android sensor synchronization.
 * Tracks pairing statistics, delta distributions, dropped samples, and duplicate prevention.
 */
class SensorSyncDiagnostics {

    companion object {
        const val TAG = "CrashGuardSync"
    }

    var totalAccelReceived: Long = 0
        private set
    var totalGyroReceived: Long = 0
        private set
    var totalPaired: Long = 0
        private set
    var totalDroppedOrSkipped: Long = 0
        private set
    var totalDuplicateBlocked: Long = 0
        private set

    // Deltas in milliseconds for statistical distribution
    private val pairDeltasMs = ArrayList<Float>()
    private var minDeltaMs = Float.MAX_VALUE
    private var maxDeltaMs = 0f

    @Synchronized
    fun onAccelReceived(timestampNs: Long) {
        totalAccelReceived++
    }

    @Synchronized
    fun onGyroReceived(timestampNs: Long) {
        totalGyroReceived++
    }

    @Synchronized
    fun onDuplicateAttempt(accelTs: Long, gyroTs: Long) {
        totalDuplicateBlocked++
        totalDroppedOrSkipped++
    }

    @Synchronized
    fun onGapExceeded(accelTs: Long, gyroTs: Long, gapNs: Long) {
        totalDroppedOrSkipped++
    }

    @Synchronized
    fun onSamplePaired(
        triggerSensorType: String,
        accelTs: Long,
        gyroTs: Long,
        sampleTs: Long
    ) {
        totalPaired++
        val deltaNs = abs(accelTs - gyroTs)
        val deltaMs = deltaNs / 1_000_000f

        minDeltaMs = min(minDeltaMs, deltaMs)
        maxDeltaMs = max(maxDeltaMs, deltaMs)
        pairDeltasMs.add(deltaMs)

        // Detailed log for each paired sample
        DetectionLogger.d(
            TAG,
            String.format(
                "PAIRED #%d | Trigger=%s | AccelTs=%d | GyroTs=%d | SampleTs=%d | Delta=%.3f ms",
                totalPaired,
                triggerSensorType,
                accelTs,
                gyroTs,
                sampleTs,
                deltaMs
            )
        )

        // Periodic summary log every 100 paired samples
        if (totalPaired % 100L == 0L) {
            logSummary()
        }
    }

    @Synchronized
    fun getStats(): SyncStats {
        val (p50, p95) = computePercentiles()
        val minD = if (pairDeltasMs.isEmpty()) 0f else minDeltaMs
        return SyncStats(
            accelReceived = totalAccelReceived,
            gyroReceived = totalGyroReceived,
            pairedSamples = totalPaired,
            droppedSamples = (totalAccelReceived + totalGyroReceived) - (2 * totalPaired),
            duplicatesBlocked = totalDuplicateBlocked,
            minDeltaMs = minD,
            p50DeltaMs = p50,
            p95DeltaMs = p95,
            maxDeltaMs = maxDeltaMs
        )
    }

    @Synchronized
    fun logSummary() {
        val stats = getStats()
        DetectionLogger.i(
            TAG,
            String.format(
                "DIAGNOSTIC SUMMARY: Accel=%d, Gyro=%d, Paired=%d, Dropped=%d, DupBlocked=%d | Delta(ms): min=%.3f, p50=%.3f, p95=%.3f, max=%.3f",
                stats.accelReceived,
                stats.gyroReceived,
                stats.pairedSamples,
                stats.droppedSamples,
                stats.duplicatesBlocked,
                stats.minDeltaMs,
                stats.p50DeltaMs,
                stats.p95DeltaMs,
                stats.maxDeltaMs
            )
        )
    }

    @Synchronized
    fun reset() {
        totalAccelReceived = 0
        totalGyroReceived = 0
        totalPaired = 0
        totalDroppedOrSkipped = 0
        totalDuplicateBlocked = 0
        pairDeltasMs.clear()
        minDeltaMs = Float.MAX_VALUE
        maxDeltaMs = 0f
    }

    private fun computePercentiles(): Pair<Float, Float> {
        if (pairDeltasMs.isEmpty()) return Pair(0f, 0f)
        val sorted = ArrayList(pairDeltasMs)
        Collections.sort(sorted)
        val n = sorted.size
        val p50Idx = (n * 0.50).toInt().coerceIn(0, n - 1)
        val p95Idx = (n * 0.95).toInt().coerceIn(0, n - 1)
        return Pair(sorted[p50Idx], sorted[p95Idx])
    }
}

data class SyncStats(
    val accelReceived: Long,
    val gyroReceived: Long,
    val pairedSamples: Long,
    val droppedSamples: Long,
    val duplicatesBlocked: Long,
    val minDeltaMs: Float,
    val p50DeltaMs: Float,
    val p95DeltaMs: Float,
    val maxDeltaMs: Float
)
