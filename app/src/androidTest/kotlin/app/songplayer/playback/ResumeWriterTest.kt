package app.songplayer.playback

import android.os.Handler
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.TestMp3
import app.songplayer.core.log.AppLog
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.resume.ResumeStore
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** When the resume writer saves what (story B4), on a real ExoPlayer and a recording store. */
@RunWith(AndroidJUnit4::class)
class ResumeWriterTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun openApp() = AppInForeground.start()

        @AfterClass
        @JvmStatic
        fun closeApp() = AppInForeground.stop()
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

    private class Recording : ResumeStore {
        val queues = Collections.synchronizedList(mutableListOf<ResumeState>())
        val positions = Collections.synchronizedList(mutableListOf<Triple<Int, SongKey, Long>>())
        override fun load(): ResumeState? = null
        override fun saveQueue(state: ResumeState) {
            queues += state
        }
        override fun savePosition(index: Int, key: SongKey, positionMs: Long) {
            positions += Triple(index, key, positionMs)
        }
    }

    @Before
    fun bringTheAppToTheFront() = AppInForeground.ensure()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> readOnMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun item(name: String, frames: Int = 400): MediaItem {
        val file = TestMp3.write(File(context.cacheDir, "$name.mp3"), frames = frames)
        toClean += { file.delete() }
        return MediaItem.Builder().setMediaId("external_primary:Music/$name.mp3").setUri(file.toUri()).build()
    }

    private class Rig(val player: ExoPlayer, val writer: ResumeWriter, val store: Recording)

    private fun rig(): Rig {
        val store = Recording()
        return readOnMain {
            val player = PlaybackService.createPlayer(context)
            val writer = ResumeWriter(player, store, AppLog(emptyList()), Executor { it.run() }, Handler(player.applicationLooper))
            player.addListener(writer)
            toClean += { onMain { player.release() } }
            Rig(player, writer, store)
        }
    }

    @Test
    fun Writer_BeforeEnable_SavesNothing_AfterEnable_SavesTheQueueAlreadyThere() {
        val r = rig()
        onMain { r.player.setMediaItems(listOf(item("w0"), item("w1")), 1, 0L) }
        Thread.sleep(300)
        assertTrue("nothing is written while the restore may still be running", r.store.queues.isEmpty() && r.store.positions.isEmpty())
        onMain { r.writer.enable() }
        waitUntil("the queue to be saved") { r.store.queues.isNotEmpty() }
        val saved = r.store.queues.last()
        assertEquals(2, saved.queue.size)
        assertEquals(1, saved.currentIndex)
    }

    @Test
    fun Writer_SeveralQueueChangesInOneTurn_AreSavedOnce() {
        val r = rig()
        onMain { r.writer.enable() }
        onMain {
            r.player.setMediaItems(listOf(item("c0")), 0, 0L)
            r.player.addMediaItem(item("c1"))
            r.player.addMediaItem(item("c2"))
        }
        waitUntil("the queue to be saved") { r.store.queues.isNotEmpty() }
        Thread.sleep(300)
        assertEquals("one write for the whole burst", 1, r.store.queues.size)
        assertEquals(3, r.store.queues.single().queue.size)
    }

    @Test
    fun Writer_PlayingSong_SavesItsPositionEveryThreeSeconds() {
        val r = rig()
        onMain {
            r.writer.enable()
            r.player.setMediaItems(listOf(item("p0")), 0, 0L)
            r.player.prepare()
            r.player.play()
        }
        waitUntil("two position writes at least 2.5 seconds into the song") {
            r.store.positions.count { it.third >= 2_500 } >= 1
        }
        assertTrue(r.store.positions.all { it.first == 0 && it.second.value.endsWith("p0.mp3") })
    }

    @Test
    fun Writer_Pause_SavesThePosition() {
        val r = rig()
        onMain {
            r.writer.enable()
            r.player.setMediaItems(listOf(item("q0")), 0, 0L)
            r.player.prepare()
            r.player.play()
        }
        waitUntil("the song to play") { readOnMain { r.player.isPlaying } }
        Thread.sleep(800)
        val before = r.store.positions.size
        onMain { r.player.pause() }
        waitUntil("a position write for the pause") { r.store.positions.size > before }
        assertTrue(r.store.positions.last().third >= 500)
    }

    @Test
    fun Writer_Seek_WhilePaused_IsSaved() {
        val r = rig()
        onMain {
            r.writer.enable()
            r.player.setMediaItems(listOf(item("s0")), 0, 0L)
            r.player.prepare()
        }
        Thread.sleep(500)
        val before = r.store.positions.size
        onMain { r.player.seekTo(5_000) }
        waitUntil("a position write for the seek") { r.store.positions.size > before }
        assertEquals(5_000L, r.store.positions.last().third)
    }

    @Test
    fun Writer_Flush_SavesTheLastPosition() {
        val r = rig()
        onMain {
            r.writer.enable()
            r.player.setMediaItems(listOf(item("f0")), 0, 4_000L)
        }
        Thread.sleep(300)
        val before = r.store.positions.size
        onMain { r.writer.flush() }
        assertTrue(r.store.positions.size > before)
        assertEquals(4_000L, r.store.positions.last().third)
    }

    @Test
    fun Writer_QueueEnded_SavesPositionZero() {
        val r = rig()
        onMain {
            r.writer.enable()
            r.player.setMediaItems(listOf(item("e0", frames = 12)), 0, 0L)
            r.player.prepare()
            r.player.play()
        }
        waitUntil("playback to end") { readOnMain { r.player.playbackState } == androidx.media3.common.Player.STATE_ENDED }
        waitUntil("the position to be reset") { r.store.positions.lastOrNull()?.third == 0L }
    }

    @Test
    fun Writer_StoreThrows_PlaybackGoesOnAndTheFailureIsLoggedByCode() {
        val scenario = object : ResumeStore {
            override fun load(): ResumeState? = null
            override fun saveQueue(state: ResumeState) = throw IllegalStateException("storage full")
            override fun savePosition(index: Int, key: SongKey, positionMs: Long) = throw IllegalStateException("storage full")
        }
        val codes = Collections.synchronizedList(mutableListOf<app.songplayer.core.log.LogCode>())
        val sink = object : app.songplayer.core.log.LogSink {
            override fun write(code: app.songplayer.core.log.LogCode, values: List<Int>) {
                codes += code
            }
        }
        val player = readOnMain {
            PlaybackService.createPlayer(context).also { p ->
                p.addListener(ResumeWriter(p, scenario, AppLog(listOf(sink)), Executor { it.run() }, Handler(p.applicationLooper)).also { it.enable() })
                toClean += { onMain { p.release() } }
            }
        }
        onMain {
            player.setMediaItems(listOf(item("x0")), 0, 0L)
            player.prepare()
            player.play()
        }
        waitUntil("the failed writes to be logged") { codes.contains(app.songplayer.core.log.LogCode.RESUME_WRITE_FAILED) }
        waitUntil("the song to play although every write failed") { readOnMain { player.isPlaying } }
        assertTrue(codes.all { it == app.songplayer.core.log.LogCode.RESUME_WRITE_FAILED })
    }
}
