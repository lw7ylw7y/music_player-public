package app.songplayer.core.resume

import app.songplayer.core.library.SongKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRulesTest {
    private fun key(n: Int) = SongKey("external_primary:Music/$n.mp3")
    private fun entry(n: Int, rank: Int? = null, added: Boolean = false) = QueueEntry(key(n), added, rank)

    private fun state(vararg entries: QueueEntry, current: Int = 0, position: Long = 70_000, shuffle: Boolean = false) =
        ResumeState(entries.toList(), current, position, shuffle, repeatMode = 2, source = "Songs")

    private fun library(vararg n: Int) = n.map { key(it) }.toSet()

    @Test
    fun ResumeState_Validate_EverythingPresent_IsUnchanged() {
        val s = state(entry(1), entry(2), entry(3), current = 1)
        assertEquals(s, ResumeRules.validate(s, library(1, 2, 3)))
    }

    @Test
    fun ResumeState_Validate_DropsMissingSong() {
        val s = state(entry(1), entry(2), entry(3), current = 2)
        val v = ResumeRules.validate(s, library(1, 3))!!
        assertEquals(listOf(key(1), key(3)), v.queue.map { it.songKey })
        // The playing song (3) is still there, now at index 1, and keeps its position.
        assertEquals(1, v.currentIndex)
        assertEquals(70_000L, v.positionMs)
    }

    @Test
    fun ResumeState_Validate_PlayingSongGone_NextOneTakesOverFromTheStart() {
        val s = state(entry(1), entry(2), entry(3), current = 1)
        val v = ResumeRules.validate(s, library(1, 3))!!
        assertEquals(key(3), v.queue[v.currentIndex].songKey)
        assertEquals(0L, v.positionMs)
    }

    @Test
    fun ResumeState_Validate_PlayingSongGoneAndNoneAfter_LastOneTakesOver() {
        val s = state(entry(1), entry(2), entry(3), current = 2)
        val v = ResumeRules.validate(s, library(1, 2))!!
        assertEquals(key(2), v.queue[v.currentIndex].songKey)
        assertEquals(0L, v.positionMs)
    }

    @Test
    fun ResumeState_Validate_NothingLeft_IsNull() {
        assertNull(ResumeRules.validate(state(entry(1), entry(2)), library(9)))
        assertNull(ResumeRules.validate(state(entry(1)), emptySet()))
    }

    @Test
    fun ResumeState_Validate_ShuffleRanksAreRenumberedWithoutChangingTheOrder() {
        // Play order: song 2, song 4, song 1, song 3 (ranks 0 to 3). Song 4 is gone.
        val s = state(entry(1, rank = 2), entry(2, rank = 0), entry(3, rank = 3), entry(4, rank = 1), shuffle = true)
        val v = ResumeRules.validate(s, library(1, 2, 3))!!
        assertTrue(v.shuffleOn)
        assertEquals(listOf(1, 0, 2), v.queue.map { it.shuffleRank })
    }

    @Test
    fun ResumeState_Validate_ShuffleOnButARankIsMissing_ShuffleIsLeftOff() {
        val s = state(entry(1, rank = 0), entry(2, rank = null), shuffle = true)
        val v = ResumeRules.validate(s, library(1, 2))!!
        assertFalse(v.shuffleOn)
        assertTrue(v.queue.all { it.shuffleRank == null })
    }

    @Test
    fun ResumeState_Validate_KeepsRepeatSourceAndAddedFlags() {
        val s = state(entry(1), entry(2, added = true), current = 0)
        val v = ResumeRules.validate(s, library(1, 2))!!
        assertEquals(2, v.repeatMode)
        assertEquals("Songs", v.source)
        assertTrue(v.queue[1].added)
    }

    @Test
    fun Resume_PositionWriteRacingQueueReplace_LastCommittedWins() {
        // A position write for song 1 at index 0 was prepared; then the queue was replaced and song 5 is at index 0.
        assertFalse(ResumeRules.acceptsPosition(storedKeyAtIndex = key(5), key = key(1)))
        // The same write against the queue it belongs to is accepted.
        assertTrue(ResumeRules.acceptsPosition(storedKeyAtIndex = key(1), key = key(1)))
        // An index that no longer exists is not accepted either.
        assertFalse(ResumeRules.acceptsPosition(storedKeyAtIndex = null, key = key(1)))
    }

    @Test
    fun Resume_SaveInterval_IsShorterThanTheFiveSecondTolerance() {
        assertTrue(ResumeRules.POSITION_SAVE_INTERVAL_MS < 5_000)
    }

    @Test
    fun ResumeState_Validate_TwoSongsWithTheSameRank_ShuffleIsLeftOff() {
        val s = state(entry(1, rank = 0), entry(2, rank = 0), entry(3, rank = 1), shuffle = true)
        val v = ResumeRules.validate(s, library(1, 2, 3))!!
        assertFalse(v.shuffleOn)
        assertTrue(v.queue.all { it.shuffleRank == null })
    }

    @Test
    fun ResumeState_Validate_UnknownRepeatModeIsOff_NegativePositionIsZero() {
        val s = state(entry(1), position = -5).copy(repeatMode = 9)
        val v = ResumeRules.validate(s, library(1))!!
        assertEquals(0, v.repeatMode)
        assertEquals(0L, v.positionMs)
    }

    @Test
    fun ResumeState_Validate_ASongQueuedTwice_KeepsBothEntries() {
        val s = state(entry(1), entry(2), entry(1), current = 2)
        val v = ResumeRules.validate(s, library(1, 2))!!
        assertEquals(3, v.queue.size)
        assertEquals(2, v.currentIndex)
    }

    @Test
    fun ResumeState_Validate_OpenedFileIsKeptEvenThoughTheLibraryDoesNotHaveIt() {
        val file = app.songplayer.core.openfile.ExternalFile("content://provider/doc/42", "Morning.mp3")
        val s = state(QueueEntry(file.key, false, null, displayName = file.displayName), current = 0)
        val v = ResumeRules.validate(s, library())!!
        assertEquals(file.key, v.queue.single().songKey)
        assertEquals("Morning.mp3", v.queue.single().displayName)
        assertEquals(70_000L, v.positionMs)
    }

    @Test
    fun ResumeState_Validate_LibrarySongGoneButOpenedFileStays() {
        val file = app.songplayer.core.openfile.ExternalFile("content://provider/doc/42", "Morning.mp3")
        val s = state(entry(1), QueueEntry(file.key, false, null, displayName = file.displayName), current = 0)
        val v = ResumeRules.validate(s, library())!!
        assertEquals(listOf(file.key), v.queue.map { it.songKey })
        assertEquals(0L, v.positionMs)
    }
}
