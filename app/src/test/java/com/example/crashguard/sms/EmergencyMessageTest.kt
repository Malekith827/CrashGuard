package com.example.crashguard.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EmergencyMessageTest {

    @Test
    fun testMessageGeneration_containsStandardPhrasing() {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.set(2026, Calendar.OCTOBER, 19, 14, 30, 0)
        val timestampMs = calendar.timeInMillis

        val message = EmergencySmsManager.buildEmergencyMessage(timestampMs)

        // Must identify CrashGuard
        assertTrue("Must contain 'CrashGuard alert'", message.contains("CrashGuard alert"))
        // Must mention confirmed two-wheeler crash
        assertTrue("Must mention 'two-wheeler crash'", message.contains("two-wheeler crash"))
        // Must contain timestamp format (14:30:00)
        assertTrue("Must contain formatted time '14:30:00'", message.contains("14:30:00"))
        // Must request verifying rider safety
        assertTrue("Must ask to contact rider and verify safety", message.contains("Please try to contact the rider and verify their safety"))

        // Length must be 160 characters or fewer for standard single-segment GSM SMS
        assertTrue("Message length (${message.length}) must be <= 160 characters", message.length <= 160)
    }

    @Test
    fun testMessage_doesNotMakeFalseClaims() {
        val message = EmergencySmsManager.buildEmergencyMessage()

        // Must not claim police, ambulance, or 911/112 were contacted
        assertFalse("Must not claim police called", message.lowercase().contains("police"))
        assertFalse("Must not claim ambulance dispatched", message.lowercase().contains("ambulance"))
        assertFalse("Must not claim 911 called", message.contains("911"))
        assertFalse("Must not claim 112 called", message.contains("112"))

        // Must not invent fake GPS coordinates
        assertFalse("Must not invent fake coordinates", message.contains("lat=") || message.contains("lon="))
    }
}
