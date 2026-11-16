package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A baked piece of weather: white at every alpha (the alpha is the piece), [w] by [h] pixels, drawn
 * tinted. [tile] says it repeats seamlessly (sideways, and for the rain downward too).
 */
internal class WeatherLayer(val w: Int, val h: Int, val pixels: IntArray, val tile: Boolean) {
    val bytes: Int
        get() = w * h * 4
}

/**
 * The weather's painted pieces, made once from pure Kotlin ([Noise], [Ambient.hash]) and drawn as
 * images: a tile of rain streaks (seamless both ways, scrolled down the picture at two sizes for
 * depth), a few frames of a splash (a crown of droplets rising and falling), four puddle shapes (a
 * soft-edged pool to take the sky's colour, and the thin bright rim its far edge catches), and the
 * band of a morning mist (dense at its foot, its top broken by noise, seamless sideways). Nothing
 * here is called per frame.
 */
internal object WeatherSprites {
    /** Pixels in a street unit (or a dp) for every piece. */
    const val PX_PER_UNIT = 2f

    const val STREAK_W = 96
    const val STREAK_H = 192

    /** How far a streak leans sideways per unit it falls (the wind). */
    const val SLANT = .18f

    const val SPLASH_FRAMES = 4
    const val SPLASH_W = 20
    const val SPLASH_H = 14

    const val PUDDLES = 4
    const val PUDDLE_W = 72
    const val PUDDLE_H = 24

    const val FOG_W = 256
    const val FOG_H = 96

    fun bytes(): Int =
        STREAK_W * STREAK_H * 4 +
            SPLASH_FRAMES * SPLASH_W * SPLASH_H * 4 +
            PUDDLES * PUDDLE_W * PUDDLE_H * 4 * 2 +
            FOG_W * FOG_H * 4

    /**
     * The rain: about thirty streaks of several lengths, each a fine bright thread brighter towards
     * its head (the drop), leaning with [SLANT], wrapped at the edges so the tile repeats without a
     * seam.
     */
    fun streaks(): WeatherLayer {
        val w = STREAK_W
        val h = STREAK_H
        val n = 30
        val px = FloatArray(w * h)
        val dx = SLANT / sqrt(1f + SLANT * SLANT)
        val dy = 1f / sqrt(1f + SLANT * SLANT)
        for (i in 0 until n) {
            val x0 = Ambient.hash(3, i, 11) * w
            val y0 = Ambient.hash(3, i, 12) * h
            val len = 14f + 34f * Ambient.hash(3, i, 13) * Ambient.hash(3, i, 13)
            val thick = .55f + .5f * Ambient.hash(3, i, 14)
            val bright = .45f + .55f * Ambient.hash(3, i, 15)
            // the pixels along the streak, with a soft edge of a pixel either side
            val reach = (thick + 1.5f).toInt() + 1
            var t = 0f
            while (t <= len) {
                val cx = x0 + dx * t
                val cy = y0 + dy * t
                val along = t / len
                // brightest at the head (the bottom), thinning to nothing at the tail
                val a = bright * along * along * (1f - .3f * along)
                val ix0 = (cx - reach).toInt()
                val iy0 = (cy - reach).toInt()
                for (yy in iy0..iy0 + 2 * reach) for (xx in ix0..ix0 + 2 * reach) {
                    val ex = xx + .5f - cx
                    val ey = yy + .5f - cy
                    // distance across the streak (along it, the steps overlap)
                    val across = kotlin.math.abs(ex * dy - ey * dx)
                    val k = Px.smooth(thick + .9f, thick * .35f, across)
                    if (k <= 0f) continue
                    val wx = ((xx % w) + w) % w
                    val wy = ((yy % h) + h) % h
                    val at = wy * w + wx
                    px[at] = max(px[at], a * k)
                }
                t += .5f
            }
        }
        return white(px, w, h, true)
    }

    /**
     * One frame of a splash: a drop hits, a ring spreads, droplets fly up in a crown and fall back.
     * Frame 0 is the impact, the last the dying ring.
     */
    fun splash(frame: Int): WeatherLayer {
        val w = SPLASH_W
        val h = SPLASH_H
        val t = (frame + .5f) / SPLASH_FRAMES
        val px = FloatArray(w * h)
        val cx = w / 2f
        val cy = h - 3f
        fun dot(x: Float, y: Float, r: Float, a: Float) {
            val x0 = (x - r - 1f).toInt().coerceAtLeast(1)
            val x1 = (x + r + 1f).toInt().coerceAtMost(w - 2)
            val y0 = (y - r - 1f).toInt().coerceAtLeast(1)
            val y1 = (y + r + 1f).toInt().coerceAtMost(h - 2)
            for (yy in y0..y1) for (xx in x0..x1) {
                val d = sqrt((xx + .5f - x) * (xx + .5f - x) + (yy + .5f - y) * (yy + .5f - y))
                val k = Px.smooth(r + .8f, r * .3f, d)
                if (k > 0f) {
                    val i = yy * w + xx
                    px[i] = max(px[i], a * k)
                }
            }
        }
        // the ring on the ground, seen flat: an ellipse growing and fading
        val ring = 1.5f + 6.5f * t
        var th = 0f
        while (th < 2f * PI) {
            dot(cx + ring * cos(th), cy + ring * .3f * sin(th), .7f, (1f - t) * .55f)
            th += .25f
        }
        // the crown: droplets thrown up, highest about half way, falling by the end
        val up = 4f * sin((t * PI).toFloat()) + 1f
        for (k in 0 until 7) {
            val side = (k - 3) / 3f
            val spread = ring * .9f * side
            val lift = up * (1f - .45f * side * side) * (.8f + .2f * Ambient.hash(7, k, frame))
            dot(cx + spread, cy - lift - .5f, .55f + .25f * (1f - t), (1f - t * t) * .9f)
        }
        if (frame == 0) dot(cx, cy - 1f, 1.2f, .8f)
        return white(px, w, h, false)
    }

    /**
     * A puddle's shape, [variant] of [PUDDLES]: a pool with a soft, irregular edge (an ellipse
     * eroded by noise), [fill] to take the sky's reflection and [rim] the thin bright line its far
     * edge catches.
     */
    fun puddle(variant: Int): Pair<WeatherLayer, WeatherLayer> {
        val w = PUDDLE_W
        val h = PUDDLE_H
        val seed = 400 + variant * 37
        val field = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val ax = w * (.34f + .1f * Ambient.hash(seed, 1, 2))
        val ay = h * (.26f + .1f * Ambient.hash(seed, 2, 2))
        for (y in 0 until h) for (x in 0 until w) {
            val ex = (x + .5f - cx) / ax
            val ey = (y + .5f - cy) / ay
            val n = Noise.fbm(x / 17f, y / 8f, seed, 3) - .5f
            val n2 = Noise.fbm(x / 5f + 3f, y / 4f, seed + 9, 2) - .5f
            val d = 1f - (ex * ex + ey * ey) + n * 1.7f + n2 * .4f
            field[y * w + x] = Px.smooth(-.18f, .3f, d)
        }
        // nothing on the border, so the shape is its own
        for (x in 0 until w) {
            field[x] = 0f
            field[(h - 1) * w + x] = 0f
        }
        for (y in 0 until h) {
            field[y * w] = 0f
            field[y * w + w - 1] = 0f
        }
        val rim = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val here = field[y * w + x]
            val below = field[(y + 1) * w + x]
            // the lower (far) edge of the pool, where the water meets the slab facing the light:
            // the edge's own steepness, a pixel wide, fading where the shape thins
            val edge = max(0f, here - below)
            rim[y * w + x] = Px.smooth(.04f, .2f, edge) * Px.smooth(.15f, .5f, here) * .9f
        }
        return white(field, w, h, false) to white(rim, w, h, false)
    }

    /**
     * The mist: a band dense at its foot and clear at its top, the top edge wandering and torn by
     * noise, seamless sideways (noise sampled round a circle), with a little inner structure.
     */
    fun fog(): WeatherLayer {
        val w = FOG_W
        val h = FOG_H
        val px = FloatArray(w * h)
        for (x in 0 until w) {
            val th = x / w.toFloat() * 2f * PI.toFloat()
            // the top edge, seamless: noise round a circle
            val edge = Noise.fbm(cos(th) * 1.7f + 8f, sin(th) * 1.7f + 8f, 91, 3)
            val top = h * (.12f + .36f * edge)
            for (y in 0 until h) {
                val tear = Noise.fbm(cos(th) * 3.1f + 20f, sin(th) * 3.1f + y / 26f, 93, 3) - .5f
                val yy = y + tear * 16f
                val body = Px.smooth(top, top + h * .42f, yy)
                val depth = .55f + .45f * Px.smooth(0f, h.toFloat(), y.toFloat())
                val inner =
                    .85f + .15f * (Noise.fbm(cos(th) * 2.3f + 40f, sin(th) * 2.3f + y / 30f, 97, 2))
                px[y * w + x] = Px.clamp01(body * depth * inner)
            }
        }
        for (x in 0 until w) px[x] = 0f
        return white(px, w, h, true)
    }

    private fun white(a: FloatArray, w: Int, h: Int, tile: Boolean): WeatherLayer =
        WeatherLayer(w, h, IntArray(w * h) { Px.argbF(Px.clamp01(a[it]), 1f, 1f, 1f) }, tile)
}
