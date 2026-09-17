package com.example.crashguard.data

/**
 * Represents a discrete labeled event marked by the user during continuous sensor recording.
 *
 * @property sessionId The unique session identifier (e.g., session_2026-08-31_194500).
 * @property timestampNs The monotonic sensor timestamp (in nanoseconds) aligned with SensorEvent.timestamp.
 * @property label The event category (e.g., POTHOLE, SPEED_BREAKER, HARD_BRAKE, ACCELERATION, ROUGH_ROAD, OTHER).
 * @property notes Optional contextual notes for the event.
 */
data class EventMarker(
    val sessionId: String,
    val timestampNs: Long,
    val label: String,
    val notes: String = ""
)
