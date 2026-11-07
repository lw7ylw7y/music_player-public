package app.songplayer.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.LibraryDiff
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.TextKeyer
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.PlayFailureEntity
import app.songplayer.library.db.RoomSongStore
import app.songplayer.library.db.SettingDao
import app.songplayer.library.db.SettingEntity
import app.songplayer.library.db.SongDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSongStoreTest {
    private lateinit var db: AppDatabase
    private val keyer = FakeKeyer()
    private val a = song("a.mp3", id = 1)
    private val b = song("b.mp3", id = 2)

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun RoomSongStore_ApplyPlan_AddsChangesAndRemoves() {
        val store = RoomSongStore(db, keyer)
        store.apply(LibraryDiff.diff(emptyList(), listOf(a, b)))
        assertEquals(setOf(a, b), store.all().toSet())

        val bRetagged = b.copy(title = "new")
        val c = song("c.mp3", id = 3)
        store.apply(LibraryDiff.diff(store.all(), listOf(bRetagged, c)))
        assertEquals(setOf(bRetagged, c), store.all().toSet())
    }

    @Test
    fun RoomSongStore_ApplyPlan_RoundTripsChineseAndNullTags() {
        val store = RoomSongStore(db, keyer)
        val s = song("我的歌.mp3", title = "後來", artist = "劉若英", album = null)
        store.apply(LibraryDiff.diff(emptyList(), listOf(s)))
        assertEquals(listOf(s), store.all())
    }

    @Test
    fun SongDao_ApplyDiff_FailsHalfway_RollsBack() {
        val seed = RoomSongStore(db, keyer)
        seed.apply(LibraryDiff.diff(emptyList(), listOf(a, b)))

        val real = db.songDao()
        val failing = object : SongDao by real {
            // The last step of the transaction fails, after the deletes and the upsert have run.
            override fun clearFailures(keys: List<String>) = throw IllegalStateException("disk full")
        }
        val store = RoomSongStore(db, keyer, failing)
        val replaced = a.copy(sizeBytes = a.sizeBytes + 1)
        val plan = LibraryDiff.diff(seed.all(), listOf(replaced, song("c.mp3", id = 3)))

        val error = try {
            store.apply(plan)
            null
        } catch (e: StoreError.WriteFailed) {
            e
        }
        assertTrue("apply must report StoreError.WriteFailed", error != null)
        assertEquals("nothing from the failed plan may remain", setOf(a, b), seed.all().toSet())
    }

    @Test
    fun RoomSongStore_FileChanged_ClearsFailureMark_RemovedSongKeepsIt() {
        val store = RoomSongStore(db, keyer)
        store.apply(LibraryDiff.diff(emptyList(), listOf(a, b)))
        db.songDao().markFailed(PlayFailureEntity(a.key.value, failedAt = 1))
        db.songDao().markFailed(PlayFailureEntity(b.key.value, failedAt = 1))

        val replacedA = a.copy(sizeBytes = a.sizeBytes + 1)
        store.apply(LibraryDiff.diff(store.all(), listOf(replacedA))) // a changed on disk, b is gone

        assertEquals(listOf(b.key.value), db.songDao().failedKeys())
    }

    @Test
    fun RoomSongStore_5000Songs_ApplyAndReadBack() {
        val store = RoomSongStore(db, keyer)
        val many = (1..5000).map { song("s$it.mp3", id = it.toLong()) }
        store.apply(LibraryDiff.diff(emptyList(), many))
        assertEquals(5000, store.all().size)
        store.apply(LibraryDiff.diff(store.all(), emptyList()))
        assertEquals(0, store.all().size)
    }

    @Test
    fun RoomSongStore_KeyOfRow_IsTheSongKey() {
        val store = RoomSongStore(db, keyer)
        store.apply(LibraryDiff.diff(emptyList(), listOf(a)))
        assertEquals(SongKey.of("external_primary", "Music/", "a.mp3"), store.all().single().key)
    }

    // ---- sort and search keys (A3) ----

    private class Tagged(private val tag: String, private val inner: TextKeyer = FakeKeyer()) : TextKeyer {
        override val version = tag
        override fun sortKey(text: String) = tag + inner.sortKey(text)
        override fun searchFold(text: String) = tag + inner.searchFold(text)
    }

    @Test
    fun RoomSongStore_Apply_StoresSortAndSearchKeys() {
        val store = RoomSongStore(db, keyer)
        val s = song("我等你.mp3", title = "我等你", artist = "張學友", album = "愛情")
        store.apply(LibraryDiff.diff(emptyList(), listOf(s)))
        assertEquals(listOf(IndexedSong.of(s, keyer)), store.indexed())
    }

    @Test
    fun Recompute_VersionChanged_RewritesAllKeysAndTheVersion() {
        RoomSongStore(db, Tagged("v1:")).also {
            it.apply(LibraryDiff.diff(emptyList(), listOf(a, b)))
            assertTrue("first call stores the version", it.recomputeKeysIfNeeded())
        }
        val v2 = Tagged("v2:")
        val store = RoomSongStore(db, v2)
        assertTrue(store.recomputeKeysIfNeeded())
        assertEquals(setOf(IndexedSong.of(a, v2), IndexedSong.of(b, v2)), store.indexed().toSet())
        assertTrue("same version again does nothing", !store.recomputeKeysIfNeeded())
    }

    @Test
    fun Recompute_EmptyLibrary_StoresTheVersion() {
        val store = RoomSongStore(db, keyer)
        assertTrue(store.recomputeKeysIfNeeded())
        assertTrue(!store.recomputeKeysIfNeeded())
    }

    @Test
    fun Recompute_KilledMidway_KeepsOldKeys() {
        val old = Tagged("v1:")
        val seed = RoomSongStore(db, old)
        seed.apply(LibraryDiff.diff(emptyList(), listOf(a, b, song("c.mp3", id = 3))))
        seed.recomputeKeysIfNeeded()
        val before = seed.indexed().toSet()

        // Every song's keys are rewritten first; the last step, storing the new version, then fails.
        val realSettings = db.settingDao()
        val failingSettings = object : SettingDao by realSettings {
            override fun put(setting: SettingEntity) = throw IllegalStateException("disk full")
        }
        val dying = RoomSongStore(db, Tagged("v2:"), settings = failingSettings)
        val error = try {
            dying.recomputeKeysIfNeeded()
            null
        } catch (e: StoreError.WriteFailed) {
            e
        }
        assertTrue("the failed pass must report StoreError.WriteFailed", error != null)
        assertEquals("old keys must all remain", before, seed.indexed().toSet())
        assertTrue("old version remains, so the next start tries again", RoomSongStore(db, Tagged("v2:")).recomputeKeysIfNeeded())
    }
}
