package app.songplayer.core.split

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitAnalyzerTest {
    private val frameSec = 1152.0 / 44100
    private val total = 600.0
    private val frames = (total / frameSec).toInt()

    /** The true level at time [t]: music with a slow wobble, digital silence in 2.5 s gaps, and one quiet (not silent) passage. */
    private fun trueDb(t: Double): Double = when {
        t in 98.75..101.25 || t in 198.75..201.25 || t in 298.75..301.25 || t in 398.75..401.25 || t in 498.75..501.25 -> -90.0
        t in 248.0..251.0 -> -42.0                                 // quiet music: a dip, not a gap
        else -> -20.0 + 4 * sin(t / 3.0)
    }

    /** Bits of main data follow the level: loud frames use many, silent ones almost none (the real relation is rough, hence the noise). */
    private fun bits(t: Double): Int = (30 + 40 * Math.pow(10.0, (trueDb(t) + 20) / 20) * 70 + 20 * sin(t * 40)).toInt().coerceAtLeast(0)

    private fun index(bitsOf: (Double) -> Int = ::bits) = Mp3Index(
        offset = IntArray(frames) { it * 417 }, length = ShortArray(frames) { 417 }, layout = ByteArray(frames) { 0x70 },
        mainDataBegin = ShortArray(frames), mainDataBits = ShortArray(frames) { bitsOf(it * frameSec).coerceAtMost(30000).toShort() },
        bitrateIndex = ByteArray(frames) { 9 }, first = 0, junk = 0,
    )

    private class Fake(val db: (Double) -> Double) : LoudnessSource {
        var decoded = 0.0
        var calls = 0
        override fun decodeDb(startSec: Double, steps: Int): FloatArray {
            decoded += steps * GapFinder.HOP; calls++
            return FloatArray(steps) { db(startSec + it * GapFinder.HOP).toFloat() }
        }
    }

    private val ideal = GapFinder.findCuts(FloatArray((total / GapFinder.HOP).toInt()) { trueDb(it * GapFinder.HOP).toFloat() })

    @Test
    fun SplitAnalyzer_FindsTheSameCutsAsDecodingEverything_WithAFractionOfTheDecoding() {
        val source = Fake(::trueDb)
        val result = SplitAnalyzer.analyze(index(), source)
        assertTrue(result.proxyUsable)
        assertEquals(5, ideal.size)
        assertEquals(ideal.size, result.cuts.size)
        for ((a, b) in ideal.zip(result.cuts)) {
            assertTrue("${a.timeSec} vs ${b.timeSec}", abs(a.timeSec - b.timeSec) < 0.2)
            assertEquals(a.confident, b.confident)
        }
        assertTrue("decoded ${source.decoded} s of $total", source.decoded < total / 4)
        assertEquals(source.decoded, result.decodedSec, 1e-6)
    }

    @Test
    fun SplitAnalyzer_AProxyThatDoesNotFollowTheLoudness_DecodesTheWholeFileAndStillFindsTheCuts() {
        val source = Fake(::trueDb)
        val result = SplitAnalyzer.analyze(index { 500 }, source)          // every frame the same size: no information
        assertFalse(result.proxyUsable)
        assertTrue("${'$'}{source.decoded}", source.decoded >= total - 1 && source.decoded < total + 15)   // the file, and the sample windows before it
        assertEquals(ideal.map { it.timeSec.toInt() }, result.cuts.map { it.timeSec.toInt() })
    }

    @Test
    fun SplitAnalyzer_ProgressNeverGoesBackAndEndsAtOne_AndCancelStopsAtOnce() {
        val seen = ArrayList<Double>()
        SplitAnalyzer.analyze(index(), Fake(::trueDb), progress = { seen += it })
        assertEquals(1.0, seen.last(), 0.0)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(seen.all { it in 0.0..1.0 })

        val source = Fake(::trueDb)
        try { SplitAnalyzer.analyze(index(), source, cancelled = { source.calls >= 3 }); fail() } catch (_: CancellationException) {}
        assertEquals(3, source.calls)
    }

    @Test
    fun SplitAnalyzer_AFileUnderTwoMinimumSongs_HasNoCutsAndDecodesNothing() {
        val short = Mp3Index(
            IntArray(2000) { it * 417 }, ShortArray(2000) { 417 }, ByteArray(2000) { 0x70 }, ShortArray(2000), ShortArray(2000) { 100 },
            ByteArray(2000) { 9 }, 0, 0,
        )                                                                  // about 52 s
        val source = Fake(::trueDb)
        val result = SplitAnalyzer.analyze(short, source)
        assertTrue(result.cuts.isEmpty())
        assertEquals(0, source.calls)
    }
}

class SplitAnalyzerGridTest {
    @Test
    fun SplitAnalyzer_EveryDecodedStretch_StartsOnTheGrid_AndIsPlacedWhereItWasAsked() {
        // A fake that returns the step number as its value shows where each stretch was put: a value from step k must land at step k.
        val frameSec = 1152.0 / 44100
        val frames = (600.0 / frameSec).toInt()
        val bits = { t: Double -> if (t in 98.75..101.25 || t in 298.75..301.25) 0 else 3000 }
        val index = Mp3Index(
            IntArray(frames) { it * 417 }, ShortArray(frames) { 417 }, ByteArray(frames) { 0x70 }, ShortArray(frames),
            ShortArray(frames) { bits(it * frameSec).toShort() }, ByteArray(frames) { 9 }, 0, 0,
        )
        val asked = ArrayList<Pair<Double, Int>>()
        val source = object : LoudnessSource {
            override fun decodeDb(startSec: Double, steps: Int): FloatArray {
                asked += startSec to steps
                return FloatArray(steps) { if ((startSec + it * GapFinder.HOP) in 98.75..101.25 || (startSec + it * GapFinder.HOP) in 298.75..301.25) -90f else -20f }
            }
        }
        val result = SplitAnalyzer.analyze(index, source)
        for ((start, _) in asked) assertEquals("start $start is on the grid", 0.0, abs(start / GapFinder.HOP - Math.round(start / GapFinder.HOP)), 1e-6)
        assertEquals(2, result.cuts.size)
        assertTrue(abs(result.cuts[0].timeSec - 100.0) < 0.1 && abs(result.cuts[1].timeSec - 300.0) < 0.1)
        // Lengths such as 29 steps (0.58 s) must not come back one step short: asked counts are whole steps, never rounded down.
        assertTrue(asked.all { it.second > 0 })
    }
}
