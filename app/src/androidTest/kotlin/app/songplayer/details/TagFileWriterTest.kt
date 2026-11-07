package app.songplayer.details

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.details.FieldChange
import app.songplayer.core.details.Id3
import app.songplayer.core.details.Id3Shapes
import app.songplayer.core.details.JournalRecord
import app.songplayer.core.details.TagChange
import app.songplayer.core.details.WriteMode
import app.songplayer.core.details.WriteRecovery
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import app.songplayer.core.library.Song
import app.songplayer.core.library.TestMp3
import app.songplayer.core.log.AppLog
import app.songplayer.playback.AppInForeground
import app.songplayer.playback.TestSongs
import app.songplayer.playback.audioUri
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests of the tag writer (story H2) on real files in the media index that the test app itself owns, so Android lets it
 * change them. "No access" for a file of another app and the Media management switch are the manual steps (probes P3 and P4);
 * a refused restore is made with [WriteFaults].
 */
@RunWith(AndroidJUnit4::class)
class TagFileWriterTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun openApp() = AppInForeground.start()

        @AfterClass
        @JvmStatic
        fun closeApp() = AppInForeground.stop()
    }

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val toClean = mutableListOf<() -> Unit>()
    private val sizes = mutableListOf<Pair<String, Long>>()
    private val journalDir = File(context.filesDir, "details-journal")
    private val journal = WriteJournalFiles(journalDir)
    private val change = TagChange(FieldChange.Set("New Title 我等你"), FieldChange.Set("Artist 張學友"), FieldChange.Set("Album 經典"))

    @After
    fun cleanUp() {
        toClean.reversed().forEach { runCatching { it() } }
        journalDir.listFiles()?.forEach { it.delete() }
    }

    private fun seed(frames: Int = 40, content: ByteArray? = null): Song =
        TestSongs.seed(context, "H2", frames = frames, toClean = toClean, fileName = "h2-${System.nanoTime()}.mp3", content = content)

    private fun writer(
        song: Song?,
        free: Long = Long.MAX_VALUE / 4,
        maxRewrite: Long = Long.MAX_VALUE,
        faults: WriteFaults = object : WriteFaults {},
    ) = TagFileWriter(
        context, { if (song != null && it == song.key.value) song else null }, { k, s -> sizes += k to s },
        AppLog(emptyList()), { free }, maxRewrite, faults,
    )

    private fun bytesOf(song: Song): ByteArray = context.contentResolver.openInputStream(audioUri(song.mediaStoreId))!!.use { it.readBytes() }

    private fun corrupt(song: Song) {
        context.contentResolver.openFileDescriptor(audioUri(song.mediaStoreId), "rwt")!!.use { fd ->
            java.io.FileOutputStream(fd.fileDescriptor).use { it.write(ByteArray(100) { 9 }) }
        }
    }

    private fun journalFiles() = journalDir.listFiles()?.size ?: 0

    /** A write whose file was damaged and whose restore is refused: the entry and the backup stay. */
    private fun cutOffWrite(song: Song): Throwable? {
        val faults = object : WriteFaults {
            override fun afterApply() { corrupt(song); throw IOException("the app was killed here") }
            override fun beforeRestore() { throw SecurityException("no write access now") }
        }
        return try { writer(song, faults = faults).write(song.key.value, change); null } catch (e: DetailsError.WriteFailed) { e }
    }

    @Test
    fun TagFileWriter_SeededFile_WritesAndVerifies() {
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        writer(song).write(song.key.value, change)

        val after = bytesOf(song)
        val h = Id3.readHeader(after)!!
        val tag = Id3.parse(h, after.copyOfRange(0, h.totalSize))
        assertEquals("New Title 我等你", Id3.fields(tag).title)
        assertArrayEquals("the audio bytes are unchanged", original, after.copyOfRange(h.totalSize, after.size))
        assertEquals("no journal entry or backup is left", 0, journalFiles())
        assertEquals("Artist 張學友", writer(song).read(song.key.value).artist)
    }

    @Test
    fun TagFileWriter_Rewrite_UpdatesPlaylistEntrySizes() {
        val song = seed()
        writer(song).write(song.key.value, change)
        assertEquals(1, sizes.size)
        assertEquals(song.key.value, sizes[0].first)
        assertEquals(bytesOf(song).size.toLong(), sizes[0].second)
        // The second edit fits in the padding the rewrite left: in place, so no new size is reported.
        writer(song).write(song.key.value, TagChange(title = FieldChange.Set("Shorter")))
        assertEquals(1, sizes.size)
    }

    @Test
    fun TagFileWriter_LowSpaceOrUnreadable_ChangesNothing() {
        val song = seed()
        val before = bytesOf(song)
        try {
            writer(song, free = 0).write(song.key.value, change)
            fail("expected NotWritten")
        } catch (e: DetailsError.NotWritten) {
            assertEquals(NotWrittenReason.LowSpace, e.reason)
        }
        assertArrayEquals(before, bytesOf(song))
        try {
            writer(song).write("no such song", change)
            fail("expected NotWritten")
        } catch (e: DetailsError.NotWritten) {
            assertEquals(NotWrittenReason.Unreadable, e.reason)
        }
        assertEquals(0, journalFiles())
    }

    @Test
    fun TagFileWriter_FileTooBigToRewrite_IsRefused() {
        val song = seed() // no tag: the write would need a rewrite
        val before = bytesOf(song)
        try {
            writer(song, maxRewrite = 1_000).write(song.key.value, change)
            fail("expected NotWritten")
        } catch (e: DetailsError.NotWritten) {
            assertEquals(NotWrittenReason.TooBigToRewrite, e.reason)
        }
        assertArrayEquals(before, bytesOf(song))
    }

    @Test
    fun TagFileWriter_UnsupportedOrHugeTag_IsRefused_AndTheFileIsUntouched() {
        val v22 = Id3Shapes.refused().first { it.name == "2.2 tag" }.file
        val huge = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F) + ByteArray(2_000)
        for (content in listOf(v22, huge)) {
            val song = seed(content = content)
            val before = bytesOf(song)
            try {
                writer(song).write(song.key.value, change)
                fail("expected NotWritten")
            } catch (e: DetailsError.NotWritten) {
                assertEquals(NotWrittenReason.UnsupportedTag, e.reason)
            }
            assertArrayEquals(before, bytesOf(song))
        }
    }

    @Test
    fun TagFileWriter_FailureAfterTruncation_RestoresAtOnce() {
        // A rewrite has truncated the file and then fails before writing anything: the file is empty until the journal puts it right.
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        val faults = object : WriteFaults { override fun afterOpened() { throw IOException("boom") } }
        try {
            writer(song, faults = faults).write(song.key.value, change)
            fail("expected WriteFailed")
        } catch (e: DetailsError.WriteFailed) {
            // expected
        }
        assertArrayEquals("the original is back", original, bytesOf(song))
        assertEquals("nothing is left to recover", 0, journalFiles())
    }

    @Test
    fun TagFileWriter_FailureAfterTheFileIsFinished_CountsAsWritten() {
        // The file already holds exactly the intended bytes when something goes wrong (for example the read-back): it is a finished write.
        val song = seed()
        val faults = object : WriteFaults { override fun afterApply() { throw IOException("boom") } }
        writer(song, faults = faults).write(song.key.value, change)
        assertEquals("New Title 我等你", writer(song).read(song.key.value).title)
        assertEquals(0, journalFiles())
    }

    @Test
    fun TagFileWriter_Mismatch_IsCaughtByTheReadBack_AndRestored() {
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        val faults = object : WriteFaults { override fun afterApply() { corrupt(song) } } // no exception: only the check can notice
        try {
            writer(song, faults = faults).write(song.key.value, change)
            fail("expected WriteFailed")
        } catch (e: DetailsError.WriteFailed) {
            // expected
        }
        assertArrayEquals(original, bytesOf(song))
        assertEquals(0, journalFiles())
    }

    @Test
    fun TagFileWriter_RestoreRefused_KeepsEntryAndBackup_AndTheNextStartPutsItRight() {
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        cutOffWrite(song)
        assertEquals("entry and backup stay", 2, journalFiles())
        assertEquals("the file is still damaged", 100, bytesOf(song).size)

        writer(null).recover() // the song is not even in the library cache: the entry has the media id

        assertArrayEquals("the original is back", original, bytesOf(song))
        assertEquals(0, journalFiles())
    }

    @Test
    fun TagFileWriter_EarlierCutOffWrite_IsSettledBeforeANewWrite_NotOverwritten() {
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        cutOffWrite(song)
        val firstBackup = journalDir.listFiles()!!.first { it.name.endsWith(".bak") }.readBytes()
        assertArrayEquals(original, firstBackup)

        writer(song).write(song.key.value, change) // settles the old entry (restores), then writes

        assertEquals("New Title 我等你", writer(song).read(song.key.value).title)
        assertEquals(0, journalFiles())
    }

    @Test
    fun TagFileWriter_MissingBackup_KeepsTheEntry() {
        val song = seed()
        cutOffWrite(song)
        journalDir.listFiles()!!.first { it.name.endsWith(".bak") }.delete()
        writer(song).recover()
        assertEquals("the entry stays; nothing is guessed", 1, journalFiles())
        assertEquals("the file is left as it is", 100, bytesOf(song).size)
    }

    @Test
    fun TagFileWriter_Recovery_UntouchedFile_JustClearsTheEntry() {
        val original = TestMp3.bytes(frames = 40)
        val song = seed()
        journal.prepareDir()
        val id = journal.newId()
        journal.backupFile(id).writeBytes(original)
        journal.commit(JournalEntry(id, song.key.value, song.mediaStoreId, JournalRecord(WriteMode.Rewrite, original.size.toLong(), WriteRecovery.sha256(original), 1L, "x"), original.size.toLong()))
        writer(song).recover()
        assertEquals(0, journalFiles())
        assertArrayEquals(original, bytesOf(song))
    }

    @Test
    fun TagFileWriter_Recovery_SweepsABackupThatHasNoEntry() {
        journal.prepareDir()
        File(journalDir, "orphan.bak").writeBytes(ByteArray(10))
        writer(null).recover()
        assertFalse(File(journalDir, "orphan.bak").exists())
    }

    @Test
    fun MediaManagement_Helpers_BuildTheRightIntents() {
        val intent = MediaManagement.settingsIntent(context)
        assertEquals(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertNull(intent.component)
        // Whether the access is granted is the user's choice; the call must simply work.
        MediaManagement.isGranted(context)
    }
}
