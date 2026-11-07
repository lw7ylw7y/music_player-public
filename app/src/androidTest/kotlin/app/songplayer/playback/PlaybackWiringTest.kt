package app.songplayer.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import app.songplayer.core.log.AppLog
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeState
import app.songplayer.core.text.FakeKeyer
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.RoomResumeStore
import app.songplayer.library.db.RoomSongStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A start of the playback wiring around a fresh player, which is how a restart is tested (story B4): the service itself
 * is kept alive by Android's own media interface and cannot be stopped and started on demand. The same database serves
 * the "before" and the "after" start, as the app's file does across a restart.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackWiringTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun openApp() = AppInForeground.start()

        @AfterClass
        @JvmStatic
        fun closeApp() = AppInForeground.stop()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

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

    private fun database(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        toClean += { db.close() }
        return db
    }

    private class Start(val player: ExoPlayer, val wiring: PlaybackWiring)

    /** One start of the wiring on a fresh player; [library] is what the library holds at that start. */
    private fun start(db: AppDatabase, library: () -> List<Song>): Start {
        val executor = Executors.newSingleThreadExecutor()
        toClean += { executor.shutdown() }
        return readOnMain {
            val player = PlaybackService.createPlayer(context)
            toClean += { instrumentation.runOnMainSync { player.release() } }
            val wiring = PlaybackWiring(
                player, RoomResumeStore(db), RoomSongStore(db, FakeKeyer()), library, AppLog(emptyList()), executor,
                Handler(player.applicationLooper),
            )
            wiring.start()
            Start(player, wiring)
        }
    }

    private fun Start.restored() = wiring.restoreDone.get(10, TimeUnit.SECONDS)

    private fun shuffledSequence(player: ExoPlayer): List<Int> = readOnMain {
        val t = player.currentTimeline
        val seq = ArrayList<Int>()
        var i = t.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            seq += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        seq
    }

    @Test
    fun Wiring_KillMidSong_RestoresWithin5Seconds() {
        val songs = (0 until 3).map { TestSongs.seed(context, "B4 resume $it", frames = 400, toClean = toClean) }
        val db = database()
        val store = RoomResumeStore(db)

        // First start: a shuffled, repeating queue plays from its second song.
        val before = start(db) { songs }
        assertNull(before.restored())
        onMain {
            before.wiring.noteNewQueue(3, songs.first().key.value) // what the service does when a controller sets a queue
            before.player.repeatMode = Player.REPEAT_MODE_ALL
            before.player.shuffleModeEnabled = true
            before.player.setMediaItems(songs.map { it.toMediaItem(PlayingFrom.Songs) }, 1, 0L)
            before.player.prepare()
            before.player.play()
        }
        waitUntil("the song to play") { readOnMain { before.player.isPlaying } }
        waitUntil("a saved position 3 seconds into the song") { (store.load()?.positionMs ?: 0) >= 3_000 }
        val saved = store.load()!!
        // The process is gone: no further write, not even the last position.
        onMain { before.player.release() }

        // Next start, on a fresh player with the same database.
        val after = start(db) { songs }
        assertNotNull(after.restored())
        val p = after.player
        assertEquals(songs.map { it.key.value }, readOnMain { (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId } })
        assertEquals(saved.currentIndex, readOnMain { p.currentMediaItemIndex })
        assertEquals(Player.REPEAT_MODE_ALL, readOnMain { p.repeatMode })
        assertTrue(readOnMain { p.shuffleModeEnabled })
        // The shuffle order is the saved one: ranks say where each song plays.
        assertEquals(saved.queue.indices.sortedBy { saved.queue[it].shuffleRank }, shuffledSequence(p))
        val position = readOnMain { p.currentPosition }
        assertTrue("restored position $position is not within 5 s of the saved ${saved.positionMs}", kotlin.math.abs(position - saved.positionMs) < 5_000)
        assertEquals(Player.STATE_IDLE, readOnMain { p.playbackState })
        assertFalse(readOnMain { p.playWhenReady })

        // Play prepares and plays from there.
        onMain {
            p.prepare()
            p.play()
        }
        waitUntil("the song to play after Play", timeoutMs = 5_000) { readOnMain { p.isPlaying } }
        assertTrue(readOnMain { p.currentPosition } >= saved.positionMs - 5_000)
    }

    private fun stateOf(songs: List<Song>, current: Int, position: Long, repeat: Int = Player.REPEAT_MODE_OFF) = ResumeState(
        queue = songs.mapIndexed { i, s -> QueueEntry(s.key, added = i == 2, shuffleRank = null) },
        currentIndex = current, positionMs = position, shuffleOn = false, repeatMode = repeat, source = "Songs",
    )

    @Test
    fun Wiring_Restore_StartsPausedAndUnprepared() {
        val songs = (0 until 3).map { song("w$it.mp3", id = 920_000L + it) }
        val db = database()
        RoomResumeStore(db).saveQueue(stateOf(songs, current = 1, position = 65_000, repeat = Player.REPEAT_MODE_ONE))
        val s = start(db) { songs }
        assertNotNull(s.restored())
        assertEquals(3, readOnMain { s.player.mediaItemCount })
        assertEquals(1, readOnMain { s.player.currentMediaItemIndex })
        assertEquals(Player.REPEAT_MODE_ONE, readOnMain { s.player.repeatMode })
        assertEquals(Player.STATE_IDLE, readOnMain { s.player.playbackState })
        assertFalse(readOnMain { s.player.playWhenReady })
        assertEquals(65_000L, readOnMain { s.player.currentPosition })
        // The "Playing from" label and the added flag come back with the items.
        assertEquals("Songs", readOnMain { s.player.getMediaItemAt(0).mediaMetadata.extras?.getString("playingFrom") })
        assertTrue(readOnMain { s.player.getMediaItemAt(2).mediaMetadata.extras?.getBoolean("addedToQueue") } == true)
    }

    @Test
    fun Wiring_SavedSongGoneFromTheLibrary_IsLeftOutOfTheRestoredQueue() {
        val songs = (0 until 3).map { song("g$it.mp3", id = 921_000L + it) }
        val db = database()
        RoomResumeStore(db).saveQueue(stateOf(songs, current = 2, position = 30_000))
        val s = start(db) { songs.take(2) } // the third song has left the library
        assertNotNull(s.restored())
        assertEquals(2, readOnMain { s.player.mediaItemCount })
        // The song that was playing is gone: the last one left takes its place, from the start.
        assertEquals(1, readOnMain { s.player.currentMediaItemIndex })
        assertEquals(0L, readOnMain { s.player.currentPosition })
    }

    @Test
    fun Wiring_NothingLeftInTheLibrary_StartsWithAnEmptyQueue() {
        val songs = (0 until 2).map { song("n$it.mp3", id = 922_000L + it) }
        val db = database()
        RoomResumeStore(db).saveQueue(stateOf(songs, current = 0, position = 1_000))
        val s = start(db) { emptyList() }
        assertNull(s.restored())
        assertEquals(0, readOnMain { s.player.mediaItemCount })
    }

    @Test
    fun Wiring_LibraryCannotBeRead_StartsEmptyAndTheNewQueueIsSaved() {
        val songs = (0 until 2).map { song("r$it.mp3", id = 923_000L + it) }
        val db = database()
        val s = start(db) { throw IllegalStateException("database unreadable") }
        assertNull(s.restored())
        onMain { s.player.setMediaItems(songs.map { it.toMediaItem(PlayingFrom.Songs) }, 0, 0L) }
        waitUntil("the new queue to be saved") { RoomResumeStore(db).load()?.queue?.size == 2 }
    }

    @Test
    fun Wiring_TapBeforeTheRestoreFinishes_IsNotOverwrittenAndIsSaved() {
        val old = (0 until 3).map { song("o$it.mp3", id = 924_000L + it) }
        val tapped = (0 until 2).map { song("t$it.mp3", id = 925_000L + it) }
        val db = database()
        RoomResumeStore(db).saveQueue(stateOf(old, current = 0, position = 5_000))
        val release = CountDownLatch(1)
        val s = start(db) {
            release.await(10, TimeUnit.SECONDS)
            old
        }
        // A song is tapped while the saved queue is still being read.
        onMain { s.player.setMediaItems(tapped.map { it.toMediaItem(PlayingFrom.Search) }, 1, 0L) }
        release.countDown()
        s.restored()
        assertEquals(tapped.map { it.key.value }, readOnMain { (0 until s.player.mediaItemCount).map { s.player.getMediaItemAt(it).mediaId } })
        waitUntil("the tapped queue to replace the saved one") { RoomResumeStore(db).load()?.queue?.map { it.songKey } == tapped.map { it.key } }
    }

    @Test
    fun Wiring_StoppedBeforeTheRestoreFinishes_AppliesNothing() {
        val songs = (0 until 2).map { song("s$it.mp3", id = 926_000L + it) }
        val db = database()
        RoomResumeStore(db).saveQueue(stateOf(songs, current = 0, position = 1_000))
        val release = CountDownLatch(1)
        val s = start(db) {
            release.await(10, TimeUnit.SECONDS)
            songs
        }
        onMain { s.wiring.stop() }
        release.countDown()
        s.restored()
        assertEquals(0, readOnMain { s.player.mediaItemCount })
    }
}
