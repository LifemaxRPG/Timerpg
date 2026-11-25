package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Bells made live: a church or clock tower's bell (its partials tuned as a bell founder's: hum, prime, tierce, quint, nominal). */
object BellSynth {
    /** One strike of a tower bell whose nominal is [nominal] Hz (stereo Pcm data, about [seconds] long). */
    fun tower(nominal: Float = 520f, seconds: Float = 6.5f, seed: Long = 9): FloatArray {
        val r = Rng(seed)
        val n = (Pcm.RATE * seconds).toInt()
        val d = FloatArray(n * 2)
        // partial (ratio to the nominal), amplitude, decay (s)
        val parts = arrayOf(
            floatArrayOf(.25f, .55f, 6f), floatArrayOf(.5f, .7f, 4.5f), floatArrayOf(.6f, .45f, 3.2f), floatArrayOf(.75f, .3f, 2.6f),
            floatArrayOf(1f, .8f, 2.4f), floatArrayOf(1.25f, .3f, 1.6f), floatArrayOf(1.5f, .25f, 1.2f), floatArrayOf(2f, .2f, .8f), floatArrayOf(2.66f, .12f, .5f),
        )
        val det = FloatArray(parts.size) { 1f + r.range(-.002f, .002f) }
        val strike = OnePole(3000f)
        for (i in 0 until n) {
            val t = i / Pcm.RATE.toFloat()
            var v = 0f
            for (p in parts.indices) {
                val f = nominal * parts[p][0] * det[p]
                // (each partial beats slowly against its pair: the bell's warble)
                val beat = 1f + .12f * sin(2f * PI.toFloat() * (.7f + p * .23f) * t)
                v += sin(2f * PI.toFloat() * f * t) * parts[p][1] * exp(-t / parts[p][2]) * beat
            }
            v *= (1f - exp(-t / .002f))
            v += strike.lp(r.noise()) * exp(-t / .012f) * .6f
            d[2 * i] = v * .16f; d[2 * i + 1] = v * .16f
        }
        return d
    }
}

/**
 * The police's siren far off, while they are out looking (docs/SOUND.md "Ambience"), of its era: a bell's clang before
 * sirens, a motor siren's rising and falling wail (its rotor's harmonics), or the electronic wail turning to a yelp. It
 * sounds in bursts from where the nearest officer is, moving a little.
 */
class SirenSource : Mixer.Source(Mixer.AMB) {
    @Volatile var style = "electronic"
    @Volatile var on = false
    @Volatile var x = 0f
    @Volatile var z = 0f
    private val r = Rng(17)
    private var t = 0f
    private var ph = 0f; private var ph2 = 0f
    private var g = 0f
    private var burst = 0f
    private var rest = .5f
    private val tmp = FloatArray(3)
    private val lp = OnePole(2500f)
    private val clang = Biquad().bandpass(1800f, 6f)
    private var clangEnv = 0f

    override fun render(m: Mixer, out: FloatArray, frames: Int) {
        val dt = frames / m.rate.toFloat()
        // while officers are on a call near (SoundRules.sirenFor): long runs of 20-40 s with short gaps of 2-6 s (the car
        // behind a block, the driver cutting it at a junction); it starts within a second of the call
        if (on) {
            if (burst > 0f) burst -= dt else { rest -= dt; if (rest <= 0f) { burst = r.range(20f, 40f); rest = r.range(2f, 6f) } }
        } else { burst = (burst - dt).coerceAtMost(0f); rest = .5f }
        val target = if (on && burst > 0f) .5f else 0f
        if (g < 1e-4f && target == 0f) return
        if (!m.spatial(x, z, 40f, tmp)) return
        val cut = tmp[2]
        for (k in 0 until frames) {
            g += (target - g) * .0004f
            t += 1f / m.rate
            var v: Float
            when (style) {
                "bell" -> {
                    // a hand bell rung on the wagon: a clang three times a second
                    if ((t * 3f) % 1f < 1f / m.rate * 3f) clangEnv = 1f
                    v = clang.run(r.noise()) * clangEnv * 3f + sin(ph) * clangEnv * .4f
                    ph += 2f * PI.toFloat() * 1820f / m.rate
                    clangEnv *= .9996f
                }
                "mechanical" -> {
                    // the rotor spins up and down over ~6 s: a sawtooth-rich wail from 250 to 950 Hz
                    val cyc = (t / 6f) % 1f
                    val f = 250f + 700f * sin(PI.toFloat() * cyc).let { it * it }
                    ph += 2f * PI.toFloat() * f / m.rate; if (ph > 6.2831855f) ph -= 6.2831855f
                    v = (sin(ph) + .5f * sin(2f * ph) + .33f * sin(3f * ph) + .25f * sin(4f * ph)) * .45f
                }
                else -> {
                    // wail (a 4 s sweep 650-1450 Hz) for 9 s, then the yelp (fast sweeps) for 5
                    val seg = t % 14f
                    val f = if (seg < 9f) 650f + 800f * (.5f - .5f * cos(2f * PI.toFloat() * seg / 4f)) else 700f + 750f * ((seg * 3.3f) % 1f)
                    ph += 2f * PI.toFloat() * f / m.rate; if (ph > 6.2831855f) ph -= 6.2831855f
                    // (a square-ish tone through a horn speaker)
                    v = soft(sin(ph) * 2.2f) * .6f
                }
            }
            v = lp.lp(v)
            out[2 * k] += v * g * tmp[0]; out[2 * k + 1] += v * g * tmp[1]
        }
    }
}

/**
 * The far traffic (docs/SOUND.md "Ambience"): cars of the era passing on the streets around, each a made-live engine
 * of a kind the year has ([EngineProfile.fleet]) cruising at town speed in a gear an automatic would choose, with its tyres'
 * roar, heard from where it is (behind the buildings: far, dark, Doppler-shifted as it goes by). How many a minute is the
 * rules' ([SoundRules.Mix.trafficPerMinute]); at most [MAX] at once.
 */
class TrafficSource(seed: Long = 23) : Mixer.Source(Mixer.AMB) {
    @Volatile var perMinute = 0f
    @Volatile var year = 2025
    private val r = Rng(seed)

    private inner class Pass(val profile: EngineProfile) {
        val state = EngineState(profile)
        val synth = EngineSynth(profile, (r.next() * 1e6).toLong())
        val tyres = TyreSynth((r.next() * 1e6).toLong())
        val gears = GearModel(if (profile.cylinders >= 6 && profile.redline < 4000f) floatArrayOf(2.8f, 1.6f, 1f) else floatArrayOf(3.2f, 2f, 1.4f, 1f),
            0f, .33f, profile.idleRpm, profile.redline).let { g ->
                GearModel(g.gears, GearModel.finalDrive(g.gears, profile.redline, if (profile.redline < 3500f) 100f else 170f, .33f), .33f, profile.idleRpm, profile.redline)
            }
        var x = 0f; var z = 0f; var vx = 0f; var vz = 0f
        var life = 0f
        var doppler = 1f
        val lp = OnePole(1500f)
        val speed: Float get() = sqrt(vx * vx + vz * vz)
    }

    private val passes = ArrayList<Pass>()
    private var mono = FloatArray(0)
    private val tmp = FloatArray(3)

    override fun render(m: Mixer, out: FloatArray, frames: Int) {
        val dt = frames / m.rate.toFloat()
        if (passes.size < MAX && r.next() < perMinute / 60f * dt) spawn(m)
        if (passes.isEmpty()) return
        if (mono.size < frames) mono = FloatArray(frames)
        val it = passes.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            p.x += p.vx * Mixer.U * dt; p.z += p.vz * Mixer.U * dt
            val throttle = .25f + .1f * sin(p.life * .7f)
            p.state.update(dt, true, true, p.gears.update(p.speed, throttle, dt), p.gears.gear, throttle, p.speed)
            if (p.life > 40f) { it.remove(); continue }
            if (!m.spatial(p.x, p.z, 14f, tmp)) continue
            val dx = p.x - m.lx; val dz = p.z - m.lz
            val d = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-3f)
            val radial = (p.vx * dx + p.vz * dz) / d
            p.doppler = 343f / (343f + radial)
            java.util.Arrays.fill(mono, 0, frames, 0f)
            p.synth.render(p.state, mono, frames, .8f, p.doppler)
            p.tyres.render(mono, frames, 1.1f, p.speed, 0f, "asphalt", p.doppler)
            // (the buildings between: darker than the open air alone would make it)
            p.lp.set(minOf(tmp[2] * m.rate / 6.28f, 2600f))
            for (k in 0 until frames) {
                val x = p.lp.lp(mono[k])
                out[2 * k] += x * tmp[0] * 1.6f; out[2 * k + 1] += x * tmp[1] * 1.6f
            }
        }
    }

    /** A car on a straight street past the listener: [OFFSET_M] off to a side, from far on one hand to far on the other. */
    private fun spawn(m: Mixer) {
        val fleet = EngineProfile.fleet(year)
        if (fleet.isEmpty()) return
        var pick = r.next(); var prof = fleet.last().first
        for ((p, w) in fleet) { if (pick < w) { prof = p; break }; pick -= w }
        val p = Pass(prof)
        val ang = r.next() * 2f * PI.toFloat()
        val ux = cos(ang); val uz = sin(ang)
        val off = r.range(OFFSET_M[0], OFFSET_M[1]) * (if (r.next() < .5f) -1f else 1f)
        val v = r.range(8f, 15f) * (if (prof.redline < 3500f) .75f else 1f)
        val start = -160f
        p.x = m.lx + (-uz * off + ux * start) * Mixer.U; p.z = m.lz + (ux * off + uz * start) * Mixer.U
        p.vx = ux * v; p.vz = uz * v
        p.state.startRunning(prof.idleRpm * 2f)
        passes += p
    }

    val count: Int get() = passes.size

    companion object {
        const val MAX = 3
        val OFFSET_M = floatArrayOf(22f, 120f)
    }
}
