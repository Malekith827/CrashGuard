package com.example.crashguard.detection

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.SensorSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import kotlin.math.sin

class CrashDetectionPipelineTest {

    private lateinit var stage1Model: IsolationForestModel
    private val detectedAlerts = ArrayList<CrashAlertState>()

    @Before
    fun setUp() {
        val modelStream: InputStream = this::class.java.classLoader!!.getResourceAsStream("stage1_anomaly_detector.onnx")
            ?: throw IllegalStateException("Resource stage1_anomaly_detector.onnx not found")
        stage1Model = IsolationForestModel.fromInputStream(modelStream)
        detectedAlerts.clear()
    }

    @After
    fun tearDown() {
        stage1Model.close()
    }

    @Test
    fun testBenignRidingGeneratesNoCrashAlerts() {
        val pipeline = CrashDetectionPipeline(
            stage1Model = stage1Model,
            stage2Verifier = Stage2Verifier(Stage2Config(defaultCrashThreshold = 0.50f)),
            experimentalRecoveryEnabled = true,
            onCrashDetected = { alert ->
                detectedAlerts.add(alert)
            }
        )

        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L

        // Feed 10 seconds of smooth normal riding (~530 samples)
        for (i in 0 until 530) {
            val ax = 9.8f + (sin(i * 0.1) * 0.3).toFloat()
            val ay = (sin(i * 0.08) * 0.2).toFloat()
            val az = (sin(i * 0.05) * 0.1).toFloat()
            val gx = (sin(i * 0.05) * 0.05).toFloat()
            val gy = (sin(i * 0.03) * 0.04).toFloat()
            val gz = (sin(i * 0.02) * 0.02).toFloat()

            pipeline.onSample(SensorSample(currentTs, ax, ay, az, gx, gy, gz))
            currentTs += dtNs
        }

        assertTrue("Expected 0 crash alerts during benign riding, got ${detectedAlerts.size}", detectedAlerts.isEmpty())
        pipeline.close()
    }

    @Test
    fun testExperimentalRecoverySuppressesRecoveringTransient() {
        val pipeline = CrashDetectionPipeline(
            stage1Model = stage1Model,
            stage2Verifier = Stage2Verifier(Stage2Config(defaultCrashThreshold = 0.50f)),
            experimentalRecoveryEnabled = true,
            onCrashDetected = { alert ->
                detectedAlerts.add(alert)
            }
        )

        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L

        // 1. Initial 3.0s baseline riding (160 samples)
        for (i in 0 until 160) {
            val ax = 9.81f + (sin(i * 0.5) * 1.0).toFloat()
            pipeline.onSample(SensorSample(currentTs, ax, 0f, 0f, 0.5f, 0f, 0f))
            currentTs += dtNs
        }

        // 2. High transient shock triggering Stage 1 + Stage 2 candidate (20 samples)
        for (i in 0 until 20) {
            pipeline.onSample(SensorSample(currentTs, 35.0f, 10f, 0f, 4.0f, 1f, 0f))
            currentTs += dtNs
        }

        // 3. Post-event riding recovery (returns to normal baseline riding for 4.0s = ~212 samples)
        for (i in 0 until 212) {
            val ax = 9.81f + (sin(i * 0.5) * 1.0).toFloat()
            pipeline.onSample(SensorSample(currentTs, ax, 0f, 0f, 0.5f, 0f, 0f))
            currentTs += dtNs
        }

        // In experimental recovery mode, recovering to riding baseline suppresses the crash alert!
        assertTrue("Expected 0 crash alerts due to post-event riding recovery, got ${detectedAlerts.size}", detectedAlerts.isEmpty())
        pipeline.close()
    }
}
