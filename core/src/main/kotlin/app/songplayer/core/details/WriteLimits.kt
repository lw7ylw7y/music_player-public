package app.songplayer.core.details

/** When a tag write is refused for lack of room (Technical Design 2.2 and 5.8; both constants are provisional). */
object WriteLimits {
    /** The new copy plus the original backup need twice what is backed up; this leaves one more as margin. */
    const val MIN_FREE_FACTOR = 3L

    /** A file larger than this (for example an unsplit compilation) is only written in place, never rewritten. */
    const val MAX_REWRITE_BYTES = 50L * 1024 * 1024

    /** What a write backs up: the tag region for an in-place write, the whole file for a rewrite. */
    fun hasRoom(freeBytes: Long, backupBytes: Long): Boolean = freeBytes >= MIN_FREE_FACTOR * backupBytes

    fun mayRewrite(fileBytes: Long): Boolean = fileBytes <= MAX_REWRITE_BYTES
}
