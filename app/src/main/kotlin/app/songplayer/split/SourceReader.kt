package app.songplayer.split

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.songplayer.core.split.ByteSource
import java.io.FileInputStream

/**
 * Random-access reading of the file being split, through a file descriptor opened for reading only (Technical Design 5.2). The original is
 * never changed. A read that fails throws an `IOException`; the caller decides what that means (the analysis: unreadable; the writing: the
 * original changed).
 */
class SourceReader(context: Context, mediaId: Long, volume: String) : ByteSource, AutoCloseable {
    private val descriptor = context.contentResolver.openFileDescriptor(
        ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), mediaId), "r",
    ) ?: throw java.io.FileNotFoundException()
    private val input: FileInputStream
    override val length: Long

    init {
        try {
            input = FileInputStream(descriptor.fileDescriptor)
            length = descriptor.statSize
        } catch (e: Throwable) {
            descriptor.close()   // do not leak the descriptor when the rest of the set-up fails
            throw e
        }
    }

    override fun read(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
        if (offset >= length) return -1
        return input.channel.read(java.nio.ByteBuffer.wrap(buffer, off, len), offset)
    }

    override fun close() = descriptor.close()
}
