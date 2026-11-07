package app.songplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.details.Candidate
import app.songplayer.core.details.ReleaseGroup
import app.songplayer.core.details.ReviewCounts
import app.songplayer.core.details.ReviewItem
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of Review and Not found on fixtures (Stories H9). The rules of a review are tested on the JVM (`ReviewTest`). */
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val under = song("001 Rain.mp3", title = null, artist = null, album = null, durationMs = 228_000)
    private val candidates = listOf(
        Candidate("Rain", listOf("Ann"), 228, 0.94, listOf(ReleaseGroup("Weather", "Album"))),
        Candidate("Rain (live)", listOf("Ann", "Bob"), 230, 0.80, emptyList()),
    )
    private fun reviewing(selected: Int = 0) = ReviewUi.Reviewing(1, 12, ReviewItem(under, candidates), selected)

    private fun show(ui: ReviewUi, playing: Boolean = false, onPlay: (Song) -> Unit = {}, onSelect: (Int) -> Unit = {},
                     onUse: () -> Unit = {}, onNone: () -> Unit = {}, onSkip: () -> Unit = {}, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SongPlayerTheme { ReviewScreen(ui, playing, onPlay, {}, onSelect, onUse, onNone, onSkip, {}) }
            }
        }
    }

    @Test
    fun Review_ShowsThePositionTheSongAndTheBestMatchSelected() {
        show(reviewing())
        rule.onNodeWithText("Review · 2 of 12").assertIsDisplayed()
        rule.onNodeWithText("001 Rain.mp3").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.review_which)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Rain, Ann, Weather").assertIsSelected()
    }

    @Test
    fun Review_ShowsTheSongsCurrentDetails() {
        val tagged = song("x.mp3", title = "Old", artist = "Old artist", album = "Old album")
        show(ReviewUi.Reviewing(0, 1, ReviewItem(tagged, candidates), 0))
        rule.onNodeWithText("Now: Old · Old artist · Old album").assertIsDisplayed()
    }

    @Test
    fun Review_Buttons_CallTheirActions_AndAChoiceSelects() {
        val calls = mutableListOf<String>()
        show(reviewing(), onSelect = { calls += "select$it" }, onUse = { calls += "use" }, onNone = { calls += "none" }, onSkip = { calls += "skip" })
        rule.onNodeWithContentDescription("Rain (live), Ann, Bob, Album unknown").performClick()
        rule.onNodeWithText(text(R.string.review_use)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.review_none)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.review_skip)).performScrollTo().performClick()
        assertEquals(listOf("select1", "use", "none", "skip"), calls)
    }

    @Test
    fun Review_Play_PlaysTheSongUnderReview() {
        var played: Song? = null
        show(reviewing(), onPlay = { played = it })
        rule.onNodeWithText(text(R.string.review_play)).performClick()
        assertEquals(under, played)
    }

    @Test
    fun Review_Finished_ShowsTheCounts() {
        show(ReviewUi.Finished(ReviewCounts(filledIn = 9, rejected = 2, skipped = 1)))
        rule.onNodeWithText(text(R.string.review_done_title)).assertIsDisplayed()
        rule.onNodeWithText("12 songs decided: 9 filled in, 2 rejected, 1 skipped.").assertIsDisplayed()
    }

    @Test
    fun Review_LargestFont_ControlsStayVisible() {
        show(reviewing(), fontScale = 2f)
        rule.onNodeWithText(text(R.string.review_use)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.review_none)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.review_skip)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun NotFound_Edit_OpensTheEditor() {
        var edited: Song? = null
        rule.setContent { SongPlayerTheme { NotFoundScreen(listOf(under), onEdit = { edited = it }, onClose = {}) } }
        rule.onNodeWithText("001 Rain.mp3").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.notfound_edit)).performClick()
        assertEquals(under, edited)
    }
}
