package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDiffTest {
    private val a = song("a.mp3", id = 1)
    private val b = song("b.mp3", id = 2)
    private val c = song("c.mp3", id = 3)

    @Test
    fun LibraryDiff_AddedRemovedChanged_AreExact() {
        val bRetagged = b.copy(title = "new title")
        val plan = LibraryDiff.diff(cache = listOf(a, b), scan = listOf(bRetagged, c))
        assertEquals(listOf(c), plan.added)
        assertEquals(listOf(a.key), plan.removed)
        assertEquals(listOf(bRetagged), plan.changed)
        assertTrue("a retag is not a changed file", plan.filesChanged.isEmpty())
    }

    @Test
    fun LibraryDiff_SameKeyNewMediaStoreId_IsChanged() {
        val moved = a.copy(mediaStoreId = 99)
        val plan = LibraryDiff.diff(cache = listOf(a), scan = listOf(moved))
        assertEquals(listOf(moved), plan.changed)
        assertTrue(plan.added.isEmpty() && plan.removed.isEmpty())
    }

    @Test
    fun LibraryDiff_NothingDifferent_IsEmptyPlan() {
        assertTrue(LibraryDiff.diff(listOf(a, b), listOf(b, a)).isEmpty)
    }

    @Test
    fun LibraryDiff_SizeOrModifiedTimeChange_MarksFileChanged() {
        val replaced = a.copy(sizeBytes = a.sizeBytes + 1)
        val touched = b.copy(dateModifiedSec = b.dateModifiedSec + 5)
        val plan = LibraryDiff.diff(listOf(a, b), listOf(replaced, touched))
        assertEquals(listOf(a.key, b.key), plan.filesChanged)
    }

    @Test
    fun LibraryDiff_FirstScanIntoEmptyCache_ReportsNoNewSongs() {
        val plan = LibraryDiff.diff(cache = emptyList(), scan = listOf(a, b))
        assertEquals(listOf(a, b), plan.added)
        assertTrue(plan.newSongs.isEmpty())
    }

    @Test
    fun LibraryDiff_LaterScan_ReportsAddedSongsAsNew() {
        val plan = LibraryDiff.diff(cache = listOf(a), scan = listOf(a, b, c))
        assertEquals(listOf(b, c), plan.newSongs)
    }

    @Test
    fun LibraryDiff_OrderIsDeterministic_ScanOrderForAdds_CacheOrderForRemovals() {
        val plan = LibraryDiff.diff(cache = listOf(c, a), scan = listOf(b))
        assertEquals(listOf(b), plan.added)
        assertEquals(listOf(c.key, a.key), plan.removed)
    }

    @Test
    fun SongKey_ToString_DoesNotRevealThePath() {
        val text = a.key.toString()
        assertTrue(text, !text.contains("a.mp3") && !text.contains("Music"))
    }
}
