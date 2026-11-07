package app.songplayer.core.text

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortKeysTest {
    private val keyer = FakeKeyer()

    private fun indexed(file: String, title: String?, artist: String? = "z", added: Long = 0, id: Long = 1) =
        IndexedSong.of(song(file, title = title, artist = artist, dateAddedSec = added, id = id), keyer)

    private fun titles(items: List<IndexedSong>) = items.map { it.song.displayTitle }

    @Test
    fun Sort_MixedTitles_LettersThenDigitsThenSymbols() {
        val items = listOf(
            indexed("1.mp3", "~tilde"), indexed("2.mp3", "2 Become 1"), indexed("3.mp3", "Zombie"),
            indexed("4.mp3", "apple"), indexed("5.mp3", "!!!"), indexed("6.mp3", "100 Years"), indexed("7.mp3", "我等你"),
        )
        assertEquals(
            listOf("apple", "我等你", "Zombie", "100 Years", "2 Become 1", "!!!", "~tilde"),
            titles(items.sorted(SortOrder.Title)),
        )
    }

    @Test
    fun Sort_ChinesePinyinAndLatinShareOneAlphabet() {
        val items = listOf(indexed("a.mp3", "Yellow"), indexed("b.mp3", "后来"), indexed("c.mp3", "Believe"), indexed("d.mp3", "爱你"))
        assertEquals(listOf("爱你", "Believe", "后来", "Yellow"), titles(items.sorted(SortOrder.Title)))
    }

    @Test
    fun Sort_SyllablesAreSeparate_XiAnBeforeXian() {
        val items = listOf(indexed("a.mp3", "先"), indexed("b.mp3", "西安"))
        assertEquals(listOf("西安", "先"), titles(items.sorted(SortOrder.Title)))
    }

    @Test
    fun Sort_SimplifiedAndTraditionalOfTheSameTitle_GetTheSameKey() {
        assertEquals(
            SortKeys.of("后来", keyer),
            SortKeys.of("後來", keyer),
        )
    }

    @Test
    fun Sort_Ties_AreBrokenByKey_SoOrderIsStable() {
        val x = indexed("x.mp3", "same", id = 1)
        val y = indexed("y.mp3", "same", id = 2)
        val z = indexed("a.mp3", "same", id = 3)
        val expected = listOf(z, x, y).map { it.song.key.value } // key order: .../a.mp3, .../x.mp3, .../y.mp3
        assertEquals(expected, listOf(y, z, x).sorted(SortOrder.Title).map { it.song.key.value })
        assertEquals(expected, listOf(x, y, z).sorted(SortOrder.Title).map { it.song.key.value })
    }

    @Test
    fun Sort_ByArtist_ThenTitleWithinArtist() {
        val items = listOf(
            indexed("1.mp3", "b song", artist = "Adele"), indexed("2.mp3", "a song", artist = "Adele"),
            indexed("3.mp3", "x", artist = null),
        )
        assertEquals(listOf("a song", "b song", "x"), titles(items.sorted(SortOrder.Artist)))
    }

    @Test
    fun Sort_RecentlyAdded_IsNewestFirst_TiesByKey() {
        val items = listOf(indexed("a.mp3", "old", added = 10), indexed("b.mp3", "new", added = 30), indexed("c.mp3", "mid", added = 20))
        assertEquals(listOf("new", "mid", "old"), titles(items.sorted(SortOrder.RecentlyAdded)))
    }

    @Test
    fun Sort_MissingTitle_UsesTheFileNameBecauseThatIsWhatIsShown() {
        val items = listOf(indexed("zebra.mp3", null), indexed("alpha.mp3", "middle"))
        assertEquals(listOf("middle", "zebra.mp3"), titles(items.sorted(SortOrder.Title)))
    }

    @Test
    fun Sort_OddInput_EmptyEmojiAndUnpairedSurrogate_DoesNotThrow() {
        val odd = listOf("", "   ", "😀", "\uD800", "a\u0000b")
        odd.forEach { SortKeys.of(it, keyer) }
        assertTrue(SortKeys.of("", keyer).startsWith("|"))
    }

    @Test
    fun FastScrollLetter_IsTheInitialOfTheSortKey_OrHash() {
        assertEquals("A", SortKeys.letterOf(SortKeys.of("爱你", keyer)))
        assertEquals("B", SortKeys.letterOf(SortKeys.of("believe", keyer)))
        assertEquals("#", SortKeys.letterOf(SortKeys.of("99 Problems", keyer)))
        assertEquals("#", SortKeys.letterOf(SortKeys.of("!!!", keyer)))
        assertEquals("#", SortKeys.letterOf(SortKeys.of("", keyer)))
    }

    @Test
    fun FastScrollMonth_IsTheMonthOfDateAdded() {
        val march = 1_709_251_200L // 2024-03-01T00:00:00Z
        assertEquals(java.time.YearMonth.of(2024, 3), SortKeys.monthOf(march, java.time.ZoneOffset.UTC))
    }

    @Test
    fun Sort_5000Songs_UnderBudget() {
        val items = (1..5000).map { indexed("t$it.mp3", "歌 ${(it * 7919) % 5000}", id = it.toLong()) }
        items.sorted(SortOrder.Title) // warm up
        val times = (1..20).map {
            val start = System.nanoTime()
            items.sorted(SortOrder.Title)
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        val median = times[times.size / 2]
        println("sort median over 5000 songs: $median ms")
        assertTrue("median $median ms is over the 200 ms budget", median < 200)
    }
}
