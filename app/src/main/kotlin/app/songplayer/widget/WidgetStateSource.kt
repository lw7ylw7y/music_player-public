package app.songplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import app.songplayer.SongPlayerApp
import app.songplayer.core.log.LogCode
import app.songplayer.core.widget.WidgetRules
import app.songplayer.core.widget.WidgetState
import app.songplayer.playback.audioUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The state the playback service last pushed, in this process's memory; null until it pushes one. The widget collects
 * [flow], so a Glance session that is still running follows every change instead of showing what it read when it began.
 */
object WidgetLive {
    private val _flow = MutableStateFlow<WidgetState?>(null)
    val flow: StateFlow<WidgetState?> = _flow

    var state: WidgetState?
        get() = _flow.value
        set(value) {
            _flow.value = value
        }
}

/** What the widget draws: the live state while the player holds a song, otherwise the song `resume` was on (Technical Design 5.5). */
object WidgetStateSource {
    /** The song the saved queue was on, shown paused; null when nothing is saved, the song is gone or the saved queue cannot be read. Blocking: off the main thread. */
    fun saved(context: Context): WidgetState? {
        val container = (context.applicationContext as SongPlayerApp).container
        return try {
            val resume = container.resumeStore.load() ?: return null
            WidgetRules.fromResume(resume) { container.songStore.get(it) }
        } catch (e: Exception) {
            // Nothing readable to show: the widget then says "Music Player, Tap to open". Logged as a code, no content.
            container.log.event(LogCode.WIDGET_FAILED, 1)
            null
        }
    }

    /** What the widget would draw now (blocking: off the main thread). */
    fun current(context: Context): WidgetState {
        val live = WidgetLive.state
        return WidgetRules.shown(live, if (live?.song != null) null else saved(context))
    }

    /** The cover, scaled to about 96 dp (the system limits the size of images sent to a widget); null when the song has none. */
    fun cover(context: Context, mediaStoreId: Long): Bitmap? {
        if (mediaStoreId < 0) return null
        val px = (96 * context.resources.displayMetrics.density).toInt().coerceIn(96, 384)
        return try {
            context.contentResolver.loadThumbnail(audioUri(mediaStoreId), Size(px, px), null)
        } catch (e: Exception) {
            null
        }
    }
}
