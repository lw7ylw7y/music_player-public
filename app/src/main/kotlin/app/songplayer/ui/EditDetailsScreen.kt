package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.details.DetailsRules
import app.songplayer.core.details.TagFields
import app.songplayer.core.library.Song

/**
 * "Edit details" (UX Design 4.10, H10): the title, artist and album of one song, by hand (R-30). Leaving with changes asks
 * "Discard your changes?". Save hands the three texts back; a blank field means no value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDetailsScreen(song: Song, onSave: (TagFields) -> Unit, onClose: () -> Unit, bottomBar: @Composable () -> Unit = {}) {
    var title by rememberSaveable(song.key.value) { mutableStateOf(song.title.orEmpty()) }
    var artist by rememberSaveable(song.key.value) { mutableStateOf(song.artist.orEmpty()) }
    var album by rememberSaveable(song.key.value) { mutableStateOf(song.album.orEmpty()) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val edited = TagFields(DetailsRules.clean(title), DetailsRules.clean(artist), DetailsRules.clean(album))
    val changed = DetailsRules.diff(TagFields(song.title, song.artist, song.album), edited).isNotEmpty()
    fun leave() { if (changed) asking = true else onClose() }

    BackHandler(onBack = ::leave)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_details_title)) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.edit_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.edit_field_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.edit_field_album)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.edit_details_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::leave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Button(onClick = { onSave(edited) }, enabled = changed, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.edit_details_save)) }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.edit_details_discard_title)) },
            confirmButton = { TextButton(onClick = { asking = false; onClose() }) { Text(stringResource(R.string.edit_details_discard)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}
