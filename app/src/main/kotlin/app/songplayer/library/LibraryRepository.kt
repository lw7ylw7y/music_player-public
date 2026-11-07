package app.songplayer.library

import app.songplayer.core.errors.AppError
import app.songplayer.core.errors.LibraryError
import app.songplayer.core.library.LibrarySync
import app.songplayer.core.library.NewSongsNotice
import app.songplayer.core.library.SyncResult
import app.songplayer.core.library.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs library syncs off the main thread, one at a time, with exactly one rerun when requests arrive
 * during a run ([SyncScheduler]). [scope] belongs to the application, so a rotation cannot cancel a sync.
 */
class LibraryRepository(
    private val sync: LibrarySync,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    /** Runs before every sync; it brings the stored sort and search keys up to date with the keyer. */
    private val beforeSync: () -> Unit = {},
    /** Told how many new songs each successful sync found (R-22). */
    private val newSongs: NewSongsNotice = NewSongsNotice(),
    /** Runs after every successful sync (the song details stop overlaying what the files now have). It must not throw. */
    private val afterSync: () -> Unit = {},
) {
    private val scheduler = SyncScheduler()
    private val _lastResult = MutableStateFlow<SyncResult?>(null)

    /** The result of the most recent finished sync, or null before the first one. */
    val lastResult: StateFlow<SyncResult?> = _lastResult

    private val _syncing = MutableStateFlow(false)

    /** True from a request until the last queued run has finished; the Library shows its progress bar. */
    val syncing: StateFlow<Boolean> = _syncing

    fun requestSync() {
        // The scheduler's own lock is held while the flag changes, so a request cannot slip in between
        // "the last run ended" and "syncing is false" and have its flag overwritten.
        synchronized(scheduler) {
            if (!scheduler.request()) return
            _syncing.value = true
        }
        scope.launch(dispatcher) {
            do {
                val result = try {
                    beforeSync()
                    sync.run()
                } catch (e: AppError) {
                    SyncResult.Failure(e)
                } catch (e: RuntimeException) {
                    // Whatever went wrong, the scheduler must be released below; the message is dropped (NFR-4).
                    SyncResult.Failure(LibraryError.QueryFailed())
                }
                if (result is SyncResult.Success) {
                    newSongs.add(result.plan.newSongs.size)
                    afterSync()
                }
                _lastResult.value = result
                val again = synchronized(scheduler) {
                    scheduler.finished().also { if (!it) _syncing.value = false }
                }
            } while (again)
        }
    }
}
