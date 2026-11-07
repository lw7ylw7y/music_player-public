package app.songplayer.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Gives Media3 the cover of a song for the notification and the lock screen (Technical Design 5.5): the same
 * `loadThumbnail` call the lists use. A song with no art makes the call throw; the future then fails and Media3
 * shows the notification without a picture.
 */
@OptIn(UnstableApi::class)
class CoverBitmapLoader(private val resolver: ContentResolver) : BitmapLoader {
    // A daemon thread, so the loader never keeps the process alive and needs no shutdown.
    private val executor = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { task -> Thread(task, "cover-loader").apply { isDaemon = true } },
    )

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = executor.submit(Callable {
        requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size)) { "not an image" }
    })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit(Callable {
        resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
    })

    private companion object {
        // The notification and lock screen scale this down; 512 px is sharp on the largest of them.
        const val THUMBNAIL_PX = 512
    }
}
