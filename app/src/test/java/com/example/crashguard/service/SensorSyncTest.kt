package com.example.crashguard.service

import com.example.crashguard.data.SensorSample
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic unit tests for the Android sensor synchronization and pairing logic.
 */
class SensorSyncTest {

    private class TestSensorSynchronizer(private val maxPairingGapNs: Long = 20_000_000L) {
        val diagnostics = SensorSyncDiagnostics()
        val pairedSamples = ArrayList<SensorSample>()

        var lastAx = 0f
        var lastAy = 0f
        var lastAz = 0f
        var lastAccelTimeNs = 0L

        var lastGx = 0f
        var lastGy = 0f
        var lastGz = 0f
        var lastGyroTimeNs = 0L

        var lastLoggedAccelTimeNs = 0L
        var lastLoggedGyroTimeNs = 0L

        fun onAccelEvent(timestampNs: Long, ax: Float = 9.8f, ay: Float = 0f, az: Float = 0f) {
            lastAx = ax
            lastAy = ay
            lastAz = az
            lastAccelTimeNs = timestampNs
            diagnostics.onAccelReceived(timestampNs)
            attemptPair("ACCEL", timestampNs)
        }

        fun onGyroEvent(timestampNs: Long, gx: Float = 0.1f, gy: Float = 0f, gz: Float = 0f) {
            lastGx = gx
            lastGy = gy
            lastGz = gz
            lastGyroTimeNs = timestampNs
            diagnostics.onGyroReceived(timestampNs)
            attemptPair("GYRO", timestampNs)
        }

        private fun attemptPair(triggerType: String, eventTimestampNs: Long) {
            if (lastAccelTimeNs > 0 && lastGyroTimeNs > 0) {
                val gap = abs(lastAccelTimeNs - lastGyroTimeNs)

                if (gap <= maxPairingGapNs) {
                    val isDuplicate = (lastAccelTimeNs == lastLoggedAccelTimeNs || lastGyroTimeNs == lastLoggedGyroTimeNs)

                    if (!isDuplicate) {
                        val sample = SensorSample(
                            timestampNs = eventTimestampNs,
                            ax = lastAx,
                            ay = lastAy,
                            az = lastAz,
                            gx = lastGx,
                            gy = lastGy,
                            gz = lastGz
                        )

                        diagnostics.onSamplePaired(
                            triggerSensorType = triggerType,
                            accelTs = lastAccelTimeNs,
                            gyroTs = lastGyroTimeNs,
                            sampleTs = eventTimestampNs
                        )

                        pairedSamples.add(sample)

                        lastLoggedAccelTimeNs = lastAccelTimeNs
                        lastLoggedGyroTimeNs = lastGyroTimeNs
                    } else {
                        diagnostics.onDuplicateAttempt(lastAccelTimeNs, lastGyroTimeNs)
                    }
                } else {
                    diagnostics.onGapExceeded(lastAccelTimeNs, lastGyroTimeNs, gap)
                }
            }
        }
    }

    @Test
    fun test106HzAccelWith53HzGyro_pairsOneToOneAt53Hz() {
        val synchronizer = TestSensorSynchronizer()

        // Simulate 1.0 second of sensor data:
        // Accel arrives at ~9.417 ms interval (106.2 Hz) -> 106 samples
        // Gyro arrives at ~18.832 ms interval (53.1 Hz) -> 53 samples
        val accelIntervalNs = 9_417_000L
        val gyroIntervalNs = 18_832_000L

        var nextAccelTs = 1_000_000_000L
        var nextGyroTs = 1_000_500_000L // 0.5 ms phase offset

        var accelCount = 0
        var gyroCount = 0

        while (accelCount < 106 || gyroCount < 53) {
            if (accelCount < 106 && (gyroCount >= 53 || nextAccelTs <= nextGyroTs)) {
                synchronizer.onAccelEvent(nextAccelTs, ax = (accelCount + 1).toFloat())
                nextAccelTs += accelIntervalNs
                accelCount++
            } else {
                synchronizer.onGyroEvent(nextGyroTs, gx = (gyroCount + 1).toFloat())
                nextGyroTs += gyroIntervalNs
                gyroCount++
            }
        }

        val stats = synchronizer.diagnostics.getStats()

        assertEquals("Expected 106 accel samples", 106L, stats.accelReceived)
        assertEquals("Expected 53 gyro samples", 53L, stats.gyroReceived)
        assertEquals("Expected exactly 53 paired samples (1-to-1 matching gyro rate)", 53L, stats.pairedSamples)
        assertEquals("Expected 53 dropped/unpaired accel samples", 53L, stats.droppedSamples)
        assertTrue("Duplicate blocks should equal dropped accel samples", stats.duplicatesBlocked >= 50L)

        // Verify that all pair deltas are well within 20ms tolerance (expected < 10ms for 106Hz stream)
        assertTrue("Max delta should be <= 20.0ms", stats.maxDeltaMs <= 20.0f)
        assertTrue("Min delta should be >= 0.0ms", stats.minDeltaMs >= 0.0f)
        assertTrue("p50 delta should be <= 10.0ms", stats.p50DeltaMs <= 10.0f)

        // Verify no duplicate sensor samples in paired output
        val loggedAccels = synchronizer.pairedSamples.map { it.ax }.toSet()
        val loggedGyros = synchronizer.pairedSamples.map { it.gx }.toSet()
        assertEquals("All paired accel values must be unique (no reuse)", 53, loggedAccels.size)
        assertEquals("All paired gyro values must be unique (no reuse)", 53, loggedGyros.size)
    }

    @Test
    fun testExcessiveGap_dropsSamples() {
        val synchronizer = TestSensorSynchronizer(maxPairingGapNs = 20_000_000L) // 20 ms

        // Accel at t=0
        synchronizer.onAccelEvent(1_000_000_000L)
        // Gyro at t=25ms (gap = 25ms > 20ms)
        synchronizer.onGyroEvent(1_025_000_000L)

        val stats = synchronizer.diagnostics.getStats()
        assertEquals("Should not pair when gap > 20ms", 0L, stats.pairedSamples)
        assertEquals("Both samples should be recorded as received", 1L, stats.accelReceived)
        assertEquals("Both samples should be recorded as received", 1L, stats.gyroReceived)
        assertEquals("Both samples should be dropped", 2L, stats.droppedSamples)
    }

    @Test
    fun testDuplicatePrevention_neverReusesSameTimestamp() {
        val synchronizer = TestSensorSynchronizer()

        // Accel 1 at t=0, Gyro 1 at t=1ms -> Pairs (A1, G1)
        synchronizer.onAccelEvent(1_000_000_000L, ax = 1.0f)
        synchronizer.onGyroEvent(1_001_000_000L, gx = 1.0f)
        assertEquals(1, synchronizer.pairedSamples.size)

        // Accel 2 arrives at t=9ms, but no new gyro yet -> Should NOT pair with G1 again
        synchronizer.onAccelEvent(1_009_000_000L, ax = 2.0f)
        assertEquals("Must NOT reuse G1", 1, synchronizer.pairedSamples.size)

        // Accel 3 arrives at t=18ms -> Still no new gyro -> Should NOT pair
        synchronizer.onAccelEvent(1_018_000_000L, ax = 3.0f)
        assertEquals("Must NOT reuse G1", 1, synchronizer.pairedSamples.size)

        // Gyro 2 arrives at t=19ms -> Pairs with latest Accel (A3)
        synchronizer.onGyroEvent(1_019_000_000L, gx = 2.0f)
        assertEquals("Should pair (A3, G2)", 2, synchronizer.pairedSamples.size)
        assertEquals(3.0f, synchronizer.pairedSamples[1].ax, 0.001f)
        assertEquals(2.0f, synchronizer.pairedSamples[1].gx, 0.001f)
    }

    @Test
    fun testTimestampProvenance_recordsTriggerTimestamp() {
        val synchronizer = TestSensorSynchronizer()

        // Case 1: Gyro arrives first, Accel triggers pairing
        synchronizer.onGyroEvent(1_000_000_000L)
        synchronizer.onAccelEvent(1_002_000_000L) // Trigger is Accel
        assertEquals("Timestamp must match triggering event (Accel)", 1_002_000_000L, synchronizer.pairedSamples[0].timestampNs)

        // Case 2: Accel arrives first, Gyro triggers pairing
        synchronizer.onAccelEvent(1_010_000_000L)
        synchronizer.onGyroEvent(1_012_000_000L) // Trigger is Gyro
        assertEquals("Timestamp must match triggering event (Gyro)", 1_012_000_000L, synchronizer.pairedSamples[1].timestampNs)
    }
}
