package app.songplayer.core.details

import app.songplayer.core.Clock
import app.songplayer.core.library.Song
import java.time.Instant
import java.time.ZoneId

/** How one background run ended (Technical Design 5.8). */
sealed interface RunResult {
    /** Nothing more to do now: all songs are decided, or none could be looked up. */
    data object Done : RunResult

    /** A full batch was handled and songs may remain: run again right away. */
    data object NextBatch : RunResult

    /** Several transient failures in a row: let the scheduler try again later with its backoff. */
    data object Retry : RunResult

    /** The service asked us to wait: nothing is sent until [untilMs] (the start of the next local day). */
    data class Quota(val untilMs: Long) : RunResult

    /** The feature is off or this build has no usable key: nothing was sent, and nothing is scheduled. */
    data object Stopped : RunResult
}

/** Remembers, for the rest of the day, which songs failed for a transient reason several times, so one bad song cannot block a run. Process-local on purpose. */
class AttemptLimit(private val clock: Clock, private val zone: ZoneId = ZoneId.systemDefault(), private val maxAttempts: Int = 3) {
    private val counts = HashMap<String, Int>()
    private var day = today()

    fun failed(songKey: String) { rollOver(); counts[songKey] = (counts[songKey] ?: 0) + 1 }

    fun skipped(songKey: String): Boolean { rollOver(); return (counts[songKey] ?: 0) >= maxAttempts }

    private fun today() = Instant.ofEpochMilli(clock.wallMs()).atZone(zone).toLocalDate()

    private fun rollOver() { val t = today(); if (t != day) { counts.clear(); day = t } }
}

/**
 * One background run over the songs: in key order, at most [BATCH_SONGS] looked up, stopping early on a quota answer, a missing key or
 * [MAX_TRANSIENT_IN_A_ROW] transient failures in a row. Songs that need nothing are passed over without counting. Run by one worker at a time.
 */
class DetailsRunner(
    private val settings: DetailsSettings,
    private val processor: SongProcessor,
    private val clock: Clock,
    private val attempts: AttemptLimit,
    /** The songs that need no lookup, read once per run so a chain of batches does not ask the store about each of them again. */
    private val decided: () -> Set<String> = { emptySet() },
) {
    /** The songs this run was going to look at, so [hasNewWork] can tell which ones appeared later. */
    private var startedWith: Set<String> = emptySet()

    /**
     * True when [songs] (read again after a run ended) hold an undecided song that needs a lookup and was not among the songs the last run
     * started with: songs added while the run was at work. The worker then runs once more instead of waiting for the next refresh. Only new
     * songs count, so a song that is skipped for some other reason can never keep the chain going.
     */
    fun hasNewWork(songs: List<Song>): Boolean {
        val done = decided()
        val replace = settings.replaceExisting()
        val newSince = settings.onlyNewSinceSec()
        return songs.any { it.key.value !in done && it.key.value !in startedWith && MatchRules.needsLookup(it, replace, newSince) && !attempts.skipped(it.key.value) }
    }

    suspend fun run(songs: List<Song>): RunResult {
        if (!settings.enabled()) return RunResult.Stopped
        if (clock.wallMs() < settings.pausedUntilMs()) return RunResult.Quota(settings.pausedUntilMs())
        val done = decided()
        val replace = settings.replaceExisting()
        val newSince = settings.onlyNewSinceSec()
        val todo = songs.filter { it.key.value !in done && MatchRules.needsLookup(it, replace, newSince) }
            .sortedBy { it.key.value }
        startedWith = todo.mapTo(HashSet()) { it.key.value }
        val startedAt = clock.elapsedMs()
        var handled = 0
        var failuresInARow = 0
        for ((i, song) in todo.withIndex()) {
            if (attempts.skipped(song.key.value)) continue
            when (processor.process(song)) {
                LookupOutcome.Skipped -> continue
                LookupOutcome.Applied, LookupOutcome.Held, LookupOutcome.NotFound, LookupOutcome.Decided -> { handled++; failuresInARow = 0 }
                LookupOutcome.Transient -> {
                    handled++
                    attempts.failed(song.key.value)
                    if (++failuresInARow >= MAX_TRANSIENT_IN_A_ROW) return RunResult.Retry
                }
                LookupOutcome.QuotaStop -> return RunResult.Quota(settings.pausedUntilMs())
                LookupOutcome.NotConfigured -> return RunResult.Stopped
            }
            // The user may turn the feature off between two songs; the service checks again, and the run ends here.
            if (!settings.enabled()) return RunResult.Stopped
            if (handled >= BATCH_SONGS || clock.elapsedMs() - startedAt >= BATCH_BUDGET_MS) return if (i < todo.lastIndex) RunResult.NextBatch else RunResult.Done
        }
        return RunResult.Done
    }

    companion object {
        const val BATCH_SONGS = 20

        /** A batch also ends after this long, so a slow phone never meets the scheduler's limit of about ten minutes per run. */
        const val BATCH_BUDGET_MS = 5L * 60 * 1000
        const val MAX_TRANSIENT_IN_A_ROW = 3
    }
}

/** What the scheduler does after a run: nothing, run again at once, retry with its backoff, or run again after a delay. */
sealed interface NextStep {
    data object None : NextStep
    data object RetryLater : NextStep
    data class Again(val delayMs: Long) : NextStep
}

object DetailsPlan {
    /** The longest random extra wait added after a quota stop, so a day's first run is not exactly at midnight. */
    const val QUOTA_JITTER_MS = 2L * 60 * 60 * 1000

    /** [jitterMs] is a random number from 0 up to [QUOTA_JITTER_MS]. */
    fun next(result: RunResult, nowMs: Long, jitterMs: Long): NextStep = when (result) {
        RunResult.Done, RunResult.Stopped -> NextStep.None
        RunResult.NextBatch -> NextStep.Again(0)
        RunResult.Retry -> NextStep.RetryLater
        is RunResult.Quota -> NextStep.Again(maxOf(0L, result.untilMs - nowMs) + jitterMs.coerceIn(0L, QUOTA_JITTER_MS))
    }
}
