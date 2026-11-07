package app.songplayer.playback

import android.os.SystemClock
import app.songplayer.core.Clock

/** The phone's two clocks for the sleep timer: real time since start (ignores date changes) and the date. */
object SystemClockSource : Clock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()
}
