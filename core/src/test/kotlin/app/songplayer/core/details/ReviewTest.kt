package app.songplayer.core.details

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private class FakeStore(var held: Set<String> = emptySet()) : ReviewStore {
        val applied = mutableListOf<Pair<String, List<FieldDiff>>>()
        val rejected = mutableListOf<String>()
        override fun applyReview(songKey: String, diffs: List<FieldDiff>, nowSec: Long): Boolean {
            if (songKey !in held) return false
            held = held - songKey; applied += songKey to diffs; return true
        }
        override fun reject(songKey: String, nowSec: Long): Boolean {
            if (songKey !in held) return false
            held = held - songKey; rejected += songKey; return true
        }
    }

    private fun cand(title: String, artist: String = "A", score: Double = 0.9, album: String? = null) =
        Candidate(title, listOf(artist), 200, score, if (album == null) emptyList() else listOf(ReleaseGroup(album, "Album")))

    private val songs = List(4) { song("s$it.mp3", title = null, artist = null, album = null) }
    private val items = songs.map { ReviewItem(it, listOf(cand("Best ${it.fileName}", album = "Alb"), cand("Second"))) }
    private fun session(store: FakeStore) = ReviewSession(items, store) { 100 }.also { store.held = songs.map { s -> s.key.value }.toSet() }

    @Test
    fun Review_UseThis_AppliesAndAdvances() {
        val store = FakeStore()
        val s = session(store)
        assertEquals(0, s.selected) // the best match is selected first
        s.select(1)
        assertTrue(s.use())
        val (key, diffs) = store.applied.single()
        assertEquals(songs[0].key.value, key)
        assertEquals(listOf(DetailField.Title, DetailField.Artist), diffs.map { it.field }) // "Second" has no album
        assertEquals(1, s.index)
        assertEquals(0, s.selected) // the next song starts on its best match
        assertEquals(ReviewCounts(filledIn = 1), s.counts)
    }

    @Test
    fun Review_UseThis_FillsOnlyWhatIsMissing() {
        val store = FakeStore()
        val partly = song("p.mp3", title = "Mine", artist = null, album = "My album")
        store.held = setOf(partly.key.value)
        val s = ReviewSession(listOf(ReviewItem(partly, listOf(cand("Their title", album = "Their album")))), store) { 1 }
        s.use()
        assertEquals(listOf(FieldDiff(DetailField.Artist, null, "A")), store.applied.single().second)
    }

    @Test
    fun Review_ReplaceOn_UseThisReplaces() {
        val store = FakeStore()
        val full = song("f.mp3", title = "Old", artist = "Old artist", album = "Old album")
        store.held = setOf(full.key.value)
        val item = ReviewItem(full, listOf(cand("New", album = "Alb")))
        ReviewSession(listOf(item), store, replace = false) { 1 }.use()
        assertEquals(emptyList<FieldDiff>(), store.applied.single().second) // setting off: nothing to fill
        store.held = setOf(full.key.value)
        store.applied.clear()
        ReviewSession(listOf(item), store, replace = true) { 1 }.use()
        assertEquals(listOf(DetailField.Title, DetailField.Artist, DetailField.Album), store.applied.single().second.map { it.field })
    }

    @Test
    fun Review_NoneOfThese_RejectsForGood() {
        val store = FakeStore()
        val s = session(store)
        assertTrue(s.reject())
        assertEquals(listOf(songs[0].key.value), store.rejected)
        assertEquals(ReviewCounts(rejected = 1), s.counts)
        assertEquals(1, s.index)
    }

    @Test
    fun Review_Skip_KeepsHeld() {
        val store = FakeStore()
        val s = session(store)
        s.skip()
        assertEquals(emptyList<String>(), store.rejected)
        assertEquals(emptyList<Pair<String, List<FieldDiff>>>(), store.applied)
        assertTrue(songs[0].key.value in store.held) // it comes back the next time Review opens
        assertEquals(ReviewCounts(skipped = 1), s.counts)
    }

    @Test
    fun Review_Finished_ShowsTheCounts() {
        val store = FakeStore()
        val s = session(store)
        s.use(); s.reject(); s.skip()
        assertFalse(s.finished)
        s.use()
        assertTrue(s.finished)
        assertEquals(null, s.current)
        assertEquals(ReviewCounts(filledIn = 2, rejected = 1, skipped = 1), s.counts)
        assertEquals(4, s.counts.decided)
        assertEquals(s.total, s.counts.decided) // the counts add up
        s.use(); s.skip() // nothing happens after the end
        assertEquals(4, s.counts.decided)
    }

    @Test
    fun Review_ASongDecidedMeanwhile_IsPassedOverWithoutACount() {
        val store = FakeStore()
        val s = session(store)
        store.held = store.held - songs[0].key.value // edited by hand while the screen was open
        assertFalse(s.use())
        assertEquals(1, s.index)
        assertEquals(ReviewCounts(), s.counts)
    }

    @Test
    fun Review_SelectingOutsideTheChoices_IsIgnored() {
        val s = session(FakeStore())
        s.select(5); s.select(-1)
        assertEquals(0, s.selected)
    }

    @Test
    fun Review_SameSongOnSeveralReleases_IsOneChoice() {
        val list = listOf(cand("夜曲", "周杰伦", 0.94, "A"), cand(" 夜曲 ", "周杰伦", 0.93, "B"), cand("Other", "X", 0.5))
        assertEquals(listOf("夜曲", "Other"), ReviewRules.collapse(list).map { it.title })
        assertEquals(0.94, ReviewRules.collapse(list)[0].score, 0.0)
    }
}
