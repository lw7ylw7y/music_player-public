package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.text.SearchIndex
import app.songplayer.core.text.SearchResults
import kotlinx.coroutines.launch

/**
 * Search (UX Design 4.3, B1 and B2): a field with a clear button, and below it the matching songs, albums and
 * artists, narrowing on every keystroke. Rows do nothing yet: playing comes with B1 and the album and artist
 * pages with C1. Back closes search (the first Back press, while the keyboard is up, only hides the keyboard;
 * that is Android's own behaviour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    index: SearchIndex?,
    onClose: () -> Unit,
    playingKey: String? = null,
    onPlay: (List<Song>, Int) -> Unit = { _, _ -> },
    onPlayNext: ((Song) -> Boolean)? = null,
    onAddToQueue: ((Song) -> Boolean)? = null,
    failedKeys: Set<String> = emptySet(),
    playlists: PlaylistActions? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val addToPlaylist = remember { AddToPlaylistState() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(index, query) { index?.search(query) ?: SearchResults.EMPTY }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val willPlayNext = stringResource(R.string.snackbar_will_play_next)
    val addedToQueue = stringResource(R.string.snackbar_added_to_queue)
    fun tell(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }
    // The message is shown only when the command was sent (there is a connection to the service).
    val playNextAndTell: ((Song) -> Unit)? = onPlayNext?.let { act -> { song -> if (act(song)) tell(willPlayNext) } }
    val addToQueueAndTell: ((Song) -> Unit)? = onAddToQueue?.let { act -> { song -> if (act(song)) tell(addedToQueue) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                // The index is built a moment after the screen opens (or after a rotation); until then say nothing.
                query.isBlank() || index == null -> Unit
                results.isEmpty -> NothingFound(query)
                else -> ResultsList(
                    results, playingKey, onPlay, playNextAndTell, addToQueueAndTell, failedKeys,
                    onAddToPlaylist = playlists?.let { _ -> { song: Song -> addToPlaylist.start(listOf(song)) } },
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                )
            }
        }
    }
    if (playlists != null) AddToPlaylistHost(addToPlaylist, playlists, tell = { tell(it) })
}

@Composable
private fun NothingFound(query: String) {
    // A polite live region, so TalkBack announces the message when it appears after typing.
    Column(Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.nothing_found_title, query.trim()), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.nothing_found_body),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultsList(
    results: SearchResults,
    playingKey: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayNext: ((Song) -> Unit)?,
    onAddToQueue: ((Song) -> Unit)?,
    failedKeys: Set<String>,
    onAddToPlaylist: ((Song) -> Unit)?,
    onOpenAlbum: ((String) -> Unit)?,
    onOpenArtist: ((String) -> Unit)?,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (results.songs.isNotEmpty()) {
            item(key = "songs-header") { GroupHeader(R.string.tab_songs) }
            itemsIndexed(results.songs, key = { _, it -> "song:" + it.song.key.value }) { index, it ->
                SongRow(
                    it.song,
                    playing = it.song.key.value == playingKey,
                    // The Songs group is the queue (R-6).
                    onClick = { onPlay(results.songs.map { s -> s.song }, index) },
                    onPlayNext = onPlayNext?.let { f -> { f(it.song) } },
                    onAddToQueue = onAddToQueue?.let { f -> { f(it.song) } },
                    onAddToPlaylist = onAddToPlaylist?.let { f -> { f(it.song) } },
                    cantPlay = it.song.key.value in failedKeys,
                )
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "albums-header") { GroupHeader(R.string.tab_albums) }
            items(results.albums, key = { "album:" + it.key }) { AlbumRow(it, onOpenAlbum?.let { open -> Modifier.clickable(role = Role.Button) { open(it.key) } } ?: Modifier) }
        }
        if (results.artists.isNotEmpty()) {
            item(key = "artists-header") { GroupHeader(R.string.tab_artists) }
            items(results.artists, key = { "artist:" + it.key }) { ArtistRow(it, onOpenArtist?.let { open -> Modifier.clickable(role = Role.Button) { open(it.key) } } ?: Modifier) }
        }
    }
}

@Composable
private fun GroupHeader(title: Int) {
    Text(
        stringResource(title),
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
