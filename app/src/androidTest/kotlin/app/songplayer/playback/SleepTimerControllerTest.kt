package app.songplayer.playback

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.Clock
import app.songplayer.core.library.TestMp3
import app.songplayer.core.log.AppLog
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.sleep.SleepState
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests of the sleep timer (story D2): the controller on a real player with a clock the test moves, and the
 * commands through the real service. A song of about two seconds stands in for "the current song"; the clock jumps
 * 15 minutes at once, so nothing waits that long.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerControllerTest {
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

    private class FakeClock(var elapsed: Long = 1_000_000L) : Clock {
        override fun elapsedMs() = elapsed
        override fun wallMs() = 1_700_000_000_000L
    }

    @Before
    fun bringTheAppToTheFront() {
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

    private val changes = mutableListOf<SleepState>()
    private val clock = FakeClock()

    /**
     * A player holding [songs] songs of about ten seconds, with a controller around it, playing (or paused). With [nearEnd]
     * the first song is close to its end, so a test about the end of a song does not wait ten seconds.
     */
    private fun rig(playing: Boolean, repeatOne: Boolean = false, songs: Int = 1, nearEnd: Boolean = false): Pair<ExoPlayer, SleepTimerController> {
        val player = readOnMain { PlaybackService.createPlayer(context) }
        toClean += { onMain { player.release() } }
        val items = (1..songs).map {
            val file = TestMp3.write(File(context.cacheDir, "sleep-${System.nanoTime()}.mp3"), frames = 400)
            toClean += { file.delete() }
            MediaItem.fromUri(file.toUri())
        }
        val controller = readOnMain {
            SleepTimerController(player, clock, Handler(Looper.getMainLooper()), onChanged = { synchronized(changes) { changes += it } })
                .also { player.addListener(it) }
        }
        toClean += { onMain { controller.release() } }
        onMain {
            player.setMediaItems(items)
            if (repeatOne) player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
            player.playWhenReady = playing
        }
        waitUntil("the player to be ready") { readOnMain { player.playbackState } == Player.STATE_READY }
        if (nearEnd) onMain { player.seekTo(player.duration - 1_800) }
        if (playing) waitUntil("it to play") { readOnMain { player.isPlaying } }
        return player to controller
    }

    private fun seen() = synchronized(changes) { changes.toList() }

    // ---- a timer of some minutes: playback pauses when the time runs out ----------------------------

    @Test
    fun Service_TimerExpires_PausesPlaybackAtOnce_InTheMiddleOfTheSong() {
        val (player, timer) = rig(playing = true)
        onMain { timer.start(15) }
        assertTrue(seen().last() is SleepState.Running)
        clock.elapsed += 15 * 60_000L
        onMain { timer.checkNow() }
        assertEquals(SleepState.Idle, seen().last())
        assertFalse("playback is paused", readOnMain { player.playWhenReady })
        assertTrue("in the middle of the song, which has seconds left", readOnMain { player.currentPosition < player.duration - 3_000 })
        assertEquals("the timer ended once", 1, seen().count { it == SleepState.Idle })
        Thread.sleep(1_500)
        assertFalse("and it stays paused", readOnMain { player.isPlaying })
    }

    @Test
    fun Service_TimerExpires_RepeatOne_StillPauses() {
        val (player, timer) = rig(playing = true, repeatOne = true, nearEnd = true)
        onMain { timer.start(30) }
        clock.elapsed += 30 * 60_000L
        onMain { timer.checkNow() }
        assertFalse(readOnMain { player.playWhenReady })
        Thread.sleep(2_500) // a repeat would be playing again by now
        assertFalse(readOnMain { player.isPlaying })
    }

    @Test
    fun Service_TimerExpiresWhilePaused_EndsQuietly() {
        val (player, timer) = rig(playing = false)
        onMain { timer.start(15) }
        clock.elapsed += 20 * 60_000L
        onMain { timer.checkNow() }
        assertEquals(SleepState.Idle, seen().last())
        assertFalse(readOnMain { player.playWhenReady })
    }

    @Test
    fun Service_TimerThatHasNotRunOut_DoesNothing_AndKeepsWaiting() {
        val (player, timer) = rig(playing = true)
        onMain { timer.start(15) }
        clock.elapsed += 14 * 60_000L
        onMain { timer.checkNow() }
        assertTrue(seen().last() is SleepState.Running)
        assertTrue(readOnMain { player.playWhenReady })
    }

    @Test
    fun Service_CancelAndRefusedValues() {
        val (player, timer) = rig(playing = true)
        assertFalse("7 minutes is refused", readOnMain { timer.start(7) })
        assertTrue(seen().isEmpty())
        onMain { timer.start(45) }
        onMain { timer.cancel() }
        assertEquals(SleepState.Idle, seen().last())
        clock.elapsed += 60 * 60_000L
        onMain { timer.checkNow() }
        assertTrue("a cancelled timer does nothing at its old deadline", readOnMain { player.playWhenReady })
    }

    // ---- "End of song": playback pauses when the song that is playing ends ---------------------------

    @Test
    fun Service_EndOfSong_PausesAtTheEndOfTheSong() {
        val (player, timer) = rig(playing = true, nearEnd = true)
        onMain { timer.startEndOfSong() }
        assertEquals(SleepState.EndOfSong, seen().last())
        assertTrue("the song is not cut off", readOnMain { player.playWhenReady })
        assertTrue("the player is told to pause at the end", readOnMain { player.pauseAtEndOfMediaItems })
        waitUntil("playback to pause at the end of the song") { !readOnMain { player.playWhenReady } }
        waitUntil("the timer to be over") { seen().last() == SleepState.Idle }
        assertFalse("the setting does not stay on for the next song", readOnMain { player.pauseAtEndOfMediaItems })
    }

    @Test
    fun Service_EndOfSong_RepeatOne_PausesWithoutRepeat() {
        val (player, timer) = rig(playing = true, repeatOne = true, nearEnd = true)
        onMain { timer.startEndOfSong() }
        waitUntil("playback to pause at the end of the song") { !readOnMain { player.playWhenReady } }
        Thread.sleep(2_500)
        assertFalse(readOnMain { player.isPlaying })
        assertEquals(SleepState.Idle, seen().last())
    }

    @Test
    fun Service_EndOfSong_SetWhilePaused_WaitsForTheSongThatPlaysNext() {
        // The usual order (S-5): the timer is set first, then Play is pressed.
        val (player, timer) = rig(playing = false, nearEnd = true)
        onMain { timer.startEndOfSong() }
        assertEquals(SleepState.EndOfSong, seen().last())
        onMain { player.play() }
        waitUntil("the song to play") { readOnMain { player.isPlaying } }
        waitUntil("playback to pause at the end of the song") { !readOnMain { player.playWhenReady } }
        waitUntil("the timer to be over") { seen().last() == SleepState.Idle }
    }

    @Test
    fun Service_EndOfSong_NextSongInTheQueuePlaysNormallyAfterwards() {
        val (player, timer) = rig(playing = true, songs = 2, nearEnd = true)
        onMain { timer.startEndOfSong() }
        waitUntil("playback to pause at the end of the first song") { !readOnMain { player.playWhenReady } }
        // The player stays at the end of the first song; pressing Play moves on to the second, which plays on.
        onMain { player.play() }
        waitUntil("the second song to play") { readOnMain { player.currentMediaItemIndex == 1 && player.isPlaying } }
        assertFalse("and the timer does not pause that one too", readOnMain { player.pauseAtEndOfMediaItems })
    }

    @Test
    fun Service_EndOfSong_PausedByTheUser_EndsTheTimer() {
        val (player, timer) = rig(playing = true)
        onMain { timer.startEndOfSong() }
        onMain { player.pause() }
        waitUntil("the timer to be over") { seen().last() == SleepState.Idle }
        assertFalse("it does not linger for the next song", readOnMain { player.pauseAtEndOfMediaItems })
    }

    @Test
    fun Service_EndOfSong_NewQueue_EndsTheTimer() {
        val (player, timer) = rig(playing = true)
        onMain { timer.startEndOfSong() }
        val file = TestMp3.write(File(context.cacheDir, "sleep-new-${System.nanoTime()}.mp3"), frames = 400)
        toClean += { file.delete() }
        onMain { player.setMediaItems(listOf(MediaItem.fromUri(file.toUri()))) }
        waitUntil("the timer to be over") { seen().last() == SleepState.Idle }
        assertFalse(readOnMain { player.pauseAtEndOfMediaItems })
    }

    @Test
    fun Service_ChoosingTheOtherKindOfTimer_ReplacesTheFirst() {
        val (player, timer) = rig(playing = true)
        onMain { timer.startEndOfSong() }
        onMain { timer.start(30) }
        assertTrue(seen().last() is SleepState.Running)
        assertFalse("the pause at the end of the song is off again", readOnMain { player.pauseAtEndOfMediaItems })
        onMain { timer.startEndOfSong() }
        clock.elapsed += 60 * 60_000L
        onMain { timer.checkNow() }
        assertEquals("the minutes timer is gone and the song is not paused by it", SleepState.EndOfSong, seen().last())
        assertTrue(readOnMain { player.playWhenReady })
    }

    @Test
    fun Service_NewControllerAfterARestart_HasNoTimer() {
        // Nothing about the timer is saved anywhere, so a fresh controller around a fresh player starts without one.
        // (A real force-stop of the app is the manual check.)
        val (_, timer) = rig(playing = false)
        onMain { timer.start(60) }
        onMain { timer.release() }
        val (player2, timer2) = rig(playing = false)
        assertEquals(SleepState.Idle, timer2.state)
        assertFalse(readOnMain { player2.pauseAtEndOfMediaItems })
    }

    // ---- the commands through the real service ----------------------------------------------------

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
                connected.sendCustomCommand(SessionCommand(PlaybackService.ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY), Bundle.EMPTY)
                connected.stop()
                connected.clearMediaItems()
                connected.release()
            }
        }
        return connected
    }

    @Test
    fun Service_SetAndCancelCommands_PublishTheEndTimeToTheScreens() {
        val client = PlaybackClient(context, AppLog(emptyList()))
        onMain { client.connect() }
        toClean += { onMain { client.release() } }
        val c = testController()

        val before = SystemClock.elapsedRealtime()
        onMain { client.startSleepTimer(15) }
        waitUntil("the end time to be published") { client.sleep.value.endsAtMs != null }
        val ends = client.sleep.value.endsAtMs!!
        assertTrue("about 15 minutes from now", ends - before in (15 * 60_000L - 5_000)..(15 * 60_000L + 5_000))

        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>? = null
        onMain {
            future = c.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply { putInt(PlaybackService.KEY_MINUTES, 7) },
            )
        }
        assertEquals("another value is refused", SessionError.ERROR_BAD_VALUE, future!!.get(10, TimeUnit.SECONDS).resultCode)
        assertEquals("and the running timer is untouched", ends, client.sleep.value.endsAtMs)

        onMain { client.startSleepEndOfSong() }
        waitUntil("End of song to replace the countdown") { client.sleep.value.endOfSong && client.sleep.value.endsAtMs == null }

        onMain { client.cancelSleepTimer() }
        waitUntil("the timer to be cleared") { !client.sleep.value.active }
    }
}
