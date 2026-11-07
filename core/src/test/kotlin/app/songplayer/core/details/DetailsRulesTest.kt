package app.songplayer.core.details

import app.songplayer.core.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsRulesTest {
    private val base = song("a.mp3", title = "Old", artist = "Old artist", album = "Old album")
    private val key = base.key.value

    private fun change(
        id: Long, field: DetailField, new: String?, previous: String? = null, write: WriteState = WriteState.Waiting,
        undone: Boolean = false, source: ChangeSource = ChangeSource.Manual, at: Long = 1_000_000, batch: Long = id, songKey: String = key,
    ) = DetailChange(id, batch, songKey, field, previous, new, source, at, write, undone)

    @Test
    fun DetailsRules_Overlay_AppliesOnlyWhileUnwritten() {
        val waiting = change(1, DetailField.Title, "New")
        assertEquals("New", DetailsRules.overlay(base, listOf(waiting)).title)
        assertEquals("Failed rows still show", "New", DetailsRules.overlay(base, listOf(waiting.copy(write = WriteState.Failed))).title)
        assertEquals("In the file but not yet shown by the library: the overlay stays", "New", DetailsRules.overlay(base, listOf(waiting.copy(write = WriteState.InFile))).title)
        assertEquals("Confirmed: the library's own value is shown", "Old", DetailsRules.overlay(base, listOf(waiting.copy(write = WriteState.Confirmed))).title)
        assertEquals("Undone: never shown", "Old", DetailsRules.overlay(base, listOf(waiting.copy(undone = true))).title)
        assertEquals("another song's change", "Old", DetailsRules.overlay(base, listOf(waiting.copy(songKey = "other"))).title)
        val newest = DetailsRules.overlay(base, listOf(change(1, DetailField.Title, "First"), change(2, DetailField.Title, "Second")))
        assertEquals("the newest change of a field wins", "Second", newest.title)
        assertEquals("a removed field shows as missing", null, DetailsRules.overlay(base, listOf(change(3, DetailField.Album, null))).album)
    }

    @Test
    fun DetailsRules_Diff_OnlyChangedFields_BlankMeansNoValue() {
        val diff = DetailsRules.diff(TagFields("Old", "Old artist", "Old album"), TagFields(" New ", "Old artist", "  "))
        assertEquals(listOf(FieldDiff(DetailField.Title, "Old", "New"), FieldDiff(DetailField.Album, "Old album", null)), diff)
        assertTrue(DetailsRules.diff(TagFields("A", null, null), TagFields("A", "", null)).isEmpty())
    }

    @Test
    fun DetailsRules_ConfirmedByTheFile_WhenTheLibraryShowsTheNewValue() {
        val changes = listOf(change(1, DetailField.Title, "New"), change(2, DetailField.Artist, "Other artist"))
        val written = base.copy(title = "New")
        assertEquals(listOf(1L), DetailsRules.confirmedByTheFile(changes) { written })
        assertEquals(emptyList<Long>(), DetailsRules.confirmedByTheFile(changes) { null })
    }

    @Test
    fun DetailsRules_Confirming_RetiresOlderChangesOfTheSameField() {
        // A to B, then B to C, both waiting; the file now has C: the older change must not stay as an overlay.
        val older = change(1, DetailField.Title, "B")
        val newer = change(2, DetailField.Title, "C")
        val other = change(3, DetailField.Artist, "Someone")
        val shown = base.copy(title = "C")
        assertEquals(listOf(1L, 2L), DetailsRules.confirmedByTheFile(listOf(older, newer, other)) { shown }.sorted())
        // A newer change is not retired by an older one that happens to match.
        assertEquals(listOf(1L), DetailsRules.confirmedByTheFile(listOf(older, newer)) { base.copy(title = "B") })
    }

    @Test
    fun DetailsRules_Undo_RefusedWhileALaterChangeOfTheSameFieldIsLive() {
        val first = change(1, DetailField.Title, "B", previous = "A", write = WriteState.InFile)
        val later = change(2, DetailField.Title, "C", previous = "B")
        assertEquals(UndoPlan.LaterChange, DetailsRules.undo(listOf(first), TagFields("B", null, null), listOf(later)))
        assertTrue(DetailsRules.undo(listOf(first), TagFields("B", null, null), listOf(later.copy(undone = true))) is UndoPlan.Apply)
        assertTrue("another field does not count", DetailsRules.undo(listOf(first), TagFields("B", null, null), listOf(change(3, DetailField.Album, "X"))) is UndoPlan.Apply)
    }

    @Test
    fun DetailsRules_TagChange_CarriesTheNewestValuePerField() {
        val tc = DetailsRules.tagChange(listOf(change(1, DetailField.Title, "First"), change(2, DetailField.Title, "Second"), change(3, DetailField.Album, null)))
        assertEquals(TagChange(FieldChange.Set("Second"), FieldChange.Keep, FieldChange.Remove), tc)
    }

    @Test
    fun DetailsRules_Undo_RefusedWhenTheFileChanged() {
        val written = change(1, DetailField.Title, "New", previous = "Old", write = WriteState.InFile)
        assertEquals(UndoPlan.FileChanged, DetailsRules.undo(listOf(written), TagFields("Changed elsewhere", null, null)))
        val ok = DetailsRules.undo(listOf(written), TagFields("New", null, null)) as UndoPlan.Apply
        assertEquals(listOf(FieldDiff(DetailField.Title, "New", "Old")), ok.inverse)
        assertTrue(ok.writesFile)
        // A change that never reached the file is dropped without touching it, whatever the file holds.
        val unwritten = change(2, DetailField.Artist, "X", previous = "Old artist")
        val plan = DetailsRules.undo(listOf(unwritten), TagFields("anything", null, null)) as UndoPlan.Apply
        assertEquals(emptyList<FieldDiff>(), plan.inverse)
        assertEquals(false, plan.writesFile)
        assertEquals(listOf(2L), plan.undoneIds)
    }

    @Test
    fun DetailsRules_RecentList_KeepsThirtyDays() {
        val now = 100L * 86_400
        val recent = change(1, DetailField.Title, "A", at = now - 29 * 86_400, batch = 1)
        val old = change(2, DetailField.Title, "B", at = now - 31 * 86_400, batch = 2)
        val undoneOne = change(3, DetailField.Title, "C", at = now, batch = 3, undone = true)
        val inverse = change(4, DetailField.Title, "D", at = now, batch = 4, source = ChangeSource.Undo)
        val two = listOf(change(5, DetailField.Title, "E", at = now - 10, batch = 5), change(6, DetailField.Album, "F", at = now - 10, batch = 5))
        val list = DetailsRules.recent(listOf(recent, old, undoneOne, inverse) + two, now)
        assertEquals(listOf(5L, 1L), list.map { it.batch })
        assertEquals("the two fields saved together are one row", 2, list.first().changes.size)
    }

    @Test
    fun DetailsRules_ManualWinsOverPendingAuto_ByTheStatusRule() {
        assertEquals(true, DetailsStatus.mayApplyAutomatic(null))
        assertEquals(true, DetailsStatus.mayApplyAutomatic("pending"))
        assertEquals(false, DetailsStatus.mayApplyAutomatic(DetailsStatus.MANUAL))
        assertEquals(false, DetailsStatus.mayApplyAutomatic(DetailsStatus.REJECTED))
        assertEquals(false, DetailsStatus.mayApplyAutomatic(DetailsStatus.APPLIED))
    }
}
