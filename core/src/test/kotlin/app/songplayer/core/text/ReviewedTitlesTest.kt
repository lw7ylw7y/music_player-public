package app.songplayer.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedTitlesTest {
    @Test
    fun ReviewedTitles_HasAtLeast100Titles_WithDistinctReferences() {
        val all = ReviewedTitles.all
        assertTrue("only ${all.size} titles", all.size >= 100)
        assertEquals(all.size, all.map { it.reference }.toSet().size)
        assertEquals(all.size, all.map { it.title }.toSet().size)
    }

    @Test
    fun ReviewedTitles_References_AreLowercaseAsciiWithSingleSpaces() {
        ReviewedTitles.all.forEach {
            assertTrue(it.reference, it.reference.all { c -> c.code in 0x20..0x7E } && it.reference == it.reference.lowercase())
            assertTrue(it.reference, !it.reference.contains("  ") && it.reference == it.reference.trim())
        }
    }

    @Test
    fun ReviewedTitles_Order_PutsLettersThenDigitsThenSymbols() {
        val order = ReviewedTitles.all.sortedBy { SortKeys.of(it.reference, FallbackKeyer) }.map { it.title }
        val firstDigit = order.indexOfFirst { it.first().isDigit() }
        val firstSymbol = order.indexOfFirst { !it.first().isLetterOrDigit() && it.first().code < 0x4E00 }
        assertTrue(order.indexOf("Zombie") < firstDigit)
        assertTrue(order.indexOf("最爱") < firstDigit)
        assertTrue(firstDigit < firstSymbol)
    }

    @Test
    fun ReviewedTitles_SyllablesStayApart_XiAnBeforeXian() {
        val order = ReviewedTitles.all.sortedBy { SortKeys.of(it.reference, FallbackKeyer) }.map { it.title }
        assertTrue(order.indexOf("西安") < order.indexOf("先"))
    }
}
