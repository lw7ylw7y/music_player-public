package app.songplayer.core.split

/**
 * What a delete request may name (Requirements R-47 and R-49, Technical Design 5.9): the songs of one run that still exist, or the original of
 * a run that is finished. Nothing else in the app builds a list of files to delete.
 */
object DeleteTargets {
    /**
     * The media ids of the songs the run wrote that still exist with the size the run recorded ([existingSizes]: media id to current size);
     * one the user deleted already is skipped, and an id that now names a file of another size is not ours and is left alone.
     */
    fun songs(record: RunRecord, existingSizes: Map<Long, Long>): List<Long> {
        val sameFile = existingSizes.filter { (id, size) -> record.written.any { it.mediaId == id && it.size == size } }.keys
        return SplitRecovery.songsToRemove(record, sameFile)
    }

    /** Whether the file now at the original's media id is the file the run split (size, time and head hash), checked right before a delete request. */
    fun originalIsUnchanged(record: RunRecord, current: SourceIdentity): Boolean = current == record.source

    /** The media id of the original, only for a finished run: while songs are missing the original is the only complete copy. */
    fun original(record: RunRecord): Long {
        require(record.phase == RunPhase.Done && record.written.size == record.songCount) { "the original is deleted only after every song is written" }
        return record.source.mediaId
    }
}
