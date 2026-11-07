package app.songplayer.playback

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.openfile.ExternalFile
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeRules
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.resume.ResumeStore
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Saves the queue and where the player is in it, so that the next start can show the last song and play it from
 * there (R-11, Technical Design 5.4). The queue is saved whole whenever it changes (a new list, Play next, Add to
 * queue, a removal, shuffle or repeat changing); the position is saved when the song changes, on pause, at the end,
 * when the service goes, and every [ResumeRules.POSITION_SAVE_INTERVAL_MS] while playing. Everything is read on the
 * player's thread and written, in order, on [io]. Writing stays off until [enable] is called after the restore, so the
 * restore itself is not saved back.
 */
class ResumeWriter(
    private val player: Player,
    private val store: ResumeStore,
    private val log: AppLog,
    private val io: Executor,
    private val handler: Handler,
) : Player.Listener {
    private var enabled = false
    private var queueWritePending = false
    private val tick = object : Runnable {
        override fun run() {
            savePosition()
            if (player.isPlaying) handler.postDelayed(this, ResumeRules.POSITION_SAVE_INTERVAL_MS)
        }
    }

    /** Called once the restore has been tried. If a queue was started in the meantime it is saved now. */
    fun enable() {
        enabled = true
        if (player.mediaItemCount > 0) saveQueue()
    }

    /** The service is going away: the last position is written before the player is released. */
    fun flush() {
        handler.removeCallbacks(tick)
        if (!enabled) return
        if (queueWritePending) {
            queueWritePending = false
            snapshotQueue()
        }
        savePosition()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()

    override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = savePosition()

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // A seek while paused would otherwise be lost to a kill.
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) savePosition()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, ResumeRules.POSITION_SAVE_INTERVAL_MS) else savePosition()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) savePosition()
    }

    /** The queue, its order or a setting changed in a way the player does not announce by itself: save it again. */
    fun queueChanged() = saveQueue()

    /**
     * Several events usually come together (a Play next is a timeline change and an order change): the queue is
     * written once, from the state at the end of that turn of the main loop.
     */
    private fun saveQueue() {
        if (!enabled || queueWritePending) return
        queueWritePending = true
        handler.post {
            if (queueWritePending) {
                queueWritePending = false
                snapshotQueue()
            }
        }
    }

    private fun snapshotQueue() {
        val count = player.mediaItemCount
        val ranks: IntArray? = if (player.shuffleModeEnabled && count > 0) {
            val order = shuffledOrder(player.currentTimeline)
            IntArray(count).also { r -> order.forEachIndexed { rank, position -> r[position] = rank } }
        } else {
            null
        }
        val entries = (0 until count).map { i ->
            val item = player.getMediaItemAt(i)
            val key = SongKey(item.mediaId)
            // A file opened from another app is not in the library; its name is saved with it (it is the item's title).
            val name = if (ExternalFile.uriOf(key) != null) item.mediaMetadata.title?.toString() else null
            QueueEntry(key, item.mediaMetadata.extras?.getBoolean(EXTRA_ADDED_TO_QUEUE) == true, ranks?.get(i), name)
        }
        val state = ResumeState(
            queue = entries,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffleOn = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            source = firstSource(),
        )
        write { store.saveQueue(state) }
    }

    /** A queue that has played out is saved at the start of its last song, not at the very end of it. */
    private fun savePosition(positionMs: Long = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition) {
        if (!enabled) return
        val item = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        write { store.savePosition(index, SongKey(item.mediaId), positionMs) }
    }

    private fun firstSource(): String? {
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).mediaMetadata.extras?.getString(EXTRA_PLAYING_FROM)?.let { return it }
        }
        return null
    }

    private fun write(action: () -> Unit) {
        try {
            io.execute {
                try {
                    action()
                } catch (e: Exception) {
                    // Playback goes on; the state is written again at the next change.
                    log.event(LogCode.RESUME_WRITE_FAILED)
                }
            }
        } catch (e: RejectedExecutionException) {
            // The service has ended and its writer is shut down; a late event has nothing left to save to.
        }
    }
}
