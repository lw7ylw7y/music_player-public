package app.songplayer

import app.songplayer.core.widget.WidgetSong
import app.songplayer.core.widget.WidgetState
import app.songplayer.widget.WidgetLive
import app.songplayer.widget.WidgetUpdater
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetUpdaterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val failures = AtomicInteger()
    private fun song(n: Int) = WidgetSong("k$n", "Song $n", "Ann", n.toLong())

    @After
    fun reset() {
        WidgetLive.state = null
    }

    /**
     * Runs [events] through an updater and returns how many redraws followed. It waits until [expected] redraws have run, then
     * a moment longer, so a redraw that should not have happened would show up in the count.
     */
    private fun redraws(events: List<WidgetState>, expected: Int, failing: Boolean = false): Int {
        val count = AtomicInteger()
        failures.set(0)
        val updater = WidgetUpdater(scope, onFailure = { failures.incrementAndGet() }) {
            if (count.incrementAndGet() == 1 && failing) throw IllegalStateException("cannot draw")
        }
        events.forEach(updater::onChange)
        val end = System.currentTimeMillis() + 5_000
        while (count.get() < expected && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(150)
        return count.get()
    }

    @Test
    fun WidgetUpdater_OnlyRedrawsWhenTheSongOrThePlayStateChanges() {
        val playing = WidgetState(song(1), playing = true)
        val events = listOf(playing, playing.copy(), playing.copy(), playing.copy(playing = false), playing.copy(playing = false), WidgetState(song(2), false))
        assertEquals("first, pause, next song", 3, redraws(events, expected = 3))
    }

    @Test
    fun WidgetUpdater_RecordsTheLiveStateForTheWidgetToDraw() {
        val state = WidgetState(song(3), playing = true)
        redraws(listOf(state), expected = 1)
        assertEquals(state, WidgetLive.state.takeIf { it?.song?.mediaId == "k3" })
    }

    @Test
    fun WidgetUpdater_AFailedRedraw_DoesNotStopLaterOnes() {
        val events = listOf(WidgetState(song(1), true), WidgetState(song(2), true), WidgetState(song(3), true))
        assertEquals(3, redraws(events, expected = 3, failing = true))
        assertEquals("the failure was reported once", 1, failures.get())
    }

    @Test
    fun WidgetUpdater_AFailedRedraw_IsTriedAgainWhenTheSameStateArrivesAgain() {
        val count = AtomicInteger()
        val updater = WidgetUpdater(scope) { if (count.incrementAndGet() == 1) throw IllegalStateException("cannot draw") }
        val state = WidgetState(song(1), playing = true)
        updater.onChange(state)
        val end = System.currentTimeMillis() + 5_000
        while (count.get() < 1 && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(150)
        updater.onChange(state.copy())
        while (count.get() < 2 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(2, count.get())
    }
}
