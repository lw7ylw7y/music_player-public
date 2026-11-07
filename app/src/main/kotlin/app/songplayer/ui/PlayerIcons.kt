package app.songplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few player icons that Compose's small built-in icon set lacks. The shapes are Material Design's own
 * (Apache 2.0); drawing them here avoids the large extended icon library for four icons.
 */
object PlayerIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }
    val Play: ImageVector by lazy { icon("Play", "M8 5v14l11-7z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
    /** The "block" mark of a song that cannot play (a circle with a slash). */
    val Block: ImageVector by lazy {
        icon("Block", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z")
    }
    val Shuffle: ImageVector by lazy {
        icon("Shuffle", "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z")
    }
    val Repeat: ImageVector by lazy { icon("Repeat", "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z") }
    val RepeatOne: ImageVector by lazy {
        icon("RepeatOne", "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z")
    }
    /** A crescent moon: the sleep timer. */
    val Moon: ImageVector by lazy {
        icon("Moon", "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z")
    }

    /** A list with a note: the queue. */
    val Queue: ImageVector by lazy {
        icon("Queue", "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z")
    }

    /** An arrow up: the list runs A to Z, or oldest first. */
    val ArrowUp: ImageVector by lazy { icon("ArrowUp", "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z") }

    /** An arrow down: the list runs Z to A, or newest first. */
    val ArrowDown: ImageVector by lazy { icon("ArrowDown", "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z") }

    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z") }
}
