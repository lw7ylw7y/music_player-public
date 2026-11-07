package app.songplayer.core.split

import app.songplayer.core.errors.SplitError
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitWriterTest {
    private val original = ByteArray(1200) { (it * 7 + 3).toByte() }
    private val source = ByteArraySource(original)

    /** Four songs of 300 bytes; song 2 has a 10-byte header (a VBR source). */
    private val spans = List(4) { k ->
        SongSpan(k + 1, k * 10, k * 10 + 10, k * 300L, k * 300L + 300, if (k == 1) ByteArray(10) { 9 } else null)
    }

    private fun record(written: List<RunSong> = emptyList(), phase: RunPhase = RunPhase.Reviewing) = RunRecord(
        id = "r1", source = SourceIdentity(5, 1200, 1000, "h"), sourceName = "big.mp3",
        cuts = listOf(RunCut(10.0, null, true, false), RunCut(20.0, null, true, false), RunCut(30.0, null, true, false)),
        folder = "Music/", volume = "external_primary", phase = phase, written = written, createdSec = 1,
    )

    private fun writer(sink: SongSink, files: Files = Files()) = SplitWriter(sink, RunStore(files)) to files

    @Test
    fun SplitWriter_WritesEverySongInOrder_AndSavesTheRecordAfterEach() {
        val sink = FakeSink()
        val (w, files) = writer(sink)
        val result = w.run(record(), spans, source)
        assertTrue(result.finished)
        assertEquals(RunPhase.Done, result.record.phase)
        assertEquals(listOf("big 01.mp3", "big 02.mp3", "big 03.mp3", "big 04.mp3"), sink.visible.keys.toList())
        assertArrayEquals(original.copyOfRange(0, 300), sink.visible["big 01.mp3"])
        assertArrayEquals(ByteArray(10) { 9 } + original.copyOfRange(300, 600), sink.visible["big 02.mp3"])   // header first, then the range
        assertEquals(listOf(1, 2, 3, 4), result.record.written.map { it.number })
        assertEquals(spans.map { it.bytes }, result.record.written.map { it.size })
        assertEquals(1 + 4 + 1, files.saves)                      // at the start, after each song, at the end
        assertEquals(result.record, RunStore(files).load("r1"))
    }

    @Test
    fun SplitWriter_KilledMidSong_KeepsEarlierSongs_AndTheRerunWritesOnlyTheRest() {
        val sink = FakeSink().apply { killAtSong = "big 03.mp3" }
        val (w, files) = writer(sink)
        try { w.run(record(), spans, source); fail() } catch (_: IOException) {}
        assertEquals(listOf("big 01.mp3", "big 02.mp3"), sink.visible.keys.toList())
        assertEquals("big 03.mp3", sink.pending)                  // the unfinished file is not visible
        assertEquals(listOf(1, 2), RunStore(files).load("r1")!!.written.map { it.number })

        sink.killAtSong = null; sink.writes = 0
        val result = w.run(RunStore(files).load("r1")!!, spans, source)
        assertEquals(1, sink.removedPending)                     // the leftover went first
        assertEquals(2, sink.writes)                              // songs 3 and 4 only: nothing is written twice
        assertTrue(result.finished)
        assertEquals(4, sink.visible.size)
        assertArrayEquals(original.copyOfRange(600, 900), sink.visible["big 03.mp3"])
    }

    @Test
    fun SplitWriter_ASongFinishedJustBeforeTheRecordWasSaved_CountsAsWritten() {
        val sink = FakeSink().apply { visible["big 01.mp3"] = original.copyOfRange(0, 300) }
        val (w, _) = writer(sink)
        val result = w.run(record(), spans, source)
        assertEquals(3, sink.writes)
        assertEquals(listOf(1, 2, 3, 4), result.record.written.map { it.number })
    }

    @Test
    fun SplitWriter_NameInUseWithOtherContent_StopsBeforeWritingAnything() {
        val sink = FakeSink().apply { visible["big 03.mp3"] = ByteArray(5) }
        val (w, files) = writer(sink)
        try { w.run(record(), spans, source); fail() } catch (e: SplitError.NameInUse) { assertEquals("big 03.mp3", e.name); assertEquals("SPLIT_NAME_IN_USE", e.message) }
        assertEquals(0, sink.writes)
        assertEquals(1, sink.visible.size)
        assertEquals(0, files.saves)
    }

    @Test
    fun SplitWriter_AFileMadeBetweenTheCheckAndTheInsert_StopsTheRunAndIsNeverOverwritten() {
        val sink = object : SongSink by FakeSink() {
            val inner = FakeSink()
            var calls = 0
            override fun freeBytes() = inner.freeBytes()
            override fun removePending(names: List<String>) = inner.removePending(names)
            override fun write(name: String, header: ByteArray?, source: ByteSource, start: Long, end: Long, cancelled: () -> Boolean) = inner.write(name, header, source, start, end, cancelled)
            override fun find(name: String): FinishedSong? {
                // Free during the first pass, taken by the user's own file before song 2 is inserted.
                if (name == "big 02.mp3" && inner.visible.containsKey("big 01.mp3")) inner.visible[name] = ByteArray(1)
                return inner.find(name)
            }
        }
        val (w, _) = writer(sink)
        try { w.run(record(), spans, source); fail() } catch (e: SplitError.NameInUse) { assertEquals("big 02.mp3", e.name) }
        assertEquals(listOf("big 01.mp3", "big 02.mp3"), sink.inner.visible.keys.toList())
        assertEquals(1, sink.inner.visible["big 02.mp3"]!!.size)  // the user's file is untouched
    }

    @Test
    fun SplitWriter_TooLittleSpace_RefusesToStart_WritingNothing() {
        val sink = FakeSink(free = 1200 + 10 + SplitWriter.SPACE_MARGIN_BYTES - 1)
        val (w, files) = writer(sink)
        try { w.run(record(), spans, source); fail() } catch (e: SplitError.NoSpace) { assertEquals("SPLIT_NO_SPACE", e.code) }
        assertEquals(0, sink.writes)
        assertEquals(0, files.saves)
        sink.free += 1
        assertTrue(w.run(record(), spans, source).finished)
    }

    @Test
    fun SplitWriter_RunningOutMidRun_StopsCleanly_AndKeepsTheFinishedSongs() {
        val sink = FakeSink().apply { runOutAtSong = "big 03.mp3" }
        val (w, files) = writer(sink)
        try { w.run(record(), spans, source); fail() } catch (_: SplitError.NoSpace) {}
        assertNull(sink.pending)
        assertEquals(listOf(1, 2), RunStore(files).load("r1")!!.written.map { it.number })
        assertEquals(RunPhase.Writing, RunStore(files).load("r1")!!.phase)
    }

    @Test
    fun SplitWriter_Cancel_StopsAfterTheCurrentSong_OrAtOnce_AndKeepsWhatIsDone() {
        for (stopAfter in 0..4) {
            val sink = FakeSink()
            val (w, files) = writer(sink)
            val result = w.run(record(), spans, source, cancelled = { sink.visible.size >= stopAfter && stopAfter < 4 })
            assertEquals("after $stopAfter", stopAfter == 4, result.finished)
            assertEquals(stopAfter, sink.visible.size)
            assertNull(sink.pending)
            assertEquals(stopAfter, RunStore(files).load("r1")!!.written.size)
            if (!result.finished) assertEquals(RunPhase.Writing, result.record.phase)
        }
    }

    @Test
    fun SplitWriter_ASongTheUserDeleted_IsWrittenAgain_AndNothingElseIsTouched() {
        val sink = FakeSink()
        val (w, _) = writer(sink)
        val first = w.run(record(), spans, source).record
        val keep = sink.visible.getValue("big 04.mp3")
        sink.visible.remove("big 02.mp3")
        sink.writes = 0
        val again = w.run(first.copy(phase = RunPhase.Writing), spans, source)
        assertEquals(1, sink.writes)
        assertEquals(4, sink.visible.size)
        assertTrue(keep === sink.visible["big 04.mp3"])
        assertTrue(again.finished)
    }

    @Test
    fun SongCopy_OriginalEndsEarly_OrCannotBeRead_IsSourceChanged_AndNothingIsPublished() {
        val short = ByteArraySource(ByteArray(100))
        try { SongCopy.copy(short, 0, 300, null, ByteArrayOutputStream(), { false }); fail() } catch (e: SplitError.SourceChanged) { assertEquals("SPLIT_SOURCE_CHANGED", e.code) }
        val broken = object : ByteSource { override val length = 1000L; override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int = throw IOException("gone") }
        try { SongCopy.copy(broken, 0, 300, null, ByteArrayOutputStream(), { false }); fail() } catch (_: SplitError.SourceChanged) {}
    }

    @Test
    fun SongCopy_CopiesLargeRangesInChunks_AndStopsWhenCancelled() {
        val big = ByteArray(1_000_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertTrue(SongCopy.copy(ByteArraySource(big), 100, 900_000, ByteArray(3) { 1 }, out, { false }))
        assertArrayEquals(ByteArray(3) { 1 } + big.copyOfRange(100, 900_000), out.toByteArray())
        var polls = 0
        assertFalse(SongCopy.copy(ByteArraySource(big), 0, 1_000_000, null, ByteArrayOutputStream(), { ++polls > 2 }))
    }

    @Test
    fun FolderRule_OriginalsFolderWhenAndroidTakesAudioThere_OtherwiseTheAcceptedFallback() {
        assertEquals("Music/Albums/", FolderRule.choose("Music/Albums/", "big.mp3", useFallback = false))
        assertEquals("Podcasts/x/", FolderRule.choose("Podcasts/x", "big.mp3", useFallback = false))
        assertEquals("Music/Mix 我等你/", FolderRule.choose("Download/", "Mix 我等你.mp3", useFallback = true))
        for (bad in listOf("Download/", "Documents/x/", "DCIM/", "", null)) {
            try { FolderRule.choose(bad, "Mix: one?.mp3", useFallback = false); fail("$bad") } catch (e: SplitError.FolderNotAllowed) {
                assertEquals("Music/Mix one/", e.suggested); assertEquals("SPLIT_FOLDER_NOT_ALLOWED", e.code)
            }
        }
        assertFalse(FolderRule.isAudioFolder("music/"))      // Android compares the names as written
    }
}

class DeleteTargetsTest {
    private fun record(written: Int, phase: RunPhase) = RunRecord(
        "r1", SourceIdentity(5, 1200, 1000, "h"), "big.mp3", listOf(RunCut(10.0, null, true, false), RunCut(20.0, null, true, false)),
        "Music/", "external_primary", phase, List(written) { RunSong(it + 1, "big 0${it + 1}.mp3", 100L + it, 400) }, 1,
    )

    @Test
    fun DeleteTargets_AnIdThatNowNamesAFileOfAnotherSize_IsNotDeleted() {
        assertEquals(listOf(100L), DeleteTargets.songs(record(3, RunPhase.Done), mapOf(100L to 400L, 101L to 12_345L)))
    }

    @Test
    fun DeleteTargets_TheOriginal_MustStillBeTheFileThatWasSplit() {
        val r = record(3, RunPhase.Done)
        assertTrue(DeleteTargets.originalIsUnchanged(r, SourceIdentity(5, 1200, 1000, "h")))
        assertFalse(DeleteTargets.originalIsUnchanged(r, SourceIdentity(5, 1200, 1000, "other")))
        assertFalse(DeleteTargets.originalIsUnchanged(r, SourceIdentity(5, 999, 1000, "h")))
    }

    @Test
    fun DeleteTargets_SongsStillThere_AndTheOriginalOnlyWhenDone() {
        assertEquals(listOf(100L, 102L), DeleteTargets.songs(record(3, RunPhase.Done), mapOf(100L to 400L, 102L to 400L, 999L to 400L)))   // 101 was deleted already; 999 is not ours
        assertEquals(emptyList<Long>(), DeleteTargets.songs(record(0, RunPhase.Reviewing), mapOf(100L to 400L)))
        assertEquals(5L, DeleteTargets.original(record(3, RunPhase.Done)))
        for (r in listOf(record(3, RunPhase.Writing), record(2, RunPhase.Done), record(0, RunPhase.Reviewing))) {
            try { DeleteTargets.original(r); fail() } catch (_: IllegalArgumentException) {}
        }
    }
}

internal class Files : AtomicFiles {
    val store = HashMap<String, ByteArray>()
    var saves = 0
    override fun writeAtomic(name: String, bytes: ByteArray) { store[name] = bytes; saves++ }
    override fun read(name: String) = store[name]
    override fun delete(name: String) { store.remove(name) }
    override fun names() = store.keys.toList()
}

/** A media index in memory: visible files, one pending file at most, a free-space quota, and a way to be killed half way through a song. */
internal class FakeSink(var free: Long = 100_000_000) : SongSink {
    val visible = LinkedHashMap<String, ByteArray>()
    var pending: String? = null
    var killAtSong: String? = null          // the write of this name stops half way and leaves its pending file, as a kill would
    var runOutAtSong: String? = null        // the phone runs out of space while writing this one
    var removedPending = 0
    var writes = 0
    override fun freeBytes() = free
    override fun find(name: String) = visible[name]?.let { FinishedSong(100L + name.hashCode() % 1000, it.size.toLong()) }
    override fun removePending(names: List<String>): Int { val n = if (pending != null) 1 else 0; pending = null; removedPending += n; return n }
    override fun write(name: String, header: ByteArray?, source: ByteSource, start: Long, end: Long, cancelled: () -> Boolean): FinishedSong? {
        writes++
        pending = name
        val out = ByteArrayOutputStream()
        if (name == killAtSong) { SongCopy.copy(source, start, start + (end - start) / 2, header, out, cancelled); throw IOException("killed") }
        if (name == runOutAtSong) { pending = null; throw SplitError.NoSpace() }
        if (!SongCopy.copy(source, start, end, header, out, cancelled)) { pending = null; return null }
        visible[name] = out.toByteArray()
        pending = null
        return find(name)
    }
}
