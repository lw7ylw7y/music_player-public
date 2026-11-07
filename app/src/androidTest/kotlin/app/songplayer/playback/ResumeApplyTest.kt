package app.songplayer.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.core.library.TestMp3
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Giving the saved queue to a real player (story B4): paused, unprepared, with the saved shuffle order. */
@RunWith(AndroidJUnit4::class)
class ResumeApplyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

    private fun <T> onMainReturning(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun item(name: String): MediaItem {
        val file = TestMp3.write(File(context.cacheDir, "$name.mp3"), frames = 40)
        toClean += { file.delete() }
        return MediaItem.Builder().setMediaId(name).setUri(file.toUri()).build()
    }

    private fun restored(shuffle: Boolean) = Restored(
        items = listOf(item("r0"), item("r1"), item("r2"), item("r3")),
        index = 2,
        positionMs = 31_000,
        shuffleOn = shuffle,
        repeatMode = Player.REPEAT_MODE_ALL,
        shuffleOrder = if (shuffle) intArrayOf(2, 0, 3, 1) else null,
    )

    @Test
    fun ResumeRestore_Apply_GivesTheQueuePausedAndUnprepared() {
        onMainReturning {
            val player = PlaybackService.createPlayer(context)
            toClean += { instrumentation.runOnMainSync { player.release() } }
            ResumeRestore.apply(player, restored(shuffle = false))
            assertEquals(4, player.mediaItemCount)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals(31_000L, player.currentPosition)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertFalse(player.shuffleModeEnabled)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
        }
    }

    @Test
    fun ResumeRestore_Apply_RestoresTheSavedShuffleOrder() {
        onMainReturning {
            val player = PlaybackService.createPlayer(context)
            toClean += { instrumentation.runOnMainSync { player.release() } }
            // The service reacts to shuffle being turned on by setting a fresh random order; the saved one must win.
            player.addListener(object : Player.Listener {
                private val shuffler = QueueShuffler(player)
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    if (shuffleModeEnabled) shuffler.currentFirst()
                }
            })
            ResumeRestore.apply(player, restored(shuffle = true))
            assertTrue(player.shuffleModeEnabled)
            assertArrayEquals(intArrayOf(2, 0, 3, 1), shuffledOrder(player.currentTimeline))
        }
    }

    @Test
    fun ResumeRestore_Apply_PlayerAlreadyHasAQueue_ChangesNothing() {
        onMainReturning {
            val player = PlaybackService.createPlayer(context)
            toClean += { instrumentation.runOnMainSync { player.release() } }
            player.setMediaItems(listOf(item("tapped0"), item("tapped1")), 1, 0L)
            ResumeRestore.apply(player, restored(shuffle = true))
            assertEquals(2, player.mediaItemCount)
            assertEquals("tapped1", player.currentMediaItem?.mediaId)
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
            assertFalse(player.shuffleModeEnabled)
        }
    }
}
