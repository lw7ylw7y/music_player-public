package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTest {
    @Test
    fun DisplayFallback_MissingTags_UsesFileNameAndUnknowns() {
        val s = song("recording_0412.mp3", title = null, artist = null, album = null)
        assertEquals("recording_0412.mp3", s.displayTitle)
        assertEquals("Unknown artist", s.displayArtist)
        assertEquals("Unknown album", s.displayAlbum)
    }

    @Test
    fun DisplayFallback_FullTags_ShowsTags() {
        val s = song("x.mp3", title = "後來", artist = "劉若英", album = "我等你")
        assertEquals("後來", s.displayTitle)
        assertEquals("劉若英", s.displayArtist)
        assertEquals("我等你", s.displayAlbum)
    }

    @Test
    fun TagText_NullBlankAndUnknownMarker_BecomeNull() {
        assertEquals(null, TagText.clean(null))
        assertEquals(null, TagText.clean(""))
        assertEquals(null, TagText.clean("   "))
        assertEquals(null, TagText.clean("<unknown>"))
        assertEquals("後來", TagText.clean(" 後來 "))
    }
}
