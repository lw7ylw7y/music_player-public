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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.playback.QueueRow

/**
 * The queue (UX Design 4.6, D5 and D8): what plays now and what comes after, in play order. Songs put there by
 * "Play next" or "Add to queue" are headed "Added to queue", the others "Next from the list". A tap on a row plays
 * it; a swipe, or the three-dot menu, removes it. Removing never touches a playlist (D-14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    rows: List<QueueRow>,
    onClose: () -> Unit,
    onJump: (QueueRow) -> Unit,
    onRemove: (QueueRow) -> Unit,
    failedKeys: Set<String> = emptySet(),
) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.padding(padding).padding(24.dp).fillMaxSize()) {
                Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.queue_empty_body),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> "${row.index}:${row.mediaId}" }) { position, row ->
                    // A heading wherever the kind of row changes: now playing, then runs of listed and added songs.
                    val headingRes = when {
                        row.isCurrent -> R.string.queue_now_playing
                        position > 0 && rows[position - 1].isCurrent -> headingOf(row)
                        position > 0 && rows[position - 1].added != row.added -> headingOf(row)
                        else -> null
                    }
                    if (headingRes != null) {
                        Text(
                            stringResource(headingRes),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (row.isCurrent) {
                        QueueItem(row, onJump = {}, onRemove = onRemove, cantPlay = row.mediaId in failedKeys)
                    } else {
                        val state = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    onRemove(row)
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(state = state, backgroundContent = {}) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                QueueItem(row, onJump = { onJump(row) }, onRemove = onRemove, cantPlay = row.mediaId in failedKeys)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun headingOf(row: QueueRow) = if (row.added) R.string.queue_added else R.string.queue_next_from_list

@Composable
private fun QueueItem(row: QueueRow, onJump: () -> Unit, onRemove: (QueueRow) -> Unit, cantPlay: Boolean) {
    val described = stringResource(R.string.queue_row_description, row.title, row.artist)
    val spoken = when {
        cantPlay -> stringResource(R.string.song_row_cant_play, described)
        row.isCurrent -> stringResource(R.string.queue_row_current, described)
        else -> described
    }
    val coverPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.play), enabled = !row.isCurrent, onClick = onJump)
                .clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(row.mediaStoreId, row.artistTag, coverPx, Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cantPlay) {
                    CantPlayMark()
                } else {
                    Text(
                        row.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.song_more_options, row.title))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_from_queue)) },
                    onClick = {
                        menuOpen = false
                        onRemove(row)
                    },
                )
            }
        }
    }
}
