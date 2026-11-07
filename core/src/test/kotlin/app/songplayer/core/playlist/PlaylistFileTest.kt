package app.songplayer.core.playlist

import app.songplayer.core.details.DetailsStatus
import app.songplayer.core.errors.PlaylistFileError
import app.songplayer.core.library.SongKey
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PlaylistFileTest {
    private fun entry(n: Int) = PlaylistEntry(0, SongKey("external_primary:Music/$n.mp3"), "$n.mp3", 1_000L + n, 200_000L + n)

    private val sample = listOf(
        FilePlaylist("Driving", 1_700_000_000, listOf(entry(3), entry(1), entry(2))),
        FilePlaylist("\u6162\u6B4C \"quoted\" \\ back", 5, emptyList()),
    )

    private fun refused(text: String) {
        try { PlaylistFile.read(text); fail("accepted: ${text.take(60)}") } catch (_: PlaylistFileError.Invalid) {}
    }

    @Test
    fun PlaylistFile_RoundTrip_PreservesNamesOrderAndEntries() {
        assertEquals(sample, PlaylistFile.read(PlaylistFile.write(sample)).playlists)
    }

    @Test
    fun PlaylistFile_DamagedOrForeign_IsRefusedAndChangesNothing() {
        val good = PlaylistFile.write(sample)
        for (bad in listOf(
            "", "   ", "not json", "[]", "{}", good.dropLast(5), good + "x",
            good.replace("music-player-playlists", "other-app"), good.replace("\"version\":1", "\"version\":2"),
            """{"format":"music-player-playlists","version":1,"playlists":[]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":[]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":[{"key":"k","status":"pending","at":1}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":[{"key":"k","status":"applied","at":1,"cand":"x"}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":[{"key":"","status":"applied","at":1}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":"all"}""",
            """{"format":"music-player-playlists","version":1,"playlists":[],"details":[{"key":"k","status":"held","at":1}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[{"name":"  ","created":1,"songs":[]}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[{"name":"A","created":-1,"songs":[]}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[{"name":"A","created":1,"songs":[{"key":"k","file":"","size":1,"ms":1}]}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[{"name":"A","created":1,"songs":[{"key":"k","file":"f","size":"big","ms":1}]}]}""",
            """{"format":"music-player-playlists","version":1,"playlists":[{"name":"A","created":1,"songs":[7]}]}""",
        )) refused(bad)
    }

    @Test
    fun PlaylistFile_ASongTwiceInOnePlaylist_IsKeptOnce() {
        val twice = listOf(FilePlaylist("P", 1, listOf(entry(1), entry(2), entry(1))))
        assertEquals(listOf(entry(1), entry(2)), PlaylistFile.read(PlaylistFile.write(twice)).playlists.single().entries)
    }

    @Test
    fun PlaylistFile_NewOnes_SkipsWhatIsAlreadyThere_SoImportingTwiceAddsNothing() {
        assertEquals(sample, PlaylistFile.newOnes(emptyList(), sample))
        assertEquals(emptyList<FilePlaylist>(), PlaylistFile.newOnes(sample, sample))
        // Same name, different songs: it is another playlist and is added.
        val other = FilePlaylist("Driving", 1, listOf(entry(9)))
        assertEquals(listOf(other), PlaylistFile.newOnes(sample, listOf(other)))
        // The same playlist twice inside the file is added once.
        assertEquals(1, PlaylistFile.newOnes(emptyList(), listOf(sample[0], sample[0])).size)
    }

    @Test
    fun PlaylistFile_ThousandsOfSongs_RoundTripQuickly() {
        val big = listOf(FilePlaylist("Big", 1, (1..5_000).map { entry(it) }))
        val started = System.nanoTime()
        assertEquals(big, PlaylistFile.read(PlaylistFile.write(big)).playlists)
        assertEquals(true, (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    private val decisions = listOf(
        FileDecision("external_primary:Music/1.mp3", "applied", 100, null),
        FileDecision("external_primary:Music/2.mp3", "notFound", 101, null),
        FileDecision("external_primary:Music/3.mp3", "held", 102, "[{\"t\":\"x\"}]"),
        FileDecision("external_primary:Music/4.mp3", "rejected", 103, null),
        FileDecision("external_primary:Music/5.mp3", "manual", 104, null),
    )

    @Test
    fun PlaylistFile_Decisions_RoundTrip_AlongsideThePlaylists_OrOnTheirOwn() {
        assertEquals(FileContent(sample, decisions), PlaylistFile.read(PlaylistFile.write(sample, decisions)))
        assertEquals(FileContent(emptyList(), decisions), PlaylistFile.read(PlaylistFile.write(emptyList(), decisions)))
    }

    @Test
    fun PlaylistFile_AFileWithoutDetails_IsStillRead_AsPlaylistsOnly() {
        val old = """{"format":"music-player-playlists","version":1,"playlists":[{"name":"A","created":1,"songs":[]}]}"""
        assertEquals(FileContent(listOf(FilePlaylist("A", 1, emptyList())), emptyList()), PlaylistFile.read(old))
    }

    @Test
    fun PlaylistFile_ADecisionTwice_IsKeptOnce() {
        val twice = decisions + decisions[0].copy(status = "rejected")
        assertEquals(decisions, PlaylistFile.read(PlaylistFile.write(emptyList(), twice)).decisions)
    }

    @Test
    fun PlaylistFile_EveryStatusTheLookupCanKeep_IsAcceptedOnImport() {
        val all = listOf(
            DetailsStatus.APPLIED, DetailsStatus.MANUAL, DetailsStatus.REJECTED, DetailsStatus.NOT_FOUND,
        ).mapIndexed { i, s -> FileDecision("k$i", s, 1, null) } + FileDecision("kh", DetailsStatus.HELD, 1, "[]")
        assertEquals(all, PlaylistFile.read(PlaylistFile.write(emptyList(), all)).decisions)
    }
}
