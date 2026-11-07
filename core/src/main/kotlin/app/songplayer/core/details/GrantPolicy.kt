package app.songplayer.core.details

/**
 * When the app asks Android for the write grant for songs whose details wait to be written (Technical Design 5.8). Plain numbers, so the
 * rules that stop it from asking over and over are tested on the JVM: a song that can never be written must not keep the app asking.
 */
object GrantPolicy {
    /** One request lists at most this many songs (a longer list would be too large for Android to hand over). */
    const val SONGS_PER_REQUEST = 200

    /**
     * Whether to ask when the app comes to the front. [noAccess] songs were refused for access in the last flush; [lastAskedCount] is how many
     * were refused when it last asked (null: never). It asks again only when the number changed, so songs that stay refused after a grant
     * (another volume, a damaged write, anything the grant does not cover) cause no loop of requests.
     */
    fun askOnResume(noAccess: Int, filesGranted: Boolean, grantInFlight: Boolean, lastAskedCount: Int?): Boolean =
        noAccess > 0 && filesGranted && !grantInFlight && noAccess != lastAskedCount

    /** After a request was granted and the songs written: another round only while the last one wrote something and some are still refused. */
    fun anotherRound(wrote: Int, noAccessLeft: Int): Boolean = wrote > 0 && noAccessLeft > 0

    /** Songs for the next request: those not asked for yet first (so stuck songs cannot starve the others), then the rest, at most [max]. */
    fun nextBatch(waiting: List<String>, alreadyAsked: Set<String>, max: Int = SONGS_PER_REQUEST): List<String> {
        val sorted = waiting.sorted()
        return (sorted.filter { it !in alreadyAsked } + sorted.filter { it in alreadyAsked }).take(max)
    }
}
