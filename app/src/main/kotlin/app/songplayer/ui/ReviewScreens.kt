package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.details.Candidate
import app.songplayer.core.library.Song
import java.text.NumberFormat

/**
 * Review (UX Design 4.10, D-16): one held song at a time with Play so the user can hear it, the best match already selected, and three
 * buttons. The header "Review · 2 of 12" is a polite live region, so TalkBack says it after each decision; focus moves to the first choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    ui: ReviewUi,
    playing: Boolean,
    onPlay: (Song) -> Unit,
    onTogglePlay: () -> Unit,
    onSelect: (Int) -> Unit,
    onUse: () -> Unit,
    onNone: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val format = NumberFormat.getInstance()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (ui) {
                        is ReviewUi.Reviewing -> stringResource(R.string.review_title, format.format(ui.index + 1), format.format(ui.total))
                        else -> stringResource(R.string.review_heading)
                    }
                    Text(title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (ui) {
                ReviewUi.Loading -> Unit
                ReviewUi.Failed -> Message(stringResource(R.string.review_heading), stringResource(R.string.review_failed), stringResource(R.string.review_retry), onRetry)
                ReviewUi.Empty -> Message(stringResource(R.string.review_heading), stringResource(R.string.review_empty), stringResource(R.string.review_done_button), onClose)
                is ReviewUi.Finished -> {
                    val c = ui.counts
                    Message(
                        stringResource(R.string.review_done_title),
                        LocalResources.current.getQuantityString(
                            R.plurals.review_done_body, c.decided, format.format(c.decided), format.format(c.filledIn), format.format(c.rejected), format.format(c.skipped),
                        ),
                        stringResource(R.string.review_done_button),
                        onClose,
                    )
                }
                is ReviewUi.Reviewing -> Reviewing(ui, playing, onPlay, onTogglePlay, onSelect, onUse, onNone, onSkip)
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String, button: String, onButton: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onButton) { Text(button) }
    }
}

@Composable
private fun Reviewing(
    ui: ReviewUi.Reviewing,
    playing: Boolean,
    onPlay: (Song) -> Unit,
    onTogglePlay: () -> Unit,
    onSelect: (Int) -> Unit,
    onUse: () -> Unit,
    onNone: () -> Unit,
    onSkip: () -> Unit,
) {
    val song = ui.item.song
    val firstChoice = remember { FocusRequester() }
    // After each decision the next song starts here: focus goes to its first choice.
    LaunchedEffect(ui.index) { runCatching { firstChoice.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.review_this_song), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(song.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(song.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = stringResource(if (playing) R.string.review_pause else R.string.review_play)
            OutlinedButton(onClick = { if (playing) onTogglePlay() else onPlay(song) }) {
                Icon(if (playing) PlayerIcons.Pause else PlayerIcons.Play, contentDescription = null)
                Text(label)
            }
        }
        val current = listOfNotNull(song.title, song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (current.isNotEmpty()) {
            Text(stringResource(R.string.review_now, current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.review_which), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        ui.item.candidates.forEachIndexed { i, candidate ->
            Choice(
                candidate = candidate,
                selected = i == ui.selected,
                onSelect = { onSelect(i) },
                modifier = if (i == 0) Modifier.focusRequester(firstChoice) else Modifier,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_use)) }
            OutlinedButton(onClick = onNone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_none)) }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_skip)) }
        }
    }
}

/** A radio row: a filled circle and a check mark for the selected one, and the words "Selected" for the screen reader (never colour alone). */
@Composable
private fun Choice(candidate: Candidate, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val artist = candidate.artists.joinToString(", ").ifEmpty { stringResource(R.string.review_choice_no_artist) }
    val album = candidate.albums.firstOrNull { it.type == "Album" }?.title ?: stringResource(R.string.review_choice_no_album)
    val spoken = stringResource(R.string.review_choice_description, candidate.title, artist, album)
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = spoken }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        // The row speaks once, through its description; the selected state comes from the selectable itself. The texts and the mark are not read again.
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.review_choice_line, artist, album), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = null)
    }
}

/** "Not found" (UX Design 4.10, H8): the songs the music database did not know or that could not be read, each with Edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotFoundScreen(songs: List<Song>, onEdit: (Song) -> Unit, onClose: () -> Unit, bottomBar: @Composable () -> Unit = {}) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notfound_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(stringResource(R.string.notfound_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                if (songs.isEmpty()) Text(stringResource(R.string.notfound_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(songs, key = { it.key.value }) { song ->
                val label = stringResource(R.string.notfound_edit_description, song.fileName)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(song.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedButton(onClick = { onEdit(song) }, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.notfound_edit)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
