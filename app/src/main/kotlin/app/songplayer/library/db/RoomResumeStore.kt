package app.songplayer.library.db

import android.database.sqlite.SQLiteException
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.SongKey
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeRules
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.resume.ResumeStore

/**
 * [ResumeStore] on Room. The queue and the resume row are replaced in one transaction, so a kill between the two
 * cannot leave them disagreeing; a position write is applied only if the queue still has that song at that place.
 */
class RoomResumeStore(
    private val db: AppDatabase,
    private val dao: ResumeDao = db.resumeDao(),
) : ResumeStore {

    override fun load(): ResumeState? = try {
        val resume = dao.resume()
        val queue = dao.queue()
        // The queue and the resume row are written together, so they agree; if they do not, nothing is trusted.
        if (resume == null || queue.isEmpty() || queue.getOrNull(resume.currentIndex)?.songKey != resume.currentKey) {
            null
        } else {
            ResumeState(
                queue = queue.map { QueueEntry(SongKey(it.songKey), it.added, it.shuffleRank, it.displayName) },
                currentIndex = resume.currentIndex,
                positionMs = resume.positionMs,
                shuffleOn = resume.shuffleOn,
                repeatMode = resume.repeatMode,
                source = resume.queueSource,
            )
        }
    } catch (e: SQLiteException) {
        // The message may name a table or a path; only the typed code leaves this class.
        throw StoreError.Corrupt()
    }

    override fun saveQueue(state: ResumeState) = write {
        dao.clearQueue()
        dao.clearResume()
        if (state.queue.isNotEmpty() && state.currentIndex in state.queue.indices) {
            dao.putQueue(state.queue.mapIndexed { i, e -> QueueItemEntity(i, e.songKey.value, e.added, e.shuffleRank, e.displayName) })
            dao.putResume(
                ResumeEntity(
                    currentIndex = state.currentIndex,
                    currentKey = state.queue[state.currentIndex].songKey.value,
                    positionMs = state.positionMs,
                    shuffleOn = state.shuffleOn,
                    repeatMode = state.repeatMode,
                    queueSource = state.source,
                ),
            )
        }
    }

    override fun savePosition(index: Int, key: SongKey, positionMs: Long) = write {
        if (ResumeRules.acceptsPosition(dao.keyAt(index)?.let { SongKey(it) }, key)) dao.updatePosition(index, key.value, positionMs)
    }

    private fun write(block: () -> Unit) {
        try {
            db.runInTransaction(Runnable { block() })
        } catch (e: RuntimeException) {
            throw StoreError.WriteFailed()
        }
    }
}
