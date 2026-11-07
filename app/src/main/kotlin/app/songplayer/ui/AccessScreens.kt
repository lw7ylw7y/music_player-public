package app.songplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songplayer.R

/** A centered message with one filled button and optionally one outlined button (UX Design A1, A2, A9). Scrolls at large font sizes. */
@Composable
private fun MessageScreen(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onPrimary) { Text(primaryLabel) }
        if (secondaryLabel != null) OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
    }
}

/** A1: why the app needs access to the music, before Android's own dialog. */
@Composable
fun FirstStartScreen(onAllow: () -> Unit) = MessageScreen(
    stringResource(R.string.access_first_title),
    stringResource(R.string.access_first_body),
    stringResource(R.string.access_first_button),
    onAllow,
)

/** A2: the user said no. "Try again" asks again, or opens Settings when Android no longer asks. */
@Composable
fun AccessRefusedScreen(onTryAgain: () -> Unit, onOpenSettings: () -> Unit) = MessageScreen(
    stringResource(R.string.access_refused_title),
    stringResource(R.string.access_refused_body),
    stringResource(R.string.access_try_again),
    onTryAgain,
    stringResource(R.string.access_open_settings),
    onOpenSettings,
)

/** The first sync failed and there is nothing to show yet. */
@Composable
fun SyncFailedScreen(onRetry: () -> Unit) = MessageScreen(
    stringResource(R.string.sync_failed_title),
    stringResource(R.string.sync_failed_body),
    stringResource(R.string.refresh_failed_retry),
    onRetry,
)

/** A9: access is allowed but Android lists no MP3 file. */
@Composable
fun EmptyLibraryScreen(onRefresh: () -> Unit) = MessageScreen(
    stringResource(R.string.empty_title),
    stringResource(R.string.empty_body),
    stringResource(R.string.refresh_library),
    onRefresh,
)
