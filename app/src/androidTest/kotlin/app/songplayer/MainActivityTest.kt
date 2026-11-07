package app.songplayer

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Which screen shows depends on the permission the emulator has, so any of the three entry screens passes. */
    @Test
    fun App_Launch_ShowsALibraryOrAnAccessScreen() {
        val library = hasText(rule.activity.getString(R.string.app_name))
        val first = hasText(rule.activity.getString(R.string.access_first_title))
        val refused = hasText(rule.activity.getString(R.string.access_refused_title))
        rule.onNode(library or first or refused).assertExists()
    }
}
