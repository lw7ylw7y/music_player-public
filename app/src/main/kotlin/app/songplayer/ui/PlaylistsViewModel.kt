package app.songplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.playlist.AddOutcome
import app.songplayer.core.playlist.Playlist
import app.songplayer.core.playlist.PlaylistItem
import app.songplayer.core.playlist.PlaylistRules
import app.songplayer.core.playlist.PlaylistStore
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.playlist.PlaylistView
import app.songplayer.core.playlist.RemovedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Playlists tab's content. */
sealed interface PlaylistsState {
    data object Loading : PlaylistsState

    data class Ready(val playlists: List<PlaylistSummary>) : PlaylistsState

    /** The playlists could not be read. [damaged] is true when the file is unreadable as a database, false for another reason (a retry may work). */
    data class Unavailable(val damaged: Boolean) : PlaylistsState
}

/** The open playlist (UX Design C2). */
sealed interface DetailState {
    data object Closed : DetailState

    data object Loading : DetailState

    data class Ready(val playlist: Playlist, val items: List<PlaylistItem>) : DetailState

    /** The playlist was deleted while its screen was open. */
    data object Gone : DetailState

    data class Unavailable(val damaged: Boolean) : DetailState
}

/** What adding songs to a playlist did. */
sealed interface AddResult {
    data class Done(val added: Int, val alreadyIn: Int) : AddResult

    data object PlaylistGone : AddResult

    data object Failed : AddResult
}

/** The result of making a playlist and putting songs in it. */
sealed interface CreateResult {
    data class Created(val playlist: Playlist, val added: Int) : CreateResult

    data object Failed : CreateResult
}

/**
 * Holds the playlists for the screens (Technical Design 5.3): reads them through the [app.songplayer.core.playlist.PlaylistStore],
 * and runs every change off the main thread, then tells the caller on the main thread whether it was saved. The
 * screens never touch the database. A failure is logged as a code (no names, NFR-4) and returned as a result the
 * screen turns into words.
 */
class PlaylistsViewModel(private val store: PlaylistStore, private val log: AppLog) : ViewModel() {
    private val library = MutableStateFlow<List<Song>>(emptyList())
    private val version = MutableStateFlow(0)
    private val openId = MutableStateFlow<Long?>(null)
    private val writes = Mutex()

    val lists: StateFlow<PlaylistsState> = version
        .map { readLists() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsState.Loading)

    val detail: StateFlow<DetailState> = combine(openId, library, version) { id, songs, _ -> id to songs }
        .map { (id, songs) -> if (id == null) DetailState.Closed else readDetail(id, songs) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Closed)

    /** The songs the library has now; the open playlist is shown with their details. */
    fun setLibrary(songs: List<Song>) {
        library.value = songs
    }

    fun open(playlistId: Long) {
        openId.value = playlistId
    }

    fun close() {
        openId.value = null
    }

    /** Reads the playlists again (the Playlists tab's retry). */
    fun reload() = bump()

    private fun bump() {
        version.value += 1
    }

    private fun readLists(): PlaylistsState = try {
        PlaylistsState.Ready(store.playlists())
    } catch (e: StoreError) {
        failed(LogCode.PLAYLIST_READ_FAILED, e)
        PlaylistsState.Unavailable(damaged = e is StoreError.Corrupt)
    }

    private fun readDetail(id: Long, songs: List<Song>): DetailState = try {
        val summary = store.playlists().firstOrNull { it.playlist.id == id }
        if (summary == null) DetailState.Gone else DetailState.Ready(summary.playlist, PlaylistView.items(store.entries(id), songs))
    } catch (e: StoreError) {
        failed(LogCode.PLAYLIST_READ_FAILED, e)
        DetailState.Unavailable(damaged = e is StoreError.Corrupt)
    }

    private fun failed(code: LogCode, e: StoreError) = log.event(code, if (e is StoreError.Corrupt) 1 else 0)

    /** Runs [work] on the IO thread; a store failure is logged and gives null. Lists are read again afterwards. */
    private fun <T : Any> change(work: () -> T, done: (T?) -> Unit) {
        viewModelScope.launch {
            // One change at a time, in the order the screen asked: two quick moves must not swap.
            val result = writes.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        work()
                    } catch (e: StoreError) {
                        failed(LogCode.PLAYLIST_WRITE_FAILED, e)
                        null
                    }
                }
            }
            bump()
            done(result)
        }
    }

    /** Makes a playlist called [rawName] (trimmed; empty or over 100 characters is refused by the dialog) and puts [songs] in it. */
    fun create(rawName: String, songs: List<Song>, done: (CreateResult) -> Unit) {
        val name = PlaylistRules.cleanName(rawName)
        if (name == null) {
            done(CreateResult.Failed)
            return
        }
        change(
            work = {
                val playlist = store.create(name, System.currentTimeMillis() / 1000)
                // The playlist exists from here on; if putting the songs in fails, it is kept (shorter) and the caller is told.
                var added = 0
                try {
                    for (song in songs) if (store.add(playlist.id, song) is AddOutcome.Added) added++
                } catch (e: StoreError) {
                    failed(LogCode.PLAYLIST_WRITE_FAILED, e)
                }
                CreateResult.Created(playlist, added)
            },
            done = { done(it ?: CreateResult.Failed) },
        )
    }

    fun addSongs(playlistId: Long, songs: List<Song>, done: (AddResult) -> Unit) {
        change(work = { addAll(playlistId, songs) }, done = { done(it ?: AddResult.Failed) })
    }

    private fun addAll(playlistId: Long, songs: List<Song>): AddResult {
        var added = 0
        var already = 0
        for (song in songs) {
            when (store.add(playlistId, song)) {
                is AddOutcome.Added -> added++
                AddOutcome.AlreadyIn -> already++
                AddOutcome.PlaylistGone -> return AddResult.PlaylistGone
            }
        }
        return AddResult.Done(added, already)
    }

    fun rename(playlistId: Long, rawName: String, done: (Boolean) -> Unit) {
        val name = PlaylistRules.cleanName(rawName)
        if (name == null) {
            done(false)
            return
        }
        change(work = { store.rename(playlistId, name); true }, done = { done(it == true) })
    }

    fun delete(playlistId: Long, done: (Boolean) -> Unit) =
        change(work = { store.delete(playlistId); true }, done = { done(it == true) })

    /** Takes the row out; [done] gets what Undo needs, or null when the row was gone already or saving failed ([saved] tells which). */
    fun remove(playlistId: Long, entryId: Long, done: (saved: Boolean, removed: RemovedEntry?) -> Unit) =
        change(work = { Optional(store.remove(playlistId, entryId)) }, done = { done(it != null, it?.value) })

    fun undoRemove(playlistId: Long, removed: RemovedEntry, done: (Boolean) -> Unit) =
        change(work = { store.undoRemove(playlistId, removed); true }, done = { done(it == true) })

    fun move(playlistId: Long, entryId: Long, toIndex: Int, done: (Boolean) -> Unit = {}) =
        change(work = { store.move(playlistId, entryId, toIndex); true }, done = { done(it == true) })

    /** The songs were deleted from the phone (R-50): their entries leave every playlist. [done] says whether it was saved. */
    fun removeSongs(songKeys: Set<SongKey>, done: (Boolean) -> Unit = {}) =
        change(work = { store.removeSongs(songKeys); true }, done = { done(it == true) })

    private var relinkedKeys: Set<SongKey>? = null

    /** After an import: the lists are read again, and entries whose songs sit elsewhere on this phone are linked to them (R-17, R-26). */
    fun importedPlaylists(songs: List<Song>) {
        bump()
        relinkedKeys = null
        relink(songs)
    }

    /**
     * After the library changed: entries whose song was moved or renamed are linked to it again (R-17). It runs only when
     * the set of songs differs from the last time (a new sort order is the same songs), and in turn with the other changes.
     */
    fun relink(songs: List<Song>) {
        val keys = songs.mapTo(HashSet()) { it.key }
        if (keys == relinkedKeys) return
        relinkedKeys = keys
        viewModelScope.launch {
            val changed = writes.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        store.relinkAll(songs)
                    } catch (e: StoreError) {
                        failed(LogCode.PLAYLIST_WRITE_FAILED, e)
                        0
                    }
                }
            }
            if (changed > 0) bump()
        }
    }

    /** A holder, because the work may legitimately produce nothing and [change] needs a non-null result. */
    private class Optional<T>(val value: T?)
}
