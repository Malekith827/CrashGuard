package com.example.crashguard.ui.main

import java.util.ArrayDeque

/**
 * Thread-safe class that tracks event timestamps and computes a rolling
 * estimate of the actual sampling frequency (Hz).
 */
class RateCalculator(private val windowSize: Int = 50) {
    private val timestamps = ArrayDeque<Long>(windowSize)

    @Synchronized
    fun addTimestamp(ns: Long): Double {
        timestamps.addLast(ns)
        if (timestamps.size > windowSize) {
            timestamps.removeFirst()
        }
        return getCurrentRate()
    }

    @Synchronized
    fun getCurrentRate(): Double {
        if (timestamps.size < 2) return 0.0
        val durationNs = timestamps.last() - timestamps.first()
        if (durationNs <= 0) return 0.0
        return (timestamps.size - 1) * 1_000_000_000.0 / durationNs
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}
