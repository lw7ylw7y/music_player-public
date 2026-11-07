package app.songplayer.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {
    @Test
    fun PlaybackErrors_FileGoneUnreadableOrMalformed_AreTheSongsFault() {
        listOf(2000, 2005, 2008, 3001, 3003, 4003, 4004, 4005).forEach { assertTrue("code $it", PlaybackErrors.isTheSongsFault(it)) }
    }

    @Test
    fun PlaybackErrors_PermissionAudioOutputAndUnknown_AreNot() {
        // 2006 no permission; 5001 and 5002 audio track; 4001 decoder init and 4006 resources reclaimed; 1000 unspecified; 1003 timeout.
        listOf(2006, 5001, 5002, 5003, 4001, 4002, 4006, 1000, 1003, -100).forEach {
            assertFalse("code $it", PlaybackErrors.isTheSongsFault(it))
        }
    }
}
