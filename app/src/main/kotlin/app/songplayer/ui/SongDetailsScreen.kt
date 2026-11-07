package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.details.DetailField
import app.songplayer.core.details.DetailsState
import java.text.NumberFormat

/**
 * "Song details" (UX Design 4.10, Stories H8): the switch "Fill in missing details" (with the consent dialog the first time), "Only on Wi-Fi",
 * what the lookup is doing in words, the "Allow" action when the files cannot be changed yet, and "Recently filled in" with Undo (R-31).
 * [ui] is null until the first reading of the state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsScreen(
    ui: SongDetailsUi?,
    rows: List<RecentRowUi>,
    onUndo: (RecentRowUi) -> Unit,
    onClose: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onAllowFiles: () -> Unit,
    onWriteNow: () -> Unit = {},
    onReview: () -> Unit = {},
    onReplace: (Boolean) -> Unit = {},
    onOnlyNew: (Boolean) -> Unit = {},
    onNotFound: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    var askConsent by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.song_details_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (ui != null) {
                item {
                    val available = ui.state != DetailsState.Unavailable
                    SwitchRow(
                        title = stringResource(R.string.details_switch_title),
                        body = stringResource(R.string.details_switch_body),
                        checked = ui.enabled,
                        enabled = available,
                        onChange = { on -> if (on && !ui.consented) askConsent = true else onEnabled(on) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = stringResource(R.string.details_wifi_title),
                        body = stringResource(R.string.details_wifi_body),
                        checked = ui.wifiOnly,
                        enabled = available,
                        onChange = onWifiOnly,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = stringResource(R.string.details_replace_title),
                        body = stringResource(R.string.details_replace_body),
                        checked = ui.replace,
                        enabled = available,
                        onChange = onReplace,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = stringResource(R.string.details_only_new_title),
                        body = stringResource(R.string.details_only_new_body),
                        checked = ui.onlyNew,
                        enabled = available,
                        onChange = onOnlyNew,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StatusBlock(ui.state)
                    if (ui.waitingSongs > 0) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(countText(R.plurals.details_files_title, ui.waitingSongs), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (ui.filesGranted) R.string.details_files_body_granted else R.string.details_files_body),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onWriteNow) { Text(stringResource(R.string.details_write_now)) }
                                if (!ui.filesGranted) OutlinedButton(onClick = onAllowFiles) { Text(stringResource(R.string.details_allow)) }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (ui.held > 0) {
                        ListRow(countText(R.plurals.review_banner, ui.held), onReview)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (ui.notFound > 0) {
                        ListRow(countText(R.plurals.notfound_row, ui.notFound), onNotFound)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.recent_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                Text(
                    stringResource(R.string.recent_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (rows.isEmpty()) {
                    Text(
                        stringResource(R.string.recent_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(rows, key = { it.batch }) { row ->
                RecentRow(row, onUndo = { onUndo(row) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text(stringResource(R.string.consent_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.consent_body))
                    Text(stringResource(R.string.consent_permission), modifier = Modifier.padding(top = 12.dp))
                }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text(stringResource(R.string.consent_not_now)) } },
            confirmButton = { TextButton(onClick = { askConsent = false; onEnabled(true) }) { Text(stringResource(R.string.consent_turn_on)) } },
        )
    }
}

/** A row that opens a list (Review, Not found). */
@Composable
private fun ListRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/** A switch row whose state is spoken in words ("On"/"Off"), not only shown by the switch (never colour alone). */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val stateWords = stringResource(if (checked) R.string.switch_word_on else R.string.switch_word_off)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = stateWords }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stateWords, style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** What the lookup is doing, in words (the states of UX Design 4.10); never by colour alone. */
@Composable
private fun StatusBlock(state: DetailsState) {
    val format = NumberFormat.getInstance()
    val resources = LocalResources.current
    val (title, body) = when (state) {
        DetailsState.Unavailable -> stringResource(R.string.details_state_unavailable) to null
        DetailsState.Off -> stringResource(R.string.details_state_off) to null
        is DetailsState.Running -> stringResource(R.string.details_state_running) to stringResource(R.string.details_state_running_count, format.format(state.checked), format.format(state.total))
        is DetailsState.WaitingForWifi -> stringResource(R.string.details_state_wifi) to resources.getQuantityString(R.plurals.details_state_wifi_body, state.remaining, format.format(state.remaining))
        is DetailsState.Offline -> stringResource(R.string.details_state_offline) to null
        DetailsState.AskedToWait -> stringResource(R.string.details_state_wait) to stringResource(R.string.details_state_wait_body)
        is DetailsState.Done ->
            (if (state.held > 0) resources.getQuantityString(R.plurals.details_state_done_review, state.held, format.format(state.held)) else stringResource(R.string.details_state_done)) to null
        DetailsState.NothingToLookUp -> stringResource(R.string.details_state_nothing) to null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state is DetailsState.Running && state.total > 0) {
            LinearProgressIndicator(progress = { state.checked.toFloat() / state.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun RecentRow(row: RecentRowUi, onUndo: () -> Unit) {
    val who = stringResource(if (row.byUser) R.string.recent_edited_by_you else R.string.recent_filled_in)
    // `map` is inline, so the string lookups stay in the composable; `joinToString` takes an ordinary lambda.
    val fields = row.fields.distinct().map { stringResource(fieldLabel(it)) }.joinToString(", ")
    val spoken = stringResource(R.string.recent_row_description, row.title, row.artist, who, fields)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = spoken }) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.recent_row_line, row.artist, who, fields), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onUndo) { Text(stringResource(R.string.snackbar_undo)) }
    }
}

private fun fieldLabel(field: DetailField) = when (field) {
    DetailField.Title -> R.string.edit_field_title
    DetailField.Artist -> R.string.edit_field_artist
    DetailField.Album -> R.string.edit_field_album
}
