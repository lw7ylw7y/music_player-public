package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Mp3FramesTest {
    private fun scan(bytes: ByteArray) = Mp3Frames.scan(ByteArraySource(bytes))

    @Test
    fun Mp3Frames_CbrAndVbr_IndexIsExact() {
        // CBR: 128 kbit/s at 44.1 kHz is 417 bytes, or 418 with padding.
        val cbr = Mp3Shapes.repeat(100) { Mp3Shapes.frame(bitrateIndex = 9, padding = it % 2) }
        val a = scan(cbr)
        assertEquals(100, a.count)
        assertEquals(0, a.first)
        var at = 0
        for (i in 0 until 100) { assertEquals(at, a.offset[i]); at += 417 + i % 2; assertEquals(417 + i % 2, a.frameLength(i)) }
        assertEquals(44100, a.sampleRate(0)); assertEquals(1152, a.samples(0))
        // VBR: a Xing frame first, then frames of changing bitrate.
        val vbr = Mp3Shapes.join(Mp3Shapes.xingFrame(), Mp3Shapes.repeat(50) { Mp3Shapes.frame(bitrateIndex = 5 + it % 8) })
        val b = scan(vbr)
        assertEquals(51, b.count)
        assertEquals(1, b.first) // the header frame is not audio
        assertEquals(0, b.offset[0])
        assertEquals(b.offset[0] + b.frameLength(0), b.offset[1])
    }

    @Test
    fun Mp3Frames_MainDataBegin_AndMainDataBits_AreRead() {
        val bytes = Mp3Shapes.join(
            Mp3Shapes.frame(mainDataBegin = 0, part2_3 = listOf(0, 0, 0, 0)),
            Mp3Shapes.frame(mainDataBegin = 7, part2_3 = listOf(100, 200, 300, 400)),
            Mp3Shapes.frame(mainDataBegin = 300, part2_3 = listOf(4095, 1, 2, 3)),
            Mp3Shapes.frame(mainDataBegin = 511, part2_3 = listOf(1, 1, 1, 1)),
        )
        val i = scan(bytes)
        assertArrayEquals(shortArrayOf(0, 7, 300, 511), i.mainDataBegin)
        assertArrayEquals(shortArrayOf(0, 1000, 4101, 4), i.mainDataBits)
        // MPEG-2 mono (22.05 kHz, 576 samples): an 8-bit main_data_begin and one granule of one channel.
        val m2 = Mp3Shapes.repeat(5) { Mp3Shapes.frame(mpeg1 = false, mono = true, sampleRateIndex = 0, mainDataBegin = 200 + it, part2_3 = listOf(500 + it)) }
        val j = scan(m2)
        assertArrayEquals(shortArrayOf(200, 201, 202, 203, 204), j.mainDataBegin)
        assertArrayEquals(shortArrayOf(500, 501, 502, 503, 504), j.mainDataBits)
        assertEquals(576, j.samples(0)); assertTrue(j.isMono(0)); assertEquals(22050, j.sampleRate(0))
    }

    @Test
    fun Mp3Frames_TagsAndXing_AreFound() {
        val frames = Mp3Shapes.repeat(20) { Mp3Shapes.frame() }
        val bytes = Mp3Shapes.join(Mp3Shapes.id3v2(1000), Mp3Shapes.xingFrame(tag = "Info"), frames, Mp3Shapes.id3v1())
        val i = scan(bytes)
        assertEquals(21, i.count)
        assertEquals(1, i.first)
        assertEquals(1010, i.offset[0]) // after the ID3v2 tag
        assertEquals(0L, i.junk)       // the tags are not junk
        assertEquals(bytes.size - 128, i.offset[20] + i.frameLength(20)) // the ID3v1 tag at the end is not a frame
    }

    @Test
    fun Mp3Frames_Garbage_IsUnreadable() {
        for (bytes in listOf(ByteArray(0), Mp3Shapes.noise(2_000_000), ByteArray(1000), Mp3Shapes.frame())) {
            try { scan(bytes); fail("expected Unreadable for ${bytes.size} bytes") } catch (e: SplitError.Unreadable) { assertEquals("SPLIT_UNREADABLE", e.code) }
        }
    }

    @Test
    fun Mp3Frames_TruncatedFrame_IndexesUpToIt() {
        val bytes = Mp3Shapes.repeat(200) { Mp3Shapes.frame() }
        val cut = bytes.copyOf(bytes.size - 200) // the last frame is cut in the middle
        val i = scan(cut)
        assertEquals(199, i.count)
        assertEquals(217L, i.junk) // the cut frame's remaining bytes are junk, and well under 1 %
    }

    @Test
    fun Mp3Frames_JunkUnderOnePercent_IsSkipped_OverIsDamaged() {
        val frames = Mp3Shapes.repeat(400) { Mp3Shapes.frame() }          // about 167 KB
        val little = Mp3Shapes.join(frames.copyOfRange(0, 100_000), ByteArray(500) { 0x11 }, frames.copyOfRange(100_000, frames.size))
        val i = scan(little)
        // The noise lands inside frame 239. While in step with the stream that frame's header is accepted (the tool does the same), so it
        // swallows 80 of the noise bytes; the other 420 noise bytes and the 80 bytes of its old tail are junk and all 400 frames are found.
        assertEquals(400, i.count)
        assertEquals(500L, i.junk)
        val much = Mp3Shapes.join(frames.copyOfRange(0, 100_000), Mp3Shapes.noise(5_000).also { it.fill(0x11) }, frames.copyOfRange(100_000, frames.size))
        try { scan(much); fail() } catch (_: SplitError.Unreadable) {}
    }

    @Test
    fun Mp3Frames_TooLarge_IsRefusedBeforeScanning() {
        val huge = object : ByteSource {
            override val length = SplitLimits.MAX_BYTES + 1
            override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int = error("must not be read")
        }
        try { Mp3Frames.scan(huge); fail() } catch (e: SplitError.TooLarge) { assertEquals("SPLIT_TOO_LARGE", e.code) }
    }

    @Test
    fun Mp3Frames_ManyFramesAcrossTheReadWindow_AreAllFound() {
        // More than one 1 MB read window: 4,000 frames of 417 bytes are 1.6 MB.
        val i = scan(Mp3Shapes.repeat(4000) { Mp3Shapes.frame() })
        assertEquals(4000, i.count)
        for (k in 0 until 4000) assertEquals(k * 417, i.offset[k])
    }

    /** Frames whose side information is written by hand from the MP3 specification, not by the fixture's own offsets. */
    @Test
    fun Mp3Frames_HandWrittenSideInfo_IsReadAsTheSpecSays() {
        // MPEG-1 stereo, 128 kbit/s, 44.1 kHz (417 bytes). Side information bits: main_data_begin 165 (9), private 0 (3), scfsi 0 (8),
        // then part2_3_length 291 (12 bits at bit 20: granule 0, channel 0) and 255 (12 bits at bit 79: granule 0, channel 1).
        val mpeg1 = ByteArray(417).also {
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00).copyInto(it)
            byteArrayOf(0x52, 0x80.toByte(), 0x01, 0x23).copyInto(it, 4)          // side info bytes 0 to 3
            it[4 + 10] = 0x1F; it[4 + 11] = 0xE0.toByte()                          // side info bytes 10 and 11 (bits 79 to 90)
        }
        // MPEG-2 stereo, 64 kbit/s, 22.05 kHz (208 bytes): main_data_begin 90 (8), private 0 (2), then 801 at bit 10 and 171 at bit 73.
        val mpeg2 = ByteArray(208).also {
            byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x80.toByte(), 0x00).copyInto(it)
            byteArrayOf(0x5A, 0x0C, 0x84.toByte()).copyInto(it, 4)
            it[4 + 9] = 0x05; it[4 + 10] = 0x58
        }
        val a = scan(Mp3Shapes.join(mpeg1, mpeg1, mpeg1))
        assertArrayEquals(shortArrayOf(165, 165, 165), a.mainDataBegin)
        assertArrayEquals(shortArrayOf(546, 546, 546), a.mainDataBits)
        val b = scan(Mp3Shapes.join(mpeg2, mpeg2, mpeg2))
        assertArrayEquals(shortArrayOf(90, 90, 90), b.mainDataBegin)
        assertArrayEquals(shortArrayOf(972, 972, 972), b.mainDataBits)
    }

    @Test
    fun Mp3Frames_OtherLayouts_AreRead() {
        // MPEG-1 mono, a CRC after the header, a Xing header in an MPEG-2 frame, and a VBRI header.
        val mono = scan(Mp3Shapes.repeat(5) { Mp3Shapes.frame(mono = true, mainDataBegin = 33 + it, part2_3 = listOf(10 + it, 20 + it)) })
        assertArrayEquals(shortArrayOf(33, 34, 35, 36, 37), mono.mainDataBegin)
        assertArrayEquals(shortArrayOf(30, 32, 34, 36, 38), mono.mainDataBits)
        assertTrue(mono.isMono(0))
        val withCrc = scan(Mp3Shapes.repeat(5) { Mp3Shapes.frame(mainDataBegin = 120, part2_3 = listOf(1, 2, 3, 4), crc = true) })
        assertArrayEquals(shortArrayOf(120, 120, 120, 120, 120), withCrc.mainDataBegin)
        assertArrayEquals(shortArrayOf(10, 10, 10, 10, 10), withCrc.mainDataBits)
        val xing2 = scan(Mp3Shapes.join(Mp3Shapes.xingFrame(mpeg1 = false), Mp3Shapes.repeat(5) { Mp3Shapes.frame(mpeg1 = false) }))
        assertEquals(1, xing2.first)
        val vbri = scan(Mp3Shapes.join(Mp3Shapes.vbriFrame(), Mp3Shapes.repeat(5) { Mp3Shapes.frame() }))
        assertEquals(1, vbri.first)
        // 48 kHz and a different version use their own sample rates and frame sizes.
        val k48 = scan(Mp3Shapes.repeat(5) { Mp3Shapes.frame(sampleRateIndex = 1) })
        assertEquals(48000, k48.sampleRate(0)); assertEquals(384 * 1000 * 0 + 144 * 128000 / 48000, k48.frameLength(0))
    }

    @Test
    fun Mp3Frames_InvalidHeadersAndFalseSyncs_AreJunk() {
        val good = Mp3Shapes.repeat(300) { Mp3Shapes.frame() }
        val layer2 = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x90.toByte(), 0x00)         // Layer II
        val reservedBitrate = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0x00) // bitrate index 15
        val reservedRate = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x9C.toByte(), 0x00)    // sample rate index 3
        val falseSync = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)        // a valid header not followed by another frame
        val bytes = Mp3Shapes.join(good, layer2, reservedBitrate, reservedRate, falseSync, ByteArray(20), good)
        val i = scan(bytes)
        assertEquals(600, i.count)
        assertEquals(4L + 4 + 4 + 4 + 20, i.junk) // the four fake headers and the filler, all counted as junk
    }

    @Test
    fun Mp3Frames_NoFrameInTheFirstMegabyte_IsUnreadable_ButABigTagDoesNotCount() {
        val junkThenFrames = Mp3Shapes.join(ByteArray(1_200_000) { 0x11 }, Mp3Shapes.repeat(50) { Mp3Shapes.frame() })
        try { scan(junkThenFrames); fail() } catch (_: SplitError.Unreadable) {}
        // A 2 MB cover picture in the ID3 tag, a few stray bytes, then the audio: the tag is not scanned and not junk.
        val tagged = Mp3Shapes.join(Mp3Shapes.id3v2(2_000_000), ByteArray(300) { 0x11 }, Mp3Shapes.repeat(500) { Mp3Shapes.frame() })
        val i = scan(tagged)
        assertEquals(500, i.count)
        assertEquals(300L, i.junk)
    }

    @Test
    fun Mp3Frames_ID3InTheMiddle_IsSkipped_AndStartSecondsAdds() {
        val bytes = Mp3Shapes.join(Mp3Shapes.repeat(100) { Mp3Shapes.frame() }, Mp3Shapes.id3v2(500), Mp3Shapes.repeat(100) { Mp3Shapes.frame() })
        val i = scan(bytes)
        assertEquals(200, i.count)
        assertEquals(0L, i.junk)
        assertEquals(100 * 417 + 510, i.offset[100])
        assertEquals(100 * 1152.0 / 44100, i.startSeconds(100), 1e-9)
    }

    @Test
    fun Mp3Frames_FileBackedSource_GivesTheSameIndex() {
        val bytes = Mp3Shapes.join(Mp3Shapes.id3v2(100), Mp3Shapes.repeat(3000) { Mp3Shapes.frame(padding = it % 2) })
        val file = java.io.File.createTempFile("frames", ".mp3").also { it.writeBytes(bytes); it.deleteOnExit() }
        val fromFile = FileByteSource(file).use { Mp3Frames.scan(it) }
        val fromBytes = scan(bytes)
        assertArrayEquals(fromBytes.offset, fromFile.offset)
        assertArrayEquals(fromBytes.mainDataBegin, fromFile.mainDataBegin)
    }

    @Test
    fun Mp3Frames_TooManyFrames_IsTooLarge() {
        // Tiny MPEG-2.5 frames (8 kbit/s at 8 kHz is 72 bytes): the frame count is capped whatever the file size.
        val frame = Mp3Shapes.frame(mpeg1 = false, bitrateIndex = 1, sampleRateIndex = 2)
        val total = 2_500_001L
        val source = object : ByteSource {
            override val length = total * frame.size
            override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
                if (offset >= length) return -1
                val n = minOf(len.toLong(), length - offset).toInt()
                for (k in 0 until n) buffer[off + k] = frame[((offset + k) % frame.size).toInt()]
                return n
            }
        }
        try { Mp3Frames.scan(source); fail() } catch (_: SplitError.TooLarge) {}
    }
}
