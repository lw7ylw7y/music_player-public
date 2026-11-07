package app.songplayer.core.library

import app.songplayer.core.errors.AppError
import app.songplayer.core.errors.LibraryError

/** Reads the media index. Returns only MP3 files. Throws [LibraryError] (permission refused, query failed). */
interface LibrarySource {
    fun scan(): List<Song>
}

/** The app's copy of the song metadata. */
interface SongStore {
    fun all(): List<Song>

    /**
     * Applies the plan in one transaction: all of it or none. Removes the rows in `removed`, writes `added`
     * and `changed`, and clears the "Can't play" mark of `filesChanged`. Throws `StoreError` on failure.
     */
    fun apply(plan: LibraryPlan)
}

sealed interface SyncResult {
    data class Success(val plan: LibraryPlan) : SyncResult
    data class Failure(val error: AppError) : SyncResult
}

/**
 * One library sync: scan, diff against the cache, apply. Any failure leaves the cache as it was and
 * comes back as a typed error whose message is a code only (Technical Design 5.1).
 */
class LibrarySync(private val source: LibrarySource, private val store: SongStore) {
    fun run(): SyncResult {
        val scan = try {
            source.scan()
        } catch (e: LibraryError) {
            return SyncResult.Failure(e)
        } catch (e: RuntimeException) {
            // The original message may contain a file name or path; it is dropped on purpose.
            return SyncResult.Failure(LibraryError.QueryFailed())
        }
        return try {
            val plan = LibraryDiff.diff(store.all(), scan)
            if (!plan.isEmpty) store.apply(plan)
            SyncResult.Success(plan)
        } catch (e: AppError) {
            SyncResult.Failure(e)
        }
    }
}
