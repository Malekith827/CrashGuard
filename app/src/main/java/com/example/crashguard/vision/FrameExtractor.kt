package com.example.crashguard.vision

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.example.crashguard.detection.DetectionLogger
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ExtractedFrame(
    val timestampMs: Long,
    val base64Jpeg: String,
    val width: Int,
    val height: Int
)

object FrameExtractor {

    private const val TAG = "CrashGuardVision"
    private const val MAX_DIMENSION = 640
    private const val JPEG_QUALITY = 80
    private const val REQUIRED_FRAMES_COUNT = 5

    /**
     * Extracts exactly 5 frames from the recorded MP4 file:
     * Timestamps: 0.0s, 0.5s, 1.0s, 1.5s, 2.0s (or evenly spaced if duration < 2.0s).
     * Resizes to 640px longest side and compresses to JPEG 80%.
     */
    fun extractFrames(videoFile: File): List<ExtractedFrame> {
        val exists = videoFile.exists()
        val fileSize = if (exists) videoFile.length() else 0L
        val canRead = if (exists) videoFile.canRead() else false

        DetectionLogger.i(
            TAG,
            "FrameExtractor starting: file=${videoFile.absolutePath}, exists=$exists, size=$fileSize bytes, canRead=$canRead"
        )

        if (!exists || fileSize == 0L || !canRead) {
            DetectionLogger.w(TAG, "Video file is missing, empty, or unreadable: ${videoFile.absolutePath}")
            return emptyList()
        }

        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<ExtractedFrame>()

        try {
            // Prefer FileDescriptor for maximum compatibility across Android OEM media frameworks
            try {
                java.io.FileInputStream(videoFile).use { fis ->
                    retriever.setDataSource(fis.fd)
                }
            } catch (t: Throwable) {
                DetectionLogger.w(TAG, "FileDescriptor setDataSource failed, falling back to absolutePath: ${t.message}")
                retriever.setDataSource(videoFile.absolutePath)
            }

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            DetectionLogger.i(
                TAG,
                "MediaMetadataRetriever metadata: durationStr='$durationStr' (${durationMs}ms), hasVideo='$hasVideo'"
            )

            val safeEndMs = if (durationMs > 100L) {
                (durationMs - 50L).coerceAtLeast(0L)
            } else {
                durationMs.coerceAtLeast(0L)
            }

            val targetTimesMs = if (durationMs >= 2000L) {
                listOf(0L, 500L, 1000L, 1500L, min(2000L, safeEndMs))
            } else if (durationMs > 0L) {
                val step = if (REQUIRED_FRAMES_COUNT > 1) safeEndMs.toDouble() / (REQUIRED_FRAMES_COUNT - 1) else 0.0
                (0 until REQUIRED_FRAMES_COUNT).map { (it * step).toLong().coerceAtMost(safeEndMs) }
            } else {
                // If duration metadata is missing, try default 500ms intervals up to 2.0s
                listOf(0L, 500L, 1000L, 1500L, 2000L)
            }

            for ((idx, timeMs) in targetTimesMs.withIndex()) {
                val timeUs = timeMs * 1000L

                // Multi-tier fallback strategy for decoding frames
                var rawBitmap: Bitmap? = try {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Throwable) {
                    null
                }

                if (rawBitmap == null) {
                    rawBitmap = try {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (e: Throwable) {
                        null
                    }
                }

                if (rawBitmap == null) {
                    rawBitmap = try {
                        retriever.getFrameAtTime(timeUs)
                    } catch (e: Throwable) {
                        null
                    }
                }

                if (rawBitmap == null && idx == 0) {
                    rawBitmap = try {
                        retriever.frameAtTime
                    } catch (e: Throwable) {
                        null
                    }
                }

                if (rawBitmap != null) {
                    val scaledBitmap = scaleBitmap(rawBitmap, MAX_DIMENSION)
                    val base64String = bitmapToBase64(scaledBitmap, JPEG_QUALITY)

                    frames.add(
                        ExtractedFrame(
                            timestampMs = timeMs,
                            base64Jpeg = base64String,
                            width = scaledBitmap.width,
                            height = scaledBitmap.height
                        )
                    )

                    DetectionLogger.i(
                        TAG,
                        "Frame [$idx/$REQUIRED_FRAMES_COUNT] extracted at ${timeMs}ms (${timeUs}us) -> ${scaledBitmap.width}x${scaledBitmap.height}"
                    )

                    if (scaledBitmap != rawBitmap) {
                        scaledBitmap.recycle()
                    }
                    rawBitmap.recycle()
                } else {
                    DetectionLogger.w(
                        TAG,
                        "Frame [$idx/$REQUIRED_FRAMES_COUNT] requested at ${timeMs}ms (${timeUs}us) returned NULL"
                    )
                }
            }

            // If we extracted at least 1 frame but fewer than REQUIRED_FRAMES_COUNT, pad with the last frame
            if (frames.isNotEmpty() && frames.size < REQUIRED_FRAMES_COUNT) {
                DetectionLogger.w(
                    TAG,
                    "Extracted ${frames.size} frames (< $REQUIRED_FRAMES_COUNT). Padding with last frame to fulfill requirement."
                )
                val lastFrame = frames.last()
                while (frames.size < REQUIRED_FRAMES_COUNT) {
                    frames.add(lastFrame.copy(timestampMs = lastFrame.timestampMs + 100L * frames.size))
                }
            }

            DetectionLogger.i(
                TAG,
                "Frames extraction complete: ${frames.size} frames from ${videoFile.name} (duration: ${durationMs}ms, size: $fileSize bytes)"
            )
        } catch (t: Throwable) {
            DetectionLogger.e(TAG, "Failed to extract frames from video: ${t.message}", t)
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                // Ignore release errors
            }
        }

        return frames
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxDim) return bitmap

        val ratio = maxDim.toFloat() / longest
        val newW = (w * ratio).roundToInt().coerceAtLeast(1)
        val newH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
