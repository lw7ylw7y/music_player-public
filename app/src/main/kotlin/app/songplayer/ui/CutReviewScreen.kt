package app.songplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.split.CutReview
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.SplitTime
import kotlinx.coroutines.delay

/**
 * K3, "Check a cut" (UX Design 4.11): one cut at a time with Play the cut (10 s either side, from the original), Earlier and Later by half a second
 * (a long press repeats), a time line to drag, and Use this cut, Remove this cut and Next. A cut can never come closer than 5 s to a neighbour or
 * an end; a button that would cross that is off and says "Closest allowed". [index] is the place of the cut in the record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutReviewScreen(
    record: RunRecord,
    durationSec: Double,
    index: Int,
    playing: Boolean,
    onPlay: (LongRange) -> Unit,
    onPause: () -> Unit,
    onMoveBy: (Double) -> Unit,
    onMoveTo: (Double) -> Unit,
    onUse: () -> Unit,
    onRemove: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val cut = record.cuts[index]
    val timeText = SplitTime.formatHalf(cut.timeSec)
    val spokenTime = spokenTime(cut.timeSec)
    val playFocus = remember { FocusRequester() }
    // After Use, Remove or Next the next cut is shown: focus goes to Play the cut, and the title ("Cut 4 of 11") is announced.
    LaunchedEffect(index) { runCatching { playFocus.requestFocus() } }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cut_title, index + 1, record.cuts.size), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cut_between, index + 1, index + 2), style = MaterialTheme.typography.titleMedium)
            // The new time is spoken whenever it changes (Earlier, Later, a drag).
            Text(
                timeText,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = spokenTime },
            )
            TimeLine(record, durationSec, index, onMoveTo)
            val gap = CutReview.gap(record, index)
            Text(
                if (gap != null) stringResource(R.string.cut_gap_description, SplitTime.format(gap.start), SplitTime.format(gap.endInclusive)) else stringResource(R.string.cut_no_gap_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NudgeButton(
                    label = stringResource(R.string.cut_earlier),
                    description = stringResource(R.string.cut_earlier_description),
                    enabled = CutReview.canMove(record, durationSec, index, -CutReview.STEP_SEC),
                    onNudge = { onMoveBy(-CutReview.STEP_SEC) },
                    modifier = Modifier.weight(1f),
                )
                NudgeButton(
                    label = stringResource(R.string.cut_later),
                    description = stringResource(R.string.cut_later_description),
                    enabled = CutReview.canMove(record, durationSec, index, CutReview.STEP_SEC),
                    onNudge = { onMoveBy(CutReview.STEP_SEC) },
                    modifier = Modifier.weight(1f),
                )
            }
            FilledTonalButton(
                onClick = { if (playing) onPause() else onPlay(CutReview.clip(record, durationSec, index)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(playFocus),
            ) { Text(stringResource(if (playing) R.string.cut_pause else R.string.cut_play)) }
            Text(stringResource(R.string.cut_play_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cut_use)) }
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cut_remove)) }
            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cut_next)) }
        }
    }
}

/**
 * Earlier or Later: one tap moves once; holding repeats after 0.4 s. When the move would break the 5 s rule the button is off and says
 * "Closest allowed", and a screen reader hears the same.
 */
@Composable
private fun NudgeButton(label: String, description: String, enabled: Boolean, onNudge: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val nudge by rememberUpdatedState(onNudge)
    val allowed by rememberUpdatedState(enabled)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            repeated = false                      // a press that ended without a click (the finger slid off) must not swallow the next tap
            delay(REPEAT_AFTER_MS)
            while (allowed) {
                repeated = true
                nudge()
                delay(REPEAT_EVERY_MS)
            }
        }
    }
    val spoken = if (enabled) description else stringResource(R.string.cut_closest)
    OutlinedButton(
        onClick = {
            // The tap that ends a long press must not move the cut once more.
            if (repeated) repeated = false else onNudge()
        },
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken },
    ) { Text(if (enabled) label else stringResource(R.string.cut_closest)) }
}

/** The time line: a strip showing the stretch around the cut with the gap and the cut marked, and a slider to drag the cut (a spoken "Cut position"). */
@Composable
private fun TimeLine(record: RunRecord, durationSec: Double, index: Int, onMoveTo: (Double) -> Unit) {
    val cut = record.cuts[index]
    val allowed = CutReview.range(record, durationSec, index)
    // The stretch shown stays where the cut was when the screen opened, so the strip does not slide under the finger while dragging.
    val start = remember(index, record.cuts.size) { cut.timeSec }
    val center = if (Math.abs(cut.timeSec - start) > WINDOW_SEC - 1) cut.timeSec else start   // a long run of nudges re-centres the strip
    val low = maxOf(allowed.start, center - WINDOW_SEC)
    val high = minOf(allowed.endInclusive, center + WINDOW_SEC)
    val gap = CutReview.gap(record, index)
    val bar = MaterialTheme.colorScheme.surfaceVariant
    val band = MaterialTheme.colorScheme.secondary
    val mark = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        drawRect(bar, size = Size(size.width, size.height))
        if (high > low) {
            fun x(t: Double) = (((t - low) / (high - low)).coerceIn(0.0, 1.0) * size.width).toFloat()
            gap?.let { drawRect(band, topLeft = Offset(x(it.start), 0f), size = Size(x(it.endInclusive) - x(it.start), size.height)) }
            drawRect(mark, topLeft = Offset(x(cut.timeSec) - 2.dp.toPx(), 0f), size = Size(4.dp.toPx(), size.height))
        }
    }
    if (high > low) {
        var dragging by remember(index) { mutableFloatStateOf(Float.NaN) }
        // The thumb stays where it was dropped until the saved cut arrives.
        androidx.compose.runtime.LaunchedEffect(cut.timeSec) { dragging = Float.NaN }
        val shown = if (dragging.isNaN()) cut.timeSec.toFloat() else dragging
        val position = stringResource(R.string.cut_position)
        val timeState = stringResource(R.string.cut_time_description, spokenTime(shown.toDouble()))
        Slider(
            value = shown.coerceIn(low.toFloat(), high.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onMoveTo((Math.round(dragging / CutReview.STEP_SEC) * CutReview.STEP_SEC).coerceIn(allowed.start, allowed.endInclusive))
            },
            valueRange = low.toFloat()..high.toFloat(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = position; stateDescription = timeState },
        )
    }
}

private const val WINDOW_SEC = 15.0
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 150L

/** The time as a screen reader should say it: "8 minutes 29 seconds and a half". */
@Composable
private fun spokenTime(sec: Double): String {
    val halves = Math.round(sec.coerceAtLeast(0.0) * 2)
    val whole = (halves / 2).toInt()
    val minutes = whole / 60
    val seconds = whole % 60
    val secondsText = pluralStringResource(R.plurals.cut_spoken_seconds, seconds, seconds).let { if (halves % 2 == 1L) stringResource(R.string.cut_spoken_and_a_half, it) else it }
    return if (minutes > 0) stringResource(R.string.cut_spoken_join, pluralStringResource(R.plurals.cut_spoken_minutes, minutes, minutes), secondsText) else secondsText
}
