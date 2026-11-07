package app.songplayer.details

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songplayer.core.details.DetailsStatus
import app.songplayer.core.details.FieldChange
import app.songplayer.core.details.FieldDiff
import app.songplayer.core.details.DetailField
import app.songplayer.core.details.ChangeSource
import app.songplayer.core.details.TagChange
import app.songplayer.core.details.TagFields
import app.songplayer.core.details.TagFileStore
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.SongKey
import app.songplayer.core.library.song
import app.songplayer.core.log.AppLog
import app.songplayer.core.text.FakeKeyer
import app.songplayer.core.text.IndexedSong
import app.songplayer.library.db.AppDatabase
import app.songplayer.core.details.Candidate
import app.songplayer.core.details.ReleaseGroup
import app.songplayer.library.db.RoomDetailsSettings
import app.songplayer.library.db.RoomDetailsStore
import app.songplayer.library.db.RoomSongStore
import app.songplayer.library.db.toEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The song details on a real (in-memory) database with a fake tag writer: the store, the overlay, hand edits and Undo (Stories H3). */
@RunWith(AndroidJUnit4::class)
class DetailsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val keyer = FakeKeyer()
    private val store = RoomDetailsStore(db)
    private val songs = RoomSongStore(db, keyer, pendingDetails = { store.pending() })
    private val raw = song("a.mp3", id = 1, title = "Old", artist = "Old artist", album = "Old album")
    private val key = raw.key.value
    private var clock = 1_000_000L

    private class FakeFiles : TagFileStore {
        var refuse: NotWrittenReason? = null
        var fail = false
        var fileNow = TagFields("Old", "Old artist", "Old album")
        val writes = mutableListOf<TagChange>()

        override fun write(songKey: String, change: TagChange) {
            refuse?.let { throw DetailsError.NotWritten(it) }
            if (fail) throw DetailsError.WriteFailed()
            writes += change
            fun next(c: FieldChange, now: String?) = when (c) { is FieldChange.Set -> c.text; FieldChange.Remove -> null; FieldChange.Keep -> now }
            fileNow = TagFields(next(change.title, fileNow.title), next(change.artist, fileNow.artist), next(change.album, fileNow.album))
        }

        override fun read(songKey: String) = fileNow
    }

    private val files = FakeFiles()
    private val actions = DetailsActions(store, songs, files, AppLog(emptyList()), nowSec = { clock++ })

    init {
        db.songDao().upsert(listOf(IndexedSong.of(raw, keyer).toEntity()))
    }

    @After
    fun close() = db.close()

    private fun shown() = songs.indexed().single().song

    @Test
    fun EditDetails_Save_ShowsEverywhere_AndUndoRestores() {
        val result = actions.saveEdit(shown(), TagFields("New title", "Old artist", "Old album")) as SaveResult.Saved
        assertEquals("the overlay shows the edit at once, before the library has rescanned", "New title", shown().title)
        assertEquals("the sort key follows the new text", "new title", songs.indexed().single().keys.sortTitle.lowercase())
        assertEquals(DetailsStatus.MANUAL, store.status(key))

        // The library rescans and shows the file's value: the overlay ends, the library's own value is shown.
        db.songDao().upsert(listOf(IndexedSong.of(raw.copy(title = "New title"), keyer).toEntity()))
        actions.confirmWritten()
        assertEquals(0, store.pending().size)
        assertEquals("New title", shown().title)
        // A later change by another app is not hidden by an old overlay.
        db.songDao().upsert(listOf(IndexedSong.of(raw.copy(title = "Changed elsewhere"), keyer).toEntity()))
        assertEquals("Changed elsewhere", shown().title)
        db.songDao().upsert(listOf(IndexedSong.of(raw.copy(title = "New title"), keyer).toEntity()))

        assertEquals(UndoResult.Done, actions.undo(result.batch))
        assertEquals("the earlier title is back in the file", "Old", files.fileNow.title)
        assertEquals("and shown at once", "Old", shown().title)
        assertEquals("an undone hand edit is not listed again", 0, actions.recent().count { it.batch == result.batch })
    }

    @Test
    fun EditDetails_NoAccess_KeepsInAppAndOffersAllow() {
        files.refuse = NotWrittenReason.NoAccess
        val result = actions.saveEdit(shown(), TagFields("New title", "Old artist", "Old album"))
        assertEquals(SaveResult.InAppOnly((result as SaveResult.InAppOnly).batch, NotWrittenReason.NoAccess), result)
        assertEquals("shown from the app", "New title", shown().title)
        assertEquals(0, files.writes.size)

        files.refuse = null // the user allowed it
        val summary = actions.flush()
        assertEquals(1, summary.songsWritten)
        assertEquals("New title", files.fileNow.title)
    }

    @Test
    fun DetailsStore_FailedWrite_IsNotRetriedByItself() {
        files.fail = true
        assertTrue(actions.saveEdit(shown(), TagFields("X", "Old artist", "Old album")) is SaveResult.InAppOnly)
        files.fail = false
        assertEquals("an automatic flush leaves a failed write alone", 0, actions.flush().songsWritten)
        assertEquals(0, files.writes.size)
        assertEquals("when the user asks again it is tried", 1, actions.flush(key, includeFailed = true).songsWritten)
        assertEquals("X", files.fileNow.title)
    }

    @Test
    fun DetailsStore_ConditionalApply_DoesNotOverwriteManual() {
        val auto = listOf(FieldDiff(DetailField.Title, "Old", "Looked up"))
        actions.saveEdit(shown(), TagFields("By hand", "Old artist", "Old album"))
        assertEquals("a late automatic apply loses to the hand edit", false, store.applyAutomatic(key, auto, ChangeSource.Auto, clock++))
        assertEquals("By hand", shown().title)
        // A song that was never edited takes it.
        val other = RoomDetailsStore(db)
        assertEquals(true, other.applyAutomatic("another key", auto, ChangeSource.Auto, clock++))
        assertEquals(DetailsStatus.APPLIED, other.status("another key"))
    }

    @Test
    fun DetailsStore_UndoRefused_WhenAnotherAppChangedTheTag() {
        val saved = actions.saveEdit(shown(), TagFields("New title", "Old artist", "Old album")) as SaveResult.Saved
        files.fileNow = TagFields("Someone else's title", "Old artist", "Old album")
        assertEquals(UndoResult.FileChanged, actions.undo(saved.batch))
        assertEquals("nothing was changed", 1, store.batch(saved.batch).count { !it.undone })
    }

    @Test
    fun DetailsStore_UndoOfAnAutomaticFill_MakesTheSongRejected() {
        val diffs = listOf(FieldDiff(DetailField.Title, "Old", "Looked up"))
        assertTrue(store.applyAutomatic(key, diffs, ChangeSource.Auto, clock++))
        val batch = store.pending().single().batch
        actions.flush(key)
        assertEquals(UndoResult.Done, actions.undo(batch))
        assertEquals(DetailsStatus.REJECTED, store.status(key))
        assertEquals("a rejected song is not looked up again", false, store.applyAutomatic(key, diffs, ChangeSource.Auto, clock++))
    }

    @Test
    fun DetailsStore_WriteFailed_IsATypedError() {
        db.close()
        try {
            store.saveEdit(key, listOf(FieldDiff(DetailField.Title, "Old", "New")), clock)
            fail("expected StoreError.WriteFailed")
        } catch (e: StoreError.WriteFailed) {
            assertNull(e.message?.takeIf { it != e.code })
        }
    }

    @Test
    fun EditDetails_ClearingAField_RemovesItFromTheFile() {
        actions.saveEdit(shown(), TagFields("Old", "Old artist", null))
        assertEquals(FieldChange.Remove, files.writes.single().album)
        assertNull(files.fileNow.album)
        assertNull("shown as missing at once", shown().album)
    }

    @Test
    fun DetailsStore_TwoEditsOfOneField_ThenTheFileShowsTheNewest_LeavesNoOverlay() {
        files.refuse = NotWrittenReason.NoAccess // no access yet: both edits wait
        actions.saveEdit(shown(), TagFields("B", "Old artist", "Old album"))
        actions.saveEdit(shown(), TagFields("C", "Old artist", "Old album"))
        assertEquals("C", shown().title)
        files.refuse = null
        actions.flush()
        db.songDao().upsert(listOf(IndexedSong.of(raw.copy(title = "C"), keyer).toEntity())) // the library rescans
        actions.confirmWritten()
        assertEquals("no older text is left to lay over the file", 0, store.pending().size)
        db.songDao().upsert(listOf(IndexedSong.of(raw.copy(title = "Changed elsewhere"), keyer).toEntity()))
        assertEquals("and a later edit by another app is shown", "Changed elsewhere", shown().title)
    }

    @Test
    fun DetailsStore_UndoOfAnOlderBatch_IsRefusedWhileALaterChangeIsLive() {
        val first = actions.saveEdit(shown(), TagFields("B", "Old artist", "Old album")) as SaveResult.Saved
        actions.saveEdit(shown(), TagFields("C", "Old artist", "Old album"))
        assertEquals(UndoResult.LaterChange, actions.undo(first.batch))
        assertEquals("C", files.fileNow.title)
    }

    @Test
    fun DetailsStore_AListenerThatThrows_DoesNotBreakTheSave_AndCanBeRemoved() {
        var heard = 0
        store.onChanged { throw IllegalStateException("a listener that fails") }
        val handle = store.onChanged { heard++ }
        actions.saveEdit(shown(), TagFields("X", "Old artist", "Old album"))
        assertTrue("the others still hear it", heard >= 1)
        handle.close()
        val before = heard
        actions.saveEdit(shown(), TagFields("Y", "Old artist", "Old album"))
        assertEquals("a removed listener hears nothing", before, heard)
    }

    @Test
    fun DetailsStore_BatchIds_AreNeverUsedTwice() {
        val diffs = listOf(FieldDiff(DetailField.Title, "Old", "New"))
        val batches = (1..50).map { store.saveEdit(key, diffs, 5_000L) } // the same second, many times
        assertEquals(50, batches.toSet().size)
    }

    @Test
    fun DetailsStore_Recent_KeepsThirtyDays() {
        actions.saveEdit(shown(), TagFields("A", "Old artist", "Old album"))
        assertEquals(1, actions.recent().size)
        clock += 31L * 86_400
        assertEquals("a change older than the window drops out", 0, actions.recent().size)
        assertEquals(SongKey(key).value, key)
    }

    @Test
    fun DetailsStore_HeldCandidates_RoundTrip() {
        val candidates = listOf(
            Candidate("夜曲 \"live\"", listOf("周杰伦", "B"), 226, 0.91, listOf(ReleaseGroup("十一月的萧邦", "Album"))),
            Candidate("Other", emptyList(), null, 0.5, emptyList()),
        )
        assertTrue(store.hold(key, candidates, clock))
        assertEquals(DetailsStatus.HELD, store.status(key))
        assertEquals(candidates, store.heldCandidates(key))
        // A held song is decided: later automatic results change nothing.
        assertEquals(false, store.markNotFound(key, clock))
        assertEquals(false, store.applyAutomatic(key, listOf(FieldDiff(DetailField.Title, null, "x")), ChangeSource.Auto, clock))
        assertEquals(emptyList<Candidate>(), store.heldCandidates("another-key"))
    }

    @Test
    fun DetailsStore_NotFoundAndManual_AreNotOverwrittenByTheLookup() {
        store.saveEdit(key, listOf(FieldDiff(DetailField.Title, "Old", "Mine")), clock)
        assertEquals(false, store.hold(key, emptyList(), clock))
        assertEquals(false, store.markNotFound(key, clock))
        assertEquals(DetailsStatus.MANUAL, store.status(key))
        assertTrue(store.markNotFound("b-key", clock))
        assertEquals(DetailsStatus.NOT_FOUND, store.status("b-key"))
    }

    @Test
    fun DetailsSettings_DefaultsAndRoundTrip() {
        val settings = RoomDetailsSettings(db.settingDao())
        assertEquals(false, settings.enabled())
        assertEquals(false, settings.replaceExisting()) // replacing is off until the user turns it on
        assertEquals(true, settings.wifiOnly())
        settings.setReplaceExisting(true)
        assertEquals(true, settings.replaceExisting())
        assertEquals(0L, settings.pausedUntilMs())
        settings.setPausedUntilMs(1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, settings.pausedUntilMs())
    }

    @Test
    fun DetailsStore_ReviewDecisions_AreConditionalOnTheSongBeingHeld() {
        val cand = Candidate("T", listOf("A"), 200, 0.9, emptyList())
        assertTrue(store.hold(key, listOf(cand), clock))
        assertEquals(listOf(key), store.heldKeys())
        assertEquals(true, store.applyReview(key, listOf(FieldDiff(DetailField.Title, null, "T")), clock))
        assertEquals(DetailsStatus.APPLIED, store.status(key))
        assertEquals(emptyList<String>(), store.heldKeys())
        assertEquals("a second decision changes nothing", false, store.applyReview(key, listOf(FieldDiff(DetailField.Title, "T", "X")), clock))
        assertEquals(false, store.reject(key, clock))
        assertTrue(store.hold("c-key", listOf(cand), clock))
        assertEquals(true, store.reject("c-key", clock))
        assertEquals(DetailsStatus.REJECTED, store.status("c-key"))
        assertTrue(store.markNotFound("d-key", clock))
        assertEquals(listOf("d-key"), store.notFoundKeys())
    }

    @Test
    fun Decisions_Export_ThenImportIntoAnEmptyStore_RestoresThem_AndNeverChangesAnExistingOne() {
        assertTrue(store.markNotFound("n-key", clock))
        assertTrue(store.hold("h-key", emptyList(), clock))
        val exported = store.exportDecisions()
        assertEquals(setOf("n-key", "h-key"), exported.map { it.songKey }.toSet())

        val fresh = RoomDetailsStore(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build())
        assertEquals(2, fresh.importDecisions(exported))
        assertEquals(DetailsStatus.NOT_FOUND, fresh.status("n-key"))
        assertEquals(setOf("n-key", "h-key"), fresh.decidedKeys())
        // A second import adds nothing; a decision that is already there is never replaced.
        assertEquals(0, fresh.importDecisions(exported))
        assertEquals(0, fresh.importDecisions(listOf(exported.first { it.songKey == "n-key" }.copy(status = DetailsStatus.REJECTED))))
        assertEquals(DetailsStatus.NOT_FOUND, fresh.status("n-key"))
    }
}
