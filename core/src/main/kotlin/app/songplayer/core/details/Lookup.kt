package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import java.net.URLEncoder

/** One album a recording appears on, as the service names it. [type] is the release group's primary type ("Album", "Single", ...). */
data class ReleaseGroup(val title: String, val type: String)

/** One possible identity of a song. [score] is the service's own number for the fingerprint match (0 to 1). */
data class Candidate(
    val title: String,
    val artists: List<String>,
    val durationS: Int?,
    val score: Double,
    val albums: List<ReleaseGroup>,
)

/** The seam to the online music database (Technical Design 5.8). Throws [DetailsError] only; an answer with no match is an empty list. */
interface LookupClient {
    /** False when this build has no usable key: nothing can be asked, so nothing should be decoded either. */
    val configured: Boolean get() = true

    /** [durationS] is the length of the whole song in seconds, not of the part that was fingerprinted. */
    suspend fun lookup(fingerprint: String, durationS: Int): List<Candidate>
}

/** What goes into the request, and how the answer is read. Pure, so both are tested without a network. */
object AcoustId {
    const val URL = "https://api.acoustid.org/v2/lookup"

    /** The form body: the four fields of R-19 and nothing else. */
    fun requestBody(key: String, durationS: Int, fingerprint: String): String =
        "client=${enc(key)}&duration=$durationS&fingerprint=${enc(fingerprint)}&meta=recordings+releasegroups+compress"

    /**
     * Reads the service's JSON answer. Candidates come best score first; a recording that appears in several results keeps its best
     * score. Throws [DetailsError.NotConfigured], [DetailsError.QuotaReached], [DetailsError.Unavailable] or [DetailsError.BadAnswer].
     */
    fun parseAnswer(text: String): List<Candidate> {
        val root = try { Json.parse(text) } catch (_: IllegalArgumentException) { throw DetailsError.BadAnswer() } as? Map<*, *>
            ?: throw DetailsError.BadAnswer()
        when (root["status"]) {
            "ok" -> {}
            "error" -> throw errorFor((root["error"] as? Map<*, *>)?.get("code") as? Double)
            else -> throw DetailsError.BadAnswer()
        }
        val results = root["results"] as? List<*> ?: throw DetailsError.BadAnswer()
        val best = LinkedHashMap<String, Candidate>()
        for (r in results) {
            val result = r as? Map<*, *> ?: throw DetailsError.BadAnswer()
            val score = result["score"] as? Double ?: throw DetailsError.BadAnswer()
            val recordings = result["recordings"] as? List<*> ?: continue
            for (rec in recordings) {
                val m = rec as? Map<*, *> ?: throw DetailsError.BadAnswer()
                val title = (m["title"] as? String)?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artists = (m["artists"] as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("name") as? String)?.trim() }.filter { it.isNotEmpty() }
                val albums = (m["releasegroups"] as? List<*>).orEmpty().mapNotNull {
                    val g = it as? Map<*, *> ?: return@mapNotNull null
                    val t = (g["title"] as? String)?.trim().orEmpty()
                    if (t.isEmpty()) null else ReleaseGroup(t, (g["type"] as? String).orEmpty())
                }
                val c = Candidate(title, artists, (m["duration"] as? Double)?.toInt(), score, albums)
                val key = (m["id"] as? String) ?: "$title|$artists"
                val old = best[key]
                if (old == null || old.score < score) best[key] = c
            }
        }
        return best.values.sortedByDescending { it.score }
    }

    /** The service documents no error codes; these are the ones its source uses (4 and 6 bad key, 12 not allowed, 14 too many requests, 5 and 13 on its side). */
    private fun errorFor(code: Double?): DetailsError = when (code?.toInt()) {
        4, 6, 12 -> DetailsError.NotConfigured()
        14 -> DetailsError.QuotaReached()
        5, 13 -> DetailsError.Unavailable()
        else -> DetailsError.BadAnswer()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
