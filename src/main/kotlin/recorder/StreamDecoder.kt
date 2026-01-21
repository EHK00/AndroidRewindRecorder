package recorder

import config.PathFinder
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * scrcpy-server를 이용한 H.264 스트림 디코딩
 * scrcpy-server (내장) → FFmpeg → PNG frames
 *
 * 장점:
 * - 외부 scrcpy 설치 불필요 (서버만 내장)
 * - 화면 변화 없이도 안정적인 프레임 캡처
 * - 낮은 지연 시간
 */
class StreamDecoder {

    private val ffmpegPath: String get() = PathFinder.ffmpegPath

    private var scrcpyClient: ScrcpyClient? = null
    private var ffmpegProcess: Process? = null
    private var decodeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    val isActive: Boolean
        get() = isRunning.get()

    /**
     * H.264 스트림 디코딩 시작
     * @param fps 추출할 FPS
     * @param maxSize 최대 해상도 (짧은 변 기준, 0이면 원본)
     * @param onFrame PNG 프레임 콜백
     * @param onError 에러 콜백
     */
    fun startDecoding(
        fps: Int = 30,
        maxSize: Int = 0,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (isRunning.getAndSet(true)) {
            return // 이미 실행 중
        }

        decodeJob = scope.launch {
            try {
                println("StreamDecoder: Starting with maxSize=$maxSize, fps=$fps")

                // scrcpy-server 시작 및 H.264 스트림 획득
                scrcpyClient = ScrcpyClient()
                val h264Stream = scrcpyClient?.start(
                    maxSize = maxSize,
                    maxFps = fps,
                    bitRate = 8_000_000
                )

                if (h264Stream == null) {
                    withContext(Dispatchers.Main) {
                        onError("scrcpy-server 연결 실패")
                    }
                    isRunning.set(false)
                    return@launch
                }

                println("StreamDecoder: H.264 stream connected")

                // FFmpeg로 H.264 → PNG 변환
                ffmpegProcess = ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-f", "h264",              // raw H.264 입력
                    "-i", "pipe:0",
                    "-vf", "fps=$fps",
                    "-vsync", "cfr",
                    "-f", "image2pipe",
                    "-vcodec", "png",
                    "-compression_level", "1",  // 빠른 압축
                    "pipe:1"
                ).redirectErrorStream(false).start()

                // FFmpeg 에러 스트림 모니터링
                launch(Dispatchers.IO) {
                    try {
                        ffmpegProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                            if (line.isNotBlank()) println("FFmpeg: $line")
                        }
                    } catch (_: Exception) {}
                }

                // 파이프 연결: scrcpy H.264 → ffmpeg stdin
                launch(Dispatchers.IO) pipeJob@{
                    try {
                        val buffer = ByteArray(8192)
                        val ffmpegInput = ffmpegProcess?.outputStream ?: return@pipeJob

                        while (isActive && isRunning.get()) {
                            val bytesRead = h264Stream.read(buffer)
                            if (bytesRead == -1) break

                            ffmpegInput.write(buffer, 0, bytesRead)
                            ffmpegInput.flush()
                        }
                    } catch (e: Exception) {
                        println("StreamDecoder: Pipe error - ${e.message}")
                    } finally {
                        try {
                            ffmpegProcess?.outputStream?.close()
                        } catch (_: Exception) {}
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
        var frameCount = 0

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
                            frameCount++
                            if (frameCount <= 3 || frameCount % 100 == 0) {
                                println("StreamDecoder: Frame #$frameCount (${pngData.size} bytes)")
                            }
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
            println("StreamDecoder: Stream ended - ${e.message}")
        }
    }

    /**
     * 디코딩 중지
     */
    fun stopDecoding() {
        isRunning.set(false)
        decodeJob?.cancel()
        decodeJob = null

        scrcpyClient?.stop()
        scrcpyClient = null

        ffmpegProcess?.destroy()
        ffmpegProcess = null
    }

    fun cleanup() {
        stopDecoding()
        scope.cancel()
    }
}
