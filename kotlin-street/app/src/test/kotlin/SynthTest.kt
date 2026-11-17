package com.timerpg.street.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The live ambience makes sound (not silence, not clipping); with SYNTH_OUT set it also writes WAVs to listen to. */
class SynthTest {
    private fun wav(f: File, st: FloatArray) {
        val n = st.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(Pcm.RATE).putInt(Pcm.RATE * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (x in st) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }

    @Test
    fun bedsSoundAndStayInRange() {
        val out = System.getenv("SYNTH_OUT")?.let { File(it).apply { mkdirs() } }
        for (name in listOf("rain_light", "rain_heavy", "wind", "crickets_night", "night_quiet", "city_day")) {
            val s = Synth(3)
            val frames = Pcm.RATE * 8
            val buf = FloatArray(frames * 2)
            var k = 0
            while (k < frames) { val n = minOf(960, frames - k); val tmp = FloatArray(n * 2); s.render(name, tmp, n, .8f); System.arraycopy(tmp, 0, buf, k * 2, n * 2); k += n }
            val rms = kotlin.math.sqrt(buf.sumOf { (it * it).toDouble() } / buf.size)
            val peak = buf.maxOf { kotlin.math.abs(it) }
            assertTrue("$name rms $rms", rms > .003)
            assertTrue("$name peak $peak", peak < 1.5f)
            out?.let { wav(File(it, "$name.wav"), buf) }
        }
    }
}
