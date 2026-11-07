package app.songplayer.core.text

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
    private val keyer = FakeKeyer()

    private fun indexed(file: String, artist: String?, album: String?, added: Long = 0, id: Long = 1) =
        IndexedSong.of(song(file, artist = artist, album = album, dateAddedSec = added, id = id), keyer)

    @Test
    fun Albums_GroupedByFoldedName_SimplifiedAndTraditionalMerge() {
        val groups = Albums.group(
            listOf(indexed("a.mp3", "x", "爱情"), indexed("b.mp3", "x", "愛情"), indexed("c.mp3", "x", "Other")),
            keyer,
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.displayName == "爱情" }.songs.size)
    }

    @Test
    fun Albums_ArtistLine_SameArtistInBothSpellingsIsNotVariousArtists() {
        val one = Albums.group(listOf(indexed("a.mp3", "張學友", "愛情"), indexed("b.mp3", "张学友", "愛情"), indexed("c.mp3", "张学友", "爱情")), keyer).single()
        assertEquals("張學友", one.artistLine) // the spelling of the song that sorts first
        val adele = Albums.group(listOf(indexed("a.mp3", "Adele", "25"), indexed("b.mp3", "adele", "25")), keyer).single()
        assertEquals("Adele", adele.artistLine)
        val various = Albums.group(listOf(indexed("a.mp3", "張學友", "x"), indexed("b.mp3", "Adele", "x")), keyer).single()
        assertEquals(Albums.VARIOUS_ARTISTS, various.artistLine)
    }

    @Test
    fun Albums_NoAlbumTag_ShareOneUnknownAlbum() {
        val groups = Albums.group(listOf(indexed("a.mp3", "x", null), indexed("b.mp3", "y", null)), keyer)
        assertEquals(1, groups.size)
        assertEquals("Unknown album", groups.single().displayName)
        assertEquals(2, groups.single().songs.size)
    }

    @Test
    fun Albums_ArtistLine_OneArtistSeveralOrNone() {
        val one = Albums.group(listOf(indexed("a.mp3", "Adele", "25"), indexed("b.mp3", "Adele", "25")), keyer).single()
        val various = Albums.group(listOf(indexed("a.mp3", "Adele", "Hits"), indexed("b.mp3", "Queen", "Hits")), keyer).single()
        val none = Albums.group(listOf(indexed("a.mp3", null, "Mystery")), keyer).single()
        val mixed = Albums.group(listOf(indexed("a.mp3", "Adele", "Mix"), indexed("b.mp3", null, "Mix")), keyer).single()
        assertEquals("Adele", one.artistLine)
        assertEquals("Various artists", various.artistLine)
        assertEquals("Unknown artist", none.artistLine)
        assertEquals("Various artists", mixed.artistLine)
    }

    @Test
    fun Albums_AddedIsTheNewestSong_AndCoverIsADeterministicSong() {
        val group = Albums.group(
            listOf(indexed("z.mp3", "x", "A", added = 5, id = 1), indexed("a.mp3", "x", "A", added = 9, id = 2)),
            keyer,
        ).single()
        assertEquals(9, group.addedSec)
        assertEquals("a.mp3", group.cover.fileName)
    }

    @Test
    fun Albums_SortedByTitleArtistAndRecentlyAdded() {
        val groups = Albums.group(
            listOf(
                indexed("1.mp3", "Zed", "Beta", added = 1), indexed("2.mp3", "Amy", "Gamma", added = 3),
                indexed("3.mp3", "Mia", "Alpha", added = 2),
            ),
            keyer,
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), groups.sorted(SortOrder.Title).map { it.displayName })
        assertEquals(listOf("Gamma", "Alpha", "Beta"), groups.sorted(SortOrder.Artist).map { it.displayName })
        assertEquals(listOf("Gamma", "Alpha", "Beta"), groups.sorted(SortOrder.RecentlyAdded).map { it.displayName })
    }

    @Test
    fun Artists_GroupedByFoldedName_CountedAndSortedAToZ() {
        val groups = Artists.group(
            listOf(
                indexed("1.mp3", "張學友", "a"), indexed("2.mp3", "张学友", "b"), indexed("3.mp3", "Adele", "c"),
                indexed("4.mp3", null, "d"),
            ),
            keyer,
        )
        assertEquals(3 + 1, groups.sumOf { it.songCount })
        assertEquals(3, groups.size)
        // The group shows the first spelling it saw; both spellings of the artist are one group of two songs.
        assertEquals(2, groups.single { it.displayName == "張學友" }.songCount)
        // A to Z by sort key: Adele, then "Unknown artist" under U, then the pinyin "zhang xue you".
        assertEquals(listOf("Adele", "Unknown artist", "張學友"), groups.sortedByName().map { it.displayName })
    }

    @Test
    fun Groups_5000Songs_AreFastEnough() {
        val songs = (1..5000).map { indexed("s$it.mp3", "artist ${it % 300}", "album ${it % 600}", added = it.toLong(), id = it.toLong()) }
        val start = System.nanoTime()
        Albums.group(songs, keyer).sorted(SortOrder.Title)
        Artists.group(songs, keyer).sortedByName()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("grouping 5000 songs into albums and artists: $ms ms")
        assertTrue("took $ms ms", ms < 500)
    }

    @Test
    fun Groups_DisplayName_DoesNotDependOnTheOrderOfTheList() {
        val a = indexed("a.mp3", "x", "爱情")
        val b = indexed("b.mp3", "x", "愛情")
        assertEquals("爱情", Albums.group(listOf(a, b), keyer).single().displayName)
        assertEquals("爱情", Albums.group(listOf(b, a), keyer).single().displayName)
        val artistA = indexed("a.mp3", "刘", "m")
        val artistB = indexed("b.mp3", "劉", "m")
        assertEquals("刘", Artists.group(listOf(artistB, artistA), keyer).single().displayName)
    }

    @Test
    fun Artists_SeveralNamesInOneTag_AreListedUnderEachArtist() {
        val groups = Artists.group(
            listOf(
                indexed("1.mp3", "A,B", "x"), indexed("2.mp3", "a, B", "x"), indexed("3.mp3", "B", "y"),
                indexed("4.mp3", "張學友\uFF0C Adele", "z"), indexed("5.mp3", "张学友\u3001Adele;Adele", "z"),
            ),
            keyer,
        )
        fun songsOf(name: String) = groups.first { it.displayName == name }.songs.map { it.song.fileName }
        assertEquals(listOf("1.mp3", "2.mp3"), songsOf("A"))
        assertEquals(listOf("1.mp3", "2.mp3", "3.mp3"), songsOf("B"))
        assertEquals(listOf("4.mp3", "5.mp3"), songsOf("張學友"))            // both spellings, both commas: one artist
        assertEquals(listOf("4.mp3", "5.mp3"), songsOf("Adele"))             // listed twice in one tag: the song counts once
        assertEquals(4, groups.size)
        assertEquals(3, groups.first { it.displayName == "B" }.songCount)
    }

    @Test
    fun Artists_NoNameInTheTag_IsUnknownArtist_AndAmpersandIsNotASeparator() {
        val groups = Artists.group(listOf(indexed("1.mp3", ",,", "x"), indexed("2.mp3", null, "x"), indexed("3.mp3", "Simon & Garfunkel", "x")), keyer)
        assertEquals(setOf("Unknown artist", "Simon & Garfunkel"), groups.map { it.displayName }.toSet())
        assertEquals(2, groups.first { !it.named }.songCount)
        assertEquals(1, groups.first { it.displayName == "Simon & Garfunkel" }.songCount)
        assertEquals(listOf("A", "B"), Artists.names(" A , B ,, "))
    }
}
