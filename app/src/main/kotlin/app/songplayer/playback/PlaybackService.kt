package app.songplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import app.songplayer.MainActivity
import app.songplayer.R
import app.songplayer.core.browse.BrowseFolder
import app.songplayer.core.browse.BrowseIds
import app.songplayer.core.browse.BrowseTree
import app.songplayer.core.library.SongKey
import app.songplayer.core.browse.ControllerPolicy
import app.songplayer.core.browse.ResolvedQueue
import app.songplayer.core.voice.VoiceResolver
import app.songplayer.SongPlayerApp
import app.songplayer.core.playback.PlaybackRules
import app.songplayer.core.sleep.SleepState
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The one playback service (Technical Design 5.5). It owns the [ExoPlayer] and one media session; the screens,
 * the notification, the lock screen and headset buttons are all controllers of that session, so they cannot
 * disagree. Media3 starts and stops the foreground notification with playback.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    // Database work (the "Can't play" marks, the saved queue and position, reading them back) happens on this one
    // thread, in order, never on the player's.
    // Its own thread, so that a slow browse of a big library cannot delay saving the playback position.
    private val browseThread = Executors.newSingleThreadExecutor { task -> Thread(task, "car-browse").apply { isDaemon = true } }
    private val background = Executors.newSingleThreadExecutor { task -> Thread(task, "playback-db").apply { isDaemon = true } }
    private var wiring: PlaybackWiring? = null

    override fun onCreate() {
        super.onCreate()
        val player = createPlayer(this)
        val container = (application as SongPlayerApp).container
        val wiring = PlaybackWiring(
            player,
            container.resumeStore,
            container.songStore,
            library = { container.songStore.indexed().map { it.song } },
            log = container.log,
            background = background,
            handler = Handler(player.applicationLooper),
            onSleepChanged = ::publishSleep,
            onWidget = container.widgetUpdater::onChange,
        )
        this.wiring = wiring
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val callback = QueueCallback(
            wiring,
            CarLibrary(container, this),
            MoreExecutors.listeningDecorator(browseThread),
            Executor { task -> mainHandler.post(task) },
        )
        // The media notification wears the app's own small icon (Media3's default is a generic one).
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_status_icon) })
        session = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(CoverBitmapLoader(contentResolver))
            .setSessionActivity(openApp)
            .build()
        wiring.start()
        // A hand edit or an Undo changes what a song is called: the items of the queue that hold it get the new text, without a restart.
        detailsListener = container.detailsStore.onChanged { keys -> if (!background.isShutdown) background.execute { refreshQueueItems(container, player, keys) } }
        updateModeButtons()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateModeButtons()

            override fun onRepeatModeChanged(repeatMode: Int) = updateModeButtons()
        })
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var detailsListener: AutoCloseable? = null

    /** Reads the songs off the main thread (the database), then replaces the queue items that hold them on it; the playing song carries on. */
    private fun refreshQueueItems(container: app.songplayer.AppContainer, player: Player, keys: Set<String>) {
        val songs = keys.mapNotNull { container.songStore.get(SongKey(it)) }.associateBy { it.key.value }
        if (songs.isEmpty()) return
        mainHandler.post {
            for (i in 0 until player.mediaItemCount) {
                val old = player.getMediaItemAt(i)
                val song = songs[old.mediaId] ?: continue
                val extras = old.mediaMetadata.extras
                val from = extras?.getString(EXTRA_PLAYING_FROM)?.let { name -> PlayingFrom.entries.firstOrNull { it.name == name } }
                player.replaceMediaItem(i, song.toMediaItem(from, extras?.getBoolean(EXTRA_ADDED_TO_QUEUE) == true))
            }
        }
    }

    /**
     * Shuffle and repeat as buttons of the session, so that Android Auto's Now Playing (and any other controller that draws
     * buttons) shows them with their state; they call the same player commands as the app's own buttons.
     */
    private fun updateModeButtons() {
        val s = session ?: return
        val p = s.player
        val shuffle = CommandButton.Builder(if (p.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
            .setDisplayName(getString(if (p.shuffleModeEnabled) R.string.shuffle_on else R.string.shuffle_off))
            .build()
        val repeat = CommandButton.Builder(
            when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE)
            .setDisplayName(
                getString(
                    when (p.repeatMode) {
                        Player.REPEAT_MODE_ALL -> R.string.repeat_all
                        Player.REPEAT_MODE_ONE -> R.string.repeat_one
                        else -> R.string.repeat_off
                    },
                ),
            )
            .build()
        s.setMediaButtonPreferences(ImmutableList.of(shuffle, repeat))
    }

    /** The end time goes to the screens as session extras; they count down from it (Technical Design 5.6). */
    private fun publishSleep(state: SleepState) {
        session?.setSessionExtras(
            Bundle().apply {
                putLong(EXTRA_SLEEP_ENDS_AT, (state as? SleepState.Running)?.endsAtMs ?: 0L)
                putBoolean(EXTRA_SLEEP_END_OF_SONG, state == SleepState.EndOfSong)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // onTaskRemoved is left at Media3's default: the service stops when nothing is playing and goes on when music is.
    override fun onDestroy() {
        wiring?.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        wiring = null
        // Writes already queued (the last position among them) still run; no thread is left behind for the next service.
        detailsListener?.close()
        background.shutdown()
        browseThread.shutdown()
        super.onDestroy()
    }

    /**
     * Only the app itself and the system (the media notification, the lock screen, headset buttons) are trusted by
     * Media3's own rule (`isTrusted`), and Android Auto's own controller is let in by Media3's check for it; any other app
     * is refused, so none can read the library or control playback (NFR-4). The assistant's controller (the Google app's package,
     * verified by Android) is let in too, for spoken requests. The custom commands (Play next, Add to queue, the sleep timer) are offered to the accepted controllers.
     * The car browses the library through the callbacks below, which run the database reads on [background].
     */
    private class QueueCallback(
        private val wiring: PlaybackWiring,
        private val carLibrary: CarLibrary,
        private val background: ListeningExecutorService,
        private val mainThread: Executor,
    ) : MediaLibrarySession.Callback {
        private val tree = BrowseTree()
        private val voice = VoiceResolver(carLibrary.keyer)

        // `mainThread` is the main looper, which is the player's application looper (the player is built in onCreate).

        /** The caller's package name only when Android has checked that it belongs to the caller's uid; otherwise empty. */
        private fun verifiedPackage(controller: MediaSession.ControllerInfo) = if (controller.isPackageNameVerified) controller.packageName else ""

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!ControllerPolicy.allows(controller.isTrusted, session.isAutoCompanionController(controller), verifiedPackage(controller))) {
                return MediaSession.ConnectionResult.reject()
            }
            val commands: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY_NEXT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_ADD_TO_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SLEEP_END_OF_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        /**
         * A media button, the widget or the car asks to play while the player has no queue (the service may have just
         * started): once the restore at the start has finished, give back the saved queue. If nothing was saved the
         * request fails, which is the right answer. The shuffle and repeat settings were applied by the restore; the
         * queue is marked as new so that, with shuffle on, the song that plays comes first.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.transformAsync(
                wiring.restoreDone,
                { restored ->
                    if (restored == null) {
                        Futures.immediateFailedFuture<MediaSession.MediaItemsWithStartPosition>(UnsupportedOperationException("nothing saved"))
                    } else {
                        wiring.noteNewQueue(restored.items.size, restored.items.firstOrNull()?.mediaId)
                        Futures.immediateFuture<MediaSession.MediaItemsWithStartPosition>(
                            MediaSession.MediaItemsWithStartPosition(restored.items, restored.index, restored.positionMs),
                        )
                    }
                },
                MoreExecutors.directExecutor(),
            )

        /** The top of the car's tree (R-16). Quick on purpose: the car gives up on a slow root, so nothing is read here. */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(BrowseFolder(BrowseIds.ROOT, carLibrary.appName).toCarItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = background.submit<LibraryResult<ImmutableList<MediaItem>>> {
            // Android Auto does not page, so the whole list goes back (it is never longer than the cap).
            val nodes = tree.children(parentId, carLibrary.snapshot(), carLibrary.labels)
            if (nodes == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItemList(ImmutableList.copyOf(nodes.map { it.toCarItem() }), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = background.submit<LibraryResult<MediaItem>> {
            val node = tree.item(mediaId, carLibrary.snapshot(), carLibrary.labels)
            if (node == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(node.toCarItem(), null)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val only = mediaItems.singleOrNull()
            // A spoken request from the car's assistant (R-16) is an item with a search query; "play some music" may come as an
            // item with no id, no uri and no query at all, which is the same as a blank query.
            val isSpoken = only != null && (only.requestMetadata.searchQuery != null ||
                (only.mediaId == MediaItem.DEFAULT_MEDIA_ID && only.requestMetadata.mediaUri == null && only.localConfiguration == null))
            if (isSpoken) {
                val spoken = only?.requestMetadata?.searchQuery
                // No match fails the request; Media3 ignores a failed spoken request, so the car says nothing (Technical Design 5.5).
                val resolved = background.submit<List<MediaItem>> {
                    voice.resolve(spoken, carLibrary.snapshot()).songs.map { it.toMediaItem(PlayingFrom.Songs) }
                }
                return Futures.transform(
                    resolved,
                    { items ->
                        wiring.noteNewQueue(items.size, items.firstOrNull()?.mediaId)
                        MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
                    },
                    mainThread,
                )
            }
            val tapped = mediaItems.singleOrNull()?.mediaId
            if (tapped != null && tapped.indexOf(BrowseIds.SEPARATOR) >= 0) {
                // A song tapped in a car list: the list it was in becomes the queue, starting at it (R-6).
                val resolved = background.submit<ResolvedQueue?> { tree.queueFor(tapped, carLibrary.snapshot()) }
                return Futures.transform(
                    resolved,
                    { queue ->
                        if (queue == null) throw IllegalArgumentException("that song is not in the list any more")
                        val items = queue.songs.map { it.toMediaItem(PlayingFrom.Songs) }
                        wiring.noteNewQueue(items.size, items.firstOrNull()?.mediaId)
                        MediaSession.MediaItemsWithStartPosition(items, queue.index, 0L)
                    },
                    mainThread,
                )
            }
            wiring.noteNewQueue(mediaItems.size, mediaItems.firstOrNull()?.mediaId)
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SET_SLEEP_TIMER -> {
                    val ok = wiring.sleepTimer.start(args.getInt(KEY_MINUTES, -1))
                    return Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                }
                ACTION_SLEEP_END_OF_SONG -> {
                    wiring.sleepTimer.startEndOfSong()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CANCEL_SLEEP_TIMER -> {
                    wiring.sleepTimer.cancel()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            val bundle = args.getBundle(KEY_ITEM)
            if (bundle == null || (customCommand.customAction != ACTION_PLAY_NEXT && customCommand.customAction != ACTION_ADD_TO_QUEUE)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val item = try {
                MediaItem.fromBundle(bundle, MediaLibraryInfo.INTERFACE_VERSION)
            } catch (e: RuntimeException) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            // The player only ever plays songs from Android's audio index (NFR-4), and an item with no address
            // cannot be played at all and would stop the service with an exception.
            val address = item.localConfiguration?.uri?.toString()
            if (!app.songplayer.core.library.MediaAddress.isIndexedSong(address)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            if (customCommand.customAction == ACTION_PLAY_NEXT) wiring.shuffler.playNext(item) else wiring.shuffler.addToQueue(item)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        internal const val ACTION_PLAY_NEXT = "app.songplayer.PLAY_NEXT"
        internal const val ACTION_ADD_TO_QUEUE = "app.songplayer.ADD_TO_QUEUE"
        internal const val KEY_ITEM = "item"
        internal const val ACTION_SET_SLEEP_TIMER = "app.songplayer.SET_SLEEP_TIMER"
        internal const val ACTION_SLEEP_END_OF_SONG = "app.songplayer.SLEEP_END_OF_SONG"
        internal const val ACTION_CANCEL_SLEEP_TIMER = "app.songplayer.CANCEL_SLEEP_TIMER"
        internal const val KEY_MINUTES = "minutes"
        internal const val EXTRA_SLEEP_ENDS_AT = "sleepEndsAtMs"
        internal const val EXTRA_SLEEP_END_OF_SONG = "sleepEndOfSong"

        /**
         * Music audio attributes with audio focus handled by the player (it pauses when a call or another app takes
         * focus and resumes after a temporary loss, not after a permanent one), pause when headphones are unplugged
         * (no automatic resume), the CPU kept awake while playing, and Previous restarting after 3 seconds (R-9).
         */
        fun createPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMaxSeekToPreviousPositionMs(PlaybackRules.PREVIOUS_RESTART_MS)
            .build()
    }
}
