package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Id3Test {
    private val change = TagChange(FieldChange.Set("New Title 我等你"), FieldChange.Set("Artist 張學友"), FieldChange.Set("Album 經典"))

    private fun parsed(file: ByteArray): ParsedTag? {
        val h = Id3.readHeader(file) ?: return null
        return Id3.parse(h, file.copyOfRange(0, h.totalSize))
    }

    private fun write(file: ByteArray, c: TagChange = change): Pair<ByteArray, WritePlan> {
        val plan = Id3.plan(parsed(file), c)
        return Id3.apply(file, plan) to plan
    }

    @Test
    fun Id3_EveryShape_AudioBytesUnchanged() {
        for (shape in Id3Shapes.shapes()) {
            val before = parsed(shape.file)
            val (after, _) = write(shape.file)
            val newTag = parsed(after)!!
            assertEquals(shape.name, TagFields("New Title 我等你", "Artist 張學友", "Album 經典"), Id3.fields(newTag))
            val audioBefore = shape.file.copyOfRange(Id3.audioStart(before), shape.file.size)
            val audioAfter = after.copyOfRange(Id3.audioStart(newTag), after.size)
            assertArrayEquals("${shape.name}: the bytes after the tag", audioBefore, audioAfter)
        }
    }

    @Test
    fun Id3_Chinese_RoundTrips_InUtf16AndUtf8() {
        val v3 = parsed(write(Id3Shapes.audio()).first)!!
        assertEquals("a new tag is 2.3", 3, v3.major)
        assertEquals("UTF-16 with a byte-order mark", 1, v3.frames.first { it.id == "TIT2" }.data[0].toInt())
        val v4file = Id3Shapes.tag(4, listOf(Id3Shapes.textFrame(4, "TIT2", "old")), padding = 3000) + Id3Shapes.audio()
        val v4 = parsed(write(v4file).first)!!
        assertEquals(4, v4.major)
        assertEquals("UTF-8", 3, v4.frames.first { it.id == "TIT2" }.data[0].toInt())
        assertEquals("Artist 張學友", Id3.fields(v4).artist)
        // Plain Latin text stays ISO-8859-1 in both.
        val latin = parsed(write(Id3Shapes.audio(), TagChange(title = FieldChange.Set("Plain"))).first)!!
        assertEquals(0, latin.frames.first { it.id == "TIT2" }.data[0].toInt())
    }

    @Test
    fun Id3_BigPadding_IsWrittenInPlace_AndKeepsTheLength() {
        val file = Id3Shapes.tag(3, listOf(Id3Shapes.textFrame(3, "TIT2", "old")), padding = 4000) + Id3Shapes.audio()
        val (after, plan) = write(file)
        assertTrue(plan is WritePlan.InPlace)
        assertEquals(file.size, after.size)
    }

    @Test
    fun Id3_NoRoom_RewritesWithPadding_SoTheNextEditFitsInPlace() {
        val file = Id3Shapes.tag(3, listOf(Id3Shapes.textFrame(3, "TIT2", "old")), padding = 0) + Id3Shapes.audio()
        val (after, plan) = write(file, TagChange(title = FieldChange.Set("a much longer title than before")))
        assertTrue(plan is WritePlan.Rewrite)
        val (_, second) = write(after, TagChange(title = FieldChange.Set("another long title that is different")))
        assertTrue("the second edit fits in the padding", second is WritePlan.InPlace)
    }

    @Test
    fun Id3_NoTag_IsRewrittenAsANew23Tag_FromTheFirstByte() {
        val file = Id3Shapes.audio()
        val plan = Id3.plan(null, change) as WritePlan.Rewrite
        assertEquals(0, plan.audioOffset)
        assertEquals(3, parsed(Id3.apply(file, plan))!!.major)
    }

    @Test
    fun Id3_KeepSetRemove_TouchOnlyTheirFrame_AndOtherFramesAreCopiedByteForByte() {
        val cover = Id3Shapes.frame(3, "APIC", ByteArray(300) { (it * 7).toByte() })
        val file = Id3Shapes.tag(
            3,
            listOf(Id3Shapes.textFrame(3, "TIT2", "one"), Id3Shapes.textFrame(3, "TIT2", "two"), Id3Shapes.textFrame(3, "TPE1", "artist"), cover, Id3Shapes.textFrame(3, "TALB", "album")),
            padding = 500,
        ) + Id3Shapes.audio()
        val (after, _) = write(file, TagChange(title = FieldChange.Set("only"), artist = FieldChange.Remove, album = FieldChange.Keep))
        val t = parsed(after)!!
        assertEquals(1, t.frames.count { it.id == "TIT2" })
        assertEquals(TagFields("only", null, "album"), Id3.fields(t))
        assertArrayEquals(cover.copyOfRange(10, cover.size), t.frames.first { it.id == "APIC" }.data)
    }

    @Test
    fun Id3_ID3v1Tail_IsLeftAlone() {
        val v1 = Id3Shapes.id3v1()
        val file = Id3Shapes.audio() + v1
        val after = write(file).first
        assertArrayEquals(v1, after.copyOfRange(after.size - 128, after.size))
    }

    @Test
    fun Id3_UnsupportedShape_IsRefused_AndNothingIsPlanned() {
        for (shape in Id3Shapes.refused()) {
            try {
                write(shape.file)
                fail("${shape.name} should have been refused")
            } catch (e: DetailsError.NotWritten) {
                assertTrue(shape.name, e.reason == NotWrittenReason.UnsupportedTag || e.reason == NotWrittenReason.Unreadable)
            }
        }
    }

    @Test
    fun Id3_TruncatedOrCorruptTags_NeverThrowAnythingButNotWritten() {
        val random = java.util.Random(11)
        val samples = Id3Shapes.shapes().map { it.file } + Id3Shapes.refused().map { it.file }
        for (file in samples) {
            val tagLength = Id3.readHeader(file)?.totalSize ?: 0
            // Every truncation of the first bytes, then random byte damage inside the tag region.
            val variants = ArrayList<ByteArray>()
            for (n in 0..minOf(file.size, tagLength + 12)) variants += file.copyOf(n)
            repeat(300) {
                val copy = file.copyOf()
                repeat(1 + random.nextInt(4)) { copy[random.nextInt(minOf(copy.size, tagLength + 12))] = random.nextInt(256).toByte() }
                variants += copy
            }
            for (v in variants) {
                try {
                    val h = Id3.readHeader(v) ?: continue
                    val tag = Id3.parse(h, v.copyOf(minOf(v.size, h.totalSize)))
                    Id3.apply(v, Id3.plan(tag, change))
                } catch (e: DetailsError.NotWritten) {
                    // refused: fine
                }
            }
        }
    }

    @Test
    fun Id3_HugeDeclaredSize_IsRefused_NotAllocated() {
        val head = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F)
        try {
            Id3.readHeader(head)
            fail("a 268 MB tag must be refused")
        } catch (e: DetailsError.NotWritten) {
            assertEquals(NotWrittenReason.UnsupportedTag, e.reason)
        }
    }

    @Test
    fun Id3_NoTagInTheFile_ReadHeaderIsNull_AndFieldsAreNull() {
        assertNull(Id3.readHeader(Id3Shapes.audio()))
        assertEquals(TagFields(null, null, null), Id3.fields(null))
    }
}
