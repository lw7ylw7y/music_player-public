package app.songplayer.core.playback

import java.util.Random

/** Where the player puts a song added by "Play next" and "Add to queue" (R-13). Media3 owns the queue itself. */
object QueueEdit {
    /** "Play next": right after the song that is playing; at the end when nothing is playing (current is -1) or it is the last. */
    fun playNextIndex(current: Int, size: Int): Int = (current + 1).coerceIn(0, size)

    /** "Add to queue": at the end. */
    fun addToQueueIndex(size: Int): Int = size
}

/**
 * The order in which a shuffled queue plays, as a list of positions in the queue (Technical Design 5.3). Media3
 * shuffles on its own but gives no way to say "the song I am on comes first" or "this one plays next", both of
 * which R-7 and R-13 need, so the player is given the order built here. Every function returns a new array that is
 * a permutation of 0 until size.
 */
object ShuffleRules {
    /**
     * A random order of the queue in which [current] comes first, so that turning shuffle on plays every song once
     * (R-7). With no valid current song it is a plain random order.
     */
    fun currentFirst(size: Int, current: Int, random: Random): IntArray {
        val rest = (0 until size).filter { it != current }.toMutableList()
        for (i in rest.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val t = rest[i]; rest[i] = rest[j]; rest[j] = t
        }
        return if (current in 0 until size) intArrayOf(current) + rest.toIntArray() else rest.toIntArray()
    }

    /** [order] with [item] moved to the place right after [anchor]; everything else keeps its relative order. */
    fun moveAfter(order: IntArray, item: Int, anchor: Int): IntArray {
        if (item == anchor || item !in order || anchor !in order) return order.copyOf()
        val without = order.filter { it != item }
        val at = without.indexOf(anchor) + 1
        return (without.take(at) + item + without.drop(at)).toIntArray()
    }

    /** [order] with [item] moved to the end. */
    fun moveToEnd(order: IntArray, item: Int): IntArray {
        if (item !in order) return order.copyOf()
        return (order.filter { it != item } + item).toIntArray()
    }
}
