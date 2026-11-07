package app.songplayer.playback

import android.Manifest
import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.browse.BrowseIds
import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests of what the car sees (story E2): the real service answers a browser the way Android Auto's would be
 * answered, from the real library with two seeded songs. The Android Auto controller itself cannot be played here (that
 * needs the Desktop Head Unit); `ControllerPolicy` is covered in `core`.
 */
@RunWith(AndroidJUnit4::class)
class CarBrowseTest {
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

    @After
    fun cleanUp() {
        toClean.reversed().forEach { runCatching { it() } }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun browser(): MediaBrowser {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        var browser: MediaBrowser? = null
        instrumentation.runOnMainSync {
            val future = MediaBrowser.Builder(context, token).buildAsync()
            future.addListener({ browser = future.get() }, MoreExecutors.directExecutor())
        }
        waitUntil("a browser to connect") { browser != null }
        val connected = browser!!
        toClean += {
            instrumentation.runOnMainSync {
                connected.stop()
                connected.clearMediaItems()
                connected.release()
            }
        }
        return connected
    }

    /** A MediaBrowser may only be called on the thread it was built on (the main thread); the answer is awaited here. */
    private fun children(b: MediaBrowser, parentId: String): List<androidx.media3.common.MediaItem> {
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>? = null
        instrumentation.runOnMainSync { future = b.getChildren(parentId, 0, Int.MAX_VALUE, null) }
        return future!!.get().value!!
    }

    @Test
    fun Session_Root_HasTheFourTabsInOrder_AndASongTapQueuesItsList() {
        TestSongs.seed(context, "E2 car one", frames = 40, toClean = toClean, fileName = "E2 car one.mp3")
        TestSongs.seed(context, "E2 car two", frames = 40, toClean = toClean, fileName = "E2 car two.mp3")
        container.library.requestSync()
        waitUntil("the library to list the test songs") { container.songStore.all().count { it.fileName.startsWith("E2 car") } == 2 }
        val b = browser()

        val tabs = children(b, BrowseIds.ROOT)
        assertEquals(
            listOf(BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS),
            tabs.map { it.mediaId },
        )
        assertTrue("every tab can be opened", tabs.all { it.mediaMetadata.isBrowsable == true })

        val songs = children(b, BrowseIds.SONGS)
        assertTrue("a list never passes the cap", songs.size <= 100)
        val playable = songs.filter { it.mediaMetadata.isPlayable == true }
        assertTrue("two seeded songs at least", playable.size >= 2)
        val tapped = playable[1]
        instrumentation.runOnMainSync { b.setMediaItem(tapped); b.prepare() }
        val tappedAt = 1
        waitUntil("the tapped list to be the queue") {
            var count = 0
            var index = -1
            instrumentation.runOnMainSync { count = b.mediaItemCount; index = b.currentMediaItemIndex }
            count == playable.size && index == tappedAt
        }
    }
}
