package app.songplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.songplayer.AppContainer
import app.songplayer.core.errors.AppError
import app.songplayer.core.library.SyncResult
import app.songplayer.core.log.LogCode
import app.songplayer.core.text.AlbumGroup
import app.songplayer.core.text.Albums
import app.songplayer.core.text.ArtistGroup
import app.songplayer.core.text.Artists
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SearchIndex
import app.songplayer.core.text.Section
import app.songplayer.core.text.Sections
import app.songplayer.core.text.SortOrder
import app.songplayer.core.text.TextKeyer
import app.songplayer.core.text.sorted
import app.songplayer.core.text.sortedByName
import app.songplayer.library.LibraryPrefs
import app.songplayer.library.LibraryTab
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The three lists of the Library, already sorted, with the fast-scroll sections of each. */
class LibraryLists(
    val songs: List<IndexedSong>,
    val songSections: List<Section>,
    val albums: List<AlbumGroup>,
    val albumSections: List<Section>,
    val artists: List<ArtistGroup>,
    val artistSections: List<Section>,
) {
    companion object {
        val Empty = LibraryLists(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /** Sorts the songs, groups albums and artists, and finds the fast-scroll sections of each list. */
        fun build(
            songs: List<IndexedSong>,
            order: SortOrder,
            keyer: TextKeyer,
            zone: ZoneId,
            locale: Locale,
            reversed: Boolean = false,
        ): LibraryLists {
            val sortedSongs = songs.sorted(order, reversed)
            val albums = Albums.group(songs, keyer).sorted(order, reversed)
            val artists = Artists.group(songs, keyer).sortedByName()
            val songLabels = when (order) {
                SortOrder.RecentlyAdded -> Sections.months(sortedSongs.map { it.song.dateAddedSec }, zone, locale)
                SortOrder.Artist -> Sections.letters(sortedSongs.map { it.keys.sortArtist })
                SortOrder.Title -> Sections.letters(sortedSongs.map { it.keys.sortTitle })
            }
            val albumLabels = when (order) {
                SortOrder.RecentlyAdded -> Sections.months(albums.map { it.addedSec }, zone, locale)
                SortOrder.Artist -> Sections.letters(albums.map { it.sortArtist })
                SortOrder.Title -> Sections.letters(albums.map { it.sortName })
            }
            return LibraryLists(
                sortedSongs, Sections.of(songLabels),
                albums, Sections.of(albumLabels),
                artists, Sections.of(Sections.letters(artists.map { it.sortName })),
            )
        }
    }
}

data class LibraryUi(
    /** False until the app's saved copy of the library has been read once. */
    val loaded: Boolean = false,
    val tab: LibraryTab = LibraryTab.Songs,
    val sort: SortOrder = SortOrder.Title,
    /** True when the sort runs against its natural direction (UX Design 4.2). */
    val reversed: Boolean = false,
    val lists: LibraryLists = LibraryLists.Empty,
    val syncing: Boolean = false,
    /** Bumped every time the lists were rebuilt from a finished sync; the screen uses it to look for new songs. */
    val generation: Int = 0,
    /** True after a failed sync or read, until the message is dismissed. */
    val failed: Boolean = false,
    /** True while the latest finished sync failed; with an empty list the Library shows a retry screen. */
    val syncFailed: Boolean = false,
)

private data class SortState(val loaded: Boolean, val tab: LibraryTab, val sort: SortOrder, val reversed: Boolean)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = LibraryPrefs(container.database.settingDao())
    private val all = MutableStateFlow<List<IndexedSong>?>(null)
    private val tab = MutableStateFlow(LibraryTab.Songs)
    private val sort = MutableStateFlow(SortOrder.Title)
    private val reversed = MutableStateFlow(false)
    private val generation = MutableStateFlow(0)
    private val failed = MutableStateFlow(false)
    private val syncFailed = MutableStateFlow(false)
    private val _accessAsked = MutableStateFlow<Boolean?>(null)

    /** Whether Android's music-access dialog was shown before; null until the setting has been read. */
    val accessAsked: StateFlow<Boolean?> = _accessAsked

    private val _notificationAsked = MutableStateFlow<Boolean?>(null)

    /** Whether the notification question was shown before; null until the setting has been read. */
    val notificationAsked: StateFlow<Boolean?> = _notificationAsked

    private val lists: Flow<LibraryLists> = combine(all, sort, reversed) { songs, order, back ->
        if (songs == null) LibraryLists.Empty else LibraryLists.build(songs, order, container.keyer, ZoneId.systemDefault(), Locale.getDefault(), back)
    }.flowOn(Dispatchers.Default)

    /** The search index, built from the saved library while a screen collects it (that is, while searching). */
    val searchIndex: StateFlow<SearchIndex?> = all
        .map { songs -> songs?.let { SearchIndex.build(it, container.keyer) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The keys of the songs marked "Can't play" (R-12), kept up to date for every list that shows the mark. */
    val failedKeys: StateFlow<Set<String>> = container.songStore.failedKeysFlow()
        .catch {
            // A mark that cannot be read is only a missing mark; the lists still work.
            container.log.event(LogCode.PLAY_FAILURE_READ_FAILED)
            emit(emptySet())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val state: StateFlow<LibraryUi> = combine(
        combine(all, tab, sort, reversed) { songs, t, s, r -> SortState(songs != null, t, s, r) },
        lists,
        container.library.syncing,
        combine(generation, failed, syncFailed) { g, f, sf -> Triple(g, f, sf) },
    ) { v, l, syncing, (g, f, sf) ->
        LibraryUi(v.loaded, v.tab, v.sort, v.reversed, l, syncing, g, f, sf)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tab.value = prefs.tab()
                sort.value = prefs.sort()
                reversed.value = prefs.sortReversed()
                _accessAsked.value = prefs.accessAsked()
                _notificationAsked.value = prefs.notificationAsked()
            } catch (e: RuntimeException) {
                container.log.event(LogCode.SETTING_READ_FAILED)
                _accessAsked.value = _accessAsked.value ?: false
                _notificationAsked.value = _notificationAsked.value ?: true
            }
            // A change to a song's details (a hand edit, an Undo, a written file) reads the songs again.
            launch { container.detailsStore.version.drop(1).collect { reload() } }
            reload()
            container.library.lastResult.collect { result ->
                when (result) {
                    null -> Unit
                    is SyncResult.Success -> {
                        // Always read again: the sync may also have rewritten the sort keys without changing any song.
                        reload()
                        syncFailed.value = false
                        generation.value += 1
                    }
                    is SyncResult.Failure -> {
                        failed.value = true
                        syncFailed.value = true
                    }
                }
            }
        }
    }

    private fun reload() {
        try {
            all.value = container.songStore.indexed()
        } catch (e: AppError) {
            failed.value = true
            all.value = all.value ?: emptyList()
        }
    }

    fun selectTab(value: LibraryTab) {
        tab.value = value
        save { prefs.saveTab(value) }
    }

    /** Another field starts in its own natural direction (UX Design 4.2). */
    fun selectSort(value: SortOrder) {
        // The direction first: the lists are rebuilt on every change, and the other order would show the new field reversed for a moment.
        reversed.value = false
        sort.value = value
        save {
            // The direction first: a stop between the two writes then leaves the old field in its natural direction.
            prefs.saveSortReversed(false)
            prefs.saveSort(value)
        }
    }

    fun reverseSort() {
        val now = !reversed.value
        reversed.value = now
        save { prefs.saveSortReversed(now) }
    }

    fun markAccessAsked() {
        _accessAsked.value = true
        save { prefs.saveAccessAsked() }
    }

    fun markNotificationAsked() {
        _notificationAsked.value = true
        save { prefs.saveNotificationAsked() }
    }

    fun dismissFailure() {
        failed.value = false
    }

    fun requestSync() = container.library.requestSync()

    /** Takes the count of songs found since the message was last shown (R-22). */
    fun takeNewSongs(): Int = container.newSongs.take()

    private fun save(write: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                write()
            } catch (e: RuntimeException) {
                // A view setting that is not saved is only forgotten at the next start; nothing to tell the user.
                container.log.event(LogCode.SETTING_WRITE_FAILED)
            }
        }
    }
}
