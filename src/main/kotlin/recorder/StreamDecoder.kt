package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 스트림을 PNG 프레임으로 디코딩
 * screenrecord → FFmpeg → PNG frames
 */
class StreamDecoder {

    private val adbPath: String get() = PathFinder.adbPath
    private val ffmpegPath: String get() = PathFinder.ffmpegPath

    private var screenrecordProcess: Process? = null
    private var ffmpegProcess: Process? = null
    private var decodeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    val isActive: Boolean
        get() = isRunning.get()

    /**
     * H.264 스트림 디코딩 시작
     * @param fps 추출할 FPS
     * @param resolution 해상도 (예: "1280x720")
     * @param onFrame PNG 프레임 콜백
     * @param onError 에러 콜백
     */
    fun startDecoding(
        fps: Int = 30,
        resolution: String = "1280x720",
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (isRunning.getAndSet(true)) {
            return // 이미 실행 중
        }

        decodeJob = scope.launch {
            try {
                // screenrecord 시작 (H.264 raw stream)
                screenrecordProcess = ProcessBuilder(
                    adbPath, "exec-out", "screenrecord",
                    "--output-format=h264",
                    "--size", resolution,
                    "--bit-rate", "8000000",
                    "-"
                ).redirectErrorStream(false).start()

                // FFmpeg로 H.264 → PNG 변환
                // -vsync cfr: 화면이 변하지 않아도 일정한 프레임 레이트 유지
                ffmpegProcess = ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-f", "h264",
                    "-i", "pipe:0",
                    "-vf", "fps=$fps",
                    "-vsync", "cfr",
                    "-f", "image2pipe",
                    "-vcodec", "png",
                    "pipe:1"
                ).redirectErrorStream(false).start()

                // 파이프 연결: screenrecord stdout → ffmpeg stdin
                launch(Dispatchers.IO) pipeJob@{
                    try {
                        screenrecordProcess?.inputStream?.copyTo(
                            ffmpegProcess?.outputStream ?: return@pipeJob
                        )
                    } catch (_: Exception) {
                        // 스트림 종료 시 발생 가능
                    } finally {
                        ffmpegProcess?.outputStream?.close()
                    }
                }

                // PNG 프레임 읽기
                readPngFrames(ffmpegProcess?.inputStream, onFrame)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Stream decode error: ${e.message}")
                }
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * PNG 스트림에서 개별 이미지 추출
     * PNG 시그니처: 89 50 4E 47 0D 0A 1A 0A
     * PNG 종료: 49 45 4E 44 AE 42 60 82 (IEND chunk)
     */
    private suspend fun readPngFrames(
        inputStream: InputStream?,
        onFrame: (ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (inputStream == null) return@withContext

        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A
        )
        val pngEnd = byteArrayOf(
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )

        var frameBuffer = mutableListOf<Byte>()
        var inFrame = false

        try {
            val readBuffer = ByteArray(8192)
            while (isActive && isRunning.get()) {
                val bytesRead = inputStream.read(readBuffer)
                if (bytesRead == -1) break

                for (i in 0 until bytesRead) {
                    val b = readBuffer[i]
                    frameBuffer.add(b)

                    // PNG 시작 감지
                    if (!inFrame && frameBuffer.size >= 8) {
                        val tail = frameBuffer.takeLast(8).toByteArray()
                        if (tail.contentEquals(pngSignature)) {
                            frameBuffer = tail.toMutableList()
                            inFrame = true
                        }
                    }

                    // PNG 종료 감지
                    if (inFrame && frameBuffer.size >= 8) {
                        val tail = frameBuffer.takeLast(8).toByteArray()
                        if (tail.contentEquals(pngEnd)) {
                            // 완전한 PNG 프레임
                            val pngData = frameBuffer.toByteArray()
                            withContext(Dispatchers.Main) {
                                onFrame(pngData)
                            }
                            frameBuffer.clear()
                            inFrame = false
                        }
                    }

                    // 버퍼 오버플로우 방지
                    if (frameBuffer.size > 5 * 1024 * 1024) {
                        frameBuffer.clear()
                        inFrame = false
                    }
                }
            }
        } catch (e: Exception) {
            // 스트림 종료
        }
    }

    /**
     * 디코딩 중지
     */
    fun stopDecoding() {
        isRunning.set(false)
        decodeJob?.cancel()
        decodeJob = null

        screenrecordProcess?.destroy()
        screenrecordProcess = null

        ffmpegProcess?.destroy()
        ffmpegProcess = null
    }

    fun cleanup() {
        stopDecoding()
        scope.cancel()
    }
}
