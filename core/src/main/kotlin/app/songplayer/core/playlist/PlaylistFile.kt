package app.songplayer.core.playlist

import app.songplayer.core.details.Json
import app.songplayer.core.errors.PlaylistFileError
import app.songplayer.core.library.SongKey

/** A playlist as the export file holds it: the name, when it was made, and its songs in order (the entries' row ids are not kept). */
data class FilePlaylist(val name: String, val createdAtSec: Long, val entries: List<PlaylistEntry>)

/**
 * What the song-details lookup decided about one song (applied, held for review, not found, rejected, manual), so that a reinstall does not
 * make the lookup start over on every song (R-26). [candidates] is the stored text of a held song's candidates, otherwise null.
 */
data class FileDecision(val songKey: String, val status: String, val checkedAtSec: Long, val candidates: String?)

/** The export file's content: the playlists and the lookup's decisions. */
data class FileContent(val playlists: List<FilePlaylist>, val decisions: List<FileDecision>)

/** The lookup's decisions as the export and import see them (`app.db`). */
interface DecisionStore {
    /** Every decision except `pending`. */
    fun exportDecisions(): List<FileDecision>

    /** Adds the decisions of songs that have none yet; an existing decision is never changed. Returns how many were added. */
    fun importDecisions(incoming: List<FileDecision>): Int
}

/**
 * The playlist export file (R-26, Technical Design 5.7): one JSON text with a format name and a version, then each playlist with its songs'
 * keys, file names, sizes and lengths. The same fields the app keeps, so an import on another phone finds the songs again with [Relinker].
 * [read] refuses anything that is not exactly this format, and changes nothing because it only returns a value.
 */
object PlaylistFile {
    const val FORMAT = "music-player-playlists"
    const val VERSION = 1

    /** A file bigger than this is refused unread; 5,000 songs in 100 playlists come to a few MB. */
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_PLAYLISTS = 2_000
    private const val MAX_ENTRIES = 50_000
    private const val MAX_DECISIONS = 100_000
    private const val MAX_CANDIDATES_CHARS = 10_000
    private const val MAX_KEY_CHARS = 1_000
    private val STATUSES = setOf("applied", "held", "notFound", "rejected", "manual")

    fun write(playlists: List<FilePlaylist>, decisions: List<FileDecision> = emptyList()): String = buildString {
        append("{\"format\":").append(q(FORMAT)).append(",\"version\":").append(VERSION).append(",\"playlists\":[")
        playlists.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"name\":").append(q(p.name)).append(",\"created\":").append(p.createdAtSec).append(",\"songs\":[")
            p.entries.forEachIndexed { j, e ->
                if (j > 0) append(',')
                append("{\"key\":").append(q(e.songKey.value)).append(",\"file\":").append(q(e.fileName))
                    .append(",\"size\":").append(e.sizeBytes).append(",\"ms\":").append(e.durationMs).append('}')
            }
            append("]}")
        }
        append("],\"details\":[")
        decisions.forEachIndexed { i, d ->
            if (i > 0) append(',')
            append("{\"key\":").append(q(d.songKey)).append(",\"status\":").append(q(d.status)).append(",\"at\":").append(d.checkedAtSec)
            if (d.candidates != null) append(",\"cand\":").append(q(d.candidates))
            append('}')
        }
        append("]}")
    }

    /**
     * The playlists and decisions in [text], or [PlaylistFileError.Invalid]. A song listed twice in one playlist is kept once (R-10). The
     * file must hold at least one playlist or one decision; a file made before the decisions were added has no `details` and is still read.
     */
    fun read(text: String): FileContent {
        val root = try { Json.parse(text) } catch (_: IllegalArgumentException) { throw PlaylistFileError.Invalid() }
        val obj = root as? Map<*, *> ?: throw PlaylistFileError.Invalid()
        if (obj["format"] != FORMAT || (obj["version"] as? Double) != VERSION.toDouble()) throw PlaylistFileError.Invalid()
        val lists = obj["playlists"] as? List<*> ?: throw PlaylistFileError.Invalid()
        if (lists.size > MAX_PLAYLISTS) throw PlaylistFileError.Invalid()
        val rawDecisions = when (val d = obj["details"]) { null -> emptyList<Any?>(); is List<*> -> d; else -> throw PlaylistFileError.Invalid() }
        if (rawDecisions.size > MAX_DECISIONS || (lists.isEmpty() && rawDecisions.isEmpty())) throw PlaylistFileError.Invalid()
        var total = 0
        val playlists = lists.map { raw ->
            val p = raw as? Map<*, *> ?: throw PlaylistFileError.Invalid()
            val name = (p["name"] as? String)?.let(PlaylistRules::cleanName) ?: throw PlaylistFileError.Invalid()
            val created = whole(p["created"])
            val songs = p["songs"] as? List<*> ?: throw PlaylistFileError.Invalid()
            total += songs.size
            if (total > MAX_ENTRIES) throw PlaylistFileError.Invalid()
            val seen = HashSet<String>()
            val entries = songs.mapNotNull { s ->
                val m = s as? Map<*, *> ?: throw PlaylistFileError.Invalid()
                val key = (m["key"] as? String)?.takeIf { it.isNotEmpty() } ?: throw PlaylistFileError.Invalid()
                val file = (m["file"] as? String)?.takeIf { it.isNotEmpty() } ?: throw PlaylistFileError.Invalid()
                val entry = PlaylistEntry(0, SongKey(key), file, whole(m["size"]), whole(m["ms"]))
                if (seen.add(key)) entry else null
            }
            FilePlaylist(name, created, entries)
        }
        val seenKeys = HashSet<String>()
        val decisions = rawDecisions.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: throw PlaylistFileError.Invalid()
            val key = (m["key"] as? String)?.takeIf { it.isNotEmpty() } ?: throw PlaylistFileError.Invalid()
            val status = (m["status"] as? String)?.takeIf { it in STATUSES } ?: throw PlaylistFileError.Invalid()
            val candidates = when (val c = m["cand"]) { null -> null; is String -> c.takeIf { it.length <= MAX_CANDIDATES_CHARS } ?: throw PlaylistFileError.Invalid(); else -> throw PlaylistFileError.Invalid() }
            // A held song is kept for review, so it must carry its candidates; no other status may.
            if ((status == "held") != (candidates != null)) throw PlaylistFileError.Invalid()
            if (key.length > MAX_KEY_CHARS) throw PlaylistFileError.Invalid()
            if (seenKeys.add(key)) FileDecision(key, status, whole(m["at"]), candidates) else null
        }
        return FileContent(playlists, decisions)
    }

    /**
     * The playlists of the file that are not already there: one with the same name and the same songs in the same order as an existing
     * playlist is left out, so importing the same file twice adds nothing the second time (import never replaces or deletes).
     */
    fun newOnes(existing: List<FilePlaylist>, incoming: List<FilePlaylist>): List<FilePlaylist> {
        val have = existing.mapTo(HashSet()) { it.name to it.entries.map { e -> e.songKey } }
        return incoming.filter { have.add(it.name to it.entries.map { e -> e.songKey }) }
    }

    private fun whole(v: Any?): Long {
        val d = v as? Double ?: throw PlaylistFileError.Invalid()
        if (d < 0 || d != Math.floor(d) || d > 9.0e15) throw PlaylistFileError.Invalid()
        return d.toLong()
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
