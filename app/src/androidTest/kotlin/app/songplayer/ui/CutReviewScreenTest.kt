package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.split.RunCut
import app.songplayer.core.split.RunPhase
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.SourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 tests of "Check a cut" (Stories X8), on fixtures. */
@RunWith(AndroidJUnit4::class)
class CutReviewScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private fun record(vararg cuts: RunCut) = RunRecord(
        "r", SourceIdentity(1, 1, 1, "h"), "album.mp3", cuts.toList(), "Music/", "external_primary", RunPhase.Reviewing, emptyList(), 1,
    )

    private val middle = record(RunCut(200.0, 2.0, true, false), RunCut(510.0, 1.5, false, false), RunCut(800.0, 1.0, false, false))

    private class Calls {
        val moved = mutableListOf<Double>()
        val to = mutableListOf<Double>()
        var used = 0
        var removed = 0
        var next = 0
        var back = 0
        var paused = 0
        val played = mutableListOf<LongRange>()
    }

    private fun show(record: RunRecord, index: Int, calls: Calls, playing: Boolean = false) {
        rule.setContent {
            SongPlayerTheme {
                CutReviewScreen(
                    record, 1000.0, index, playing, { calls.played += it }, { calls.paused++ }, { calls.moved += it }, { calls.to += it },
                    { calls.used++ }, { calls.removed++ }, { calls.next++ }, { calls.back++ },
                )
            }
        }
    }

    @Test
    fun CutReview_Actions_ChangeThePlan() {
        val calls = Calls()
        show(middle, 1, calls)
        rule.onNodeWithText(context.getString(R.string.cut_title, 2, 3)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.cut_between, 2, 3)).assertIsDisplayed()
        rule.onNodeWithText("8:30").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.cut_earlier)).performClick()
        rule.onNodeWithText(text(R.string.cut_later)).performClick()
        assertEquals(listOf(-0.5, 0.5), calls.moved)
        rule.onNodeWithText(text(R.string.cut_use)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.cut_remove)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.cut_next)).performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1), listOf(calls.used, calls.removed, calls.next))
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals(1, calls.back)
    }

    @Test
    fun CutReview_AtTheLimit_ButtonsAreOffAndSpoken() {
        // A cut 5 s after the one before it cannot go earlier; one 5 s before the end cannot go later.
        val calls = Calls()
        show(record(RunCut(200.0, 2.0, true, false), RunCut(205.0, 1.0, false, false)), 1, calls)
        rule.onNodeWithContentDescription(text(R.string.cut_closest)).assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.cut_later)).assertIsEnabled()
        assertEquals(emptyList<Double>(), calls.moved)
    }

    @Test
    fun CutReview_Play_ClipsTheOriginal() {
        val calls = Calls()
        show(middle, 1, calls)
        rule.onNodeWithText(text(R.string.cut_play)).performClick()
        assertEquals(listOf(500_000L..520_000L), calls.played)             // 10 s either side of 8:30
        rule.onNodeWithText(text(R.string.cut_play_note)).assertIsDisplayed()
    }

    @Test
    fun CutReview_Playing_OffersPause() {
        val calls = Calls()
        show(middle, 1, calls, playing = true)
        rule.onNodeWithText(text(R.string.cut_pause)).performClick()
        assertEquals(1, calls.paused)
        assertEquals(emptyList<LongRange>(), calls.played)
    }
}
