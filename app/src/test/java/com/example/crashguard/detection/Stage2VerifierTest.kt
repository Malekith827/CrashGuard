package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Stage2VerifierTest {

    private val verifier = Stage2Verifier(Stage2Config(defaultCrashThreshold = 0.50f))

    private fun syntheticWindow(
        coreAccel: Float,
        coreGyro: Float,
        baselineAccel: Float = 9.81f,
        gyroOffsetSamples: Int = 0,
        postAccelStd: Float = 0.0f,
        postGyroMean: Float = 0.0f
    ): Pair<List<SensorSample>, Long> {
        val sampleCount = 180
        val triggerIndex = 70
        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L
        val samples = ArrayList<SensorSample>(sampleCount)

        val gyroCenter = triggerIndex + gyroOffsetSamples
        val gStart = max(0, gyroCenter - 13)
        val gEnd = min(sampleCount, gyroCenter + 14)

        for (i in 0 until sampleCount) {
            val inCoreA = i in triggerIndex - 13..triggerIndex + 13
            val inCoreG = i in gStart until gEnd
            val isPost = i > triggerIndex + 13

            val ax = when {
                inCoreA -> coreAccel
                isPost && postAccelStd > 0f -> baselineAccel + (sin(i * 1.5) * (postAccelStd * 1.414)).toFloat()
                else -> baselineAccel
            }
            val gx = when {
                inCoreG -> coreGyro
                isPost && postGyroMean > 0f -> postGyroMean
                else -> 0.0f
            }

            samples.add(
                SensorSample(
                    timestampNs = currentTs,
                    ax = ax,
                    ay = 0.0f,
                    az = 0.0f,
                    gx = gx,
                    gy = 0.0f,
                    gz = 0.0f
                )
            )
            currentTs += dtNs
        }

        return samples to samples[triggerIndex].timestampNs
    }

    @Test
    fun testCaseA_genuineImpactFollowedByRotation_isConfirmed() {
        // Case A: High S2 + high shock + rotation occurring AFTER impact -> confirmed.
        // Gyro peak placed at +5 samples (approx +0.094s after impact)
        val (samples, triggerTs) = syntheticWindow(coreAccel = 30.0f, coreGyro = 4.0f, gyroOffsetSamples = 5)
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected S2 >= 0.50", result.continuousCrashScore >= 0.50f)
        assertTrue("Expected Shock >= 0.25", result.scoreShock >= 0.25f)
        assertTrue("Expected Temporal >= 0.20", result.temporalImpactRotationScore >= 0.20f)
        assertTrue("Expected crash confirmed", result.isCrashConfirmed)
    }

    @Test
    fun testCaseB_rotationBeforeImpact_isRejected() {
        // Case B: High S2 + high shock + gyro peak occurring BEFORE impact -> rejected by temporal gate.
        // Gyro peak placed 25 samples BEFORE impact (approx -0.47s before impact)
        val (samples, triggerTs) = syntheticWindow(coreAccel = 30.0f, coreGyro = 4.0f, gyroOffsetSamples = -25)
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected S2 >= 0.50", result.continuousCrashScore >= 0.50f)
        assertTrue("Expected Shock >= 0.25", result.scoreShock >= 0.25f)
        assertTrue("Expected Temporal < 0.20", result.temporalImpactRotationScore < 0.20f)
        assertFalse("Expected crash rejected due to pre-impact rotation", result.isCrashConfirmed)
    }

    @Test
    fun testCaseC_insufficientShock_isRejected() {
        // Case C: High S2 + Shock below 0.25 -> rejected.
        // baseline=15.0, coreAccel=17.8 -> scoreShock ~ 0.07 < 0.25
        val (samples, triggerTs) = syntheticWindow(coreAccel = 17.8f, coreGyro = 4.0f, baselineAccel = 15.0f, gyroOffsetSamples = 5)
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected S2 >= 0.50", result.continuousCrashScore >= 0.50f)
        assertTrue("Expected Shock < 0.25", result.scoreShock < 0.25f)
        assertFalse("Expected crash rejected due to insufficient shock", result.isCrashConfirmed)
    }

    @Test
    fun testCaseD_insufficientTemporalScore_isRejected() {
        // Case D: High shock + low angular rotation (coreGyro = 1.0 < peakGyroMin 1.5) -> Temporal < 0.20 -> rejected.
        val (samples, triggerTs) = syntheticWindow(coreAccel = 30.0f, coreGyro = 1.0f, gyroOffsetSamples = 5)
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected Temporal < 0.20", result.temporalImpactRotationScore < 0.20f)
        assertFalse("Expected crash rejected due to insufficient temporal rotation", result.isCrashConfirmed)
    }

    @Test
    fun testCaseE_insufficientS2_isRejected() {
        // Case E: S2 below 0.50 -> rejected.
        // High shock, but no rotation and active vibration -> S2 < 0.50
        val (samples, triggerTs) = syntheticWindow(
            coreAccel = 30.0f,
            coreGyro = 0.0f,
            postAccelStd = 10.0f,
            postGyroMean = 2.0f
        )
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected S2 < 0.50", result.continuousCrashScore < 0.50f)
        assertFalse("Expected crash rejected due to low S2", result.isCrashConfirmed)
    }

    @Test
    fun testCaseF_benignRiding_isRejected() {
        // Case F: Ordinary benign riding event -> not confirmed.
        val (samples, triggerTs) = syntheticWindow(coreAccel = 9.81f, coreGyro = 0.3f, postAccelStd = 3.0f, postGyroMean = 0.5f)
        val result = verifier.evaluateWindow(samples, triggerTs)

        assertTrue("Expected S2 < 0.50", result.continuousCrashScore < 0.50f)
        assertFalse("Expected crash not confirmed", result.isCrashConfirmed)
    }

    @Test
    fun testCrashFallProfileIsConfirmed() {
        // Build a synthetic 3.0s + 1.2s fall profile at 53Hz (~220 samples):
        // 1. Pre-impact baseline (1.0s): riding noise ax=9.8, az=0, gyro=0.4
        // 2. Impact transient (0.3s): sharp shock ax=28.0 m/s^2, gyro=3.0 rad/s
        // 3. Post-impact stillness (1.5s): motorcycle lying on ground ax=9.81, std=0.3 m/s^2, gyro=0.1 rad/s (still)
        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L

        val samples = ArrayList<SensorSample>()
        val triggerIdx = 80 // impact peak around sample 80 (~1.5s in)

        for (i in 0 until 220) {
            val ax: Float
            val ay: Float
            val az: Float
            val gx: Float
            val gy: Float
            val gz: Float

            if (i < 75) {
                // Pre-impact baseline
                ax = 9.8f + (sin(i * 0.5) * 1.0).toFloat()
                ay = (sin(i * 0.3) * 0.8).toFloat()
                az = 1.0f
                gx = 0.3f
                gy = 0.2f
                gz = 0.1f
            } else if (i in 75..85) {
                // Impact + tumble
                val mult = if (i == 80) 28.0f else 18.0f
                ax = mult
                ay = 12.0f
                az = 8.0f
                gx = 2.8f
                gy = 1.5f
                gz = 1.2f
            } else {
                // Post-impact stillness (slide / rest)
                ax = 9.81f + (sin(i * 0.1) * 0.2).toFloat() // very low std ~0.2
                ay = 0.1f
                az = 0.05f
                gx = 0.05f
                gy = 0.02f
                gz = 0.01f
            }

            samples.add(SensorSample(currentTs, ax, ay, az, gx, gy, gz))
            currentTs += dtNs
        }

        val triggerTs = samples[triggerIdx].timestampNs
        val result = verifier.evaluateWindow(samples, triggerTs, threshold = 0.50f)

        println(
            String.format(
                "[STAGE 2 TEST] Synthetic Crash Fall Score: %.4f (Threshold=%.2f, Confirmed=%b, Shock=%.2f, Rot=%.2f, ADamp=%.2f, GDamp=%.2f, Temporal=%.2f)",
                result.continuousCrashScore,
                result.crashThreshold,
                result.isCrashConfirmed,
                result.scoreShock,
                result.scoreRotation,
                result.scoreAccelDamping,
                result.scoreGyroDamping,
                result.temporalImpactRotationScore
            )
        )

        assertTrue("Expected synthetic crash profile to confirm crash (score >= 0.50)", result.isCrashConfirmed)
        assertTrue("Expected score >= 0.50", result.continuousCrashScore >= 0.50f)
    }

    @Test
    fun testPotholeProfileIsSuppressed() {
        // Pothole profile:
        // High transient shock, but CONTINUED active riding vibration in post-buffer (std ~ 5.5 m/s^2, gyro ~ 1.5 rad/s)
        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L

        val samples = ArrayList<SensorSample>()
        val triggerIdx = 80

        for (i in 0 until 220) {
            val ax: Float
            val ay: Float
            val az: Float
            val gx: Float
            val gy: Float
            val gz: Float

            if (i in 78..82) {
                // Sharp bump shock
                ax = 24.0f
                ay = 5.0f
                az = 2.0f
                gx = 1.0f
                gy = 0.5f
                gz = 0.2f
            } else {
                // High vibration riding
                ax = 9.8f + (sin(i * 1.5) * 5.0).toFloat()
                ay = (sin(i * 1.2) * 3.0).toFloat()
                az = (sin(i * 0.9) * 2.0).toFloat()
                gx = 1.4f + (sin(i * 0.4) * 0.6).toFloat()
                gy = 0.8f
                gz = 0.5f
            }

            samples.add(SensorSample(currentTs, ax, ay, az, gx, gy, gz))
            currentTs += dtNs
        }

        val triggerTs = samples[triggerIdx].timestampNs
        val result = verifier.evaluateWindow(samples, triggerTs, threshold = 0.50f)

        println(
            String.format(
                "[STAGE 2 TEST] Synthetic Pothole Score: %.4f (Threshold=%.2f, Confirmed=%b, ADamp=%.2f, GDamp=%.2f, Temporal=%.2f)",
                result.continuousCrashScore,
                result.crashThreshold,
                result.isCrashConfirmed,
                result.scoreAccelDamping,
                result.scoreGyroDamping,
                result.temporalImpactRotationScore
            )
        )

        assertFalse("Expected pothole with continuous riding vibration to be suppressed (score < 0.50)", result.isCrashConfirmed)
    }
}
