package recorder

import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Ring buffer for MP4 segment files with time and size constraints
 */
class SegmentRingBuffer(
    private val maxDurationSeconds: Int = 120,
    private val maxSizeBytes: Long = 200 * 1024 * 1024,
    private val segmentsDir: File
) {
    private val segments = ConcurrentLinkedDeque<SegmentInfo>()

    init {
        segmentsDir.mkdirs()
    }

    /**
     * Add a new segment and enforce constraints
     */
    @Synchronized
    fun add(segment: SegmentInfo) {
        segments.addLast(segment)
        enforceConstraints()
    }

    /**
     * Clear all segments and delete files
     */
    @Synchronized
    fun clear() {
        segments.forEach { it.file.delete() }
        segments.clear()
    }

    /**
     * Get segments covering the requested duration (most recent)
     * Returns segments sorted by startTime ascending
     * Uses actual time range (not sum of durations) to account for overlaps
     */
    @Synchronized
    fun getSegmentsForDuration(seconds: Int): List<SegmentInfo> {
        val targetDurationMs = seconds * 1000L
        val sorted = segments.sortedBy { it.startTimeMs }

        if (sorted.isEmpty()) return emptyList()

        // Calculate actual time coverage (first start to last end)
        val totalCoverage = sorted.last().endTimeMs - sorted.first().startTimeMs
        if (totalCoverage <= targetDurationMs) {
            return sorted  // Return all if we don't have enough
        }

        // Select segments from the end based on actual time range
        val latestEndTime = sorted.last().endTimeMs
        val targetStartTime = latestEndTime - targetDurationMs

        val result = mutableListOf<SegmentInfo>()

        for (segment in sorted.reversed()) {
            result.add(0, segment)
            // Include all segments that overlap with our target time range
            if (segment.startTimeMs <= targetStartTime) {
                break
            }
        }

        return result
    }

    /**
     * Get B-slot segments only (for simplified concat without overlap handling)
     */
    @Synchronized
    fun getBSlotSegmentsForDuration(seconds: Int): List<SegmentInfo> {
        val targetDurationMs = seconds * 1000L
        val bSlots = segments.filter { it.slot == Slot.B }.sortedBy { it.startTimeMs }

        if (bSlots.isEmpty()) return emptyList()

        val result = mutableListOf<SegmentInfo>()
        var accumulatedDuration = 0L

        for (segment in bSlots.reversed()) {
            result.add(0, segment)
            accumulatedDuration += segment.durationMs
            if (accumulatedDuration >= targetDurationMs) {
                break
            }
        }

        return result
    }

    /**
     * Get the first A-slot segment (for covering initial 0-5 seconds)
     */
    @Synchronized
    fun getFirstASlotSegment(): SegmentInfo? {
        return segments.filter { it.slot == Slot.A }
            .minByOrNull { it.startTimeMs }
    }

    /**
     * Get all segments sorted by start time
     */
    @Synchronized
    fun getAllSegments(): List<SegmentInfo> {
        return segments.sortedBy { it.startTimeMs }
    }

    /**
     * Get total segment count
     */
    fun getSegmentCount(): Int = segments.size

    /**
     * Get total duration in seconds
     */
    fun getTotalDurationSeconds(): Double {
        val sorted = segments.sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return 0.0

        // Calculate effective duration (accounting for overlaps)
        val first = sorted.first()
        val last = sorted.last()
        return (last.endTimeMs - first.startTimeMs) / 1000.0
    }

    /**
     * Get total size in bytes
     */
    fun getTotalSizeBytes(): Long = segments.sumOf { it.sizeBytes }

    /**
     * Get total memory usage in MB
     */
    fun getTotalMemoryMB(): Int = (getTotalSizeBytes() / (1024 * 1024)).toInt()

    /**
     * Get frame count estimate (for UI compatibility)
     * Assumes 30fps average
     */
    fun getFrameCount(): Int = (getTotalDurationSeconds() * 30).toInt()

    /**
     * Enforce time and size constraints by removing oldest segments
     */
    private fun enforceConstraints() {
        // Remove by duration
        while (getTotalDurationSeconds() > maxDurationSeconds && segments.size > 1) {
            val oldest = segments.pollFirst()
            oldest?.file?.delete()
        }

        // Remove by size
        while (getTotalSizeBytes() > maxSizeBytes && segments.size > 1) {
            val oldest = segments.pollFirst()
            oldest?.file?.delete()
        }
    }

    /**
     * Clean up segment directory
     */
    fun cleanup() {
        clear()
        segmentsDir.listFiles()?.forEach { it.delete() }
    }
}
