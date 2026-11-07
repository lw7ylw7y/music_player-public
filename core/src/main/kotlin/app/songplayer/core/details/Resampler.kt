package app.songplayer.core.details

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Turns interleaved 16-bit audio of any rate and channel count into mono 16-bit audio at [OUT_RATE] Hz,
 * the input the fingerprint algorithm wants. Written for this app: a windowed-sinc low-pass filter (8 zero crossings) read from a
 * precomputed table at the nearest entry (1,024 per crossing; the error is far below what a fingerprint notices). Channels are averaged. It works on a stream, so a decoder can
 * hand over its buffers as they come.
 *
 * The first and last few milliseconds are treated as silence outside the audio; the fingerprint does not care.
 */
class Resampler(private val inRate: Int, private val channels: Int) {
    init {
        require(inRate > 0 && channels > 0)
    }

    private val step = inRate.toDouble() / OUT_RATE
    private val scale = minOf(1.0, OUT_RATE.toDouble() / inRate)
    private val halfWidth = ZERO_CROSSINGS / scale
    private val passThrough = inRate == OUT_RATE
    private val tapStep = Math.round(scale * TABLE_RES * ONE).toInt()   // table position moved per input sample, 16.16 fixed point

    private var mono = DoubleArray(4096)
    private var monoLength = 0
    private var monoStart = 0L          // global input index of mono[0]
    private var nextOut = 0L

    /** Appends [length] interleaved samples; every finished output sample goes to [out]. */
    fun push(samples: ShortArray, length: Int, out: (Short) -> Unit) {
        require(length % channels == 0) { "a split frame" }
        val frames = length / channels
        ensureRoom(frames)
        for (f in 0 until frames) {
            var sum = 0
            val base = f * channels
            for (c in 0 until channels) sum += samples[base + c]
            mono[monoLength++] = sum.toDouble() / channels
        }
        if (passThrough) {
            // No filtering needed: the rounded average is the output.
            for (i in 0 until monoLength) out(toShort(mono[i]))
            monoStart += monoLength
            monoLength = 0
            return
        }
        produce(out)
    }

    private fun produce(out: (Short) -> Unit) {
        val end = monoStart + monoLength
        while (true) {
            val center = nextOut * step
            val first = ceil(center - halfWidth).toLong()
            val last = floor(center + halfWidth).toLong()
            if (last >= end) break
            // Walk the taps with a fixed-point position in the kernel table: no per-tap abs, multiply or interpolation.
            // Left of the centre the position falls by one step per tap, right of it the position rises.
            val centerFloor = floor(center).toLong()
            var acc = 0.0
            var j = (maxOf(first, 0L) - monoStart).toInt()
            var n = maxOf(first, 0L)
            var fx = ((center - n) * scale * TABLE_RES * ONE).toInt()
            while (n <= centerFloor) { acc += mono[j++] * TABLE[maxOf(fx, 0) shr 16]; fx -= tapStep; n++ }
            fx = ((n - center) * scale * TABLE_RES * ONE).toInt()
            while (n <= last) { acc += mono[j++] * TABLE[minOf(fx shr 16, TABLE_LAST)]; fx += tapStep; n++ }
            out(toShort(acc * scale))
            nextOut++
        }
        // Drop input nobody needs again.
        val keepFrom = ceil(nextOut * step - halfWidth).toLong().coerceAtLeast(0L)
        val drop = (keepFrom - monoStart).toInt()
        if (drop > 0) {
            System.arraycopy(mono, drop, mono, 0, monoLength - drop)
            monoLength -= drop
            monoStart += drop
        }
    }

    private fun ensureRoom(frames: Int) {
        if (monoLength + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, monoLength + frames))
    }

    private fun toShort(v: Double): Short = Math.round(v).coerceIn(-32768L, 32767L).toInt().toShort()

    companion object {
        const val OUT_RATE = 11025
        private const val ZERO_CROSSINGS = 8
        private const val TABLE_RES = 1024
        private const val ONE = 65536
        private const val TABLE_LAST = ZERO_CROSSINGS * TABLE_RES + 3

        /** sinc(u) times a Blackman window over [0, ZERO_CROSSINGS], for u in steps of 1/TABLE_RES. */
        private val TABLE = DoubleArray(ZERO_CROSSINGS * TABLE_RES + 4) { i ->
            val u = i.toDouble() / TABLE_RES
            val sinc = if (u == 0.0) 1.0 else sin(PI * u) / (PI * u)
            val t = u / ZERO_CROSSINGS
            val window = 0.42 + 0.5 * cos(PI * t) + 0.08 * cos(2 * PI * t)
            if (u >= ZERO_CROSSINGS) 0.0 else sinc * window
        }
    }
}
