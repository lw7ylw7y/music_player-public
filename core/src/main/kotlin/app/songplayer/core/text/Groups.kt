package app.songplayer.core.text

import app.songplayer.core.library.Song

/** An album as the Library shows it: the songs that share a folded album name (Technical Design 5.2). */
data class AlbumGroup(
    val key: String,
    val displayName: String,
    /** The one artist of every song, "Various artists" when they differ, or "Unknown artist". */
    val artistLine: String,
    val songs: List<IndexedSong>,
    val addedSec: Long,
    /** The song whose cover represents the album: the first by song key, so it does not change between syncs. */
    val cover: Song,
    val sortName: String,
    val sortArtist: String,
) {
    val songCount: Int get() = songs.size

    /** False for the one shared "Unknown album" group, which no search matches. */
    val named: Boolean get() = key != Albums.NO_ALBUM_KEY
}

object Albums {
    const val VARIOUS_ARTISTS = "Various artists"
    internal const val NO_ALBUM_KEY = "\u0000no-album"

    fun group(songs: List<IndexedSong>, keyer: TextKeyer): List<AlbumGroup> =
        songs.groupBy { it.song.album?.let(keyer::searchFold) ?: NO_ALBUM_KEY }.map { (key, members) ->
            // The name comes from the song with the smallest key, so it does not depend on the order of the list.
            val displayName = members.minBy { it.song.key.value }.song.displayAlbum
            // The same artist spelled in Traditional and Simplified (or in another case) is one artist, not "Various artists".
            val artists = members.sortedBy { it.song.key.value }.map { it.song.displayArtist }.distinctBy(keyer::searchFold)
            val artistLine = if (artists.size == 1) artists.single() else VARIOUS_ARTISTS
            AlbumGroup(
                key = key,
                displayName = displayName,
                artistLine = artistLine,
                songs = members,
                addedSec = members.maxOf { it.song.dateAddedSec },
                cover = members.minBy { it.song.key.value }.song,
                sortName = SortKeys.of(displayName, keyer),
                sortArtist = SortKeys.of(artistLine, keyer),
            )
        }
}

/** Sorts albums like songs: by title, by artist line, or newest first; ties by the group key; [reversed] is that list backwards. */
fun List<AlbumGroup>.sorted(order: SortOrder, reversed: Boolean = false): List<AlbumGroup> {
    val byKey = compareBy<AlbumGroup> { it.key }
    val comparator: Comparator<AlbumGroup> = when (order) {
        SortOrder.Title -> compareBy<AlbumGroup> { it.sortName }.then(byKey)
        SortOrder.Artist -> compareBy<AlbumGroup> { it.sortArtist }.thenBy { it.sortName }.then(byKey)
        SortOrder.RecentlyAdded -> compareByDescending<AlbumGroup> { it.addedSec }.then(byKey)
    }
    return sortedWith(comparator).let { if (reversed) it.asReversed() else it }
}

data class ArtistGroup(
    val key: String,
    val displayName: String,
    /** The artist's songs, in the order they were given. */
    val songs: List<IndexedSong>,
    val sortName: String,
) {
    val songCount: Int get() = songs.size

    /** False for the one shared "Unknown artist" group, which no search matches. */
    val named: Boolean get() = key != Artists.NO_ARTIST_KEY
}

object Artists {
    internal const val NO_ARTIST_KEY = "\u0000no-artist"

    /** What separates several artists in one tag: a comma, the full-width comma, the ideographic comma, a semicolon. Not "&" or "/", which are in real names. */
    private val SEPARATORS = Regex("[,\uFF0C\u3001;\uFF1B]")

    /** The individual artists in one tag, trimmed, without empty parts ("A, B" gives A and B). A tag with no name in it gives none. */
    fun names(tag: String): List<String> = tag.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * One group per individual artist: a song tagged with several names (separated as in [names]) is listed under each of them, and the
     * same artist in another case or in Traditional and Simplified Chinese is one artist. A song with no artist is in the one Unknown artist group.
     */
    fun group(songs: List<IndexedSong>, keyer: TextKeyer): List<ArtistGroup> {
        val members = LinkedHashMap<String, MutableList<IndexedSong>>()
        val shown = HashMap<String, Pair<String, String>>()   // key to (smallest song key, the name as that song spells it)
        for (s in songs) {
            val tagged = s.song.artist?.let(::names).orEmpty()
            val byKey = LinkedHashMap<String, String>()   // folded name to the name as this song spells it
            if (tagged.isEmpty()) byKey[NO_ARTIST_KEY] = Song.UNKNOWN_ARTIST else for (name in tagged) byKey.putIfAbsent(keyer.searchFold(name), name)
            for ((key, label) in byKey) {
                members.getOrPut(key) { ArrayList() }.add(s)
                val current = shown[key]
                if (current == null || s.song.key.value < current.first) shown[key] = s.song.key.value to label
            }
        }
        return members.map { (key, list) ->
            val displayName = shown.getValue(key).second
            ArtistGroup(key, displayName, list, SortKeys.of(displayName, keyer))
        }
    }
}

/** Artists are listed A to Z by name (they have no sort control); ties by the group key. */
fun List<ArtistGroup>.sortedByName(): List<ArtistGroup> = sortedWith(compareBy<ArtistGroup> { it.sortName }.thenBy { it.key })
