package app.songplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import app.songplayer.core.library.Song
import app.songplayer.core.openfile.ExternalFile
import app.songplayer.core.openfile.OpenFileRules
import app.songplayer.core.resume.ResumeRules
import app.songplayer.core.resume.ResumeStore
import java.util.Random

/** The saved queue turned into what the player needs, already checked against the songs the library has now. */
class Restored(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffleOn: Boolean,
    val repeatMode: Int,
    /** For each queue position, where it plays when shuffled; null when shuffle is off. */
    val shuffleOrder: IntArray?,
)

object ResumeRestore {
    /**
     * Reads the saved state and checks it against [library] ([ResumeRules.validate]). Blocking: call it off the main
     * thread. Null when nothing is saved or none of the saved songs is left.
     */
    fun read(store: ResumeStore, library: List<Song>): Restored? {
        val state = store.load() ?: return null
        val songs = library.associateBy { it.key }
        val valid = ResumeRules.validate(state, songs.keys) ?: return null
        val source = valid.source?.let { name -> PlayingFrom.entries.firstOrNull { it.name == name } }
        val items = valid.queue.map { entry ->
            val uri = ExternalFile.uriOf(entry.songKey)
            if (uri != null) {
                ExternalFile(uri, OpenFileRules.displayName(entry.displayName, uri)).toMediaItem()
            } else {
                songs.getValue(entry.songKey).toMediaItem(source = if (entry.added) null else source, addedToQueue = entry.added)
            }
        }
        val order = if (valid.shuffleOn) {
            IntArray(valid.queue.size).also { o -> valid.queue.forEachIndexed { position, e -> o[e.shuffleRank!!] = position } }
        } else {
            null
        }
        return Restored(items, valid.currentIndex, valid.positionMs, valid.shuffleOn, valid.repeatMode, order)
    }

    /**
     * Gives the saved queue to the player, paused and not prepared, at the saved position (Technical Design 5.4). Does
     * nothing if the player already has a queue (a song was tapped before the restore finished). Call it on the
     * player's thread.
     */
    @OptIn(UnstableApi::class)
    fun apply(player: ExoPlayer, restored: Restored) {
        if (player.mediaItemCount != 0) return
        player.setMediaItems(restored.items, restored.index, restored.positionMs)
        player.repeatMode = restored.repeatMode
        player.shuffleModeEnabled = restored.shuffleOn
        // Turning shuffle on makes the service's listener set a fresh random order at once (the listener runs inside the
        // call above); the saved order is set after it and replaces it.
        restored.shuffleOrder?.let { player.setShuffleOrder(DefaultShuffleOrder(it, Random().nextLong())) }
        player.playWhenReady = false
    }
}
