package app.songplayer.ui

import android.content.res.Resources
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.playlist.AddMessage
import app.songplayer.core.playlist.PlaylistView
import java.text.NumberFormat

/** What the screens can do with playlists, handed to them by the root (the screens never see the database). */
class PlaylistActions(
    val state: PlaylistsState,
    val onOpen: (Long) -> Unit,
    val onAdd: (playlistId: Long, songs: List<Song>, done: (AddResult) -> Unit) -> Unit,
    val onCreate: (name: String, songs: List<Song>, done: (CreateResult) -> Unit) -> Unit,
    val onRetry: () -> Unit,
)

/** Which songs are waiting for a playlist to be chosen ("Add to playlist", UX Design E2), and whether the name dialog is up. */
@Stable
class AddToPlaylistState {
    var songs by mutableStateOf<List<Song>?>(null)
        private set
    var naming by mutableStateOf(false)
        private set

    fun start(songs: List<Song>) {
        this.songs = songs
        naming = false
    }

    internal fun askName() {
        naming = true
    }

    internal fun backToChoosing() {
        naming = false
    }

    internal fun close() {
        songs = null
        naming = false
    }
}

/** The words after an add: one song says "Added to X" or "Already in X"; several give a count (UX Design 5). */
internal fun addedText(resources: Resources, playlistName: String, requested: Int, added: Int, alreadyIn: Int): String =
    when (val m = PlaylistView.addMessage(requested, added, alreadyIn)) {
        AddMessage.AddedOne -> resources.getString(R.string.snackbar_added_to, playlistName)
        AddMessage.AlreadyIn -> resources.getString(R.string.snackbar_already_in, playlistName)
        is AddMessage.AddedMany -> {
            val base = resources.getQuantityString(R.plurals.snackbar_added_songs, m.added, NumberFormat.getInstance().format(m.added), playlistName)
            if (m.alreadyIn == 0) base else resources.getString(R.string.snackbar_added_songs_some_already_in, base, NumberFormat.getInstance().format(m.alreadyIn))
        }
    }

/**
 * The "Add to playlist" sheet and its "New playlist" dialog. Choosing a playlist adds the songs and says what happened
 * through [tell]; the sheet shows each playlist with its song count and "New playlist" first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistHost(state: AddToPlaylistState, actions: PlaylistActions, tell: (String) -> Unit, onAdded: () -> Unit = {}) {
    val songs = state.songs ?: return
    val resources = LocalResources.current
    val saveFailed = stringResource(R.string.playlist_save_failed)
    val gone = stringResource(R.string.playlist_gone)

    fun report(playlistName: String, result: AddResult) {
        when (result) {
            is AddResult.Done -> {
                tell(addedText(resources, playlistName, songs.size, result.added, result.alreadyIn))
                onAdded()
            }
            AddResult.PlaylistGone -> tell(gone)
            AddResult.Failed -> tell(saveFailed)
        }
    }

    if (state.naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.dialog_create),
            initial = "",
            onConfirm = { name ->
                state.close()
                actions.onCreate(name, songs) { result ->
                    when (result) {
                        is CreateResult.Created -> {
                            // The playlist exists even when not every song got in; say so rather than claim they did.
                            if (result.added < songs.size) {
                                tell(saveFailed)
                            } else {
                                tell(addedText(resources, result.playlist.name, songs.size, result.added, 0))
                            }
                            onAdded()
                        }
                        CreateResult.Failed -> tell(saveFailed)
                    }
                }
            },
            onDismiss = state::backToChoosing,
        )
        return
    }

    ModalBottomSheet(onDismissRequest = state::close) {
        Text(
            stringResource(R.string.sheet_add_to_playlist_title),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        when (val playlists = actions.state) {
            is PlaylistsState.Ready -> LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    NewPlaylistRow(onClick = state::askName)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(playlists.playlists, key = { it.playlist.id }) { summary ->
                    PlaylistRow(summary, onClick = {
                        state.close()
                        actions.onAdd(summary.playlist.id, songs) { result -> report(summary.playlist.name, result) }
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            is PlaylistsState.Unavailable -> Column(Modifier.padding(bottom = 16.dp)) {
                PlaylistsUnavailable(playlists.damaged, actions.onRetry, Modifier.fillMaxWidth())
            }
            PlaylistsState.Loading -> Unit
        }
    }
}
