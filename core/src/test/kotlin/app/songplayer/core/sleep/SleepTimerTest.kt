package app.songplayer.core.sleep

import app.songplayer.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private class FakeClock(var elapsed: Long = 5_000_000, var wall: Long = 1_700_000_000_000) : Clock {
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
    }

    private val clock = FakeClock()
    private val minute = 60_000L

    @Test
    fun SleepTimer_AllowedValues_OnlyFour() {
        val timer = SleepTimer(clock)
        assertEquals(listOf(15, 30, 45, 60), timer.choices)
        for (bad in listOf(0, 1, 5, 14, 16, 90, -15, 120)) {
            assertFalse("$bad is refused", timer.start(bad))
            assertEquals(SleepState.Idle, timer.state)
        }
        for (good in listOf(15, 30, 45, 60)) assertTrue(timer.start(good))
    }

    @Test
    fun SleepTimer_Start_RunsUntilTheChosenMinutesHavePassed_ThenExpiresOnce() {
        val timer = SleepTimer(clock)
        timer.start(15)
        assertEquals(SleepState.Running(clock.elapsed + 15 * minute), timer.state)
        assertEquals(15 * minute, timer.leftMs())
        clock.elapsed += 14 * minute + 59_000
        assertEquals(1_000L, timer.leftMs())
        assertFalse("not yet", timer.expire())
        clock.elapsed += 1_000
        assertTrue("the time is up", timer.expire())
        assertEquals("and the timer is over", SleepState.Idle, timer.state)
        assertFalse("it expires once", timer.expire())
        assertNull(timer.leftMs())
    }

    @Test
    fun SleepTimer_ClockJumps_DoesNotMoveEnd() {
        val timer = SleepTimer(clock)
        timer.start(30)
        val end = (timer.state as SleepState.Running).endsAtMs
        // The user sets the date forward by a day, and then back by two: the timer does not notice.
        clock.wall += 24 * 60 * minute
        assertFalse(timer.expire())
        clock.wall -= 48 * 60 * minute
        assertFalse(timer.expire())
        assertEquals(SleepState.Running(end), timer.state)
        assertEquals(30 * minute, timer.leftMs())
    }

    @Test
    fun SleepTimer_EndOfSong_WaitsForTheSongNotForTheClock() {
        val timer = SleepTimer(clock)
        timer.startEndOfSong()
        assertEquals(SleepState.EndOfSong, timer.state)
        assertNull("it has no countdown", timer.leftMs())
        clock.elapsed += 600 * minute
        assertFalse("and the clock never ends it", timer.expire())
        assertEquals(SleepState.EndOfSong, timer.state)
        timer.songEnded()
        assertEquals(SleepState.Idle, timer.state)
        timer.songEnded()
        assertEquals(SleepState.Idle, timer.state)
    }

    @Test
    fun SleepTimer_SongEnding_DoesNotEndARunningTimer() {
        val timer = SleepTimer(clock)
        timer.start(15)
        timer.songEnded()
        assertTrue(timer.state is SleepState.Running)
    }

    @Test
    fun SleepTimer_Cancel_ClearsRunningAndEndOfSong_AndStartReplacesWhatWasThere() {
        val timer = SleepTimer(clock)
        timer.start(15)
        timer.cancel()
        assertEquals(SleepState.Idle, timer.state)
        assertNull(timer.leftMs())

        timer.start(15)
        clock.elapsed += 10 * minute
        timer.start(60)
        assertEquals(60 * minute, timer.leftMs())

        timer.startEndOfSong()
        assertEquals(SleepState.EndOfSong, timer.state)
        assertTrue(timer.start(30))
        assertEquals(30 * minute, timer.leftMs())
        timer.startEndOfSong()
        timer.cancel()
        assertEquals(SleepState.Idle, timer.state)
    }

    @Test
    fun SleepTimer_NothingRunning_HasNoTimeLeftAndNeverExpires() {
        val timer = SleepTimer(clock)
        assertNull(timer.leftMs())
        assertFalse(timer.expire())
    }
}
