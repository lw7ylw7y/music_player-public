package app.songplayer.widget

import app.songplayer.core.widget.WidgetRules
import app.songplayer.core.widget.WidgetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tells the widget when the playback service reports a different song or play state ([WidgetRules.shouldUpdate]); every
 * other event is dropped here. There is no timer and no periodic work (NFR-10). The new state goes to [WidgetLive], which a
 * running widget session follows; [redraw] makes sure a widget session is running. A failed redraw is reported to
 * [onFailure] (a code, no content), the widget keeps what it shows, and the same state is tried again at the next event.
 */
class WidgetUpdater(
    private val scope: CoroutineScope,
    private val onFailure: () -> Unit = {},
    private val redraw: suspend () -> Unit,
) {
    private var last: WidgetState? = null

    @Synchronized
    fun onChange(now: WidgetState) {
        if (!WidgetRules.shouldUpdate(last, now)) return
        last = now
        WidgetLive.state = now
        scope.launch {
            try {
                redraw()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@WidgetUpdater) { if (last == now) last = null }
                onFailure()
            }
        }
    }
}
