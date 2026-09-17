package com.example.crashguard.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.crashguard.data.CrashAlertState
import com.example.crashguard.detection.DetectionLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraRecorder(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var currentVideoFile: File? = null

    fun bindPreviewAndRecording(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onBound: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()

                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture
                )

                DetectionLogger.i(TAG, "CameraX preview and video capture bound successfully.")
                onBound?.invoke()
            } catch (t: Throwable) {
                DetectionLogger.e(TAG, "Failed to bind CameraX lifecycle: ${t.message}", t)
                onError?.invoke(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startRecording(sessionId: String, onStarted: ((File) -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        val capture = videoCapture
        if (capture == null) {
            val err = IllegalStateException("VideoCapture is not bound")
            DetectionLogger.e(TAG, err.message ?: "")
            onError?.invoke(err)
            return
        }

        try {
            val recordingsDir = getRecordingsDirectory(context)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFile = File(recordingsDir, "crash_${sessionId}_${timeStamp}.mp4")
            currentVideoFile = videoFile

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            currentRecording = capture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            DetectionLogger.i(TAG, "Video recording started: ${videoFile.absolutePath}")
                            onStarted?.invoke(videoFile)
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (recordEvent.hasError()) {
                                DetectionLogger.e(TAG, "Video recording error code: ${recordEvent.error}")
                            } else {
                                DetectionLogger.i(TAG, "Video recording finalized: ${videoFile.absolutePath}")
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Error starting camera recording: ${t.message}", t)
            onError?.invoke(t)
        }
    }

    fun stopRecording(onStopped: ((File?) -> Unit)? = null) {
        try {
            currentRecording?.stop()
            currentRecording?.close()
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Error stopping recording: ${t.message}", t)
        } finally {
            val file = currentVideoFile
            currentRecording = null
            onStopped?.invoke(file)
        }
    }

    fun release() {
        try {
            stopRecording()
            cameraProvider?.unbindAll()
            cameraProvider = null
            videoCapture = null
            currentVideoFile = null
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Error releasing camera: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "CrashGuardCamera"

        fun getRecordingsDirectory(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "CrashGuard/recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun writeEventMetadata(
            context: Context,
            sessionId: String,
            alert: CrashAlertState,
            outcome: CameraVerificationOutcome,
            videoFilePath: String?
        ): File? {
            return try {
                val dir = getRecordingsDirectory(context)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val metaFile = File(dir, "crash_${sessionId}_${timeStamp}_meta.json")

                val json = JSONObject().apply {
                    put("session_id", sessionId)
                    put("created_at_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
                    put("outcome", outcome.name)
                    put("video_file_path", videoFilePath ?: "")
                    put("imu_trigger_timestamp_ns", alert.timestampNs)
                    put("stage1_score", alert.stage1Score.toDouble())
                    put("stage2_score", alert.stage2Score.toDouble())
                    put("shock_score", alert.shockScore.toDouble())
                    put("temporal_score", alert.temporalScore.toDouble())
                    put("temporal_delay_s", alert.temporalDelayS.toDouble())
                    put("recovery_state", alert.recoveryState)
                    put("recovery_observed_s", alert.recoveryObservedS.toDouble())
                    put("recovery_horizon_s", alert.recoveryHorizonS.toDouble())
                    put("details", alert.details)
                }

                metaFile.writeText(json.toString(2))
                DetectionLogger.i(TAG, "Saved event metadata to ${metaFile.absolutePath}")
                metaFile
            } catch (t: Throwable) {
                DetectionLogger.e(TAG, "Failed to write metadata JSON: ${t.message}", t)
                null
            }
        }
    }
}
