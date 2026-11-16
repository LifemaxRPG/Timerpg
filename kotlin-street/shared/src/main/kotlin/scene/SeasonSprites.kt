package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The small things the seasons let fall, painted once: autumn leaves (three shapes in the amber and
 * the red palettes, lit from the upper left with a vein down them), blossom petals (pink and white)
 * and a snowflake (a soft white speck), all small ARGB squares drawn turned and scaled as they
 * fall. Pure Kotlin, deterministic; [SceneSprites] turns them into images.
 */
internal object SeasonSprites {
    /** A leaf's picture is [LEAF] pixels square, [LEAF_PPU] of them to a street unit. */
    const val LEAF = 16
    const val LEAF_PPU = 3f
    const val LEAF_SHAPES = 3

    const val PETAL = 10
    const val PETAL_PPU = 4f

    const val FLAKE = 12
    const val FLAKE_PPU = 5f

    /** The six leaves: shape 0 to 2, amber then red (index shape * 2 + red). */
    fun leaves(): Array<IntArray> = Array(LEAF_SHAPES * 2) { leaf(it / 2, it % 2 == 1) }

    /** The two petals: pink, then white. */
    fun petals(): Array<IntArray> = arrayOf(petal(false), petal(true))

    fun bytes(): Int = LEAF_SHAPES * 2 * LEAF * LEAF * 4 + 2 * PETAL * PETAL * 4 + FLAKE * FLAKE * 4

    /** How far from the leaf's spine its edge is at [u] along it (-1 to 1), in the unit square. */
    private fun halfWidth(shape: Int, u: Float, v: Float): Float {
        val core = sqrt(max(0f, 1f - u * u))
        return when (shape) {
            // a pointed oval, its tip to the right
            0 -> .5f * core * (1f - .35f * max(0f, u)) + .02f
            // three lobes: a middle one and two set back at an angle
            1 -> {
                var best = .34f * core * (1f - .3f * max(0f, u))
                for (s in intArrayOf(-1, 1)) {
                    // a lobe turned 55 degrees from the spine, rooted a third of the way back
                    val a = s * .96f
                    val px = (u + .3f) * cos(a) + v * sin(a)
                    val py = -(u + .3f) * sin(a) + v * cos(a)
                    val along = px / .62f
                    if (along in -1f..1f) {
                        val w = .26f * sqrt(1f - along * along)
                        best = max(best, w - abs(py) + abs(v))
                    }
                }
                best
            }
            // round, like an aspen's, a touch wider at its base
            else -> .62f * core * (1f + .1f * -u) + .02f
        }
    }

    private fun leaf(shape: Int, red: Boolean): IntArray {
        val out = IntArray(LEAF * LEAF)
        val mid = (LEAF - 1) / 2f
        val dark = if (red) floatArrayOf(.42f, .12f, .08f) else floatArrayOf(.55f, .3f, .07f)
        val light = if (red) floatArrayOf(.9f, .36f, .18f) else floatArrayOf(.95f, .7f, .25f)
        val seed = 810 + shape * 3 + (if (red) 1 else 0)
        for (j in 0 until LEAF) for (i in 0 until LEAF) {
            val u = (i - mid) / (LEAF * .44f)
            val v = (j - mid) / (LEAF * .44f)
            if (abs(u) > 1f) continue
            val hw = halfWidth(shape, u, v)
            // the signed distance to the edge, in pixels
            val edge = (hw - abs(v)) * LEAF * .44f
            val a = Px.smooth(-.6f, .6f, edge)
            if (a <= 0f) continue
            // lit from the upper left, a touch of mottle, the vein and the shade of the edge
            var t = .55f - .22f * u - .3f * v + (Noise.fbm(i * .6f, j * .6f, seed, 2) - .5f) * .3f
            t -= .25f * Px.smooth(1.1f, .2f, abs(v) * LEAF * .44f) * Px.smooth(1f, -.3f, u)
            t -= .12f * Px.smooth(2f, .3f, edge)
            t = t.coerceIn(0f, 1f)
            out[j * LEAF + i] =
                Px.argbF(
                    a,
                    dark[0] + (light[0] - dark[0]) * t,
                    dark[1] + (light[1] - dark[1]) * t,
                    dark[2] + (light[2] - dark[2]) * t,
                )
        }
        return out
    }

    private fun petal(white: Boolean): IntArray {
        val out = IntArray(PETAL * PETAL)
        val mid = (PETAL - 1) / 2f
        for (j in 0 until PETAL) for (i in 0 until PETAL) {
            val u = (i - mid) / (PETAL * .42f)
            val v = (j - mid) / (PETAL * .42f)
            if (abs(u) > 1f) continue
            // an oval, narrower at its root
            val hw = .5f * sqrt(1f - u * u) * (1f - .25f * max(0f, -u))
            val edge = (hw - abs(v)) * PETAL * .42f
            val a = Px.smooth(-.6f, .6f, edge)
            if (a <= 0f) continue
            val t = (.6f - .25f * v - .15f * u).coerceIn(0f, 1f)
            // pink deepens at the root; white blushes yellow there
            out[j * PETAL + i] =
                if (white) Px.argbF(a, .98f, .95f - .08f * max(0f, -u), .9f - .2f * max(0f, -u))
                else Px.argbF(a, .95f + .05f * t, .6f + .3f * t, .72f + .22f * t)
        }
        return out
    }

    /** A snowflake: a soft white speck with a brighter heart (no arms: it is far too small). */
    fun flake(): IntArray {
        val out = IntArray(FLAKE * FLAKE)
        val mid = (FLAKE - 1) / 2f
        for (j in 0 until FLAKE) for (i in 0 until FLAKE) {
            val dx = (i - mid) / (FLAKE / 2f)
            val dy = (j - mid) / (FLAKE / 2f)
            val r2 = dx * dx + dy * dy
            val a = exp(-r2 * 4.5f) * .95f + exp(-r2 * 18f) * .4f
            val border = minOf(i, FLAKE - 1 - i, j, FLAKE - 1 - j)
            val k = Px.clamp01(a) * Px.smooth(0f, 1.5f, border.toFloat())
            if (k > .003f) out[j * FLAKE + i] = Px.argbF(k, 1f, 1f, 1f)
        }
        return out
    }
}
