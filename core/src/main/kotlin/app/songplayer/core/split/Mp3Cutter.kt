package app.songplayer.core.split

import app.songplayer.core.errors.SplitError

/**
 * One new song: the frames [firstFrame] until [endFrame] of the original, copied byte for byte from [byteStart] until [byteEnd]; [xingHeader] is
 * a frame to write first when the source has a variable bit rate (the source's own header describes the whole file), else null.
 */
class SongSpan(
    val number: Int,
    val firstFrame: Int,
    val endFrame: Int,
    val byteStart: Long,
    val byteEnd: Long,
    val xingHeader: ByteArray?,
) {
    val frames: Int get() = endFrame - firstFrame
    val bytes: Long get() = byteEnd - byteStart + (xingHeader?.size ?: 0)
}

/**
 * The lossless cutter (Technical Design 4.4): nothing is decoded or re-encoded; a song is a range of whole frames. A cut falls on a frame that
 * needs nothing from the frame before it (`main_data_begin == 0`), so the new song starts cleanly. A port of `snap_to_frame`, `plan_songs` and
 * `build_xing` of the command-line tool, plus the case the tool does not have (a cut the user moved) and a seek table in the Xing header.
 */
object Mp3Cutter {
    /** How far from a moved cut a self-contained frame is still "where the user put it", in seconds. */
    const val NEAR_SEC = 0.5

    fun spans(index: Mp3Index, plan: SplitPlan): List<SongSpan> {
        val starts = index.startTimes()
        val cuts = plan.cuts
        val bounds = ArrayList<Int>()
        bounds.add(index.first)
        for ((k, cut) in cuts.withIndex()) {
            // Each cut stays between the frame of the one before it and the frame of the one after it, so two cuts never meet.
            val low = bounds.last() + 1
            val high = if (k + 1 < cuts.size) frameAt(index, starts, cuts[k + 1].timeSec) - 1 else index.count - 1
            if (low > high) throw SplitError.CutsTooClose()
            bounds.add(cutFrame(index, starts, cut, low, high))
        }
        bounds.add(index.count)
        val vbr = index.isVariableBitRate
        return (0 until bounds.size - 1).map { k ->
            val a = bounds[k]
            val z = bounds[k + 1]
            val start = index.offset[a].toLong()
            val end = index.offset[z - 1].toLong() + index.frameLength(z - 1)
            val head = if (vbr) xingHeader(index, starts, a, z - a, end - start) else null
            SongSpan(k + 1, a, z, start, end, head)
        }
    }

    /** The frame that contains time [t]: the last one that starts at or before it, or -1 before the first. */
    private fun frameAt(index: Mp3Index, starts: DoubleArray, t: Double): Int {
        if (starts[0] > t) return -1
        var lo = 0
        var hi = index.count - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    /** The frame that starts the song after [cut], within the frames [lowLimit] to [highLimit]. */
    fun cutFrame(index: Mp3Index, starts: DoubleArray, cut: PlanCut, lowLimit: Int = index.first + 1, highLimit: Int = index.count - 1): Int {
        val lowest = maxOf(index.first + 1, lowLimit)
        val highest = minOf(index.count - 1, highLimit)
        val mid = frameAt(index, starts, cut.timeSec).coerceIn(lowest, highest)
        fun nearestSelfContained(lo: Int, hi: Int): Int? =
            (maxOf(lo, lowest)..minOf(hi, highest)).filter { index.mainDataBegin[it].toInt() == 0 }
                .minWithOrNull(compareBy<Int> { Math.abs(it - mid) }.thenBy { it })
        val half = cut.halfGapSec
        // The app's own proposal looks inside its quiet stretch (the tool's rule); a cut the user moved or placed within half a second.
        val reach = half ?: NEAR_SEC
        nearestSelfContained(frameAt(index, starts, cut.timeSec - reach), frameAt(index, starts, cut.timeSec + reach))?.let { return it }
        // None there (an encoder that rarely starts a frame without the bit reservoir): the cut's own frame, as the tool's `snap_to_frame` does.
        // The first frame or two of the new song may then miss the reservoir bytes of the frame before, which the decoder hides; the cut sits in
        // a quiet stretch. Going to a self-contained frame elsewhere would move the cut by minutes and make a song a few seconds long.
        return mid
    }

    /**
     * A silent frame that holds a Xing header (frame count, byte count and the 100-entry seek table), so players show the right length of a
     * variable-bit-rate song and seek in it. The song's own first frame gives the version, sample rate and channel mode. The command-line
     * tool writes no seek table; this is the one difference.
     */
    fun xingHeader(index: Mp3Index, starts: DoubleArray, firstFrame: Int, frames: Int, bodyBytes: Long): ByteArray {
        val layout = index.layoutOf(firstFrame)
        val mpeg1 = (layout shr 6) and 1 == 1
        val ver = (layout shr 4) and 3
        val mono = (layout shr 3) and 1 == 1
        val sri = layout and 3
        val rate = Mp3Index.SAMPLE_RATES[ver][sri]
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val need = 4 + side + 16 + TOC_ENTRIES
        val kbps = if (mpeg1) MPEG1_KBPS else MPEG2_KBPS
        var bi = 1
        var length = 0
        while (bi <= 14) {
            length = (if (mpeg1) 144 else 72) * kbps[bi] * 1000 / rate
            if (length >= need) break
            bi++
        }
        check(length >= need) { "no bitrate gives a frame big enough for a Xing header" }
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = (0xE0 or (ver shl 3) or (1 shl 1) or 1).toByte()
        frame[2] = ((bi shl 4) or (sri shl 2)).toByte()
        frame[3] = ((if (mono) 3 else 0) shl 6).toByte()
        val o = 4 + side
        "Xing".forEachIndexed { k, c -> frame[o + k] = c.code.toByte() }
        putInt(frame, o + 4, 7)                                   // flags: frame count, byte count and seek table
        putInt(frame, o + 8, frames)
        val total = bodyBytes + length
        putInt(frame, o + 12, total.toInt())
        // Seek table: entry i is where, as a fraction of the file in 256ths, the audio at i percent of the song's length starts.
        val t0 = starts[firstFrame]
        val span = starts[firstFrame + frames] - t0
        var f = firstFrame
        for (i in 0 until TOC_ENTRIES) {
            val t = t0 + span * i / TOC_ENTRIES
            while (f + 1 < firstFrame + frames && starts[f + 1] <= t) f++
            val position = length + (index.offset[f].toLong() - index.offset[firstFrame])
            frame[o + 16 + i] = (position * 256 / total).coerceAtMost(255).toByte()
        }
        return frame
    }

    private const val TOC_ENTRIES = 100

    private fun putInt(b: ByteArray, at: Int, v: Int) { b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte() }

    private val MPEG1_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
}
