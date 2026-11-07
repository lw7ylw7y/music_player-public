package app.songplayer.core.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantPolicyTest {
    @Test
    fun GrantPolicy_ResumeAsksOnlyWhenTheCountChanged_SoStuckSongsCauseNoLoop() {
        assertTrue(GrantPolicy.askOnResume(noAccess = 5, filesGranted = true, grantInFlight = false, lastAskedCount = null))
        // The grant came back, 2 songs are still refused (they cannot be written): the next resume does not ask again...
        assertFalse(GrantPolicy.askOnResume(noAccess = 2, filesGranted = true, grantInFlight = false, lastAskedCount = 2))
        // ...until the count changes (new songs wait).
        assertTrue(GrantPolicy.askOnResume(noAccess = 3, filesGranted = true, grantInFlight = false, lastAskedCount = 2))
        assertFalse(GrantPolicy.askOnResume(noAccess = 0, filesGranted = true, grantInFlight = false, lastAskedCount = null))
        assertFalse(GrantPolicy.askOnResume(noAccess = 5, filesGranted = false, grantInFlight = false, lastAskedCount = null)) // no Media management: a button, not an automatic ask
        assertFalse(GrantPolicy.askOnResume(noAccess = 5, filesGranted = true, grantInFlight = true, lastAskedCount = null))   // returning from the grant screen
    }

    @Test
    fun GrantPolicy_Rounds_StopWhenARoundWritesNothing() {
        assertTrue(GrantPolicy.anotherRound(wrote = 200, noAccessLeft = 450))
        assertFalse(GrantPolicy.anotherRound(wrote = 0, noAccessLeft = 450))   // nothing written: stuck songs, stop
        assertFalse(GrantPolicy.anotherRound(wrote = 12, noAccessLeft = 0))    // all done
        // A long sequence ends: 450 waiting, 200 per round.
        var left = 450
        var rounds = 0
        var wrote: Int
        do { wrote = minOf(200, left); left -= wrote; rounds++ } while (GrantPolicy.anotherRound(wrote, left))
        assertEquals(3, rounds)
    }

    @Test
    fun GrantPolicy_NextBatch_TakesUnaskedFirst_AndCapsTheRequest() {
        val waiting = (1..450).map { "k%03d".format(it) }
        val first = GrantPolicy.nextBatch(waiting, emptySet())
        assertEquals(200, first.size); assertEquals("k001", first.first()); assertEquals("k200", first.last())
        // The first 200 were asked for and are stuck (still waiting): the next request goes to the ones not asked yet.
        val second = GrantPolicy.nextBatch(waiting, first.toSet())
        assertEquals("k201", second.first()); assertEquals("k400", second.last())
        // When everything was asked for, the stuck ones are offered again rather than nothing.
        assertEquals(200, GrantPolicy.nextBatch(waiting, waiting.toSet()).size)
        assertEquals(emptyList<String>(), GrantPolicy.nextBatch(emptyList(), emptySet()))
    }
}
