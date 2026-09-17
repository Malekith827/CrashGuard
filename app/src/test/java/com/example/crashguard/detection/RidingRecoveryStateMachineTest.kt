package com.example.crashguard.detection

import com.example.crashguard.data.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sin

class RidingRecoveryStateMachineTest {

    private val config = RecoveryConfig(
        horizonS = 3.0f,
        chunkS = 0.25f,
        requiredConsecutiveChunks = 2,
        logRatioThreshold = ln(2.0f),
        targetSamplingRateHz = 53.0f
    )
    private val stateMachine = RidingRecoveryStateMachine(config)

    /**
     * Helper to build synthetic sensor stream:
     * - Pre-baseline (1.0s @ 53Hz = 53 samples): sinusoidal riding vibration (a_std ~ 0.707 m/s^2, g_mean = 0.5 rad/s)
     * - Impact core (0.5s @ 53Hz = 27 samples): high acceleration & gyro transient
     * - Post-buffer: configurable post-impact motion profile
     */
    private fun createSyntheticStream(
        postChunkCount: Int,
        postAccelProfile: (chunkIdx: Int, sampleInChunk: Int) -> Float,
        postGyroProfile: (chunkIdx: Int, sampleInChunk: Int) -> Float
    ): Pair<List<SensorSample>, Long> {
        val dtNs = (1_000_000_000.0 / 53.0).toLong()
        var currentTs = 1_000_000_000L
        val samples = ArrayList<SensorSample>()

        val preSamples = 53
        val triggerIdx = preSamples + 13 // Trigger near middle of impact core

        // 1. Pre-baseline (1.0s)
        for (i in 0 until preSamples) {
            val ax = 9.81f + (sin(i * 0.5) * 1.0).toFloat()
            val gx = 0.5f
            samples.add(SensorSample(currentTs, ax, 0f, 0f, gx, 0f, 0f))
            currentTs += dtNs
        }

        // 2. Impact core (0.5s = 27 samples)
        for (i in 0 until 27) {
            val ax = 35.0f
            val gx = 4.5f
            samples.add(SensorSample(currentTs, ax, 0f, 0f, gx, 0f, 0f))
            currentTs += dtNs
        }

        // 3. Post-impact chunks (each chunk = 13 samples @ 53Hz ~ 0.25s)
        val chunkLen = 13
        for (c in 0 until postChunkCount) {
            for (s in 0 until chunkLen) {
                val ax = postAccelProfile(c, s)
                val gx = postGyroProfile(c, s)
                samples.add(SensorSample(currentTs, ax, 0f, 0f, gx, 0f, 0f))
                currentTs += dtNs
            }
        }

        val triggerTs = samples[triggerIdx].timestampNs
        return samples to triggerTs
    }

    @Test
    fun testObviousRecovery_returnsRecovered() {
        // Post-event motion immediately returns to pre-impact riding state
        val (samples, triggerTs) = createSyntheticStream(
            postChunkCount = 4, // 1.0s of post-event data
            postAccelProfile = { _, s -> 9.81f + (sin(s * 0.5) * 1.0).toFloat() },
            postGyroProfile = { _, _ -> 0.5f }
        )

        val result = stateMachine.evaluateRecovery(samples, triggerTs)

        assertEquals("Expected RECOVERED state", RecoveryState.RECOVERED, result.state)
        assertEquals("Expected 2 chunks observed for confirmation", 2, result.chunksObserved)
        assertTrue("Expected observed time around 0.5s", result.observedS in 0.45f..0.55f)
        assertTrue("Expected chunk distances <= ln(2)", result.chunkDistances.all { it <= ln(2.0f) + 1e-4f })
    }

    @Test
    fun testPersistentAbnormal_returnsPersistentAbnormalAfterFullHorizon() {
        // Post-event motion collapses to total stillness (lying on ground: a_std ~ 0.0, g_mean ~ 0.01)
        // Full horizon observed: 14 chunks = 3.5s > 3.0s horizon (159 samples)
        val (samples, triggerTs) = createSyntheticStream(
            postChunkCount = 14,
            postAccelProfile = { _, _ -> 9.81f },
            postGyroProfile = { _, _ -> 0.01f }
        )

        val result = stateMachine.evaluateRecovery(samples, triggerTs)

        assertEquals("Expected PERSISTENT_ABNORMAL state", RecoveryState.PERSISTENT_ABNORMAL, result.state)
        assertEquals("Expected 12 chunks observed (capped at horizon)", 12, result.chunksObserved)
        assertTrue("Expected observed time at full 3.0s horizon", result.observedS >= 2.9f)
    }

    @Test
    fun testInsufficientData_returnsObserving() {
        // Less than full horizon observed (e.g. only 3 chunks = 0.75s) and still abnormal
        val (samples, triggerTs) = createSyntheticStream(
            postChunkCount = 3, // 0.75s (less than 3.0s horizon)
            postAccelProfile = { _, _ -> 9.81f },
            postGyroProfile = { _, _ -> 0.01f }
        )

        val result = stateMachine.evaluateRecovery(samples, triggerTs)

        assertEquals("Expected OBSERVING state when horizon not yet reached", RecoveryState.OBSERVING, result.state)
        assertEquals("Expected 3 chunks observed", 3, result.chunksObserved)
    }

    @Test
    fun testTwoConsecutiveCloseChunksRequirement() {
        // Chunk 0: Close (riding match)
        // Chunk 1: Abnormal (high spin / stillness) -> breaks consecutive run!
        // Chunk 2: Close (riding match) -> run = 1 (not yet recovered!)
        // Chunk 3: Close (riding match) -> run = 2 -> RECOVERED!
        val (samples, triggerTs) = createSyntheticStream(
            postChunkCount = 5,
            postAccelProfile = { c, s ->
                when (c) {
                    0, 2, 3 -> 9.81f + (sin(s * 0.5) * 1.0).toFloat()
                    else -> 9.81f + 15.0f // extreme vibration
                }
            },
            postGyroProfile = { c, _ ->
                when (c) {
                    0, 2, 3 -> 0.5f
                    else -> 5.0f // extreme rotation
                }
            }
        )

        val result = stateMachine.evaluateRecovery(samples, triggerTs)

        assertEquals("Expected RECOVERED on 4th chunk (chunks 2 and 3 consecutive close)", RecoveryState.RECOVERED, result.state)
        assertEquals("Expected 4 chunks observed before recovery", 4, result.chunksObserved)
    }

    @Test
    fun testBoundaryBehaviorAroundLn2() {
        // Test distance boundary around ln(2):
        // Pre-baseline has a_std ~ 0.70 m/s^2, g_mean = 0.5 rad/s
        // Case 1: gyro mean scaled by 3.5x -> dist = 0.5 * ln(3.5) = 0.626 <= ln(2) -> close
        val (samplesInside, triggerTs1) = createSyntheticStream(
            postChunkCount = 2,
            postAccelProfile = { _, s -> 9.81f + (sin(s * 0.5) * 1.0).toFloat() },
            postGyroProfile = { _, _ -> 0.5f * 3.5f }
        )
        val resultInside = stateMachine.evaluateRecovery(samplesInside, triggerTs1)
        assertEquals("Expected RECOVERED when distance is 0.626 <= ln(2)", RecoveryState.RECOVERED, resultInside.state)

        // Case 2: gyro mean scaled by 4.5x -> dist = 0.5 * ln(4.5) = 0.752 > ln(2) -> not close
        val (samplesOutside, triggerTs2) = createSyntheticStream(
            postChunkCount = 2,
            postAccelProfile = { _, s -> 9.81f + (sin(s * 0.5) * 1.0).toFloat() },
            postGyroProfile = { _, _ -> 0.5f * 4.5f }
        )
        val resultOutside = stateMachine.evaluateRecovery(samplesOutside, triggerTs2)
        assertEquals("Expected OBSERVING when distance is 0.752 > ln(2) and horizon not reached", RecoveryState.OBSERVING, resultOutside.state)
    }
}
