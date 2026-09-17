package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

class Stage1FeatureExtractorTest {

    companion object {
        private lateinit var metadataJson: JSONObject

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val stream: InputStream = this::class.java.classLoader!!.getResourceAsStream("stage1_metadata.json")
                ?: throw IllegalStateException("Resource stage1_metadata.json not found in test classpath")
            val text = stream.bufferedReader().use { it.readText() }
            metadataJson = JSONObject(text)
        }
    }

    @Test
    fun testFeatureExtractionParityAgainstPythonGroundTruth() {
        val testVectorsArray = metadataJson.getJSONArray("test_vectors")
        val numVectors = testVectorsArray.length()
        assertTrue("Expected at least 1 test vector, got $numVectors", numVectors > 0)

        var maxAbsoluteError = 0.0
        var totalFeaturesTested = 0

        for (vIdx in 0 until numVectors) {
            val vecObj = testVectorsArray.getJSONObject(vIdx)
            val windowId = vecObj.getString("window_id")
            val label = vecObj.getString("label")

            val rawDataArr = vecObj.getJSONArray("raw_imu_data")
            val rawTsArr = vecObj.getJSONArray("timestamps_ns")
            val expectedFeatsArr = vecObj.getJSONArray("feature_vector")

            val n = rawDataArr.length()
            val samples = ArrayList<SensorSample>(n)
            for (i in 0 until n) {
                val row = rawDataArr.getJSONArray(i)
                val ts = rawTsArr.getLong(i)
                samples.add(
                    SensorSample(
                        timestampNs = ts,
                        ax = row.getDouble(0).toFloat(),
                        ay = row.getDouble(1).toFloat(),
                        az = row.getDouble(2).toFloat(),
                        gx = row.getDouble(3).toFloat(),
                        gy = row.getDouble(4).toFloat(),
                        gz = row.getDouble(5).toFloat()
                    )
                )
            }

            val actualFeats = Stage1FeatureExtractor.extractFeatures(samples)
            assertEquals("Feature count mismatch", 33, actualFeats.size)

            for (fIdx in 0 until 33) {
                val expected = expectedFeatsArr.getDouble(fIdx)
                val actual = actualFeats[fIdx].toDouble()
                val diff = abs(actual - expected)
                maxAbsoluteError = max(maxAbsoluteError, diff)
                totalFeaturesTested++

                // Allow small floating-point tolerance (< 1e-3) for single vs double precision
                assertTrue(
                    "Feature '$fIdx' (${Stage1FeatureExtractor.FEATURE_NAMES[fIdx]}) for $windowId ($label) differed too much: expected=$expected, actual=$actual, diff=$diff",
                    diff < 1e-3
                )
            }
        }

        println(
            String.format(
                "[PARITY TEST] Tested %d features across %d windows. Max Absolute Error: %.8e",
                totalFeaturesTested,
                numVectors,
                maxAbsoluteError
            )
        )
        // Stricter check: max error across all features must be < 5e-4
        assertTrue("Max absolute error across all features ($maxAbsoluteError) exceeded 5e-4", maxAbsoluteError < 5e-4)
    }
}
