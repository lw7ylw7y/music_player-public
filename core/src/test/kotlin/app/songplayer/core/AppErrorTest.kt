package app.songplayer.core

import app.songplayer.core.errors.AppError
import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason
import app.songplayer.core.errors.LibraryError
import app.songplayer.core.errors.OpenFileError
import app.songplayer.core.errors.PlaybackError
import app.songplayer.core.errors.SplitError
import app.songplayer.core.errors.StoreError
import app.songplayer.core.errors.VoiceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    private val all: List<AppError> = listOf(
        LibraryError.PermissionDenied(), LibraryError.QueryFailed(),
        PlaybackError.SourceUnreadable(),
        OpenFileError.NotMp3(), OpenFileError.Unreadable(),
        StoreError.WriteFailed(), StoreError.Corrupt(), StoreError.ReadFailed(),
        VoiceError.NoMatch(),
        DetailsError.NotWritten(NotWrittenReason.NoAccess), DetailsError.WriteFailed(),
        SplitError.Unreadable(), SplitError.TooLarge(), SplitError.CutsTooClose(), SplitError.FolderNotAllowed("Music/x/"), SplitError.NameInUse("x"),
        SplitError.NoSpace(), SplitError.SourceChanged(), SplitError.WriteFailed(),
    )

    @Test
    fun AppError_Message_IsTheCodeOnly() {
        all.forEach { assertEquals(it.code, it.message) }
    }

    @Test
    fun AppError_Codes_AreUniqueAndUpperSnakeCase() {
        assertEquals(all.size, all.map { it.code }.toSet().size)
        all.forEach { assertTrue(it.code, it.code.matches(Regex("[A-Z][A-Z0-9_]+"))) }
    }
}
