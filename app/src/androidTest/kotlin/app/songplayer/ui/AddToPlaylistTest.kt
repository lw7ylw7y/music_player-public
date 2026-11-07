package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.library.song as fixtureSong
import app.songplayer.core.playlist.Playlist
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortOrder
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests for "Add to playlist" and selecting several songs (story C2), on fixtures. */
@RunWith(AndroidJUnit4::class)
class AddToPlaylistTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val keyer = FakeKeyer()

    private fun song(n: Int) = fixtureSong("$n.mp3", id = n.toLong(), title = "Song $n", artist = "Ann", album = "Alb")
    private val songs = (1..4).map(::song)
    private fun row(n: Int) = "Song $n, Ann, Alb"
    private val driving = PlaylistSummary(Playlist(7, "Driving", 1), 12)

    private class Calls {
        var added: Triple<Long, List<Song>, Int>? = null
        var created: Pair<String, List<Song>>? = null
        var addCount = 0
    }

    private fun show(
        calls: Calls,
        result: AddResult = AddResult.Done(added = 1, alreadyIn = 0),
        state: PlaylistsState = PlaylistsState.Ready(listOf(driving)),
        created: CreateResult = CreateResult.Created(Playlist(9, "Quiet", 3), added = 1),
    ) {
        val ui = LibraryUi(
            loaded = true,
            lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
            generation = 1,
        )
        rule.setContent {
            SongPlayerTheme {
                LibraryScreen(
                    ui, onTab = {}, onSort = {}, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { 0 },
                    playlists = PlaylistActions(
                        state = state,
                        onOpen = {},
                        onAdd = { id, list, done ->
                            calls.addCount++
                            calls.added = Triple(id, list, calls.addCount)
                            done(result)
                        },
                        onCreate = { name, list, done ->
                            calls.created = name to list
                            done(created)
                        },
                        onRetry = {},
                    ),
                )
            }
        }
    }

    private fun openSongMenuAndAdd(n: Int) {
        rule.onNodeWithContentDescription("More options for Song $n").performClick()
        rule.onNodeWithText(text(R.string.menu_add_to_playlist)).performClick()
    }

    @Test
    fun SongMenu_AddToPlaylist_ShowsPlaylistsWithCounts_AndAddsTheSong() {
        val calls = Calls()
        show(calls)
        openSongMenuAndAdd(2)
        rule.onNodeWithText(text(R.string.sheet_add_to_playlist_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.playlist_new)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Driving, 12 songs").performClick()
        assertEquals(7L, calls.added?.first)
        assertEquals(listOf(song(2)), calls.added?.second)
        rule.onNodeWithText("Added to Driving").assertIsDisplayed()
    }

    @Test
    fun SongMenu_AddingASongThatIsAlreadyIn_SaysAlreadyIn() {
        val calls = Calls()
        show(calls, result = AddResult.Done(added = 0, alreadyIn = 1))
        openSongMenuAndAdd(1)
        rule.onNodeWithContentDescription("Driving, 12 songs").performClick()
        rule.onNodeWithText("Already in Driving").assertIsDisplayed()
    }

    @Test
    fun SongMenu_NewPlaylist_AsksForAName_ThenCreatesItWithTheSong() {
        val calls = Calls()
        show(calls)
        openSongMenuAndAdd(3)
        rule.onNodeWithText(text(R.string.playlist_new)).performClick()
        rule.onNodeWithText(text(R.string.dialog_name_field)).performTextInput("Quiet")
        rule.onNodeWithText(text(R.string.dialog_create)).performClick()
        assertEquals("Quiet", calls.created?.first)
        assertEquals(listOf(song(3)), calls.created?.second)
        rule.onNodeWithText("Added to Quiet").assertIsDisplayed()
    }

    @Test
    fun SongMenu_SaveFails_SaysSo() {
        val calls = Calls()
        show(calls, result = AddResult.Failed)
        openSongMenuAndAdd(1)
        rule.onNodeWithContentDescription("Driving, 12 songs").performClick()
        rule.onNodeWithText(text(R.string.playlist_save_failed)).assertIsDisplayed()
    }

    @Test
    fun SongMenu_PlaylistsCannotBeRead_SaysSoInTheSheet() {
        show(Calls(), state = PlaylistsState.Unavailable(damaged = true))
        openSongMenuAndAdd(1)
        rule.onNodeWithText(text(R.string.playlists_damaged_title)).assertIsDisplayed()
    }

    @Test
    fun Selection_LongPressStartsIt_TapsAddAndDrop_AndAddToPlaylistTakesAllPicked() {
        val calls = Calls()
        show(calls, result = AddResult.Done(added = 2, alreadyIn = 1))
        rule.onNodeWithContentDescription(row(1)).performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("${row(1)}, selected").assertIsDisplayed()
        rule.onNodeWithContentDescription(row(2)).performClick()
        rule.onNodeWithContentDescription(row(3)).performClick()
        rule.onNodeWithContentDescription("${row(3)}, selected").performClick() // dropped again
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.menu_add_to_playlist)).performClick()
        rule.onNodeWithContentDescription("Driving, 12 songs").performClick()
        assertEquals(listOf(song(1), song(2)), calls.added?.second)
        rule.onNodeWithText("Added 2 songs to Driving (1 already in it)").assertIsDisplayed()
        rule.onNodeWithText("2 selected").assertDoesNotExist()
    }

    @Test
    fun Selection_Close_LeavesNothingSelected_AndPlayingResumesOnTap() {
        val calls = Calls()
        show(calls)
        rule.onNodeWithContentDescription(row(1)).performTouchInput { longClick() }
        rule.onNodeWithContentDescription(text(R.string.selection_close)).performClick()
        rule.onNodeWithText("1 selected").assertDoesNotExist()
        rule.onNodeWithContentDescription(row(1)).assertIsDisplayed()
        assertNull(calls.added)
    }
}
