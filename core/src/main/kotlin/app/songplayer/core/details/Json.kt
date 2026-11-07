package app.songplayer.core.details

/**
 * A small JSON reader for the lookup answer, so `core` needs no library (Android's `org.json` is not on the JVM that runs the tests).
 * Values come out as Map, List, String, Double, Boolean or null. Anything malformed, too deep or with trailing text throws
 * [IllegalArgumentException]; callers turn that into a typed error.
 */
internal object Json {
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skip()
        val v = p.value(0)
        p.skip()
        require(p.pos == text.length) { "trailing text" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skip() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "too deep" }
            require(pos < s.length) { "end" }
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "literal" }
            pos += word.length
            return v
        }

        fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("number")
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(pos < s.length) { "escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "unicode" }
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("unicode"))
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("escape")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("control character")
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skip()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skip(); out.add(value(depth + 1)); skip()
                when (next()) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("array") }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skip()
                require(peek() == '"') { "key" }
                val k = str(); skip()
                require(next() == ':') { "colon" }
                skip(); out[k] = value(depth + 1); skip()
                when (next()) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("object") }
            }
        }

        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        fun next(): Char { require(pos < s.length) { "end" }; return s[pos++] }
    }
}
