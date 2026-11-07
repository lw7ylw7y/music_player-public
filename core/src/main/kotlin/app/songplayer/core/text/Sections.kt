package app.songplayer.core.text

import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A run of neighbouring list items that share a fast-scroll label (R-25). */
data class Section(val label: String, val firstIndex: Int)

object Sections {
    /** Runs of equal neighbouring [labels]: the letters A to Z and "#", or the months of "Recently added". */
    fun of(labels: List<String>): List<Section> {
        val sections = ArrayList<Section>()
        labels.forEachIndexed { index, label ->
            if (sections.lastOrNull()?.label != label) sections += Section(label, index)
        }
        return sections
    }

    /** The label of each item of a list sorted by Title or Artist: the first letter of its sort key. */
    fun letters(sortKeys: List<String>): List<String> = sortKeys.map(SortKeys::letterOf)

    /** The label of each item of a list sorted by "Recently added", for example "Oct 2026". */
    fun months(addedSec: List<Long>, zone: ZoneId, locale: Locale): List<String> {
        val format = DateTimeFormatter.ofPattern("MMM yyyy", locale)
        val cache = HashMap<YearMonth, String>()
        return addedSec.map { cache.getOrPut(SortKeys.monthOf(it, zone)) { SortKeys.monthOf(it, zone).format(format) } }
    }

    /**
     * The section under a drag position: [fraction] 0 is the top of the handle's track and 1 the bottom.
     * The track is divided evenly between the sections, so dragging to the "C" part shows the first "C" item.
     */
    fun at(sections: List<Section>, fraction: Float): Section? {
        if (sections.isEmpty()) return null
        val index = (fraction.coerceIn(0f, 1f) * sections.size).toInt().coerceAtMost(sections.lastIndex)
        return sections[index]
    }
}
