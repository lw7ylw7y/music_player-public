package app.songplayer.details

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The one background job of the app (Technical Design 5.8, 8): a unique one-time work that looks songs up, [BATCH_SONGS][app.songplayer.core.details.DetailsRunner.BATCH_SONGS]
 * at a time, and queues itself again while songs remain. It needs an unmetered network when "Only on Wi-Fi" is on (the default), any network
 * otherwise, and a battery that is not low. There is no periodic work anywhere (SourceRulesTest).
 */
object DetailsWork {
    const val UNIQUE_NAME = "details-lookup"
    private const val BACKOFF_SECONDS = 30L

    /**
     * Queues the work. [policy] decides what happens to work that is already queued: KEEP (app start, a library sync) leaves it,
     * REPLACE (the Wi-Fi option changed) starts over with the new constraint, APPEND_OR_REPLACE (the worker chaining itself) runs after the current run.
     */
    fun enqueue(context: Context, wifiOnly: Boolean, delayMs: Long = 0, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequest.Builder(DetailsWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    /** Turning the feature off: no queued or running run sends anything afterwards (the service also checks the setting before each request). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
