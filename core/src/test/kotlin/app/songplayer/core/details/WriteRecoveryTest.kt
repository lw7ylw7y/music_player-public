package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteRecoveryTest {
    private val change = TagChange(FieldChange.Set("New Title"), FieldChange.Set("New Artist"), FieldChange.Set("New Album"))

    private class Case(val original: ByteArray, val intended: ByteArray, val plan: WritePlan, val record: JournalRecord, val backup: ByteArray)

    private fun case(file: ByteArray): Case {
        val h = Id3.readHeader(file)
        val existing = h?.let { Id3.parse(it, file.copyOfRange(0, it.totalSize)) }
        val plan = Id3.plan(existing, change)
        val intended = Id3.apply(file, plan)
        val mode = if (plan is WritePlan.InPlace) WriteMode.InPlace else WriteMode.Rewrite
        val backup = if (plan is WritePlan.InPlace) file.copyOfRange(0, plan.tag.size) else file
        val record = JournalRecord(mode, file.size.toLong(), WriteRecovery.sha256(file), intended.size.toLong(), WriteRecovery.sha256(intended))
        return Case(file, intended, plan, record, backup)
    }

    /** What the file holds when the app is killed after [k] bytes of the write have reached it. */
    private fun cutAt(c: Case, k: Int): ByteArray = when (val p = c.plan) {
        is WritePlan.InPlace -> c.original.copyOf().also { p.tag.copyInto(it, 0, 0, k.coerceAtMost(p.tag.size)) }
        is WritePlan.Rewrite -> c.intended.copyOfRange(0, k.coerceAtMost(c.intended.size)) // opened with truncate, then written from the start
    }

    private fun target(current: ByteArray, c: Case, refuse: Boolean = false) = object : RecoveryTarget {
        var restored: ByteArray? = null
        override fun state() = FileState(current.size.toLong(), WriteRecovery.sha256(current))
        override fun restore() {
            if (refuse) throw DetailsError.NotWritten(NotWrittenReason.NoAccess)
            restored = WriteRecovery.restore(current, c.backup, c.record.mode)
        }
    }

    @Test
    fun WriteJournal_CutAtEveryByte_RestoresTheOriginal() {
        val files = Id3Shapes.shapes().map { it.name to it.file }
        for ((name, file) in files) {
            val c = case(file)
            val length = if (c.plan is WritePlan.InPlace) (c.plan as WritePlan.InPlace).tag.size else c.intended.size
            for (k in 0..length) {
                val current = cutAt(c, k)
                val t = target(current, c)
                assertEquals("$name cut $k", RecoveryOutcome.Cleared, WriteRecovery.recover(c.record, t))
                val result = t.restored ?: current
                assertTrue("$name cut $k: neither the original nor the intended bytes", result.contentEquals(c.original) || result.contentEquals(c.intended))
                // A file that already holds the intended bytes (the rest of an in-place tag is identical padding) counts as finished;
                // every other cut ends as the original.
                if (!current.contentEquals(c.intended)) assertArrayEquals("$name cut $k", c.original, result)
            }
        }
    }

    @Test
    fun WriteJournal_RestoreRefused_KeepsRowAndBackup() {
        val c = case(Id3Shapes.tag(3, listOf(Id3Shapes.textFrame(3, "TIT2", "old")), padding = 0) + Id3Shapes.audio())
        val half = cutAt(c, 50)
        val t = target(half, c, refuse = true)
        assertEquals(RecoveryOutcome.Kept, WriteRecovery.recover(c.record, t))
        assertEquals("nothing was changed by the refused restore", null, t.restored)
    }

    @Test
    fun WriteJournal_FileAsOriginalOrAsIntended_NeedsNoRestore() {
        val c = case(Id3Shapes.audio())
        assertEquals(RecoveryAction.NothingToDo, WriteRecovery.decide(c.record, c.original.size.toLong(), WriteRecovery.sha256(c.original)))
        assertEquals(RecoveryAction.Complete, WriteRecovery.decide(c.record, c.intended.size.toLong(), WriteRecovery.sha256(c.intended)))
        assertEquals(RecoveryAction.Restore, WriteRecovery.decide(c.record, 3, "x"))
    }

    @Test
    fun TagWrite_LowSpaceOrHugeFile_WritesNothing() {
        assertEquals(false, WriteLimits.hasRoom(freeBytes = 10_000L, backupBytes = 5_000L))
        assertEquals(true, WriteLimits.hasRoom(freeBytes = 15_000L, backupBytes = 5_000L))
        assertEquals("a rewrite over the limit", false, WriteLimits.mayRewrite(WriteLimits.MAX_REWRITE_BYTES + 1))
        assertEquals("an unsplit compilation is fine when only its small tag is backed up and written in place", true, WriteLimits.hasRoom(freeBytes = 1L shl 20, backupBytes = 2_048L))
    }
}
