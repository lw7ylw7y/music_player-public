package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.playlist.PlaylistView
import java.util.Random
import kotlinx.coroutines.launch

/**
 * One album or one artist (UX Design 4.4, C1): its songs in title order, with Play and Shuffle and the song menu of the Library. Like a
 * playlist, Play copies the list into the queue, and Shuffle also turns shuffle on; nothing here changes the album or the artist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    title: String,
    subtitle: String,
    songs: List<Song>,
    onClose: () -> Unit,
    onPlay: (queue: List<Song>, index: Int) -> Unit,
    onShuffle: (queue: List<Song>, index: Int) -> Unit,
    playingKey: String? = null,
    failedKeys: Set<String> = emptySet(),
    onPlayNext: ((Song) -> Boolean)? = null,
    onAddToQueue: ((Song) -> Boolean)? = null,
    playlists: PlaylistActions? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val addToPlaylist = remember { AddToPlaylistState() }
    val willPlayNext = stringResource(R.string.snackbar_will_play_next)
    val addedToQueue = stringResource(R.string.snackbar_added_to_queue)
    fun tell(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }
    val minutes = (songs.sumOf { it.durationMs } / 60_000).coerceAtLeast(if (songs.isEmpty()) 0 else 1)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (subtitle.isBlank()) {
                                stringResource(R.string.playlist_header, countText(R.plurals.song_count, songs.size), lengthText(minutes))
                            } else {
                                stringResource(R.string.group_header, subtitle, countText(R.plurals.song_count, songs.size), lengthText(minutes))
                            },
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlay(songs, 0) }, enabled = songs.isNotEmpty()) {
                                Icon(PlayerIcons.Play, contentDescription = null, Modifier.size(18.dp))
                                Text(stringResource(R.string.play), Modifier.padding(start = 8.dp))
                            }
                            OutlinedButton(
                                onClick = { PlaylistView.shuffleStart(songs.size, Random())?.let { onShuffle(songs, it) } },
                                enabled = songs.isNotEmpty(),
                            ) {
                                Icon(PlayerIcons.Shuffle, contentDescription = null, Modifier.size(18.dp))
                                Text(stringResource(R.string.shuffle_button), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                itemsIndexed(songs, key = { _, it -> it.key.value }) { index, song ->
                    SongRow(
                        song,
                        playing = song.key.value == playingKey,
                        onClick = { onPlay(songs, index) },
                        onPlayNext = onPlayNext?.let { act -> { if (act(song)) tell(willPlayNext) } },
                        onAddToQueue = onAddToQueue?.let { act -> { if (act(song)) tell(addedToQueue) } },
                        onAddToPlaylist = playlists?.let { _ -> { addToPlaylist.start(listOf(song)) } },
                        cantPlay = song.key.value in failedKeys,
                    )
                }
            }
        }
    }
    if (playlists != null) AddToPlaylistHost(addToPlaylist, playlists, tell = { tell(it) })
}
