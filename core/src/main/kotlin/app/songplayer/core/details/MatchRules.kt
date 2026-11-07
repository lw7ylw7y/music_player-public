package app.songplayer.core.details

import app.songplayer.core.library.Song

/** What to do with the service's answer for one song (Technical Design 5.8). */
sealed interface MatchDecision {
    /** Sure enough to apply without asking. */
    data class Sure(val match: Candidate) : MatchDecision

    /** Unsure or tied: kept for the user, at most [MatchRules.MAX_CANDIDATES], best first. */
    data class Held(val candidates: List<Candidate>) : MatchDecision

    /** The service knows nothing for this song. */
    data object NotFound : MatchDecision
}

/** The values a sure match writes into the missing fields. A field with nothing to write is null. */
data class MatchFill(val title: String, val artist: String?, val album: String?)

/**
 * The rule engine. The numbers are provisional (Technical Design 5.8): they are tuned on the user's 120-song album and are named so
 * that tuning changes one line. The service's score is not defined by the service, so it is never the only test.
 */
object MatchRules {
    const val SURE_SCORE = 0.95
    const val DURATION_TOLERANCE_S = 5
    const val SURE_MARGIN = 0.10
    const val MAX_CANDIDATES = 3

    /** [fileDurationS] is the song's own length in seconds. */
    fun decide(candidates: List<Candidate>, fileDurationS: Int): MatchDecision {
        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return MatchDecision.NotFound
        val second = ranked.getOrNull(1)
        val lengthFits = best.durationS != null && Math.abs(best.durationS - fileDurationS) <= DURATION_TOLERANCE_S
        val clear = second == null || best.score - second.score >= SURE_MARGIN || sameSong(best, second)
        return if (best.score >= SURE_SCORE && lengthFits && clear) MatchDecision.Sure(best)
        else MatchDecision.Held(ranked.take(MAX_CANDIDATES))
    }

    /** What a match offers for each field: the artists joined, and the first release group of type Album (none: no album). */
    fun fill(match: Candidate): MatchFill = MatchFill(
        title = match.title,
        artist = match.artists.joinToString(", ").ifEmpty { null },
        album = match.albums.firstOrNull { it.type == "Album" }?.title,
    )

    /**
     * Whether a song is worth a lookup: it lacks a title, artist or album, or, with the replace setting (R-32), always. With
     * [newSinceSec] (R-51, "only new songs") a song added to the phone before that time is never looked up, whatever else is set.
     */
    fun needsLookup(song: Song, replace: Boolean, newSinceSec: Long? = null): Boolean =
        (newSinceSec == null || song.dateAddedSec >= newSinceSec) &&
            (replace || song.title.isNullOrBlank() || song.artist.isNullOrBlank() || song.album.isNullOrBlank())

    /**
     * The changes a match makes to [song], and only with what the match has. By default only the fields the song lacks (R-19); with
     * [replace] (R-32) also the fields whose text differs from the match's; [same] says when two texts are the same song detail for this
     * purpose (the app passes the search fold, so case, width and Simplified or Traditional Chinese are not real differences).
     */
    fun diffs(song: Song, match: Candidate, replace: Boolean = false, same: (String, String) -> Boolean = { a, b -> a.trim() == b.trim() }): List<FieldDiff> {
        val fill = fill(match)
        fun takes(current: String?, new: String?) = new != null && (current.isNullOrBlank() || (replace && !same(current, new)))
        return buildList {
            if (takes(song.title, fill.title)) add(FieldDiff(DetailField.Title, song.title, fill.title))
            if (takes(song.artist, fill.artist)) add(FieldDiff(DetailField.Artist, song.artist, fill.artist))
            if (takes(song.album, fill.album)) add(FieldDiff(DetailField.Album, song.album, fill.album))
        }
    }

    internal fun sameSong(a: Candidate, b: Candidate) = norm(a.title) == norm(b.title) && a.artists.map(::norm) == b.artists.map(::norm)

    internal fun norm(s: String) = s.trim().lowercase()
}
