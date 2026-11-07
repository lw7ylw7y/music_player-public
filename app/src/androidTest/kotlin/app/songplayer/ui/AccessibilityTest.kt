package app.songplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.library.song
import app.songplayer.core.playlist.Playlist
import app.songplayer.core.playlist.PlaylistSummary
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SearchIndex
import app.songplayer.core.text.SortOrder
import app.songplayer.library.LibraryTab
import app.songplayer.playback.NowPlaying
import app.songplayer.playback.QueueRow
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story F1, NFR-5 (L3, on fixtures): every built screen, at the normal and the largest font, has a spoken name on every control
 * and no control smaller than 48 dp. Text contrast is `ContrastTest` (the palette, both themes); "no meaning by color alone" and
 * the TalkBack wording are read by eye with the UX Design checklist (F1 manual steps), because no automated check can judge them.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val keyer = FakeKeyer()
    private val songs = listOf(
        song("a.mp3", id = 1, title = "Rain", artist = "Ann", album = "Weather"),
        song("b.mp3", id = 2, title = "我等你", artist = "張學友", album = "愛情"),
        song("c.mp3", id = 3, title = "Sunny", artist = "Ann", album = "Weather"),
    )
    private val now = NowPlaying(
        mediaId = "k", mediaStoreId = 1, title = "Rain", artist = "Ann", artistTag = "Ann", album = "Weather",
        durationMs = 248_000, isPlaying = true,
    )

    private fun library(tab: LibraryTab) = LibraryUi(
        loaded = true,
        tab = tab,
        sort = SortOrder.Title,
        lists = LibraryLists.build(songs.map { IndexedSong.of(it, keyer) }, SortOrder.Title, keyer, ZoneId.of("UTC"), Locale.ENGLISH),
        generation = 1,
    )

    /** Shows [content] at [fontScale], then checks every clickable control of the merged tree. */
    private fun check(screen: String, fontScale: Float, content: @Composable () -> Unit) {
        val density = context.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) { SongPlayerTheme { content() } }
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("$screen: no control found at all", nodes.isNotEmpty())
        for (node in nodes) {
            // A row of a long list that is below the visible part is not placed (size 0 x 0): the user reaches it by scrolling and it is
            // checked when it is on screen, so only the controls that are laid out are measured.
            if (node.boundsInRoot.width <= 0f || node.boundsInRoot.height <= 0f) continue
            val spoken = (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()) +
                (node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty())
            assertTrue("$screen (font $fontScale): a control at ${node.boundsInRoot} has no spoken name", spoken.isNotBlank())
            // The touch area, not the drawn icon: Material gives a 40 dp icon button a 48 dp area around it.
            val widthDp = node.touchBoundsInRoot.width / density
            val heightDp = node.touchBoundsInRoot.height / density
            assertTrue(
                "$screen (font $fontScale): \"$spoken\" is %.0f x %.0f dp, under 48 dp".format(widthDp, heightDp),
                widthDp >= 47.5f && heightDp >= 47.5f,
            )
        }
    }

    private fun both(screen: String, content: @Composable () -> Unit) {
        check(screen, 1f, content)
    }

    @Test fun Accessibility_LibrarySongs_NormalFont() = both("Library, Songs") { LibraryScreen(library(LibraryTab.Songs), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_LibrarySongs_LargestFont() = check("Library, Songs", 2f) { LibraryScreen(library(LibraryTab.Songs), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_LibraryAlbums_NormalFont() = both("Library, Albums") { LibraryScreen(library(LibraryTab.Albums), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_LibraryAlbums_LargestFont() = check("Library, Albums", 2f) { LibraryScreen(library(LibraryTab.Albums), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_LibraryArtists_NormalFont() = both("Library, Artists") { LibraryScreen(library(LibraryTab.Artists), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_LibraryArtists_LargestFont() = check("Library, Artists", 2f) { LibraryScreen(library(LibraryTab.Artists), {}, {}, {}, {}, {}, { 0 }) }

    @Test fun Accessibility_PlaylistsTab_NormalFont() = both("Playlists tab") {
        PlaylistsTab(PlaylistsState.Ready(listOf(PlaylistSummary(Playlist(7, "Driving", 1), 12))), {}, {}, {})
    }

    @Test fun Accessibility_PlaylistsTab_LargestFont() = check("Playlists tab", 2f) {
        PlaylistsTab(PlaylistsState.Ready(listOf(PlaylistSummary(Playlist(7, "Driving", 1), 12))), {}, {}, {})
    }

    @Test fun Accessibility_Search_NormalFont() = both("Search") { SearchScreen(SearchIndex.build(songs.map { IndexedSong.of(it, keyer) }, keyer), onClose = {}) }

    @Test fun Accessibility_Search_LargestFont() = check("Search", 2f) { SearchScreen(SearchIndex.build(songs.map { IndexedSong.of(it, keyer) }, keyer), onClose = {}) }

    @Test fun Accessibility_NowPlaying_NormalFont() = both("Now Playing") {
        NowPlayingScreen(now, positionMs = { 94_000 }, onClose = {}, onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {})
    }

    @Test fun Accessibility_NowPlaying_LargestFont() = check("Now Playing", 2f) {
        NowPlayingScreen(now, positionMs = { 94_000 }, onClose = {}, onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {})
    }

    @Test fun Accessibility_Queue_NormalFont() = both("Queue") { QueueScreen(queueRows(), {}, {}, {}) }

    @Test fun Accessibility_Queue_LargestFont() = check("Queue", 2f) { QueueScreen(queueRows(), {}, {}, {}) }

    @Test fun Accessibility_Settings_NormalFont() = both("Settings") { SettingsScreen("1.0", { "x" }, {}) }

    @Test fun Accessibility_Settings_LargestFont() = check("Settings", 2f) { SettingsScreen("1.0", { "x" }, {}) }

    @Test fun Accessibility_MiniPlayer_NormalFont() = both("Mini player") { MiniPlayer(now, {}, {}, {}) }

    @Test fun Accessibility_MiniPlayer_LargestFont() = check("Mini player", 2f) { MiniPlayer(now, {}, {}, {}) }

    @Test fun Accessibility_EditDetails_NormalFont() = both("Edit details") { EditDetailsScreen(songs[0], onSave = {}, onClose = {}) }

    @Test fun Accessibility_EditDetails_LargestFont() = check("Edit details", 2f) { EditDetailsScreen(songs[0], onSave = {}, onClose = {}) }

    @Test fun Accessibility_SongDetails_NormalFont() = both("Song details") { DetailsScreenFixture() }

    @Test fun Accessibility_SongDetails_LargestFont() = check("Song details", 2f) { DetailsScreenFixture() }

    @Test fun Accessibility_LibraryWithReviewBanner_NormalFont() = both("Library with the review banner") {
        LibraryScreen(library(LibraryTab.Songs), {}, {}, {}, {}, {}, { 0 }, reviewBanner = ReviewBanner(12, {}, {}))
    }

    @Test fun Accessibility_LibraryWithReviewBanner_LargestFont() = check("Library with the review banner", 2f) {
        LibraryScreen(library(LibraryTab.Songs), {}, {}, {}, {}, {}, { 0 }, reviewBanner = ReviewBanner(12, {}, {}))
    }

    private val reviewing = ReviewUi.Reviewing(
        0, 12,
        app.songplayer.core.details.ReviewItem(
            songs[0],
            listOf(
                app.songplayer.core.details.Candidate("Rain", listOf("Ann"), 228, 0.94, emptyList()),
                app.songplayer.core.details.Candidate("Rain (live)", listOf("Ann"), 230, 0.80, emptyList()),
            ),
        ),
        0,
    )

    @Test fun Accessibility_Review_NormalFont() = both("Review") { ReviewScreen(reviewing, false, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun Accessibility_Review_LargestFont() = check("Review", 2f) { ReviewScreen(reviewing, false, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun Accessibility_Album_NormalFont() = both("Album") { GroupScreen("Weather", "Ann", songs, {}, { _, _ -> }, { _, _ -> }) }

    @Test fun Accessibility_Album_LargestFont() = check("Album", 2f) { GroupScreen("Weather", "Ann", songs, {}, { _, _ -> }, { _, _ -> }) }

    @Test fun Accessibility_NotFound_NormalFont() = both("Not found") { NotFoundScreen(songs, {}, {}) }

    @Test fun Accessibility_NotFound_LargestFont() = check("Not found", 2f) { NotFoundScreen(songs, {}, {}) }

    private val longSong = song("album.mp3", id = 9, title = "Big album", durationMs = 45 * 60_000L)
    private val foundFixture = app.songplayer.core.split.SongsFound.of(
        app.songplayer.core.split.RunRecord(
            "r", app.songplayer.core.split.SourceIdentity(9, 1, 1, "h"), "album.mp3",
            listOf(app.songplayer.core.split.RunCut(252.0, 2.0, true, false), app.songplayer.core.split.RunCut(510.0, 1.0, false, false)),
            "Music/", "external_primary", app.songplayer.core.split.RunPhase.Reviewing, emptyList(), 1,
        ),
        45 * 60.0,
    )

    @Test fun Accessibility_SplitFinding_NormalFont() = both("Finding the songs") { FindingScreen(longSong, 400, {}) }

    @Test fun Accessibility_SplitFinding_LargestFont() = check("Finding the songs", 2f) { FindingScreen(longSong, 400, {}) }

    private val cutRecord = app.songplayer.core.split.RunRecord(
        "r", app.songplayer.core.split.SourceIdentity(9, 1, 1, "h"), "album.mp3",
        listOf(app.songplayer.core.split.RunCut(252.0, 2.0, true, false), app.songplayer.core.split.RunCut(510.0, 1.0, false, false)),
        "Music/", "external_primary", app.songplayer.core.split.RunPhase.Reviewing, emptyList(), 1,
    )

    @Test fun Accessibility_CutReview_NormalFont() = both("Check a cut") { CutReviewScreen(cutRecord, 2700.0, 1, false, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun Accessibility_CutReview_LargestFont() = check("Check a cut", 2f) { CutReviewScreen(cutRecord, 2700.0, 1, false, {}, {}, {}, {}, {}, {}, {}, {}) }

    private val readySummary = app.songplayer.core.split.SplitSummary("Big album.mp3", 12, "Big album 01", "Big album 12", listOf("Music", "Albums"), 520L shl 20, 2L shl 30, 3)

    @Test fun Accessibility_SplitReady_NormalFont() = both("Ready to split") { ReadyScreen(readySummary, {}, {}, {}) }

    @Test fun Accessibility_SplitReady_LargestFont() = check("Ready to split", 2f) { ReadyScreen(readySummary, {}, {}, {}) }

    @Test fun Accessibility_SplitWriting_NormalFont() = both("Writing") { WritingScreen(4, 12, {}) }

    @Test fun Accessibility_SplitWriting_LargestFont() = check("Writing", 2f) { WritingScreen(4, 12, {}) }

    @Test fun Accessibility_SplitDone_NormalFont() = both("Done") { FinishedScreen(app.songplayer.core.split.SplitOutcome(12, 12, 0, null), "Big album.mp3", listOf("Music"), false, false, {}, {}, {}, {}) }

    @Test fun Accessibility_SplitDone_LargestFont() = check("Done", 2f) { FinishedScreen(app.songplayer.core.split.SplitOutcome(12, 12, 0, null), "Big album.mp3", listOf("Music"), false, false, {}, {}, {}, {}) }

    @Test fun Accessibility_SplitBanner_NormalFont() = both("Split banner") { SplitBannerRow(SplitBanner(SplitBannerUi.Found(12), {}, {})) }

    @Test fun Accessibility_SplitBanner_LargestFont() = check("Split banner", 2f) { SplitBannerRow(SplitBanner(SplitBannerUi.Found(12), {}, {})) }

    @Test fun Accessibility_SplitSongsFound_NormalFont() = both("The songs found") { SongsFoundScreen(longSong, foundFixture, false, {}, {}, {}) }

    @Test fun Accessibility_SplitSongsFound_LargestFont() = check("The songs found", 2f) { SongsFoundScreen(longSong, foundFixture, false, {}, {}, {}) }

    @Composable
    private fun DetailsScreenFixture() = SongDetailsScreen(
        ui = SongDetailsUi(
            enabled = true, wifiOnly = true, replace = true, state = app.songplayer.core.details.DetailsState.Running(214, 1248),
            held = 12, notFound = 2, waitingSongs = 214, filesGranted = false, consented = true,
        ),
        rows = recent(), onUndo = {}, onClose = {}, onEnabled = {}, onWifiOnly = {}, onAllowFiles = {},
    )

    private fun recent() = listOf(RecentRowUi(1, "Rain", "Ann", listOf(app.songplayer.core.details.DetailField.Title), byUser = true))

    private fun queueRows() = listOf(
        QueueRow(index = 0, mediaId = "k0", mediaStoreId = 1, title = "Rain", artist = "Ann", artistTag = "Ann", added = false, isCurrent = true),
        QueueRow(index = 1, mediaId = "k1", mediaStoreId = 2, title = "Sunny", artist = "Ann", artistTag = "Ann", added = false, isCurrent = false),
    )
}
