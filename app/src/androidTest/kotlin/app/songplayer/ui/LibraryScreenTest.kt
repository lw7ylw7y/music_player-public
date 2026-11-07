package app.songplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortOrder
import app.songplayer.library.LibraryTab
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests on fixtures: no permission, no media index, no database. */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private val keyer = FakeKeyer()

    private fun ui(
        songs: List<Song>,
        tab: LibraryTab = LibraryTab.Songs,
        sort: SortOrder = SortOrder.Title,
        reversed: Boolean = false,
    ) = LibraryUi(
        loaded = true,
        tab = tab,
        sort = sort,
        reversed = reversed,
        lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, sort, keyer, ZoneId.of("UTC"), Locale.ENGLISH, reversed),
        generation = 1,
    )

    private fun show(
        ui: LibraryUi,
        newSongs: Int = 0,
        onTab: (LibraryTab) -> Unit = {},
        onSort: (SortOrder) -> Unit = {},
        onReverse: () -> Unit = {},
    ) = rule.setContent {
        SongPlayerTheme {
            LibraryScreen(ui, onTab, onSort, onRefresh = {}, onSearch = {}, onDismissFailure = {}, takeNewSongs = { newSongs }, onReverse = onReverse)
        }
    }

    @Test
    fun LibraryScreen_PermissionRefused_ShowsExplanationAndBothButtons() {
        var tried = 0
        var settings = 0
        rule.setContent { SongPlayerTheme { AccessRefusedScreen({ tried++ }, { settings++ }) } }
        rule.onNodeWithText(text(R.string.access_refused_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.access_refused_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.access_try_again)).performClick()
        rule.onNodeWithText(text(R.string.access_open_settings)).performClick()
        assertEquals(1, tried)
        assertEquals(1, settings)
    }

    @Test
    fun SongsList_MissingTags_ShowsFallbacks() {
        val bare = song("bare name.mp3", title = null, artist = null, album = null)
        show(ui(listOf(bare)))
        // A row is one node for TalkBack (its children are cleared), so what is spoken is checked on the row,
        // and what is drawn is looked up in the unmerged tree.
        rule.onNodeWithContentDescription("bare name.mp3, Unknown artist, Unknown album").assertIsDisplayed()
        rule.onNodeWithText("bare name.mp3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Unknown artist · Unknown album", useUnmergedTree = true).assertExists()
    }

    @Test
    fun Albums_NoCover_ShowsFirstCharacterOfArtist() {
        val songs = listOf(song("a.mp3", artist = "周杰伦", album = "范特西"), song("b.mp3", artist = "周杰伦", album = "范特西"))
        show(ui(songs, tab = LibraryTab.Albums))
        rule.onNodeWithContentDescription("范特西, 周杰伦, 2 songs").assertIsDisplayed()
        rule.onNodeWithText("范特西", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2 songs", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("周", useUnmergedTree = true).assertExists()
    }

    @Test
    fun Snackbar_NewSongs_ShowOpensRecentlyAdded() {
        var tab: LibraryTab? = null
        var sort: SortOrder? = null
        rule.mainClock.autoAdvance = false // the 8-second timer must not run out while the test waits
        show(ui(listOf(song("a.mp3"))), newSongs = 3, onTab = { tab = it }, onSort = { sort = it })
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("3 new songs found").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.new_songs_show)).performClick()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(LibraryTab.Songs, tab)
        assertEquals(SortOrder.RecentlyAdded, sort)
    }

    @Test
    fun Snackbar_NewSongs_ShowSelectsNewestFirst() {
        // Recently added is already chosen but reversed (oldest first): Show must put it back to newest first.
        var sort: SortOrder? = null
        rule.mainClock.autoAdvance = false
        show(ui(listOf(song("a.mp3")), sort = SortOrder.RecentlyAdded, reversed = true), newSongs = 3, onSort = { sort = it })
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText(text(R.string.new_songs_show)).performClick()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(SortOrder.RecentlyAdded, sort)
    }

    @Test
    fun SortDirection_Button_ReversesTheList() {
        val songs = listOf(song("a.mp3", id = 1, title = "Alpha"), song("b.mp3", id = 2, title = "Bravo"))
        var taps = 0
        show(ui(songs), onReverse = { taps++ })
        rule.onNodeWithContentDescription(text(R.string.sort_a_to_z)).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun SortDirection_ReversedList_SaysZToA_AndStartsWithTheLastTitle() {
        val songs = listOf(song("a.mp3", id = 1, title = "Alpha"), song("b.mp3", id = 2, title = "Bravo"))
        show(ui(songs, reversed = true))
        rule.onNodeWithContentDescription(text(R.string.sort_z_to_a)).assertIsDisplayed()
        // The first row is Bravo: it is drawn above Alpha.
        val bravo = rule.onNodeWithContentDescription("Bravo, artist, album").getUnclippedBoundsInRoot()
        val alpha = rule.onNodeWithContentDescription("Alpha, artist, album").getUnclippedBoundsInRoot()
        assertTrue("Bravo is above Alpha", bravo.top < alpha.top)
    }

    @Test
    fun SortDirection_ArtistsTab_HasNoButton() {
        show(ui(listOf(song("a.mp3")), tab = LibraryTab.Artists))
        rule.onNodeWithContentDescription(text(R.string.sort_a_to_z)).assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.sort_z_to_a)).assertDoesNotExist()
    }

    @Test
    fun SortDirection_Button_IsSpokenInWords() {
        show(ui(listOf(song("a.mp3")), sort = SortOrder.RecentlyAdded))
        rule.onNodeWithContentDescription("Sorted newest first. Tap to reverse.").assertIsDisplayed()
    }

    @Test
    fun SortDirection_LargestFont_ButtonStaysVisible() {
        val density = context.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                SongPlayerTheme { LibraryScreen(ui(listOf(song("a.mp3"))), {}, {}, {}, {}, {}, { 0 }) }
            }
        }
        // Material gives a 40 dp icon button a 48 dp touch area; the semantics node itself is the 40 dp icon, so the touch bounds count.
        rule.onNodeWithContentDescription(text(R.string.sort_a_to_z)).assertIsDisplayed()
        val touch = rule.onNodeWithContentDescription(text(R.string.sort_a_to_z)).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("touch area is ${touch.width / density} x ${touch.height / density} dp", touch.width / density >= 47.5f && touch.height / density >= 47.5f)
        rule.onNodeWithText("Sort: Title").assertIsDisplayed()
        rule.onNodeWithText("1 song").assertIsDisplayed()
    }

    @Test
    fun Snackbar_OneNewSong_UsesSingular() {
        rule.mainClock.autoAdvance = false
        show(ui(listOf(song("a.mp3"))), newSongs = 1)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("1 new song found").assertIsDisplayed()
    }

    @Test
    fun SortMenu_ChoosingAnOrder_ReportsIt() {
        var sort: SortOrder? = null
        show(ui(listOf(song("a.mp3"))), onSort = { sort = it })
        rule.onNodeWithText("Sort: Title").performClick()
        rule.onNodeWithText(text(R.string.sort_artist)).performClick()
        assertEquals(SortOrder.Artist, sort)
    }

    @Test
    fun LibraryScreen_LargestFont_NoClippedControls() {
        val density = context.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                SongPlayerTheme {
                    LibraryScreen(ui(listOf(song("a.mp3"))), {}, {}, {}, {}, {}, { 0 })
                }
            }
        }
        rule.onNodeWithText(text(R.string.tab_songs)).assertIsDisplayed()
        rule.onNodeWithText("Sort: Title").assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.more_options)).assertIsDisplayed()
        rule.onNodeWithContentDescription("title of a.mp3, artist, album").assertIsDisplayed()
    }

    @Test
    fun Accessibility_AllControls_HaveLabels() {
        show(ui(listOf(song("a.mp3", title = "Rain", artist = "Ann", album = "Weather"))))
        rule.onNodeWithContentDescription(text(R.string.more_options)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.search_open)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_songs)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_albums)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.tab_artists)).assertIsDisplayed()
        rule.onNodeWithText("Sort: Title").assertIsDisplayed()
        rule.onNodeWithContentDescription("Rain, Ann, Weather").assertIsDisplayed()
    }

    @Test
    fun LibraryScreen_NothingFoundAfterASync_ShowsTheEmptyState() {
        show(ui(emptyList()))
        rule.onNodeWithText(text(R.string.empty_title)).assertIsDisplayed()
    }

    @Test
    fun LibraryScreen_ArtistsTab_HasNoSortChip() {
        show(ui(listOf(song("a.mp3")), tab = LibraryTab.Artists))
        rule.onNodeWithText("1 artist").assertIsDisplayed()
        rule.onAllNodesWithText("Sort: Title").assertCountEquals(0)
    }

    @Test
    fun SongMenu_PlayNext_SaysWillPlayNext() {
        var played: String? = null
        rule.setContent {
            SongPlayerTheme {
                LibraryScreen(
                    ui(listOf(song("a.mp3", title = "Rain"))), {}, {}, {}, {}, {}, { 0 },
                    onPlayNext = { played = it.displayTitle; true }, onAddToQueue = { true },
                )
            }
        }
        rule.onNodeWithContentDescription("More options for Rain").performClick()
        rule.onNodeWithText(text(R.string.menu_play_next)).performClick()
        rule.onNodeWithText(text(R.string.snackbar_will_play_next)).assertIsDisplayed()
        assertEquals("Rain", played)
    }

    @Test
    fun SongMenu_AddToQueue_SaysAddedToQueue() {
        var added: String? = null
        rule.setContent {
            SongPlayerTheme {
                LibraryScreen(
                    ui(listOf(song("a.mp3", title = "Rain"))), {}, {}, {}, {}, {}, { 0 },
                    onPlayNext = { true }, onAddToQueue = { added = it.displayTitle; true },
                )
            }
        }
        rule.onNodeWithContentDescription("More options for Rain").performClick()
        rule.onNodeWithText(text(R.string.menu_add_to_queue)).performClick()
        rule.onNodeWithText(text(R.string.snackbar_added_to_queue)).assertIsDisplayed()
        assertEquals("Rain", added)
    }
}
