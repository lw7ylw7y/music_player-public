package app.songplayer.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song
import app.songplayer.core.playlist.AddOutcome
import app.songplayer.core.playlist.PlaylistRules
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PlaylistsDatabase
    private lateinit var store: RoomPlaylistStore

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, PlaylistsDatabase::class.java).build()
        store = RoomPlaylistStore(db)
    }

    @After
    fun close() = db.close()

    private fun songs(n: Int) = (1..n).map { song("s$it.mp3", id = it.toLong(), sizeBytes = 1_000L + it) }

    private fun fill(playlistId: Long, count: Int) = songs(count).forEach { assertTrue(store.add(playlistId, it) is AddOutcome.Added) }

    private fun order(playlistId: Long) = store.entries(playlistId).map { it.fileName }

    @Test
    fun Playlist_CreateRenameDelete_Work_AndTwoMayShareAName() {
        val a = store.create("Driving", 100)
        val b = store.create("Driving", 200)
        assertEquals(listOf("Driving" to 0, "Driving" to 0), store.playlists().map { it.playlist.name to it.songCount })
        store.rename(a.id, "  Gym ")
        assertEquals(listOf("Gym", "Driving"), store.playlists().map { it.playlist.name })
        store.delete(b.id)
        assertEquals(listOf(a.id), store.playlists().map { it.playlist.id })
    }

    @Test
    fun Playlist_EmptyOrTooLongName_IsRejectedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException::class.java) { store.create("   ", 1) }
        val p = store.create("Ok", 1)
        assertThrows(IllegalArgumentException::class.java) { store.rename(p.id, "x".repeat(PlaylistRules.MAX_NAME_LENGTH + 1)) }
        assertEquals("Ok", store.playlists().single().playlist.name)
    }

    @Test
    fun PlaylistDao_AddDuplicate_ChangesNothingAndReportsAlreadyIn() {
        val p = store.create("Driving", 1)
        val s = songs(1).single()
        assertTrue(store.add(p.id, s) is AddOutcome.Added)
        assertEquals(AddOutcome.AlreadyIn, store.add(p.id, s))
        assertEquals(1, store.entries(p.id).size)
        // The same song may be in another playlist.
        val q = store.create("Gym", 2)
        assertTrue(store.add(q.id, s) is AddOutcome.Added)
    }

    @Test
    fun PlaylistDao_AddToADeletedPlaylist_ReportsPlaylistGone() {
        val p = store.create("Gone", 1)
        store.delete(p.id)
        assertEquals(AddOutcome.PlaylistGone, store.add(p.id, songs(1).single()))
    }

    @Test
    fun PlaylistDao_MoveAndRemove_KeepPositionsDense() {
        val p = store.create("P", 1)
        fill(p.id, 5)
        val ids = store.entries(p.id).map { it.id }
        store.move(p.id, ids[1], 4)
        assertEquals(listOf("s1.mp3", "s3.mp3", "s4.mp3", "s5.mp3", "s2.mp3"), order(p.id))
        store.remove(p.id, ids[2])
        assertEquals(listOf("s1.mp3", "s4.mp3", "s5.mp3", "s2.mp3"), order(p.id))
        assertEquals(listOf(0, 1, 2, 3), db.dao().entries(p.id).map { it.position })
    }

    @Test
    fun PlaylistDao_RemoveSongs_DropsTheSongFromEveryPlaylist_AndKeepsPositionsDense() {
        val a = store.create("A", 1)
        val b = store.create("B", 2)
        fill(a.id, 4)
        fill(b.id, 3)
        val gone = songs(4).filter { it.fileName == "s2.mp3" || it.fileName == "s4.mp3" }.map { it.key }.toSet()
        assertEquals(3, store.removeSongs(gone))
        assertEquals(listOf("s1.mp3", "s3.mp3"), order(a.id))
        assertEquals(listOf("s1.mp3", "s3.mp3"), order(b.id))
        assertEquals(listOf(0, 1), db.dao().entries(a.id).map { it.position })
        assertEquals(0, store.removeSongs(gone))
    }

    @Test
    fun PlaylistStore_ExportThenImportIntoEmptyDb_RestoresNamesOrderAndEntries() {
        val a = store.create("Driving", 100)
        val b = store.create("Gym", 200)
        fill(a.id, 4)
        fill(b.id, 2)
        store.move(a.id, store.entries(a.id)[0].id, 3)
        val exported = store.exportAll()
        assertEquals(listOf("Driving", "Gym"), exported.map { it.name })

        store.delete(a.id)
        store.delete(b.id)
        assertEquals(2, store.importAll(exported, createdAtSec = 999))
        assertEquals(exported.map { it.name to it.entries.map { e -> e.songKey } }, store.exportAll().map { it.name to it.entries.map { e -> e.songKey } })
        assertEquals(listOf(0, 1, 2, 3), db.dao().entries(store.playlists().first().playlist.id).map { it.position })
    }

    @Test
    fun PlaylistStore_ImportTwice_AddsNothingTheSecondTime_AndChangesNothingThatIsThere() {
        val a = store.create("Driving", 100)
        fill(a.id, 3)
        val exported = store.exportAll()
        assertEquals(0, store.importAll(exported, 5))
        assertEquals(1, store.playlists().size)
        assertEquals(listOf("s1.mp3", "s2.mp3", "s3.mp3"), order(a.id))
    }

    @Test
    fun PlaylistDao_UndoRemove_RestoresTheEntryInPlace() {
        val p = store.create("P", 1)
        fill(p.id, 5)
        val before = store.entries(p.id)
        val removed = store.remove(p.id, before[2].id)!!
        assertEquals(4, store.entries(p.id).size)
        store.undoRemove(p.id, removed)
        assertEquals(before, store.entries(p.id))
        assertEquals(listOf(0, 1, 2, 3, 4), db.dao().entries(p.id).map { it.position })
    }

    @Test
    fun PlaylistDao_RemoveUnknownEntry_ReturnsNull() {
        val p = store.create("P", 1)
        fill(p.id, 2)
        assertNull(store.remove(p.id, 9_999))
        assertEquals(2, store.entries(p.id).size)
    }

    @Test
    fun PlaylistDao_DeletingAPlaylist_LeavesOtherPlaylistsUntouched() {
        val a = store.create("A", 1)
        val b = store.create("B", 2)
        fill(a.id, 3)
        fill(b.id, 4)
        store.delete(a.id)
        assertEquals(0, store.entries(a.id).size)
        assertEquals(4, store.entries(b.id).size)
        assertEquals(0, db.dao().allEntries().count { it.playlistId == a.id })
    }

    @Test
    fun PlaylistDao_500Songs_ReorderInOneTransaction() {
        val p = store.create("Big", 1)
        fill(p.id, 500)
        val last = store.entries(p.id).last()
        val start = System.nanoTime()
        store.move(p.id, last.id, 0)
        val ms = (System.nanoTime() - start) / 1_000_000
        val after = store.entries(p.id)
        assertEquals(500, after.size)
        assertEquals("s500.mp3", after.first().fileName)
        assertEquals((0 until 500).toList(), db.dao().entries(p.id).map { it.position })
        assertTrue("moving in a 500-song playlist took $ms ms", ms < 3_000)
    }

    // ---- failure: nothing is half-written ------------------------------------------------------

    private fun failing(failOn: String, after: Int = 0): PlaylistDao {
        val real = db.dao()
        var calls = 0
        return object : PlaylistDao by real {
            override fun setPosition(id: Long, position: Int) {
                if (failOn == "setPosition" && calls++ >= after) throw IllegalStateException("disk full")
                real.setPosition(id, position)
            }
            override fun insertEntry(row: EntryRow): Long {
                if (failOn == "insertEntry") throw IllegalStateException("disk full")
                return real.insertEntry(row)
            }
            override fun entries(playlistId: Long): List<EntryRow> {
                if (failOn == "entries") throw IllegalStateException("read error")
                return real.entries(playlistId)
            }
            override fun deleteEntry(id: Long) {
                if (failOn == "deleteEntry") throw IllegalStateException("disk full")
                real.deleteEntry(id)
            }
        }
    }

    @Test
    fun PlaylistsDb_ReadFailsForAnotherReason_IsReadFailedNotCorrupt() {
        val p = store.create("P", 1)
        fill(p.id, 2)
        val broken = RoomPlaylistStore(db, failing("entries"))
        assertThrows(StoreError.ReadFailed::class.java) { broken.entries(p.id) }
        assertEquals(2, store.entries(p.id).size)
    }

    @Test
    fun PlaylistDao_WriteFails_RollsBack() {
        val p = store.create("P", 1)
        fill(p.id, 3)
        val before = store.entries(p.id)
        val broken = RoomPlaylistStore(db, failing("insertEntry"))
        assertThrows(StoreError.WriteFailed::class.java) { broken.add(p.id, song("new.mp3", id = 99)) }
        assertEquals(before, store.entries(p.id))
    }

    @Test
    fun PlaylistDao_UndoRemoveFailsAfterTheRowWasInserted_KeepsTheListWithoutIt() {
        val p = store.create("P", 1)
        fill(p.id, 5)
        val removed = store.remove(p.id, store.entries(p.id)[1].id)!!
        val without = store.entries(p.id)
        // The entry is inserted, then the renumbering fails: the insert must be rolled back too.
        val broken = RoomPlaylistStore(db, failing("setPosition"))
        assertThrows(StoreError.WriteFailed::class.java) { broken.undoRemove(p.id, removed) }
        assertEquals(without, store.entries(p.id))
    }

    @Test
    fun PlaylistDao_StorageFull_IsAWriteFailed() {
        val p = store.create("P", 1)
        val real = db.dao()
        val full = object : PlaylistDao by real {
            override fun insertEntry(row: EntryRow): Long = throw android.database.sqlite.SQLiteFullException("database or disk is full")
        }
        assertThrows(StoreError.WriteFailed::class.java) { RoomPlaylistStore(db, full).add(p.id, songs(1).single()) }
        assertEquals(0, store.entries(p.id).size)
    }

    @Test
    fun PlaylistDao_UndoRemoveIntoADeletedPlaylist_DoesNothing() {
        val p = store.create("P", 1)
        fill(p.id, 2)
        val removed = store.remove(p.id, store.entries(p.id)[0].id)!!
        store.delete(p.id)
        store.undoRemove(p.id, removed)
        assertEquals(0, db.dao().allEntries().size)
    }

    @Test
    fun PlaylistDao_UndoRemoveAfterTheSongWasAddedAgain_DoesNothing() {
        val p = store.create("P", 1)
        val s = songs(2)
        s.forEach { store.add(p.id, it) }
        val removed = store.remove(p.id, store.entries(p.id)[0].id)!!
        store.add(p.id, s[0])
        store.undoRemove(p.id, removed)
        assertEquals(listOf("s2.mp3", "s1.mp3"), order(p.id))
    }

    @Test
    fun Playlist_RenameOrDeleteOfAMissingPlaylist_ChangesNothing() {
        val p = store.create("P", 1)
        store.rename(9_999, "Nope")
        store.delete(9_999)
        assertEquals(listOf("P"), store.playlists().map { it.playlist.name })
        assertEquals(p.id, store.playlists().single().playlist.id)
    }

    @Test
    fun PlaylistsDb_DeletingThePlaylistRowDirectly_CascadesToItsEntries() {
        // Proves that foreign keys are really on, so the cascade is a second line of defence behind the explicit delete.
        val p = store.create("P", 1)
        fill(p.id, 3)
        db.dao().delete(p.id)
        assertEquals(0, db.dao().allEntries().size)
    }

    @Test
    fun PlaylistDao_KilledMidTransaction_KeepsOldList() {
        val p = store.create("P", 1)
        fill(p.id, 6)
        val before = store.entries(p.id)
        // The move renumbers several rows; the process dies (here: an exception) after the second row is written.
        val broken = RoomPlaylistStore(db, failing("setPosition", after = 2))
        assertThrows(StoreError.WriteFailed::class.java) { broken.move(p.id, before.last().id, 0) }
        assertEquals(before, store.entries(p.id))
        assertEquals((0 until 6).toList(), db.dao().entries(p.id).map { it.position })
        // A removal that fails after the row was deleted keeps the row.
        val broken2 = RoomPlaylistStore(db, failing("setPosition"))
        assertThrows(StoreError.WriteFailed::class.java) { broken2.remove(p.id, before[0].id) }
        assertEquals(before, store.entries(p.id))
    }

    @Test
    fun PlaylistDao_ReorderRacingRemove_EndsDense() {
        val p = store.create("P", 1)
        fill(p.id, 6)
        val ids = store.entries(p.id).map { it.id }
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(
            Thread { start.await(); runCatching { store.move(p.id, ids[1], 5) }.onFailure { failures += it } },
            Thread { start.await(); runCatching { store.remove(p.id, ids[1]) }.onFailure { failures += it } },
        )
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue("an action failed: $failures", failures.isEmpty())
        assertEquals(listOf("s1.mp3", "s3.mp3", "s4.mp3", "s5.mp3", "s6.mp3"), order(p.id))
        assertEquals((0 until 5).toList(), db.dao().entries(p.id).map { it.position })
    }

    // ---- relinking after a restore -------------------------------------------------------------

    @Test
    fun Relink_AllPlaylists_LinksEntriesWhoseSongMovedAndKeepsTheOthers() {
        val a = store.create("A", 1)
        val b = store.create("B", 2)
        val old = song("後來.mp3", relativePath = "Old/", id = 1, sizeBytes = 4_000)
        val kept = song("keep.mp3", id = 2, sizeBytes = 5_000)
        store.add(a.id, old)
        store.add(a.id, kept)
        store.add(b.id, old)
        val nowHere = song("後來.mp3", relativePath = "Download/", id = 3, sizeBytes = 4_000)
        assertEquals(2, store.relinkAll(listOf(nowHere, kept)))
        assertEquals(listOf(nowHere.key, kept.key), store.entries(a.id).map { it.songKey })
        assertEquals(listOf(nowHere.key), store.entries(b.id).map { it.songKey })
        assertEquals(0, store.relinkAll(listOf(nowHere, kept)))
    }

    @Test
    fun Relink_TwoMissingEntriesForOneSong_LinksOnlyOne() {
        val p = store.create("P", 1)
        store.add(p.id, song("a.mp3", relativePath = "Old/", id = 1, sizeBytes = 4_000))
        store.add(p.id, song("a.mp3", relativePath = "Older/", id = 2, sizeBytes = 4_000))
        val here = song("a.mp3", relativePath = "Music/", id = 3, sizeBytes = 4_000)
        assertEquals(1, store.relinkAll(listOf(here)))
        assertEquals(1, store.entries(p.id).count { it.songKey == here.key })
    }

    // ---- an unreadable file -------------------------------------------------------------------

    @Test
    fun PlaylistsDb_Corrupt_ReturnsCorruptAndFileIsByteIdentical() {
        val name = "corrupt-playlists-test.db"
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val garbage = ByteArray(4096) { (it * 31 + 7).toByte() }
        file.writeBytes(garbage)
        val damaged = PlaylistsDatabase.open(context, name)
        try {
            val broken = RoomPlaylistStore(damaged)
            assertThrows(StoreError.Corrupt::class.java) { broken.playlists() }
            assertThrows(StoreError.Corrupt::class.java) { broken.add(1, songs(1).single()) }
            assertTrue("the damaged file must not be deleted", file.exists())
            assertTrue("the damaged file must not be changed", garbage.contentEquals(File(file.path).readBytes()))
        } finally {
            damaged.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun PlaylistsDb_KeepsPlaylistsAcrossAReopen() {
        val name = "reopen-playlists-test.db"
        try {
            PlaylistsDatabase.open(context, name).also { RoomPlaylistStore(it).create("Driving", 5).also { p -> RoomPlaylistStore(it).add(p.id, songs(1).single()) } }.close()
            val again = PlaylistsDatabase.open(context, name)
            try {
                val s = RoomPlaylistStore(again)
                assertEquals(listOf("Driving" to 1), s.playlists().map { it.playlist.name to it.songCount })
            } finally {
                again.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
