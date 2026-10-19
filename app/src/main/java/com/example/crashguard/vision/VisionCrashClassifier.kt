package com.example.crashguard.vision

import com.example.crashguard.BuildConfig
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.DetectionLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Google Gemini 3 Flash Preview Vision crash verification using generateContent REST API.
 */
class VisionCrashClassifier(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val modelName: String = "gemini-3-flash-preview",
    private val apiEndpoint: String = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "CrashGuardVision"

        const val SYSTEM_PROMPT = """You are CrashGuard, a motorcycle crash verification assistant.

You receive sequential frames captured immediately after an IMU-based crash candidate.

Classify ONLY what is visually observable.

Possible outputs:
- CRASH
- NO_CRASH
- UNCERTAIN

CRASH means visual evidence strongly suggests the rider has fallen, the motorcycle is on the ground, collision aftermath is visible, or an obvious accident scene exists.

NO_CRASH means rider is still riding normally, motorcycle remains upright, camera shows normal movement, road vibration, potholes, or no crash aftermath.

UNCERTAIN means the frames are insufficient, blurred, obstructed, dark, or inconclusive.

Return ONLY JSON."""
    }

    /**
     * Classifies a list of 5 Base64 JPEG frames alongside IMU telemetry.
     */
    fun classifyFrames(
        frames: List<ExtractedFrame>,
        alert: CrashAlertState
    ): VisionClassificationResult {
        val startTimeMs = System.currentTimeMillis()

        if (apiKey.isBlank()) {
            val latencyMs = System.currentTimeMillis() - startTimeMs
            DetectionLogger.w(TAG, "Gemini API key is missing. Returning UNCERTAIN decision.")
            return VisionClassificationResult(
                decision = VisionDecision.UNCERTAIN,
                confidence = 0.0f,
                reason = "API key not configured (GEMINI_API_KEY missing)",
                latencyMs = latencyMs
            )
        }

        if (frames.isEmpty()) {
            val latencyMs = System.currentTimeMillis() - startTimeMs
            DetectionLogger.w(TAG, "No frames provided for vision classification. Returning UNCERTAIN decision.")
            return VisionClassificationResult(
                decision = VisionDecision.UNCERTAIN,
                confidence = 0.0f,
                reason = "No frames extracted from recording",
                latencyMs = latencyMs
            )
        }

        DetectionLogger.i(TAG, "API request started: sending ${frames.size} frames to $modelName at $apiEndpoint")

        try {
            val requestJson = buildGeminiPayload(frames, alert)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(apiEndpoint)
                .addHeader("x-goog-api-key", apiKey)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latencyMs = System.currentTimeMillis() - startTimeMs
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    DetectionLogger.e(TAG, "Gemini Vision API error: HTTP ${response.code} (latency: ${latencyMs}ms). Response body: $responseBody")
                    return VisionClassificationResult(
                        decision = VisionDecision.UNCERTAIN,
                        confidence = 0.0f,
                        reason = "API HTTP error ${response.code}: $responseBody",
                        latencyMs = latencyMs,
                        rawJson = responseBody
                    )
                }

                val parsedResult = parseGeminiResponse(responseBody, latencyMs)
                DetectionLogger.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Vision Result: Decision=%s, Confidence=%.2f, Latency=%dms, Reason=\"%s\"",
                        parsedResult.decision.name,
                        parsedResult.confidence,
                        parsedResult.latencyMs,
                        parsedResult.reason
                    )
                )
                return parsedResult
            }
        } catch (t: Throwable) {
            val latencyMs = System.currentTimeMillis() - startTimeMs
            DetectionLogger.e(TAG, "Vision classification network/parsing error: ${t.message} (latency: ${latencyMs}ms)", t)
            return VisionClassificationResult(
                decision = VisionDecision.UNCERTAIN,
                confidence = 0.0f,
                reason = "Network or timeout error: ${t.message}",
                latencyMs = latencyMs
            )
        }
    }

    private fun buildGeminiPayload(frames: List<ExtractedFrame>, alert: CrashAlertState): JSONObject {
        val imuText = String.format(
            Locale.US,
            "IMU Crash Candidate Summary:\n" +
            "- Stage 1 Anomaly Score: %.3f (threshold >= 0.572)\n" +
            "- Stage 2 Causal Score: %.3f (threshold >= 0.500)\n" +
            "- Shock Score: %.2f\n" +
            "- Temporal Rotation Score: %.2f (delay: %.3fs)\n" +
            "- Motion Recovery State: %s (%.1fs observed)\n" +
            "- Telemetry Details: %s\n\n" +
            "Inspect the following %d sequential post-event camera frames and classify the scene.",
            alert.stage1Score,
            alert.stage2Score,
            alert.shockScore,
            alert.temporalScore,
            alert.temporalDelayS,
            alert.recoveryState,
            alert.recoveryObservedS,
            alert.details,
            frames.size
        )

        val userPartsArray = JSONArray()

        // 1. Structured IMU Telemetry text
        userPartsArray.put(
            JSONObject().apply {
                put("text", imuText)
            }
        )

        // 2. 5 Sequential Image Frames (Base64 JPEG inlineData)
        for (frame in frames) {
            val inlineDataObj = JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", frame.base64Jpeg)
            }
            userPartsArray.put(
                JSONObject().apply {
                    put("inlineData", inlineDataObj)
                }
            )
        }

        val contentsArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", userPartsArray)
                }
            )
        }

        val systemInstructionObj = JSONObject().apply {
            put(
                "parts",
                JSONArray().apply {
                    put(JSONObject().apply { put("text", SYSTEM_PROMPT) })
                }
            )
        }

        val responseSchemaObj = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "decision",
                        JSONObject().apply {
                            put("type", "STRING")
                            put("enum", JSONArray(listOf("CRASH", "NO_CRASH", "UNCERTAIN")))
                        }
                    )
                    put(
                        "confidence",
                        JSONObject().apply {
                            put("type", "NUMBER")
                        }
                    )
                    put(
                        "reason",
                        JSONObject().apply {
                            put("type", "STRING")
                        }
                    )
                }
            )
            put("required", JSONArray(listOf("decision", "confidence", "reason")))
        }

        val generationConfigObj = JSONObject().apply {
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchemaObj)
        }

        return JSONObject().apply {
            put("systemInstruction", systemInstructionObj)
            put("contents", contentsArray)
            put("generationConfig", generationConfigObj)
        }
    }

    fun parseGeminiResponse(responseBody: String, latencyMs: Long): VisionClassificationResult {
        return try {
            val root = JSONObject(responseBody)
            var contentStr: String? = null

            // 1. Check Gemini candidates array -> content -> parts -> text
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.optJSONObject(0)
                val contentObj = firstCandidate?.optJSONObject("content")
                val partsArray = contentObj?.optJSONArray("parts")
                if (partsArray != null && partsArray.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until partsArray.length()) {
                        val part = partsArray.optJSONObject(i)
                        val text = part?.optString("text")
                        if (!text.isNullOrBlank()) {
                            sb.append(text)
                        }
                    }
                    if (sb.isNotBlank()) {
                        contentStr = sb.toString()
                    }
                }
            }

            // Fallback for direct text
            if (contentStr == null && root.has("text")) {
                contentStr = root.optString("text")
            }

            if (contentStr == null || contentStr.isBlank()) {
                return VisionClassificationResult(
                    decision = VisionDecision.UNCERTAIN,
                    confidence = 0.0f,
                    reason = "No candidate parts found in Gemini response",
                    latencyMs = latencyMs,
                    rawJson = responseBody
                )
            }

            val contentJson = JSONObject(contentStr)

            val rawDecision = contentJson.optString("decision", "UNCERTAIN").uppercase(Locale.US).trim()
            val decision = when (rawDecision) {
                "CRASH" -> VisionDecision.CRASH
                "NO_CRASH" -> VisionDecision.NO_CRASH
                else -> VisionDecision.UNCERTAIN
            }

            val rawConfidence = contentJson.optDouble("confidence", 0.5)
            val confidence = rawConfidence.toFloat().coerceIn(0.0f, 1.0f)
            val reason = contentJson.optString("reason", "").take(250)

            VisionClassificationResult(
                decision = decision,
                confidence = confidence,
                reason = reason,
                latencyMs = latencyMs,
                rawJson = contentStr
            )
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Failed to parse structured Gemini Vision JSON: ${t.message}", t)
            VisionClassificationResult(
                decision = VisionDecision.UNCERTAIN,
                confidence = 0.0f,
                reason = "JSON parse error: ${t.message}",
                latencyMs = latencyMs,
                rawJson = responseBody
            )
        }
    }

    /**
     * Backward-compatible alias for tests and existing callers.
     */
    fun parseOpenAiResponse(responseBody: String, latencyMs: Long): VisionClassificationResult {
        return parseGeminiResponse(responseBody, latencyMs)
    }
}
