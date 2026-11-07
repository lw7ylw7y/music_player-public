package app.songplayer.core.playlist

import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey

data class Playlist(val id: Long, val name: String, val createdAtSec: Long)

/** A playlist with the number of songs in it, for the Playlists tab and the "Add to playlist" sheet. */
data class PlaylistSummary(val playlist: Playlist, val songCount: Int)

/**
 * One song of a playlist. Besides the key it keeps the file name, size and length, so that after a restore on another
 * phone (where paths differ) the song can be found again ([Relinker]), and so that a song whose file is gone can still
 * be shown. [id] is the row's own identity; actions name an entry by it, not by its position, because a position
 * can change between the moment a screen drew the list and the moment the action runs.
 */
data class PlaylistEntry(
    val id: Long,
    val songKey: SongKey,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
) {
    companion object {
        /** An entry for a song about to be added; the store gives it its id. */
        fun of(song: Song) = PlaylistEntry(0, song.key, song.fileName, song.sizeBytes, song.durationMs)
    }
}

/** An entry that was taken out, with the place it had, so that Undo can put it back there. */
data class RemovedEntry(val entry: PlaylistEntry, val index: Int)

sealed interface AddOutcome {
    /** The song is in the playlist now, at the end, as [entry] (with its id). */
    data class Added(val entry: PlaylistEntry) : AddOutcome

    /** The song was already in this playlist; nothing changed (R-10). */
    data object AlreadyIn : AddOutcome

    /** The playlist was deleted in the meantime; nothing changed. */
    data object PlaylistGone : AddOutcome
}

/** The pure rules of playlist editing: they take the current list and return the new one. Positions are the list order. */
object PlaylistRules {
    const val MAX_NAME_LENGTH = 100

    /** The name as it will be stored (trimmed), or null when it is empty or too long. Two playlists may share a name. */
    fun cleanName(raw: String): String? {
        val name = raw.trim()
        return if (name.isEmpty() || name.length > MAX_NAME_LENGTH) null else name
    }

    /** [entry] goes to the end, unless a song with the same key is already in the list. */
    fun add(entries: List<PlaylistEntry>, entry: PlaylistEntry): List<PlaylistEntry>? =
        if (entries.any { it.songKey == entry.songKey }) null else entries + entry

    /** The entry [entryId] moves to [toIndex] (counted in the list as it is after the move); unknown id: no change. */
    fun move(entries: List<PlaylistEntry>, entryId: Long, toIndex: Int): List<PlaylistEntry> {
        val from = entries.indexOfFirst { it.id == entryId }
        if (from < 0) return entries
        val rest = entries.toMutableList().also { it.removeAt(from) }
        rest.add(toIndex.coerceIn(0, rest.size), entries[from])
        return rest
    }

    /** The list without any entry of [songs] (songs the user deleted from the phone); the order of the rest is kept. */
    fun withoutSongs(entries: List<PlaylistEntry>, songs: Set<SongKey>): List<PlaylistEntry> = entries.filter { it.songKey !in songs }

    /** The list without [entryId] and what was removed; unknown id: the list as it is and null. */
    fun remove(entries: List<PlaylistEntry>, entryId: Long): Pair<List<PlaylistEntry>, RemovedEntry?> {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return entries to null
        return entries.filterIndexed { i, _ -> i != index } to RemovedEntry(entries[index], index)
    }

    /**
     * Puts a removed entry back where it was, or at the end if the list has become shorter. Null (nothing to do) if the
     * entry, or its song, is in the list again (it was added again meanwhile): a song is in a playlist once.
     */
    fun undoRemove(entries: List<PlaylistEntry>, removed: RemovedEntry): List<PlaylistEntry>? {
        if (entries.any { it.id == removed.entry.id || it.songKey == removed.entry.songKey }) return null
        return entries.toMutableList().also { it.add(removed.index.coerceIn(0, it.size), removed.entry) }
    }
}

/** A playlist entry that should point at another song key. */
data class Relink(val entryId: Long, val newKey: SongKey)

object Relinker {
    /**
     * For the entries whose song is not in [library], the entries that can be linked to a song again: exactly one
     * library song has the same file name and size (R-12, R-17, Technical Design 5.3). None or several: no change. A
     * link is not made if the playlist already holds that song.
     */
    fun relink(entries: List<PlaylistEntry>, library: List<Song>): List<Relink> {
        val keys = library.mapTo(HashSet()) { it.key }
        val present = entries.mapTo(HashSet()) { it.songKey }
        val byFile = library.groupBy { it.fileName to it.sizeBytes }
        return entries.mapNotNull { entry ->
            if (entry.songKey in keys) return@mapNotNull null
            val match = byFile[entry.fileName to entry.sizeBytes]?.singleOrNull() ?: return@mapNotNull null
            // Two missing entries that both point at the one song: only the first is linked, so the playlist keeps it once.
            if (!present.add(match.key)) null else Relink(entry.id, match.key)
        }
    }
}

/**
 * The saved playlists (Technical Design 5.3). Every action is one transaction, so a failure or a kill leaves the old
 * list. A write that fails throws [app.songplayer.core.errors.StoreError.WriteFailed]; a damaged database throws
 * [app.songplayer.core.errors.StoreError.Corrupt] and is never deleted; any other read failure throws
 * [app.songplayer.core.errors.StoreError.ReadFailed].
 */
interface PlaylistStore {
    fun playlists(): List<PlaylistSummary>

    fun create(name: String, createdAtSec: Long): Playlist

    fun rename(playlistId: Long, name: String)

    /** Deletes the playlist and its entries; song files and other playlists are untouched. */
    fun delete(playlistId: Long)

    fun entries(playlistId: Long): List<PlaylistEntry>

    fun add(playlistId: Long, song: Song): AddOutcome

    fun move(playlistId: Long, entryId: Long, toIndex: Int)

    /** The removed entry for [undoRemove], or null if it was not in the list any more. */
    fun remove(playlistId: Long, entryId: Long): RemovedEntry?

    fun undoRemove(playlistId: Long, removed: RemovedEntry)

    /** Links entries of every playlist to the songs the library has now ([Relinker]); returns how many were changed. */
    fun relinkAll(library: List<Song>): Int

    /** Every playlist with its songs in order, read in one transaction, for the export file (R-26). */
    fun exportAll(): List<FilePlaylist>

    /**
     * Adds the playlists of an import file that are not there yet ([PlaylistFile.newOnes]), each with its songs in order, in one
     * transaction: all or nothing. Nothing existing is changed or deleted. Returns how many playlists were added.
     */
    fun importAll(incoming: List<FilePlaylist>, createdAtSec: Long): Int

    /** The user deleted these songs from the phone (R-50): every entry of them leaves every playlist; returns how many entries went. Positions stay dense. */
    fun removeSongs(songKeys: Set<SongKey>): Int

    /** The file of this song changed size (its tag was rewritten): every entry of it keeps the new size, so a later relink still finds it. */
    fun updateSize(songKey: SongKey, sizeBytes: Long)
}
