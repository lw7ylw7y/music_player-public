package app.songplayer.playlist

import android.content.Context
import android.net.Uri
import app.songplayer.core.errors.PlaylistFileError
import app.songplayer.core.errors.StoreError
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.playlist.DecisionStore
import app.songplayer.core.playlist.PlaylistFile
import app.songplayer.core.playlist.PlaylistStore
import java.io.IOException

/** What an export or an import did, for the screen to put into words. */
sealed interface TransferResult {
    data class Exported(val playlists: Int, val decisions: Int) : TransferResult
    data object NothingToExport : TransferResult
    /** [detailsFailed]: the playlists were added but the song-details record could not be restored. */
    data class Imported(val playlists: Int, val decisions: Int, val detailsFailed: Boolean) : TransferResult
    data object NothingNew : TransferResult
    /** The file is not a Music Player playlist file (damaged, empty, foreign, too big); nothing was changed. */
    data object Refused : TransferResult
    /** The playlists database cannot be read; [damaged] says whether the file is damaged. Nothing was changed. */
    data class Unavailable(val damaged: Boolean) : TransferResult
    /** Reading the chosen file failed; nothing was changed. */
    data object Failed : TransferResult
    /** Writing the export file failed; the file the user chose may be empty or incomplete, the playlists are unchanged. */
    data object ExportFailed : TransferResult
}

/**
 * Export and import of the playlists through a file the user chose with the system file picker (R-26, Technical Design 5.7): no storage
 * permission, no network. The rules and the format are in `core` ([PlaylistFile]); this class only moves the text. The file holds playlist
 * and song names, so nothing of it is ever logged (only codes and counts). Call from a background thread.
 */
class PlaylistFileTransfer(context: Context, private val store: PlaylistStore, private val details: DecisionStore, private val log: AppLog) {
    private val resolver = context.contentResolver

    fun export(uri: Uri): TransferResult {
        val playlists = try { store.exportAll() } catch (e: StoreError) { return unavailable(EXPORT, e) }
        val decisions = try { details.exportDecisions() } catch (e: StoreError) { return unavailable(EXPORT, e) }
        if (playlists.isEmpty() && decisions.isEmpty()) return TransferResult.NothingToExport
        val text = PlaylistFile.write(playlists, decisions)
        return try {
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            TransferResult.Exported(playlists.size, decisions.size)
        } catch (_: IOException) {
            log.event(LogCode.PLAYLIST_FILE_FAILED, EXPORT, 1)
            TransferResult.ExportFailed
        } catch (_: SecurityException) {
            log.event(LogCode.PLAYLIST_FILE_FAILED, EXPORT, 1)
            TransferResult.ExportFailed
        }
    }

    fun import(uri: Uri, nowSec: Long): TransferResult {
        val bytes = try {
            (resolver.openInputStream(uri) ?: throw IOException("no stream")).use { it.readNBytes(PlaylistFile.MAX_BYTES + 1) }
        } catch (_: IOException) {
            log.event(LogCode.PLAYLIST_FILE_FAILED, IMPORT, 1)
            return TransferResult.Failed
        } catch (_: SecurityException) {
            log.event(LogCode.PLAYLIST_FILE_FAILED, IMPORT, 1)
            return TransferResult.Failed
        }
        val content = try {
            if (bytes.size > PlaylistFile.MAX_BYTES) throw PlaylistFileError.TooLarge()
            PlaylistFile.read(String(bytes, Charsets.UTF_8))
        } catch (_: PlaylistFileError) {
            log.event(LogCode.PLAYLIST_FILE_FAILED, IMPORT, 0)
            return TransferResult.Refused
        }
        val added = try { if (content.playlists.isEmpty()) 0 else store.importAll(content.playlists, nowSec) } catch (e: StoreError) { return unavailable(IMPORT, e) }
        // The two databases are separate, so this step is its own: the playlists above stay even if the record cannot be restored.
        var restored = 0
        var detailsFailed = false
        try { restored = details.importDecisions(content.decisions) } catch (e: StoreError) {
            detailsFailed = true
            log.event(LogCode.PLAYLIST_FILE_FAILED, IMPORT, 3)
        }
        return if (added == 0 && restored == 0 && !detailsFailed) TransferResult.NothingNew else TransferResult.Imported(added, restored, detailsFailed)
    }

    private fun unavailable(what: Int, e: StoreError): TransferResult {
        val damaged = e is StoreError.Corrupt
        log.event(LogCode.PLAYLIST_FILE_FAILED, what, if (damaged) 2 else 1)
        return TransferResult.Unavailable(damaged)
    }

    private companion object {
        const val EXPORT = 0
        const val IMPORT = 1
    }
}
