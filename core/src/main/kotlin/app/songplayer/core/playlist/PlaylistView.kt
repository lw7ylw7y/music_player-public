package app.songplayer.core.playlist

import app.songplayer.core.library.Song
import java.util.Random

/** One row of an open playlist: the saved [entry], and the library's song for it, or null when the library has no such song now. */
data class PlaylistItem(val entry: PlaylistEntry, val song: Song?)

/**
 * What a playlist screen shows and plays (UX Design 4.4). The saved entries are the playlist; the library only supplies
 * the details to display, so a song that has left the library stays listed and can be removed (R-12).
 */
object PlaylistView {
    /** The entries in their saved order, each with its library song. */
    fun items(entries: List<PlaylistEntry>, library: List<Song>): List<PlaylistItem> {
        val byKey = library.associateBy { it.key }
        return entries.map { PlaylistItem(it, byKey[it.songKey]) }
    }

    /** The songs "Play" copies into the queue: those the library has, in playlist order. A new list, so later edits do not reach the queue (D-14). */
    fun queue(items: List<PlaylistItem>): List<Song> = items.mapNotNull { it.song }

    /** Where in [queue] the row [entryId] sits, or null when that row is unknown or its song is gone. */
    fun queueIndexOf(items: List<PlaylistItem>, entryId: Long): Int? {
        var index = 0
        for (item in items) {
            if (item.song == null) continue
            if (item.entry.id == entryId) return index
            index++
        }
        return null
    }

    /** "Shuffle" starts at any song of a queue of [size] songs; null when the queue is empty. */
    fun shuffleStart(size: Int, random: Random): Int? = if (size <= 0) null else random.nextInt(size)

    /** The length of the songs that can play, for the header line. */
    fun playableDurationMs(items: List<PlaylistItem>): Long = items.sumOf { it.song?.durationMs ?: 0L }

    /** How many places a drag of [offsetPx] has moved a row of height [rowPx]: whole rows only, toward zero. */
    fun dragSteps(offsetPx: Float, rowPx: Float): Int = if (rowPx <= 0f) 0 else (offsetPx / rowPx).toInt()

    /** The place a row at [index] goes to after [steps] places, kept inside a list of [count] rows. */
    fun moveTarget(index: Int, steps: Int, count: Int): Int = (index + steps).coerceIn(0, (count - 1).coerceAtLeast(0))

    /** What to tell the user after songs were added to a playlist: the first song's wording for one song, a count for several. */
    fun addMessage(requested: Int, added: Int, alreadyIn: Int): AddMessage = when {
        added == 0 -> AddMessage.AlreadyIn
        requested == 1 -> AddMessage.AddedOne
        else -> AddMessage.AddedMany(added, alreadyIn)
    }
}

sealed interface AddMessage {
    /** "Added to Driving" */
    data object AddedOne : AddMessage

    /** "Already in Driving": nothing was new. */
    data object AlreadyIn : AddMessage

    /** "Added 3 songs to Driving", with how many were in it already. */
    data class AddedMany(val added: Int, val alreadyIn: Int) : AddMessage
}
