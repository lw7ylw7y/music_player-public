package app.songplayer.core.library

/**
 * Identity of a song: `<volume>:<relative path><file name>`, for example
 * `external_primary:Download/Songs/a.mp3` (Technical Design 5.1). A moved or renamed file is a new key.
 * [toString] hides the value so a key that reaches a log or an assertion message reveals no path (NFR-4).
 */
@JvmInline
value class SongKey(val value: String) {
    override fun toString() = "SongKey(***)"

    /** The storage volume the song is on (`external_primary`, or a card's name). */
    val volume: String get() = value.substringBefore(':')

    companion object {
        fun of(volume: String, relativePath: String, fileName: String) = SongKey("$volume:$relativePath$fileName")
    }
}

/**
 * One MP3 that the media index lists: the metadata the app keeps, never the audio.
 * Missing tags are null; the display fallbacks are applied when reading (R-3).
 */
data class Song(
    val key: SongKey,
    val mediaStoreId: Long,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
) {
    val displayTitle: String get() = title ?: fileName
    val displayArtist: String get() = artist ?: UNKNOWN_ARTIST
    val displayAlbum: String get() = album ?: UNKNOWN_ALBUM

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
    }
}

/** Turns what the media index returns for a tag into null when there is nothing to show. */
object TagText {
    private const val UNKNOWN_MARKER = "<unknown>"

    fun clean(raw: String?): String? {
        val text = raw?.trim()
        return if (text.isNullOrEmpty() || text == UNKNOWN_MARKER) null else text
    }
}
