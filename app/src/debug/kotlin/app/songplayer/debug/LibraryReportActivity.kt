package app.songplayer.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.songplayer.SongPlayerApp
import app.songplayer.core.library.LibrarySync
import app.songplayer.core.library.SyncResult
import app.songplayer.library.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only report for the library check (Technical Design 15, checks A-1 and A-2). It scans the media
 * index and shows counts and the first songs on screen. Nothing is logged: the titles stay on the screen.
 * It is not part of the release build.
 */
class LibraryReportActivity : ComponentActivity() {

    private class Report(
        val total: Int,
        val flaggedMusic: Int,
        val scanMs: Long,
        val missingTitle: Int,
        val missingArtist: Int,
        val missingAlbum: Int,
        val byVolume: Map<String, Int>,
        val cached: Int,
        val syncError: String?,
        val sample: List<String>,
    )

    private fun granted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun buildReport(): Report = withContext(Dispatchers.IO) {
        val container = (application as SongPlayerApp).container
        val started = System.nanoTime()
        val songs = MediaStoreSource(this@LibraryReportActivity).scan()
        val scanMs = (System.nanoTime() - started) / 1_000_000
        val flagged = MediaStoreSource(this@LibraryReportActivity, requireMusicFlag = true).scan().size
        val sync = LibrarySync(container.librarySource, container.songStore).run()
        Report(
            total = songs.size,
            flaggedMusic = flagged,
            scanMs = scanMs,
            missingTitle = songs.count { it.title == null },
            missingArtist = songs.count { it.artist == null },
            missingAlbum = songs.count { it.album == null },
            byVolume = songs.groupingBy { it.key.value.substringBefore(':') }.eachCount(),
            cached = container.songStore.all().size,
            syncError = (sync as? SyncResult.Failure)?.error?.code,
            sample = songs.take(200).map { "${it.displayTitle} | ${it.displayArtist} | ${it.displayAlbum}   [${it.fileName}]" },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var hasPermission by remember { mutableStateOf(granted()) }
            var report by remember { mutableStateOf<Report?>(null) }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

            Column(Modifier.padding(16.dp)) {
                Text("Library report (debug build only)")
                Text("Music access: " + if (hasPermission) "allowed" else "not allowed")
                if (!hasPermission) {
                    Button(onClick = { ask.launch(Manifest.permission.READ_MEDIA_AUDIO) }) { Text("Allow music access") }
                } else {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            report = buildReport()
                            busy = false
                        }
                    }) { Text(if (busy) "Scanning..." else "Scan now") }
                }
                report?.let { r ->
                    Text("MP3 files found by Android: ${r.total} (scan ${r.scanMs} ms)")
                    Text("Of those flagged as music: ${r.flaggedMusic}")
                    Text("Missing title / artist / album: ${r.missingTitle} / ${r.missingArtist} / ${r.missingAlbum}")
                    Text("By volume: ${r.byVolume}")
                    Text("Saved in app.db after sync: ${r.cached}" + (r.syncError?.let { "  (sync error $it)" } ?: ""))
                    Text("First ${r.sample.size} songs (title | artist | album):")
                    LazyColumn { items(r.sample) { Text(it) } }
                }
            }
        }
    }
}
