package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.songplayer.R
import app.songplayer.playback.NowPlaying
import app.songplayer.playback.PlayingFrom
import app.songplayer.playback.SleepUi
import java.util.Locale
import kotlinx.coroutines.delay

private val Controls = 56.dp

@Composable
private fun PlayerButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(Controls)) {
        Icon(icon, contentDescription = label)
    }
}

/** One equal share of a row, with its content centred. */
@Composable
private fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

/**
 * A toggle that is only an icon (shuffle, repeat). When it is on, a ring and a filled circle sit behind the icon, so
 * the state is carried by shape, not by color alone; a screen reader says the state in words ([spoken]).
 */
@Composable
private fun StateToggle(icon: ImageVector, spoken: String, active: Boolean, onClick: () -> Unit, kind: Role) {
    val on = if (active) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier
    }
    Box(
        Modifier.size(Controls).then(on).clip(CircleShape)
            .clickable(role = kind, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun playingFromLabel(from: PlayingFrom): Int = when (from) {
    PlayingFrom.Songs -> R.string.playing_from_songs
    PlayingFrom.Search -> R.string.playing_from_search
    PlayingFrom.Playlist -> R.string.playing_from_playlist
    PlayingFrom.File -> R.string.playing_from_file
}

/** "3:05" or "1:02:09". */
internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/**
 * The strip above the bottom edge while a song is loaded (UX Design 4.2): cover, title, artist, play or pause and
 * next. Tapping the body opens Now Playing. The play button is the one control that changes with the state, and it
 * says so in its label ("Play" or "Pause"), not only in its icon.
 */
@Composable
fun MiniPlayer(now: NowPlaying, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    val spoken = stringResource(R.string.mini_player_description, now.title, now.artist)
    val coverPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        // The app draws edge to edge, so the strip keeps clear of the system navigation bar (gesture bar or buttons)
        // inside its own background; otherwise the phone maker's buttons cover Next.
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.open_now_playing), onClick = onOpen)
                        .clearAndSetSemantics { contentDescription = spoken }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Cover(now.mediaStoreId, now.artistTag, coverPx, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(now.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(now.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                PlayerButton(
                    if (now.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                    stringResource(if (now.isPlaying) R.string.pause else R.string.play),
                    onToggle,
                )
                PlayerButton(PlayerIcons.SkipNext, stringResource(R.string.next), onNext)
            }
        }
    }
}

/**
 * Now Playing (UX Design 4.5, D1): big cover, title, artist and album, the seek bar with both times, and
 * previous, play or pause, next. Shuffle, repeat, the queue and the sleep timer come with their own stories.
 */
@Composable
fun NowPlayingScreen(
    now: NowPlaying,
    positionMs: () -> Long,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    onQueue: () -> Unit = {},
    sleep: SleepUi = SleepUi.None,
    sleepChoices: List<Int> = emptyList(),
    onSleepStart: (Int) -> Unit = {},
    onSleepEndOfSong: () -> Unit = {},
    onSleepCancel: () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    var sleepSheet by rememberSaveable { mutableStateOf(false) }
    // The position is read twice a second, only while this screen is shown and only while the song plays (NFR-10).
    var position by remember { mutableLongStateOf(positionMs()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(now.mediaId, now.isPlaying, dragging) {
        position = positionMs()
        while (now.isPlaying && !dragging) {
            delay(500)
            position = positionMs()
        }
    }
    val duration = now.durationMs.coerceAtLeast(1L)
    val coverPx = with(LocalDensity.current) { 280.dp.roundToPx() }
    val seekLabel = stringResource(R.string.seek)
    // Spoken only when the song starts, pauses or is dragged, not twice a second while it plays.
    val spokenPosition = remember(now.mediaId, now.isPlaying, dragging) { position }
    val seekSpoken = stringResource(R.string.seek_description, spokenTime(spokenPosition), spokenTime(now.durationMs))

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.close_now_playing))
                }
            }
            now.playingFrom?.let { from ->
                Text(
                    stringResource(R.string.playing_from, stringResource(playingFromLabel(from))),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Cover(now.mediaStoreId, now.artistTag, coverPx, Modifier.padding(top = 8.dp).size(280.dp))
            Text(
                now.title,
                Modifier.fillMaxWidth().padding(top = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.song_row_subtitle, now.artist, now.album),
                Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SleepLine(sleep)
            Slider(
                value = position.coerceIn(0L, duration).toFloat() / duration,
                onValueChange = {
                    dragging = true
                    position = (it * duration).toLong()
                },
                onValueChangeFinished = {
                    onSeek(position)
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics {
                    contentDescription = seekLabel
                    stateDescription = seekSpoken
                },
            )
            // The slider already says both times; the printed times are not read a second time.
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shuffle and repeat show they are on by a ring and a filled circle, never by color alone (R-8).
                // Each control gets an equal share of the width, so nothing is cut off at large font sizes.
                Cell {
                    StateToggle(
                        icon = PlayerIcons.Shuffle,
                        spoken = stringResource(if (now.shuffleOn) R.string.shuffle_on else R.string.shuffle_off),
                        active = now.shuffleOn,
                        onClick = onShuffle,
                        kind = Role.Switch,
                    )
                }
                Cell { PlayerButton(PlayerIcons.SkipPrevious, stringResource(R.string.previous), onPrevious) }
                Cell {
                    PlayerButton(
                        if (now.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        stringResource(if (now.isPlaying) R.string.pause else R.string.play),
                        onToggle,
                        Modifier.size(72.dp),
                    )
                }
                Cell { PlayerButton(PlayerIcons.SkipNext, stringResource(R.string.next), onNext) }
                Cell {
                    StateToggle(
                        icon = if (now.repeatMode == Player.REPEAT_MODE_ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
                        spoken = stringResource(
                            when (now.repeatMode) {
                                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                                else -> R.string.repeat_off
                            },
                        ),
                        active = now.repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = onRepeat,
                        kind = Role.Button,
                    )
                }
            }
            // Two icons, no printed words (as for shuffle and repeat): the queue, and the sleep timer as a moon that gets the
            // ring when a timer is set. Both have spoken names.
            Row(Modifier.padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                PlayerButton(PlayerIcons.Queue, stringResource(R.string.queue), onQueue)
                StateToggle(
                    icon = PlayerIcons.Moon,
                    spoken = stringResource(if (sleep.active) R.string.sleep_timer_on else R.string.sleep_timer),
                    active = sleep.active,
                    onClick = { sleepSheet = true },
                    kind = Role.Button,
                )
            }
        }
    }
    if (sleepSheet) SleepTimerSheet(sleep, sleepChoices, onSleepStart, onSleepEndOfSong, onSleepCancel, onDismiss = { sleepSheet = false })
}
