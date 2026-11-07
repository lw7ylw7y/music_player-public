package app.songplayer.core.text

import app.songplayer.core.library.Song
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

enum class SortOrder { Title, Artist, RecentlyAdded }

/** How a list runs, in the words the Library says out loud (UX Design 5); the arrow is up for [AToZ] and [OldestFirst]. */
enum class SortWords { AToZ, ZToA, NewestFirst, OldestFirst }

/** The words for [order] run [reversed] or not: Title and Artist start A to Z, Recently added starts newest first. */
fun sortWords(order: SortOrder, reversed: Boolean): SortWords = when (order) {
    SortOrder.RecentlyAdded -> if (reversed) SortWords.OldestFirst else SortWords.NewestFirst
    SortOrder.Title, SortOrder.Artist -> if (reversed) SortWords.ZToA else SortWords.AToZ
}

/** The sort and search keys of one song, computed once and kept (Technical Design 5.2). */
data class SongKeys(
    val sortTitle: String,
    val sortArtist: String,
    val sortAlbum: String,
    val searchText: String,
)

data class IndexedSong(val song: Song, val keys: SongKeys) {
    companion object {
        /** Sort keys are built from what the screens show, so a missing title sorts by its file name. */
        fun of(song: Song, keyer: TextKeyer) = IndexedSong(
            song,
            SongKeys(
                sortTitle = SortKeys.of(song.displayTitle, keyer),
                sortArtist = SortKeys.of(song.displayArtist, keyer),
                sortAlbum = SortKeys.of(song.displayAlbum, keyer),
                searchText = listOfNotNull(song.title, song.artist, song.album, song.fileName)
                    .joinToString(" ") { keyer.searchFold(it) },
            ),
        )
    }
}

object SortKeys {
    // ASCII order puts '{' and '|' after 'z', so letters sort first, then digits, then everything else.
    private const val DIGIT_PREFIX = "{"
    private const val SYMBOL_PREFIX = "|"

    /** The key to sort [text] by: a-z first (Latin and pinyin together), then digits, then other characters. */
    fun of(text: String, keyer: TextKeyer): String {
        val raw = keyer.sortKey(text).trim()
        return when (raw.firstOrNull()) {
            null -> SYMBOL_PREFIX
            in 'a'..'z' -> raw
            in '0'..'9' -> DIGIT_PREFIX + raw
            else -> SYMBOL_PREFIX + raw
        }
    }

    /** The fast-scroll letter of a sort key: A to Z, or "#" for digits and everything else. */
    fun letterOf(key: String): String = key.firstOrNull()?.takeIf { it in 'a'..'z' }?.uppercase() ?: "#"

    /** The fast-scroll section of a song sorted by "Recently added". */
    fun monthOf(dateAddedSec: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochSecond(dateAddedSec).atZone(zone))
}

/**
 * Sorts in memory. Ties are broken by the song key, so the order is stable (Technical Design 5.2). [reversed] gives exactly the
 * default list backwards, ties included.
 */
fun List<IndexedSong>.sorted(order: SortOrder, reversed: Boolean = false): List<IndexedSong> {
    val byKey = compareBy<IndexedSong> { it.song.key.value }
    val comparator: Comparator<IndexedSong> = when (order) {
        SortOrder.Title -> compareBy<IndexedSong> { it.keys.sortTitle }.then(byKey)
        // Within one artist, order by title; the key only breaks the remaining ties.
        SortOrder.Artist -> compareBy<IndexedSong> { it.keys.sortArtist }.thenBy { it.keys.sortTitle }.then(byKey)
        SortOrder.RecentlyAdded -> compareByDescending<IndexedSong> { it.song.dateAddedSec }.then(byKey)
    }
    return sortedWith(comparator).let { if (reversed) it.asReversed() else it }
}
