package app.songplayer.core.text

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionsTest {
    private val labels = listOf("A", "A", "C", "C", "C", "#")

    @Test
    fun Of_GroupsNeighbouringEqualLabels() {
        assertEquals(listOf(Section("A", 0), Section("C", 2), Section("#", 5)), Sections.of(labels))
    }

    @Test
    fun Of_NoItems_NoSections() {
        assertEquals(emptyList<Section>(), Sections.of(emptyList()))
    }

    @Test
    fun Letters_UseTheSortKeyInitial_DigitsAndSymbolsAreHash() {
        val keyer = FakeKeyer()
        val keys = listOf("Cherry", "apple", "9 lives", "!!!").map { SortKeys.of(it, keyer) }
        assertEquals(listOf("C", "A", "#", "#"), Sections.letters(keys))
    }

    @Test
    fun At_DividesTheTrackEvenly_AndClampsTheEnds() {
        val sections = Sections.of(labels)
        assertEquals("A", Sections.at(sections, 0f)?.label)
        assertEquals("C", Sections.at(sections, 0.4f)?.label)
        assertEquals("#", Sections.at(sections, 1f)?.label)
        assertEquals("#", Sections.at(sections, 5f)?.label)
        assertEquals("A", Sections.at(sections, -1f)?.label)
        assertNull(Sections.at(emptyList(), 0.5f))
    }

    @Test
    fun Months_LabelByMonthInTheGivenZone() {
        val zone = ZoneId.of("UTC")
        // 2026-10-31 23:30 UTC and 2026-11-01 00:30 UTC are different months.
        val oct = 1793489400L
        val nov = oct + 3600
        assertEquals(listOf("Oct 2026", "Nov 2026"), Sections.months(listOf(oct, nov), zone, Locale.ENGLISH))
    }
}
