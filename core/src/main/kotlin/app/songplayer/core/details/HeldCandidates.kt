package app.songplayer.core.details

/** The small JSON text stored for a song held for review (Technical Design 6): at most three candidates. */
object HeldCandidates {
    fun encode(candidates: List<Candidate>): String = candidates.joinToString(",", "[", "]") { c ->
        "{\"t\":${q(c.title)},\"a\":${c.artists.joinToString(",", "[", "]") { q(it) }},\"d\":${c.durationS ?: -1},\"s\":${c.score}," +
            "\"g\":${c.albums.joinToString(",", "[", "]") { "[${q(it.title)},${q(it.type)}]" }}}"
    }

    /** Reads what [encode] wrote; anything else gives an empty list (a damaged row is treated as holding nothing). */
    fun decode(text: String): List<Candidate> = try {
        (Json.parse(text) as List<*>).map { e ->
            val m = e as Map<*, *>
            Candidate(
                title = m["t"] as String,
                artists = (m["a"] as List<*>).map { it as String },
                durationS = (m["d"] as Double).toInt().takeIf { it >= 0 },
                score = m["s"] as Double,
                albums = (m["g"] as List<*>).map { g -> (g as List<*>).let { ReleaseGroup(it[0] as String, it[1] as String) } },
            )
        }
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
