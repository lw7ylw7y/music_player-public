package app.songplayer.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.library.song
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.resume.ResumeStore
import app.songplayer.core.library.SongKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The saved queue turned into player items, against a library (no player, no service). */
@RunWith(AndroidJUnit4::class)
class ResumeRestoreTest {
    private val a = song("a.mp3", id = 1)
    private val b = song("b.mp3", id = 2)
    private val c = song("c.mp3", id = 3)

    private class Fixed(private val state: ResumeState?) : ResumeStore {
        override fun load() = state
        override fun saveQueue(state: ResumeState) = error("not used")
        override fun savePosition(index: Int, key: SongKey, positionMs: Long) = error("not used")
    }

    private fun saved(vararg songs: app.songplayer.core.library.Song, current: Int = 0, shuffle: Boolean = false) = ResumeState(
        queue = songs.mapIndexed { i, s -> QueueEntry(s.key, added = i == 2, shuffleRank = if (shuffle) songs.size - 1 - i else null) },
        currentIndex = current,
        positionMs = 70_000,
        shuffleOn = shuffle,
        repeatMode = 2,
        source = "Search",
    )

    @Test
    fun ResumeRestore_Read_BuildsItemsInTheSavedOrderWithTheirFlags() {
        val r = ResumeRestore.read(Fixed(saved(a, b, c, current = 1)), listOf(a, b, c))!!
        assertEquals(listOf(a.key.value, b.key.value, c.key.value), r.items.map { it.mediaId })
        assertEquals(1, r.index)
        assertEquals(70_000L, r.positionMs)
        assertEquals(2, r.repeatMode)
        assertFalse(r.shuffleOn)
        assertNull(r.shuffleOrder)
        // The added song carries no source; the others carry the saved one.
        assertEquals("Search", r.items[0].mediaMetadata.extras?.getString("playingFrom"))
        assertNull(r.items[2].mediaMetadata.extras?.getString("playingFrom"))
        assertTrue(r.items[2].mediaMetadata.extras?.getBoolean("addedToQueue") == true)
    }

    @Test
    fun ResumeRestore_Read_ShuffleOrderIsRebuiltFromTheRanks() {
        // Ranks 2, 1, 0: the last song plays first.
        val r = ResumeRestore.read(Fixed(saved(a, b, c, shuffle = true)), listOf(a, b, c))!!
        assertTrue(r.shuffleOn)
        assertArrayEquals(intArrayOf(2, 1, 0), r.shuffleOrder)
    }

    @Test
    fun ResumeRestore_Read_DropsSongsThatLeftTheLibrary() {
        val r = ResumeRestore.read(Fixed(saved(a, b, c, current = 2)), listOf(a, c))!!
        assertEquals(listOf(a.key.value, c.key.value), r.items.map { it.mediaId })
        assertEquals(1, r.index)
        assertEquals(70_000L, r.positionMs)
    }

    @Test
    fun ResumeRestore_Read_NothingLeftOrNothingSaved_IsNull() {
        assertNull(ResumeRestore.read(Fixed(saved(a, b)), listOf(c)))
        assertNull(ResumeRestore.read(Fixed(null), listOf(a, b, c)))
    }
}
