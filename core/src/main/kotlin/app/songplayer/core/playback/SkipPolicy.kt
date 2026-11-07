package app.songplayer.core.playback

/** What to do after a song failed to play (R-12). */
sealed interface SkipDecision {
    /** Mark the song, move to the next one, say nothing. */
    data object Skip : SkipDecision

    /** Stop where playback is; nothing in the queue can be played. */
    data object Stop : SkipDecision
}

object SkipPolicy {
    /**
     * [consecutiveFailures] songs in a row have failed (this one included) in a queue of [queueSize]. Once the
     * failures reach the size of the queue every song has been tried, so playback stops; without this, repeat all over
     * a queue of unplayable songs would skip forever. The count starts again at the first song that plays.
     */
    fun next(consecutiveFailures: Int, queueSize: Int): SkipDecision =
        if (consecutiveFailures >= queueSize) SkipDecision.Stop else SkipDecision.Skip
}
