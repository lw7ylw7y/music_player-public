package app.songplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.songplayer.AppContainer
import app.songplayer.BuildConfig
import app.songplayer.core.details.ChangeSource
import app.songplayer.core.details.DetailsOverview
import app.songplayer.core.details.DetailsState
import app.songplayer.core.details.MatchRules
import app.songplayer.core.details.WriteState
import app.songplayer.details.FlushSummary
import kotlinx.coroutines.withContext
import app.songplayer.core.details.DetailField
import app.songplayer.core.details.RecentItem
import app.songplayer.core.details.TagFields
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.Song
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.LogCode
import app.songplayer.details.SaveResult
import app.songplayer.details.UndoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of "Recently filled in": what to show about a batch of changes. */
data class RecentRowUi(val batch: Long, val title: String, val artist: String, val fields: List<DetailField>, val byUser: Boolean)

/** What Song details shows about the lookup (Stories H8). */
data class SongDetailsUi(
    val enabled: Boolean,
    val wifiOnly: Boolean,
    /** "Also replace details that are already filled in" (R-32). */
    val replace: Boolean,
    val state: DetailsState,
    val held: Int,
    /** Songs the service did not know or that could not be read. */
    val notFound: Int,
    /** How many songs have details that are only in the app, not yet in the file ("N songs' details are only in the app" with "Write them now"). */
    val waitingSongs: Int,
    /** Whether the "Media management" access is on (then Android grants a write request without a dialog). */
    val filesGranted: Boolean,
    /** The user has agreed before, so turning on again needs no dialog. */
    val consented: Boolean,
    /** "Only look up new songs" (R-51). */
    val onlyNew: Boolean = false,
)

/**
 * The screens' side of the song details (Stories H3): hand edits, Undo, and the list of recent changes. Every call runs off the main
 * thread; the callback comes back on it. A failed save or undo is reported as null, never as an exception.
 */
class DetailsViewModel(private val container: AppContainer) : ViewModel() {
    val recent: StateFlow<List<RecentRowUi>> = container.detailsStore.version
        .map { load() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val refresh = MutableStateFlow(0)
    private val ticker = flow { while (true) { emit(Unit); delay(OVERVIEW_PERIOD_MS) } }

    /** The state of the lookup for the Song details screen; read again every few seconds while the screen is shown, and at once on any change. */
    val overview: StateFlow<SongDetailsUi?> = combine(container.detailsStore.version, container.detailsStore.statusVersion, refresh, ticker) { _, _, _, _ -> loadOverview() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the lookup is on: the Settings row says "On." or "Off." in words (a cheap read; the full overview is only taken while Song details is open). */
    val enabled: StateFlow<Boolean> = refresh.map { container.detailsSettings.enabled() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The songs the service did not know or that could not be read, for the "Not found" list. */
    val notFound: StateFlow<List<Song>> = combine(container.detailsStore.statusVersion, container.detailsStore.version, refresh) { _, _, _ -> loadNotFound() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun loadNotFound(): List<Song> = try {
        container.detailsStore.notFoundKeys().mapNotNull { container.songStore.get(SongKey(it)) }
    } catch (e: StoreError) {
        container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
        emptyList()
    }

    /** The number of songs waiting for review, or 0 when the banner is dismissed or nothing waits. */
    val bannerCount: StateFlow<Int> = combine(container.detailsStore.statusVersion, refresh) { _, _ -> loadBanner() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private fun loadBanner(): Int = try {
        val held = liveCount(container.detailsStore.heldKeys())
        val stored = container.detailsSettings.bannerDismissedAt()
        // Some held songs were decided since the dismissal: a later new one must show the banner again.
        val dismissed = DetailsOverview.dismissalToKeep(held, stored)
        if (dismissed != stored) container.detailsSettings.setBannerDismissedAt(dismissed)
        if (DetailsOverview.bannerVisible(held, dismissed)) held else 0
    } catch (e: StoreError) {
        container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
        0
    }

    private fun loadOverview(): SongDetailsUi? = try {
        val settings = container.detailsSettings
        val songs = container.songStore.indexed().map { it.song }
        val decided = container.detailsStore.decidedKeys()
        val checked = songs.count { it.key.value in decided }
        val replace = settings.replaceExisting()
        val newSince = settings.onlyNewSinceSec()
        val remaining = songs.count { it.key.value !in decided && MatchRules.needsLookup(it, replace, newSince) }
        val held = liveCount(container.detailsStore.heldKeys())
        val enabled = settings.enabled()
        val wifiOnly = settings.wifiOnly()
        val state = DetailsOverview.state(
            keyConfigured = BuildConfig.ACOUSTID_KEY.isNotEmpty(), enabled = enabled, wifiOnly = wifiOnly,
            connected = container.networkStatus.connected(), unmetered = container.networkStatus.unmetered(),
            pausedUntilMs = settings.pausedUntilMs(), nowMs = System.currentTimeMillis(), checked = checked, remaining = remaining, held = held,
        )
        val waitingSongs = container.detailsActions.waitingSongKeys().size
        SongDetailsUi(enabled, wifiOnly, replace, state, held, liveCount(container.detailsStore.notFoundKeys()), waitingSongs, container.filesChangeable(), settings.consentAtMs() != null, onlyNew = newSince != null)
    } catch (e: StoreError) {
        container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
        null
    }

    /** Counts only the songs still in the library, the ones the lists show, so a count never promises a row that is not there. */
    private fun liveCount(keys: List<String>): Int = keys.count { container.songStore.get(SongKey(it)) != null }

    fun setEnabled(on: Boolean) = act { container.setDetailsEnabled(on) }

    fun setWifiOnly(on: Boolean) = act { container.setDetailsWifiOnly(on) }

    fun setOnlyNew(on: Boolean) = act { container.setDetailsOnlyNew(on) }

    fun setReplace(on: Boolean) = act { container.setDetailsReplace(on) }

    /** Hides the Library banner until more songs wait for review than now. */
    fun dismissBanner() = act { container.detailsSettings.setBannerDismissedAt(liveCount(container.detailsStore.heldKeys())) }

    private fun act(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: StoreError) { container.log.event(LogCode.DETAILS_STORE_FAILED, 1) }
            refresh.update { it + 1 }
        }
    }

    private fun load(): List<RecentRowUi> = try {
        container.detailsActions.recent().mapNotNull { rowOf(it) }
    } catch (e: StoreError) {
        container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
        emptyList()
    }

    private fun rowOf(item: RecentItem): RecentRowUi? {
        val song = container.songStore.get(SongKey(item.songKey)) ?: return null // the song left the library
        return RecentRowUi(item.batch, song.displayTitle, song.displayArtist, item.changes.map { it.field }, item.source == ChangeSource.Manual)
    }

    fun save(song: Song, edited: TagFields, done: (SaveResult?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { container.detailsActions.saveEdit(song, edited) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a StoreError, or anything else from the writer: a plain message, never a crash
                container.log.event(LogCode.DETAILS_STORE_FAILED, 1)
                null
            }
            done(result)
        }
    }

    fun undo(batch: Long, done: (UndoResult?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { container.detailsActions.undo(batch) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a StoreError, or anything else from the writer: a plain message, never a crash
                container.log.event(LogCode.DETAILS_STORE_FAILED, 1)
                null
            }
            done(result)
        }
    }

    /** Writes what is waiting for access (when the app comes to the front, and after the user allowed it); [done] hears how it went, on the main thread. */
    fun flush(includeFailed: Boolean = false, done: (FlushSummary?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = try { container.detailsActions.flush(includeFailed = includeFailed) } catch (e: CancellationException) { throw e } catch (e: Exception) { container.log.event(LogCode.DETAILS_STORE_FAILED, 1); null }
            withContext(Dispatchers.Main) { done(summary) }
        }
    }

    /** The songs already named in a write request in this run of the app, so the next request goes to others first. Cleared when nothing waits any more. */
    private val asked = HashSet<String>()

    /**
     * Android's write request for the next songs that wait for access, handed to [launch] on the main thread; [none] is called instead when
     * no song can be asked for (or the request could not be made), so the caller can close out what it was doing.
     */
    fun writeGrantRequest(none: () -> Unit, launch: (android.content.IntentSender) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val sender = try { container.writeGrantRequest(asked) } catch (e: CancellationException) { throw e } catch (e: Exception) { container.log.event(LogCode.DETAILS_STORE_FAILED, 1); null }
            withContext(Dispatchers.Main) { if (sender != null) launch(sender) else { asked.clear(); none() } }
        }
    }

    private companion object {
        const val OVERVIEW_PERIOD_MS = 3_000L
    }
}
