package app.songplayer.core.playlist

import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaylistRulesTest {
    private fun entry(n: Int, size: Long = 1_000) =
        PlaylistEntry(id = n.toLong(), songKey = SongKey("external_primary:Music/$n.mp3"), fileName = "$n.mp3", sizeBytes = size, durationMs = 1_000)

    private fun ids(list: List<PlaylistEntry>) = list.map { it.id.toInt() }

    @Test
    fun PlaylistRules_AddDuplicate_NoChangeAndReports() {
        val list = listOf(entry(1), entry(2))
        assertNull(PlaylistRules.add(list, entry(2).copy(id = 0)))
        assertEquals(listOf(1, 2, 0), ids(PlaylistRules.add(list, entry(3).copy(id = 0))!!))
    }

    @Test
    fun PlaylistRules_Add_SameSongAgainWithADifferentRowId_IsStillADuplicate() {
        val list = listOf(entry(1))
        assertNull(PlaylistRules.add(list, entry(1).copy(id = 99)))
    }

    @Test
    fun PlaylistRules_WithoutSongs_DropsEveryEntryOfThoseSongs_AndKeepsTheOrderOfTheRest() {
        val list = (1..5).map { entry(it) }
        val gone = setOf(SongKey("external_primary:Music/2.mp3"), SongKey("external_primary:Music/4.mp3"), SongKey("external_primary:Music/99.mp3"))
        assertEquals(listOf(1, 3, 5), ids(PlaylistRules.withoutSongs(list, gone)))
        assertEquals(listOf(1, 2, 3, 4, 5), ids(PlaylistRules.withoutSongs(list, emptySet())))
    }

    @Test
    fun PlaylistRules_MoveAndRemove_PositionsStayDense() {
        val list = (1..5).map { entry(it) }
        assertEquals(listOf(1, 3, 4, 5, 2), ids(PlaylistRules.move(list, entryId = 2, toIndex = 4)))
        assertEquals(listOf(4, 1, 2, 3, 5), ids(PlaylistRules.move(list, entryId = 4, toIndex = 0)))
        val (without, removed) = PlaylistRules.remove(list, entryId = 3)
        assertEquals(listOf(1, 2, 4, 5), ids(without))
        assertEquals(2, removed!!.index)
    }

    @Test
    fun PlaylistRules_Move_IndexOutsideTheListIsClamped_UnknownIdChangesNothing() {
        val list = (1..3).map { entry(it) }
        assertEquals(listOf(2, 3, 1), ids(PlaylistRules.move(list, 1, toIndex = 99)))
        assertEquals(listOf(3, 1, 2), ids(PlaylistRules.move(list, 3, toIndex = -5)))
        assertSame(list, PlaylistRules.move(list, 42, 0))
    }

    @Test
    fun PlaylistRules_UndoRemove_RestoresInPlace() {
        val list = (1..5).map { entry(it) }
        val (without, removed) = PlaylistRules.remove(list, 3)
        assertEquals(list, PlaylistRules.undoRemove(without, removed!!))
    }

    @Test
    fun PlaylistRules_UndoRemove_AfterOtherChanges_KeepsPlaceWhereItCanAndOtherwiseGoesToTheEnd() {
        val list = (1..5).map { entry(it) }
        val (without, removed) = PlaylistRules.remove(list, 5)
        val shorter = PlaylistRules.remove(without, 4).first
        assertEquals(listOf(1, 2, 3, 5), ids(PlaylistRules.undoRemove(shorter, removed!!)!!))
    }

    @Test
    fun PlaylistRules_UndoRemove_SongAddedAgainMeanwhile_ChangesNothing() {
        val list = (1..3).map { entry(it) }
        val (without, removed) = PlaylistRules.remove(list, 2)
        val readded = PlaylistRules.add(without, entry(2).copy(id = 77))!!
        assertNull(PlaylistRules.undoRemove(readded, removed!!))
    }

    @Test
    fun PlaylistRules_RemoveUnknownEntry_ReturnsNullAndTheListUnchanged() {
        val list = listOf(entry(1))
        val (same, removed) = PlaylistRules.remove(list, 9)
        assertSame(list, same)
        assertNull(removed)
    }

    @Test
    fun PlaylistRules_CleanName_TrimsAndRejectsEmptyOrTooLong() {
        assertEquals("Driving", PlaylistRules.cleanName("  Driving "))
        assertEquals("好听的歌", PlaylistRules.cleanName("好听的歌"))
        assertNull(PlaylistRules.cleanName("   "))
        assertNull(PlaylistRules.cleanName("x".repeat(PlaylistRules.MAX_NAME_LENGTH + 1)))
        assertNotNull(PlaylistRules.cleanName("x".repeat(PlaylistRules.MAX_NAME_LENGTH)))
    }

    @Test
    fun PlaylistRules_ReorderRacingRemove_EndsConsistent() {
        // A screen drew [1..6] and sent "move entry 2 to the end" and "remove entry 2" at the same time. Whichever the
        // store runs first, applying both to the current list leaves a dense, duplicate-free list; because actions name
        // the entry, not a position, the second action finds the right entry or finds nothing.
        repeat(200) { round ->
            val start = (1..6).map { entry(it) }
            var current: List<PlaylistEntry> = start
            val lock = Any()
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val moveFirst = round % 2 == 0
            val actions = listOf<() -> Unit>(
                { synchronized(lock) { current = PlaylistRules.move(current, 2, toIndex = 5) } },
                { synchronized(lock) { current = PlaylistRules.remove(current, 2).first } },
            ).let { if (moveFirst) it else it.reversed() }
            val done = actions.map { action -> pool.submit { gate.await(); action() } }
            gate.countDown()
            done.forEach { it.get(5, TimeUnit.SECONDS) }
            pool.shutdown()
            assertEquals("round $round", listOf(1, 3, 4, 5, 6), ids(current))
        }
    }

    @Test
    fun Relinker_OneMatch_Relinks() {
        val gone = entry(1, size = 4_000).copy(fileName = "后来.mp3")
        val here = song("后来.mp3", relativePath = "Download/", sizeBytes = 4_000)
        val result = Relinker.relink(listOf(gone), listOf(here, song("other.mp3")))
        assertEquals(listOf(Relink(gone.id, here.key)), result)
    }

    @Test
    fun Relinker_NoMatchOrTwoMatches_ChangesNothing() {
        val gone = entry(1, size = 4_000).copy(fileName = "a.mp3")
        assertEquals(emptyList<Relink>(), Relinker.relink(listOf(gone), listOf(song("b.mp3", sizeBytes = 4_000))))
        assertEquals(emptyList<Relink>(), Relinker.relink(listOf(gone), listOf(song("a.mp3", sizeBytes = 5_000))))
        val two = listOf(song("a.mp3", relativePath = "Music/", sizeBytes = 4_000), song("a.mp3", relativePath = "Download/", sizeBytes = 4_000))
        assertEquals(emptyList<Relink>(), Relinker.relink(listOf(gone), two))
    }

    @Test
    fun Relinker_SongStillInTheLibrary_IsLeftAlone() {
        val s = song("a.mp3", sizeBytes = 4_000)
        val entry = PlaylistEntry(1, s.key, "a.mp3", 4_000, 1_000)
        assertEquals(emptyList<Relink>(), Relinker.relink(listOf(entry), listOf(s, song("a.mp3", relativePath = "Other/", sizeBytes = 4_000))))
    }

    @Test
    fun Relinker_WouldMakeAnEntryTwice_IsSkipped() {
        // The playlist already holds the song the missing entry would be linked to.
        val present = song("a.mp3", sizeBytes = 4_000)
        val gone = PlaylistEntry(2, SongKey("external_primary:Old/a.mp3"), "a.mp3", 4_000, 1_000)
        val have = PlaylistEntry(1, present.key, "a.mp3", 4_000, 1_000)
        assertEquals(emptyList<Relink>(), Relinker.relink(listOf(have, gone), listOf(present)))
    }

    @Test
    fun Relinker_TwoMissingEntriesForOneSong_OnlyTheFirstIsLinked() {
        val a = PlaylistEntry(1, SongKey("external_primary:Old/a.mp3"), "a.mp3", 4_000, 1_000)
        val b = PlaylistEntry(2, SongKey("external_primary:Older/a.mp3"), "a.mp3", 4_000, 1_000)
        val here = song("a.mp3", relativePath = "Music/", sizeBytes = 4_000)
        assertEquals(listOf(Relink(1, here.key)), Relinker.relink(listOf(a, b), listOf(here)))
    }

    @Test
    fun PlaylistEntry_Of_KeepsWhatRelinkingNeeds() {
        val s = song("x.mp3", sizeBytes = 123, durationMs = 456)
        val e = PlaylistEntry.of(s)
        assertEquals(listOf(s.key, "x.mp3", 123L, 456L), listOf(e.songKey, e.fileName, e.sizeBytes, e.durationMs))
    }

    @Test
    fun PlaylistRules_500Songs_MoveIsDenseAndFast() {
        val list = (1..500).map { entry(it) }
        val start = System.nanoTime()
        val moved = PlaylistRules.move(list, 500, 0)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals((listOf(500) + (1..499)), ids(moved))
        assert(ms < 50) { "moving in a 500-song list took $ms ms" }
    }
}
