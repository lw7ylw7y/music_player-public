package app.songplayer.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.text.SortOrder
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.SettingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "prefs-test.db"

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun LibraryPrefs_NothingSaved_ReturnsDefaults() {
        val db = open()
        val prefs = LibraryPrefs(db.settingDao())
        assertEquals(LibraryTab.Songs, prefs.tab())
        assertEquals(SortOrder.Title, prefs.sort())
        assertFalse(prefs.accessAsked())
        db.close()
    }

    @Test
    fun Library_LastTabAndSort_SurviveRestart() {
        val first = open()
        LibraryPrefs(first.settingDao()).apply {
            saveTab(LibraryTab.Albums)
            saveSort(SortOrder.RecentlyAdded)
            saveAccessAsked()
        }
        first.close() // the process "restarts": a new database object reads the same file

        val second = open()
        val prefs = LibraryPrefs(second.settingDao())
        assertEquals(LibraryTab.Albums, prefs.tab())
        assertEquals(SortOrder.RecentlyAdded, prefs.sort())
        assertTrue(prefs.accessAsked())
        second.close()
    }

    @Test
    fun LibraryPrefs_SortReversed_SurvivesRestart() {
        val first = open()
        assertFalse("nothing saved is the natural direction", LibraryPrefs(first.settingDao()).sortReversed())
        LibraryPrefs(first.settingDao()).saveSortReversed(true)
        first.close()

        val second = open()
        assertTrue(LibraryPrefs(second.settingDao()).sortReversed())
        LibraryPrefs(second.settingDao()).saveSortReversed(false)
        assertFalse(LibraryPrefs(second.settingDao()).sortReversed())
        second.close()
    }

    @Test
    fun LibraryPrefs_UnknownDirection_FallsBackToDefault() {
        val db = open()
        db.settingDao().put(SettingEntity("sortReversed", "sideways"))
        assertFalse(LibraryPrefs(db.settingDao()).sortReversed())
        db.close()
    }

    @Test
    fun LibraryPrefs_UnknownStoredValue_FallsBackToDefault() {
        val db = open()
        db.settingDao().put(SettingEntity("tab", "Podcasts"))
        db.settingDao().put(SettingEntity("sort", "Loudness"))
        val prefs = LibraryPrefs(db.settingDao())
        assertEquals(LibraryTab.Songs, prefs.tab())
        assertEquals(SortOrder.Title, prefs.sort())
        db.close()
    }
}
