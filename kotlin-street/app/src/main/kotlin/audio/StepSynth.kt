package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Footsteps made by code (Footsteps set E): each step a heel strike and a toe roll a moment later, each built from what the
 * ground does to a shoe: a low body thud, a click of the sole on hard ground, grit under it on a road, a rustle of blades
 * on a lawn, a crunch of stones on gravel, a crunch and squeak of packed snow, the boards' hollow ring on a deck, a room's
 * short reflections indoors, a carpet's muffled pad. Random every time (no two alike). Mono float at [Pcm.RATE].
 */
class StepSynth(seed: Long = 11) {
    private val r = Rng(seed)

    /** One step on [surface] ([Footsteps.SURFACES]); [running] lands harder with a shorter roll. */
    fun make(surface: String, running: Boolean = false): FloatArray {
        val len = (Pcm.RATE * (if (surface == "snow" || surface == "gravel") .42f else .34f)).toInt()
        val o = FloatArray(len)
        val hard = if (running) 1.25f else 1f
        val roll = (if (running) .045f else .07f) + r.range(-.01f, .012f)
        val toe = r.range(.45f, .7f)
        when (surface) {
            "concrete", "asphalt" -> {
                val dark = surface == "asphalt"
                thud(o, 0f, r.range(95f, 125f) * (if (dark) .9f else 1f), .022f, .55f * hard)
                click(o, 0f, if (dark) 2700f else 3600f, 900f, .006f, .8f * hard)
                grains(o, .002f, .03f, if (dark) 16 else 5, 1500f, 4500f, .0012f, .12f * hard)
                thud(o, roll, r.range(120f, 150f), .015f, .25f * toe * hard)
                click(o, roll, if (dark) 2600f else 3300f, 1000f, .005f, .55f * toe * hard)
                scuff(o, roll, .045f, 1600f, 4200f, .06f * toe)
            }
            "grass" -> {
                thud(o, 0f, r.range(70f, 90f), .03f, .32f * hard)
                grains(o, 0f, .16f, 45 + (r.next() * 25).toInt(), 1800f, 6000f, .004f, .1f * hard)
                scuff(o, 0f, .12f, 2000f, 5500f, .09f * hard)
                grains(o, roll, .1f, 20, 2000f, 6000f, .003f, .07f * toe)
            }
            "gravel" -> {
                thud(o, 0f, r.range(80f, 100f), .025f, .35f * hard)
                grains(o, 0f, .17f, 90 + (r.next() * 50).toInt(), 700f, 4200f, .0018f, .32f * hard)
                grains(o, roll, .14f, 45, 800f, 4200f, .0016f, .22f * toe)
            }
            "snow" -> {
                thud(o, 0f, r.range(60f, 80f), .04f, .22f * hard)
                grains(o, 0f, .24f, 80 + (r.next() * 40).toInt(), 450f, 2600f, .004f, .2f * hard)
                squeak(o, .03f, .1f, r.range(1100f, 1700f), .035f)
                grains(o, roll + .04f, .16f, 40, 500f, 2400f, .004f, .14f * toe)
            }
            "wood" -> {
                thud(o, 0f, r.range(120f, 145f), .03f, .5f * hard)
                modes(o, 0f, floatArrayOf(r.range(170f, 200f), r.range(290f, 330f), r.range(450f, 500f), r.range(690f, 760f)), floatArrayOf(.11f, .08f, .06f, .045f), .3f * hard)
                click(o, 0f, 3200f, 1200f, .004f, .45f * hard)
                modes(o, roll, floatArrayOf(r.range(200f, 230f), r.range(330f, 360f)), floatArrayOf(.07f, .05f), .14f * toe)
                click(o, roll, 3000f, 1200f, .004f, .3f * toe)
                if (r.next() < .12f) creak(o, roll + .02f, .13f, r.range(520f, 800f), .03f)
            }
            "floor" -> {
                thud(o, 0f, r.range(110f, 135f), .02f, .42f * hard)
                modes(o, 0f, floatArrayOf(r.range(230f, 260f), r.range(500f, 560f)), floatArrayOf(.05f, .035f), .18f * hard)
                click(o, 0f, 4200f, 1300f, .004f, .65f * hard)
                thud(o, roll, r.range(140f, 160f), .012f, .2f * toe)
                click(o, roll, 4000f, 1300f, .004f, .4f * toe)
                room(o, .011f, .023f)
            }
            else -> { // carpet: a muffled pad
                thud(o, 0f, r.range(80f, 100f), .03f, .38f * hard)
                burst(o, 0f, .02f, 120f, 800f, .25f * hard)
                scuff(o, roll, .05f, 2500f, 5000f, .02f * toe)
                thud(o, roll, r.range(100f, 120f), .02f, .14f * toe)
            }
        }
        // the shoe and the ground take the top off (a sole is soft): each ground's own low-pass, twice for a steeper edge
        val cut = (TOP[surface] ?: 5000f) * r.range(.9f, 1.1f)
        val l1 = Biquad().lowpass(cut, .6f); val l2 = Biquad().lowpass(cut * 1.3f, .6f)
        for (i in o.indices) o[i] = l2.run(l1.run(o[i]))
        // (a short fade at the end so nothing clicks)
        val f = (Pcm.RATE * .02f).toInt()
        for (i in 0 until f) o[len - 1 - i] *= i / f.toFloat()
        // each ground at its own loudness (soft ground quieter), within a decibel or so step to step
        var peak = 0f; for (x in o) peak = maxOf(peak, abs(x))
        val want = (LEVEL[surface] ?: .6f) * r.range(.88f, 1.06f) * (if (running) 1.1f else 1f)
        if (peak > 1e-6f) { val k = want / peak; for (i in o.indices) o[i] *= k }
        return o
    }

    private fun at(t: Float) = (t * Pcm.RATE).toInt()

    companion object {
        /** Each ground's peak level. */
        val TOP = mapOf("concrete" to 4500f, "asphalt" to 4000f, "floor" to 5000f, "wood" to 4500f, "grass" to 5500f, "gravel" to 5000f, "carpet" to 2500f, "snow" to 4500f)
        val LEVEL = mapOf("concrete" to .7f, "asphalt" to .65f, "floor" to .68f, "wood" to .72f, "grass" to .42f, "gravel" to .55f, "carpet" to .38f, "snow" to .45f)
    }

    /** A low body thud: a sine falling from 1.6x to [hz] as it dies over [decay] s, with a puff of low noise. */
    private fun thud(o: FloatArray, t0: Float, hz: Float, decay: Float, amp: Float) {
        val s = at(t0); var ph = 0f
        val lp = OnePole(hz * 2f)
        val n = minOf(o.size - s, at(decay * 6f))
        for (i in 0 until n) {
            val t = i / Pcm.RATE.toFloat()
            val f = hz * (1f + .6f * exp(-t / (decay * .4f)))
            ph += 2f * PI.toFloat() * f / Pcm.RATE
            val env = (1f - exp(-t / .0007f)) * exp(-t / decay)
            o[s + i] += (sin(ph) * .8f + lp.lp(r.noise()) * 1.2f) * env * amp
        }
    }

    /** The sole's click on hard ground: band-passed noise, a sharp attack and a [decay] s fall. */
    private fun click(o: FloatArray, t0: Float, hz: Float, low: Float, decay: Float, amp: Float) {
        val s = at(t0)
        val bp = Biquad().bandpass(hz * r.range(.85f, 1.15f), .8f)
        val hp = Biquad().highpass(low)
        val n = minOf(o.size - s, at(decay * 7f))
        for (i in 0 until n) {
            val t = i / Pcm.RATE.toFloat()
            val env = (1f - exp(-t / .0003f)) * exp(-t / decay)
            o[s + i] += hp.run(bp.run(r.noise())) * env * amp * 2.2f
        }
    }

    /** Tiny grains (stones, blades, grit, snow crystals): [count] clicks over [span] s, each a ringing band of noise. */
    private fun grains(o: FloatArray, t0: Float, span: Float, count: Int, lo: Float, hi: Float, decay: Float, amp: Float) {
        for (g in 0 until count) {
            // denser early (the weight comes down), thinning out
            val u = r.next(); val t = t0 + span * u * u
            val s = at(t)
            val bp = Biquad().bandpass(lo * Math.pow((hi / lo).toDouble(), r.next().toDouble()).toFloat(), r.range(1.5f, 4f))
            val a = amp * r.range(.2f, 1f) * (1f - .6f * u)
            val d = decay * r.range(.5f, 1.5f)
            val n = minOf(o.size - s, at(d * 6f))
            for (i in 0 until n) {
                val tt = i / Pcm.RATE.toFloat()
                o[s + i] += bp.run(r.noise()) * exp(-tt / d) * a * 3f
            }
        }
    }

    /** A scuff: a swell and fall of high noise over [len] s. */
    private fun scuff(o: FloatArray, t0: Float, len: Float, lo: Float, hi: Float, amp: Float) {
        val s = at(t0)
        val hp = Biquad().highpass(lo); val lp = Biquad().lowpass(hi)
        val n = minOf(o.size - s, at(len))
        for (i in 0 until n) {
            val x = i / n.toFloat()
            val env = sin(PI.toFloat() * x) * (1f - x)
            o[s + i] += lp.run(hp.run(r.noise())) * env * amp * 2f
        }
    }

    /** A low burst of noise (the carpet's pad). */
    private fun burst(o: FloatArray, t0: Float, decay: Float, lo: Float, hi: Float, amp: Float) {
        val s = at(t0)
        val hp = Biquad().highpass(lo); val lp = Biquad().lowpass(hi)
        val n = minOf(o.size - s, at(decay * 6f))
        for (i in 0 until n) {
            val t = i / Pcm.RATE.toFloat()
            o[s + i] += lp.run(hp.run(r.noise())) * (1f - exp(-t / .002f)) * exp(-t / decay) * amp * 2f
        }
    }

    /** Boards ringing: a few decaying partials, struck. */
    private fun modes(o: FloatArray, t0: Float, hz: FloatArray, decay: FloatArray, amp: Float) {
        val s = at(t0)
        for (m in hz.indices) {
            val n = minOf(o.size - s, at(decay[m] * 5f))
            val a = amp * r.range(.6f, 1f) / (1f + m * .4f)
            val ph0 = r.next() * 6.28f
            for (i in 0 until n) {
                val t = i / Pcm.RATE.toFloat()
                o[s + i] += sin(ph0 + 2f * PI.toFloat() * hz[m] * t) * exp(-t / decay[m]) * (1f - exp(-t / .0008f)) * a
            }
        }
    }

    /** A board's creak: a stick-slip squeal wandering in pitch. */
    private fun creak(o: FloatArray, t0: Float, len: Float, hz: Float, amp: Float) {
        val s = at(t0); var ph = 0f
        val n = minOf(o.size - s, at(len))
        for (i in 0 until n) {
            val x = i / n.toFloat()
            ph += 2f * PI.toFloat() * hz * (1f + .15f * sin(x * 9f)) / Pcm.RATE
            // (the stick-slip: a pulse train rather than a pure tone)
            val v = sin(ph); val p = if (v > .6f) v - .6f else 0f
            o[s + i] += p * sin(PI.toFloat() * x) * amp * 3f
        }
    }

    /** Packed snow's squeak: a short rising chirp. */
    private fun squeak(o: FloatArray, t0: Float, len: Float, hz: Float, amp: Float) {
        val s = at(t0); var ph = 0f
        val n = minOf(o.size - s, at(len))
        for (i in 0 until n) {
            val x = i / n.toFloat()
            ph += 2f * PI.toFloat() * hz * (1f + .4f * x) / Pcm.RATE
            o[s + i] += sin(ph) * abs(sin(ph * .5f)) * sin(PI.toFloat() * x) * amp
        }
    }

    /** A room's first reflections (a floor indoors): two darker, quieter copies a few metres' travel later. */
    private fun room(o: FloatArray, d1: Float, d2: Float) {
        val a = at(d1); val b = at(d2)
        val lp = OnePole(2500f)
        val src = o.copyOf()
        for (i in o.indices) {
            val x = (if (i >= a) src[i - a] * .32f else 0f) + (if (i >= b) src[i - b] * .2f else 0f)
            o[i] += lp.lp(x)
        }
    }
}
