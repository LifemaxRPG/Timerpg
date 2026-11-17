package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ambience made live instead of looped: rain (a hiss of countless drops, a body of rumble and close drops ticking on
 * the pavement), wind (a low rush that swells in gusts), crickets (a few insects chirping at their own pitch and pace,
 * left and right), the night's hush and the day's distant traffic. It never repeats, and follows the weather at once.
 * Pure Kotlin on stereo float at [Pcm.RATE]; [render] adds one bed into a buffer at a gain.
 */
class Synth(seed: Long = 1) {
    private var rng = seed * 6364136223846793005L + 1442695040888963407L
    private fun rnd(): Float { rng = rng * 6364136223846793005L + 1442695040888963407L; return ((rng ushr 40).toInt() and 0xFFFFFF) / 16777216f }
    private fun noise() = rnd() * 2f - 1f

    // one-pole filters' states, per bed and side
    private val lp = HashMap<String, FloatArray>()
    private fun st(name: String) = lp.getOrPut(name) { FloatArray(8) }

    private var t = 0L
    // close rain drops: (time left, gain, pan, tone)
    private val drops = ArrayDeque<FloatArray>()
    // crickets: phase, chirp clock, pitch, pan, pace
    private val crickets = Array(4) { floatArrayOf(0f, rnd() * 2f, 3900f + rnd() * 1300f, rnd() * 1.6f - .8f, .55f + rnd() * .5f) }

    /** Adds [frames] frames of bed [name] into [buf] at [gain]. Returns false for a bed it does not make. */
    fun render(name: String, buf: FloatArray, frames: Int, gain: Float): Boolean {
        val s = st(name)
        when (name) {
            "rain_light", "rain_heavy" -> {
                val heavy = name == "rain_heavy"
                val dropRate = if (heavy) 90f else 30f                      // close drops a second
                for (k in 0 until frames) {
                    // the hiss: white noise high-passed (raw minus a low-passed copy), and the body: low-passed noise
                    val nl = noise(); val nr = noise()
                    s[0] += (nl - s[0]) * .08f; s[1] += (nr - s[1]) * .08f
                    s[2] += (nl - s[2]) * .006f; s[3] += (nr - s[3]) * .006f
                    var l = (nl - s[0]) * (if (heavy) .32f else .18f) + s[2] * (if (heavy) 2.2f else 1.1f)
                    var r = (nr - s[1]) * (if (heavy) .32f else .18f) + s[3] * (if (heavy) 2.2f else 1.1f)
                    if (rnd() < dropRate / Pcm.RATE) drops.addLast(floatArrayOf(.012f + rnd() * .02f, .15f + rnd() * .35f, rnd() * 2f - 1f, 1500f + rnd() * 3000f))
                    var i = 0
                    while (i < drops.size) {
                        val d = drops[i]
                        d[0] -= 1f / Pcm.RATE
                        if (d[0] <= 0f) { drops.removeAt(i); continue }
                        val env = d[0] / .03f
                        val v = sin(2 * PI * d[3] * d[0]).toFloat() * env * env * d[1] * noise()
                        l += v * (1f - maxOf(0f, d[2])); r += v * (1f + minOf(0f, d[2]))
                        i++
                    }
                    buf[2 * k] += l * gain; buf[2 * k + 1] += r * gain
                }
            }
            "wind" -> {
                for (k in 0 until frames) {
                    // gusts: a slow wander of loudness and brightness
                    s[4] += (noise() * .5f - s[4]) * .00003f
                    val gust = (.55f + s[4] * 6f).coerceIn(.15f, 1.4f)
                    val cut = .002f + .01f * gust
                    s[0] += (noise() - s[0]) * cut; s[1] += (noise() - s[1]) * cut
                    s[2] += (s[0] - s[2]) * cut; s[3] += (s[1] - s[3]) * cut
                    buf[2 * k] += s[2] * 9f * gust * gain; buf[2 * k + 1] += s[3] * 9f * gust * gain
                }
            }
            "crickets_night" -> {
                for (k in 0 until frames) {
                    var l = 0f; var r = 0f
                    for (c in crickets) {
                        // each chirps in bursts of 3 pulses, then rests; the pace is its own
                        c[1] += c[4] / Pcm.RATE
                        val cyc = c[1] % 1f
                        val pulse = if (cyc < .3f) { val p = (cyc / .1f) % 1f; if (p < .55f) sin(PI * p / .55f).toFloat() else 0f } else 0f
                        c[0] += (2 * PI * c[2] / Pcm.RATE).toFloat()
                        if (c[0] > 2 * PI) c[0] -= (2 * PI).toFloat()
                        val v = sin(c[0]) * pulse * .05f
                        l += v * (1f - maxOf(0f, c[3])); r += v * (1f + minOf(0f, c[3]))
                    }
                    buf[2 * k] += l * gain; buf[2 * k + 1] += r * gain
                }
            }
            "night_quiet", "city_day" -> {
                // a low rush: the night's hush, or by day the far traffic of the town (louder, a little brighter)
                val day = name == "city_day"
                val cut = if (day) .004f else .0015f
                for (k in 0 until frames) {
                    s[0] += (noise() - s[0]) * cut; s[1] += (noise() - s[1]) * cut
                    s[2] += (s[0] - s[2]) * cut; s[3] += (s[1] - s[3]) * cut
                    val a = if (day) 6f else 3.5f
                    buf[2 * k] += s[2] * a * gain; buf[2 * k + 1] += s[3] * a * gain
                }
            }
            else -> return false
        }
        t += frames
        return true
    }
}
