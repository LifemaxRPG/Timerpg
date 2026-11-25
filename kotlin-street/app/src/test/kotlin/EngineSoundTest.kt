package com.timerpg.street.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class EngineSoundTest {
    private fun rms(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() } / a.size).toFloat()

    @Test
    fun everyDrivableCarHasAnEngineOfItsEra() {
        val keys = listOf("alderwick-1928" to 1928, "fenmere-1938" to 1938, "northcairn-1956" to 1956, "rowanvale-1970" to 1970,
            "tallmere-1972" to 1972, "torrel-1968" to 1968, "torrel-1988" to 1988)
        val profiles = keys.map { (k, y) -> EngineProfile.forCar(k, y) }
        // each its own (the two Torrels differ), the 1928 a slow six, the 1970 a V8 that revs
        assertEquals(keys.size, profiles.toSet().size)
        assertEquals(6, profiles[0].cylinders); assertTrue(profiles[0].redline <= 3200f)
        assertEquals(8, profiles[3].cylinders); assertTrue(profiles[3].lope > profiles[2].lope)
        assertTrue(profiles[6].redline > profiles[0].redline * 2f)
        for (p in EngineProfile.ALL) if (!p.electric) {
            assertEquals(p.cylinders, p.firing.size); assertEquals(p.cylinders, p.bank.size)
            assertTrue(p.idleRpm in 400f..1000f && p.redline > p.idleRpm * 3f)
        }
        // a four-stroke fires each cylinder once in two turns
        assertEquals(40f, EngineProfile.OHV_V8.fireHz(600f), 1e-3f)
        assertEquals(EngineProfile.ELECTRIC, EngineProfile.forYear(2030, .1f))
        assertEquals(EngineProfile.VINTAGE_SIX, EngineProfile.forYear(1925))
    }

    @Test
    fun theGearboxShiftsAndTheRevsFollowTheSpeed() {
        val gears = floatArrayOf(2.45f, 1.45f, 1f)
        val fd = GearModel.finalDrive(gears, 4400f, 153f, .34f)
        val g = GearModel(gears, fd, .34f, 600f, 4400f)
        // top gear at the top speed is the redline
        assertEquals(4400f, g.rpmAt(153f / 3.6f, 3), 1f)
        var shifts = 0; var last = 1; var maxRpm = 0f
        var v = 0f
        while (v < 40f) {
            val rpm = g.update(v, .7f, .05f)
            if (g.gear != last) { assertTrue("a shift up at ${v} m/s", g.gear == last + 1); shifts++; last = g.gear }
            assertTrue(rpm in 600f..4400f)
            maxRpm = maxOf(maxRpm, rpm)
            v += .2f
        }
        assertEquals(2, shifts)
        // and back down slowing
        while (v > 1f) { g.update(v, 0f, .05f); v -= .2f }
        assertEquals(1, g.gear)
    }

    @Test
    fun theEngineStartsRunsAndStops() {
        val s = EngineState(EngineProfile.OHV_V8)
        val dt = .02f
        fun run(sec: Float, want: Boolean, can: Boolean, rpm: Float = 0f, thr: Float = 0f) { var t = 0f; while (t < sec) { s.update(dt, want, can, rpm, 1, thr, 0f); t += dt } }
        assertEquals(EngineState.Phase.OFF, s.phase)
        run(.1f, true, true)
        assertEquals(EngineState.Phase.CRANK, s.phase)
        assertTrue(s.rpm in 100f..260f)
        run(1.5f, true, true)
        assertEquals(EngineState.Phase.RUN, s.phase)
        // it catches with a flare, then settles at idle
        run(3f, true, true)
        assertEquals(600f, s.rpm, 60f)
        // the gas: the physics' revs followed, the load up
        run(1f, true, true, 3000f, 1f)
        assertTrue(s.rpm > 2800f && s.load > .9f)
        // off the gas at revs: the overrun
        run(.05f, true, true, 2500f, 0f)
        assertTrue(s.overrun)
        // switched off: it runs down to nothing
        run(3f, false, true)
        assertEquals(EngineState.Phase.OFF, s.phase)
        assertTrue(s.rpm < 50f)
        // a dead engine (no fuel): it cranks on the gas but never catches
        val d = EngineState(EngineProfile.VINTAGE_SIX)
        var t = 0f; var cranked = false
        while (t < 8f) { d.update(dt, true, false, 0f, 0, 1f, 0f); if (d.phase == EngineState.Phase.CRANK) cranked = true; assertTrue(d.phase != EngineState.Phase.RUN); t += dt }
        assertTrue(cranked && d.failed > 0)
    }

    /** Renders [sec] s of [p] following [rpmAt] (rpm, throttle by time). */
    private fun render(p: EngineProfile, sec: Float, rpmAt: (Float) -> Pair<Float, Float>): FloatArray {
        val s = EngineState(p); val e = EngineSynth(p, 7)
        val frames = (sec * Pcm.RATE).toInt(); val out = FloatArray(frames)
        var k = 0; s.startRunning(p.idleRpm)
        while (k < frames) {
            val n = minOf(960, frames - k); val (rpm, thr) = rpmAt(k / Pcm.RATE.toFloat())
            s.update(n / Pcm.RATE.toFloat(), true, true, rpm, 2, thr, rpm / 100f)
            val tmp = FloatArray(n); e.render(s, tmp, n, 1f); System.arraycopy(tmp, 0, out, k, n); k += n
        }
        return out
    }

    @Test
    fun theEnginesSoundAndFollowTheRevs() {
        for (p in EngineProfile.ALL) {
            val idle = render(p, 2f) { p.idleRpm to 0f }.copyOfRange(Pcm.RATE / 2, Pcm.RATE * 2)
            val pull = render(p, 2f) { p.redline * .7f to 1f }.copyOfRange(Pcm.RATE / 2, Pcm.RATE * 2)
            val peak = maxOf(idle.maxOf { abs(it) }, pull.maxOf { abs(it) })
            assertTrue("${p.name} peak $peak", peak < 1.01f)
            if (p.electric) { assertTrue("${p.name} whines under way", rms(pull) > .005f); continue }
            assertTrue("${p.name} idle ${rms(idle)}", rms(idle) > .01f)
            assertTrue("${p.name} louder pulling: ${rms(idle)} -> ${rms(pull)}", rms(pull) > rms(idle) * 1.3f)
        }
    }

    @Test
    fun theFiringRateIsHeard() {
        // the envelope's strongest period at idle is the firing rate (a V8 at 600 rpm: 40 a second)
        val p = EngineProfile.OHV_V8
        val a = render(p, 3f) { 600f to .3f }.copyOfRange(Pcm.RATE, Pcm.RATE * 3)
        val env = FloatArray(a.size / 48) { i -> var s = 0f; for (j in 0 until 48) s += abs(a[i * 48 + j]); s }
        val m = env.average().toFloat(); for (i in env.indices) env[i] -= m
        var best = 0; var bestV = -1f
        // lags of 15..80 Hz at 1 kHz envelope rate
        for (lag in 12..66) { var s = 0f; for (i in 0 until env.size - lag) s += env[i] * env[i + lag]; if (s > bestV) { bestV = s; best = lag } }
        val hz = 1000f / best
        // (the burble's uneven banks put energy at half the rate too: either is the engine's beat)
        assertTrue("heard $hz Hz", abs(hz - 40f) < 5f || abs(hz - 20f) < 3f)
    }

    @Test
    fun tyresRoarWithSpeedAndSquealWhenSliding() {
        fun roll(v: Float, slip: Float, surf: String = "asphalt"): Float {
            val t = TyreSynth(1); val out = FloatArray(Pcm.RATE * 2)
            var k = 0; while (k < out.size) { val n = 960; val tmp = FloatArray(n); t.render(tmp, n, 1f, v, slip, surf); System.arraycopy(tmp, 0, out, k, n); k += n }
            return rms(out.copyOfRange(Pcm.RATE, out.size))
        }
        assertTrue(roll(25f, 0f) > roll(5f, 0f) * 2f)
        assertTrue(roll(15f, 1f) > roll(15f, 0f) * 1.5f)
        // no squeal on grass
        assertTrue(roll(15f, 1f, "grass") < roll(15f, 1f) * .6f)
        assertEquals(0f, roll(0f, 0f), 1e-6f)
        // the slip: sideways, locked, spinning
        assertTrue(CarAudio.slip(5f, 15f, false, 0f, 0f, 3, .3f) > .7f)
        assertTrue(CarAudio.slip(.5f, 15f, true, 9f, 0f, 3, .3f) > .5f)
        assertTrue(CarAudio.slip(.5f, 3f, false, 0f, 1f, 1, .8f) > .3f)
        assertEquals(0f, CarAudio.slip(.5f, 15f, false, 0f, .3f, 3, .3f), 1e-6f)
    }
}
