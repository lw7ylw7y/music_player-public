package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.split.FoundPrimary
import app.songplayer.core.split.FoundRow
import app.songplayer.core.split.FoundState
import app.songplayer.core.split.SongsFound
import app.songplayer.core.split.SplitLimits
import app.songplayer.core.split.SplitTime

/** The messages and questions of the "Split into songs" flow that sit over the screen underneath (UX Design 3). */
@Composable
fun SplitDialogs(ui: SplitUi, onDismiss: () -> Unit, onUseFolder: () -> Unit, onContinue: () -> Unit, onStartOver: () -> Unit) {
    when (ui) {
        is SplitUi.Message -> {
            val (title, body) = when (val m = ui.message) {
                SplitMessage.Unreadable -> stringResource(R.string.split_unreadable_title) to stringResource(R.string.split_unreadable_body)
                SplitMessage.TooLarge -> stringResource(R.string.split_too_large_title) to stringResource(R.string.split_too_large_body)
                is SplitMessage.TooManySongs -> stringResource(R.string.split_too_many_title) to stringResource(R.string.split_too_many_body, m.found, SplitLimits.MAX_SONGS)
                SplitMessage.Gone -> stringResource(R.string.split_gone_title) to stringResource(R.string.split_gone_body)
                is SplitMessage.NameInUse -> stringResource(R.string.split_name_in_use_title) to stringResource(R.string.split_name_in_use_body, m.name)
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = { Text(body) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) } },
            )
        }
        is SplitUi.AskFolder -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.split_folder_title)) },
            text = { Text(stringResource(R.string.split_folder_body, ui.suggested.trimEnd('/').replace("/", " › "))) },
            confirmButton = { TextButton(onClick = onUseFolder) { Text(stringResource(R.string.split_folder_use)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        )
        is SplitUi.AskContinue -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.split_continue_title)) },
            text = { Text(stringResource(R.string.split_continue_body, ui.written, ui.total)) },
            confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.split_continue)) } },
            dismissButton = { TextButton(onClick = onStartOver) { Text(stringResource(R.string.split_start_over)) } },
        )
        else -> Unit
    }
}

/** K1, "Finding the songs": the file name, a progress bar with the percentage in words, "Keep going in the background" (also Back) and Cancel (UX Design 4.11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindingScreen(song: Song, permille: Int, onCancel: () -> Unit, onBackground: () -> Unit = {}, bottomBar: @Composable () -> Unit = {}) {
    BackHandler(onBack = onBackground)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.split_title)) }) }, bottomBar = bottomBar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(song.fileName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.split_finding), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { permille / 1000f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.split_progress, permille / 10), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onBackground, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.split_in_background)) }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
        }
    }
}

/**
 * K2, "The songs found" (UX Design 4.11): every proposed song with its start, end, length and a state in words and an icon, then the primary
 * action. [onPrimary] is null until the checking and writing screens exist; the button is then off. Leaving asks "Leave without splitting?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsFoundScreen(
    song: Song,
    found: SongsFound,
    addCutFailed: Boolean,
    onAddCut: (String) -> Unit,
    onClearAddCutError: () -> Unit,
    onLeave: () -> Unit,
    onOpenCut: ((Int) -> Unit)? = null,
    onPrimary: (() -> Unit)? = null,
    /** "Split into N songs" while some cuts still need a look: they are used as found and K4 says how many (UX Design D-4). */
    onSplitAsFound: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    BackHandler { asking = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_title)) },
                navigationIcon = { IconButton(onClick = { asking = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.split_found_header, song.fileName, found.songCount), style = MaterialTheme.typography.titleMedium)
                if (found.noGaps) {
                    Text(stringResource(R.string.split_no_gaps_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.split_no_gaps_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        if (found.uncertain == 0) stringResource(R.string.split_found_all_right, found.songCount) else stringResource(R.string.split_found_counts, found.looksRight, found.uncertain),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!found.noGaps) {
                LazyColumn(Modifier.weight(1f)) { items(found.rows, key = { it.number }) { FoundRowItem(it, onOpenCut) } }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val primary = found.primary
                val label = when (primary) {
                    is FoundPrimary.Check -> if (primary.uncertain == 1) stringResource(R.string.split_check_one_song) else stringResource(R.string.split_check_songs, primary.uncertain)
                    is FoundPrimary.Split -> stringResource(R.string.split_split_songs, primary.songs)
                    FoundPrimary.AddACut -> stringResource(R.string.split_add_cut)
                }
                if (primary == FoundPrimary.AddACut) {
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                } else {
                    Button(onClick = { onPrimary?.invoke() }, enabled = onPrimary != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                    if (primary is FoundPrimary.Check && onSplitAsFound != null) {
                        OutlinedButton(onClick = onSplitAsFound, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.split_split_songs, found.songCount)) }
                    }
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.split_add_cut)) }
                }
                OutlinedButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.split_leave_title)) },
            text = { Text(stringResource(R.string.split_leave_body)) },
            confirmButton = { TextButton(onClick = { asking = false; onLeave() }) { Text(stringResource(R.string.split_leave)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.split_stay)) } },
        )
    }
    if (adding) {
        var text by rememberSaveable { mutableStateOf("") }
        val close = { adding = false; onClearAddCutError() }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.split_add_cut_title)) },
            text = {
                OutlinedTextField(
                    text,
                    { text = it; if (addCutFailed) onClearAddCutError() },
                    label = { Text(stringResource(R.string.split_add_cut_label)) },
                    singleLine = true,
                    isError = addCutFailed,
                    supportingText = if (addCutFailed) ({ Text(stringResource(R.string.split_add_cut_error)) }) else null,
                )
            },
            confirmButton = { TextButton(onClick = { onAddCut(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.split_add_cut_confirm)) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.dialog_cancel)) } },
        )
        // A cut that was accepted turns the list longer and the error off: the dialog closes by itself.
        androidx.compose.runtime.LaunchedEffect(found.songCount) { if (text.isNotEmpty() && !addCutFailed) { adding = false; text = "" } }
    }
}

/** One row of K2: the number, start and end, the length, and the state as an icon and a word (never colour alone). */
@Composable
private fun FoundRowItem(row: FoundRow, onOpenCut: ((Int) -> Unit)?) {
    val start = SplitTime.format(row.startSec)
    val end = SplitTime.format(row.endSec)
    val length = SplitTime.format(row.lengthSec)
    val state = stringResource(if (row.state == FoundState.NeedsCheck) R.string.split_row_check else R.string.split_row_looks_right)
    val spoken = stringResource(R.string.split_row_description, row.number, start, end, length, state)
    // A tap opens the cut at the start of the song; for song 1 that is the cut at its end (UX Design 4.11 (K2)).
    val open = if (onOpenCut != null) Modifier.clickable(onClickLabel = stringResource(R.string.split_row_open_cut), role = androidx.compose.ui.semantics.Role.Button) { onOpenCut(if (row.number == 1) 0 else row.number - 2) } else Modifier
    Row(
        Modifier.fillMaxWidth().then(open).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(row.number.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.split_row_times, start, end), style = MaterialTheme.typography.bodyLarge)
            Text(length, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (row.state == FoundState.NeedsCheck) Icons.Default.Warning else Icons.Default.Check, contentDescription = null)
        Text(state, style = MaterialTheme.typography.labelLarge)
    }
}
