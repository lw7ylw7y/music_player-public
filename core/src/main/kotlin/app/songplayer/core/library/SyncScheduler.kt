package app.songplayer.core.library

/**
 * Lets one library sync run at a time. A request that arrives during a run is not lost: however many
 * arrive, exactly one more run follows (Technical Design 5.1). Thread-safe; the caller does the work.
 *
 * ```
 * if (scheduler.request()) { do { sync() } while (scheduler.finished()) }
 * ```
 */
class SyncScheduler {
    private var running = false
    private var rerun = false

    /** True when the caller should start a run now; false when a run is active (one rerun is queued). */
    @Synchronized
    fun request(): Boolean {
        if (running) {
            rerun = true
            return false
        }
        running = true
        return true
    }

    /** Call when a run ends. True when the caller should run once more. */
    @Synchronized
    fun finished(): Boolean {
        if (rerun) {
            rerun = false
            return true
        }
        running = false
        return false
    }
}
