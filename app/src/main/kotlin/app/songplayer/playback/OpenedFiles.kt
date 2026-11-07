package app.songplayer.playback

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.songplayer.core.errors.OpenFileError
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.openfile.ExternalFile
import app.songplayer.core.openfile.OpenFileRules
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the app can ask about an address another app handed over; the real one reads through the content resolver, tests use a fake. */
interface OpenableSource {
    fun mimeType(uri: Uri): String?

    fun displayName(uri: Uri): String?

    /** The first [max] bytes, or null when the address cannot be opened or read. */
    fun head(uri: Uri, max: Int): ByteArray?

    /** Keeps access past this run if the other app offered it (see [FileOpener.keepAccess]). */
    fun keepAccess(uri: Uri, intentFlags: Int)
}

/** [OpenableSource] on the content resolver. Blocking calls: run them off the main thread. */
class ResolverSource(private val resolver: ContentResolver) : OpenableSource {
    override fun mimeType(uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (e: RuntimeException) {
        null
    }

    override fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: RuntimeException) {
        null
    }

    override fun head(uri: Uri, max: Int): ByteArray? = try {
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(max)
            var filled = 0
            while (filled < buffer.size) {
                val n = stream.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            buffer.copyOf(filled)
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Only the newest file keeps lasting access (Android limits how many addresses an app may hold). A provider that
     * does not offer it is not an error: the saved file then cannot play after the temporary access ends, and fails
     * like any other song that cannot be read (R-12).
     */
    override fun keepAccess(uri: Uri, intentFlags: Int) {
        val wanted = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (intentFlags and wanted != wanted) return
        try {
            // Take the new one first: if the provider refuses, the previous file keeps its access. The app holds no other
            // lasting grants today; the backup story, if it uses any, must record its own and release only the file's.
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: SecurityException) {
            // Not offered for this address.
        }
    }
}

/**
 * Looks at a file another app handed over and decides whether it may replace the queue (R-15, Technical Design 5.3).
 * Only `content` addresses are opened: a `file` address from outside could point at any path the app can read, so it is
 * refused as unreadable.
 */
class FileOpener(private val source: OpenableSource) {
    /** The file to play, or a typed [OpenFileError] when it is not an MP3 ([OpenFileError.NotMp3]) or cannot be read ([OpenFileError.Unreadable]). */
    @Throws(OpenFileError::class)
    fun inspect(uri: Uri): ExternalFile {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) throw OpenFileError.Unreadable()
        val name = source.displayName(uri)
        OpenFileRules.check(source.mimeType(uri), name, source.head(uri, OpenFileRules.HEAD_BYTES))?.let { throw it }
        return ExternalFile(uri.toString(), OpenFileRules.displayName(name, uri.toString()))
    }

    fun keepAccess(uri: Uri, intentFlags: Int) = source.keepAccess(uri, intentFlags)
}

/**
 * What the screens need of an "Open with" request: whether the refusal dialog (UX Design D7) is showing, and a count
 * that goes up each time a file has started to play, so the app can open Now Playing (UX Design D6). The old queue is
 * replaced only after the file passed its checks; a refused file changes nothing.
 */
class OpenedFiles(
    private val opener: FileOpener,
    private val playback: PlaybackClient,
    private val log: AppLog,
    private val scope: CoroutineScope,
) {
    private val _refused = MutableStateFlow(false)
    val refused: StateFlow<Boolean> = _refused

    private val _started = MutableStateFlow(0)

    /** How many files have started to play in this run; the player is needed once it is above zero, even without library access. */
    val started: StateFlow<Int> = _started

    private val _showPlayer = MutableStateFlow(false)

    /** True from the moment a file starts until the screen has opened Now Playing for it ([playerShown]); a recreated screen does not open it again. */
    val showPlayer: StateFlow<Boolean> = _showPlayer

    private var current: Job? = null

    private sealed interface Outcome {
        data class Playable(val file: ExternalFile) : Outcome

        data class Refused(val error: OpenFileError) : Outcome
    }

    /** Handles the intent the app was started or brought to the front with; anything but a view of an address is ignored. */
    fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val flags = intent.flags
        // The newest request wins: an older one that is still waiting for a slow provider is dropped, so it cannot
        // finish last and replace what was opened after it.
        current?.cancel()
        current = scope.launch {
            val outcome = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = opener.inspect(uri)
                        opener.keepAccess(uri, flags)
                        Outcome.Playable(file)
                    } catch (e: OpenFileError) {
                        Outcome.Refused(e)
                    }
                }
            } ?: Outcome.Refused(OpenFileError.Unreadable())
            when (outcome) {
                is Outcome.Playable -> {
                    playback.playFile(outcome.file)
                    _started.value += 1
                    _showPlayer.value = true
                }
                is Outcome.Refused -> {
                    log.event(LogCode.OPEN_FILE_REJECTED, if (outcome.error is OpenFileError.NotMp3) 0 else 1)
                    _refused.value = true
                }
            }
        }
    }

    fun dismissRefused() {
        _refused.value = false
    }

    /** The screen has opened Now Playing for the file that started. */
    fun playerShown() {
        _showPlayer.value = false
    }

    private companion object {
        /** A provider that does not answer (a cloud document) must not keep the app waiting; the file is then treated as unreadable. */
        const val PROVIDER_TIMEOUT_MS = 10_000L
    }
}
