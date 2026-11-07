package app.songplayer.core

/**
 * The two clocks the rules may be given, so a test can move either. [elapsedMs] counts real time since the phone
 * started and ignores changes to the date, time or time zone; [wallMs] is the date and time as the user sees them and
 * can jump.
 */
interface Clock {
    fun elapsedMs(): Long

    fun wallMs(): Long
}
