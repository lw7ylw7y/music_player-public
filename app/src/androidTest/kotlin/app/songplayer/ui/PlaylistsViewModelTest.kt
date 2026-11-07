package app.songplayer.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink
import app.songplayer.core.playlist.PlaylistStore
import app.songplayer.playlist.PlaylistsDatabase
import app.songplayer.playlist.RoomPlaylistStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3 tests of the playlists ViewModel on a real Room database: what the Playlists tab and the playlist screen are
 * built from. A "restart" is a new database object on the same file and a new ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistsViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val toClose = mutableListOf<() -> Unit>()
    private val logged = mutableListOf<Pair<LogCode, List<Int>>>()
    private val log = AppLog(listOf(object : LogSink {
        override fun write(code: LogCode, values: List<Int>) {
            synchronized(logged) { logged += code to values }
        }
    }))

    @After
    fun cleanUp() = toClose.reversed().forEach { runCatching { it() } }

    private fun songs(n: Int): List<Song> = (1..n).map { song("v$it.mp3", id = it.toLong(), title = "Song $it", sizeBytes = 1_000L + it) }

    private fun memoryStore(): RoomPlaylistStore {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistsDatabase::class.java).build()
        toClose += { db.close() }
        return RoomPlaylistStore(db)
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T>()
        start {
            result.set(it)
            latch.countDown()
        }
        assertTrue("timed out waiting for the ViewModel to finish", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun PlaylistsViewModel.readyLists(): PlaylistsState.Ready = runBlocking {
        withTimeout(10_000) { lists.first { it is PlaylistsState.Ready } } as PlaylistsState.Ready
    }

    @Test
    fun PlaylistScreen_Create_AddSongs_SurvivesRestart() {
        val name = "vm-test-${System.nanoTime()}.db"
        toClose += { context.deleteDatabase(name) }
        val first = PlaylistsDatabase.open(context, name)
        val created = await<CreateResult> { PlaylistsViewModel(RoomPlaylistStore(first), log).create("  Driving ", songs(2), it) }
        first.close()
        assertEquals(2, (created as CreateResult.Created).added)
        assertEquals("the name is stored trimmed", "Driving", created.playlist.name)

        val second = PlaylistsDatabase.open(context, name)
        toClose += { second.close() }
        val ready = PlaylistsViewModel(RoomPlaylistStore(second), log).readyLists()
        assertEquals(listOf("Driving" to 2), ready.playlists.map { it.playlist.name to it.songCount })
    }

    @Test
    fun PlaylistsViewModel_AddSongs_AlreadyInIsCountedAndNothingIsDuplicated() {
        val vm = PlaylistsViewModel(memoryStore(), log)
        val id = (await<CreateResult> { vm.create("P", songs(2), it) } as CreateResult.Created).playlist.id
        val result = await<AddResult> { vm.addSongs(id, songs(3), it) }
        assertEquals(AddResult.Done(added = 1, alreadyIn = 2), result)
        assertEquals(3, vm.readyLists().playlists.single().songCount)
    }

    @Test
    fun PlaylistsViewModel_AddToADeletedPlaylist_ReportsItIsGone() {
        val vm = PlaylistsViewModel(memoryStore(), log)
        val id = (await<CreateResult> { vm.create("P", emptyList(), it) } as CreateResult.Created).playlist.id
        assertTrue(await<Boolean> { vm.delete(id, it) })
        assertEquals(AddResult.PlaylistGone, await<AddResult> { vm.addSongs(id, songs(1), it) })
    }

    @Test
    fun PlaylistsViewModel_RemoveThenUndo_PutsTheSongBackInPlace() {
        val vm = PlaylistsViewModel(memoryStore(), log)
        val library = songs(3)
        val id = (await<CreateResult> { vm.create("P", library, it) } as CreateResult.Created).playlist.id
        vm.setLibrary(library)
        vm.open(id)
        fun ready(count: Int) = runBlocking {
            withTimeout(10_000) { vm.detail.first { it is DetailState.Ready && it.items.size == count } } as DetailState.Ready
        }
        val before = ready(3).items.map { it.entry.id }
        val (saved, removed) = await<Pair<Boolean, app.songplayer.core.playlist.RemovedEntry?>> { done -> vm.remove(id, before[1]) { s, r -> done(s to r) } }
        assertTrue(saved)
        assertNotNull(removed)
        assertEquals(listOf(before[0], before[2]), ready(2).items.map { it.entry.id })
        assertTrue(await<Boolean> { vm.undoRemove(id, removed!!, it) })
        assertEquals(before, ready(3).items.map { it.entry.id })
    }

    @Test
    fun PlaylistsViewModel_Move_ReordersAndIsSaved() {
        val vm = PlaylistsViewModel(memoryStore(), log)
        val library = songs(3)
        val id = (await<CreateResult> { vm.create("P", library, it) } as CreateResult.Created).playlist.id
        vm.setLibrary(library)
        vm.open(id)
        val before = runBlocking {
            withTimeout(10_000) { vm.detail.first { it is DetailState.Ready && it.items.size == 3 } } as DetailState.Ready
        }.items.map { it.entry.id }
        assertTrue(await<Boolean> { vm.move(id, before[0], 2, it) })
        val after = runBlocking {
            withTimeout(10_000) { vm.detail.first { it is DetailState.Ready && it.items.map { i -> i.entry.id } != before } } as DetailState.Ready
        }.items.map { it.entry.id }
        assertEquals(listOf(before[1], before[2], before[0]), after)
    }

    @Test
    fun PlaylistsViewModel_StorageFull_ReportsFailureLogsOnlyACodeAndKeepsTheLists() {
        val real = memoryStore()
        val full = object : PlaylistStore by real {
            override fun create(name: String, createdAtSec: Long) = throw StoreError.WriteFailed()
        }
        val vm = PlaylistsViewModel(full, log)
        assertEquals(CreateResult.Failed, await<CreateResult> { vm.create("Secret name", songs(1), it) })
        assertTrue(vm.readyLists().playlists.isEmpty())
        val entries = synchronized(logged) { logged.toList() }
        assertTrue(entries.any { it.first == LogCode.PLAYLIST_WRITE_FAILED && it.second == listOf(0) })
    }

    @Test
    fun PlaylistsViewModel_DamagedFile_IsUnavailableDamaged_AndAnotherReadErrorIsNot() {
        val real = memoryStore()
        val damaged = object : PlaylistStore by real {
            override fun playlists() = throw StoreError.Corrupt()
        }
        val unreadable = object : PlaylistStore by real {
            override fun playlists() = throw StoreError.ReadFailed()
        }
        fun PlaylistsViewModel.unavailable() = runBlocking {
            withTimeout(10_000) { lists.first { it is PlaylistsState.Unavailable } } as PlaylistsState.Unavailable
        }
        assertEquals(PlaylistsState.Unavailable(damaged = true), PlaylistsViewModel(damaged, log).unavailable())
        assertEquals(PlaylistsState.Unavailable(damaged = false), PlaylistsViewModel(unreadable, log).unavailable())
    }

    @Test
    fun PlaylistsViewModel_Relink_AfterAFileMove_LinksTheEntryToTheNewSong() {
        val store = memoryStore()
        val vm = PlaylistsViewModel(store, log)
        val old = song("a.mp3", relativePath = "Music/Old/", id = 1, sizeBytes = 5_000)
        val id = (await<CreateResult> { vm.create("P", listOf(old), it) } as CreateResult.Created).playlist.id
        val moved = song("a.mp3", relativePath = "Music/New/", id = 2, sizeBytes = 5_000)
        vm.relink(listOf(moved))
        val end = System.currentTimeMillis() + 10_000
        while (store.entries(id).single().songKey != moved.key && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals(moved.key, store.entries(id).single().songKey)
    }
}
