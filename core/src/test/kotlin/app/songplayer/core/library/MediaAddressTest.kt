package app.songplayer.core.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAddressTest {
    @Test
    fun MediaAddress_OnlyOneSongOfTheAudioIndexIsAccepted() {
        assertTrue(MediaAddress.isIndexedSong("content://media/external/audio/media/1234"))
        for (bad in listOf(
            null, "", "content://media/external/audio/media", "content://media/external/audio/media/",
            "content://media/external/audio/mediaX/1", "content://media/external/audio/media/1/extra", "content://media/external/audio/media/1?x=1",
            "content://media/external/audio/media/abc", "content://other/external/audio/media/1", "file:///sdcard/a.mp3",
            "content://media/external/video/media/1", "content://media/external/audio/media/1#f",
        )) assertFalse("$bad", MediaAddress.isIndexedSong(bad))
    }
}
