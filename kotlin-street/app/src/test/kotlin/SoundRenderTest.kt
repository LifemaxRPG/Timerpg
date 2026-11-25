package com.timerpg.street.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The comparison renders for the owner (docs/SOUND.md "Listening"): with SOUND_OUT set, writes WAVs of each engine through
 * a drive (start, idle, a blip, pulling away through the gears, lifting off, braking, idle, off), made live and (with
 * SOUND_LOOPS: the recorded engine loops as raw mono float) from the recordings; the far traffic of three eras; the
 * sirens; the tower bell; the made-live footsteps. Skipped otherwise.
 */
class SoundRenderTest {
    private val out = System.getenv("SOUND_OUT")?.let { File(it).apply { mkdirs() } }

    private fun wav(f: File, st: FloatArray) {
        val n = st.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(Pcm.RATE).putInt(Pcm.RATE * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (x in st) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }

    /** A drive in [p]'s car: the pedal by time, the speed from a simple car (its power by the gear), the revs from the gearbox. */
    private fun drive(p: EngineProfile, bank: EngineLoopBank?): FloatArray {
        val sec = 16f
        val m = Mixer()
        val cars = CarAudio { bank }; cars.recorded = bank != null; m.add(cars)
        val f = cars.feed("car") { p }
        val gears = if (p.redline < 3700f) floatArrayOf(2.8f, 1.6f, 1f) else floatArrayOf(3.0f, 1.9f, 1.35f, 1f)
        val top = if (p.redline < 3200f) 100f else if (p.redline < 4500f) 150f else 190f
        val g = GearModel(gears, GearModel.finalDrive(gears, p.redline, top, .33f), .33f, p.idleRpm, p.redline)
        val frames = (sec * Pcm.RATE).toInt()
        val buf = FloatArray(frames * 2)
        var v = 0f; var k = 0
        while (k < frames) {
            val t = k / Pcm.RATE.toFloat()
            // the pedal: start at 0.3 s, a blip at 3 s, pull away 4.5-10 s, lift off, brake 12-13.5 s, off at 15 s
            val thr = when { t in 3f..3.35f -> 1f; t in 4.5f..10f -> if (t < 6f) .8f else 1f; else -> 0f }
            val brake = t in 12f..13.6f
            val accel = if (thr > 0f && t > 4.4f) thr * (4.2f - .09f * v) / (g.gear * .55f + .45f) else -(.25f + .01f * v * v / 10f) - (if (brake) 6f else 0f)
            v = (v + accel * .02f).coerceAtLeast(0f)
            val rpm = if (v < .5f) (if (thr > 0f) p.idleRpm + (p.redline * .6f) * thr else 0f) else g.update(v, thr, .02f)
            f.wantRun = t > .3f && t < 15f; f.canRun = true
            f.rpm = rpm; f.gear = g.gear; f.throttle = thr; f.speed = v
            f.slip = CarAudio.slip(0f, v, brake, if (brake) 6.5f else 0f, thr, g.gear, rpm / p.redline)
            val n = minOf(960, frames - k)
            val tmp = FloatArray(n * 2); m.render(tmp, n); System.arraycopy(tmp, 0, buf, k * 2, n * 2); k += n
        }
        return buf
    }

    private fun loadBank(dir: File): EngineLoopBank? {
        fun raw(n: String): Pcm? = File(dir, "$n.f32").takeIf { it.exists() }?.let { f ->
            val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val d = FloatArray(bb.remaining() * 2); var i = 0; while (bb.hasRemaining()) { val x = bb.get(); d[i++] = x; d[i++] = x }; Pcm(d)
        }
        val idle = listOf("idle_v8", "idle_old", "idle_car", "idle_four").mapNotNull { n -> raw(n)?.let { n to it } }.toMap()
        val load = (0 until 6).mapNotNull { i -> raw("load_$i")?.let { it to EngineLoopBank.LOAD_HZ[i] } }
        return if (idle.isEmpty() && load.isEmpty()) null else EngineLoopBank(idle, load)
    }

    @Test
    fun renderTheComparisons() {
        val dir = out
        assumeTrue("SOUND_OUT not set", dir != null)
        dir!!
        val bank = System.getenv("SOUND_LOOPS")?.let { loadBank(File(it)) }
        for (p in EngineProfile.ALL) {
            val name = p.name.replace(' ', '_').replace("-", "")
            wav(File(dir, "engine_live_$name.wav"), drive(p, null))
            if (bank != null && !p.electric) wav(File(dir, "engine_loops_$name.wav"), drive(p, bank))
        }
        // the far traffic of three eras (30 s each)
        for (year in listOf(1925, 1970, 2025)) {
            val m = Mixer(); val tr = TrafficSource(year.toLong()); tr.year = year; tr.perMinute = 8f * EngineProfile.trafficShare(year) + 2f; m.add(tr)
            val frames = Pcm.RATE * 30; val buf = FloatArray(frames * 2)
            var k = 0; while (k < frames) { val tmp = FloatArray(1920); m.render(tmp, 960); System.arraycopy(tmp, 0, buf, k * 2, 1920); k += 960 }
            wav(File(dir, "traffic_$year.wav"), buf)
        }
        // the sirens (from 150 m) and the tower bell (from 80 m)
        for (style in listOf("bell", "mechanical", "electronic")) {
            val m = Mixer(); val s = SirenSource(); s.style = style; s.on = true; s.x = 150f * Mixer.U; m.add(s)
            val frames = Pcm.RATE * 75; val buf = FloatArray(frames * 2)
            var k = 0; while (k < frames) { val tmp = FloatArray(1920); m.render(tmp, 960); System.arraycopy(tmp, 0, buf, k * 2, 1920); k += 960 }
            wav(File(dir, "siren_$style.wav"), buf)
        }
        run {
            val m = Mixer(); val bell = Pcm(BellSynth.tower())
            for (i in 0 until 3) m.play(Mixer.Voice(bell, 1f, Mixer.AMB, world = true, x = 80f * Mixer.U, z = -30f * Mixer.U, refM = 60f))
            val frames = Pcm.RATE * 8; val buf = FloatArray(frames * 2); m.render(buf, frames)
            wav(File(dir, "bell_tower.wav"), buf)
        }
        // set E's footsteps: walking then running on each surface (about 4 s each)
        run {
            val synth = StepSynth(3); val pick = Footsteps.Picker(1)
            val parts = ArrayList<FloatArray>()
            for (surf in Footsteps.SURFACES) for (running in listOf(false, true)) {
                val sec = 3.2f; val frames = (sec * Pcm.RATE).toInt(); val buf = FloatArray(frames * 2)
                val gap = if (running) .34f else .52f
                var t = .1f
                while (t < sec - .5f) {
                    val st = pick.next(surf, 1, running); val s = synth.make(surf, running)
                    val at = (t * Pcm.RATE).toInt()
                    val gl = st.gain * (if (st.pan < 0f) 1f else .8f); val gr = st.gain * (if (st.pan > 0f) 1f else .8f)
                    // (resampled as the mixer plays it: each output frame read between two of the step's)
                    var j = 0
                    while (at + j < frames) {
                        val x = j * st.rate; val i = x.toInt(); if (i + 1 >= s.size) break
                        val v = s[i] + (s[i + 1] - s[i]) * (x - i)
                        buf[2 * (at + j)] += v * gl; buf[2 * (at + j) + 1] += v * gr; j++
                    }
                    t += gap * (1f + (Math.random().toFloat() - .5f) * .06f)
                }
                parts += buf
            }
            val all = FloatArray(parts.sumOf { it.size }); var o = 0
            for (p in parts) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
            wav(File(dir, "footsteps_E_synth.wav"), all)
        }
    }
}
