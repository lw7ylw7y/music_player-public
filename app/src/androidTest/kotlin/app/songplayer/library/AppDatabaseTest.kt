package app.songplayer.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.library.db.AppDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "corrupt-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun AppDb_Corrupt_RebuiltEmpty() {
        // A file that is not a database at all: Android replaces it with an empty one and the app opens normally.
        File(context.getDatabasePath(name).apply { parentFile?.mkdirs() }.path).writeBytes(ByteArray(4096) { (it * 31).toByte() })
        val db = AppDatabase.open(context, name)
        try {
            assertTrue(db.songDao().all().isEmpty())
            assertTrue(db.resumeDao().queue().isEmpty())
            // And it is a working database again.
            db.settingDao().put(app.songplayer.library.db.SettingEntity("tab", "Albums"))
            assertEquals("Albums", db.settingDao().get("tab"))
        } finally {
            db.close()
        }
    }

    @Test
    fun AppDb_Healthy_KeepsItsContents() {
        AppDatabase.open(context, name).apply {
            settingDao().put(app.songplayer.library.db.SettingEntity("sort", "Artist"))
            close()
        }
        val db = AppDatabase.open(context, name)
        try {
            assertEquals("Artist", db.settingDao().get("sort"))
        } finally {
            db.close()
        }
    }
}
