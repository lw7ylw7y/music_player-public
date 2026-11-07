package app.songplayer.playback

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.annotation.OptIn
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.TestMp3
import app.songplayer.core.library.song
import app.songplayer.core.log.AppLog
import app.songplayer.core.playback.PlaybackRules
import app.songplayer.core.resume.ResumeState
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests for playback (story B1): the real service, the real player and Media3's own session, on files
 * these tests create and delete themselves. Audio-focus and unplugging-headphones behaviour is Media3's and is
 * checked by hand on the phone (the story's manual steps 4 and 5).
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun openApp() = AppInForeground.start()

        @AfterClass
        @JvmStatic
        fun closeApp() = AppInForeground.stop()
    }

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val toClean = mutableListOf<() -> Unit>()

    private val container get() = (context.applicationContext as app.songplayer.SongPlayerApp).container
    private val nothingSaved = ResumeState(emptyList(), -1, 0, false, 0, null)

    /** The saved queue lives in the app's database and would be restored into the next test's service. */
    @Before
    fun forgetTheSavedQueueAndBringTheAppToTheFront() {
        container.resumeStore.saveQueue(nothingSaved)
        AppInForeground.ensure()
    }

    @After
    fun cleanUp() {
        toClean.reversed().forEach { runCatching { it() } }
        container.resumeStore.saveQueue(nothingSaved)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> readOnMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ---- the player's configuration (R-9) ------------------------------------------------------

    @Test
    fun Player_Configuration_MusicAudioAttributesAndThreeSecondPreviousRule() {
        val player = readOnMain { PlaybackService.createPlayer(context) }
        toClean += { onMain { player.release() } }
        val attributes = readOnMain { player.audioAttributes }
        assertEquals(C.USAGE_MEDIA, attributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, attributes.contentType)
        assertEquals(PlaybackRules.PREVIOUS_RESTART_MS, readOnMain { player.maxSeekToPreviousPosition })
    }

    private fun twoTenSecondFiles(): List<MediaItem> = listOf("one", "two").map { name ->
        val file = TestMp3.write(File(context.cacheDir, "$name.mp3"), frames = 400) // about 10 seconds
        toClean += { file.delete() }
        MediaItem.fromUri(file.toUri())
    }

    private fun readyPlayerOnSecondSong(): ExoPlayer {
        val player = readOnMain { PlaybackService.createPlayer(context) }
        toClean += { onMain { player.release() } }
        onMain {
            player.setMediaItems(twoTenSecondFiles(), 1, 0L)
            player.prepare()
        }
        waitUntil("the player to be ready") { readOnMain { player.playbackState } == Player.STATE_READY }
        return player
    }

    @Test
    fun CutReview_Play_ClipsTheOriginal() {
        // The split review plays 10 s either side of a cut from the original (Technical Design 5.9): a clipped item of a 40 s file
        // is 20 s long and ends there; the file itself is untouched.
        val file = TestMp3.write(File(context.cacheDir, "clip.mp3"), frames = 1560) // about 40 seconds
        toClean += { file.delete() }
        val size = file.length()
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(10_000).setEndPositionMs(30_000).build()
        val player = readOnMain { PlaybackService.createPlayer(context) }
        toClean += { onMain { player.release() } }
        onMain {
            player.setMediaItems(listOf(MediaItem.fromUri(file.toUri()).buildUpon().setClippingConfiguration(clip).build()), 0, 0L)
            player.prepare()
        }
        waitUntil("the clipped item to be ready") { readOnMain { player.playbackState } == Player.STATE_READY }
        val duration = readOnMain { player.duration }
        assertTrue("the clip is 20 s, was $duration ms", duration in 19_800L..20_200L)
        assertEquals(size, file.length())
    }

    @Test
    fun Previous_After3Seconds_RestartsSong() {
        val player = readyPlayerOnSecondSong()
        onMain {
            player.seekTo(5_000)
            player.seekToPrevious()
        }
        assertEquals(1, readOnMain { player.currentMediaItemIndex })
        assertTrue(readOnMain { player.currentPosition } < 1_000)
    }

    @Test
    fun Previous_Within3Seconds_GoesToPreviousSong() {
        val player = readyPlayerOnSecondSong()
        onMain {
            player.seekTo(1_000)
            player.seekToPrevious()
        }
        assertEquals(0, readOnMain { player.currentMediaItemIndex })
    }

    // ---- the real service ----------------------------------------------------------------------

    private fun connect(client: PlaybackClient) {
        onMain { client.connect() }
        toClean += { onMain { client.release() } }
    }

    private fun testController(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        var controller: MediaController? = null
        onMain {
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        }
        waitUntil("a controller to connect") { controller != null }
        val connected = controller!!
        toClean += {
            onMain {
                connected.repeatMode = Player.REPEAT_MODE_OFF
                connected.shuffleModeEnabled = false
                connected.stop()
                connected.clearMediaItems()
                connected.release()
            }
        }
        return connected
    }

    @Test
    fun Service_TapSong_QueueIsTheTappedList() {
        val songs = seedSongs(3, "B1 queue")
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        // The tap comes at once, before the controller has connected: it must not be lost.
        onMain { client.play(songs, 1, PlayingFrom.Songs) }
        waitUntil("the tapped song to be the current one") { client.nowPlaying.value?.mediaId == songs[1].key.value }

        val controller = testController()
        assertEquals(3, readOnMain { controller.mediaItemCount })
        assertEquals(songs.map { it.key.value }, readOnMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } })
        assertEquals(1, readOnMain { controller.currentMediaItemIndex })
    }

    @Test
    fun Service_ShuffleOnThenTapSong_TappedSongPlaysFirst() {
        val songs = seedSongs(6, "B2 shuffle")
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain { controller.shuffleModeEnabled = true }
        onMain { client.play(songs, 3, PlayingFrom.Songs) }
        waitUntil("the tapped song to be the current one") { client.nowPlaying.value?.mediaId == songs[3].key.value }
        // Nothing comes before the tapped song in the shuffled order, so every song plays once (R-7).
        waitUntil("the tapped song to come first in the shuffled order") { readOnMain { controller.previousMediaItemIndex } == C.INDEX_UNSET }
        assertEquals(6, readOnMain { controller.mediaItemCount })
    }

    @Test
    fun Service_PlayNextAndAddToQueue_WhileShuffled() {
        val songs = seedSongs(5, "B2 queue")
        val playNext = seedSong("B2 next", frames = 40)
        val last = seedSong("B2 last", frames = 40)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain { client.play(songs, 1, PlayingFrom.Songs) }
        waitUntil("the queue") { readOnMain { controller.mediaItemCount } == 5 }
        onMain { controller.shuffleModeEnabled = true }

        onMain { client.playNext(playNext) }
        waitUntil("Play next to put the song next") {
            readOnMain { controller.mediaItemCount == 6 && controller.getMediaItemAt(controller.nextMediaItemIndex).mediaId == playNext.key.value }
        }
        onMain { client.addToQueue(last) }
        waitUntil("Add to queue to put the song last") {
            readOnMain {
                val t = controller.currentTimeline
                var i = t.getFirstWindowIndex(true)
                var end = i
                while (i != C.INDEX_UNSET) { end = i; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true) }
                controller.mediaItemCount == 7 && controller.getMediaItemAt(end).mediaId == last.key.value
            }
        }
        // The rows of the queue screen follow the same order and mark both songs as added.
        val rows = readOnMain { client.queueRows() }
        assertEquals(playNext.key.value, rows[1].mediaId)
        assertTrue(rows[1].added)
        assertEquals(last.key.value, rows.last().mediaId)
        assertTrue(rows.last().added)
    }

    @Test
    @OptIn(UnstableApi::class)
    fun Service_QueueCommandWithAnAddressOutsideTheMediaIndex_IsRefused() {
        val songs = seedSongs(3, "B2 refused")
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain { client.play(songs, 0, PlayingFrom.Songs) }
        waitUntil("the queue") { readOnMain { controller.mediaItemCount } == 3 }

        val foreign = MediaItem.fromUri("file:///data/local/tmp/elsewhere.mp3")
        val args = Bundle().apply { putBundle("item", foreign.toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION)) }
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>? = null
        onMain { future = controller.sendCustomCommand(SessionCommand("app.songplayer.PLAY_NEXT", Bundle.EMPTY), args) }
        val result = future!!.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(androidx.media3.session.SessionError.ERROR_BAD_VALUE, result.resultCode)
        assertEquals("nothing was added", 3, readOnMain { controller.mediaItemCount })
    }

    // ---- resume (R-11) ------------------------------------------------------------------------

    @Test
    fun Service_TappedQueue_IsSavedForTheNextStart() {
        val songs = seedSongs(3, "B4 saved", frames = 40)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain {
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.shuffleModeEnabled = true
        }
        onMain { client.play(songs, 1, PlayingFrom.Songs) }
        // The service writes what a restart needs into the app's database: queue, song, settings and the shuffle order.
        waitUntil("the queue to be saved") { container.resumeStore.load()?.queue?.map { it.songKey } == songs.map { it.key } }
        waitUntil("the shuffle order to be saved") { container.resumeStore.load()?.shuffleOn == true }
        val saved = container.resumeStore.load()!!
        assertEquals(songs.map { it.key }, saved.queue.map { it.songKey })
        assertEquals(1, saved.currentIndex)
        assertEquals(Player.REPEAT_MODE_ALL, saved.repeatMode)
        assertEquals("Songs", saved.source)
        assertEquals(setOf(0, 1, 2), saved.queue.mapNotNull { it.shuffleRank }.toSet())
    }

    // ---- songs that cannot play (R-12) ----------------------------------------------------------

    private val store get() = (context.applicationContext as app.songplayer.SongPlayerApp).container.songStore

    /** Forgets the marks these songs get during the test, so they do not stay in the app's database. */
    private fun forgetMarks(vararg songs: Song) {
        // A mark is written on another thread; give a late write time to land before the marks are removed.
        toClean += { Thread.sleep(500); songs.forEach { store.clearFailure(it.key) } }
    }

    @Test
    fun Service_SourceError_MarksSongAndSkips() {
        val bad = song("b3-missing.mp3", id = 910_100)
        val after = seedSong("B3 after", frames = 400)
        forgetMarks(bad, after)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        onMain { client.play(listOf(bad, after), 0, PlayingFrom.Songs) }

        waitUntil("the song after the bad one to play") { client.nowPlaying.value?.let { it.mediaId == after.key.value && it.isPlaying } == true }
        waitUntil("the bad song to be marked") { bad.key.value in store.failedKeys() }
        assertTrue("the song that played is not marked", after.key.value !in store.failedKeys())
    }

    @Test
    fun Service_AllSongsBad_RepeatAll_Stops() {
        val bad = (0 until 3).map { song("b3-gone$it.mp3", id = 910_110L + it) }
        forgetMarks(*bad.toTypedArray())
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain { controller.repeatMode = Player.REPEAT_MODE_ALL }
        onMain { client.play(bad, 0, PlayingFrom.Songs) }

        waitUntil("every bad song to be marked") { store.failedKeys().containsAll(bad.map { it.key.value }) }
        // After the last try playback stops: it does not go round again. The position stays put for two seconds.
        waitUntil("playback to stop with an error") {
            readOnMain { controller.playerError != null && !controller.isPlaying && controller.playbackState == Player.STATE_IDLE }
        }
        val position = readOnMain { controller.currentMediaItemIndex }
        Thread.sleep(2_000)
        assertEquals(position, readOnMain { controller.currentMediaItemIndex })
        assertTrue(readOnMain { controller.playerError != null })
    }

    @Test
    fun Service_RepeatOneOnBadSong_MovesToNext() {
        val bad = song("b3-gone-r1.mp3", id = 910_120)
        val good = seedSong("B3 repeat one", frames = 400)
        forgetMarks(bad, good)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        onMain { controller.repeatMode = Player.REPEAT_MODE_ONE }
        onMain { client.play(listOf(bad, good), 0, PlayingFrom.Songs) }
        waitUntil("the good song to play although repeat one was on") {
            client.nowPlaying.value?.let { it.mediaId == good.key.value && it.isPlaying } == true
        }
        waitUntil("the bad song to be marked") { bad.key.value in store.failedKeys() }
    }

    @Test
    fun Service_RepeatOffLastSongBad_MarksItAndStaysStopped() {
        val first = seedSong("B3 first", frames = 12)
        val bad = song("b3-gone-last.mp3", id = 910_130)
        forgetMarks(first, bad)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        val controller = testController()
        // Started on the last song, which is bad: there is no next song to move to.
        onMain { client.play(listOf(first, bad), 1, PlayingFrom.Songs) }

        waitUntil("the last song to be marked") { bad.key.value in store.failedKeys() }
        waitUntil("playback to stop with an error") {
            readOnMain { controller.playerError != null && !controller.isPlaying && controller.playbackState == Player.STATE_IDLE }
        }
        assertEquals(1, readOnMain { controller.currentMediaItemIndex })
        assertTrue("the other song is not marked", first.key.value !in store.failedKeys())
    }

    @Test
    fun Service_UpcomingSongMissing_IsMarkedAndTheQueueCarriesOn() {
        val first = seedSong("B3 first of three", frames = 80)
        val bad = song("b3-gone-middle.mp3", id = 910_140)
        val last = seedSong("B3 last of three", frames = 400)
        forgetMarks(first, bad, last)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        onMain { client.play(listOf(first, bad, last), 0, PlayingFrom.Songs) }

        // Whether the player reports the missing song early (while the first plays) or on reaching it, the bad song,
        // and only that one, is marked and the last song plays.
        waitUntil("the missing song to be marked") { bad.key.value in store.failedKeys() }
        waitUntil("the last song to play") { client.nowPlaying.value?.let { it.mediaId == last.key.value && it.isPlaying } == true }
        assertTrue("the songs that could play are not marked", first.key.value !in store.failedKeys() && last.key.value !in store.failedKeys())
    }

    @Test
    fun Service_PlayingASong_ClearsItsMark() {
        val song = seedSong("B3 recovered", frames = 400)
        forgetMarks(song)
        store.markFailed(song.key, 1L)
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        onMain { client.play(listOf(song), 0, PlayingFrom.Songs) }
        waitUntil("the song to play") { client.nowPlaying.value?.isPlaying == true }
        waitUntil("the mark to go") { song.key.value !in store.failedKeys() }
    }

    // ---- the notification (R-9) ----------------------------------------------------------------

    private fun seedSong(title: String, frames: Int = 400): Song = TestSongs.seed(context, title, frames, toClean)

    /** Real, short songs in the media index. The queue tests use these so that no song fails and moves the queue on. */
    private fun seedSongs(count: Int, prefix: String, frames: Int = 40): List<Song> = (0 until count).map { seedSong("$prefix $it", frames = frames) }

    @Test
    fun Notification_Shown_WithTitleArtistAndButtons() {
        val seeded = seedSong("B1 notification title")
        val client = PlaybackClient(context, AppLog(emptyList()))
        connect(client)
        // A second controller watches the player from the start, so a failure can say what happened to it.
        val probe = testController()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        onMain {
            probe.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    events += "playWhenReady=$playWhenReady(reason $reason)"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    events += "state=$state"
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    events += "suppression=$reason"
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    events += "error=${error.errorCodeName}"
                }
            })
        }
        onMain { client.play(listOf(seeded, seeded.copy(key = SongKey("external_primary:Music/x2.mp3"))), 0, PlayingFrom.Songs) }
        try {
            waitUntil("the song to be playing") { client.nowPlaying.value?.isPlaying == true }
        } catch (e: AssertionError) {
            throw AssertionError(
                e.message + readOnMain {
                    " (state=${probe.playbackState}, playWhenReady=${probe.playWhenReady}, suppression=${probe.playbackSuppressionReason}, " +
                        "items=${probe.mediaItemCount}, events=$events)"
                },
            )
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        waitUntil("the media notification") {
            manager.activeNotifications.any { it.notification.extras.getCharSequence("android.title") == "B1 notification title" }
        }
        val notification = manager.activeNotifications.first { it.notification.extras.getCharSequence("android.title") == "B1 notification title" }.notification
        assertEquals("Test artist", notification.extras.getCharSequence("android.text")?.toString())
        assertNotNull(notification.actions)
        assertTrue("expected previous, play or pause, and next; found ${notification.actions?.size}", (notification.actions?.size ?: 0) >= 2)
        onMain { client.togglePlay() }
    }
}
