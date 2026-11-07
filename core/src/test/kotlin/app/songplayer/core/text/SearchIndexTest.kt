package app.songplayer.core.text

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private val keyer = FakeKeyer()
    private val zhang = song("a.mp3", id = 1, title = "我等你", artist = "張學友", album = "愛情")
    private val liu = song("b.mp3", id = 2, title = "后来", artist = "刘若英", album = "愛情")
    private val adele = song("c.mp3", id = 3, title = "Hello", artist = "Adele", album = "25")
    private val noTags = song("recording_0412.mp3", id = 4, title = null, artist = null, album = null)
    private val index = SearchIndex.build(listOf(zhang, liu, adele, noTags).map { IndexedSong.of(it, keyer) }, keyer)

    private fun SearchResults.songList() = songs.map { it.song }

    @Test
    fun Search_SimplifiedFindsTraditional_AndBack() {
        assertEquals(listOf(zhang), index.search("张").songList())
        assertEquals(listOf(zhang), index.search("張").songList())
        assertEquals(listOf(liu), index.search("後來").songList())
        assertEquals(listOf(liu), index.search("后来").songList())
    }

    @Test
    fun Search_MultiWord_RequiresAllParts() {
        assertEquals(listOf(adele), index.search("hello adele").songList())
        assertTrue(index.search("hello 张").songList().isEmpty())
    }

    @Test
    fun Search_IsCaseAndWidthInsensitive() {
        assertEquals(listOf(adele), index.search("ＨＥＬＬＯ").songList())
        assertEquals(listOf(adele), index.search("hElLo").songList())
    }

    @Test
    fun Search_MatchesTitleArtistAlbumAndFileName() {
        assertEquals(listOf(adele), index.search("25").songList())
        assertEquals(listOf(noTags), index.search("recording_0412").songList())
        // Results come in Title order: 后来 (hou lai) sorts before 我等你 (wo deng ni).
        assertEquals(listOf(liu, zhang), index.search("爱情").songList())
    }

    @Test
    fun Search_Pinyin_DoesNotMatchChinese() {
        assertTrue(index.search("hou lai").songList().isEmpty())
        assertTrue(index.search("zhang").songList().isEmpty())
    }

    @Test
    fun Search_GroupsAlbumsAndArtistsByTheirOwnName() {
        val hit = index.search("爱情")
        assertEquals(listOf("愛情"), hit.albums.map { it.displayName })
        assertEquals(2, hit.albums.single().songCount)
        assertTrue(hit.artists.isEmpty())
        assertEquals(listOf("Adele"), index.search("adele").artists.map { it.displayName })
    }

    @Test
    fun Search_UnknownAlbumAndArtist_AreNotSearchable() {
        // The shared "Unknown album" and "Unknown artist" groups have no name to match, so typing the words finds nothing.
        val hit = index.search("unknown")
        assertTrue(hit.albums.isEmpty() && hit.artists.isEmpty())
    }

    @Test
    fun Search_SimplifiedAndTraditionalAlbum_IsOneGroup() {
        val mixed = SearchIndex.build(
            listOf(song("a.mp3", id = 1, album = "爱情"), song("b.mp3", id = 2, album = "愛情")).map { IndexedSong.of(it, keyer) },
            keyer,
        )
        assertEquals(1, mixed.search("愛情").albums.size)
        assertEquals(1, mixed.search("爱情").albums.size)
    }

    @Test
    fun Search_NoMatches_ReturnsEmptyGroups() {
        val none = index.search("qqqq")
        assertTrue(none.isEmpty)
    }

    @Test
    fun Search_BlankQuery_ReturnsEmptyGroups() {
        assertTrue(index.search("   ").isEmpty)
        assertTrue(index.search("").songList().isEmpty())
    }

    @Test
    fun Search_5000Songs_UnderBudget() {
        val many = (1..5000).map { song("track$it.mp3", id = it.toLong(), title = "歌 $it", artist = "artist ${it % 50}", album = "album ${it % 200}") }
        val big = SearchIndex.build(many.map { IndexedSong.of(it, keyer) }, keyer)
        big.search("track") // warm up
        val times = (1..20).map {
            val start = System.nanoTime()
            big.search("artist 7 歌")
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        val median = times[times.size / 2]
        println("search median over 5000 songs: $median ms")
        assertTrue("median $median ms is over the 100 ms budget", median < 100)
    }
}
