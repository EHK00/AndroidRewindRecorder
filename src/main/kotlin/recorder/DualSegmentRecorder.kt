package recorder

import config.AppSettings
import config.PathFinder
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dual recorder that runs two screenrecord processes with offset
 * to ensure zero frame loss during segment transitions
 *
 * Segment: 5 seconds, Offset: 2 seconds, Overlap: 3 seconds
 */
class DualSegmentRecorder(
    private val buffer: SegmentRingBuffer,
    private val segmentsDir: File,
    private val segmentDurationSeconds: Int = 5,
    private val slotOffsetSeconds: Int = 2
) {
    private val isRunning = AtomicBoolean(false)
    private val segmentCounterA = AtomicInteger(0)
    private val segmentCounterB = AtomicInteger(0)

    private var slotAJob: Job? = null
    private var slotBJob: Job? = null
    private var scope: CoroutineScope? = null

    private var recordingSize: Int = 720
    private var onSegmentCallback: ((SegmentInfo) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    // Track segment completion for wait functionality
    private val lastCompletedTimeA = AtomicInteger(0)
    private val lastCompletedTimeB = AtomicInteger(0)
    @Volatile private var segmentCompletionListeners = mutableListOf<CompletableDeferred<Unit>>()
    private val listenerLock = Any()

    init {
        segmentsDir.mkdirs()
    }

    /**
     * Start dual recording with specified size
     */
    fun start(
        size: Int,
        onSegmentReceived: (SegmentInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRunning.get()) return

        isRunning.set(true)
        recordingSize = size
        onSegmentCallback = onSegmentReceived
        onErrorCallback = onError

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Start Slot A immediately
        slotAJob = scope?.launch {
            runSlotLoop(Slot.A)
        }

        // Start Slot B after offset
        slotBJob = scope?.launch {
            delay(slotOffsetSeconds * 1000L)
            runSlotLoop(Slot.B)
        }
    }

    /**
     * Stop both recording slots
     */
    fun stop() {
        isRunning.set(false)
        slotAJob?.cancel()
        slotBJob?.cancel()
        scope?.cancel()
        scope = null
    }

    fun isRunning(): Boolean = isRunning.get()

    /**
     * Wait for current recording segments to complete
     * Maximum wait time is one segment duration (5 seconds)
     * After this, buffer will have the most recent completed segments
     */
    suspend fun waitForCurrentSegments(): Unit = withContext(Dispatchers.IO) {
        if (!isRunning.get()) return@withContext

        val deferred = CompletableDeferred<Unit>()

        synchronized(listenerLock) {
            segmentCompletionListeners.add(deferred)
        }

        // Wait for next segment completion or timeout
        try {
            withTimeout((segmentDurationSeconds * 1000L) + 1000L) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout is acceptable - use whatever segments are available
        } finally {
            synchronized(listenerLock) {
                segmentCompletionListeners.remove(deferred)
            }
        }
    }

    /**
     * Notify all listeners that a segment has completed
     */
    private fun notifySegmentCompleted() {
        synchronized(listenerLock) {
            segmentCompletionListeners.forEach { it.complete(Unit) }
            segmentCompletionListeners.clear()
        }
    }

    /**
     * Main loop for a single recording slot
     */
    private suspend fun runSlotLoop(slot: Slot) {
        val counter = if (slot == Slot.A) segmentCounterA else segmentCounterB

        while (isRunning.get()) {
            val index = counter.incrementAndGet()
            val startTime = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date(startTime))
            val segmentFile = File(segmentsDir, "seg_${slot.name}_${timestamp}.mp4")
            val remotePath = "/sdcard/rec_${slot.name}_$index.mp4"

            try {

                // Record segment on device
                val recordSuccess = recordSegment(remotePath, recordingSize)

                if (!recordSuccess) {
                    if (isRunning.get()) {
                        onErrorCallback?.invoke("Recording failed for slot $slot")
                        delay(1000) // Brief delay before retry
                    }
                    continue
                }

                // Pull and cleanup
                val pullSuccess = pullSegment(remotePath, segmentFile)
                cleanupRemote(remotePath)

                if (pullSuccess && segmentFile.exists()) {
                    // Get actual MP4 duration using ffprobe (handles VFR correctly)
                    val actualDuration = getVideoDurationMs(segmentFile)
                        ?: (segmentDurationSeconds * 1000L)  // Fallback to expected duration

                    val segmentInfo = SegmentInfo(
                        file = segmentFile,
                        slot = slot,
                        startTimeMs = startTime,
                        durationMs = actualDuration,
                        sizeBytes = segmentFile.length()
                    )

                    buffer.add(segmentInfo)
                    onSegmentCallback?.invoke(segmentInfo)

                    // Notify waiters that a segment is ready
                    notifySegmentCompleted()
                }

            } catch (e: CancellationException) {
                // Normal cancellation, cleanup
                cleanupRemote(remotePath)
                throw e
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onErrorCallback?.invoke("Error in slot $slot: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    /**
     * Record a segment using adb screenrecord
     * Uses --bugreport option when timestamp overlay is enabled
     * This also fixes VFR issues by ensuring constant frame generation
     */
    private suspend fun recordSegment(remotePath: String, size: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = mutableListOf(
                PathFinder.adbPath,
                "shell",
                "screenrecord",
                "--time-limit", segmentDurationSeconds.toString(),
                "--size", "${size}x${size}",
                "--bit-rate", "3000000"
            )

            command.add("--bugreport")

            command.add(remotePath)

            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Wait for recording to complete (time-limit will auto-stop)
            val exitCode = process.waitFor()

            // Exit code 0 or 1 can be success (1 sometimes means interrupted normally)
            return@withContext exitCode == 0 || exitCode == 1
        } catch (e: Exception) {
            println("Record segment error: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Pull recorded segment from device to local storage
     */
    private suspend fun pullSegment(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                PathFinder.adbPath,
                "pull",
                remotePath,
                localFile.absolutePath
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            return@withContext exitCode == 0 && localFile.exists()
        } catch (e: Exception) {
            println("Pull segment error: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Remove segment file from device
     */
    private suspend fun cleanupRemote(remotePath: String) = withContext(Dispatchers.IO) {
        try {
            ProcessBuilder(
                PathFinder.adbPath,
                "shell",
                "rm",
                "-f",
                remotePath
            ).start().waitFor()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Get actual video duration from MP4 file using ffprobe
     * This correctly handles VFR (Variable Frame Rate) videos
     */
    private fun getVideoDurationMs(file: File): Long? {
        return try {
            val process = ProcessBuilder(
                PathFinder.ffprobePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            // Parse duration in seconds and convert to milliseconds
            output.toDoubleOrNull()?.let { (it * 1000).toLong() }
        } catch (e: Exception) {
            println("ffprobe error: ${e.message}")
            null
        }
    }
}
