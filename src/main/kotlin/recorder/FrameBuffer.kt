package recorder

import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.abs

/**
 * 순환 버퍼로 최근 프레임들을 저장
 * Thread-safe한 구현 + 타임스탬프 기반 중복 제거
 */
class FrameBuffer(
    private var maxDurationSeconds: Int = 60,
    private var fps: Int = 30
) {

    data class TimestampedFrame(
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TimestampedFrame
            return timestamp == other.timestamp && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    // ConcurrentSkipListMap: 타임스탬프 기준 자동 정렬 + Thread-safe
    private val frames = ConcurrentSkipListMap<Long, TimestampedFrame>()

    // 중복 판정 임계값 (ms) - 이 시간 이내의 프레임은 중복으로 처리
    private val dedupeThresholdMs = 30L

    private val maxFrames: Int
        get() = maxDurationSeconds * fps

    /**
     * 프레임 추가 (타임스탬프 기반 중복 제거)
     */
    fun addFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()

        // 중복 체크: 가장 가까운 기존 타임스탬프 확인
        val floorKey = frames.floorKey(now)
        val ceilingKey = frames.ceilingKey(now)

        val isDuplicate = when {
            floorKey != null && abs(now - floorKey) < dedupeThresholdMs -> true
            ceilingKey != null && abs(now - ceilingKey) < dedupeThresholdMs -> true
            else -> false
        }

        if (isDuplicate) {
            return // 중복 프레임, 스킵
        }

        frames[now] = TimestampedFrame(frame, now)

        // 최대 프레임 수 초과 시 오래된 프레임 제거
        while (frames.size > maxFrames) {
            frames.pollFirstEntry()
        }
    }

    /**
     * 프레임 추가 (명시적 타임스탬프 지정)
     */
    fun addFrame(frame: ByteArray, timestamp: Long) {
        // 중복 체크
        val floorKey = frames.floorKey(timestamp)
        val ceilingKey = frames.ceilingKey(timestamp)

        val isDuplicate = when {
            floorKey != null && abs(timestamp - floorKey) < dedupeThresholdMs -> true
            ceilingKey != null && abs(timestamp - ceilingKey) < dedupeThresholdMs -> true
            else -> false
        }

        if (isDuplicate) {
            return
        }

        frames[timestamp] = TimestampedFrame(frame, timestamp)

        while (frames.size > maxFrames) {
            frames.pollFirstEntry()
        }
    }

    /**
     * 최근 N초간의 프레임 반환
     */
    fun getFrames(durationSeconds: Int): List<ByteArray> {
        val cutoffTime = System.currentTimeMillis() - (durationSeconds * 1000L)

        return frames.tailMap(cutoffTime).values
            .map { it.data }
            .toList()
    }

    /**
     * 최근 N초간의 프레임과 타임스탬프 반환
     * 마지막 프레임 기준으로 계산 (현재 시간이 아닌)
     */
    fun getFramesWithTimestamp(durationSeconds: Int): List<TimestampedFrame> {
        if (frames.isEmpty()) return emptyList()

        // 마지막 프레임 타임스탬프 기준으로 cutoff 계산
        val lastTimestamp = frames.lastEntry()?.value?.timestamp ?: return emptyList()
        val cutoffTime = lastTimestamp - (durationSeconds * 1000L)

        return frames.tailMap(cutoffTime).values.toList()
    }

    /**
     * 프레임 리스트의 실제 FPS 계산
     */
    fun calculateActualFps(frameList: List<TimestampedFrame>): Int {
        if (frameList.size < 2) return fps

        val firstTimestamp = frameList.first().timestamp
        val lastTimestamp = frameList.last().timestamp
        val durationMs = lastTimestamp - firstTimestamp

        if (durationMs <= 0) return fps

        val actualFps = (frameList.size * 1000.0 / durationMs).toInt()
        return actualFps.coerceIn(1, 60)  // 1~60 범위로 제한 (고FPS 지원)
    }

    /**
     * 모든 프레임 반환
     */
    fun getAllFrames(): List<ByteArray> {
        return frames.values.map { it.data }.toList()
    }

    /**
     * 모든 프레임과 타임스탬프 반환
     */
    fun getAllFramesWithTimestamp(): List<TimestampedFrame> {
        return frames.values.toList()
    }

    /**
     * 현재 저장된 프레임 수
     */
    fun getFrameCount(): Int = frames.size

    /**
     * 버퍼 비우기
     */
    fun clear() {
        frames.clear()
    }

    /**
     * 설정 업데이트
     */
    fun updateSettings(duration: Int, newFps: Int) {
        this.maxDurationSeconds = duration
        this.fps = newFps

        // 새 설정에 맞게 오래된 프레임 제거
        while (frames.size > maxFrames) {
            frames.pollFirstEntry()
        }
    }

    /**
     * 버퍼 상태 정보
     */
    fun getStatus(): String {
        val durationMs = if (frames.isEmpty()) 0L else {
            val first = frames.firstEntry()?.value?.timestamp ?: 0L
            val last = frames.lastEntry()?.value?.timestamp ?: 0L
            last - first
        }
        return "Frames: ${frames.size}, Duration: ${durationMs / 1000}s, Max: ${maxDurationSeconds}s"
    }

    /**
     * 현재 버퍼가 점유 중인 총 메모리 (MB)
     */
    fun getTotalMemoryMB(): Int {
        val totalBytes = frames.values.sumOf { it.data.size.toLong() }
        return (totalBytes / (1024 * 1024)).toInt()
    }
}
