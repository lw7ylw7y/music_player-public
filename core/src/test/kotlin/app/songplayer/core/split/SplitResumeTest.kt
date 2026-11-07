package app.songplayer.core.split

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitResumeTest {
    private fun job(media: Long, running: Boolean, analysis: Boolean = true) = ActiveJob(UUID.randomUUID(), media, analysis, running)

    private fun record(id: String, media: Long, at: Long) = RunRecord(
        id, SourceIdentity(media, 1, 1, "h"), "a.mp3", emptyList(), "Music/", "external_primary", RunPhase.Reviewing, emptyList(), at,
    )

    @Test
    fun SplitResume_Active_PrefersARunningJob_AndOneWhoseSongIsThere() {
        val queued = job(1, running = false)
        val running = job(2, running = true, analysis = false)
        val unknown = job(3, running = true)
        assertEquals(running, SplitResume.pickActive(listOf(queued, running), hasSong = { true }))
        assertEquals(queued, SplitResume.pickActive(listOf(queued, unknown), hasSong = { it != 3L }))        // the song of the running job is not in the library (yet)
        assertNull(SplitResume.pickActive(listOf(unknown), hasSong = { false }))
        assertNull(SplitResume.pickActive(emptyList(), hasSong = { true }))
    }

    @Test
    fun SplitResume_Recent_TakesTheNewestRunOfTheLastDayWithASong() {
        val now = 1_000_000L
        val records = listOf(record("new", 1, now - 100), record("other", 2, now - 200), record("old", 1, now - SplitResume.RECENT_SEC - 1))
        assertEquals("new", SplitResume.pickRecent(records, now) { true }?.id)
        assertEquals("other", SplitResume.pickRecent(records, now) { it == 2L }?.id)                     // the newest run's song is gone: the next one
        assertNull(SplitResume.pickRecent(records, now) { it == 9L })
        assertNull(SplitResume.pickRecent(listOf(records[2]), now) { true })                              // older than a day
    }
}
