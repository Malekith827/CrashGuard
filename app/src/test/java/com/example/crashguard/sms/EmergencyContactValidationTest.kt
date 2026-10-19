package com.example.crashguard.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyContactValidationTest {

    @Test
    fun testValidPhoneNumbers_allPass() {
        val validNumbers = listOf(
            "+1234567890",
            "+919876543210",
            "+442079460958",
            "+61491570156",
            "+1 (555) 123-4567",
            "(555) 123-4567",
            "555-123-4567",
            "+91 9876 543210",
            "9876543210",
            "1234567" // 7 digits (min allowed by E.164)
        )

        for (num in validNumbers) {
            assertTrue("Expected valid: $num", PhoneNumberValidator.isValid(num))
        }
    }

    @Test
    fun testInvalidPhoneNumbers_allRejected() {
        val invalidNumbers = listOf(
            "",
            "   ",
            "123", // too short (< 7 digits)
            "+12345", // too short (5 digits)
            "1234567890123456", // too long (> 15 digits)
            "abc1234567", // contains letters
            "+1234abc5678", // contains letters
            "phone number",
            "123+4567890", // '+' not at the beginning
            "++1234567890", // double '+'
            "(555 123-4567", // mismatched opening parenthesis
            "555) 123-4567" // mismatched closing parenthesis
        )

        for (num in invalidNumbers) {
            assertFalse("Expected invalid: $num", PhoneNumberValidator.isValid(num))
        }
    }

    @Test
    fun testNormalizePhoneNumber() {
        assertEquals("+15551234567", PhoneNumberValidator.normalize("+1 (555) 123-4567"))
        assertEquals("+919876543210", PhoneNumberValidator.normalize("+91 9876-543210"))
        assertEquals("5551234567", PhoneNumberValidator.normalize("(555) 123-4567"))
        assertEquals("+442079460958", PhoneNumberValidator.normalize("+44 20 7946 0958"))
    }

    @Test
    fun testMaskPhoneNumber() {
        assertEquals("+***3210", PhoneNumberValidator.mask("+919876543210"))
        assertEquals("***4567", PhoneNumberValidator.mask("5551234567"))
        assertEquals("Not configured", PhoneNumberValidator.mask(null))
        assertEquals("Not configured", PhoneNumberValidator.mask(""))
    }
}
