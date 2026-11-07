package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TestMp3Test {
    @Test
    fun TestMp3_Bytes_HasASyncHeaderAtEveryFrame() {
        val bytes = TestMp3.bytes(frames = 5)
        assertEquals(5 * TestMp3.FRAME_BYTES, bytes.size)
        repeat(5) {
            val at = it * TestMp3.FRAME_BYTES
            assertEquals(0xFF, bytes[at].toInt() and 0xFF)
            assertEquals(0xFB, bytes[at + 1].toInt() and 0xFF)
            assertEquals(0x90, bytes[at + 2].toInt() and 0xFF)
        }
    }
}
