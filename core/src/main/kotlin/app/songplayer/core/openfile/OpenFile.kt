package app.songplayer.core.openfile

import app.songplayer.core.errors.OpenFileError
import app.songplayer.core.library.SongKey

/**
 * An MP3 another app handed to this one (R-15): its address and the name to show. It is not in the library; the queue
 * holds it alone, and it is saved with the queue so that it is the last played song after a restart (UX Design D6).
 */
data class ExternalFile(val uri: String, val displayName: String) {
    /** The identity in the queue and in the saved queue: `file:` and the address, which no library key starts with. */
    val key: SongKey get() = SongKey(KEY_PREFIX + uri)

    companion object {
        const val KEY_PREFIX = "file:"

        /** The address of the file a queue key stands for, or null when the key is a library song's. */
        fun uriOf(key: SongKey): String? = key.value.takeIf { it.startsWith(KEY_PREFIX) }?.removePrefix(KEY_PREFIX)
    }
}

/** Whether the first bytes of a file look like an MP3: an ID3 tag, or a valid MPEG audio frame header (Layer III, no reserved values). */
object Mp3Sniffer {
    fun looksLikeMp3(head: ByteArray): Boolean {
        if (head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return true
        for (i in 0..head.size - 4) {
            val b1 = head[i + 1].toInt() and 0xFF
            if (head[i] != 0xFF.toByte() || (b1 and 0xE0) != 0xE0) continue
            val b2 = head[i + 2].toInt() and 0xFF
            val version = (b1 shr 3) and 3 // 1 is reserved
            val layer = (b1 shr 1) and 3 // 1 is Layer III
            val bitrate = (b2 shr 4) and 0xF // 15 is invalid
            val sampleRate = (b2 shr 2) and 3 // 3 is reserved
            if (version != 1 && layer == 1 && bitrate != 15 && sampleRate != 3) return true
        }
        return false
    }
}

/** The checks made before an opened file replaces the queue (Technical Design 5.3): the old queue stays when any fails. */
object OpenFileRules {
    private val GENERIC_TYPES = setOf("", "application/octet-stream")

    /** How many bytes of the file are read to decide; an ID3 tag with a cover can be long, but a frame is found within a few kilobytes of it. */
    const val HEAD_BYTES = 8_192

    /**
     * The error to show, or null when the file may play. [mimeType] and [displayName] are what the provider says (either
     * may be missing); [head] is the first bytes of the file, or null when it could not be opened or read.
     */
    fun check(mimeType: String?, displayName: String?, head: ByteArray?): OpenFileError? {
        val type = mimeType?.trim()?.lowercase().orEmpty()
        val mp3Type = type == "audio/mpeg" || type == "audio/mp3"
        val unknownTypeButMp3Name = type in GENERIC_TYPES && displayName?.trim()?.lowercase()?.endsWith(".mp3") == true
        if (!mp3Type && !unknownTypeButMp3Name) return OpenFileError.NotMp3()
        if (head == null || head.isEmpty()) return OpenFileError.Unreadable()
        if (!Mp3Sniffer.looksLikeMp3(head)) return OpenFileError.NotMp3()
        return null
    }

    /** The name shown as the title: the provider's name without any folder part, or the last part of the address when there is none. */
    fun displayName(provided: String?, uri: String): String {
        val name = provided?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return name ?: uri.substringAfterLast('/').ifEmpty { uri }
    }
}
