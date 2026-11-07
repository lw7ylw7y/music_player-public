package app.songplayer.core.split

/**
 * One boundary between two songs. [halfGapSec] is how far the quiet stretch it sits in reaches on each side of [timeSec] (the cutter keeps
 * the cut inside it); null for a cut the user moved or placed, which has no gap to stay in. [confident] and [checked] drive the review.
 */
data class PlanCut(val timeSec: Double, val halfGapSec: Double?, val confident: Boolean, val checked: Boolean = false)

/**
 * The editable list of cuts for one file (Requirements R-3 to R-6, Technical Design 4.4). The app's own proposals come from [GapFinder];
 * the user may move, remove or add cuts, but never closer than [MIN_DISTANCE_SEC] to a neighbour or to either end of the file (the 60 s
 * rule of the finder applies to its proposals only, because interludes and skits can be short).
 */
class SplitPlan(val durationSec: Double, cuts: List<PlanCut>) {
    private val list = cuts.sortedBy { it.timeSec }.toMutableList()

    val cuts: List<PlanCut> get() = list.toList()

    /** One more song than there are cuts. */
    val songCount: Int get() = list.size + 1

    /** Uncertain cuts the user has not checked yet (the confirm step counts them, R-3). */
    val unchecked: Int get() = list.count { !it.confident && !it.checked }

    /** The times between which cut [index] may be moved. */
    fun allowedRange(index: Int): ClosedFloatingPointRange<Double> {
        val low = if (index == 0) 0.0 else list[index - 1].timeSec
        val high = if (index == list.size - 1) durationSec else list[index + 1].timeSec
        return (low + MIN_DISTANCE_SEC)..(high - MIN_DISTANCE_SEC)
    }

    /** Moves cut [index]; false (and nothing changes) when the new time breaks the 5 s rule. The cut counts as checked. */
    fun move(index: Int, timeSec: Double): Boolean {
        if (index !in list.indices || timeSec !in allowedRange(index)) return false
        list[index] = PlanCut(timeSec, null, list[index].confident, checked = true)
        return true
    }

    /** Marks cut [index] as looked at and kept as it is ("Use this cut"). */
    fun confirm(index: Int) {
        if (index in list.indices) list[index] = list[index].copy(checked = true)
    }

    /** Removes cut [index]: the two songs around it become one. */
    fun remove(index: Int) {
        if (index in list.indices) list.removeAt(index)
    }

    /** Adds a cut by hand; false when it breaks the 5 s rule. */
    fun add(timeSec: Double): Boolean {
        if (timeSec.isNaN() || timeSec < MIN_DISTANCE_SEC || durationSec - timeSec < MIN_DISTANCE_SEC) return false
        if (list.any { Math.abs(it.timeSec - timeSec) < MIN_DISTANCE_SEC }) return false
        list.add(PlanCut(timeSec, null, confident = true, checked = true))
        list.sortBy { it.timeSec }
        return true
    }

    companion object {
        const val MIN_DISTANCE_SEC = 5.0

        /** The plan from the finder's cuts: every one of them is used, the uncertain ones waiting for the user's look. */
        fun from(cuts: List<Cut>, durationSec: Double): SplitPlan =
            SplitPlan(durationSec, cuts.map { PlanCut(it.timeSec, it.lengthSec / 2, it.confident) })
    }
}
