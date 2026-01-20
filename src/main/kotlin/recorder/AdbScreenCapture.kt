package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 캡처 모드
 */
enum class CaptureMode {
    SCREENCAP,      // 기존 방식: 개별 스크린샷 (저FPS, 호환성 높음)
    SCREENRECORD    // 새 방식: H.264 스트림 (고FPS, Android 4.4+)
}

class AdbScreenCapture {

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 듀얼 스트림 관리
    private var streamDecoderA: StreamDecoder? = null
    private var streamDecoderB: StreamDecoder? = null
    private var streamSchedulerJob: Job? = null
    private val isStreaming = AtomicBoolean(false)

    // 현재 캡처 모드
    private var currentMode = CaptureMode.SCREENCAP

    /**
     * 연결된 Android 디바이스 ID를 반환
     */
    suspend fun getConnectedDevice(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()

            // "List of devices attached" 다음 줄에서 device ID 찾기
            lines.drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("device") && !it.contains("unauthorized") }
                .firstOrNull()
                ?.split("\\s+".toRegex())
                ?.firstOrNull()
        } catch (e: Exception) {
            println("Error getting device: ${e.message}")
            null
        }
    }

    /**
     * ADB 경로 (PathFinder에서 가져옴)
     */
    val adbPath: String get() = PathFinder.adbPath

    /**
     * Android 버전이 screenrecord를 지원하는지 확인 (4.4+)
     */
    suspend fun isScreenrecordSupported(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(adbPath, "shell", "getprop", "ro.build.version.sdk")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sdkVersion = reader.readLine()?.trim()?.toIntOrNull() ?: 0
            process.waitFor()

            sdkVersion >= 19 // Android 4.4 = API 19
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 스크린 캡처 시작
     * @param fps 프레임 레이트
     * @param mode 캡처 모드 (SCREENCAP 또는 SCREENRECORD)
     * @param onFrame 프레임 콜백
     */
    fun startCapturing(
        fps: Int,
        mode: CaptureMode = CaptureMode.SCREENRECORD,
        onFrame: (ByteArray) -> Unit
    ) {
        stopCapturing()
        currentMode = mode

        when (mode) {
            CaptureMode.SCREENCAP -> startScreencapMode(fps, onFrame)
            CaptureMode.SCREENRECORD -> startScreenrecordMode(fps, onFrame)
        }
    }

    /**
     * 기존 screencap 방식 (저FPS, 호환성)
     */
    private fun startScreencapMode(fps: Int, onFrame: (ByteArray) -> Unit) {
        val interval = 1000L / fps

        captureJob = scope.launch {
            while (isActive) {
                val startTime = System.currentTimeMillis()

                try {
                    val frame = captureScreen()
                    if (frame != null) {
                        withContext(Dispatchers.Main) {
                            onFrame(frame)
                        }
                    }
                } catch (e: Exception) {
                    println("Capture error: ${e.message}")
                }

                // 프레임 간격 유지
                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = interval - elapsed
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        }
    }

    /**
     * screenrecord 듀얼 스트림 방식 (고FPS, 갭 없음)
     */
    private fun startScreenrecordMode(fps: Int, onFrame: (ByteArray) -> Unit) {
        if (isStreaming.getAndSet(true)) {
            return
        }

        val recordDurationMs = 60_000L      // 60초 녹화
        val overlapStartMs = 50_000L        // 50초 지점에서 다음 스트림 시작 (10초 오버랩)

        streamDecoderA = StreamDecoder()
        streamDecoderB = StreamDecoder()

        streamSchedulerJob = scope.launch {
            var useA = true

            while (isActive && isStreaming.get()) {
                val currentDecoder = if (useA) streamDecoderA else streamDecoderB
                val nextDecoder = if (useA) streamDecoderB else streamDecoderA

                // 현재 스트림 시작
                currentDecoder?.startDecoding(
                    fps = fps,
                    onFrame = { frame ->
                        onFrame(frame)
                    },
                    onError = { error ->
                        println("Stream error: $error")
                    }
                )

                // 오버랩 시작 시점까지 대기
                delay(overlapStartMs)

                if (!isActive || !isStreaming.get()) break

                // 다음 스트림 시작 (오버랩)
                nextDecoder?.startDecoding(
                    fps = fps,
                    onFrame = { frame ->
                        onFrame(frame)
                    },
                    onError = { error ->
                        println("Stream error: $error")
                    }
                )

                // 현재 스트림 종료 시점까지 대기
                delay(recordDurationMs - overlapStartMs)

                // 현재 스트림 종료
                currentDecoder?.stopDecoding()

                // 다음 사이클을 위해 전환
                useA = !useA
            }
        }
    }

    /**
     * 스크린 캡처 중지
     */
    fun stopCapturing() {
        // screencap 모드 중지
        captureJob?.cancel()
        captureJob = null

        // screenrecord 모드 중지
        isStreaming.set(false)
        streamSchedulerJob?.cancel()
        streamSchedulerJob = null

        streamDecoderA?.stopDecoding()
        streamDecoderB?.stopDecoding()
    }

    /**
     * 단일 스크린샷 캡처 (screencap 방식)
     */
    private suspend fun captureScreen(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // adb exec-out screencap -p 를 사용하여 PNG 바이트 직접 가져오기
            val process = ProcessBuilder(adbPath, "exec-out", "screencap", "-p")
                .redirectErrorStream(false)
                .start()

            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty() && process.exitValue() == 0) {
                bytes
            } else {
                null
            }
        } catch (e: Exception) {
            println("Screenshot error: ${e.message}")
            null
        }
    }

    /**
     * 현재 캡처 모드 반환
     */
    fun getCurrentMode(): CaptureMode = currentMode

    /**
     * 터치 포인터 표시 설정
     * @param enabled true면 터치 위치가 화면에 표시됨
     */
    suspend fun setPointerLocation(enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            val value = if (enabled) "1" else "0"
            ProcessBuilder(adbPath, "shell", "settings", "put", "system", "pointer_location", value)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) {
            println("Failed to set pointer location: ${e.message}")
        }
    }

    /**
     * 리소스 정리
     */
    fun cleanup() {
        stopCapturing()
        streamDecoderA?.cleanup()
        streamDecoderB?.cleanup()
        streamDecoderA = null
        streamDecoderB = null
        scope.cancel()
    }
}
