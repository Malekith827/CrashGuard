package com.example.crashguard.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.crashguard.data.MainUiState
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
    val context = LocalContext.current

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

    // High-contrast dark theme colors
    val backgroundColor = Color(0xFF121212)
    val cardColor = Color(0xFF1E1E1E)
    val primaryColor = Color(0xFF2E7D32) // Dark Green
    val errorColor = Color(0xFFD32F2F) // Red
    val textColor = Color(0xFFE0E0E0)
    val mutedTextColor = Color(0xFF9E9E9E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header
            Text(
                text = "CrashGuard",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = "Two-Wheeler Continuous Sensor & Event Recorder",
                fontSize = 12.sp,
                color = mutedTextColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // High-Visibility Real-Time Crash Alert Banner
            state.recording.crashAlert?.let { alert ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            PulsingDot(color = Color.Yellow)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CRASH DETECTED",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Stage 1 (Anomaly): " + String.format(Locale.US, "%.3f", alert.stage1Score) + " (>= 0.572)\n" +
                                   "Stage 2 (Causal Fall): " + String.format(Locale.US, "%.3f", alert.stage2Score) + " (>= 0.500)\n" +
                                   alert.details,
                            color = Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.dismissCrashAlert() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("DISMISS ALERT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Sensor Readiness Card
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Sensor Status",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Accelerometer:", color = textColor, fontSize = 14.sp)
                        StatusBadge(
                            isAvailable = state.accel.isAvailable,
                            readyLabel = "READY (${String.format(Locale.US, "%.1f", state.accel.rateHz)} Hz)",
                            unavailableLabel = "UNAVAILABLE"
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Gyroscope:", color = textColor, fontSize = 14.sp)
                        StatusBadge(
                            isAvailable = state.gyro.isAvailable,
                            readyLabel = "READY (${String.format(Locale.US, "%.1f", state.gyro.rateHz)} Hz)",
                            unavailableLabel = "UNAVAILABLE"
                        )
                    }
                }
            }

            // Live Sensor Readings Preview
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Readings",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // Accelerometer Grid
                    Text(text = "Accelerometer (m/s²)", color = mutedTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ValueBox(label = "X", value = state.accel.x, modifier = Modifier.weight(1f))
                        ValueBox(label = "Y", value = state.accel.y, modifier = Modifier.weight(1f))
                        ValueBox(label = "Z", value = state.accel.z, modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Gyroscope Grid
                    Text(text = "Gyroscope (rad/s)", color = mutedTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ValueBox(label = "X", value = state.gyro.x, modifier = Modifier.weight(1f))
                        ValueBox(label = "Y", value = state.gyro.y, modifier = Modifier.weight(1f))
                        ValueBox(label = "Z", value = state.gyro.z, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Active Recording Mode & Event Marking Controls
            if (state.recording.isRecording) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header Status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(bottom = 14.dp)
                        ) {
                            PulsingDot(color = errorColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RIDE RECORDING ACTIVE",
                                color = errorColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        // Statistics Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            RecordingStat(
                                label = "Duration",
                                value = formatDuration(state.recording.durationSeconds)
                            )
                            RecordingStat(
                                label = "Samples",
                                value = "${state.recording.samplesCount}"
                            )
                            RecordingStat(
                                label = "Events",
                                value = "${state.recording.eventMarkersCount}"
                            )
                            RecordingStat(
                                label = "Rate",
                                value = "${String.format(Locale.US, "%.1f", state.accel.rateHz)} Hz"
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Brief Event Confirmation Banner
                        AnimatedVisibility(
                            visible = showConfirmation,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2E7D32))
                                    .padding(vertical = 10.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓ $lastConfirmedEvent MARKED",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Section Title: Event Marking Grid
                        Text(
                            text = "TAP TO MARK EVENT (TIMESTAMP ALIGNED)",
                            color = mutedTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        // 6 Large Event Buttons
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                EventButton(
                                    label = "POTHOLE",
                                    backgroundColor = Color(0xFFE65100),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("POTHOLE") }
                                )
                                EventButton(
                                    label = "SPEED BREAKER",
                                    backgroundColor = Color(0xFFF57F17),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("SPEED_BREAKER") }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                EventButton(
                                    label = "HARD BRAKE",
                                    backgroundColor = Color(0xFFC62828),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("HARD_BRAKE") }
                                )
                                EventButton(
                                    label = "ACCELERATION",
                                    backgroundColor = Color(0xFF00897B),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("ACCELERATION") }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                EventButton(
                                    label = "ROUGH ROAD",
                                    backgroundColor = Color(0xFF6D4C41),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("ROUGH_ROAD") }
                                )
                                EventButton(
                                    label = "OTHER",
                                    backgroundColor = Color(0xFF5E35B1),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.markEvent("OTHER") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Stop Recording Button
                        Button(
                            onClick = { viewModel.stopRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = errorColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("STOP RECORDING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            } else {
                // Setup / Idle Mode
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Data Collection Mode",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = "Continuous sensor data is saved to a raw CSV. Tap event buttons during the ride to log timestamp-aligned event markers.",
                            color = mutedTextColor,
                            fontSize = 13.sp
                        )

                        // Ride Notes Input (Optional)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = "Ride Notes (Optional)", color = mutedTextColor, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            TextField(
                                value = state.recording.notes,
                                onValueChange = { viewModel.updateNotes(it) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF2C2C2C),
                                    unfocusedContainerColor = Color(0xFF2C2C2C),
                                    disabledContainerColor = Color(0xFF2C2C2C),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                placeholder = { Text("e.g. urban route, helmet mount, dry road", color = Color.Gray, fontSize = 13.sp) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Button(
                            onClick = { viewModel.startRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = state.accel.isAvailable && state.gyro.isAvailable
                        ) {
                            Text("START RECORDING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                // Completed Session Export Card
                if (!state.recording.sessionId.isNullOrEmpty() && !state.recording.filePath.isNullOrEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Last Recording Complete",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Session: ${state.recording.sessionId}",
                                color = textColor,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Sensor Rows: ${state.recording.samplesCount} | Events: ${state.recording.eventMarkersCount}",
                                color = mutedTextColor,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    shareSessionFiles(
                                        context,
                                        state.recording.sessionId!!,
                                        state.recording.filePath!!
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("SHARE SESSION FILES", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Camera Verification Overlay downstream of PERSISTENT_ABNORMAL
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
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp),
        modifier = modifier.heightIn(min = 60.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
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
fun StatusBadge(isAvailable: Boolean, readyLabel: String, unavailableLabel: String) {
    val bgColor = if (isAvailable) Color(0xFF2E7D32) else Color(0xFFC62828)
    val text = if (isAvailable) readyLabel else unavailableLabel
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ValueBox(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2C2C2C))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = String.format(Locale.US, "%.3f", value),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RecordingStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 15.sp,
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
            .clip(RoundedCornerShape(5.dp))
            .background(color)
    )
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
