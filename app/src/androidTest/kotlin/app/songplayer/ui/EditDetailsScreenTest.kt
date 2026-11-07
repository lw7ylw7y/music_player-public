package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.details.DetailField
import app.songplayer.core.details.TagFields
import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of "Edit details" and "Recently filled in" on fixtures (Stories H3). */
@RunWith(AndroidJUnit4::class)
class EditDetailsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val song = song("a.mp3", title = "Rain", artist = "Ann", album = "Weather")

    @Test
    fun EditDetails_SaveIsOffUntilSomethingChanges_AndHandsBackTheCleanedText() {
        var saved: TagFields? = null
        rule.setContent { SongPlayerTheme { EditDetailsScreen(song, onSave = { saved = it }, onClose = {}) } }
        rule.onNodeWithText(text(R.string.edit_details_save)).assertIsNotEnabled()
        rule.onNodeWithText("Rain").performTextClearance()
        rule.onNodeWithText(text(R.string.edit_field_title)).performTextInput("  Sunny day ")
        rule.onNodeWithText(text(R.string.edit_details_save)).assertIsEnabled().performClick()
        assertEquals(TagFields("Sunny day", "Ann", "Weather"), saved)
    }

    @Test
    fun EditDetails_ABlankField_MeansNoValue() {
        var saved: TagFields? = null
        rule.setContent { SongPlayerTheme { EditDetailsScreen(song, onSave = { saved = it }, onClose = {}) } }
        rule.onNodeWithText("Weather").performTextClearance()
        rule.onNodeWithText(text(R.string.edit_details_save)).performClick()
        assertEquals(TagFields("Rain", "Ann", null), saved)
    }

    @Test
    fun EditDetails_DiscardDialog_AsksOnlyWhenThereAreChanges() {
        var closed = 0
        rule.setContent { SongPlayerTheme { EditDetailsScreen(song, onSave = {}, onClose = { closed++ }) } }
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals("nothing changed: leaves at once", 1, closed)
        rule.onNodeWithText(text(R.string.edit_field_artist)).performTextInput("x")
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        rule.onNodeWithText(text(R.string.edit_details_discard_title)).assertIsDisplayed()
        assertEquals(1, closed)
        rule.onNodeWithText(text(R.string.edit_details_discard)).performClick()
        assertEquals(2, closed)
    }

    @Test
    fun SongDetails_ListsRecentChangesWithUndo_AndSaysWhenThereAreNone() {
        var undone: Long? = null
        rule.setContent {
            SongPlayerTheme {
                SongDetailsScreen(
                    ui = null,
                    rows = listOf(RecentRowUi(7, "Rain", "Ann", listOf(DetailField.Title, DetailField.Album), byUser = true)),
                    onUndo = { undone = it.batch }, onClose = {}, onEnabled = {}, onWifiOnly = {}, onAllowFiles = {},
                )
            }
        }
        rule.onNodeWithText(text(R.string.recent_title)).assertIsDisplayed()
        rule.onNodeWithText("Ann · ${text(R.string.recent_edited_by_you)} · Title, Album").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.snackbar_undo)).performClick()
        assertEquals(7L, undone)
    }

    @Test
    fun SongDetails_Empty_SaysNothingChanged() {
        rule.setContent { SongPlayerTheme { SongDetailsScreen(ui = null, rows = emptyList(), onUndo = {}, onClose = {}, onEnabled = {}, onWifiOnly = {}, onAllowFiles = {}) } }
        rule.onNodeWithText(text(R.string.recent_empty)).assertIsDisplayed()
    }

    @Test
    fun SongMenu_OffersEditDetails_WhenTheRootProvidesIt() {
        var edited: String? = null
        rule.setContent {
            SongPlayerTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalEditDetails provides { s -> edited = s.title }) {
                    SongRow(song, onPlayNext = {})
                }
            }
        }
        rule.onNodeWithContentDescription(context.getString(R.string.song_more_options, "Rain")).performClick()
        rule.onNodeWithText(text(R.string.menu_edit_details)).performClick()
        assertEquals("Rain", edited)
    }
}
