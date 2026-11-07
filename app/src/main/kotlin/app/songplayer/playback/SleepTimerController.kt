package app.songplayer.playback

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.songplayer.core.Clock
import app.songplayer.core.sleep.SleepState
import app.songplayer.core.sleep.SleepTimer

/**
 * The sleep timer in the playback service (Technical Design 5.6): the rules are `core`'s [SleepTimer]. A timer of some
 * minutes wakes up at its deadline and pauses playback; "End of song" asks the player to pause at the end of the song that
 * is playing. [onChanged] hears every change of state so the end time can be published to the screens. Nothing here ticks:
 * the screens count down from the end time themselves. All calls are on the player's thread. The state is not saved
 * anywhere, so a killed service has no timer afterwards.
 */
@OptIn(UnstableApi::class)
class SleepTimerController(
    private val player: ExoPlayer,
    clock: Clock,
    private val handler: Handler,
    private val onChanged: (SleepState) -> Unit = {},
) : Player.Listener {
    private val timer = SleepTimer(clock)
    private val deadline = Runnable { checkNow() }

    val state: SleepState get() = timer.state

    /** False, and nothing changes, when [minutes] is not one of the allowed values. */
    fun start(minutes: Int): Boolean {
        if (!timer.start(minutes)) return false
        player.pauseAtEndOfMediaItems = false
        schedule()
        onChanged(timer.state)
        return true
    }

    /** Playback pauses when the song that is playing ends; with repeat one the song is not played again. */
    fun startEndOfSong() {
        handler.removeCallbacks(deadline)
        timer.startEndOfSong()
        player.pauseAtEndOfMediaItems = true
        onChanged(timer.state)
    }

    fun cancel() {
        handler.removeCallbacks(deadline)
        timer.cancel()
        player.pauseAtEndOfMediaItems = false
        onChanged(timer.state)
    }

    /** Looks at the deadline now; the scheduled wake-up calls this, and a test can too. Time is up: playback pauses, in the middle of a song if need be. */
    fun checkNow() {
        if (timer.expire()) {
            player.pause()
            onChanged(timer.state)
        } else if (timer.state is SleepState.Running) {
            schedule()
        }
    }

    fun release() = handler.removeCallbacks(deadline)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // The song ended and playback paused as asked; or the user paused it himself (the aim is reached either way, and
        // the timer must not linger and pause some other song later).
        if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)) finished()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new queue replaced the one the timer was waiting on. A skip to the next song keeps waiting: the end of that song.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) finished()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) finished()
    }

    private fun finished() {
        if (timer.state != SleepState.EndOfSong) return
        timer.songEnded()
        player.pauseAtEndOfMediaItems = false
        onChanged(timer.state)
    }

    /** The wake-up counts uptime, which stops while the phone sleeps; it is checked again when it fires, so a late wake-up is harmless. */
    private fun schedule() {
        handler.removeCallbacks(deadline)
        val left = timer.leftMs() ?: return
        handler.postDelayed(deadline, left.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
    }

    private companion object {
        const val MIN_WAIT_MS = 50L
        const val MAX_WAIT_MS = 30_000L
    }
}
