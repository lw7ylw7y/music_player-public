package app.songplayer.core.playback

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEditTest {
    @Test
    fun QueueEdit_PlayNext_IsRightAfterTheCurrentSong() {
        assertEquals(3, QueueEdit.playNextIndex(current = 2, size = 10))
    }

    @Test
    fun QueueEdit_PlayNext_AtTheLastSongOrWithNothingPlaying_StaysInsideTheQueue() {
        assertEquals(5, QueueEdit.playNextIndex(current = 4, size = 5))
        assertEquals(0, QueueEdit.playNextIndex(current = -1, size = 5))
        assertEquals(0, QueueEdit.playNextIndex(current = -1, size = 0))
    }

    @Test
    fun QueueEdit_AddToQueue_IsTheEnd() {
        assertEquals(7, QueueEdit.addToQueueIndex(7))
    }

    @Test
    fun ShuffleRules_CurrentFirst_IsAPermutationStartingWithTheCurrentSong() {
        repeat(50) { seed ->
            val order = ShuffleRules.currentFirst(20, current = 7, random = Random(seed.toLong()))
            assertEquals(7, order[0])
            assertEquals((0 until 20).toList(), order.sorted())
        }
    }

    @Test
    fun ShuffleRules_CurrentFirst_ReallyShuffles() {
        val orders = (0 until 20).map { ShuffleRules.currentFirst(10, 0, Random(it.toLong())).toList() }.toSet()
        assert(orders.size > 10) { "only ${orders.size} different orders in 20 tries" }
    }

    @Test
    fun ShuffleRules_CurrentFirst_NoCurrentSongOrOneSong() {
        assertEquals(listOf(0, 1, 2), ShuffleRules.currentFirst(3, -1, Random(1)).sorted())
        assertArrayEquals(intArrayOf(0), ShuffleRules.currentFirst(1, 0, Random(1)))
        assertEquals(0, ShuffleRules.currentFirst(0, -1, Random(1)).size)
    }

    @Test
    fun ShuffleRules_MoveAfter_PutsTheItemRightAfterTheAnchorAndKeepsTheRest() {
        // The order plays 3, 1, 4, 0, 2; item 0 should come right after 1.
        assertArrayEquals(intArrayOf(3, 1, 0, 4, 2), ShuffleRules.moveAfter(intArrayOf(3, 1, 4, 0, 2), item = 0, anchor = 1))
        // Already there, or the item comes before the anchor.
        assertArrayEquals(intArrayOf(3, 1, 4, 0, 2), ShuffleRules.moveAfter(intArrayOf(3, 1, 4, 0, 2), item = 4, anchor = 1))
        assertArrayEquals(intArrayOf(1, 4, 3, 0, 2), ShuffleRules.moveAfter(intArrayOf(3, 1, 4, 0, 2), item = 3, anchor = 4))
    }

    @Test
    fun ShuffleRules_MoveAfter_UnknownItemOrAnchor_ChangesNothing() {
        assertArrayEquals(intArrayOf(0, 1, 2), ShuffleRules.moveAfter(intArrayOf(0, 1, 2), item = 9, anchor = 1))
        assertArrayEquals(intArrayOf(0, 1, 2), ShuffleRules.moveAfter(intArrayOf(0, 1, 2), item = 1, anchor = 1))
    }

    @Test
    fun ShuffleRules_MoveToEnd_MovesOnlyThatItem() {
        assertArrayEquals(intArrayOf(3, 4, 0, 2, 1), ShuffleRules.moveToEnd(intArrayOf(3, 1, 4, 0, 2), 1))
        assertArrayEquals(intArrayOf(0, 1), ShuffleRules.moveToEnd(intArrayOf(0, 1), 1))
    }
}
