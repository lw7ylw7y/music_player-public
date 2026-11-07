package app.songplayer.core.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitSummaryTest {
    private fun record(cuts: Int, written: Int = 0, phase: RunPhase = RunPhase.Reviewing, checked: Boolean = true) = RunRecord(
        "r", SourceIdentity(1, 1, 1, "h"), "Big album.mp3", List(cuts) { RunCut(100.0 * (it + 1), 2.0, false, checked) }, "Music/Albums/", "external_primary", phase,
        List(written) { RunSong(it + 1, "Big album ${"%02d".format(it + 1)}.mp3", 10L + it, 100) }, 1,
    )

    @Test
    fun ReadyToSplit_NamesFolderSpaceAndUncheckedCuts() {
        val summary = SplitSummary.of(record(11, checked = false), 520L * 1024 * 1024, 2L * 1024 * 1024 * 1024)
        assertEquals(12, summary.songCount)
        assertEquals("Big album 01", summary.firstName)
        assertEquals("Big album 12", summary.lastName)
        assertEquals(listOf("Music", "Albums"), summary.folderParts)
        assertEquals(11, summary.uncheckedCuts)
        assertEquals(520L * 1024 * 1024 * 21 / 20, summary.neededBytes)       // the size plus 5 %
        assertTrue(summary.canStart)
        assertEquals(0, SplitSummary.of(record(11), 1, 1_000_000).uncheckedCuts)
    }

    @Test
    fun ReadyToSplit_TooLittleSpace_TurnsTheButtonOff() {
        val size = 1_000_000L
        assertTrue(SplitSummary.of(record(3), size, SplitSummary.spaceNeeded(size)).canStart)            // exactly enough
        val short = SplitSummary.of(record(3), size, SplitSummary.spaceNeeded(size) - 1)
        assertFalse(short.enoughSpace)
        assertFalse(short.canStart)
        assertFalse(SplitSummary.of(record(0), size, size * 2).canStart)                                // one song is not a split
    }

    @Test
    fun SplitOutcome_ReasonFor_MapsHowTheJobEnded() {
        assertEquals(StopReason.Cancelled, SplitOutcome.reasonFor(cancelled = true, failed = false, errorCode = null))
        assertEquals(StopReason.Cancelled, SplitOutcome.reasonFor(cancelled = true, failed = true, errorCode = "SPLIT_NO_SPACE"))   // a cancel wins
        assertEquals(StopReason.NoSpace, SplitOutcome.reasonFor(false, true, app.songplayer.core.errors.SplitError.NoSpace().code))
        assertEquals(StopReason.SourceGone, SplitOutcome.reasonFor(false, true, app.songplayer.core.errors.SplitError.SourceChanged().code))
        assertEquals(StopReason.Failed, SplitOutcome.reasonFor(false, true, "SPLIT_WRITE_FAILED"))
        assertEquals(StopReason.Failed, SplitOutcome.reasonFor(false, true, null))
        assertNull(SplitOutcome.reasonFor(false, false, null))
    }

    @Test
    fun SplitOutcome_FinishedStoppedAndContinued() {
        val done = SplitOutcome.of(record(11, written = 12, phase = RunPhase.Done), earlier = 0, stopped = null)
        assertTrue(done.finished); assertEquals(12, done.now); assertFalse(done.continued)
        val continued = SplitOutcome.of(record(11, written = 12, phase = RunPhase.Done), earlier = 7, stopped = null)
        assertTrue(continued.continued); assertEquals(5, continued.now); assertEquals(7, continued.earlier)
        val cancelled = SplitOutcome.of(record(11, written = 5, phase = RunPhase.Writing), earlier = 0, stopped = StopReason.Cancelled)
        assertFalse(cancelled.finished); assertEquals(5, cancelled.written); assertEquals(StopReason.Cancelled, cancelled.stopped)
        // A job that says "stopped" but whose record is complete is finished; one that gives no reason is a failure.
        assertNull(SplitOutcome.of(record(11, written = 12, phase = RunPhase.Done), 0, StopReason.NoSpace).stopped)
        assertEquals(StopReason.Failed, SplitOutcome.of(record(11, written = 3, phase = RunPhase.Writing), 0, null).stopped)
        assertEquals(StopReason.Failed, SplitOutcome.of(null, 0, null).stopped)
    }
}
