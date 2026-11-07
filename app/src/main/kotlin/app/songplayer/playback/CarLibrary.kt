package app.songplayer.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.songplayer.AppContainer
import app.songplayer.R
import app.songplayer.core.browse.BrowseAlbum
import app.songplayer.core.browse.BrowseArtist
import app.songplayer.core.browse.BrowseFolder
import app.songplayer.core.browse.BrowseLabels
import app.songplayer.core.browse.BrowseLibrary
import app.songplayer.core.browse.BrowseNode
import app.songplayer.core.browse.BrowsePlaylist
import app.songplayer.core.browse.BrowseTrack
import app.songplayer.core.errors.StoreError
import app.songplayer.core.log.LogCode
import app.songplayer.core.playlist.PlaylistView
import app.songplayer.core.text.Albums
import app.songplayer.core.text.Artists
import app.songplayer.core.text.SortOrder
import app.songplayer.core.text.sorted
import app.songplayer.core.text.sortedByName

/**
 * The library as the car sees it (R-16): reads the saved songs, albums, artists and playlists, sorted as the Library sorts
 * them, and gives them to `core`'s [app.songplayer.core.browse.BrowseTree]. Blocking: call it off the main thread. A
 * playlist that cannot be read is left out, so a damaged `playlists.db` does not take the car's other tabs with it.
 */
class CarLibrary(private val container: AppContainer, private val context: Context) {
    val keyer get() = container.keyer

    val appName: String get() = context.getString(R.string.app_name)

    val labels: BrowseLabels
        get() = BrowseLabels(
            playlists = context.getString(R.string.tab_playlists),
            albums = context.getString(R.string.tab_albums),
            artists = context.getString(R.string.tab_artists),
            songs = context.getString(R.string.tab_songs),
            emptyMessage = context.getString(R.string.car_empty_message),
            songCount = { n -> context.resources.getQuantityString(R.plurals.song_count, n, n.toString()) },
        )

    private var cached: BrowseLibrary? = null
    private var cachedAtMs = 0L

    /**
     * The library for a browse request. One browse (the root, a tab, a list, then a tap) asks several times within a few
     * seconds and each read sorts and groups everything, so the answer is kept for [KEEP_MS]; a tap on a song that a
     * sync removed in that time finds nothing and is refused. Without music access the car sees an empty library, whatever
     * the saved cache holds (the songs could not be played).
     */
    @Synchronized
    fun snapshot(): BrowseLibrary {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < KEEP_MS) return it }
        return read().also { cached = it; cachedAtMs = now }
    }

    private fun read(): BrowseLibrary {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) return BrowseLibrary(emptyList(), emptyList(), emptyList(), emptyList())
        val songs = container.songStore.indexed().sorted(SortOrder.Title)
        val albums = Albums.group(songs, container.keyer).sorted(SortOrder.Title)
            .map { BrowseAlbum(it.key, it.displayName, it.artistLine, it.sortName, it.songs.map { s -> s.song }) }
        val artists = Artists.group(songs, container.keyer).sortedByName()
            .map { BrowseArtist(it.key, it.displayName, it.sortName, it.songs.map { s -> s.song }) }
        val playlists = try {
            val all = songs.map { it.song }
            container.playlistStore.playlists().map { summary ->
                val items = PlaylistView.items(container.playlistStore.entries(summary.playlist.id), all)
                BrowsePlaylist(summary.playlist.id, summary.playlist.name, PlaylistView.queue(items))
            }
        } catch (e: StoreError) {
            container.log.event(LogCode.PLAYLIST_READ_FAILED, if (e is StoreError.Corrupt) 1 else 0)
            emptyList()
        }
        return BrowseLibrary(songs, albums, artists, playlists)
    }
}

private const val KEEP_MS = 5_000L

/** The car's item for a node: a folder is something to open, a song something to play (its queue is made when it is tapped). */
fun BrowseNode.toCarItem(): MediaItem = when (this) {
    is BrowseFolder -> MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()
    is BrowseTrack -> MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(song.displayArtist)
                .setAlbumTitle(song.displayAlbum)
                .setDurationMs(song.durationMs)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
}
