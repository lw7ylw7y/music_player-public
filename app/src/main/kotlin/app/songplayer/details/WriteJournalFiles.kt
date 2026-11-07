package app.songplayer.details

import android.system.Os
import android.system.OsConstants
import app.songplayer.core.details.JournalRecord
import app.songplayer.core.details.WriteMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/** One journal entry: what a write was about to do to which file, and the size of the backup that belongs to it. */
class JournalEntry(
    val id: String,
    val songKey: String,
    val mediaStoreId: Long,
    val record: JournalRecord,
    val backupSize: Long,
)

/**
 * The journal of tag writes (Technical Design 5.8), kept as small files next to the backups in the app's private storage, not in
 * a database: it is lost only together with the backup it describes, a version change of `app.db` cannot drop it, and the
 * entry is flushed to disk like the backup. An entry is `<id>.journal` and its backup `<id>.bak`; an entry is committed only
 * after its backup has been flushed, and a backup with no entry belongs to a write that never touched the file.
 */
class WriteJournalFiles(private val dir: File) {
    fun backupFile(id: String) = File(dir, "$id.bak")

    fun newId(): String = "w${System.nanoTime()}"

    fun prepareDir() {
        dir.mkdirs()
    }

    /** Writes the entry atomically (a temporary file, flushed, then renamed) so a half-written entry is never read. */
    fun commit(entry: JournalEntry) {
        val props = Properties().apply {
            setProperty("songKey", entry.songKey)
            setProperty("mediaStoreId", entry.mediaStoreId.toString())
            setProperty("mode", entry.record.mode.name)
            setProperty("originalSize", entry.record.originalSize.toString())
            setProperty("originalSha256", entry.record.originalSha256)
            setProperty("newSize", entry.record.newSize.toString())
            setProperty("newSha256", entry.record.newSha256)
            setProperty("backupSize", entry.backupSize.toString())
        }
        val tmp = File(dir, "${entry.id}.tmp")
        FileOutputStream(tmp).use { out ->
            props.store(out, null)
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, "${entry.id}.journal"))) throw java.io.IOException("cannot commit the journal entry")
        syncDirectory()
    }

    /** Every entry that can be read; an entry that cannot be read is left alone (never guess), and not returned. */
    fun entries(): List<JournalEntry> = (dir.listFiles { f -> f.name.endsWith(".journal") } ?: emptyArray()).mapNotNull { file ->
        try {
            val p = Properties().apply { FileInputStream(file).use { load(it) } }
            JournalEntry(
                id = file.name.removeSuffix(".journal"),
                songKey = p.getProperty("songKey"),
                mediaStoreId = p.getProperty("mediaStoreId").toLong(),
                record = JournalRecord(
                    WriteMode.valueOf(p.getProperty("mode")),
                    p.getProperty("originalSize").toLong(), p.getProperty("originalSha256"),
                    p.getProperty("newSize").toLong(), p.getProperty("newSha256"),
                ),
                backupSize = p.getProperty("backupSize").toLong(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Removes the entry first and then its backup: a crash in between leaves an orphan backup, never an entry without one. */
    fun delete(id: String) {
        File(dir, "$id.journal").delete()
        backupFile(id).delete()
        File(dir, "$id.tmp").delete()
        syncDirectory()
    }

    /** Backups and temporary files with no entry belong to a write that never touched the file; they are only clutter. */
    fun sweepOrphans() {
        for (f in dir.listFiles() ?: return) {
            val id = f.name.substringBeforeLast('.')
            val hasEntry = File(dir, "$id.journal").exists()
            if ((f.name.endsWith(".bak") && !hasEntry) || f.name.endsWith(".tmp")) f.delete()
        }
    }

    private fun syncDirectory() {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            // Best effort: the entry file itself has been flushed.
        }
    }
}
