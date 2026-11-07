package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.song
import app.songplayer.playback.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests for the song rows, the mini player and Now Playing (story B1), on fixtures. */
@RunWith(AndroidJUnit4::class)
class PlayerScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private fun now(playing: Boolean) = NowPlaying(
        mediaId = "k", mediaStoreId = 1, title = "Rain", artist = "Ann", artistTag = "Ann", album = "Weather",
        durationMs = 248_000, isPlaying = playing,
    )

    @Test
    fun SongRow_Tap_PlaysAndIsAnnouncedWithThePlayAction() {
        var taps = 0
        rule.setContent { SongPlayerTheme { SongRow(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather"), onClick = { taps++ }) } }
        rule.onNodeWithContentDescription("Rain, Ann, Weather").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun SongRow_Playing_IsSpokenAsPlaying() {
        rule.setContent { SongPlayerTheme { SongRow(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather"), playing = true) } }
        rule.onNodeWithContentDescription("Rain, Ann, Weather, playing").assertIsDisplayed()
    }

    @Test
    fun MiniPlayer_Playing_OffersPauseAndNextAndOpens() {
        var toggled = 0
        var next = 0
        var opened = 0
        rule.setContent { SongPlayerTheme { MiniPlayer(now(true), onToggle = { toggled++ }, onNext = { next++ }, onOpen = { opened++ }) } }
        rule.onNodeWithContentDescription("Now playing: Rain by Ann").performClick()
        rule.onNodeWithContentDescription(text(R.string.pause)).performClick()
        rule.onNodeWithContentDescription(text(R.string.next)).performClick()
        assertEquals(listOf(1, 1, 1), listOf(opened, toggled, next))
    }

    @Test
    fun MiniPlayer_Paused_OffersPlay() {
        rule.setContent { SongPlayerTheme { MiniPlayer(now(false), {}, {}, {}) } }
        rule.onNodeWithContentDescription(text(R.string.play)).assertIsDisplayed()
    }

    @Test
    fun NowPlaying_ShowsTitleTimesAndLabelledControls() {
        var previous = 0
        rule.setContent {
            SongPlayerTheme {
                NowPlayingScreen(now(false), positionMs = { 94_000 }, onClose = {}, onToggle = {}, onPrevious = { previous++ }, onNext = {}, onSeek = {})
            }
        }
        rule.onNodeWithText("Rain").assertIsDisplayed()
        // The printed times are hidden from TalkBack (the seek bar speaks them), so they are only in the unmerged tree.
        rule.onNodeWithText("1:34", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("4:08", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(text(R.string.close_now_playing)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.play)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.next)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.previous)).performClick()
        assertEquals(1, previous)
    }

    @Test
    fun NowPlaying_ShuffleAndRepeat_AreSpokenInWordsAndWorkByTap() {
        var shuffles = 0
        var repeats = 0
        var queues = 0
        val from = now(true).copy(shuffleOn = true, repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE, playingFrom = app.songplayer.playback.PlayingFrom.Search)
        rule.setContent {
            SongPlayerTheme {
                NowPlayingScreen(
                    from, positionMs = { 0 }, onClose = {}, onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {},
                    onShuffle = { shuffles++ }, onRepeat = { repeats++ }, onQueue = { queues++ },
                )
            }
        }
        rule.onNodeWithContentDescription(text(R.string.shuffle_on)).performClick()
        rule.onNodeWithContentDescription(text(R.string.repeat_one)).performClick()
        rule.onNodeWithContentDescription(text(R.string.queue)).performClick()
        rule.onNodeWithText("Playing from: Search results").assertIsDisplayed()
        assertEquals(listOf(1, 1, 1), listOf(shuffles, repeats, queues))
    }

    @Test
    fun NowPlaying_ShuffleOffRepeatOff_AreSpokenAsOffWithNoPrintedWords() {
        rule.setContent {
            SongPlayerTheme { NowPlayingScreen(now(false), positionMs = { 0 }, onClose = {}, onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}) }
        }
        rule.onNodeWithContentDescription(text(R.string.shuffle_off)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.repeat_off)).assertIsDisplayed()
        // No printed words under the icons.
        rule.onNodeWithText(text(R.string.shuffle_off)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.repeat_off)).assertDoesNotExist()
    }

    @Test
    fun SongRow_Menu_OffersPlayNextAndAddToQueue() {
        var next = 0
        var added = 0
        rule.setContent {
            SongPlayerTheme {
                SongRow(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather"), onPlayNext = { next++ }, onAddToQueue = { added++ })
            }
        }
        rule.onNodeWithContentDescription("More options for Rain").performClick()
        rule.onNodeWithText(text(R.string.menu_play_next)).performClick()
        rule.onNodeWithContentDescription("More options for Rain").performClick()
        rule.onNodeWithText(text(R.string.menu_add_to_queue)).performClick()
        assertEquals(listOf(1, 1), listOf(next, added))
    }

    @Test
    fun SongRow_PlayFailure_ShowsIconAndWord() {
        rule.setContent {
            SongPlayerTheme { SongRow(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather"), cantPlay = true) }
        }
        // The row is one node for a screen reader, so the drawn word is looked up in the unmerged tree.
        rule.onNodeWithText(text(R.string.cant_play), useUnmergedTree = true).assertExists()
        // The word replaces the artist line and the row is spoken with it.
        rule.onNodeWithContentDescription("Rain, Ann, Weather, can't play").assertIsDisplayed()
        rule.onNodeWithText("Ann · Weather", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun SongRow_WithoutMenuActions_HasNoMenuButton() {
        rule.setContent { SongPlayerTheme { SongRow(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather")) } }
        rule.onNodeWithContentDescription("More options for Rain").assertDoesNotExist()
    }
}
