package app.songplayer.core.split

import java.util.UUID

/** A splitting job the scheduler still has (queued or running): which file, which kind, and whether it is running. */
data class ActiveJob(val id: UUID, val mediaId: Long, val analysis: Boolean, val running: Boolean)

/**
 * Which splitting job or saved run the screens pick up when the app is opened again or the user taps the job's notification (Stories X11). Pure
 * choices, so the rules can be tested without a phone.
 */
object SplitResume {
    /** The last day: a saved run older than this is not offered by the notification (the run records are kept 30 days for "Continue"). */
    const val RECENT_SEC = 24L * 60 * 60

    /**
     * The job to show: one whose song is in the library ([hasSong]), a running one before a queued one, otherwise the first. Null when none can be
     * shown (a job whose song is not in the library yet may be picked once the library has loaded).
     */
    fun pickActive(jobs: List<ActiveJob>, hasSong: (Long) -> Boolean): ActiveJob? {
        val usable = jobs.filter { hasSong(it.mediaId) }
        return usable.firstOrNull { it.running } ?: usable.firstOrNull()
    }

    /** The newest saved run of the last day whose song is in the library, or null; [records] are newest first. */
    fun pickRecent(records: List<RunRecord>, nowSec: Long, hasSong: (Long) -> Boolean): RunRecord? =
        records.firstOrNull { nowSec - it.createdSec < RECENT_SEC && hasSong(it.source.mediaId) }
}
