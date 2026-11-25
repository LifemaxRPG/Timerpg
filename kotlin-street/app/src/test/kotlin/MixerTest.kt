package com.timerpg.street.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MixerTest {
    private fun tone(sec: Float = 1f): Pcm {
        val n = (sec * Pcm.RATE).toInt(); val d = FloatArray(n * 2)
        for (i in 0 until n) { val v = kotlin.math.sin(i * .05f) * .5f; d[2 * i] = v; d[2 * i + 1] = v }
        return Pcm(d)
    }
    private fun lr(m: Mixer, frames: Int = 4800): Pair<Float, Float> {
        val out = FloatArray(frames * 2); m.render(out, frames)
        var l = 0.0; var r = 0.0
        for (k in 0 until frames) { l += out[2 * k] * out[2 * k]; r += out[2 * k + 1] * out[2 * k + 1] }
        return sqrt(l / frames).toFloat() to sqrt(r / frames).toFloat()
    }

    @Test
    fun aSoundIsHeardFromWhereItIs() {
        val U = Mixer.U
        // the camera looking north: a sound to the east is on the right
        val m = Mixer(); m.yawDeg = 0f
        m.play(Mixer.Voice(tone(), 1f, world = true, x = 10f * U, z = 0f))
        val (l, r) = lr(m)
        assertTrue("east on the right: $l $r", r > l * 2f)
        // turned to face east: it is ahead (both sides alike)
        val m2 = Mixer(); m2.yawDeg = 90f
        m2.play(Mixer.Voice(tone(), 1f, world = true, x = 10f * U, z = 0f))
        val (l2, r2) = lr(m2)
        assertEquals(1f, l2 / r2, .15f)
        // facing south: the east is on the left
        val m3 = Mixer(); m3.yawDeg = 180f
        m3.play(Mixer.Voice(tone(), 1f, world = true, x = 10f * U, z = 0f))
        val (l3, r3) = lr(m3)
        assertTrue(l3 > r3 * 2f)
        // farther is fainter
        assertTrue(Mixer.attenuation(40f, 4f) < Mixer.attenuation(10f, 4f) && Mixer.attenuation(2f, 4f) == 1f)
        assertTrue(Mixer.distanceCutoff(200f) < Mixer.distanceCutoff(10f))
    }

    @Test
    fun theVoiceLimitKeepsTheImportantOnes() {
        val m = Mixer()
        repeat(Mixer.MAX_VOICES + 10) { m.play(Mixer.Voice(tone(2f), .1f, priority = 1)) }
        m.play(Mixer.Voice(tone(2f), .5f, priority = 9))
        lr(m, 960)
        assertEquals(Mixer.MAX_VOICES, m.voiceCount)
        assertTrue(m.stolen >= 11)
    }

    @Test
    fun theStreetDipsUnderAVoiceAndFollowsTheVolumes() {
        fun level(duck: Boolean, amb: Float = 1f, muffle: Float = 0f): Float {
            val m = Mixer(); m.volume[Mixer.AMB] = amb; m.ducked = duck; m.muffle = muffle
            val bed = LoopSource(Mixer.AMB, tone(2f)); bed.target = 1f; m.add(bed)
            repeat(80) { lr(m, 960) }
            return lr(m, 4800).first
        }
        val open = level(false)
        assertTrue(open > .05f)
        assertTrue(level(true) < open * .5f)
        assertTrue(level(false, amb = .3f) < open * .4f)
        assertTrue(level(false, muffle = 1f) < open * .7f)
    }

    /**
     * The mixing's cost on this machine (a desktop JVM, not the S25): a busy street (every bed, the far traffic at its
     * most, the driven car pulling, steps), printed as the share of real time it takes.
     */
    @Test
    fun theMixIsCheap() {
        val m = Mixer()
        val synth = Synth()
        for (b in SoundRules.BEDS) m.add(LoopSource(Mixer.AMB, if (b in SoundRules.SYNTH_BEDS) null else tone(3f)).also { s ->
            s.target = .4f; if (b in SoundRules.SYNTH_BEDS) s.synth = { buf, n, g -> synth.render(b, buf, n, g) } })
        val traffic = TrafficSource(); traffic.perMinute = 600f; m.add(traffic)
        val cars = CarAudio { null }; m.add(cars)
        val f = cars.feed("mine") { EngineProfile.MUSCLE_V8 }
        f.wantRun = true; f.rpm = 4000f; f.throttle = 1f; f.speed = 20f; f.gear = 2
        val others = (0 until 3).map { i -> cars.feed("parked$i") { EngineProfile.OHV_V8 }.also { o -> o.x = (i + 1) * 300f; o.speed = 3f } }
        val steps = StepSynth()
        val buf = FloatArray(960 * 2)
        // warm up (the JIT), then 30 s of blocks
        repeat(200) { m.render(buf, 960) }
        val blocks = 30 * 50
        val t0 = System.nanoTime()
        for (i in 0 until blocks) {
            if (i % 25 == 0) m.play(Mixer.Voice(Pcm(steps.make("concrete").let { s -> FloatArray(s.size * 2) { s[it / 2] } }), .6f))
            m.render(buf, 960)
        }
        val ns = System.nanoTime() - t0
        val share = ns / (blocks * 20e6)
        println("SOUND COST: %.2f ms per 20 ms block (%.1f %% of one core, desktop JVM), traffic %d, voices %d".format(ns / blocks / 1e6, share * 100, traffic.count, m.voiceCount))
        assertTrue("the mix takes ${share * 100} % of real time", share < .35)
        assertTrue(others.isNotEmpty())
    }
}
