package com.example.crashguard.detection

import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.SensorSample
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import kotlin.math.sin

class CrashDetectionPipelineTest {

    private lateinit var stage1Model: IsolationForestModel
    private lateinit var pipeline: CrashDetectionPipeline
    private val detectedAlerts = ArrayList<CrashAlertState>()

    @Before
    fun setUp() {
        val modelStream: InputStream = this::class.java.classLoader!!.getResourceAsStream("stage1_anomaly_detector.onnx")
            ?: throw IllegalStateException("Resource stage1_anomaly_detector.onnx not found")
        stage1Model = IsolationForestModel.fromInputStream(modelStream)
        detectedAlerts.clear()

        pipeline = CrashDetectionPipeline(
            stage1Model = stage1Model,
            stage2Verifier = Stage2Verifier(Stage2Config(defaultCrashThreshold = 0.50f)),
            onCrashDetected = { alert ->
                detectedAlerts.add(alert)
            }
        )
    }

    @After
    fun tearDown() {
        pipeline.close()
    }

    @Test
    fun testBenignRidingGeneratesNoCrashAlerts() {
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
    }
}
