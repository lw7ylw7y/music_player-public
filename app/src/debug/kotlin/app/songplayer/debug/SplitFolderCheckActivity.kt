package app.songplayer.debug

import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.songplayer.core.split.FolderRule
import app.songplayer.split.SongWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only check P-C of the splitter (Technical Design 15): for every folder that holds music on this phone, can the app make
 * a song there? For each folder it inserts one empty pending item and removes it again. It changes no existing file, and the folder names stay
 * on the screen (nothing is logged). It is not part of the release build.
 */
class SplitFolderCheckActivity : ComponentActivity() {
    private class Row(val volume: String, val folder: String, val songs: Int, val result: Boolean?)

    private suspend fun scan(test: Boolean): List<Row> = withContext(Dispatchers.IO) {
        val rows = ArrayList<Row>()
        for (volume in MediaStore.getExternalVolumeNames(this@SplitFolderCheckActivity)) {
            val counts = LinkedHashMap<String, Int>()
            contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume), arrayOf(MediaStore.Audio.Media.RELATIVE_PATH),
                "${MediaStore.Audio.Media.MIME_TYPE} = ?", arrayOf("audio/mpeg"), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { counts.merge(it, 1, Int::plus) } }
            for ((folder, n) in counts.toSortedMap()) {
                val ok = if (test) SongWriter(this@SplitFolderCheckActivity, folder, volume, FolderRule.fallback("x.mp3")).canCreateHere() else null
                rows += Row(volume, folder, n, ok)
            }
        }
        rows
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var rows by remember { mutableStateOf<List<Row>?>(null) }
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(16.dp)) {
                Text("Split folder check (P-C)")
                Button(onClick = { scope.launch { rows = scan(test = true) } }) { Text("Check every music folder") }
                val list = rows
                if (list == null) Text("Tap the button. Each folder gets one empty test item that is removed again.")
                else {
                    Text("${list.size} folders; can create: ${list.count { it.result == true }}, cannot: ${list.count { it.result == false }}")
                    LazyColumn {
                        items(list) { r ->
                            val word = when (r.result) { true -> "CAN"; false -> "CANNOT"; null -> "-" }
                            Text("$word  ${r.volume}  ${r.folder}  (${r.songs} MP3)")
                        }
                    }
                }
            }
        }
    }
}
