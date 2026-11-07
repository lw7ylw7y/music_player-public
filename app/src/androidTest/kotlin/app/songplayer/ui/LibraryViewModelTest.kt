package app.songplayer.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.SongPlayerApp
import app.songplayer.core.text.SortOrder
import app.songplayer.library.LibraryPrefs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** L3: the view model's sort rules, on the app's real container (it puts back the sort and direction that were saved). */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    private val container = (ApplicationProvider.getApplicationContext<android.content.Context>() as SongPlayerApp).container
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val prefs get() = LibraryPrefs(container.database.settingDao())
    private val savedSort = prefs.sort()
    private val savedReversed = prefs.sortReversed()

    @After
    fun restoreTheSavedView() {
        prefs.apply {
            saveSort(savedSort)
            saveSortReversed(savedReversed)
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun SortDirection_ChangingTheField_ResetsTheDirection() {
        var vm: LibraryViewModel? = null
        instrumentation.runOnMainSync { vm = LibraryViewModel(container) }
        val model = vm!!
        // The state is only kept while something collects it, as the screen does.
        val seen = java.util.concurrent.atomic.AtomicReference<LibraryUi?>(null)
        val job = GlobalScope.launch(Dispatchers.Default) { model.state.collect { seen.set(it) } }
        try {
            waitUntil("the library view to load") { seen.get()?.loaded == true }
            instrumentation.runOnMainSync { model.reverseSort() }
            waitUntil("the order to be reversed") { seen.get()?.reversed == true }
            waitUntil("the direction to be saved") { LibraryPrefs(container.database.settingDao()).sortReversed() }

            instrumentation.runOnMainSync { model.selectSort(SortOrder.Artist) }
            waitUntil("another field to start in its natural direction") { seen.get()?.sort == SortOrder.Artist }
            assertFalse(seen.get()!!.reversed)
            waitUntil("the direction to be saved") { !LibraryPrefs(container.database.settingDao()).sortReversed() }
            assertEquals(SortOrder.Artist, LibraryPrefs(container.database.settingDao()).sort())
        } finally {
            job.cancel()
        }
    }
}
