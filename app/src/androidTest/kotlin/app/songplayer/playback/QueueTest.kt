package app.songplayer.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.core.library.TestMp3
import java.io.File
import java.util.Collections
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests for the queue (story B2): the real ExoPlayer and the real [QueueShuffler], on short silent MP3s
 * these tests write themselves. The app is opened once for the class because Android 15 refuses audio focus to an app
 * with nothing on screen.
 */
@RunWith(AndroidJUnit4::class)
class QueueTest {
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

    @Before
    fun bringTheAppToTheFront() = AppInForeground.ensure()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> readOnMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun item(name: String): MediaItem {
        val file = TestMp3.write(File(context.cacheDir, "$name.mp3"), frames = 12) // about a third of a second
        toClean += { file.delete() }
        return MediaItem.Builder().setMediaId(name).setUri(file.toUri()).build()
    }

    private fun items(count: Int) = (0 until count).map { item("q$it") }

    /** The player, wired like the service wires it. */
    private class Rig(val player: ExoPlayer, val shuffler: QueueShuffler)

    private fun rig(): Rig {
        return readOnMain {
            val player = PlaybackService.createPlayer(context)
            val shuffler = QueueShuffler(player)
            player.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    if (shuffleModeEnabled) shuffler.currentFirst()
                }
            })
            toClean += { onMain { player.release() } }
            Rig(player, shuffler)
        }
    }

    /** The ids of the songs in the order they start playing, until playback ends or [limit] songs have started. */
    private fun playedOrder(player: ExoPlayer, limit: Int = 50): List<String> {
        val played = Collections.synchronizedList(mutableListOf<String>())
        var ended = false
        onMain {
            // The first song's own transition happened when the queue was set, before this listener.
            player.currentMediaItem?.let { played += it.mediaId }
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.let { played += it.mediaId }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended = true
                }
            })
            player.prepare()
            player.play()
        }
        waitUntil("playback to end or $limit songs to start") { ended || played.size >= limit }
        return played.toList()
    }

    @Test
    fun Player_ShuffleOn_PlaysEachSongOnce_ThenStops() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(6), 2, 0L)
            r.player.shuffleModeEnabled = true
        }
        val played = playedOrder(r.player)
        assertEquals("every song exactly once", listOf("q0", "q1", "q2", "q3", "q4", "q5"), played.sorted())
        assertEquals("the song that was playing comes first", "q2", played.first())
        assertEquals(Player.STATE_ENDED, readOnMain { r.player.playbackState })
    }

    @Test
    fun Player_ShuffleOff_KeepsCurrentAndListOrder() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(4), 1, 0L)
            r.player.shuffleModeEnabled = true
            r.player.shuffleModeEnabled = false
        }
        assertEquals(listOf("q1", "q2", "q3"), playedOrder(r.player))
    }

    @Test
    fun Player_RepeatAll_WrapsToTheFirstSongAfterTheLast() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(3), 2, 0L)
            r.player.repeatMode = Player.REPEAT_MODE_ALL
        }
        val played = playedOrder(r.player, limit = 3)
        assertEquals(listOf("q2", "q0", "q1"), played)
    }

    @Test
    fun Player_RepeatOne_RepeatsTheSong_AndNextStillMovesOn() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(3), 1, 0L)
            r.player.repeatMode = Player.REPEAT_MODE_ONE
        }
        val repeats = Collections.synchronizedList(mutableListOf<Int>())
        onMain {
            r.player.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) repeats += new.mediaItemIndex
                }
            })
            r.player.prepare()
            r.player.play()
        }
        waitUntil("the song to repeat twice") { repeats.size >= 2 }
        assertTrue("it stayed on the same song: $repeats", repeats.all { it == 1 })
        onMain { r.player.seekToNextMediaItem() }
        assertEquals("Next leaves a repeating song", 2, readOnMain { r.player.currentMediaItemIndex })
    }

    @Test
    fun Player_RepeatOff_StopsAfterTheLastSong() {
        val r = rig()
        onMain { r.player.setMediaItems(items(3), 1, 0L) }
        assertEquals(listOf("q1", "q2"), playedOrder(r.player))
        assertEquals(Player.STATE_ENDED, readOnMain { r.player.playbackState })
    }

    @Test
    fun Player_PlayNextWhileShuffled_PlaysNext() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(8), 3, 0L)
            r.player.shuffleModeEnabled = true
            r.shuffler.playNext(item("played-next"))
        }
        val next = readOnMain { r.player.getMediaItemAt(r.player.nextMediaItemIndex).mediaId }
        assertEquals("played-next", next)
    }

    @Test
    fun Player_PlayNextWithShuffleOff_InsertsAfterTheCurrentSong() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(4), 1, 0L)
            r.shuffler.playNext(item("played-next"))
        }
        assertEquals(listOf("q0", "q1", "played-next", "q2", "q3"), readOnMain { (0 until r.player.mediaItemCount).map { r.player.getMediaItemAt(it).mediaId } })
        assertEquals("played-next", readOnMain { r.player.getMediaItemAt(r.player.nextMediaItemIndex).mediaId })
    }

    @Test
    fun Player_AddToQueueWhileShuffled_PlaysLast() {
        val r = rig()
        onMain {
            r.player.setMediaItems(items(6), 0, 0L)
            r.player.shuffleModeEnabled = true
            r.shuffler.addToQueue(item("added"))
        }
        val lastInPlayOrder = readOnMain {
            val t = r.player.currentTimeline
            var i = t.getFirstWindowIndex(true)
            var last = i
            while (i != C.INDEX_UNSET) { last = i; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true) }
            r.player.getMediaItemAt(last).mediaId
        }
        assertEquals("added", lastInPlayOrder)
    }

    @Test
    fun Player_RemoveCurrentSong_MovesOnToTheNextOne() {
        val r = rig()
        onMain { r.player.setMediaItems(items(4), 1, 0L) }
        onMain { r.player.removeMediaItem(r.player.currentMediaItemIndex) }
        assertEquals("q2", readOnMain { r.player.currentMediaItem?.mediaId })
        assertEquals(3, readOnMain { r.player.mediaItemCount })
    }

    @Test
    fun Player_PlayNextOnAnEmptyQueue_IsReadyToPlay() {
        val r = rig()
        onMain { r.shuffler.playNext(item("only")) }
        waitUntil("the player to be ready") { readOnMain { r.player.playbackState } == Player.STATE_READY }
        assertEquals(1, readOnMain { r.player.mediaItemCount })
    }

    @Test
    fun Player_AddToQueueOnAnEmptyQueue_IsReadyToPlay() {
        val r = rig()
        onMain { r.shuffler.addToQueue(item("only")) }
        waitUntil("the player to be ready") { readOnMain { r.player.playbackState } == Player.STATE_READY }
        assertEquals(1, readOnMain { r.player.mediaItemCount })
    }
}
