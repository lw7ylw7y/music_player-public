package app.songplayer.details

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.errors.DetailsError
import app.songplayer.details.net.AcoustIdClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** L3: the HTTP client against a fake server on the phone. Nothing here touches the real service. */
@RunWith(AndroidJUnit4::class)
class AcoustIdClientTest {
    private val answer = """{"status":"ok","results":[{"score":0.97,"id":"r","recordings":[{"id":"m","title":"夜曲","duration":226,
        "artists":[{"id":"a","name":"周杰伦"}],"releasegroups":[{"id":"g","title":"十一月的萧邦","type":"Album"}]}]}]}"""

    private fun code(server: FakeLookupServer, key: String = "KEY123"): String? = try {
        runBlocking { AcoustIdClient(key, "SongPlayer/test", server.url).lookup("AQAAfp", 241) }; null
    } catch (e: DetailsError) { e.code }

    @Test
    fun AcoustIdClient_FakeServer_RequestHasOnlyTheFourFields() {
        FakeLookupServer(body = answer).use { server ->
            val candidates = runBlocking { AcoustIdClient("KEY123", "SongPlayer/1.0", server.url).lookup("AQAAfp", 241) }
            assertEquals("夜曲", candidates.single().title)
            val r = server.seen.single()
            assertTrue(r.requestLine, r.requestLine.startsWith("POST /v2/lookup"))
            assertEquals(setOf("client", "duration", "fingerprint", "meta"), r.body.split("&").map { it.substringBefore("=") }.toSet())
            assertEquals("client=KEY123&duration=241&fingerprint=AQAAfp&meta=recordings+releasegroups+compress", r.body)
            assertEquals("SongPlayer/1.0", r.headers["user-agent"])
            assertTrue("no cookie or credentials header: ${r.headers.keys}", r.headers.keys.none { it == "cookie" || it == "authorization" })
        }
    }

    @Test
    fun AcoustIdClient_Statuses_AreTyped() {
        FakeLookupServer(status = 429, body = "slow down").use { assertEquals("DETAILS_QUOTA_REACHED", code(it)) }
        FakeLookupServer(status = 503, body = "").use { assertEquals("DETAILS_UNAVAILABLE", code(it)) }
        FakeLookupServer(status = 200, body = "<html>captive portal</html>").use { assertEquals("DETAILS_BAD_ANSWER", code(it)) }
        FakeLookupServer(status = 401, body = "").use { assertEquals("DETAILS_NOT_CONFIGURED", code(it)) }
        FakeLookupServer(status = 400, body = """{"status":"error","error":{"code":4,"message":"invalid API key"}}""").use {
            assertEquals("DETAILS_NOT_CONFIGURED", code(it))
        }
    }

    @Test
    fun AcoustIdClient_EmptyKey_IsNotConfiguredAndSendsNothing() {
        FakeLookupServer(body = answer).use { server ->
            assertEquals("DETAILS_NOT_CONFIGURED", code(server, key = ""))
            assertEquals(0, server.seen.size)
        }
    }

    @Test
    fun AcoustIdClient_NothingListening_IsUnavailable() {
        val dead = FakeLookupServer().also { it.close() }
        assertEquals("DETAILS_UNAVAILABLE", code(dead))
    }

    @Test
    fun AcoustIdClient_AnswerOverTheCap_IsBadAnswer() {
        FakeLookupServer(body = "x".repeat(1_100_000)).use { assertEquals("DETAILS_BAD_ANSWER", code(it)) }
    }
}
