package app.songplayer.core.split

import app.songplayer.core.errors.SplitError

/**
 * Where the new songs go (Requirements R-42, Technical Design 5.9): the original's own folder when Android takes audio files there,
 * else `Music/<original name>/`, which the user must accept first.
 */
object FolderRule {
    /** The first folder names under which Android's media index accepts audio files. */
    val AUDIO_ROOTS = listOf("Music", "Podcasts", "Audiobooks", "Alarms", "Notifications", "Ringtones", "Recordings")

    /** True when [relativePath] (for example `Music/Albums/`) is inside one of the audio roots. */
    fun isAudioFolder(relativePath: String?): Boolean =
        relativePath != null && relativePath.trim('/').substringBefore('/') in AUDIO_ROOTS

    /** The folder offered when the original's own folder takes no audio: `Music/<name without extension>/`. */
    fun fallback(originalName: String): String = "Music/${SongNames.clean(originalName.substringBeforeLast('.', originalName))}/"

    /**
     * The folder for the songs, with a trailing slash. The original's folder when it takes audio; otherwise [fallback] if the user accepted it
     * ([useFallback]), else a [SplitError.FolderNotAllowed] carrying the suggestion for the screen to ask about.
     */
    fun choose(originalRelativePath: String?, originalName: String, useFallback: Boolean): String = when {
        isAudioFolder(originalRelativePath) -> originalRelativePath!!.trim('/') + "/"
        useFallback -> fallback(originalName)
        else -> throw SplitError.FolderNotAllowed(fallback(originalName))
    }
}
