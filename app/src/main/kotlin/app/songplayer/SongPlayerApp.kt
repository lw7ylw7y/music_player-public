package app.songplayer

import android.app.Application
import app.songplayer.core.log.LogCode
import app.songplayer.core.widget.WidgetState
import app.songplayer.library.CoverFetcher
import app.songplayer.library.CoverKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

class SongPlayerApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, listOf(LogcatSink(), ErrorLogSink({ container.errorLog })))
        container.log.event(LogCode.APP_START)
        // A new process knows nothing about a song that was playing in the last one: the widget must not keep showing "playing".
        container.widgetUpdater.onChange(WidgetState.Empty)
        // Opening the database (and rebuilding a damaged one) takes a moment; do it now, away from the main thread, so
        // the first screen and the playback service find it ready.
        Thread({ container.database }, "db-open").apply { isDaemon = true }.start()
        // A tag write that was cut off by a kill is put right now, whichever way the process started (the car can start the service alone).
        Thread({ runCatching { container.tagFileWriter.recover() } }, "details-recover").apply { isDaemon = true }.start()
        // The background lookup is queued again at every start (the unique work keeps one copy), so work lost by a reboot or a data clear comes back.
        Thread({ runCatching { container.queueDetailsIfOn() } }, "details-queue").apply { isDaemon = true }.start()
    }

    /** Cover thumbnails come from a bounded memory cache of 24 MB. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(CoverKeyer())
                add(CoverFetcher.Factory(contentResolver))
            }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(COVER_CACHE_BYTES).build() }
            .build()

    private companion object {
        const val COVER_CACHE_BYTES = 24L * 1024 * 1024
    }
}
