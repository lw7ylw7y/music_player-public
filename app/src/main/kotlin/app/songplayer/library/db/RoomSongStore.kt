package app.songplayer.library.db

import android.database.sqlite.SQLiteException
import app.songplayer.core.details.DetailChange
import app.songplayer.core.details.DetailsRules
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.LibraryPlan
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.SongStore
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.TextKeyer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SongStore] on Room. [apply] is one SQLite transaction, so a failure or a kill leaves the old rows.
 * Every row also carries the sort and search keys made by [keyer]; [recomputeKeysIfNeeded] rewrites them all,
 * again in one transaction, when the keyer's version differs from the one that wrote them.
 */
class RoomSongStore(
    private val db: AppDatabase,
    private val keyer: TextKeyer,
    private val dao: SongDao = db.songDao(),
    private val settings: SettingDao = db.settingDao(),
    /** Changes that the files do not have yet; the readers of the songs see them laid over the tags (Technical Design 5.8). */
    private val pendingDetails: () -> List<DetailChange> = { emptyList() },
) : SongStore {

    override fun all(): List<Song> = read { dao.all().map { it.toSong() } }

    /** One song by its key as the files have it (no overlay): what a change is compared with once the file may have it. */
    fun rawGet(key: SongKey): Song? = read { dao.get(key.value)?.toSong() }

    /** One song by its key, with the details overlay, or null when the library does not have it. */
    fun get(key: SongKey): Song? = read { dao.get(key.value)?.toSong() }?.let { DetailsRules.overlay(it, pendingDetails()) }

    /**
     * The songs with their stored sort and search keys, ready to sort and search without recomputing; a song with a change the file
     * does not have yet gets the new text and its keys made again. [all] stays the files' own tags: the library sync compares it.
     */
    fun indexed(): List<IndexedSong> {
        val rows = read { dao.all().map { it.toIndexed() } }
        val pending = pendingDetails()
        if (pending.isEmpty()) return rows
        val keys = pending.map { it.songKey }.toSet()
        return rows.map { if (it.song.key.value in keys) IndexedSong.of(DetailsRules.overlay(it.song, pending), keyer) else it }
    }

    override fun apply(plan: LibraryPlan) {
        write {
            // SQLite limits the number of variables in one statement, so long key lists go in chunks.
            plan.removed.map { it.value }.chunked(CHUNK).forEach(dao::delete)
            dao.upsert((plan.added + plan.changed).map { IndexedSong.of(it, keyer).toEntity() })
            plan.filesChanged.map { it.value }.chunked(CHUNK).forEach(dao::clearFailures)
        }
    }

    /** A play attempt of this song failed (R-12): it shows "Can't play" wherever it is listed until it plays. */
    fun markFailed(key: SongKey, atSec: Long) = write { dao.markFailed(PlayFailureEntity(key.value, atSec)) }

    /** The song played: its mark goes. */
    fun clearFailure(key: SongKey) = write { dao.clearFailures(listOf(key.value)) }

    fun failedKeys(): Set<String> = read { dao.failedKeys().toSet() }

    /** The keys of the songs marked "Can't play", kept up to date for the lists. */
    fun failedKeysFlow(): Flow<Set<String>> = dao.failedKeysFlow().map { it.toSet() }

    /** Rewrites every song's keys if [TextKeyer.version] changed. Returns true when it did. All or nothing. */
    fun recomputeKeysIfNeeded(): Boolean {
        val stored = read { settings.get(KEYER_VERSION) }
        if (stored == keyer.version) return false
        write {
            dao.upsert(dao.all().map { IndexedSong.of(it.toSong(), keyer).toEntity() })
            settings.put(SettingEntity(KEYER_VERSION, keyer.version))
        }
        return true
    }

    private fun <T> read(block: () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        // The message may name a table or a path; only the typed code leaves this class.
        throw StoreError.Corrupt()
    }

    private fun write(block: () -> Unit) {
        try {
            db.runInTransaction(Runnable { block() })
        } catch (e: RuntimeException) {
            throw StoreError.WriteFailed()
        }
    }

    private companion object {
        const val CHUNK = 500
        const val KEYER_VERSION = "keyerVersion"
    }
}
