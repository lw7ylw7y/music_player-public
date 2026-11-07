package app.songplayer.core.split

import app.songplayer.core.errors.SplitError

object SplitLimits {
    /** The size limit of Requirements R-2 (the user, 2026-11-05). */
    const val MAX_BYTES = 500L * 1024 * 1024

    /** The most songs one file may be split into (Requirements R-2, the user, 2026-11-05). */
    const val MAX_SONGS = 120
}

/**
 * Every Layer III frame of an MP3, in order (Technical Design 4.1). Arrays are indexed by frame; [first] is the index of the first audio
 * frame (1 when frame 0 is a Xing, Info or VBRI header, which describes the whole file and is never copied into a song).
 */
class Mp3Index(
    val offset: IntArray,
    val length: ShortArray,
    private val layout: ByteArray,
    /** The 9-bit (MPEG-1) or 8-bit (MPEG-2) `main_data_begin`: 0 means the frame needs nothing from the frame before it. */
    val mainDataBegin: ShortArray,
    /** The sum of `part2_3_length` over the frame's granules and channels: the bits of main data it uses. Near 0 in digital silence. */
    val mainDataBits: ShortArray,
    /** The bitrate index (1 to 14) of each frame: more than one among the audio frames means a variable-bit-rate file. */
    val bitrateIndex: ByteArray,
    val first: Int,
    /** Bytes that were not part of any frame or tag. */
    val junk: Long,
) {
    val count: Int get() = offset.size

    fun sampleRate(frame: Int): Int = SAMPLE_RATES[(layout[frame].toInt() shr 4) and 3][layout[frame].toInt() and 3]

    fun samples(frame: Int): Int = if ((layout[frame].toInt() shr 6) and 1 == 1) 1152 else 576

    fun isMono(frame: Int): Boolean = (layout[frame].toInt() shr 3) and 1 == 1

    fun frameLength(frame: Int): Int = length[frame].toInt()

    /** The packed layout byte of [frame] (MPEG-1 flag, version bits, mono flag, sample-rate index), for the cutter. */
    internal fun layoutOf(frame: Int): Int = layout[frame].toInt()

    /** The start time of [frame] in seconds, from the frame sizes before it (computed on demand: O(frame)). */
    fun startSeconds(frame: Int): Double {
        var t = 0.0
        for (i in 0 until frame) t += samples(i).toDouble() / sampleRate(i)
        return t
    }

    /** The start time of every frame, and one more entry for the end of the last: the map from time to frame (about 9 MB for 1.1 million frames). */
    fun startTimes(): DoubleArray {
        val t = DoubleArray(count + 1)
        for (i in 0 until count) t[i + 1] = t[i] + samples(i).toDouble() / sampleRate(i)
        return t
    }

    /** True when the audio frames use more than one bitrate: such a file needs a Xing header in each song cut from it. */
    val isVariableBitRate: Boolean get() {
        for (i in first + 1 until count) if (bitrateIndex[i] != bitrateIndex[first]) return true
        return false
    }

    internal companion object {
        // Rows: MPEG-2.5 (0), reserved (1), MPEG-2 (2), MPEG-1 (3), as the two version bits of the header.
        val SAMPLE_RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))
    }
}

/** Reads the frames of an MP3 without decoding anything: a port of `parse_frames` of the command-line tool (`cli/splitmp3.py`). */
object Mp3Frames {
    private const val SCAN_LIMIT = 1 shl 20

    /** About 18 hours of audio: a bound on the arrays, whatever the file size (a 500 MB file of tiny frames would otherwise hold millions). */
    private const val MAX_FRAMES = 2_500_000
    private val BITRATES_MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    /**
     * Throws [SplitError.TooLarge] before reading when the file is over [maxBytes], and [SplitError.Unreadable] when it is not an MP3 or
     * more than 1 % of it is not valid MP3 data. A frame cut off at the end of the file is not indexed (it counts as junk). Differences from
     * the tool, all deliberate: that cut-off frame; a frame followed by an ID3 or TAG tag is accepted in the resync as well; at most [MAX_FRAMES]
     * frames (else [SplitError.TooLarge]); the one error [SplitError.Unreadable] stands for "not an MP3" and "damaged".
     */
    fun scan(source: ByteSource, maxBytes: Long = SplitLimits.MAX_BYTES): Mp3Index {
        val n = source.length
        if (n > maxBytes) throw SplitError.TooLarge()
        if (n == 0L) throw SplitError.Unreadable()
        val r = Reader(source)
        val offsets = IntList()
        val lengths = ShortList()
        val layouts = ByteList()
        val mdb = ShortList()
        val bits = ShortList()
        val rates = ByteList()
        var first = 0
        var junk = 0L
        var i = if (r.at(0) == 'I'.code && r.at(1) == 'D'.code && r.at(2) == '3'.code && n >= 10) id3Size(r, 0L) else 0L
        // "No frame in the first MB" is measured from where the audio can start, after a leading ID3 tag (which may hold a large cover picture).
        val audioStart = i
        var syncedKey = -1
        while (i < n) {
            if (r.at(i) == 'I'.code && r.at(i + 1) == 'D'.code && r.at(i + 2) == '3'.code && i + 10 <= n) { i += id3Size(r, i); continue }
            if (r.at(i) == 'T'.code && i + 128 == n && r.at(i + 1) == 'A'.code && r.at(i + 2) == 'G'.code) break
            val h = header(r, i, n)
            if (h != null && (syncedKey == h.key || nextLooksRight(r, i + h.length, n))) {
                if (offsets.size == 0 && isHeaderFrame(r, i, h)) first = 1
                val crc = if (r.at(i + 1) and 1 == 1) 0 else 2
                val o = i + 4 + crc
                val begin = if (h.mpeg1) (r.at(o) shl 1) or (r.at(o + 1) shr 7) else r.at(o)
                if (offsets.size >= MAX_FRAMES) throw SplitError.TooLarge()
                offsets.add(i.toInt()); lengths.add(h.length.toShort()); layouts.add(h.layout)
                mdb.add(begin.toShort()); bits.add(mainDataBits(r, o, h).toShort()); rates.add(h.bitrateIndex.toByte())
                i += h.length
                syncedKey = h.key
                continue
            }
            syncedKey = -1
            var j = i + 1
            while (j < n) {
                val hh = if (r.at(j) == 0xFF) header(r, j, n) else null
                if (hh != null && nextLooksRight(r, j + hh.length, n)) break
                j++
            }
            junk += j - i
            i = j
            if (offsets.size == 0 && i - audioStart > SCAN_LIMIT) throw SplitError.Unreadable()
            if (junk > n / 100) throw SplitError.Unreadable()
        }
        if (offsets.size - first < 2) throw SplitError.Unreadable()
        return Mp3Index(offsets.toArray(), lengths.toArray(), layouts.toArray(), mdb.toArray(), bits.toArray(), rates.toArray(), first, junk)
    }

    private class Header(val length: Int, val mpeg1: Boolean, val mono: Boolean, val layout: Byte, val key: Int, val bitrateIndex: Int)

    /** The frame header at [i], or null: sync, MPEG version, Layer III, a usable bitrate and sample rate, and a frame that fits in the file. */
    private fun header(r: Reader, i: Long, n: Long): Header? {
        if (i + 4 > n) return null
        val b0 = r.at(i); val b1 = r.at(i + 1); val b2 = r.at(i + 2); val b3 = r.at(i + 3)
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val ver = (b1 shr 3) and 3
        if (ver == 1 || (b1 shr 1) and 3 != 1) return null
        val bi = b2 shr 4
        val si = (b2 shr 2) and 3
        if (bi == 0 || bi == 15 || si == 3) return null
        val mpeg1 = ver == 3
        val rate = Mp3Index.SAMPLE_RATES[ver][si]
        val length = (if (mpeg1) 144 else 72) * (if (mpeg1) BITRATES_MPEG1[bi] else BITRATES_MPEG2[bi]) * 1000 / rate + ((b2 shr 1) and 1)
        if (i + length > n) return null
        val mono = (b3 shr 6) == 3
        val layout = ((if (mpeg1) 1 else 0) shl 6) or (ver shl 4) or ((if (mono) 1 else 0) shl 3) or si
        return Header(length, mpeg1, mono, layout.toByte(), (ver shl 2) or si, bi)
    }

    private fun nextLooksRight(r: Reader, next: Long, n: Long): Boolean {
        if (next >= n) return true
        if (header(r, next, n) != null) return true
        return (r.at(next) == 'T'.code && r.at(next + 1) == 'A'.code && r.at(next + 2) == 'G'.code) ||
            (r.at(next) == 'I'.code && r.at(next + 1) == 'D'.code && r.at(next + 2) == '3'.code)
    }

    private fun sideInfoSize(h: Header) = if (h.mpeg1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)

    private fun isHeaderFrame(r: Reader, i: Long, h: Header): Boolean {
        val o = i + 4 + (if (r.at(i + 1) and 1 == 1) 0 else 2) + sideInfoSize(h)
        val tag = String(CharArray(4) { r.at(o + it).toChar() })
        val vbri = String(CharArray(4) { r.at(i + 36 + it).toChar() })
        return tag == "Xing" || tag == "Info" || vbri == "VBRI"
    }

    private fun id3Size(r: Reader, i: Long): Long =
        10L + ((r.at(i + 6) and 127) shl 21 or (r.at(i + 7) and 127 shl 14) or (r.at(i + 8) and 127 shl 7) or (r.at(i + 9) and 127))

    /** Sums `part2_3_length` (12 bits) of every granule and channel from the side information that starts at [o]. */
    private fun mainDataBits(r: Reader, o: Long, h: Header): Int {
        val channels = if (h.mono) 1 else 2
        val granules = if (h.mpeg1) 2 else 1
        // Bits before the first granule: main_data_begin (9 or 8), private bits (5/3 or 1/2), and for MPEG-1 four scfsi bits per channel.
        val head = if (h.mpeg1) 9 + (if (h.mono) 5 else 3) + 4 * channels else 8 + (if (h.mono) 1 else 2)
        val block = if (h.mpeg1) 59 else 63
        var sum = 0
        for (k in 0 until granules * channels) {
            val bit = head + k * block
            var v = 0
            for (b in 0 until 12) v = (v shl 1) or ((r.at(o + (bit + b) / 8) shr (7 - (bit + b) % 8)) and 1)
            sum += v
        }
        return sum
    }

    /** A 1 MB window over the source: bytes outside the file read as -1. */
    private class Reader(private val source: ByteSource) {
        private val buf = ByteArray(WINDOW)
        private var start = -1L
        private var len = 0

        fun at(pos: Long): Int {
            if (pos < 0 || pos >= source.length) return -1
            if (pos < start || pos >= start + len) {
                start = pos
                len = 0
                while (len < WINDOW) {
                    val got = source.read(start + len, buf, len, WINDOW - len)
                    if (got <= 0) break
                    len += got
                }
                if (len == 0) return -1
            }
            return buf[(pos - start).toInt()].toInt() and 0xFF
        }

        private companion object { const val WINDOW = 1 shl 20 }
    }

    private class IntList { var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
    private class ShortList { var a = ShortArray(1024); var size = 0
        fun add(v: Short) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
    private class ByteList { var a = ByteArray(1024); var size = 0
        fun add(v: Byte) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
}
