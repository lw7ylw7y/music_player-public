package app.songplayer.core.text

import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The text work that needs Chinese data, kept behind one interface so the ordering and matching rules in
 * `core` can be tested without it (Technical Design 5.2). The real implementation uses Android's ICU.
 */
interface TextKeyer {
    /** Changes whenever the keys this keyer produces could change (for example an operating-system update). */
    val version: String

    /** Lowercase text to sort by: pinyin with a space between syllables for Chinese, plain lowercase for Latin. */
    fun sortKey(text: String): String

    /** Text to match on: lowercase, full-width folded, Traditional Chinese turned into Simplified. */
    fun searchFold(text: String): String
}

/** Plain lowercase and width folding, no Chinese data: Chinese then sorts by code point. */
object FallbackKeyer : TextKeyer {
    override val version = "fallback-1"

    override fun sortKey(text: String) = fold(text)

    override fun searchFold(text: String) = fold(text)

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

/**
 * Uses [primary] and falls back to [fallback] when it throws, so a phone without the transliterators still
 * sorts and searches with a coarser order. Logs [LogCode.KEYER_FALLBACK] once, never the text.
 * [version] is the primary's: when the primary works again after an update, the keys are recomputed.
 */
class ResilientKeyer(
    private val primary: TextKeyer,
    private val fallback: TextKeyer,
    private val log: AppLog,
) : TextKeyer {
    private val reported = AtomicBoolean(false)

    override val version get() = primary.version

    override fun sortKey(text: String) = try {
        primary.sortKey(text)
    } catch (e: RuntimeException) {
        noteFallback()
        fallback.sortKey(text)
    }

    override fun searchFold(text: String) = try {
        primary.searchFold(text)
    } catch (e: RuntimeException) {
        noteFallback()
        fallback.searchFold(text)
    }

    private fun noteFallback() {
        if (reported.compareAndSet(false, true)) log.event(LogCode.KEYER_FALLBACK)
    }
}
