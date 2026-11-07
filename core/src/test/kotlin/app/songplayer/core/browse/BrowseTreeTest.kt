package app.songplayer.core.browse

import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.core.text.SortKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTreeTest {
    private val keyer = FakeKeyer()
    private val labels = BrowseLabels("Playlists", "Albums", "Artists", "Songs", "Open Music Player on your phone to set up your music.") { n ->
        if (n == 1) "1 song" else "$n songs"
    }
    private val tree = BrowseTree()

    private fun songNamed(title: String, n: Int, album: String? = "Alb", artist: String? = "Ann") =
        song("f$n.mp3", id = n.toLong(), title = title, album = album, artist = artist)

    private fun library(
        songs: List<Song>,
        playlists: List<BrowsePlaylist> = emptyList(),
    ): BrowseLibrary {
        val indexed = songs.map { IndexedSong.of(it, keyer) }.sortedBy { it.keys.sortTitle }
        val albums = indexed.groupBy { it.song.displayAlbum }.map { (name, members) ->
            BrowseAlbum("a:$name", name, members.first().song.displayArtist, SortKeys.of(name, keyer), members.map { it.song })
        }.sortedBy { it.sortName }
        val artists = indexed.groupBy { it.song.displayArtist }.map { (name, members) ->
            BrowseArtist("r:$name", name, SortKeys.of(name, keyer), members.map { it.song })
        }.sortedBy { it.sortName }
        return BrowseLibrary(indexed, albums, artists, playlists)
    }

    private fun kids(parent: String, lib: BrowseLibrary) = tree.children(parent, lib, labels)!!

    @Test
    fun BrowseTree_Root_HasFourTabsInOrder() {
        val lib = library(listOf(songNamed("Alpha", 1)))
        val root = kids(BrowseIds.ROOT, lib)
        assertEquals(listOf("Playlists", "Albums", "Artists", "Songs"), root.map { it.title })
        assertEquals(listOf(BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS), root.map { it.id })
        assertTrue("every tab is something to open", root.all { it is BrowseFolder })
    }

    @Test
    fun BrowseTree_EmptyLibrary_ReturnsSingleMessageItem() {
        val root = kids(BrowseIds.ROOT, library(emptyList()))
        assertEquals(1, root.size)
        assertEquals("Open Music Player on your phone to set up your music.", root.single().title)
        assertTrue(root.single() is BrowseFolder)
        assertEquals("and opening it shows nothing more", emptyList<BrowseNode>(), kids(BrowseIds.EMPTY, library(emptyList())))
    }

    private fun bigLibrary(n: Int): BrowseLibrary {
        // Titles spread over the alphabet and over a few digits, like a real collection; each has a unique number.
        val letters = ('A'..'Z').toList()
        val songs = (1..n).map { i ->
            val title = if (i % 40 == 0) "$i number" else "${letters[(i * 7) % 26]}${letters[i % 26].lowercaseChar()} song $i"
            songNamed(title, i, album = "Album ${i % 300}", artist = "Artist ${i % 250}")
        }
        return library(songs)
    }

    /** Every list node reachable from [parent], to the depth where songs appear; reports the longest list and all songs found. */
    private fun walk(lib: BrowseLibrary, parent: String, seen: MutableSet<String>, longest: IntArray, depth: Int = 0) {
        assertTrue("the tree is not deeper than 5 levels", depth <= 5)
        val children = tree.children(parent, lib, labels) ?: return
        if (parent != BrowseIds.ROOT) longest[0] = maxOf(longest[0], children.size)
        for (child in children) {
            when (child) {
                is BrowseTrack -> seen += child.song.key.value
                is BrowseFolder -> walk(lib, child.id, seen, longest, depth + 1)
            }
        }
    }

    @Test
    fun BrowseTree_5000Songs_NoListOverCap_LetterLevelCoversAll() {
        val lib = bigLibrary(5_000)
        val seen = mutableSetOf<String>()
        val longest = intArrayOf(0)
        walk(lib, BrowseIds.ROOT, seen, longest)
        assertTrue("no list is longer than the cap (the longest has ${longest[0]})", longest[0] <= BrowseTree.AUTO_LIST_CAP)
        assertEquals("every song can be reached from the root", 5_000, seen.size)
    }

    @Test
    fun BrowseTree_LongSongList_StartsWithLettersAndEndsWithRunsOfTheCap() {
        val lib = bigLibrary(5_000)
        val songs = kids(BrowseIds.SONGS, lib)
        assertTrue("letters first", songs.all { it is BrowseFolder && it.id.startsWith("songs:") })
        assertTrue(songs.size <= 27)
        assertEquals("A to Z, then #", songs.map { it.title }.sortedBy { if (it == "#") 99 else it[0] - 'A' }, songs.map { it.title })
        val bigLetter = songs.maxBy { (kids(it.id, lib)).size.let { size -> size } }
        val inside = kids(bigLetter.id, lib)
        assertTrue(inside.size <= BrowseTree.AUTO_LIST_CAP)
    }

    @Test
    fun BrowseTree_ALetterOverTheCap_IsSplitIntoRunsNamedByTheirRange() {
        // 250 songs that all sort under "A".
        val lib = library((1..250).map { songNamed("Aa song ${it.toString().padStart(3, '0')}", it) })
        val letters = kids(BrowseIds.SONGS, lib)
        assertEquals(listOf("A"), letters.map { it.title })
        val runs = kids("songs:A", lib)
        assertEquals(listOf("A 1-100", "A 101-200", "A 201-250"), runs.map { it.title })
        assertEquals(100, kids(runs[0].id, lib).size)
        assertEquals(50, kids(runs[2].id, lib).size)
    }

    @Test
    fun BrowseTree_TappingASong_QueuesTheListItWasIn_StartingAtIt() {
        val songs = (1..250).map { songNamed("Aa song ${it.toString().padStart(3, '0')}", it) }
        val lib = library(songs)
        val run2 = kids("songs:A", lib)[1]
        val tracks = kids(run2.id, lib).filterIsInstance<BrowseTrack>()
        val tapped = tracks[10]
        val queue = tree.queueFor(tapped.id, lib)
        assertNotNull(queue)
        assertEquals("the list is the run that was on the screen", tracks.map { it.song }, queue!!.songs)
        assertEquals(10, queue.index)
        assertEquals(tapped.song, queue.songs[queue.index])
    }

    @Test
    fun BrowseTree_PlaylistAlbumAndArtist_QueueTheirOwnSongsInOrder() {
        val songs = (1..4).map { songNamed("Song $it", it, album = if (it <= 2) "One" else "Two", artist = "Ann") }
        val playlist = BrowsePlaylist(7, "Driving", listOf(songs[3], songs[0], songs[2]))
        val lib = library(songs, listOf(playlist))

        val playlists = kids(BrowseIds.PLAYLISTS, lib)
        assertEquals("Driving", playlists.single().title)
        assertEquals("3 songs", (playlists.single() as BrowseFolder).subtitle)
        val inPlaylist = kids(BrowseIds.playlist(7), lib).filterIsInstance<BrowseTrack>()
        assertEquals(listOf("Song 4", "Song 1", "Song 3"), inPlaylist.map { it.title })
        assertEquals(1, tree.queueFor(inPlaylist[1].id, lib)!!.index)
        assertEquals(playlist.songs, tree.queueFor(inPlaylist[1].id, lib)!!.songs)

        val albums = kids(BrowseIds.ALBUMS, lib)
        assertEquals(listOf("One", "Two"), albums.map { it.title })
        val album = kids(albums[0].id, lib).filterIsInstance<BrowseTrack>()
        assertEquals(listOf("Song 1", "Song 2"), album.map { it.title })

        val artist = kids(kids(BrowseIds.ARTISTS, lib).single().id, lib).filterIsInstance<BrowseTrack>()
        assertEquals(4, artist.size)
    }

    @Test
    fun BrowseTree_ALongPlaylist_IsCutIntoRuns() {
        val songs = (1..230).map { songNamed("Song $it", it) }
        val lib = library(songs, listOf(BrowsePlaylist(1, "Big", songs)))
        val runs = kids(BrowseIds.playlist(1), lib)
        assertEquals(listOf("1-100", "101-200", "201-230"), runs.map { it.title })
        val last = kids(runs[2].id, lib).filterIsInstance<BrowseTrack>()
        assertEquals(30, last.size)
        assertEquals(songs.subList(200, 230), tree.queueFor(last[5].id, lib)!!.songs)
    }

    @Test
    fun BrowseTree_AnAlbumNameWithAHashAndAColon_StillOpens() {
        val songs = listOf(songNamed("Intro", 1, album = "Vol. #2: Live"), songNamed("Outro", 2, album = "Vol. #2: Live"))
        val lib = library(songs)
        val album = kids(BrowseIds.ALBUMS, lib).single()
        assertEquals(2, kids(album.id, lib).size)
    }

    @Test
    fun BrowseTree_UnknownIdsAndStaleTaps_AreNullNotCrashes() {
        val lib = library(listOf(songNamed("Alpha", 1)))
        assertNull(tree.children("nonsense", lib, labels))
        assertNull(tree.children("songs:Q", lib, labels))
        assertNull(tree.children("playlist:99", lib, labels))
        assertNull(tree.queueFor("noseparator", lib))
        assertNull(tree.queueFor(BrowseIds.track(BrowseIds.SONGS, "external_primary:Music/gone.mp3"), lib))
        assertNull(tree.item(BrowseIds.track(BrowseIds.SONGS, "external_primary:Music/gone.mp3"), lib, labels))
    }

    @Test
    fun BrowseTree_Item_AnswersForASongAndForATab() {
        val a = songNamed("Alpha", 1)
        val lib = library(listOf(a))
        val id = BrowseIds.track(BrowseIds.SONGS, a.key.value)
        assertEquals(a, (tree.item(id, lib, labels) as BrowseTrack).song)
        assertEquals("Albums", tree.item(BrowseIds.ALBUMS, lib, labels)?.title)
        assertEquals("the app's own queue item is a plain song key", a, (tree.item(a.key.value, lib, labels) as BrowseTrack).song)
    }

    @Test
    fun ControllerPolicy_TrustedAndAndroidAutoAreLetIn_AnyoneElseIsNot() {
        assertTrue(ControllerPolicy.allows(trusted = true, androidAuto = false))
        assertTrue(ControllerPolicy.allows(trusted = false, androidAuto = true))
        assertTrue(!ControllerPolicy.allows(trusted = false, androidAuto = false))
    }

    @Test
    fun ControllerPolicy_TheAssistantsPackage_IsLetIn_ASimilarNameIsNot() {
        assertTrue(ControllerPolicy.allows(trusted = false, androidAuto = false, packageName = "com.google.android.googlequicksearchbox"))
        assertTrue(!ControllerPolicy.allows(trusted = false, androidAuto = false, packageName = "com.google.android.googlequicksearchbox.evil"))
        assertTrue(!ControllerPolicy.allows(trusted = false, androidAuto = false, packageName = "com.example.other"))
    }
}
