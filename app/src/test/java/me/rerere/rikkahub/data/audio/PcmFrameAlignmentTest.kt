package me.rerere.rikkahub.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 半帧写进 AudioTrack = 左右声道错位 = 每块数据交界处一声「咔」。
 * `WRITE_BLOCKING` 的返回值可能落在半帧上，所以对齐逻辑单独测。
 */
class PcmFrameAlignmentTest {
    @Test
    fun `keeps whole stereo frames untouched`() {
        assertEquals(1024, alignToFrameBoundary(1024, 4))
    }

    @Test
    fun `truncates a partial stereo frame`() {
        assertEquals(1020, alignToFrameBoundary(1023, 4))
        assertEquals(1020, alignToFrameBoundary(1021, 4))
    }

    @Test
    fun `aligns mono frames to 2 bytes`() {
        assertEquals(100, alignToFrameBoundary(101, 2))
        assertEquals(0, alignToFrameBoundary(1, 2))
    }

    @Test
    fun `non-positive input yields zero`() {
        assertEquals(0, alignToFrameBoundary(0, 4))
        assertEquals(0, alignToFrameBoundary(-8, 4))
        assertEquals(0, alignToFrameBoundary(8, 0))
    }

    /**
     * 2026-10-09 修的真 bug：残字节**不能丢**。
     * 单声道帧只有 2 字节，网络读回奇数长度时丢 1 个字节，后面整条流全部半样本错位。
     */
    @Test
    fun `carries the partial mono frame into the next write`() {
        val (whole1, rest1) = splitWholeFrames(ByteArray(0), byteArrayOf(1, 2, 3), 3, 2)
        assertEquals(2, whole1.size)
        assertEquals(1, rest1.size)
        assertEquals(listOf<Byte>(1, 2), whole1.toList())

        val (whole2, rest2) = splitWholeFrames(rest1, byteArrayOf(4), 1, 2)
        assertEquals(2, whole2.size)
        assertEquals(0, rest2.size)
        assertEquals(listOf<Byte>(3, 4), whole2.toList())
    }

    @Test
    fun `survives odd reads on stereo frames`() {
        val (whole1, rest1) = splitWholeFrames(ByteArray(0), ByteArray(7) { it.toByte() }, 7, 4)
        assertEquals(4, whole1.size)
        assertEquals(3, rest1.size)

        val (whole2, rest2) = splitWholeFrames(rest1, byteArrayOf(99), 1, 4)
        assertEquals(4, whole2.size)
        assertEquals(0, rest2.size)
    }

    @Test
    fun `keeps everything when a read is smaller than one frame`() {
        val (whole, rest) = splitWholeFrames(ByteArray(0), byteArrayOf(7), 1, 4)
        assertEquals(0, whole.size)
        assertEquals(1, rest.size)
    }
}
