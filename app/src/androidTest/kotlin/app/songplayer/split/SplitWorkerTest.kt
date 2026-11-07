package app.songplayer.split

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.songplayer.core.library.TestMp3
import app.songplayer.core.split.RunCut
import app.songplayer.core.split.RunPhase
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.RunStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The splitter's background work on the real WorkManager, with a silent 141 s file this test makes itself in a folder of its own (Stories X6).
 * The file has no loudness to follow, so the analysis falls back to decoding all of it and finds no cut; the writing is tested from a record
 * with cuts made here. Cancelling is tested on the JVM (`SplitWriterTest`, `SplitJobTest`); a song is written too fast here to cancel in the middle.
 */
@RunWith(AndroidJUnit4::class)
class SplitWorkerTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "Music/SongPlayerSplitWorkerTest/"
    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(volume)
    private val runId = "worker-test"
    private val store = RunStore(RunRecordFile(File(context.filesDir, "split-runs")))
    private var sourceId = -1L

    @Before
    fun seed() {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "splitworker_test_source.mp3"); put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(TestMp3.bytes(frames = 5400)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        sourceId = ContentUris.parseId(uri)
    }

    @After
    fun clean() {
        WorkManager.getInstance(context).cancelUniqueWork(SplitWork.uniqueName(sourceId))
        context.contentResolver.delete(collection, "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(folder))
        store.delete(runId)
    }

    private fun waitForEnd(): WorkInfo {
        val manager = WorkManager.getInstance(context)
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until) {
            val info = manager.getWorkInfosForUniqueWork(SplitWork.uniqueName(sourceId)).get().lastOrNull()
            if (info != null && info.state.isFinished) return info
            Thread.sleep(250)
        }
        error("the split work did not finish in 3 minutes")
    }

    private fun songsInFolder(): List<String> {
        val names = ArrayList<String>()
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(folder, "splitworker_test_source %"), "${MediaStore.Audio.Media.DISPLAY_NAME} ASC",
        )?.use { while (it.moveToNext()) names += it.getString(0) }
        return names
    }

    @Test
    fun SplitWorker_SeededFile_RunsToTheEnd() {
        SplitWork.enqueueAnalysis(context, sourceId, volume, runId, folder)
        val analysed = waitForEnd()
        assertEquals(WorkInfo.State.SUCCEEDED, analysed.state)
        assertEquals(1, analysed.outputData.getInt(SplitWork.OUT_SONGS, 0))               // a silent file has no cuts: one song
        assertEquals(false, analysed.outputData.getBoolean(SplitWork.OUT_PROXY, true))   // and nothing for the side information to follow
        val found = store.load(runId)!!
        assertEquals(RunPhase.Reviewing, found.phase)

        // Writing from a record with two cuts (made here; a silent file offers none).
        store.save(found.copy(cuts = listOf(RunCut(40.0, 1.0, true, false), RunCut(90.0, 1.0, true, false))))
        SplitWork.enqueueWriting(context, sourceId, volume, runId)
        val written = waitForEnd()
        assertEquals(WorkInfo.State.SUCCEEDED, written.state)
        assertEquals(3, written.outputData.getInt(SplitWork.OUT_WRITTEN, 0))
        assertEquals(listOf("splitworker_test_source 01.mp3", "splitworker_test_source 02.mp3", "splitworker_test_source 03.mp3"), songsInFolder())
        assertEquals(RunPhase.Done, store.load(runId)!!.phase)
        assertTrue(store.load(runId)!!.written.all { it.size > 0 })
    }
}
