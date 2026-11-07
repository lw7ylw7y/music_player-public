package app.songplayer.split

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.songplayer.R
import app.songplayer.SongPlayerApp
import app.songplayer.core.errors.SplitError
import app.songplayer.core.log.LogCode
import app.songplayer.core.split.FolderRule
import app.songplayer.core.split.RunStore
import app.songplayer.core.split.SongNames
import app.songplayer.core.split.SplitJob
import app.songplayer.core.split.SplitPhase
import app.songplayer.core.split.SplitProgress
import app.songplayer.core.split.SplitWriter
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The background job of the splitter (Stories X6, Technical Design 5.1): a foreground work with a progress notification. It only connects the
 * rules of `core` (`SplitJob`, `SplitWriter`) to the phone: the real file, Android's decoder and the media index. The scheduler stops it by
 * cancelling the coroutine; the writing looks at that between two chunks, removes the unfinished song and keeps the finished ones.
 */
class SplitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.getString(R.string.split_notification_finding))

    override suspend fun doWork(): Result {
        val container = (applicationContext as SongPlayerApp).container
        val mediaId = inputData.getLong(SplitWork.KEY_MEDIA_ID, -1)
        val volume = inputData.getString(SplitWork.KEY_VOLUME)
        val runId = inputData.getString(SplitWork.KEY_RUN_ID)
        val mode = inputData.getString(SplitWork.KEY_MODE)
        if (volume == null || runId == null || mode != SplitWork.MODE_ANALYZE && mode != SplitWork.MODE_WRITE) return Result.failure()
        val job = currentCoroutineContext()[Job]
        val stopped: () -> Boolean = { job?.isCancelled == true }
        val store = RunStore(RunRecordFile(File(applicationContext.filesDir, "split-runs")))
        var written = 0
        var songs = 0
        var names = emptyList<String>()
        return try {
            // Android 12 and newer refuse to start a foreground service from the background once the expedited quota is used up.
            setForeground(getForegroundInfo())
            withContext(Dispatchers.IO) {
                val info = SourceInfo.read(applicationContext, mediaId, volume) ?: throw SplitError.SourceChanged()
                val identity = SourceInfo.identity(applicationContext, mediaId, volume, info)
                val splitJob = SplitJob(store)
                var lastPermille = -1
                var lastPhase: SplitPhase? = null
                var lastStage = ""
                var lastAt = 0L
                val progress: (SplitProgress) -> Unit = { p ->
                    val permille = (p.fraction * 1000).toInt()
                    val now = System.currentTimeMillis()
                    // At most two updates a second (Android drops notifications from an app that posts too fast), but never the first of a phase or the last.
                    if (p.phase != lastPhase || p.stage != lastStage || permille == 1000 || now - lastAt >= MIN_UPDATE_MS && (permille != lastPermille || p.stage == "regions")) {
                        lastPhase = p.phase; lastStage = p.stage; lastPermille = permille; lastAt = now
                        written = p.songsWritten; songs = p.songCount
                        notify(p)
                    }
                }
                SourceReader(applicationContext, mediaId, volume).use { source ->
                    if (mode == SplitWork.MODE_ANALYZE) {
                        val folder = inputData.getString(SplitWork.KEY_FOLDER) ?: FolderRule.choose(info.relativePath, info.name, useFallback = false)
                        var decoder: PcmLoudness? = null
                        val run = try {
                            splitJob.analyze(
                                source, identity, info.name, folder, volume, { index -> PcmLoudness(index, source, stopped).also { decoder = it } },
                                runId, System.currentTimeMillis() / 1000, progress, stopped,
                            )
                        } finally {
                            decoder?.close()
                        }
                        val a = run.analysis
                        songs = run.record.songCount
                        done(applicationContext.getString(R.string.split_notification_found, songs))
                        Result.success(
                            workDataOf(
                                SplitWork.OUT_SONGS to songs, SplitWork.OUT_SCAN_MS to run.scanMs, SplitWork.OUT_ANALYZE_MS to run.analyzeMs,
                                SplitWork.OUT_DECODED_DS to (a.decodedSec * 10).toLong(), SplitWork.OUT_WINDOWS to a.windows,
                                SplitWork.OUT_REGIONS to a.regions, SplitWork.OUT_PROXY to a.proxyUsable,
                            ),
                        )
                    } else {
                        val record = store.load(runId) ?: throw SplitError.SourceChanged()
                        names = SongNames.names(record.sourceName, record.songCount)
                        if (record.source != identity) throw SplitError.SourceChanged()
                        val writer = SplitWriter(SongWriter(applicationContext, record.folder, record.volume, FolderRule.fallback(info.name), container.log), store)
                        val result = splitJob.write(record, source, writer, progress, stopped)
                        written = result.record.written.size; songs = result.record.songCount
                        done(applicationContext.getString(if (result.finished) R.string.split_notification_done else R.string.split_notification_stopped, written))
                        Result.success(workDataOf(SplitWork.OUT_WRITTEN to written, SplitWork.OUT_SONGS to songs))
                    }
                }
            }
        } catch (e: CancellationException) {
            // The scheduler stopped the job: what was written stays (the writer removed the unfinished song) and the notification says how many.
            if (mode == SplitWork.MODE_WRITE) done(applicationContext.getString(R.string.split_notification_stopped, written)) else notifications.cancel(FINAL_NOTIFICATION_ID)
            throw e
        } catch (e: Exception) {
            // A typed split error, or anything the phone throws on the way (a lost permission, a full disk, a service that may not start): the job
            // ends with a code (no names), the notification says so, and the record keeps what was written.
            container.log.event(logCodeOf(e), if (mode == SplitWork.MODE_WRITE) 1 else 0)
            done(applicationContext.getString(R.string.split_notification_failed))
            Result.failure(
                workDataOf(
                    SplitWork.OUT_ERROR to ((e as? SplitError)?.code ?: "SPLIT_FAILED"), SplitWork.OUT_WRITTEN to written, SplitWork.OUT_SONGS to songs,
                    SplitWork.OUT_CLASH to ((e as? SplitError.NameInUse)?.let { names.indexOf(it.name) + 1 } ?: 0),
                ),
            )
        }
    }

    private fun logCodeOf(e: Exception): LogCode = when (e) {
        is SplitError.NoSpace -> LogCode.SPLIT_NO_SPACE
        is SplitError.NameInUse -> LogCode.SPLIT_NAME_IN_USE
        is SplitError.FolderNotAllowed -> LogCode.SPLIT_FOLDER_NOT_ALLOWED
        else -> LogCode.SPLIT_WRITE_FAILED
    }

    private fun notify(p: SplitProgress) {
        setProgressAsync(workDataOf(SplitWork.PROGRESS_PHASE to p.phase.name, SplitWork.PROGRESS_PERMILLE to (p.fraction * 1000).toInt(), SplitWork.PROGRESS_WRITTEN to p.songsWritten, SplitWork.PROGRESS_SONGS to p.songCount, SplitWork.PROGRESS_STAGE to p.stage, SplitWork.PROGRESS_DONE to p.done, SplitWork.PROGRESS_TOTAL to p.total, SplitWork.PROGRESS_DECODED_SEC to p.decodedSec.toFloat()))
        val text = when (p.phase) {
            SplitPhase.Writing -> applicationContext.getString(R.string.split_notification_writing, minOf(p.songsWritten + 1, p.songCount), p.songCount)
            else -> applicationContext.getString(R.string.split_notification_finding)
        }
        notifications.notify(NOTIFICATION_ID, build(text, (p.fraction * 1000).toInt(), ongoing = true))
    }

    /** The last message, under an id of its own: when the foreground work ends Android removes the notification it was shown with. */
    private fun done(text: String) {
        notifications.notify(FINAL_NOTIFICATION_ID, build(text, -1, ongoing = false))
    }

    private fun foreground(text: String): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, build(text, -1, ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun build(text: String, permille: Int, ongoing: Boolean): Notification {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.split_channel_name), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_icon)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(1000, permille.coerceAtLeast(0), permille < 0)
                    addAction(R.drawable.ic_status_icon, applicationContext.getString(R.string.split_notification_cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
                }
            }
            .build()
    }

    /** Tapping the notification opens Music Player at the splitting flow (the banner on the Library, or the screen the job is on). */
    private fun openApp(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
        applicationContext, 0,
        android.content.Intent(applicationContext, app.songplayer.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SplitWork.EXTRA_OPEN_SPLIT, true),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "split"
        const val NOTIFICATION_ID = 4711
        const val FINAL_NOTIFICATION_ID = 4712
        const val MIN_UPDATE_MS = 500L
    }
}
