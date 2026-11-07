package app.songplayer.split

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import app.songplayer.core.errors.SplitError
import app.songplayer.core.split.ByteSource
import app.songplayer.core.split.GapFinder
import app.songplayer.core.split.LoudnessSource
import app.songplayer.core.split.Mp3Index
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The true loudness of stretches of the original, from Android's own decoder (MediaExtractor and MediaCodec, no library): the RMS of the mono
 * mix in dB for each [GapFinder.HOP] seconds, as the command-line tool computes it. No seek is used: the frame index names the frame that plays
 * at the start of a stretch, and only the bytes from [PRIME_FRAMES] frames before it are given to the decoder, so the time of every sample
 * is known exactly (a frame is 1152 or 576 samples) however coarse the file's own seek table is. One decoder stays open for all the stretches.
 * Blocking: call it from a background thread, and [close] when done.
 */
class PcmLoudness(private val index: Mp3Index, private val source: ByteSource, private val isCancelled: () -> Boolean = { false }) : LoudnessSource, AutoCloseable {
    private val starts = index.startTimes()
    private var codec: MediaCodec? = null
    private var channels = 0
    private var float = false

    /** The last frame that starts at or before [t]. */
    private fun frameAt(t: Double): Int {
        var lo = 0
        var hi = index.count - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    override fun decodeDb(startSec: Double, steps: Int): FloatArray {
        val first = max(index.first, frameAt(startSec) - PRIME_FRAMES)
        val last = minOf(index.count - 1, frameAt(startSec + steps * GapFinder.HOP) + 1)
        val byteStart = index.offset[first].toLong()
        val byteEnd = index.offset[last].toLong() + index.frameLength(last)
        val rate = index.sampleRate(first)
        val out = FloatArray(steps)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(RangeDataSource(source, byteStart, byteEnd))
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw SplitError.Unreadable()
            extractor.selectTrack(track)
            val codec = codec ?: MediaCodec.createDecoderByType(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(extractor.getTrackFormat(track), null, null, 0); it.start(); this.codec = it
            }
            codec.flush()
            channels = if (index.isMono(first)) 1 else 2
            var filled = 0                                                       // steps finished
            var inStep = 0                                                       // samples in the step being built
            var sum = 0.0
            var skip = Math.round((startSec - starts[first]) * rate)             // samples between the first byte given and the requested start
            val stepSamples = Math.round(rate * GapFinder.HOP).toInt()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (filled < steps) {
                if (isCancelled()) throw java.util.concurrent.CancellationException()
                var moved = false
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    moved = true
                }
                val o = codec.dequeueOutputBuffer(info, if (moved) 0L else 10_000L)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    idle = 0
                } else if (o >= 0) {
                    idle = 0
                    val buffer = codec.getOutputBuffer(o)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val frames = (if (float) info.size / 4 else info.size / 2) / channels
                    for (f in 0 until frames) {
                        if (filled >= steps) break
                        var mono = 0.0
                        for (c in 0 until channels) mono += if (float) buffer.getFloat().toDouble() else buffer.getShort() / 32768.0
                        mono /= channels
                        if (skip > 0) { skip--; continue }
                        sum += mono * mono
                        if (++inStep == stepSamples) {
                            out[filled++] = (20 * log10(max(sqrt(sum / stepSamples), 1e-5))).toFloat()
                            inStep = 0; sum = 0.0
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (!moved && ++idle > MAX_IDLE_ROUNDS) {
                    throw SplitError.Unreadable()           // the decoder is stuck
                }
            }
            return if (filled == steps) out else out.copyOf(filled)
        } catch (e: SplitError) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: IOException) {
            throw SplitError.SourceChanged()
        } catch (_: RuntimeException) {
            throw SplitError.Unreadable()
        } finally {
            extractor.release()
        }
    }

    override fun close() {
        try { codec?.stop() } catch (_: RuntimeException) {}
        try { codec?.release() } catch (_: RuntimeException) {}
        codec = null
    }

    /** A byte range of the original as a file of its own, so the extractor starts at a frame and needs no seek. */
    private class RangeDataSource(private val source: ByteSource, private val start: Long, private val end: Long) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= end - start) return -1
            return source.read(start + position, buffer, offset, minOf(size.toLong(), end - start - position).toInt())
        }

        override fun getSize(): Long = end - start

        override fun close() {}
    }

    companion object {
        /** Frames given to the decoder before the requested one, so its bit reservoir is full (the main data of a frame may start in the frames before it). */
        const val PRIME_FRAMES = 3
        private const val MAX_IDLE_ROUNDS = 500
    }
}
