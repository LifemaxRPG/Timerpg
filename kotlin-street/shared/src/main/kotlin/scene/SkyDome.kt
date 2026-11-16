package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sky itself, painted per hour into a small picture that is stretched over the view: not a
 * three-colour ramp but the air's own light. By day a deep blue overhead (the air scatters blue
 * most where there is least of it to look through) paling to a milky haze at the horizon, a little
 * brighter on the sun's side. Through golden hour and dusk a warm band along the horizon, hottest
 * under the sun, rising into rose and then the cool violet of the upper sky; opposite the sun the
 * pink Belt of Venus over the blue-grey shadow of the earth. Through the blue hour the warmth sinks
 * into a last ember at the horizon under cobalt. At night a near-black zenith over a deep navy, the
 * city's light glowing warm and dusty up from the horizon, and faint unevenness in the dark so it
 * is never one flat colour. The colours are mixed as light (squared), so a blend of blue and orange
 * passes through violet and rose, not grey. The banding of dark ramps is broken by the scene's
 * grain.
 *
 * Pure: the colours in, ARGB pixels out ([W] by [H], the top row overhead, the bottom row the
 * horizon, the left column the left edge of the view).
 */
internal object SkyDome {
    const val W = 24
    const val H = 160

    /**
     * What the picture holds, in bytes (one per Backdrop, re-painted in place as the hour moves).
     */
    const val BYTES = W * H * 4

    /** The colour of the sky a share [at] of the way down to the horizon, mid view, from [px]. */
    fun at(px: IntArray, at: Float): Int {
        val row = (Px.clamp01(at) * (H - 1)).toInt()
        return px[row * W + W / 2]
    }

    /**
     * Paints [out] for a sky whose colours ramp [zenith], [mid], [horizon] (ARGB), with the
     * horizon's [glow] and the sun's [halo], [dark] (0 day, about .55 at dusk, 1 at night), the sun
     * or moon on the right when [sunRight].
     */
    fun paint(
        out: IntArray,
        zenith: Int,
        mid: Int,
        horizon: Int,
        glow: Int,
        halo: Int,
        dark: Float,
        sunRight: Boolean,
    ) {
        val day = 1f - Px.smooth(.04f, .42f, dark)
        val dusk = Px.smooth(.04f, .42f, dark) * (1f - Px.smooth(.68f, .97f, dark))
        val night = Px.smooth(.6f, 1f, dark)
        // the ramp's stops (squared: mixed as light)
        val z = lin(zenith)
        val m = lin(mid)
        val h = lin(horizon)
        // the control colour that puts the curve through [mid] half way down
        val bend = FloatArray(3) { (2f * m[it] - (z[it] + h[it]) / 2f).coerceAtLeast(0f) }
        val warm = lin(glow or (0xFF shl 24))
        val sun = lin(halo or (0xFF shl 24))
        val dayHaze = mixL(h, WHITE, .55f)
        val daySun = mixL(h, sun, .45f)
        val c = FloatArray(3)
        val hot = FloatArray(3)
        for (y in 0 until H) {
            val v = y / (H - 1f)
            for (x in 0 until W) {
                val u = x / (W - 1f)
                // 1 at the sun's edge of the view, 0 at the other
                val s = if (sunRight) u else 1f - u
                // the ramp: one smooth curve through the three colours (a quadratic through the
                // middle one), no stops to kink at and no Mach bands
                val g = v.pow(1.15f)
                for (i in 0 until 3) {
                    val a = (1f - g) * (1f - g)
                    val b = 2f * g * (1f - g)
                    c[i] = a * z[i] + b * bend[i] + g * g * h[i]
                }
                if (day > 0f) {
                    // a deeper, purer blue overhead
                    toward(c, DAY_ZENITH, day * (1f - v).pow(2.2f) * .42f)
                    // the milky haze of the low sky, whitest at the horizon
                    toward(c, dayHaze, day * v.pow(5f) * .5f)
                    // the sky brightens towards the sun
                    toward(c, daySun, day * s * s * (.03f + .25f * v))
                }
                if (dusk > 0f) {
                    // the warm band along the horizon, hottest under the sun
                    val band = v.pow(4f)
                    set(hot, warm, EMBER, .35f * s)
                    toward(c, hot, dusk * band * (.42f + .5f * s))
                    // a rose layer above it, strongest opposite the sun: the Belt of Venus
                    val belt = bell(v, .7f, .16f)
                    toward(c, ROSE, dusk * belt * (.22f + .3f * (1f - s)))
                    // and under that belt, the earth's own shadow rising, blue-grey
                    toward(
                        c,
                        EARTH_SHADOW,
                        dusk * Px.smooth(.86f, 1f, v) * (1f - s) * (1f - s) * .42f,
                    )
                    // a golden glow round where the sun went down
                    toward(c, GOLD, dusk * s.pow(2.4f) * Px.smooth(.45f, 1f, v) * .38f)
                    // and the cool violet of the high dusk sky
                    toward(c, VIOLET, dusk * (1f - v).pow(1.5f) * .3f)
                }
                if (night > 0f) {
                    // a near-black zenith
                    toward(c, NIGHT_ZENITH, night * (1f - v).pow(1.4f) * .55f)
                    // the city's light, warm and dusty, up from the horizon
                    val city = exp(-(1f - v) / .17f)
                    toward(c, CITY_GLOW, night * city * .8f)
                    toward(c, CITY_LOW, night * exp(-(1f - v) / .04f) * .55f)
                    // a little brighter on the moon's side
                    toward(c, MOON_SKY, night * s * s * (.25f - .2f * v))
                    // and never one flat colour: very faint, very wide unevenness
                    val n =
                        (Noise.fbm(u * 2.2f + 3.1f, v * 1.6f + 7.7f, 41, 3) - .5f) *
                            .16f *
                            night *
                            (1f - v)
                    c[0] *= 1f + n
                    c[1] *= 1f + n
                    c[2] *= 1f + n * 1.4f
                }
                out[y * W + x] =
                    Px.argbF(
                        1f,
                        sqrt(Px.clamp01(c[0])),
                        sqrt(Px.clamp01(c[1])),
                        sqrt(Px.clamp01(c[2])),
                    )
            }
        }
    }

    // ---- the extra colours of the air (squared, as they are mixed)

    private val DAY_ZENITH = lin(0xFF1D4F9E.toInt())
    private val WHITE = lin(0xFFF4F8FB.toInt())
    private val EMBER = lin(0xFFFF7A3C.toInt())
    private val ROSE = lin(0xFFD98A9E.toInt())
    private val EARTH_SHADOW = lin(0xFF4E5582.toInt())
    private val GOLD = lin(0xFFFFD08A.toInt())
    private val VIOLET = lin(0xFF2A2458.toInt())
    private val NIGHT_ZENITH = lin(0xFF02040B.toInt())
    private val CITY_GLOW = lin(0xFF3A3152.toInt())
    private val CITY_LOW = lin(0xFF5C4652.toInt())
    private val MOON_SKY = lin(0xFF1C2A55.toInt())

    private fun lin(argb: Int): FloatArray {
        fun ch(shift: Int): Float {
            val v = ((argb ushr shift) and 0xFF) / 255f
            return v * v
        }
        return floatArrayOf(ch(16), ch(8), ch(0))
    }

    private fun mixL(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    private fun set(c: FloatArray, a: FloatArray, b: FloatArray, t: Float) {
        for (i in 0 until 3) c[i] = a[i] + (b[i] - a[i]) * t
    }

    private fun toward(c: FloatArray, to: FloatArray, t: Float) {
        val k = Px.clamp01(t)
        for (i in 0 until 3) c[i] += (to[i] - c[i]) * k
    }

    private fun bell(v: Float, at: Float, width: Float): Float {
        val d = abs(v - at) / width
        return if (d >= 1f) 0f else .5f + .5f * sin((1f - d - .5f) * PI.toFloat())
    }
}

/**
 * The Milky Way, painted once: a long soft band of light, lumpy with star clouds, its middle split
 * by the dark rifts of dust, warmer and brighter along its core and cool at its edges, fading out
 * at both ends. Drawn added over the night sky, faint, turned across it, only on dark clear nights
 * and less under a bright moon. ARGB, [W] by [H].
 */
internal object MilkyWay {
    const val W = 320
    const val H = 96
    const val BYTES = W * H * 4

    fun pixels(): IntArray {
        val out = IntArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val u = x / (W - 1f)
                // the band's middle wanders a little; its width breathes along it
                val mid = H * (.5f + .07f * sin(u * PI.toFloat() * 1.4f + .6f))
                val sigma = H * (.15f + .07f * Noise.fbm(u * 3f, 1.3f, 71, 3))
                val dy = (y - mid) / sigma
                val profile = exp(-dy * dy)
                // star clouds: lumps of light at two scales
                val clouds =
                    .45f +
                        .4f * Noise.fbm(x / 34f, y / 16f, 73, 4) +
                        .3f * (Noise.billow(x / 14f, y / 9f, 79, 3) - .5f)
                // the dark rift along the core, ragged
                val rw = sigma * (.2f + .16f * Noise.fbm(u * 5f, 4.1f, 83, 2))
                val off = (y - mid - sigma * .12f) / rw
                val rift = exp(-off * off) * Px.smooth(.35f, .7f, Noise.fbm(x / 26f, y / 8f, 89, 4))
                val ends = Px.smooth(0f, .3f, u) * Px.smooth(1f, .7f, u)
                val b = Px.clamp01(profile * clouds * (1f - .6f * rift) * ends * 1.15f)
                if (b <= .002f) continue
                // warm along the bright core, cool blue at the edges
                val core = Px.smooth(.25f, .8f, profile * clouds)
                out[y * W + x] =
                    Px.argbF(b, .72f + .28f * core, .78f + .16f * core, 1f - .12f * core)
            }
        }
        return out
    }
}
