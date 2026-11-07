package app.songplayer

import app.songplayer.core.log.ErrorEntry
import app.songplayer.core.log.ErrorLog
import app.songplayer.core.log.LogCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogSinkTest {
    private class Recording(private val latch: CountDownLatch? = null, private val failFirst: Boolean = false) : ErrorLog {
        val written = java.util.Collections.synchronizedList(mutableListOf<Triple<LogCode, List<Int>, Long>>())
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var first = true
        val sawLast = CountDownLatch(1)
        override fun record(code: LogCode, values: List<Int>, atEpochSec: Long) {
            latch?.await(5, TimeUnit.SECONDS)
            threads += Thread.currentThread().name
            if (failFirst && first) {
                first = false
                throw IllegalStateException("disk full")
            }
            written += Triple(code, values, atEpochSec)
            if (values == listOf(-1)) sawLast.countDown()
        }

        override fun recent(): List<ErrorEntry> = emptyList()
    }

    /** Sends a last event with the value -1 through the same sink and waits until [log] has seen it: everything queued before it has run. */
    private fun drain(sink: ErrorLogSink, log: Recording) {
        sink.write(LogCode.KEYER_FALLBACK, listOf(-1))
        assertTrue("the sink did not finish its queue", log.sawLast.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun ErrorLogSink_OnlyErrorsAreKept_WithTheirNumbersAndTheTimeTheyHappened() {
        val log = Recording()
        val sink = ErrorLogSink({ log }, nowSec = { 42L })
        sink.write(LogCode.APP_START, emptyList())
        sink.write(LogCode.PLAY_TAP_TO_SOUND_MS, listOf(120))
        sink.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(2005))
        drain(sink, log)
        assertEquals(listOf(Triple(LogCode.PLAYBACK_ITEM_FAILED, listOf(2005), 42L), Triple(LogCode.KEYER_FALLBACK, listOf(-1), 42L)), log.written.toList())
    }

    @Test
    fun ErrorLogSink_KeepsTheOrderOfTheEvents() {
        val log = Recording()
        val sink = ErrorLogSink({ log })
        for (i in 1..50) sink.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(i))
        drain(sink, log)
        assertEquals((1..50).toList(), log.written.filter { it.second != listOf(-1) }.map { it.second.single() })
    }

    @Test
    fun ErrorLogSink_WritesOnItsOwnThread_AndNeverBlocksTheCaller() {
        val gate = CountDownLatch(1)
        val log = Recording(latch = gate)
        val sink = ErrorLogSink({ log })
        val started = System.nanoTime()
        sink.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(1))
        assertTrue("the caller was not held up by a slow write", System.nanoTime() - started < 1_000_000_000L)
        gate.countDown()
        drain(sink, log)
        assertTrue(log.threads.all { it == "error-log" })
    }

    @Test
    fun ErrorLogSink_AFailingWriteOrProvider_IsDroppedAndLaterEventsStillArrive() {
        val log = Recording(failFirst = true)
        val sink = ErrorLogSink({ log })
        sink.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(1)) // this write throws
        sink.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(2))
        drain(sink, log)
        assertEquals(listOf(2, -1), log.written.map { it.second.single() })

        val second = Recording()
        var calls = 0
        val flaky = ErrorLogSink({ if (calls++ == 0) throw IllegalStateException("cannot open") else second })
        flaky.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(7)) // the provider throws
        flaky.write(LogCode.PLAYBACK_ITEM_FAILED, listOf(8))
        drain(flaky, second)
        assertTrue(second.written.any { it.second == listOf(8) })
    }
}
