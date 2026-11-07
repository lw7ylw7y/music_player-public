package app.songplayer.core.openfile

import app.songplayer.core.errors.OpenFileError
import app.songplayer.core.library.TestMp3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFileTest {
    private val mp3 = TestMp3.bytes(frames = 3).copyOf(2_048)

    @Test
    fun Mp3Sniffer_RealFrames_AreAnMp3() {
        assertTrue(Mp3Sniffer.looksLikeMp3(mp3))
    }

    @Test
    fun Mp3Sniffer_Id3TagFirst_IsAnMp3_EvenBeforeAnyFrame() {
        assertTrue(Mp3Sniffer.looksLikeMp3("ID3".toByteArray() + ByteArray(20)))
    }

    @Test
    fun Mp3Sniffer_FrameAfterSomeLeadingBytes_IsFound() {
        assertTrue(Mp3Sniffer.looksLikeMp3(ByteArray(300) + mp3))
    }

    @Test
    fun Mp3Sniffer_TextEmptyAndReservedHeaders_AreNot() {
        assertFalse(Mp3Sniffer.looksLikeMp3("This is a plain text file, not music.".toByteArray()))
        assertFalse(Mp3Sniffer.looksLikeMp3(ByteArray(0)))
        assertFalse("reserved version", Mp3Sniffer.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0x90.toByte(), 0)))
        assertFalse("reserved layer", Mp3Sniffer.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x90.toByte(), 0)))
        assertFalse("bad bitrate", Mp3Sniffer.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0)))
        assertFalse("reserved sample rate", Mp3Sniffer.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x9C.toByte(), 0)))
    }

    @Test
    fun Intent_OpenFile_ParsedToOneItemQueue() {
        val file = ExternalFile("content://provider/doc/42", "Morning.mp3")
        assertEquals("file:content://provider/doc/42", file.key.value)
        assertEquals("content://provider/doc/42", ExternalFile.uriOf(file.key))
        assertNull("a library key is not a file", ExternalFile.uriOf(app.songplayer.core.library.SongKey("external_primary:Music/a.mp3")))
    }

    @Test
    fun OpenFileRules_AudioMpegWithMp3Bytes_IsAccepted() {
        assertNull(OpenFileRules.check("audio/mpeg", "a.mp3", mp3))
        assertNull(OpenFileRules.check("audio/mp3", "a.mp3", mp3))
    }

    @Test
    fun OpenFileRules_ProviderThatDoesNotKnowTheType_IsAcceptedByTheNameEnding() {
        assertNull(OpenFileRules.check(null, "Song.MP3", mp3))
        assertNull(OpenFileRules.check("application/octet-stream", "Song.mp3", mp3))
        assertTrue(OpenFileRules.check(null, "Song.txt", mp3) is OpenFileError.NotMp3)
        assertTrue(OpenFileRules.check(null, null, mp3) is OpenFileError.NotMp3)
    }

    @Test
    fun OpenFileRules_OtherTypes_AreNotMp3() {
        assertTrue(OpenFileRules.check("text/plain", "notes.txt", "hello".toByteArray()) is OpenFileError.NotMp3)
        assertTrue(OpenFileRules.check("audio/flac", "a.flac", mp3) is OpenFileError.NotMp3)
    }

    @Test
    fun OpenFileRules_Mp3NameButTextInside_IsNotMp3() {
        assertTrue(OpenFileRules.check("audio/mpeg", "fake.mp3", "just words".toByteArray()) is OpenFileError.NotMp3)
    }

    @Test
    fun OpenFileRules_CannotBeRead_IsUnreadable() {
        assertTrue(OpenFileRules.check("audio/mpeg", "a.mp3", null) is OpenFileError.Unreadable)
        assertTrue(OpenFileRules.check("audio/mpeg", "a.mp3", ByteArray(0)) is OpenFileError.Unreadable)
    }

    @Test
    fun OpenFileRules_DisplayName_FallsBackToAGenericName_AndNeverContainsAPath() {
        assertEquals("Morning.mp3", OpenFileRules.displayName("Morning.mp3", "content://x/1"))
        assertEquals("1", OpenFileRules.displayName(null, "content://x/1"))
        assertEquals("1", OpenFileRules.displayName("  ", "content://x/1"))
        assertEquals("song.mp3", OpenFileRules.displayName("/storage/emulated/0/Download/song.mp3", "content://x/9"))
    }
}
