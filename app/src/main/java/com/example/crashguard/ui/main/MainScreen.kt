package com.example.crashguard.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.crashguard.sms.EmergencySmsManager
import com.example.crashguard.sms.PhoneNumberValidator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.data.IncidentHistoryItem
import com.example.crashguard.data.MainUiState
import com.example.crashguard.data.PipelineDiagnosticsState
import com.example.crashguard.ui.camera.CameraVerificationOverlay
import kotlinx.coroutines.delay
import java.io.File
import java.util.ArrayList
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val emergencyContact by viewModel.emergencyContact.collectAsStateWithLifecycle()
    val emergencyContactInput by viewModel.emergencyContactInput.collectAsStateWithLifecycle()
    val emergencyContactError by viewModel.emergencyContactError.collectAsStateWithLifecycle()
    val emergencyContactSaveSuccess by viewModel.emergencyContactSaveSuccess.collectAsStateWithLifecycle()
    val isSmsPermissionGranted by viewModel.isSmsPermissionGranted.collectAsStateWithLifecycle()
    val testSmsStatus by viewModel.testSmsStatus.collectAsStateWithLifecycle()

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setSmsPermissionGranted(isGranted)
    }

    LaunchedEffect(Unit) {
        viewModel.loadEmergencyContact(context)
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setSmsPermissionGranted(hasPermission)
    }

    // Auto-load incident history when History tab is selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == MainScreenViewModel.TAB_HISTORY) {
            viewModel.loadIncidentHistory(context)
        }
    }

    // Auto-dismiss the "EVENT MARKED" confirmation after 2.5 seconds
    var showConfirmation by remember { mutableStateOf(false) }
    var lastConfirmedEvent by remember { mutableStateOf("") }

    LaunchedEffect(state.recording.lastMarkedEvent, state.recording.lastMarkedTimestampNs) {
        state.recording.lastMarkedEvent?.let { eventName ->
            lastConfirmedEvent = eventName
            showConfirmation = true
            delay(2500)
            showConfirmation = false
            viewModel.clearLastMarkedEvent()
        }
    }

    // Premium dark palette
    val backgroundColor = Color(0xFF0D0F12)
    val cardColor = Color(0xFF161A20)
    val cardBorderColor = Color(0xFF222832)
    val primaryGreen = Color(0xFF00E676)
    val alertRed = Color(0xFFFF3D00)
    val warningOrange = Color(0xFFFF9100)
    val cyanAccent = Color(0xFF00E5FF)
    val textColor = Color(0xFFECEFF1)
    val mutedTextColor = Color(0xFF90A4AE)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // App Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CrashGuard",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PRO",
                                color = Color(0xFF00E5FF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Intelligent Two-Wheeler Crash Safety System",
                        fontSize = 11.sp,
                        color = mutedTextColor
                    )
                }

                // High-level system state pill: Ready / Monitoring / Verifying / No Crash / Crash Confirmed
                val (statusColor, statusLabel) = when {
                    state.recording.cameraVerification?.outcome == CameraVerificationOutcome.CRASH_CONFIRMED -> Pair(alertRed, "CRASH CONFIRMED")
                    state.recording.cameraVerification?.outcome == CameraVerificationOutcome.FALSE_ALARM -> Pair(primaryGreen, "NO CRASH")
                    state.recording.cameraVerification != null -> Pair(warningOrange, "VERIFYING")
                    state.recording.crashAlert != null -> Pair(alertRed, "CRASH TRIGGER")
                    state.recording.isRecording -> Pair(primaryGreen, "MONITORING")
                    else -> Pair(Color(0xFF607D8B), "READY")
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDot(color = statusColor)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Navigation Tabs
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF14181E),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222834)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TabButton(
                        label = "🛡️ Monitor",
                        isSelected = selectedTab == MainScreenViewModel.TAB_MONITOR,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.selectTab(MainScreenViewModel.TAB_MONITOR) }
                    )
                    TabButton(
                        label = "🔬 Diag",
                        isSelected = selectedTab == MainScreenViewModel.TAB_DIAGNOSTICS,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.selectTab(MainScreenViewModel.TAB_DIAGNOSTICS) }
                    )
                    TabButton(
                        label = "📋 History",
                        isSelected = selectedTab == MainScreenViewModel.TAB_HISTORY,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.selectTab(MainScreenViewModel.TAB_HISTORY) }
                    )
                    TabButton(
                        label = "⚙️ Settings",
                        isSelected = selectedTab == MainScreenViewModel.TAB_SETTINGS,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.selectTab(MainScreenViewModel.TAB_SETTINGS) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            when (selectedTab) {
                MainScreenViewModel.TAB_MONITOR -> {
                    MonitorTabView(
                        state = state,
                        viewModel = viewModel,
                        emergencyContact = emergencyContact,
                        isSmsPermissionGranted = isSmsPermissionGranted,
                        cardColor = cardColor,
                        cardBorderColor = cardBorderColor,
                        primaryColor = primaryGreen,
                        errorColor = alertRed,
                        textColor = textColor,
                        mutedTextColor = mutedTextColor,
                        showConfirmation = showConfirmation,
                        lastConfirmedEvent = lastConfirmedEvent,
                        context = context
                    )
                }

                MainScreenViewModel.TAB_DIAGNOSTICS -> {
                    DeveloperDiagnosticsTabView(
                        state = state,
                        cardColor = cardColor,
                        cardBorderColor = cardBorderColor,
                        textColor = textColor,
                        mutedTextColor = mutedTextColor,
                        cyanAccent = cyanAccent
                    )
                }

                MainScreenViewModel.TAB_HISTORY -> {
                    IncidentHistoryTabView(
                        history = state.history,
                        onRefresh = { viewModel.loadIncidentHistory(context) },
                        cardColor = cardColor,
                        cardBorderColor = cardBorderColor,
                        textColor = textColor,
                        mutedTextColor = mutedTextColor
                    )
                }

                MainScreenViewModel.TAB_SETTINGS -> {
                    SettingsTabView(
                        emergencyContact = emergencyContact,
                        contactInput = emergencyContactInput,
                        onContactInputChange = { viewModel.updateEmergencyContactInput(it) },
                        errorMessage = emergencyContactError,
                        isSaveSuccess = emergencyContactSaveSuccess,
                        isSmsPermissionGranted = isSmsPermissionGranted,
                        onRequestPermission = { smsPermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                        onSaveContact = { viewModel.saveEmergencyContact(context) },
                        onClearContact = { viewModel.clearEmergencyContact(context) },
                        onSendTestSms = { viewModel.sendTestSms(context) },
                        testSmsStatus = testSmsStatus,
                        cardColor = cardColor,
                        cardBorderColor = cardBorderColor,
                        primaryColor = primaryGreen,
                        errorColor = alertRed,
                        textColor = textColor,
                        mutedTextColor = mutedTextColor
                    )
                }
            }
        }

        // Camera Verification Overlay
        state.recording.cameraVerification?.let { cameraSession ->
            CameraVerificationOverlay(
                session = cameraSession,
                onOutcomeSelected = { outcome, videoPath ->
                    viewModel.resolveCameraOutcome(outcome, videoPath)
                },
                onDismiss = {
                    viewModel.dismissCameraVerification()
                }
            )
        }
    }
}

@Composable
fun TabButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF263238) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color(0xFF78909C),
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// -------------------------------------------------------------
// TAB 1: MONITOR VIEW
// -------------------------------------------------------------
@Composable
fun MonitorTabView(
    state: MainUiState,
    viewModel: MainScreenViewModel,
    emergencyContact: String?,
    isSmsPermissionGranted: Boolean,
    cardColor: Color,
    cardBorderColor: Color,
    primaryColor: Color,
    errorColor: Color,
    textColor: Color,
    mutedTextColor: Color,
    showConfirmation: Boolean,
    lastConfirmedEvent: String,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Sensor Readiness & Hardware Status
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sensor & System Readiness",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    val allReady = state.accel.isAvailable && state.gyro.isAvailable
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (allReady) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFFFF5252).copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (allReady) "ALL ONLINE" else "OFFLINE",
                            color = if (allReady) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                SystemStatusRow(
                    label = "Accelerometer (3-Axis)",
                    status = if (state.accel.isAvailable) "Ready (${String.format(Locale.US, "%.0f", state.accel.rateHz)} Hz)" else "Unavailable",
                    isReady = state.accel.isAvailable
                )
                Spacer(modifier = Modifier.height(8.dp))
                SystemStatusRow(
                    label = "Gyroscope (Angular Rate)",
                    status = if (state.gyro.isAvailable) "Ready (${String.format(Locale.US, "%.0f", state.gyro.rateHz)} Hz)" else "Unavailable",
                    isReady = state.gyro.isAvailable
                )
                Spacer(modifier = Modifier.height(8.dp))
                SystemStatusRow(
                    label = "Crash Detection Engine",
                    status = if (state.recording.isRecording) "Active (2-Stage Real-Time)" else "Standby",
                    isReady = state.accel.isAvailable
                )
                Spacer(modifier = Modifier.height(8.dp))
                SystemStatusRow(
                    label = "Camera & Vision AI",
                    status = "Gemini 3 Flash Preview (Ready)",
                    isReady = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                SystemStatusRow(
                    label = "Emergency SMS Alert",
                    status = if (emergencyContact != null) {
                        "Ready (${PhoneNumberValidator.mask(emergencyContact)})"
                    } else {
                        "Not Set (Tap Settings)"
                    },
                    isReady = emergencyContact != null && isSmsPermissionGranted
                )
            }
        }

        // Active Ride Monitoring Card
        if (state.recording.isRecording) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        PulsingDot(color = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RIDE MONITORING IN PROGRESS",
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Metrics Strip
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF101318),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2430)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            RecordingStat(label = "Duration", value = formatDuration(state.recording.durationSeconds))
                            RecordingStat(label = "Samples", value = "${state.recording.samplesCount}")
                            RecordingStat(label = "Events", value = "${state.recording.eventMarkersCount}")
                            RecordingStat(label = "Rate", value = "${String.format(Locale.US, "%.0f", state.accel.rateHz)} Hz")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Event Tagging Confirmation Toast
                    AnimatedVisibility(
                        visible = showConfirmation,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF00C853))
                                .padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ $lastConfirmedEvent MARKED",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "MANUAL ROAD EVENT TAGGING",
                        color = mutedTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Event Tag Buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EventButton(label = "POTHOLE", backgroundColor = Color(0xFFE65100), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("POTHOLE") })
                            EventButton(label = "SPEED BREAKER", backgroundColor = Color(0xFFF57F17), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("SPEED_BREAKER") })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EventButton(label = "HARD BRAKE", backgroundColor = Color(0xFFC62828), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("HARD_BRAKE") })
                            EventButton(label = "ACCELERATION", backgroundColor = Color(0xFF00897B), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("ACCELERATION") })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EventButton(label = "ROUGH ROAD", backgroundColor = Color(0xFF5D4037), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("ROUGH_ROAD") })
                            EventButton(label = "OTHER EVENT", backgroundColor = Color(0xFF4527A0), modifier = Modifier.weight(1f), onClick = { viewModel.markEvent("OTHER") })
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = errorColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("STOP MONITORING & RECORDING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        } else {
            // Idle Setup Card
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Start Ride Session",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "Real-time IMU sensing continuously monitors motion. Upon detecting a severe shock or abnormal post-event state, CrashGuard automatically records camera evidence and triggers Gemini Vision AI verification.",
                        color = mutedTextColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    // Optional Notes Input
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "Ride Notes / Route Label (Optional)", color = mutedTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = state.recording.notes,
                            onValueChange = { viewModel.updateNotes(it) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF101318),
                                unfocusedContainerColor = Color(0xFF101318),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color(0xFF00E5FF),
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            placeholder = { Text("e.g. City commute, handlebar mount, highway", color = Color(0xFF546E7A), fontSize = 12.sp) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = { viewModel.startRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = state.accel.isAvailable && state.gyro.isAvailable
                    ) {
                        Text("START RIDE MONITORING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            // Export Card for Last Completed Session
            if (!state.recording.sessionId.isNullOrEmpty() && !state.recording.filePath.isNullOrEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Last Completed Session",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ID: ${state.recording.sessionId}",
                            color = textColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Samples: ${state.recording.samplesCount}  |  Events: ${state.recording.eventMarkersCount}",
                            color = mutedTextColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                shareSessionFiles(
                                    context,
                                    state.recording.sessionId!!,
                                    state.recording.filePath!!
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text("SHARE SESSION FILES", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 2: DEVELOPER DIAGNOSTICS VIEW
// -------------------------------------------------------------
@Composable
fun DeveloperDiagnosticsTabView(
    state: MainUiState,
    cardColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    mutedTextColor: Color,
    cyanAccent: Color
) {
    val diag = state.diagnostics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Live IMU Sensor Telemetry
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Live IMU Sensor Stream", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "${String.format(Locale.US, "%.1f", state.accel.rateHz)} Hz", color = cyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Accelerometer
                Text(
                    text = "Accelerometer (m/s²)  |  Mag: ${String.format(Locale.US, "%.2f", state.accel.magnitude)}",
                    color = mutedTextColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueBox(label = "X", value = state.accel.x, modifier = Modifier.weight(1f))
                    ValueBox(label = "Y", value = state.accel.y, modifier = Modifier.weight(1f))
                    ValueBox(label = "Z", value = state.accel.z, modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Gyroscope
                Text(
                    text = "Gyroscope (rad/s)  |  Mag: ${String.format(Locale.US, "%.2f", state.gyro.magnitude)}",
                    color = mutedTextColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueBox(label = "X", value = state.gyro.x, modifier = Modifier.weight(1f))
                    ValueBox(label = "Y", value = state.gyro.y, modifier = Modifier.weight(1f))
                    ValueBox(label = "Z", value = state.gyro.z, modifier = Modifier.weight(1f))
                }
            }
        }

        // Section 2: Stage 1 Isolation Forest Anomaly Trigger
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Stage 1: Isolation Forest Anomaly Trigger", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))

                DiagRow(label = "Anomaly Score:", value = String.format(Locale.US, "%.4f", diag.s1AnomalyScore), isAlert = diag.s1AnomalyScore >= diag.s1Threshold)
                DiagRow(label = "Deployment Threshold:", value = String.format(Locale.US, ">= %.4f (p95)", diag.s1Threshold))
                DiagRow(label = "Trigger State:", value = if (diag.isS1Triggered) "TRIGGERED" else "IDLE", isAlert = diag.isS1Triggered)
                if (diag.lastS1TriggerTimestampNs > 0) {
                    DiagRow(label = "Last Trigger Timestamp:", value = "${diag.lastS1TriggerTimestampNs} ns")
                }
            }
        }

        // Section 3: Stage 2 Causal Crash Verification
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Stage 2: Causal Crash-Signature Verification", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))

                DiagRow(label = "Composite Crash Score:", value = String.format(Locale.US, "%.4f", diag.s2ContinuousScore), isAlert = diag.s2ContinuousScore >= diag.s2Threshold)
                DiagRow(label = "Decision Threshold:", value = String.format(Locale.US, ">= %.2f", diag.s2Threshold))
                DiagRow(label = "Shock Evidence Score:", value = String.format(Locale.US, "%.3f (weight: 0.30)", diag.s2ShockScore))
                DiagRow(label = "Rotation Evidence Score:", value = String.format(Locale.US, "%.3f (weight: 0.30)", diag.s2RotationScore))
                DiagRow(label = "Accel Damping Score:", value = String.format(Locale.US, "%.3f (weight: 0.25)", diag.s2AccelDampingScore))
                DiagRow(label = "Gyro Damping Score:", value = String.format(Locale.US, "%.3f (weight: 0.15)", diag.s2GyroDampingScore))
                DiagRow(label = "Temporal Sequencing Score:", value = String.format(Locale.US, "%.3f", diag.s2TemporalScore))
                DiagRow(label = "Impact -> Rotation Delay:", value = String.format(Locale.US, "%.3f s", diag.s2TemporalDelayS))
                DiagRow(label = "Detection Latency:", value = String.format(Locale.US, "%.2f s", diag.s2DetectionLatencyS))
                DiagRow(label = "S2 Confirmed:", value = if (diag.isS2Confirmed) "YES" else "NO", isAlert = diag.isS2Confirmed)
            }
        }

        // Section 4: Riding Recovery State Machine
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Experimental Riding Recovery Guard", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))

                val isAbnormal = diag.recoveryState == "PERSISTENT_ABNORMAL"
                DiagRow(label = "Recovery State:", value = diag.recoveryState, isAlert = isAbnormal)
                DiagRow(label = "Observation Horizon:", value = "${String.format(Locale.US, "%.2f", diag.recoveryObservedS)}s / ${String.format(Locale.US, "%.1f", diag.recoveryHorizonS)}s")
                DiagRow(label = "Chunks Observed:", value = "${diag.recoveryChunksObserved}")
                if (diag.recoveryRefAccelStd > 0f) {
                    DiagRow(label = "Reference Accel Std:", value = String.format(Locale.US, "%.4f", diag.recoveryRefAccelStd))
                    DiagRow(label = "Reference Gyro Mean:", value = String.format(Locale.US, "%.4f", diag.recoveryRefGyroMean))
                }
            }
        }

        // Section 5: Camera & Gemini Vision AI Subsystem
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Camera & Gemini Vision AI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))

                DiagRow(label = "Camera State:", value = diag.cameraState)
                DiagRow(label = "Vision Model:", value = diag.visionModel)
                DiagRow(label = "Vision State:", value = diag.visionState)
                DiagRow(label = "Frames Extracted:", value = if (diag.extractedFramesCount > 0) "${diag.extractedFramesCount} frames" else "5 frames (Target)")

                diag.visionDecision?.let { decision ->
                    DiagRow(label = "AI Decision:", value = decision, isAlert = decision == "CRASH")
                }
                diag.visionConfidence?.let { conf ->
                    DiagRow(label = "AI Confidence:", value = String.format(Locale.US, "%.1f%%", conf * 100))
                }
                diag.visionLatencyMs?.let { latency ->
                    DiagRow(label = "API Latency:", value = "${latency} ms")
                }
                diag.visionReason?.let { reason ->
                    if (reason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Reason: $reason", color = Color(0xFFCFD8DC), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 3: INCIDENT HISTORY VIEW
// -------------------------------------------------------------
@Composable
fun IncidentHistoryTabView(
    history: List<IncidentHistoryItem>,
    onRefresh: () -> Unit,
    cardColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    mutedTextColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recorded Incidents (${history.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            TextButton(onClick = onRefresh) {
                Text("REFRESH", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No recorded incidents found", color = Color(0xFF78909C), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Verified crash & false-alarm events will appear here", color = Color(0xFF455A64), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { item ->
                    IncidentCard(item = item, cardColor = cardColor, textColor = textColor, mutedTextColor = mutedTextColor)
                }
            }
        }
    }
}

@Composable
fun IncidentCard(
    item: IncidentHistoryItem,
    cardColor: Color,
    textColor: Color,
    mutedTextColor: Color
) {
    val (badgeBg, badgeText, borderColor) = when (item.outcome) {
        "CRASH_CONFIRMED" -> Triple(Color(0xFFFF3D00), "CRASH CONFIRMED", Color(0xFFFF3D00).copy(alpha = 0.5f))
        "FALSE_ALARM" -> Triple(Color(0xFF00E676), "FALSE ALARM", Color(0xFF00E676).copy(alpha = 0.4f))
        "UNCERTAIN" -> Triple(Color(0xFFFF9100), "UNCERTAIN", Color(0xFFFF9100).copy(alpha = 0.4f))
        else -> Triple(Color.Gray, item.outcome, Color(0xFF37474F))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.timestampIso.take(19).replace("T", " "),
                    color = mutedTextColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg.copy(alpha = 0.15f))
                        .border(1.dp, badgeBg.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = badgeText, color = badgeBg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // IMU metrics
            Text(
                text = "S1: ${String.format(Locale.US, "%.3f", item.stage1Score)}  |  S2: ${String.format(Locale.US, "%.3f", item.stage2Score)}  |  Recovery: ${item.recoveryState}",
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            // Vision AI Reason
            item.visionReason?.let { reason ->
                if (reason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AI Reason (${item.visionConfidence?.let { "${(it * 100).toInt()}%" } ?: ""}): $reason",
                        color = Color(0xFFECEFF1),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Video indicator
            if (!item.videoFilePath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "📹 Video clip: ${File(item.videoFilePath).name}",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// -------------------------------------------------------------
// UI Helper Components
// -------------------------------------------------------------
@Composable
fun SystemStatusRow(label: String, status: String, isReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFFB0BEC5), fontSize = 13.sp)
        Text(
            text = status,
            color = if (isReady) Color(0xFF00E676) else Color(0xFFFF5252),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun DiagRow(label: String, value: String, isAlert: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF78909C), fontSize = 12.sp)
        Text(
            text = value,
            color = if (isAlert) Color(0xFFFF5252) else Color(0xFFECEFF1),
            fontSize = 12.sp,
            fontWeight = if (isAlert) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun EventButton(
    label: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
        modifier = modifier.heightIn(min = 50.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

private fun shareSessionFiles(context: Context, sessionId: String, sensorCsvPath: String) {
    val sensorFile = File(sensorCsvPath)
    val eventsFile = File(sensorCsvPath.replace(".csv", "_events.csv"))
    val jsonFile = File(sensorCsvPath.replace(".csv", ".json"))

    val uris = ArrayList<Uri>()
    try {
        if (sensorFile.exists()) {
            uris.add(FileProvider.getUriForFile(context, "com.example.crashguard.fileprovider", sensorFile))
        }
        if (eventsFile.exists()) {
            uris.add(FileProvider.getUriForFile(context, "com.example.crashguard.fileprovider", eventsFile))
        }
        if (jsonFile.exists()) {
            uris.add(FileProvider.getUriForFile(context, "com.example.crashguard.fileprovider", jsonFile))
        }
    } catch (e: Exception) {
        Log.e("CrashGuard", "Failed to resolve file Uris for sharing: ${e.message}", e)
        return
    }

    if (uris.isEmpty()) {
        Log.w("CrashGuard", "No files found to share for session: $sessionId")
        return
    }

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Session Files ($sessionId)"))
}

@Composable
fun ValueBox(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1F2630))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color(0xFF78909C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = String.format(Locale.US, "%.3f", value),
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RecordingStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF78909C), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

// -------------------------------------------------------------
// TAB 4: SETTINGS VIEW (EMERGENCY CONTACT & SMS)
// -------------------------------------------------------------
@Composable
fun SettingsTabView(
    emergencyContact: String?,
    contactInput: String,
    onContactInputChange: (String) -> Unit,
    errorMessage: String?,
    isSaveSuccess: Boolean,
    isSmsPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onSaveContact: () -> Unit,
    onClearContact: () -> Unit,
    onSendTestSms: () -> Unit,
    testSmsStatus: String?,
    cardColor: Color,
    cardBorderColor: Color,
    primaryColor: Color,
    errorColor: Color,
    textColor: Color,
    mutedTextColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Emergency Contact Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Emergency Contact",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (emergencyContact != null) primaryColor.copy(alpha = 0.15f) else Color(0xFFFF9100).copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (emergencyContact != null) "CONFIGURED" else "NOT SET",
                            color = if (emergencyContact != null) primaryColor else Color(0xFFFF9100),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CrashGuard will automatically send an emergency SMS alert to this number when a crash is confirmed by visual or sensor verification.",
                    fontSize = 12.sp,
                    color = mutedTextColor,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = contactInput,
                    onValueChange = onContactInputChange,
                    label = { Text("Phone Number", color = mutedTextColor) },
                    placeholder = { Text("+1234567890 or +919876543210", color = mutedTextColor.copy(alpha = 0.5f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = cardBorderColor,
                        errorBorderColor = errorColor
                    ),
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage,
                        color = errorColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isSaveSuccess) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "✓ Emergency contact saved successfully.",
                        color = primaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSaveContact,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Save Contact",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    if (emergencyContact != null) {
                        OutlinedButton(
                            onClick = onClearContact,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
                            border = androidx.compose.foundation.BorderStroke(1.dp, errorColor.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Clear",
                                color = errorColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                if (emergencyContact != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Current saved: ${PhoneNumberValidator.mask(emergencyContact)}",
                        fontSize = 11.sp,
                        color = mutedTextColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Card 2: SMS Runtime Permission Status
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SMS Permission",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSmsPermissionGranted) primaryColor.copy(alpha = 0.15f) else Color(0xFFFF5252).copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isSmsPermissionGranted) "GRANTED" else "ACTION REQUIRED",
                            color = if (isSmsPermissionGranted) primaryColor else Color(0xFFFF5252),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Android requires explicit runtime permission to send automatic SMS notifications when a crash is detected in the background.",
                    fontSize = 12.sp,
                    color = mutedTextColor,
                    lineHeight = 16.sp
                )

                if (!isSmsPermissionGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Grant SEND_SMS Permission",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Card 3: Live Message Preview
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Emergency Message Preview",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Standard concise 160-char SMS dispatched on confirmed crashes:",
                    fontSize = 11.sp,
                    color = mutedTextColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0D0F12),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222834)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = EmergencySmsManager.buildEmergencyMessage(),
                        color = Color(0xFFECEFF1),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Card 4: Test SMS Dispatch
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Test SMS Pipeline",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Dispatch a safe non-emergency test SMS to verify that cellular delivery is functioning properly with your carrier.",
                    fontSize = 12.sp,
                    color = mutedTextColor
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onSendTestSms,
                    enabled = emergencyContact != null && isSmsPermissionGranted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Send Test SMS",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                if (testSmsStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = testSmsStatus,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
