package app.songplayer.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.songplayer.core.errors.LibraryError
import app.songplayer.core.library.LibrarySource
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.TagText

/**
 * Reads the MP3 files from Android's media index on all external volumes.
 *
 * Without the audio permission the media index silently returns only the app's own files instead of
 * failing, so the permission is checked first and [LibraryError.PermissionDenied] is thrown explicitly.
 * [requireMusicFlag] is for the library report, which compares the count with and without the
 * index's "is music" flag (Technical Design 5.1, check A-1).
 */
class MediaStoreSource(
    private val context: Context,
    private val hasPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    private val requireMusicFlag: Boolean = false,
) : LibrarySource {

    override fun scan(): List<Song> {
        if (!hasPermission()) throw LibraryError.PermissionDenied()
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                PROJECTION,
                if (requireMusicFlag) "$MP3_ONLY AND ${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0" else MP3_ONLY,
                arrayOf(MP3_MIME),
                null,
            ) ?: throw LibraryError.QueryFailed()
            cursor.use { return readSongs(it) }
        } catch (e: LibraryError) {
            throw e
        } catch (e: SecurityException) {
            throw LibraryError.PermissionDenied()
        } catch (e: RuntimeException) {
            // The framework's message can contain file names; only the typed code leaves this class.
            throw LibraryError.QueryFailed()
        }
    }

    private fun readSongs(c: android.database.Cursor): List<Song> {
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val fileName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val volume = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
        val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

        val songs = ArrayList<Song>(c.count)
        while (c.moveToNext()) {
            val name = c.getString(fileName) ?: continue
            songs += Song(
                key = SongKey.of(c.getString(volume).orEmpty(), c.getString(path).orEmpty(), name),
                mediaStoreId = c.getLong(id),
                fileName = name,
                title = TagText.clean(c.getString(title)),
                artist = TagText.clean(c.getString(artist)),
                album = TagText.clean(c.getString(album)),
                durationMs = c.getLong(duration),
                sizeBytes = c.getLong(size),
                dateAddedSec = c.getLong(added),
                dateModifiedSec = c.getLong(modified),
            )
        }
        return songs
    }

    private companion object {
        const val MP3_MIME = "audio/mpeg"
        const val MP3_ONLY = "${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
