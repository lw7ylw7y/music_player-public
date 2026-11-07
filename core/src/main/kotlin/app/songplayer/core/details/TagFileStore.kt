package app.songplayer.core.details

/**
 * Writes a song's title, artist and album into its file (R-27). Implemented in `app` over `MediaStore`; the rules and the
 * bytes are `core`'s ([Id3], [WriteRecovery]). A failure is a [app.songplayer.core.errors.DetailsError]: `NotWritten` when
 * nothing was changed, `WriteFailed` when a write started and was undone.
 */
interface TagFileStore {
    fun write(songKey: String, change: TagChange)

    /** The tag as the file has it now, read back (for the check after a write and for Undo). */
    fun read(songKey: String): TagFields
}
