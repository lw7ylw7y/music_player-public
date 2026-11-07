package app.songplayer.core.library

/** What a sync must change in the cache. */
data class LibraryPlan(
    val added: List<Song>,
    val removed: List<SongKey>,
    /** Same key, different data (a retag, or a new media-index id). */
    val changed: List<Song>,
    /** The subset of [changed] whose file changed (size or modified time): their "Can't play" mark is cleared. */
    val filesChanged: List<SongKey>,
    val cacheWasEmpty: Boolean,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /** Songs the "N new songs found" message may announce (R-22): none on the first scan into an empty cache. */
    val newSongs: List<Song> get() = if (cacheWasEmpty) emptyList() else added
}

object LibraryDiff {
    /** Compares the cache with a fresh scan. Additions and changes keep the scan's order, removals the cache's. */
    fun diff(cache: List<Song>, scan: List<Song>): LibraryPlan {
        val cached = cache.associateBy { it.key }
        val scanned = scan.associateBy { it.key }
        val added = scan.filter { it.key !in cached }
        val removed = cache.filter { it.key !in scanned }.map { it.key }
        val changed = scan.filter { s -> cached[s.key]?.let { it != s } == true }
        val filesChanged = changed.filter { s ->
            val old = cached.getValue(s.key)
            old.sizeBytes != s.sizeBytes || old.dateModifiedSec != s.dateModifiedSec
        }.map { it.key }
        return LibraryPlan(added, removed, changed, filesChanged, cacheWasEmpty = cache.isEmpty())
    }
}
