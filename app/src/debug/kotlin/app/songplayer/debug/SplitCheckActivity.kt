package app.songplayer.debug

import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.RunStore
import app.songplayer.split.RunRecordFile
import app.songplayer.split.SplitWork
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug-only "Split check" (Stories X6, spikes P-B and P-D): pick one big MP3 from this phone, find its songs, and write
 * them into the folder `Music/SplitCheck/` (never next to the original). It shows the cuts the app proposes, in seconds, to compare with
 * what `cli/splitmp3.py` finds on the same file, and the times of each step. The names stay on the screen; nothing is logged. It changes no
 * existing file. Delete the folder `Music/SplitCheck/` in a file manager afterwards. It is not part of the release build.
 */
class SplitCheckActivity : ComponentActivity() {
    private class Item(val id: Long, val name: String, val mb: Long, val volume: String)

    private suspend fun bigFiles(): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        for (volume in MediaStore.getExternalVolumeNames(this@SplitCheckActivity)) {
            contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume),
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.MIME_TYPE} = ? AND ${MediaStore.Audio.Media.SIZE} > ?", arrayOf("audio/mpeg", (20L shl 20).toString()),
                "${MediaStore.Audio.Media.SIZE} DESC",
            )?.use { c -> while (c.moveToNext()) out += Item(c.getLong(0), c.getString(1) ?: "?", c.getLong(2) shr 20, volume) }
        }
        out.sortedByDescending { it.mb }.take(20)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RunStore(RunRecordFile(File(filesDir, "split-runs")))
        setContent {
            var items by remember { mutableStateOf<List<Item>?>(null) }
            var chosen by remember { mutableStateOf<Item?>(null) }
            LaunchedEffect(Unit) { items = bigFiles() }
            val file = chosen
            Column(Modifier.padding(16.dp)) {
                Text("Split check (P-B, P-D). Writes only into Music/SplitCheck/.")
                if (file == null) {
                    val list = items
                    if (list == null) Text("Looking for big MP3 files…") else if (list.isEmpty()) Text("No MP3 over 20 MB on this phone.")
                    LazyColumn { items(list.orEmpty()) { Text("${it.name}  (${it.mb} MB)", Modifier.clickable { chosen = it }.padding(vertical = 10.dp)) } }
                } else {
                    val runId = "check-${file.id}"
                    val infos by WorkManager.getInstance(this@SplitCheckActivity).getWorkInfosForUniqueWorkFlow(SplitWork.uniqueName(file.id)).collectAsState(emptyList())
                    val info = infos.lastOrNull()
                    var record by remember { mutableStateOf<RunRecord?>(null) }
                    LaunchedEffect(info?.state, info?.progress?.getInt(SplitWork.PROGRESS_WRITTEN, 0)) { record = withContext(Dispatchers.IO) { store.load(runId) } }
                    Text("${file.name}  (${file.mb} MB)")
                    Row {
                        Button(onClick = { SplitWork.enqueueAnalysis(this@SplitCheckActivity, file.id, file.volume, runId, "Music/SplitCheck/") }) { Text("Find songs") }
                        Button(enabled = record != null, onClick = { SplitWork.enqueueWriting(this@SplitCheckActivity, file.id, file.volume, runId) }) { Text("Write") }
                        Button(onClick = { SplitWork.cancel(this@SplitCheckActivity, file.id) }) { Text("Cancel") }
                    }
                    Button(onClick = { chosen = null }) { Text("Another file") }
                    if (info != null) {
                        val p = info.progress
                        Text("Work: ${info.state}  ${p.getString(SplitWork.PROGRESS_PHASE) ?: ""} ${p.getInt(SplitWork.PROGRESS_PERMILLE, 0) / 10.0}%  songs ${p.getInt(SplitWork.PROGRESS_WRITTEN, 0)}/${p.getInt(SplitWork.PROGRESS_SONGS, 0)}")
                        p.getString(SplitWork.PROGRESS_STAGE)?.takeIf { it.isNotEmpty() }?.let { stage ->
                            Text("Analysis: $stage ${p.getInt(SplitWork.PROGRESS_DONE, 0)} of ${p.getInt(SplitWork.PROGRESS_TOTAL, 0)}, decoded ${"%.0f".format(p.getFloat(SplitWork.PROGRESS_DECODED_SEC, 0f))} s so far")
                        }
                        val o = info.outputData
                        if (info.state == WorkInfo.State.SUCCEEDED && o.getLong(SplitWork.OUT_SCAN_MS, -1) >= 0) {
                            Text("Frame scan ${o.getLong(SplitWork.OUT_SCAN_MS, 0)} ms; analysis ${o.getLong(SplitWork.OUT_ANALYZE_MS, 0)} ms; decoded ${o.getLong(SplitWork.OUT_DECODED_DS, 0) / 10.0} s in ${o.getInt(SplitWork.OUT_WINDOWS, 0)} windows and ${o.getInt(SplitWork.OUT_REGIONS, 0)} regions; side-information proxy usable: ${o.getBoolean(SplitWork.OUT_PROXY, false)}")
                        }
                        o.getString(SplitWork.OUT_ERROR)?.let { Text("Stopped: $it") }
                    }
                    record?.let { r ->
                        Text("${r.songCount} songs, written ${r.written.size}, phase ${r.phase}")
                        LazyColumn {
                            items(r.cuts.withIndex().toList()) { (k, cut) ->
                                Text("${k + 1}. at ${"%.1f".format(cut.timeSec)} s  gap ${"%.1f".format((cut.halfGapSec ?: 0.0) * 2)} s  ${if (cut.confident) "confident" else "unsure"}")
                            }
                        }
                    }
                }
            }
        }
    }
}
