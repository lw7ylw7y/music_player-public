package app.songplayer.core.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFinderTest {
    private class Case(val name: String, val seconds: Int, val seed: Long, val gaps: List<Triple<Double, Double, Int>>, val flat: Set<Int> = emptySet(), val minGap: Double = GapFinder.CONFIDENT_GAP)

    // The same curves the tool's results were made from (a script outside the repo, `find_cuts` of cli/splitmp3.py): music near -20 dB
    // with a little deterministic noise, gaps at the stated depth (0 to 2 dB above it), all from an integer generator so both sides agree.
    private val cases = listOf(
        Case("album", 1800, 1, listOf(Triple(240.0, 5.0, -100), Triple(515.0, 5.0, -100), Triple(760.0, 6.0, -100), Triple(1010.0, 4.0, -100), Triple(1300.0, 5.0, -100), Triple(1520.0, 5.0, -100))),
        Case("nogaps", 1800, 2, emptyList()),
        Case("close", 1800, 3, listOf(Triple(300.0, 6.0, -100), Triple(340.0, 3.0, -100), Triple(900.0, 5.0, -100), Triple(1400.0, 5.0, -100))),
        Case("shallowdips", 1800, 4, listOf(Triple(240.0, 5.0, -100), Triple(500.0, 0.5, -60), Triple(520.0, 0.6, -65), Triple(800.0, 5.0, -100), Triple(1100.0, 5.0, -100), Triple(1400.0, 5.0, -100))),
        Case("edges", 1800, 5, listOf(Triple(0.0, 30.0, -100), Triple(240.0, 5.0, -100), Triple(700.0, 5.0, -100), Triple(1100.0, 5.0, -100), Triple(1770.0, 30.0, -100))),
        Case("tooshort", 100, 6, listOf(Triple(50.0, 5.0, -100))),
        Case("longstretch", 2400, 7, listOf(Triple(300.0, 5.0, -100), Triple(2100.0, 5.0, -100))),
        Case("tie", 1800, 9, listOf(Triple(300.0, 5.0, -100), Triple(340.0, 5.0, -100), Triple(900.0, 5.0, -100), Triple(1400.0, 5.0, -100)), flat = setOf(0, 1)),
        Case("weakstrong", 1800, 10, listOf(Triple(300.0, 10.0, -100), Triple(330.0, 0.6, -50), Triple(900.0, 5.0, -100), Triple(1400.0, 5.0, -100))),
        Case("exact60", 1800, 11, listOf(Triple(57.5, 5.0, -100), Triple(300.0, 5.0, -100), Triple(900.0, 5.0, -100), Triple(1737.5, 5.0, -100))),
        Case("constant", 1800, 12, emptyList()),
        Case("album_min6", 1800, 1, listOf(Triple(240.0, 5.0, -100), Triple(515.0, 5.0, -100), Triple(760.0, 6.0, -100), Triple(1010.0, 4.0, -100), Triple(1300.0, 5.0, -100), Triple(1520.0, 5.0, -100)), minGap = 6.0),
        Case("notsilent", 1800, 8, listOf(Triple(240.0, 10.0, -100), Triple(600.0, 5.0, -85), Triple(900.0, 10.0, -100), Triple(1200.0, 10.0, -100), Triple(1500.0, 10.0, -100), Triple(1700.0, 10.0, -100))),
    )

    private fun curve(c: Case): FloatArray {
        val n = (c.seconds / GapFinder.HOP).toInt()
        var x = c.seed
        fun next(): Long { x = (x * 1103515245L + 12345L) and 0x7fffffffL; return x }
        val db = FloatArray(n) { if (c.name == "constant") { next(); -20f } else -20f + (((next() shr 8) % 7).toInt() - 3) }
        for ((k, g) in c.gaps.withIndex()) {
            val (s, l, d) = g
            val a = Math.round(s / GapFinder.HOP).toInt()
            val z = minOf(n, Math.round((s + l) / GapFinder.HOP).toInt())
            for (i in a until z) { val r = ((next() shr 8) % 3).toInt(); db[i] = (if (k in c.flat) d else d + r).toFloat() }
        }
        return db
    }

    /** name -> list of expected lines, from the resource made by the tool. */
    private fun expected(): Map<String, List<List<String>>> {
        val lines = javaClass.getResourceAsStream("/app/songplayer/core/split/gapfinder-cases.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        return lines.map { it.split("|") }.groupBy { it[0] }
    }

    @Test
    fun GapFinder_ToolCases_SameCutsAndClasses() {
        val exp = expected()
        for (c in cases) {
            val db = curve(c)
            val cuts = GapFinder.findCuts(db, c.minGap)
            val levels = exp.getValue(c.name).single { it[1] == "levels" }
            val got = GapFinder.levels(db)
            assertEquals("${c.name}: floor", levels[2].toDouble(), got.floor, 1e-5)
            assertEquals("${c.name}: median", levels[3].toDouble(), got.median, 1e-5)
            val want = exp.getValue(c.name).filter { it[1] == "cut" }
            assertEquals("${c.name}: number of cuts", want.size, cuts.size)
            for ((cut, w) in cuts.zip(want)) {
                assertEquals("${c.name}: time", w[2].toDouble(), cut.timeSec * 1000, 1.0)
                assertEquals("${c.name}: length", w[3].toDouble(), cut.lengthSec * 1000, 1.0)
                assertEquals("${c.name}: depth", w[4].toDouble(), cut.depthDb, 0.5)
                assertEquals("${c.name}: strength", w[7].toDouble(), cut.strength, 1e-4)
                assertEquals("${c.name}: class", w[5] == "1", cut.confident)
                val reason = when (cut.reason) {
                    null -> ""
                    CutReason.SimilarGapWithinAMinute -> "a similar gap within a minute"
                    CutReason.ShortGap -> "short gap"
                    CutReason.NotFullySilent -> "not fully silent"
                }
                assertEquals("${c.name}: reason", w.getOrElse(6) { "" }, reason)
            }
            val missedWant = exp.getValue(c.name).filter { it[1] == "missed" }
            // The tool measures the stretches between the cuts it will use, which are the confident ones.
            val missed = GapFinder.missedStretches(cuts.filter { it.confident }.map { it.timeSec }, db.size * GapFinder.HOP).filter { cuts.isNotEmpty() }
            assertEquals("${c.name}: missed stretches", missedWant.size, missed.size)
            for ((m, w) in missed.zip(missedWant)) { assertEquals(w[2].toDouble(), m.first * 1000, 1.0); assertEquals(w[3].toDouble(), m.second * 1000, 1.0) }
        }
    }

    @Test
    fun GapFinder_NoGaps_NoCuts() {
        val c = cases.first { it.name == "nogaps" }
        assertEquals(emptyList<Cut>(), GapFinder.findCuts(curve(c)))
        assertEquals(emptyList<Cut>(), GapFinder.findCuts(FloatArray(0)))
        assertEquals(emptyList<Cut>(), GapFinder.findCuts(curve(cases.first { it.name == "constant" }))) // floor equals the median: nothing is below the threshold
    }

    @Test
    fun GapFinder_CloseCandidates_KeepsTheStronger() {
        val cuts = GapFinder.findCuts(curve(cases.first { it.name == "close" }))
        // The 6 s gap at 300 s beats the 3 s gap at 340 s; they are similar, so the kept one is only uncertain.
        assertEquals(3, cuts.size)
        assertEquals(303.0, cuts[0].timeSec, 0.1)
        assertFalse(cuts[0].confident)
        assertEquals(CutReason.SimilarGapWithinAMinute, cuts[0].reason)
    }

    @Test
    fun GapFinder_EdgesAndLongStretch_AreHandled() {
        // Silence within a minute of either end is not a cut, and a file under two minutes has none.
        val edges = GapFinder.findCuts(curve(cases.first { it.name == "edges" }))
        assertTrue(edges.none { it.timeSec < 60 || 1800 - it.timeSec < 60 })
        assertEquals(3, edges.size)
        assertEquals(emptyList<Cut>(), GapFinder.findCuts(curve(cases.first { it.name == "tooshort" })))
        // Two cuts 1,800 s apart in a 2,400 s file: the long stretch between them is flagged.
        val long = GapFinder.findCuts(curve(cases.first { it.name == "longstretch" }))
        val missed = GapFinder.missedStretches(long.filter { it.confident }.map { it.timeSec }, 2400.0)
        assertEquals(1, missed.size)
        assertEquals(long[0].timeSec, missed[0].first, 1e-9)
    }

    @Test
    fun GapFinder_ShallowDip_IsNeverConfident() {
        val cuts = GapFinder.findCuts(curve(cases.first { it.name == "shallowdips" }))
        val dip = cuts.single { it.lengthSec < 1.0 }
        assertFalse(dip.confident)
        // The one that is not fully silent is uncertain too, and the real gaps are confident.
        val notSilent = GapFinder.findCuts(curve(cases.first { it.name == "notsilent" })).single { it.depthDb > -90 }
        assertFalse(notSilent.confident)
        assertEquals(CutReason.NotFullySilent, notSilent.reason)
    }
}
