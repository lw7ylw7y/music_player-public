package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.playlist.PlaylistEntry
import app.songplayer.core.playlist.PlaylistItem
import app.songplayer.core.playlist.PlaylistRules
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.playlist.PlaylistView
import app.songplayer.core.playlist.RemovedEntry
import java.util.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** The music note on a playlist's flat square, like a song without a cover (UX Design A6). A symbol, not text to translate. */
private const val NOTE_GLYPH = "♪"

/**
 * The Playlists tab (UX Design A8): "New playlist" first, then one row per playlist. When the playlists cannot be
 * read the tab says so and the other tabs go on working (R-10, failure table of Technical Design 11).
 */
@Composable
fun PlaylistsTab(state: PlaylistsState, onOpen: (Long) -> Unit, onNew: () -> Unit, onRetry: () -> Unit) {
    when (state) {
        PlaylistsState.Loading -> Unit
        is PlaylistsState.Unavailable -> PlaylistsUnavailable(state.damaged, onRetry, Modifier.fillMaxSize())
        is PlaylistsState.Ready -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                NewPlaylistRow(onNew)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.playlists_none_hint),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.playlists, key = { it.playlist.id }) {
                PlaylistRow(it, onClick = { onOpen(it.playlist.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun NewPlaylistRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, contentDescription = null) }
        Text(stringResource(R.string.playlist_new), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PlaylistRow(summary: PlaylistSummary, onClick: () -> Unit) {
    val count = countText(R.plurals.song_count, summary.songCount)
    val spoken = stringResource(R.string.playlist_row_description, summary.playlist.name, count)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(NOTE_GLYPH, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f)) {
            Text(summary.playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The playlists could not be read: a damaged file has no retry (it is never deleted, and trying again changes nothing); any other reason does. */
@Composable
fun PlaylistsUnavailable(damaged: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (damaged) R.string.playlists_damaged_title else R.string.playlists_read_failed_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(if (damaged) R.string.playlists_damaged_body else R.string.playlists_read_failed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!damaged) {
            Button(onClick = onRetry, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.playlist_try_again)) }
        }
    }
}

/** The name dialog of New playlist and Rename (UX Design E3): Create or Rename stays disabled while the name is empty or too long. */
@Composable
fun PlaylistNameDialog(title: String, confirmLabel: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = PlaylistRules.cleanName(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(PlaylistRules.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.dialog_name_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/**
 * One playlist (UX Design 4.4, C2 and C4): its name, Play and Shuffle, and its songs in order. A song that has left the
 * library stays in the list marked "Can't play" and can be removed. Play copies the list into the queue; nothing here
 * changes the queue afterwards, and nothing a queue does changes the playlist (D-14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    state: DetailState,
    onClose: () -> Unit,
    onPlay: (queue: List<Song>, index: Int) -> Unit,
    onShuffle: (queue: List<Song>, index: Int) -> Unit,
    onPlayNext: ((Song) -> Boolean)?,
    onAddToQueue: ((Song) -> Boolean)?,
    onRename: (String, (Boolean) -> Unit) -> Unit,
    onDelete: ((Boolean) -> Unit) -> Unit,
    onRemove: (Long, (saved: Boolean, removed: RemovedEntry?) -> Unit) -> Unit,
    onUndo: (RemovedEntry, (Boolean) -> Unit) -> Unit,
    onMove: (entryId: Long, toIndex: Int, done: (Boolean) -> Unit) -> Unit,
    onRetry: () -> Unit,
    playingKey: String? = null,
    failedKeys: Set<String> = emptySet(),
    bottomBar: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    var editing by rememberSaveable { mutableStateOf(false) }
    // Registered after the one above, so it wins while the order is being edited: Back leaves Edit order first.
    BackHandler(enabled = editing) { editing = false }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val messages = remember { Mutex() }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    val saveFailed = stringResource(R.string.playlist_save_failed)
    val removedFormat = stringResource(R.string.snackbar_removed)
    val undoLabel = stringResource(R.string.snackbar_undo)
    val willPlayNext = stringResource(R.string.snackbar_will_play_next)
    val addedToQueue = stringResource(R.string.snackbar_added_to_queue)
    fun tell(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }

    val ready = state as? DetailState.Ready
    // Edit order needs two songs; if removing leaves one, the screen leaves it (and the top bar agrees with the body).
    val editingNow = editing && (ready?.items?.size ?: 0) > 1
    LaunchedEffect(editingNow, ready != null) { if (editing && !editingNow && ready != null) editing = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = ready?.playlist?.name.orEmpty()
                    Text(if (editingNow) stringResource(R.string.edit_order_title, name) else name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (ready != null && editingNow) {
                        TextButton(onClick = { editing = false }) { Text(stringResource(R.string.edit_done)) }
                    } else if (ready != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (ready.items.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_edit_order)) },
                                        onClick = {
                                            menuOpen = false
                                            editing = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_rename_playlist)) },
                                    onClick = {
                                        menuOpen = false
                                        renaming = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_delete_playlist)) },
                                    onClick = {
                                        menuOpen = false
                                        deleting = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                DetailState.Closed, DetailState.Loading -> Unit
                DetailState.Gone -> Text(stringResource(R.string.playlist_gone), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                is DetailState.Unavailable -> PlaylistsUnavailable(state.damaged, onRetry, Modifier.fillMaxSize())
                is DetailState.Ready -> PlaylistBody(
                    items = state.items,
                    editing = editingNow,
                    onMove = { id, to -> onMove(id, to) { ok -> if (!ok) tell(saveFailed) } },
                    playingKey = playingKey,
                    failedKeys = failedKeys,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onPlayNext = onPlayNext?.let { act -> { song -> if (act(song)) tell(willPlayNext) } },
                    onAddToQueue = onAddToQueue?.let { act -> { song -> if (act(song)) tell(addedToQueue) } },
                    onRemove = { item ->
                        val title = item.song?.displayTitle ?: item.entry.fileName
                        onRemove(item.entry.id) { saved, removed ->
                            when {
                                !saved -> tell(saveFailed)
                                removed == null -> Unit
                                else -> scope.launch {
                                    val result = showTimed(context, messages, snackbar, removedFormat.format(title), undoLabel)
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        onUndo(removed) { ok -> if (!ok) tell(saveFailed) }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    if (renaming && ready != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.dialog_rename_title),
            confirmLabel = stringResource(R.string.dialog_rename_action),
            initial = ready.playlist.name,
            onConfirm = { name ->
                renaming = false
                onRename(name) { ok -> if (!ok) tell(saveFailed) }
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting && ready != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.dialog_delete_title, ready.playlist.name)) },
            text = { Text(stringResource(R.string.dialog_delete_body)) },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete { ok -> if (ok) onClose() else tell(saveFailed) }
                }) { Text(stringResource(R.string.dialog_delete_action)) }
            },
        )
    }
}

/** "48 min", or "1 h 42 min" from an hour up (UX Design 5). */
@Composable
internal fun lengthText(minutes: Long): String {
    val number = java.text.NumberFormat.getInstance()
    return if (minutes >= 60) {
        stringResource(R.string.playlist_hours_minutes, number.format(minutes / 60), number.format(minutes % 60))
    } else {
        stringResource(R.string.playlist_minutes, number.format(minutes))
    }
}

@Composable
private fun PlaylistBody(
    items: List<PlaylistItem>,
    editing: Boolean,
    onMove: (Long, Int) -> Unit,
    playingKey: String?,
    failedKeys: Set<String>,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>, Int) -> Unit,
    onPlayNext: ((Song) -> Unit)?,
    onAddToQueue: ((Song) -> Unit)?,
    onRemove: (PlaylistItem) -> Unit,
) {
    // The queue is copied when a button or a row is tapped, from the list as it is on screen at that moment.
    val queue = remember(items) { PlaylistView.queue(items) }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.playlist_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.playlist_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (editing) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, it -> it.entry.id }) { index, item ->
                EditRow(item, index, items.size, onMove, onRemove = { onRemove(item) })
            }
        }
        return
    }
    val minutes = (PlaylistView.playableDurationMs(items) / 60_000).coerceAtLeast(if (queue.isEmpty()) 0 else 1)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.playlist_header,
                        countText(R.plurals.song_count, items.size),
                        lengthText(minutes),
                    ),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(queue, 0) }, enabled = queue.isNotEmpty()) {
                        Icon(PlayerIcons.Play, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.play), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { PlaylistView.shuffleStart(queue.size, Random())?.let { onShuffle(queue, it) } },
                        enabled = queue.isNotEmpty(),
                    ) {
                        Icon(PlayerIcons.Shuffle, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.shuffle_button), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        items(items, key = { it.entry.id }) { item ->
            val song = item.song
            if (song != null) {
                SongRow(
                    song,
                    playing = song.key.value == playingKey,
                    onClick = { PlaylistView.queueIndexOf(items, item.entry.id)?.let { onPlay(queue, it) } },
                    onPlayNext = onPlayNext?.let { f -> { f(song) } },
                    onAddToQueue = onAddToQueue?.let { f -> { f(song) } },
                    onRemoveFromPlaylist = { onRemove(item) },
                    cantPlay = song.key.value in failedKeys,
                )
            } else {
                MissingSongRow(item.entry, onRemove = { onRemove(item) })
            }
        }
    }
}

/** An entry whose song the library does not have now (deleted, moved, or on a phone it was restored from): kept, marked "Can't play", removable (R-12, R-17). */
@Composable
private fun MissingSongRow(entry: PlaylistEntry, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val spoken = stringResource(R.string.song_row_cant_play, entry.fileName)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
            Text(entry.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CantPlayMark()
        }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.song_more_options, entry.fileName))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_remove_from_playlist)) },
                    onClick = {
                        open = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * A row in Edit order (UX Design C3 and C5): a drag handle, the song, and a menu with Move up, Move down, Move to top
 * and Remove from playlist. Dragging the handle moves the row one place for every row-height of travel, saved at once;
 * the menu and the handle's accessibility actions do the same without dragging (D-3).
 */
@Composable
private fun LazyItemScope.EditRow(item: PlaylistItem, index: Int, count: Int, onMove: (Long, Int) -> Unit, onRemove: () -> Unit) {
    val title = item.song?.displayTitle ?: item.entry.fileName
    val id = item.entry.id
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    var rowPx by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val up = stringResource(R.string.move_song_up)
    val down = stringResource(R.string.move_song_down)
    val reorder = stringResource(R.string.reorder_handle)
    Row(
        Modifier.animateItem().fillMaxWidth().heightIn(min = 64.dp).onSizeChanged { rowPx = it.height }
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp)
                .semantics {
                    contentDescription = reorder
                    customActions = listOfNotNull(
                        if (index > 0) CustomAccessibilityAction(up) { onMove(id, index - 1); true } else null,
                        if (index < count - 1) CustomAccessibilityAction(down) { onMove(id, index + 1); true } else null,
                    )
                }
                .pointerInput(id) {
                    var travelled = 0f
                    var place = 0
                    detectDragGestures(
                        onDragStart = {
                            travelled = 0f
                            place = latestIndex
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            travelled += drag.y
                            val steps = PlaylistView.dragSteps(travelled, rowPx.toFloat())
                            if (steps != 0) {
                                // The list is read again a moment after each move, so the place is followed here.
                                val target = PlaylistView.moveTarget(place, steps, latestCount)
                                if (target != place) {
                                    onMove(id, target)
                                    place = target
                                }
                                travelled -= steps * rowPx
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Menu, contentDescription = null) }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val song = item.song
            if (song == null) {
                CantPlayMark()
            } else {
                Text(
                    song.displayArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.song_more_options, title))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        onClick = {
                            menuOpen = false
                            onMove(id, index - 1)
                        },
                    )
                }
                if (index < count - 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        onClick = {
                            menuOpen = false
                            onMove(id, index + 1)
                        },
                    )
                }
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_top)) },
                        onClick = {
                            menuOpen = false
                            onMove(id, 0)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_remove_from_playlist)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}
