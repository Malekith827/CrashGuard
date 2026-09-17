package com.example.crashguard.detection

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

class IsolationForestModelTest {

    private lateinit var model: IsolationForestModel
    private lateinit var metadataJson: JSONObject

    @Before
    fun setUp() {
        val modelStream: InputStream = this::class.java.classLoader!!.getResourceAsStream("stage1_anomaly_detector.onnx")
            ?: throw IllegalStateException("Resource stage1_anomaly_detector.onnx not found")
        model = IsolationForestModel.fromInputStream(modelStream)

        val metaStream: InputStream = this::class.java.classLoader!!.getResourceAsStream("stage1_metadata.json")
            ?: throw IllegalStateException("Resource stage1_metadata.json not found")
        metadataJson = JSONObject(metaStream.bufferedReader().use { it.readText() })
    }

    @After
    fun tearDown() {
        model.close()
    }

    @Test
    fun testOnnxInferenceParityAgainstPythonScores() {
        val testVectorsArray = metadataJson.getJSONArray("test_vectors")
        val numVectors = testVectorsArray.length()

        var maxAbsoluteError = 0.0

        for (vIdx in 0 until numVectors) {
            val vecObj = testVectorsArray.getJSONObject(vIdx)
            val windowId = vecObj.getString("window_id")
            val label = vecObj.getString("label")
            val expectedScore = vecObj.getDouble("expected_s1_score").toFloat()
            val featsArr = vecObj.getJSONArray("feature_vector")

            val feats = FloatArray(33)
            for (i in 0 until 33) {
                feats[i] = featsArr.getDouble(i).toFloat()
            }

            val actualScore = model.scoreWindow(feats)
            val diff = abs(actualScore - expectedScore)
            maxAbsoluteError = max(maxAbsoluteError, diff.toDouble())

            assertEquals(
                "Score mismatch for window $windowId ($label)",
                expectedScore,
                actualScore,
                1e-4f
            )
        }

        println(
            String.format(
                "[ONNX PARITY TEST] Evaluated %d test vectors. Max Absolute Error vs Python: %.8e",
                numVectors,
                maxAbsoluteError
            )
        )
        // Must satisfy < 1e-5 numerical error requirement
        assertTrue("Max absolute ONNX error ($maxAbsoluteError) exceeded 1e-5", maxAbsoluteError < 1e-5)
    }
}
