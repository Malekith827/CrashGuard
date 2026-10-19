package com.example.crashguard.vision

import android.content.Context
import com.example.crashguard.camera.CameraVerificationSession
import com.example.crashguard.data.RecordingStateHolder
import com.example.crashguard.detection.DetectionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates asynchronous frame extraction, Vision LLM inference, and session updates.
 */
class VisionVerificationManager(
    val classifier: VisionCrashClassifier = VisionCrashClassifier()
) {

    companion object {
        private const val TAG = "CrashGuardVision"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    /**
     * Called when camera recording finishes to analyze the captured video using Vision LLM.
     */
    fun analyzeRecordingAsync(
        context: Context,
        session: CameraVerificationSession,
        videoFile: File,
        onComplete: ((VisionClassificationResult) -> Unit)? = null
    ) {
        scope.launch {
            try {
                // 1. Extract exactly 5 representative frames
                val frames = FrameExtractor.extractFrames(videoFile)
                withContext(Dispatchers.Main) {
                    RecordingStateHolder.updatePipelineDiagnostics { current ->
                        current.copy(extractedFramesCount = frames.size)
                    }
                }

                // 2. Execute Vision LLM classification
                val visionResult = classifier.classifyFrames(frames, session.crashAlert)

                // 3. Delegate outcome directly on Main thread without injecting stale session snapshots
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(visionResult)
                }
            } catch (t: Throwable) {
                DetectionLogger.e(TAG, "Error in vision analysis workflow: ${t.message}", t)
                val fallbackResult = VisionClassificationResult(
                    decision = VisionDecision.UNCERTAIN,
                    confidence = 0.0f,
                    reason = "Analysis workflow error: ${t.message}"
                )
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(fallbackResult)
                }
            }
        }
    }
}
