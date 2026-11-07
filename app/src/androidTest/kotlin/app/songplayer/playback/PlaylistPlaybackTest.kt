package app.songplayer.playback

import android.Manifest
import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.library.Song
import app.songplayer.core.log.AppLog
import app.songplayer.core.playlist.PlaylistView
import app.songplayer.core.resume.ResumeState
import app.songplayer.playlist.PlaylistsDatabase
import app.songplayer.playlist.RoomPlaylistStore
import com.google.common.util.concurrent.MoreExecutors
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
 * Emulator tests of how a playlist and the queue relate (story C2, decision D-14): the real service and player, real
 * songs in the media index, and a real playlist database in memory. Play copies the playlist into the queue; after
 * that neither side changes the other.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistPlaybackTest {
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

    private fun seedSongs(count: Int, prefix: String): List<Song> =
        (0 until count).map { TestSongs.seed(context, "$prefix $it", frames = 40, toClean = toClean) }

    private fun connect(): PlaybackClient {
        val client = PlaybackClient(context, AppLog(emptyList()))
        onMain { client.connect() }
        toClean += { onMain { client.release() } }
        return client
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
                connected.shuffleModeEnabled = false
                connected.stop()
                connected.clearMediaItems()
                connected.release()
            }
        }
        return connected
    }

    private fun playlistStore(songs: List<Song>): Pair<RoomPlaylistStore, Long> {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistsDatabase::class.java).build()
        toClean += { db.close() }
        val store = RoomPlaylistStore(db)
        val playlist = store.create("Driving", 1)
        songs.forEach { store.add(playlist.id, it) }
        return store to playlist.id
    }

    private fun queueIds(c: MediaController) = readOnMain { (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }

    @Test
    fun PlaylistScreen_Play_DoesNotChangeShuffle() {
        val songs = seedSongs(3, "C2 play")
        val client = connect()
        val c = controller()
        val queue = PlaylistView.queue(PlaylistView.items(playlistStore(songs).let { (s, id) -> s.entries(id) }, songs))

        onMain { c.shuffleModeEnabled = false }
        onMain { client.play(queue, 0, PlayingFrom.Playlist) }
        waitUntil("the playlist to be the queue") { queueIds(c) == songs.map { it.key.value } }
        assertFalse("Play with shuffle off leaves it off", readOnMain { c.shuffleModeEnabled })

        // Move on to the last song first, so that waiting for the first song proves the second Play took effect.
        onMain { c.seekTo(2, 0L) }
        waitUntil("the last song to play") { client.nowPlaying.value?.mediaId == songs[2].key.value }
        onMain { c.shuffleModeEnabled = true }
        onMain { client.play(queue, 0, PlayingFrom.Playlist) }
        waitUntil("the queue to start again from the top") { client.nowPlaying.value?.mediaId == songs[0].key.value }
        assertTrue("Play with shuffle on leaves it on", readOnMain { c.shuffleModeEnabled })
    }

    @Test
    fun PlaylistScreen_Shuffle_TurnsShuffleOn() {
        val songs = seedSongs(4, "C2 shuffle")
        val client = connect()
        val c = controller()
        onMain { c.shuffleModeEnabled = false }
        onMain { client.play(songs, 2, PlayingFrom.Playlist, shuffle = true) }
        waitUntil("the chosen song to play") { client.nowPlaying.value?.mediaId == songs[2].key.value }
        // The test's own controller hears about the change from the service a moment after the song starts.
        waitUntil("shuffle to be on") { readOnMain { c.shuffleModeEnabled } && client.nowPlaying.value?.shuffleOn == true }
        assertEquals("every song is queued once", songs.map { it.key.value }.toSet(), queueIds(c).toSet())
        // The service sets the shuffle order a moment after the queue arrives (as in the B2 test of the same rule).
        waitUntil("the chosen song to come first in the shuffled order") { readOnMain { c.previousMediaItemIndex } == androidx.media3.common.C.INDEX_UNSET }
    }

    @Test
    fun PlaylistScreen_QueueEdits_DoNotChangePlaylist() {
        val songs = seedSongs(3, "C2 queue")
        val extra = seedSongs(2, "C2 extra")
        val (store, id) = playlistStore(songs)
        val before = store.entries(id)
        val client = connect()
        val c = controller()
        onMain { client.play(songs, 0, PlayingFrom.Playlist) }
        waitUntil("the queue") { queueIds(c).size == 3 }

        onMain { client.playNext(extra[0]) }
        onMain { client.addToQueue(extra[1]) }
        waitUntil("both songs queued") { queueIds(c).size == 5 }
        val lastIndex = readOnMain { c.mediaItemCount } - 1
        onMain { client.removeFromQueue(lastIndex, extra[1].key.value) }
        waitUntil("one removed") { queueIds(c).size == 4 }
        assertEquals("the playlist is as it was", before, store.entries(id))
    }

    @Test
    fun PlaylistScreen_EditWhilePlaying_DoesNotChangeQueue() {
        val songs = seedSongs(3, "C2 edit")
        val extra = seedSongs(1, "C2 more").single()
        val (store, id) = playlistStore(songs)
        val client = connect()
        val c = controller()
        onMain { client.play(songs, 0, PlayingFrom.Playlist) }
        waitUntil("the queue") { queueIds(c).size == 3 }
        val queued = queueIds(c)

        val entries = store.entries(id)
        store.add(id, extra)
        store.move(id, entries[0].id, 2)
        store.remove(id, entries[1].id)
        assertEquals("the playlist did change", 3, store.entries(id).size)
        Thread.sleep(500)
        assertEquals("the queue did not", queued, queueIds(c))
    }
}
