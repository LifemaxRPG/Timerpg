package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The small signal-processing pieces the made-live sounds share (footsteps, engines, bells, sirens, the ambience): a
 * fast noise source, one-pole filters and RBJ biquads. Pure Kotlin floats, no allocation per sample.
 */
class Rng(seed: Long) {
    private var s = seed * 6364136223846793005L + 1442695040888963407L
    /** 0 until 1. */
    fun next(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f }
    /** -1 until 1. */
    fun noise(): Float = next() * 2f - 1f
    fun range(a: Float, b: Float) = a + (b - a) * next()
}

/** A one-pole low-pass at [hz] (the coefficient set with [set]); [hp] gives the high-passed rest. */
class OnePole(hz: Float = 1000f, rate: Int = Pcm.RATE) {
    var a = 0f; private set
    var y = 0f
    init { set(hz, rate) }
    fun set(hz: Float, rate: Int = Pcm.RATE) { a = (1f - exp(-2f * PI.toFloat() * hz.coerceAtLeast(1f) / rate)).coerceIn(0f, 1f) }
    fun lp(x: Float): Float { y += (x - y) * a; return y }
    fun hp(x: Float): Float { y += (x - y) * a; return x - y }
}

/** An RBJ biquad (direct form I). */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun lowpass(hz: Float, q: Float = .707f, rate: Int = Pcm.RATE): Biquad = rbj(0, hz, q, 0f, rate)
    fun highpass(hz: Float, q: Float = .707f, rate: Int = Pcm.RATE): Biquad = rbj(1, hz, q, 0f, rate)
    /** A band-pass with 0 dB peak gain. */
    fun bandpass(hz: Float, q: Float, rate: Int = Pcm.RATE): Biquad = rbj(2, hz, q, 0f, rate)
    /** A peaking EQ of [db] at [hz]. */
    fun peak(hz: Float, q: Float, db: Float, rate: Int = Pcm.RATE): Biquad = rbj(3, hz, q, db, rate)

    private fun rbj(kind: Int, hz: Float, q: Float, db: Float, rate: Int): Biquad {
        val w = 2.0 * PI * hz.toDouble().coerceIn(5.0, rate * .45) / rate
        val cw = cos(w); val sw = sin(w)
        val alpha = sw / (2.0 * q.coerceAtLeast(.05f))
        var nb0: Double; var nb1: Double; var nb2: Double; val na0: Double; val na1: Double; val na2: Double
        when (kind) {
            0 -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            1 -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            2 -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            else -> {
                val a = Math.pow(10.0, db / 40.0)
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
        }
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        return this
    }

    fun run(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1
        // (denormals: a filter ringing down to nothing stays at zero)
        y1 = if (y > -1e-20f && y < 1e-20f) 0f else y
        return y1
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

/** A soft saturator (tanh-like, cheap). */
fun soft(x: Float): Float = if (x > 3f) 1f else if (x < -3f) -1f else x * (27f + x * x) / (27f + 9f * x * x)

/** An equal-power pan: the left and right gains for [pan] -1 .. 1, into [out]. */
fun panGains(pan: Float, out: FloatArray) {
    val p = (pan.coerceIn(-1f, 1f) + 1f) * .25f * PI.toFloat()
    // (1 each side at the centre, as the old linear pan had it; no side ever louder than 1)
    out[0] = minOf(1f, cos(p) * 1.4142135f); out[1] = minOf(1f, sin(p) * 1.4142135f)
}

internal fun dbToGain(db: Float) = Math.pow(10.0, db / 20.0).toFloat()
internal fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Float { var s = 0.0; for (i in from until to) s += a[i] * a[i]; return sqrt(s / maxOf(1, to - from)).toFloat() }
