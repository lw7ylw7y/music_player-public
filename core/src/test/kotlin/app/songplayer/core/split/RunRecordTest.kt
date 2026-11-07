package app.songplayer.core.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecordTest {
    private val source = SourceIdentity(42, 477_000_000, 1_700_000_000, "abcd1234")

    private fun record(written: Int = 0, phase: RunPhase = RunPhase.Writing, id: String = "r1", at: Long = 1000, total: Int = 12) = RunRecord(
        id = id, source = source, sourceName = "我等你 \"專輯\" \\ big.mp3",
        cuts = List(total - 1) { RunCut(100.5 * (it + 1), if (it % 2 == 0) 2.5 else null, it % 3 != 0, it % 3 == 0) },
        folder = "Music/Albums/", volume = "external_primary", phase = phase,
        written = List(written) { RunSong(it + 1, "big ${"%02d".format(it + 1)}.mp3", 900L + it, 1_000_000L * (it + 1)) }, createdSec = at,
    )

    /** Files in memory; [crashBeforeRename] makes the next write stop half way, as a killed process would. */
    private class FakeFiles : AtomicFiles {
        val store = HashMap<String, ByteArray>()
        var crashNext = false
        override fun writeAtomic(name: String, bytes: ByteArray) {
            if (crashNext) { crashNext = false; store["$name.tmp"] = bytes.copyOf(bytes.size / 2); throw java.io.IOException("killed") }
            store[name] = bytes
        }
        override fun read(name: String) = store[name]
        override fun delete(name: String) { store.remove(name) }
        override fun names() = store.keys.filter { !it.endsWith(".tmp") }
    }

    @Test
    fun RunRecord_JsonRoundTrip() {
        for (r in listOf(record(), record(written = 5), record(written = 12, phase = RunPhase.Done), record(total = 2))) {
            assertEquals(r, RunRecord.fromJson(r.toJson()))
        }
    }

    @Test
    fun RunRecord_Damaged_IsNoRecord() {
        val good = record(written = 3).toJson()
        val damaged = listOf("", "not json", "{}", good.dropLast(10), good.replace("\"v\":1", "\"v\":2"), good.replace("\"phase\":\"Writing\"", "\"phase\":\"Gone\""),
            good.replace("\"src\":{", "\"src\":["), "[]", good.replace("\"cuts\":[[", "\"cuts\":[1,["))
        for (text in damaged) assertNull("text: ${text.take(40)}", RunRecord.fromJson(text))
    }

    @Test
    fun RunRecord_EveryTruncation_AndBrokenValues_AreNoRecord() {
        val good = record(written = 2).toJson()
        for (n in 0 until good.length) assertNull("prefix $n", RunRecord.fromJson(good.take(n)))
        assertNull(RunRecord.fromJson("$good trailing"))
        assertNull(RunRecord.fromJson(good.replace("\"at\":1000", "\"at\":\"1000\"")))      // a number given as a string
        assertNull(RunRecord.fromJson(good.replace("\"folder\":\"Music/Albums/\",", "")))     // a missing field
        assertNull(RunRecord.fromJson(good.replace("\"at\":1000", "\"at\":null")))           // null where a number belongs
        assertNull(RunRecord.fromJson(good.replace("\"v\":1", "\"v\":1.5")))
        assertNull(RunRecord.fromJson(good.replace("[100.5,", "[300.5,")))                       // cuts out of order
        assertNull(RunRecord.fromJson(good.replace("[1,\"big 01.mp3\"", "[13,\"big 01.mp3\"")))  // a written song numbered outside 1 to the count
        assertEquals(record(written = 2), RunRecord.fromJson(good.replace("\"v\":1,", "\"v\":1,\"extra\":[1,2],"))) // unknown fields are ignored
    }

    @Test
    fun RunRecord_NotWritableOrReadableValues_FailLoudlyWhenBuilt() {
        for (bad in listOf({ record().copy(cuts = listOf(RunCut(Double.NaN, null, true, true))) }, { record().copy(cuts = listOf(RunCut(Double.POSITIVE_INFINITY, null, true, true))) },
            { record(total = 3).copy(written = listOf(RunSong(1, "a.mp3", 1, 1), RunSong(1, "b.mp3", 2, 2))) }, { record(total = 3).copy(written = listOf(RunSong(9, "a.mp3", 1, 1))) })) {
            try { bad(); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
        }
        assertFalse(record(written = 3).toString().contains("big"))   // the string form has counts only, never a file name
    }

    @Test
    fun RunStore_LatestFor_TakesTheNewest_TiesByIdAndSkipsDamaged() {
        val files = FakeFiles()
        val store = RunStore(files)
        store.save(record(id = "a", at = 5)); store.save(record(id = "b", at = 5)); store.save(record(id = "c", at = 3))
        files.store["z.json"] = "{".toByteArray()
        assertEquals("b", store.latestFor(42)!!.id)
        assertNull(store.load("a-other"))
        files.store["liar.json"] = record(id = "someone-else").toJson().toByteArray()
        assertNull(store.load("liar")) // a record whose id is not its file name is not trusted
    }

    @Test
    fun RunRecord_HoldsNoAudioNoTitlesNoTags() {
        val keys = Regex("\"([a-z]+)\":").findAll(record(written = 2).toJson()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("v", "id", "src", "m", "s", "t", "h", "name", "folder", "volume", "phase", "at", "cuts", "written"), keys)
    }

    @Test
    fun RunStore_AKilledWrite_LeavesTheEarlierRecordWhole() {
        val files = FakeFiles()
        val store = RunStore(files)
        store.save(record(written = 3))
        files.crashNext = true
        try { store.save(record(written = 4)); org.junit.Assert.fail() } catch (_: java.io.IOException) {}
        assertEquals(3, store.load("r1")!!.written.size) // the half-written temporary file is never read
        assertEquals(record(written = 3), store.latestFor(42))
    }

    @Test
    fun RunStore_All_ListsReadableRecordsNewestFirst_AndSkipsDamagedOnes() {
        val files = FakeFiles()
        val store = RunStore(files)
        store.save(record(id = "a", at = 100)); store.save(record(id = "b", at = 300)); store.save(record(id = "c", at = 200))
        files.store["broken.json"] = "{".toByteArray()
        assertEquals(listOf("b", "c", "a"), store.all().map { it.id })
    }

    @Test
    fun RunStore_Expire_RemovesOnlyOldReadableRecords() {
        val files = FakeFiles()
        val store = RunStore(files)
        store.save(record(id = "old", at = 0)); store.save(record(id = "new", at = 40L * 86_400))
        files.store["broken.json"] = "{".toByteArray()
        files.store["future.json"] = record(id = "future", at = 0).toJson().replace("\"v\":1", "\"v\":2").toByteArray()
        assertEquals(1, store.expire(nowSec = 31L * 86_400)) // only the readable record older than 30 days goes
        assertEquals(setOf("new.json", "broken.json", "future.json"), files.names().toSet()) // unreadable records may be the only reference to written songs
        assertEquals(0, store.expire(nowSec = 30L * 86_400 + 0)) // 30 days exactly is not older
        assertNull(store.latestFor(7)) // nothing for another file
    }

    @Test
    fun SplitRecovery_EveryStep_ContinuesOnce() {
        // Kill a 12-song run before the first song, between every pair and after the last: a continued run writes exactly the missing ones.
        for (killedAfter in 0..12) {
            val r = record(written = killedAfter, phase = if (killedAfter == 12) RunPhase.Done else RunPhase.Writing)
            val existing = r.written.associate { it.mediaId to it.size }
            val decision = SplitRecovery.decide(r, source, existing)
            if (killedAfter == 12) { assertEquals(Recovery.NothingToContinue, decision); continue }
            decision as Recovery.Continue
            assertEquals(killedAfter, decision.written); assertEquals(12, decision.total)
            assertEquals(((killedAfter + 1)..12).toList(), decision.remaining)
            // Writing the remaining ones gives every number exactly once.
            val all = r.written.map { it.number } + decision.remaining
            assertEquals((1..12).toList(), all.sorted())
        }
    }

    @Test
    fun SplitRecovery_ReviewingPhase_CanContinueWithNothingWritten_AndADoneRunNeverContinues() {
        val reviewing = record(phase = RunPhase.Reviewing)
        assertEquals(Recovery.Continue(0, 12, (1..12).toList()), SplitRecovery.decide(reviewing, source, emptyMap()))
        val done = record(written = 12, phase = RunPhase.Done)
        assertEquals(Recovery.NothingToContinue, SplitRecovery.decide(done, source.copy(size = 1), emptyMap())) // a finished run is left to "Remove" and "Delete the original"
        assertEquals(12, SplitRecovery.songsToRemove(done, done.written.map { it.mediaId }.toSet()).size)
    }

    @Test
    fun SplitRecovery_ASongTheUserDeleted_IsWrittenAgain_AndNothingElseIsTouched() {
        val r = record(written = 5)
        val gone = r.written.filter { it.mediaId != 903L }.associate { it.mediaId to it.size }.toMutableMap() // song 4 (media id 903) was deleted by the user
        gone[904L] = 1L // song 5's file was replaced by another of a different size: not the song that was written
        val d = SplitRecovery.decide(r, source, gone) as Recovery.Continue
        assertEquals(3, d.written)
        assertEquals(listOf(4, 5) + (6..12).toList(), d.remaining)
        assertEquals(listOf(900L, 901L, 902L, 904L), SplitRecovery.songsToRemove(r, gone.keys)) // "Remove these songs" skips the deleted one
    }

    @Test
    fun SplitRecovery_SourceChanged_RefusesToContinue() {
        val r = record(written = 5)
        for (other in listOf(source.copy(size = 1), source.copy(modifiedSec = 5), source.copy(headHash = "other"), source.copy(mediaId = 9))) {
            assertEquals(Recovery.SourceChanged, SplitRecovery.decide(r, other, mapOf(900L to 1_000_000L)))
        }
        assertEquals(Recovery.NothingToContinue, SplitRecovery.decide(null, source, emptyMap()))
        assertFalse(SplitRecovery.decide(r, source, mapOf(900L to 1_000_000L)) is Recovery.SourceChanged)
        assertTrue(SplitRecovery.songsToRemove(record(), emptySet()).isEmpty())
    }
}
