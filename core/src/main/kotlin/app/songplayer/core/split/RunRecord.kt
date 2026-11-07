package app.songplayer.core.split

import app.songplayer.core.details.Json

/** Which file a run is about, so a continued run can tell that it is still the same file (a media id, the size, the time, a hash of the first 64 KB). */
data class SourceIdentity(val mediaId: Long, val size: Long, val modifiedSec: Long, val headHash: String)

/** One cut as the run keeps it: the user's changes included. */
data class RunCut(val timeSec: Double, val halfGapSec: Double?, val confident: Boolean, val checked: Boolean)

/** A song that is complete in the media index: its number, its file name, its media id and size. */
data class RunSong(val number: Int, val name: String, val mediaId: Long, val size: Long)

enum class RunPhase { Reviewing, Writing, Done }

/**
 * Everything a split run needs to be continued, undone or finished after the app was killed (Technical Design 5.3): the source, the cuts, where
 * the songs go and which are written. It holds no audio and no tags or titles; the file names are the user's own and never go to a log.
 */
data class RunRecord(
    val id: String,
    val source: SourceIdentity,
    val sourceName: String,
    val cuts: List<RunCut>,
    /** The folder of the new songs, relative to the volume (for example `Music/Albums/`), and the volume's name. */
    val folder: String,
    val volume: String,
    val phase: RunPhase,
    val written: List<RunSong>,
    val createdSec: Long,
) {
    init {
        // A record that could not be read back would lose track of written songs without a word: refuse to build one.
        require(cuts.all { it.timeSec.isFinite() && (it.halfGapSec == null || it.halfGapSec.isFinite() && it.halfGapSec >= 0) }) { "cuts must be finite" }
        require(written.all { it.number in 1..cuts.size + 1 } && written.map { it.number }.toSet().size == written.size) { "written songs must be numbered 1 to the song count, once each" }
    }

    val songCount: Int get() = cuts.size + 1

    /** Counts only: the record holds file names, which never go into a log or a message. */
    override fun toString() = "RunRecord(phase=$phase, songs=$songCount, written=${written.size})"

    fun toJson(): String = buildString {
        append("{\"v\":$VERSION,\"id\":${q(id)},\"src\":{\"m\":${source.mediaId},\"s\":${source.size},\"t\":${source.modifiedSec},\"h\":${q(source.headHash)}},")
        append("\"name\":${q(sourceName)},\"folder\":${q(folder)},\"volume\":${q(volume)},\"phase\":${q(phase.name)},\"at\":$createdSec,")
        append("\"cuts\":[")
        append(cuts.joinToString(",") { "[${it.timeSec},${it.halfGapSec ?: -1.0},${if (it.confident) 1 else 0},${if (it.checked) 1 else 0}]" })
        append("],\"written\":[")
        append(written.joinToString(",") { "[${it.number},${q(it.name)},${it.mediaId},${it.size}]" })
        append("]}")
    }

    companion object {
        const val VERSION = 1

        /** The record in [text], or null when it is damaged or of another version (treated as "no record", never a crash). */
        fun fromJson(text: String): RunRecord? = try {
            val m = Json.parse(text) as Map<*, *>
            if ((m["v"] as Double) != VERSION.toDouble()) null else {
                val src = m["src"] as Map<*, *>
                val record = RunRecord(
                    id = m["id"] as String,
                    source = SourceIdentity((src["m"] as Double).toLong(), (src["s"] as Double).toLong(), (src["t"] as Double).toLong(), src["h"] as String),
                    sourceName = m["name"] as String,
                    cuts = (m["cuts"] as List<*>).map { c -> (c as List<*>).let { RunCut(it[0] as Double, (it[1] as Double).takeIf { h -> h >= 0 }, it[2] == 1.0, it[3] == 1.0) } },
                    folder = m["folder"] as String,
                    volume = m["volume"] as String,
                    phase = RunPhase.valueOf(m["phase"] as String),
                    written = (m["written"] as List<*>).map { s -> (s as List<*>).let { RunSong((it[0] as Double).toInt(), it[1] as String, (it[2] as Double).toLong(), (it[3] as Double).toLong()) } },
                    createdSec = (m["at"] as Double).toLong(),
                )
                // Cuts must run forward, as the plan keeps them.
                if (record.cuts.zipWithNext().all { (a, b) -> a.timeSec < b.timeSec }) record else null
            }
        } catch (_: RuntimeException) {
            null // includes IllegalArgumentException from the checks above: a record that breaks them is no record
        }

        private fun q(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}

/**
 * Files in the app's private storage, written so that a reader never sees half a file (the app writes a temporary file and renames it).
 * [names] lists only finished files, never a temporary one.
 */
interface AtomicFiles {
    fun writeAtomic(name: String, bytes: ByteArray)
    fun read(name: String): ByteArray?
    fun delete(name: String)
    fun names(): List<String>
}

/**
 * The run records on [files], one `<id>.json` each. One writer at a time: the job owns a record once it starts writing, and the screens do
 * not save over it (a unique work per source file, Technical Design 5.1).
 */
class RunStore(private val files: AtomicFiles) {
    fun save(record: RunRecord) = files.writeAtomic("${record.id}.json", record.toJson().toByteArray(Charsets.UTF_8))

    fun load(id: String): RunRecord? =
        try { files.read("$id.json")?.let { RunRecord.fromJson(String(it, Charsets.UTF_8)) }?.takeIf { it.id == id } } catch (_: java.io.IOException) { null }

    /** The newest record whose source has this media id (ties go to the larger id), or null. A damaged file is skipped. */
    fun latestFor(sourceMediaId: Long): RunRecord? =
        files.names().filter { it.endsWith(".json") }.mapNotNull { load(it.removeSuffix(".json")) }
            .filter { it.source.mediaId == sourceMediaId }.maxWithOrNull(compareBy<RunRecord> { it.createdSec }.thenBy { it.id })

    /** Every readable record, newest first. */
    fun all(): List<RunRecord> =
        files.names().filter { it.endsWith(".json") }.mapNotNull { load(it.removeSuffix(".json")) }.sortedWith(compareByDescending<RunRecord> { it.createdSec }.thenByDescending { it.id })

    fun delete(id: String) = files.delete("$id.json")

    /**
     * Deletes the records older than [KEEP_DAYS] days; returns how many went. A record that cannot be read (damaged, or of a newer version) is
     * left alone: it may be the only reference to songs already written, and the app cannot tell how old it is.
     */
    fun expire(nowSec: Long): Int {
        var gone = 0
        for (n in files.names().filter { it.endsWith(".json") }) {
            val r = load(n.removeSuffix(".json")) ?: continue
            if (nowSec - r.createdSec > KEEP_DAYS * 86_400L) { files.delete(n); gone++ }
        }
        return gone
    }

    companion object { const val KEEP_DAYS = 30 }
}

/** What to do when the user opens "Split into songs" on a file that may have an earlier run. */
sealed interface Recovery {
    /** No unfinished run: start a new split. */
    data object NothingToContinue : Recovery

    /** An earlier run of this same file stopped: [written] of [total] songs exist; "Continue" writes [remaining] (song numbers) only. */
    data class Continue(val written: Int, val total: Int, val remaining: List<Int>) : Recovery

    /** The file is not the one the earlier run was about any more: its songs and cuts cannot be reused. */
    data object SourceChanged : Recovery
}

object SplitRecovery {
    /**
     * [record] is the earlier run for this file (or null); [current] is the file as it is now; [existing] maps the media ids that still
     * exist in the media index to their sizes, so a song the user deleted or replaced meanwhile counts as not written. A song that was
     * finished just before the record could be saved is not in the record: the writer's name-and-size check (R-9) skips it when it is written again.
     */
    fun decide(record: RunRecord?, current: SourceIdentity, existing: Map<Long, Long>): Recovery {
        if (record == null || record.phase == RunPhase.Done) return Recovery.NothingToContinue
        if (record.source != current) return Recovery.SourceChanged
        val done = record.written.filter { existing[it.mediaId] == it.size }.map { it.number }.toSet()
        val remaining = (1..record.songCount).filter { it !in done }
        return Recovery.Continue(done.size, record.songCount, remaining)
    }

    /** The media ids "Remove these songs" would delete: the record's songs that still exist (a song the user deleted already is skipped). */
    fun songsToRemove(record: RunRecord, existingMediaIds: Set<Long>): List<Long> = record.written.map { it.mediaId }.filter { it in existingMediaIds }
}
