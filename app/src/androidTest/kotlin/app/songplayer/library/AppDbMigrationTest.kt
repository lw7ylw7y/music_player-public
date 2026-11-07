package app.songplayer.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.SettingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun AppDb_Migration_5To6_KeepsEverything() {
        // Make a version 5 file: the current schema without the two details tables, and the version number 5.
        val first = AppDatabase.open(context, name)
        first.settingDao().put(SettingEntity("tab", "Albums"))
        first.openHelper.writableDatabase.execSQL("DROP TABLE details_change")
        first.openHelper.writableDatabase.execSQL("DROP TABLE details_state")
        first.openHelper.writableDatabase.execSQL("PRAGMA user_version = 5")
        first.close()

        val migrated = AppDatabase.open(context, name)
        assertEquals("a row of version 5 is kept", "Albums", migrated.settingDao().get("tab"))
        assertEquals(0, migrated.detailsDao().pending().size)
        assertEquals(null, migrated.detailsDao().status("none"))
        migrated.close()
    }
}
