package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitJobTest {
    private val frameSec = 1152.0 / 44100
    private val frames = (200.0 / frameSec).toInt()

    private fun silent(t: Double) = t in 98.75..101.25

    /** 200 s of 128 kbps frames with a 2.5 s digital silence at 100 s: loud frames use many bits, silent ones none. */
    private val bytes = Mp3Shapes.repeat(frames) {
        val t = it * frameSec
        Mp3Shapes.frame(mainDataBegin = if (silent(t) || it % 40 == 0) 0 else 100, part2_3 = if (silent(t)) listOf(0, 0, 0, 0) else listOf(300 + it % 50, 320, 310, 305))
    }

    private val loudness = object : LoudnessSource {
        override fun decodeDb(startSec: Double, steps: Int) =
            FloatArray(steps) { if (silent(startSec + it * GapFinder.HOP)) -90f else -20f + (it % 7) }
    }

    private val identity = SourceIdentity(5, bytes.size.toLong(), 1000, "h")

    private fun analyze(job: SplitJob, store: RunStore, progress: (SplitProgress) -> Unit = {}, cancelled: () -> Boolean = { false }) =
        job.analyze(ByteArraySource(bytes), identity, "big.mp3", "Music/", "external_primary", { loudness }, "r1", 10, progress, cancelled)

    @Test
    fun SplitJob_Phases_ProgressAndCancel() {
        val files = Files()
        val store = RunStore(files)
        val job = SplitJob(store)
        val seen = ArrayList<SplitProgress>()
        val run = analyze(job, store, { seen += it })
        assertEquals(listOf(SplitPhase.Scanning, SplitPhase.Analyzing), seen.map { it.phase }.distinct())
        assertEquals(1.0, seen.last().fraction, 0.0)
        assertEquals(1, run.record.cuts.size)
        assertTrue(abs(run.record.cuts[0].timeSec - 100.0) < 0.3)
        assertTrue(run.record.cuts[0].confident)
        assertEquals(RunPhase.Reviewing, run.record.phase)
        assertEquals(run.record, store.load("r1"))                // the review screens read the cuts from the saved record

        val sink = FakeSink()
        val written = ArrayList<SplitProgress>()
        val result = job.write(run.record, ByteArraySource(bytes), SplitWriter(sink, store), { written += it })
        assertTrue(result.finished)
        assertEquals(listOf("big 01.mp3", "big 02.mp3"), sink.visible.keys.toList())
        assertEquals(SplitPhase.Writing, written.last().phase)
        assertEquals(2, written.last().songsWritten)
        assertEquals(1.0, written.last().fraction, 0.0)
        assertTrue(written.map { it.fraction }.zipWithNext().all { (a, b) -> b >= a })

        // Cancelled while analysing: nothing is saved. Cancelled while writing: the finished song stays and the record says so.
        val other = RunStore(Files())
        try { analyze(SplitJob(other), other, cancelled = { true }); fail() } catch (_: CancellationException) {}
        assertTrue(other.latestFor(5) == null)
        val sink2 = FakeSink()
        val store2 = RunStore(Files())
        val cancelledRun = SplitJob(store2).write(run.record, ByteArraySource(bytes), SplitWriter(sink2, store2), cancelled = { sink2.visible.size >= 1 })
        assertEquals(false, cancelledRun.finished)
        assertEquals(1, cancelledRun.record.written.size)
    }

    @Test
    fun SplitJob_Continue_SkipsFinishedSongs() {
        val store = RunStore(Files())
        val job = SplitJob(store)
        val run = analyze(job, store)
        val sink = FakeSink().apply { killAtSong = "big 02.mp3" }
        try { job.write(run.record, ByteArraySource(bytes), SplitWriter(sink, store)); fail() } catch (_: java.io.IOException) {}
        val after = store.load("r1")!!
        assertEquals(listOf(1), after.written.map { it.number })
        assertEquals(Recovery.Continue(1, 2, listOf(2)), SplitRecovery.decide(after, identity, mapOf(after.written[0].mediaId to after.written[0].size)))

        sink.killAtSong = null
        sink.writes = 0
        val result = job.write(after, ByteArraySource(bytes), SplitWriter(sink, store))
        assertEquals(1, sink.writes)                              // only song 2
        assertTrue(result.finished)
        assertEquals(2, sink.visible.size)
    }

    @Test
    fun SplitJob_TheFileChangedSinceTheRecord_IsSourceChanged_AndNothingIsWritten() {
        val store = RunStore(Files())
        val run = analyze(SplitJob(store), store)
        val sink = FakeSink()
        try { SplitJob(store).write(run.record, ByteArraySource(bytes.copyOf(bytes.size - 417)), SplitWriter(sink, store)); fail() } catch (e: SplitError.SourceChanged) {}
        assertEquals(0, sink.writes)
    }
}
