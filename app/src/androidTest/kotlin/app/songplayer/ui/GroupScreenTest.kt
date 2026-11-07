package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of the album and artist screens on fixtures (Stories A7). */
@RunWith(AndroidJUnit4::class)
class GroupScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val songs = listOf(
        song("a.mp3", id = 1, title = "Alpha", artist = "Ann", album = "Weather", durationMs = 120_000),
        song("b.mp3", id = 2, title = "Beta", artist = "Ann", album = "Weather", durationMs = 180_000),
    )

    private fun show(onPlay: (List<Song>, Int) -> Unit = { _, _ -> }, onShuffle: (List<Song>, Int) -> Unit = { _, _ -> }, onClose: () -> Unit = {}, subtitle: String = "Ann") {
        rule.setContent { SongPlayerTheme { GroupScreen("Weather", subtitle, songs, onClose, onPlay, onShuffle) } }
    }

    @Test
    fun GroupScreen_ShowsTheTitleCountLengthAndSongs() {
        show()
        rule.onNodeWithText("Weather").assertIsDisplayed()
        rule.onNodeWithText("Ann · 2 songs · 5 min").assertIsDisplayed()
        rule.onNodeWithText("Alpha", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Beta", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun GroupScreen_ArtistHeader_HasNoEmptySubtitle() {
        show(subtitle = "")
        rule.onNodeWithText("2 songs · 5 min").assertIsDisplayed()
    }

    @Test
    fun GroupScreen_PlayShuffleAndASong_StartTheQueue() {
        var played: Pair<List<Song>, Int>? = null
        var shuffled: Pair<List<Song>, Int>? = null
        show(onPlay = { q, i -> played = q to i }, onShuffle = { q, i -> shuffled = q to i })
        rule.onNodeWithText(text(R.string.play)).performClick()
        assertEquals(songs to 0, played)
        rule.onNodeWithText("Beta", useUnmergedTree = true).performClick() // the row speaks through one description, so its texts are in the unmerged tree
        assertEquals(songs to 1, played)
        rule.onNodeWithText(text(R.string.shuffle_button)).performClick()
        assertTrue(shuffled!!.first == songs && shuffled!!.second in songs.indices)
    }

    @Test
    fun GroupScreen_Back_Closes() {
        var closed = 0
        show(onClose = { closed++ })
        rule.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals(1, closed)
    }
}
