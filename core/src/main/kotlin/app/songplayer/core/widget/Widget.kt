package app.songplayer.core.widget

import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.openfile.ExternalFile
import app.songplayer.core.resume.ResumeState

/** What the widget needs to draw a song (UX Design G1): never more than the title, the artist and where the cover comes from. */
data class WidgetSong(
    val mediaId: String,
    val title: String,
    val artist: String,
    /** The media-index id the cover is asked for; -1 when there is none (a file opened from another app). */
    val mediaStoreId: Long,
)

/** The widget's whole content: a song, or none, and whether it is playing. */
data class WidgetState(val song: WidgetSong?, val playing: Boolean) {
    companion object {
        val Empty = WidgetState(null, false)
    }
}

object WidgetRules {
    /**
     * The widget is redrawn only when what it shows changes: another song, or playing against paused (R-24, NFR-10).
     * The position is not part of [WidgetState], so it never causes a redraw, and nothing here is driven by a timer.
     */
    fun shouldUpdate(last: WidgetState?, now: WidgetState): Boolean = now != last

    /**
     * What to draw: the live state while the player holds a song; otherwise the last song that was saved, shown paused,
     * so that Play resumes it; otherwise the empty widget ("Music Player", "Tap to open").
     */
    fun shown(live: WidgetState?, saved: WidgetState?): WidgetState = when {
        live?.song != null -> live
        saved?.song != null -> saved.copy(playing = false)
        else -> WidgetState.Empty
    }

    /** The song the saved queue was on, found by [lookup] (or an opened file kept with its name), shown paused; null when nothing is saved or the song is gone. */
    fun fromResume(resume: ResumeState?, lookup: (SongKey) -> Song?): WidgetState? {
        val entry = resume?.queue?.getOrNull(resume.currentIndex) ?: return null
        val file = ExternalFile.uriOf(entry.songKey)?.let { ExternalFile(it, entry.displayName ?: it.substringAfterLast('/')) }
        val song = when {
            file != null -> WidgetSong(file.key.value, file.displayName, Song.UNKNOWN_ARTIST, -1L)
            else -> lookup(entry.songKey)?.let { WidgetSong(it.key.value, it.displayTitle, it.displayArtist, it.mediaStoreId) }
        } ?: return null
        return WidgetState(song, playing = false)
    }
}
