package app.songplayer.core.playback

/**
 * Which player errors mean "this song cannot be played" (R-12) and which mean something else, such as the audio
 * output failing or the permission being taken away. Only the first kind marks the song "Can't play" and skips it;
 * the others leave the queue alone and stop, because marking and skipping would blame every song for one fault.
 * The numbers are Media3's `PlaybackException.ERROR_CODE_*` values; `core` cannot import them.
 */
object PlaybackErrors {
    private val SONG_PROBLEM_CODES = setOf(
        2000, // IO_UNSPECIFIED: the file could not be read
        2005, // IO_FILE_NOT_FOUND: the file is gone
        2008, // IO_READ_POSITION_OUT_OF_RANGE: the file is shorter than its index says
        3001, // PARSING_CONTAINER_MALFORMED: not valid MP3 data
        3003, // PARSING_CONTAINER_UNSUPPORTED
        4003, // DECODING_FAILED
        4004, // DECODING_FORMAT_EXCEEDS_CAPABILITIES
        4005, // DECODING_FORMAT_UNSUPPORTED
    )

    fun isTheSongsFault(errorCode: Int): Boolean = errorCode in SONG_PROBLEM_CODES
}
