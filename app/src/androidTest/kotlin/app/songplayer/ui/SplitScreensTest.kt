package app.songplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import app.songplayer.core.split.RunCut
import app.songplayer.core.split.RunPhase
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.SongsFound
import app.songplayer.core.split.SourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 tests of the screens up to "The songs found" (Stories X7), on fixtures. */
@RunWith(AndroidJUnit4::class)
class SplitScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val long = song("album.mp3", id = 1, title = "Big album", durationMs = 45 * 60_000L)
    private val short = song("short.mp3", id = 2, title = "Short", durationMs = 4 * 60_000L)

    private fun record(vararg cuts: RunCut) = RunRecord(
        "r", SourceIdentity(1, 1, 1, "h"), "album.mp3", cuts.toList(), "Music/", "external_primary", RunPhase.Reviewing, emptyList(), 1,
    )

    private fun found(vararg cuts: RunCut) = SongsFound.of(record(*cuts), 45 * 60.0)

    @Test
    fun SplitEntry_ShownOnlyForLongSongs() {
        var started: Song? = null
        rule.setContent {
            SongPlayerTheme {
                CompositionLocalProvider(LocalSplitSong provides { started = it }) {
                    androidx.compose.foundation.layout.Column {
                        SongRow(long, onClick = {}, onPlayNext = {}, onAddToQueue = {})
                        SongRow(short, onClick = {}, onPlayNext = {}, onAddToQueue = {})
                    }
                }
            }
        }
        rule.onNodeWithContentDescription(text(R.string.song_more_options, "Big album")).performClick()
        rule.onNodeWithText(text(R.string.menu_split)).assertIsDisplayed().performClick()
        assertEquals(long, started)
        rule.onNodeWithContentDescription(text(R.string.song_more_options, "Short")).performClick()
        assertEquals("a 4-minute song has no such entry", 0, rule.onAllNodesWithText(text(R.string.menu_split)).fetchSemanticsNodes().size)
    }

    @Test
    fun SplitChecks_EachFailure_ShowsItsMessage() {
        val cases = listOf(
            SplitMessage.Unreadable to (R.string.split_unreadable_title to text(R.string.split_unreadable_body)),
            SplitMessage.TooLarge to (R.string.split_too_large_title to text(R.string.split_too_large_body)),
            SplitMessage.TooManySongs(143) to (R.string.split_too_many_title to text(R.string.split_too_many_body, 143, 120)),
            SplitMessage.Gone to (R.string.split_gone_title to text(R.string.split_gone_body)),
        )
        val shown = androidx.compose.runtime.mutableStateOf<SplitUi>(SplitUi.Idle)
        rule.setContent { SongPlayerTheme { SplitDialogs(shown.value, onDismiss = { shown.value = SplitUi.Idle }, onUseFolder = {}, onContinue = {}, onStartOver = {}) } }
        for ((message, expected) in cases) {
            rule.runOnIdle { shown.value = SplitUi.Message(message) }
            rule.onNodeWithText(text(expected.first)).assertIsDisplayed()
            rule.onNodeWithText(expected.second).assertIsDisplayed()
            rule.onNodeWithText(text(R.string.dialog_ok)).performClick()
            rule.onNodeWithText(text(expected.first)).assertDoesNotExist()
        }
    }

    @Test
    fun SplitChecks_FolderAndEarlierRun_AskTheirQuestions() {
        var folder = 0
        var again = 0
        val shown = androidx.compose.runtime.mutableStateOf<SplitUi>(SplitUi.AskFolder(long, "Music/album/"))
        rule.setContent {
            SongPlayerTheme { SplitDialogs(shown.value, onDismiss = {}, onUseFolder = { folder++ }, onContinue = {}, onStartOver = { again++ }) }
        }
        rule.onNodeWithText(text(R.string.split_folder_body, "Music › album")).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_folder_use)).performClick()
        assertEquals(1, folder)
        rule.runOnIdle { shown.value = SplitUi.AskContinue(long, 7, 12) }
        rule.onNodeWithText(text(R.string.split_continue_body, 7, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_start_over)).performClick()
        assertEquals(1, again)
    }

    @Test
    fun SongsFound_ListAndPrimaryAction() {
        val sure = RunCut(252.0, 2.0, true, false)
        val unsure = RunCut(510.0, 1.0, false, false)
        var splitAsFound = 0
        rule.setContent { SongPlayerTheme { SongsFoundScreen(long, found(sure, unsure), false, {}, {}, {}, onSplitAsFound = { splitAsFound++ }) } }
        rule.onNodeWithText(text(R.string.split_found_header, "album.mp3", 3)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_found_counts, 2, 1)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_check_one_song)).assertIsDisplayed()
        // Each row speaks its number, times, length and state; the states are words, with an icon beside them.
        rule.onNodeWithContentDescription(text(R.string.split_row_description, 2, "4:12", "8:30", "4:18", text(R.string.split_row_looks_right))).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.split_row_description, 3, "8:30", "45:00", "36:30", text(R.string.split_row_check))).assertIsDisplayed()
        // The user may go on without looking at the uncertain cut: they are used as found (UX D-26), and K4 says how many.
        rule.onNodeWithText(text(R.string.split_split_songs, 3)).assertIsDisplayed().performClick()
        assertEquals(1, splitAsFound)
    }

    @Test
    fun SongsFound_AllSure_OffersSplitIntoN() {
        rule.setContent { SongPlayerTheme { SongsFoundScreen(long, found(RunCut(252.0, 2.0, true, false)), false, {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.split_split_songs, 2)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_found_all_right, 2)).assertIsDisplayed()
    }

    @Test
    fun SongsFound_NoGaps_OffersAddACut() {
        val typed = mutableListOf<String>()
        rule.setContent { SongPlayerTheme { SongsFoundScreen(long, found(), false, { typed += it }, {}, {}) } }
        rule.onNodeWithText(text(R.string.split_no_gaps_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_no_gaps_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_add_cut)).assertIsEnabled().performClick()
        rule.onNodeWithText(text(R.string.split_add_cut_label)).performTextInput("8:30")
        rule.onNodeWithText(text(R.string.split_add_cut_confirm)).performClick()
        assertEquals(listOf("8:30"), typed)
    }

    @Test
    fun SongsFound_Leave_AsksFirst() {
        var left = 0
        rule.setContent { SongPlayerTheme { SongsFoundScreen(long, found(), false, {}, {}, { left++ }) } }
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        rule.onNodeWithText(text(R.string.split_leave_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_stay)).performClick()
        assertEquals(0, left)
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        rule.onNodeWithText(text(R.string.split_leave)).performClick()
        assertEquals(1, left)
    }

    @Test
    fun SplitFinding_ShowsProgressInWords_AndCancels() {
        var cancelled = 0
        rule.setContent { SongPlayerTheme { FindingScreen(long, 400, { cancelled++ }) } }
        rule.onNodeWithText(text(R.string.split_finding)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_progress, 40)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.dialog_cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun SplitFinding_KeepGoingInTheBackground_IsNotCancel() {
        var cancelled = 0
        var background = 0
        rule.setContent { SongPlayerTheme { FindingScreen(long, 400, { cancelled++ }, { background++ }) } }
        rule.onNodeWithText(text(R.string.split_in_background)).performClick()
        assertEquals(listOf(0, 1), listOf(cancelled, background))
    }

    @Test
    fun SplitBanner_EachState_SaysWhatIsGoingOn_AndOffersTheWayBack() {
        var opened = 0
        var dismissed = 0
        val shown = androidx.compose.runtime.mutableStateOf<SplitBannerUi>(SplitBannerUi.Finding(400))
        rule.setContent { SongPlayerTheme { SplitBannerRow(SplitBanner(shown.value, { opened++ }, { dismissed++ })) } }
        rule.onNodeWithText(text(R.string.split_banner_finding, 40)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_banner_open)).performClick()
        assertEquals(1, opened)
        assertEquals(0, rule.onAllNodesWithContentDescription(text(R.string.split_banner_dismiss)).fetchSemanticsNodes().size)   // a running job is stopped with Cancel, not dismissed
        rule.runOnIdle { shown.value = SplitBannerUi.Writing(4, 12) }
        rule.onNodeWithText(text(R.string.split_banner_writing, 5, 12)).assertIsDisplayed()
        rule.runOnIdle { shown.value = SplitBannerUi.Found(12) }
        rule.onNodeWithText(text(R.string.split_banner_found, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_banner_check)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.split_banner_dismiss)).performClick()
        assertEquals(1, dismissed)
        // A result of the writing opens K6 (with the questions about the original and the songs) and cannot be closed from the banner.
        rule.runOnIdle { shown.value = SplitBannerUi.Done(12) }
        rule.onNodeWithText(text(R.string.split_banner_done, 12)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.split_banner_open)).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithContentDescription(text(R.string.split_banner_dismiss)).fetchSemanticsNodes().size)
        rule.runOnIdle { shown.value = SplitBannerUi.Stopped(5) }
        rule.onNodeWithText(text(R.string.split_banner_stopped, 5)).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithContentDescription(text(R.string.split_banner_dismiss)).fetchSemanticsNodes().size)
    }
}
