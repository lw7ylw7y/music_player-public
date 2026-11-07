package app.songplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.R
import app.songplayer.core.details.DetailsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3 screen tests of Song details and the Library banner on fixtures (Stories H8). */
@RunWith(AndroidJUnit4::class)
class SongDetailsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private fun ui(state: DetailsState, enabled: Boolean = true, consented: Boolean = true, wifiOnly: Boolean = true, files: Boolean = false, replace: Boolean = false) =
        SongDetailsUi(enabled, wifiOnly, replace, state, held = 3, notFound = 2, waitingSongs = if (files) 3 else 0, filesGranted = false, consented = consented)

    private fun show(ui: SongDetailsUi?, onEnabled: (Boolean) -> Unit = {}, onWifiOnly: (Boolean) -> Unit = {}, onAllow: () -> Unit = {}) {
        rule.setContent {
            SongPlayerTheme {
                SongDetailsScreen(ui, rows = emptyList(), onUndo = {}, onClose = {}, onEnabled = onEnabled, onWifiOnly = onWifiOnly, onAllowFiles = onAllow)
            }
        }
    }

    @Test
    fun SongDetails_FirstTurnOn_ShowsConsent_NotNowKeepsOff() {
        val calls = mutableListOf<Boolean>()
        show(ui(DetailsState.Off, enabled = false, consented = false), onEnabled = { calls += it })
        rule.onNodeWithText(text(R.string.details_switch_title)).performClick()
        rule.onNodeWithText(text(R.string.consent_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.consent_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.consent_not_now)).performClick()
        assertEquals("Not now sends nothing and changes nothing", emptyList<Boolean>(), calls)
        rule.onNodeWithText(text(R.string.details_switch_title)).performClick()
        rule.onNodeWithText(text(R.string.consent_turn_on)).performClick()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun SongDetails_AfterConsent_TurningOnNeedsNoDialog() {
        val calls = mutableListOf<Boolean>()
        show(ui(DetailsState.Off, enabled = false, consented = true), onEnabled = { calls += it })
        rule.onNodeWithText(text(R.string.details_switch_title)).performClick()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun SongDetails_EachStatus_ShowsItsWords() {
        val cases = listOf(
            DetailsState.Off to text(R.string.details_state_off),
            DetailsState.Unavailable to text(R.string.details_state_unavailable),
            DetailsState.Running(214, 1248) to text(R.string.details_state_running),
            DetailsState.WaitingForWifi(700) to text(R.string.details_state_wifi),
            DetailsState.Offline(5) to text(R.string.details_state_offline),
            DetailsState.AskedToWait to text(R.string.details_state_wait),
            DetailsState.Done(0) to text(R.string.details_state_done),
            DetailsState.Done(12) to "All songs checked. 12 need your review.",
            DetailsState.NothingToLookUp to text(R.string.details_state_nothing),
        )
        var state: DetailsState by mutableStateOf(cases.first().first)
        rule.setContent { SongPlayerTheme { SongDetailsScreen(ui(state), emptyList(), {}, {}, {}, {}, {}) } }
        for ((s, words) in cases) {
            state = s
            rule.waitForIdle()
            rule.onNodeWithText(words).assertIsDisplayed()
        }
    }

    @Test
    fun SongDetails_RunningAndWaiting_ShowTheirNumbers() {
        show(ui(DetailsState.Running(214, 1248)))
        rule.onNodeWithText("214 of 1,248 checked").assertIsDisplayed()
    }

    @Test
    fun SongDetails_WifiOnly_DefaultOnAndSpoken() {
        val calls = mutableListOf<Boolean>()
        show(ui(DetailsState.Running(1, 2)), onWifiOnly = { calls += it })
        rule.onNodeWithText(text(R.string.details_wifi_title)).assertIsOn()
        // "On" is written inside that row, not only drawn by the switch.
        rule.onNode(hasText(text(R.string.details_wifi_title)) and hasText(text(R.string.switch_word_on))).assertExists()
        rule.onNodeWithText(text(R.string.details_wifi_title)).performClick()
        assertEquals(listOf(false), calls)
    }

    @Test
    fun SongDetails_ReplaceSwitch_OffByDefault_AndSpoken() {
        val calls = mutableListOf<Boolean>()
        rule.setContent {
            SongPlayerTheme { SongDetailsScreen(ui(DetailsState.Running(1, 2)), emptyList(), {}, {}, {}, {}, {}, onReplace = { calls += it }) }
        }
        rule.onNodeWithText(text(R.string.details_replace_title)).assertIsOff()
        rule.onNode(hasText(text(R.string.details_replace_title)) and hasText(text(R.string.switch_word_off))).assertExists()
        rule.onNodeWithText(text(R.string.details_replace_title)).performClick()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun SongDetails_FilesNotChangeable_OffersAllow() {
        var allowed = 0
        var writeNow = 0
        rule.setContent { SongPlayerTheme { SongDetailsScreen(ui(DetailsState.Running(1, 2), files = true), emptyList(), {}, {}, {}, {}, onAllowFiles = { allowed++ }, onWriteNow = { writeNow++ }) } }
        rule.onNodeWithText("3 songs\' details are only in the app").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.details_allow)).performClick()
        rule.onNodeWithText(text(R.string.details_write_now)).performClick()
        assertEquals(1, allowed); assertEquals(1, writeNow)
    }

    @Test
    fun SongDetails_NoKeyInThisBuild_SwitchesAreOff() {
        show(ui(DetailsState.Unavailable, enabled = false))
        rule.onNodeWithText(text(R.string.details_switch_title)).assertIsOff()
    }

    @Test
    fun SongDetails_LargestFont_NoClippedControls() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { SongPlayerTheme { SongDetailsScreen(ui(DetailsState.WaitingForWifi(700), files = true), emptyList(), {}, {}, {}, {}, {}) } }
        }
        // At twice the font the three switches fill the screen: the status line is below them, so scroll to it.
        rule.onNodeWithText(text(R.string.details_switch_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.details_state_wifi)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.details_allow)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun SongDetails_ConsentDialog_FitsAtTheLargestFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SongPlayerTheme { SongDetailsScreen(ui(DetailsState.Off, enabled = false, consented = false), emptyList(), {}, {}, {}, {}, {}) }
            }
        }
        rule.onNodeWithText(text(R.string.details_switch_title)).performClick()
        rule.onNodeWithText(text(R.string.consent_not_now)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.consent_turn_on)).assertIsDisplayed()
    }

    @Test
    fun LibraryBanner_ShowsCountAndDismisses() {
        var reviewed = 0
        var dismissed = 0
        rule.setContent {
            SongPlayerTheme { ReviewBannerRow(ReviewBanner(12, { reviewed++ }, { dismissed++ })) }
        }
        rule.onNodeWithText("12 songs need your review").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.review_banner_action)).performClick()
        rule.onNodeWithContentDescription(text(R.string.review_banner_dismiss)).performClick()
        assertEquals(1, reviewed)
        assertEquals(1, dismissed)
    }
}
