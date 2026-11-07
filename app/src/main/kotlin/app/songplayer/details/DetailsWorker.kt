package app.songplayer.details

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import app.songplayer.SongPlayerApp
import app.songplayer.core.details.DetailsPlan
import app.songplayer.core.details.NextStep
import app.songplayer.core.details.RunResult
import app.songplayer.core.errors.StoreError
import app.songplayer.core.log.LogCode
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * One run of the lookup (Stories H7). The rules are in `core` (`DetailsRunner`, `DetailsPlan`); this class only connects them to
 * WorkManager: it reads the songs, runs one batch off the main thread, and turns the result into success, a retry with the scheduler's
 * backoff, or the next unique work (at once for the next batch, after a delay once the quota is reached).
 */
class DetailsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SongPlayerApp).container
        // The scheduler stops a run by cancelling this coroutine; a decode in another thread looks at the job between two audio buffers.
        val job = currentCoroutineContext()[Job]
        val stopped: () -> Boolean = { job?.isCancelled == true }
        container.detailsStopped = stopped
        try {
            val result = withContext(Dispatchers.IO) {
                val first = container.detailsRunner.run(container.songStore.indexed().map { it.song })
                // Songs added while this run was at work (a library refresh found them) would otherwise wait for the next trigger.
                if (first == RunResult.Done && container.detailsRunner.hasNewWork(container.songStore.indexed().map { it.song })) RunResult.NextBatch else first
            }
            when (result) {
                RunResult.Retry -> container.log.event(LogCode.DETAILS_LOOKUP_FAILED, 0)
                is RunResult.Quota -> container.log.event(LogCode.DETAILS_LOOKUP_FAILED, 1)
                RunResult.Stopped -> if (container.detailsSettings.enabled()) container.log.event(LogCode.DETAILS_LOOKUP_FAILED, 3)
                else -> {}
            }
            val jitter = Random.nextLong(0, DetailsPlan.QUOTA_JITTER_MS + 1)
            return when (val next = DetailsPlan.next(result, System.currentTimeMillis(), jitter)) {
                NextStep.None -> Result.success()
                NextStep.RetryLater -> Result.retry()
                is NextStep.Again -> {
                    // The user may have turned the feature off while this run worked: then nothing is queued behind it.
                    if (container.detailsSettings.enabled()) {
                        DetailsWork.enqueue(applicationContext, container.detailsSettings.wifiOnly(), next.delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    }
                    Result.success()
                }
            }
        } catch (e: StoreError) {
            container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
            return Result.retry()
        } finally {
            // A replaced run may already have installed its own hook: only remove ours.
            if (container.detailsStopped === stopped) container.detailsStopped = { false }
        }
    }
}
