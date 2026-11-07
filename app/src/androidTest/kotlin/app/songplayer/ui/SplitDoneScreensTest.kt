package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.split.SplitOutcome
import app.songplayer.core.split.SplitSummary
import app.songplayer.core.split.StopReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 tests of "Ready to split", "Writing" and "Done" (Stories X9), on fixtures. */
@RunWith(AndroidJUnit4::class)
class SplitDoneScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun summary(free: Long, unchecked: Int = 0) = SplitSummary(
        "Big album.mp3", 12, "Big album 01", "Big album 12", listOf("Music", "Albums"), neededBytes = 520L shl 20, freeBytes = free, uncheckedCuts = unchecked,
    )

    @Test
    fun ReadyToSplit_NothingWrittenUntilConfirmed() {
        var started = 0
        rule.setContent { SongPlayerTheme { ReadyScreen(summary(2L shl 30, unchecked = 3), { started++ }, {}, {}) } }
        rule.onNodeWithText(text(R.string.ready_summary, "Big album.mp3", 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.ready_names_value, "Big album 01", "Big album 12")).assertIsDisplayed()
        rule.onNodeWithText("Music › Albums").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.ready_unchecked, 3)).assertIsDisplayed()
        assertEquals(0, started)                                                       // showing the screen starts nothing
        rule.onNodeWithText(text(R.string.split_split_songs, 12)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun ReadyToSplit_TooLittleSpace_TurnsTheButtonOffWithTheReason() {
        rule.setContent { SongPlayerTheme { ReadyScreen(summary(310L shl 20), {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.split_split_songs, 12)).performScrollTo().assertIsNotEnabled()
        // The reason is in words, with the two sizes.
        rule.onNodeWithText(text(R.string.ready_space_short, android.text.format.Formatter.formatShortFileSize(context, 520L shl 20), android.text.format.Formatter.formatShortFileSize(context, 310L shl 20))).assertIsDisplayed()
    }

    @Test
    fun Writing_ShowsTheSongAndCancels() {
        var cancelled = 0
        rule.setContent { SongPlayerTheme { WritingScreen(4, 12, { cancelled++ }) } }
        rule.onNodeWithText(text(R.string.writing_progress, 5, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.dialog_cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun Writing_Cancel_ShowsHowManyKept() {
        // After a cancel the message says how many songs were kept, and offers to remove them; a full disk adds its reason.
        val cancelled = SplitOutcome(12, 5, 0, StopReason.Cancelled)
        val shown = androidx.compose.runtime.mutableStateOf(cancelled)
        rule.setContent { SongPlayerTheme { FinishedScreen(shown.value, "Big album.mp3", listOf("Music"), false, false, {}, {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.stopped_title, 5)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.done_remove_songs)).assertIsDisplayed()
        rule.runOnIdle { shown.value = SplitOutcome(12, 5, 0, StopReason.NoSpace) }
        rule.onNodeWithText(text(R.string.stopped_no_space)).assertIsDisplayed()
    }

    @Test
    fun Done_DeleteOriginal_AsksAndKeepsByDefault() {
        var deleted = 0
        var shown = 0
        val done = SplitOutcome(12, 12, 0, null)
        rule.setContent { SongPlayerTheme { FinishedScreen(done, "Big album.mp3", listOf("Music", "Albums"), false, false, { shown++ }, { deleted++ }, {}, {}) } }
        rule.onNodeWithText(text(R.string.done_title, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.done_where, "Music › Albums")).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.done_delete_original_question, "Big album.mp3")).assertIsDisplayed()
        assertEquals(0, deleted)                                                        // nothing is deleted by showing the question
        rule.onNodeWithText(text(R.string.done_keep)).performClick()
        rule.onNodeWithText(text(R.string.done_delete_original_question, "Big album.mp3")).assertDoesNotExist()
        assertEquals(0, deleted)
        rule.onNodeWithText(text(R.string.done_show_songs)).performClick()
        assertEquals(1, shown)
    }

    @Test
    fun Done_Delete_AsksTheScreensCallbackOnce() {
        var deleted = 0
        rule.setContent { SongPlayerTheme { FinishedScreen(SplitOutcome(12, 12, 0, null), "Big album.mp3", listOf("Music"), false, false, {}, { deleted++ }, {}, {}) } }
        rule.onNodeWithText(text(R.string.done_delete)).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun Done_RemoveSongs_AsksThenListsOnlyThisRunsFiles() {
        var removed = 0
        rule.setContent { SongPlayerTheme { FinishedScreen(SplitOutcome(12, 12, 0, null), "Big album.mp3", listOf("Music"), false, false, {}, {}, { removed++ }, {}) } }
        rule.onNodeWithText(text(R.string.done_remove_songs)).performClick()
        rule.onNodeWithText(text(R.string.done_remove_title, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.done_remove_body)).assertIsDisplayed()
        assertEquals(0, removed)
        rule.onNodeWithText(text(R.string.dialog_cancel)).performClick()
        assertEquals(0, removed)                                                        // a cancelled question deletes nothing
        rule.onNodeWithText(text(R.string.done_remove_songs)).performClick()
        rule.onNodeWithText(text(R.string.done_remove_confirm)).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun Done_AfterAContinuedSplit_SaysHowManyWereEarlier() {
        rule.setContent { SongPlayerTheme { FinishedScreen(SplitOutcome(12, 12, 7, null), "Big album.mp3", listOf("Music"), false, false, {}, {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.done_title_continued, 12, 7, 5)).assertIsDisplayed()
    }
}
