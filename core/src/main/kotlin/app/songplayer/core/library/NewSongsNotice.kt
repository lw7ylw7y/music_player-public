package app.songplayer.core.library

/**
 * The "N new songs found" message waiting to be shown (R-22). Each sync adds the songs it found; the Library
 * takes the count when it is on screen, and then the same songs are never announced again. It lives in memory:
 * songs found by an earlier sync are already in the cache, so a later sync does not find them again.
 */
class NewSongsNotice {
    private var pending = 0

    @Synchronized
    fun add(count: Int) {
        pending += count
    }

    @Synchronized
    fun take(): Int {
        val count = pending
        pending = 0
        return count
    }
}
