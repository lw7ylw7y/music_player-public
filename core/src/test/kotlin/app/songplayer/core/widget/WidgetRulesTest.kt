package app.songplayer.core.widget

import app.songplayer.core.library.song
import app.songplayer.core.openfile.ExternalFile
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRulesTest {
    private fun widgetSong(n: Int) = WidgetSong("k$n", "Song $n", "Ann", n.toLong())

    @Test
    fun WidgetRules_Refresh_OnlyWhenTheSongOrThePlayStateChanges() {
        val playing = WidgetState(widgetSong(1), playing = true)
        assertTrue("the first state is drawn", WidgetRules.shouldUpdate(null, playing))
        assertFalse("the same again is not", WidgetRules.shouldUpdate(playing, playing.copy()))
        assertTrue("pausing is", WidgetRules.shouldUpdate(playing, playing.copy(playing = false)))
        assertTrue("another song is", WidgetRules.shouldUpdate(playing, WidgetState(widgetSong(2), playing = true)))
        assertTrue("nothing left is", WidgetRules.shouldUpdate(playing, WidgetState.Empty))
    }

    @Test
    fun WidgetRules_Shown_PrefersTheLiveSong_ThenTheSavedOneAsPaused_ThenEmpty() {
        val live = WidgetState(widgetSong(1), playing = true)
        val saved = WidgetState(widgetSong(2), playing = true)
        assertEquals(live, WidgetRules.shown(live, saved))
        assertEquals(WidgetState(widgetSong(2), playing = false), WidgetRules.shown(null, saved))
        assertEquals("a live state without a song falls back to the saved one", WidgetState(widgetSong(2), false), WidgetRules.shown(WidgetState.Empty, saved))
        assertEquals(WidgetState.Empty, WidgetRules.shown(null, null))
        assertNull(WidgetRules.shown(null, null).song)
    }

    private fun resume(vararg entries: QueueEntry, current: Int = 0) = ResumeState(entries.toList(), current, 5_000, false, 0, null)

    @Test
    fun WidgetRules_FromResume_IsTheSongTheQueueWasOn_ShownPaused() {
        val a = song("a.mp3", id = 11, title = "Alpha", artist = "Ann")
        val b = song("b.mp3", id = 12, title = null, artist = null)
        val library = listOf(a, b).associateBy { it.key }
        val lookup = { key: app.songplayer.core.library.SongKey -> library[key] }
        val state = WidgetRules.fromResume(resume(QueueEntry(a.key, false, null), QueueEntry(b.key, false, null), current = 1), lookup)!!
        assertFalse(state.playing)
        assertEquals("b.mp3", state.song?.title)
        assertEquals("Unknown artist", state.song?.artist)
        assertEquals(12L, state.song?.mediaStoreId)
    }

    @Test
    fun WidgetRules_FromResume_NothingSavedOrTheSongGone_IsNull() {
        val a = song("a.mp3")
        assertNull(WidgetRules.fromResume(null) { null })
        assertNull(WidgetRules.fromResume(resume(QueueEntry(a.key, false, null))) { null })
    }

    @Test
    fun WidgetRules_FromResume_AnOpenedFile_KeepsItsNameAndHasNoCover() {
        val file = ExternalFile("content://provider/doc/42", "Morning.mp3")
        val state = WidgetRules.fromResume(resume(QueueEntry(file.key, false, null, displayName = "Morning.mp3"))) { null }!!
        assertEquals("Morning.mp3", state.song?.title)
        assertEquals(-1L, state.song?.mediaStoreId)
        assertEquals(file.key.value, state.song?.mediaId)
    }
}
