package app.songplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.songplayer.ui.SongPlayerRoot
import app.songplayer.ui.SongPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as SongPlayerApp).container
        container.openedFiles.handle(intent)
        if (intent.getBooleanExtra(app.songplayer.split.SplitWork.EXTRA_OPEN_SPLIT, false)) container.requestSplitReopen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SongPlayerApp).container
        // Not again after a rotation (saved state), nor when the task is reopened from Recents (the original intent comes back).
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) {
            container.openedFiles.handle(intent)
            if (intent.getBooleanExtra(app.songplayer.split.SplitWork.EXTRA_OPEN_SPLIT, false)) container.requestSplitReopen()
        }
        setContent {
            SongPlayerTheme { SongPlayerRoot(container) }
        }
    }
}
