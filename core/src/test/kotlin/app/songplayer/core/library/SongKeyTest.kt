package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SongKeyTest {
    @Test
    fun SongKey_Volume_IsTheNamePartBeforeTheFirstColon() {
        assertEquals("external_primary", SongKey.of("external_primary", "Music/", "a:b.mp3").volume)
        assertEquals("1A2B-3C4D", SongKey.of("1A2B-3C4D", "Music/", "a.mp3").volume)
    }
}
