package app.songplayer.core.library

import app.songplayer.core.errors.LibraryError
import app.songplayer.core.errors.StoreError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    private val a = song("a.mp3", id = 1)
    private val b = song("b.mp3", id = 2)

    @Test
    fun Sync_Success_AppliesThePlanOnce() {
        val store = InMemorySongStore(listOf(a))
        val result = LibrarySync(FakeLibrarySource(listOf(a, b)), store).run()
        assertTrue(result is SyncResult.Success)
        assertEquals(listOf(b), (result as SyncResult.Success).plan.added)
        assertEquals(listOf(a, b), store.all())
        assertEquals(1, store.applyCalls)
    }

    @Test
    fun Sync_NothingChanged_DoesNotWrite() {
        val store = InMemorySongStore(listOf(a))
        LibrarySync(FakeLibrarySource(listOf(a)), store).run()
        assertEquals(0, store.applyCalls)
    }

    @Test
    fun Sync_QueryFails_CacheUntouched_ReturnsQueryFailed() {
        val store = InMemorySongStore(listOf(a))
        val source = FakeLibrarySource(listOf(b), failure = LibraryError.QueryFailed())
        val result = LibrarySync(source, store).run()
        assertTrue((result as SyncResult.Failure).error is LibraryError.QueryFailed)
        assertEquals(listOf(a), store.all())
        assertEquals(0, store.applyCalls)
    }

    @Test
    fun Sync_PermissionDenied_CacheUntouched_ReturnsPermissionDenied() {
        val store = InMemorySongStore(listOf(a))
        val source = FakeLibrarySource(failure = LibraryError.PermissionDenied())
        val result = LibrarySync(source, store).run()
        assertTrue((result as SyncResult.Failure).error is LibraryError.PermissionDenied)
        assertEquals(listOf(a), store.all())
    }

    @Test
    fun Sync_UnexpectedException_BecomesQueryFailed_WithoutLeakingItsMessage() {
        val store = InMemorySongStore(listOf(a))
        val source = FakeLibrarySource(failure = IllegalStateException("/storage/emulated/0/Music/secret.mp3"))
        val failure = LibrarySync(source, store).run() as SyncResult.Failure
        assertTrue(failure.error is LibraryError.QueryFailed)
        assertFalse(failure.error.message!!.contains("secret"))
        assertEquals(listOf(a), store.all())
    }

    @Test
    fun Sync_StoreWriteFails_ReturnsTheStoreError_AndNothingIsApplied() {
        val store = InMemorySongStore(listOf(a)).also { it.failApply = true }
        val failure = LibrarySync(FakeLibrarySource(listOf(a, b)), store).run() as SyncResult.Failure
        assertTrue(failure.error is StoreError.WriteFailed)
        assertEquals(listOf(a), store.all())
    }

    @Test
    fun Sync_StoreUnreadable_ReturnsTheStoreError() {
        val store = InMemorySongStore().also { it.failRead = true }
        val failure = LibrarySync(FakeLibrarySource(listOf(a)), store).run() as SyncResult.Failure
        assertTrue(failure.error is StoreError.Corrupt)
    }

    @Test
    fun Sync_SongGoneFromPhone_IsRemoved_AndChangedFilesClearTheirFailureMark() {
        val replaced = b.copy(sizeBytes = b.sizeBytes + 1)
        val store = InMemorySongStore(listOf(a, b))
        LibrarySync(FakeLibrarySource(listOf(replaced)), store).run()
        assertEquals(listOf(replaced), store.all())
        assertEquals(listOf(b.key), store.clearedFailures)
    }
}
