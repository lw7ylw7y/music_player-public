package app.songplayer.core.library

/** Which addresses the player accepts for a song sent by another controller (NFR-4): one song of Android's audio index, nothing else. */
object MediaAddress {
    private val song = Regex("""content://media/external/audio/media/\d+""")

    /** `content://media/external/audio/media/<number>` exactly: no other authority, volume, path, query or fragment. */
    fun isIndexedSong(address: String?): Boolean = address != null && song.matches(address)
}
