package com.timerpg.street.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The recorded engine mode's loops (Settings "Engines: recorded loops"; docs/SOUND.md): an idle recording per family and
 * load loops measured at their firing rates, each played faster or slower for the engine's speed and crossfaded by it
 * (how most games do engines). Mono [Pcm]s (left channel used).
 */
class EngineLoopBank(val idle: Map<String, Pcm>, val load: List<Pair<Pcm, Float>>) {
    /** The idle recording for [p]: a V8's, an old engine's, a four's, else any. */
    fun idleFor(p: EngineProfile): Pcm? = idle[when {
        p.cylinders == 8 && p.idleRpm < 560f -> "idle_old"
        p.cylinders == 8 -> "idle_v8"
        p.cylinders == 6 -> "idle_old"
        p.cylinders == 4 && p.redline > 6000f -> "idle_four"
        else -> "idle_car"
    }] ?: idle.values.firstOrNull()

    companion object {
        /** The load loops' firing rates (Hz), measured from the files (tools: autocorrelation of the low band). */
        val LOAD_HZ = floatArrayOf(43f, 60f, 65f, 70f, 73f, 77f)
        /** The idle recordings' firing rates (Hz), as their families idle. */
        val IDLE_HZ = mapOf("idle_v8" to 46f, "idle_old" to 26f, "idle_car" to 25f, "idle_four" to 27f)
    }
}

/** An engine from [EngineLoopBank] recordings: the idle crossfaded into the load loops as it revs and pulls. */
class LoopEngine(val profile: EngineProfile, private val bank: EngineLoopBank) {
    private var idlePos = 0.0
    private val loadPos = DoubleArray(bank.load.size)
    private var level = 0f
    private val lp = OnePole(4000f)

    fun render(s: EngineState, out: FloatArray, frames: Int, gain: Float, doppler: Float = 1f) {
        val on = s.phase == EngineState.Phase.RUN || s.phase == EngineState.Phase.STOPPING || s.phase == EngineState.Phase.CRANK
        val target = if (on && s.rpm > 60f) 1f else 0f
        if (target == 0f && level < 1e-4f) return
        val idle = bank.idleFor(profile)
        val fire = profile.fireHz(s.rpm) * doppler
        val idleHz = EngineLoopBank.IDLE_HZ.entries.firstOrNull { bank.idle[it.key] === idle }?.value ?: 30f
        val idleRate = (fire / idleHz).coerceIn(.4f, 2.2f).toDouble()
        // the idle gives way to the load loops as it revs or pulls
        val revs = ((s.rpm - profile.idleRpm) / (profile.redline - profile.idleRpm)).coerceIn(0f, 1f)
        val wIdle = (1f - revs * 3f).coerceIn(0f, 1f) * (1f - .5f * s.load)
        // two neighbouring load loops by the firing rate, equal-power crossfaded
        val hz = EngineLoopBank.LOAD_HZ
        val n = bank.load.size
        val pos = ((revs * (n - 1))).coerceIn(0f, n - 1f)
        val a = pos.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val t = pos - a
        val ga = kotlin.math.cos(t * Math.PI.toFloat() / 2f) * (1f - wIdle); val gb = kotlin.math.sin(t * Math.PI.toFloat() / 2f) * (1f - wIdle)
        lp.set(1200f + 6000f * (.3f + .7f * s.load) * (.4f + revs))
        val loudness = (.45f + .55f * s.load) * gain
        for (k in 0 until frames) {
            level += (target - level) * .002f
            var x = 0f
            if (idle != null && wIdle > .001f) { x += sample(idle, idlePos) * wIdle; idlePos = wrap(idlePos + idleRate, idle.frames) }
            if (n > 0) {
                val pa = bank.load[a].first; val pb = bank.load[b].first
                x += sample(pa, loadPos[a]) * ga; loadPos[a] = wrap(loadPos[a] + (fire / hz[a.coerceAtMost(hz.size - 1)]).coerceIn(.3f, 3f), pa.frames)
                if (b != a) { x += sample(pb, loadPos[b]) * gb; loadPos[b] = wrap(loadPos[b] + (fire / hz[b.coerceAtMost(hz.size - 1)]).coerceIn(.3f, 3f), pb.frames) }
            }
            out[k] += lp.lp(x) * loudness * level
        }
    }

    private fun sample(p: Pcm, pos: Double): Float {
        val i = pos.toInt(); val j = if (i + 1 >= p.frames) 0 else i + 1; val t = (pos - i).toFloat()
        return p[2 * i] + (p[2 * j] - p[2 * i]) * t
    }
    private fun wrap(p: Double, len: Int) = if (p >= len) p - len else p
}

/**
 * Every car's sound (docs/SOUND.md "Engines"): the game writes each car's state into its [Feed] each frame; on the audio
 * thread each car's [EngineState] follows it and the nearest few are heard: the engine (made live, or from recordings in
 * the recorded mode), the tyres rolling and squealing, all from where the car is, Doppler-shifted as it passes.
 */
class CarAudio(private val bank: () -> EngineLoopBank?) : Mixer.Source(Mixer.CAR) {
    /** A car's state as the game last saw it. */
    class Feed(val profile: EngineProfile) {
        @Volatile var x = 0f; @Volatile var z = 0f
        /** Velocity (m/s, world x and z) and speed along its forward (m/s). */
        @Volatile var vx = 0f; @Volatile var vz = 0f; @Volatile var speed = 0f
        @Volatile var rpm = 0f; @Volatile var gear = 0
        @Volatile var throttle = 0f
        /** 0..1: sliding sideways, locked under braking, spinning the wheels. */
        @Volatile var slip = 0f
        @Volatile var wantRun = false; @Volatile var canRun = true
        @Volatile var surface = "asphalt"
        @Volatile var seenNs = 0L
    }

    /** Recorded loops instead of the made-live engines. */
    @Volatile var recorded = false
    /** The cars heard at once (the nearest that make any sound). */
    var maxHeard = 4

    private inner class Car(val feed: Feed, seed: Long) {
        val state = EngineState(feed.profile)
        val synth = EngineSynth(feed.profile, seed)
        val tyres = TyreSynth(seed + 1)
        var loops: LoopEngine? = null
        var dist = 0f
        var doppler = 1f
        val lp = FloatArray(2)
    }

    private val cars = ConcurrentHashMap<Any, Car>()
    private var mono = FloatArray(0)
    private val tmp = FloatArray(3)
    private val order = ArrayList<Car>()
    private var seeds = 100L

    /** The feed of the car [key] (made on first use, its engine by [profile]). */
    fun feed(key: Any, profile: () -> EngineProfile): Feed = cars.getOrPut(key) { Car(Feed(profile()), seeds++) }.feed

    /** Forgets the cars not seen for a while (towed, streamed out). */
    fun sweep(olderThanNs: Long) { val now = System.nanoTime(); cars.entries.removeIf { now - it.value.feed.seenNs > olderThanNs } }

    /** The engine of [key], as heard now (tests, the debug line). */
    fun stateOf(key: Any): EngineState? = cars[key]?.state

    override fun render(m: Mixer, out: FloatArray, frames: Int) {
        if (mono.size < frames) mono = FloatArray(frames)
        val dt = frames / m.rate.toFloat()
        order.clear()
        for (c in cars.values) {
            val f = c.feed
            c.state.update(dt, f.wantRun, f.canRun, f.rpm, f.gear, f.throttle, f.speed)
            val dx = f.x - m.lx; val dz = f.z - m.lz
            c.dist = sqrt(dx * dx + dz * dz) / Mixer.U
            val audible = c.state.phase != EngineState.Phase.OFF || c.state.rpm > 30f || abs(f.speed) > .3f
            if (audible && c.dist < 220f) order += c
        }
        order.sortBy { it.dist }
        val bankNow = if (recorded) bank() else null
        for (i in 0 until minOf(maxHeard, order.size)) {
            val c = order[i]; val f = c.feed
            if (!m.spatial(f.x, f.z, 6f, tmp)) continue
            // Doppler: its speed towards the listener (the listener's own is the driven car's, heard from it)
            val dx = f.x - m.lx; val dz = f.z - m.lz
            val d = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-3f)
            val radial = ((f.vx - m.lvx) * dx + (f.vz - m.lvz) * dz) / d
            val want = (343f / (343f + radial.coerceIn(-80f, 80f))).coerceIn(.8f, 1.25f)
            c.doppler += (want - c.doppler) * .3f
            java.util.Arrays.fill(mono, 0, frames, 0f)
            if (bankNow != null) {
                val le = c.loops ?: LoopEngine(f.profile, bankNow).also { c.loops = it }
                le.render(c.state, mono, frames, .9f, c.doppler)
            } else c.synth.render(c.state, mono, frames, .9f, c.doppler)
            c.tyres.render(mono, frames, 1f, f.speed, f.slip, f.surface, c.doppler)
            val gl = tmp[0]; val gr = tmp[1]; val cut = tmp[2]
            for (k in 0 until frames) {
                var x = mono[k]
                if (cut < .999f) { c.lp[0] += (x - c.lp[0]) * cut; x = c.lp[0] }
                out[2 * k] += x * gl; out[2 * k + 1] += x * gr
            }
        }
    }

    companion object {
        /**
         * How much a car slips (0..1) for the squeal: sliding sideways faster than a couple of metres a second, locking the
         * wheels under hard braking at speed, or spinning them pulling away hard in first.
         */
        fun slip(lateral: Float, speed: Float, braking: Boolean, decel: Float, throttle: Float, gear: Int, rpmShare: Float): Float {
            val side = ((abs(lateral) - 1.8f) / 3.5f).coerceIn(0f, 1f)
            val lock = if (braking && abs(speed) > 5f) ((decel - 5f) / 4f).coerceIn(0f, 1f) * .8f else 0f
            val spin = if (throttle > .85f && gear <= 1 && abs(speed) < 7f && rpmShare > .55f) .5f else 0f
            return maxOf(side, lock, spin)
        }
    }
}
