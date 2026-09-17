package com.example.crashguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.crashguard.data.RecordingStateHolder
import com.example.crashguard.service.CrashDetectionService
import com.example.crashguard.theme.CrashGuardTheme
import com.example.crashguard.ui.main.MainScreenViewModel
import com.example.crashguard.ui.main.RateCalculator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var viewModel: MainScreenViewModel

    private var isResumed = false
    private var isLocalSensorRegistered = false

    private var pendingLabel: String? = null
    private var pendingNotes: String? = null

    // Rate calculators for live preview mode (when idle)
    private val localAccelRateCalculator = RateCalculator()
    private val localGyroRateCalculator = RateCalculator()

    companion object {
        private const val TAG = "CrashGuard"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SensorManager and check for sensor availability
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Initialize ViewModel shared with the Compose UI
        viewModel = ViewModelProvider(this)[MainScreenViewModel::class.java]
        
        // Push initial availability state
        RecordingStateHolder.updateSensorsAvailable(accelerometer != null, gyroscope != null)

        // Wire start/stop/mark recording lambdas from ViewModel to Activity
        viewModel.startRecordingTrigger = { label, notes ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingLabel = label
                pendingNotes = notes
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            } else {
                startLoggingService(label, notes)
            }
        }
        viewModel.stopRecordingTrigger = {
            stopLoggingService()
        }
        viewModel.markEventTrigger = { label, notes ->
            markEventInService(label, notes)
        }

        // Reactively manage local sensor listeners depending on the recording state
        lifecycleScope.launch {
            RecordingStateHolder.uiState.collect { state ->
                if (state.recording.isRecording) {
                    // Unregister local listeners so the service is the sole sensor listener
                    unregisterLocalSensors()
                } else {
                    // Register local listeners for live preview if the activity is in focus
                    if (isResumed) {
                        registerLocalSensors()
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            CrashGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavigation(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        // Only start local sensors if we are not currently recording
        if (!RecordingStateHolder.uiState.value.recording.isRecording) {
            registerLocalSensors()
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        // Always unregister local listeners when activity is paused to save battery
        unregisterLocalSensors()
    }

    private fun registerLocalSensors() {
        if (isLocalSensorRegistered) return
        localAccelRateCalculator.reset()
        localGyroRateCalculator.reset()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        isLocalSensorRegistered = true
        Log.d(TAG, "Registered local sensor listeners for live preview")
    }

    private fun unregisterLocalSensors() {
        if (!isLocalSensorRegistered) return
        sensorManager.unregisterListener(this)
        isLocalSensorRegistered = false
        Log.d(TAG, "Unregistered local sensor listeners")
    }

    private fun startLoggingService(label: String, notes: String) {
        Log.i(TAG, "Starting foreground logging service with label: $label")
        val intent = Intent(this, CrashDetectionService::class.java).apply {
            action = CrashDetectionService.ACTION_START
            putExtra(CrashDetectionService.EXTRA_LABEL, label)
            putExtra(CrashDetectionService.EXTRA_NOTES, notes)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLoggingService() {
        Log.i(TAG, "Stopping foreground logging service")
        val intent = Intent(this, CrashDetectionService::class.java).apply {
            action = CrashDetectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun markEventInService(label: String, notes: String) {
        Log.i(TAG, "Dispatching mark event intent for: $label")
        val intent = Intent(this, CrashDetectionService::class.java).apply {
            action = CrashDetectionService.ACTION_MARK_EVENT
            putExtra(CrashDetectionService.EXTRA_EVENT_LABEL, label)
            putExtra(CrashDetectionService.EXTRA_EVENT_NOTES, notes)
        }
        startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val label = pendingLabel ?: "NORMAL"
                val notes = pendingNotes ?: ""
                startLoggingService(label, notes)
            } else {
                Log.w(TAG, "Notification permission denied. Cannot start service.")
                // Tell ViewModel to reset recording state
                viewModel.stopRecording()
            }
            pendingLabel = null
            pendingNotes = null
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        // This is only active during live preview (not recording)
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val rate = localAccelRateCalculator.addTimestamp(event.timestamp)
                RecordingStateHolder.updateAccelerometer(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    rateHz = rate
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                val rate = localGyroRateCalculator.addTimestamp(event.timestamp)
                RecordingStateHolder.updateGyroscope(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    rateHz = rate
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action required
    }
}
