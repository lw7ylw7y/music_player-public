package app.songplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import app.songplayer.core.playback.QueueEdit
import app.songplayer.core.playback.ShuffleRules
import java.util.Random

/**
 * Gives the player the shuffle order that R-7 and R-13 ask for (Technical Design 5.3). The rules are in `core`
 * ([ShuffleRules], [QueueEdit]); this class only reads the player's order and sets the new one. Call it on the
 * thread the player lives on (the service's main thread).
 */
@OptIn(UnstableApi::class)
class QueueShuffler(
    private val player: ExoPlayer,
    private val random: Random = Random(),
    /** Called after the order has been set, so that the saved queue gets the final order and not the player's first guess. */
    private val onOrderSet: () -> Unit = {},
) {

    /** Shuffle turned on, or a new queue set while it is on: the song that is playing comes first, then all the others once. */
    fun currentFirst() {
        val count = player.mediaItemCount
        if (count == 0) return
        setOrder(ShuffleRules.currentFirst(count, player.currentMediaItemIndex, random))
        onOrderSet()
    }

    /** "Play next": right after the song that is playing, with shuffle on or off. */
    fun playNext(item: MediaItem) {
        val current = player.currentMediaItemIndex
        val wasEmpty = player.mediaItemCount == 0
        val at = QueueEdit.playNextIndex(current, player.mediaItemCount)
        player.addMediaItem(at, item)
        setOrder(ShuffleRules.moveAfter(playOrder(), item = at, anchor = current))
        if (wasEmpty) player.prepare()
        onOrderSet()
    }

    /** "Add to queue": at the end, after everything that is already queued. */
    fun addToQueue(item: MediaItem) {
        val wasEmpty = player.mediaItemCount == 0
        val at = QueueEdit.addToQueueIndex(player.mediaItemCount)
        player.addMediaItem(at, item)
        setOrder(ShuffleRules.moveToEnd(playOrder(), at))
        // With nothing queued before, the player is idle; make it ready so that Play starts it.
        if (wasEmpty) player.prepare()
        onOrderSet()
    }

    private fun setOrder(order: IntArray) {
        player.setShuffleOrder(DefaultShuffleOrder(order, random.nextLong()))
    }

    private fun playOrder(): IntArray = shuffledOrder(player.currentTimeline)
}

/** The queue positions in the order they play when shuffle is on (the player keeps this order even when it is off). */
internal fun shuffledOrder(timeline: Timeline): IntArray {
    val order = ArrayList<Int>(timeline.windowCount)
    var index = timeline.getFirstWindowIndex(/* shuffleModeEnabled = */ true)
    while (index != C.INDEX_UNSET) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled = */ true)
    }
    return order.toIntArray()
}
