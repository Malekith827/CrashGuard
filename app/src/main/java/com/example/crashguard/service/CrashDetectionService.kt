package com.example.crashguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.crashguard.MainActivity
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.data.RecordingStateHolder
import com.example.crashguard.data.SensorSample
import com.example.crashguard.detection.CrashDetectionPipeline
import com.example.crashguard.detection.IsolationForestModel
import com.example.crashguard.detection.Stage2Verifier
import com.example.crashguard.storage.CsvLogger
import com.example.crashguard.ui.main.RateCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CrashDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var csvLogger: CsvLogger? = null
    private var pipeline: CrashDetectionPipeline? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var durationJob: Job? = null

    // Notes for the current ride/session
    private var sessionLabel = "NORMAL"
    private var sessionNotes = ""

    // Latest sensor reading values
    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f
    private var lastAccelTimeNs = 0L

    private var lastGx = 0f
    private var lastGy = 0f
    private var lastGz = 0f
    private var lastGyroTimeNs = 0L

    // Latest observed sensor timestamp (in monotonic nanoseconds domain)
    private var lastSensorTimestampNs = 0L

    // Tracks the last written timestamps to prevent duplicate rows
    private var lastLoggedAccelTimeNs = 0L
    private var lastLoggedGyroTimeNs = 0L

    // Sampling frequency calculators
    private val accelRateCalculator = RateCalculator()
    private val gyroRateCalculator = RateCalculator()

    // Sensor synchronization diagnostics tracker
    private val syncDiagnostics = SensorSyncDiagnostics()

    private var accelCount = 0
    private var gyroCount = 0

    companion object {
        private const val TAG = "CrashGuardService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "crashguard_recording_channel"
        private const val CHANNEL_NAME = "CrashGuard Sensor Recording"
        
        const val ACTION_START = "com.example.crashguard.service.action.START"
        const val ACTION_STOP = "com.example.crashguard.service.action.STOP"
        const val ACTION_MARK_EVENT = "com.example.crashguard.service.action.MARK_EVENT"

        const val EXTRA_LABEL = "com.example.crashguard.service.extra.LABEL"
        const val EXTRA_NOTES = "com.example.crashguard.service.extra.NOTES"
        const val EXTRA_EVENT_LABEL = "com.example.crashguard.service.extra.EVENT_LABEL"
        const val EXTRA_EVENT_NOTES = "com.example.crashguard.service.extra.EVENT_NOTES"

        // Maximum allowable synchronization gap: 20 ms
        private const val MAX_PAIRING_GAP_NS = 20_000_000L 
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CrashDetectionService created")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        Log.i(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_START -> {
                // 1. Call startForeground immediately to satisfy OS startup contract
                startForegroundNotification()

                sessionLabel = intent.getStringExtra(EXTRA_LABEL) ?: "NORMAL"
                sessionNotes = intent.getStringExtra(EXTRA_NOTES) ?: ""
                startRecordingSession()
            }
            ACTION_STOP -> {
                stopRecordingSession()
            }
            ACTION_MARK_EVENT -> {
                handleMarkEvent(intent)
            }
        }

        return START_NOT_STICKY
    }

    private fun handleMarkEvent(intent: Intent) {
        val logger = csvLogger
        if (logger == null) {
            Log.w(TAG, "Received mark event request, but recording session is not active")
            return
        }

        val label = intent.getStringExtra(EXTRA_EVENT_LABEL) ?: "OTHER"
        val notes = intent.getStringExtra(EXTRA_EVENT_NOTES) ?: ""

        // Crucial: Use monotonic sensor nanoseconds so event markers match sensor stream timestamps
        val eventTimeNs = if (lastSensorTimestampNs > 0) {
            lastSensorTimestampNs
        } else {
            SystemClock.elapsedRealtimeNanos()
        }

        val totalEvents = logger.logEventMarker(label, eventTimeNs, notes)
        RecordingStateHolder.markEvent(label, eventTimeNs, totalEvents)
        Log.i(TAG, "Marked event '$label' at timestamp $eventTimeNs. Total events: $totalEvents")
    }

    private fun startRecordingSession() {
        if (csvLogger != null) {
            Log.w(TAG, "Recording session already in progress")
            return
        }

        if (accelerometer == null || gyroscope == null) {
            Log.e(TAG, "Cannot start recording: required sensor unavailable")
            RecordingStateHolder.stopRecordingState()
            stopSelf()
            return
        }

        try {
            csvLogger = CsvLogger(this)
            val (sensorFile, eventsFile) = csvLogger!!.startSession(sessionLabel, sessionNotes)
            RecordingStateHolder.startRecordingState(
                sessionId = csvLogger!!.sessionId,
                filePath = sensorFile.absolutePath,
                eventsFilePath = eventsFile.absolutePath
            )

            // Acquire partial wake lock to keep CPU awake during screen lock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrashGuard::RecordingWakeLock").apply {
                acquire()
            }

            // Reset sensor sync states
            lastAccelTimeNs = 0L
            lastGyroTimeNs = 0L
            lastSensorTimestampNs = 0L
            lastLoggedAccelTimeNs = 0L
            lastLoggedGyroTimeNs = 0L
            accelCount = 0
            gyroCount = 0
            accelRateCalculator.reset()
            gyroRateCalculator.reset()
            syncDiagnostics.reset()

            // Setup HandlerThread for background sensor listeners execution
            sensorThread = HandlerThread("SensorLoggingThread").apply {
                start()
                sensorHandler = Handler(looper)
            }

            // Register sensor listeners on dedicated HandlerThread
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, sensorHandler)

            Log.i(TAG, "Sensor listeners registered on background thread")

            // Initialize real-time CrashDetectionPipeline
            try {
                val stage1Model = IsolationForestModel.fromAssets(this)
                pipeline = CrashDetectionPipeline(
                    stage1Model = stage1Model,
                    stage2Verifier = Stage2Verifier(),
                    onCrashDetected = { alert ->
                        Log.e(TAG, "!!! CRASH DETECTED !!! Alert: $alert")
                        RecordingStateHolder.reportCrashAlert(alert)
                    }
                )
                Log.i(TAG, "Initialized real-time CrashDetectionPipeline successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CrashDetectionPipeline: ${e.message}", e)
            }

            // Start duration and sample count update loop
            durationJob = serviceScope.launch {
                var duration = 0L
                while (isActive) {
                    delay(1000)
                    duration++
                    csvLogger?.let {
                        RecordingStateHolder.updateRecordingStats(duration, it.rowsWritten, it.eventMarkersCount)
                    }
                }
            }

            Log.i(TAG, "Recording session started: ${csvLogger?.sessionId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session: ${e.message}", e)
            cleanupOnError()
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays recording status of CrashGuard"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("CrashGuard recording active")
            .setContentText("Collecting accelerometer and gyroscope data")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopRecordingSession() {
        Log.i(TAG, "Stopping recording session")
        
        // Unregister sensor listeners
        sensorManager.unregisterListener(this)

        // Close background thread
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        // Stop duration timer
        durationJob?.cancel()
        durationJob = null

        // Finalize sensor CSV, events CSV, and metadata JSON
        csvLogger?.let {
            it.stopSession(
                label = sessionLabel,
                notes = sessionNotes,
                accelAvailable = accelerometer != null,
                gyroAvailable = gyroscope != null,
                observedSamplingRateHz = accelRateCalculator.getCurrentRate()
            )
            Log.i(TAG, "Session stopped. Rows written: ${it.rowsWritten}, Events logged: ${it.eventMarkersCount}")
        }
        syncDiagnostics.logSummary()
        csvLogger = null

        // Close CrashDetectionPipeline
        try {
            pipeline?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing pipeline: ${e.message}", e)
        }
        pipeline = null

        // Release WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        // Update UI state to stopped
        RecordingStateHolder.stopRecordingState()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupOnError() {
        try {
            sensorManager.unregisterListener(this)
            sensorThread?.quitSafely()
            pipeline?.close()
            csvLogger?.stopSession(
                sessionLabel,
                sessionNotes,
                accelerometer != null,
                gyroscope != null,
                observedSamplingRateHz = accelRateCalculator.getCurrentRate()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error cleaning up on failure: ${t.message}", t)
        } finally {
            pipeline = null
            sensorThread = null
            sensorHandler = null
            csvLogger = null
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            RecordingStateHolder.stopRecordingState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // Maintain latest sensor timestamp in monotonic nanoseconds
        lastSensorTimestampNs = event.timestamp

        val sensorType = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelCount++
                lastAx = event.values[0]
                lastAy = event.values[1]
                lastAz = event.values[2]
                lastAccelTimeNs = event.timestamp
                syncDiagnostics.onAccelReceived(event.timestamp)

                val rate = accelRateCalculator.addTimestamp(event.timestamp)
                RecordingStateHolder.updateAccelerometer(lastAx, lastAy, lastAz, rate)
                "ACCEL"
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroCount++
                lastGx = event.values[0]
                lastGy = event.values[1]
                lastGz = event.values[2]
                lastGyroTimeNs = event.timestamp
                syncDiagnostics.onGyroReceived(event.timestamp)

                val rate = gyroRateCalculator.addTimestamp(event.timestamp)
                RecordingStateHolder.updateGyroscope(lastGx, lastGy, lastGz, rate)
                "GYRO"
            }
            else -> "UNKNOWN"
        }

        // Nearest-neighbor synchronization pairing logic
        if (lastAccelTimeNs > 0 && lastGyroTimeNs > 0) {
            val gap = Math.abs(lastAccelTimeNs - lastGyroTimeNs)

            if (gap <= MAX_PAIRING_GAP_NS) {
                // Ensure we don't pair the same sample points repeatedly
                val isDuplicate = (lastAccelTimeNs == lastLoggedAccelTimeNs || lastGyroTimeNs == lastLoggedGyroTimeNs)
                
                if (!isDuplicate) {
                    val sample = SensorSample(
                        timestampNs = event.timestamp,
                        ax = lastAx,
                        ay = lastAy,
                        az = lastAz,
                        gx = lastGx,
                        gy = lastGy,
                        gz = lastGz
                    )
                    
                    syncDiagnostics.onSamplePaired(
                        triggerSensorType = sensorType,
                        accelTs = lastAccelTimeNs,
                        gyroTs = lastGyroTimeNs,
                        sampleTs = event.timestamp
                    )

                    csvLogger?.writeSample(sample)
                    pipeline?.onSample(sample)
                    
                    lastLoggedAccelTimeNs = lastAccelTimeNs
                    lastLoggedGyroTimeNs = lastGyroTimeNs
                } else {
                    syncDiagnostics.onDuplicateAttempt(lastAccelTimeNs, lastGyroTimeNs)
                }
            } else {
                syncDiagnostics.onGapExceeded(lastAccelTimeNs, lastGyroTimeNs, gap)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action required
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CrashDetectionService destroyed")
        stopRecordingSession()
    }
}
