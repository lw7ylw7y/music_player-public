package app.songplayer.core.split

/**
 * What "Ready to split" (UX Design 4.11 (K4)) shows and decides (R-40, R-46): the names the songs will get, the folder, the space the writing needs and
 * the space there is, and how many uncertain cuts were not looked at. [canStart] is false when the space is too small; nothing is written
 * before the user taps the button.
 */
class SplitSummary(
    val sourceName: String,
    val songCount: Int,
    val firstName: String,
    val lastName: String,
    /** The folder with its levels apart, for `Music › Albums`. */
    val folderParts: List<String>,
    val neededBytes: Long,
    val freeBytes: Long,
    val uncheckedCuts: Int,
) {
    val enoughSpace: Boolean get() = freeBytes >= neededBytes
    val canStart: Boolean get() = enoughSpace && songCount in 2..SplitLimits.MAX_SONGS

    companion object {
        /** The original's size plus 5 % for temporary files (Requirements R-46). */
        fun spaceNeeded(sourceBytes: Long): Long = sourceBytes + sourceBytes / 20

        fun of(record: RunRecord, sourceBytes: Long, freeBytes: Long): SplitSummary {
            val names = SongNames.names(record.sourceName, record.songCount)
            return SplitSummary(
                sourceName = record.sourceName,
                songCount = record.songCount,
                firstName = names.first().removeSuffix(".mp3"),
                lastName = names.last().removeSuffix(".mp3"),
                folderParts = record.folder.trim('/').split('/').filter { it.isNotEmpty() },
                neededBytes = spaceNeeded(sourceBytes),
                freeBytes = freeBytes,
                uncheckedCuts = CutReview.uncertain(record).size,
            )
        }
    }
}

/** How a writing run stopped before the last song (UX Design 4.11 (K5) and K6). */
enum class StopReason { Cancelled, NoSpace, SourceGone, Failed }

/**
 * The end of a writing run for "Done" (UX Design 4.11 (K6)) and the stopped message: how many songs exist now, how many of them an earlier run wrote,
 * and why it stopped (null when every song is written). [earlier] counts the songs already written when this run began.
 */
class SplitOutcome(val total: Int, val written: Int, val earlier: Int, val stopped: StopReason?) {
    val finished: Boolean get() = stopped == null && written == total
    val now: Int get() = written - earlier
    val continued: Boolean get() = earlier > 0 && finished

    companion object {
        /** Why a writing job ended early, from how WorkManager ended it and the job's error code (null when it ended well). */
        fun reasonFor(cancelled: Boolean, failed: Boolean, errorCode: String?): StopReason? = when {
            cancelled -> StopReason.Cancelled
            errorCode == "SPLIT_NO_SPACE" -> StopReason.NoSpace
            errorCode == "SPLIT_SOURCE_CHANGED" -> StopReason.SourceGone
            failed -> StopReason.Failed
            else -> null
        }

        /** From the record read back after the job ended; [earlier] is what the record held when the run started. */
        fun of(record: RunRecord?, earlier: Int, stopped: StopReason?): SplitOutcome {
            val total = record?.songCount ?: 0
            val written = record?.written?.size ?: 0
            // A job that ended with every song written is finished, whatever it reported.
            val done = record != null && record.phase == RunPhase.Done && written == total
            return SplitOutcome(total, written, minOf(earlier, written), if (done) null else stopped ?: StopReason.Failed)
        }
    }
}
