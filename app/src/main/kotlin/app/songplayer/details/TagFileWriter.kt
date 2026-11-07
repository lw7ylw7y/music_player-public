package app.songplayer.details

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import app.songplayer.core.details.FileState
import app.songplayer.core.details.Id3
import app.songplayer.core.details.JournalRecord
import app.songplayer.core.details.RecoveryOutcome
import app.songplayer.core.details.RecoveryTarget
import app.songplayer.core.details.TagChange
import app.songplayer.core.details.TagFields
import app.songplayer.core.details.TagFileStore
import app.songplayer.core.details.WriteLimits
import app.songplayer.core.details.WriteMode
import app.songplayer.core.details.WritePlan
import app.songplayer.core.details.WriteRecovery
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import app.songplayer.core.library.Song
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.playback.audioUri
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where a test can make a write fail on purpose (the safety-critical path needs to be exercised); the app passes none. */
interface WriteFaults {
    /** Called once the file is open for writing (a rewrite has truncated it) and before any byte is written. */
    fun afterOpened() {}

    /** Called after the file has been written and before it is checked. */
    fun afterApply() {}

    /** Called before a backup is copied back over the file. */
    fun beforeRestore() {}
}

/**
 * Writes the title, artist and album into a song's file (R-27), through `MediaStore`, one song at a time (Technical Design 5.8).
 * The order is the design's: the original bytes are copied to the app's private storage and flushed, the journal entry is
 * committed and flushed, only then the file is touched; afterwards the whole file is read back and must equal the intended
 * bytes. On any failure the original is put back at once, or at the next start ([recover]); a restore is itself checked, and
 * the entry and its backup are deleted only when the file is verifiably in a known state. The user's audio is never
 * changed: only the tag at the start of the file differs (the rules and the bytes are `core`'s [Id3]).
 *
 * Blocking: call it off the main thread. [write] and [recover] never run at the same time (one lock).
 */
class TagFileWriter(
    private val context: Context,
    private val songs: (String) -> Song?,
    private val playlistSizeChanged: (songKey: String, sizeBytes: Long) -> Unit,
    private val log: AppLog,
    private val freeBytes: () -> Long = { StatFs(context.filesDir.path).availableBytes },
    private val maxRewriteBytes: Long = WriteLimits.MAX_REWRITE_BYTES,
    private val faults: WriteFaults = object : WriteFaults {},
) : TagFileStore {
    private val journal = WriteJournalFiles(File(context.filesDir, "details-journal"))
    private val lock = Any()

    override fun read(songKey: String): TagFields {
        val uri = uriOf(songKey) ?: throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
        return try {
            Id3.fields(readTag(uri))
        } catch (e: SecurityException) {
            throw DetailsError.NotWritten(NotWrittenReason.NoAccess)
        } catch (e: IOException) {
            throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
        }
    }

    override fun write(songKey: String, change: TagChange) = synchronized(lock) {
        val song = songs(songKey) ?: throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
        val uri = audioUri(song.mediaStoreId)
        // An earlier write of this song that was cut off must be settled first, never overwritten (its backup may be the only good copy).
        for (earlier in journal.entries().filter { it.songKey == songKey }) {
            if (settle(earlier) == RecoveryOutcome.Kept) throw refused(NotWrittenReason.NoAccess)
        }
        val (existing, size) = try {
            readTag(uri) to fileSize(uri)
        } catch (e: SecurityException) {
            throw refused(NotWrittenReason.NoAccess)
        } catch (e: IOException) {
            throw refused(NotWrittenReason.Unreadable)
        }
        val plan = try {
            Id3.plan(existing, change)
        } catch (e: DetailsError.NotWritten) {
            throw refused(e.reason)
        }
        val rewrite = plan is WritePlan.Rewrite
        if (rewrite && size > maxRewriteBytes) throw refused(NotWrittenReason.TooBigToRewrite)
        val backupBytes = if (plan is WritePlan.InPlace) plan.tag.size.toLong() else size
        if (!WriteLimits.hasRoom(freeBytes(), backupBytes)) throw refused(NotWrittenReason.LowSpace)
        // Opening for writing without truncating tells whether Android lets us change this file; nothing is changed yet.
        try {
            open(uri, "rw").close()
        } catch (e: SecurityException) {
            throw refused(NotWrittenReason.NoAccess)
        } catch (e: IOException) {
            throw refused(NotWrittenReason.Unreadable)
        }

        journal.prepareDir()
        val id = journal.newId()
        val backup = journal.backupFile(id)
        val entry: JournalEntry
        try {
            val prepared = prepare(uri, plan, size, backup)
            entry = JournalEntry(id, songKey, song.mediaStoreId, prepared.first, prepared.second)
            journal.commit(entry)
        } catch (e: Exception) {
            journal.delete(id) // nothing has touched the file yet
            throw refused(if (e.message?.contains("ENOSPC") == true) NotWrittenReason.LowSpace else NotWrittenReason.Unreadable)
        }
        try {
            apply(uri, plan, backup)
            faults.afterApply()
            val now = streamState(uri)
            if (now.size != entry.record.newSize || now.sha256 != entry.record.newSha256) throw IOException("verification failed")
        } catch (e: Exception) {
            // Something went wrong, perhaps only the check. A file that holds exactly the intended bytes is a finished write.
            val finished = try { streamState(uri).let { it.size == entry.record.newSize && it.sha256 == entry.record.newSha256 } } catch (inner: Exception) { false }
            if (!finished) {
                val outcome = try { settle(entry) } catch (inner: Exception) { RecoveryOutcome.Kept }
                log.event(LogCode.DETAILS_WRITE_FAILED, if (outcome == RecoveryOutcome.Cleared) 1 else 0)
                throw DetailsError.WriteFailed()
            }
        }
        journal.delete(id)
        if (rewrite) {
            try {
                playlistSizeChanged(songKey, entry.record.newSize)
            } catch (e: Exception) {
                log.event(LogCode.DETAILS_STORE_FAILED, 2) // the file is written; only the playlists' stored size is stale
            }
        }
    }

    /**
     * Puts right every write that was cut off (Technical Design 5.8): at every process start and at the start of each worker
     * run. A file that cannot be restored now (no write access) keeps its entry and backup for the next time. It never throws.
     */
    fun recover() {
        synchronized(lock) {
            try {
                journal.sweepOrphans()
                for (entry in journal.entries()) {
                    val outcome = try { settle(entry) } catch (e: Exception) { RecoveryOutcome.Kept }
                    log.event(LogCode.DETAILS_RECOVERY, if (outcome == RecoveryOutcome.Cleared) 1 else 0)
                }
            } catch (e: Exception) {
                log.event(LogCode.DETAILS_RECOVERY, 0)
            }
        }
    }

    // ---- steps ----------------------------------------------------------------------------------------

    /** Decides and acts on one entry; deletes it (and its backup) only when the file is verifiably in a known state or gone. */
    private fun settle(entry: JournalEntry): RecoveryOutcome {
        val uri = audioUri(entry.mediaStoreId)
        val backup = journal.backupFile(entry.id)
        val target = object : RecoveryTarget {
            override fun state() = streamState(uri)
            override fun restore() {
                if (!backup.isFile || backup.length() != entry.backupSize) throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
                try {
                    faults.beforeRestore()
                    ParcelFileDescriptor.AutoCloseOutputStream(open(uri, if (entry.record.mode == WriteMode.Rewrite) "rwt" else "rw")).use { out ->
                        FileInputStream(backup).use { copy(it, out) }
                        out.fd.sync()
                    }
                } catch (e: SecurityException) {
                    throw DetailsError.NotWritten(NotWrittenReason.NoAccess)
                } catch (e: IOException) {
                    throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
                }
                // A restore is checked like a write: only a file that equals the original lets the backup go.
                val now = streamState(uri)
                if (now.size != entry.record.originalSize || now.sha256 != entry.record.originalSha256) {
                    throw DetailsError.NotWritten(NotWrittenReason.Unreadable)
                }
            }
        }
        val outcome = try {
            WriteRecovery.recover(entry.record, target)
        } catch (e: FileNotFoundException) {
            RecoveryOutcome.Cleared // the song is gone from the phone: there is no file to put right
        }
        if (outcome == RecoveryOutcome.Cleared) journal.delete(entry.id)
        return outcome
    }

    /** One pass over the original: its hash, the hash of the intended file, and the backup copy (flushed to disk). */
    private fun prepare(uri: Uri, plan: WritePlan, size: Long, backup: File): Pair<JournalRecord, Long> {
        val original = MessageDigest.getInstance("SHA-256")
        val intended = MessageDigest.getInstance("SHA-256")
        val newBytes: ByteArray
        val skip: Long
        val mode: WriteMode
        val newSize: Long
        when (plan) {
            is WritePlan.InPlace -> { newBytes = plan.tag; skip = plan.tag.size.toLong(); mode = WriteMode.InPlace; newSize = size }
            is WritePlan.Rewrite -> { newBytes = plan.newTag; skip = plan.audioOffset.toLong(); mode = WriteMode.Rewrite; newSize = plan.newTag.size + (size - plan.audioOffset) }
        }
        intended.update(newBytes)
        val backupLimit = if (mode == WriteMode.InPlace) skip else Long.MAX_VALUE
        var backedUp = 0L
        (context.contentResolver.openInputStream(uri) ?: throw IOException("cannot read")).use { input ->
            FileOutputStream(backup).use { out ->
                val buffer = ByteArray(64 * 1024)
                var pos = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    original.update(buffer, 0, n)
                    if (pos + n > skip) { val from = (skip - pos).coerceAtLeast(0).toInt(); intended.update(buffer, from, n - from) }
                    if (pos < backupLimit) { val count = minOf(n.toLong(), backupLimit - pos).toInt(); out.write(buffer, 0, count); backedUp += count }
                    pos += n
                }
                out.fd.sync()
            }
        }
        return JournalRecord(mode, size, hex(original.digest()), newSize, hex(intended.digest())) to backedUp
    }

    private fun apply(uri: Uri, plan: WritePlan, backup: File) {
        when (plan) {
            is WritePlan.InPlace -> ParcelFileDescriptor.AutoCloseOutputStream(open(uri, "rw")).use { out ->
                faults.afterOpened()
                out.write(plan.tag)
                out.fd.sync()
            }
            // "rwt" truncates: from here the journal is what protects the file.
            is WritePlan.Rewrite -> ParcelFileDescriptor.AutoCloseOutputStream(open(uri, "rwt")).use { out ->
                faults.afterOpened()
                out.write(plan.newTag)
                FileInputStream(backup).use { input -> skipFully(input, plan.audioOffset.toLong()); copy(input, out) }
                out.fd.sync()
            }
        }
    }

    // ---- small helpers -------------------------------------------------------------------------------

    private fun uriOf(songKey: String): Uri? = songs(songKey)?.let { audioUri(it.mediaStoreId) }

    private fun open(uri: Uri, mode: String): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, mode) ?: throw IOException("cannot open")

    private fun fileSize(uri: Uri): Long = open(uri, "r").use { it.statSize }.also { if (it < 0) throw IOException("unknown size") }

    private fun readTag(uri: Uri) = (context.contentResolver.openInputStream(uri) ?: throw IOException("cannot read")).use { input ->
        val head = readUpTo(input, 10)
        val header = Id3.readHeader(head) ?: return@use null
        val rest = readUpTo(input, header.bodySize)
        Id3.parse(header, head + rest)
    }

    private fun streamState(uri: Uri): FileState {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        (context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("gone")).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size += n }
        }
        return FileState(size, hex(digest.digest()))
    }

    private fun refused(reason: NotWrittenReason): DetailsError.NotWritten {
        log.event(LogCode.DETAILS_NOT_WRITTEN, reason.ordinal)
        return DetailsError.NotWritten(reason)
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var got = 0
        while (got < count) { val n = input.read(out, got, count - got); if (n < 0) break; got += n }
        return out.copyOf(got)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) { val n = input.skip(left); if (n <= 0) throw IOException("short backup"); left -= n }
    }

    private fun copy(input: InputStream, out: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
