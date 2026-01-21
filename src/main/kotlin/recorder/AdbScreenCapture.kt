package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * scrcpy 기반 스크린 캡처
 */
class AdbScreenCapture {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var streamDecoder: StreamDecoder? = null
    private val isCapturing = AtomicBoolean(false)

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
     * 디바이스 해상도 가져오기
     * @return Pair(width, height) 또는 null
     */
    suspend fun getDeviceResolution(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(adbPath, "shell", "wm", "size")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()

            // "Physical size: 1080x2400" 형식 파싱
            val regex = Regex("(\\d+)x(\\d+)")
            val match = regex.find(output)
            if (match != null) {
                val width = match.groupValues[1].toInt()
                val height = match.groupValues[2].toInt()
                Pair(width, height)
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error getting resolution: ${e.message}")
            null
        }
    }

    /**
     * ADB 경로 (PathFinder에서 가져옴)
     */
    val adbPath: String get() = PathFinder.adbPath

    /**
     * 스크린 캡처 시작 (scrcpy 사용)
     * @param fps 프레임 레이트
     * @param maxSize 최대 해상도 (짧은 변 기준, 0이면 원본)
     * @param onFrame 프레임 콜백
     * @param onError 에러 콜백
     */
    fun startCapturing(
        fps: Int,
        maxSize: Int = 0,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        stopCapturing()

        if (isCapturing.getAndSet(true)) {
            return
        }

        streamDecoder = StreamDecoder()
        streamDecoder?.startDecoding(
            fps = fps,
            maxSize = maxSize,
            onFrame = onFrame,
            onError = { error ->
                isCapturing.set(false)
                onError(error)
            }
        )
    }

    /**
     * 스크린 캡처 중지
     */
    fun stopCapturing() {
        isCapturing.set(false)
        streamDecoder?.stopDecoding()
        streamDecoder = null
    }

    /**
     * 캡처 중인지 확인
     */
    fun isCapturing(): Boolean = isCapturing.get()

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
        streamDecoder?.cleanup()
        streamDecoder = null
        scope.cancel()
    }
}
