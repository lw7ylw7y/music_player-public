package app.songplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.songplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings (UX Design 4.8, F1): "Song details" (V1.1), "Copy diagnostics" and "About", and nothing else. "Copy diagnostics" puts the app version, the phone model, the
 * Android version and the recent error codes on the clipboard (R-21) and says so; "About Music Player" shows the version
 * and what the app promises. There is no theme option: the app follows the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    versionName: String,
    diagnosticsText: () -> String,
    onClose: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    /** Opens "Song details" (Stories H3); no row when the screen is shown without it. */
    onSongDetails: (() -> Unit)? = null,
    /** Whether the lookup is on: the row says "On." or "Off." in words. */
    songDetailsOn: Boolean = false,
    /** Export and Import of the playlists (R-26); no rows when the screen is shown without them. */
    onExportPlaylists: (() -> Unit)? = null,
    onImportPlaylists: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.diagnostics_copied)
    val clipLabel = stringResource(R.string.diagnostics_clip_label)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (onSongDetails != null) {
                SettingsRow(
                    title = stringResource(R.string.settings_song_details),
                    body = stringResource(if (songDetailsOn) R.string.settings_song_details_on else R.string.settings_song_details_off),
                    onClick = onSongDetails,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (onExportPlaylists != null && onImportPlaylists != null) {
                SettingsRow(
                    title = stringResource(R.string.settings_export_playlists),
                    body = stringResource(R.string.settings_export_playlists_body),
                    onClick = onExportPlaylists,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.settings_import_playlists),
                    body = stringResource(R.string.settings_import_playlists_body),
                    onClick = onImportPlaylists,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SettingsRow(
                title = stringResource(R.string.settings_copy_diagnostics),
                body = stringResource(R.string.settings_copy_diagnostics_body),
                onClick = {
                    scope.launch {
                        // The error log is read off the main thread; the clipboard is set on it.
                        val text = withContext(Dispatchers.IO) { diagnosticsText() }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(copied)
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = stringResource(R.string.settings_about),
                body = stringResource(R.string.settings_about_body, versionName),
                onClick = null,
            )
        }
    }
}

@Composable
private fun SettingsRow(title: String, body: String, onClick: (() -> Unit)?) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(Modifier.fillMaxWidth().then(tap).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
