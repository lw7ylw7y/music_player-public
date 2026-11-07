package app.songplayer.details

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.songplayer.core.details.Fingerprinter
import app.songplayer.core.errors.DetailsError
import java.io.IOException
import java.nio.ByteOrder

/** What one song gives the lookup: its fingerprint and its whole length in seconds, and the time spent computing the fingerprint (the rest of the call is decoding). */
class SongFingerprint(val fingerprint: String, val durationS: Int, val fingerprintMs: Long)

/**
 * Decodes a song with Android's own decoders (MediaExtractor and MediaCodec, no library) and feeds the PCM, at the song's own
 * rate and channel count, to a [Fingerprinter] until it is full (the first 120 seconds). Blocking: call it from a background thread.
 *
 * A file that cannot be opened or decoded gives [DetailsError.NotDecodable] (skipped for good). A decoder that cannot start, or
 * memory that runs out, gives [DetailsError.Unavailable] (the song is tried again later).
 */
class AudioDecoder(private val context: Context) {

    fun fingerprint(uri: Uri, isCancelled: () -> Boolean = { false }): SongFingerprint {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (_: IOException) {
                throw DetailsError.NotDecodable()
            } catch (_: SecurityException) {
                throw DetailsError.Unavailable() // access lost, not a bad file: try again later
            } catch (_: RuntimeException) {
                throw DetailsError.NotDecodable()
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DetailsError.NotDecodable()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationS = if (format.containsKey(MediaFormat.KEY_DURATION)) (format.getLong(MediaFormat.KEY_DURATION) / 1_000_000L).toInt() else 0
            // The lookup needs the whole song's length; a file that does not say it cannot be looked up.
            if (durationS <= 0) throw DetailsError.NotDecodable()
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw DetailsError.NotDecodable()
            codec = try {
                MediaCodec.createDecoderByType(mime).also { codec = it; it.configure(format, null, null, 0); it.start() }
            } catch (_: IOException) {
                throw DetailsError.Unavailable()
            } catch (_: IllegalArgumentException) {
                throw DetailsError.NotDecodable() // no decoder for this format on this phone
            } catch (_: MediaCodec.CodecException) {
                throw DetailsError.Unavailable()
            } catch (_: IllegalStateException) {
                throw DetailsError.Unavailable()
            }
            val (text, fingerprintNs) = try {
                decode(extractor, codec, format, isCancelled)
            } catch (_: OutOfMemoryError) {
                throw DetailsError.Unavailable()
            } catch (e: MediaCodec.CodecException) {
                // A decoder taken away by another app is not a bad file; only the rest counts as unreadable.
                if (e.isTransient || e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED ||
                    e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE
                ) throw DetailsError.Unavailable()
                throw DetailsError.NotDecodable()
            } catch (_: RuntimeException) {
                throw DetailsError.NotDecodable() // a sample too big for its buffer, a format without a rate, a codec in a bad state
            }
            return SongFingerprint(text, durationS, fingerprintNs / 1_000_000)
        } finally {
            try { codec?.stop() } catch (_: RuntimeException) {}
            try { codec?.release() } catch (_: RuntimeException) {}
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, startFormat: MediaFormat, isCancelled: () -> Boolean): Pair<String, Long> {
        var rate = startFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = startFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var fingerprinter = Fingerprinter(rate, channels)
        var fingerprintNs = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idle = 0
        var scratch = ShortArray(0)
        while (!outputDone && !fingerprinter.isFull) {
            if (isCancelled()) throw DetailsError.Unavailable()
            // Feed every input buffer that is free, then take every output that is ready; wait (briefly) only when neither moved.
            var fed = false
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                fed = true
            }
            var drained = false
            while (!outputDone && !fingerprinter.isFull) {
                val out = codec.dequeueOutputBuffer(info, if (fed || drained) 0L else TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    if (newRate != rate || newChannels != channels) {
                        // The stream's real format differs from the container's (for example HE-AAC): start the fingerprint again with the right one.
                        rate = newRate; channels = newChannels
                        fingerprinter = Fingerprinter(rate, channels)
                    }
                    drained = true
                } else if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val count = if (float) info.size / 4 else info.size / 2
                    val usable = count - count % channels
                    if (scratch.size < usable) scratch = ShortArray(usable)
                    if (float) {
                        val f = buffer.asFloatBuffer()
                        for (i in 0 until usable) scratch[i] = (f.get(i) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    } else {
                        buffer.asShortBuffer().get(scratch, 0, usable)
                    }
                    val t0 = System.nanoTime()
                    fingerprinter.consume(scratch, usable)
                    fingerprintNs += System.nanoTime() - t0
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    drained = true
                } else {
                    break // nothing ready (or the old "buffers changed" note)
                }
            }
            idle = if (fed || drained) 0 else idle + 1
            if (idle > MAX_IDLE_ROUNDS) throw DetailsError.NotDecodable()
        }
        val t0 = System.nanoTime()
        val text = fingerprinter.finish()
        fingerprintNs += System.nanoTime() - t0
        return text to fingerprintNs
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** About 5 to 10 seconds with nothing moving: the decoder is stuck, so the song counts as unreadable. */
        const val MAX_IDLE_ROUNDS = 500
    }
}
