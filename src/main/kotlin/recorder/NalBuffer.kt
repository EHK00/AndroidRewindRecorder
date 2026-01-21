package recorder

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 NAL 유닛 링버퍼
 *
 * NAL 유닛을 타임스탬프와 함께 저장하고,
 * 지정된 시간 범위의 NAL들을 추출할 수 있음
 */
class NalBuffer(
    private var maxDurationSeconds: Int = 60
) {
    private val buffer = ConcurrentLinkedDeque<H264NalParser.NalUnit>()
    private val totalBytes = AtomicLong(0)

    // SPS/PPS 캐시 (MP4 생성 시 필요)
    @Volatile
    private var cachedSps: ByteArray? = null
    @Volatile
    private var cachedPps: ByteArray? = null

    /**
     * NAL 유닛 추가
     */
    fun addNalUnit(nalUnit: H264NalParser.NalUnit) {
        // SPS/PPS 캐시
        when (nalUnit.type) {
            H264NalParser.NAL_TYPE_SPS -> cachedSps = nalUnit.data.copyOf()
            H264NalParser.NAL_TYPE_PPS -> cachedPps = nalUnit.data.copyOf()
        }

        buffer.addLast(nalUnit)
        totalBytes.addAndGet(nalUnit.data.size.toLong())

        // 오래된 NAL 제거 (시간 기반)
        trimOldNals()
    }

    /**
     * 시간 초과된 NAL 제거
     */
    private fun trimOldNals() {
        val cutoffTime = System.nanoTime() - (maxDurationSeconds * 1_000_000_000L)

        while (buffer.isNotEmpty()) {
            val oldest = buffer.peekFirst() ?: break
            if (oldest.timestamp < cutoffTime) {
                val removed = buffer.pollFirst()
                if (removed != null) {
                    totalBytes.addAndGet(-removed.data.size.toLong())
                }
            } else {
                break
            }
        }
    }

    /**
     * 최근 N초 동안의 NAL 유닛 반환
     * 키프레임부터 시작하도록 조정
     */
    fun getNalUnits(durationSeconds: Int): List<H264NalParser.NalUnit> {
        if (buffer.isEmpty()) return emptyList()

        val lastTimestamp = buffer.peekLast()?.timestamp ?: return emptyList()
        val cutoffTime = lastTimestamp - (durationSeconds * 1_000_000_000L)

        // 시간 범위 내의 NAL 필터링
        val nalsInRange = buffer.filter { it.timestamp >= cutoffTime }

        // 키프레임(IDR 또는 SPS) 찾기
        val keyFrameIndex = nalsInRange.indexOfFirst { it.isKeyFrame }

        return if (keyFrameIndex >= 0) {
            // 키프레임부터 시작
            nalsInRange.drop(keyFrameIndex)
        } else {
            // 키프레임이 없으면 SPS/PPS 추가 후 반환
            val result = mutableListOf<H264NalParser.NalUnit>()

            // SPS 추가
            cachedSps?.let { sps ->
                result.add(H264NalParser.NalUnit(
                    type = H264NalParser.NAL_TYPE_SPS,
                    data = sps,
                    timestamp = nalsInRange.firstOrNull()?.timestamp ?: System.nanoTime(),
                    isKeyFrame = true
                ))
            }

            // PPS 추가
            cachedPps?.let { pps ->
                result.add(H264NalParser.NalUnit(
                    type = H264NalParser.NAL_TYPE_PPS,
                    data = pps,
                    timestamp = nalsInRange.firstOrNull()?.timestamp ?: System.nanoTime(),
                    isKeyFrame = true
                ))
            }

            result.addAll(nalsInRange)
            result
        }
    }

    /**
     * 모든 NAL 유닛 반환
     */
    fun getAllNalUnits(): List<H264NalParser.NalUnit> {
        return buffer.toList()
    }

    /**
     * 저장된 NAL 개수
     */
    fun getNalCount(): Int = buffer.size

    /**
     * 프레임 수 추정 (IDR + non-IDR slice 개수)
     */
    fun getFrameCount(): Int {
        return buffer.count {
            it.type == H264NalParser.NAL_TYPE_IDR || it.type == H264NalParser.NAL_TYPE_SLICE
        }
    }

    /**
     * 총 메모리 사용량 (MB)
     */
    fun getTotalMemoryMB(): Int {
        return (totalBytes.get() / (1024 * 1024)).toInt()
    }

    /**
     * 버퍼 비우기
     */
    fun clear() {
        buffer.clear()
        totalBytes.set(0)
        // SPS/PPS는 유지 (다음 녹화에 필요할 수 있음)
    }

    /**
     * 설정 업데이트
     */
    fun updateSettings(duration: Int) {
        this.maxDurationSeconds = duration
        trimOldNals()
    }

    /**
     * 캐시된 SPS
     */
    fun getSps(): ByteArray? = cachedSps

    /**
     * 캐시된 PPS
     */
    fun getPps(): ByteArray? = cachedPps

    /**
     * 버퍼 상태 정보
     */
    fun getStatus(): String {
        val durationNs = if (buffer.isEmpty()) 0L else {
            val first = buffer.peekFirst()?.timestamp ?: 0L
            val last = buffer.peekLast()?.timestamp ?: 0L
            last - first
        }
        val durationSec = durationNs / 1_000_000_000L
        return "NALs: ${buffer.size}, Frames: ${getFrameCount()}, Duration: ${durationSec}s, Memory: ${getTotalMemoryMB()}MB"
    }
}
