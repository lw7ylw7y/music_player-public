package app.songplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.playback.PlaybackErrors
import app.songplayer.core.playback.SkipDecision
import app.songplayer.core.playback.SkipPolicy
import app.songplayer.library.db.RoomSongStore
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A song that cannot be played is marked, skipped and not mentioned (R-12, Technical Design 5.3). A failure is
 * recorded under the song's key, then the player moves to the next song, even when repeat one is on; when as many
 * songs in a row have failed as the queue holds, playback stops where it is. The first song that plays clears the count
 * and its own mark. Database writes go to [io], never to the player's thread.
 */
@OptIn(UnstableApi::class)
class PlaybackFailures(
    private val player: Player,
    private val store: RoomSongStore,
    private val log: AppLog,
    private val io: Executor,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : Player.Listener {
    private var inARow = 0
    private var skipping = false

    override fun onPlayerError(error: PlaybackException) {
        log.event(LogCode.PLAYBACK_ITEM_FAILED, error.errorCode)
        // A skip that started earlier has finished by now, whether or not it reported a transition.
        skipping = false
        if (!PlaybackErrors.isTheSongsFault(error.errorCode)) {
            // The audio output failed, or permission went: not this song's doing. Nothing is marked or skipped.
            inARow = 0
            return
        }
        // The player can report that a song further down the queue cannot be loaded while the current one still plays,
        // so the failed song is the one the error names, not necessarily the one that is playing.
        val failed = failedIndex(error)
        if (failed !in 0 until player.mediaItemCount) return
        player.getMediaItemAt(failed).mediaId.let { key -> write { store.markFailed(SongKey(key), nowSec()) } }
        if (failed != player.currentMediaItemIndex) {
            // Taking the bad song out of the queue lets the current song carry on; it is marked, so it is still listed.
            player.removeMediaItem(failed)
            player.prepare()
            return
        }
        inARow++
        when (SkipPolicy.next(inARow, player.mediaItemCount)) {
            SkipDecision.Skip -> if (player.hasNextMediaItem()) {
                skipping = true
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            SkipDecision.Stop -> inARow = 0
        }
    }

    /** The queue position of the song the error is about; the current one when the player does not say. */
    private fun failedIndex(error: PlaybackException): Int {
        val periodUid = (error as? ExoPlaybackException)?.mediaPeriodId?.periodUid
        val timeline = player.currentTimeline
        if (periodUid != null) {
            val period = timeline.getIndexOfPeriod(periodUid)
            if (period != C.INDEX_UNSET) return timeline.getPeriod(period, Timeline.Period()).windowIndex
        }
        return player.currentMediaItemIndex
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Our own skip keeps the count; a new queue or a tap by the user starts it again.
        if (skipping) skipping = false else inARow = 0
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        inARow = 0
        player.currentMediaItem?.mediaId?.let { key -> write { store.clearFailure(SongKey(key)) } }
    }

    private fun write(action: () -> Unit) {
        try {
            io.execute {
                try {
                    action()
                } catch (e: Exception) {
                    // A mark that is not written only means the song shows no mark; it must never take the app down.
                    log.event(LogCode.PLAY_FAILURE_WRITE_FAILED)
                }
            }
        } catch (e: RejectedExecutionException) {
            // The service has ended; there is nothing left to write to.
        }
    }
}
