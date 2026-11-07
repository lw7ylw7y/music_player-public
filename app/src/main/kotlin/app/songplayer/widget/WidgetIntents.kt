package app.songplayer.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import android.view.KeyEvent
import androidx.media3.session.MediaButtonReceiver

/**
 * The widget's buttons are media-button events sent to Media3's receiver, which starts the playback service if it is not
 * running and hands the key to the session: the same path as a headset button (Technical Design 5.5). Each key has its own
 * address, because Android treats two intents that differ only in their extras as the same pending intent.
 */
@OptIn(UnstableApi::class)
object WidgetIntents {
    fun mediaButton(context: Context, keyCode: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(context, MediaButtonReceiver::class.java)
            .setData("songplayer-widget://key/$keyCode".toUri())
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
}
