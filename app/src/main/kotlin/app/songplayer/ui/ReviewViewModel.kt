package app.songplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.songplayer.AppContainer
import app.songplayer.core.details.ReviewCounts
import app.songplayer.core.details.ReviewItem
import app.songplayer.core.details.ReviewRules
import app.songplayer.core.details.ReviewSession
import app.songplayer.core.errors.StoreError
import app.songplayer.core.library.SongKey
import app.songplayer.core.log.LogCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** What the Review screen shows (UX Design 4.10, H6 and H7). */
sealed interface ReviewUi {
    data object Loading : ReviewUi

    /** Nothing is waiting for review. */
    data object Empty : ReviewUi

    data class Reviewing(val index: Int, val total: Int, val item: ReviewItem, val selected: Int) : ReviewUi

    data class Finished(val counts: ReviewCounts) : ReviewUi

    /** The songs could not be read, or a decision could not be saved: nothing is shown as done; "Try again" starts over. */
    data object Failed : ReviewUi
}

/**
 * One pass through the held songs (Stories H9). The rules are in `core` (`ReviewSession`); this class loads the songs when Review opens and
 * runs each decision off the main thread. A store failure is logged and the song is left as it was.
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<ReviewUi>(ReviewUi.Loading)
    val ui: StateFlow<ReviewUi> = _ui
    @Volatile private var session: ReviewSession? = null

    /** One decision at a time: a double tap must not act on the next song, which the user has not seen. Touched from the main thread only. */
    private var deciding = false
    private val lock = Mutex()

    /** Reads the held songs and starts at the first. Called every time Review is opened, so a skipped song comes back. */
    fun open() {
        _ui.value = ReviewUi.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val items = try {
                container.detailsStore.heldKeys().mapNotNull { key ->
                    val song = container.songStore.get(SongKey(key)) ?: return@mapNotNull null // the song left the library
                    val candidates = ReviewRules.collapse(container.detailsStore.heldCandidates(key))
                    if (candidates.isEmpty()) null else ReviewItem(song, candidates)
                }
            } catch (e: StoreError) {
                container.log.event(LogCode.DETAILS_STORE_FAILED, 0)
                null
            }
            lock.withLock {
                session = items?.let { ReviewSession(it, container.detailsStore, container.detailsSettings.replaceExisting(), { a, b -> container.keyer.searchFold(a) == container.keyer.searchFold(b) }) { System.currentTimeMillis() / 1000 } }
                if (items == null) _ui.value = ReviewUi.Failed else publish()
            }
        }
    }

    fun select(choice: Int) {
        viewModelScope.launch(Dispatchers.IO) { lock.withLock { session?.select(choice); publish() } }
    }

    fun use() = decide { it.use() }

    fun reject() = decide { it.reject() }

    fun skip() = decide { it.skip() }

    private fun decide(action: (ReviewSession) -> Unit) {
        val s = session ?: return
        if (deciding) return
        deciding = true
        viewModelScope.launch(Dispatchers.IO) {
            lock.withLock {
                try {
                    action(s)
                    publish()
                } catch (e: StoreError) {
                    container.log.event(LogCode.DETAILS_STORE_FAILED, 1)
                    _ui.value = ReviewUi.Failed
                }
            }
            withContext(Dispatchers.Main.immediate) { deciding = false }
        }
    }

    private fun publish() {
        val s = session ?: return
        val item = s.current
        _ui.value = when {
            s.total == 0 -> ReviewUi.Empty
            item == null -> ReviewUi.Finished(s.counts)
            else -> ReviewUi.Reviewing(s.index, s.total, item, s.selected)
        }
    }
}
