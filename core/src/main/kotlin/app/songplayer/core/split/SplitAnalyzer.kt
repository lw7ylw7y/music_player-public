package app.songplayer.core.split

import kotlin.math.ln

/**
 * True loudness of a stretch of the original, by decoding it (the app: `MediaCodec`; the tests: a fake). One value in dB per
 * [GapFinder.HOP] seconds, as the command-line tool computes it (RMS of the mono mix, 20 log10, floor 1e-5). May return fewer values than
 * asked at the end of the file. The start is a time on the grid of [GapFinder.HOP] seconds.
 */
interface LoudnessSource {
    /** [steps] values from [startSec] on, one per [GapFinder.HOP] seconds. */
    fun decodeDb(startSec: Double, steps: Int): FloatArray
}

/** What the analysis found, and what it cost (the numbers P-B asks for). */
class Analysis(
    val durationSec: Double,
    val cuts: List<Cut>,
    val levels: GapFinder.Levels,
    /** False when the side information did not follow the loudness on the sample windows: the whole file was decoded instead. */
    val proxyUsable: Boolean,
    val decodedSec: Double,
    val windows: Int,
    val regions: Int,
)

/**
 * Finds the cuts of an indexed MP3 without decoding all of it (Technical Design 4.2, option A). The bits of main data a frame uses follow
 * the loudness (digital silence uses almost none), so they give a rough loudness curve for free. A few one-second windows spread over the file
 * are decoded to fit that curve to true dB; the stretches that look quiet on it are decoded for their true level; [GapFinder] then runs on the
 * curve made of both. If the side information does not follow the loudness on the windows (a constant-size file, say), the whole file is
 * decoded in chunks instead, which is slow but the tool's own method.
 */
object SplitAnalyzer {
    const val WINDOW_SEC = 1.0
    const val WINDOW_EVERY_SEC = 60.0
    const val MAX_WINDOWS = 600
    /** How far above the quiet threshold (dB on the fitted curve) a step still counts as a candidate to decode. */
    const val CANDIDATE_MARGIN_DB = 6.0
    const val REGION_PAD_SEC = 1.0
    const val MERGE_GAP_SEC = 2.0
    /** The fitted line must explain at least this share of the windows' variation, else the proxy is not trusted. */
    const val MIN_FIT = 0.5
    const val CHUNK_SEC = 60.0

    /** [progress] gets 0.0 to 1.0; [cancelled] is polled between decodes and ends the analysis with a [java.util.concurrent.CancellationException]. */
    fun analyze(
        index: Mp3Index,
        loudness: LoudnessSource,
        progress: (Double) -> Unit = {},
        cancelled: () -> Boolean = { false },
        /** Where it is, in numbers, for the debug screen: the stage ("windows", "fit", "regions", "all"), how many are done of how many, and the seconds decoded so far. */
        onStep: (stage: String, done: Int, total: Int, decodedSec: Double) -> Unit = { _, _, _, _ -> },
    ): Analysis {
        val starts = index.startTimes()
        val duration = starts[index.count]
        val n = (duration / GapFinder.HOP).toInt()
        val curve = FloatArray(n)
        val trueMask = BooleanArray(n)         // the steps whose value was decoded, not fitted
        fun check() { if (cancelled()) throw java.util.concurrent.CancellationException() }
        if (n * GapFinder.HOP < 2 * GapFinder.MIN_SONG) {
            return Analysis(duration, emptyList(), GapFinder.Levels(0.0, 0.0, 0.0), true, 0.0, 0, 0)
        }
        val proxy = proxyCurve(index, starts, n)
        var decoded = 0.0

        fun decode(fromStep: Int, steps: Int): FloatArray { check(); decoded += steps * GapFinder.HOP; return loudness.decodeDb(fromStep * GapFinder.HOP, steps).also { put(curve, fromStep, it, trueMask) } }

        // 1. Sample windows: fit the proxy to true dB.
        val windowStarts = windowStarts(duration)
        val truth = ArrayList<Pair<Int, FloatArray>>()
        val windowSteps = Math.round(WINDOW_SEC / GapFinder.HOP).toInt()
        for ((k, t) in windowStarts.withIndex()) {
            val at = Math.round(t / GapFinder.HOP).toInt()
            truth += at to decode(at, windowSteps)
            progress(0.3 * (k + 1) / windowStarts.size)
            onStep("windows", k + 1, windowStarts.size, decoded)
        }
        val fit = fit(proxy, truth)
        onStep("fit", 0, 0, decoded)
        var regions = 0
        if (fit == null) {
            // 2a. The proxy is no use here: decode everything, in chunks.
            var at = 0.0
            while (at < duration) {
                val len = minOf(CHUNK_SEC, duration - at)
                decode(Math.round(at / GapFinder.HOP).toInt(), Math.round(len / GapFinder.HOP).toInt())
                at += len
                progress(0.3 + 0.7 * at / duration)
                onStep("all", Math.round(at / CHUNK_SEC).toInt(), Math.ceil(duration / CHUNK_SEC).toInt(), decoded)
            }
            regions = 1
        } else {
            // 2b. The fitted curve everywhere, true values where it looks quiet.
            for (i in 0 until n) curve[i] = (fit.a + fit.b * proxy[i]).toFloat()
            for ((at, db) in truth) put(curve, at, db, trueMask)
            // Pass 1 decodes what looks quiet on the fitted curve. Real silence is often above what the fit predicts for zero bits, which
            // moves the threshold up once true values are in; pass 2 decodes what the new threshold also calls quiet.
            for (pass in 0 until 2) {
                val threshold = GapFinder.levels(curve).threshold + CANDIDATE_MARGIN_DB
                val spans = candidateRegions(curve, threshold, n).filter { span -> span.any { !trueMask[it] } }
                regions += spans.size
                onStep("regions", 0, spans.size, decoded)
                for ((k, span) in spans.withIndex()) {
                    decode(span.first, span.last + 1 - span.first)
                    progress(0.3 + 0.35 * (pass + (k + 1.0) / spans.size))
                    onStep("regions", k + 1, spans.size, decoded)
                }
            }
        }
        progress(1.0)
        return Analysis(duration, GapFinder.findCuts(curve), GapFinder.levels(curve), fit != null, decoded, windowStarts.size, regions)
    }

    private fun put(curve: FloatArray, at: Int, values: FloatArray, mask: BooleanArray) {
        for (k in values.indices) if (at + k in curve.indices) { curve[at + k] = values[k]; mask[at + k] = true }
    }

    /** ln(1 + main-data bits) of the frame that plays at each step. */
    private fun proxyCurve(index: Mp3Index, starts: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var f = 0
        for (i in 0 until n) {
            val t = i * GapFinder.HOP
            while (f + 1 < index.count && starts[f + 1] <= t) f++
            out[i] = ln(1.0 + index.mainDataBits[f].toInt().coerceAtLeast(0))
        }
        return out
    }

    private fun windowStarts(duration: Double): List<Double> {
        val count = minOf(MAX_WINDOWS, maxOf(2, (duration / WINDOW_EVERY_SEC).toInt()))
        val room = duration - WINDOW_SEC
        return List(count) { Math.round(room * it / (count - 1) / GapFinder.HOP) * GapFinder.HOP }   // on the grid of steps
    }

    private class Line(val a: Double, val b: Double)

    /** Least squares of true dB on the proxy over the window steps; null when it explains too little or slopes the wrong way. */
    private fun fit(proxy: DoubleArray, truth: List<Pair<Int, FloatArray>>): Line? {
        var count = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for ((at, db) in truth) for (k in db.indices) {
            if (at + k >= proxy.size) break
            val x = proxy[at + k]; val y = db[k].toDouble()
            count++; sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y
        }
        if (count < 10) return null
        val vx = sxx - sx * sx / count
        val vy = syy - sy * sy / count
        if (vx < 1e-9 || vy < 1e-9) return null
        val cov = sxy - sx * sy / count
        val b = cov / vx
        val r2 = cov * cov / (vx * vy)
        if (b <= 0 || r2 < MIN_FIT) return null
        return Line((sy - b * sx) / count, b)
    }

    /** Runs of steps below [threshold], padded and merged: the stretches whose true level is worth decoding. */
    private fun candidateRegions(curve: FloatArray, threshold: Double, n: Int): List<IntRange> {
        val pad = (REGION_PAD_SEC / GapFinder.HOP).toInt()
        val merge = (MERGE_GAP_SEC / GapFinder.HOP).toInt()
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (curve[i] < threshold) {
                var j = i
                while (j < n && curve[j] < threshold) j++
                val from = maxOf(0, i - pad)
                val to = minOf(n - 1, j + pad)
                val last = out.lastOrNull()
                if (last != null && from - last.last <= merge) out[out.size - 1] = last.first..to else out += from..to
                i = j
            } else i++
        }
        return out
    }
}
