package app.songplayer.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val allowedLine = Regex(
        """^(Music Player diagnostics|App version: [\w .()+-]+|Phone: [\w .()+-]+|Android: [\w .()+-]+|""" +
            """Recent error codes: (none|unavailable)|Recent error codes \(newest first\):|[A-Z][A-Z_]+( -?\d+)*)$""",
    )

    private fun build(recent: List<ErrorEntry>?) = DiagnosticsText.build("1.0 (1)", "acme PX-100", "13 (API 33)", recent)

    @Test
    fun Diagnostics_Text_ContainsOnlyAllowedFields() {
        val text = build(
            listOf(
                ErrorEntry(LogCode.PLAYBACK_ITEM_FAILED, listOf(2005)),
                ErrorEntry(LogCode.PLAYLIST_READ_FAILED, listOf(1)),
                ErrorEntry(LogCode.RESUME_READ_FAILED, emptyList()),
            ),
        )
        val lines = text.lines()
        assertEquals("Music Player diagnostics", lines[0])
        assertEquals("App version: 1.0 (1)", lines[1])
        assertEquals("Phone: acme PX-100", lines[2])
        assertEquals("Android: 13 (API 33)", lines[3])
        assertEquals(listOf("PLAYBACK_ITEM_FAILED 2005", "PLAYLIST_READ_FAILED 1", "RESUME_READ_FAILED"), lines.drop(5))
        for (line in lines) assertTrue("not an allowed line: $line", allowedLine.matches(line))
    }

    @Test
    fun Diagnostics_NoErrorsAndAnUnreadableLog_AreSaidPlainly() {
        assertTrue(build(emptyList()).contains("Recent error codes: none"))
        assertTrue(build(null).contains("Recent error codes: unavailable"))
    }

    @Test
    fun Diagnostics_TextBuilder_ContainsNoDistinctiveName() {
        // The builder's inputs are a version, a phone, an Android version and codes with numbers; a name has no way in. Even a
        // system string with line breaks or odd characters stays on its own line and the codes are enum names.
        val text = DiagnosticsText.build("1.0\nSong: Qzv Title 7731", "Qzv\r\nPhone", "13", LogCode.entries.map { ErrorEntry(it, listOf(1, 2)) })
        assertEquals("one line per field", 4 + 1 + LogCode.entries.size, text.lines().size)
        assertFalse(text.contains("\r"))
        for (code in LogCode.entries) assertTrue(text.contains(code.name))
    }

    @Test
    fun Diagnostics_Text_ShowsAtMostTheNewest50() {
        val many = (1..80).map { ErrorEntry(LogCode.PLAYBACK_ITEM_FAILED, listOf(it)) }
        val lines = build(many).lines().drop(5)
        assertEquals(ErrorLogRules.CAP, lines.size)
        assertEquals("PLAYBACK_ITEM_FAILED 1", lines.first())
    }

    @Test
    fun LogCode_OnlyTimingsAndTheStartAreNotErrors() {
        assertEquals(
            setOf(LogCode.APP_START, LogCode.PLAY_TAP_TO_SOUND_MS, LogCode.PLAY_QUEUE_BUILD_MS, LogCode.SPLIT_PENDING_REMOVED, LogCode.SONGS_DELETED),
            LogCode.entries.filterNot { it.isError }.toSet(),
        )
    }
}
