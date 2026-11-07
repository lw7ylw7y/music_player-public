package app.songplayer.core.details

import app.songplayer.core.errors.DetailsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FingerprinterTest {
    private val rate = 11025

    private fun signal(name: String, n: Int): ShortArray {
        val out = ShortArray(n)
        when (name) {
            "silence" -> {}
            "square" -> for (i in 0 until n) out[i] = (if (i % 25 < 12) 8000 else -8000).toShort()
            "saw" -> for (i in 0 until n) out[i] = ((i % 50) * 400 - 10000).toShort()
            "noise" -> noise(n).forEachIndexed { i, v -> out[i] = v.toShort() }
            "chirp" -> {
                var p = 0L
                for (i in 0 until n) {
                    p += 20 + i / 300
                    val t = (p % 65536).toInt()
                    out[i] = (if (t < 32768) t - 16384 else 49152 - t).toShort()
                }
            }
            "mix" -> {
                val c = noise(n)
                for (i in 0 until n) {
                    val a = if (i % 25 < 12) 8000 else -8000
                    val b = (i % 50) * 400 - 10000
                    out[i] = (Math.floorDiv(a, 2) + Math.floorDiv(b, 2) + Math.floorDiv(c[i], 4)).coerceIn(-32768, 32767).toShort()
                }
            }
            else -> error(name)
        }
        return out
    }

    private fun noise(n: Int): IntArray {
        var x = 12345L
        return IntArray(n) {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            ((x shr 8) % 20001).toInt() - 10000
        }
    }

    private fun fingerprint(pcm: ShortArray, sampleRate: Int = rate, channels: Int = 1): String {
        val f = Fingerprinter(sampleRate, channels)
        var i = 0
        while (i < pcm.size) { // uneven chunks on purpose
            val len = minOf(pcm.size - i, 7001 * channels)
            f.consume(pcm.copyOfRange(i, i + len), len)
            i += len
        }
        return f.finish()
    }

    private fun vectors(): List<Triple<String, Int, String>> =
        javaClass.getResourceAsStream("/app/songplayer/core/details/fpcalc-vectors.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { l -> l.split("|").let { Triple(it[0], it[1].toInt(), it[2]) } }

    @Test
    fun Fingerprinter_SyntheticVectors_EqualFpcalc() {
        // Expected strings come from the official fpcalc 1.6.2 on the same raw signals (Technical Design 5.8).
        for ((name, seconds, expected) in vectors()) {
            assertEquals(name, expected, fingerprint(signal(name, seconds * rate)))
        }
    }

    @Test
    fun Fingerprinter_Deterministic_SameInputSameOutput() {
        val pcm = signal("mix", 12 * rate)
        assertEquals(fingerprint(pcm), fingerprint(pcm))
    }

    @Test
    fun Fingerprinter_TooShort_IsNotDecodable() {
        try {
            fingerprint(signal("noise", 9 * rate))
            fail()
        } catch (e: DetailsError.NotDecodable) {
            assertEquals("DETAILS_NOT_DECODABLE", e.code)
        }
        try { fingerprint(ShortArray(0)); fail() } catch (_: DetailsError.NotDecodable) {}
    }

    @Test
    fun Fingerprinter_LongAudio_StopsAtTheLimit() {
        val f = Fingerprinter(rate, 1)
        val chunk = signal("noise", 10 * rate)
        var fed = 0
        while (!f.isFull) { f.consume(chunk, chunk.size); fed++ }
        assertEquals(12, fed)
        f.consume(chunk, chunk.size) // ignored
        f.finish()
    }

    @Test
    fun Resampler_RatesAndChannels_ToMono11025() {
        fun tone(rateIn: Int, channels: Int, seconds: Int, hz: Double): List<Short> {
            val pcm = ShortArray(rateIn * seconds * channels)
            for (i in 0 until rateIn * seconds) for (c in 0 until channels)
                pcm[i * channels + c] = (10000 * sin(2 * PI * hz * i / rateIn)).toInt().toShort()
            val out = ArrayList<Short>()
            Resampler(rateIn, channels).push(pcm, pcm.size) { out.add(it) }
            return out
        }
        for ((r, ch) in listOf(44100 to 2, 48000 to 2, 22050 to 1, 11025 to 2, 8000 to 1, 96000 to 6)) {
            val out = tone(r, ch, 2, 440.0)
            assertTrue("$r/$ch length ${out.size}", abs(out.size - 2 * rate) < 40)
            // Amplitude and frequency survive: the middle of the output matches a 440 Hz tone of the same size.
            var worst = 0.0
            for (i in 2000 until 4000) worst = maxOf(worst, abs(out[i] - 10000 * sin(2 * PI * 440.0 * i / rate)))
            assertTrue("$r/$ch worst error $worst", worst < 400)
        }
    }

    @Test
    fun Resampler_AboveTheNewLimit_IsFilteredOut() {
        val out = ArrayList<Short>()
        val rateIn = 44100
        val pcm = ShortArray(rateIn) { (10000 * sin(2 * PI * 12000.0 * it / rateIn)).toInt().toShort() }
        Resampler(rateIn, 1).push(pcm, pcm.size) { out.add(it) }
        assertTrue(out.drop(500).dropLast(500).all { abs(it.toInt()) < 100 })
    }

    @Test
    fun Fingerprinter_DuplicatedStereo_EqualsMono() {
        val mono = signal("mix", 20 * rate)
        val stereo = ShortArray(mono.size * 2) { mono[it / 2] }
        assertEquals(fingerprint(mono), fingerprint(stereo, rate, 2))
    }

    @Test
    fun Resampler_ChunkBoundaries_DoNotChangeTheOutput() {
        val rateIn = 44100
        val frames = 3 * rateIn
        val pcm = ShortArray(frames * 2) { (6000 * sin(2 * PI * 300.0 * (it / 2) / rateIn) + (it * 7919 % 2001) - 1000).toInt().toShort() }
        fun run(chunks: List<Int>): List<Short> {
            val out = ArrayList<Short>()
            val r = Resampler(rateIn, 2)
            var pos = 0; var k = 0
            while (pos < frames) {
                val n = minOf(chunks[k++ % chunks.size], frames - pos)
                r.push(pcm.copyOfRange(pos * 2, (pos + n) * 2), n * 2) { out.add(it) }
                pos += n
            }
            return out
        }
        val whole = run(listOf(frames))
        assertEquals(whole, run(listOf(1, 999, 4097)))
        assertEquals(whole, run(listOf(333)))
    }

    @Test
    fun Resampler_ASplitFrame_IsRefused() {
        try { Resampler(44100, 2).push(ShortArray(3), 3) {}; fail() } catch (_: IllegalArgumentException) {}
    }
}
