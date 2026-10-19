package com.example.crashguard.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.crashguard.camera.CameraRecorder
import com.example.crashguard.camera.CameraVerificationManager
import com.example.crashguard.camera.CameraVerificationOutcome
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.vision.VisionDecision
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun CameraPulsingDot(color: Color, size: Int = 10) {
    val infiniteTransition = rememberInfiniteTransition(label = "camera_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(size.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun CameraVerificationOverlay(
    session: CameraVerificationSession,
    onOutcomeSelected: (CameraVerificationOutcome, videoFilePath: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val cameraRecorder = remember { CameraRecorder(context) }
    var recordedVideoFile by remember { mutableStateOf<File?>(null) }
    var isRecordingActive by remember { mutableStateOf(false) }

    // Auto-record a short 2.5s clip for Vision LLM classification
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            delay(2500)
            cameraRecorder.stopRecording { file ->
                isRecordingActive = false
                if (file != null && file.exists() && file.length() > 0L) {
                    recordedVideoFile = file
                    CameraVerificationManager.onVideoRecorded(file)
                } else {
                    com.example.crashguard.detection.DetectionLogger.w(
                        "CrashGuardCamera",
                        "Recording stopped but video file is null, empty, or missing: ${file?.absolutePath}"
                    )
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraRecorder.release()
        }
    }

    val isResolved = session.outcome != CameraVerificationOutcome.PENDING

    LaunchedEffect(isResolved) {
        if (isResolved) {
            delay(1800)
            onDismiss()
        }
    }

    val themeColor = when {
        session.outcome == CameraVerificationOutcome.CRASH_CONFIRMED || (session.visionResult?.decision == VisionDecision.CRASH) -> Color(0xFFFF3D00)
        session.outcome == CameraVerificationOutcome.FALSE_ALARM || (session.visionResult?.decision == VisionDecision.NO_CRASH) -> Color(0xFF00E676)
        session.outcome == CameraVerificationOutcome.UNCERTAIN || (session.visionResult?.decision == VisionDecision.UNCERTAIN) -> Color(0xFFFF9100)
        else -> Color(0xFFFF9100)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14181E)),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, themeColor.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CameraPulsingDot(color = themeColor, size = 12)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isResolved) "INCIDENT VERIFIED" else "CRASH VERIFICATION ACTIVE",
                        color = themeColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = "Automated Multimodal Verification Layer",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )

                // Live Camera Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0C0E))
                        .border(1.dp, Color(0xFF263238), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCameraPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    cameraRecorder.bindPreviewAndRecording(
                                        lifecycleOwner = lifecycleOwner,
                                        previewView = this,
                                        onBound = {
                                            cameraRecorder.startRecording(
                                                sessionId = session.sessionId,
                                                onStarted = { file ->
                                                    recordedVideoFile = file
                                                    isRecordingActive = true
                                                }
                                            )
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isRecordingActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.8f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFF1744))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "RECORDING EVIDENCE (2.5s)",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Camera preview unavailable",
                                color = Color(0xFF78909C),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("GRANT CAMERA PERMISSION", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Vision LLM Analysis Progress Banner (VERIFYING)
                if (session.isAnalyzingVision) {
                    Surface(
                        color = Color(0xFF1E242C),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFFFF9100),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Analyzing camera evidence with Gemini 3 Vision AI...",
                                color = Color(0xFFFFB74D),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else if (session.visionResult != null) {
                    val vision = session.visionResult
                    val (bannerBg, iconText, bannerTitle) = when (vision.decision) {
                        VisionDecision.CRASH -> Triple(Color(0xFFB71C1C), "🚨", "Crash Detected & Confirmed")
                        VisionDecision.NO_CRASH -> Triple(Color(0xFF1B5E20), "✅", "Crash Not Detected (False Alarm)")
                        VisionDecision.UNCERTAIN -> Triple(Color(0xFFE65100), "⚠️", "Unable to Confirm Incident")
                    }

                    Surface(
                        color = bannerBg.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bannerBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = iconText, fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$bannerTitle (${(vision.confidence * 100).toInt()}%)",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (vision.reason.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = vision.reason,
                                    color = Color(0xFFECEFF1),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                // Final Outcome Banner (if resolved)
                if (isResolved) {
                    val (outcomeBg, outcomeText) = when (session.outcome) {
                        CameraVerificationOutcome.CRASH_CONFIRMED -> Pair(Color(0xFFFF3D00), "FINAL OUTCOME: CRASH CONFIRMED")
                        CameraVerificationOutcome.FALSE_ALARM -> Pair(Color(0xFF00E676), "FINAL OUTCOME: FALSE ALARM (DISMISSED)")
                        CameraVerificationOutcome.UNCERTAIN -> Pair(Color(0xFFFF9100), "FINAL OUTCOME: UNCERTAIN")
                        CameraVerificationOutcome.PENDING -> Pair(Color.Gray, "PENDING")
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(outcomeBg.copy(alpha = 0.15f))
                            .border(1.5.dp, outcomeBg, RoundedCornerShape(12.dp))
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = outcomeText,
                            color = outcomeBg,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                } else {
                    Text(
                        text = "Auto-confirmation in ${session.countdownRemainingSeconds}s...",
                        color = Color(0xFFFF9100),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Manual Override Section
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "MANUAL OVERRIDE",
                            color = Color(0xFF78909C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    cameraRecorder.stopRecording { file ->
                                        onOutcomeSelected(CameraVerificationOutcome.CRASH_CONFIRMED, file?.absolutePath ?: recordedVideoFile?.absolutePath)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3D00)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(2.dp)
                            ) {
                                Text("CRASH CONFIRMED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }

                            Button(
                                onClick = {
                                    cameraRecorder.stopRecording { file ->
                                        onOutcomeSelected(CameraVerificationOutcome.FALSE_ALARM, file?.absolutePath ?: recordedVideoFile?.absolutePath)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(2.dp)
                            ) {
                                Text("FALSE ALARM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }

                            OutlinedButton(
                                onClick = {
                                    cameraRecorder.stopRecording { file ->
                                        onOutcomeSelected(CameraVerificationOutcome.UNCERTAIN, file?.absolutePath ?: recordedVideoFile?.absolutePath)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(2.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF546E7A))
                            ) {
                                Text("UNCERTAIN", color = Color(0xFFECEFF1), fontWeight = FontWeight.SemiBold, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
