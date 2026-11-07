package app.songplayer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.songplayer.MainActivity
import app.songplayer.R
import app.songplayer.SongPlayerApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3: the refusal dialog of "Open with" (story D1, UX Design D7) on the real screen. */
@RunWith(AndroidJUnit4::class)
class OpenFileDialogTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    @Test
    fun OpenFile_Refused_ShowsOneDialogWithTheDesignedWords_AndOkClosesIt() {
        val container = (rule.activity.application as SongPlayerApp).container
        rule.runOnUiThread { container.openedFiles.handle(Intent(Intent.ACTION_VIEW, Uri.parse("file:///data/local/tmp/notes.txt"))) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text(R.string.open_file_error_title)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text(R.string.open_file_error_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.open_file_error_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.dialog_ok)).performClick()
        rule.onNodeWithText(text(R.string.open_file_error_title)).assertDoesNotExist()
    }
}
