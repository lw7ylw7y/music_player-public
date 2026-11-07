package app.songplayer.core.library

import java.io.File

/**
 * A short silent but valid MP3 built in code (MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, stereo).
 * Every frame is the same 4-byte header followed by zeros, so nothing binary is checked in.
 */
object TestMp3 {
    const val FRAME_BYTES = 417 // 144 * 128000 / 44100, no padding
    private val HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

    /** About 26 ms of audio per frame; 40 frames is about one second. */
    fun bytes(frames: Int = 40): ByteArray {
        require(frames > 0)
        val out = ByteArray(frames * FRAME_BYTES)
        repeat(frames) { HEADER.copyInto(out, it * FRAME_BYTES) }
        return out
    }

    fun write(file: File, frames: Int = 40): File = file.also { it.writeBytes(bytes(frames)) }
}
