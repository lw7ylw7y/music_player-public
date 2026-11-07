package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NewSongsNoticeTest {
    @Test
    fun NewSongsNotice_IsShownOnceForTheSameSongs() {
        val notice = NewSongsNotice()
        notice.add(3)
        assertEquals(3, notice.take())
        assertEquals("the same songs are not announced again", 0, notice.take())
    }

    @Test
    fun NewSongsNotice_SyncsBeforeItIsShown_AreAddedUp() {
        val notice = NewSongsNotice()
        notice.add(3)
        notice.add(2)
        assertEquals(5, notice.take())
    }

    @Test
    fun NewSongsNotice_NothingNew_ShowsNothing() {
        val notice = NewSongsNotice()
        notice.add(0)
        assertEquals(0, notice.take())
    }

    @Test
    fun NewSongsNotice_FirstScanIntoEmptyCache_ShowsNothing() {
        val plan = LibraryDiff.diff(emptyList(), listOf(song("a.mp3"), song("b.mp3")))
        val notice = NewSongsNotice()
        notice.add(plan.newSongs.size)
        assertEquals(0, notice.take())
    }
}
