package com.example.crashguard.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.detection.DetectionLogger
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Result statuses for emergency SMS alert operations.
 */
enum class SmsSendStatus {
    SENT,
    SKIPPED_NO_CONTACT,
    SKIPPED_NOT_CONFIRMED,
    SKIPPED_DUPLICATE,
    SKIPPED_NO_PERMISSION,
    FAILED_EXCEPTION
}

sealed class SmsSendResult {
    data class Success(
        val recipientMasked: String,
        val messageText: String
    ) : SmsSendResult()

    data class Skipped(
        val reason: String,
        val status: SmsSendStatus
    ) : SmsSendResult()

    data class Failure(
        val errorMessage: String,
        val status: SmsSendStatus = SmsSendStatus.FAILED_EXCEPTION,
        val throwable: Throwable? = null
    ) : SmsSendResult()
}

/**
 * Abstraction for sending SMS messages to permit unit testing without hardware/cellular dependencies.
 */
interface SmsSender {
    fun sendSms(
        destinationAddress: String,
        message: String,
        sentIntent: PendingIntent? = null,
        deliveryIntent: PendingIntent? = null
    ): Boolean
}

/**
 * Default Android [SmsSender] utilizing platform [SmsManager].
 */
class AndroidSmsSender(private val context: Context) : SmsSender {
    override fun sendSms(
        destinationAddress: String,
        message: String,
        sentIntent: PendingIntent?,
        deliveryIntent: PendingIntent?
    ): Boolean {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: throw IllegalStateException("Platform SmsManager is unavailable on this device.")

        val parts = smsManager.divideMessage(message)
        if (parts.size > 1) {
            val sentIntents = sentIntent?.let { ArrayList(listOf(it)) }
            val deliveryIntents = deliveryIntent?.let { ArrayList(listOf(it)) }
            smsManager.sendMultipartTextMessage(destinationAddress, null, parts, sentIntents, deliveryIntents)
        } else {
            smsManager.sendTextMessage(destinationAddress, null, message, sentIntent, deliveryIntent)
        }
        return true
    }
}

/**
 * Manages dispatching automatic emergency SMS alerts to a configured contact number
 * upon a confirmed crash.
 */
object EmergencySmsManager {

    private const val TAG = "EmergencySms"

    const val ACTION_SMS_SENT = "com.example.crashguard.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.example.crashguard.SMS_DELIVERED"

    // Deduplication tracker: tracks session IDs that have already triggered an SMS attempt
    private val alertedSessionIds = Collections.synchronizedSet(mutableSetOf<String>())

    // Injectable permission checker for testing
    var permissionChecker: (Context, String) -> Boolean = { ctx, perm ->
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Builds the standard concise emergency message with incident timestamp.
     * Fits cleanly within a single standard 160-character GSM SMS.
     */
    fun buildEmergencyMessage(timestampMs: Long = System.currentTimeMillis()): String {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedTime = timeFormat.format(Date(timestampMs))
        return "CrashGuard alert: A possible two-wheeler crash has been confirmed by the app at $formattedTime. Please try to contact the rider and verify their safety."
    }

    /**
     * Evaluates a resolved verification session and dispatches an emergency SMS if:
     * 1. The session outcome is strictly [CameraVerificationOutcome.CRASH_CONFIRMED].
     * 2. This session has not already been alerted (per-incident deduplication).
     * 3. An emergency contact number is configured and valid.
     * 4. The SEND_SMS runtime permission is granted.
     */
    fun sendCrashAlertSms(
        context: Context,
        session: CameraVerificationSession,
        repository: EmergencyContactRepository = SharedPrefsEmergencyContactRepository(context),
        smsSender: SmsSender = AndroidSmsSender(context)
    ): SmsSendResult {
        // 1. Strict outcome filter: Only CRASH_CONFIRMED triggers an SMS
        if (session.outcome != CameraVerificationOutcome.CRASH_CONFIRMED) {
            DetectionLogger.d(TAG, "Skipping SMS for non-crash outcome: ${session.outcome}")
            return SmsSendResult.Skipped(
                reason = "Outcome is ${session.outcome}, not CRASH_CONFIRMED",
                status = SmsSendStatus.SKIPPED_NOT_CONFIRMED
            )
        }

        // 2. Per-incident deduplication: Ensure each session ID is alerted at most once
        if (!alertedSessionIds.add(session.sessionId)) {
            DetectionLogger.w(TAG, "Duplicate SMS alert suppressed for session: ${session.sessionId}")
            return SmsSendResult.Skipped(
                reason = "Duplicate alert for session ${session.sessionId}",
                status = SmsSendStatus.SKIPPED_DUPLICATE
            )
        }

        // 3. Emergency contact configuration check
        val contactNumber = repository.getEmergencyContact()
        if (contactNumber.isNullOrBlank() || !PhoneNumberValidator.isValid(contactNumber)) {
            DetectionLogger.w(TAG, "No valid emergency contact configured. Skipping SMS alert.")
            return SmsSendResult.Skipped(
                reason = "No emergency contact configured",
                status = SmsSendStatus.SKIPPED_NO_CONTACT
            )
        }

        // 4. Runtime permission check
        if (!permissionChecker(context, Manifest.permission.SEND_SMS)) {
            DetectionLogger.w(TAG, "SEND_SMS permission not granted. Cannot dispatch emergency SMS.")
            return SmsSendResult.Skipped(
                reason = "SEND_SMS permission not granted",
                status = SmsSendStatus.SKIPPED_NO_PERMISSION
            )
        }

        // 5. Build message text
        val messageText = buildEmergencyMessage(session.startedAtMs)
        val maskedNumber = PhoneNumberValidator.mask(contactNumber)

        return try {
            val sentIntent = try {
                val pkg = try { context.packageName } catch (_: Throwable) { null }
                PendingIntent.getBroadcast(
                    context,
                    session.sessionId.hashCode(),
                    Intent(ACTION_SMS_SENT).apply { pkg?.let { setPackage(it) } },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (_: Throwable) {
                null
            }

            val deliveryIntent = try {
                val pkg = try { context.packageName } catch (_: Throwable) { null }
                PendingIntent.getBroadcast(
                    context,
                    session.sessionId.hashCode() + 1,
                    Intent(ACTION_SMS_DELIVERED).apply { pkg?.let { setPackage(it) } },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (_: Throwable) {
                null
            }

            smsSender.sendSms(
                destinationAddress = contactNumber,
                message = messageText,
                sentIntent = sentIntent,
                deliveryIntent = deliveryIntent
            )

            DetectionLogger.i(TAG, "Emergency SMS dispatched to $maskedNumber for session ${session.sessionId}")
            SmsSendResult.Success(
                recipientMasked = maskedNumber,
                messageText = messageText
            )
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Failed to send emergency SMS to $maskedNumber: ${t.message}", t)
            SmsSendResult.Failure(
                errorMessage = t.message ?: "Unknown SMS sending failure",
                throwable = t
            )
        }
    }

    /**
     * Checks whether an incident session ID has already been alerted via SMS.
     */
    fun isSessionAlerted(sessionId: String): Boolean {
        return alertedSessionIds.contains(sessionId)
    }

    /**
     * Resets the deduplication state (useful for test resets).
     */
    fun reset() {
        alertedSessionIds.clear()
        permissionChecker = { ctx, perm ->
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}
