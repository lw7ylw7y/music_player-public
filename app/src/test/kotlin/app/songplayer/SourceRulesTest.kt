package app.songplayer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-scan rules. They are heuristics: they catch the usual forms, not every way to write them. */
class SourceRulesTest {
    @Test
    fun Architecture_NoDirectAndroidLogOutsideWrapper() {
        val files = RepoFiles.kotlinSources("app") + RepoFiles.kotlinSources("core")
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val forbidden = Regex("""android\.util\.(Log|\*)|\bLog\.[a-z]\(|\bprintln\(|System\.(out|err)""")
        val offending = files.filter { it.name != "LogcatSink.kt" && forbidden.containsMatchIn(it.readText()) }
        assertTrue("only LogcatSink may use android.util.Log: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Architecture_NoHardCodedTextInComposables() {
        val files = RepoFiles.kotlinSources("app")
        assertTrue("no sources found; the scan would pass for nothing", files.isNotEmpty())
        val literal = Regex("""(Text\(\s*(text\s*=\s*)?"|\b(contentDescription|title|label|placeholder)\s*=\s*")""")
        val offending = files.filter { literal.containsMatchIn(it.readText()) }
        assertTrue("user-visible text must be a string resource (NFR-8): ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Architecture_OnlyPlaybackPackage_ImportsExoPlayer() {
        val files = RepoFiles.kotlinSources("app")
        assertTrue("no sources found; the scan would pass for nothing", files.isNotEmpty())
        val offending = files.filter {
            it.invariantSeparatorsPath.contains("/app/songplayer/playback/").not() &&
                it.readText().contains("androidx.media3.exoplayer")
        }
        assertTrue("screens must use the MediaController, not the player (Technical Design 4.4): ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Architecture_NoCodePath_OpensSongFilesForWriting() {
        // The player never moves or deletes the user's music on its own. Two packages may write: `details` changes only the tags of a song
        // file (R-27, Technical Design 5.8: the tag writer, the journal files in the app's own storage and the write-access helper), and
        // `library/SongDeleter` asks Android to delete songs the user chose (R-50), `playlist/PlaylistFileTransfer` writes the export file the user chose (R-26), and `split` makes the new song files of a split and asks Android to delete after the app's own question (V2, Technical Design 3 and
        // 5.9: `SongWriter`, and `RunRecordFile` for the run record in the app's own storage). Everything else only
        // reads: no write stream, no write mode, no delete, rename or write request on a file or the media index. Comments are ignored,
        // so a note that names one of these does not fail the test.
        val files = RepoFiles.kotlinSources("app") + RepoFiles.kotlinSources("core")
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val writing = Regex(
            """openOutputStream|FileOutputStream|RandomAccessFile|FileChannel|\bFileWriter\b|\.outputStream\(|Files\.(write|newOutputStream|delete|move|copy)|""" +
                """writeText|writeBytes|appendText|appendBytes|deleteRecursively|renameTo|\.delete\(\)|MODE_(READ_WRITE|WRITE_ONLY|TRUNCATE|APPEND)|""" +
                """createWriteRequest|createDeleteRequest|createTrashRequest|(?i:resolver)\.(delete|update|insert)\(|AutoCloseOutputStream|""" +
                """\.(openFile|openFileDescriptor|openAssetFileDescriptor)\([^"]*"(w|rw|wt|wa|rwt)\"""",
        )
        val allowed = setOf("details/TagFileWriter.kt", "details/WriteJournalFiles.kt", "details/MediaManagement.kt", "split/RunRecordFile.kt", "split/SongWriter.kt", "library/SongDeleter.kt", "playlist/PlaylistFileTransfer.kt")
        val offending = files.filter { f ->
            writing.containsMatchIn(withoutComments(f.readText())) && allowed.none { f.invariantSeparatorsPath.endsWith("/app/songplayer/$it") }
        }
        assertTrue("these files may write to song files: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Split_OnlySongWriter_InsertsAndDeletes() {
        // V2 (Technical Design 3): only `split/SongWriter` makes audio files in the media index or asks Android to delete one; the run
        // record file is the one other writer in `split`, and it writes only inside the app's private storage.
        val files = RepoFiles.kotlinSources("app") + RepoFiles.kotlinSources("core")
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val media = Regex("""(?i:resolver)\.(insert|delete)\(|createDeleteRequest|createTrashRequest|IS_PENDING""")
        val offending = files.filter { media.containsMatchIn(withoutComments(it.readText())) && !it.invariantSeparatorsPath.endsWith("/app/songplayer/split/SongWriter.kt") && !it.invariantSeparatorsPath.endsWith("/app/songplayer/library/SongDeleter.kt") }
        assertTrue("only SongWriter (and SongDeleter, for the user's own delete from the library, R-50) may insert or delete media: ${offending.map { it.name }}", offending.isEmpty())
        val record = files.single { it.name == "RunRecordFile.kt" }.readText()
        assertTrue("the run record must not touch the media index", !Regex("""MediaStore|ContentResolver""").containsMatchIn(withoutComments(record)))
    }

    @Test
    fun Split_CoreSplit_ImportsNoAndroid() {
        val split = RepoFiles.kotlinSources("core").filter { it.invariantSeparatorsPath.contains("/core/split/") }
        assertTrue("no split sources found; the scan would pass for nothing", split.size >= 8)
        val offending = split.filter { Regex("""import (android|androidx)\.""").containsMatchIn(it.readText()) }
        assertTrue("core.split must not import android: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Split_Privacy_NoNameOrPathInLogs() {
        // Technical Design 6: only codes and counts. The split code never prints and never calls the platform log; the log wrapper takes integers.
        val files = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/songplayer/split/") }
        assertTrue("no split sources found; the scan would pass for nothing", files.size >= 3)
        val leaks = Regex("""\bprintln\(|Log\.[dievw]\(|log\??\.event\(\s*LogCode\.\w+\s*,[^)]*(name|path|folder)""", RegexOption.IGNORE_CASE)
        val offending = files.filter { leaks.containsMatchIn(withoutComments(it.readText())) }
        assertTrue("these files may log a name or a path: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Network_OnlyTheDetailsNetPackage_UsesJavaNet() {
        // Technical Design 4.4: the lookup client is the only code that opens a connection.
        val files = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/src/main/") }
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val network = Regex("""java\.net\.|HttpURLConnection|HttpsURLConnection|okhttp|\bURL\(""")
        val offending = files.filter {
            !it.invariantSeparatorsPath.contains("/app/songplayer/details/net/") && network.containsMatchIn(withoutComments(it.readText()))
        }
        assertTrue("these files use the network outside details/net: ${offending.map { it.name }}", offending.isEmpty())
        assertTrue("the lookup client moved; update this rule", files.any { it.invariantSeparatorsPath.contains("/app/songplayer/details/net/") })
    }

    @Test
    fun Work_NoPeriodicRequests_AndOnlyTheDetailsWorker() {
        // Background work is the song-details lookup and the splitter (Technical Design 5.8, 8; 5.9): unique one-time works, never periodic.
        val files = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/src/main/") }
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val periodic = files.filter { withoutComments(it.readText()).contains("PeriodicWorkRequest") }
        assertTrue("periodic work is not allowed: ${periodic.map { it.name }}", periodic.isEmpty())
        val oneTime = files.filter { withoutComments(it.readText()).contains("OneTimeWorkRequest") }.map { it.name }
        assertEquals(listOf("DetailsWork.kt", "SplitWork.kt"), oneTime.sorted())
        val workers = files.filter { Regex("""\bCoroutineWorker\b|\bListenableWorker\b|\bWorker\(""").containsMatchIn(withoutComments(it.readText())) }.map { it.name }
        assertEquals(listOf("DetailsWorker.kt", "SplitWorker.kt"), workers.sorted())
    }

    @Test
    fun Details_Privacy_NoFingerprintOrTitleInLogsOrDiagnostics() {
        // The fingerprint, the request and the answer identify what the user owns (R-19): none may reach AppLog (codes and integers only,
        // so text cannot be passed) or an exception message (the typed errors carry a code only, AppErrorTest). Here: the details code
        // never calls the platform log, never prints, and never builds a message from a fingerprint or an answer.
        val files = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/songplayer/details/") }
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val leaks = Regex("""\bprintln\(|Log\.[dievw]\(|Throwable\(.*(fingerprint|answer|body)|Exception\(.*(fingerprint|answer|body)|log\.event\([^)]*(fingerprint|answer|body)""", RegexOption.IGNORE_CASE)
        val offending = files.filter { leaks.containsMatchIn(withoutComments(it.readText())) }
        assertTrue("these files may log or throw private content: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Overlay_ListsAndSearch_ReadThroughTheMerge() {
        // The lists, search, the queue and the car read songs with the song details laid over the files' tags (Technical Design 5.8):
        // `indexed()` and `get()` do that; `all()` is the files' own tags for the library sync and must not feed a screen.
        val files = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/src/main/") }
        assertTrue("no sources found; the scan would pass for nothing", files.size >= 5)
        val offending = files.filter { withoutComments(it.readText()).contains("songStore.all()") }
        assertTrue("these files read the songs without the details overlay: ${offending.map { it.name }}", offending.isEmpty())
    }

    @Test
    fun Widget_Updates_OnlyFromSongOrStateChange() {
        // The widget is redrawn only when the playback service reports a different song or play state (R-24, NFR-10): no
        // alarm, no periodic work, no timer and no delayed loop in the widget's code, and no update period in its definition.
        val widgetFiles = RepoFiles.kotlinSources("app").filter { it.invariantSeparatorsPath.contains("/app/songplayer/widget/") }
        assertTrue("no widget sources found; the scan would pass for nothing", widgetFiles.size >= 3)
        val scheduling = Regex("""AlarmManager|WorkManager|PeriodicWorkRequest|OneTimeWorkRequest|postDelayed|\bTimer\(|scheduleAtFixedRate|\bdelay\(|JobScheduler""")
        val offending = widgetFiles.filter { scheduling.containsMatchIn(withoutComments(it.readText())) }
        assertTrue("the widget code schedules work: ${offending.map { it.name }}", offending.isEmpty())
        val info = File(RepoFiles.root, "app/src/main/res/xml/song_widget_info.xml").readText()
        assertTrue("the widget must have no update period", Regex("""updatePeriodMillis\s*=\s*"0"""").containsMatchIn(info))
    }

    private fun withoutComments(source: String) =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")
}
