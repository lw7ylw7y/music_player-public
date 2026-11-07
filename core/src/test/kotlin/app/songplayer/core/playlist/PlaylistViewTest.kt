package app.songplayer.core.playlist

import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song as fixtureSong
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistViewTest {
    private fun song(n: Int, durationMs: Long = 180_000) = fixtureSong("$n.mp3", id = n.toLong(), durationMs = durationMs)

    private fun entryFor(song: Song, id: Long) = PlaylistEntry(id, song.key, song.fileName, song.sizeBytes, song.durationMs)

    private fun ghost(id: Long, name: String) =
        PlaylistEntry(id, SongKey("external_primary:Music/$name"), name, 1_234, 60_000)

    @Test
    fun PlaylistView_Items_FollowTheEntriesAndPairEachWithItsLibrarySong() {
        val a = song(1)
        val b = song(2)
        val entries = listOf(entryFor(b, 10), entryFor(a, 11))
        val items = PlaylistView.items(entries, listOf(a, b))
        assertEquals(listOf(10L, 11L), items.map { it.entry.id })
        assertEquals(listOf(b, a), items.map { it.song })
    }

    @Test
    fun PlaylistView_SongGoneFromTheLibrary_StaysListedWithoutASong() {
        val a = song(1)
        val items = PlaylistView.items(listOf(entryFor(a, 1), ghost(2, "gone.mp3")), listOf(a))
        assertEquals(2, items.size)
        assertNull(items[1].song)
        assertEquals("gone.mp3", items[1].entry.fileName)
    }

    @Test
    fun PlaylistView_Queue_HoldsOnlySongsTheLibraryHas_AndTappedRowMapsToItsPlace() {
        val a = song(1)
        val c = song(3)
        val items = PlaylistView.items(listOf(entryFor(a, 1), ghost(2, "gone.mp3"), entryFor(c, 3)), listOf(a, c))
        val queue = PlaylistView.queue(items)
        assertEquals(listOf(a, c), queue)
        assertEquals(0, PlaylistView.queueIndexOf(items, entryId = 1))
        assertEquals(1, PlaylistView.queueIndexOf(items, entryId = 3))
        assertNull("a song that is gone cannot start", PlaylistView.queueIndexOf(items, entryId = 2))
        assertNull("an unknown row cannot start", PlaylistView.queueIndexOf(items, entryId = 99))
    }

    @Test
    fun PlaylistView_Queue_IsACopy_SoLaterEditsDoNotChangeIt() {
        val a = song(1)
        val items = PlaylistView.items(listOf(entryFor(a, 1)), listOf(a)).toMutableList()
        val queue = PlaylistView.queue(items)
        items.clear()
        assertEquals(listOf(a), queue)
    }

    @Test
    fun PlaylistView_ShuffleStart_IsAnyPlaceInTheQueue_AndNullForAnEmptyOne() {
        assertNull(PlaylistView.shuffleStart(0, Random(1)))
        val seen = (1..200).map { PlaylistView.shuffleStart(5, Random(it.toLong()))!! }.toSet()
        assertTrue(seen.all { it in 0..4 })
        assertEquals("every place can be chosen", setOf(0, 1, 2, 3, 4), seen)
    }

    @Test
    fun PlaylistView_Summary_SecondsOfPlayableSongsOnly() {
        val a = song(1, durationMs = 60_000)
        val b = song(2, durationMs = 90_000)
        val items = PlaylistView.items(listOf(entryFor(a, 1), ghost(2, "gone.mp3"), entryFor(b, 3)), listOf(a, b))
        assertEquals(150_000L, PlaylistView.playableDurationMs(items))
    }

    @Test
    fun PlaylistView_AddMessage_OneSongAddedOrAlreadyIn() {
        assertEquals(AddMessage.AddedOne, PlaylistView.addMessage(requested = 1, added = 1, alreadyIn = 0))
        assertEquals(AddMessage.AlreadyIn, PlaylistView.addMessage(requested = 1, added = 0, alreadyIn = 1))
    }

    @Test
    fun PlaylistView_AddMessage_SeveralSongsGiveACount_AndNothingNewIsAlreadyIn() {
        assertEquals(AddMessage.AddedMany(3, 0), PlaylistView.addMessage(3, 3, 0))
        assertEquals(AddMessage.AddedMany(2, 1), PlaylistView.addMessage(3, 2, 1))
        assertEquals(AddMessage.AddedMany(1, 2), PlaylistView.addMessage(3, 1, 2))
        assertEquals(AddMessage.AlreadyIn, PlaylistView.addMessage(3, 0, 3))
    }

    @Test
    fun PlaylistView_DragSteps_AreWholeRowsTowardZero() {
        assertEquals(0, PlaylistView.dragSteps(40f, 100f))
        assertEquals(1, PlaylistView.dragSteps(100f, 100f))
        assertEquals(2, PlaylistView.dragSteps(250f, 100f))
        assertEquals(-2, PlaylistView.dragSteps(-260f, 100f))
        assertEquals(0, PlaylistView.dragSteps(500f, 0f))
    }

    @Test
    fun PlaylistView_MoveTarget_StaysInsideTheList() {
        assertEquals(3, PlaylistView.moveTarget(1, 2, 5))
        assertEquals(4, PlaylistView.moveTarget(3, 9, 5))
        assertEquals(0, PlaylistView.moveTarget(1, -9, 5))
        assertEquals(0, PlaylistView.moveTarget(0, 1, 0))
    }
}
