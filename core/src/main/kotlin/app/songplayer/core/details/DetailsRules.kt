package app.songplayer.core.details

import app.songplayer.core.library.Song

enum class DetailField { Title, Artist, Album }

/** Who made a change: the lookup (Auto), the user in Review (Review), by hand (Manual), or an Undo (the inverse of an earlier change). */
enum class ChangeSource { Auto, Review, Manual, Undo }

/**
 * Where a change is on its way to the song's file (the order is what the database stores): Waiting for access or its turn; Confirmed (the
 * library shows the new value, so the overlay ends); Failed (a write was tried and undone); InFile (the file has it, the library has not shown it yet,
 * so the overlay stays).
 */
enum class WriteState { Waiting, Confirmed, Failed, InFile }

/**
 * One changed field of one song (Technical Design 5.8). [previous] and [new] are null for "no value". Changes saved together
 * share a [batch], which is what the user sees and undoes as one.
 */
data class DetailChange(
    val id: Long,
    val batch: Long,
    val songKey: String,
    val field: DetailField,
    val previous: String?,
    val new: String?,
    val source: ChangeSource,
    val atSec: Long,
    val write: WriteState,
    val undone: Boolean,
)

/** A field that an edit changes: from [previous] to [new]. */
data class FieldDiff(val field: DetailField, val previous: String?, val new: String?)

sealed interface UndoPlan {
    /** A later change to one of these fields is still live: it has to be undone first, or the older text would replace it. */
    data object LaterChange : UndoPlan

    /** The file's value is no longer what the change wrote (another app changed it): nothing is undone. */
    data object FileChanged : UndoPlan

    /** Put [inverse] in place of the changes in [undoneIds]; [writesFile] is false when none of them had reached the file. */
    data class Apply(val inverse: List<FieldDiff>, val undoneIds: List<Long>, val writesFile: Boolean) : UndoPlan
}

/** One row of "Recently filled in": all the fields saved together, newest first. */
data class RecentItem(val batch: Long, val songKey: String, val source: ChangeSource, val atSec: Long, val changes: List<DetailChange>)

/** The rules of the details store, without Android (Technical Design 5.8; Stories H3). */
object DetailsRules {
    /** How long a change stays in "Recently filled in" (provisional; the user may set it, Requirements section 15). */
    const val UNDO_KEEP_DAYS = 30

    private fun Song.value(field: DetailField): String? = when (field) {
        DetailField.Title -> title
        DetailField.Artist -> artist
        DetailField.Album -> album
    }

    private fun Song.with(field: DetailField, v: String?): Song = when (field) {
        DetailField.Title -> copy(title = v)
        DetailField.Artist -> copy(artist = v)
        DetailField.Album -> copy(album = v)
    }

    /** An edit's text as stored: trimmed, and a blank field means "no value". */
    fun clean(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /** What changes between [current] and [edited] (both cleaned): only the fields that differ. */
    fun diff(current: TagFields, edited: TagFields): List<FieldDiff> = buildList {
        val pairs = listOf(
            Triple(DetailField.Title, current.title, edited.title),
            Triple(DetailField.Artist, current.artist, edited.artist),
            Triple(DetailField.Album, current.album, edited.album),
        )
        for ((f, a, b) in pairs) if (clean(a) != clean(b)) add(FieldDiff(f, clean(a), clean(b)))
    }

    /** The song as the screens show it: the file's tags with the newest change of each field that the file does not have yet. */
    fun overlay(song: Song, changes: List<DetailChange>): Song {
        var out = song
        val newestFirst = changes.filter { it.songKey == song.key.value && !it.undone && it.write != WriteState.Confirmed }.sortedByDescending { it.id }
        for (field in DetailField.entries) newestFirst.firstOrNull { it.field == field }?.let { out = out.with(field, it.new) }
        return out
    }

    /**
     * The changes that stop being an overlay: those whose new value the library now shows, and every older unconfirmed change of the
     * same field of that song (the newer one replaced it, so an old text must never be laid over the file again).
     */
    fun confirmedByTheFile(changes: List<DetailChange>, song: (String) -> Song?): List<Long> {
        val live = changes.filter { !it.undone && it.write != WriteState.Confirmed }
        val shown = live.filter { c ->
            val s = song(c.songKey) ?: return@filter false
            clean(s.value(c.field)) == c.new
        }
        val retired = live.filter { c -> shown.any { it.songKey == c.songKey && it.field == c.field && it.id >= c.id } }
        return retired.map { it.id }
    }

    /** The tag change that carries the newest value of each field in [pending] (changes still to be written for one song). */
    fun tagChange(pending: List<DetailChange>): TagChange {
        val newestFirst = pending.filter { !it.undone }.sortedByDescending { it.id }
        fun of(field: DetailField): FieldChange {
            val c = newestFirst.firstOrNull { it.field == field } ?: return FieldChange.Keep
            return c.new?.let { FieldChange.Set(it) } ?: FieldChange.Remove
        }
        return TagChange(of(DetailField.Title), of(DetailField.Artist), of(DetailField.Album))
    }

    /**
     * What undoing a batch comes to (R-31). A change that reached the file (in the file, or confirmed by the library) may be undone only
     * while the file still holds what it wrote ([fileNow]); a change that never reached the file is simply dropped.
     */
    fun undo(batch: List<DetailChange>, fileNow: TagFields, laterLive: List<DetailChange> = emptyList()): UndoPlan {
        val live = batch.filter { !it.undone }
        if (live.any { c -> laterLive.any { !it.undone && it.songKey == c.songKey && it.field == c.field && it.id > c.id } }) return UndoPlan.LaterChange
        val inverse = ArrayList<FieldDiff>()
        var writesFile = false
        for (c in live) {
            if (c.write == WriteState.InFile || c.write == WriteState.Confirmed) {
                val now = when (c.field) { DetailField.Title -> fileNow.title; DetailField.Artist -> fileNow.artist; DetailField.Album -> fileNow.album }
                if (clean(now) != c.new) return UndoPlan.FileChanged
                inverse += FieldDiff(c.field, c.new, c.previous)
                writesFile = true
            }
        }
        return UndoPlan.Apply(inverse, live.map { it.id }, writesFile)
    }

    /** "Recently filled in": the batches of the last [keepDays] days that are not undone, newest first; Undo changes themselves are not listed. */
    fun recent(changes: List<DetailChange>, nowSec: Long, keepDays: Int = UNDO_KEEP_DAYS): List<RecentItem> =
        changes.filter { it.source != ChangeSource.Undo && !it.undone && it.atSec >= nowSec - keepDays * 86_400L }
            .groupBy { it.batch }
            .map { (batch, list) -> RecentItem(batch, list.first().songKey, list.first().source, list.maxOf { it.atSec }, list.sortedBy { it.id }) }
            .sortedByDescending { it.atSec }
}

/** The status of a song in the details store (Technical Design 6). The lookup (H6) only acts on a song that has none or is pending. */
object DetailsStatus {
    const val PENDING = "pending"
    const val APPLIED = "applied"
    const val MANUAL = "manual"
    const val REJECTED = "rejected"
    const val HELD = "held"
    const val NOT_FOUND = "notFound"

    fun mayApplyAutomatic(status: String?): Boolean = status == null || status == PENDING
}
