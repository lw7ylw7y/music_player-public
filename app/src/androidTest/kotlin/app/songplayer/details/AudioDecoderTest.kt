package app.songplayer.details

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.errors.DetailsError
import app.songplayer.playback.AppInForeground
import app.songplayer.playback.TestSongs
import app.songplayer.playback.audioUri
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L3: the decoder on seeded MP3 files in the media index. */
@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {
    companion object {
        @BeforeClass @JvmStatic fun openApp() = AppInForeground.start()
        @AfterClass @JvmStatic fun closeApp() = AppInForeground.stop()
    }

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val toClean = mutableListOf<() -> Unit>()

    @After
    fun cleanUp() = toClean.reversed().forEach { runCatching { it() } }

    private fun seed(frames: Int = 40, content: ByteArray? = null) =
        TestSongs.seed(context, "H5", frames = frames, toClean = toClean, fileName = "h5-${System.nanoTime()}.mp3", content = content)

    @Test
    fun AudioDecoder_SeededMp3_ProducesPcmOfTheRightLength() {
        // 460 frames of 1,152 samples at 44.1 kHz are 12.02 s; a silent song gives a silence fingerprint of that many steps.
        val result = AudioDecoder(context).fingerprint(audioUri(seed(frames = 460).mediaStoreId))
        // The container has no length field; Android estimates it from the file size and bit rate, so it may round down to 11.
        assertTrue("duration ${result.durationS}", result.durationS in 11..12)
        assertTrue(result.fingerprint, result.fingerprint.startsWith("AQAA"))
        // The header holds the number of 32-bit steps: 132,480 samples at 11,025 Hz give 95 frames, 91 rows and 76 steps; a decoder may
        // trim some start-up samples, hence the margin.
        val header = android.util.Base64.decode(result.fingerprint, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        val steps = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        assertTrue("steps $steps", steps in 74..78)
    }

    @Test
    fun AudioDecoder_TooShortSong_IsNotDecodable() {
        try { AudioDecoder(context).fingerprint(audioUri(seed(frames = 40).mediaStoreId)); fail() } catch (_: DetailsError.NotDecodable) {}
    }

    @Test
    fun AudioDecoder_CorruptFile_IsNotDecodable() {
        val song = seed(content = ByteArray(6000) { 0x55 })
        try { AudioDecoder(context).fingerprint(audioUri(song.mediaStoreId)); fail() } catch (_: DetailsError.NotDecodable) {}
    }
}
