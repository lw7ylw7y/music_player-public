package app.songplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.songplayer.MainActivity
import app.songplayer.R
import app.songplayer.core.widget.WidgetRules
import app.songplayer.core.widget.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The music note on the flat square of a song without a cover; a symbol, not text to translate. */
private const val NOTE_GLYPH = "♪"

private val Surface = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1E1F))
private val OnSurface = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
private val OnSurfaceVariant = ColorProvider(day = Color(0xFF4A4A4F), night = Color(0xFFC7C7CC))
private val CoverBackground = ColorProvider(day = Color(0xFFE8EEEC), night = Color(0xFF2A2E2E))

/**
 * The home-screen widget (UX Design G1, R-24): the song that plays, or the last one that played, with previous, play or
 * pause and next; tapping the cover opens the app. Nothing playing and nothing ever played shows "Music Player, Tap to
 * open". It is drawn from [WidgetStateSource] and redrawn by [WidgetUpdater] only when the song or the play state changes.
 */
class SongWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The saved song is read once when the session begins. What plays is followed from then on: a session lives for a
        // while after each redraw, and a later update only reaches it through what it observes (Glance's own guidance).
        val saved = withContext(Dispatchers.IO) { WidgetStateSource.saved(context) }
        provideContent {
            val live by WidgetLive.flow.collectAsState()
            val state = WidgetRules.shown(live, saved)
            val coverId = state.song?.mediaStoreId ?: -1L
            // The cover is loaded again only when the song changes, not for a play or pause.
            val cover by produceState<Bitmap?>(initialValue = null, key1 = coverId) {
                value = withContext(Dispatchers.IO) { WidgetStateSource.cover(context, coverId) }
            }
            SongWidgetContent(state, cover)
        }
    }
}

class SongWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SongWidget()
}

@Composable
fun SongWidgetContent(state: WidgetState, cover: Bitmap?) {
    val context = LocalContext.current
    val open: Action = actionStartActivity(Intent(context, MainActivity::class.java))
    val song = state.song
    if (song == null) {
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().background(Surface).cornerRadius(16.dp).padding(12.dp).clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(context.getString(R.string.app_name), style = TextStyle(color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(context.getString(R.string.widget_tap_to_open), style = TextStyle(color = OnSurfaceVariant, fontSize = 14.sp))
        }
        return
    }
    Row(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(Surface).cornerRadius(16.dp).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            Image(
                ImageProvider(cover),
                contentDescription = context.getString(R.string.widget_open_app),
                modifier = GlanceModifier.size(48.dp).cornerRadius(6.dp).clickable(open),
            )
        } else {
            Box(
                GlanceModifier.size(48.dp).cornerRadius(6.dp).background(CoverBackground).clickable(open)
                    .semantics { contentDescription = context.getString(R.string.widget_open_app) },
                contentAlignment = Alignment.Center,
            ) {
                Text(NOTE_GLYPH, style = TextStyle(color = OnSurfaceVariant, fontSize = 20.sp))
            }
        }
        Column(GlanceModifier.defaultWeight().padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(song.title, maxLines = 1, style = TextStyle(color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Text(song.artist, maxLines = 1, style = TextStyle(color = OnSurfaceVariant, fontSize = 12.sp))
        }
        WidgetButton(R.drawable.ic_widget_previous, context.getString(R.string.previous), WidgetIntents.mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        WidgetButton(
            if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            context.getString(if (state.playing) R.string.pause else R.string.play),
            WidgetIntents.mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        WidgetButton(R.drawable.ic_widget_next, context.getString(R.string.next), WidgetIntents.mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
    }
}

@Composable
private fun WidgetButton(icon: Int, description: String, intent: android.content.Intent) {
    Image(
        ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(OnSurface),
        modifier = GlanceModifier.size(48.dp).padding(12.dp).clickable(actionSendBroadcast(intent)),
    )
}
