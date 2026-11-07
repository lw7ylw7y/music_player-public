package app.songplayer.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.log.ErrorLogRules
import app.songplayer.core.log.LogCode
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.ErrorLogEntity
import app.songplayer.library.db.RoomErrorLog
import app.songplayer.library.db.SettingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The error log (story D3) on a real Room database. */
@RunWith(AndroidJUnit4::class)
class ErrorLogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

    private fun memoryDb(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        toClean += { db.close() }
        return db
    }

    @Test
    fun ErrorLog_Keeps50Newest() {
        val log = RoomErrorLog(memoryDb())
        for (i in 1..80) log.record(LogCode.PLAYBACK_ITEM_FAILED, listOf(i), atEpochSec = i.toLong())
        val recent = log.recent()
        assertEquals(ErrorLogRules.CAP, recent.size)
        assertEquals("newest first", listOf(80), recent.first().values)
        assertEquals("the 30 oldest are gone", listOf(31), recent.last().values)
    }

    @Test
    fun ErrorLog_CodeFromANewerVersion_IsSkippedNotACrash() {
        val db = memoryDb()
        val log = RoomErrorLog(db)
        log.record(LogCode.RESUME_READ_FAILED, emptyList(), 1)
        db.errorLogDao().insert(ErrorLogEntity(code = "SOME_FUTURE_CODE", atEpochSec = 2, numbers = "7"))
        assertEquals(listOf(LogCode.RESUME_READ_FAILED), log.recent().map { it.code })
    }

    @Test
    fun ErrorLog_ValuesRoundTrip() {
        val log = RoomErrorLog(memoryDb())
        log.record(LogCode.PLAYLIST_READ_FAILED, listOf(1, -2, 300), 5)
        log.record(LogCode.OPEN_FILE_REJECTED, emptyList(), 6)
        assertEquals(listOf(emptyList<Int>(), listOf(1, -2, 300)), log.recent().map { it.values })
    }

    @Test
    fun Diagnostics_LogThatCannotBeOpened_StillCopiesAndSaysUnavailable() {
        val text = app.songplayer.Diagnostics(context) { throw IllegalStateException("cannot open") }.text()
        assertTrue(text.contains("Recent error codes: unavailable"))
        assertTrue(text.startsWith("Music Player diagnostics"))
    }

    @Test
    fun AppDatabase_FromVersion4_KeepsItsDataAndAddsTheErrorLog() {
        val name = "migrate-${System.nanoTime()}.db"
        toClean += { context.deleteDatabase(name) }
        // A current database, made to look like version 4: no error log table, and the version number 4.
        val current = AppDatabase.open(context, name)
        current.settingDao().put(SettingEntity("tab", "Albums"))
        current.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE error_log")
            raw.version = 4
        }

        val migrated = AppDatabase.open(context, name)
        toClean += { migrated.close() }
        assertEquals("what was there is kept", "Albums", migrated.settingDao().get("tab"))
        val log = RoomErrorLog(migrated)
        log.record(LogCode.KEYER_FALLBACK, listOf(1), 1)
        assertTrue("and the error log works", log.recent().single().code == LogCode.KEYER_FALLBACK)
    }
}
