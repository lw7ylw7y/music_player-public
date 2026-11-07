package app.songplayer.core.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsOverviewTest {
    private fun state(
        key: Boolean = true, on: Boolean = true, wifiOnly: Boolean = true, connected: Boolean = true, unmetered: Boolean = true,
        paused: Long = 0, now: Long = 1_000, checked: Int = 5, remaining: Int = 10, held: Int = 2,
    ) = DetailsOverview.state(key, on, wifiOnly, connected, unmetered, paused, now, checked, remaining, held)

    @Test
    fun SongDetails_EachStatus_ShowsItsState() {
        assertEquals(DetailsState.Unavailable, state(key = false, on = true))
        assertEquals(DetailsState.Unavailable, state(key = false, on = false))
        assertEquals(DetailsState.Off, state(on = false))
        assertEquals(DetailsState.Running(5, 15), state())
        assertEquals(DetailsState.WaitingForWifi(10), state(unmetered = false))
        assertEquals(DetailsState.Running(5, 15), state(unmetered = false, wifiOnly = false)) // mobile data is allowed
        assertEquals(DetailsState.Offline(10), state(connected = false))
        assertEquals(DetailsState.AskedToWait, state(paused = 5_000))
        assertEquals(DetailsState.Done(2), state(remaining = 0))
        assertEquals(DetailsState.NothingToLookUp, state(remaining = 0, checked = 0))
    }

    @Test
    fun SongDetails_PauseOutranksTheConnection_AndEndsAtTheStamp() {
        assertEquals(DetailsState.AskedToWait, state(paused = 5_000, connected = false, unmetered = false))
        assertEquals(DetailsState.Running(5, 15), state(paused = 1_000, now = 1_000))
    }

    @Test
    fun LibraryBanner_ShowsOnlyForNewHeldSongs() {
        assertFalse(DetailsOverview.bannerVisible(held = 0, dismissedAt = 0))
        assertTrue(DetailsOverview.bannerVisible(held = 3, dismissedAt = 0))
        assertFalse(DetailsOverview.bannerVisible(held = 3, dismissedAt = 3))
        assertTrue(DetailsOverview.bannerVisible(held = 4, dismissedAt = 3))
        assertFalse(DetailsOverview.bannerVisible(held = 1, dismissedAt = 3)) // some were decided since
        // ...and the kept dismissal falls with them, so the next new held song shows the banner again.
        val kept = DetailsOverview.dismissalToKeep(held = 1, dismissedAt = 3)
        assertEquals(1, kept)
        assertTrue(DetailsOverview.bannerVisible(held = 2, dismissedAt = kept))
        assertEquals(3, DetailsOverview.dismissalToKeep(held = 5, dismissedAt = 3))
    }
}
