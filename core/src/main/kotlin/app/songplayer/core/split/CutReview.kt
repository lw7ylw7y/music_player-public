package app.songplayer.core.split

/**
 * The actions of "Check a cut" (UX Design 4.11 (K3)) on a run record: move, keep, remove, and which cut comes next. Each returns the changed record
 * (or null when the 5 s rule refuses it); the rules themselves are those of [SplitPlan]. The record keeps the cuts in time order, so a cut is
 * known by its place in the list.
 */
object CutReview {
    /** How far one Earlier or Later moves the cut, in seconds (UX Design D-2; the user tries it). */
    const val STEP_SEC = 0.5

    /** How much of the original "Play the cut" plays on each side of the cut, in seconds (UX Design D-3; the user tries it). */
    const val CLIP_SEC = 10.0

    private fun planOf(record: RunRecord, durationSec: Double) =
        SplitPlan(durationSec, record.cuts.map { PlanCut(it.timeSec, it.halfGapSec, it.confident, it.checked) })

    private fun RunRecord.withPlan(plan: SplitPlan) = copy(cuts = plan.cuts.map { RunCut(it.timeSec, it.halfGapSec, it.confident, it.checked) })

    /** The places of the cuts that are uncertain and not yet looked at, in time order. */
    fun uncertain(record: RunRecord): List<Int> = record.cuts.indices.filter { !record.cuts[it].confident && !record.cuts[it].checked }

    /** The next uncertain cut after place [index], or null when it was the last (then the review returns to the list). */
    fun nextUncertain(record: RunRecord, index: Int): Int? = uncertain(record).firstOrNull { it > index }

    /** True when the cut can move by [deltaSec] without breaking the 5 s rule (the Earlier and Later buttons are off when it cannot). */
    fun canMove(record: RunRecord, durationSec: Double, index: Int, deltaSec: Double): Boolean {
        val cut = record.cuts.getOrNull(index) ?: return false
        return cut.timeSec + deltaSec in planOf(record, durationSec).allowedRange(index)
    }

    /** The cut at [timeSec] (the time line's drag); null when that breaks the rule. A cut that was moved counts as looked at. */
    fun moveTo(record: RunRecord, durationSec: Double, index: Int, timeSec: Double): RunRecord? {
        val plan = planOf(record, durationSec)
        return if (plan.move(index, timeSec)) record.withPlan(plan) else null
    }

    fun nudge(record: RunRecord, durationSec: Double, index: Int, deltaSec: Double): RunRecord? {
        val cut = record.cuts.getOrNull(index) ?: return null
        return moveTo(record, durationSec, index, cut.timeSec + deltaSec)
    }

    /** "Use this cut": kept as it is, and counted as looked at. */
    fun use(record: RunRecord, durationSec: Double, index: Int): RunRecord {
        val plan = planOf(record, durationSec)
        plan.confirm(index)
        return record.withPlan(plan)
    }

    /** "Remove this cut": the two songs around it become one. */
    fun remove(record: RunRecord, durationSec: Double, index: Int): RunRecord {
        val plan = planOf(record, durationSec)
        plan.remove(index)
        return record.withPlan(plan)
    }

    /** The times between which cut [index] may be put (5 s from its neighbours and from the ends). */
    fun range(record: RunRecord, durationSec: Double, index: Int): ClosedFloatingPointRange<Double> = planOf(record, durationSec).allowedRange(index)

    /** The part of the original "Play the cut" plays, as start and end in milliseconds, inside the file. */
    fun clip(record: RunRecord, durationSec: Double, index: Int): LongRange {
        val t = record.cuts[index].timeSec
        return (Math.max(0.0, t - CLIP_SEC) * 1000).toLong()..(Math.min(durationSec, t + CLIP_SEC) * 1000).toLong()
    }

    /** The gap the app found around cut [index] (for the time line and its spoken equivalent), or null for a cut placed or moved by hand. */
    fun gap(record: RunRecord, index: Int): ClosedFloatingPointRange<Double>? =
        record.cuts[index].halfGapSec?.let { (record.cuts[index].timeSec - it)..(record.cuts[index].timeSec + it) }
}
