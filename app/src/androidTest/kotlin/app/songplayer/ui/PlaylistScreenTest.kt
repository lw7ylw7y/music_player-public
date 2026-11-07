package app.songplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song as fixtureSong
import app.songplayer.core.playlist.Playlist
import app.songplayer.core.playlist.PlaylistEntry
import app.songplayer.core.playlist.PlaylistItem
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.playlist.RemovedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests for the Playlists tab and the playlist screen (story C2), on fixtures: no database, no player. */
@RunWith(AndroidJUnit4::class)
class PlaylistScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private fun song(n: Int) = fixtureSong("$n.mp3", id = n.toLong(), title = "Song $n", artist = "Ann", album = "Alb")
    private fun entryFor(song: Song, id: Long) = PlaylistEntry(id, song.key, song.fileName, song.sizeBytes, song.durationMs)
    private val driving = Playlist(7, "Driving", 1)

    private fun item(n: Int) = PlaylistItem(entryFor(song(n), n.toLong()), song(n))
    private val gone = PlaylistItem(PlaylistEntry(99, SongKey("external_primary:Music/gone.mp3"), "gone.mp3", 10, 1_000), null)

    // ---- the Playlists tab ------------------------------------------------------------------------------------

    private fun tab(state: PlaylistsState, onOpen: (Long) -> Unit = {}, onNew: () -> Unit = {}, onRetry: () -> Unit = {}) =
        rule.setContent { SongPlayerTheme { PlaylistsTab(state, onOpen, onNew, onRetry) } }

    @Test
    fun PlaylistsTab_Playlists_AreListedAfterTheNewPlaylistRowWithTheirCounts() {
        var opened = -1L
        tab(PlaylistsState.Ready(listOf(PlaylistSummary(driving, 12), PlaylistSummary(Playlist(8, "Quiet", 2), 1))), onOpen = { opened = it })
        rule.onNodeWithText(text(R.string.playlist_new)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Driving, 12 songs").assertIsDisplayed()
        rule.onNodeWithContentDescription("Quiet, 1 song").performClick()
        assertEquals(8L, opened)
    }

    @Test
    fun PlaylistsTab_NoPlaylists_ShowsTheNewRowAndAHint() {
        var made = false
        tab(PlaylistsState.Ready(emptyList()), onNew = { made = true })
        rule.onNodeWithText(text(R.string.playlists_none_hint)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.playlist_new)).performClick()
        assertTrue(made)
    }

    @Test
    fun PlaylistScreen_100Playlists_AllListed() {
        val many = (1..100).map { PlaylistSummary(Playlist(it.toLong(), "List $it", 1), it) }
        tab(PlaylistsState.Ready(many))
        rule.onNodeWithContentDescription("List 1, 1 song").assertExists()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndex(100)
        rule.onNodeWithContentDescription("List 100, 100 songs").assertIsDisplayed()
    }

    @Test
    fun PlaylistsTab_DamagedFile_SaysSoAndOffersNoRetry() {
        tab(PlaylistsState.Unavailable(damaged = true))
        rule.onNodeWithText(text(R.string.playlists_damaged_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.playlist_try_again)).assertDoesNotExist()
    }

    @Test
    fun PlaylistsTab_ReadFailedForAnotherReason_OffersARetry() {
        var retried = false
        tab(PlaylistsState.Unavailable(damaged = false), onRetry = { retried = true })
        rule.onNodeWithText(text(R.string.playlists_read_failed_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.playlist_try_again)).performClick()
        assertTrue(retried)
    }

    @Test
    fun PlaylistNameDialog_CreateIsDisabledUntilThereIsAName_AndGivesTheTypedName() {
        var name: String? = null
        rule.setContent {
            SongPlayerTheme { PlaylistNameDialog("New playlist", "Create", "", onConfirm = { name = it }, onDismiss = {}) }
        }
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.dialog_name_field)).performTextInput("  Driving ")
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertEquals("  Driving ", name)
    }

    // ---- the playlist screen ----------------------------------------------------------------------------------

    private class Calls {
        var played: Pair<List<Song>, Int>? = null
        var shuffled: Pair<List<Song>, Int>? = null
        var removedEntry: Long? = null
        var undone: RemovedEntry? = null
        var renamed: String? = null
        var deleted = false
        var closed = false
        val moves = mutableListOf<Pair<Long, Int>>()
    }

    private fun screen(state: DetailState, calls: Calls = Calls(), failedKeys: Set<String> = emptySet(), saved: Boolean = true) =
        rule.setContent {
            SongPlayerTheme {
                PlaylistScreen(
                    state = state,
                    onClose = { calls.closed = true },
                    onPlay = { q, i -> calls.played = q to i },
                    onShuffle = { q, i -> calls.shuffled = q to i },
                    onPlayNext = { true },
                    onAddToQueue = { true },
                    onRename = { name, done -> calls.renamed = name; done(saved) },
                    onDelete = { done -> calls.deleted = true; done(saved) },
                    onRemove = { id, done ->
                        calls.removedEntry = id
                        done(saved, RemovedEntry(entryFor(song(id.toInt()), id), 0))
                    },
                    onUndo = { removed, done -> calls.undone = removed; done(true) },
                    onMove = { id, to, done -> calls.moves += id to to; done(true) },
                    onRetry = {},
                    failedKeys = failedKeys,
                )
            }
        }

    @Test
    fun PlaylistScreen_ShowsNameCountAndSongsInOrder() {
        screen(DetailState.Ready(driving, listOf(item(1), item(2))))
        rule.onNodeWithText("Driving").assertIsDisplayed()
        rule.onNodeWithContentDescription("Song 1, Ann, Alb").assertIsDisplayed()
        rule.onNodeWithContentDescription("Song 2, Ann, Alb").assertIsDisplayed()
        rule.onNode(hasText("2 songs", substring = true)).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_Play_QueuesThePlayableSongsFromTheTop_AndShuffleStartsInsideThem() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1), gone, item(3))), calls)
        rule.onNodeWithText(text(R.string.play)).performClick()
        assertEquals(listOf(song(1), song(3)), calls.played?.first)
        assertEquals(0, calls.played?.second)
        rule.onNodeWithText(text(R.string.shuffle_button)).performClick()
        assertEquals(listOf(song(1), song(3)), calls.shuffled?.first)
        assertTrue(calls.shuffled!!.second in 0..1)
    }

    @Test
    fun PlaylistScreen_TappingARow_PlaysFromThatSong() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1), gone, item(3))), calls)
        rule.onNodeWithContentDescription("Song 3, Ann, Alb").performClick()
        assertEquals(1, calls.played?.second)
    }

    @Test
    fun PlaylistScreen_MissingSong_ShowsCantPlayAndCanBeRemoved() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1), gone)), calls)
        rule.onNodeWithContentDescription("gone.mp3, can't play").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options for gone.mp3").performClick()
        rule.onNodeWithText(text(R.string.menu_remove_from_playlist)).performClick()
        assertEquals(99L, calls.removedEntry)
    }

    @Test
    fun PlaylistScreen_Remove_OffersUndoWhichRestoresTheSong() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1), item(2))), calls)
        rule.onNodeWithContentDescription("More options for Song 2").performClick()
        rule.onNodeWithText(text(R.string.menu_remove_from_playlist)).performClick()
        rule.onNodeWithText("Removed \"Song 2\"").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.snackbar_undo)).performClick()
        assertEquals(2L, calls.undone?.entry?.id)
    }

    @Test
    fun PlaylistScreen_RemoveThatCannotBeSaved_SaysSoAndOffersNoUndo() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1))), calls, saved = false)
        rule.onNodeWithContentDescription("More options for Song 1").performClick()
        rule.onNodeWithText(text(R.string.menu_remove_from_playlist)).performClick()
        rule.onNodeWithText(text(R.string.playlist_save_failed)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.snackbar_undo)).assertDoesNotExist()
    }

    @Test
    fun PlaylistScreen_Delete_ConfirmsFirst_CancelChangesNothing() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1))), calls)
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_delete_playlist)).performClick()
        rule.onNodeWithText("Delete \"Driving\"?").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.dialog_delete_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.dialog_cancel)).performClick()
        assertFalse(calls.deleted)
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_delete_playlist)).performClick()
        rule.onNodeWithText(text(R.string.dialog_delete_action)).performClick()
        assertTrue(calls.deleted)
        assertTrue("the screen closes after a delete", calls.closed)
    }

    @Test
    fun PlaylistScreen_Rename_StartsFromTheCurrentNameAndSendsTheNewOne() {
        val calls = Calls()
        screen(DetailState.Ready(driving, listOf(item(1))), calls)
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_rename_playlist)).performClick()
        rule.onNodeWithText(text(R.string.dialog_rename_action)).assertIsEnabled().performClick()
        assertEquals("Driving", calls.renamed)
    }

    @Test
    fun PlaylistScreen_EmptyPlaylist_ShowsTheDesignedText() {
        screen(DetailState.Ready(driving, emptyList()))
        rule.onNodeWithText(text(R.string.playlist_empty_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.playlist_empty_body)).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_FailedSong_ShowsCantPlayButKeepsItsRow() {
        screen(DetailState.Ready(driving, listOf(item(1))), failedKeys = setOf(song(1).key.value))
        rule.onNodeWithContentDescription("Song 1, Ann, Alb, can't play").assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_Unreadable_ShowsTheErrorInsteadOfTheList() {
        screen(DetailState.Unavailable(damaged = true))
        rule.onNodeWithText(text(R.string.playlists_damaged_title)).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_DeletedMeanwhile_SaysSo() {
        screen(DetailState.Gone)
        rule.onNodeWithText(text(R.string.playlist_gone)).assertIsDisplayed()
    }

    // ---- Edit order -------------------------------------------------------------------------------------------

    private fun editOrder(calls: Calls, items: List<PlaylistItem> = listOf(item(1), item(2), item(3))) {
        screen(DetailState.Ready(driving, items), calls)
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_edit_order)).performClick()
    }

    @Test
    fun PlaylistScreen_EditOrder_ShowsHandlesAndDone_AndDoneLeavesIt() {
        editOrder(Calls())
        rule.onAllNodesWithContentDescription(text(R.string.reorder_handle)).assertCountEquals(3)
        rule.onNodeWithText(text(R.string.play)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.edit_done)).performClick()
        rule.onNodeWithText(text(R.string.play)).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(text(R.string.reorder_handle)).assertCountEquals(0)
    }

    @Test
    fun PlaylistScreen_MoveUpDownActions_Reorder() {
        val calls = Calls()
        editOrder(calls)
        rule.onNodeWithContentDescription("More options for Song 2").performClick()
        rule.onNodeWithText(text(R.string.move_up)).performClick()
        rule.onNodeWithContentDescription("More options for Song 2").performClick()
        rule.onNodeWithText(text(R.string.move_down)).performClick()
        rule.onNodeWithContentDescription("More options for Song 3").performClick()
        rule.onNodeWithText(text(R.string.move_to_top)).performClick()
        assertEquals(listOf(2L to 0, 2L to 2, 3L to 0), calls.moves)
    }

    @Test
    fun PlaylistScreen_EditOrder_FirstRowCannotMoveUpAndLastCannotMoveDown() {
        editOrder(Calls())
        rule.onNodeWithContentDescription("More options for Song 1").performClick()
        rule.onNodeWithText(text(R.string.move_up)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.move_to_top)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.move_down)).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_EditOrder_RemoveStillOffersUndo() {
        val calls = Calls()
        editOrder(calls)
        rule.onNodeWithContentDescription("More options for Song 2").performClick()
        rule.onNodeWithText(text(R.string.menu_remove_from_playlist)).performClick()
        assertEquals(2L, calls.removedEntry)
        rule.onNodeWithText(text(R.string.snackbar_undo)).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_OneSong_HasNoEditOrder() {
        screen(DetailState.Ready(driving, listOf(item(1))))
        rule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        rule.onNodeWithText(text(R.string.menu_edit_order)).assertDoesNotExist()
    }

    @Test
    fun PlaylistScreen_DraggingTheHandleDownTwoRows_MovesTheRowTwoPlaces() {
        val calls = Calls()
        editOrder(calls)
        val rowPx = with(rule.density) { 64.dp.toPx() }
        rule.onAllNodesWithContentDescription(text(R.string.reorder_handle))[0].performTouchInput {
            down(center)
            moveBy(Offset(0f, rowPx * 2.5f))
            up()
        }
        assertEquals("the first row ends up last", 1L to 2, calls.moves.last())
        assertTrue("no move goes outside the list", calls.moves.all { it.second in 0..2 })
    }

    @Test
    fun PlaylistScreen_LongPlaylist_ShowsHoursAndMinutes() {
        // 34 songs of 3 minutes: 102 minutes.
        val many = (1..34).map { item(it) }
        screen(DetailState.Ready(driving, many))
        rule.onNode(hasText("34 songs · 1 h 42 min")).assertIsDisplayed()
    }

    @Test
    fun PlaylistScreen_EditOrder_TitlesTheScreenWithTheName() {
        editOrder(Calls())
        rule.onNodeWithText("Edit \"Driving\"").assertIsDisplayed()
    }
}
