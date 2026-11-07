package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import java.io.OutputStream

/** A song that is complete and visible in the media index. */
data class FinishedSong(val mediaId: Long, val size: Long)

/**
 * Where the new songs are made: the media index of the phone (the app's `SongWriter`) or, in tests, a fake. One folder, set when the sink is
 * built. Android's own automatic renaming must never happen, so the writer asks [find] before every insert.
 */
interface SongSink {
    /** Free bytes on the volume of the folder. */
    fun freeBytes(): Long

    /** The finished (visible) file called [name] in the folder, or null. */
    fun find(name: String): FinishedSong?

    /** Removes unfinished (pending) files of an earlier run called one of [names] in the folder; returns how many went. */
    fun removePending(names: List<String>): Int

    /**
     * Makes the file [name]: [header] (if any) and the bytes [start] until [end] of [source], and publishes it only when it is complete and
     * its size is right. Returns null, with nothing left behind, when [cancelled] became true. A failure leaves nothing visible and removes the
     * unfinished file; running out of space is a [SplitError.NoSpace], an unreadable original a [SplitError.SourceChanged].
     */
    fun write(name: String, header: ByteArray?, source: ByteSource, start: Long, end: Long, cancelled: () -> Boolean): FinishedSong?
}

/** Copies one song's bytes (an optional header, then a range of the original) to [out]. */
object SongCopy {
    private const val CHUNK = 256 * 1024

    /** Returns false when [cancelled] stopped it; throws [SplitError.SourceChanged] when the original ends before [end] or cannot be read. */
    fun copy(source: ByteSource, start: Long, end: Long, header: ByteArray?, out: OutputStream, cancelled: () -> Boolean): Boolean {
        header?.let(out::write)
        val buffer = ByteArray(CHUNK)
        var at = start
        while (at < end) {
            if (cancelled()) return false
            val n = try { source.read(at, buffer, 0, minOf(CHUNK.toLong(), end - at).toInt()) } catch (_: java.io.IOException) { throw SplitError.SourceChanged() }
            if (n <= 0) throw SplitError.SourceChanged()
            out.write(buffer, 0, n)
            at += n
        }
        return true
    }
}

/** How a write run ended: the record as saved, and whether every song is written (false when cancelled). */
class WriteResult(val record: RunRecord, val finished: Boolean)

/**
 * Writes the songs of a run one at a time, in order, and saves the run record after each (Technical Design 5.2 and 5.3). A kill or a failure leaves
 * complete songs and at most one unfinished file, which the next run removes; the record is never ahead of the files.
 */
class SplitWriter(private val sink: SongSink, private val store: RunStore) {
    /**
     * Writes the songs of [record] that are not in the media index yet. A file with a song's name and size already there counts as written
     * (also a song finished just before the record could be saved); a file with that name and another size stops the run before anything is
     * written ([SplitError.NameInUse]); too little free space refuses to start ([SplitError.NoSpace]).
     */
    fun run(
        record: RunRecord,
        spans: List<SongSpan>,
        source: ByteSource,
        cancelled: () -> Boolean = { false },
        onSong: (written: Int) -> Unit = {},
    ): WriteResult {
        require(spans.size == record.songCount) { "one span per song" }
        val names = SongNames.names(record.sourceName, record.songCount)
        sink.removePending(names)

        // Which songs are already there (and a clash is found before any byte is written).
        val adopted = HashMap<Int, FinishedSong>()
        for (n in 1..record.songCount) {
            val found = sink.find(names[n - 1]) ?: continue
            if (found.size == spans[n - 1].bytes) adopted[n] = found else throw SplitError.NameInUse(names[n - 1])
        }
        val todo = (1..record.songCount).filter { it !in adopted }
        if (todo.isNotEmpty() && sink.freeBytes() < todo.sumOf { spans[it - 1].bytes } + SPACE_MARGIN_BYTES) throw SplitError.NoSpace()

        var current = record.copy(
            phase = RunPhase.Writing,
            written = adopted.map { (n, f) -> RunSong(n, names[n - 1], f.mediaId, f.size) }.sortedBy { it.number },
        )
        store.save(current)
        for (n in todo) {
            if (cancelled()) return WriteResult(current, false)
            val span = spans[n - 1]
            // Checked again right before the insert: a file made meanwhile must not make Android rename ours.
            sink.find(names[n - 1])?.let { throw SplitError.NameInUse(names[n - 1]) }
            val done = sink.write(names[n - 1], span.xingHeader, source, span.byteStart, span.byteEnd, cancelled) ?: return WriteResult(current, false)
            current = current.copy(written = (current.written + RunSong(n, names[n - 1], done.mediaId, done.size)).sortedBy { it.number })
            store.save(current)
            onSong(current.written.size)
        }
        current = current.copy(phase = RunPhase.Done)
        store.save(current)
        return WriteResult(current, true)
    }

    companion object {
        /** Room left over on top of the songs' own size (provisional; tuned on the phone in X6). */
        const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    }
}
