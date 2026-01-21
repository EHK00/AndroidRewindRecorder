package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adb screenrecord를 사용한 H.264 스트림 캡처
 *
 * screenrecord --output-format=h264 출력을 NAL 유닛으로 파싱하여
 * NalBuffer에 저장
 */
class ScreenRecorder {

    private val adbPath: String get() = PathFinder.adbPath

    private var recordProcess: Process? = null
    private var parseJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private val nalParser = H264NalParser()

    val isActive: Boolean
        get() = isRunning.get()

    /**
     * 녹화 시작
     *
     * @param resolution 해상도 (예: "1280x720")
     * @param bitRate 비트레이트 (bps)
     * @param onNalUnit NAL 유닛 콜백
     * @param onError 에러 콜백
     */
    fun startRecording(
        resolution: String = "1280x720",
        bitRate: Int = 8_000_000,
        onNalUnit: (H264NalParser.NalUnit) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (isRunning.getAndSet(true)) {
            return // 이미 실행 중
        }

        parseJob = scope.launch {
            try {
                println("ScreenRecorder: Starting with resolution=$resolution, bitRate=$bitRate")

                // screenrecord 시작
                recordProcess = ProcessBuilder(
                    adbPath, "exec-out", "screenrecord",
                    "--output-format=h264",
                    "--size", resolution,
                    "--bit-rate", bitRate.toString(),
                    "-"  // stdout으로 출력
                )
                    .redirectErrorStream(false)
                    .start()

                // 에러 스트림 모니터링
                launch(Dispatchers.IO) {
                    try {
                        recordProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                            if (line.isNotBlank()) {
                                println("screenrecord: $line")
                                if (line.contains("error", ignoreCase = true)) {
                                    scope.launch(Dispatchers.Main) {
                                        onError("screenrecord 에러: $line")
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // H.264 스트림 파싱
                val inputStream = recordProcess?.inputStream
                if (inputStream != null) {
                    nalParser.parseStream(
                        inputStream = inputStream,
                        onNalUnit = { nalUnit ->
                            // 메인 스레드에서 콜백 (UI 업데이트용)
                            onNalUnit(nalUnit)
                        },
                        isRunning = { isActive && isRunning.get() }
                    )
                }

            } catch (e: Exception) {
                println("ScreenRecorder: Error - ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("녹화 에러: ${e.message}")
                }
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * 녹화 중지
     */
    fun stopRecording() {
        isRunning.set(false)
        parseJob?.cancel()
        parseJob = null

        recordProcess?.destroy()
        recordProcess = null

        nalParser.reset()

        println("ScreenRecorder: Stopped")
    }

    /**
     * 리소스 정리
     */
    fun cleanup() {
        stopRecording()
        scope.cancel()
    }

    /**
     * 저장된 SPS
     */
    fun getSps(): ByteArray? = nalParser.getSps()

    /**
     * 저장된 PPS
     */
    fun getPps(): ByteArray? = nalParser.getPps()
}
