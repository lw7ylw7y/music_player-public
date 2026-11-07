package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.playback.QueueRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests for the queue (story B2), on fixtures. */
@RunWith(AndroidJUnit4::class)
class QueueScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private fun row(index: Int, title: String, added: Boolean = false, current: Boolean = false) = QueueRow(
        index = index, mediaId = "k$index", mediaStoreId = index.toLong(), title = title, artist = "Ann",
        artistTag = "Ann", added = added, isCurrent = current,
    )

    private val rows = listOf(
        row(0, "Rain", current = true),
        row(3, "Sunny", added = true),
        row(1, "Cloud"),
        row(2, "Wind"),
    )

    @Test
    fun QueueScreen_ShowsNowPlayingThenAddedAndListedSongsUnderTheirHeadings() {
        rule.setContent { SongPlayerTheme { QueueScreen(rows, {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.queue_now_playing)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.queue_added)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.queue_next_from_list)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Rain, Ann, playing now").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sunny, Ann").assertIsDisplayed()
    }

    @Test
    fun QueueScreen_RemoveRow_UpdatesQueue() {
        var removed: QueueRow? = null
        rule.setContent { SongPlayerTheme { QueueScreen(rows, {}, {}, { removed = it }) } }
        rule.onNodeWithContentDescription("More options for Cloud").performClick()
        rule.onNodeWithText(text(R.string.remove_from_queue)).performClick()
        assertEquals(1, removed?.index)
        assertEquals("Cloud", removed?.title)
    }

    @Test
    fun QueueScreen_TapRow_JumpsToIt() {
        var jumped: QueueRow? = null
        rule.setContent { SongPlayerTheme { QueueScreen(rows, {}, { jumped = it }, {}) } }
        rule.onNodeWithContentDescription("Wind, Ann").performClick()
        assertEquals(2, jumped?.index)
    }

    @Test
    fun QueueScreen_FailedSong_ShowsCantPlay() {
        rule.setContent { SongPlayerTheme { QueueScreen(rows, {}, {}, {}, failedKeys = setOf("k3")) } }
        rule.onNodeWithText(text(R.string.cant_play), useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Sunny, Ann, can't play").assertIsDisplayed()
    }

    @Test
    fun QueueScreen_Empty_SaysTheQueueIsEmpty() {
        rule.setContent { SongPlayerTheme { QueueScreen(emptyList(), {}, {}, {}) } }
        rule.onNodeWithText(text(R.string.queue_empty)).assertIsDisplayed()
    }

    @Test
    fun QueueScreen_BackArrow_Closes() {
        var closed = 0
        rule.setContent { SongPlayerTheme { QueueScreen(rows, { closed++ }, {}, {}) } }
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun QueueScreen_SwipeRow_RemovesIt() {
        var removed: QueueRow? = null
        rule.setContent { SongPlayerTheme { QueueScreen(rows, {}, {}, { removed = it }) } }
        rule.onNodeWithContentDescription("Cloud, Ann").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("Cloud", removed?.title)
    }
}
