package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * screenrecord + NAL 방식 스크린 캡처
 *
 * H.264 NAL 유닛을 직접 버퍼에 저장하고,
 * 저장 시 MP4로 mux (재인코딩 없음)
 */
class AdbScreenCapture {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var screenRecorder: ScreenRecorder? = null
    private val isCapturing = AtomicBoolean(false)

    // NAL 버퍼 (외부에서 접근 가능)
    val nalBuffer = NalBuffer()

    // MP4 Muxer
    val muxer = Mp4Muxer()

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
     * 스크린 캡처 시작 (NAL 방식)
     *
     * @param maxSize 최대 해상도 (짧은 변 기준)
     * @param bitRate 비트레이트 (bps)
     * @param onNalReceived NAL 수신 콜백 (UI 업데이트용)
     * @param onError 에러 콜백
     */
    fun startCapturing(
        maxSize: Int = 1280,
        bitRate: Int = 8_000_000,
        onNalReceived: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stopCapturing()

        if (isCapturing.getAndSet(true)) {
            return
        }

        // 해상도 문자열 생성
        val resolution = "${maxSize}x${maxSize}"

        screenRecorder = ScreenRecorder()
        screenRecorder?.startRecording(
            resolution = resolution,
            bitRate = bitRate,
            onNalUnit = { nalUnit ->
                // NAL 버퍼에 저장
                nalBuffer.addNalUnit(nalUnit)
                onNalReceived()
            },
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
        screenRecorder?.stopRecording()
        screenRecorder = null
    }

    /**
     * 캡처 중인지 확인
     */
    fun isCapturing(): Boolean = isCapturing.get()

    /**
     * 최근 N초 녹화 저장
     *
     * @param durationSeconds 저장할 시간 (초)
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun saveRecording(durationSeconds: Int): String? {
        val nalUnits = nalBuffer.getNalUnits(durationSeconds)
        if (nalUnits.isEmpty()) {
            println("AdbScreenCapture: No NAL units to save")
            return null
        }

        println("AdbScreenCapture: Saving ${nalUnits.size} NAL units (${durationSeconds}s)")
        return muxer.muxWithPts(nalUnits)
    }

    /**
     * 스크린샷 저장
     */
    suspend fun saveScreenshot(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(adbPath, "exec-out", "screencap", "-p")
                .redirectErrorStream(false)
                .start()

            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty() && process.exitValue() == 0) {
                muxer.saveScreenshot(bytes)
            } else {
                null
            }
        } catch (e: Exception) {
            println("Screenshot error: ${e.message}")
            null
        }
    }

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
        screenRecorder?.cleanup()
        screenRecorder = null
        scope.cancel()
    }
}
