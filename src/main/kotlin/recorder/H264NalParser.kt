package recorder

import java.io.InputStream
import java.nio.ByteBuffer

/**
 * H.264 NAL 유닛 파서
 *
 * H.264 Annex B 형식의 스트림에서 NAL 유닛을 추출
 * NAL 시작 코드: 0x00 0x00 0x00 0x01 또는 0x00 0x00 0x01
 */
class H264NalParser {

    companion object {
        // NAL 유닛 타입
        const val NAL_TYPE_SLICE = 1       // Non-IDR slice (P-frame, B-frame)
        const val NAL_TYPE_DPA = 2         // Data partition A
        const val NAL_TYPE_DPB = 3         // Data partition B
        const val NAL_TYPE_DPC = 4         // Data partition C
        const val NAL_TYPE_IDR = 5         // IDR slice (키프레임)
        const val NAL_TYPE_SEI = 6         // Supplemental enhancement information
        const val NAL_TYPE_SPS = 7         // Sequence parameter set
        const val NAL_TYPE_PPS = 8         // Picture parameter set
        const val NAL_TYPE_AUD = 9         // Access unit delimiter

        // 시작 코드
        private val START_CODE_3 = byteArrayOf(0x00, 0x00, 0x01)
        private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    /**
     * NAL 유닛 데이터 클래스
     */
    data class NalUnit(
        val type: Int,                    // NAL 타입 (1-31)
        val data: ByteArray,              // 시작 코드 포함 전체 데이터
        val timestamp: Long,              // monotonic 타임스탬프 (나노초)
        val isKeyFrame: Boolean           // IDR 또는 SPS/PPS 포함 여부
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as NalUnit
            return timestamp == other.timestamp && type == other.type
        }

        override fun hashCode(): Int {
            var result = type
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    private val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB 버퍼
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * 스트림에서 NAL 유닛 읽기 (콜백 방식)
     */
    fun parseStream(
        inputStream: InputStream,
        onNalUnit: (NalUnit) -> Unit,
        isRunning: () -> Boolean
    ) {
        val readBuffer = ByteArray(8192)
        val nalBuffer = mutableListOf<Byte>()
        var inNal = false
        var zeroCount = 0

        while (isRunning()) {
            val bytesRead = inputStream.read(readBuffer)
            if (bytesRead == -1) break

            for (i in 0 until bytesRead) {
                val b = readBuffer[i]

                if (b == 0x00.toByte()) {
                    zeroCount++
                    nalBuffer.add(b)
                } else if (b == 0x01.toByte() && zeroCount >= 2) {
                    // 시작 코드 발견
                    if (inNal && nalBuffer.size > 4) {
                        // 이전 NAL 유닛 완료 (시작 코드 제외)
                        if (zeroCount >= 3) 4 else 3
                        val nalData = nalBuffer.dropLast(zeroCount).toByteArray()

                        if (nalData.isNotEmpty()) {
                            val nalUnit = createNalUnit(nalData)
                            if (nalUnit != null) {
                                // SPS/PPS 저장
                                when (nalUnit.type) {
                                    NAL_TYPE_SPS -> sps = nalData
                                    NAL_TYPE_PPS -> pps = nalData
                                }
                                onNalUnit(nalUnit)
                            }
                        }
                    }

                    // 새 NAL 시작
                    nalBuffer.clear()
                    // 시작 코드 추가
                    repeat(zeroCount.coerceAtMost(3)) { nalBuffer.add(0x00) }
                    nalBuffer.add(0x01)
                    inNal = true
                    zeroCount = 0
                } else {
                    nalBuffer.add(b)
                    zeroCount = 0
                }
            }
        }

        // 마지막 NAL 처리
        if (inNal && nalBuffer.size > 4) {
            val nalData = nalBuffer.toByteArray()
            val nalUnit = createNalUnit(nalData)
            if (nalUnit != null) {
                onNalUnit(nalUnit)
            }
        }
    }

    /**
     * NAL 데이터로부터 NalUnit 생성
     */
    private fun createNalUnit(data: ByteArray): NalUnit? {
        if (data.size < 5) return null

        // 시작 코드 이후 첫 바이트에서 NAL 타입 추출
        val headerIndex = findNalHeader(data)
        if (headerIndex < 0) return null

        val nalHeader = data[headerIndex].toInt() and 0xFF
        val nalType = nalHeader and 0x1F

        val isKeyFrame = nalType == NAL_TYPE_IDR || nalType == NAL_TYPE_SPS || nalType == NAL_TYPE_PPS

        return NalUnit(
            type = nalType,
            data = data,
            timestamp = System.nanoTime(),
            isKeyFrame = isKeyFrame
        )
    }

    /**
     * 시작 코드 이후 헤더 위치 찾기
     */
    private fun findNalHeader(data: ByteArray): Int {
        if (data.size < 4) return -1

        // 0x00 0x00 0x00 0x01 패턴
        if (data.size >= 5 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x00.toByte() && data[3] == 0x01.toByte()) {
            return 4
        }

        // 0x00 0x00 0x01 패턴
        if (data.size >= 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x01.toByte()) {
            return 3
        }

        return -1
    }

    /**
     * 저장된 SPS 반환
     */
    fun getSps(): ByteArray? = sps

    /**
     * 저장된 PPS 반환
     */
    fun getPps(): ByteArray? = pps

    /**
     * 파서 리셋
     */
    fun reset() {
        buffer.clear()
        sps = null
        pps = null
    }
}
