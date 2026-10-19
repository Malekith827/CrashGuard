package com.example.crashguard.sms

import android.content.Context
import android.content.SharedPreferences

/**
 * Validates, normalizes, and masks telephone numbers for Emergency SMS alerting.
 */
object PhoneNumberValidator {

    /**
     * Checks if the phone number string represents a valid telephone number.
     * Supports international numbers with a leading '+' and 7 to 15 digits (ITU E.164).
     */
    fun isValid(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val trimmed = phone.trim()

        // Count digits: E.164 requires between 7 and 15 digits
        val digitCount = trimmed.count { it.isDigit() }
        if (digitCount !in 7..15) return false

        // Must start with optional '+', followed by digits, spaces, hyphens, and parentheses
        val regex = Regex("""^\+?[0-9\s\-\(\)]+$""")
        if (!regex.matches(trimmed)) return false

        // '+' must only be at the first position
        if (trimmed.indexOf('+') > 0) return false

        // Balanced parentheses check if present
        val openCount = trimmed.count { it == '(' }
        val closeCount = trimmed.count { it == ')' }
        if (openCount != closeCount || openCount > 1) return false

        return true
    }

    /**
     * Normalizes the phone number to E.164 format (retains leading '+' if present and strips formatting characters).
     */
    fun normalize(phone: String): String {
        val trimmed = phone.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (hasPlus) "+$digits" else digits
    }

    /**
     * Masks the phone number for secure logging or UI preview, revealing only the last 4 digits.
     */
    fun mask(phone: String?): String {
        if (phone.isNullOrBlank()) return "Not configured"
        val normalized = normalize(phone)
        if (normalized.length <= 4) return "****"
        val visible = normalized.takeLast(4)
        val prefix = if (normalized.startsWith("+")) "+" else ""
        return "$prefix***$visible"
    }
}

/**
 * Contract for persisting and retrieving the user's emergency contact phone number.
 */
interface EmergencyContactRepository {
    fun getEmergencyContact(): String?
    fun saveEmergencyContact(phoneNumber: String): Boolean
    fun clearEmergencyContact(): Boolean
    fun hasEmergencyContact(): Boolean
}

/**
 * SharedPreferences-backed implementation of [EmergencyContactRepository].
 */
class SharedPrefsEmergencyContactRepository(
    context: Context,
    prefsName: String = PREFS_NAME
) : EmergencyContactRepository {

    companion object {
        const val PREFS_NAME = "crashguard_emergency_contact_prefs"
        const val KEY_PHONE_NUMBER = "emergency_contact_phone"
        const val KEY_UPDATED_AT_MS = "emergency_contact_updated_ms"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getEmergencyContact(): String? {
        val raw = prefs.getString(KEY_PHONE_NUMBER, null)
        return if (PhoneNumberValidator.isValid(raw)) raw else null
    }

    override fun saveEmergencyContact(phoneNumber: String): Boolean {
        if (!PhoneNumberValidator.isValid(phoneNumber)) {
            return false
        }
        val normalized = PhoneNumberValidator.normalize(phoneNumber)
        return prefs.edit()
            .putString(KEY_PHONE_NUMBER, normalized)
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
            .commit()
    }

    override fun clearEmergencyContact(): Boolean {
        return prefs.edit()
            .remove(KEY_PHONE_NUMBER)
            .remove(KEY_UPDATED_AT_MS)
            .commit()
    }

    override fun hasEmergencyContact(): Boolean {
        return getEmergencyContact() != null
    }
}
