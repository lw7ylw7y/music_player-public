package app.songplayer.text

import android.icu.text.Transliterator
import android.icu.util.VersionInfo
import app.songplayer.core.text.TextKeyer
import java.util.concurrent.ConcurrentHashMap

/**
 * [TextKeyer] on Android's built-in ICU: no dependency, no download (Technical Design 5.2). Pinyin comes from
 * `Han-Latin`, tones are dropped by `Latin-ASCII`, and Traditional characters become Simplified.
 *
 * Running ICU over whole titles took about 1.3 ms each on the emulator, too slow for a first scan of
 * thousands of songs. A library uses only a few thousand distinct non-ASCII characters, so ASCII is handled
 * directly and every other character is transliterated once and remembered. Han-Latin reads each character
 * on its own anyway (it has no word dictionary), so the result is the same.
 *
 * A [Transliterator] is not thread-safe, so its calls are serialised. It is created on first use and an
 * unavailable one throws there, which `ResilientKeyer` turns into the plain fallback.
 */
class IcuKeyer : TextKeyer {
    private val lock = Any()
    private val sortTransliterator by lazy {
        Transliterator.getInstance("Any-NFKC; Traditional-Simplified; Han-Latin; Latin-ASCII; Any-Lower")
    }
    private val foldTransliterator by lazy { Transliterator.getInstance("Any-NFKC; Traditional-Simplified; Any-Lower") }
    private val sortPieces = ConcurrentHashMap<Int, String>()
    private val foldPieces = ConcurrentHashMap<Int, String>()

    override val version: String = "icu-" + VersionInfo.ICU_VERSION.toString()

    /** Pinyin syllables of Chinese characters each stand alone between spaces, so "xi an" sorts before "xian". */
    override fun sortKey(text: String): String {
        val out = StringBuilder(text.length * 2)
        forEachCodePoint(text) { cp ->
            if (cp < ASCII_LIMIT) {
                appendCollapsingSpaces(out, cp.toChar().lowercaseChar())
            } else {
                val piece = sortPieces.getOrPut(cp) { transliterate(sortTransliterator, cp).trim() }
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                    appendCollapsingSpaces(out, ' ')
                    out.append(piece)
                    appendCollapsingSpaces(out, ' ')
                } else {
                    out.append(piece)
                }
            }
        }
        return out.toString().trim()
    }

    override fun searchFold(text: String): String {
        val out = StringBuilder(text.length)
        forEachCodePoint(text) { cp ->
            if (cp < ASCII_LIMIT) out.append(cp.toChar().lowercaseChar())
            else out.append(foldPieces.getOrPut(cp) { transliterate(foldTransliterator, cp) })
        }
        return out.toString()
    }

    private fun transliterate(transliterator: Transliterator, codePoint: Int): String =
        synchronized(lock) { transliterator.transliterate(String(Character.toChars(codePoint))) }

    private fun appendCollapsingSpaces(out: StringBuilder, ch: Char) {
        if (ch == ' ' && (out.isEmpty() || out.last() == ' ')) return
        out.append(ch)
    }

    private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            action(cp)
            i += Character.charCount(cp)
        }
    }

    private companion object {
        const val ASCII_LIMIT = 0x80
    }
}
