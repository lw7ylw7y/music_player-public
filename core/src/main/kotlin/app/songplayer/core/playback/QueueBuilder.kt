package app.songplayer.core.playback

import app.songplayer.core.library.Song

/** The songs to hand to the player and the one to start with. */
data class Queue(val songs: List<Song>, val startIndex: Int)

object QueueBuilder {
    /**
     * The queue made by tapping the song at [startIndex] in a list (R-6): the list as shown, in its order, starting at
     * the tapped song. The list is copied, so later edits to a playlist or to the Library's list do not change it.
     */
    fun fromList(songs: List<Song>, startIndex: Int): Queue {
        require(startIndex in songs.indices) { "startIndex $startIndex is outside a list of ${songs.size}" }
        return Queue(songs.toList(), startIndex)
    }
}

object PlaybackRules {
    /** After this much of a song, Previous restarts it; before, it goes to the previous song (UX 4.5). */
    const val PREVIOUS_RESTART_MS = 3_000L
}
