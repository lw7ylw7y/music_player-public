package app.songplayer.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.songplayer.BuildConfig
import app.songplayer.SongPlayerApp
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.library.Song
import app.songplayer.library.MediaStoreSource
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Debug-only check of the song-details lookup on the real phone (Stories H5, probes P2 and P5). It is not a test and it changes
 * nothing: it reads the media index, decodes songs and, on the second button only, sends fingerprints and lengths to the lookup service.
 * It writes no file and no database row, and nothing is logged; the result stays on the screen, and "Copy" puts the text on the clipboard
 * so it can be pasted into a note. It is not part of the release build.
 */
class DetailsCheckActivity : ComponentActivity() {

    private fun granted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Songs of at least a minute, spread evenly over the library, the same songs each time. */
    private suspend fun sample(count: Int): List<Song> = withContext(Dispatchers.IO) {
        val songs = MediaStoreSource(this@DetailsCheckActivity).scan().filter { it.durationMs >= 60_000 }.sortedBy { it.fileName }
        if (songs.size <= count) songs else List(count) { songs[it * songs.size / count] }
    }

    private companion object {
        /** Few songs on purpose: the user wants a short check (decision 2026-11-05). */
        const val SONGS = 5
    }

    private fun uri(song: Song) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.mediaStoreId)

    private suspend fun timing(out: (String) -> Unit) {
        val container = (application as SongPlayerApp).container
        val songs = sample(SONGS)
        out("Timing: decode and fingerprint ${songs.size} songs (nothing is sent)")
        val times = mutableListOf<Long>()
        for ((i, s) in songs.withIndex()) {
            val t0 = System.nanoTime()
            var fingerprintMs = 0L
            val outcome = withContext(Dispatchers.IO) {
                try { fingerprintMs = container.audioDecoder.fingerprint(uri(s)).fingerprintMs; "ok" } catch (e: DetailsError) { e.code }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            times += ms
            out("${i + 1}. $ms ms (fingerprint $fingerprintMs ms, decoding ${ms - fingerprintMs} ms), ${s.durationMs / 1000} s long, $outcome")
        }
        if (times.isNotEmpty()) {
            val sorted = times.sorted()
            out("Songs ${sorted.size}, median ${sorted[sorted.size / 2]} ms, slowest ${sorted.last()} ms (the story's limit is 5000 ms)")
        }
    }

    /** Numbers only: how the lookup stands on the whole library (Stories H10). Nothing is sent and nothing is changed. */
    private suspend fun report(out: (String) -> Unit) {
        val container = (application as SongPlayerApp).container
        val (songs, counts, pending) = withContext(Dispatchers.IO) {
            Triple(container.songStore.indexed().map { it.song }, container.detailsStore.statusCounts(), container.detailsStore.pending())
        }
        val missing = songs.count { it.title.isNullOrBlank() || it.artist.isNullOrBlank() || it.album.isNullOrBlank() }
        out("Library report (numbers only)")
        out("Songs: ${songs.size}; still missing a title, artist or album: $missing")
        out("Filled in automatically (applied): ${counts["applied"] ?: 0}; waiting for your review: ${counts["held"] ?: 0}; rejected by you: ${counts["rejected"] ?: 0}")
        out("Not found: ${counts["notFound"] ?: 0}; edited by hand: ${counts["manual"] ?: 0}")
        out("Changes not yet shown from the files: ${pending.size} (waiting for access ${pending.count { it.write.name == "Waiting" }}, failed ${pending.count { it.write.name == "Failed" }}, written but not yet re-read ${pending.count { it.write.name == "InFile" }})")
    }

    private suspend fun coverage(out: (String) -> Unit) {
        val container = (application as SongPlayerApp).container
        if (BuildConfig.ACOUSTID_KEY.isEmpty()) { out("No key: put acoustid.key in local.properties and build again."); return }
        val songs = sample(SONGS)
        out("Coverage: ${songs.size} songs. Look at every SURE line: is the service's song the same as yours? A wrong SURE is the worst case.")
        var recognised = 0; var sure = 0; var oneAlbum = 0; var failed = 0
        for ((i, s) in songs.withIndex()) {
            if (i > 0) delay(1000) // one request a second
            val label = "${i + 1}. ${s.fileName} [tags: ${s.title ?: "-"} / ${s.artist ?: "-"} / ${s.album ?: "-"}, ${s.durationMs / 1000} s]"
            val candidates = try {
                withContext(Dispatchers.IO) {
                    val fp = container.audioDecoder.fingerprint(uri(s))
                    container.lookupClient.lookup(fp.fingerprint, fp.durationS)
                }
            } catch (e: DetailsError.QuotaReached) { out("STOPPED after $i songs: the service asked us to wait."); return }
            catch (e: DetailsError.NotConfigured) { out("STOPPED: the key was refused."); return }
            catch (e: DetailsError) { failed++; out("$label\n   no result: ${e.code}"); continue }
            if (candidates.isEmpty()) { out("$label\n   NOT FOUND"); continue }
            recognised++
            val best = candidates.first()
            val lengthOk = best.durationS != null && Math.abs(best.durationS!! - s.durationMs / 1000) <= 5
            val isSure = best.score >= 0.95 && lengthOk
            val albums = best.albums.filter { it.type == "Album" }.map { it.title }.distinct()
            if (isSure) sure++
            if (isSure && albums.size == 1) oneAlbum++
            out("$label\n   ${if (isSure) "SURE" else "held"}: ${best.title} / ${best.artists.joinToString(", ")} / albums: " +
                "${albums.joinToString("; ").ifEmpty { "none of type Album" }} (score ${best.score}, ${best.durationS ?: "?"} s); ${candidates.size} candidate(s)")
        }
        out("DONE. Tried ${songs.size}; recognised $recognised; SURE (score 0.95 or more, length within 5 s; looser than the final rule) $sure; " +
            "of those with exactly one Album $oneAlbum; failed $failed.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var hasPermission by remember { mutableStateOf(granted()) }
            var busy by remember { mutableStateOf(false) }
            val lines = remember { mutableStateListOf<String>() }
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboardManager.current
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

            fun run(block: suspend ((String) -> Unit) -> Unit) {
                busy = true
                lines.clear()
                scope.launch {
                    try { block { lines.add(it) } } catch (e: Exception) { lines.add("Stopped: ${e.javaClass.simpleName}") }
                    busy = false
                }
            }

            Column(Modifier.padding(16.dp)) {
                Text("Song details check (debug build only)")
                Text("Changes nothing on the phone. The first and the third buttons send nothing; the second sends a fingerprint and a length for 5 songs.")
                if (!hasPermission) {
                    Button(onClick = { ask.launch(Manifest.permission.READ_MEDIA_AUDIO) }) { Text("Allow music access") }
                } else {
                    Row {
                        Button(enabled = !busy, onClick = { run(::timing) }) { Text("Time 5 songs") }
                        Button(enabled = !busy, onClick = { run(::coverage) }) { Text("Coverage check") }
                    }
                    Button(enabled = !busy, onClick = { run(::report) }) { Text("Library report") }
                    Button(enabled = lines.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy") }
                }
                if (busy) Text("Working...")
                LazyColumn { items(lines.toList()) { Text(it) } }
            }
        }
    }
}
