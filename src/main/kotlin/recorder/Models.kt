package recorder

import java.io.File

/**
 * Recording slot identifier for dual recorder system
 */
enum class Slot { A, B }

/**
 * Represents a single MP4 segment captured by the recorder
 */
data class SegmentInfo(
    val file: File,
    val slot: Slot,
    val startTimeMs: Long,      // Capture start time (System.currentTimeMillis)
    val durationMs: Long,       // Segment duration (typically 10000ms)
    val sizeBytes: Long
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}
