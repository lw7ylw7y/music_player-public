package app.songplayer.core.details

import app.songplayer.core.Clock
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsRunnerTest {
    private fun <T> runBlocking(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    private val utc = ZoneId.of("UTC")
    private class FakeClock(var wall: Long = ZonedDateTime.of(2026, 11, 5, 15, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), var elapsed: Long = 0L) : Clock {
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
    }
    private class FakeSettings(var on: Boolean = true, var paused: Long = 0, var replace: Boolean = false, var newSince: Long? = null) : DetailsSettings {
        override fun enabled() = on
        override fun onlyNewSinceSec() = newSince
        override fun replaceExisting() = replace
        override fun wifiOnly() = true
        override fun pausedUntilMs() = paused
        override fun setPausedUntilMs(ms: Long) { paused = ms }
    }

    private val clock = FakeClock()
    private val settings = FakeSettings()
    private val seen = mutableListOf<String>()
    private fun songs(n: Int) = List(n) { song("s%03d.mp3".format(it), title = null) }
    private var decided: Set<String> = emptySet()
    private fun runner(attempts: AttemptLimit = AttemptLimit(clock, utc), process: (Song) -> LookupOutcome) =
        DetailsRunner(settings, { seen += it.fileName; process(it) }, clock, attempts) { decided }

    @Test
    fun DetailsRunner_OnlyNewSongs_LeavesEveryEarlierSongAlone_EvenWithReplaceOn() = runBlocking {
        val old = List(5) { song("old$it.mp3", title = null, dateAddedSec = 1_000) }
        val added = List(2) { song("new$it.mp3", title = null, dateAddedSec = 5_000) }
        settings.newSince = 2_000
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(old + added))
        assertEquals(added.map { it.fileName }, seen)          // only the songs added since the option was turned on
        seen.clear()
        settings.replace = true
        val complete = song("complete-old.mp3", dateAddedSec = 1_000)
        runner { LookupOutcome.Applied }.run(listOf(complete) + added)
        assertEquals(added.map { it.fileName }, seen)          // replacing never reaches back to old songs either
        seen.clear()
        settings.newSince = null                              // option off: all songs that need it are looked up again
        runner { LookupOutcome.Applied }.run(old)
        assertEquals(old.map { it.fileName }, seen)
    }

    @Test
    fun DetailsRunner_OnlyNewSongs_AnOldSongDoesNotKeepTheChainGoing() {
        settings.newSince = 2_000
        val r = runner { LookupOutcome.Applied }
        assertEquals(false, r.hasNewWork(listOf(song("old.mp3", title = null, dateAddedSec = 1_000))))
        assertEquals(true, r.hasNewWork(listOf(song("new.mp3", title = null, dateAddedSec = 3_000))))
    }

    @Test
    fun DetailsRunner_FeatureOff_DoesNothing() = runBlocking {
        settings.on = false
        assertEquals(RunResult.Stopped, runner { LookupOutcome.Applied }.run(songs(5)))
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun NewSongs_LookupOn_NamesThem_LookupOff_SendsNothing() = runBlocking {
        // The songs a split writes have no tags (Requirements R-48): the media index lists them with no title, artist or album.
        val fromSplit = List(12) { song("Big album %02d.mp3".format(it + 1), title = null, artist = null, album = null) }
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(fromSplit))
        assertEquals(fromSplit.map { it.fileName }, seen)                      // every one is looked up
        seen.clear()
        settings.on = false
        assertEquals(RunResult.Stopped, runner { LookupOutcome.Applied }.run(fromSplit))
        assertEquals(emptyList<String>(), seen)                                // with the lookup off nothing is asked
    }

    @Test
    fun DetailsRunner_Batch_HandlesAtMostTwentyAndChains() = runBlocking {
        assertEquals(RunResult.NextBatch, runner { LookupOutcome.Applied }.run(songs(45)))
        assertEquals(20, seen.size)
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(songs(20)))
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(songs(3)))
    }

    @Test
    fun DetailsRunner_SkippedSongsAndSongsWithAllFields_DoNotCountTowardTheBatch() = runBlocking {
        val all = songs(30) + listOf(song("full.mp3"))
        // The first ten need nothing (already decided): they are passed over; twenty more are handled.
        assertEquals(RunResult.Done, runner { if (it.fileName < "s010") LookupOutcome.Skipped else LookupOutcome.Applied }.run(all))
        assertEquals(30, seen.size)
        assertEquals(false, "full.mp3" in seen)
    }

    @Test
    fun DetailsRunner_ThreeTransientFailures_Retries() = runBlocking {
        assertEquals(RunResult.Retry, runner { LookupOutcome.Transient }.run(songs(10)))
        assertEquals(3, seen.size)
        // A success in between resets the count.
        seen.clear()
        var n = 0
        assertEquals(RunResult.Done, runner(AttemptLimit(clock, utc, maxAttempts = 99)) { if (n++ % 3 == 2) LookupOutcome.Applied else LookupOutcome.Transient }.run(songs(9)))
        assertEquals(9, seen.size)
    }

    @Test
    fun DetailsRunner_QuotaStop_DelaysToTomorrow() = runBlocking {
        val tomorrow = ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        val result = runner { settings.paused = tomorrow; LookupOutcome.QuotaStop }.run(songs(5))
        assertEquals(RunResult.Quota(tomorrow), result)
        assertEquals(1, seen.size)
        // While paused the runner sends nothing at all.
        seen.clear()
        assertEquals(RunResult.Quota(tomorrow), runner { LookupOutcome.Applied }.run(songs(5)))
        assertEquals(emptyList<String>(), seen)
        // The plan waits until tomorrow plus the jitter (never more than two hours).
        val now = clock.wall
        assertEquals(NextStep.Again(tomorrow - now + 1_000), DetailsPlan.next(result, now, 1_000))
        assertEquals(NextStep.Again(tomorrow - now + DetailsPlan.QUOTA_JITTER_MS), DetailsPlan.next(result, now, Long.MAX_VALUE))
    }

    @Test
    fun DetailsPlan_EachResult_HasItsNextStep() {
        assertEquals(NextStep.None, DetailsPlan.next(RunResult.Done, 0, 0))
        assertEquals(NextStep.None, DetailsPlan.next(RunResult.Stopped, 0, 0))
        assertEquals(NextStep.Again(0), DetailsPlan.next(RunResult.NextBatch, 0, 0))
        assertEquals(NextStep.RetryLater, DetailsPlan.next(RunResult.Retry, 0, 0))
    }

    @Test
    fun DetailsRunner_SongThatKeepsFailing_IsSkippedForTheRestOfTheDay() = runBlocking {
        val attempts = AttemptLimit(clock, utc, maxAttempts = 3)
        val all = songs(6)
        // s000 always fails; every other song works. Run 1 and 2: the bad song is first, each time it is tried again.
        fun process(s: Song) = if (s.fileName == "s000.mp3") LookupOutcome.Transient else LookupOutcome.Applied
        assertEquals(RunResult.Done, runner(attempts, ::process).run(all))
        assertEquals(RunResult.Done, runner(attempts, ::process).run(all))
        assertEquals(RunResult.Done, runner(attempts, ::process).run(all))
        assertEquals(3, seen.count { it == "s000.mp3" })
        seen.clear()
        assertEquals(RunResult.Done, runner(attempts, ::process).run(all))
        assertEquals(false, "s000.mp3" in seen)
        // Tomorrow it is tried again.
        clock.wall += 24 * 60 * 60 * 1000
        seen.clear()
        runner(attempts, ::process).run(all)
        assertEquals(true, "s000.mp3" in seen)
    }

    @Test
    fun DetailsRunner_DecidedSongs_AreNotAskedAbout() = runBlocking {
        val all = songs(30)
        decided = all.take(25).map { it.key.value }.toSet()
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(all))
        assertEquals(5, seen.size)
    }

    @Test
    fun DetailsRunner_ReplaceOn_CompleteSongsAreIncluded() = runBlocking {
        val all = songs(3) + listOf(song("full.mp3"))
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(all))
        assertEquals(false, "full.mp3" in seen) // setting off: a complete song is passed over
        seen.clear()
        settings.replace = true
        assertEquals(RunResult.Done, runner { LookupOutcome.Applied }.run(all))
        assertEquals(true, "full.mp3" in seen)
        // A complete song that already has a decision (for example a hand edit) is never asked about, whatever the setting.
        seen.clear()
        val full = all.last()
        decided = setOf(full.key.value)
        runner { LookupOutcome.Applied }.run(all)
        assertEquals(false, "full.mp3" in seen)
    }

    @Test
    fun DetailsRunner_SongsAddedWhileItWorked_ChainOneMoreRun() = runBlocking {
        val before = songs(3)
        val r = runner { LookupOutcome.Applied }
        assertEquals(RunResult.Done, r.run(before))
        assertEquals(false, r.hasNewWork(before))                                   // nothing new: no extra run
        val added = song("new.mp3", title = null)
        assertEquals(true, r.hasNewWork(before + added))                            // a song appeared meanwhile
        decided = setOf(added.key.value)
        assertEquals(false, r.hasNewWork(before + added))                           // ...but it already has a decision
        decided = emptySet()
        assertEquals(false, r.hasNewWork(before + song("full.mp3")))                // a complete song needs nothing
        settings.replace = true
        assertEquals(true, r.hasNewWork(before + song("full.mp3")))                 // unless replacing is on
        settings.replace = false
        val limit = AttemptLimit(clock, utc, maxAttempts = 1).also { it.failed(added.key.value) }
        assertEquals(false, runner(limit) { LookupOutcome.Applied }.also { it.run(before) }.hasNewWork(before + added)) // a song that keeps failing today
        assertEquals(RunResult.Done, r.run(before + added))                         // and the next run does handle it
        assertEquals(false, r.hasNewWork(before + added))                           // after which nothing is new
    }

    @Test
    fun DetailsRunner_SlowBatch_EndsAtTheTimeBudgetAndChains() = runBlocking {
        // Each song takes two minutes: the batch stops after three (six minutes), long before twenty.
        assertEquals(RunResult.NextBatch, runner { clock.elapsed += 120_000; LookupOutcome.Applied }.run(songs(10)))
        assertEquals(3, seen.size)
    }

    @Test
    fun DetailsRunner_TurnedOffMidRun_Stops() = runBlocking {
        assertEquals(RunResult.Stopped, runner { settings.on = false; LookupOutcome.Applied }.run(songs(10)))
        assertEquals(1, seen.size)
    }

    @Test
    fun DetailsRunner_NoUsableKey_StopsWithoutRetry() = runBlocking {
        assertEquals(RunResult.Stopped, runner { LookupOutcome.NotConfigured }.run(songs(10)))
        assertEquals(1, seen.size)
        assertEquals(NextStep.None, DetailsPlan.next(RunResult.Stopped, 0, 0))
    }
}
