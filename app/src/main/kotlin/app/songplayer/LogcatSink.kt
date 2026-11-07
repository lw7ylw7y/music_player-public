package app.songplayer

import android.util.Log
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink

/** The only file that touches `android.util.Log` (enforced by SourceRulesTest). Codes and numbers only. */
class LogcatSink : LogSink {
    override fun write(code: LogCode, values: List<Int>) {
        Log.i(TAG, (listOf(code.name) + values).joinToString(" "))
    }

    private companion object {
        const val TAG = "SongPlayer"
    }
}
