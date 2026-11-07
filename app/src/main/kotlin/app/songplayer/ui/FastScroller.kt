package app.songplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.songplayer.core.text.Section
import app.songplayer.core.text.Sections
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Thumb = 48.dp
private const val HIDE_AFTER_MS = 1_200L

/**
 * The fast-scroll handle of R-25. It appears while the list scrolls or is dragged, rides along the right edge,
 * and shows the section label (a letter, or a month) in a bubble while it is dragged. Dragging to a part of the
 * track jumps to the first item of that section ([Sections.at]). TalkBack ignores it: the list scrolls with the
 * normal screen-reader gestures, and a drag handle has no useful spoken form.
 */
@Composable
fun FastScroller(
    sections: List<Section>,
    itemCount: Int,
    firstVisibleIndex: () -> Int,
    isScrolling: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.size < 2 || itemCount < 2) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var trackPx by remember { mutableStateOf(0) }
    val thumbPx = with(LocalDensity.current) { Thumb.roundToPx() }

    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) {
            shown = true
        } else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    if (!shown && !dragging) return

    val fraction = if (dragging) dragFraction else firstVisibleIndex().toFloat() / (itemCount - 1).coerceAtLeast(1)
    val label = Sections.at(sections, fraction)?.label.orEmpty()
    val travel = (trackPx - thumbPx).coerceAtLeast(0)

    Box(
        modifier.fillMaxHeight().width(Thumb).onSizeChanged { trackPx = it.height }.clearAndSetSemantics {}
            .pointerInput(sections) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = ((offset.y - thumbPx / 2f) / (size.height - thumbPx).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Sections.at(sections, dragFraction)?.let { onJump(it.firstIndex) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        dragFraction = ((change.position.y - thumbPx / 2f) / (size.height - thumbPx).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Sections.at(sections, dragFraction)?.let { onJump(it.firstIndex) }
                    },
                )
            },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction.coerceIn(0f, 1f) * travel).roundToInt()) }
                .size(Thumb)
                .padding(vertical = 4.dp, horizontal = 14.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
        )
        if (dragging) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(-thumbPx, (fraction.coerceIn(0f, 1f) * travel).roundToInt()) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
