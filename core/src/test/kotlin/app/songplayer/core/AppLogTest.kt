package app.songplayer.core

import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {
    private class RecordingSink : LogSink {
        val events = mutableListOf<Pair<LogCode, List<Int>>>()
        override fun write(code: LogCode, values: List<Int>) {
            events += code to values
        }
    }

    @Test
    fun AppLog_Event_AcceptsOnlyCodes() {
        // The signature itself is the rule: a code and integers, no String anywhere.
        val event = AppLog::class.java.methods.single { it.name == "event" }
        assertEquals(listOf(LogCode::class.java, IntArray::class.java), event.parameterTypes.toList())
        val write = LogSink::class.java.methods.single { it.name == "write" }
        assertEquals(listOf(LogCode::class.java, List::class.java), write.parameterTypes.toList())
    }

    @Test
    fun AppLog_Event_ReachesEverySinkWithCodeAndValues() {
        val a = RecordingSink()
        val b = RecordingSink()
        AppLog(listOf(a, b)).event(LogCode.KEYER_FALLBACK, 3, 7)
        assertEquals(listOf(LogCode.KEYER_FALLBACK to listOf(3, 7)), a.events)
        assertEquals(a.events, b.events)
    }
}
