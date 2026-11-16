package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lens flare's parts, white at every alpha so the sun's colour tints them: three kinds of ghost
 * (a soft [disc], a thin [ring], a soft six-sided [poly], the iris's shape) and the anamorphic
 * [streak] through the sun ([streakW] by [streakH] pixels, [streakPpd] to a dp).
 */
internal class FlareSprite(
    val disc: SkyLayer,
    val ring: SkyLayer,
    val poly: SkyLayer,
    val streakW: Int,
    val streakH: Int,
    val streakPpd: Float,
    val streak: IntArray,
) {
    fun layer(kind: Int): SkyLayer =
        when (kind) {
            0 -> disc
            1 -> ring
            else -> poly
        }

    fun bytes(): Int = disc.bytes + ring.bytes + poly.bytes + streakW * streakH * 4
}

/** The flare sprite generator, in the style of [SunMoonSprites]: fields of light, feathered. */
internal object FlareSprites {
    /** A ghost is [GHOST] pixels square, [GHOST_PPD] of them to a dp (so 32 dp across). */
    const val GHOST = 48
    const val GHOST_PPD = 1.5f

    const val STREAK_W = 192
    const val STREAK_H = 12
    const val STREAK_PPD = 1.5f

    fun flare(): FlareSprite {
        val r = GHOST / GHOST_PPD / 2f - 2f // the ghost's radius in dp, clear of the border
        val disc =
            layer(GHOST, GHOST_PPD) { _, _, d, _ ->
                val t = Px.clamp01(d / r)
                // a soft disc, a touch brighter towards its rim, as a ghost of the iris is
                (1f - t * t) * (.7f + .3f * Px.smooth(.5f, .92f, t)) * Px.smooth(1f, .9f, t)
            }
        val ring =
            layer(GHOST, GHOST_PPD) { _, _, d, _ ->
                val e = (d - r * .86f) / (r * .09f)
                val f = (d - r * .8f) / (r * .3f)
                exp(-e * e) * .9f + exp(-f * f) * .2f
            }
        val poly =
            layer(GHOST, GHOST_PPD) { x, y, _, _ ->
                // a hexagon: the furthest of the distances to its three pairs of sides
                var h = 0f
                for (k in 0 until 3) {
                    val a = k * PI.toFloat() / 3f + .26f
                    h = max(h, abs(x * cos(a) + y * sin(a)))
                }
                val t = Px.clamp01(h / (r * .92f))
                Px.smooth(1f, .9f, t) * (.5f + .5f * Px.smooth(.55f, .95f, t))
            }
        val streak = IntArray(STREAK_W * STREAK_H)
        val mx = (STREAK_W - 1) / 2f
        val my = (STREAK_H - 1) / 2f
        for (j in 0 until STREAK_H) for (i in 0 until STREAK_W) {
            val x = (i - mx) / STREAK_PPD
            val y = (j - my) / STREAK_PPD
            // long and thin: a soft band with a hot line down its middle, fading along its length
            val along = exp(-(x / 44f) * (x / 44f)) * (1f - .15f * abs(x) / 64f)
            val a =
                (exp(-(y / 2.4f) * (y / 2.4f)) * .55f + exp(-(y / .75f) * (y / .75f)) * .7f) * along
            val border = min(min(i, STREAK_W - 1 - i), min(j, STREAK_H - 1 - j)).toFloat()
            val k = Px.clamp01(a) * Px.smooth(0f, 3f, border)
            if (k > .003f) streak[j * STREAK_W + i] = Px.argbF(k, 1f, 1f, 1f)
        }
        return FlareSprite(disc, ring, poly, STREAK_W, STREAK_H, STREAK_PPD, streak)
    }

    fun bytes(): Int = 3 * GHOST * GHOST * 4 + STREAK_W * STREAK_H * 4

    private inline fun layer(
        size: Int,
        pxPerDp: Float,
        f: (x: Float, y: Float, r: Float, theta: Float) -> Float,
    ): SkyLayer {
        val px = IntArray(size * size)
        val mid = (size - 1) / 2f
        for (j in 0 until size) for (i in 0 until size) {
            val x = (i - mid) / pxPerDp
            val y = (j - mid) / pxPerDp
            val border = min(min(i, size - 1 - i), min(j, size - 1 - j)).toFloat()
            val a = Px.clamp01(f(x, y, sqrt(x * x + y * y), 0f)) * Px.smooth(0f, 2f, border)
            if (a > .003f) px[j * size + i] = Px.argbF(a, 1f, 1f, 1f)
        }
        return SkyLayer(size, pxPerDp, px)
    }
}
