package com.timerpg.street.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one mixer every sound goes through (docs/SOUND.md "Mixing"): four buses (ambience, effects, vehicles, music), each
 * with its volume from Settings and its duck under an NPC's voice; the ambience muffled indoors and in a car (a low-pass
 * on the bus); continuous [Source]s (beds, engines, the bike, the far traffic) and one-shot [Voice]s (footsteps, dogs,
 * bells, crashes) at most [MAX_VOICES] at a time by priority. A sound with a place in the world is heard from the
 * listener's: panned by the camera's heading, fainter and darker with distance. Pure (no Android): [SoundScape] feeds an
 * AudioTrack from [render]; the tests and the desktop measure it.
 */
class Mixer(val rate: Int = Pcm.RATE) {
    companion object {
        const val AMB = 0; const val FX = 1; const val CAR = 2; const val MUSIC = 3
        const val BUSES = 4
        const val MAX_VOICES = 28
        /** Street units a metre (VehicleWorld.U). */
        const val U = (184f / 224f) / .0388f

        /** How loud a sound [m] metres off is, against [refM] where it is at full: the inverse-distance law, never above 1. */
        fun attenuation(m: Float, refM: Float): Float = if (m <= refM) 1f else refM / (refM + (m - refM) * 1.0f)

        /** The air's and the buildings' darkening of a far sound: a low-pass's corner (Hz) at [m] metres. */
        fun distanceCutoff(m: Float): Float = (18000f / (1f + m / 35f)).coerceIn(500f, 18000f)
    }

    /** A sound some code makes as it goes (an engine, a bed); it adds [frames] stereo frames into [out]. */
    abstract class Source(val bus: Int) {
        /** False once it is done (the mixer drops it). */
        @Volatile var alive = true
        abstract fun render(m: Mixer, out: FloatArray, frames: Int)
    }

    /** A recorded one-shot: [pcm] at [gain], [rate] (pitch), panned [pan] or, with [world], from the place ([x], [z] street units). */
    class Voice(val pcm: Pcm, val gain: Float, val bus: Int = FX, val priority: Int = 1, val rate: Float = 1f, val pan: Float = 0f,
                val world: Boolean = false, val x: Float = 0f, val z: Float = 0f, val refM: Float = 4f) {
        internal var pos = 0.0
        internal val lp = FloatArray(2)
        internal var loud = gain
    }

    // the volumes (Settings) and the state the game sets
    @Volatile var master = 1f
    val volume = floatArrayOf(1f, 1f, 1f, 1f)
    @Volatile var ducked = false
    /** 0 out in the open .. 1 indoors (the ambience low-passed and quieter). */
    @Volatile var muffle = 0f
    /** The listener: where (street units) and the camera's heading (degrees: 0 north, clockwise). */
    @Volatile var lx = 0f
    @Volatile var lz = 0f
    @Volatile var yawDeg = 0f
    /** The listener's velocity (m/s, world x and z: the driven car's), for the Doppler of the others. */
    @Volatile var lvx = 0f
    @Volatile var lvz = 0f

    val sources = CopyOnWriteArrayList<Source>()
    private val incoming = ConcurrentLinkedQueue<Voice>()
    private val voices = ArrayList<Voice>()
    private val bus = Array(BUSES) { FloatArray(0) }
    private val busGain = floatArrayOf(1f, 1f, 1f, 1f)
    private val duckTo = FloatArray(BUSES)
    private val muffleLp = FloatArray(2)
    private var muffleNow = 0f
    private val tmp = FloatArray(3)
    /** The mixing's own time (ns) per block, eased, and how many voices play (the debug line). */
    var blockNs = 0L; private set
    val voiceCount: Int get() = voices.size
    /** Voices dropped for the limit (since the start). */
    var stolen = 0; private set

    fun play(v: Voice) { incoming.add(v) }
    fun add(s: Source) { sources.add(s) }

    /** For a sound at ([x], [z]): its left and right gains and its distance low-pass coefficient into [out]; null if out of earshot. */
    fun spatial(x: Float, z: Float, refM: Float, out: FloatArray): Boolean {
        val dx = x - lx; val dz = z - lz
        val m = sqrt(dx * dx + dz * dz) / U
        val att = attenuation(m, refM)
        if (att < .004f) return false
        // the camera's right (heading 0 north = -z; clockwise): (cos, sin)
        val yr = Math.toRadians(yawDeg.toDouble())
        val rx = cos(yr).toFloat(); val rz = sin(yr).toFloat()
        val pan = if (m < .5f) 0f else ((dx * rx + dz * rz) / (m * U)).coerceIn(-1f, 1f) * (m / (m + 2f))
        panGains(pan * .85f, out)
        // (behind the listener a touch darker, as a head shadows it)
        val fx = rz; val fz = -rx
        val behind = ((-(dx * fx + dz * fz)) / (m * U + 1e-3f)).coerceIn(0f, 1f)
        out[0] *= att; out[1] *= att
        out[2] = 1f - exp(-2f * Math.PI.toFloat() * distanceCutoff(m) * (1f - .35f * behind) / rate)
        return true
    }

    /** Mixes [frames] stereo frames of everything into [out] (overwritten). */
    fun render(out: FloatArray, frames: Int) {
        val t0 = System.nanoTime()
        val n = frames * 2
        for (b in 0 until BUSES) { if (bus[b].size < n) bus[b] = FloatArray(n) else java.util.Arrays.fill(bus[b], 0, n, 0f) }
        // the continuous sources
        for (s in sources) {
            if (!s.alive) { sources.remove(s); continue }
            s.render(this, bus[s.bus], frames)
        }
        // the one-shots: new ones in, the limit kept (the least important and quietest go first)
        while (true) { voices += incoming.poll() ?: break }
        while (voices.size > MAX_VOICES) {
            var worst = 0
            for (i in voices.indices) {
                val a = voices[i]; val w = voices[worst]
                if (a.priority < w.priority || (a.priority == w.priority && a.loud < w.loud)) worst = i
            }
            voices.removeAt(worst); stolen++
        }
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (!mixVoice(v, bus[v.bus], frames)) it.remove()
        }
        // the buses: their volumes, the duck eased in and out, the ambience muffled indoors
        val d = ducked
        duckTo[AMB] = if (d) .35f else 1f; duckTo[FX] = if (d) .8f else 1f; duckTo[CAR] = if (d) .6f else 1f; duckTo[MUSIC] = if (d) .3f else 1f
        java.util.Arrays.fill(out, 0, n, 0f)
        muffleNow += (muffle - muffleNow) * .05f
        val mcut = 1f - exp(-2f * Math.PI.toFloat() * (16000f * (1f - muffleNow) + 450f * muffleNow) / rate)
        val mgain = 1f - .45f * muffleNow
        for (b in 0 until BUSES) {
            val target = volume[b] * duckTo[b] * (if (b == AMB) mgain else 1f)
            val src = bus[b]
            var g = busGain[b]
            val step = (target - g) / frames * .25f
            for (k in 0 until frames) {
                g += step
                var l = src[2 * k] * g; var r = src[2 * k + 1] * g
                if (b == AMB && muffleNow > .01f) { muffleLp[0] += (l - muffleLp[0]) * mcut; muffleLp[1] += (r - muffleLp[1]) * mcut; l = muffleLp[0]; r = muffleLp[1] }
                out[2 * k] += l; out[2 * k + 1] += r
            }
            busGain[b] = g
        }
        // a soft limit, never a hard clip
        val mg = master
        for (i in 0 until n) { val x = out[i] * mg; out[i] = x / (1f + abs(x) * .25f) }
        val ns = System.nanoTime() - t0
        blockNs = if (blockNs == 0L) ns else blockNs + (ns - blockNs) / 16
    }

    /** Adds a voice's next block; false when it has ended (or is out of earshot). */
    private fun mixVoice(v: Voice, b: FloatArray, frames: Int): Boolean {
        val d = v.pcm; val len = v.pcm.frames
        var gl: Float; var gr: Float; var cut = 1f
        if (v.world) {
            if (!spatial(v.x, v.z, v.refM, tmp)) return false
            gl = tmp[0] * v.gain; gr = tmp[1] * v.gain; cut = tmp[2]
        } else {
            panGains(v.pan, tmp); gl = tmp[0] * v.gain; gr = tmp[1] * v.gain
        }
        v.loud = maxOf(gl, gr)
        var p = v.pos
        val step = v.rate.toDouble()
        var k = 0
        while (k < frames) {
            val i = p.toInt()
            if (i >= len - 1) break
            val t = (p - i).toFloat()
            var l = d[2 * i] + (d[2 * i + 2] - d[2 * i]) * t
            var r = d[2 * i + 1] + (d[2 * i + 3] - d[2 * i + 1]) * t
            if (cut < .999f) { v.lp[0] += (l - v.lp[0]) * cut; v.lp[1] += (r - v.lp[1]) * cut; l = v.lp[0]; r = v.lp[1] }
            b[2 * k] += l * gl; b[2 * k + 1] += r * gr
            p += step; k++
        }
        v.pos = p
        return p.toInt() < len - 1
    }
}

/**
 * A looped recording as a bed (its loudness eased towards [target]) or, with [world], from a place (a fountain, the
 * shore, a crowd): panned and faded by distance like a voice.
 */
class LoopSource(bus: Int, @Volatile var pcm: Pcm?, val refM: Float = 0f) : Mixer.Source(bus) {
    @Volatile var target = 0f
    @Volatile var x = 0f
    @Volatile var z = 0f
    @Volatile var world = false
    @Volatile var rate = 1f
    private var g = 0f
    private var pos = 0.0
    private val lp = FloatArray(2)
    private val tmp = FloatArray(3)
    /** Synthesizes the bed when there is no recording ([Synth]'s beds), mono-free stereo into the bus. */
    @Volatile var synth: ((FloatArray, Int, Float) -> Unit)? = null
    val level: Float get() = g

    override fun render(m: Mixer, out: FloatArray, frames: Int) {
        val p = pcm
        var gl = 1f; var gr = 1f; var cut = 1f
        if (world) { if (m.spatial(x, z, refM, tmp)) { gl = tmp[0]; gr = tmp[1]; cut = tmp[2] } else { gl = 0f; gr = 0f } }
        val t = target
        if (g < .001f && t < .001f) { g = 0f; return }
        if (p == null) {
            val s = synth ?: return
            g += (t - g) * (1f - Math.pow(1.0 - .00004, frames.toDouble()).toFloat())
            s(out, frames, g * (gl + gr) * .5f)
            return
        }
        val d = p; val len = p.frames
        val step = rate.toDouble()
        for (k in 0 until frames) {
            g += (t - g) * .00004f
            val i = pos.toInt()
            var l = d[2 * i]; var r = d[2 * i + 1]
            if (cut < .999f) { lp[0] += (l - lp[0]) * cut; lp[1] += (r - lp[1]) * cut; l = lp[0]; r = lp[1] }
            out[2 * k] += l * g * gl; out[2 * k + 1] += r * g * gr
            pos += step; if (pos >= len) pos -= len
        }
    }
}
