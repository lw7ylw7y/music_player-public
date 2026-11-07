package app.songplayer.core.log

/**
 * Every event the app may log. A code carries no song, file or playlist name (NFR-4). [isError] is false for events that
 * only report that something happened or how long it took; only errors go into the error log that "Copy diagnostics"
 * reads (R-21).
 */
enum class LogCode(val isError: Boolean = true) {
    APP_START(isError = false),
    KEYER_FALLBACK,
    RESUME_WRITE_FAILED,
    SETTING_READ_FAILED,
    SETTING_WRITE_FAILED,
    /** Debug builds only (NFR-1): milliseconds from a tap on a song to the first sound; and to build the player's queue. */
    PLAY_TAP_TO_SOUND_MS(isError = false),
    PLAY_QUEUE_BUILD_MS(isError = false),
    /** The player service did not accept the connection of the screens. */
    PLAYBACK_CONNECT_FAILED,
    /** A song could not be played; the value is the player's numeric error code (no name, no path). */
    PLAYBACK_ITEM_FAILED,
    /** The "Can't play" mark could not be written or cleared. */
    PLAY_FAILURE_WRITE_FAILED,
    /** The "Can't play" marks could not be read, so the lists show none. */
    PLAY_FAILURE_READ_FAILED,
    /** The saved queue could not be read at the start; the app starts with an empty queue. */
    RESUME_READ_FAILED,
    /** The playlists could not be read; the value is 1 when the file is damaged, 0 for another reason. */
    PLAYLIST_READ_FAILED,
    /** A playlist change could not be saved; the value is 1 when the file is damaged, 0 for another reason. */
    PLAYLIST_WRITE_FAILED,
    /** A playlist export (0) or import (1) failed or was refused: the value after it is 0 for a refused file, 1 for a read or write error, 2 for the damaged database, 3 when only the song-details record could not be restored. */
    PLAYLIST_FILE_FAILED,
    /** A file opened from another app was refused; the value is 0 when it is not an MP3 and 1 when it could not be read. */
    OPEN_FILE_REJECTED,
    /** The home-screen widget could not be drawn (0) or its saved song could not be read (1); it keeps what it shows. */
    WIDGET_FAILED,

    /** A tag write was not made (the value is the reason's number: 0 access, 1 unsupported tag, 2 low space, 3 too big, 4 unreadable). */
    DETAILS_NOT_WRITTEN,

    /** A tag write started and failed or did not verify, and the file was restored (1) or could not be restored yet (0). */
    DETAILS_WRITE_FAILED,

    /** Recovery at start found a cut-off write: 1 when it was put right, 0 when it must wait (no write access). */
    DETAILS_RECOVERY,

    /** A bookkeeping step of the song details failed: 0 a read, 1 a write, 2 the playlists' stored size after a rewrite, 3 confirming written changes. */
    DETAILS_STORE_FAILED,

    /** A lookup did not give an answer: 0 the service or network is unavailable, 1 quota reached, 2 unreadable answer, 3 no usable key. */
    DETAILS_LOOKUP_FAILED,

    /** A song could not be turned into a fingerprint: 0 not decodable (skipped for good), 1 the codec or memory failed (tried again later). */
    DETAILS_DECODE_FAILED,
    /** Splitting (V2): no values; never a name or a path. */
    SPLIT_WRITE_FAILED,
    SPLIT_NO_SPACE,
    SPLIT_NAME_IN_USE,
    SPLIT_FOLDER_NOT_ALLOWED,
    /** A leftover unfinished song of an earlier run was removed; the value is how many. */
    SPLIT_PENDING_REMOVED(isError = false),
    /** The user deleted songs from the phone; the value is how many. */
    SONGS_DELETED(isError = false),
    /** Android's delete request for songs could not be made; the value is how many songs. */
    SONGS_DELETE_FAILED,
}

/** Where events go (Logcat now, the `error_log` table in a later story). */
interface LogSink {
    fun write(code: LogCode, values: List<Int>)
}

/**
 * The only way the app logs. It takes a [LogCode] and integers (counts, durations), never text,
 * so private content cannot reach a log through it (Technical Design 11).
 */
class AppLog(private val sinks: List<LogSink>) {
    fun event(code: LogCode, vararg values: Int) {
        val list = values.toList()
        sinks.forEach { it.write(code, list) }
    }
}
