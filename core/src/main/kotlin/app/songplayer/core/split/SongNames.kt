package app.songplayer.core.split

/** The file names of the new songs: `<original name without its extension> <number>.mp3`, numbered as the command-line tool does. */
object SongNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u007f]")
    private const val MAX_NAME_BYTES = 255

    /** The names for [count] songs: at least two digits, as many as the count needs (01 to 99, 001 to 120), so they sort in order. */
    fun names(originalName: String, count: Int): List<String> {
        val width = maxOf(2, count.toString().length)
        val stem = fit(clean(originalName.substringBeforeLast('.', originalName)), width)
        return List(count) { "$stem ${(it + 1).toString().padStart(width, '0')}.mp3" }
    }

    /** Takes out what a file name cannot hold and the dots and spaces a name cannot end with; never empty. */
    fun clean(stem: String): String = stem.replace(ILLEGAL, "").trim().trimEnd('.', ' ').ifEmpty { "Song" }

    /** Cuts the stem so that the whole name stays within 255 bytes of UTF-8 (Chinese characters take three). */
    private fun fit(stem: String, width: Int): String {
        val room = MAX_NAME_BYTES - " ".length - width - ".mp3".length
        var out = stem
        while (out.toByteArray(Charsets.UTF_8).size > room) out = out.substring(0, out.length - Character.charCount(out.codePointBefore(out.length)))
        return out.trimEnd('.', ' ').ifEmpty { "Song" }
    }
}
