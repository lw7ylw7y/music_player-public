package app.songplayer.core.log

/** One error that was logged: its code and the numbers that came with it (counts, durations, a player error code). Never text. */
data class ErrorEntry(val code: LogCode, val values: List<Int>)

object ErrorLogRules {
    /** The error log keeps this many of the newest entries (Technical Design 6). */
    const val CAP = 50
}

/**
 * The saved error codes (`error_log`, Technical Design 6): the writer is the one log wrapper, the reader is "Copy
 * diagnostics". Implementations keep the [ErrorLogRules.CAP] newest and may be blocking: call them off the main thread.
 */
interface ErrorLog {
    fun record(code: LogCode, values: List<Int>, atEpochSec: Long)

    /** At most [ErrorLogRules.CAP] entries, newest first. */
    fun recent(): List<ErrorEntry>
}

/**
 * The text "Copy diagnostics" puts on the clipboard (R-21, NFR-4): the app version, the phone model, the Android version
 * and the recent error codes, and nothing else. Its inputs have no place for a song, artist, file or playlist name.
 */
object DiagnosticsText {
    const val TITLE = "Music Player diagnostics"

    /** [recent] is null when the error log could not be read. */
    fun build(appVersion: String, phone: String, android: String, recent: List<ErrorEntry>?): String {
        val lines = mutableListOf(
            TITLE,
            "App version: ${oneLine(appVersion)}",
            "Phone: ${oneLine(phone)}",
            "Android: ${oneLine(android)}",
        )
        when {
            recent == null -> lines += "Recent error codes: unavailable"
            recent.isEmpty() -> lines += "Recent error codes: none"
            else -> {
                lines += "Recent error codes (newest first):"
                recent.take(ErrorLogRules.CAP).forEach { lines += (listOf(it.code.name) + it.values).joinToString(" ") }
            }
        }
        return lines.joinToString("\n")
    }

    /** A value the system gave us, made safe to sit on one line (a model name with a line break cannot add a line). */
    private fun oneLine(value: String) = value.replace(Regex("""[\p{Cntrl}]+"""), " ").trim().ifEmpty { "unknown" }
}
