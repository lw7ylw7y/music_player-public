package app.songplayer.core.playback

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QueueBuilderTest {
    private val list = listOf(song("a.mp3", id = 1), song("b.mp3", id = 2), song("c.mp3", id = 3))

    @Test
    fun QueueBuilder_FromList_StartsAtIndexAndKeepsOrder() {
        val queue = QueueBuilder.fromList(list, 1)
        assertEquals(list, queue.songs)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun QueueBuilder_FromList_CopiesTheList() {
        val mutable = list.toMutableList()
        val queue = QueueBuilder.fromList(mutable, 0)
        mutable.removeAt(0)
        assertEquals(3, queue.songs.size)
        assertNotSame(mutable, queue.songs)
    }

    @Test
    fun QueueBuilder_FromList_IndexOutsideTheList_IsRejected() {
        assertThrows(IllegalArgumentException::class.java) { QueueBuilder.fromList(list, 3) }
        assertThrows(IllegalArgumentException::class.java) { QueueBuilder.fromList(emptyList(), 0) }
        assertThrows(IllegalArgumentException::class.java) { QueueBuilder.fromList(list, -1) }
    }

    @Test
    fun QueueBuilder_FromList_5000Songs_IsInstant() {
        val many = (1..5000).map { song("t$it.mp3", id = it.toLong()) }
        val start = System.nanoTime()
        QueueBuilder.fromList(many, 2500)
        val ms = (System.nanoTime() - start) / 1_000_000
        assert(ms < 50) { "building a 5,000-song queue took $ms ms" }
    }

    @Test
    fun PlaybackRules_PreviousRestart_IsThreeSeconds() {
        assertEquals(3_000L, PlaybackRules.PREVIOUS_RESTART_MS)
    }
}
