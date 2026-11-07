package app.songplayer.core.split

/** Random-access reading of the file being split, so `core` needs no `android.*` (the app reads through a file descriptor). */
interface ByteSource {
    val length: Long

    /** Reads up to [len] bytes at [offset] into [buffer] at [off]; returns how many were read, or -1 at the end. */
    fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
        if (offset >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - offset).toInt()
        System.arraycopy(bytes, offset.toInt(), buffer, off, n)
        return n
    }
}
