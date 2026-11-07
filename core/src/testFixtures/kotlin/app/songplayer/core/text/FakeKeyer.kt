package app.songplayer.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * A [TextKeyer] with a tiny hand-made Chinese table, so the sorting and search rules can be tested on
 * the plain JVM. The real keyer (Android's ICU) is tested on the emulator.
 */
class FakeKeyer(override val version: String = "fake-1") : TextKeyer {
    private val pinyin = mapOf(
        '我' to "wo", '等' to "deng", '你' to "ni", '后' to "hou", '後' to "hou", '来' to "lai", '來' to "lai",
        '爱' to "ai", '愛' to "ai", '情' to "qing", '张' to "zhang", '張' to "zhang", '学' to "xue", '學' to "xue",
        '友' to "you", '西' to "xi", '安' to "an", '先' to "xian", '刘' to "liu", '劉' to "liu",
    )
    private val simplified = mapOf('張' to '张', '後' to '后', '來' to '来', '愛' to '爱', '學' to '学', '劉' to '刘')

    override fun sortKey(text: String): String {
        val out = StringBuilder()
        for (ch in Normalizer.normalize(text, Normalizer.Form.NFKC)) {
            val syllable = pinyin[ch]
            if (syllable != null) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                out.append(syllable).append(' ')
            } else {
                out.append(ch.lowercaseChar())
            }
        }
        return out.toString().trim()
    }

    override fun searchFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).map { simplified[it] ?: it }.joinToString("")
}

/** A keyer that always throws, for the fallback tests. */
class ThrowingKeyer(override val version: String = "broken-1") : TextKeyer {
    override fun sortKey(text: String): String = throw IllegalStateException("no transliterator: $text")
    override fun searchFold(text: String): String = throw IllegalStateException("no transliterator: $text")
}
