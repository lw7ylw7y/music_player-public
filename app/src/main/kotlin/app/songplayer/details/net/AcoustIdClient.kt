package app.songplayer.details.net

import app.songplayer.core.details.AcoustId
import app.songplayer.core.details.Candidate
import app.songplayer.core.details.LookupClient
import app.songplayer.core.errors.DetailsError
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only code that talks to the network (SourceRulesTest). One POST per call, no retry here: pacing (one request a second) and
 * the "never retry a quota stop" rule belong to the caller (Technical Design 5.8). Sends the four fields of [AcoustId.requestBody]
 * and a User-Agent with the app's version; nothing else about the phone or the user.
 *
 * [url] is a constructor argument so the tests can point it at a fake server on the phone; release builds only ever use [AcoustId.URL]
 * (cleartext is blocked there by the missing network security config).
 */
class AcoustIdClient(
    private val key: String,
    private val userAgent: String,
    private val url: String = AcoustId.URL,
) : LookupClient {

    override val configured: Boolean get() = key.isNotEmpty()

    override suspend fun lookup(fingerprint: String, durationS: Int): List<Candidate> {
        if (key.isEmpty()) throw DetailsError.NotConfigured()
        val body = AcoustId.requestBody(key, durationS, fingerprint).toByteArray(Charsets.UTF_8)
        return withContext(Dispatchers.IO) { post(body) }
    }

    private fun post(body: ByteArray): List<Candidate> {
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch (_: IOException) { throw DetailsError.Unavailable() }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 429) throw DetailsError.QuotaReached()
            if (code >= 500) throw DetailsError.Unavailable()
            if (code == 401 || code == 403) throw DetailsError.NotConfigured()
            val text = readCapped(if (code in 200..299) connection.inputStream else connection.errorStream)
            return AcoustId.parseAnswer(text)
        } catch (e: DetailsError) {
            throw e
        } catch (_: IOException) {
            throw DetailsError.Unavailable()
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [MAX_ANSWER_BYTES]; a bigger answer is not one of the service's and is refused. */
    private fun readCapped(stream: java.io.InputStream?): String {
        if (stream == null) throw DetailsError.BadAnswer()
        stream.use {
            val bytes = it.readNBytes(MAX_ANSWER_BYTES + 1)
            if (bytes.size > MAX_ANSWER_BYTES) throw DetailsError.BadAnswer()
            return String(bytes, Charsets.UTF_8)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_ANSWER_BYTES = 1_000_000
    }
}
