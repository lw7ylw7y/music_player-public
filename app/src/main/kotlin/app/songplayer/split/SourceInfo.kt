package app.songplayer.split

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.songplayer.core.split.SourceIdentity
import java.security.MessageDigest

/** What the media index says about the file being split, and the identity a run record keeps to tell later that it is still the same file. */
class SourceInfo(val name: String, val relativePath: String?, val size: Long, val modifiedSec: Long) {
    companion object {
        /** The facts of media item [mediaId] on [volume], or null when it is gone. */
        fun read(context: Context, mediaId: Long, volume: String): SourceInfo? {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), mediaId)
            val columns = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED)
            return context.contentResolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) SourceInfo(it.getString(0) ?: return null, it.getString(1), it.getLong(2), it.getLong(3)) else null
            }
        }

        /** The identity of the file: its index facts and a hash of its first 64 KB (Technical Design 5.3). */
        fun identity(context: Context, mediaId: Long, volume: String, info: SourceInfo): SourceIdentity {
            val head = ByteArray(minOf(HEAD_BYTES.toLong(), info.size).toInt())
            SourceReader(context, mediaId, volume).use { reader ->
                var got = 0
                while (got < head.size) { val n = reader.read(got.toLong(), head, got, head.size - got); if (n <= 0) break; got += n }
            }
            return SourceIdentity(mediaId, info.size, info.modifiedSec, MessageDigest.getInstance("SHA-256").digest(head).joinToString("") { "%02x".format(it) })
        }

        /** The sizes of those of [mediaIds] that still exist on [volume] (a song the user deleted is missing), for `SplitRecovery`. */
        fun existingSizes(context: Context, volume: String, mediaIds: Collection<Long>): Map<Long, Long> {
            if (mediaIds.isEmpty()) return emptyMap()
            val found = HashMap<Long, Long>()
            val args = mediaIds.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume), arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media._ID} IN (${args.joinToString(",") { "?" }})", args, null,
            )?.use { while (it.moveToNext()) found[it.getLong(0)] = it.getLong(1) }
            return found
        }

        private const val HEAD_BYTES = 64 * 1024
    }
}
