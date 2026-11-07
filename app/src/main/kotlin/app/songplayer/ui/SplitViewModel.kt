package app.songplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.songplayer.AppContainer
import app.songplayer.core.errors.SplitError
import app.songplayer.core.library.Song
import app.songplayer.core.log.LogCode
import app.songplayer.core.split.CutReview
import app.songplayer.core.split.FolderRule
import app.songplayer.core.split.durationFor
import app.songplayer.core.split.Recovery
import app.songplayer.core.split.RunRecord
import app.songplayer.core.split.SongsFound
import app.songplayer.core.split.SplitEdit
import app.songplayer.core.split.SplitEntry
import app.songplayer.core.split.ActiveJob
import app.songplayer.core.split.SongNames
import app.songplayer.core.split.SplitResume
import app.songplayer.core.split.SplitLimits
import app.songplayer.core.split.SplitOutcome
import app.songplayer.core.split.SplitSummary
import app.songplayer.core.split.StopReason
import app.songplayer.core.split.DeleteTargets
import app.songplayer.split.SongWriter
import android.content.IntentSender
import app.songplayer.core.split.SplitRecovery
import app.songplayer.core.split.SplitTime
import app.songplayer.split.SourceInfo
import app.songplayer.split.SplitWork
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The one plain message a failed check or a failed analysis shows (UX Design 3); the screen turns each into its title and body. */
sealed interface SplitMessage {
    data object Unreadable : SplitMessage
    data object TooLarge : SplitMessage
    data class TooManySongs(val found: Int) : SplitMessage
    data object Gone : SplitMessage
    data class NameInUse(val name: String) : SplitMessage
}

/** Where the "Split into songs" flow is (UX Design 4.11): nothing, a question or a message over the screen, K1 or K2. */
sealed interface SplitUi {
    data object Idle : SplitUi
    data class Message(val message: SplitMessage) : SplitUi
    data class AskFolder(val song: Song, val suggested: String) : SplitUi
    data class AskContinue(val song: Song, val written: Int, val total: Int) : SplitUi
    data class Finding(val song: Song, val permille: Int) : SplitUi
    data class Found(val song: Song, val record: RunRecord, val found: SongsFound, val addCutFailed: Boolean = false) : SplitUi

    /** K4: everything is decided; nothing is written until the button is tapped (R-40). */
    data class Ready(val song: Song, val record: RunRecord, val summary: SplitSummary) : SplitUi

    /** K5: the songs are being written; [written] of [total] are done. */
    data class Writing(val song: Song, val record: RunRecord, val written: Int, val total: Int) : SplitUi

    /** K6 (or the stopped message): how the run ended, and what has been done about the original and the songs since. */
    data class Finished(
        val song: Song,
        val record: RunRecord?,
        val outcome: SplitOutcome,
        /** How many of the run's songs still exist: what "Remove these songs" would delete. */
        val removable: Int = outcome.written,
        val originalDeleted: Boolean = false,
        val songsRemoved: Boolean = false,
    ) : SplitUi

    /** K3: the cut at place [index] of the record is being checked. */
    data class Checking(val song: Song, val record: RunRecord, val index: Int) : SplitUi
}

/** What the Library's banner says about a splitting job that is not on screen (UX Design 4.11): working, found, written or stopped. */
sealed interface SplitBannerUi {
    data class Finding(val permille: Int) : SplitBannerUi
    data class Writing(val written: Int, val total: Int) : SplitBannerUi
    data class Found(val songs: Int) : SplitBannerUi
    data class Done(val songs: Int) : SplitBannerUi
    data class Stopped(val kept: Int) : SplitBannerUi
}

/**
 * The screens' side of the splitter up to "The songs found" (Stories X7): the checks of the menu action, the question about an earlier run
 * and about the folder, the watching of the analysis job, and adding a cut by hand. Everything that reads files or the media index runs off
 * the main thread. The job itself is [app.songplayer.split.SplitWorker]; this class never touches an audio file.
 */
class SplitViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<SplitUi>(SplitUi.Idle)
    val ui: StateFlow<SplitUi> = _ui

    private val _open = MutableStateFlow(false)

    /** True while the user is looking at the splitting screens; false when the job goes on behind the Library (then the banner shows). */
    val open: StateFlow<Boolean> = _open

    /** The banner for a job that is not on screen: working, found, written or stopped; null when nothing is going on or the screens are open. */
    val banner: StateFlow<SplitBannerUi?> = combine(_ui, _open) { ui, open -> if (open) null else bannerOf(ui) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun bannerOf(ui: SplitUi): SplitBannerUi? = when (ui) {
        is SplitUi.Finding -> SplitBannerUi.Finding(ui.permille)
        is SplitUi.Writing -> SplitBannerUi.Writing(ui.written, ui.total)
        is SplitUi.Found -> SplitBannerUi.Found(ui.found.songCount)
        is SplitUi.Finished -> if (ui.outcome.finished) SplitBannerUi.Done(ui.outcome.written) else SplitBannerUi.Stopped(ui.outcome.written)
        else -> null
    }

    private val context get() = container.appContext
    private val store get() = container.splitRuns
    private var watching: Job? = null
    private var starting = false
    private val cutTurn = Mutex()
    private var deleteBusy = false

    /** A failure of a screen's step: logged by code (no names), shown as the one plain message; a cancelled coroutine is not a failure. */
    private fun failed(e: Exception) {
        if (e is CancellationException) throw e
        container.log.event(LogCode.SPLIT_WRITE_FAILED, 2)
        _ui.value = SplitUi.Message(SplitMessage.Unreadable)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val SETTLE_TRIES = 6
    }

    private fun volumeOf(song: Song) = song.key.value.substringBefore(':')

    /** The menu action: the checks that need no reading, then the file's identity and any earlier run, then the analysis. */
    fun begin(song: Song) {
        // A job that is going on behind the Library (or a result waiting there): show it instead of starting another.
        if (_ui.value != SplitUi.Idle) {
            // The job on is shown; if it is about another song, the user is told why nothing new started.
            val current = when (val ui = _ui.value) {
                is SplitUi.Finding -> ui.song; is SplitUi.Writing -> ui.song; is SplitUi.Found -> ui.song; is SplitUi.Ready -> ui.song
                is SplitUi.Checking -> ui.song; is SplitUi.Finished -> ui.song; is SplitUi.AskFolder -> ui.song; is SplitUi.AskContinue -> ui.song
                else -> null
            }
            if (current != null && current.mediaStoreId != song.mediaStoreId) busy.tryEmit(Unit)
            reopen()
            return
        }
        if (starting) return
        _open.value = true
        starting = true
        SplitEntry.refusal(song)?.let { _ui.value = SplitUi.Message(SplitMessage.TooLarge); starting = false; return }
        viewModelScope.launch {
            try {
                val volume = volumeOf(song)
                val step = withContext(Dispatchers.IO) {
                    val info = SourceInfo.read(context, song.mediaStoreId, volume) ?: return@withContext Step.Gone
                    val identity = SourceInfo.identity(context, song.mediaStoreId, volume, info)
                    val earlier = store.latestFor(song.mediaStoreId)
                    val existing = earlier?.let { SourceInfo.existingSizes(context, volume, it.written.map { s -> s.mediaId }) }.orEmpty()
                    when (val r = SplitRecovery.decide(earlier, identity, existing)) {
                        is Recovery.Continue -> if (r.written > 0) Step.Ask(earlier!!, r) else Step.Show(earlier!!)
                        else -> Step.Fresh(info)
                    }
                }
                when (step) {
                    Step.Gone -> _ui.value = SplitUi.Message(SplitMessage.Gone)
                    is Step.Show -> _ui.value = found(song, step.record)
                    is Step.Ask -> _ui.value = SplitUi.AskContinue(song, step.how.written, step.how.total).also { pending = step.record }
                    is Step.Fresh -> analyse(song, step.info, useFallback = false)
                }
            } catch (e: Exception) {
                failed(e)
            } finally {
                starting = false
            }
        }
    }

    private sealed interface Step {
        data object Gone : Step
        data class Fresh(val info: SourceInfo) : Step
        data class Show(val record: RunRecord) : Step
        data class Ask(val record: RunRecord, val how: Recovery.Continue) : Step
    }

    private var pending: RunRecord? = null

    /** "Continue" on the question about an earlier run: its cuts are shown again; nothing is analysed twice. */
    fun continueEarlier() {
        val ask = _ui.value as? SplitUi.AskContinue ?: return
        val record = pending ?: return
        toReady(ask.song, record)
    }

    /** "Start over": the earlier record is removed and the file is analysed again. */
    fun startOver() {
        val ask = _ui.value as? SplitUi.AskContinue ?: return
        val record = pending
        pending = null
        _ui.value = SplitUi.Finding(ask.song, 0)               // at once, so a second tap finds no question to answer
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    record?.let { store.delete(it.id) }
                    SourceInfo.read(context, ask.song.mediaStoreId, volumeOf(ask.song))
                }
                if (info == null) _ui.value = SplitUi.Message(SplitMessage.Gone) else analyse(ask.song, info, useFallback = false)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** "Use Music folder" on the question about the folder. */
    fun useSuggestedFolder() {
        val ask = _ui.value as? SplitUi.AskFolder ?: return
        _ui.value = SplitUi.Finding(ask.song, 0)               // at once, so a second tap finds no question to answer
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { SourceInfo.read(context, ask.song.mediaStoreId, volumeOf(ask.song)) }
                if (info == null) _ui.value = SplitUi.Message(SplitMessage.Gone) else analyse(ask.song, info, useFallback = true)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private suspend fun analyse(song: Song, info: SourceInfo, useFallback: Boolean) {
        val folder = try {
            FolderRule.choose(info.relativePath, info.name, useFallback)
        } catch (e: SplitError.FolderNotAllowed) {
            _ui.value = SplitUi.AskFolder(song, e.suggested)
            return
        }
        val runId = "run-${UUID.randomUUID()}"
        // If a job for this file already runs (after a restart of the app, say), this watches that one.
        val id = withContext(Dispatchers.IO) { SplitWork.enqueueAnalysis(context, song.mediaStoreId, volumeOf(song), runId, folder) }
        _ui.value = SplitUi.Finding(song, 0)
        watchAnalysis(song, id)
    }

    private fun watchAnalysis(song: Song, id: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { work ->
                val state = work?.state ?: return@collect
                try {
                    when (state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val record = withContext(Dispatchers.IO) { store.latestFor(song.mediaStoreId) }
                            _ui.value = when {
                                record == null -> SplitUi.Message(SplitMessage.Unreadable)
                                record.songCount > SplitLimits.MAX_SONGS -> {
                                    withContext(Dispatchers.IO) { store.delete(record.id) }
                                    SplitUi.Message(SplitMessage.TooManySongs(record.songCount))
                                }
                                else -> found(song, record)
                            }
                        }
                        WorkInfo.State.FAILED -> _ui.value = SplitUi.Message(
                            when (work.outputData.getString(SplitWork.OUT_ERROR)) {
                                SplitError.TooLarge().code -> SplitMessage.TooLarge
                                SplitError.SourceChanged().code -> SplitMessage.Gone
                                else -> SplitMessage.Unreadable
                            },
                        )
                        WorkInfo.State.CANCELLED -> leave()
                        WorkInfo.State.RUNNING -> _ui.value = SplitUi.Finding(song, work.progress.getInt(SplitWork.PROGRESS_PERMILLE, 0))
                        else -> Unit
                    }
                } catch (e: Exception) {
                    failed(e)
                }
            }
        }
    }

    private fun found(song: Song, record: RunRecord) = SplitUi.Found(song, record, SongsFound.of(record, durationFor(record, song.durationMs / 1000.0)))

    /** Cancel on K1: the job stops, nothing was written. */
    fun cancelFinding() {
        val finding = _ui.value as? SplitUi.Finding ?: return
        watching?.cancel()
        SplitWork.cancel(context, finding.song.mediaStoreId)
        _open.value = false
        _ui.value = SplitUi.Idle
    }

    /** Back on K1 or K5: the job goes on behind the Library and a banner there brings the user back. Cancel is the button that stops it. */
    fun minimize() {
        if (_ui.value is SplitUi.Finding || _ui.value is SplitUi.Writing) _open.value = false
    }

    /** The banner, a second tap on the menu action, or the notification: look at the job again. */
    fun reopen() {
        if (_ui.value != SplitUi.Idle) _open.value = true
    }

    /**
     * The close button of the "songs found" banner: leaves the flow; the saved run stays for "Continue". A result of the writing has no close
     * button: its banner opens K6, which holds the questions about the original and the songs, and "OK" there closes it.
     */
    fun dismissBanner() {
        if (_ui.value is SplitUi.Found) leave()
    }

    /** Said once to the root, which shows it as a snackbar: the user asked for a split while another one is on. */
    val busy = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val attachTurn = Mutex()
    private var restored = false

    /**
     * At the start of the app: a job that was started before the app was closed and is still running gets its screen state back. Found again by
     * the tags on its work; a running one before a queued one. If its song is not in the library yet, this is tried again when the library
     * changes. Results that came while the app was closed are reached by the notification ([reopenFromNotification]) or the menu action.
     */
    fun restore(songs: List<Song>) {
        if (restored) return
        viewModelScope.launch {
            try {
                attachTurn.withLock {
                    if (restored || _ui.value != SplitUi.Idle) return@withLock
                    val found = attachActive(songs)
                    // Nothing queued or running at all: nothing to wait for. A job whose song is missing is looked for again with the next library.
                    if (found || !anyActive()) restored = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e      // a failed look for a job is no reason to bother the user
                container.log.event(LogCode.SPLIT_WRITE_FAILED, 3)
            }
        }
    }

    private suspend fun activeJobs(): List<WorkInfo> = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        (manager.getWorkInfosByTag(SplitWork.TAG_ANALYZE).get() + manager.getWorkInfosByTag(SplitWork.TAG_WRITE).get()).filter { !it.state.isFinished }
    }

    private suspend fun anyActive() = activeJobs().isNotEmpty()

    /** Shows the job that is queued or running, if there is one whose song is in [songs]; true when it did. */
    private suspend fun attachActive(songs: List<Song>): Boolean {
        val infos = activeJobs()
        val byId = infos.associateBy { it.id }
        val pick = SplitResume.pickActive(
            infos.mapNotNull { w ->
                SplitWork.mediaIdOf(w.tags)?.let { ActiveJob(w.id, it, SplitWork.TAG_ANALYZE in w.tags, w.state == WorkInfo.State.RUNNING) }
            },
        ) { id -> songs.any { it.mediaStoreId == id } } ?: return false
        val work = byId.getValue(pick.id)
        val song = songs.first { it.mediaStoreId == pick.mediaId }
        if (_ui.value != SplitUi.Idle) return true
        if (pick.analysis) {
            _ui.value = SplitUi.Finding(song, work.progress.getInt(SplitWork.PROGRESS_PERMILLE, 0))
            watchAnalysis(song, work.id)
        } else {
            // The record of this file's writing run; the newest one for the file is it while its job is on.
            val record = withContext(Dispatchers.IO) { store.latestFor(pick.mediaId) } ?: return false
            val earlier = withContext(Dispatchers.IO) { writtenAndStillThere(record) }
            _ui.value = SplitUi.Writing(song, record, work.progress.getInt(SplitWork.PROGRESS_WRITTEN, earlier), record.songCount)
            watchWriting(song, record, earlier, work.id)
        }
        return true
    }

    /**
     * The user tapped the notification. A job that is still on screen or behind the Library is shown first; a job that is queued or running
     * (the app was closed meanwhile) comes before any saved run; only when there is none, the newest saved run of the last day whose song is in
     * the library opens: the songs found, or the result of the writing.
     */
    fun reopenFromNotification(songs: List<Song>) {
        if (_ui.value != SplitUi.Idle) { reopen(); return }
        viewModelScope.launch {
            try {
                attachTurn.withLock {
                    if (_ui.value != SplitUi.Idle) { reopen(); return@withLock }
                    if (attachActive(songs)) { restored = true; _open.value = true; return@withLock }
                    val now = System.currentTimeMillis() / 1000
                    val record = withContext(Dispatchers.IO) { SplitResume.pickRecent(store.all(), now) { id -> songs.any { it.mediaStoreId == id } } } ?: return@withLock
                    val song = songs.first { it.mediaStoreId == record.source.mediaId }
                    val state = when (record.phase) {
                        app.songplayer.core.split.RunPhase.Reviewing -> found(song, record)
                        app.songplayer.core.split.RunPhase.Writing -> finishedState(song, record, 0, StopReason.Cancelled)
                        app.songplayer.core.split.RunPhase.Done -> finishedState(song, record, 0, null)
                    }
                    if (_ui.value == SplitUi.Idle) { _ui.value = state; _open.value = true }
                }
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** "Add a cut" on K2 with the time the user typed; the screen shows the error when [text] is not a time or the cut breaks the 5 s rule. */
    fun addCut(text: String) {
        viewModelScope.launch {
            // One cut at a time, each added to the record as it is now: two quick confirms never start from the same record.
            cutTurn.withLock {
                val current = _ui.value as? SplitUi.Found ?: return@withLock
                val time = SplitTime.parse(text)
                val edited = time?.let { SplitEdit.addCut(current.record, durationFor(current.record, current.song.durationMs / 1000.0), it) }
                if (edited == null) {
                    _ui.value = current.copy(addCutFailed = true)
                    return@withLock
                }
                try {
                    withContext(Dispatchers.IO) { store.save(edited) }
                    // The user may have left while the record was saved: the record stays, the screen does not come back.
                    if (_ui.value is SplitUi.Found) _ui.value = found(current.song, edited)
                } catch (e: Exception) {
                    failed(e)
                }
            }
        }
    }

    /** K2's primary button with uncertain cuts, or a tap on a row: the cut at place [index] (null: the first uncertain one) is shown on K3. */
    fun openCut(index: Int? = null) {
        val found = _ui.value as? SplitUi.Found ?: return
        val place = index ?: CutReview.uncertain(found.record).firstOrNull() ?: return
        if (place in found.record.cuts.indices) _ui.value = SplitUi.Checking(found.song, found.record, place)
    }

    private fun duration(song: Song, record: RunRecord) = durationFor(record, song.durationMs / 1000.0)

    /**
     * One action of K3 on the record as it is now. [change] gives the changed record, or null when the rule refuses it, and where to go next:
     * a place of K3 or null for the list. Each change is saved before the screen moves on, one at a time.
     */
    private fun edit(change: (SplitUi.Checking) -> Pair<RunRecord, Int?>?) {
        viewModelScope.launch {
            cutTurn.withLock {
                val current = _ui.value as? SplitUi.Checking ?: return@withLock
                val (record, next) = change(current) ?: return@withLock
                try {
                    if (record != current.record) withContext(Dispatchers.IO) { store.save(record) }
                    val now = _ui.value
                    if (now is SplitUi.Checking) _ui.value = if (next == null) found(current.song, record) else SplitUi.Checking(current.song, record, next)
                } catch (e: Exception) {
                    failed(e)
                }
            }
        }
    }

    /** Earlier (negative) or Later (positive) by [deltaSec]; stays on the same cut. */
    fun moveBy(deltaSec: Double) = edit { c -> CutReview.nudge(c.record, duration(c.song, c.record), c.index, deltaSec)?.let { it to c.index } }

    /** The time line was dragged to [timeSec]. */
    fun moveTo(timeSec: Double) = edit { c -> CutReview.moveTo(c.record, duration(c.song, c.record), c.index, timeSec)?.let { it to c.index } }

    /** "Use this cut": kept, counted as looked at; on to the next uncertain cut or back to the list. */
    fun useCut() = edit { c ->
        val record = CutReview.use(c.record, duration(c.song, c.record), c.index)
        record to CutReview.nextUncertain(record, c.index)
    }

    /** "Remove this cut": the two songs join; on to the next uncertain cut or back to the list. */
    fun removeCut() = edit { c ->
        val record = CutReview.remove(c.record, duration(c.song, c.record), c.index)
        record to CutReview.nextUncertain(record, c.index - 1)
    }

    /** "Next": the cut stays as found; on to the next uncertain cut or back to the list. */
    fun nextCut() = edit { c -> c.record to CutReview.nextUncertain(c.record, c.index) }

    /** K2's primary button when every cut looks right (or K3's way on): the summary of what will be written. */
    fun openReady() {
        val f = _ui.value as? SplitUi.Found ?: return
        toReady(f.song, f.record)
    }

    private fun toReady(song: Song, record: RunRecord) {
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val free = writerFor(song, record).freeBytes()
                    SplitSummary.of(record, song.sizeBytes, free)
                }
                _ui.value = SplitUi.Ready(song, record, summary)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun writerFor(song: Song, record: RunRecord) = SongWriter(context, record.folder, record.volume, FolderRule.fallback(song.fileName), container.log)

    /** "Check them" on K4. */
    fun checkUnchecked() {
        val r = _ui.value as? SplitUi.Ready ?: return
        _ui.value = found(r.song, r.record)
        openCut()
    }

    /** Back from K4 to the list. */
    fun backFromReady() {
        val r = _ui.value as? SplitUi.Ready ?: return
        _ui.value = found(r.song, r.record)
    }

    /** "Split into N songs": the first moment anything is written (R-40). The songs already written by an earlier run are kept and skipped. */
    fun startWriting() {
        val r = _ui.value as? SplitUi.Ready ?: return
        if (!r.summary.canStart) return
        _ui.value = SplitUi.Writing(r.song, r.record, r.record.written.size, r.record.songCount)      // at once, so a second tap finds no button
        viewModelScope.launch {
            try {
                val earlier = withContext(Dispatchers.IO) { writtenAndStillThere(r.record) }
                _ui.value = SplitUi.Writing(r.song, r.record, earlier, r.record.songCount)
                val id = withContext(Dispatchers.IO) { SplitWork.enqueueWriting(context, r.song.mediaStoreId, volumeOf(r.song), r.record.id) }
                watchWriting(r.song, r.record, earlier, id)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Songs an earlier run wrote and the user deleted since are written again, so they do not count as earlier. */
    private fun writtenAndStillThere(record: RunRecord): Int {
        val existing = SourceInfo.existingSizes(context, record.volume, record.written.map { it.mediaId })
        return record.written.count { existing[it.mediaId] == it.size }
    }

    private fun watchWriting(song: Song, record: RunRecord, earlier: Int, id: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { work ->
                val state = work?.state ?: return@collect
                try {
                    when (state) {
                        WorkInfo.State.RUNNING -> {
                            val total = work.progress.getInt(SplitWork.PROGRESS_SONGS, record.songCount)
                            _ui.value = SplitUi.Writing(song, record, work.progress.getInt(SplitWork.PROGRESS_WRITTEN, earlier), total)
                        }
                        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ended(song, record, earlier, state, work.outputData)
                        else -> Unit
                    }
                } catch (e: Exception) {
                    failed(e)
                }
            }
        }
    }

    /** The job ended: read the record back (the job saves it after every song) and show K6, the stopped message, or the clash. */
    private suspend fun ended(song: Song, run: RunRecord, earlier: Int, state: WorkInfo.State, output: androidx.work.Data) {
        // A cancelled job is marked so at once while its worker may still be removing the song it was on and saving the record: wait until the record stops changing.
        val record = withContext(Dispatchers.IO) { settledRecord(run.id, wait = state == WorkInfo.State.CANCELLED) }
        val code = output.getString(SplitWork.OUT_ERROR)
        val clash = output.getInt(SplitWork.OUT_CLASH, 0)
        if (code == SplitError.NameInUse("").code && clash > 0) {
            _ui.value = SplitUi.Message(SplitMessage.NameInUse(SongNames.names(run.sourceName, run.songCount)[clash - 1]))
            _open.value = true                                  // a clash needs the user's eyes whether or not the screen was open
            return
        }
        _ui.value = finishedState(song, record, earlier, SplitOutcome.reasonFor(state == WorkInfo.State.CANCELLED, state == WorkInfo.State.FAILED, code))
    }

    private suspend fun finishedState(song: Song, record: RunRecord?, earlier: Int, reason: StopReason?): SplitUi.Finished {
        val removable = withContext(Dispatchers.IO) {
            record?.let { SourceInfo.existingSizes(context, it.volume, it.written.map { s -> s.mediaId }).size } ?: 0
        }
        return SplitUi.Finished(song, record, SplitOutcome.of(record, earlier, reason), removable)
    }

    /** The record as saved once it has stopped changing (the job saves it after every song); without [wait] just the record. */
    private suspend fun settledRecord(id: String, wait: Boolean): RunRecord? {
        var last = store.load(id)
        if (!wait) return last
        repeat(SETTLE_TRIES) {
            kotlinx.coroutines.delay(SETTLE_MS)
            val now = store.load(id)
            if (now == last) return now
            last = now
        }
        return last
    }

    /** Cancel on K5: the job stops after the current chunk, removes the unfinished song and keeps the finished ones (R-44). */
    fun cancelWriting() {
        val w = _ui.value as? SplitUi.Writing ?: return
        SplitWork.cancel(context, w.song.mediaStoreId)
    }

    /**
     * "Delete" on the question about the original (R-49), after the app's own question was answered: gives the request for Android to launch. With
     * the Media management access Android shows no dialog; refusing a dialog changes nothing.
     */
    fun deleteOriginal(launch: (IntentSender) -> Unit) {
        val f = _ui.value as? SplitUi.Finished ?: return
        val record = f.record ?: return
        if (!f.outcome.finished || deleteBusy) return
        deleteBusy = true
        viewModelScope.launch {
            try {
                // Right before the request: the id must still name the file that was split (a rebuilt media index can reuse ids).
                val request = withContext(Dispatchers.IO) {
                    val info = SourceInfo.read(context, record.source.mediaId, record.volume)
                    when {
                        info == null -> null
                        !DeleteTargets.originalIsUnchanged(record, SourceInfo.identity(context, record.source.mediaId, record.volume, info)) -> throw SplitError.SourceChanged()
                        else -> writerFor(f.song, record).deleteOriginalRequest(record, userSaidYes = true)
                    }
                }
                if (request == null) originalDeleted() else launch(request.intentSender)
            } catch (e: Exception) {
                deleteBusy = false
                failed(e)
            }
        }
    }

    /** "Remove" on the question "Remove N songs?" (R-47): exactly the songs this run wrote that still exist. */
    fun removeSongs(launch: (IntentSender) -> Unit) {
        val f = _ui.value as? SplitUi.Finished ?: return
        val record = f.record ?: return
        if (deleteBusy) return
        deleteBusy = true
        viewModelScope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    val existing = SourceInfo.existingSizes(context, record.volume, record.written.map { it.mediaId })
                    if (DeleteTargets.songs(record, existing).isEmpty()) null else writerFor(f.song, record).removeSongsRequest(record, existing, userSaidYes = true)
                }
                if (request == null) songsRemoved() else launch(request.intentSender)
            } catch (e: Exception) {
                deleteBusy = false
                failed(e)
            }
        }
    }

    /** Leaves K6: a finished run's record is no longer needed (Technical Design 5.9); one that stopped is kept for "Continue". */
    fun closeFinished() {
        val f = _ui.value as? SplitUi.Finished
        _open.value = false
        _ui.value = SplitUi.Idle
        val record = f?.record
        if (f != null && record != null && f.outcome.finished) viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { store.delete(record.id) } } }
    }

    /** Android's delete request came back approved. */
    fun originalDeleted() {
        deleteBusy = false
        (_ui.value as? SplitUi.Finished)?.let { _ui.value = it.copy(originalDeleted = true) }
    }

    fun songsRemoved() {
        deleteBusy = false
        (_ui.value as? SplitUi.Finished)?.let { _ui.value = it.copy(songsRemoved = true) }
    }

    /** Android's dialog was refused or closed: nothing changed. */
    fun deleteDeclined() {
        deleteBusy = false
    }

    /** Back from K3 to the list. */
    fun backToList() {
        // After any edit still being saved, so the list shows the record as it was saved.
        viewModelScope.launch {
            cutTurn.withLock {
                val c = _ui.value as? SplitUi.Checking ?: return@withLock
                _ui.value = found(c.song, c.record)
            }
        }
    }

    fun clearAddCutError() {
        (_ui.value as? SplitUi.Found)?.let { _ui.value = it.copy(addCutFailed = false) }
    }

    /** Leaves the flow (a message dismissed, a question declined, K2 left): the saved record stays for a later "Continue". */
    fun leave() {
        watching?.cancel()
        pending = null
        _open.value = false
        _ui.value = SplitUi.Idle
    }
}
