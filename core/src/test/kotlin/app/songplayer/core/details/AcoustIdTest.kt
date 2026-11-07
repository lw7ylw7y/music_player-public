package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AcoustIdTest {
    private val good = """
        {"status":"ok","results":[
          {"score":0.62,"id":"r2","recordings":[{"id":"rec-b","title":"Other","artists":[{"id":"a","name":"X"}],"duration":200}]},
          {"score":0.98,"id":"r1","recordings":[
            {"id":"rec-a","title":"夜曲","duration":226,"artists":[{"id":"a1","name":"周杰伦"},{"id":"a2","name":"Guest"}],
             "releasegroups":[{"id":"g1","title":"十一月的萧邦","type":"Album"},{"id":"g2","title":"Hits","type":"Single"}]},
            {"id":"rec-c","title":"Third","artists":[],"duration":201}]},
          {"score":0.7,"id":"r3","recordings":[{"id":"rec-b","title":"Other","artists":[],"duration":200}]},
          {"score":0.5,"id":"r4"}]}
    """.trimIndent()

    @Test
    fun AcoustIdAnswer_Parsing_Candidates() {
        val c = AcoustId.parseAnswer(good)
        assertEquals(listOf("夜曲", "Third", "Other"), c.map { it.title })
        assertEquals(0.98, c[0].score, 0.0)
        assertEquals(listOf("周杰伦", "Guest"), c[0].artists)
        assertEquals(226, c[0].durationS)
        assertEquals(listOf(ReleaseGroup("十一月的萧邦", "Album"), ReleaseGroup("Hits", "Single")), c[0].albums)
        assertEquals(emptyList<ReleaseGroup>(), c[1].albums)
        assertEquals(0.7, c[2].score, 0.0) // rec-b is in two results; the better score is kept
    }

    @Test
    fun AcoustIdAnswer_NoResults_IsAnEmptyList() {
        assertEquals(emptyList<Candidate>(), AcoustId.parseAnswer("""{"status":"ok","results":[]}"""))
        assertEquals(emptyList<Candidate>(), AcoustId.parseAnswer("""{"status":"ok","results":[{"score":0.9,"id":"x"}]}"""))
    }

    private fun code(text: String): String? = try { AcoustId.parseAnswer(text); null } catch (e: DetailsError) { e.code }

    @Test
    fun AcoustIdAnswer_QuotaAndGarbage_AreTyped() {
        assertEquals("DETAILS_QUOTA_REACHED", code("""{"status":"error","error":{"code":14,"message":"too many requests"}}"""))
        assertEquals("DETAILS_UNAVAILABLE", code("""{"status":"error","error":{"code":13,"message":"x"}}"""))
        for (bad in listOf("", "not json", "[]", "{}", """{"status":"ok"}""", """{"status":"ok","results":[1]}""", """{"status":"ok","results":[{"id":"x"}]}""",
            """{"status":"ok","results":[]} trailing""", "{\"a\":" + "[".repeat(100), """{"status":"error"}""", """{"status":"error","error":{"code":99}}""")) {
            assertEquals("input: $bad", "DETAILS_BAD_ANSWER", code(bad))
        }
    }

    @Test
    fun AcoustIdAnswer_InvalidKey_IsNotConfigured() {
        for (n in listOf(4, 6, 12)) assertEquals("DETAILS_NOT_CONFIGURED", code("""{"status":"error","error":{"code":$n,"message":"invalid API key"}}"""))
    }

    @Test
    fun AcoustIdRequest_Body_HasOnlyTheFourFields() {
        val body = AcoustId.requestBody("key&1", 241, "AQAA-_x")
        assertEquals("client=key%261&duration=241&fingerprint=AQAA-_x&meta=recordings+releasegroups+compress", body)
        assertEquals(setOf("client", "duration", "fingerprint", "meta"), body.split("&").map { it.substringBefore("=") }.toSet())
    }

    @Test
    fun Json_Escapes_AreDecoded() {
        assertEquals("a\"b\\c\né", Json.parse("\"a\\\"b\\\\c\\n\\u00e9\""))
        assertNull(Json.parse("null"))
        try { Json.parse("\"unterminated"); fail() } catch (_: IllegalArgumentException) {}
    }
}
