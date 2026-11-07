package app.songplayer.core.text

/** What a search found, in Title order: songs, and the albums and artists as the Library groups them. */
data class SearchResults(
    val songs: List<IndexedSong>,
    val albums: List<AlbumGroup>,
    val artists: List<ArtistGroup>,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The folded text of the library, held in memory and filtered on every keystroke (Technical Design 5.2).
 * A song matches when every space-separated part of the query is contained in its stored search text (title,
 * artist, album and file name); an album or artist group matches on its folded name. Pinyin letters do not
 * match Chinese text (decided). Results are in Title order whatever the Library's sort is.
 */
class SearchIndex private constructor(
    private val keyer: TextKeyer,
    private val songs: List<IndexedSong>,
    private val albums: List<AlbumGroup>,
    private val artists: List<ArtistGroup>,
) {
    fun search(query: String): SearchResults {
        val parts = keyer.searchFold(query).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return SearchResults.EMPTY
        fun matches(text: String) = parts.all { text.contains(it) }
        return SearchResults(
            songs = songs.filter { matches(it.keys.searchText) },
            albums = albums.filter { it.named && matches(it.key) },
            artists = artists.filter { it.named && matches(it.key) },
        )
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** [songs] carry the keys stored by the sync, so nothing is folded again here except the groups' names. */
        fun build(songs: List<IndexedSong>, keyer: TextKeyer) = SearchIndex(
            keyer,
            songs.sorted(SortOrder.Title),
            Albums.group(songs, keyer).sorted(SortOrder.Title),
            Artists.group(songs, keyer).sortedByName(),
        )
    }
}
