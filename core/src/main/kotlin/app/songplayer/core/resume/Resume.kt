package app.songplayer.core.resume

import app.songplayer.core.library.SongKey
import app.songplayer.core.openfile.ExternalFile

/** One song of the saved queue. */
data class QueueEntry(
    val songKey: SongKey,
    /** Put there by "Play next" or "Add to queue" rather than being part of the list the queue was started from. */
    val added: Boolean,
    /** Where the song plays when shuffle is on (0 is first); null when shuffle is off. */
    val shuffleRank: Int?,
    /** The name shown for a file opened from another app (its key stands for the address); null for a library song. */
    val displayName: String? = null,
)

/**
 * What is saved so that the next start can show and resume the last song (R-11): the queue in list order, which song
 * of it was playing and how far, the shuffle and repeat settings, and the list the queue came from.
 */
data class ResumeState(
    val queue: List<QueueEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleOn: Boolean,
    /** Media3's repeat mode: 0 off, 1 one, 2 all. */
    val repeatMode: Int,
    /** The list the queue was started from, for "Playing from"; null when unknown. */
    val source: String?,
)

/** The saved queue and resume row. The playback service is the only writer (Technical Design 5.4). */
interface ResumeStore {
    /** The saved state, or null when nothing is saved. */
    fun load(): ResumeState?

    /** Replaces the queue and the resume row together, in one transaction; an empty queue clears both. */
    fun saveQueue(state: ResumeState)

    /** Updates where the song at [index] is playing. Ignored when [index] no longer holds [key] (see [ResumeRules]). */
    fun savePosition(index: Int, key: SongKey, positionMs: Long)
}

object ResumeRules {
    /** While a song plays, its position is saved this often, so a kill loses at most this much (R-11 allows 5 seconds). */
    const val POSITION_SAVE_INTERVAL_MS = 3_000L

    /**
     * Whether a position write may be applied: only if the saved queue still has the same song at that place. A write
     * that was prepared before the queue was replaced then changes nothing, so whichever write commits last leaves the
     * queue and the position agreeing.
     */
    fun acceptsPosition(storedKeyAtIndex: SongKey?, key: SongKey): Boolean = storedKeyAtIndex != null && storedKeyAtIndex == key

    /**
     * The saved state made valid against the songs the library has now (Technical Design 5.4). Songs that are gone
     * are dropped. If the song that was playing is gone, the next one that is left takes its place (or the last, if
     * none follows) and starts from its beginning. Null when nothing is left.
     */
    fun validate(state: ResumeState, library: Set<SongKey>): ResumeState? {
        // A file opened from another app is not in the library, so it is kept; whether it can still be read shows when it plays.
        val kept = state.queue.withIndex().filter { it.value.songKey in library || ExternalFile.uriOf(it.value.songKey) != null }
        if (kept.isEmpty()) return null

        val currentKept = kept.indexOfFirst { it.index == state.currentIndex }
        val (newIndex, newPosition) = if (currentKept >= 0) {
            currentKept to state.positionMs
        } else {
            val next = kept.indexOfFirst { it.index > state.currentIndex }
            (if (next >= 0) next else kept.lastIndex) to 0L
        }

        // Shuffle ranks have gaps once songs are dropped; renumber them without changing the order. If a rank is
        // missing or two songs share one the saved order cannot be trusted, and shuffle is left off.
        val ranks = kept.map { it.value.shuffleRank }
        val rankable = state.shuffleOn && ranks.none { it == null } && ranks.toSet().size == ranks.size
        val dense: Map<Int, Int> = if (rankable) ranks.filterNotNull().sorted().withIndex().associate { it.value to it.index } else emptyMap()
        val queue = kept.map { (_, entry) ->
            entry.copy(shuffleRank = if (rankable) dense.getValue(entry.shuffleRank!!) else null)
        }
        // Values read back from storage are made safe: an unknown repeat mode is off, a position is never negative.
        return state.copy(
            queue = queue,
            currentIndex = newIndex,
            positionMs = newPosition.coerceAtLeast(0),
            shuffleOn = rankable,
            repeatMode = if (state.repeatMode in 0..2) state.repeatMode else 0,
        )
    }
}
