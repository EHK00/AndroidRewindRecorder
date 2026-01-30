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
    private val protectedSegments = mutableSetOf<SegmentInfo>()

    init {
        segmentsDir.mkdirs()
    }

    /**
     * Protect segments from being deleted during save operation
     */
    @Synchronized
    fun protectSegments(toProtect: List<SegmentInfo>) {
        protectedSegments.addAll(toProtect)
    }

    /**
     * Release protection after save completes
     */
    @Synchronized
    fun unprotectSegments(toUnprotect: List<SegmentInfo>) {
        protectedSegments.removeAll(toUnprotect.toSet())
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
     * Calculates effective video duration after trimming overlaps
     */
    @Synchronized
    fun getSegmentsForDuration(seconds: Int): List<SegmentInfo> {
        val targetDurationMs = seconds * 1000L
        val sorted = segments.sortedBy { it.startTimeMs }

        if (sorted.isEmpty()) return emptyList()

        // Calculate total effective duration (accounting for overlaps)
        val totalEffectiveDuration = calculateEffectiveDuration(sorted)
        if (totalEffectiveDuration <= targetDurationMs) {
            return sorted  // Return all if we don't have enough
        }

        // Select segments from the end until we have enough effective duration
        val result = mutableListOf<SegmentInfo>()
        var accumulatedDuration = 0L

        for (segment in sorted.reversed()) {
            result.add(0, segment)

            // Calculate effective duration of current selection
            accumulatedDuration = calculateEffectiveDuration(result)

            if (accumulatedDuration >= targetDurationMs) {
                break
            }
        }

        return result
    }

    /**
     * Calculate effective video duration after trimming overlaps
     * This matches the actual output duration from concatPrecise()
     */
    private fun calculateEffectiveDuration(sortedSegments: List<SegmentInfo>): Long {
        if (sortedSegments.isEmpty()) return 0L
        if (sortedSegments.size == 1) return sortedSegments[0].durationMs

        var totalDuration = 0L
        var prevEndTime = 0L

        sortedSegments.forEachIndexed { index, segment ->
            if (index == 0) {
                totalDuration += segment.durationMs
                prevEndTime = segment.endTimeMs
            } else {
                // Calculate overlap with previous segment
                val overlap = maxOf(0L, prevEndTime - segment.startTimeMs)
                // Effective duration = segment duration - overlap
                val effectiveDuration = maxOf(0L, segment.durationMs - overlap)
                totalDuration += effectiveDuration
                prevEndTime = segment.endTimeMs
            }
        }

        return totalDuration
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
     * Get total effective duration in seconds (accounting for overlaps)
     * Used for buffer constraint enforcement - must match getSegmentsForDuration logic
     */
    fun getTotalDurationSeconds(): Double {
        val sorted = segments.sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return 0.0

        // Use effective duration (same as getSegmentsForDuration uses)
        return calculateEffectiveDuration(sorted) / 1000.0
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
     * Protected segments are skipped during removal
     */
    private fun enforceConstraints() {
        // Remove by duration (skip protected segments)
        while (getTotalDurationSeconds() > maxDurationSeconds && segments.size > 1) {
            val oldest = segments.peekFirst()
            if (oldest != null && protectedSegments.contains(oldest)) {
                break  // Stop if oldest is protected
            }
            val removed = segments.pollFirst()
            removed?.file?.delete()
        }

        // Remove by size (skip protected segments)
        while (getTotalSizeBytes() > maxSizeBytes && segments.size > 1) {
            val oldest = segments.peekFirst()
            if (oldest != null && protectedSegments.contains(oldest)) {
                break  // Stop if oldest is protected
            }
            val removed = segments.pollFirst()
            removed?.file?.delete()
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
