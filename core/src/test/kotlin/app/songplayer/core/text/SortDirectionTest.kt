package app.songplayer.core.text

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Story A6: the order can be reversed; a reversed list is the default list backwards. */
class SortDirectionTest {
    private val keyer = FakeKeyer()

    private val songs = listOf(
        song("a.mp3", id = 1, title = "Bravo", artist = "Zed", album = "Hits", dateAddedSec = 30),
        song("b.mp3", id = 2, title = "Alpha", artist = "Yan", album = "Blue", dateAddedSec = 10),
        song("c.mp3", id = 3, title = "Charlie", artist = "Zed", album = "Hits", dateAddedSec = 20),
        song("d.mp3", id = 4, title = "Alpha", artist = "Yan", album = "Green", dateAddedSec = 10), // ties with b.mp3 in every field
    ).map { IndexedSong.of(it, keyer) }

    @Test
    fun Sorted_Reversed_IsTheDefaultOrderBackwards() {
        for (order in SortOrder.entries) {
            val forward = songs.sorted(order).map { it.song.key.value }
            assertEquals("songs by $order", forward.reversed(), songs.sorted(order, reversed = true).map { it.song.key.value })
            val albums = Albums.group(songs, keyer).sorted(order).map { it.key }
            assertEquals("albums by $order", albums.reversed(), Albums.group(songs, keyer).sorted(order, reversed = true).map { it.key })
        }
    }

    @Test
    fun Sorted_Reversed_KeepsTiesInADeterministicOrder() {
        // b.mp3 and d.mp3 tie by title, artist and date; the same input in another order gives the same reversed list.
        val one = songs.sorted(SortOrder.Title, reversed = true).map { it.song.key.value }
        val other = songs.shuffled(java.util.Random(7)).sorted(SortOrder.Title, reversed = true).map { it.song.key.value }
        assertEquals(one, other)
        assertNotEquals("the reversed list differs from the default", songs.sorted(SortOrder.Title).map { it.song.key.value }, one)
    }

    @Test
    fun Sections_ReversedList_LabelsFollowTheListOrder() {
        val sorted = songs.sorted(SortOrder.Title, reversed = true)
        val sections = Sections.of(Sections.letters(sorted.map { it.keys.sortTitle }))
        assertEquals(listOf("C", "B", "A"), sections.map { it.label })
        assertEquals(listOf(0, 1, 2), sections.map { it.firstIndex })
        // Dragging the handle to the top of its track lands on the first item as shown, which is now the last letter.
        assertEquals("C", Sections.at(sections, 0f)?.label)
    }

    @Test
    fun SortWords_NaturalAndReversed_ForEachField() {
        assertEquals(SortWords.AToZ, sortWords(SortOrder.Title, reversed = false))
        assertEquals(SortWords.ZToA, sortWords(SortOrder.Title, reversed = true))
        assertEquals(SortWords.AToZ, sortWords(SortOrder.Artist, reversed = false))
        assertEquals(SortWords.ZToA, sortWords(SortOrder.Artist, reversed = true))
        assertEquals(SortWords.NewestFirst, sortWords(SortOrder.RecentlyAdded, reversed = false))
        assertEquals(SortWords.OldestFirst, sortWords(SortOrder.RecentlyAdded, reversed = true))
    }
}
