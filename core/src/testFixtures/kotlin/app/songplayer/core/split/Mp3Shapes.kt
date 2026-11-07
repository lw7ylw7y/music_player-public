package app.songplayer.core.split

import java.io.ByteArrayOutputStream
import java.util.Random

/** Generated MP3 bytes for the split tests (no audio is checked in): frames with chosen side information, tags, junk and a Xing header. */
object Mp3Shapes {
    /** One Layer III frame. [bitrateIndex] 1..14, [sampleRateIndex] 0..2; [mpeg1] false means MPEG-2 (22.05, 24 or 16 kHz). */
    fun frame(
        mpeg1: Boolean = true,
        bitrateIndex: Int = 9,
        sampleRateIndex: Int = 0,
        mono: Boolean = false,
        padding: Int = 0,
        mainDataBegin: Int = 0,
        part2_3: List<Int> = emptyList(),
        crc: Boolean = false,
    ): ByteArray {
        val ver = if (mpeg1) 3 else 2
        val rate = Mp3Index.SAMPLE_RATES[ver][sampleRateIndex]
        val kbps = if (mpeg1) intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex]
        else intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIndex]
        val length = (if (mpeg1) 144 else 72) * kbps * 1000 / rate + padding
        val b = ByteArray(length)
        val shift = if (crc) 16 else 0 // two CRC bytes sit between the header and the side information
        b[0] = 0xFF.toByte()
        b[1] = (0xE0 or (ver shl 3) or (1 shl 1) or (if (crc) 0 else 1)).toByte()       // Layer III; protection bit 0 means a CRC follows
        b[2] = ((bitrateIndex shl 4) or (sampleRateIndex shl 2) or (padding shl 1)).toByte()
        b[3] = ((if (mono) 3 else 0) shl 6).toByte()
        // Side information from byte 4: main_data_begin, then the part2_3_length of each granule and channel.
        val channels = if (mono) 1 else 2
        val head = if (mpeg1) 9 + (if (mono) 5 else 3) + 4 * channels else 8 + (if (mono) 1 else 2)
        val block = if (mpeg1) 59 else 63
        fun put(bitPos: Int, width: Int, value: Int) {
            for (k in 0 until width) {
                val bit = (value shr (width - 1 - k)) and 1
                val p = 4 * 8 + shift + bitPos + k
                if (bit == 1) b[p / 8] = (b[p / 8].toInt() or (0x80 shr (p % 8))).toByte()
            }
        }
        put(0, if (mpeg1) 9 else 8, mainDataBegin)
        part2_3.forEachIndexed { k, v -> put(head + k * block, 12, v) }
        return b
    }

    /** A frame that holds a Xing (VBR) or Info (CBR) header: silent, with the tag where players look for it. */
    fun xingFrame(mpeg1: Boolean = true, tag: String = "Xing"): ByteArray {
        val f = frame(mpeg1 = mpeg1)
        val o = 4 + (if (mpeg1) 32 else 17)
        tag.forEachIndexed { k, c -> f[o + k] = c.code.toByte() }
        return f
    }

    /** A frame with a VBRI header at byte 36 (the Fraunhofer variant of the VBR header). */
    fun vbriFrame(): ByteArray = frame().also { "VBRI".forEachIndexed { k, c -> it[36 + k] = c.code.toByte() } }

    fun id3v2(payload: Int): ByteArray {
        val h = ByteArray(10 + payload)
        "ID3".forEachIndexed { k, c -> h[k] = c.code.toByte() }
        h[3] = 3
        h[6] = ((payload shr 21) and 127).toByte(); h[7] = ((payload shr 14) and 127).toByte()
        h[8] = ((payload shr 7) and 127).toByte(); h[9] = (payload and 127).toByte()
        return h
    }

    fun id3v1(): ByteArray = ByteArray(128).also { "TAG".forEachIndexed { k, c -> it[k] = c.code.toByte() } }

    fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** [n] identical frames. */
    fun repeat(n: Int, make: (Int) -> ByteArray): ByteArray = join(*Array(n) { make(it) })

    fun noise(bytes: Int, seed: Long = 7): ByteArray = ByteArray(bytes).also { Random(seed).nextBytes(it) }
}

/** A [ByteSource] over a file, for tests that want a real file (the app reads through a file descriptor instead). */
class FileByteSource(file: java.io.File) : ByteSource, AutoCloseable {
    private val raf = java.io.RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
        if (offset >= length) return -1
        raf.seek(offset)
        return raf.read(buffer, off, len)
    }

    override fun close() = raf.close()
}
