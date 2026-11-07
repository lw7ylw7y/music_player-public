package app.songplayer.library

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.songplayer.core.library.Song

/**
 * Builds Android's request to delete songs the user chose in the library (R-50). With the "Media management" access Android shows no
 * dialog of its own, so the app's own question ([userSaidYes]) is the confirmation that always exists; nothing is built without it.
 * The ids come only from [Song]s of the library, never from a path.
 */
class SongDeleter(context: Context) {
    private val resolver = context.contentResolver

    fun request(songs: List<Song>, userSaidYes: Boolean): PendingIntent {
        require(userSaidYes) { "the user has not said yes" }
        require(songs.isNotEmpty()) { "nothing to delete" }
        val uris = songs.map { ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(it.key.volume), it.mediaStoreId) }
        return MediaStore.createDeleteRequest(resolver, uris)
    }
}
