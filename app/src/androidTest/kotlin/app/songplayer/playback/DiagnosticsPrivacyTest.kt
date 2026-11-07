package app.songplayer.playback

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.resume.ResumeState
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The privacy promise of "Copy diagnostics" (story D3, R-21, NFR-4): songs with distinctive names are indexed, browsed,
 * saved in a playlist and played, one of them unplayable, and then neither the diagnostics text, the saved error log nor
 * the app's own log output may contain any of the names. (Android's own system log is not the app's, and is not checked.)
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsPrivacyTest {
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

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** The app's own log output (its tag only), as it is now; earlier runs are in it too. */
    private fun ownLog(): String {
        val process = ProcessBuilder("logcat", "-d", "-s", "SongPlayer:I").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text
    }

    @Test
    fun Diagnostics_AfterPlayFailBrowseSession_ErrorLogAndCapturedLogContainNoName() {
        val marker = "Qzv${System.nanoTime() % 1_000_000}"
        // Earlier tests and runs leave entries behind; what counts is what this session adds.
        val logBefore = container.errorLog.recent()
        val failuresInLogBefore = Regex("PLAYBACK_ITEM_FAILED").findAll(ownLog()).count()
        val good = TestSongs.seed(context, "good", frames = 40, toClean = toClean, fileName = "$marker-good-song.mp3")
        val bad = TestSongs.seed(context, "bad", frames = 40, toClean = toClean, fileName = "$marker-broken-song.mp3", content = "not music at all".toByteArray())

        // Browse: the library reads the media index, and the songs with their distinctive names are in it.
        container.library.requestSync()
        waitUntil("the library to list the test songs") { container.songStore.all().count { it.fileName.startsWith(marker) } == 2 }

        // A playlist with a distinctive name and both songs.
        val playlist = container.playlistStore.create("$marker-playlist", 1)
        toClean += { container.playlistStore.delete(playlist.id) }
        container.songStore.all().filter { it.fileName.startsWith(marker) }.forEach { container.playlistStore.add(playlist.id, it) }

        // Play the unplayable one (logs a player error code), then the good one.
        val client = PlaybackClient(context, AppLog(emptyList()))
        onMain { client.connect() }
        toClean += { onMain { client.release() } }
        val songs = container.songStore.all().filter { it.fileName.startsWith(marker) }
        val brokenSong = songs.single { it.fileName.contains("broken") }
        val goodSong = songs.single { it.fileName.contains("good") }
        onMain { client.play(listOf(brokenSong), 0, PlayingFrom.Songs) }
        waitUntil("the player error to be saved in the error log") { container.errorLog.recent() != logBefore }
        onMain { client.play(listOf(goodSong), 0, PlayingFrom.Songs) }

        val text = container.diagnostics.text()
        val saved = container.errorLog.recent().joinToString("\n") { (listOf(it.code.name) + it.values).joinToString(" ") }
        val log = ownLog()

        assertTrue("the text names the failure", text.contains("PLAYBACK_ITEM_FAILED"))
        assertTrue("and the phone", text.contains(Build.MODEL))
        for ((where, content) in listOf("diagnostics text" to text, "error log" to saved, "app log" to log)) {
            assertFalse("$where contains a name", content.contains(marker))
        }
        assertTrue("this session added a player error to the error log", saved.contains("PLAYBACK_ITEM_FAILED"))
        assertTrue(
            "and to the app's own log",
            Regex("PLAYBACK_ITEM_FAILED").findAll(log).count() > failuresInLogBefore,
        )
    }
}
