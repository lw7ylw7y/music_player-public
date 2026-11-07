package app.songplayer.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.songplayer.core.library.Song
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.playback.QueueBuilder
import app.songplayer.core.sleep.SleepTimer
import app.songplayer.core.openfile.ExternalFile
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the screens show about the song that is loaded. */
data class NowPlaying(
    /** The song key; the Library marks the row with this key as playing. */
    val mediaId: String,
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    /** Null when the file has no artist tag, so the cover shows a note instead of a letter. */
    val artistTag: String?,
    val album: String,
    val durationMs: Long,
    val isPlaying: Boolean,
    val shuffleOn: Boolean = false,
    /** One of [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL], [Player.REPEAT_MODE_ONE]. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playingFrom: PlayingFrom? = null,
)

/** One row of the queue screen, in the order the songs will play. */
data class QueueRow(
    /** The position in the player's queue when the row was built; removing and jumping use it together with [mediaId] and check both. */
    val index: Int,
    val mediaId: String,
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val artistTag: String?,
    /** Put there by "Play next" or "Add to queue", not part of the list the queue was started from. */
    val added: Boolean,
    val isCurrent: Boolean,
)

/**
 * The sleep timer as the screens see it: when it ends on the clock that ignores date changes (`SystemClock.elapsedRealtime`),
 * or that it is set to "End of song". Both empty means no timer.
 */
data class SleepUi(val endsAtMs: Long? = null, val endOfSong: Boolean = false) {
    val active: Boolean get() = endsAtMs != null || endOfSong

    companion object {
        val None = SleepUi()
    }
}

/** A tap that arrived before the connection to the service did; it is played when the connection comes. */
private data class PendingTap(val songs: List<Song>, val index: Int, val source: PlayingFrom, val shuffle: Boolean)

/**
 * The screens' remote control for the playback service's session (a `MediaController`; Technical Design 4.2).
 * The screens never hold the player. Connect while a screen is visible and release when it is not: playback
 * itself belongs to the service and goes on.
 */
class PlaybackClient(private val context: Context, private val log: AppLog) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var tapAtNanos = 0L

    /** A tap that arrived before the controller had connected (a cold start); it is played as soon as it has. */
    private var pendingTap: PendingTap? = null

    /** A file opened from another app before the controller had connected; played when it has. */
    private var pendingFile: ExternalFile? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _sleep = MutableStateFlow(SleepUi.None)

    /** The sleep timer, kept by the service and published to every screen; the screens count down from its end time. */
    val sleep: StateFlow<SleepUi> = _sleep

    /** The minutes the sleep timer offers: 15, 30, 45 and 60. */
    val sleepChoices: List<Int> = SleepTimer.ALLOWED

    private val _queueVersion = MutableStateFlow(0)

    /** Changes whenever the queue, its order or the song that is playing changes; the queue screen rebuilds on it. */
    val queueVersion: StateFlow<Int> = _queueVersion

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                _queueVersion.value += 1
            }
            if (tapAtNanos != 0L && events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
                log.event(LogCode.PLAY_TAP_TO_SOUND_MS, ((System.nanoTime() - tapAtNanos) / 1_000_000).toInt())
                tapAtNanos = 0L
            }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            _sleep.value = sleepOf(extras)
        }
    }

    private fun sleepOf(extras: Bundle): SleepUi {
        val ends = extras.getLong(PlaybackService.EXTRA_SLEEP_ENDS_AT, 0L)
        return SleepUi(endsAtMs = ends.takeIf { it > 0L }, endOfSong = extras.getBoolean(PlaybackService.EXTRA_SLEEP_END_OF_SONG, false))
    }

    /** Starts the sleep timer for [minutes] (one of [sleepChoices]); the service refuses any other number. */
    fun startSleepTimer(minutes: Int) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.KEY_MINUTES, minutes) },
        )
    }

    /** The sleep timer set to "End of song": playback pauses when the song that is playing ends. */
    fun startSleepEndOfSong() {
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(PlaybackService.ACTION_SLEEP_END_OF_SONG, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun cancelSleepTimer() {
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(PlaybackService.ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        future = pending
        pending.addListener({
            // A controller released before it connected has nothing to do.
            if (future !== pending) return@addListener
            val connected = runCatching { pending.get() }.getOrNull()
            if (connected == null) {
                // The service refused or died. Forget the attempt so the next start tries again, and drop the tap.
                log.event(LogCode.PLAYBACK_CONNECT_FAILED)
                future = null
                pendingTap = null
                pendingFile = null
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            _sleep.value = sleepOf(connected.sessionExtras)
            publish(connected)
            _queueVersion.value += 1
            pendingTap?.let { play(it.songs, it.index, it.source, it.shuffle) }
            pendingTap = null
            pendingFile?.let { playFile(it) }
            pendingFile = null
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        tapAtNanos = 0L
        // The last song stays on screen until the next connection refreshes it, so coming back to the app does not
        // flicker. A tap that was still waiting for the connection is kept and played when it comes.
    }

    /**
     * Tapping a song in a list: that list becomes the queue, starting at the tapped song (R-6). With [shuffle] the
     * shuffle setting is turned on first (a playlist's Shuffle button); otherwise it is left as it is.
     */
    fun play(songs: List<Song>, index: Int, source: PlayingFrom, shuffle: Boolean = false) {
        val started = System.nanoTime()
        val queue = QueueBuilder.fromList(songs, index)
        val c = controller
        if (c == null) {
            pendingTap = PendingTap(queue.songs, queue.startIndex, source, shuffle)
            return
        }
        val items: List<MediaItem> = queue.songs.map { it.toMediaItem(source) }
        if (debuggable) log.event(LogCode.PLAY_QUEUE_BUILD_MS, ((System.nanoTime() - started) / 1_000_000).toInt())
        tapAtNanos = if (debuggable) started else 0L
        if (shuffle) c.shuffleModeEnabled = true
        c.setMediaItems(items, queue.startIndex, 0L)
        c.prepare()
        c.play()
    }

    /**
     * A file opened from another app: the queue becomes that one file and it plays once and stops (UX Design D6), so
     * repeat is switched off for it; the shuffle setting does not matter for one song. The caller has already checked that it is
     * an MP3 that can be read, so the queue it replaces is only replaced by something that can play.
     */
    fun playFile(file: ExternalFile) {
        val c = controller
        if (c == null) {
            pendingFile = file
            return
        }
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItems(listOf(file.toMediaItem()), 0, 0L)
        c.prepare()
        c.play()
    }

    /**
     * "Play the cut" of the split review (Technical Design 5.9): the queue becomes the one song, clipped to [startMs] until [endMs] of the
     * original, and plays once. It replaces the queue and the review screen says so; nothing is written. Precision is that of the decoder.
     */
    fun playClip(song: Song, startMs: Long, endMs: Long) {
        val c = controller ?: return
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItems(listOf(song.toMediaItem().buildUpon().setClippingConfiguration(clip).build()), 0, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            // After a restart the saved queue is loaded but not prepared; Play must prepare it first.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** After 3 seconds of the song this restarts it, before that it goes to the previous song (the player's rule). */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Off, then all, then one, then off again (UX Design 4.5). */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** "Play next": the song plays right after the one that is playing, with shuffle on or off (R-13). */
    fun playNext(song: Song): Boolean = sendQueueCommand(PlaybackService.ACTION_PLAY_NEXT, song)

    /** "Add to queue": the song goes to the end of the queue (R-13). */
    fun addToQueue(song: Song): Boolean = sendQueueCommand(PlaybackService.ACTION_ADD_TO_QUEUE, song)

    // The plain bundle of a MediaItem leaves out its address (the file to play); the service would get an item with
    // nothing to play. This variant keeps it.
    @OptIn(UnstableApi::class)
    /** True when the command was sent; false when there is no connection to the service, so no message should claim success. */
    private fun sendQueueCommand(action: String, song: Song): Boolean {
        val c = controller ?: return false
        val args = Bundle().apply {
            putBundle(PlaybackService.KEY_ITEM, song.toMediaItem(addedToQueue = true).toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION))
        }
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        return true
    }

    /** The song at [index] in the queue is dropped; if it is the one playing, the next one starts (the player's rule). */
    fun removeFromQueue(index: Int, mediaId: String) {
        val c = controller ?: return
        // The row was built a moment ago; if the queue has changed since, the position may now hold another song.
        if (index in 0 until c.mediaItemCount && c.getMediaItemAt(index).mediaId == mediaId) c.removeMediaItem(index)
    }

    /** A tap on a queue row: play that song now. */
    fun jumpTo(index: Int, mediaId: String) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount || c.getMediaItemAt(index).mediaId != mediaId) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    /** The playing position; the Now Playing screen reads it twice a second while it is on screen. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    /**
     * The queue from the song that is playing onward, in play order (shuffled when shuffle is on). Built on demand by
     * the queue screen, so a 5,000-song queue costs nothing while the screen is closed.
     */
    fun queueRows(): List<QueueRow> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        val current = c.currentMediaItemIndex
        if (timeline.isEmpty || current == C.INDEX_UNSET) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val rows = ArrayList<QueueRow>()
        var index = current
        while (index != C.INDEX_UNSET) {
            rows += rowOf(c.getMediaItemAt(index), index, isCurrent = index == current)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return rows
    }

    private fun rowOf(item: MediaItem, index: Int, isCurrent: Boolean): QueueRow {
        val meta = item.mediaMetadata
        val extras = meta.extras
        val artist = meta.artist?.toString() ?: Song.UNKNOWN_ARTIST
        return QueueRow(
            index = index,
            mediaId = item.mediaId,
            mediaStoreId = extras?.getLong(EXTRA_MEDIA_STORE_ID) ?: 0L,
            title = meta.title?.toString() ?: "",
            artist = artist,
            artistTag = if (extras?.getBoolean(EXTRA_HAS_ARTIST) == true) artist else null,
            added = extras?.getBoolean(EXTRA_ADDED_TO_QUEUE) == true,
            isCurrent = isCurrent,
        )
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        if (item == null) {
            _nowPlaying.value = null
            return
        }
        val meta = item.mediaMetadata
        val extras = meta.extras
        val artist = meta.artist?.toString() ?: Song.UNKNOWN_ARTIST
        _nowPlaying.value = NowPlaying(
            mediaId = item.mediaId,
            mediaStoreId = extras?.getLong(EXTRA_MEDIA_STORE_ID) ?: 0L,
            title = meta.title?.toString() ?: "",
            artist = artist,
            artistTag = if (extras?.getBoolean(EXTRA_HAS_ARTIST) == true) artist else null,
            album = meta.albumTitle?.toString() ?: Song.UNKNOWN_ALBUM,
            durationMs = player.duration.takeIf { it > 0 } ?: meta.durationMs ?: 0L,
            isPlaying = player.isPlaying,
            shuffleOn = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            playingFrom = playingFromOf(player),
        )
    }

    /** The list the queue came from: the first song that carries it (songs added later carry none). */
    private fun playingFromOf(player: Player): PlayingFrom? {
        for (i in 0 until player.mediaItemCount) {
            val name = player.getMediaItemAt(i).mediaMetadata.extras?.getString(EXTRA_PLAYING_FROM) ?: continue
            return PlayingFrom.entries.firstOrNull { it.name == name }
        }
        return null
    }
}
