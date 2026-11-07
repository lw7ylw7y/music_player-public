package app.songplayer.library

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.songplayer.core.errors.LibraryError
import app.songplayer.core.library.TestMp3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the emulator's real media index, using files this test creates and deletes itself
 * (a fixture, never the user's music).
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSourceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seeded = mutableListOf<Uri>()
    private val folder = "Music/SongPlayerTest/"

    private fun seed(name: String, mime: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "could not create the fixture $name" }
        seeded += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    @After
    fun removeFixtures() {
        seeded.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun MediaStoreSource_SeededFiles_ListsOnlyMp3() {
        seed("songplayer_test_one.mp3", "audio/mpeg", TestMp3.bytes())
        seed("songplayer_test_two.mp3", "audio/mpeg", TestMp3.bytes(frames = 80))
        seed("songplayer_test_not_mp3.wav", "audio/x-wav", ByteArray(64))

        val songs = MediaStoreSource(context).scan()
        val ours = songs.filter { it.key.value.contains(folder) }

        assertEquals(setOf("songplayer_test_one.mp3", "songplayer_test_two.mp3"), ours.map { it.fileName }.toSet())
        assertTrue("every listed song is on a named volume", ours.all { it.key.value.startsWith("external_primary:") })
        assertTrue("sizes come from the media index", ours.all { it.sizeBytes > 0 })
        assertFalse("a non-MP3 must not be listed", songs.any { it.fileName.endsWith(".wav") && it.key.value.contains(folder) })
    }

    @Test
    fun MediaStoreSource_PermissionNotGranted_ThrowsPermissionDenied() {
        val source = MediaStoreSource(context, hasPermission = { false })
        try {
            source.scan()
            throw AssertionError("expected LibraryError.PermissionDenied")
        } catch (e: LibraryError.PermissionDenied) {
            // expected
        }
    }
}
