package app.songplayer.core.voice

import app.songplayer.core.browse.BrowseLibrary
import app.songplayer.core.errors.VoiceError
import app.songplayer.core.library.Song
import app.songplayer.core.text.TextKeyer

/** What a spoken request matched, which decides nothing about playback beyond the songs and where to start. */
enum class VoiceMatch { Playlist, Artist, Album, Songs, EverySong }

/** The songs to queue, in the order to play them. */
data class VoiceResult(val match: VoiceMatch, val songs: List<Song>)

/**
 * Turns the words the car's assistant sends ("play <something>") into a queue (R-16, Technical Design 5.5).
 * The order is: a playlist with exactly that name, then an artist, then an album, then songs; when several share the name, all of them play. "Exactly" means equal after
 * the same folding as search (case, width, Simplified and Traditional). Songs match when every word of the request is in the
 * song's title, artist, album or file name, as in search. A blank request ("play some music") is every song, by title.
 * Nothing here is logged: the request is the user's words (NFR-4).
 */
class VoiceResolver(private val keyer: TextKeyer) {
    /** @throws VoiceError.NoMatch when nothing in the library fits, or the library is empty. */
    fun resolve(query: String?, library: BrowseLibrary): VoiceResult {
        val folded = keyer.searchFold(query.orEmpty()).trim()
        if (folded.isEmpty()) {
            if (library.songs.isEmpty()) throw VoiceError.NoMatch()
            return VoiceResult(VoiceMatch.EverySong, library.songs.map { it.song })
        }
        // Two playlists or albums may share a name (two "Greatest Hits"): all of them play, one after the other.
        fun named(name: String) = keyer.searchFold(name).trim() == folded
        library.playlists.filter { named(it.name) }.flatMap { it.songs }.distinct()
            .takeIf { it.isNotEmpty() }?.let { return VoiceResult(VoiceMatch.Playlist, it) }
        library.artists.filter { named(it.name) }.flatMap { it.songs }.distinct()
            .takeIf { it.isNotEmpty() }?.let { return VoiceResult(VoiceMatch.Artist, it) }
        library.albums.filter { named(it.name) }.flatMap { it.songs }.distinct()
            .takeIf { it.isNotEmpty() }?.let { return VoiceResult(VoiceMatch.Album, it) }
        val parts = folded.split(WHITESPACE).filter { it.isNotEmpty() }
        val songs = library.songs.filter { s -> parts.all { s.keys.searchText.contains(it) } }.map { it.song }
        if (songs.isEmpty()) throw VoiceError.NoMatch()
        return VoiceResult(VoiceMatch.Songs, songs)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
