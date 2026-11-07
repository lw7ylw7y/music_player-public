package app.songplayer.playback

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.library.TestMp3
import app.songplayer.core.log.AppLog
import app.songplayer.core.resume.ResumeState
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests of "Open with" (story D1): the real service and player, a fake provider that says what the other app
 * would say, and real MP3s in the media index to play. The fake lets a test describe a text file or an unreadable file
 * without needing another app.
 */
@RunWith(AndroidJUnit4::class)
class OpenedFilesTest {
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

    /** What a provider would say about an address. */
    private class FakeSource(val mime: String?, val name: String?, val head: ByteArray?) : OpenableSource {
        var keptFlags: Int? = null
        override fun mimeType(uri: Uri) = mime
        override fun displayName(uri: Uri) = name
        override fun head(uri: Uri, max: Int) = head?.copyOf(minOf(max, head.size))
        override fun keepAccess(uri: Uri, intentFlags: Int) {
            keptFlags = intentFlags
        }
    }

    private val goodHead = TestMp3.bytes(frames = 3)

    private fun setUp(source: OpenableSource): Pair<OpenedFiles, PlaybackClient> {
        val client = PlaybackClient(context, AppLog(emptyList()))
        onMain { client.connect() }
        toClean += { onMain { client.release() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        toClean += { scope.cancel() }
        return OpenedFiles(FileOpener(source), client, AppLog(emptyList()), scope) to client
    }

    private fun controller(): MediaController {
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
                connected.stop()
                connected.clearMediaItems()
                connected.release()
            }
        }
        return connected
    }

    private fun queueIds(c: MediaController) = readOnMain { (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }

    /** A real MP3 in the media index; its address is what the "other app" hands over. */
    private fun realAddress(title: String): Uri = audioUri(TestSongs.seed(context, title, frames = 400, toClean = toClean).mediaStoreId)

    private fun view(uri: Uri, flags: Int = 0) = Intent(Intent.ACTION_VIEW, uri).addFlags(flags)

    @Test
    fun OpenFile_Mp3_PlaysAndLibraryUnchanged() {
        val uri = realAddress("D1 outside")
        val (opened, client) = setUp(FakeSource("audio/mpeg", "Morning.mp3", goodHead))
        val c = controller()
        onMain { c.repeatMode = Player.REPEAT_MODE_ALL }

        onMain { opened.handle(view(uri)) }
        val key = "file:$uri"
        waitUntil("the file to be the current song") { client.nowPlaying.value?.mediaId == key }
        waitUntil("it to play") { client.nowPlaying.value?.isPlaying == true }

        assertEquals("Morning.mp3", client.nowPlaying.value?.title)
        assertEquals(PlayingFrom.File, client.nowPlaying.value?.playingFrom)
        assertEquals("the queue holds only that file", listOf(key), queueIds(c))
        assertEquals("it plays once and stops, so repeat is off", Player.REPEAT_MODE_OFF, readOnMain { c.repeatMode })
        assertEquals(1, opened.started.value)
        // The app's copy of the library may pick up the test file from the media index by itself (a refresh is not
        // ours to stop); what matters is that opening it added nothing under its own key.
        assertTrue("the opened file is not in the library", container.songStore.all().none { it.key.value == key })
    }

    @Test
    fun OpenFile_NotMp3_ShowsDialogAndKeepsQueue() {
        val songs = (0 until 2).map { TestSongs.seed(context, "D1 queue $it", frames = 400, toClean = toClean) }
        val (opened, client) = setUp(FakeSource("text/plain", "notes.txt", "just words".toByteArray()))
        val c = controller()
        onMain { client.play(songs, 1, PlayingFrom.Songs) }
        waitUntil("the queue to play") { client.nowPlaying.value?.mediaId == songs[1].key.value && client.nowPlaying.value?.isPlaying == true }
        val before = queueIds(c)

        onMain { opened.handle(view(Uri.parse("content://elsewhere/doc/1"))) }
        waitUntil("the refusal") { opened.refused.value }

        assertEquals(before, queueIds(c))
        assertEquals(songs[1].key.value, client.nowPlaying.value?.mediaId)
        assertTrue("playback goes on", client.nowPlaying.value?.isPlaying == true)
        assertEquals(0, opened.started.value)
        opened.dismissRefused()
        assertFalse(opened.refused.value)
    }

    @Test
    fun OpenFile_Unreadable_ShowsDialogAndKeepsQueue() {
        val songs = (0 until 2).map { TestSongs.seed(context, "D1 kept $it", frames = 400, toClean = toClean) }
        val (opened, client) = setUp(FakeSource("audio/mpeg", "gone.mp3", null))
        val c = controller()
        onMain { client.play(songs, 0, PlayingFrom.Songs) }
        waitUntil("the queue") { queueIds(c) == songs.map { it.key.value } }

        onMain { opened.handle(view(Uri.parse("content://elsewhere/doc/2"))) }
        waitUntil("the refusal") { opened.refused.value }
        assertEquals(songs.map { it.key.value }, queueIds(c))
    }

    @Test
    fun OpenFile_AddressThatIsNotAContentAddress_IsRefusedWithoutBeingOpened() {
        var asked = false
        val source = object : OpenableSource {
            override fun mimeType(uri: Uri): String? { asked = true; return "audio/mpeg" }
            override fun displayName(uri: Uri): String? { asked = true; return "x.mp3" }
            override fun head(uri: Uri, max: Int): ByteArray? { asked = true; return goodHead }
            override fun keepAccess(uri: Uri, intentFlags: Int) { asked = true }
        }
        val (opened, _) = setUp(source)
        onMain { opened.handle(view(Uri.parse("file:///data/local/tmp/x.mp3"))) }
        waitUntil("the refusal") { opened.refused.value }
        assertFalse("a file address is never opened", asked)
    }

    @Test
    fun OpenFile_OtherIntents_AreIgnored() {
        val (opened, _) = setUp(FakeSource("audio/mpeg", "a.mp3", goodHead))
        onMain { opened.handle(Intent(Intent.ACTION_MAIN)) }
        onMain { opened.handle(Intent(Intent.ACTION_VIEW)) }
        Thread.sleep(500)
        assertEquals(0, opened.started.value)
        assertFalse(opened.refused.value)
    }

    @Test
    fun OpenFile_Played_IsSavedWithItsName_AndTheRestoreKeepsIt() {
        val uri = realAddress("D1 remembered")
        val source = FakeSource("audio/mpeg", "Morning.mp3", goodHead)
        val (opened, client) = setUp(source)
        controller()
        // The other app offers lasting access, as a document picker or a file manager can.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        onMain { opened.handle(view(uri, flags)) }
        val key = "file:$uri"
        waitUntil("the file to play") { client.nowPlaying.value?.mediaId == key }
        waitUntil("the file to be saved as the queue") {
            container.resumeStore.load()?.queue?.singleOrNull()?.songKey?.value == key
        }
        assertEquals("Morning.mp3", container.resumeStore.load()!!.queue.single().displayName)
        assertTrue("lasting access was asked for", (source.keptFlags ?: 0) and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)

        // A restart: the saved queue is read back and checked against the library, which does not have the file.
        val restored = ResumeRestore.read(container.resumeStore, container.songStore.all())
        assertNotNull("the opened file is not dropped for being outside the library", restored)
        val item = restored!!.items.single()
        assertEquals(key, item.mediaId)
        assertEquals("Morning.mp3", item.mediaMetadata.title.toString())
        assertEquals(uri, item.localConfiguration?.uri)
    }

    @Test
    fun OpenFile_TwoOpensInQuickSuccession_TheNewestWins_EvenWhenTheFirstAnswersLast() {
        val first = realAddress("D1 slow")
        val second = realAddress("D1 quick")
        val release = java.util.concurrent.CountDownLatch(1)
        val source = object : OpenableSource {
            override fun mimeType(uri: Uri) = "audio/mpeg"
            override fun displayName(uri: Uri) = if (uri == first) "First.mp3" else "Second.mp3"
            override fun head(uri: Uri, max: Int): ByteArray {
                // The provider of the first file is slow; it answers only after the second file has started to play.
                if (uri == first) release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return goodHead
            }
            override fun keepAccess(uri: Uri, intentFlags: Int) = Unit
        }
        val (opened, client) = setUp(source)
        val c = controller()
        onMain { opened.handle(view(first)) }
        onMain { opened.handle(view(second)) }
        waitUntil("the second file to play") { client.nowPlaying.value?.mediaId == "file:$second" }

        release.countDown()
        Thread.sleep(1_000)
        assertEquals("file:$second", client.nowPlaying.value?.mediaId)
        assertEquals(listOf("file:$second"), queueIds(c))
    }
}
