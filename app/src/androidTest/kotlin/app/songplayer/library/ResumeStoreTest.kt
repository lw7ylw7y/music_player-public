package app.songplayer.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.SongKey
import app.songplayer.core.resume.QueueEntry
import app.songplayer.core.resume.ResumeState
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.QueueItemEntity
import app.songplayer.library.db.ResumeDao
import app.songplayer.library.db.ResumeEntity
import app.songplayer.library.db.RoomResumeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeStoreTest {
    private lateinit var db: AppDatabase
    private fun key(n: Int) = SongKey("external_primary:Music/$n.mp3")

    private fun state(vararg songs: Int, current: Int = 0, position: Long = 42_000, shuffle: Boolean = false) = ResumeState(
        queue = songs.mapIndexed { i, n -> QueueEntry(key(n), added = i == 1, shuffleRank = if (shuffle) songs.size - 1 - i else null) },
        currentIndex = current,
        positionMs = position,
        shuffleOn = shuffle,
        repeatMode = 2,
        source = "Songs",
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun ResumeStore_NothingSaved_LoadsNull() {
        assertNull(RoomResumeStore(db).load())
    }

    @Test
    fun ResumeStore_SaveQueue_RoundTripsEverything() {
        val store = RoomResumeStore(db)
        val saved = state(1, 2, 3, current = 2, shuffle = true)
        store.saveQueue(saved)
        assertEquals(saved, store.load())
    }

    @Test
    fun ResumeStore_SaveQueue_ReplacesTheOldQueue() {
        val store = RoomResumeStore(db)
        store.saveQueue(state(1, 2, 3))
        store.saveQueue(state(7, 8))
        assertEquals(listOf(key(7), key(8)), store.load()!!.queue.map { it.songKey })
    }

    @Test
    fun ResumeStore_EmptyQueue_ClearsEverything() {
        val store = RoomResumeStore(db)
        store.saveQueue(state(1, 2))
        store.saveQueue(ResumeState(emptyList(), -1, 0, false, 0, null))
        assertNull(store.load())
        assertTrue(db.resumeDao().queue().isEmpty())
        assertNull(db.resumeDao().resume())
    }

    @Test
    fun ResumeStore_QueueAndResume_WrittenInOneTransaction() {
        val real = db.resumeDao()
        RoomResumeStore(db, real).saveQueue(state(1, 2, 3, current = 1))
        // A queue write that fails after the old rows were removed leaves the old queue AND the old resume row.
        val failing = object : ResumeDao by real {
            override fun putQueue(items: List<QueueItemEntity>) = throw IllegalStateException("disk full")
        }
        assertThrows(StoreError.WriteFailed::class.java) { RoomResumeStore(db, failing).saveQueue(state(7, 8, 9)) }
        val loaded = RoomResumeStore(db, real).load()!!
        assertEquals(listOf(key(1), key(2), key(3)), loaded.queue.map { it.songKey })
        assertEquals(1, loaded.currentIndex)
    }

    @Test
    fun ResumeStore_ResumeRowFails_QueueIsNotReplacedEither() {
        val real = db.resumeDao()
        RoomResumeStore(db, real).saveQueue(state(1, 2, 3))
        val failing = object : ResumeDao by real {
            override fun putResume(resume: ResumeEntity) = throw IllegalStateException("disk full")
        }
        assertThrows(StoreError.WriteFailed::class.java) { RoomResumeStore(db, failing).saveQueue(state(7, 8)) }
        assertEquals(listOf(key(1), key(2), key(3)), RoomResumeStore(db, real).load()!!.queue.map { it.songKey })
    }

    @Test
    fun ResumeStore_SavePosition_UpdatesTheCurrentSongAndPosition() {
        val store = RoomResumeStore(db)
        store.saveQueue(state(1, 2, 3, current = 0, position = 1_000))
        store.savePosition(index = 2, key = key(3), positionMs = 9_000)
        val loaded = store.load()!!
        assertEquals(2, loaded.currentIndex)
        assertEquals(9_000L, loaded.positionMs)
    }

    @Test
    fun ResumeStore_PositionWriteForAReplacedQueue_IsIgnored() {
        val store = RoomResumeStore(db)
        store.saveQueue(state(1, 2, 3))
        store.saveQueue(state(7, 8)) // the queue is replaced
        // A position write that was prepared for the old queue arrives late.
        store.savePosition(index = 1, key = key(2), positionMs = 55_000)
        val loaded = store.load()!!
        assertEquals(listOf(key(7), key(8)), loaded.queue.map { it.songKey })
        assertEquals(42_000L, loaded.positionMs)
    }

    @Test
    fun ResumeStore_ASongQueuedTwice_RoundTrips() {
        val store = RoomResumeStore(db)
        val saved = state(1, 2, 1, current = 2)
        store.saveQueue(saved)
        assertEquals(saved, store.load())
    }

    @Test
    fun ResumeStore_ResumeRowDisagreesWithTheQueue_LoadsNothing() {
        val dao = db.resumeDao()
        RoomResumeStore(db, dao).saveQueue(state(1, 2, 3, current = 1))
        // A row that points at another song than the queue holds at that place (which the single transaction prevents
        // in practice) is not trusted.
        dao.putResume(ResumeEntity(currentIndex = 1, currentKey = key(9).value, positionMs = 1, shuffleOn = false, repeatMode = 0, queueSource = null))
        assertNull(RoomResumeStore(db, dao).load())
    }
}
