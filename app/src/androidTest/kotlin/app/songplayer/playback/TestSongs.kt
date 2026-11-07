package app.songplayer.playback

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.songplayer.core.library.Song
import app.songplayer.core.library.TestMp3
import app.songplayer.core.library.song

object TestSongs {
    /**
     * A real, silent MP3 in Android's media index, so the player can really open it. [toClean] receives the action that
     * deletes it again. About 26 ms of audio per frame: 40 frames is a second.
     */
    fun seed(
        context: Context,
        title: String,
        frames: Int,
        toClean: MutableList<() -> Unit>,
        /** The file name, when a test needs a recognisable one; the media index takes the song's title from it. */
        fileName: String? = null,
        /** The file's content, when it should not be a valid MP3. */
        content: ByteArray? = null,
    ): Song {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = fileName ?: "songplayer-test-${System.nanoTime()}.mp3"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/SongPlayerTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values))
        toClean += { resolver.delete(uri, null, null) }
        resolver.openOutputStream(uri)!!.use { it.write(content ?: TestMp3.bytes(frames = frames)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return song(name, id = uri.lastPathSegment!!.toLong(), title = title, artist = "Test artist", album = "Test album")
    }
}
