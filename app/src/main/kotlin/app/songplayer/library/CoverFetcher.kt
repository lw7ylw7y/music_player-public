package app.songplayer.library

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asks for the embedded cover of one song at about [sizePx] pixels square. Covers are never stored by the app. */
data class CoverRequest(val mediaStoreId: Long, val sizePx: Int)

class CoverKeyer : Keyer<CoverRequest> {
    override fun key(data: CoverRequest, options: Options) = "cover:${data.mediaStoreId}:${data.sizePx}"
}

/**
 * Loads a cover through Android's own thumbnail call for audio files (Technical Design 5.5). A song without
 * art makes the call throw; Coil treats that as a failed load and the placeholder stays visible.
 */
class CoverFetcher(private val resolver: ContentResolver, private val request: CoverRequest) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, request.mediaStoreId)
        val bitmap = resolver.loadThumbnail(uri, Size(request.sizePx, request.sizePx), null)
        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<CoverRequest> {
        override fun create(data: CoverRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(resolver, data)
    }
}
