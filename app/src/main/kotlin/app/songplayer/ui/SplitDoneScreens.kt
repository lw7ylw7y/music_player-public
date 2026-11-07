package app.songplayer.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.split.SplitOutcome
import app.songplayer.core.split.SplitSummary
import app.songplayer.core.split.StopReason

/**
 * K4, "Ready to split" (UX Design 4.11, R-40, R-46): the names the songs will get, the folder, the space, and a line about the uncertain cuts that
 * were not looked at. Nothing has been written; the button is the first thing that writes, and it is off when the space is too small.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyScreen(summary: SplitSummary, onStart: () -> Unit, onCheckThem: () -> Unit, onBack: () -> Unit, bottomBar: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ready_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ready_summary, summary.sourceName, summary.songCount), style = MaterialTheme.typography.titleMedium)
            Fact(stringResource(R.string.ready_names_label), stringResource(R.string.ready_names_value, summary.firstName, summary.lastName))
            Fact(stringResource(R.string.ready_folder_label), summary.folderParts.joinToString(" › "))
            val needed = Formatter.formatShortFileSize(context, summary.neededBytes)
            val free = Formatter.formatShortFileSize(context, summary.freeBytes)
            Fact(
                stringResource(R.string.ready_space_label),
                if (summary.enoughSpace) stringResource(R.string.ready_space_ok, needed, free) else stringResource(R.string.ready_space_short, needed, free),
            )
            Text(stringResource(R.string.ready_original_stays), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.uncheckedCuts > 0) {
                Text(
                    if (summary.uncheckedCuts == 1) stringResource(R.string.ready_unchecked_one) else stringResource(R.string.ready_unchecked, summary.uncheckedCuts),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCheckThem, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ready_check_them)) }
            }
            Button(onClick = onStart, enabled = summary.canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.split_split_songs, summary.songCount))
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ready_back)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** K5, "Writing": which song, a bar, "Keep going in the background" (also Back) and Cancel (R-44). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingScreen(written: Int, total: Int, onCancel: () -> Unit, onBackground: () -> Unit = {}, bottomBar: @Composable () -> Unit = {}) {
    BackHandler(onBack = onBackground)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.split_title)) }) }, bottomBar = bottomBar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.writing_progress, minOf(written + 1, total), total),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },       // a screen reader hears each new song number
            )
            LinearProgressIndicator(progress = { if (total == 0) 0f else written.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onBackground, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.split_in_background)) }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
        }
    }
}

/**
 * K6, "Done", and the message when a run stopped (UX Design 4.11, R-44, R-47, R-49): the count, "Show the songs", the question about the original
 * (Keep is the first and filled button), and "Remove these songs" as a quiet text button that asks "Remove N songs?" first. These questions are
 * the confirmation that always exists; Android's own dialog comes only when the Media management access is not granted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinishedScreen(
    outcome: SplitOutcome,
    sourceName: String,
    folderParts: List<String>,
    originalDeleted: Boolean,
    songsRemoved: Boolean,
    onShowSongs: () -> Unit,
    onDeleteOriginal: () -> Unit,
    onRemoveSongs: () -> Unit,
    onClose: () -> Unit,
    /** How many of the run's songs still exist: the count "Remove these songs" asks about. */
    removable: Int = outcome.written,
    bottomBar: @Composable () -> Unit = {},
) {
    var keep by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.split_title)) }) }, bottomBar = bottomBar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (songsRemoved) {
                Text(stringResource(R.string.done_songs_removed), style = MaterialTheme.typography.titleMedium)
            } else if (outcome.finished) {
                Text(
                    if (outcome.continued) stringResource(R.string.done_title_continued, outcome.written, outcome.earlier, outcome.now) else stringResource(R.string.done_title, outcome.written),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.done_where, folderParts.joinToString(" › ")), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onShowSongs, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.done_show_songs)) }
                if (originalDeleted) {
                    Text(stringResource(R.string.done_original_deleted), style = MaterialTheme.typography.bodyMedium)
                } else if (!keep) {
                    Text(stringResource(R.string.done_delete_original_question, sourceName), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { keep = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.done_keep)) }
                        OutlinedButton(onClick = onDeleteOriginal, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.done_delete)) }
                    }
                }
            } else {
                Text(stringResource(R.string.stopped_title, outcome.written), style = MaterialTheme.typography.titleMedium)
                val why = when (outcome.stopped) {
                    StopReason.NoSpace -> R.string.stopped_no_space
                    StopReason.SourceGone -> R.string.stopped_source_gone
                    StopReason.Failed -> R.string.stopped_failed
                    else -> null
                }
                why?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            }
            if (!songsRemoved && removable > 0) {
                TextButton(onClick = { asking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.done_remove_songs)) }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.done_close)) }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.done_remove_title, removable)) },
            text = { Text(stringResource(R.string.done_remove_body)) },
            confirmButton = { TextButton(onClick = { asking = false; onRemoveSongs() }) { Text(stringResource(R.string.done_remove_confirm)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}
