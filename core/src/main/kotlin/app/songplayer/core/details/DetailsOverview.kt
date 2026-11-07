package app.songplayer.core.details

/** What the Song details screen says about the lookup (UX Design 4.10, states H3 and H4). */
sealed interface DetailsState {
    /** This build has no usable key: "Song details isn't available in this build." */
    data object Unavailable : DetailsState

    /** The switch is off. */
    data object Off : DetailsState

    /** Songs are being looked up now. */
    data class Running(val checked: Int, val total: Int) : DetailsState

    /** Songs wait for Wi-Fi ("Only on Wi-Fi" is on and the phone is on mobile data). */
    data class WaitingForWifi(val remaining: Int) : DetailsState

    /** The phone has no internet; the lookup continues when it is back. */
    data class Offline(val remaining: Int) : DetailsState

    /** The service asked us to wait; tried again tomorrow. */
    data object AskedToWait : DetailsState

    /** Every song that needed a lookup has one; [held] of them wait for the user's review. */
    data class Done(val held: Int) : DetailsState

    /** No song lacks a title, artist or album. */
    data object NothingToLookUp : DetailsState
}

object DetailsOverview {
    /**
     * [checked] songs have a decision, [remaining] still need a lookup, [held] of the decided ones wait for review.
     * The order matters: a missing key beats everything, then the switch, then the daily pause, then the connection.
     */
    fun state(
        keyConfigured: Boolean,
        enabled: Boolean,
        wifiOnly: Boolean,
        connected: Boolean,
        unmetered: Boolean,
        pausedUntilMs: Long,
        nowMs: Long,
        checked: Int,
        remaining: Int,
        held: Int,
    ): DetailsState = when {
        !keyConfigured -> DetailsState.Unavailable
        !enabled -> DetailsState.Off
        remaining == 0 -> if (checked == 0) DetailsState.NothingToLookUp else DetailsState.Done(held)
        nowMs < pausedUntilMs -> DetailsState.AskedToWait
        !connected -> DetailsState.Offline(remaining)
        wifiOnly && !unmetered -> DetailsState.WaitingForWifi(remaining)
        else -> DetailsState.Running(checked, checked + remaining)
    }

    /** The banner shows while more songs wait for review than the user last dismissed. */
    fun bannerVisible(held: Int, dismissedAt: Int): Boolean = held > dismissedAt

    /** The dismissal count to keep: never above the number now held, so a song held later shows the banner again. */
    fun dismissalToKeep(held: Int, dismissedAt: Int): Int = minOf(held, dismissedAt)
}
