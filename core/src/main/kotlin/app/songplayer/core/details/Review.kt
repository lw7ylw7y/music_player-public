package app.songplayer.core.details

import app.songplayer.core.library.Song

/** What Review needs from the details store; both writes are conditional on the song still being held. */
interface ReviewStore {
    /** Applies [diffs] as changes by the user's choice and marks the song applied. False, changing nothing, if it is not held any more. */
    fun applyReview(songKey: String, diffs: List<FieldDiff>, nowSec: Long): Boolean

    /** The user says none of the candidates is right: the song is rejected and never looked up again. False if it is not held any more. */
    fun reject(songKey: String, nowSec: Long): Boolean
}

/** One song under review with the choices it offers, best first. */
data class ReviewItem(val song: Song, val candidates: List<Candidate>)

/** How a review ended: the three numbers of the "All reviewed" screen. */
data class ReviewCounts(val filledIn: Int = 0, val rejected: Int = 0, val skipped: Int = 0) {
    val decided: Int get() = filledIn + rejected + skipped
}

object ReviewRules {
    /**
     * The same song listed on several releases is one choice (the user accepts any album): candidates with the same title and artists
     * collapse into the best-scored one. The order is kept.
     */
    fun collapse(candidates: List<Candidate>): List<Candidate> {
        val seen = HashSet<Pair<String, List<String>>>()
        return candidates.filter { seen.add(MatchRules.norm(it.title) to it.artists.map(MatchRules::norm)) }
    }
}

/**
 * One pass through the held songs, one at a time (UX Design 4.10, D-16): the best match is selected first; "Use this" applies the
 * selected one, "None of these" rejects the song for good, "Skip for now" leaves it held for the next time. Not thread-safe.
 */
class ReviewSession(
    private val items: List<ReviewItem>,
    private val store: ReviewStore,
    /** The replace setting (R-32): "Use this" then replaces differing fields too. */
    private val replace: Boolean = false,
    private val same: (String, String) -> Boolean = { a, b -> a.trim() == b.trim() },
    private val nowSec: () -> Long,
) {
    var index = 0
        private set

    /** The choice that is selected now (0 is the best match). */
    var selected = 0
        private set

    var counts = ReviewCounts()
        private set

    val total: Int get() = items.size
    val current: ReviewItem? get() = items.getOrNull(index)
    val finished: Boolean get() = index >= items.size

    fun select(choice: Int) {
        val item = current ?: return
        if (choice in item.candidates.indices) selected = choice
    }

    /** Applies the selected candidate to the song's missing fields. Returns false if the song was decided meanwhile (it is passed over). */
    fun use(): Boolean {
        val item = current ?: return false
        val done = store.applyReview(item.song.key.value, MatchRules.diffs(item.song, item.candidates[selected], replace, same), nowSec())
        if (done) counts = counts.copy(filledIn = counts.filledIn + 1)
        advance()
        return done
    }

    fun reject(): Boolean {
        val item = current ?: return false
        val done = store.reject(item.song.key.value, nowSec())
        if (done) counts = counts.copy(rejected = counts.rejected + 1)
        advance()
        return done
    }

    fun skip() {
        if (current == null) return
        counts = counts.copy(skipped = counts.skipped + 1)
        advance()
    }

    private fun advance() {
        index++
        selected = 0
    }
}
