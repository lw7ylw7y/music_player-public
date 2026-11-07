package app.songplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortOrder
import app.songplayer.library.LibraryTab
import java.time.ZoneId
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3: the Playlists tab inside the Library screen. */
@RunWith(AndroidJUnit4::class)
class PlaylistTabsTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val keyer = FakeKeyer()
    private val songs = listOf(song("a.mp3", id = 1, title = "Alpha", artist = "Ann", album = "Alb"))

    @Test
    fun PlaylistScreen_CorruptDb_ShowsErrorAndOtherTabsWork() {
        rule.setContent {
            var tab by remember { mutableStateOf(LibraryTab.Playlists) }
            val ui = LibraryUi(
                loaded = true,
                tab = tab,
                lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
                generation = 1,
            )
            SongPlayerTheme {
                LibraryScreen(
                    ui, onTab = { tab = it }, onSort = {}, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { 0 },
                    playlists = PlaylistActions(PlaylistsState.Unavailable(damaged = true), {}, { _, _, _ -> }, { _, _, _ -> }, {}),
                )
            }
        }
        rule.onNodeWithText(text(R.string.playlists_damaged_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_songs)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.song_row_description, "Alpha", "Ann", "Alb")).assertIsDisplayed()
    }

    @Test
    fun PlaylistTab_FourTabsAreShownInOrder() {
        rule.setContent {
            val ui = LibraryUi(
                loaded = true,
                lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
                generation = 1,
            )
            SongPlayerTheme {
                LibraryScreen(ui, onTab = {}, onSort = {}, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { 0 })
            }
        }
        listOf(R.string.tab_songs, R.string.tab_albums, R.string.tab_artists, R.string.tab_playlists).forEach {
            rule.onNodeWithText(text(it)).assertIsDisplayed()
        }
    }

    @Test
    fun PlaylistTab_ShowsHowManyPlaylistsThereAreAndNoSortChip() {
        rule.setContent {
            val ui = LibraryUi(
                loaded = true,
                tab = LibraryTab.Playlists,
                lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
                generation = 1,
            )
            val summaries = listOf(
                app.songplayer.core.playlist.PlaylistSummary(app.songplayer.core.playlist.Playlist(1, "A", 1), 3),
                app.songplayer.core.playlist.PlaylistSummary(app.songplayer.core.playlist.Playlist(2, "B", 2), 0),
            )
            SongPlayerTheme {
                LibraryScreen(
                    ui, onTab = {}, onSort = {}, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { 0 },
                    playlists = PlaylistActions(PlaylistsState.Ready(summaries), {}, { _, _, _ -> }, { _, _, _ -> }, {}),
                )
            }
        }
        rule.onNodeWithText("2 playlists").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.sort_label, context.getString(R.string.sort_title))).assertDoesNotExist()
    }
}
