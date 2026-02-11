package recorder

import config.AppSettings
import config.PathFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main controller for Android screen capture using dual segment recording
 * Maintains backward compatibility with existing UI (App.kt)
 */
class AdbScreenCapture {

    private val outputDir = File(AppSettings.outputPath)
    private val segmentsDir = File(outputDir, ".segments")

    // Buffer size based on user setting + 20% margin for safety
    private val bufferDurationSeconds: Int
        get() = (AppSettings.bufferDuration * 1.2).toInt().coerceAtLeast(30)

    // Estimate max size: ~2MB per 5s segment, so bufferDuration/5 * 2MB * 1.5 margin
    private val bufferMaxSizeBytes: Long
        get() = (bufferDurationSeconds / 5L * 2 * 1024 * 1024 * 1.5).toLong()

    // Public buffers for UI compatibility
    val sampleBuffer: SegmentRingBuffer = SegmentRingBuffer(
        maxDurationSeconds = bufferDurationSeconds,
        maxSizeBytes = bufferMaxSizeBytes,
        segmentsDir = segmentsDir
    )

    // Muxer interface for UI compatibility
    val muxer: MuxerInterface = MuxerInterfaceImpl()

    private val concatenator = SegmentConcatenator(outputDir)
    private val recorder = DualSegmentRecorder(
        buffer = sampleBuffer,
        segmentsDir = segmentsDir
        // Uses default: 5s segments, 2s offset, 3s overlap
    )

    init {
        outputDir.mkdirs()
        segmentsDir.mkdirs()
    }

    /**
     * Get currently connected Android device
     */
    suspend fun getConnectedDevice(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                PathFinder.adbPath, "devices"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val lines = output.lines()
            for (line in lines) {
                if (line.contains("\tdevice")) {
                    return@withContext line.split("\t").firstOrNull()?.trim()
                }
            }
            return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }
    }

    /**
     * Get device screen resolution
     */
    fun getDeviceResolution(): Pair<Int, Int>? {
        return try {
            val process = ProcessBuilder(
                PathFinder.adbPath, "shell", "wm", "size"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse "Physical size: 1080x2400"
            val match = Regex("""(\d+)x(\d+)""").find(output)
            if (match != null) {
                val width = match.groupValues[1].toInt()
                val height = match.groupValues[2].toInt()
                Pair(width, height)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculate optimal recording resolution based on device resolution
     * Maintains aspect ratio to avoid black bars (F-2 fix)
     */
    private fun calculateRecordingResolution(resolution: Pair<Int, Int>?): Pair<Int, Int> {
        if (resolution == null) return Pair(720, 1280)

        val (w, h) = resolution
        val minDim = minOf(w, h)
        val targetMinDim = when {
            minDim >= 1440 -> 1080  // QHD+ → FHD
            minDim >= 1080 -> 720   // FHD → HD
            minDim >= 720 -> 540    // HD → qHD
            else -> minDim          // Original
        }

        val scale = targetMinDim.toDouble() / minDim
        // Round to even numbers (required by video encoders)
        val recW = ((w * scale).toInt() / 2) * 2
        val recH = ((h * scale).toInt() / 2) * 2
        return Pair(recW, recH)
    }

    /**
     * Set touch pointer visibility on device
     */
    fun setPointerLocation(enable: Boolean) {
        try {
            val value = if (enable) "1" else "0"
            ProcessBuilder(
                PathFinder.adbPath, "shell",
                "settings", "put", "system", "pointer_location", value
            ).start().waitFor()
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    /**
     * Start capturing with dual recorder
     * Compatible with existing App.kt interface
     */
    suspend fun startCapturing(
        maxSize: Int,
        onSampleReceived: () -> Unit,
        onError: (String) -> Unit
    ) {
        val resolution = getDeviceResolution()
        val (recW, recH) = if (maxSize > 0) {
            // Scale proportionally from maxSize as the short side
            val (w, h) = resolution ?: Pair(maxSize, maxSize)
            val scale = maxSize.toDouble() / minOf(w, h)
            Pair(((w * scale).toInt() / 2) * 2, ((h * scale).toInt() / 2) * 2)
        } else {
            calculateRecordingResolution(resolution)
        }

        recorder.start(
            width = recW,
            height = recH,
            onSegmentReceived = { onSampleReceived() },
            onError = onError
        )
    }

    /**
     * Stop capturing
     */
    fun stopCapturing() {
        recorder.stop()
        sampleBuffer.cleanup()
    }

    /**
     * Save recording of specified duration
     * Waits for current segment to complete (max 5 seconds) before saving
     * Protects segments from deletion during save operation
     */
    suspend fun saveRecording(durationSeconds: Int): String? = withContext(Dispatchers.IO) {
        var bufferSegments: List<SegmentInfo> = emptyList()
        try {
            // Wait for current recording segment to complete (max ~5s wait)
            recorder.waitForCurrentSegments()

            // Get all segments for requested duration
            bufferSegments = sampleBuffer.getSegmentsForDuration(durationSeconds)

            // Protect segments from being deleted during concat
            sampleBuffer.protectSegments(bufferSegments)

            // Use precise concat with overlap trimming
            val outputFile = concatenator.concatPrecise(bufferSegments)

            return@withContext outputFile?.absolutePath
        } catch (e: Exception) {
            println("Save recording error: ${e.message}")
            return@withContext null
        } finally {
            // Always release protection
            if (bufferSegments.isNotEmpty()) {
                sampleBuffer.unprotectSegments(bufferSegments)
            }
        }
    }

    /**
     * Take a screenshot
     */
    suspend fun saveScreenshot(): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDir, "screenshot_$timestamp.png")
            val remotePath = "/sdcard/screenshot_temp.png"

            // Capture screenshot
            val captureProcess = ProcessBuilder(
                PathFinder.adbPath, "shell", "screencap", "-p", remotePath
            ).start()
            captureProcess.waitFor()

            // Pull to local
            val pullProcess = ProcessBuilder(
                PathFinder.adbPath, "pull", remotePath, outputFile.absolutePath
            ).start()
            pullProcess.waitFor()

            // Cleanup
            ProcessBuilder(
                PathFinder.adbPath, "shell", "rm", remotePath
            ).start()

            return@withContext if (outputFile.exists()) outputFile.absolutePath else null
        } catch (e: Exception) {
            return@withContext null
        }
    }

    /**
     * Muxer interface wrapper for UI compatibility
     */
    inner class MuxerInterfaceImpl : MuxerInterface {
        override fun getOutputDirectory(): String = concatenator.getOutputDirectory()

        override fun setOutputDirectory(path: String) {
            concatenator.setOutputDirectory(path)
        }
    }
}

/**
 * Interface for muxer to maintain UI compatibility
 */
interface MuxerInterface {
    fun getOutputDirectory(): String
    fun setOutputDirectory(path: String)
}
