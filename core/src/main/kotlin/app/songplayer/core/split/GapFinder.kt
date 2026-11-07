package app.songplayer.core.split

/** Why a cut is only uncertain (the words of the command-line tool, which the review screen can say plainly). */
enum class CutReason { SimilarGapWithinAMinute, ShortGap, NotFullySilent }

/**
 * One proposed boundary between two songs: the middle of a quiet stretch. [strength] is the length weighted by depth, how clearly this is a
 * song gap; [reason] is null for a confident cut.
 */
data class Cut(
    val timeSec: Double,
    val lengthSec: Double,
    val depthDb: Double,
    val confident: Boolean,
    val reason: CutReason?,
    val strength: Double,
)

/**
 * Finds the cuts in a loudness curve (one value in dB per [HOP] seconds): a port of `find_cuts` and `missed_stretches` of the command-line
 * tool (`cli/splitmp3.py`), so the app and the tool agree (Technical Design 4.3). It takes plain numbers and uses no `android.*`.
 */
object GapFinder {
    const val HOP = 0.02            // seconds per loudness step
    const val MIN_SONG = 60.0       // a song is at least a minute: two cuts closer than this cannot both be real
    const val MIN_CANDIDATE = 0.3   // the shortest quiet run considered at all
    const val CONFIDENT_GAP = 2.0   // a confident gap is at least this long...
    const val CONFIDENT_DEPTH = 10.0 // ...and within this many dB of the file's silence floor
    const val MISSED_MIN = 480.0    // a stretch longer than this and 2.5 times the median song is flagged

    /** The levels the finder works from: the silence floor (2nd percentile), the median, and the threshold a quarter of the way up. */
    data class Levels(val floor: Double, val median: Double, val threshold: Double)

    fun levels(db: FloatArray): Levels {
        val sorted = db.copyOf().also { java.util.Arrays.sort(it) }
        val floor = percentile(sorted, 2.0)
        val med = percentile(sorted, 50.0)
        return Levels(floor, med, floor + 0.25 * (med - floor))
    }

    /** Confident and uncertain cuts, sorted by time. A file shorter than two minimum songs has none. */
    fun findCuts(db: FloatArray, minGap: Double = CONFIDENT_GAP): List<Cut> {
        val n = db.size
        if (n * HOP < 2 * MIN_SONG) return emptyList()
        val (floor, _, thr) = levels(db)
        val candidates = ArrayList<Cut>()
        var i = 0
        while (i < n) {
            if (db[i] < thr) {
                var j = i
                var lowest = db[i]
                while (j < n && db[j] < thr) { if (db[j] < lowest) lowest = db[j]; j++ }
                val length = (j - i) * HOP
                if (length >= MIN_CANDIDATE) {
                    val t = (i + j) / 2.0 * HOP
                    if (t >= MIN_SONG && n * HOP - t >= MIN_SONG) {
                        val depth = lowest.toDouble()
                        candidates.add(Cut(t, length, depth, true, null, length * (1 + maxOf(0.0, thr - depth) / 20)))
                    }
                }
                i = j
            } else {
                i++
            }
        }
        val accepted = ArrayList<Cut>()
        val rival = java.util.IdentityHashMap<Cut, Boolean>()
        // Strongest first; among equals the earlier one first (the tool's sort is stable).
        for (c in candidates.sortedByDescending { it.strength }) {
            val near = accepted.filter { Math.abs(it.timeSec - c.timeSec) < MIN_SONG }
            if (near.isNotEmpty()) {
                for (a in near) if (a.strength < 3 * c.strength && a.depthDb - c.depthDb < 20) rival[a] = true
                continue
            }
            accepted.add(c)
        }
        return accepted.map { c ->
            when {
                rival.containsKey(c) -> c.copy(confident = false, reason = CutReason.SimilarGapWithinAMinute)
                c.lengthSec < minGap -> c.copy(confident = false, reason = CutReason.ShortGap)
                c.depthDb > floor + CONFIDENT_DEPTH -> c.copy(confident = false, reason = CutReason.NotFullySilent)
                else -> c
            }
        }.sortedBy { it.timeSec }
    }

    /**
     * Stretches (start, end in seconds) much longer than a typical song, which may hold a cut the finder missed. [cutTimes] are the cuts that
     * will be used: the tool passes only the confident ones (unless told to use the uncertain ones too), and so must the caller.
     */
    fun missedStretches(cutTimes: List<Double>, durationSec: Double): List<Pair<Double, Double>> {
        val bounds = listOf(0.0) + cutTimes + durationSec
        val lengths = bounds.zipWithNext { a, b -> b - a }
        val median = lengths.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val limit = maxOf(MISSED_MIN, 2.5 * median)
        return lengths.indices.filter { lengths[it] > limit }.map { bounds[it] to bounds[it + 1] }
    }

    /** `numpy.percentile` with its default linear interpolation, on an already sorted array; the arithmetic is in double on purpose. */
    private fun percentile(sorted: FloatArray, q: Double): Double {
        val pos = (sorted.size - 1) * q / 100.0
        val lo = Math.floor(pos).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (pos - lo) * (sorted[hi].toDouble() - sorted[lo])
    }
}
