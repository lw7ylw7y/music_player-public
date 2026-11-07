package app.songplayer.library.db

import app.songplayer.core.errors.StoreError
import app.songplayer.core.log.ErrorEntry
import app.songplayer.core.log.ErrorLog
import app.songplayer.core.log.ErrorLogRules
import app.songplayer.core.log.LogCode

/**
 * [ErrorLog] on Room: each entry is inserted and the table trimmed to the newest [ErrorLogRules.CAP] in the same
 * transaction. A row whose code this version does not know (written by a newer one) is skipped when reading.
 */
class RoomErrorLog(private val db: AppDatabase, private val dao: ErrorLogDao = db.errorLogDao()) : ErrorLog {
    override fun record(code: LogCode, values: List<Int>, atEpochSec: Long) {
        try {
            db.runInTransaction(Runnable {
                dao.insert(ErrorLogEntity(code = code.name, atEpochSec = atEpochSec, numbers = values.joinToString(",")))
                dao.trim(ErrorLogRules.CAP)
            })
        } catch (e: RuntimeException) {
            throw StoreError.WriteFailed()
        }
    }

    override fun recent(): List<ErrorEntry> = try {
        dao.recent(ErrorLogRules.CAP).mapNotNull { row ->
            val code = LogCode.entries.firstOrNull { it.name == row.code } ?: return@mapNotNull null
            ErrorEntry(code, row.numbers.split(',').mapNotNull { it.toIntOrNull() })
        }
    } catch (e: RuntimeException) {
        // The message may name a table or a path; only the typed code leaves this class.
        throw StoreError.ReadFailed()
    }
}
