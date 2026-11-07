package app.songplayer.core.split

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutterTest {
    private val frameSec = 1152.0 / 44100

    /** 20,000 CBR frames (522 s); every 40th frame is self-contained (main_data_begin 0), the others reach back 100 bytes. */
    private val cbrBytes = Mp3Shapes.repeat(20_000) { Mp3Shapes.frame(mainDataBegin = if (it % 40 == 0) 0 else 100) }
    private val cbr = Mp3Frames.scan(ByteArraySource(cbrBytes))

    private fun plan(vararg times: Double, half: Double? = 2.5) =
        SplitPlan(522.0, times.map { PlanCut(it, half, confident = true) })

    @Test
    fun Mp3Cutter_AllFramesOnceInOrder() {
        val spans = Mp3Cutter.spans(cbr, plan(100.0, 250.0, 400.0))
        assertEquals(4, spans.size)
        assertEquals(0, spans.first().firstFrame)
        assertEquals(cbr.count, spans.last().endFrame)
        for (k in 1 until spans.size) {
            assertEquals(spans[k - 1].endFrame, spans[k].firstFrame)              // every frame in exactly one song, in order
            assertEquals(spans[k - 1].byteEnd, spans[k].byteStart)                // the byte ranges join exactly (a clean file)
        }
        assertEquals(cbr.count, spans.sumOf { it.frames })
        assertEquals(cbrBytes.size.toLong(), spans.sumOf { it.bytes })
        assertEquals(listOf(1, 2, 3, 4), spans.map { it.number })
        assertTrue(spans.all { it.xingHeader == null })                           // a constant-bit-rate source needs none
    }

    @Test
    fun Mp3Cutter_XingHeaderOnlyForVbr() {
        val vbrBytes = Mp3Shapes.join(Mp3Shapes.xingFrame(), Mp3Shapes.repeat(20_000) { Mp3Shapes.frame(bitrateIndex = 5 + it % 8, mainDataBegin = if (it % 40 == 0) 0 else 100) })
        val vbr = Mp3Frames.scan(ByteArraySource(vbrBytes))
        assertTrue(vbr.isVariableBitRate)
        assertFalse(cbr.isVariableBitRate)
        val spans = Mp3Cutter.spans(vbr, SplitPlan(500.0, listOf(PlanCut(120.0, 2.5, true), PlanCut(300.0, 2.5, true))))
        assertEquals(3, spans.size)
        assertEquals(vbr.first, spans.first().firstFrame) // the source's own header frame is never copied
        for (s in spans) {
            val head = s.xingHeader ?: error("a VBR song needs a Xing header")
            // The header plus the copied frames are a valid file: a Xing frame, then exactly the song's frames, and the header says so.
            val song = Mp3Shapes.join(head, vbrBytes.copyOfRange(s.byteStart.toInt(), s.byteEnd.toInt()))
            val idx = Mp3Frames.scan(ByteArraySource(song))
            assertEquals(1, idx.first)
            assertEquals(s.frames + 1, idx.count)
            val o = 4 + 32
            assertEquals("Xing", String(head, o, 4))
            assertEquals(s.frames, ((head[o + 8].toInt() and 255) shl 24) or ((head[o + 9].toInt() and 255) shl 16) or ((head[o + 10].toInt() and 255) shl 8) or (head[o + 11].toInt() and 255))
            assertEquals(song.size, ((head[o + 12].toInt() and 255) shl 24) or ((head[o + 13].toInt() and 255) shl 16) or ((head[o + 14].toInt() and 255) shl 8) or (head[o + 15].toInt() and 255))
            assertEquals(7, head[o + 7].toInt()) // flags: frames, bytes and the seek table
            // The seek table never goes backwards, stays under 256, and starts near the beginning of the file.
            val toc = IntArray(100) { head[o + 16 + it].toInt() and 255 }
            assertTrue(toc.toList() == toc.sorted())
            assertTrue(toc[0] in 0..3 && toc[99] > 200)
        }
    }

    @Test
    fun Mp3Cutter_XingHeader_OtherLayouts() {
        // MPEG-2 stereo, MPEG-1 mono and MPEG-2 mono sources: the header frame has the layout's side-information size and is a valid frame.
        for ((mpeg1, mono) in listOf(false to false, true to true, false to true)) {
            val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            val bytes = Mp3Shapes.join(Mp3Shapes.xingFrame(mpeg1 = mpeg1), Mp3Shapes.repeat(6000) { Mp3Shapes.frame(mpeg1 = mpeg1, mono = mono, bitrateIndex = 7 + it % 6, mainDataBegin = if (it % 40 == 0) 0 else 100) })
            val idx = Mp3Frames.scan(ByteArraySource(bytes))
            val spans = Mp3Cutter.spans(idx, SplitPlan(idx.startSeconds(idx.count - 1), listOf(PlanCut(idx.startSeconds(idx.count - 1) / 2, 2.0, true))))
            for (s in spans) {
                val head = s.xingHeader!!
                val o = 4 + side
                assertEquals("Xing", String(head, o, 4))
                assertEquals(7, head[o + 7].toInt())
                val song = Mp3Shapes.join(head, bytes.copyOfRange(s.byteStart.toInt(), s.byteEnd.toInt()))
                val parsed = Mp3Frames.scan(ByteArraySource(song))
                assertEquals(1, parsed.first); assertEquals(s.frames + 1, parsed.count)
                assertEquals(mono, parsed.isMono(0)); assertEquals(mpeg1, parsed.samples(0) == 1152)
            }
        }
    }

    @Test
    fun Mp3Cutter_CutsStartOnSelfContainedFrames() {
        val starts = cbr.startTimes()
        val spans = Mp3Cutter.spans(cbr, plan(100.0, 250.0, 400.0))
        for (s in spans.drop(1)) assertEquals("song ${s.number} starts on a self-contained frame", 0, cbr.mainDataBegin[s.firstFrame].toInt())
        // An app proposal stays inside its gap (5 s wide around the chosen time) and takes the self-contained frame nearest the middle.
        val c = Mp3Cutter.cutFrame(cbr, starts, PlanCut(100.0, 2.5, true))
        assertTrue(starts[c] in 97.5..102.5)
        val mid = (100.0 / frameSec).toInt()
        assertTrue((0..40).none { k -> cbr.mainDataBegin[mid + k - 20].toInt() == 0 && Math.abs(mid + k - 20 - mid) < Math.abs(c - mid) })
        // A cut the user moved: the nearest self-contained frame within half a second (one frame in 40 is self-contained, about a second
        // apart, so at most 20 frames or 0.52 s away: the cut at 200 s is within reach), and a frame outside half a second is reported as not close.
        val moved = Mp3Cutter.cutFrame(cbr, starts, PlanCut(200.0, null, true, checked = true))
        assertEquals(0, cbr.mainDataBegin[moved].toInt())
        assertTrue(Math.abs(starts[moved] - 200.0) <= Mp3Cutter.NEAR_SEC)
    }

    @Test
    fun Mp3Cutter_NoSelfContainedFrameNearTheCut_CutsWhereTheCutIs_NotMinutesAway() {
        // The failure seen on the user's files: an encoder that almost never starts a frame without the bit reservoir. The command-line tool
        // then cuts at the cut's own frame (snap_to_frame); the app once jumped to the nearest self-contained frame in the whole file, so a
        // song came out as a few seconds and the next as two songs. Here the only self-contained frames are 200 s away from every cut.
        val rare = Mp3Frames.scan(ByteArraySource(Mp3Shapes.repeat(40_000) { Mp3Shapes.frame(mainDataBegin = if (it == 10_000 || it == 30_000) 0 else 100) }))
        val starts = rare.startTimes()
        val cuts = listOf(240.0, 480.0, 720.0)
        val spans = Mp3Cutter.spans(rare, SplitPlan(1000.0, cuts.map { PlanCut(it, 2.5, confident = true) }))
        assertEquals(4, spans.size)
        spans.drop(1).forEachIndexed { k, s -> assertTrue("cut ${k + 1} is at ${starts[s.firstFrame]}", Math.abs(starts[s.firstFrame] - cuts[k]) <= 0.1) }
        assertTrue("no song is far shorter or longer than 240 s", spans.dropLast(1).all { Math.abs(it.frames * frameSec - 240.0) < 1.0 })
    }

    @Test
    fun Mp3Cutter_NoSelfContainedFrameInTheGap_CutsAtTheCutsOwnFrame_AndNeverMeetsTheNextCut() {
        // Two cuts 10 s apart with no self-contained frame in either gap: each is cut at its own frame, in order, and never meets the other.
        val sparse = Mp3Frames.scan(ByteArraySource(Mp3Shapes.repeat(5_000) { Mp3Shapes.frame(mainDataBegin = 100) }))
        val spans = Mp3Cutter.spans(sparse, SplitPlan(130.0, listOf(PlanCut(40.0, 2.0, true), PlanCut(50.0, 2.0, true))))
        assertEquals(3, spans.size)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.endFrame == b.firstFrame && a.frames > 0 })
        assertEquals(40.0, sparse.startTimes()[spans[1].firstFrame], 0.03)
        assertEquals(50.0, sparse.startTimes()[spans[2].firstFrame], 0.03)
    }

    @Test
    fun SplitPlan_MoveRemoveAdd_KeepTheRules() {
        val p = SplitPlan(600.0, listOf(PlanCut(100.0, 2.0, true), PlanCut(250.0, 2.0, false), PlanCut(400.0, 2.0, true)))
        assertEquals(4, p.songCount)
        assertEquals(1, p.unchecked)
        // Moves stay 5 s from the neighbours and from the ends of the file.
        assertEquals(5.0, p.allowedRange(0).start, 0.0); assertEquals(245.0, p.allowedRange(0).endInclusive, 0.0)
        assertEquals(105.0, p.allowedRange(1).start, 0.0); assertEquals(395.0, p.allowedRange(1).endInclusive, 0.0)
        assertFalse(p.move(0, 4.9)); assertFalse(p.move(0, 245.1)); assertTrue(p.move(0, 245.0))
        assertFalse(p.move(2, 595.1)); assertTrue(p.move(2, 595.0)); assertEquals(595.0, p.cuts[2].timeSec, 0.0)
        val copy = p.cuts; assertEquals(3, copy.size) // the list handed out is a copy
        // A moved cut has no gap any more and counts as checked; confirm checks without moving.
        assertNull(p.cuts[0].halfGapSec)
        p.confirm(1); assertEquals(0, p.unchecked)
        // Remove joins songs; add refuses a cut too near another or an end, and keeps the list in order.
        p.remove(1); assertEquals(3, p.songCount)
        assertFalse(p.add(247.0)); assertFalse(p.add(2.0)); assertFalse(p.add(598.0)); assertTrue(p.add(400.0)); assertFalse(p.add(Double.NaN))
        assertEquals(listOf(245.0, 400.0, 595.0), p.cuts.map { it.timeSec })
        p.remove(99); p.confirm(99); assertEquals(4, p.songCount) // out-of-range calls do nothing
    }

    @Test
    fun SplitPlan_FromTheFinder_UsesEveryCut_AndTheUncertainOnesWaitForALook() {
        val cuts = listOf(Cut(100.0, 5.0, -100.0, true, null, 20.0), Cut(300.0, 1.0, -60.0, false, CutReason.ShortGap, 2.0))
        val p = SplitPlan.from(cuts, 600.0)
        assertEquals(3, p.songCount)
        assertEquals(1, p.unchecked)
        assertEquals(2.5, p.cuts[0].halfGapSec!!, 0.0)
    }

    @Test
    fun SongNames_PaddingAndForbiddenCharacters() {
        assertEquals(listOf("Big album 01", "Big album 02", "Big album 03"), SongNames.names("Big album.mp3", 3).map { it.removeSuffix(".mp3") })
        assertEquals("Album 99.mp3", SongNames.names("Album.mp3", 99)[98])
        assertEquals("Album 100.mp3", SongNames.names("Album.mp3", 100)[99])
        assertEquals("Album 001.mp3", SongNames.names("Album.mp3", 120)[0])
        assertEquals("Album 120.mp3", SongNames.names("Album.mp3", 120)[119])
        // Chinese text stays, forbidden characters and trailing dots and spaces go, an empty stem gets a name.
        assertEquals("我等你 專輯 01.mp3", SongNames.names("我等你 專輯.mp3", 5)[0])
        assertEquals("ab 01.mp3", SongNames.names("a:b*?\".mp3", 5)[0])
        assertEquals("abc 01.mp3", SongNames.names("a|b\u007fc.mp3", 5)[0])
        assertEquals("Song 01.mp3", SongNames.names("???.mp3", 5)[0])
        // A long Chinese name is cut so that the whole file name fits in 255 bytes of UTF-8.
        val long = SongNames.names("專".repeat(200) + ".mp3", 120)
        assertTrue(long.all { it.toByteArray(Charsets.UTF_8).size <= 255 })
        assertEquals(120, long.toSet().size) // still distinct, and in order
        assertEquals(long, long.sorted())
    }
}
