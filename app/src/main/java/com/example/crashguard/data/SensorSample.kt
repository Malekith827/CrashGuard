package com.example.crashguard.data

/**
 * Represents a synchronized sample containing readings from both the accelerometer and gyroscope
 * at a specific paired timestamp.
 */
data class SensorSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float
)
