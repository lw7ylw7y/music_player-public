package app.songplayer.core.library

import app.songplayer.core.errors.StoreError

/** Builds a [Song] with sensible defaults so a test states only what it cares about. */
fun song(
    fileName: String,
    relativePath: String = "Music/",
    volume: String = "external_primary",
    id: Long = 1,
    title: String? = "title of $fileName",
    artist: String? = "artist",
    album: String? = "album",
    durationMs: Long = 180_000,
    sizeBytes: Long = 4_000_000,
    dateAddedSec: Long = 1_000,
    dateModifiedSec: Long = 1_000,
) = Song(
    key = SongKey.of(volume, relativePath, fileName),
    mediaStoreId = id,
    fileName = fileName,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    dateAddedSec = dateAddedSec,
    dateModifiedSec = dateModifiedSec,
)

/** A [LibrarySource] that returns a fixed list or throws what the test says. Counts its scans. */
class FakeLibrarySource(
    var songs: List<Song> = emptyList(),
    var failure: Throwable? = null,
) : LibrarySource {
    var scans = 0
        private set

    override fun scan(): List<Song> {
        scans++
        failure?.let { throw it }
        return songs
    }
}

/**
 * An in-memory [SongStore] whose [apply] is all-or-nothing, like the real transaction.
 * [failApply] makes the next apply throw before changing anything.
 */
class InMemorySongStore(initial: List<Song> = emptyList()) : SongStore {
    private val rows = LinkedHashMap<SongKey, Song>().also { m -> initial.forEach { m[it.key] = it } }
    val clearedFailures = mutableListOf<SongKey>()
    var applyCalls = 0
        private set
    var failApply = false
    var failRead = false

    override fun all(): List<Song> {
        if (failRead) throw StoreError.Corrupt()
        return rows.values.toList()
    }

    override fun apply(plan: LibraryPlan) {
        applyCalls++
        if (failApply) throw StoreError.WriteFailed()
        plan.removed.forEach { rows.remove(it) }
        (plan.added + plan.changed).forEach { rows[it.key] = it }
        clearedFailures += plan.filesChanged
    }
}
