package app.songplayer.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.text.FallbackKeyer
import app.songplayer.core.text.ReviewedTitles
import app.songplayer.core.text.SortKeys
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks Android's built-in ICU against the reviewed title set (check A-7; the set is still a draft). */
@RunWith(AndroidJUnit4::class)
class IcuKeyerTest {
    private val keyer = IcuKeyer()

    @Test
    fun IcuKeyer_ReviewedSet_SortsAsExpected() {
        val titles = ReviewedTitles.all
        val expectedOrder = titles.sortedBy { SortKeys.of(it.reference, FallbackKeyer) }.map { it.title }
        val actualOrder = titles.sortedBy { SortKeys.of(it.title, keyer) }.map { it.title }

        val differing = titles.filter { SortKeys.of(it.title, keyer) != SortKeys.of(it.reference, FallbackKeyer) }
            .map { "${it.title}: ICU '${keyer.sortKey(it.title)}' vs reference '${it.reference}'" }
        assertEquals("keys that differ (${differing.size}): ${differing.take(15)}", expectedOrder, actualOrder)
    }

    @Test
    fun IcuKeyer_SimplifiedAndTraditionalTitles_GetTheSameSortKey() {
        assertEquals(SortKeys.of("后来", keyer), SortKeys.of("後來", keyer))
        assertEquals(SortKeys.of("爱情", keyer), SortKeys.of("愛情", keyer))
    }

    @Test
    fun IcuKeyer_SearchFold_MakesTraditionalAndSimplifiedMatch() {
        assertEquals(keyer.searchFold("张学友"), keyer.searchFold("張學友"))
        assertEquals(keyer.searchFold("ABC"), keyer.searchFold("ａｂｃ"))
    }

    @Test
    fun IcuKeyer_Version_NamesTheIcuVersion() {
        assertTrue(keyer.version, keyer.version.startsWith("icu-"))
    }

    @Test
    fun IcuKeyer_5000Titles_SortKeysAndSearchFold_AreFastEnough() {
        val titles = (1..5000).map { ReviewedTitles.all[it % ReviewedTitles.all.size].title + " $it" }
        val start = System.nanoTime()
        titles.forEach { SortKeys.of(it, keyer) }
        val sortKeysMs = (System.nanoTime() - start) / 1_000_000
        val foldStart = System.nanoTime()
        titles.forEach { keyer.searchFold(it) }
        val foldMs = (System.nanoTime() - foldStart) / 1_000_000
        println("ICU keys for 5000 titles: sort keys $sortKeysMs ms, search fold $foldMs ms")
        // Timing is only meaningful on the phone (Technical Design 12: emulator numbers are for correctness). On a busy
        // emulator this took 1.1 s, 5.5 s and 40 s with the same code. So the limit is checked only when asked for:
        //   adb shell am instrument -e songplayer.perf 1 ...   (or the instrumentation argument in the Gradle task)
        // The first scan of 2,000 songs must stay inside 3 seconds on the phone (NFR-1): 2 s for 5,000 titles is 0.8 s for 2,000.
        val strict = InstrumentationRegistry.getArguments().getString("songplayer.perf") == "1"
        if (strict) {
            assertTrue("5000 sort keys took $sortKeysMs ms and search folds $foldMs ms", sortKeysMs < 2000 && foldMs < 2000)
        }
    }

    @Test
    fun IcuKeyer_AsciiAndMixedText_KeepsSingleSpacesBetweenSyllablesAndWords() {
        assertEquals("ge 1", keyer.sortKey("歌 1"))
        assertEquals("hou lai", keyer.sortKey("后来"))
        assertEquals("a hou lai b", keyer.sortKey("A后来B"))
        assertEquals("cafe", keyer.sortKey("Café"))
        assertEquals("abc", keyer.searchFold("ＡＢＣ"))
    }
}
