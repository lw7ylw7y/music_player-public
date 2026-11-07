package app.songplayer.ui

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.songplayer.R
import java.text.NumberFormat

/** The time as a screen reader should say it: "1 minute 34 seconds". */
@Composable
fun spokenTime(ms: Long): String {
    val resources = LocalResources.current
    val total = ms.coerceAtLeast(0) / 1000
    val minutes = (total / 60).toInt()
    val seconds = (total % 60).toInt()
    val m = if (minutes > 0) resources.getQuantityString(R.plurals.spoken_minutes, minutes, minutes) else null
    val s = if (seconds > 0 || minutes == 0) resources.getQuantityString(R.plurals.spoken_seconds, seconds, seconds) else null
    return listOfNotNull(m, s).joinToString(" ")
}

/** "1 song" or "1,248 songs": the number is grouped for the phone's language. */
@Composable
fun countText(@PluralsRes plural: Int, count: Int): String {
    val resources = LocalResources.current
    return resources.getQuantityString(plural, count, NumberFormat.getInstance().format(count))
}
