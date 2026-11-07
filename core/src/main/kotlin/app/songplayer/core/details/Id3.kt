package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import app.songplayer.core.errors.NotWrittenReason

/** What to do with one text field of a song's tag. */
sealed interface FieldChange {
    /** Leave whatever the tag has. */
    data object Keep : FieldChange

    /** Replace it (and any duplicates) with this text. */
    data class Set(val text: String) : FieldChange

    /** Take it out of the tag. */
    data object Remove : FieldChange
}

/** The three fields the app writes (R-27). */
data class TagChange(
    val title: FieldChange = FieldChange.Keep,
    val artist: FieldChange = FieldChange.Keep,
    val album: FieldChange = FieldChange.Keep,
)

/** The three fields as read from a tag; null when the tag has no such frame. */
data class TagFields(val title: String?, val artist: String?, val album: String?)

/** The ten bytes that start an ID3v2 tag. [bodySize] excludes the header (and a footer, which is refused). */
class Id3Header(val major: Int, val revision: Int, val flags: Int, val bodySize: Int) {
    val totalSize: Int get() = HEADER_SIZE + bodySize
}

class Id3Frame(val id: String, val flags: Int, val data: ByteArray)

/** A parsed ID3v2.3 or 2.4 tag. [totalSize] is the header, the frames and the padding. */
class ParsedTag(val header: Id3Header, val frames: List<Id3Frame>) {
    val major: Int get() = header.major
    val totalSize: Int get() = header.totalSize
}

/** How a tag change is carried out on a file. */
sealed interface WritePlan {
    /** The new tag fits the old tag's room: these bytes replace the first bytes of the file, the length does not change. */
    class InPlace(val tag: ByteArray) : WritePlan

    /** The tag grows: [newTag] followed by the original bytes from [audioOffset] on. */
    class Rewrite(val newTag: ByteArray, val audioOffset: Int) : WritePlan
}

internal const val HEADER_SIZE = 10

/**
 * Reads and writes the title, artist and album of an MP3's ID3v2 tag without touching anything else (R-27, Technical
 * Design 5.8). Everything after the tag, the audio and an ID3v1 tag at the end, is never read or changed. A shape it does not
 * handle is refused with [DetailsError.NotWritten], never half-handled.
 */
object Id3 {
    private const val TITLE = "TIT2"
    private const val ARTIST = "TPE1"
    private const val ALBUM = "TALB"
    private val TARGETS = setOf(TITLE, ARTIST, ALBUM)

    /** Padding that a rewrite leaves, so that later edits fit in place. */
    const val REWRITE_PADDING = 2048

    /** A tag larger than this (a huge cover, or a damaged size) is not handled: it would not fit in memory on a phone. */
    const val MAX_TAG_BYTES = 16 * 1024 * 1024

    /** The tag header at the start of [head] (at least 10 bytes), or null when the file has no ID3v2 tag. */
    fun readHeader(head: ByteArray): Id3Header? {
        if (head.size < HEADER_SIZE) return null
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val sizeBytes = (6..9).map { head[it].toInt() }
        if (sizeBytes.any { it < 0 || it > 0x7F }) throw notWritten(NotWrittenReason.UnsupportedTag)
        val size = sizeBytes.fold(0) { acc, b -> (acc shl 7) or b }
        if (size > MAX_TAG_BYTES) throw notWritten(NotWrittenReason.UnsupportedTag)
        return Id3Header(head[3].toInt() and 0xFF, head[4].toInt() and 0xFF, head[5].toInt() and 0xFF, size)
    }

    /** Parses a tag. [tag] holds the whole tag: the header and [Id3Header.bodySize] bytes. */
    fun parse(header: Id3Header, tag: ByteArray): ParsedTag {
        if (header.major != 3 && header.major != 4) throw notWritten(NotWrittenReason.UnsupportedTag) // 2.2 is not handled
        // Bit 7 unsynchronisation, 6 extended header, 4 footer (2.4): none is handled. Other bits are not defined for us.
        if (header.flags != 0) throw notWritten(NotWrittenReason.UnsupportedTag)
        if (tag.size < header.totalSize) throw notWritten(NotWrittenReason.Unreadable)
        val frames = ArrayList<Id3Frame>()
        var pos = HEADER_SIZE
        val end = header.totalSize
        while (pos + HEADER_SIZE <= end) {
            if (tag[pos].toInt() == 0) break // padding starts
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) throw notWritten(NotWrittenReason.UnsupportedTag)
            val size = if (header.major == 4) synchsafe(tag, pos + 4) else bigEndian(tag, pos + 4)
            val flags = ((tag[pos + 8].toInt() and 0xFF) shl 8) or (tag[pos + 9].toInt() and 0xFF)
            val dataStart = pos + HEADER_SIZE
            if (size < 0 || dataStart.toLong() + size > end) throw notWritten(NotWrittenReason.UnsupportedTag)
            frames += Id3Frame(id, flags, tag.copyOfRange(dataStart, dataStart + size))
            pos = dataStart + size
        }
        return ParsedTag(header, frames)
    }

    /** The title, artist and album of a parsed tag (the first frame of each, or null). */
    fun fields(tag: ParsedTag?): TagFields = TagFields(text(tag, TITLE), text(tag, ARTIST), text(tag, ALBUM))

    /** What a change to [existing] (null: the file has no tag) comes to. */
    fun plan(existing: ParsedTag?, change: TagChange): WritePlan {
        val major = existing?.major ?: 3
        val changes = mapOf(TITLE to change.title, ARTIST to change.artist, ALBUM to change.album)
        existing?.frames?.forEach { f ->
            // A target frame that is compressed or encrypted cannot be read, so it is not replaced either.
            if (f.id in TARGETS && changes.getValue(f.id) !is FieldChange.Keep && hasUnreadableFlags(major, f.flags)) {
                throw notWritten(NotWrittenReason.UnsupportedTag)
            }
        }
        val out = ArrayList<ByteArray>()
        existing?.frames?.forEach { f ->
            val c = changes[f.id]
            if (c == null || c is FieldChange.Keep) out += encodeFrame(major, f.id, f.flags, f.data)
        }
        for ((id, c) in changes) if (c is FieldChange.Set) out += encodeFrame(major, id, 0, encodeText(major, c.text))
        val framesSize = out.sumOf { it.size }
        val fits = existing != null && HEADER_SIZE + framesSize <= existing.totalSize
        return if (fits) {
            val body = existing!!.totalSize - HEADER_SIZE
            WritePlan.InPlace(buildTag(major, existing.header.revision, body, out))
        } else {
            WritePlan.Rewrite(buildTag(major, existing?.header?.revision ?: 0, framesSize + REWRITE_PADDING, out), existing?.totalSize ?: 0)
        }
    }

    /** Where the audio starts in a file whose tag is [existing] (null: there is no tag). */
    fun audioStart(existing: ParsedTag?): Int = existing?.totalSize ?: 0

    /** Applies [plan] to the whole file in memory: for tests and for small files. */
    fun apply(original: ByteArray, plan: WritePlan): ByteArray = when (plan) {
        is WritePlan.InPlace -> original.copyOf().also { plan.tag.copyInto(it) }
        is WritePlan.Rewrite -> plan.newTag + original.copyOfRange(plan.audioOffset, original.size)
    }

    // ---- bytes ----------------------------------------------------------------------------------------

    private fun buildTag(major: Int, revision: Int, bodySize: Int, frames: List<ByteArray>): ByteArray {
        val out = ByteArray(HEADER_SIZE + bodySize)
        out[0] = 'I'.code.toByte(); out[1] = 'D'.code.toByte(); out[2] = '3'.code.toByte()
        out[3] = major.toByte(); out[4] = revision.toByte(); out[5] = 0
        for (i in 0..3) out[6 + i] = ((bodySize shr (7 * (3 - i))) and 0x7F).toByte()
        var pos = HEADER_SIZE
        for (f in frames) { f.copyInto(out, pos); pos += f.size }
        return out // the rest is zero: the padding
    }

    private fun encodeFrame(major: Int, id: String, flags: Int, data: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + data.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        if (major == 4) for (i in 0..3) out[4 + i] = ((data.size shr (7 * (3 - i))) and 0x7F).toByte()
        else for (i in 0..3) out[4 + i] = ((data.size shr (8 * (3 - i))) and 0xFF).toByte()
        out[8] = (flags shr 8).toByte(); out[9] = flags.toByte()
        data.copyInto(out, HEADER_SIZE)
        return out
    }

    /** ISO-8859-1 when the text fits in it; otherwise UTF-16 with a byte-order mark (2.3) or UTF-8 (2.4). */
    internal fun encodeText(major: Int, text: String): ByteArray {
        if (text.all { it.code <= 0xFF }) return byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        return if (major == 4) byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        else byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
    }

    private fun text(tag: ParsedTag?, id: String): String? {
        val data = tag?.frames?.firstOrNull { it.id == id }?.data ?: return null
        if (data.isEmpty()) return null
        val body = data.copyOfRange(1, data.size)
        val s = when (data[0].toInt()) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> when {
                body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> String(body, 2, body.size - 2, Charsets.UTF_16LE)
                body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> String(body, 2, body.size - 2, Charsets.UTF_16BE)
                else -> String(body, Charsets.UTF_16LE)
            }
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> return null
        }
        return s.substringBefore('\u0000')
    }

    // v2.3 flag bytes: 0x0080 compression, 0x0040 encryption. v2.4: 0x0008 compression, 0x0004 encryption.
    private fun hasUnreadableFlags(major: Int, flags: Int): Boolean =
        if (major == 4) flags and 0x000C != 0 else flags and 0x00C0 != 0

    private fun synchsafe(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0..3) { val x = b[at + i].toInt(); if (x < 0 || x > 0x7F) return -1; v = (v shl 7) or x }
        return v
    }

    private fun bigEndian(b: ByteArray, at: Int): Int {
        var v = 0L
        for (i in 0..3) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }

    private fun notWritten(reason: NotWrittenReason) = DetailsError.NotWritten(reason)
}
