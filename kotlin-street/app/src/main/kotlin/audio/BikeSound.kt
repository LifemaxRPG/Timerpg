package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The bicycle's sound, made live (nothing recorded, so it follows the speed and the ground at once): the tyres rolling
 * (a soft hiss on the road whose brightness and loudness rise with the speed, a low rumble with crackles on gravel, a
 * duller swish on grass, a hollow knock on boards), a faint thump once a wheel turn, and, coasting, the freewheel's ticks
 * (its pawls clicking past the ratchet, faster the faster the wheel turns: a tick-tick slowing to a stop). [bell] makes the
 * bell's ring (two inharmonic partials, a sharp strike, a long fade). Stereo float at [Pcm.RATE], mixed by [SoundScape].
 */
class BikeSound {
    /** Set by the game each frame: speed (street units a second), what is underfoot (Surface), pedalling, the wheel's radius. */
    @Volatile var speed = 0f
    @Volatile var surface = 0
    @Volatile var pedalling = false
    @Volatile var wheelRadius = 10f
    @Volatile var on = false

    private var rng = 0x2545F4914F6CDD1DL
    private fun noise(): Float { rng = rng * 6364136223846793005L + 1442695040888963407L; return ((rng ushr 40).toInt() and 0xFFFFFF) / 8388608f - 1f }

    private var gain = 0f
    private var lp1 = 0f; private var lp2 = 0f; private var hp = 0f
    private var wheelPhase = 0f
    private var tickPhase = 0f
    private var click = 0f
    private var clickTone = 0f
    private var crackle = 0f

    /** Adds [frames] frames of the bike into [buf] (stereo, interleaved), at [master]. */
    fun render(buf: FloatArray, frames: Int, master: Float) {
        val v = if (on) speed else 0f
        val target = if (v < 2f) 0f else (v / 300f).coerceIn(0f, 1.4f)
        if (gain < 1e-4f && target <= 0f) return
        val dt = 1f / Pcm.RATE
        // the tyres' sound by the ground: brightness (a low-pass's coefficient), loudness, crackles a second
        val (bright, loud, crackRate) = when (surface) {
            1 -> Triple(.035f, .55f, 0f)          // grass: dull
            2 -> Triple(.12f, .8f, 0f)            // boards
            3 -> Triple(.09f, 1.1f, 240f)         // gravel
            else -> Triple(.07f + .08f * (v / 300f).coerceIn(0f, 1f), .7f, 6f)  // the road: brighter with speed
        }
        val wheelHz = v / (2f * PI.toFloat() * wheelRadius)
        // (the freewheel: 18 pawl clicks a wheel turn, only coasting)
        val tickHz = if (!pedalling && v > 3f) wheelHz * 18f else 0f
        for (k in 0 until frames) {
            gain += (target - gain) * .0006f
            val n = noise()
            lp1 += (n - lp1) * bright
            lp2 += (lp1 - lp2) * bright
            hp += (lp2 - hp) * .004f
            // (the tyres' noise read as a loud wind to the owner: kept only as a whisper; the freewheel's ticks carry the sound)
            var s = (lp2 - hp) * .12f * loud
            // a thump once a wheel turn (the tyre's seam), very faint
            wheelPhase += wheelHz * dt
            if (wheelPhase >= 1f) wheelPhase -= 1f
            s *= 1f + .25f * exp(-wheelPhase * 30f)
            // crackles: grit under the tyre
            if (crackRate > 0f && noise() * .5f + .5f < crackRate * (v / 300f) * dt) crackle = .6f + .4f * (noise() * .5f + .5f)
            if (crackle > 1e-3f) { s += crackle * noise() * .06f; crackle *= .992f }
            s *= gain
            // the freewheel's ticks: a short bright click each pawl
            if (tickHz > 0f) {
                tickPhase += tickHz * dt
                if (tickPhase >= 1f) { tickPhase -= 1f; click = 1f; clickTone = 0f }
            }
            if (click > 1e-3f) {
                clickTone += 5200f * dt
                s += sin(2f * PI.toFloat() * clickTone) * click * .045f * (.4f + .6f * (v / 300f).coerceAtMost(1f))
                click *= .955f
            }
            val o = s * master
            buf[2 * k] += o; buf[2 * k + 1] += o
        }
    }

    companion object {
        /** The bell: a strike and two partials ringing out over about a second and a half (stereo float). */
        fun bell(): Pcm {
            val n = (Pcm.RATE * 1.6f).toInt()
            val d = FloatArray(n * 2)
            for (i in 0 until n) {
                val t = i.toFloat() / Pcm.RATE
                val env = exp(-t * 3.2f) * (1f - exp(-t * 900f))
                val v = (sin(2.0 * PI * 2093.0 * t).toFloat() * .55f + sin(2.0 * PI * 5340.0 * t).toFloat() * .25f * exp(-t * 6f) +
                    sin(2.0 * PI * 3140.0 * t).toFloat() * .2f * exp(-t * 4f)) * env * .5f
                // (two strikes of the clapper, a tenth of a second apart)
                val t2 = t - .11f
                val v2 = if (t2 > 0f) (sin(2.0 * PI * 2093.0 * t2).toFloat() * .5f + sin(2.0 * PI * 5340.0 * t2).toFloat() * .2f) * exp(-t2 * 3.2f) * (1f - exp(-t2 * 900f)) * .4f else 0f
                d[2 * i] = v + v2; d[2 * i + 1] = v + v2
            }
            return Pcm(d)
        }
    }
}
