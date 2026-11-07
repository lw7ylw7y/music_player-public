package app.songplayer.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.text.SortOrder
import app.songplayer.core.text.SortWords
import app.songplayer.core.text.sortWords
import app.songplayer.library.LibraryTab
import java.text.NumberFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val MESSAGE_MS = 8_000

/**
 * The Library (UX Design 4.2): tabs, sort chip with count, the list with its fast-scroll handle, the progress
 * bar, and the two messages ("N new songs found", and a failed refresh). The ViewModel owns the data; this
 * function owns what only the screen knows (open menus, scroll positions, which message is showing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    ui: LibraryUi,
    onTab: (LibraryTab) -> Unit,
    onSort: (SortOrder) -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onDismissFailure: () -> Unit,
    takeNewSongs: () -> Int,
    onReverse: () -> Unit = {},
    playingKey: String? = null,
    onPlay: (List<Song>, Int) -> Unit = { _, _ -> },
    onPlayNext: ((Song) -> Boolean)? = null,
    onAddToQueue: ((Song) -> Boolean)? = null,
    failedKeys: Set<String> = emptySet(),
    playlists: PlaylistActions? = null,
    onSettings: (() -> Unit)? = null,
    /** "N songs need your review" (Stories H8); null when nothing waits or the user dismissed it. */
    reviewBanner: ReviewBanner? = null,
    /** A splitting job that is not on screen: working, found, written or stopped (UX Design 4.11). */
    splitBanner: SplitBanner? = null,
    /** Open an album or an artist by its group key (UX Design 4.4, C1); null where the screen is shown without them. */
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val latest by rememberUpdatedState(ui)
    val messages = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val songsState = rememberLazyListState()
    val artistsState = rememberLazyListState()
    val albumsState = rememberLazyGridState()
    var menuOpen by remember { mutableStateOf(false) }
    var newPlaylistOpen by rememberSaveable { mutableStateOf(false) }
    // Songs picked by a long-press, by key (UX Design E5). Only the Songs tab selects.
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val addToPlaylist = remember { AddToPlaylistState() }
    val selecting = picked.isNotEmpty()
    val deleteSongs = LocalDeleteSongs.current
    // A refresh may have removed a picked song; the count and the add follow what is still listed.
    LaunchedEffect(ui.lists) {
        if (picked.isNotEmpty()) {
            val listed = ui.lists.songs.mapTo(HashSet()) { it.song.key.value }
            if (!listed.containsAll(picked)) picked = picked.filterTo(HashSet()) { it in listed }
        }
    }
    BackHandler(enabled = selecting) { picked = emptySet() }
    var pullRequested by remember { mutableStateOf(false) }
    // A new sort order rebuilds the lists a moment later; the jump to the top waits for the new lists.
    var topAfter by remember { mutableStateOf<LibraryLists?>(null) }

    suspend fun scrollAllToTop() {
        songsState.scrollToItem(0)
        albumsState.scrollToItem(0)
        artistsState.scrollToItem(0)
    }

    // [force]: "Show" wants Recently added in its natural direction, even when that field is chosen but reversed.
    fun changeSort(order: SortOrder, force: Boolean = false) {
        if (order == latest.sort && !force) return
        topAfter = latest.lists
        onSort(order)
    }

    LaunchedEffect(ui.lists) {
        if (topAfter != null && topAfter !== ui.lists) {
            topAfter = null
            scrollAllToTop()
        }
    }
    LaunchedEffect(ui.syncing) { if (!ui.syncing) pullRequested = false }

    // R-22: the message is shown on the Library only; a count found while another screen is open waits.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val showLabel = stringResource(R.string.new_songs_show)
    LaunchedEffect(ui.generation, resumed) {
        if (!resumed) return@LaunchedEffect
        val count = takeNewSongs()
        if (count == 0) return@LaunchedEffect
        val message = resources.getQuantityString(R.plurals.new_songs_found, count, NumberFormat.getInstance().format(count))
        // Not cancelled when the key changes, so the message survives a second sync finishing.
        scope.launch {
            if (showTimed(context, messages, snackbar, message, showLabel) == SnackbarResult.ActionPerformed) {
                onTab(LibraryTab.Songs)
                if (latest.sort == SortOrder.RecentlyAdded && !latest.reversed) scrollAllToTop() else changeSort(SortOrder.RecentlyAdded, force = true)
            }
        }
    }

    // The song menu's actions say what they did in a short message (UX Design E1).
    val willPlayNext = stringResource(R.string.snackbar_will_play_next)
    val addedToQueue = stringResource(R.string.snackbar_added_to_queue)
    fun tell(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }
    // The message is shown only when the command was sent (there is a connection to the service).
    val playNextAndTell: ((Song) -> Unit)? = onPlayNext?.let { act -> { song -> if (act(song)) tell(willPlayNext) } }
    val addToQueueAndTell: ((Song) -> Unit)? = onAddToQueue?.let { act -> { song -> if (act(song)) tell(addedToQueue) } }

    val playlistSaveFailed = stringResource(R.string.playlist_save_failed)
    fun songsOf(keys: Set<String>) = latest.lists.songs.map { it.song }.filter { it.key.value in keys }

    val failedMessage = stringResource(R.string.refresh_failed)
    val retryLabel = stringResource(R.string.refresh_failed_retry)
    LaunchedEffect(ui.failed) {
        if (!ui.failed) return@LaunchedEffect
        val result = showTimed(context, messages, snackbar, failedMessage, retryLabel)
        onDismissFailure()
        if (result == SnackbarResult.ActionPerformed) onRefresh()
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selection_count, NumberFormat.getInstance().format(picked.size))) },
                    navigationIcon = {
                        IconButton(onClick = { picked = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_close))
                        }
                    },
                    actions = {
                        if (playlists != null) {
                            TextButton(onClick = { addToPlaylist.start(songsOf(picked)) }) {
                                Text(stringResource(R.string.menu_add_to_playlist))
                            }
                        }
                        deleteSongs?.let { delete ->
                            TextButton(onClick = { delete(songsOf(picked)) }) {
                                Text(stringResource(R.string.selection_delete))
                            }
                        }
                    },
                )
            } else {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_open))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh_library)) },
                                onClick = {
                                    menuOpen = false
                                    onRefresh()
                                },
                            )
                            if (onSettings != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    onClick = {
                                        menuOpen = false
                                        onSettings()
                                    },
                                )
                            }
                        }
                    }
                },
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (splitBanner != null && !selecting) SplitBannerRow(splitBanner)
            if (reviewBanner != null && !selecting) ReviewBannerRow(reviewBanner)
            TabRow(selectedTabIndex = ui.tab.ordinal) {
                LibraryTab.entries.forEach { tab ->
                    val selected = tab == ui.tab
                    Tab(
                        selected = selected,
                        onClick = {
                            picked = emptySet()
                            onTab(tab)
                        },
                        text = { Text(tabLabel(tab), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            if (ui.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())

            val songCount = ui.lists.songs.size
            when {
                !ui.loaded -> Unit
                songCount == 0 && ui.syncing -> Text(
                    stringResource(R.string.loading),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                songCount == 0 && ui.syncFailed -> SyncFailedScreen(onRefresh)
                songCount == 0 && ui.generation > 0 -> EmptyLibraryScreen(onRefresh)
                songCount == 0 -> Unit
                else -> {
                    SortRow(ui, (playlists?.state as? PlaylistsState.Ready)?.playlists?.size, ::changeSort, onReverse)
                    PullToRefreshBox(
                        isRefreshing = pullRequested && ui.syncing,
                        onRefresh = {
                            pullRequested = true
                            onRefresh()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (ui.tab) {
                            LibraryTab.Songs -> SongsList(
                                ui.lists, songsState, playingKey, onPlay, playNextAndTell, addToQueueAndTell, failedKeys,
                                selected = picked,
                                onSelect = { song -> picked = if (song.key.value in picked) picked - song.key.value else picked + song.key.value },
                                onAddToPlaylist = playlists?.let { _ -> { song: Song -> addToPlaylist.start(listOf(song)) } },
                            )
                            LibraryTab.Albums -> AlbumsGrid(ui.lists, albumsState, onOpenAlbum)
                            LibraryTab.Artists -> ArtistsList(ui.lists, artistsState, onOpenArtist)
                            LibraryTab.Playlists -> PlaylistsTab(
                                playlists?.state ?: PlaylistsState.Loading,
                                onOpen = { playlists?.onOpen?.invoke(it) },
                                onNew = { newPlaylistOpen = true },
                                onRetry = { playlists?.onRetry?.invoke() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (newPlaylistOpen) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.dialog_create),
            initial = "",
            onConfirm = { name ->
                newPlaylistOpen = false
                playlists?.onCreate?.invoke(name, emptyList()) { result -> if (result is CreateResult.Failed) tell(playlistSaveFailed) }
            },
            onDismiss = { newPlaylistOpen = false },
        )
    }
    if (playlists != null) AddToPlaylistHost(addToPlaylist, playlists, tell = { tell(it) }, onAdded = { picked = emptySet() })
}

/**
 * Shows a message that goes away after 8 seconds, or after the phone's own "time to take action" if that is longer.
 * Messages take turns through [turn], so the clock of one starts only when it is really on screen.
 */
internal suspend fun showTimed(context: Context, turn: Mutex, host: SnackbarHostState, message: String, action: String): SnackbarResult? = turn.withLock {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    val timeout = manager?.getRecommendedTimeoutMillis(
        MESSAGE_MS,
        AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
    ) ?: MESSAGE_MS
    withTimeoutOrNull(timeout.toLong()) {
        host.showSnackbar(message, actionLabel = action.ifEmpty { null }, duration = SnackbarDuration.Indefinite)
    }
}

@Composable
private fun tabLabel(tab: LibraryTab) = stringResource(
    when (tab) {
        LibraryTab.Songs -> R.string.tab_songs
        LibraryTab.Albums -> R.string.tab_albums
        LibraryTab.Artists -> R.string.tab_artists
        LibraryTab.Playlists -> R.string.tab_playlists
    },
)

@Composable
private fun sortWordsText(words: SortWords) = stringResource(
    when (words) {
        SortWords.AToZ -> R.string.sort_a_to_z
        SortWords.ZToA -> R.string.sort_z_to_a
        SortWords.NewestFirst -> R.string.sort_newest_first
        SortWords.OldestFirst -> R.string.sort_oldest_first
    },
)

@Composable
private fun sortName(order: SortOrder) = stringResource(
    when (order) {
        SortOrder.Title -> R.string.sort_title
        SortOrder.Artist -> R.string.sort_artist
        SortOrder.RecentlyAdded -> R.string.sort_recently_added
    },
)

/** The sort chip (Songs and Albums only; Artists are always A to Z) and the count of what is listed. */
@Composable
private fun SortRow(ui: LibraryUi, playlistCount: Int?, onSort: (SortOrder) -> Unit, onReverse: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val count = when (ui.tab) {
        LibraryTab.Songs -> countText(R.plurals.song_count, ui.lists.songs.size)
        LibraryTab.Albums -> countText(R.plurals.album_count, ui.lists.albums.size)
        LibraryTab.Artists -> countText(R.plurals.artist_count, ui.lists.artists.size)
        LibraryTab.Playlists -> if (playlistCount == null) "" else countText(R.plurals.playlist_count, playlistCount)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.tab == LibraryTab.Songs || ui.tab == LibraryTab.Albums) {
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text(stringResource(R.string.sort_label, sortName(ui.sort))) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(sortName(order), fontWeight = if (order == ui.sort) FontWeight.Bold else FontWeight.Normal)
                            },
                            // The check mark and the bold text mark the chosen order; colour is not needed.
                            leadingIcon = if (order == ui.sort) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                open = false
                                onSort(order)
                            },
                        )
                    }
                }
            }
            val words = sortWords(ui.sort, ui.reversed)
            val up = words == SortWords.AToZ || words == SortWords.OldestFirst
            // The arrow is the picture; TalkBack gets the words (the arrow alone would carry the meaning by shape only).
            IconButton(onClick = onReverse) {
                Icon(if (up) PlayerIcons.ArrowUp else PlayerIcons.ArrowDown, contentDescription = sortWordsText(words))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongsList(
    lists: LibraryLists,
    state: LazyListState,
    playingKey: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayNext: ((Song) -> Unit)?,
    onAddToQueue: ((Song) -> Unit)?,
    failedKeys: Set<String>,
    selected: Set<String>,
    onSelect: (Song) -> Unit,
    onAddToPlaylist: ((Song) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val selecting = selected.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lists.songs, key = { _, it -> it.song.key.value }) { index, it ->
                SongRow(
                    it.song,
                    playing = it.song.key.value == playingKey,
                    // While songs are selected a tap picks or drops the row instead of playing it.
                    onClick = if (selecting) ({ onSelect(it.song) }) else ({ onPlay(lists.songs.map { s -> s.song }, index) }),
                    onLongClick = { onSelect(it.song) },
                    selected = it.song.key.value in selected,
                    onPlayNext = if (selecting) null else onPlayNext?.let { f -> { f(it.song) } },
                    onAddToQueue = if (selecting) null else onAddToQueue?.let { f -> { f(it.song) } },
                    onAddToPlaylist = if (selecting) null else onAddToPlaylist?.let { f -> { f(it.song) } },
                    cantPlay = it.song.key.value in failedKeys,
                )
            }
        }
        FastScroller(
            sections = lists.songSections,
            itemCount = lists.songs.size,
            firstVisibleIndex = { state.firstVisibleItemIndex },
            isScrolling = state.isScrollInProgress,
            onJump = { scope.launch { state.scrollToItem(it) } },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun ArtistsList(lists: LibraryLists, state: LazyListState, onOpenArtist: ((String) -> Unit)?) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(lists.artists, key = { it.key }) {
                ArtistRow(it, onOpenArtist?.let { open -> Modifier.clickable(role = Role.Button) { open(it.key) } } ?: Modifier)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        FastScroller(
            sections = lists.artistSections,
            itemCount = lists.artists.size,
            firstVisibleIndex = { state.firstVisibleItemIndex },
            isScrolling = state.isScrollInProgress,
            onJump = { scope.launch { state.scrollToItem(it) } },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun AlbumsGrid(lists: LibraryLists, state: LazyGridState, onOpenAlbum: ((String) -> Unit)?) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(lists.albums, key = { it.key }) { AlbumTile(it, onOpenAlbum?.let { open -> Modifier.clickable(role = Role.Button) { open(it.key) } } ?: Modifier) }
        }
        FastScroller(
            sections = lists.albumSections,
            itemCount = lists.albums.size,
            firstVisibleIndex = { state.firstVisibleItemIndex },
            isScrolling = state.isScrollInProgress,
            onJump = { scope.launch { state.scrollToItem(it) } },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** The one-line notice above the tabs when songs wait for the user's decision (UX Design 4.10, H5). */
data class ReviewBanner(val count: Int, val onReview: () -> Unit, val onDismiss: () -> Unit)

@Composable
internal fun ReviewBannerRow(banner: ReviewBanner) {
    val resources = LocalResources.current
    val text = resources.getQuantityString(R.plurals.review_banner, banner.count, NumberFormat.getInstance().format(banner.count))
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = banner.onReview) { Text(stringResource(R.string.review_banner_action)) }
            IconButton(onClick = banner.onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.review_banner_dismiss)) }
        }
    }
}

/** The notice above the tabs for a splitting job that goes on behind the Library; [onOpen] brings its screen back, [onDismiss] (results only) leaves the flow. */
class SplitBanner(val state: SplitBannerUi, val onOpen: () -> Unit, val onDismiss: () -> Unit)

@Composable
internal fun SplitBannerRow(banner: SplitBanner) {
    val (text, action) = when (val s = banner.state) {
        is SplitBannerUi.Finding -> stringResource(R.string.split_banner_finding, s.permille / 10) to stringResource(R.string.split_banner_open)
        is SplitBannerUi.Writing -> stringResource(R.string.split_banner_writing, minOf(s.written + 1, s.total), s.total) to stringResource(R.string.split_banner_open)
        is SplitBannerUi.Found -> stringResource(R.string.split_banner_found, s.songs) to stringResource(R.string.split_banner_check)
        is SplitBannerUi.Done -> stringResource(R.string.split_banner_done, s.songs) to stringResource(R.string.split_banner_open)
        is SplitBannerUi.Stopped -> stringResource(R.string.split_banner_stopped, s.kept) to stringResource(R.string.split_banner_open)
    }
    // Only "songs found" can be closed here: a result of the writing opens K6, which holds the questions about the original and the songs.
    val closable = banner.state is SplitBannerUi.Found
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // A screen reader hears a result when it arrives, but not every percent of the progress.
            val working = banner.state is SplitBannerUi.Finding || banner.state is SplitBannerUi.Writing
            Text(
                text,
                modifier = Modifier.weight(1f).semantics { if (!working) liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = banner.onOpen) { Text(action) }
            if (closable) IconButton(onClick = banner.onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.split_banner_dismiss)) }
        }
    }
}
