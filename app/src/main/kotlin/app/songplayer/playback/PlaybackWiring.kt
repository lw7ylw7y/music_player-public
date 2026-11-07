package app.songplayer.playback

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.songplayer.core.Clock
import app.songplayer.core.library.Song
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.resume.ResumeStore
import app.songplayer.core.widget.WidgetSong
import app.songplayer.core.widget.WidgetState
import app.songplayer.core.sleep.SleepState
import app.songplayer.library.db.RoomSongStore
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor

/**
 * Everything the playback service attaches to its player (Technical Design 5.3 and 5.4): the order of a shuffled
 * queue, the "Can't play" marks, saving the queue and position, and giving the saved queue back at the start. It
 * lives apart from the service so that a test can build it around a fresh player, which is how a restart is tested:
 * the service itself is kept alive by Android's own media interface and cannot be stopped and started on demand.
 *
 * Call [start] once, on the player's thread, and [stop] before the player is released.
 */
@OptIn(UnstableApi::class)
class PlaybackWiring(
    private val player: ExoPlayer,
    private val resumeStore: ResumeStore,
    private val marks: RoomSongStore,
    /** The songs the library has now; read off the player's thread. */
    private val library: () -> List<Song>,
    private val log: AppLog,
    /** The one background thread for database work, in order. */
    private val background: Executor,
    private val handler: Handler,
    clock: Clock = SystemClockSource,
    onSleepChanged: (SleepState) -> Unit = {},
    /** Told, on the player's thread, whenever what the widget shows may have changed; the receiver drops repeats. */
    private val onWidget: (WidgetState) -> Unit = {},
) {
    val sleepTimer = SleepTimerController(player, clock, handler, onChanged = onSleepChanged)
    private val writer = ResumeWriter(player, resumeStore, log, background, handler)
    val shuffler = QueueShuffler(player, onOrderSet = { writer.queueChanged() })

    /** Completes, on the player's thread, when the restore has been tried; holds what was restored, or null. */
    val restoreDone: SettableFuture<Restored?> = SettableFuture.create()

    private var freshQueue: Pair<Int, String?>? = null
    private var stopped = false

    /** A controller has just asked for a queue of [size] songs starting with [firstMediaId]. */
    fun noteNewQueue(size: Int, firstMediaId: String?) {
        freshQueue = size to firstMediaId
    }

    /**
     * True once, when the player now holds the queue that was asked for; the playing song then comes first when shuffled.
     * A timeline change from anything else (Play next, a removal) does not match, so it cannot undo the order of those
     * edits; a request that failed leaves a mark that never matches.
     */
    private fun takeFreshQueue(): Boolean {
        val wanted = freshQueue ?: return false
        val count = player.mediaItemCount
        val matches = count == wanted.first && (count == 0 || player.getMediaItemAt(0).mediaId == wanted.second)
        if (matches) freshQueue = null
        return matches
    }

    fun start() {
        player.addListener(PlaybackFailures(player, marks, log, background))
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) shuffler.currentFirst()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // A queue just set by a tap, while shuffle is already on: the tapped song comes first (R-7).
                if (takeFreshQueue() && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) {
                    shuffler.currentFirst()
                }
            }
        })
        player.addListener(writer)
        player.addListener(sleepTimer)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Only the song and the play state matter to the widget (R-24); the position never does.
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) {
                    onWidget(widgetState())
                }
            }
        })

        // Give the last queue back, paused and not prepared, as soon as it has been read (R-11). A tap that comes
        // before that is not overwritten, and its queue is saved when the writer is enabled.
        background.execute {
            val restored = try {
                ResumeRestore.read(resumeStore, library())
            } catch (e: Exception) {
                log.event(LogCode.RESUME_READ_FAILED)
                null
            }
            handler.post {
                if (!stopped) {
                    restored?.let { ResumeRestore.apply(player, it) }
                    writer.enable()
                }
                restoreDone.set(restored)
            }
        }
    }

    /** The song in the player and whether it is meant to be playing, as the widget needs them (never the position). */
    private fun widgetState(): WidgetState {
        val item = player.currentMediaItem ?: return WidgetState.Empty
        val extras = item.mediaMetadata.extras
        val song = WidgetSong(
            mediaId = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            mediaStoreId = extras?.getLong(EXTRA_MEDIA_STORE_ID, -1L) ?: -1L,
        )
        return WidgetState(song, playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED)
    }

    /** The last position is written before the player is released. */
    fun stop() {
        stopped = true
        sleepTimer.release()
        // The service is going: the widget keeps the song but stops showing it as playing.
        onWidget(widgetState().copy(playing = false))
        writer.flush()
    }
}
