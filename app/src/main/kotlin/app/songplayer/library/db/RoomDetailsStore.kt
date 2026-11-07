package app.songplayer.library.db

import app.songplayer.core.details.Candidate
import app.songplayer.core.details.ChangeSource
import app.songplayer.core.details.DetailChange
import app.songplayer.core.details.DetailField
import app.songplayer.core.details.DetailsStatus
import app.songplayer.core.details.DetailsStore
import app.songplayer.core.details.ReviewStore
import app.songplayer.core.details.HeldCandidates
import app.songplayer.core.details.FieldDiff
import app.songplayer.core.details.WriteState
import app.songplayer.core.errors.StoreError
import app.songplayer.core.playlist.DecisionStore
import app.songplayer.core.playlist.FileDecision
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The song details on Room (Technical Design 5.8 and 6): the only writer of `details_state` and `details_change`. Every update
 * that depends on a song's status is conditional and runs in one transaction, so a hand edit saved a moment before is never
 * overwritten by a late automatic apply. A failed write is a [StoreError.WriteFailed]; a read that fails a [StoreError.ReadFailed].
 */
class RoomDetailsStore(private val db: AppDatabase, private val dao: DetailsDao = db.detailsDao()) : DetailsStore, ReviewStore, DecisionStore {
    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()
    private val _version = MutableStateFlow(0)
    private var lastBatch = -1L

    /** Goes up after every change to the details, so the lists read the songs again. */
    val version: StateFlow<Int> = _version

    private val _statusVersion = MutableStateFlow(0)

    /** Goes up when a song's status changes (applied, held, not found, rejected, manual): the counts on Song details and the Library banner read again. */
    val statusVersion: StateFlow<Int> = _statusVersion

    /**
     * [listener] hears the keys of the songs whose details changed (the playback service updates the queue items). Remove it when its
     * owner goes away: the returned handle does. A listener that throws never stops the others or the change itself.
     */
    fun onChanged(listener: (Set<String>) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** What the screens lay over the files' tags (the song store reads this on every list read). */
    fun pending(): List<DetailChange> = read { dao.pending().map { it.toChange() } }

    fun since(sinceSec: Long): List<DetailChange> = read { dao.since(sinceSec).map { it.toChange() } }

    fun batch(batch: Long): List<DetailChange> = read { dao.batch(batch).map { it.toChange() } }

    /** Every change of the song that is not undone, oldest first (to see whether a later change makes an Undo unsafe). */
    fun liveOf(songKey: String): List<DetailChange> = read { dao.liveOf(songKey).map { it.toChange() } }

    override fun status(songKey: String): String? = read { dao.status(songKey) }

    /** A hand edit: the changes and the status `manual` are saved together; it wins over everything. Returns the batch id. */
    fun saveEdit(songKey: String, diffs: List<FieldDiff>, nowSec: Long): Long {
        val batch = newBatch(nowSec)
        write {
            dao.insertChanges(diffs.map { it.toRow(batch, songKey, ChangeSource.Manual, nowSec, WriteState.Waiting) })
            dao.putState(DetailsStateEntity(songKey, DetailsStatus.MANUAL, nowSec, null))
        }
        changed(setOf(songKey))
        return batch
    }

    /**
     * An automatic or reviewed change (the lookup, Stories H6): applied only while the song is still pending, in one transaction
     * with its status. Returns false, changing nothing, when the song was edited by hand or decided meanwhile.
     */
    override fun applyAutomatic(songKey: String, diffs: List<FieldDiff>, source: ChangeSource, nowSec: Long): Boolean {
        var applied = false
        write {
            if (!DetailsStatus.mayApplyAutomatic(dao.status(songKey))) return@write
            if (diffs.isNotEmpty()) {
                val batch = newBatch(nowSec)
                dao.insertChanges(diffs.map { it.toRow(batch, songKey, source, nowSec, WriteState.Waiting) })
            }
            dao.putState(DetailsStateEntity(songKey, DetailsStatus.APPLIED, nowSec, null))
            applied = true
        }
        if (applied) _statusVersion.update { it + 1 }
        if (applied && diffs.isNotEmpty()) changed(setOf(songKey))
        return applied
    }

    /** An unsure or tied lookup result: the candidates are kept for Review, only while the song is still pending. */
    override fun hold(songKey: String, candidates: List<Candidate>, nowSec: Long): Boolean = decide(songKey, DetailsStatus.HELD, HeldCandidates.encode(candidates), nowSec)

    /** The service does not know the song (or it cannot be decoded): it is not looked up again, only while it is still pending. */
    override fun markNotFound(songKey: String, nowSec: Long): Boolean = decide(songKey, DetailsStatus.NOT_FOUND, null, nowSec)

    /** How many songs have each status (numbers only; the first-run report of Stories H10). */
    fun statusCounts(): Map<String, Int> = read { dao.statusCounts().associate { it.status to it.n } }

    /** The songs waiting for review, in key order. */
    fun heldKeys(): List<String> = read { dao.heldKeys() }

    /** The songs the service did not know or that could not be read, in key order. */
    fun notFoundKeys(): List<String> = read { dao.notFoundKeys() }

    /** Review "Use this": applies [diffs] as the user's choice, only while the song is still held. */
    override fun applyReview(songKey: String, diffs: List<FieldDiff>, nowSec: Long): Boolean {
        var done = false
        write {
            if (dao.status(songKey) != DetailsStatus.HELD) return@write
            if (diffs.isNotEmpty()) {
                val batch = newBatch(nowSec)
                dao.insertChanges(diffs.map { it.toRow(batch, songKey, ChangeSource.Review, nowSec, WriteState.Waiting) })
            }
            dao.putState(DetailsStateEntity(songKey, DetailsStatus.APPLIED, nowSec, null))
            done = true
        }
        if (done) _statusVersion.update { it + 1 }
        if (done && diffs.isNotEmpty()) changed(setOf(songKey))
        return done
    }

    /** Review "None of these": the song is rejected and never looked up again, only while it is still held. */
    override fun reject(songKey: String, nowSec: Long): Boolean {
        var done = false
        write {
            if (dao.status(songKey) != DetailsStatus.HELD) return@write
            dao.putState(DetailsStateEntity(songKey, DetailsStatus.REJECTED, nowSec, null))
            done = true
        }
        if (done) _statusVersion.update { it + 1 }
        return done
    }

    /** How many songs wait for the user's review. */
    fun heldCount(): Int = read { dao.heldCount() }

    override fun exportDecisions(): List<FileDecision> = read { dao.decisions().map { FileDecision(it.songKey, it.status, it.checkedAt, it.candidates) } }

    override fun importDecisions(incoming: List<FileDecision>): Int {
        var added = 0
        write { added = dao.addStatesIfAbsent(incoming.map { DetailsStateEntity(it.songKey, it.status, it.checkedAtSec, it.candidates) }).count { it != -1L } }
        if (added > 0) _statusVersion.update { it + 1 }
        return added
    }

    override fun decidedKeys(): Set<String> = read { dao.decidedKeys().toSet() }

    /** The candidates kept for a held song, best first; empty for any other song. */
    fun heldCandidates(songKey: String): List<Candidate> = read { dao.candidates(songKey) }?.let(HeldCandidates::decode).orEmpty()

    private fun decide(songKey: String, status: String, candidates: String?, nowSec: Long): Boolean {
        var done = false
        write {
            if (!DetailsStatus.mayApplyAutomatic(dao.status(songKey))) return@write
            dao.putState(DetailsStateEntity(songKey, status, nowSec, candidates))
            done = true
        }
        if (done) _statusVersion.update { it + 1 }
        return done
    }

    /** Undo: [inverse] becomes new waiting changes (source Undo) and the changes in [undoneIds] stop counting. An undone automatic fill makes the song `rejected`. */
    fun undo(songKey: String, undoneIds: List<Long>, inverse: List<FieldDiff>, wasAutomatic: Boolean, nowSec: Long) {
        write {
            dao.markUndone(undoneIds)
            if (inverse.isNotEmpty()) {
                val batch = newBatch(nowSec)
                dao.insertChanges(inverse.map { it.toRow(batch, songKey, ChangeSource.Undo, nowSec, WriteState.Waiting) })
            }
            if (wasAutomatic) dao.putState(DetailsStateEntity(songKey, DetailsStatus.REJECTED, nowSec, null))
        }
        changed(setOf(songKey))
    }

    /** The file has these changes now; the overlay stays until the library shows them ([confirm]). */
    fun markInFile(ids: List<Long>) = write { dao.setWriteState(ids, WriteState.InFile.ordinal) }

    /** The library shows these changes: the overlay ends and the file's own value is read from then on. */
    fun confirm(ids: List<Long>, songKeys: Set<String>) { write { dao.setWriteState(ids, WriteState.Confirmed.ordinal) }; changed(songKeys) }

    fun markFailed(ids: List<Long>) = write { dao.setWriteState(ids, WriteState.Failed.ordinal) }

    /** Forgets confirmed changes older than the Undo window. */
    fun trim(beforeSec: Long) = write { dao.trim(beforeSec) }

    /** A batch id that is never used twice: after the time in milliseconds, and after the highest one stored (the clock can go back). */
    @Synchronized
    private fun newBatch(nowSec: Long): Long {
        // Only writes ask for a batch id, so a failure here is a failed write.
        if (lastBatch < 0) lastBatch = (try { dao.maxBatch() } catch (e: RuntimeException) { throw StoreError.WriteFailed() }) ?: 0L
        lastBatch = maxOf(lastBatch + 1, nowSec * 1000)
        return lastBatch
    }

    private fun changed(keys: Set<String>) {
        _version.update { it + 1 }
        for (l in listeners) try { l(keys) } catch (e: Exception) { /* a listener's failure is its own; the change is saved */ }
    }

    private fun <T> read(block: () -> T): T = try { block() } catch (e: RuntimeException) { throw StoreError.ReadFailed() }

    private fun write(block: () -> Unit) {
        try { db.runInTransaction(Runnable { block() }) } catch (e: RuntimeException) { throw StoreError.WriteFailed() }
    }

    private fun FieldDiff.toRow(batch: Long, songKey: String, source: ChangeSource, nowSec: Long, state: WriteState) =
        DetailsChangeEntity(0, batch, songKey, field.name, previous, new, source.name, nowSec, state.ordinal, false)

    private fun DetailsChangeEntity.toChange() = DetailChange(
        id, batch, songKey, DetailField.valueOf(field), previous, newValue, ChangeSource.valueOf(source), atEpochSec,
        WriteState.entries[writeState], undone,
    )
}
