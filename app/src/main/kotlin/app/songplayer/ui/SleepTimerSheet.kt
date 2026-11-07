package app.songplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.playback.SleepUi
import kotlinx.coroutines.delay

/**
 * The line under the title while a sleep timer runs (UX Design D4): the time left, counted down here from the end time
 * the service published, once a second and only while this is on screen (NFR-10); or "Sleep after this song". Nothing is
 * shown without a timer.
 */
@Composable
fun SleepLine(sleep: SleepUi, modifier: Modifier = Modifier) {
    val text = sleepText(sleep) ?: return
    Text(
        text,
        modifier.fillMaxWidth().padding(top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The words for a running timer, or null with none. Recomputed once a second while it runs. */
@Composable
private fun sleepText(sleep: SleepUi): String? {
    val ends = sleep.endsAtMs
    var left by remember(ends) { mutableStateOf(ends?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }) }
    LaunchedEffect(ends) {
        if (ends == null) return@LaunchedEffect
        while (true) {
            left = (ends - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    return when {
        sleep.endOfSong -> stringResource(R.string.sleep_end_of_song_line)
        left != null -> stringResource(R.string.sleep_running, formatTime((left ?: 0L) + 999))
        else -> null
    }
}

/**
 * The sleep timer sheet (UX Design D3 and D9): 15, 30, 45 and 60 minutes, "End of song", and,
 * while a timer runs, the time left and "Cancel timer". No explanation: the choices say what they do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    sleep: SleepUi,
    choices: List<Int>,
    onStart: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.sleep_timer),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (sleep.active) {
                SleepLine(sleep)
                OutlinedButton(
                    onClick = {
                        onCancel()
                        onDismiss()
                    },
                    Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.sleep_cancel)) }
            }
            choices.forEach { minutes ->
                Text(
                    resources.getQuantityString(R.plurals.sleep_minutes, minutes, minutes),
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                        onStart(minutes)
                        onDismiss()
                    }.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                stringResource(R.string.sleep_end_of_song),
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                    onEndOfSong()
                    onDismiss()
                }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
