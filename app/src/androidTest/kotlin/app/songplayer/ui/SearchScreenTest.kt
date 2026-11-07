package app.songplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests for search, on fixtures. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val keyer = FakeKeyer()

    // "Hello" matches a title, an album and an artist; the two Chinese songs are one album written two ways.
    private val songs = listOf(
        song("a.mp3", id = 1, title = "Hello", artist = "Adele", album = "25"),
        song("b.mp3", id = 2, title = "Another", artist = "Hello Band", album = "Hello Album"),
        song("c.mp3", id = 3, title = "我等你", artist = "張學友", album = "愛情"),
        song("d.mp3", id = 4, title = "后来", artist = "刘若英", album = "爱情"),
    )
    private val index = SearchIndex.build(songs.map { IndexedSong.of(it, keyer) }, keyer)

    private fun show(onClose: () -> Unit = {}) = rule.setContent {
        SongPlayerTheme { SearchScreen(index, onClose) }
    }

    private fun type(query: String) = rule.onNode(hasSetTextAction()).performTextInput(query)

    @Test
    fun SearchScreen_Typing_NarrowsAllThreeGroups() {
        show()
        type("hello")
        rule.onNodeWithText(text(R.string.tab_songs)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_albums)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_artists)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Hello, Adele, 25").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hello Album, Hello Band, 1 song").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hello Band, 1 song").assertIsDisplayed()
        rule.onNodeWithContentDescription("后来, 刘若英, 爱情").assertDoesNotExist()
    }

    @Test
    fun SearchScreen_SimplifiedAndTraditional_FindEachOther() {
        show()
        type("愛情")
        // Both spellings of the album are one album group (two songs, two artists), and both songs are found.
        rule.onNodeWithContentDescription("愛情, Various artists, 2 songs").assertIsDisplayed()
        rule.onNodeWithContentDescription("后来, 刘若英, 爱情").assertIsDisplayed()
        rule.onNodeWithContentDescription("我等你, 張學友, 愛情").assertIsDisplayed()
    }

    @Test
    fun SearchScreen_NoMatch_ShowsNothingFound() {
        show()
        type("xyzq")
        rule.onNodeWithText("Nothing found for \"xyzq\"").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.nothing_found_body)).assertIsDisplayed()
    }

    @Test
    fun SearchScreen_ClearButton_EmptiesTheField() {
        show()
        type("hello")
        rule.onNodeWithContentDescription(text(R.string.search_clear)).performClick()
        rule.onNodeWithContentDescription(text(R.string.search_clear)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.tab_songs)).assertDoesNotExist()
    }

    @Test
    fun SearchScreen_Back_ClosesSearch() {
        var closed = 0
        show(onClose = { closed++ })
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun SearchScreen_BackArrow_ClosesSearch() {
        var closed = 0
        show(onClose = { closed++ })
        rule.onNodeWithContentDescription(text(R.string.search_back)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun SearchScreen_ClosedAndReopened_StartsWithAnEmptyField() {
        var open by mutableStateOf(true)
        rule.setContent { SongPlayerTheme { if (open) SearchScreen(index, onClose = {}) } }
        type("hello")
        rule.onNodeWithText(text(R.string.tab_songs)).assertIsDisplayed()
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.tab_songs)).assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.search_clear)).assertDoesNotExist()
    }

    @Test
    fun SearchScreen_IndexNotBuiltYet_ShowsNothingNotNothingFound() {
        rule.setContent { SongPlayerTheme { SearchScreen(null, onClose = {}) } }
        type("hello")
        rule.onNodeWithText("Nothing found for \"hello\"").assertDoesNotExist()
    }

    @Test
    fun SearchScreen_LargestFont_ControlsStayVisible() {
        val density = context.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                SongPlayerTheme { SearchScreen(index, onClose = {}) }
            }
        }
        type("hello")
        rule.onNodeWithContentDescription(text(R.string.search_back)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.search_clear)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Hello, Adele, 25").assertIsDisplayed()
    }
}
