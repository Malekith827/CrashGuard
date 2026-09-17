package com.example.crashguard.service

import com.example.crashguard.data.SensorSample
import java.util.ArrayDeque

/**
 * High-performance circular buffer for real-time IMU sensor streams.
 * Thread-safe for multi-threaded sensor acquisition and inference.
 *
 * @param capacity Maximum number of samples retained in the buffer (default 300 ~ 5.6s @ 53Hz).
 */
class SensorRingBuffer(val capacity: Int = 300) {

    private val deque = ArrayDeque<SensorSample>(capacity)

    @Synchronized
    fun add(sample: SensorSample) {
        if (deque.size >= capacity) {
            deque.removeFirst()
        }
        deque.addLast(sample)
    }

    @Synchronized
    fun size(): Int = deque.size

    @Synchronized
    fun isEmpty(): Boolean = deque.isEmpty()

    @Synchronized
    fun clear() {
        deque.clear()
    }

    /**
     * Returns the total time span in nanoseconds between the newest and oldest sample.
     */
    @Synchronized
    fun getDurationNs(): Long {
        if (deque.size < 2) return 0L
        return deque.last.timestampNs - deque.first.timestampNs
    }

    /**
     * Returns an immutable snapshot of all samples currently in the buffer.
     */
    @Synchronized
    fun getSnapshot(): List<SensorSample> {
        return ArrayList(deque)
    }

    /**
     * Returns an immutable snapshot of samples spanning at least [durationNs] up to the newest sample.
     */
    @Synchronized
    fun getSnapshotSpan(durationNs: Long): List<SensorSample> {
        if (deque.isEmpty()) return emptyList()
        val latestTs = deque.last.timestampNs
        val targetStartTs = latestTs - durationNs
        val result = ArrayList<SensorSample>()
        for (sample in deque) {
            if (sample.timestampNs >= targetStartTs) {
                result.add(sample)
            }
        }
        return result
    }

    /**
     * Returns an immutable snapshot of samples within a specific nanosecond interval [startTsNs, endTsNs].
     */
    @Synchronized
    fun getSnapshotRange(startTsNs: Long, endTsNs: Long): List<SensorSample> {
        val result = ArrayList<SensorSample>()
        for (sample in deque) {
            if (sample.timestampNs in startTsNs..endTsNs) {
                result.add(sample)
            }
        }
        return result
    }
}
