package app.songplayer.core.details

import java.security.MessageDigest

/** How a write is carried out; the journal keeps it so that a restore knows what to put back. */
enum class WriteMode { InPlace, Rewrite }

/**
 * What the journal row of a write says (Technical Design 5.8): the file as it was, and as it is meant to become. The
 * original bytes themselves (the tag region for [WriteMode.InPlace], the whole file for [WriteMode.Rewrite]) are a backup file
 * in the app's private storage; the row is committed only after that file has been flushed to disk.
 */
class JournalRecord(
    val mode: WriteMode,
    val originalSize: Long,
    val originalSha256: String,
    val newSize: Long,
    val newSha256: String,
)

enum class RecoveryAction {
    /** The file is as it was: the write never started or was undone. Clear the row. */
    NothingToDo,

    /** The file is as intended: the write had finished. Clear the row. */
    Complete,

    /** Neither: the write was cut off. Put the original back, then clear the row. */
    Restore,
}

/** The file of one journal row, as the recovery sees it. [restore] throws `NotWritten` when the app has no write access. */
interface RecoveryTarget {
    fun state(): FileState
    fun restore()
}

class FileState(val size: Long, val sha256: String)

enum class RecoveryOutcome {
    /** The row can be deleted (and its backup). */
    Cleared,

    /** The file could not be restored now (no write access): keep the row and the backup, try again later (R-27). */
    Kept,
}

object WriteRecovery {
    /** Runs the recovery of one journal row against its file. */
    fun recover(record: JournalRecord, target: RecoveryTarget): RecoveryOutcome {
        val state = target.state()
        return when (decide(record, state.size, state.sha256)) {
            RecoveryAction.NothingToDo, RecoveryAction.Complete -> RecoveryOutcome.Cleared
            RecoveryAction.Restore -> try {
                target.restore()
                RecoveryOutcome.Cleared
            } catch (e: app.songplayer.core.errors.DetailsError.NotWritten) {
                RecoveryOutcome.Kept
            }
        }
    }

    /** Decides what to do with a journal row, from the file's size and hash now (streamed hash in the app). */
    fun decide(record: JournalRecord, currentSize: Long, currentSha256: String): RecoveryAction = when {
        currentSize == record.originalSize && currentSha256 == record.originalSha256 -> RecoveryAction.NothingToDo
        currentSize == record.newSize && currentSha256 == record.newSha256 -> RecoveryAction.Complete
        else -> RecoveryAction.Restore
    }

    /** Puts the backup back, in memory (the app does the same with streams). */
    fun restore(current: ByteArray, backup: ByteArray, mode: WriteMode): ByteArray = when (mode) {
        WriteMode.InPlace -> current.copyOf().also { backup.copyInto(it) }
        WriteMode.Rewrite -> backup.copyOf()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
