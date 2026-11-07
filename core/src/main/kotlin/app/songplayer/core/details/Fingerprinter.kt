package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The audio fingerprint of a song, in the format AcoustID takes (Technical Design 5.8). This is Chromaprint's
 * default algorithm (its "test2" configuration), written from its MIT-licensed source with our own FFT; no code
 * from FFmpeg. Feed it audio in any rate and channel count; it fingerprints the first [MAX_AUDIO_S] seconds.
 *
 * Pipeline: mono 11,025 Hz audio, frames of 4,096 samples every 1,365, Hamming window, FFT energy per bin,
 * 12 pitch classes between 28 and 3,520 Hz, a 5-tap smoothing over time, unit length, then 16 filters over a
 * 12-column by time image, each quantised to 2 bits, giving 32 bits per step; the steps are delta-compressed
 * and written in URL-safe base64.
 *
 * Not thread-safe; one instance per song.
 */
class Fingerprinter(sampleRate: Int, channels: Int) {
    private val resampler = Resampler(sampleRate, channels)
    private val window = DoubleArray(FRAME)
    private var windowLength = 0
    private var samplesSeen = 0L
    private val image = ArrayList<DoubleArray>()          // integral image: one row per time step
    private val smoothing = Array(SMOOTH_RING) { DoubleArray(BANDS) }
    private var smoothOffset = 0
    private var smoothSize = 1
    private val subfingerprints = ArrayList<Int>()
    private val re = DoubleArray(HALF)
    private val im = DoubleArray(HALF)
    private var finished = false

    /** True once the first [MAX_AUDIO_S] seconds have been taken; the decoder can stop. */
    val isFull: Boolean get() = samplesSeen >= MAX_SAMPLES

    /** Appends [length] interleaved samples (frames times channels). Anything after [MAX_AUDIO_S] seconds is ignored. */
    fun consume(samples: ShortArray, length: Int) {
        check(!finished)
        if (isFull) return
        resampler.push(samples, length) { s ->
            if (samplesSeen < MAX_SAMPLES) {
                samplesSeen++
                addSample(s)
            }
        }
    }

    /** The encoded fingerprint. Throws [DetailsError.NotDecodable] when less than [MIN_AUDIO_S] seconds of audio came in. */
    fun finish(): String {
        check(!finished)
        finished = true
        if (samplesSeen < MIN_AUDIO_S.toLong() * Resampler.OUT_RATE) throw DetailsError.NotDecodable()
        return encode(subfingerprints.toIntArray())
    }

    private fun addSample(s: Short) {
        // Frames overlap: keep the last FRAME samples and cut a frame every STEP once the first is full.
        window[windowLength++] = s.toDouble()
        if (windowLength == FRAME) {
            processFrame(window)
            System.arraycopy(window, STEP, window, 0, FRAME - STEP)
            windowLength = FRAME - STEP
        }
    }

    private fun processFrame(samples: DoubleArray) {
        // A real signal of 4,096 samples needs only a 2,048-point complex FFT: even samples are the real parts, odd samples the
        // imaginary parts, and the two spectra are told apart afterwards. Only the bins the chroma uses are computed.
        for (i in 0 until HALF) {
            re[i] = samples[2 * i] * HAMMING[2 * i]
            im[i] = samples[2 * i + 1] * HAMMING[2 * i + 1]
        }
        fft(re, im)
        val features = DoubleArray(BANDS)
        for (k in MIN_INDEX until MAX_INDEX) {
            val a = re[k]; val b = im[k]
            val c = re[(HALF - k) and (HALF - 1)]; val d = im[(HALF - k) and (HALF - 1)]
            val er = (a + c) * 0.5; val ei = (b - d) * 0.5
            val or = (b + d) * 0.5; val oi = (c - a) * 0.5
            val wr = COS[k]; val wi = SIN[k]
            val xr = er + wr * or - wi * oi
            val xi = ei + wr * oi + wi * or
            features[NOTE[k]] += xr * xr + xi * xi
        }
        smooth(features)
    }

    private fun smooth(features: DoubleArray) {
        System.arraycopy(features, 0, smoothing[smoothOffset], 0, BANDS)
        smoothOffset = (smoothOffset + 1) % SMOOTH_RING
        if (smoothSize >= SMOOTH_COEFFS.size) {
            val offset = (smoothOffset + SMOOTH_RING - SMOOTH_COEFFS.size) % SMOOTH_RING
            val result = DoubleArray(BANDS)
            for (i in 0 until BANDS) for (j in SMOOTH_COEFFS.indices) {
                result[i] += smoothing[(offset + j) % SMOOTH_RING][i] * SMOOTH_COEFFS[j]
            }
            normalise(result)
            addRow(result)
        } else {
            smoothSize++
        }
    }

    private fun normalise(v: DoubleArray) {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm < NORM_THRESHOLD) v.fill(0.0) else for (i in v.indices) v[i] /= norm
    }

    private fun addRow(row: DoubleArray) {
        // image[r][c] = sum of all cells with row <= r and column <= c.
        val cumulative = DoubleArray(BANDS)
        var rowSum = 0.0
        val previous = image.lastOrNull()
        for (c in 0 until BANDS) {
            rowSum += row[c]
            cumulative[c] = rowSum + (previous?.get(c) ?: 0.0)
        }
        image.add(cumulative)
        if (image.size >= MAX_FILTER_WIDTH) subfingerprints.add(subfingerprint(image.size - MAX_FILTER_WIDTH))
    }

    /** Sum of the cells in rows [r1, r2) and columns [c1, c2). */
    private fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val a = image[r2 - 1][c2 - 1]
        val b = if (c1 > 0) image[r2 - 1][c1 - 1] else 0.0
        val c = if (r1 > 0) image[r1 - 1][c2 - 1] else 0.0
        val d = if (r1 > 0 && c1 > 0) image[r1 - 1][c1 - 1] else 0.0
        return a - b - c + d
    }

    private fun subfingerprint(x: Int): Int {
        var bits = 0
        for (cl in CLASSIFIERS) {
            val v = cl.apply(x)
            val q = if (v < cl.t1) (if (v < cl.t0) 0 else 1) else (if (v < cl.t2) 2 else 3)
            bits = (bits shl 2) or GRAY[q]
        }
        return bits
    }

    private fun subtractLog(a: Double, b: Double) = ln((1.0 + a) / (1.0 + b))

    private inner class Classifier(
        val type: Int, val y: Int, val height: Int, val width: Int,
        val t0: Double, val t1: Double, val t2: Double,
    ) {
        fun apply(x: Int): Double {
            val w = width
            val h = height
            return when (type) {
                0 -> subtractLog(area(x, y, x + w, y + h), 0.0)
                1 -> {
                    val h2 = h / 2
                    subtractLog(area(x, y + h2, x + w, y + h), area(x, y, x + w, y + h2))
                }
                2 -> {
                    val w2 = w / 2
                    subtractLog(area(x + w2, y, x + w, y + h), area(x, y, x + w2, y + h))
                }
                3 -> {
                    val w2 = w / 2
                    val h2 = h / 2
                    subtractLog(
                        area(x, y + h2, x + w2, y + h) + area(x + w2, y, x + w, y + h2),
                        area(x, y, x + w2, y + h2) + area(x + w2, y + h2, x + w, y + h),
                    )
                }
                4 -> {
                    val h3 = h / 3
                    subtractLog(
                        area(x, y + h3, x + w, y + 2 * h3),
                        area(x, y, x + w, y + h3) + area(x, y + 2 * h3, x + w, y + h),
                    )
                }
                else -> {
                    val w3 = w / 3
                    subtractLog(
                        area(x + w3, y, x + 2 * w3, y + h),
                        area(x, y, x + w3, y + h) + area(x + 2 * w3, y, x + w, y + h),
                    )
                }
            }
        }
    }

    private val CLASSIFIERS = listOf(
        Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )

    companion object {
        const val MIN_AUDIO_S = 10
        const val MAX_AUDIO_S = 120
        private const val MAX_SAMPLES = MAX_AUDIO_S.toLong() * Resampler.OUT_RATE

        private const val FRAME = 4096
        private const val HALF = FRAME / 2
        private const val STEP = 1365
        private const val BANDS = 12
        private const val MAX_FILTER_WIDTH = 16
        private const val NORM_THRESHOLD = 0.01
        private const val SMOOTH_RING = 8
        private val SMOOTH_COEFFS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)
        private val GRAY = intArrayOf(0, 1, 3, 2)

        private val HAMMING = DoubleArray(FRAME) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME - 1))) / Short.MAX_VALUE
        }

        private fun index(freq: Double) = Math.round(FRAME * freq / Resampler.OUT_RATE).toInt()
        private val MIN_INDEX = maxOf(1, index(28.0))
        private val MAX_INDEX = minOf(FRAME / 2, index(3520.0))

        /** Pitch class (0..11) of each FFT bin in [MIN_INDEX, MAX_INDEX). */
        private val NOTE = IntArray(FRAME / 2 + 1) { i ->
            if (i < MIN_INDEX || i >= MAX_INDEX) 0 else {
                val freq = i.toDouble() * Resampler.OUT_RATE / FRAME
                val octave = log(freq / (440.0 / 16.0), 2.0)
                (BANDS * (octave - floor(octave))).toInt()
            }
        }

        private val COS = DoubleArray(FRAME / 2) { cos(2 * PI * it / FRAME) }
        private val SIN = DoubleArray(FRAME / 2) { -sin(2 * PI * it / FRAME) }

        /** In-place radix-2 complex FFT of length [HALF]; the twiddle table is the one of a [FRAME]-point transform, read with a stride. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = HALF
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val stride = FRAME / len
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val wr = COS[k * stride]
                        val wi = SIN[k * stride]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                    }
                    start += len
                }
                len = len shl 1
            }
        }

        private const val NORMAL_BITS = 3
        private const val MAX_NORMAL = (1 shl NORMAL_BITS) - 1
        private const val ALGORITHM = 1
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Chromaprint's compressed format: header, then 3-bit bit-position gaps of each step xor the one before, then 5-bit overflows. */
        internal fun encode(data: IntArray): String {
            val normal = ArrayList<Int>()
            val exceptional = ArrayList<Int>()
            fun add(x0: Int) {
                var x = x0
                var bit = 1
                var last = 0
                while (x != 0) {
                    if (x and 1 != 0) {
                        val value = bit - last
                        if (value >= MAX_NORMAL) { normal.add(MAX_NORMAL); exceptional.add(value - MAX_NORMAL) } else normal.add(value)
                        last = bit
                    }
                    x = x ushr 1
                    bit++
                }
                normal.add(0)
            }
            for (i in data.indices) add(if (i == 0) data[0] else data[i] xor data[i - 1])
            val out = ArrayList<Byte>()
            out.add(ALGORITHM.toByte())
            out.add((data.size shr 16).toByte()); out.add((data.size shr 8).toByte()); out.add(data.size.toByte())
            packBits(normal, 3, out)
            packBits(exceptional, 5, out)
            return base64(out)
        }

        /** Packs values of [bits] bits each, least significant bits first, padding the last byte with zeros. */
        private fun packBits(values: List<Int>, bits: Int, out: MutableList<Byte>) {
            var acc = 0
            var have = 0
            for (v in values) {
                acc = acc or ((v and ((1 shl bits) - 1)) shl have)
                have += bits
                while (have >= 8) { out.add(acc.toByte()); acc = acc ushr 8; have -= 8 }
            }
            if (have > 0) out.add(acc.toByte())
        }

        private fun base64(bytes: List<Byte>): String {
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xff
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
                val n = bytes.size - i
                sb.append(ALPHABET[b0 shr 2])
                sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
                if (n > 1) sb.append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
                if (n > 2) sb.append(ALPHABET[b2 and 63])
                i += 3
            }
            return sb.toString()
        }
    }
}
