package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongsFoundTest {
    private fun song(minutes: Int, name: String = "a.mp3", size: Long = 1000) =
        Song(SongKey("x:$name"), 1, name, null, null, null, minutes * 60_000L, size, 0, 0)

    private fun record(vararg cuts: RunCut) = RunRecord(
        "r", SourceIdentity(1, 1, 1, "h"), "big.mp3", cuts.toList(), "Music/", "external_primary", RunPhase.Reviewing, emptyList(), 1,
    )

    @Test
    fun SplitEntry_ShownOnlyForLongMp3s_AndRefusesOversizeFiles() {
        assertTrue(SplitEntry.offered(song(45)))
        assertTrue(SplitEntry.offered(song(10)))
        assertTrue(SplitEntry.offered(song(6)))
        assertFalse(SplitEntry.offered(song(5)))
        assertFalse(SplitEntry.offered(song(4)))
        assertFalse(SplitEntry.offered(song(45, name = "a.m4a")))
        assertNull(SplitEntry.refusal(song(45, size = SplitLimits.MAX_BYTES)))
        assertTrue(SplitEntry.refusal(song(45, size = SplitLimits.MAX_BYTES + 1)) is SplitError.TooLarge)
    }

    @Test
    fun SongsFound_ListAndPrimaryAction() {
        val sure = RunCut(252.0, 2.0, true, false)
        val unsure = RunCut(510.0, 1.0, false, false)
        val checked = RunCut(770.0, 1.0, false, true)
        val found = SongsFound.of(record(sure, unsure, checked), 1000.0)
        assertEquals(4, found.songCount)
        assertEquals(listOf(0.0, 252.0, 510.0, 770.0), found.rows.map { it.startSec })
        assertEquals(listOf(252.0, 510.0, 770.0, 1000.0), found.rows.map { it.endSec })
        assertEquals(listOf(FoundState.LooksRight, FoundState.LooksRight, FoundState.NeedsCheck, FoundState.LooksRight), found.rows.map { it.state })
        assertEquals(1, found.uncertain)
        assertEquals(3, found.looksRight)
        assertEquals(FoundPrimary.Check(1), found.primary)
        assertEquals(258.0, found.rows[1].lengthSec, 0.0)
        assertEquals(FoundPrimary.Split(3), SongsFound.of(record(sure, checked), 1000.0).primary)
    }

    @Test
    fun SongsFound_NoGaps_OffersAddACut() {
        val found = SongsFound.of(record(), 3000.0)
        assertTrue(found.noGaps)
        assertEquals(FoundPrimary.AddACut, found.primary)
        assertEquals(1, found.songCount)
        assertEquals(3000.0, found.rows[0].lengthSec, 0.0)
    }

    @Test
    fun SplitEdit_AddACut_KeepsTheFiveSecondRule_AndTheSongLimit() {
        val base = record(RunCut(300.0, 2.0, true, false))
        val added = SplitEdit.addCut(base, 1000.0, 600.0)!!
        assertEquals(listOf(300.0, 600.0), added.cuts.map { it.timeSec })
        assertTrue(added.cuts[1].confident && added.cuts[1].checked && added.cuts[1].halfGapSec == null)   // the user's own cut needs no check
        assertNull(SplitEdit.addCut(base, 1000.0, 303.0))      // closer than 5 s to a neighbour
        assertNull(SplitEdit.addCut(base, 1000.0, 3.0))        // closer than 5 s to the start
        assertNull(SplitEdit.addCut(base, 1000.0, 998.0))      // and to the end
        val full = base.copy(cuts = List(SplitLimits.MAX_SONGS - 1) { RunCut(10.0 * (it + 1), null, true, true) })
        assertNull(SplitEdit.addCut(full, 5000.0, 4000.0))     // 120 songs already
    }

    @Test
    fun DurationFor_NeverEndsBeforeTheLastCut() {
        val r = record(RunCut(990.0, 2.0, true, false))
        assertEquals(1000.0, durationFor(r, 1000.0), 0.0)
        assertEquals(995.0, durationFor(r, 960.0), 0.0)            // the index's length was short: the last song still has its 5 s
        val found = SongsFound.of(r, durationFor(r, 960.0))
        assertTrue(found.rows.all { it.lengthSec > 0 })
    }

    @Test
    fun SplitTime_FormatsAndParses() {
        assertEquals("8:30", SplitTime.format(510.4))
        assertEquals("1:02:03", SplitTime.format(3723.0))
        assertEquals("0:00", SplitTime.format(-5.0))
        assertEquals("8:29.5", SplitTime.formatHalf(509.5))
        assertEquals("8:30", SplitTime.formatHalf(509.9))
        assertEquals("8:30.5", SplitTime.formatHalf(510.5))
        assertEquals("1:02:03.5", SplitTime.formatHalf(3723.5))
        assertEquals(510.0, SplitTime.parse("8:30")!!, 0.0)
        assertEquals(3723.5, SplitTime.parse(" 1:02:03.5 ")!!, 0.0)
        assertEquals(90.0, SplitTime.parse("90")!!, 0.0)
        for (bad in listOf("", ":", "8:", "8:75", "a:30", "1.5:30", "1:2:3:4", "8:30:", "-3", "8,30")) assertNull(bad, SplitTime.parse(bad))
    }
}
