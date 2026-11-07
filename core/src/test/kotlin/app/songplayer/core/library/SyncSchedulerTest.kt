package app.songplayer.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SyncSchedulerTest {
    @Test
    fun SyncScheduler_IdleRequest_StartsARun() {
        val s = SyncScheduler()
        assertTrue(s.request())
    }

    @Test
    fun SyncScheduler_TwoRequests_RunsOnceThenOnceMore_EvenIfSecondFinishesFirst() {
        val s = SyncScheduler()
        assertTrue("first request starts a run", s.request())
        assertFalse("second request during the run only queues", s.request())
        // The second request has "finished" (it was folded into the queue) before the first run ends.
        assertTrue("finishing the first run asks for exactly one more", s.finished())
        assertFalse("the rerun, once finished, asks for nothing more", s.finished())
        assertTrue("idle again: a new request starts a run", s.request())
    }

    @Test
    fun SyncScheduler_ManyRequestsDuringARun_CollapseIntoOneRerun() {
        val s = SyncScheduler()
        assertTrue(s.request())
        repeat(5) { assertFalse(s.request()) }
        assertTrue(s.finished())
        assertFalse(s.finished())
    }

    @Test
    fun SyncScheduler_ConcurrentRequests_ExactlyOneStarts() {
        val s = SyncScheduler()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val starts = AtomicInteger()
        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                if (s.request()) starts.incrementAndGet()
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, starts.get())
        assertTrue("the 15 queued requests become one rerun", s.finished())
        assertFalse(s.finished())
    }
}
