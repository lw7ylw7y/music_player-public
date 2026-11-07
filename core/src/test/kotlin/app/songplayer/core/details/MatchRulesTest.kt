package app.songplayer.core.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRulesTest {
    private fun c(title: String, score: Double, dur: Int? = 230, artists: List<String> = listOf("A"), albums: List<ReleaseGroup> = emptyList()) =
        Candidate(title, artists, dur, score, albums)

    @Test
    fun MatchRules_RecordedAnswers_SureHeldTiedNone() {
        // Sure: high score, length fits, the runner-up is far behind.
        val sure = MatchRules.decide(listOf(c("Song", 0.97), c("Other", 0.60)), 231)
        assertEquals("Song", (sure as MatchDecision.Sure).match.title)
        // The real answers from the first coverage check: 0.9567 is sure, 0.9427 is held.
        assertTrue(MatchRules.decide(listOf(c("Song", 0.9567181, 230)), 228) is MatchDecision.Sure)
        assertTrue(MatchRules.decide(listOf(c("Song", 0.9427413, 263)), 261) is MatchDecision.Held)
        // Unsure: the score is low.
        assertTrue(MatchRules.decide(listOf(c("Song", 0.80)), 230) is MatchDecision.Held)
        // Tied: two different songs with equal scores are both held.
        val tied = MatchRules.decide(listOf(c("One", 0.98), c("Two", 0.98, artists = listOf("B")), c("Three", 0.5), c("Four", 0.4)), 230)
        assertEquals(listOf("One", "Two", "Three"), (tied as MatchDecision.Held).candidates.map { it.title })
        // None.
        assertEquals(MatchDecision.NotFound, MatchRules.decide(emptyList(), 230))
    }

    @Test
    fun MatchRules_LengthOutsideTheTolerance_IsHeld() {
        assertTrue(MatchRules.decide(listOf(c("Song", 0.99, dur = 230)), 236) is MatchDecision.Held)
        assertTrue(MatchRules.decide(listOf(c("Song", 0.99, dur = 230)), 235) is MatchDecision.Sure)
        assertTrue(MatchRules.decide(listOf(c("Song", 0.99, dur = null)), 230) is MatchDecision.Held)
    }

    @Test
    fun MatchRules_TwoCandidatesNamingTheSameSong_AreStillSure() {
        val same = MatchRules.decide(listOf(c("Song", 0.98), c(" song ", 0.97)), 230)
        assertTrue(same is MatchDecision.Sure)
    }

    @Test
    fun MatchRules_SeveralAlbums_TakesTheFirstAlbum() {
        val fill = MatchRules.fill(
            c("夜曲", 0.97, artists = listOf("周杰伦", "Guest"), albums = listOf(ReleaseGroup("Hits", "Single"), ReleaseGroup("十一月的萧邦", "Album"), ReleaseGroup("Best", "Album"))),
        )
        assertEquals(MatchFill("夜曲", "周杰伦, Guest", "十一月的萧邦"), fill)
    }

    @Test
    fun MatchRules_NoAlbumOfTypeAlbum_LeavesAlbumMissing() {
        val fill = MatchRules.fill(c("Song", 0.97, artists = emptyList(), albums = listOf(ReleaseGroup("Single", "Single"))))
        assertNull(fill.album)
        assertNull(fill.artist)
    }

    @Test
    fun MatchRules_ReplaceOn_ReplacesDifferingFields_OnlyWhatTheMatchHas() {
        val match = c("New", 0.97, albums = listOf(ReleaseGroup("Alb", "Album")))
        val song = app.songplayer.core.library.song("x.mp3", title = "Old", artist = "A", album = "Other")
        // Off: nothing is missing, nothing changes.
        assertEquals(emptyList<FieldDiff>(), MatchRules.diffs(song, match))
        // On: title and album differ; the artist is the same so it is left alone.
        assertEquals(
            listOf(FieldDiff(DetailField.Title, "Old", "New"), FieldDiff(DetailField.Album, "Other", "Alb")),
            MatchRules.diffs(song, match, replace = true),
        )
        // The match has no Album: the song's own album stays.
        assertEquals(listOf(FieldDiff(DetailField.Title, "Old", "New")), MatchRules.diffs(song, c("New", 0.97), replace = true))
        assertEquals(false, MatchRules.needsLookup(song, replace = false))
        assertEquals(true, MatchRules.needsLookup(song, replace = true))
    }

    @Test
    fun MatchRules_NeedsLookup_OnlyNewSongs_MeansAddedAtOrAfterTheCutoff() {
        val missing = { added: Long -> app.songplayer.core.library.song("n.mp3", title = null, dateAddedSec = added) }
        assertEquals(true, MatchRules.needsLookup(missing(100), replace = false, newSinceSec = null))
        assertEquals(false, MatchRules.needsLookup(missing(99), replace = false, newSinceSec = 100))
        assertEquals(true, MatchRules.needsLookup(missing(100), replace = false, newSinceSec = 100))
        assertEquals(false, MatchRules.needsLookup(missing(99), replace = true, newSinceSec = 100))
    }

    @Test
    fun MatchRules_ReplaceOn_CosmeticDifferences_AreNotReplaced() {
        val match = c("Rain", 0.97, artists = listOf("周杰伦"))
        val song = app.songplayer.core.library.song("y.mp3", title = " rain ", artist = "周杰倫", album = "x")
        val fold = { a: String, b: String -> a.lowercase().trim().replace('倫', '伦') == b.lowercase().trim().replace('倫', '伦') }
        // With the app's fold the case, the spaces and the Traditional character are not differences; only a real one is.
        assertEquals(emptyList<FieldDiff>(), MatchRules.diffs(song, match, replace = true, same = fold))
        assertEquals(
            listOf(FieldDiff(DetailField.Artist, "Someone else", "周杰伦")),
            MatchRules.diffs(song.copy(title = "Rain", artist = "Someone else"), match, replace = true, same = fold),
        )
    }

    @Test
    fun HeldCandidates_RoundTrip_AndDamagedTextIsEmpty() {
        val list = listOf(
            c("He said \"hi\"\\", 0.9, dur = null, artists = listOf("甲", "B"), albums = listOf(ReleaseGroup("专辑", "Album"))),
            c("Plain", 0.5),
        )
        assertEquals(list, HeldCandidates.decode(HeldCandidates.encode(list)))
        assertEquals(emptyList<Candidate>(), HeldCandidates.decode("not json"))
        assertEquals(emptyList<Candidate>(), HeldCandidates.decode("[{\"t\":1}]"))
        assertTrue(HeldCandidates.encode(List(3) { c("x".repeat(60), 0.9, albums = List(3) { ReleaseGroup("y".repeat(50), "Album") }) }).length < 2048)
    }
}
