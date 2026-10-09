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
}
