package app.songplayer.core.details

import app.songplayer.core.Clock
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.library.Song
import app.songplayer.core.library.song
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsServiceTest {
    /** Runs a suspend block that never really suspends (the fakes answer at once), so `core` needs no coroutine library. */
    private fun <T> runBlocking(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    private class FakeClock(var wall: Long = ZonedDateTime.of(2026, 11, 5, 15, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), var elapsed: Long = 10_000) : Clock {
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
    }

    private class FakeSettings(var on: Boolean = true, var paused: Long = 0, var replace: Boolean = false) : DetailsSettings {
        override fun enabled() = on
        override fun replaceExisting() = replace
        override fun wifiOnly() = true
        override fun pausedUntilMs() = paused
        override fun setPausedUntilMs(ms: Long) { paused = ms }
    }

    private class FakeStore(var status: String? = null, var accept: Boolean = true) : DetailsStore {
        val applied = mutableListOf<List<FieldDiff>>()
        val held = mutableListOf<List<Candidate>>()
        var notFound = 0
        override fun status(songKey: String) = status
        override fun applyAutomatic(songKey: String, diffs: List<FieldDiff>, source: ChangeSource, nowSec: Long): Boolean {
            if (accept) applied += diffs
            return accept
        }
        override fun hold(songKey: String, candidates: List<Candidate>, nowSec: Long): Boolean { if (accept) held += candidates; return accept }
        override fun markNotFound(songKey: String, nowSec: Long): Boolean { if (accept) notFound++; return accept }
        override fun decidedKeys(): Set<String> = emptySet()
    }

    private class FakeLookup(val answer: () -> List<Candidate>) : LookupClient {
        val requests = mutableListOf<Pair<String, Int>>()
        override suspend fun lookup(fingerprint: String, durationS: Int): List<Candidate> {
            requests += fingerprint to durationS
            return answer()
        }
    }

    private val good = listOf(Candidate("夜曲", listOf("周杰伦"), 180, 0.98, listOf(ReleaseGroup("十一月的萧邦", "Album"), ReleaseGroup("Best", "Album"))))
    private val clock = FakeClock()
    private val settings = FakeSettings()
    private val store = FakeStore()
    private val sleeps = mutableListOf<Long>()
    private fun service(lookup: LookupClient, audio: SongAudio = SongAudio { "FP" }) =
        DetailsService(settings, store, audio, lookup, clock, { sleeps += it; clock.elapsed += it }, ZoneId.of("UTC"))

    private val bare: Song = song("a.mp3", title = null, artist = null, album = null)

    @Test
    fun DetailsService_RecordedAnswers_AppliedHeldNotFound() = runBlocking {
        assertEquals(LookupOutcome.Applied, service(FakeLookup { good }).process(bare))
        assertEquals(1, store.applied.size)
        assertEquals(LookupOutcome.Held, service(FakeLookup { listOf(good[0].copy(score = 0.7)) }).process(bare))
        assertEquals(1, store.held.size)
        assertEquals(1, store.applied.size)
        assertEquals(LookupOutcome.NotFound, service(FakeLookup { emptyList() }).process(bare))
        assertEquals(1, store.notFound)
    }

    @Test
    fun DetailsService_FeatureOff_SendsNothing() = runBlocking {
        settings.on = false
        val lookup = FakeLookup { good }
        var decoded = 0
        assertEquals(LookupOutcome.Skipped, service(lookup) { decoded++; "FP" }.process(bare))
        assertEquals(0, lookup.requests.size)
        assertEquals(0, decoded)
    }

    @Test
    fun DetailsService_FillsOnlyMissingFields() = runBlocking {
        val lookup = FakeLookup { good }
        service(lookup).process(song("b.mp3", title = "My Title", artist = null, album = "My Album"))
        assertEquals(listOf(FieldDiff(DetailField.Artist, null, "周杰伦")), store.applied.single())
        store.applied.clear()
        service(lookup).process(song("c.mp3", title = null, artist = "Mine", album = null))
        assertEquals(listOf(DetailField.Title, DetailField.Album), store.applied.single().map { it.field })
        // Nothing missing: nothing is asked.
        val before = lookup.requests.size
        assertEquals(LookupOutcome.Skipped, service(lookup).process(song("d.mp3")))
        assertEquals(before, lookup.requests.size)
    }

    @Test
    fun DetailsService_ManualAndRejectedAndHeld_AreSkipped() = runBlocking {
        val lookup = FakeLookup { good }
        for (status in listOf(DetailsStatus.MANUAL, DetailsStatus.REJECTED, DetailsStatus.HELD, DetailsStatus.NOT_FOUND, DetailsStatus.APPLIED)) {
            store.status = status
            assertEquals(status, LookupOutcome.Skipped, service(lookup).process(bare))
        }
        assertEquals(0, lookup.requests.size)
        store.status = DetailsStatus.PENDING
        assertEquals(LookupOutcome.Applied, service(lookup).process(bare))
    }

    @Test
    fun DetailsService_NotDecodable_EndsAsNotFound() = runBlocking {
        val lookup = FakeLookup { good }
        assertEquals(LookupOutcome.NotFound, service(lookup) { throw DetailsError.NotDecodable() }.process(bare))
        assertEquals(1, store.notFound)
        assertEquals(0, lookup.requests.size)
        // A codec that cannot start is not the song's fault: it stays pending.
        assertEquals(LookupOutcome.Transient, service(lookup) { throw DetailsError.Unavailable() }.process(bare))
        assertEquals(1, store.notFound)
    }

    @Test
    fun DetailsService_Requests_AreOneSecondApart() = runBlocking {
        val svc = service(FakeLookup { good })
        svc.process(bare)
        assertEquals(emptyList<Long>(), sleeps) // the first request goes at once
        clock.elapsed += 300
        svc.process(bare)
        assertEquals(listOf(700L), sleeps)
        clock.elapsed += 5_000
        svc.process(bare)
        assertEquals(listOf(700L), sleeps)
    }

    @Test
    fun DetailsService_Quota_PausesUntilTomorrow_NoSecondRequest() = runBlocking {
        val lookup = FakeLookup { throw DetailsError.QuotaReached() }
        val svc = service(lookup)
        assertEquals(LookupOutcome.QuotaStop, svc.process(bare))
        val tomorrow = ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(tomorrow, settings.paused)
        assertEquals(LookupOutcome.Skipped, svc.process(bare))
        assertEquals(1, lookup.requests.size)
        clock.wall = tomorrow // the next day: asks again
        assertEquals(LookupOutcome.QuotaStop, svc.process(bare))
        assertEquals(2, lookup.requests.size)
    }

    @Test
    fun DetailsService_TransientFailures_LeaveTheSongPending() = runBlocking {
        for (e in listOf(DetailsError.Unavailable(), DetailsError.BadAnswer())) {
            assertEquals(LookupOutcome.Transient, service(FakeLookup { throw e }).process(bare))
        }
        assertEquals(LookupOutcome.NotConfigured, service(FakeLookup { throw DetailsError.NotConfigured() }).process(bare))
        assertTrue(store.applied.isEmpty() && store.held.isEmpty() && store.notFound == 0)
    }

    @Test
    fun DetailsService_AnswerAfterManualEdit_DoesNotOverwrite() = runBlocking {
        store.accept = false // the store refuses: the song was edited by hand while the request was out
        assertEquals(LookupOutcome.Skipped, service(FakeLookup { good }).process(bare))
        assertTrue(store.applied.isEmpty())
        assertEquals(LookupOutcome.Skipped, service(FakeLookup { emptyList() }).process(bare))
        assertEquals(LookupOutcome.Skipped, service(FakeLookup { listOf(good[0].copy(score = 0.5)) }).process(bare))
    }

    @Test
    fun DetailsService_SureMatchWithoutAnAlbum_DecidesTheSongAnyway() = runBlocking {
        val noAlbum = listOf(good[0].copy(albums = emptyList()))
        assertEquals(LookupOutcome.Decided, service(FakeLookup { noAlbum }).process(song("e.mp3", title = "T", artist = "A", album = null)))
        assertEquals(emptyList<FieldDiff>(), store.applied.single())
    }

    @Test
    fun DetailsService_FeatureTurnedOffWhileWorking_SendsNothing() = runBlocking {
        val lookup = FakeLookup { good }
        assertEquals(LookupOutcome.Skipped, service(lookup) { settings.on = false; "FP" }.process(bare))
        assertEquals(0, lookup.requests.size)
    }

    @Test
    fun DetailsService_NoKeyInThisBuild_DecodesNothing() = runBlocking {
        var decoded = 0
        val lookup = object : LookupClient {
            override val configured = false
            override suspend fun lookup(fingerprint: String, durationS: Int): List<Candidate> = error("never called")
        }
        assertEquals(LookupOutcome.NotConfigured, service(lookup) { decoded++; "FP" }.process(bare))
        assertEquals(0, decoded)
    }

    @Test
    fun DetailsService_UnknownLength_IsNotFoundWithoutARequest() = runBlocking {
        val lookup = FakeLookup { good }
        assertEquals(LookupOutcome.NotFound, service(lookup).process(song("g.mp3", title = null, durationMs = 0)))
        assertEquals(0, lookup.requests.size)
    }

    private val complete = song("full.mp3", title = "Old title", artist = "Old artist", album = "Old album")

    @Test
    fun DetailsService_ReplaceOff_CompleteSongIsNotLookedUp() = runBlocking {
        val lookup = FakeLookup { good }
        assertEquals(LookupOutcome.Skipped, service(lookup).process(complete))
        assertEquals(0, lookup.requests.size)
    }

    @Test
    fun DetailsService_ReplaceOn_SureMatchReplaces_AndKeepsTheEarlierValues() = runBlocking {
        settings.replace = true
        val lookup = FakeLookup { good }
        assertEquals(LookupOutcome.Applied, service(lookup).process(complete))
        assertEquals(
            listOf(
                FieldDiff(DetailField.Title, "Old title", "夜曲"),
                FieldDiff(DetailField.Artist, "Old artist", "周杰伦"),
                FieldDiff(DetailField.Album, "Old album", "十一月的萧邦"),
            ),
            store.applied.single(),
        ) // the previous values are in the diffs, which the store keeps for Undo
    }

    @Test
    fun DetailsService_ReplaceOn_ManualApplied_Held_Rejected_NotFound_AreNeverReplaced() = runBlocking {
        settings.replace = true
        val lookup = FakeLookup { good }
        for (status in listOf(DetailsStatus.MANUAL, DetailsStatus.APPLIED, DetailsStatus.HELD, DetailsStatus.REJECTED, DetailsStatus.NOT_FOUND)) {
            store.status = status
            assertEquals(status, LookupOutcome.Skipped, service(lookup).process(complete))
        }
        assertEquals(0, lookup.requests.size)
        assertTrue(store.applied.isEmpty())
    }

    @Test
    fun DetailsService_ReplaceOn_IdenticalMatch_DecidesWithoutChanges() = runBlocking {
        settings.replace = true
        val same = song("same.mp3", title = "夜曲", artist = "周杰伦", album = "十一月的萧邦")
        assertEquals(LookupOutcome.Decided, service(FakeLookup { good }).process(same))
        assertEquals(emptyList<FieldDiff>(), store.applied.single())
    }

    @Test
    fun DetailsService_RequestSendsOnlyTheFingerprintAndTheLength() = runBlocking {
        val lookup = FakeLookup { good }
        service(lookup) { "FINGERPRINT-OF-${it.fileName}" }.process(song("f.mp3", title = null, durationMs = 241_900))
        assertEquals(listOf("FINGERPRINT-OF-f.mp3" to 241), lookup.requests)
    }
}
