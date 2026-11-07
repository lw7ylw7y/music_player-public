package app.songplayer.core.split

import app.songplayer.core.errors.SplitError

enum class SplitPhase { Scanning, Analyzing, Writing }

/** Where a job is: the phase, how far through it (0.0 to 1.0) and, when writing, how many of the songs are written. */
data class SplitProgress(
    val phase: SplitPhase,
    val fraction: Double,
    val songsWritten: Int = 0,
    val songCount: Int = 0,
    /** While analysing: the stage ("windows", "fit", "regions", "all"), steps done of total, and seconds decoded so far (numbers for the debug screen). */
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val decodedSec: Double = 0.0,
)

/** The result of the analysis phase: the record saved for the review, the analysis, and what it cost in milliseconds (P-B). */
class AnalysisRun(val record: RunRecord, val analysis: Analysis, val scanMs: Long, val analyzeMs: Long)

/**
 * The two phases of a split run as the background worker drives them (Technical Design 5.1): analysis, which ends with a run record in the
 * phase Reviewing (the cuts, for the review screens), and writing, which starts from a record and can be run again after a kill. Pure
 * Kotlin over interfaces: the worker supplies the real file, the decoder and the media index; the tests supply fakes.
 */
class SplitJob(
    private val store: RunStore,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /**
     * Reads the frames of [source] and finds the cuts. [identity] says which file this is. [loudnessFor] makes the decoder of true loudness from
     * the frame index (the app decodes from the frames the index names, so no seek is needed). Saves the record (Reviewing) and returns it.
     * Throws [SplitError.Unreadable] and [SplitError.TooLarge] from the scan, and a [java.util.concurrent.CancellationException] when
     * [cancelled] turns true (nothing is saved then).
     */
    fun analyze(
        source: ByteSource,
        identity: SourceIdentity,
        sourceName: String,
        folder: String,
        volume: String,
        loudnessFor: (Mp3Index) -> LoudnessSource,
        runId: String,
        nowSec: Long,
        progress: (SplitProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): AnalysisRun {
        progress(SplitProgress(SplitPhase.Scanning, 0.0))
        val t0 = nanoTime()
        val index = Mp3Frames.scan(source)
        if (cancelled()) throw java.util.concurrent.CancellationException()
        val t1 = nanoTime()
        progress(SplitProgress(SplitPhase.Analyzing, 0.0))
        var fraction = 0.0
        val analysis = SplitAnalyzer.analyze(index, loudnessFor(index), { fraction = it }, cancelled) { stage, done, total, decoded ->
            progress(SplitProgress(SplitPhase.Analyzing, fraction, stage = stage, done = done, total = total, decodedSec = decoded))
        }
        progress(SplitProgress(SplitPhase.Analyzing, 1.0))
        val t2 = nanoTime()
        val cuts = analysis.cuts.map { RunCut(it.timeSec, it.lengthSec / 2, it.confident, false) }
        val record = RunRecord(runId, identity, sourceName, cuts, folder, volume, RunPhase.Reviewing, emptyList(), nowSec)
        store.save(record)
        return AnalysisRun(record, analysis, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
    }

    /**
     * Writes the songs of [record] (new, or loaded after a kill) with [writer]. The file is read again for its frames, and it must be the
     * file the record is about ([SplitError.SourceChanged] when its size differs; the caller has compared the rest of the identity).
     */
    fun write(
        record: RunRecord,
        source: ByteSource,
        writer: SplitWriter,
        progress: (SplitProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): WriteResult {
        if (source.length != record.source.size) throw SplitError.SourceChanged()
        progress(SplitProgress(SplitPhase.Scanning, 0.0))
        val index = Mp3Frames.scan(source)
        val plan = SplitPlan(index.startTimes()[index.count], record.cuts.map { PlanCut(it.timeSec, it.halfGapSec, it.confident, it.checked) })
        val spans = Mp3Cutter.spans(index, plan)
        val count = record.songCount
        progress(SplitProgress(SplitPhase.Writing, 0.0, record.written.size, count))
        return writer.run(record, spans, source, cancelled, onSong = { done -> progress(SplitProgress(SplitPhase.Writing, done.toDouble() / count, done, count)) })
    }
}
