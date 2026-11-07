package app.songplayer.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.R
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortOrder
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of Settings (story D3), on fixtures. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    @Test
    fun Settings_ShowsTheTwoRowsWithTheirWords() {
        rule.setContent { SongPlayerTheme { SettingsScreen("1.0", { "x" }, {}) } }
        rule.onNodeWithText(text(R.string.settings_copy_diagnostics)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_copy_diagnostics_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_about)).assertIsDisplayed()
        rule.onNodeWithText("Version 1.0 · Plays the music stored on this phone. No account, no ads. The internet is used only to look up song details, and only if you turn that on.").assertIsDisplayed()
    }

    @Test
    fun Settings_CopyDiagnostics_PutsTextOnClipboard() {
        val text = "Music Player diagnostics\nApp version: 1.0 (1)\nPhone: Test Phone\nAndroid: 15 (API 35)\nRecent error codes: none"
        rule.setContent { SongPlayerTheme { SettingsScreen("1.0", { text }, {}) } }
        rule.onNodeWithText(this.text(R.string.settings_copy_diagnostics)).performClick()
        // The message is shown by a coroutine after the click; wait for it instead of reading the screen at once.
        rule.waitUntil(5_000) { rule.onAllNodesWithText(this.text(R.string.diagnostics_copied)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(this.text(R.string.diagnostics_copied)).assertIsDisplayed()
        var copied: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(text, copied)
    }

    @Test
    fun Settings_Back_Closes() {
        var closed = 0
        rule.setContent { SongPlayerTheme { SettingsScreen("1.0", { "x" }, { closed++ }) } }
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun Library_Menu_OffersSettings() {
        val keyer = FakeKeyer()
        val songs = listOf(song("a.mp3", id = 1, title = "Alpha", artist = "Ann", album = "Alb"))
        var opened = 0
        rule.setContent {
            val ui = LibraryUi(
                loaded = true,
                lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
                generation = 1,
            )
            SongPlayerTheme {
                LibraryScreen(ui, onTab = {}, onSort = {}, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { 0 }, onSettings = { opened++ })
            }
        }
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_settings)).performClick()
        assertEquals(1, opened)
    }
}
