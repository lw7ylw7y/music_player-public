package app.songplayer

import app.songplayer.core.log.ErrorLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink
import java.util.concurrent.Executors

/**
 * Puts every logged error code into the error log that "Copy diagnostics" reads (R-21). Writes happen on one background
 * thread, in order, never on the caller's; a write that fails is dropped, because there is nowhere left to report it.
 * Events that are not errors (the start, timings) are not kept. [errorLog] is asked for on the writer thread at the first
 * error, never while the app is being created, so the database is not opened by creating it (and the app's `container`, which
 * the provider reads, is set by then). Events still queued when the process dies are lost; the thread is a daemon and is
 * never shut down.
 */
class ErrorLogSink(
    private val errorLog: () -> ErrorLog,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : LogSink {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "error-log").apply { isDaemon = true } }

    override fun write(code: LogCode, values: List<Int>) {
        if (!code.isError) return
        val at = nowSec()
        writer.execute {
            try {
                errorLog().record(code, values, at)
            } catch (e: Exception) {
                // Dropped: this is the log of last resort.
            }
        }
    }
}
