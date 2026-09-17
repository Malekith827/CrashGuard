package com.example.crashguard.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.FloatBuffer

/**
 * Thread-safe wrapper for the Stage 1 ONNX Isolation Forest anomaly detector.
 *
 * Evaluates 33 deployable orientation-invariant features and outputs a positive
 * anomaly score in [0.0, 1.0].
 */
class IsolationForestModel(private val modelBytes: ByteArray) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    companion object {
        private const val TAG = "IsolationForestModel"
        private const val INPUT_NAME = "float_input"
        private const val FEATURE_COUNT = 33

        fun fromAssets(context: Context, assetPath: String = "stage1_anomaly_detector.onnx"): IsolationForestModel {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            return IsolationForestModel(bytes)
        }

        fun fromInputStream(inputStream: InputStream): IsolationForestModel {
            val bytes = inputStream.use { it.readBytes() }
            return IsolationForestModel(bytes)
        }
    }

    init {
        val options = OrtSession.SessionOptions()
        session = env.createSession(modelBytes, options)
        DetectionLogger.i(TAG, "Loaded ONNX Stage 1 model successfully. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}")
    }

    /**
     * Scores a 33-dimensional feature vector.
     *
     * @param features FloatArray of length 33.
     * @return Positive anomaly score in [0.0, 1.0] (higher = more anomalous).
     */
    @Synchronized
    fun scoreWindow(features: FloatArray): Float {
        require(features.size == FEATURE_COUNT) {
            "Expected $FEATURE_COUNT features, got ${features.size}"
        }

        val shape = longArrayOf(1, FEATURE_COUNT.toLong())
        val buffer = FloatBuffer.wrap(features)
        val tensor = OnnxTensor.createTensor(env, buffer, shape)

        tensor.use { onnxTensor ->
            val inputs = mapOf(INPUT_NAME to onnxTensor)
            session.run(inputs).use { results ->
                // Output 2 is 'score_samples'
                val scoreTensor = if (results.size() >= 3) {
                    results.get(2) as? OnnxTensor
                } else {
                    val opt = results.get("score_samples")
                    if (opt.isPresent) opt.get() as? OnnxTensor else null
                }

                if (scoreTensor != null) {
                    val rawScore = scoreTensor.floatBuffer.get(0)
                    // Pipeline definition: anomaly_score = -score_samples
                    return -rawScore
                }

                // Fallback to output 1 ('scores')
                val fallbackTensor = results.get(1) as OnnxTensor
                val decScore = fallbackTensor.floatBuffer.get(0)
                return 0.5f - decScore
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            DetectionLogger.e(TAG, "Error closing ONNX session: ${e.message}", e)
        }
    }
}
