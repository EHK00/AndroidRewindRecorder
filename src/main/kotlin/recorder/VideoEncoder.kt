package recorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * FFmpeg를 사용하여 프레임들을 동영상으로 인코딩
 */
class VideoEncoder {
    
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
    fun getOutputDirectory(): String {
        return outputDir.absolutePath
    }
    
    /**
     * 프레임들을 MP4 동영상으로 인코딩 (타임스탬프 없이)
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun encode(frames: List<ByteArray>, fps: Int): String? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) {
            println("No frames to encode")
            return@withContext null
        }
        
        // 임시 디렉토리에 프레임 저장
        val tempDir = File(System.getProperty("java.io.tmpdir"), "rewind_recorder_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            println("Encoding ${frames.size} frames at ${fps}fps")
            
            // 프레임들을 임시 PNG 파일로 저장
            frames.forEachIndexed { index, frame ->
                val frameFile = File(tempDir, "frame_%05d.png".format(index))
                frameFile.writeBytes(frame)
            }
            
            // 출력 파일명 생성
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDir, "recording_$timestamp.mp4")
            
            // FFmpeg로 인코딩
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",                               // 덮어쓰기
                "-framerate", fps.toString(),       // 입력 프레임레이트
                "-i", "${tempDir.absolutePath}/frame_%05d.png",  // 입력 패턴
                "-c:v", "libx264",                  // H.264 코덱
                "-preset", "fast",                  // 인코딩 속도
                "-crf", "23",                       // 품질 (낮을수록 좋음)
                "-pix_fmt", "yuv420p",              // 픽셀 포맷
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", // 짝수 해상도로 조정
                outputFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            
            // 프로세스 출력 읽기 (디버깅용)
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && outputFile.exists()) {
                println("Video saved: ${outputFile.absolutePath}")
                outputFile.absolutePath
            } else {
                println("FFmpeg failed with exit code $exitCode")
                println("Output: $output")
                null
            }
        } catch (e: Exception) {
            println("Encoding error: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            // 임시 파일 정리
            tempDir.deleteRecursively()
        }
    }
    
    /**
     * 프레임들을 MP4 동영상으로 인코딩 (타임스탬프 오버레이 포함)
     * @param frames 타임스탬프가 포함된 프레임 리스트
     * @param fps 출력 FPS
     * @param showTimestamp 타임스탬프 오버레이 표시 여부
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun encodeWithTimestamp(
        frames: List<FrameBuffer.TimestampedFrame>,
        fps: Int,
        showTimestamp: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) {
            println("No frames to encode")
            return@withContext null
        }
        
        // 임시 디렉토리에 프레임 저장
        val tempDir = File(System.getProperty("java.io.tmpdir"), "rewind_recorder_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            println("Encoding ${frames.size} frames at ${fps}fps with timestamp overlay")
            
            // 첫 프레임의 타임스탬프 (Unix timestamp, 초 단위)
            val startTimestamp = frames.first().timestamp / 1000
            
            // 프레임들을 임시 PNG 파일로 저장
            frames.forEachIndexed { index, frame ->
                val frameFile = File(tempDir, "frame_%05d.png".format(index))
                frameFile.writeBytes(frame.data)
            }
            
            // 출력 파일명 생성
            val fileTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDir, "recording_$fileTimestamp.mp4")
            
            // 비디오 필터 구성
            val videoFilter = if (showTimestamp) {
                // drawtext 필터로 타임스탬프 오버레이
                // 콜론(:)은 FFmpeg 옵션 구분자와 충돌하므로 점(.)으로 대체
                // 형식: YYYY-MM-DD HH.MM.SS
                val fontPath = "/System/Library/Fonts/Helvetica.ttc"
                val fontFile = File(fontPath)
                
                // FFmpeg drawtext에서 특수문자 이스케이프:
                // - 콜론(:)은 옵션 구분자이므로 시간에 점(.) 사용
                // - 백슬래시와 콜론 조합이 복잡하므로 단순화
                val timeFormat = "%Y-%m-%d %H.%M.%S"
                
                val drawtextFilter = if (fontFile.exists()) {
                    "drawtext=fontfile=$fontPath:" +
                    "text='%{pts\\:localtime\\:$startTimestamp\\:$timeFormat}':" +
                    "fontsize=36:" +
                    "fontcolor=yellow:" +      // 눈에 띄는 노란색
                    "borderw=3:" +
                    "bordercolor=black:" +
                    "x=20:y=100"               // 상태바 아래로 위치 (y=100)
                } else {
                    // 폰트 파일이 없으면 기본 폰트 시도
                    "drawtext=" +
                    "text='%{pts\\:localtime\\:$startTimestamp\\:$timeFormat}':" +
                    "fontsize=36:" +
                    "fontcolor=yellow:" +      // 눈에 띄는 노란색
                    "borderw=3:" +
                    "bordercolor=black:" +
                    "x=20:y=100"               // 상태바 아래로 위치 (y=100)
                }
                
                "scale=trunc(iw/2)*2:trunc(ih/2)*2,$drawtextFilter"
            } else {
                "scale=trunc(iw/2)*2:trunc(ih/2)*2"
            }
            
            // FFmpeg로 인코딩
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",                               // 덮어쓰기
                "-framerate", fps.toString(),       // 입력 프레임레이트
                "-i", "${tempDir.absolutePath}/frame_%05d.png",  // 입력 패턴
                "-c:v", "libx264",                  // H.264 코덱
                "-preset", "fast",                  // 인코딩 속도
                "-crf", "23",                       // 품질 (낮을수록 좋음)
                "-pix_fmt", "yuv420p",              // 픽셀 포맷
                "-vf", videoFilter,                 // 비디오 필터 (스케일 + 타임스탬프)
                outputFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            
            // 프로세스 출력 읽기 (디버깅용)
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && outputFile.exists()) {
                println("Video saved with timestamp: ${outputFile.absolutePath}")
                outputFile.absolutePath
            } else {
                println("FFmpeg failed with exit code $exitCode")
                println("Output: $output")
                null
            }
        } catch (e: Exception) {
            println("Encoding error: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            // 임시 파일 정리
            tempDir.deleteRecursively()
        }
    }
    
    /**
     * FFmpeg 설치 여부 확인
     */
    suspend fun isFFmpegAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
