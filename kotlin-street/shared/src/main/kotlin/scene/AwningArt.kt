package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shop's awning as a piece of canvas on a frame, baked once for every shop: its canopy sloping
 * out from the wall over its arms (the cloth taut over each arm and sagging between, its stripes
 * following the cloth and narrowing toward the wall, its side cheeks), the rolled binding along its
 * front edge, and the scalloped valance hanging from it, hemmed and stitched, weathered: faded
 * where the sun finds it, dirtied where the rain runs off the scallops' points, a stain here and
 * there.
 *
 * Each picture is coloured when drawn ([BuildingPainter]): its alpha is the cloth, its channels are
 * weights, red the shop's colour (shaded by the folds), blue the cream of the stripes (shaded so
 * too) and green the light: so one picture serves every shop's colour, by day and by night. Lengths
 * in units from the awning's top-left at the wall's middle ([B.W] across, x from -W/2); [PPU]
 * pixels a unit. Pure Kotlin.
 */
internal object AwningArt {
    const val PPU = 4f

    /** Where the canopy meets the wall and where its front edge hangs (units down from its top). */
    const val INSET = 6f
    const val FRONT = 9.4f

    /** The arms under the cloth (one at each end), how much the cloth sags between them. */
    const val ARMS = 9
    const val SAG = 1f

    /** A stripe's width, a scallop's, the valance's depth to its scallops and theirs. */
    const val STRIPE = 6f
    const val SCALLOP = 12f
    const val VALANCE = 2.6f
    const val DIP = 4.2f

    /** The canopy's picture covers rows 0 to [CANOPY_H]; the valance's from [VALANCE_AT] down. */
    const val CANOPY_H = 11f
    const val VALANCE_AT = 8.6f
    const val VALANCE_H = 9.4f

    val W: Int = (B.W * PPU).toInt()
    val CANOPY_PX: Int = (CANOPY_H * PPU).toInt()
    val VALANCE_PX: Int = (VALANCE_H * PPU).toInt()

    fun bytes(): Int = W * (CANOPY_PX + VALANCE_PX) * 4

    private val half = B.W / 2f

    /** Between two arms at [x]: 0 over an arm, 1 midway. */
    fun between(x: Float): Float {
        val span = (B.W - 2f) / (ARMS - 1)
        val t = (x + half - 1f) / span
        return sin((t - floor(t)) * PI.toFloat())
    }

    /** The front edge's height at [x]: the binding, sagging between the arms. */
    fun front(x: Float): Float {
        val s = between(x)
        return FRONT + SAG * s * s
    }

    /** The hem's height at [x]: a round scallop each [SCALLOP], hung from the front edge. */
    fun hem(x: Float): Float {
        val t = (x + half - 1f) / SCALLOP
        val f = (t - floor(t)) * 2f - 1f
        return front(x) + VALANCE + DIP * sqrt(maxOf(0f, 1f - f * f))
    }

    /** The canopy's left and right edges at row [y] (the cheeks slant out from the wall). */
    fun left(y: Float): Float = -half + INSET - (INSET - 1f) * (y / FRONT).coerceIn(0f, 1f)

    fun right(y: Float): Float = -left(y)

    /**
     * The cloth's stripe at [u] across it (0 the left edge, 1 the right): 1 on a cream stripe, 0 on
     * the shop's colour, soft at its edges as woven cloth is.
     */
    private fun stripe(u: Float): Float {
        val n = (B.W - 2f) / STRIPE
        val p = u * n
        val f = p - floor(p)
        val e = .08f
        return Px.smooth(.5f - e, .5f + e, f) * (1f - Px.smooth(1f - e, 1f, f))
    }

    /** The cream of the stripes, as strong as it shows over the shop's colour. */
    private const val CREAM = .62f

    fun canopy(): GlassStrip {
        val h = CANOPY_PX
        val px = IntArray(W * h)
        for (j in 0 until h) for (i in 0 until W) {
            val x = -half + (i + .5f) / PPU
            val y = (j + .5f) / PPU
            val l = left(y)
            val r = right(y)
            val fy = front(x)
            // coverage: inside the cheeks and above the front edge (soft by a pixel)
            val inX = Px.clamp01((x - l) * PPU + .5f) * Px.clamp01((r - x) * PPU + .5f)
            val inY = Px.clamp01((fy + .45f - y) * PPU + .5f)
            val cov = inX * inY
            if (cov <= .004f) continue
            val v = (y / fy).coerceIn(0f, 1f)
            val u = ((x - l) / (r - l)).coerceIn(0f, 1f)
            val s = between(x)
            // the fold: taut and catching the light over an arm, a hollow in shade between, the
            // hollow deepening toward the front edge where the cloth sags most
            var shade = 1f - .28f * s * s * (.3f + .7f * v) + .06f * (1f - s) * (1f - v)
            // the slope: the sky full on it near the wall, a little less toward the edge
            shade *= .97f - .07f * v
            // the cheeks: the ends of the canopy turned away, darker
            val cheek = Px.smooth(1.2f, 0f, minOf(x - l, r - x))
            shade *= 1f - .28f * cheek
            var light = .05f * (1f - v) * (1f - s)
            // the binding along the front edge: a rolled bead, its top lit, its foot dark
            val b = fy - y
            if (b < 1.1f) {
                val t = b / 1.1f
                shade *= .78f + .3f * t
                light += .14f * Px.smooth(.35f, .75f, t) * (1f - Px.smooth(.75f, 1f, t))
                // its stitching: a dashed line along the bead
                val dash = (x * 1.25f - floor(x * 1.25f)) < .55f
                if (dash && t in .78f..0.95f) light += .1f
            }
            // weathering: the sun's fading high on the slope, a mottle of grime, a stain or two
            val fade = .06f * (1f - v) * Noise.fbm(x * .05f, y * .2f, 731, 3)
            val mottle = (Noise.fbm(x * .22f, y * .5f, 733, 3) - .5f) * .1f
            val stain = Px.smooth(.7f, .82f, Noise.value(x * .09f, y * .3f, 737)) * .12f
            shade *= 1f + mottle - stain
            light += fade
            // the weave: a faint grain across
            shade *= 1f + (Noise.value(i * .9f, j * 2.2f, 739) - .5f) * .03f
            val cream = stripe(u) * CREAM
            px[j * W + i] =
                Px.argbF(
                    cov,
                    Px.clamp01(shade * (1f - cream)),
                    Px.clamp01(light),
                    Px.clamp01(shade * cream),
                )
        }
        return GlassStrip(W, h, px)
    }

    fun valance(): GlassStrip {
        val h = VALANCE_PX
        val px = IntArray(W * h)
        for (j in 0 until h) for (i in 0 until W) {
            val x = -half + (i + .5f) / PPU
            val y = VALANCE_AT + (j + .5f) / PPU
            if (x < -half + 1f || x > half - 1f) continue
            val fy = front(x)
            val hy = hem(x)
            val inX =
                Px.clamp01((x + half - 1f) * PPU + .5f) * Px.clamp01((half - 1f - x) * PPU + .5f)
            val cov = inX * Px.clamp01((hy - y) * PPU + .5f) * Px.clamp01((y - fy + 1f) * PPU)
            if (cov <= .004f) continue
            val d = (y - fy) / (hy - fy)
            // hanging straight down, out of the sky's light: darker than the slope, more so low
            var shade = .8f - .1f * d
            // the hang between arms ripples the cloth a little: soft vertical folds
            shade *= 1f + .05f * sin(x * 1.1f + 1.3f * sin(x * .23f))
            var light = 0f
            // the hem: turned up a little (a lighter edge), stitched above it
            val e = hy - y
            if (e < .55f) shade *= .82f else if (e < 1f) light += .06f
            val stitch = (x * 1.25f - floor(x * 1.25f)) < .55f
            if (stitch && e in 1.15f..1.5f) light += .12f
            // and a stitched seam under the binding
            if (stitch && y - fy in .35f..0.65f) light += .1f
            // the rain's dirt run down to each scallop's point, and grime along the hem
            val t = (x + half - 1f) / SCALLOP
            val f = abs((t - floor(t)) * 2f - 1f)
            val run = Px.smooth(.6f, 1f, f) * Px.smooth(.2f, 1f, d) * .2f
            val grime = Px.smooth(.55f, 1f, d) * .1f * Noise.fbm(x * .3f, 3f, 741, 2)
            shade *= 1f - run - grime
            shade *= 1f + (Noise.value(i * .9f, j * 2.2f, 743) - .5f) * .03f
            val u = ((x - left(FRONT)) / (right(FRONT) - left(FRONT))).coerceIn(0f, 1f)
            val cream = stripe(u) * CREAM
            px[j * W + i] =
                Px.argbF(
                    cov,
                    Px.clamp01(shade * (1f - cream)),
                    Px.clamp01(light),
                    Px.clamp01(shade * cream),
                )
        }
        return GlassStrip(W, h, px)
    }

    /**
     * The colour matrix for a shop of colour ([r], [g], [b]) under the night's ambient ([nr], [ng],
     * [nb], white by day), into [out] (twenty, Android's layout: offsets in 0..255).
     */
    fun matrix(r: Float, g: Float, b: Float, nr: Float, ng: Float, nb: Float, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        val c = floatArrayOf(r, g, b)
        val n = floatArrayOf(nr, ng, nb)
        for (ch in 0..2) {
            out[ch * 5] = c[ch] * n[ch]
            out[ch * 5 + 1] = (1f - c[ch]) * n[ch]
            out[ch * 5 + 2] = CREAM_RGB[ch] * n[ch]
        }
        out[18] = 1f
    }

    /** The stripes' cream. */
    private val CREAM_RGB = floatArrayOf(.96f, .93f, .84f)
}
