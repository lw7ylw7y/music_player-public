package app.songplayer.split

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/**
 * Starts and stops the splitting job (Technical Design 5.1): one unique one-time work per source file, so a second start of the same file
 * while it runs does nothing. The job is expedited so that it may show its foreground notification when started from the app.
 */
object SplitWork {
    const val KEY_MODE = "mode"
    const val KEY_MEDIA_ID = "mediaId"
    const val KEY_VOLUME = "volume"
    const val KEY_RUN_ID = "runId"
    const val KEY_FOLDER = "folder"
    const val MODE_ANALYZE = "analyze"
    const val MODE_WRITE = "write"

    /** Output and progress keys: numbers and codes only, never a name. */
    const val OUT_ERROR = "error"
    /** The number (1 to N) of the song whose name was already taken by a different file; the name itself is looked up from the run. */
    const val OUT_CLASH = "clash"
    const val OUT_WRITTEN = "written"
    const val OUT_SONGS = "songs"
    const val OUT_SCAN_MS = "scanMs"
    const val OUT_ANALYZE_MS = "analyzeMs"
    const val OUT_DECODED_DS = "decodedDeciSec"
    const val OUT_WINDOWS = "windows"
    const val OUT_REGIONS = "regions"
    const val OUT_PROXY = "proxyUsable"
    const val PROGRESS_PHASE = "phase"
    const val PROGRESS_PERMILLE = "permille"
    const val PROGRESS_WRITTEN = "written"
    const val PROGRESS_SONGS = "songs"
    const val PROGRESS_STAGE = "stage"
    const val PROGRESS_DONE = "done"
    const val PROGRESS_TOTAL = "total"
    const val PROGRESS_DECODED_SEC = "decodedSec"

    fun uniqueName(mediaId: Long) = "split-$mediaId"

    /** Tags on every request: what kind of job it is and which file, so the app can find a job that was started before it was closed. */
    const val TAG_ANALYZE = "split-analyze"
    const val TAG_WRITE = "split-write"
    private const val TAG_FILE = "split-file-"

    /** The media id of the file a job (found by its tags) is about, or null. */
    fun mediaIdOf(tags: Set<String>): Long? = tags.firstOrNull { it.startsWith(TAG_FILE) }?.removePrefix(TAG_FILE)?.toLongOrNull()

    /** Put on the notification's tap: the app opens the splitting flow where the job is. */
    const val EXTRA_OPEN_SPLIT = "openSplit"

    /** Finds the cuts of the file and saves the run record `runId` for the review. [folder] is the folder the songs will go to, or null for the original's own. */
    fun enqueueAnalysis(context: Context, mediaId: Long, volume: String, runId: String, folder: String?): UUID =
        enqueue(context, mediaId, TAG_ANALYZE, workDataOf(KEY_MODE to MODE_ANALYZE, KEY_MEDIA_ID to mediaId, KEY_VOLUME to volume, KEY_RUN_ID to runId, KEY_FOLDER to folder))

    /** Writes the songs of the saved run record `runId` (also to continue one that stopped). */
    fun enqueueWriting(context: Context, mediaId: Long, volume: String, runId: String): UUID =
        enqueue(context, mediaId, TAG_WRITE, workDataOf(KEY_MODE to MODE_WRITE, KEY_MEDIA_ID to mediaId, KEY_VOLUME to volume, KEY_RUN_ID to runId))

    fun cancel(context: Context, mediaId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(mediaId))
    }

    /**
     * Queues the work and returns the id the screens watch. When a job for this file is already queued or running, nothing is queued (a second
     * start does nothing) and the id of that job is returned. Reads the scheduler's records: call it off the main thread.
     */
    private fun enqueue(context: Context, mediaId: Long, kind: String, input: Data): UUID {
        val manager = WorkManager.getInstance(context)
        manager.getWorkInfosForUniqueWork(uniqueName(mediaId)).get().firstOrNull { !it.state.isFinished }?.let { return it.id }
        val request = OneTimeWorkRequest.Builder(SplitWorker::class.java)
            .setInputData(input)
            .addTag(kind)
            .addTag(TAG_FILE + mediaId)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP: a second start of the same file while its job is queued or running does nothing.
        manager.enqueueUniqueWork(uniqueName(mediaId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
