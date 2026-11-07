package app.songplayer.core.sleep

import app.songplayer.core.Clock

sealed interface SleepState {
    data object Idle : SleepState

    /** Counting down; [endsAtMs] is on the clock that ignores date changes. */
    data class Running(val endsAtMs: Long) : SleepState

    /** "End of song": playback pauses when the song that is playing ends. */
    data object EndOfSong : SleepState
}

/**
 * The sleep timer (R-14, Technical Design 5.6): 15, 30, 45 or 60 minutes, after which playback pauses, or "End of song",
 * which pauses when the song that is playing ends. The end time is read from the clock that counts real time, not the date,
 * so setting the phone's date or time zone cannot move it. The state lives in memory only; a killed app has no timer
 * afterwards (S-5).
 */
class SleepTimer(private val clock: Clock) {
    val choices: List<Int> = ALLOWED

    var state: SleepState = SleepState.Idle
        private set

    /** Starts (or restarts) the timer; false and no change when [minutes] is not one of the [choices]. */
    fun start(minutes: Int): Boolean {
        if (minutes !in choices) return false
        state = SleepState.Running(clock.elapsedMs() + minutes * MINUTE_MS)
        return true
    }

    /** Playback will pause when the song that is playing ends; replaces a running timer. */
    fun startEndOfSong() {
        state = SleepState.EndOfSong
    }

    fun cancel() {
        state = SleepState.Idle
    }

    /** The time left while running, never negative; null otherwise. */
    fun leftMs(): Long? = (state as? SleepState.Running)?.let { (it.endsAtMs - clock.elapsedMs()).coerceAtLeast(0) }

    /** True once, when a running timer's time is up: the timer is then over and the caller pauses playback. */
    fun expire(): Boolean {
        val running = state as? SleepState.Running ?: return false
        if (clock.elapsedMs() < running.endsAtMs) return false
        state = SleepState.Idle
        return true
    }

    /** The song has ended and playback has paused: an "End of song" timer is over. */
    fun songEnded() {
        if (state == SleepState.EndOfSong) state = SleepState.Idle
    }

    companion object {
        val ALLOWED = listOf(15, 30, 45, 60)

        private const val MINUTE_MS = 60_000L
    }
}
