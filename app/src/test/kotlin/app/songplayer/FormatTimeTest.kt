package app.songplayer

import app.songplayer.ui.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimeTest {
    @Test
    fun FormatTime_Zero_IsZeroColonZeroZero() = assertEquals("0:00", formatTime(0))

    @Test
    fun FormatTime_JustUnderAMinute_RoundsDown() = assertEquals("0:59", formatTime(59_999))

    @Test
    fun FormatTime_MinutesAndSeconds_ArePadded() = assertEquals("4:08", formatTime(248_000))

    @Test
    fun FormatTime_AnHourOrMore_ShowsHours() {
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("1:02:09", formatTime(3_729_000))
    }

    @Test
    fun FormatTime_Negative_IsZero() = assertEquals("0:00", formatTime(-5))
}
