package app.songplayer.playlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.library.song
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.RoomResumeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A simulated restore (story E4, R-17): what Android's backup would copy to a new phone is the main file `playlists.db` and
 * nothing else. The real backup and restore with Google's service is the user's manual check (L4).
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

    private fun name(prefix: String) = "$prefix-${System.nanoTime()}.db".also { n -> toClean += { context.deleteDatabase(n) } }

    @Test
    fun Restore_PlaylistsDbOnly_StartsWithEmptyCacheAndKeepsPlaylists() {
        val oldSongs = (1..3).map { song("s$it.mp3", relativePath = "Music/Old/", id = it.toLong(), sizeBytes = 1_000L + it) }
        val source = name("backup-source")
        val sourceDb = PlaylistsDatabase.open(context, source)
        val sourceStore = RoomPlaylistStore(sourceDb)
        val playlist = sourceStore.create("Driving", 1)
        oldSongs.forEach { sourceStore.add(playlist.id, it) }
        sourceStore.move(playlist.id, sourceStore.entries(playlist.id).first().id, 2)
        val orderBefore = sourceStore.entries(playlist.id).map { it.fileName }

        // The backup copies the main file while the app may still have the database open: no side files come with it.
        val restoredName = name("restored")
        context.getDatabasePath(source).copyTo(context.getDatabasePath(restoredName), overwrite = true)
        // The setting that makes a main-file copy complete: the rollback journal, not the write-ahead log.
        val journalMode = sourceDb.openHelper.writableDatabase.query("PRAGMA journal_mode").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("truncate", journalMode.lowercase())
        sourceDb.close()

        val restoredDb = PlaylistsDatabase.open(context, restoredName)
        toClean += { restoredDb.close() }
        val restored = RoomPlaylistStore(restoredDb)
        val summary = restored.playlists().single()
        assertEquals("Driving", summary.playlist.name)
        assertEquals(3, summary.songCount)
        assertEquals("names and order are kept, even the change made just before the backup", orderBefore, restored.entries(summary.playlist.id).map { it.fileName })

        // The cache, the queue and the resume state are not in the backup: the app starts with them empty.
        val appDb = AppDatabase.open(context, name("restored-app"))
        toClean += { appDb.close() }
        assertTrue(appDb.songDao().all().isEmpty())
        assertNull(RoomResumeStore(appDb).load())

        // On the new phone the files are somewhere else: the entries find their songs again by file name and size (C1's rule).
        val newSongs = oldSongs.mapIndexed { i, s -> song(s.fileName, relativePath = "Download/Moved/", id = 100L + i, sizeBytes = s.sizeBytes) }
        assertEquals(3, restored.relinkAll(newSongs))
        assertEquals(newSongs.map { it.key }.toSet(), restored.entries(summary.playlist.id).map { it.songKey }.toSet())
        assertEquals("and the order is the same", orderBefore, restored.entries(summary.playlist.id).map { it.fileName })
    }

    @Test
    fun PlaylistsDb_AFileLeftInWalModeByAnEarlierVersion_OpensAsTruncateWithItsData() {
        // Before E4 the file used Room's default, the write-ahead log. Phones that ran those builds have such a file.
        val name = name("was-wal")
        val first = PlaylistsDatabase.open(context, name)
        val store = RoomPlaylistStore(first)
        val playlist = store.create("Old", 1)
        store.add(playlist.id, song("a.mp3", id = 1, sizeBytes = 1_001))
        first.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.enableWriteAheadLogging()
            val mode = raw.rawQuery("PRAGMA journal_mode", null).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("the file really is in WAL mode now", "wal", mode.lowercase())
        }

        val reopened = PlaylistsDatabase.open(context, name)
        toClean += { reopened.close() }
        val journalMode = reopened.openHelper.writableDatabase.query("PRAGMA journal_mode").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("truncate", journalMode.lowercase())
        assertEquals("Old", RoomPlaylistStore(reopened).playlists().single().playlist.name)
        assertEquals(1, RoomPlaylistStore(reopened).entries(playlist.id).size)
    }

    @Test
    fun Relink_NothingMatchesOnTheNewPhone_KeepsTheEntries() {
        val source = name("backup-missing")
        val db = PlaylistsDatabase.open(context, source)
        toClean += { db.close() }
        val store = RoomPlaylistStore(db)
        val p = store.create("Quiet", 1)
        store.add(p.id, song("gone.mp3", id = 1, sizeBytes = 5_000))
        // Nothing on the new phone matches: the entry stays (the screens mark it "Can't play", R-12) and nothing is invented.
        assertEquals(0, store.relinkAll(listOf(song("other.mp3", id = 2, sizeBytes = 9_999))))
        assertEquals(1, store.entries(p.id).size)
        assertEquals("gone.mp3", store.entries(p.id).single().fileName)
    }
}
