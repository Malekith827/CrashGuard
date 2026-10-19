package com.example.crashguard.vision

import com.example.crashguard.camera.CameraVerificationManager
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationStateMachine
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.RecoveryState
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class VisionCrashClassifierTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var classifier: VisionCrashClassifier

    private fun sampleAlert(): CrashAlertState {
        return CrashAlertState(
            timestampNs = 1_000_000_000L,
            stage1Score = 0.667f,
            stage2Score = 0.950f,
            shockScore = 1.0f,
            temporalScore = 0.90f,
            temporalDelayS = 0.05f,
            recoveryState = "PERSISTENT_ABNORMAL",
            recoveryObservedS = 3.0f,
            recoveryHorizonS = 3.0f,
            isConfirmed = true,
            details = "S1=0.667, S2=0.950"
        )
    }

    private fun sampleFrames(): List<ExtractedFrame> {
        return listOf(
            ExtractedFrame(timestampMs = 0L, base64Jpeg = "AAAA", width = 640, height = 480),
            ExtractedFrame(timestampMs = 500L, base64Jpeg = "BBBB", width = 640, height = 480),
            ExtractedFrame(timestampMs = 1000L, base64Jpeg = "CCCC", width = 640, height = 480),
            ExtractedFrame(timestampMs = 1500L, base64Jpeg = "DDDD", width = 640, height = 480),
            ExtractedFrame(timestampMs = 2000L, base64Jpeg = "EEEE", width = 640, height = 480)
        )
    }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        classifier = VisionCrashClassifier(
            apiKey = "test-gemini-key-12345",
            modelName = "gemini-3-flash-preview",
            apiEndpoint = mockWebServer.url("/v1beta/models/gemini-3-flash-preview:generateContent").toString(),
            httpClient = okHttpClient
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testCrashResponse_parsedCorrectly() {
        // Valid CRASH response from Gemini
        val jsonResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"decision\":\"CRASH\",\"confidence\":0.94,\"reason\":\"Motorcycle appears on its side and rider is on the road.\"}"
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponseBody))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        val recordedRequest = mockWebServer.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()
        assertEquals("/v1beta/models/gemini-3-flash-preview:generateContent", recordedRequest.path)
        assertEquals("test-gemini-key-12345", recordedRequest.getHeader("x-goog-api-key"))

        // Validate Gemini payload structure
        val reqJson = JSONObject(requestBody)
        assertTrue("Must contain systemInstruction", reqJson.has("systemInstruction"))
        assertTrue("Must contain contents", reqJson.has("contents"))
        assertTrue("Must contain generationConfig", reqJson.has("generationConfig"))

        val generationConfig = reqJson.getJSONObject("generationConfig")
        assertEquals("application/json", generationConfig.getString("responseMimeType"))
        assertTrue("Must contain responseSchema", generationConfig.has("responseSchema"))
        assertFalse("Must not contain temperature in generationConfig", generationConfig.has("temperature"))

        val parts = reqJson.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        // 1 text part + 5 image inlineData parts = 6 parts
        assertEquals(6, parts.length())
        assertTrue("First part must be IMU text", parts.getJSONObject(0).has("text"))

        var imagePartsCount = 0
        for (i in 1 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("inlineData")) {
                val inlineData = part.getJSONObject("inlineData")
                assertEquals("image/jpeg", inlineData.getString("mimeType"))
                assertTrue(inlineData.getString("data").isNotEmpty())
                imagePartsCount++
            }
        }
        assertEquals("Must send exactly 5 inlineData image parts", 5, imagePartsCount)

        assertEquals("Expected CRASH decision", VisionDecision.CRASH, result.decision)
        assertEquals(0.94f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("Motorcycle appears on its side"))
        assertTrue("Expected recorded latency", result.latencyMs >= 0L)
    }

    @Test
    fun testNoCrashResponse_parsedCorrectly() {
        // Valid NO_CRASH response from Gemini
        val jsonResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"decision\":\"NO_CRASH\",\"confidence\":0.91,\"reason\":\"Motorcycle remains upright and rider is riding normally.\"}"
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponseBody))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected NO_CRASH decision", VisionDecision.NO_CRASH, result.decision)
        assertEquals(0.91f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("Motorcycle remains upright"))
    }

    @Test
    fun testUncertainResponse_parsedCorrectly() {
        // Valid UNCERTAIN response from Gemini
        val jsonResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"decision\":\"UNCERTAIN\",\"confidence\":0.50,\"reason\":\"Frames are dark and camera view is obstructed.\"}"
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponseBody))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN decision", VisionDecision.UNCERTAIN, result.decision)
        assertEquals(0.50f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("obstructed"))
    }

    @Test
    fun testMalformedGeminiResponse_returnsUncertainWithoutCrashing() {
        // Malformed / unparseable Gemini response
        val malformedResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "Not a valid JSON response string"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(malformedResponseBody))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN on malformed JSON", VisionDecision.UNCERTAIN, result.decision)
        assertEquals(0.0f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("JSON parse error"))
    }

    @Test
    fun testEmptyCandidatesResponse_returnsUncertainWithoutCrashing() {
        val emptyCandidatesBody = """
        {
          "candidates": []
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(emptyCandidatesBody))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN on empty candidates", VisionDecision.UNCERTAIN, result.decision)
        assertEquals(0.0f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("No candidate parts found"))
    }

    @Test
    fun testApiHttpError_returnsUncertainWithoutCrashing() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN on HTTP 500 error", VisionDecision.UNCERTAIN, result.decision)
        assertEquals(0.0f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("HTTP error 500"))
    }

    @Test
    fun testApiTimeout_returnsUncertainWithoutCrashing() {
        mockWebServer.enqueue(
            MockResponse()
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
                .setBody("{\"candidates\":[]}")
        )

        val result = classifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN on timeout", VisionDecision.UNCERTAIN, result.decision)
        assertEquals(0.0f, result.confidence, 1e-3f)
        assertTrue(result.reason.contains("timeout") || result.reason.contains("Network"))
    }

    @Test
    fun testMissingApiKey_returnsUncertainImmediately() {
        val noKeyClassifier = VisionCrashClassifier(apiKey = "")
        val result = noKeyClassifier.classifyFrames(sampleFrames(), sampleAlert())

        assertEquals("Expected UNCERTAIN when API key is missing", VisionDecision.UNCERTAIN, result.decision)
        assertTrue(result.reason.contains("GEMINI_API_KEY missing"))
    }

    @Test
    fun testManualOverride_winsOverVisionDecision() {
        val stateMachine = CameraVerificationStateMachine()
        val alert = sampleAlert()

        stateMachine.onImuRecoveryState(RecoveryState.PERSISTENT_ABNORMAL, alert)

        // User manually taps FALSE_ALARM
        stateMachine.onOutcomeSelected(CameraVerificationOutcome.FALSE_ALARM)

        assertEquals(CameraVerificationOutcome.FALSE_ALARM, stateMachine.currentSession?.outcome)
    }
}
