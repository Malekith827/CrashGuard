package com.example.crashguard.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.crashguard.MainActivity
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.detection.DetectionLogger
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Manages high-priority local Android notifications for confirmed motorcycle crashes.
 */
object CrashNotificationManager {

    private const val TAG = "CrashNotification"

    const val CHANNEL_ID = "crashguard_crash_alert_channel"
    const val CHANNEL_NAME = "CrashGuard Incident Alerts"
    const val CHANNEL_DESCRIPTION = "High-priority alerts triggered when a motorcycle crash is confirmed."

    // Track notified sessions to guarantee no duplicate notifications for the same incident
    private val notifiedSessionIds = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Ensures the high-priority crash alert notification channel exists on API 26+.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
                DetectionLogger.i(TAG, "Created notification channel: $CHANNEL_ID")
            }
        }
    }

    /**
     * Displays a high-priority local notification ONLY if the session outcome is CRASH_CONFIRMED.
     * Guaranteed to fire at most once per session ID.
     */
    fun showCrashConfirmedNotification(context: Context, session: CameraVerificationSession) {
        // 1. Strict outcome filter: Only CRASH_CONFIRMED triggers a notification
        if (session.outcome != CameraVerificationOutcome.CRASH_CONFIRMED) {
            DetectionLogger.d(TAG, "Skipping notification for non-crash outcome: ${session.outcome}")
            return
        }

        // 2. Duplicate prevention: Check and record session ID atomically
        if (!notifiedSessionIds.add(session.sessionId)) {
            DetectionLogger.w(TAG, "Duplicate crash notification suppressed for session: ${session.sessionId}")
            return
        }

        // 3. Permission check for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                DetectionLogger.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot display notification.")
                return
            }
        }

        try {
            createNotificationChannel(context)

            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val formattedTime = timeFormat.format(Date())

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                session.sessionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Crash Detected")
                .setContentText("Severe motorcycle crash confirmed at $formattedTime. Tap to open CrashGuard.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Severe motorcycle incident verified by CrashGuard at $formattedTime.\n" +
                        "Stage 1 Score: ${String.format(Locale.US, "%.3f", session.crashAlert.stage1Score)}, " +
                        "Stage 2 Score: ${String.format(Locale.US, "%.3f", session.crashAlert.stage2Score)}\n" +
                        (session.visionResult?.let { "Vision AI: ${it.reason}" } ?: "Manual / automated confirmation.")
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .build()

            val notificationId = 3000 + (Math.abs(session.sessionId.hashCode()) % 10000)
            NotificationManagerCompat.from(context).notify(notificationId, notification)

            DetectionLogger.i(TAG, "High-priority crash notification posted for session ${session.sessionId} (notificationId=$notificationId)")
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Failed to display crash notification: ${t.message}", t)
        }
    }

    /**
     * Resets notified session tracker (useful for test resets).
     */
    fun reset() {
        notifiedSessionIds.clear()
    }

    /**
     * Checks whether a session ID has already been notified.
     */
    fun isSessionNotified(sessionId: String): Boolean {
        return notifiedSessionIds.contains(sessionId)
    }
}
