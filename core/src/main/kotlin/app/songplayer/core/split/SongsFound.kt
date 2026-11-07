package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import app.songplayer.core.library.Song

/** Whether a song may be split from the menu, and the checks that need no reading of the file (UX Design 3). */
object SplitEntry {
    /** The shortest song offered the action: 6 minutes (Requirements R-33; the user lowered it from 10 on 2026-11-06). */
    const val MIN_DURATION_MS = 6L * 60 * 1000

    /** True for an MP3 of at least [MIN_DURATION_MS]; the library lists MP3 files only. */
    fun offered(song: Song): Boolean = song.durationMs >= MIN_DURATION_MS && song.fileName.endsWith(".mp3", ignoreCase = true)

    /** The first check of the menu action that already fails from the library's own facts: a file over the size limit (R-34), else null. */
    fun refusal(song: Song): SplitError? = if (song.sizeBytes > SplitLimits.MAX_BYTES) SplitError.TooLarge() else null
}

enum class FoundState { LooksRight, NeedsCheck }

/** One proposed song: its number, where it starts and ends in the original (seconds), and whether its start was a sure cut. */
data class FoundRow(val number: Int, val startSec: Double, val endSec: Double, val state: FoundState) {
    val lengthSec: Double get() = endSec - startSec
}

/** What the primary button of "The songs found" does (UX Design 4.11, K2). */
sealed interface FoundPrimary {
    /** Some cuts are uncertain: go through them one by one. */
    data class Check(val uncertain: Int) : FoundPrimary

    /** All cuts look right: go on to the summary. */
    data class Split(val songs: Int) : FoundPrimary

    /** No gap was found: the user places cuts by hand. */
    data object AddACut : FoundPrimary
}

/**
 * The list of "The songs found" (UX Design 4.11 (K2)), from the cuts of a run record and the length of the file. A song whose start is an uncertain cut
 * that the user has not checked is "please check"; the first song starts the file and is always right. Pure data: the screen draws it.
 */
class SongsFound(val rows: List<FoundRow>) {
    val songCount: Int get() = rows.size
    val uncertain: Int get() = rows.count { it.state == FoundState.NeedsCheck }
    val looksRight: Int get() = songCount - uncertain
    val noGaps: Boolean get() = rows.size == 1

    val primary: FoundPrimary get() = when {
        noGaps -> FoundPrimary.AddACut
        uncertain > 0 -> FoundPrimary.Check(uncertain)
        else -> FoundPrimary.Split(songCount)
    }

    companion object {
        fun of(record: RunRecord, durationSec: Double): SongsFound {
            val edges = listOf(0.0) + record.cuts.map { it.timeSec } + listOf(durationSec)
            return SongsFound(
                List(record.songCount) { k ->
                    val cut = record.cuts.getOrNull(k - 1)                       // the cut that starts song k + 1
                    val unsure = cut != null && !cut.confident && !cut.checked
                    FoundRow(k + 1, edges[k], edges[k + 1], if (unsure) FoundState.NeedsCheck else FoundState.LooksRight)
                },
            )
        }
    }
}

/** The length of the file to use with [record]: the media index's length, never shorter than the last cut plus the 5 s a song needs (the index's length of an MP3 is approximate). */
fun durationFor(record: RunRecord, mediaDurationSec: Double): Double =
    maxOf(mediaDurationSec, (record.cuts.lastOrNull()?.timeSec ?: 0.0) + SplitPlan.MIN_DISTANCE_SEC)

/** Adding a cut by hand to a record (UX Design 4.11 (K2) "Add a cut"). */
object SplitEdit {
    /** [record] with a cut at [timeSec], or null when it would be closer than 5 s to a neighbour or an end, or would make more than [SplitLimits.MAX_SONGS] songs. */
    fun addCut(record: RunRecord, durationSec: Double, timeSec: Double): RunRecord? {
        if (record.songCount >= SplitLimits.MAX_SONGS) return null
        val plan = SplitPlan(durationSec, record.cuts.map { PlanCut(it.timeSec, it.halfGapSec, it.confident, it.checked) })
        if (!plan.add(timeSec)) return null
        return record.copy(cuts = plan.cuts.map { RunCut(it.timeSec, it.halfGapSec, it.confident, it.checked) })
    }
}

/** Times as the screens write and read them: `8:30`, or `1:02:03` from an hour on. */
object SplitTime {
    fun format(sec: Double): String {
        val total = sec.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
    }

    /** Like [format] but with the half second when there is one (`8:29.5`), for the cut screen where a step is half a second. */
    fun formatHalf(sec: Double): String {
        val halves = Math.round(sec.coerceAtLeast(0.0) * 2)
        return format(halves / 2.0) + if (halves % 2 == 1L) ".5" else ""
    }

    /** `m:ss` or `h:mm:ss` (seconds may have a decimal part), or a plain number of seconds; null for anything else. */
    fun parse(text: String): Double? {
        val parts = text.trim().split(':')
        if (parts.size !in 1..3 || parts.any { it.isEmpty() || !it.all { c -> c.isDigit() || c == '.' } }) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        if (numbers.dropLast(1).any { it != Math.floor(it) }) return null          // only the seconds may have a decimal part
        if (parts.size > 1 && numbers.drop(1).any { it >= 60 }) return null         // 8:75 is not a time
        return numbers.fold(0.0) { acc, n -> acc * 60 + n }
    }
}
