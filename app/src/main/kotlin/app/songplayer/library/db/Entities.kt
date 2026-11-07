package app.songplayer.library.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SongKeys

/** One row per MP3 in the media index: the app's copy of the metadata, never the audio. */
@Entity(tableName = "song")
data class SongEntity(
    @PrimaryKey val songKey: String,
    val mediaStoreId: Long,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    // Derived keys, written by the sync and by the recompute pass (Technical Design 5.2).
    val sortTitle: String,
    val sortArtist: String,
    val sortAlbum: String,
    val searchText: String,
)

/** One small setting; `keyerVersion` is the version of the keyer that wrote the stored keys. */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val settingKey: String,
    val value: String,
)

/** A song that failed to play. Kept apart from [SongEntity] so a playlist entry whose song left the library still shows the mark. */
@Entity(tableName = "play_failure")
data class PlayFailureEntity(
    @PrimaryKey val songKey: String,
    val failedAt: Long,
)

fun SongEntity.toSong() = Song(
    key = SongKey(songKey),
    mediaStoreId = mediaStoreId,
    fileName = fileName,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    dateAddedSec = dateAddedSec,
    dateModifiedSec = dateModifiedSec,
)

fun SongEntity.toIndexed() = IndexedSong(toSong(), SongKeys(sortTitle, sortArtist, sortAlbum, searchText))

fun IndexedSong.toEntity() = SongEntity(
    songKey = song.key.value,
    mediaStoreId = song.mediaStoreId,
    fileName = song.fileName,
    title = song.title,
    artist = song.artist,
    album = song.album,
    durationMs = song.durationMs,
    sizeBytes = song.sizeBytes,
    dateAddedSec = song.dateAddedSec,
    dateModifiedSec = song.dateModifiedSec,
    sortTitle = keys.sortTitle,
    sortArtist = keys.sortArtist,
    sortAlbum = keys.sortAlbum,
    searchText = keys.searchText,
)

/** The one saved "where I was" row (Technical Design 5.4); id is always 1. */
@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val id: Int = 1,
    val currentIndex: Int,
    val currentKey: String,
    val positionMs: Long,
    val shuffleOn: Boolean,
    val repeatMode: Int,
    val queueSource: String?,
)

/** One song of the saved queue, in list order. */
@Entity(tableName = "queue_item")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val songKey: String,
    val added: Boolean,
    val shuffleRank: Int?,
    /** The name of a file opened from another app; null for a library song. */
    val displayName: String? = null,
)

/** One logged error code (Technical Design 6): the code's name and its numbers as "1,2"; never text from the user's library. */
@Entity(tableName = "error_log")
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val atEpochSec: Long,
    val numbers: String,
)

/** What the details store knows about a song's title, artist and album (Technical Design 6): `manual`, `rejected`, and later `pending`, `applied` and the review states. */
@Entity(tableName = "details_state")
data class DetailsStateEntity(
    @PrimaryKey val songKey: String,
    val status: String,
    val checkedAt: Long,
    /** At most three small JSON entries for a song held for review (Stories H6); null otherwise. */
    val candidates: String?,
)

/** One changed field (see `DetailChange`): the overlay that the screens show until the file has it, and the Undo history. */
@Entity(tableName = "details_change", indices = [androidx.room.Index("songKey"), androidx.room.Index("batch")])
data class DetailsChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batch: Long,
    val songKey: String,
    val field: String,
    val previous: String?,
    val newValue: String?,
    val source: String,
    val atEpochSec: Long,
    /** The ordinal of `WriteState`: 0 waiting, 1 confirmed by the library, 2 failed, 3 in the file but not yet shown by the library. */
    val writeState: Int,
    val undone: Boolean,
)
