package app.songplayer.core.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutReviewTest {
    private val duration = 1000.0
    private fun record(vararg cuts: RunCut) = RunRecord(
        "r", SourceIdentity(1, 1, 1, "h"), "big.mp3", cuts.toList(), "Music/", "external_primary", RunPhase.Reviewing, emptyList(), 1,
    )
    private val sure = RunCut(200.0, 2.0, true, false)
    private val unsure = RunCut(400.0, 1.5, false, false)
    private val unsure2 = RunCut(600.0, 1.0, false, false)
    private val three = record(sure, unsure, unsure2)

    @Test
    fun CutReview_Actions_ChangeThePlan() {
        assertEquals(listOf(1, 2), CutReview.uncertain(three))
        assertEquals(1, CutReview.nextUncertain(three, 0))
        assertEquals(2, CutReview.nextUncertain(three, 1))
        assertNull(CutReview.nextUncertain(three, 2))

        val used = CutReview.use(three, duration, 1)
        assertTrue(used.cuts[1].checked)
        assertEquals(listOf(2), CutReview.uncertain(used))
        assertEquals(400.0, used.cuts[1].timeSec, 0.0)                   // kept as it is

        val moved = CutReview.nudge(three, duration, 1, -CutReview.STEP_SEC)!!
        assertEquals(399.5, moved.cuts[1].timeSec, 0.0)
        assertTrue(moved.cuts[1].checked)                                // moving counts as looking
        assertNull(moved.cuts[1].halfGapSec)                             // and the cut is no longer tied to the gap it was found in

        val removed = CutReview.remove(three, duration, 1)
        assertEquals(listOf(200.0, 600.0), removed.cuts.map { it.timeSec })
        assertEquals(3, removed.songCount)                                // two songs joined
        assertEquals(three.songCount, CutReview.remove(three, duration, 9).songCount) // an unknown place changes nothing
    }

    @Test
    fun CutReview_NextCut_AfterUseRemoveAndNext() {
        val allUnsure = record(RunCut(100.0, 1.0, false, false), RunCut(300.0, 1.0, false, false), RunCut(500.0, 1.0, false, false))
        // Use on the first: the second is next. Use on the last uncertain one: nothing left, the review returns to the list.
        val usedFirst = CutReview.use(allUnsure, duration, 0)
        assertEquals(1, CutReview.nextUncertain(usedFirst, 0))
        val usedAll = CutReview.use(CutReview.use(usedFirst, duration, 1), duration, 2)
        assertNull(CutReview.nextUncertain(usedAll, 2))
        // Remove the first (place 0): the cut that was second is now at place 0, so the next uncertain one is searched from place -1.
        val removedFirst = CutReview.remove(allUnsure, duration, 0)
        assertEquals(listOf(300.0, 500.0), removedFirst.cuts.map { it.timeSec })
        assertEquals(0, CutReview.nextUncertain(removedFirst, -1))
        // Remove the middle one: the former third cut takes its place.
        val removedMiddle = CutReview.remove(allUnsure, duration, 1)
        assertEquals(1, CutReview.nextUncertain(removedMiddle, 0))
        // Remove the last: nothing after it.
        assertNull(CutReview.nextUncertain(CutReview.remove(allUnsure, duration, 2), 1))
        // Next leaves the cut unchecked; it stays on the list of cuts to look at.
        assertEquals(listOf(0, 1, 2), CutReview.uncertain(allUnsure))
    }

    @Test
    fun CutReview_AtTheLimit_ButtonsAreOffAndSpoken() {
        // Exactly 5 s from a neighbour is allowed; one step beyond is not.
        val near = record(RunCut(205.0, 2.0, true, false), RunCut(210.5, 1.0, false, false))
        assertTrue(CutReview.canMove(near, duration, 1, -CutReview.STEP_SEC))
        val edge = record(RunCut(205.0, 2.0, true, false), RunCut(210.0, 1.0, false, false))
        assertTrue(CutReview.canMove(edge, duration, 1, CutReview.STEP_SEC))
        assertFalse(CutReview.canMove(edge, duration, 1, -CutReview.STEP_SEC))
        assertNull(CutReview.nudge(edge, duration, 1, -CutReview.STEP_SEC))
        assertFalse(CutReview.canMove(record(RunCut(5.0, 1.0, false, false)), duration, 0, -CutReview.STEP_SEC))   // the start
        assertFalse(CutReview.canMove(record(RunCut(995.0, 1.0, false, false)), duration, 0, CutReview.STEP_SEC))  // the end
        assertNull(CutReview.moveTo(three, duration, 1, 601.0))                  // beyond the next cut's 5 s
        assertEquals(205.0, CutReview.range(three, duration, 1).start, 0.0)                    // 5 s after the cut before it...
        assertEquals(595.0, CutReview.range(three, duration, 1).endInclusive, 0.0)             // ...and 5 s before the cut after it
    }

    @Test
    fun CutReview_Play_ClipsTheOriginal() {
        assertEquals(390_000L..410_000L, CutReview.clip(three, duration, 1))
        assertEquals(0L..12_000L, CutReview.clip(record(RunCut(2.0, null, false, false)), duration, 0))
        assertEquals(985_000L..1_000_000L, CutReview.clip(record(RunCut(995.0, null, false, false)), duration, 0))
        assertEquals(398.5..401.5, CutReview.gap(three, 1)!!.let { it.start..it.endInclusive })
        assertNull(CutReview.gap(record(RunCut(10.0, null, true, true)), 0))
    }
}
