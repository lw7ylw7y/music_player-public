package app.songplayer.playback

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.songplayer.core.library.Song
import app.songplayer.core.openfile.ExternalFile

internal const val EXTRA_MEDIA_STORE_ID = "mediaStoreId"
internal const val EXTRA_HAS_ARTIST = "hasArtist"
internal const val EXTRA_ADDED_TO_QUEUE = "addedToQueue"
internal const val EXTRA_PLAYING_FROM = "playingFrom"

/** Which list a queue was started from; Now Playing says "Playing from: ..." (UX Design 4.5). */
enum class PlayingFrom { Songs, Search, Playlist, File }

/** The content URI of a song in Android's media index; the app never reads the file path itself. */
fun audioUri(mediaStoreId: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)

/**
 * The player's item for a song. The media id is the song key (so the screens can tell which row is playing), the
 * artwork URI is the audio URI itself ([CoverBitmapLoader] turns it into a thumbnail), and the extras carry what the
 * screens need to draw the same cover placeholder as the lists.
 */
fun Song.toMediaItem(source: PlayingFrom? = null, addedToQueue: Boolean = false): MediaItem {
    val uri = audioUri(mediaStoreId)
    return MediaItem.Builder()
    .setMediaId(key.value)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(displayArtist)
            .setAlbumTitle(displayAlbum)
            .setDurationMs(durationMs)
            .setArtworkUri(uri)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putLong(EXTRA_MEDIA_STORE_ID, mediaStoreId)
                putBoolean(EXTRA_HAS_ARTIST, artist != null)
                putBoolean(EXTRA_ADDED_TO_QUEUE, addedToQueue)
                source?.let { putString(EXTRA_PLAYING_FROM, it.name) }
            })
            .build(),
    )
    .build()
}

/**
 * The player's item for a file opened from another app (UX Design D6): the file name as the title, no artist or album
 * tag, and no cover (there is no media-index id to ask for one, so the id is -1 and the screens draw the note).
 */
fun ExternalFile.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(key.value)
    .setUri(uri.toUri())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayName)
            .setArtist(Song.UNKNOWN_ARTIST)
            .setAlbumTitle(Song.UNKNOWN_ALBUM)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putLong(EXTRA_MEDIA_STORE_ID, -1L)
                putBoolean(EXTRA_HAS_ARTIST, false)
                putBoolean(EXTRA_ADDED_TO_QUEUE, false)
                putString(EXTRA_PLAYING_FROM, PlayingFrom.File.name)
            })
            .build(),
    )
    .build()
