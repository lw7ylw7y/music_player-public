package app.songplayer.core.browse

import app.songplayer.core.library.Song
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortKeys

/** An album as the car lists it. [songs] are in play order. */
class BrowseAlbum(val key: String, val name: String, val artistLine: String, val sortName: String, val songs: List<Song>)

class BrowseArtist(val key: String, val name: String, val sortName: String, val songs: List<Song>)

/** A playlist with the songs that can be played, in playlist order. */
class BrowsePlaylist(val id: Long, val name: String, val songs: List<Song>)

/**
 * What the car may browse: the songs sorted by title, the albums sorted by title, the artists A to Z and the playlists in the
 * order the app lists them. The caller sorts; the tree only splits and names (Technical Design 5.5).
 */
class BrowseLibrary(
    val songs: List<IndexedSong>,
    val albums: List<BrowseAlbum>,
    val artists: List<BrowseArtist>,
    val playlists: List<BrowsePlaylist>,
) {
    val isEmpty: Boolean get() = songs.isEmpty()
}

/** The words of the car screens, from the app's string resources (the tree itself has no language). */
class BrowseLabels(
    val playlists: String,
    val albums: String,
    val artists: String,
    val songs: String,
    /** The one line shown when there is no music: "Open Music Player on your phone to set up your music." */
    val emptyMessage: String,
    /** "12 songs" */
    val songCount: (Int) -> String,
)

sealed interface BrowseNode {
    val id: String
    val title: String
}

/** A node with children (a tab, a letter, an album); the car shows it as something to open. */
data class BrowseFolder(override val id: String, override val title: String, val subtitle: String? = null) : BrowseNode

/** A song in a list; tapping it plays that list from this song. */
data class BrowseTrack(override val id: String, override val title: String, val song: Song) : BrowseNode

/** The list a tapped song belongs to, and where the song is in it. */
data class ResolvedQueue(val songs: List<Song>, val index: Int)

object BrowseIds {
    const val ROOT = "root"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val SONGS = "songs"

    /** The folder of the one line shown when there is nothing to browse. */
    const val EMPTY = "empty"

    /** Splits a song's item id into its list and its song key; no list id and no key contains it. */
    const val SEPARATOR = '\u001F'

    fun playlist(id: Long) = "playlist:$id"
    fun album(key: String) = "album:$key"
    fun artist(key: String) = "artist:$key"
    fun letter(list: String, letter: String) = "$list:$letter"
    /** Splits a list id from the run number of a long list; no name or key contains it (a "#" can be in an album name). */
    const val RUN_SEPARATOR = '\u001E'

    fun chunk(list: String, number: Int) = "$list$RUN_SEPARATOR$number"
    fun track(list: String, songKey: String) = list + SEPARATOR + songKey
}

/**
 * The browse tree for Android Auto (R-16, Technical Design 5.5), built from the library on demand. The root has exactly four
 * tabs; the car allows no more and shows only items that can be opened. A list longer than [cap] is split first by letter
 * (A to Z, "#"), and any list or letter with more than [cap] entries into runs of [cap] ("A 1-100", "A 101-200"), so no
 * list is longer than [cap] and every song can be reached. Tapping a song queues the list it was in (R-6).
 */
class BrowseTree(private val cap: Int = AUTO_LIST_CAP) {
    init {
        require(cap >= 2) { "a list must be able to hold at least two items" }
    }

    /** The children of [parentId]; null when the id is not a node of this tree. Never longer than [cap] (the root: four). */
    fun children(parentId: String, library: BrowseLibrary, labels: BrowseLabels): List<BrowseNode>? {
        if (parentId == BrowseIds.ROOT) return root(library, labels)
        if (parentId == BrowseIds.EMPTY) return emptyList()
        val (base, run) = splitRun(parentId)
        return when {
            base == BrowseIds.PLAYLISTS ->
                paged(base, run, library.playlists, "") { folder(BrowseIds.playlist(it.id), it.name, labels.songCount(it.songs.size)) }
            base == BrowseIds.ALBUMS || base.startsWith("${BrowseIds.ALBUMS}:") ->
                byLetter(base, run, library.albums, { it.sortName }) { folder(BrowseIds.album(it.key), it.name, it.artistLine) }
            base == BrowseIds.ARTISTS || base.startsWith("${BrowseIds.ARTISTS}:") ->
                byLetter(base, run, library.artists, { it.sortName }) { folder(BrowseIds.artist(it.key), it.name, labels.songCount(it.songs.size)) }
            base == BrowseIds.SONGS || base.startsWith("${BrowseIds.SONGS}:") ->
                byLetter(base, run, library.songs, { it.keys.sortTitle }) { track(parentId, it.song) }
            else -> leafSongs(base, library)?.let { songs -> paged(base, run, songs, "") { track(parentId, it) } }
        }
    }

    /** A single node, for the car asking about an item it already knows (a song, or a folder). */
    fun item(id: String, library: BrowseLibrary, labels: BrowseLabels): BrowseNode? {
        val sep = id.indexOf(BrowseIds.SEPARATOR)
        if (sep >= 0) {
            val key = id.substring(sep + 1)
            val song = library.songs.firstOrNull { it.song.key.value == key }?.song ?: return null
            return track(id.substring(0, sep), song)
        }
        return when {
            id == BrowseIds.ROOT -> folder(BrowseIds.ROOT, labels.songs, null)
            id == BrowseIds.PLAYLISTS -> folder(id, labels.playlists, null)
            id == BrowseIds.ALBUMS -> folder(id, labels.albums, null)
            id == BrowseIds.ARTISTS -> folder(id, labels.artists, null)
            id == BrowseIds.SONGS -> folder(id, labels.songs, null)
            id == BrowseIds.EMPTY -> folder(id, labels.emptyMessage, null)
            id.startsWith("playlist:") -> library.playlists.firstOrNull { BrowseIds.playlist(it.id) == id }
                ?.let { folder(id, it.name, labels.songCount(it.songs.size)) }
            id.startsWith("album:") -> library.albums.firstOrNull { BrowseIds.album(it.key) == id }?.let { folder(id, it.name, it.artistLine) }
            id.startsWith("artist:") -> library.artists.firstOrNull { BrowseIds.artist(it.key) == id }
                ?.let { folder(id, it.name, labels.songCount(it.songs.size)) }
            library.songs.any { it.song.key.value == id } ->
                library.songs.first { it.song.key.value == id }.song.let { BrowseTrack(id, it.displayTitle, it) }
            else -> children(id, library, labels)?.let { folder(id, id.substringAfterLast(':'), null) }
        }
    }

    /** The queue for a tapped song: the list that song was shown in, starting at it. Null when the id or the song is unknown. */
    fun queueFor(trackId: String, library: BrowseLibrary): ResolvedQueue? {
        val sep = trackId.indexOf(BrowseIds.SEPARATOR)
        if (sep < 0) return null
        val listId = trackId.substring(0, sep)
        val key = trackId.substring(sep + 1)
        val songs = children(listId, library, NO_LABELS)?.filterIsInstance<BrowseTrack>()?.map { it.song } ?: return null
        val index = songs.indexOfFirst { it.key.value == key }
        return if (index < 0) null else ResolvedQueue(songs, index)
    }

    // ---- the tree ---------------------------------------------------------------------------------------

    private fun root(library: BrowseLibrary, labels: BrowseLabels): List<BrowseNode> =
        if (library.isEmpty) {
            listOf(folder(BrowseIds.EMPTY, labels.emptyMessage, null))
        } else {
            listOf(
                folder(BrowseIds.PLAYLISTS, labels.playlists, null),
                folder(BrowseIds.ALBUMS, labels.albums, null),
                folder(BrowseIds.ARTISTS, labels.artists, null),
                folder(BrowseIds.SONGS, labels.songs, null),
            )
        }

    private fun folder(id: String, title: String, subtitle: String?) = BrowseFolder(id, title, subtitle)

    private fun track(listId: String, song: Song) = BrowseTrack(BrowseIds.track(listId, song.key.value), song.displayTitle, song)

    /**
     * [items] as they are when they fit in [cap]; else the runs of [cap] (when [run] is null) or one run. [prefix] names the
     * runs ("A" gives "A 1-100").
     */
    private fun <T> paged(base: String, run: Int?, items: List<T>, prefix: String, make: (T) -> BrowseNode): List<BrowseNode>? {
        if (items.size <= cap) return if (run == null) items.map(make) else null
        if (run == null) return runs(base, items.size, prefix)
        val from = (run - 1) * cap
        if (run < 1 || from >= items.size) return null
        return items.subList(from, minOf(from + cap, items.size)).map(make)
    }

    /**
     * A list of albums, artists or songs: whole when it fits, else its letters; a letter (`albums:A`) is a list in turn, split
     * into runs if it does not fit. [base] is the list's own id (`albums`) or one of its letters.
     */
    private fun <T> byLetter(base: String, run: Int?, items: List<T>, sortKey: (T) -> String, make: (T) -> BrowseNode): List<BrowseNode>? {
        val list = base.substringBefore(':')
        val letter = base.substringAfter(':', "").ifEmpty { null }
        if (letter == null) {
            if (items.size <= cap) return if (run == null) items.map(make) else null
            if (run != null) return null
            return items.groupBy { SortKeys.letterOf(sortKey(it)) }.keys.sortedBy { it.letterRank() }
                .map { folder(BrowseIds.letter(list, it), it, null) }
        }
        val inLetter = items.filter { SortKeys.letterOf(sortKey(it)) == letter }
        if (inLetter.isEmpty()) return null
        return paged(base, run, inLetter, letter, make)
    }

    private fun runs(base: String, size: Int, prefix: String): List<BrowseNode> =
        (1..(size + cap - 1) / cap).map { n ->
            folder(BrowseIds.chunk(base, n), "$prefix ${(n - 1) * cap + 1}-${minOf(n * cap, size)}".trim(), null)
        }

    /** The songs of a playlist, an album or an artist (whole; [paged] cuts them into runs). */
    private fun leafSongs(base: String, library: BrowseLibrary): List<Song>? = when {
        base.startsWith("playlist:") -> library.playlists.firstOrNull { BrowseIds.playlist(it.id) == base }?.songs
        base.startsWith("album:") -> library.albums.firstOrNull { BrowseIds.album(it.key) == base }?.songs
        base.startsWith("artist:") -> library.artists.firstOrNull { BrowseIds.artist(it.key) == base }?.songs
        else -> null
    }

    private fun splitRun(id: String): Pair<String, Int?> {
        val at = id.lastIndexOf(BrowseIds.RUN_SEPARATOR)
        if (at < 0) return id to null
        val n = id.substring(at + 1).toIntOrNull() ?: return id to null
        return id.substring(0, at) to n
    }

    private fun String.letterRank(): Int = if (this == "#") 99 else this[0] - 'A'

    companion object {
        /**
         * The longest list the car is given. Provisional (Technical Design 5.5): Android Auto limits how many items a driver is
         * shown and does not page; the real limit that works is read off the Desktop Head Unit.
         */
        const val AUTO_LIST_CAP = 100

        private val NO_LABELS = BrowseLabels("", "", "", "", "") { "" }
    }
}

/** Who may connect to the playback session: apps Media3 trusts (this app, the system) and Android Auto's controllers. */
object ControllerPolicy {
    /** Google's app, which hosts the assistant that sends spoken requests ("play ..."); the name is checked by Android, not claimed by the caller. */
    val ASSISTANT_PACKAGES: Set<String> = setOf("com.google.android.googlequicksearchbox")

    fun allows(trusted: Boolean, androidAuto: Boolean, packageName: String = ""): Boolean =
        trusted || androidAuto || packageName in ASSISTANT_PACKAGES
}
