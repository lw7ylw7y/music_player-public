package app.songplayer.split

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.errors.SplitError
import app.songplayer.core.split.ByteArraySource
import app.songplayer.core.split.ByteSource
import app.songplayer.core.split.RunCut
import app.songplayer.core.split.RunPhase
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.RunStore
import app.songplayer.core.split.SongNames
import app.songplayer.core.split.SongSpan
import app.songplayer.core.split.SourceIdentity
import app.songplayer.core.split.SplitWriter
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The splitter's writer against the emulator's real media index (Stories X5). It makes and removes only its own fixtures in a folder of its
 * own; it never touches the user's music, and it is not run on the phone.
 */
@RunWith(AndroidJUnit4::class)
class SongWriterTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "Music/SongPlayerSplitTest/"
    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    private val writer = SongWriter(context, folder, volume, "Music/big/")
    private val original = ByteArray(3000) { (it * 5 + 1).toByte() }
    private val spans = List(3) { SongSpan(it + 1, it * 10, it * 10 + 10, it * 1000L, it * 1000L + 1000, null) }
    private val names = SongNames.names("big.mp3", 3)
    private val recordDir = File(context.cacheDir, "split-test-runs").also { it.deleteRecursively(); it.mkdirs() }

    private fun record() = RunRecord(
        "t1", SourceIdentity(1, 3000, 1, "h"), "big.mp3", List(2) { RunCut(10.0 * (it + 1), null, true, false) },
        folder, volume, RunPhase.Reviewing, emptyList(), 1,
    )

    @After
    fun removeWhatTheTestMade() {
        names.forEach { name -> writer.find(name)?.let { context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), it.mediaId), null, null) } }
        writer.removePending(names)
        recordDir.deleteRecursively()
    }

    private fun seedVisible(name: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(volume), values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    @Test
    fun SongWriter_SeededFile_WritesCompleteSongs() {
        val result = SplitWriter(writer, RunStore(RunRecordFile(recordDir))).run(record(), spans, ByteArraySource(original))
        assertTrue(result.finished)
        assertEquals(RunPhase.Done, result.record.phase)
        for ((k, name) in names.withIndex()) {
            val found = writer.find(name)!!
            assertEquals("size in the media index", 1000L, found.size)
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), found.mediaId)
            assertArrayEquals(original.copyOfRange(k * 1000, k * 1000 + 1000), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test
    fun SongWriter_SourceFailsMidSong_LeavesNothingVisible() {
        val failing = object : ByteSource {
            override val length = 3000L
            override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int =
                if (offset >= 1500) throw IOException("gone") else ByteArraySource(original).read(offset, buffer, off, minOf(len, (1500 - offset).toInt()))
        }
        try { SplitWriter(writer, RunStore(RunRecordFile(recordDir))).run(record(), spans, failing); fail() } catch (_: SplitError.SourceChanged) {}
        assertNotNull(writer.find(names[0]))                  // song 1 is whole
        assertNull("no half-written song 2 is visible", writer.find(names[1]))
        assertEquals("and no unfinished file is left behind", 0, writer.removePending(names))
        assertEquals(1, RunStore(RunRecordFile(recordDir)).load("t1")!!.written.size)
    }

    @Test
    fun SongWriter_NameClash_StopsOrSkips() {
        seedVisible(names[1], ByteArray(7))                    // the same name, other content
        try { SplitWriter(writer, RunStore(RunRecordFile(recordDir))).run(record(), spans, ByteArraySource(original)); fail() } catch (e: SplitError.NameInUse) { assertEquals(names[1], e.name) }
        assertNull("nothing was written before the stop", writer.find(names[0]))
        assertEquals(7L, writer.find(names[1])!!.size)         // the user's own file is untouched and was not renamed or replaced
        context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), writer.find(names[1])!!.mediaId), null, null)
        seedVisible(names[1], original.copyOfRange(1000, 2000)) // the same name and size: counts as written
        val result = SplitWriter(writer, RunStore(RunRecordFile(recordDir))).run(record(), spans, ByteArraySource(original))
        assertTrue(result.finished)
        assertEquals(3, result.record.written.size)
    }

    @Test
    fun SongWriter_FolderNotAllowed_IsTyped() {
        val download = SongWriter(context, "Download/SongPlayerSplitTest/", volume, "Music/big/")
        try { download.write("x 01.mp3", null, ByteArraySource(original), 0, 100, { false }); fail() } catch (e: SplitError.FolderNotAllowed) { assertEquals("Music/big/", e.suggested) }
        assertTrue(writer.canCreateHere())
        assertTrue(!download.canCreateHere())
    }
}
