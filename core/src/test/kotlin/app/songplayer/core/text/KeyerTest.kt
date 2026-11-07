package app.songplayer.core.text

import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyerTest {
    private class Sink : LogSink {
        val codes = mutableListOf<LogCode>()
        override fun write(code: LogCode, values: List<Int>) {
            codes += code
        }
    }

    @Test
    fun Keyer_Throws_FallsBackAndLogsCode() {
        val sink = Sink()
        val keyer = ResilientKeyer(ThrowingKeyer(), FallbackKeyer, AppLog(listOf(sink)))
        assertEquals("abc", keyer.sortKey("ABC"))
        assertEquals("张", keyer.searchFold("张"))
        assertEquals(listOf(LogCode.KEYER_FALLBACK), sink.codes)
    }

    @Test
    fun Keyer_Healthy_IsUsedAndLogsNothing() {
        val sink = Sink()
        val keyer = ResilientKeyer(FakeKeyer(), FallbackKeyer, AppLog(listOf(sink)))
        assertEquals("hou lai", keyer.sortKey("后来"))
        assertTrue(sink.codes.isEmpty())
    }

    @Test
    fun Keyer_Version_IsThePrimarysSoAnUpdateRecomputesKeys() {
        assertEquals("fake-1", ResilientKeyer(FakeKeyer("fake-1"), FallbackKeyer, AppLog(emptyList())).version)
    }

    @Test
    fun FallbackKeyer_ChineseSortsByCodePointUnderTheHashBucket_AndSearchStillWorks() {
        val a = SortKeys.of("啊", FallbackKeyer)
        val b = SortKeys.of("安", FallbackKeyer)
        assertEquals("#", SortKeys.letterOf(a))
        assertEquals("啊" < "安", a < b)
        assertEquals("abc", FallbackKeyer.searchFold("ＡＢＣ"))
    }

    @Test
    fun SortAndSearch_WithBrokenKeyer_StillReturnResults() {
        val keyer = ResilientKeyer(ThrowingKeyer(), FallbackKeyer, AppLog(emptyList()))
        val songs = listOf(app.songplayer.core.library.song("b.mp3", title = "Beta"), app.songplayer.core.library.song("a.mp3", title = "Alpha"))
        val sorted = songs.map { IndexedSong.of(it, keyer) }.sorted(SortOrder.Title)
        assertEquals(listOf("Alpha", "Beta"), sorted.map { it.song.displayTitle })
        assertEquals(1, SearchIndex.build(songs.map { IndexedSong.of(it, keyer) }, keyer).search("alp").songs.size)
    }
}
