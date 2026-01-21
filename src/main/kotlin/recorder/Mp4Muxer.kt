package recorder

import config.PathFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * H.264 NAL 유닛들을 MP4로 mux
 *
 * FFmpeg를 사용하여 raw H.264를 MP4 컨테이너로 변환
 * 재인코딩 없이 복사 모드(-c copy) 사용
 */
class Mp4Muxer {

    private val ffmpegPath: String get() = PathFinder.ffmpegPath

    private var outputDir: File = File(System.getProperty("user.home"), "Desktop/AndroidRecordings")
        get() {
            if (!field.exists()) {
                field.mkdirs()
            }
            return field
        }

    /**
     * 출력 디렉토리 설정
     */
    fun setOutputDirectory(path: String): Boolean {
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (dir.isDirectory && dir.canWrite()) {
                outputDir = dir
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 현재 출력 디렉토리 경로 반환
     */
    fun getOutputDirectory(): String = outputDir.absolutePath

    /**
     * NAL 유닛들을 MP4로 mux
     *
     * @param nalUnits NAL 유닛 리스트
     * @param fps 프레임 레이트
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun mux(
        nalUnits: List<H264NalParser.NalUnit>,
        fps: Int
    ): String? = withContext(Dispatchers.IO) {
        if (nalUnits.isEmpty()) {
            println("Mp4Muxer: No NAL units to mux")
            return@withContext null
        }

        // 임시 H.264 파일 생성
        val tempH264 = File.createTempFile("recording_", ".h264")
        tempH264.deleteOnExit()

        try {
            // NAL 유닛들을 H.264 파일로 저장
            FileOutputStream(tempH264).use { fos ->
                for (nalUnit in nalUnits) {
                    fos.write(nalUnit.data)
                }
            }

            println("Mp4Muxer: Written ${nalUnits.size} NAL units to temp file (${tempH264.length()} bytes)")

            // 출력 파일명 생성
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDir, "recording_$timestamp.mp4")

            // FFmpeg로 MP4 변환 (재인코딩 없이)
            val process = ProcessBuilder(
                ffmpegPath,
                "-y",                           // 덮어쓰기
                "-f", "h264",                   // 입력 형식
                "-framerate", fps.toString(),   // 입력 프레임레이트
                "-i", tempH264.absolutePath,    // 입력 파일
                "-c:v", "copy",                 // 비디오 코덱 복사 (재인코딩 없음)
                "-movflags", "+faststart",      // 스트리밍 최적화
                outputFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 && outputFile.exists()) {
                println("Mp4Muxer: Video saved to ${outputFile.absolutePath}")
                outputFile.absolutePath
            } else {
                println("Mp4Muxer: FFmpeg failed with exit code $exitCode")
                println("Mp4Muxer: Output: $output")
                null
            }
        } catch (e: Exception) {
            println("Mp4Muxer: Error - ${e.message}")
            e.printStackTrace()
            null
        } finally {
            tempH264.delete()
        }
    }

    /**
     * NAL 유닛들을 MP4로 mux (PTS 기반 duration 계산)
     *
     * @param nalUnits NAL 유닛 리스트
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun muxWithPts(
        nalUnits: List<H264NalParser.NalUnit>
    ): String? = withContext(Dispatchers.IO) {
        if (nalUnits.isEmpty()) {
            println("Mp4Muxer: No NAL units to mux")
            return@withContext null
        }

        // 프레임 수와 시간으로 실제 FPS 계산
        val frameNals = nalUnits.filter {
            it.type == H264NalParser.NAL_TYPE_IDR || it.type == H264NalParser.NAL_TYPE_SLICE
        }

        if (frameNals.size < 2) {
            println("Mp4Muxer: Not enough frames")
            return@withContext null
        }

        val firstTimestamp = frameNals.first().timestamp
        val lastTimestamp = frameNals.last().timestamp
        val durationNs = lastTimestamp - firstTimestamp

        if (durationNs <= 0) {
            println("Mp4Muxer: Invalid duration")
            return@withContext null
        }

        // 실제 FPS 계산
        val actualFps = ((frameNals.size - 1) * 1_000_000_000.0 / durationNs).toInt().coerceIn(1, 60)
        println("Mp4Muxer: Calculated FPS: $actualFps (${frameNals.size} frames in ${durationNs / 1_000_000}ms)")

        mux(nalUnits, actualFps)
    }

    /**
     * 스크린샷 저장 (단일 프레임을 PNG로)
     * 이 기능은 기존 방식 유지 (adb screencap 사용)
     */
    suspend fun saveScreenshot(imageData: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDir, "screenshot_$timestamp.png")
            outputFile.writeBytes(imageData)
            println("Mp4Muxer: Screenshot saved to ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            println("Mp4Muxer: Screenshot error - ${e.message}")
            null
        }
    }
}
