package app.songplayer.core.voice

import app.songplayer.core.browse.BrowseAlbum
import app.songplayer.core.browse.BrowseArtist
import app.songplayer.core.browse.BrowseLibrary
import app.songplayer.core.browse.BrowsePlaylist
import app.songplayer.core.errors.VoiceError
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceResolverTest {
    private val keyer = FakeKeyer()
    private val resolver = VoiceResolver(keyer)

    private fun s(n: Int, title: String, artist: String, album: String) =
        song("f$n.mp3", id = n.toLong(), title = title, artist = artist, album = album)

    private val sunny = s(1, "Sunny Day", "Ann", "Blue")
    private val rain = s(2, "Rain", "Ann", "Blue")
    private val ann = s(3, "Ann", "Bob", "Green")     // a song named like an artist
    private val zhang = s(4, "後來", "張學友", "愛情")
    private val all = listOf(sunny, rain, ann, zhang)

    private fun library(playlists: List<BrowsePlaylist> = emptyList()): BrowseLibrary {
        val indexed = all.map { IndexedSong.of(it, keyer) }.sortedBy { it.keys.sortTitle }
        val albums = indexed.groupBy { it.song.displayAlbum }.map { (n, m) -> BrowseAlbum("a:$n", n, "x", SortKeys.of(n, keyer), m.map { it.song }) }
        val artists = indexed.groupBy { it.song.displayArtist }.map { (n, m) -> BrowseArtist("r:$n", n, SortKeys.of(n, keyer), m.map { it.song }) }
        return BrowseLibrary(indexed, albums, artists, playlists)
    }

    @Test
    fun VoiceResolver_ExactPlaylistFirst_ThenArtistAlbumSong() {
        // "ann" is a playlist, an artist and a song title: the playlist wins, then the artist, then (no artist) the album.
        val withPlaylist = library(listOf(BrowsePlaylist(1, "Ann", listOf(rain))))
        assertEquals(VoiceResult(VoiceMatch.Playlist, listOf(rain)), resolver.resolve("ann", withPlaylist))

        val lib = library()
        assertEquals(VoiceMatch.Artist, resolver.resolve("ANN", lib).match)
        assertEquals(setOf(sunny, rain), resolver.resolve("ann", lib).songs.toSet())
        assertEquals(VoiceResult(VoiceMatch.Album, listOf(ann)), resolver.resolve("green", lib))
        assertEquals(VoiceResult(VoiceMatch.Songs, listOf(rain)), resolver.resolve("rain", lib))
        assertEquals(listOf(sunny), resolver.resolve("sunny blue", lib).songs)
    }

    @Test
    fun VoiceResolver_AnEmptyPlaylistWithTheNameIsSkipped() {
        val lib = library(listOf(BrowsePlaylist(1, "Rain", emptyList())))
        assertEquals(VoiceResult(VoiceMatch.Songs, listOf(rain)), resolver.resolve("rain", lib))
    }

    @Test
    fun VoiceResolver_TwoAlbumsWithTheSameName_BothPlay() {
        val one = BrowseAlbum("a:1", "Hits", "x", "hits", listOf(sunny))
        val two = BrowseAlbum("a:2", "hits", "y", "hits", listOf(rain))
        val lib = BrowseLibrary(library().songs, listOf(one, two), emptyList(), emptyList())
        assertEquals(VoiceResult(VoiceMatch.Album, listOf(sunny, rain)), resolver.resolve("HITS", lib))
    }

    @Test
    fun VoiceResolver_NoMatch_ReturnsNoMatch() {
        assertThrows(VoiceError.NoMatch::class.java) { resolver.resolve("nothing like this", library()) }
        assertThrows(VoiceError.NoMatch::class.java) { resolver.resolve("anything", BrowseLibrary(emptyList(), emptyList(), emptyList(), emptyList())) }
        assertThrows(VoiceError.NoMatch::class.java) { resolver.resolve(null, BrowseLibrary(emptyList(), emptyList(), emptyList(), emptyList())) }
    }

    @Test
    fun VoiceResolver_ABlankRequest_IsEverySongByTitle() {
        val lib = library()
        for (q in listOf(null, "", "   ")) {
            val r = resolver.resolve(q, lib)
            assertEquals(VoiceMatch.EverySong, r.match)
            assertEquals(lib.songs.map { it.song }, r.songs)
        }
    }

    @Test
    fun VoiceResolver_TraditionalQuery_FindsSimplifiedTitle() {
        assertEquals(listOf(zhang), resolver.resolve("后来", library()).songs)
        assertEquals(VoiceMatch.Artist, resolver.resolve("张学友", library()).match)
        assertEquals(VoiceMatch.Album, resolver.resolve("爱情", library()).match)
    }
}
