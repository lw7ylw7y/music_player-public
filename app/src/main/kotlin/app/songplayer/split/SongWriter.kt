package app.songplayer.split

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import app.songplayer.core.errors.SplitError
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.split.ByteSource
import app.songplayer.core.split.DeleteTargets
import app.songplayer.core.split.FinishedSong
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.SongCopy
import app.songplayer.core.split.SongSink
import java.io.IOException

/**
 * The only code that makes audio files in the media index and the only code that asks Android to delete them (Technical Design 3, 5.2, 5.4).
 * A song is inserted as a pending item (invisible), written, flushed to disk, checked for its size and only then published, so a kill leaves
 * no half-written song visible. [folder] is relative to the volume (`Music/Albums/`). [suggestion] is the folder offered when Android takes no
 * audio in [folder].
 */
class SongWriter(
    private val context: Context,
    private val folder: String,
    private val volume: String,
    private val suggestion: String,
    private val log: AppLog? = null,
) : SongSink {
    private val resolver = context.contentResolver
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(volume)

    override fun freeBytes(): Long {
        val directory = (context.getSystemService(Context.STORAGE_SERVICE) as StorageManager).getStorageVolume(collection)?.directory
            ?: throw SplitError.WriteFailed()
        return StatFs(directory.path).availableBytes
    }

    override fun find(name: String): FinishedSong? = query(name, MediaStore.MATCH_EXCLUDE).firstOrNull()?.let { FinishedSong(it.first, it.second) }

    override fun removePending(names: List<String>): Int {
        var gone = 0
        for (name in names) for ((id, _) in query(name, MediaStore.MATCH_ONLY)) {
            // Only this app's own unfinished items are visible here; Android shows the app no one else's.
            gone += resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
        }
        if (gone > 0) log?.event(LogCode.SPLIT_PENDING_REMOVED, gone)
        return gone
    }

    override fun write(name: String, header: ByteArray?, source: ByteSource, start: Long, end: Long, cancelled: () -> Boolean): FinishedSong? {
        val expected = (header?.size ?: 0) + (end - start)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val inserted = try {
            resolver.insert(collection, values)
        } catch (_: IllegalArgumentException) {
            log?.event(LogCode.SPLIT_FOLDER_NOT_ALLOWED)
            throw SplitError.FolderNotAllowed(suggestion)
        } catch (_: SecurityException) {
            log?.event(LogCode.SPLIT_FOLDER_NOT_ALLOWED)
            throw SplitError.FolderNotAllowed(suggestion)
        }
        val uri = inserted ?: throw SplitError.WriteFailed()
        var published = false
        try {
            // Android renames a clash to "name (1).mp3" without asking; the name check came first, and this catches the file that appeared since.
            if (displayName(uri) != name) { log?.event(LogCode.SPLIT_NAME_IN_USE); throw SplitError.NameInUse(name) }
            val size = ParcelFileDescriptor.AutoCloseOutputStream(resolver.openFileDescriptor(uri, "w") ?: throw SplitError.WriteFailed()).use { out ->
                if (!SongCopy.copy(source, start, end, header, out, cancelled)) return null
                out.flush()
                out.fd.sync()
                out.channel.size()
            }
            if (size != expected) throw SplitError.WriteFailed()
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) != 1) throw SplitError.WriteFailed()
            // Publishing may rename a clash too; the song must have the name that was checked.
            if (displayName(uri) != name) throw SplitError.NameInUse(name)
            published = true
            return FinishedSong(ContentUris.parseId(uri), size)
        } catch (e: IOException) {
            val noSpace = (e.cause as? ErrnoException)?.errno == OsConstants.ENOSPC || e.message?.contains("ENOSPC") == true ||
                try { freeBytes() < expected } catch (_: SplitError) { false }
            log?.event(if (noSpace) LogCode.SPLIT_NO_SPACE else LogCode.SPLIT_WRITE_FAILED)
            throw if (noSpace) SplitError.NoSpace() else SplitError.WriteFailed()
        } finally {
            // The unfinished file: nothing of it stays, visible or not. A failure here must not hide the error that got us here.
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }

    /**
     * Whether a song can be made in [folder] on [volume]: inserts an empty pending item and removes it again. Used by the debug check of
     * where the app may write (spike P-C), never by a run.
     */
    fun canCreateHere(): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "split-check-${System.nanoTime()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = try { resolver.insert(collection, values) } catch (_: IllegalArgumentException) { return false } catch (_: SecurityException) { return false }
        uri?.let { resolver.delete(it, null, null) }
        return uri != null
    }

    /** The request to delete the songs of [record] that still exist ([existingSizes]); see [request] for the confirmation. */
    fun removeSongsRequest(record: RunRecord, existingSizes: Map<Long, Long>, userSaidYes: Boolean): PendingIntent =
        request(DeleteTargets.songs(record, existingSizes), userSaidYes)

    /** The request to delete the original of the finished run [record]; see [request] for the confirmation. */
    fun deleteOriginalRequest(record: RunRecord, userSaidYes: Boolean): PendingIntent =
        request(listOf(DeleteTargets.original(record)), userSaidYes)

    /**
     * With the Media management access Android shows no dialog; the app's own question ([userSaidYes]) is therefore the confirmation that
     * always exists, and nothing is built without it (Technical Design 5.4). The ids come only from [DeleteTargets].
     */
    private fun request(mediaIds: List<Long>, userSaidYes: Boolean): PendingIntent {
        require(userSaidYes) { "the user has not said yes" }
        require(mediaIds.isNotEmpty()) { "nothing to delete" }
        return MediaStore.createDeleteRequest(resolver, mediaIds.map { ContentUris.withAppendedId(collection, it) })
    }

    /** The (id, size) of the files called [name] in the folder; [match] says whether pending items are excluded or the only ones shown. */
    private fun query(name: String, match: Int): List<Pair<Long, Long>> {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder, name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, match)
        }
        val found = ArrayList<Pair<Long, Long>>()
        resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE), args, null)?.use { c ->
            while (c.moveToNext()) found += c.getLong(0) to c.getLong(1)
        }
        return found
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
}
