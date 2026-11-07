package app.songplayer.details

import app.songplayer.core.details.ChangeSource
import app.songplayer.core.details.DetailChange
import app.songplayer.core.details.DetailsRules
import app.songplayer.core.details.GrantPolicy
import app.songplayer.core.details.RecentItem
import app.songplayer.core.details.TagFields
import app.songplayer.core.details.TagFileStore
import app.songplayer.core.details.UndoPlan
import app.songplayer.core.details.WriteState
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.library.db.RoomDetailsStore
import app.songplayer.library.db.RoomSongStore

sealed interface SaveResult {
    /** The edit changed nothing. */
    data object Unchanged : SaveResult

    /** Saved and written into the file. */
    data class Saved(val batch: Long) : SaveResult

    /** Saved in the app; the file does not have it yet ([reason]: why, for the message). */
    data class InAppOnly(val batch: Long, val reason: NotWrittenReason?) : SaveResult
}

sealed interface UndoResult {
    data object Done : UndoResult

    /** The file's tag is no longer what was written: another app changed it, so nothing was undone. */
    data object FileChanged : UndoResult

    /** The same field was changed again later and that change is still live: undo it first, or the older text would replace it. */
    data object LaterChange : UndoResult

    /** The file could not be read to check, or the undo could not be written yet: the earlier text is in the app only. */
    data class InAppOnly(val reason: NotWrittenReason?) : UndoResult
}

/** What a flush did: how many songs reached their files and how many still wait (and the last reason a write was refused). */
data class FlushSummary(val songsWritten: Int, val songsWaiting: Int, val lastReason: NotWrittenReason?, val noAccess: Int = 0)

/**
 * What the user does with song details (Stories H3): save a hand edit, undo a change, and carry the changes into the files.
 * It joins the store (`app.db`), the tag writer and the rules of `core`. Blocking: call it off the main thread. One operation
 * at a time, so a flush never races a save or an undo.
 */
class DetailsActions(
    private val store: RoomDetailsStore,
    private val songs: RoomSongStore,
    private val files: TagFileStore,
    private val log: AppLog,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Any()

    /** Saves a hand edit of [current] (the song as shown, with its overlay) and tries to write it into the file at once. */
    fun saveEdit(current: Song, edited: TagFields): SaveResult = synchronized(lock) {
        val diffs = DetailsRules.diff(TagFields(current.title, current.artist, current.album), edited)
        if (diffs.isEmpty()) return SaveResult.Unchanged
        val batch = store.saveEdit(current.key.value, diffs, nowSec())
        val summary = flush(current.key.value, includeFailed = true)
        if (summary.songsWaiting == 0) SaveResult.Saved(batch) else SaveResult.InAppOnly(batch, summary.lastReason)
    }

    /**
     * Writes the changes the files do not have yet. A song whose write failed after the check (state Failed) is left alone unless
     * [includeFailed]: the user has to ask again (R-27). Without [songKey] every song with changes is tried.
     */
    fun flush(songKey: String? = null, includeFailed: Boolean = false): FlushSummary = synchronized(lock) {
        val bySong = store.pending().filter { songKey == null || it.songKey == songKey }.groupBy { it.songKey }
        var written = 0
        var waiting = 0
        var reason: NotWrittenReason? = null
        var noAccess = 0
        for ((key, changes) in bySong) {
            val todo = changes.filter { it.write == WriteState.Waiting || (includeFailed && it.write == WriteState.Failed) }
            if (todo.isEmpty()) continue
            try {
                files.write(key, DetailsRules.tagChange(todo))
                store.markInFile(todo.map { it.id })
                written++
            } catch (e: DetailsError.NotWritten) {
                reason = e.reason
                if (e.reason == NotWrittenReason.NoAccess) noAccess++
                waiting++
            } catch (e: DetailsError.WriteFailed) {
                store.markFailed(todo.map { it.id })
                waiting++
            } catch (e: Exception) {
                // Anything unexpected from the writer: the song's changes stay in the app and are not retried by themselves.
                log.event(LogCode.DETAILS_STORE_FAILED, 4)
                store.markFailed(todo.map { it.id })
                waiting++
            }
        }
        FlushSummary(written, waiting, reason, noAccess)
    }

    /** Undoes the batch (R-31). */
    fun undo(batch: Long): UndoResult = synchronized(lock) {
        val changes = store.batch(batch)
        val first = changes.firstOrNull() ?: return UndoResult.Done
        val key = first.songKey
        val fileNow = if (changes.any { (it.write == WriteState.InFile || it.write == WriteState.Confirmed) && !it.undone }) {
            try { files.read(key) } catch (e: DetailsError.NotWritten) { return UndoResult.InAppOnly(e.reason) }
        } else {
            TagFields(null, null, null)
        }
        when (val plan = DetailsRules.undo(changes, fileNow, store.liveOf(key))) {
            UndoPlan.FileChanged -> UndoResult.FileChanged
            UndoPlan.LaterChange -> UndoResult.LaterChange
            is UndoPlan.Apply -> {
                val automatic = first.source == ChangeSource.Auto || first.source == ChangeSource.Review
                store.undo(key, plan.undoneIds, plan.inverse, automatic, nowSec())
                if (!plan.writesFile) return UndoResult.Done
                val summary = flush(key, includeFailed = true)
                if (summary.songsWaiting == 0) UndoResult.Done else UndoResult.InAppOnly(summary.lastReason)
            }
        }
    }

    /**
     * Called after a library sync: a change that the file now shows (the library, read raw, has the new value) stops being an
     * overlay, so a later change by another app is shown as it is. Also forgets written changes older than the Undo window.
     * It never throws.
     */
    fun confirmWritten() {
        try {
            synchronized(lock) {
                val pending = store.pending()
                val ids = DetailsRules.confirmedByTheFile(pending) { songs.rawGet(SongKey(it)) }
                if (ids.isNotEmpty()) store.confirm(ids, pending.filter { it.id in ids }.map { it.songKey }.toSet())
                store.trim(nowSec() - DetailsRules.UNDO_KEEP_DAYS * 86_400L)
            }
        } catch (e: Exception) {
            log.event(LogCode.DETAILS_STORE_FAILED, 3)
        }
    }

    /** The songs whose details are only in the app: changes still waiting, and writes that started and were undone (the user can retry those). */
    fun waitingSongKeys(): List<String> = synchronized(lock) {
        store.pending().filter { it.write == WriteState.Waiting || it.write == WriteState.Failed }.map { it.songKey }.distinct()
    }

    /**
     * The next songs to ask Android for write access to, as one request lists them: at most `GrantPolicy.SONGS_PER_REQUEST`, those in
     * [alreadyAsked] last so stuck songs cannot starve the rest. Only songs with changes still waiting for access are listed. Returns the
     * song keys with their addresses.
     */
    fun nextGrantBatch(alreadyAsked: Set<String>): List<Pair<String, android.net.Uri>> = synchronized(lock) {
        val waiting = store.pending().filter { it.write == WriteState.Waiting }.map { it.songKey }.distinct()
        GrantPolicy.nextBatch(waiting, alreadyAsked).mapNotNull { key -> songs.get(SongKey(key))?.mediaStoreId?.let { key to app.songplayer.playback.audioUri(it) } }
    }

    /** The rows of "Recently filled in". */
    fun recent(): List<RecentItem> {
        val now = nowSec()
        return DetailsRules.recent(store.since(now - DetailsRules.UNDO_KEEP_DAYS * 86_400L), now)
    }
}
