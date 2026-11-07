package app.songplayer.ui

import app.songplayer.core.text.SortOrder
import app.songplayer.library.LibraryTab
import app.songplayer.core.split.FoundPrimary
import app.songplayer.core.split.durationFor
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.songplayer.details.MediaManagement
import app.songplayer.details.SaveResult
import app.songplayer.details.UndoResult
import app.songplayer.core.library.SongKey
import app.songplayer.playlist.TransferResult
import app.songplayer.core.log.LogCode
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.res.stringResource
import app.songplayer.AppContainer
import app.songplayer.R
import app.songplayer.core.details.GrantPolicy
import app.songplayer.core.library.Song
import app.songplayer.core.text.IndexedSong
import app.songplayer.playback.PlayingFrom

private const val INDEX_CHANGE_DELAY_MS = 1_000L
private const val PERMISSION = Manifest.permission.READ_MEDIA_AUDIO
private const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

private fun hasAccess(context: Context) =
    ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Chooses between the first-start screen (A1), the refused screen (A2) and the Library, and starts the
 * library syncs: when the app comes to the front, when Android's media index changes while it is in front,
 * and on request (R-1). A refresh finds the music again, so allowing access in Settings fills the Library
 * without a restart.
 */
@Composable
fun SongPlayerRoot(container: AppContainer) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(container) } })
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val asked by viewModel.accessAsked.collectAsStateWithLifecycle()
    val playlistsViewModel: PlaylistsViewModel = viewModel(factory = viewModelFactory { initializer { PlaylistsViewModel(container.playlistStore, container.log) } })
    val playlistsState by playlistsViewModel.lists.collectAsStateWithLifecycle()
    val playlistDetail by playlistsViewModel.detail.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasAccess(context)) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        viewModel.markAccessAsked()
        granted = allowed
        if (allowed) viewModel.requestSync()
    }

    // Opening the app, and coming back from Settings or from another app.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        granted = hasAccess(context)
        if (granted) viewModel.requestSync()
    }

    // Songs copied to the phone while the app is open (R-1b). Requests during a sync are merged by the repository.
    LifecycleStartEffect(granted) {
        // Copying files makes Android send many notices; one sync a second after the last is enough, and it
        // finds all the new songs together, so "3 new songs found" is one message.
        val handler = Handler(Looper.getMainLooper())
        val sync = Runnable { viewModel.requestSync() }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(sync)
                handler.postDelayed(sync, INDEX_CHANGE_DELAY_MS)
            }
        }
        if (granted) {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        onStopOrDispose {
            handler.removeCallbacks(sync)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    // Playback: the screens only hold a remote control to the playback service (Technical Design 4.2).
    val playback = container.playback
    val now by playback.nowPlaying.collectAsStateWithLifecycle()
    val notificationAsked by viewModel.notificationAsked.collectAsStateWithLifecycle()
    val failedKeys by viewModel.failedKeys.collectAsStateWithLifecycle()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // A file opened from another app plays without any access to the library (R-47), so it needs the player on its own.
    val openedStarted by container.openedFiles.started.collectAsStateWithLifecycle()
    val openedRefused by container.openedFiles.refused.collectAsStateWithLifecycle()
    val showOpenedFile by container.openedFiles.showPlayer.collectAsStateWithLifecycle()
    val playerNeeded = granted || openedStarted > 0
    LifecycleStartEffect(playerNeeded) {
        if (playerNeeded) playback.connect()
        onStopOrDispose { playback.release() }
    }
    val queueVersion by playback.queueVersion.collectAsStateWithLifecycle()
    val sleep by playback.sleep.collectAsStateWithLifecycle()
    fun startPlaying(source: PlayingFrom, songs: List<Song>, index: Int, shuffle: Boolean = false) {
        playback.play(songs, index, source, shuffle)
        // UX D-13: Android's own question, the first time a song starts, never again. Music plays either way.
        if (notificationAsked == false && ContextCompat.checkSelfPermission(context, NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            viewModel.markNotificationAsked()
            askNotifications.launch(NOTIFICATIONS)
        }
    }

    // Playlists show songs with the library's details, and entries whose file moved are linked to it again after a sync (R-17).
    val librarySongs = remember(ui.lists) { ui.lists.songs.map { it.song } }
    LaunchedEffect(librarySongs) {
        playlistsViewModel.setLibrary(librarySongs)
        if (ui.generation > 0 && librarySongs.isNotEmpty()) playlistsViewModel.relink(librarySongs)
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var songDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    // An open album ("album:<key>") or artist ("artist:<key>"), UX Design 4.4 C1; it covers the Library and Search, and Back returns to them.
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var notFoundOpen by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val detailsViewModel: DetailsViewModel = viewModel(factory = viewModelFactory { initializer { DetailsViewModel(container) } })
    val recentDetails by detailsViewModel.recent.collectAsStateWithLifecycle()
    val detailsEnabled by detailsViewModel.enabled.collectAsStateWithLifecycle()
    val splitViewModel: SplitViewModel = viewModel(factory = viewModelFactory { initializer { SplitViewModel(container) } })
    val splitUi by splitViewModel.ui.collectAsStateWithLifecycle()
    val splitOpen by splitViewModel.open.collectAsStateWithLifecycle()
    val splitBanner by splitViewModel.banner.collectAsStateWithLifecycle()
    // The cut whose clip was started: when the screen moves to another cut or closes, the clip stops, so no screen shows "Pause" for another cut's clip.
    var clipFor by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    // Android's request to delete the original or the run's songs (R-47, R-49); the app's own question came first. Without the Media management
    // access Android adds its own dialog here, and a refusal changes nothing.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    // The songs the user chose to delete in the library (R-50): the question, then Android's request, then what to clean up once Android said yes.
    var askDelete by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var deletingKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var deletedKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val kind = deleting
        deleting = null
        if (kind == DELETE_LIBRARY) {
            val keys = deletingKeys
            deletingKeys = null
            if (result.resultCode == Activity.RESULT_OK && keys != null) deletedKeys = keys
        } else if (result.resultCode == Activity.RESULT_OK) {
            when (kind) {
                DELETE_ORIGINAL -> splitViewModel.originalDeleted()
                DELETE_SONGS -> splitViewModel.songsRemoved()
                else -> splitViewModel.deleteDeclined()       // the screen was recreated and the kind is lost: claim nothing
            }
        } else {
            splitViewModel.deleteDeclined()
        }
    }
    val clipTarget = (splitUi as? SplitUi.Checking)?.let { it.index to it.record.cuts[it.index].timeSec }
    val reviewViewModel: ReviewViewModel = viewModel(factory = viewModelFactory { initializer { ReviewViewModel(container) } })
    val reviewCount by detailsViewModel.bannerCount.collectAsStateWithLifecycle()
    val detailsHost = remember { SnackbarHostState() }
    val detailsTurn = remember { Mutex() }
    val detailsScope = rememberCoroutineScope()
    val savedText = stringResource(R.string.details_saved)
    val inAppText = stringResource(R.string.details_in_app_only)
    val unsupportedText = stringResource(R.string.details_in_app_unsupported)
    val lowSpaceText = stringResource(R.string.details_in_app_low_space)
    val tooBigText = stringResource(R.string.details_in_app_too_big)
    val unreadableText = stringResource(R.string.details_in_app_unreadable)
    val allowText = stringResource(R.string.details_allow)
    val saveFailedText = stringResource(R.string.details_save_failed)
    val undoneText = stringResource(R.string.details_undone)
    val undoChangedText = stringResource(R.string.details_undo_file_changed)
    val undoLaterText = stringResource(R.string.details_undo_later)
    val undoLabel = stringResource(R.string.snackbar_undo)
    fun tellDetails(text: String, action: String = "", onAction: () -> Unit = {}) {
        detailsScope.launch { if (showTimed(context, detailsTurn, detailsHost, text, action) == SnackbarResult.ActionPerformed) onAction() }
    }
    fun allowFileChanges() = context.startActivity(MediaManagement.settingsIntent(context))
    // Files that another app owns need Android's write grant (a request that, with Media management on, is granted without a dialog).
    // Many songs go in rounds of 200 (one request each); a round continues only while the last one wrote something (GrantPolicy), and the
    // total is told at the end. The flag keeps the return from the grant screen from asking again.
    var writtenInRounds by rememberSaveable { mutableStateOf(0) }
    var grantInFlight by remember { mutableStateOf(false) }
    var lastAskedCount by rememberSaveable { mutableStateOf<Int?>(null) }
    lateinit var askForWriteAccess: (Int?) -> Unit
    fun finishRounds() {
        val total = writtenInRounds
        writtenInRounds = 0
        tellDetails(if (total > 0) context.resources.getQuantityString(R.plurals.details_written_songs, total, total) else inAppText)
    }
    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        grantInFlight = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            detailsViewModel.flush { summary ->
                val wrote = summary?.songsWritten ?: 0
                writtenInRounds += wrote
                if (summary != null && GrantPolicy.anotherRound(wrote, summary.noAccess)) askForWriteAccess(summary.noAccess) else finishRounds()
            }
        } else {
            finishRounds()
        }
    }
    askForWriteAccess = { count ->
        lastAskedCount = count
        detailsViewModel.writeGrantRequest(none = { grantInFlight = false; finishRounds() }) { sender ->
            grantInFlight = true
            grantLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        }
    }
    /** The edit or the undo is saved in the app but the file did not take it: say why, and for a lack of access ask Android at once. */
    fun inAppOnly(reason: app.songplayer.core.errors.NotWrittenReason?) = when (reason) {
        app.songplayer.core.errors.NotWrittenReason.UnsupportedTag -> tellDetails(unsupportedText)
        app.songplayer.core.errors.NotWrittenReason.LowSpace -> tellDetails(lowSpaceText)
        app.songplayer.core.errors.NotWrittenReason.TooBigToRewrite -> tellDetails(tooBigText)
        app.songplayer.core.errors.NotWrittenReason.Unreadable -> tellDetails(unreadableText)
        // No access: with Media management on, Android grants the write without a dialog, so ask now; else offer the one-time dialog.
        else -> if (container.filesChangeable()) askForWriteAccess(null) else tellDetails(inAppText, allowText) { askForWriteAccess(null) }
    }
    fun undoBatch(batch: Long) = detailsViewModel.undo(batch) { result ->
        when (result) {
            null -> tellDetails(saveFailedText)
            UndoResult.Done -> tellDetails(undoneText)
            UndoResult.FileChanged -> tellDetails(undoChangedText)
            UndoResult.LaterChange -> tellDetails(undoLaterText)
            is UndoResult.InAppOnly -> inAppOnly(result.reason)
        }
    }
    // Changes waiting for the user's permission are written when the app comes to the front (after "Allow" in Settings too).
    // With Media management on, Android grants the write without a dialog, so the app asks by itself for what still waits (the filler's fills too).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        detailsViewModel.flush { summary ->
            if (summary != null && GrantPolicy.askOnResume(summary.noAccess, container.filesChangeable(), grantInFlight, lastAskedCount)) askForWriteAccess(summary.noAccess)
            if (summary != null && summary.noAccess == 0) lastAskedCount = null // nothing refused any more: the next refusal may ask again
        }
    }
    var openPlaylist by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(openPlaylist) {
        val id = openPlaylist
        if (id == null) playlistsViewModel.close() else playlistsViewModel.open(id)
    }

    val playlistActions = PlaylistActions(
        state = playlistsState,
        onOpen = { openPlaylist = it },
        onAdd = playlistsViewModel::addSongs,
        onCreate = playlistsViewModel::create,
        onRetry = playlistsViewModel::reload,
    )

    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // Nothing loaded (for example after the process was restarted): Now Playing cannot stay open.
    LaunchedEffect(now == null) { if (now == null) nowPlayingOpen = false }
    val miniPlayer: @Composable () -> Unit = {
        now?.let { MiniPlayer(it, onToggle = playback::togglePlay, onNext = playback::next, onOpen = { nowPlayingOpen = true }) }
    }

    // Queue, Now Playing and search replace the Library on screen; the holder keeps the Library's scroll positions
    // and open tab, and search's typed text, for Back. Closing search drops its text (UX Design 4.3).
    var searching by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    // An opened file starts on Now Playing (UX Design D6), whatever was open before.
    LaunchedEffect(showOpenedFile) {
        if (showOpenedFile) {
            nowPlayingOpen = true
            queueOpen = false
            searching = false
            openPlaylist = null
            openGroup = null
            settingsOpen = false
            container.openedFiles.playerShown()
        }
    }
    // A splitting job that was started before the app was closed and still runs gets its banner back; a tap on the job's notification opens it.
    LaunchedEffect(librarySongs) { if (librarySongs.isNotEmpty()) splitViewModel.restore(librarySongs) }
    val reopenRequested by container.splitReopen.collectAsStateWithLifecycle()
    LaunchedEffect(reopenRequested, librarySongs) {
        if (reopenRequested && librarySongs.isNotEmpty()) {
            container.takeSplitReopen()
            splitViewModel.reopenFromNotification(librarySongs)
        }
    }
    // Android deleted the songs: they leave every playlist, the list is refreshed, and the user is told how many went.
    LaunchedEffect(deletedKeys) {
        val keys = deletedKeys ?: return@LaunchedEffect
        deletedKeys = null
        container.log.event(LogCode.SONGS_DELETED, keys.size)
        playlistsViewModel.removeSongs(keys.mapTo(HashSet()) { SongKey(it) })
        viewModel.requestSync()
        tellDetails(context.resources.getQuantityString(R.plurals.songs_deleted, keys.size, NumberFormat.getInstance().format(keys.size)))
    }
    val deleteFailedText = stringResource(R.string.delete_failed)
    // Export and import of the playlists through the system file picker (R-26): no permission; the file is the user's to keep.
    val resources = LocalResources.current
    fun transferText(result: TransferResult): String = when (result) {
        is TransferResult.Exported -> listOfNotNull(
            if (result.playlists > 0) resources.getQuantityString(R.plurals.playlists_exported, result.playlists, NumberFormat.getInstance().format(result.playlists)) else null,
            if (result.decisions > 0) resources.getString(R.string.export_with_details) else null,
        ).joinToString(" ")
        is TransferResult.Imported -> listOfNotNull(
            if (result.playlists > 0) resources.getQuantityString(R.plurals.playlists_imported, result.playlists, NumberFormat.getInstance().format(result.playlists)) else null,
            if (result.decisions > 0) resources.getQuantityString(R.plurals.details_record_restored, result.decisions, NumberFormat.getInstance().format(result.decisions)) else null,
            if (result.detailsFailed) resources.getString(R.string.details_record_failed) else null,
        ).joinToString(" ")
        TransferResult.NothingToExport -> resources.getString(R.string.export_nothing)
        TransferResult.NothingNew -> resources.getString(R.string.import_nothing_new)
        TransferResult.Refused -> resources.getString(R.string.import_refused)
        is TransferResult.Unavailable -> resources.getString(if (result.damaged) R.string.transfer_unavailable_damaged else R.string.transfer_unavailable)
        TransferResult.Failed -> resources.getString(R.string.transfer_failed)
        TransferResult.ExportFailed -> resources.getString(R.string.export_failed)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) detailsScope.launch {
            tellDetails(transferText(withContext(Dispatchers.IO) { container.playlistFiles.export(uri) }))
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) detailsScope.launch {
            val result = withContext(Dispatchers.IO) { container.playlistFiles.import(uri, System.currentTimeMillis() / 1000) }
            if (result is TransferResult.Imported && result.playlists > 0) playlistsViewModel.importedPlaylists(librarySongs)
            tellDetails(transferText(result))
        }
    }
    val busyText = stringResource(R.string.split_busy)
    LaunchedEffect(splitViewModel) { splitViewModel.busy.collect { tellDetails(busyText) } }
    LaunchedEffect(clipTarget) {
        if (clipFor != null && clipFor != clipTarget) {
            if (now?.isPlaying == true) playback.togglePlay()
            clipFor = null
        }
    }
    CompositionLocalProvider(
        LocalEditDetails provides { song: Song -> editingKey = song.key.value },
        LocalSplitSong provides { song: Song -> splitViewModel.begin(song) },
        LocalDeleteSongs provides { songs: List<Song> -> if (songs.isNotEmpty()) askDelete = ArrayList(songs.map { it.key.value }) },
    ) {
    Box(Modifier.fillMaxSize()) {
    val group = remember(ui.lists, openGroup) { if (granted) openGroup?.let { groupOf(ui.lists, it) } else null }
    // The album or artist is gone (a sync, or an edit changed its key): the screen closes instead of waiting for it to come back.
    LaunchedEffect(ui.loaded, group) { if (ui.loaded && openGroup != null && group == null) openGroup = null }
    val editingSong = editingKey?.let { key -> ui.lists.songs.firstOrNull { it.song.key.value == key }?.song }
    // The song left the library while it was being edited (a sync removed it): the editor closes instead of waiting for it to come back.
    LaunchedEffect(ui.loaded, editingSong) { if (ui.loaded && editingKey != null && editingSong == null) editingKey = null }
    when {
        editingSong != null -> EditDetailsScreen(
            song = editingSong,
            onSave = { edited ->
                editingKey = null
                detailsViewModel.save(editingSong, edited) { result ->
                    when (result) {
                        null -> tellDetails(saveFailedText)
                        SaveResult.Unchanged -> Unit
                        is SaveResult.Saved -> tellDetails(savedText, undoLabel) { undoBatch(result.batch) }
                        is SaveResult.InAppOnly -> inAppOnly(result.reason)
                    }
                }
            },
            onClose = { editingKey = null },
            bottomBar = miniPlayer,
        )
        splitOpen && splitUi is SplitUi.Finding -> FindingScreen(
            song = (splitUi as SplitUi.Finding).song,
            permille = (splitUi as SplitUi.Finding).permille,
            onCancel = splitViewModel::cancelFinding,
            onBackground = splitViewModel::minimize,
            bottomBar = miniPlayer,
        )
        splitOpen && splitUi is SplitUi.Found -> {
            val shown = splitUi as SplitUi.Found
            SongsFoundScreen(
                song = shown.song,
                found = shown.found,
                addCutFailed = shown.addCutFailed,
                onAddCut = splitViewModel::addCut,
                onClearAddCutError = splitViewModel::clearAddCutError,
                onLeave = splitViewModel::leave,
                onOpenCut = splitViewModel::openCut,
                onSplitAsFound = splitViewModel::openReady,
                onPrimary = when (shown.found.primary) {
                    is FoundPrimary.Check -> ({ splitViewModel.openCut() })
                    is FoundPrimary.Split -> splitViewModel::openReady
                    FoundPrimary.AddACut -> null
                },
                bottomBar = miniPlayer,
            )
        }
        splitOpen && splitUi is SplitUi.Ready -> {
            val ready = splitUi as SplitUi.Ready
            ReadyScreen(ready.summary, onStart = splitViewModel::startWriting, onCheckThem = splitViewModel::checkUnchecked, onBack = splitViewModel::backFromReady, bottomBar = miniPlayer)
        }
        splitOpen && splitUi is SplitUi.Writing -> {
            val writing = splitUi as SplitUi.Writing
            WritingScreen(writing.written, writing.total, onCancel = splitViewModel::cancelWriting, onBackground = splitViewModel::minimize, bottomBar = miniPlayer)
        }
        splitOpen && splitUi is SplitUi.Finished -> {
            val finished = splitUi as SplitUi.Finished
            FinishedScreen(
                outcome = finished.outcome,
                sourceName = finished.song.fileName,
                folderParts = finished.record?.folder?.trim('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty(),
                originalDeleted = finished.originalDeleted,
                songsRemoved = finished.songsRemoved,
                removable = finished.removable,
                // The new songs appear in the Library, newest first; the lookup names them like any song without a title (R-16).
                onShowSongs = {
                    splitViewModel.closeFinished()
                    searching = false
                    openGroup = null
                    viewModel.selectTab(LibraryTab.Songs)
                    viewModel.selectSort(SortOrder.RecentlyAdded)
                    viewModel.requestSync()
                },
                onDeleteOriginal = { deleting = DELETE_ORIGINAL; splitViewModel.deleteOriginal { sender -> deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()) } },
                onRemoveSongs = { deleting = DELETE_SONGS; splitViewModel.removeSongs { sender -> deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()) } },
                onClose = splitViewModel::closeFinished,
                bottomBar = miniPlayer,
            )
        }
        splitOpen && splitUi is SplitUi.Checking -> {
            val checking = splitUi as SplitUi.Checking
            CutReviewScreen(
                record = checking.record,
                durationSec = durationFor(checking.record, checking.song.durationMs / 1000.0),
                index = checking.index,
                playing = clipFor == clipTarget && now?.mediaId == checking.song.key.value && now?.isPlaying == true,
                onPlay = { clip -> clipFor = clipTarget; playback.playClip(checking.song, clip.first, clip.last) },
                onPause = playback::togglePlay,
                onMoveBy = splitViewModel::moveBy,
                onMoveTo = splitViewModel::moveTo,
                onUse = splitViewModel::useCut,
                onRemove = splitViewModel::removeCut,
                onNext = splitViewModel::nextCut,
                onBack = splitViewModel::backToList,
                bottomBar = miniPlayer,
            )
        }
        granted && reviewOpen -> {
            val reviewUi by reviewViewModel.ui.collectAsStateWithLifecycle()
            val reviewing = (reviewUi as? ReviewUi.Reviewing)?.item?.song
            ReviewScreen(
                ui = reviewUi,
                playing = reviewing != null && now?.mediaId == reviewing.key.value && now?.isPlaying == true,
                onPlay = { song -> startPlaying(PlayingFrom.Songs, listOf(song), 0) },
                onTogglePlay = playback::togglePlay,
                onSelect = reviewViewModel::select,
                onUse = reviewViewModel::use,
                onNone = reviewViewModel::reject,
                onSkip = reviewViewModel::skip,
                onClose = { reviewOpen = false },
                onRetry = reviewViewModel::open,
                bottomBar = miniPlayer,
            )
        }
        granted && notFoundOpen -> NotFoundScreen(
            songs = detailsViewModel.notFound.collectAsStateWithLifecycle().value,
            onEdit = { song -> editingKey = song.key.value },
            onClose = { notFoundOpen = false },
            bottomBar = miniPlayer,
        )
        granted && songDetailsOpen -> SongDetailsScreen(
            // The state is read (every few seconds) only while this screen is open.
            ui = detailsViewModel.overview.collectAsStateWithLifecycle().value,
            rows = recentDetails,
            onEnabled = detailsViewModel::setEnabled,
            onWifiOnly = detailsViewModel::setWifiOnly,
            onReplace = detailsViewModel::setReplace,
            onOnlyNew = detailsViewModel::setOnlyNew,
            onAllowFiles = { allowFileChanges() },
            // Songs whose write started and was undone are retried too; the grant is asked for whatever waits for access.
            onWriteNow = { detailsViewModel.flush(includeFailed = true) { askForWriteAccess(null) } },
            onReview = { reviewViewModel.open(); reviewOpen = true },
            onNotFound = { notFoundOpen = true },
            onUndo = { undoBatch(it.batch) },
            onClose = { songDetailsOpen = false },
            bottomBar = miniPlayer,
        )
        playerNeeded && queueOpen -> {
            val rows = remember(queueVersion) { playback.queueRows() }
            QueueScreen(
                rows = rows,
                onClose = { queueOpen = false },
                onJump = { playback.jumpTo(it.index, it.mediaId) },
                onRemove = { playback.removeFromQueue(it.index, it.mediaId) },
                failedKeys = failedKeys,
            )
        }
        playerNeeded && nowPlayingOpen && now != null -> NowPlayingScreen(
            now = requireNotNull(now),
            positionMs = playback::positionMs,
            onClose = { nowPlayingOpen = false },
            onToggle = playback::togglePlay,
            onPrevious = playback::previous,
            onNext = playback::next,
            onSeek = playback::seekTo,
            onShuffle = playback::toggleShuffle,
            onRepeat = playback::cycleRepeat,
            onQueue = { queueOpen = true },
            sleep = sleep,
            sleepChoices = playback.sleepChoices,
            onSleepStart = playback::startSleepTimer,
            onSleepEndOfSong = playback::startSleepEndOfSong,
            onSleepCancel = playback::cancelSleepTimer,
        )
        group != null -> {
            GroupScreen(
                title = group.title,
                subtitle = group.subtitle,
                songs = group.songs,
                onClose = { openGroup = null },
                onPlay = { queue, i -> startPlaying(PlayingFrom.Songs, queue, i) },
                onShuffle = { queue, i -> startPlaying(PlayingFrom.Songs, queue, i, shuffle = true) },
                playingKey = now?.mediaId,
                failedKeys = failedKeys,
                onPlayNext = playback::playNext,
                onAddToQueue = playback::addToQueue,
                playlists = playlistActions,
                bottomBar = miniPlayer,
            )
        }
        granted && searching -> holder.SaveableStateProvider("search") {
            val index by viewModel.searchIndex.collectAsStateWithLifecycle()
            SearchScreen(
                index = index,
                onClose = {
                    searching = false
                    holder.removeState("search")
                },
                playingKey = now?.mediaId,
                onPlay = { songs, i -> startPlaying(PlayingFrom.Search, songs, i) },
                onPlayNext = playback::playNext,
                onAddToQueue = playback::addToQueue,
                failedKeys = failedKeys,
                playlists = playlistActions,
                onOpenAlbum = { openGroup = "album:$it" },
                onOpenArtist = { openGroup = "artist:$it" },
                bottomBar = miniPlayer,
            )
        }
        granted && settingsOpen -> SettingsScreen(
            versionName = remember { container.diagnostics.versionName() },
            diagnosticsText = container.diagnostics::text,
            onClose = { settingsOpen = false },
            bottomBar = miniPlayer,
            onSongDetails = { songDetailsOpen = true },
            songDetailsOn = detailsEnabled,
            onExportPlaylists = { exportPicker.launch(resources.getString(R.string.export_default_name)) },
            onImportPlaylists = { importPicker.launch(arrayOf("application/json")) },
        )
        granted && openPlaylist != null -> {
            val id = requireNotNull(openPlaylist)
            PlaylistScreen(
                state = playlistDetail,
                onClose = { openPlaylist = null },
                onPlay = { queue, i -> startPlaying(PlayingFrom.Playlist, queue, i) },
                onShuffle = { queue, i -> startPlaying(PlayingFrom.Playlist, queue, i, shuffle = true) },
                onPlayNext = playback::playNext,
                onAddToQueue = playback::addToQueue,
                onRename = { name, done -> playlistsViewModel.rename(id, name, done) },
                onDelete = { done -> playlistsViewModel.delete(id, done) },
                onRemove = { entryId, done -> playlistsViewModel.remove(id, entryId, done) },
                onUndo = { removed, done -> playlistsViewModel.undoRemove(id, removed, done) },
                onMove = { entryId, toIndex, done -> playlistsViewModel.move(id, entryId, toIndex, done) },
                onRetry = playlistsViewModel::reload,
                playingKey = now?.mediaId,
                failedKeys = failedKeys,
                bottomBar = miniPlayer,
            )
        }
        granted -> holder.SaveableStateProvider("library") {
            LibraryScreen(
                ui = ui,
                onTab = viewModel::selectTab,
                onSort = viewModel::selectSort,
                onReverse = viewModel::reverseSort,
                onRefresh = viewModel::requestSync,
                onSearch = { searching = true },
                onDismissFailure = viewModel::dismissFailure,
                takeNewSongs = viewModel::takeNewSongs,
                playingKey = now?.mediaId,
                onPlay = { songs, i -> startPlaying(PlayingFrom.Songs, songs, i) },
                onPlayNext = playback::playNext,
                onAddToQueue = playback::addToQueue,
                failedKeys = failedKeys,
                playlists = playlistActions,
                onSettings = { settingsOpen = true },
                onOpenAlbum = { openGroup = "album:$it" },
                onOpenArtist = { openGroup = "artist:$it" },
                splitBanner = splitBanner?.let { SplitBanner(it, onOpen = splitViewModel::reopen, onDismiss = splitViewModel::dismissBanner) },
                reviewBanner = if (reviewCount > 0) ReviewBanner(reviewCount, onReview = { reviewViewModel.open(); reviewOpen = true }, onDismiss = detailsViewModel::dismissBanner) else null,
                bottomBar = miniPlayer,
            )
        }
        asked == null -> Unit
        asked == false -> FirstStartScreen(onAllow = { ask.launch(PERMISSION) })
        else -> AccessRefusedScreen(
            // After two refusals Android stops showing the dialog; the only way left is the app's page in Settings.
            onTryAgain = {
                if (activity?.shouldShowRequestPermissionRationale(PERMISSION) == true) {
                    ask.launch(PERMISSION)
                } else {
                    openAppSettings(context)
                }
            },
            onOpenSettings = { openAppSettings(context) },
        )
    }
    SplitDialogs(splitUi, onDismiss = splitViewModel::leave, onUseFolder = splitViewModel::useSuggestedFolder, onContinue = splitViewModel::continueEarlier, onStartOver = splitViewModel::startOver)
    SnackbarHost(detailsHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (now != null) 72.dp else 8.dp))
    }
    }

    askDelete?.let { keys ->
        val chosen = ui.lists.songs.map { it.song }.filter { it.key.value in keys }
        if (chosen.isEmpty()) {
            askDelete = null
        } else {
            val count = NumberFormat.getInstance().format(chosen.size)
            AlertDialog(
                onDismissRequest = { askDelete = null },
                title = { Text(stringResource(R.string.delete_songs_title)) },
                text = {
                    Text(
                        if (chosen.size == 1) stringResource(R.string.delete_song_body, chosen[0].displayTitle)
                        else stringResource(R.string.delete_songs_body, count),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askDelete = null
                        deletingKeys = ArrayList(chosen.map { it.key.value })
                        deleting = DELETE_LIBRARY
                        detailsScope.launch {
                            try {
                                val request = withContext(Dispatchers.IO) { container.songDeleter.request(chosen, userSaidYes = true) }
                                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                deleting = null
                                deletingKeys = null
                                container.log.event(LogCode.SONGS_DELETE_FAILED, chosen.size)
                                tellDetails(deleteFailedText)
                            }
                        }
                    }) { Text(stringResource(R.string.delete_confirm)) }
                },
                dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
    // A file that is not an MP3 or cannot be read: one short message, and the music goes on as before (UX Design D7).
    if (openedRefused) {
        AlertDialog(
            onDismissRequest = container.openedFiles::dismissRefused,
            title = { Text(stringResource(R.string.open_file_error_title)) },
            text = { Text(stringResource(R.string.open_file_error_body)) },
            confirmButton = { TextButton(onClick = container.openedFiles::dismissRefused) { Text(stringResource(R.string.dialog_ok)) } },
        )
    }
}

private const val DELETE_ORIGINAL = "original"
private const val DELETE_SONGS = "songs"
private const val DELETE_LIBRARY = "library"

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(intent)
}

/** An album or an artist ready to show: its title, its subtitle, and its songs in title order (UX Design 4.4, C1). */
private class OpenGroup(val title: String, val subtitle: String, val songs: List<Song>)

private fun groupOf(lists: LibraryLists, id: String): OpenGroup? {
    fun ordered(songs: List<IndexedSong>) =
        songs.sortedWith(compareBy({ it.keys.sortTitle }, { it.song.key.value })).map { it.song }
    return when {
        id.startsWith("album:") -> lists.albums.firstOrNull { it.key == id.removePrefix("album:") }?.let { OpenGroup(it.displayName, it.artistLine, ordered(it.songs)) }
        id.startsWith("artist:") -> lists.artists.firstOrNull { it.key == id.removePrefix("artist:") }?.let { OpenGroup(it.displayName, "", ordered(it.songs)) }
        else -> null
    }
}
