package app.songplayer.core.details

import app.songplayer.core.Clock
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.library.Song
import java.time.Instant
import java.time.ZoneId

/** Fingerprints a song (the app decodes it; the audio never leaves the phone). Throws [DetailsError.NotDecodable] or [DetailsError.Unavailable]. */
fun interface SongAudio {
    fun fingerprint(song: Song): String
}

/** The feature's settings (Technical Design 6: `detailsEnabled`, `detailsPausedUntil`). */
interface DetailsSettings {
    fun enabled(): Boolean

    /** "Also replace details that are already filled in" (R-32); off unless the user turned it on. */
    fun replaceExisting(): Boolean

    /** "Only new songs" (R-51): when set, the time (epoch seconds) from which songs count as new; songs added earlier are never looked up. Null: off. */
    fun onlyNewSinceSec(): Long? = null

    /** "Only look up on Wi-Fi" (R-28); on unless the user turned it off. */
    fun wifiOnly(): Boolean
    fun pausedUntilMs(): Long
    fun setPausedUntilMs(ms: Long)
}

/** What the lookup needs from the details store; every write is conditional on the song still being undecided. */
interface DetailsStore {
    fun status(songKey: String): String?

    /** Saves [diffs] as automatic changes and marks the song applied. False, changing nothing, if it was decided meanwhile. */
    fun applyAutomatic(songKey: String, diffs: List<FieldDiff>, source: ChangeSource, nowSec: Long): Boolean

    /** Keeps [candidates] for the user's review. False if the song was decided meanwhile. */
    fun hold(songKey: String, candidates: List<Candidate>, nowSec: Long): Boolean

    /** Marks the song as one the service does not know. False if it was decided meanwhile. */
    fun markNotFound(songKey: String, nowSec: Long): Boolean

    /** The keys of every song that already has a decision (applied, held, not found, rejected, manual), one query. */
    fun decidedKeys(): Set<String>
}

/** Looks one song up. The runner takes this, so it can be tested without a service behind it. */
fun interface SongProcessor {
    suspend fun process(song: Song): LookupOutcome
}

/** What happened to one song. */
enum class LookupOutcome {
    Applied, Held, NotFound,

    /** Nothing was sent: the feature is off, paused until tomorrow, the song is decided, or nothing is missing. */
    Skipped,

    /** A sure match with nothing to write (only the album was missing and none is listed): the song is decided, no change is made. */
    Decided,

    /** The service or the network failed for now; the song stays pending. */
    Transient,

    /** The service asked us to wait; the pause until the next local day is set. */
    QuotaStop,

    /** This build has no usable key; nothing was sent. */
    NotConfigured,
}

/**
 * The pipeline for one song: consent, fingerprint, one request, the match rules, then the store (Technical Design 5.8). The caller (the
 * background worker) decides which songs and in what order. Requests are spaced [REQUEST_INTERVAL_MS] apart by [sleep]; a quota answer
 * pauses everything until the start of the next local day, and is never retried before. Not thread-safe: one caller at a time (the
 * single background worker), which is also what the one-request-a-second rule needs.
 */
class DetailsService(
    private val settings: DetailsSettings,
    private val store: DetailsStore,
    private val audio: SongAudio,
    private val lookup: LookupClient,
    private val clock: Clock,
    private val sleep: suspend (Long) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** When two texts count as the same when replacing (R-32): the app passes the search fold. */
    private val same: (String, String) -> Boolean = { a, b -> a.trim() == b.trim() },
) : SongProcessor {
    private var lastRequestAtMs: Long? = null

    override suspend fun process(song: Song): LookupOutcome {
        if (!settings.enabled()) return LookupOutcome.Skipped
        if (clock.wallMs() < settings.pausedUntilMs()) return LookupOutcome.Skipped
        val key = song.key.value
        if (!DetailsStatus.mayApplyAutomatic(store.status(key))) return LookupOutcome.Skipped
        val replace = settings.replaceExisting()
        if (!MatchRules.needsLookup(song, replace, settings.onlyNewSinceSec())) return LookupOutcome.Skipped

        if (!lookup.configured) return LookupOutcome.NotConfigured // before the costly decoding
        val durationS = (song.durationMs / 1000).toInt()
        // The service needs the song's length; a song that does not say it cannot be looked up, now or later.
        if (durationS <= 0) return if (store.markNotFound(key, nowSec())) LookupOutcome.NotFound else LookupOutcome.Skipped

        val fingerprint = try {
            audio.fingerprint(song)
        } catch (_: DetailsError.NotDecodable) {
            return if (store.markNotFound(key, nowSec())) LookupOutcome.NotFound else LookupOutcome.Skipped
        } catch (_: DetailsError.Unavailable) {
            return LookupOutcome.Transient
        }

        waitForTurn()
        // The fingerprint and the wait take time: if the user turned the feature off meanwhile, nothing more is sent (R-19).
        if (!settings.enabled()) return LookupOutcome.Skipped
        val candidates = try {
            lookup.lookup(fingerprint, durationS)
        } catch (_: DetailsError.QuotaReached) {
            settings.setPausedUntilMs(startOfNextDayMs())
            return LookupOutcome.QuotaStop
        } catch (_: DetailsError.NotConfigured) {
            return LookupOutcome.NotConfigured
        } catch (_: DetailsError.Unavailable) {
            return LookupOutcome.Transient
        } catch (_: DetailsError.BadAnswer) {
            return LookupOutcome.Transient
        }

        return when (val decision = MatchRules.decide(candidates, durationS)) {
            MatchDecision.NotFound -> if (store.markNotFound(key, nowSec())) LookupOutcome.NotFound else LookupOutcome.Skipped
            is MatchDecision.Held -> if (store.hold(key, decision.candidates, nowSec())) LookupOutcome.Held else LookupOutcome.Skipped
            is MatchDecision.Sure -> {
                val diffs = MatchRules.diffs(song, decision.match, replace, same)
                // Even with nothing to write (only an album was missing and the match has no Album) the song is decided, so it is not asked again.
                if (!store.applyAutomatic(key, diffs, ChangeSource.Auto, nowSec())) LookupOutcome.Skipped
                else if (diffs.isEmpty()) LookupOutcome.Decided
                else LookupOutcome.Applied
            }
        }
    }

    private suspend fun waitForTurn() {
        val last = lastRequestAtMs
        val now = clock.elapsedMs()
        if (last != null && now - last < REQUEST_INTERVAL_MS) sleep(REQUEST_INTERVAL_MS - (now - last))
        lastRequestAtMs = clock.elapsedMs()
    }

    private fun nowSec() = clock.wallMs() / 1000

    private fun startOfNextDayMs(): Long =
        Instant.ofEpochMilli(clock.wallMs()).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        const val REQUEST_INTERVAL_MS = 1000L
    }
}
