package app.songplayer.core.errors

/**
 * Base of the typed errors (Technical Design 9). The message is the stable [code] and nothing
 * else, so an error can be logged or copied into diagnostics without leaking a name or a path.
 */
sealed class AppError(val code: String) : Exception(code)

sealed class LibraryError(code: String) : AppError(code) {
    class PermissionDenied : LibraryError("LIBRARY_PERMISSION_DENIED")
    class QueryFailed : LibraryError("LIBRARY_QUERY_FAILED")
}

sealed class PlaybackError(code: String) : AppError(code) {
    class SourceUnreadable : PlaybackError("PLAYBACK_SOURCE_UNREADABLE")
}

sealed class OpenFileError(code: String) : AppError(code) {
    class NotMp3 : OpenFileError("OPEN_FILE_NOT_MP3")
    class Unreadable : OpenFileError("OPEN_FILE_UNREADABLE")
}

sealed class StoreError(code: String) : AppError(code) {
    class WriteFailed : StoreError("STORE_WRITE_FAILED")
    class Corrupt : StoreError("STORE_CORRUPT")

    /** The store could not be read for a reason other than damage (a lock, a read error); the file is fine. */
    class ReadFailed : StoreError("STORE_READ_FAILED")
}

/** Why a playlist file was refused (R-26). Nothing was changed; the code carries no name. */
sealed class PlaylistFileError(code: String) : AppError(code) {
    /** Not a Music Player playlist file, damaged, empty, or a version this app does not know. */
    class Invalid : PlaylistFileError("PLAYLIST_FILE_INVALID")

    /** Larger than any real playlist file; refused before it is read in full. */
    class TooLarge : PlaylistFileError("PLAYLIST_FILE_TOO_LARGE")
}

sealed class VoiceError(code: String) : AppError(code) {
    class NoMatch : VoiceError("VOICE_NO_MATCH")
}

/** Why the details of a song could not be written into its file (V1.1). The details stay in the app (Technical Design 5.8). */
enum class NotWrittenReason { NoAccess, UnsupportedTag, LowSpace, TooBigToRewrite, Unreadable }

sealed class DetailsError(code: String) : AppError(code) {
    /** Nothing was changed in the file; the reason says what the user can do (for example allow Media management). */
    class NotWritten(val reason: NotWrittenReason) : DetailsError("DETAILS_NOT_WRITTEN")

    /** A write was started and failed or did not verify; the original has been, or will be, restored from the journal. */
    class WriteFailed : DetailsError("DETAILS_WRITE_FAILED")

    /** The audio is too short (or empty) to fingerprint; the song is skipped, not retried (Technical Design 5.8). */
    class NotDecodable : DetailsError("DETAILS_NOT_DECODABLE")

    /** No network, a timeout, a server error, or a codec that could not start; the song stays pending and is tried again later. */
    class Unavailable : DetailsError("DETAILS_UNAVAILABLE")

    /** The service said "too many requests"; nothing is sent again until the next local day. */
    class QuotaReached : DetailsError("DETAILS_QUOTA_REACHED")

    /** No application key in this build, or the service refused the key; nothing is sent. */
    class NotConfigured : DetailsError("DETAILS_NOT_CONFIGURED")

    /** The answer could not be read; treated like a transient failure. */
    class BadAnswer : DetailsError("DETAILS_BAD_ANSWER")
}

/** Why a long MP3 could not be split (V2, Technical Design 5.9). The file is never changed. */
sealed class SplitError(code: String) : AppError(code) {
    /** Not an MP3, or damaged beyond the little junk the tool tolerates (under 1 %). Nothing is written. */
    class Unreadable : SplitError("SPLIT_UNREADABLE")

    /** Over the size limit; refused before the file is read. */
    class TooLarge : SplitError("SPLIT_TOO_LARGE")

    /** The cuts cannot all fall on separate frames (cannot happen with cuts 5 seconds apart; a guard, not a message). */
    class CutsTooClose : SplitError("SPLIT_CUTS_TOO_CLOSE")

    /** The original's folder takes no audio files (for example Download); [suggested] is the folder the app offers instead (`Music/<name>/`). */
    class FolderNotAllowed(val suggested: String) : SplitError("SPLIT_FOLDER_NOT_ALLOWED")

    /** A file with this name is already in the folder and is not the song that would be written (other size). Nothing was written; [name] is for the screen, never for a log. */
    class NameInUse(val name: String) : SplitError("SPLIT_NAME_IN_USE")

    /** Less free space than the songs need, or the phone ran out while writing; the finished songs are kept. */
    class NoSpace : SplitError("SPLIT_NO_SPACE")

    /** The original was changed, moved or became unreadable while its bytes were being copied. The record keeps what was written. */
    class SourceChanged : SplitError("SPLIT_SOURCE_CHANGED")

    /** A song could not be written for a reason other than space; the unfinished file was removed. */
    class WriteFailed : SplitError("SPLIT_WRITE_FAILED")
}
