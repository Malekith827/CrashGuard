package com.example.crashguard.storage

import android.content.Context
import android.util.Log
import com.example.crashguard.data.SensorSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CsvLogger(private val context: Context) {
    private var csvWriter: BufferedWriter? = null
    private var eventsWriter: BufferedWriter? = null
    
    var csvFile: File? = null
        private set
    var eventsFile: File? = null
        private set
    var jsonFile: File? = null
        private set
    
    var sessionId: String = ""
        private set
        
    var rowsWritten = 0
        private set

    var eventMarkersCount = 0
        private set

    private val markedLabelsSet = mutableSetOf<String>()
    private var startTimeIso: String = ""
    private var startTimestampMs: Long = 0L

    companion object {
        private const val TAG = "CrashGuardService"
    }

    /**
     * Prepares the session files:
     * - Sensor CSV: <sessionId>.csv (header: timestamp_ns,ax,ay,az,gx,gy,gz)
     * - Events CSV: <sessionId>_events.csv (header: session_id,event_timestamp_ns,label,notes)
     * - Metadata JSON: <sessionId>.json
     */
    fun startSession(label: String, notes: String): Pair<File, File> {
        rowsWritten = 0
        eventMarkersCount = 0
        markedLabelsSet.clear()
        
        val timeZone = TimeZone.getTimeZone("UTC")
        val sdfFile = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).apply { this.timeZone = timeZone }
        val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { this.timeZone = timeZone }
        
        val now = Date()
        startTimestampMs = now.time
        sessionId = "session_${sdfFile.format(now)}"
        startTimeIso = sdfIso.format(now)

        val baseDir = File(context.getExternalFilesDir(null), "CrashGuard/sessions")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // 1. Initialize Sensor CSV
        val sensorCsv = File(baseDir, "$sessionId.csv")
        csvFile = sensorCsv
        csvWriter = BufferedWriter(FileWriter(sensorCsv))
        csvWriter?.write("timestamp_ns,ax,ay,az,gx,gy,gz\n")
        csvWriter?.flush()

        // 2. Initialize Events CSV
        val eventsCsv = File(baseDir, "${sessionId}_events.csv")
        eventsFile = eventsCsv
        eventsWriter = BufferedWriter(FileWriter(eventsCsv))
        eventsWriter?.write("session_id,event_timestamp_ns,label,notes\n")
        eventsWriter?.flush()

        // 3. Metadata JSON reference
        jsonFile = File(baseDir, "$sessionId.json")

        Log.d(TAG, "CsvLogger started session: $sessionId. Sensor CSV: ${sensorCsv.absolutePath}, Events CSV: ${eventsCsv.absolutePath}")
        return Pair(sensorCsv, eventsCsv)
    }

    /**
     * Appends a single SensorSample row to the continuous sensor CSV stream.
     * Uses Locale.US to guarantee "." is used as a decimal separator.
     */
    fun writeSample(sample: SensorSample) {
        val writer = csvWriter ?: return
        try {
            val line = String.format(
                Locale.US,
                "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                sample.timestampNs,
                sample.ax, sample.ay, sample.az,
                sample.gx, sample.gy, sample.gz
            )
            writer.write(line)
            rowsWritten++
            
            // Flush to disk periodically to prevent data loss
            if (rowsWritten % 50 == 0) {
                writer.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write sensor sample: ${e.message}", e)
        }
    }

    /**
     * Appends an event marker to the companion events CSV stream.
     * Guaranteed immediate flush to ensure rider annotations are persisted synchronously.
     */
    @Synchronized
    fun logEventMarker(label: String, timestampNs: Long, notes: String = ""): Int {
        val writer = eventsWriter ?: return eventMarkersCount
        try {
            val sanitizedNotes = escapeCsv(notes)
            val line = String.format(
                Locale.US,
                "%s,%d,%s,%s\n",
                sessionId,
                timestampNs,
                label,
                sanitizedNotes
            )
            writer.write(line)
            writer.flush()
            eventMarkersCount++
            markedLabelsSet.add(label)
            Log.i(TAG, "Event marker logged: $label at timestampNs=$timestampNs (#$eventMarkersCount)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write event marker: ${e.message}", e)
        }
        return eventMarkersCount
    }

    /**
     * Flushes and closes both CSV streams, then writes the session metadata JSON file.
     */
    fun stopSession(
        label: String,
        notes: String,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        observedSamplingRateHz: Double = 0.0
    ) {
        try {
            csvWriter?.flush()
            csvWriter?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close sensor CSV writer: ${e.message}", e)
        } finally {
            csvWriter = null
        }

        try {
            eventsWriter?.flush()
            eventsWriter?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close events CSV writer: ${e.message}", e)
        } finally {
            eventsWriter = null
        }

        val endTime = Date()
        val endTimeIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(endTime)

        val durationSeconds = if (startTimestampMs > 0) {
            (endTime.time - startTimestampMs) / 1000L
        } else {
            0L
        }

        val labelsJsonArray = markedLabelsSet.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { "\"$it\"" }

        val jsonString = """
            {
              "sessionId": "$sessionId",
              "startTime": "$startTimeIso",
              "endTime": "$endTimeIso",
              "durationSeconds": $durationSeconds,
              "sensorRowsCount": $rowsWritten,
              "eventMarkersCount": $eventMarkersCount,
              "labelsPresent": $labelsJsonArray,
              "rideNotes": "${escapeJson(notes)}",
              "sensorSamplingRequest": "SENSOR_DELAY_GAME",
              "accelerometerAvailable": $accelAvailable,
              "gyroscopeAvailable": $gyroAvailable,
              "actualObservedSamplingRateHz": ${String.format(Locale.US, "%.2f", observedSamplingRateHz)},
              "sensorCsvFile": "${csvFile?.name ?: ""}",
              "eventsCsvFile": "${eventsFile?.name ?: ""}"
            }
        """.trimIndent()

        try {
            jsonFile?.let { file ->
                FileWriter(file).use { writer ->
                    writer.write(jsonString)
                }
                Log.d(TAG, "CsvLogger saved metadata JSON to ${file.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write metadata JSON: ${e.message}", e)
        }
    }

    private fun escapeCsv(input: String): String {
        if (input.contains(",") || input.contains("\"") || input.contains("\n") || input.contains("\r")) {
            return "\"" + input.replace("\"", "\"\"") + "\""
        }
        return input
    }

    private fun escapeJson(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
