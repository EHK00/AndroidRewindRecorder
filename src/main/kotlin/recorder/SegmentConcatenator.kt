package recorder

import config.PathFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Concatenates MP4 segments into a single output file using ffmpeg
 */
class SegmentConcatenator(
    private var outputDir: File
) {
    init {
        outputDir.mkdirs()
    }

    fun getOutputDirectory(): String = outputDir.absolutePath

    fun setOutputDirectory(path: String) {
        outputDir = File(path)
        outputDir.mkdirs()
    }

    /**
     * Concatenate segments using simple concat (B-slot focused)
     * For B-slot segments that are already sequential without overlap
     */
    suspend fun concatSimple(
        segments: List<SegmentInfo>,
        firstASegment: SegmentInfo? = null
    ): File? = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext null

        val allSegments = buildList {
            // Add first A segment for initial seconds if available
            firstASegment?.let { add(it) }
            addAll(segments)
        }.sortedBy { it.startTimeMs }

        if (allSegments.isEmpty()) return@withContext null

        val firstStartTime = allSegments.first().startTimeMs

        // Single segment - just copy
        if (allSegments.size == 1) {
            val outputFile = File(outputDir, generateOutputFileName(firstStartTime))
            allSegments[0].file.copyTo(outputFile, overwrite = true)
            return@withContext outputFile
        }

        // Create concat list file
        val concatListFile = File(outputDir, ".concat_list_${System.currentTimeMillis()}.txt")
        try {
            concatListFile.writeText(
                allSegments.joinToString("\n") { "file '${it.file.absolutePath.replace("\\", "/")}'" }
            )

            val outputFile = File(outputDir, generateOutputFileName(firstStartTime))

            // Run ffmpeg concat
            val process = ProcessBuilder(
                PathFinder.ffmpegPath,
                "-f", "concat",
                "-safe", "0",
                "-i", concatListFile.absolutePath,
                "-c", "copy",
                "-y",
                outputFile.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                println("FFmpeg error: $output")
                return@withContext null
            }

            return@withContext outputFile
        } finally {
            concatListFile.delete()
        }
    }

    /**
     * Concatenate with precise trimming to handle overlaps
     * Uses ffmpeg filter_complex for accurate timing
     * Note: screenrecord has no audio, so video-only processing
     */
    suspend fun concatPrecise(segments: List<SegmentInfo>): File? = withContext(Dispatchers.IO) {
        val sorted = segments.sortedBy { it.startTimeMs }

        if (sorted.isEmpty()) return@withContext null

        val firstStartTime = sorted.first().startTimeMs

        // Single segment - just copy
        if (sorted.size == 1) {
            val outputFile = File(outputDir, generateOutputFileName(firstStartTime))
            sorted[0].file.copyTo(outputFile, overwrite = true)
            return@withContext outputFile
        }

        // Build filter complex for trimming and concatenation
        val outputFile = File(outputDir, generateOutputFileName(firstStartTime))
        val inputs = sorted.flatMap { listOf("-i", it.file.absolutePath) }

        val filterParts = mutableListOf<String>()
        val labels = mutableListOf<String>()

        sorted.forEachIndexed { index, segment ->
            val label = "v$index"

            // Calculate overlap with previous segment
            val trimStart = if (index == 0) {
                0.0
            } else {
                val prevEnd = sorted[index - 1].endTimeMs
                val overlap = prevEnd - segment.startTimeMs
                if (overlap > 0) overlap / 1000.0 else 0.0
            }

            // Apply trim filter
            if (trimStart > 0) {
                filterParts.add("[$index:v]trim=start=$trimStart,setpts=PTS-STARTPTS[$label]")
            } else {
                filterParts.add("[$index:v]setpts=PTS-STARTPTS[$label]")
            }
            labels.add("[$label]")
        }

        val filterComplex = filterParts.joinToString(";") +
                ";" + labels.joinToString("") + "concat=n=${sorted.size}:v=1:a=0[outv]"

        val command = mutableListOf(
            PathFinder.ffmpegPath,
            *inputs.toTypedArray(),
            "-filter_complex", filterComplex,
            "-map", "[outv]",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-y",
            outputFile.absolutePath
        )

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            println("FFmpeg precise concat error: $output")
            // Fallback to simple concat (may have duplicates but works)
            return@withContext concatSimple(sorted)
        }

        return@withContext outputFile
    }

    private fun generateOutputFileName(startTimeMs: Long? = null): String {
        val time = if (startTimeMs != null) Date(startTimeMs) else Date()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(time)
        return "recording_$timestamp.mp4"
    }
}
