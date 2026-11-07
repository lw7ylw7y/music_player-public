package app.songplayer.playlist

import android.database.sqlite.SQLiteDatabaseCorruptException
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.playlist.AddOutcome
import app.songplayer.core.playlist.FilePlaylist
import app.songplayer.core.playlist.PlaylistFile
import app.songplayer.core.playlist.Playlist
import app.songplayer.core.playlist.PlaylistEntry
import app.songplayer.core.playlist.PlaylistRules
import app.songplayer.core.playlist.PlaylistStore
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.playlist.Relinker
import app.songplayer.core.playlist.RemovedEntry

/**
 * [PlaylistStore] on Room. Every action is one transaction in which the current list is read, the pure rule in `core`
 * is applied, and only the rows that changed are written, so positions stay dense and a failure or a kill leaves the old
 * list. Failures are typed: a database that cannot be read is [StoreError.Corrupt], a write that fails is
 * [StoreError.WriteFailed]; messages never leave this class (they may name a table or a path).
 */
class RoomPlaylistStore(
    private val db: PlaylistsDatabase,
    private val dao: PlaylistDao = db.dao(),
) : PlaylistStore {

    override fun playlists(): List<PlaylistSummary> = read {
        // One transaction, so the counts belong to the list that is returned.
        db.runInTransaction(java.util.concurrent.Callable {
            val counts = dao.counts().associate { it.playlistId to it.songCount }
            dao.playlists().map { PlaylistSummary(Playlist(it.id, it.name, it.createdAt), counts[it.id] ?: 0) }
        })
    }

    override fun create(name: String, createdAtSec: Long): Playlist {
        val clean = requireNotNull(PlaylistRules.cleanName(name)) { "the name is empty or too long" }
        return write {
            Playlist(dao.insertPlaylist(PlaylistRow(name = clean, createdAt = createdAtSec)), clean, createdAtSec)
        }
    }

    override fun rename(playlistId: Long, name: String) {
        val clean = requireNotNull(PlaylistRules.cleanName(name)) { "the name is empty or too long" }
        write { dao.rename(playlistId, clean) }
    }

    override fun delete(playlistId: Long) = write {
        dao.deleteEntriesOf(playlistId)
        dao.delete(playlistId)
    }

    override fun entries(playlistId: Long): List<PlaylistEntry> = read { dao.entries(playlistId).map { it.toEntry() } }

    override fun add(playlistId: Long, song: Song): AddOutcome = write {
        if (dao.exists(playlistId) == 0) return@write AddOutcome.PlaylistGone
        val current = dao.entries(playlistId).map { it.toEntry() }
        val wanted = PlaylistEntry.of(song)
        if (PlaylistRules.add(current, wanted) == null) return@write AddOutcome.AlreadyIn
        val id = dao.insertEntry(wanted.toRow(playlistId, position = current.size))
        AddOutcome.Added(wanted.copy(id = id))
    }

    override fun move(playlistId: Long, entryId: Long, toIndex: Int) = write {
        val current = dao.entries(playlistId).map { it.toEntry() }
        writeOrder(playlistId, PlaylistRules.move(current, entryId, toIndex))
    }

    override fun remove(playlistId: Long, entryId: Long): RemovedEntry? = write {
        val current = dao.entries(playlistId).map { it.toEntry() }
        val (after, removed) = PlaylistRules.remove(current, entryId)
        if (removed != null) {
            dao.deleteEntry(entryId)
            writeOrder(playlistId, after)
        }
        removed
    }

    override fun undoRemove(playlistId: Long, removed: RemovedEntry) = write {
        if (dao.exists(playlistId) == 0) return@write
        val current = dao.entries(playlistId).map { it.toEntry() }
        val after = PlaylistRules.undoRemove(current, removed) ?: return@write
        dao.insertEntry(removed.entry.toRow(playlistId, position = after.indexOfFirst { it.id == removed.entry.id }))
        writeOrder(playlistId, after)
    }

    override fun exportAll(): List<FilePlaylist> = read {
        db.runInTransaction(java.util.concurrent.Callable {
            val entries = dao.allEntries().groupBy { it.playlistId }
            dao.playlists().map { p -> FilePlaylist(p.name, p.createdAt, entries[p.id].orEmpty().map { it.toEntry() }) }
        })
    }

    override fun importAll(incoming: List<FilePlaylist>, createdAtSec: Long): Int = write {
        val entries = dao.allEntries().groupBy { it.playlistId }
        val existing = dao.playlists().map { p -> FilePlaylist(p.name, p.createdAt, entries[p.id].orEmpty().map { it.toEntry() }) }
        val added = PlaylistFile.newOnes(existing, incoming)
        for (p in added) {
            val id = dao.insertPlaylist(PlaylistRow(name = p.name, createdAt = if (p.createdAtSec > 0) p.createdAtSec else createdAtSec))
            p.entries.forEachIndexed { position, e -> dao.insertEntry(e.copy(id = 0).toRow(id, position)) }
        }
        added.size
    }

    override fun removeSongs(songKeys: Set<SongKey>): Int = write {
        var removed = 0
        dao.allEntries().groupBy { it.playlistId }.forEach { (playlistId, rows) ->
            val current = rows.map { it.toEntry() }
            val after = PlaylistRules.withoutSongs(current, songKeys)
            if (after.size == current.size) return@forEach
            val kept = after.mapTo(HashSet()) { it.id }
            current.filter { it.id !in kept }.forEach { dao.deleteEntry(it.id); removed++ }
            writeOrder(playlistId, after)
        }
        removed
    }

    override fun updateSize(songKey: SongKey, sizeBytes: Long) = write { dao.setSize(songKey.value, sizeBytes) }

    override fun relinkAll(library: List<Song>): Int = write {
        var changed = 0
        dao.allEntries().groupBy { it.playlistId }.forEach { (_, rows) ->
            Relinker.relink(rows.map { it.toEntry() }, library).forEach { link ->
                dao.setSongKey(link.entryId, link.newKey.value)
                changed++
            }
        }
        changed
    }

    /** Writes the positions of the entries whose place in [wanted] differs from their stored place (the list order is the position). */
    private fun writeOrder(playlistId: Long, wanted: List<PlaylistEntry>) {
        val storedPosition = dao.entries(playlistId).associate { it.id to it.position }
        wanted.forEachIndexed { index, entry ->
            if (storedPosition[entry.id] != index) dao.setPosition(entry.id, index)
        }
    }

    /** Damage is [StoreError.Corrupt]; any other reason the file could not be read is [StoreError.ReadFailed]. */
    private fun <T> read(block: () -> T): T = try {
        block()
    } catch (e: StoreError) {
        throw e
    } catch (e: RuntimeException) {
        throw if (isDamage(e)) StoreError.Corrupt() else StoreError.ReadFailed()
    }

    private fun <T> write(block: () -> T): T {
        try {
            return db.runInTransaction(java.util.concurrent.Callable { block() })
        } catch (e: StoreError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e // a name the caller should have checked; not a storage failure
        } catch (e: RuntimeException) {
            throw if (isDamage(e)) StoreError.Corrupt() else StoreError.WriteFailed()
        }
    }

    /** Whether the failure is a damaged database file, however far down Room has wrapped it. The messages are never used. */
    private fun isDamage(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is SQLiteDatabaseCorruptException) return true
            cause = cause.cause
        }
        return false
    }

    private fun EntryRow.toEntry() = PlaylistEntry(id, SongKey(songKey), fileName, sizeBytes, durationMs)

    private fun PlaylistEntry.toRow(playlistId: Long, position: Int) =
        EntryRow(id = id, playlistId = playlistId, position = position, songKey = songKey.value, fileName = fileName, sizeBytes = sizeBytes, durationMs = durationMs)
}
