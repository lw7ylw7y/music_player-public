package app.songplayer.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipPolicyTest {
    @Test
    fun SkipPolicy_FailuresBelowTheQueueSize_Skip() {
        assertEquals(SkipDecision.Skip, SkipPolicy.next(consecutiveFailures = 1, queueSize = 3))
        assertEquals(SkipDecision.Skip, SkipPolicy.next(consecutiveFailures = 2, queueSize = 3))
    }

    @Test
    fun SkipPolicy_FailuresReachQueueSize_Stops() {
        assertEquals(SkipDecision.Stop, SkipPolicy.next(consecutiveFailures = 3, queueSize = 3))
        assertEquals(SkipDecision.Stop, SkipPolicy.next(consecutiveFailures = 4, queueSize = 3))
    }

    @Test
    fun SkipPolicy_AQueueOfOneBadSong_StopsAtOnce() {
        assertEquals(SkipDecision.Stop, SkipPolicy.next(consecutiveFailures = 1, queueSize = 1))
    }

    @Test
    fun SkipPolicy_CounterResetsOnFirstPlayableSong() {
        // The service starts counting again at the first song that plays: after a reset the first failure skips.
        assertEquals(SkipDecision.Stop, SkipPolicy.next(consecutiveFailures = 3, queueSize = 3))
        assertEquals(SkipDecision.Skip, SkipPolicy.next(consecutiveFailures = 1, queueSize = 3))
    }

    @Test
    fun SkipPolicy_EmptyQueue_Stops() {
        assertEquals(SkipDecision.Stop, SkipPolicy.next(consecutiveFailures = 1, queueSize = 0))
    }
}
