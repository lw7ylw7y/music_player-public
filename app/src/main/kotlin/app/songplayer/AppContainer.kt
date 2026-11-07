package app.songplayer

import app.songplayer.core.split.RunStore
import app.songplayer.split.RunRecordFile
import java.io.File
import android.content.Context
import app.songplayer.core.library.LibrarySource
import app.songplayer.core.library.LibrarySync
import app.songplayer.core.library.NewSongsNotice
import app.songplayer.core.library.SongKey
import app.songplayer.core.details.AttemptLimit
import app.songplayer.core.details.DetailsRunner
import app.songplayer.core.details.DetailsService
import app.songplayer.core.details.SongAudio
import app.songplayer.core.errors.DetailsError
import app.songplayer.details.AudioDecoder
import app.songplayer.details.DetailsWork
import app.songplayer.details.MediaManagement
import app.songplayer.details.NetworkStatus
import app.songplayer.library.db.RoomDetailsSettings
import app.songplayer.playback.SystemClockSource
import app.songplayer.playback.audioUri
import kotlinx.coroutines.delay
import app.songplayer.details.DetailsActions
import app.songplayer.details.TagFileWriter
import app.songplayer.details.net.AcoustIdClient
import app.songplayer.core.text.FallbackKeyer
import app.songplayer.core.text.ResilientKeyer
import app.songplayer.core.text.TextKeyer
import app.songplayer.core.log.AppLog
import app.songplayer.core.log.LogCode
import app.songplayer.core.log.LogSink
import app.songplayer.library.LibraryRepository
import app.songplayer.library.MediaStoreSource
import app.songplayer.playback.FileOpener
import app.songplayer.playback.OpenedFiles
import app.songplayer.playback.PlaybackClient
import app.songplayer.playback.ResolverSource
import app.songplayer.playlist.PlaylistsDatabase
import app.songplayer.playlist.RoomPlaylistStore
import app.songplayer.library.db.AppDatabase
import app.songplayer.library.db.RoomDetailsStore
import app.songplayer.library.db.RoomErrorLog
import app.songplayer.library.db.RoomResumeStore
import app.songplayer.library.db.RoomSongStore
import app.songplayer.text.IcuKeyer
import app.songplayer.widget.SongWidget
import app.songplayer.widget.WidgetUpdater
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written dependency wiring (Technical Design 3 and 4.2). Later stories add services here. */
class AppContainer(private val context: Context, logSinks: List<LogSink>) {
    val log = AppLog(logSinks)

    /** The application context, for the pieces that start work of their own (the splitter's job). */
    val appContext: Context get() = context

    private val _splitReopen = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True from the user's tap on the splitter's notification until the screens have taken it ([takeSplitReopen]): then they open the flow where the job is. */
    val splitReopen: kotlinx.coroutines.flow.StateFlow<Boolean> = _splitReopen

    fun requestSplitReopen() { _splitReopen.value = true }

    /** Takes the request: the screens act on a tap once, and a later start of the app does not find it again. */
    fun takeSplitReopen() { _splitReopen.value = false }

    /** The run records of the splitter (V2), in the app's private storage. */
    val splitRuns: RunStore by lazy { RunStore(RunRecordFile(File(context.filesDir, "split-runs"))) }

    val database: AppDatabase by lazy { AppDatabase.open(context) }
    val keyer: TextKeyer = ResilientKeyer(IcuKeyer(), FallbackKeyer, log)
    val detailsStore: RoomDetailsStore by lazy { RoomDetailsStore(database) }
    val songStore: RoomSongStore by lazy { RoomSongStore(database, keyer, pendingDetails = { detailsStore.pending() }) }
    val resumeStore: RoomResumeStore by lazy { RoomResumeStore(database) }

    /** The newest error codes, for "Copy diagnostics" (R-21). */
    val errorLog: RoomErrorLog by lazy { RoomErrorLog(database) }
    val diagnostics: Diagnostics by lazy { Diagnostics(context) { errorLog } }

    val songDeleter: app.songplayer.library.SongDeleter by lazy { app.songplayer.library.SongDeleter(context) }

    /** The playlists, in their own database file so that only they are backed up (Technical Design 6). */
    val playlistStore: RoomPlaylistStore by lazy { RoomPlaylistStore(PlaylistsDatabase.open(context)) }
    val playlistFiles: app.songplayer.playlist.PlaylistFileTransfer by lazy { app.songplayer.playlist.PlaylistFileTransfer(context, playlistStore, detailsStore, log) }
    val librarySource: LibrarySource = MediaStoreSource(context)

    /** Writes tags into the song files and puts right a write that was cut off (R-27, Technical Design 5.8). */
    val tagFileWriter: TagFileWriter by lazy {
        TagFileWriter(
            context,
            songs = { key -> songStore.get(SongKey(key)) },
            playlistSizeChanged = { key, size -> playlistStore.updateSize(SongKey(key), size) },
            log = log,
        )
    }

    /** Hand edits, Undo and the carrying of changes into the files (Stories H3). */
    val detailsActions: DetailsActions by lazy { DetailsActions(detailsStore, songStore, tagFileWriter, log) }

    /** Turns a song into the fingerprint the lookup takes (Stories H5). */
    val audioDecoder: AudioDecoder by lazy { AudioDecoder(context) }

    /** The online lookup; with no key in this build every call fails with `NotConfigured` and sends nothing. */
    val lookupClient: AcoustIdClient by lazy { AcoustIdClient(BuildConfig.ACOUSTID_KEY, "SongPlayer/${BuildConfig.VERSION_NAME}") }

    val detailsSettings: RoomDetailsSettings by lazy { RoomDetailsSettings(database.settingDao()) }

    val networkStatus: NetworkStatus by lazy { NetworkStatus(context) }

    /** True when Android lets the app change the song files without a dialog each time ("Media management", R-27). */
    fun filesChangeable(): Boolean = MediaManagement.isGranted(context)

    /**
     * Android's write request for the songs whose changes wait for access (null when none wait). For files another app owns, this is how
     * the app is allowed to change them: with the "Media management" access Android grants it without a dialog, without it the user sees
     * one dialog for the batch. Blocking (reads the store): call it off the main thread.
     */
    fun writeGrantRequest(alreadyAsked: MutableSet<String>): android.content.IntentSender? {
        val batch = detailsActions.nextGrantBatch(alreadyAsked)
        if (batch.isEmpty()) return null
        alreadyAsked.addAll(batch.map { it.first })
        return MediaManagement.writeRequest(context, batch.map { it.second }).intentSender
    }

    /** The switch "Fill in missing details" (the consent dialog was accepted, or it was accepted before): turning on starts the work, turning off cancels it at once. */
    fun setDetailsEnabled(on: Boolean) {
        detailsSettings.setEnabled(on, System.currentTimeMillis())
        if (on) DetailsWork.enqueue(context, detailsSettings.wifiOnly(), 0, androidx.work.ExistingWorkPolicy.REPLACE) else DetailsWork.cancel(context)
    }

    /** The switch "Also replace details that are already filled in": the work starts over so complete songs are included (or left out). */
    fun setDetailsReplace(on: Boolean) {
        detailsSettings.setReplaceExisting(on)
        if (detailsSettings.enabled()) DetailsWork.enqueue(context, detailsSettings.wifiOnly(), 0, androidx.work.ExistingWorkPolicy.REPLACE)
    }

    /** The switch "Only look up new songs": the work starts over so the songs that are now left out (or let in) are decided at once. */
    fun setDetailsOnlyNew(on: Boolean) {
        detailsSettings.setOnlyNew(on, System.currentTimeMillis() / 1000)
        if (detailsSettings.enabled()) DetailsWork.enqueue(context, detailsSettings.wifiOnly(), 0, androidx.work.ExistingWorkPolicy.REPLACE)
    }

    /** The switch "Only on Wi-Fi": queued work starts over with the new network constraint. */
    fun setDetailsWifiOnly(on: Boolean) {
        detailsSettings.setWifiOnly(on)
        if (detailsSettings.enabled()) DetailsWork.enqueue(context, on, 0, androidx.work.ExistingWorkPolicy.REPLACE)
    }

    /** One song through the lookup: decode, one request, the match rules, the store (Stories H6). */
    val detailsService: DetailsService by lazy {
        val audio = SongAudio { song ->
            try {
                audioDecoder.fingerprint(audioUri(song.mediaStoreId)) { detailsStopped() }.fingerprint
            } catch (e: DetailsError.NotDecodable) {
                log.event(LogCode.DETAILS_DECODE_FAILED, 0); throw e
            } catch (e: DetailsError.Unavailable) {
                log.event(LogCode.DETAILS_DECODE_FAILED, 1); throw e
            }
        }
        DetailsService(detailsSettings, detailsStore, audio, lookupClient, SystemClockSource, { delay(it) }, same = { a, b -> keyer.searchFold(a) == keyer.searchFold(b) })
    }

    /** One background run over the songs (Stories H7); the worker calls it. */
    val detailsRunner: DetailsRunner by lazy {
        DetailsRunner(detailsSettings, detailsService, SystemClockSource, AttemptLimit(SystemClockSource)) { detailsStore.decidedKeys() }
    }

    /** True while the running background run has been asked to stop (the work was replaced or cancelled): a decode in progress gives up and the song stays pending. */
    @Volatile
    var detailsStopped: () -> Boolean = { false }

    /** Queues the background lookup if the feature is on (app start, a library sync that found songs); work already queued is kept. */
    fun queueDetailsIfOn() {
        if (detailsSettings.enabled()) DetailsWork.enqueue(context, detailsSettings.wifiOnly())
    }

    val newSongs = NewSongsNotice()
    val playback = PlaybackClient(context, log)

    /** "Open with" requests from other apps (R-15); results are handled on the main thread, the file checks off it. */
    val openedFiles: OpenedFiles by lazy {
        OpenedFiles(FileOpener(ResolverSource(context.contentResolver)), playback, log, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraws the home-screen widget when the song or the play state changes (R-24). */
    val widgetUpdater: WidgetUpdater by lazy { WidgetUpdater(applicationScope, onFailure = { log.event(LogCode.WIDGET_FAILED, 0) }) { SongWidget().updateAll(context) } }
    val library: LibraryRepository by lazy {
        LibraryRepository(
            LibrarySync(librarySource, songStore),
            applicationScope,
            Dispatchers.IO,
            beforeSync = { songStore.recomputeKeysIfNeeded() },
            newSongs = newSongs,
            afterSync = { detailsActions.confirmWritten(); queueDetailsIfOn() },
        )
    }
}
