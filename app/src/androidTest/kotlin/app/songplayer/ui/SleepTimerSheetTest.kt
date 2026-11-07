package app.songplayer.ui

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.playback.NowPlaying
import app.songplayer.playback.SleepUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of the sleep timer on Now Playing (story D2), on fixtures. */
@RunWith(AndroidJUnit4::class)
class SleepTimerSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private val now = NowPlaying(
        mediaId = "k", mediaStoreId = 1, title = "Rain", artist = "Ann", artistTag = "Ann", album = "Weather",
        durationMs = 248_000, isPlaying = true,
    )

    private fun show(
        sleep: SleepUi,
        started: MutableList<Int> = mutableListOf(),
        cancelled: MutableList<Unit> = mutableListOf(),
        endOfSong: MutableList<Unit> = mutableListOf(),
    ) = rule.setContent {
        SongPlayerTheme {
            NowPlayingScreen(
                now = now,
                positionMs = { 0L },
                onClose = {},
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSeek = {},
                sleep = sleep,
                sleepChoices = listOf(15, 30, 45, 60),
                onSleepStart = { started += it },
                onSleepEndOfSong = { endOfSong += Unit },
                onSleepCancel = { cancelled += Unit },
            )
        }
    }

    @Test
    fun NowPlaying_NoTimer_ShowsNoLine_AndTheSheetOffersTheChoicesWithNoExplanation() {
        val started = mutableListOf<Int>()
        show(SleepUi.None, started)
        rule.onNode(hasText("Sleep in", substring = true)).assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.sleep_timer)).performClick()
        rule.onNodeWithText("15 minutes").assertIsDisplayed()
        rule.onNodeWithText("30 minutes").assertIsDisplayed()
        rule.onNodeWithText("45 minutes").assertIsDisplayed()
        rule.onNodeWithText("60 minutes").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.sleep_end_of_song)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.sleep_cancel)).assertDoesNotExist()
        rule.onNode(hasText("finishes", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("45 minutes").performClick()
        assertEquals(listOf(45), started)
        rule.onNodeWithText("45 minutes").assertDoesNotExist()
    }

    @Test
    fun NowPlaying_EndOfSong_Chosen_IsReported() {
        val endOfSong = mutableListOf<Unit>()
        show(SleepUi.None, endOfSong = endOfSong)
        rule.onNodeWithContentDescription(text(R.string.sleep_timer)).performClick()
        rule.onNodeWithText(text(R.string.sleep_end_of_song)).performClick()
        assertEquals(1, endOfSong.size)
    }

    @Test
    fun NowPlaying_TimerSheet_ShowsRemainingAndCancel() {
        val cancelled = mutableListOf<Unit>()
        show(SleepUi(endsAtMs = SystemClock.elapsedRealtime() + 24 * 60_000L + 10_000L), cancelled = cancelled)
        // The line under the title, counted down from the end time, and nothing else about it.
        rule.onNode(hasText("Sleep in 24:", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("then this song ends", substring = true)).assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.sleep_timer_on)).performClick()
        rule.onNodeWithText(text(R.string.sleep_cancel)).performClick()
        assertEquals(1, cancelled.size)
    }

    @Test
    fun NowPlaying_EndOfSong_ShowsItsOwnLine() {
        show(SleepUi(endsAtMs = null, endOfSong = true))
        rule.onNodeWithText(text(R.string.sleep_end_of_song_line)).assertIsDisplayed()
    }

    @Test
    fun NowPlaying_QueueAndSleepAreIcons_WithSpokenNamesAndNoPrintedWords() {
        show(SleepUi.None)
        rule.onNodeWithContentDescription(text(R.string.queue)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.sleep_timer)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.queue)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.sleep_timer)).assertDoesNotExist()
    }

    @Test
    fun NowPlaying_SleepIcon_SaysWhenATimerIsOn() {
        show(SleepUi(endOfSong = true))
        rule.onNodeWithContentDescription(text(R.string.sleep_timer_on)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.sleep_timer)).assertDoesNotExist()
    }
}
