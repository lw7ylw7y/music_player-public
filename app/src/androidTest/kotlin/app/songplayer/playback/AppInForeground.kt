package app.songplayer.playback

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.songplayer.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Android 15 gives audio focus only to an app that is on screen, so tests that really play keep the app open. A test
 * class opens it once (`@BeforeClass`, [start]) and every test brings it to the front before it plays ([ensure]), which
 * costs a fraction of opening it again. If the app is still not on top, [ensure] fails at once and names what is.
 */
object AppInForeground {
    private var scenario: ActivityScenario<MainActivity>? = null

    fun start() {
        if (scenario == null) scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    fun stop() {
        scenario?.close()
        scenario = null
    }

    fun ensure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val end = System.currentTimeMillis() + 5_000
        var top = topActivity()
        while (!top.startsWith(context.packageName) && System.currentTimeMillis() < end) {
            Thread.sleep(100)
            top = topActivity()
        }
        check(top.startsWith(context.packageName)) { "the app is not on screen, which Android 15 requires for audio focus; on top: $top" }
    }

    private fun topActivity(): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities")
        BufferedReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd))).use { reader ->
            reader.lineSequence().forEach { line ->
                val i = line.indexOf("topResumedActivity=")
                if (i >= 0) return line.substring(i + "topResumedActivity=".length).trim().substringAfter(" u0 ").substringBefore(' ')
            }
        }
        return "unknown"
    }
}
