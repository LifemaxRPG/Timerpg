package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A near thing, painted once: white, its shape in the alpha, [ForegroundSprites.PX_PER_DP]. */
internal class ForegroundSprite(val w: Int, val h: Int, val pixels: IntArray) {
    val bytes: Int
        get() = w * h * 4
}

/**
 * The things nearer than the street, between the camera and it: the top of a railing, a line of
 * bollards, a sign post's edge, a branch hanging into a top corner. Dark silhouettes, baked soft
 * (near things are as much out of the depth of field as far ones) and tinted at draw time with the
 * hour's near dark. Pure Kotlin; deterministic; made once. Sizes are in dp, the sprites twice that
 * in pixels.
 */
internal object ForegroundSprites {
    const val PX_PER_DP = 2f

    /** A stretch of railing: two rails on pickets, a post with a finial every so often. */
    const val RAIL_W = 200f
    const val RAIL_H = 20f

    /** Three bollards with rounded caps. */
    const val BOLLARDS_W = 56f
    const val BOLLARDS_H = 18f

    /** A sign post: the pole, and the plate on it. */
    const val POST_W = 16f
    const val POST_H = 120f

    /** A branch hanging in from above (the left; mirror it for the right), with its leaves. */
    const val BRANCH_W = 100f
    const val BRANCH_H = 64f

    /** How soft the near things are, dp (the gaussian's sigma). */
    const val SOFT = 1.1f

    /** The top edge of a thing that stands up from the bottom: clear in its first two dp. */
    private fun top(yDp: Float): Float = Px.smooth(.5f, 2f, yDp)

    fun rail(): ForegroundSprite {
        val p = Plane(RAIL_W, RAIL_H)
        p.rect(0f, RAIL_W, 3.6f, 5.1f)
        p.rect(0f, RAIL_W, 9.2f, 10.3f)
        var x = 2.5f
        var k = 0
        while (x < RAIL_W) {
            if (k % 4 == 0) {
                p.rect(x - 1.1f, x + 1.1f, 2.6f, RAIL_H)
                p.disc(x, 1.9f, 1.5f)
            } else p.rect(x - .4f, x + .4f, 4.4f, RAIL_H)
            x += 5f
            k++
        }
        // the stretch ends softly at both ends
        return p.bake(SOFT) { xDp, yDp ->
            Px.smooth(0f, 24f, xDp) * Px.smooth(RAIL_W, RAIL_W - 24f, xDp) * top(yDp)
        }
    }

    fun bollards(): ForegroundSprite {
        val p = Plane(BOLLARDS_W, BOLLARDS_H)
        for (i in 0 until 3) {
            val cx = 10f + i * 18f
            p.rect(cx - 3.2f, cx + 3.2f, 6f, BOLLARDS_H)
            p.ellipse(cx, 6f, 3.2f, 2.2f)
            p.rect(cx - 3.6f, cx + 3.6f, 8.2f, 9.4f)
        }
        return p.bake(SOFT) { _, yDp -> top(yDp) }
    }

    fun post(): ForegroundSprite {
        val p = Plane(POST_W, POST_H)
        p.rect(6.3f, 9.7f, 12f, POST_H)
        // the plate, its corners rounded; a bracket under it
        p.fill(2f, 14f, 4f, 15f) { x, y ->
            val rx = max(0f, max(2f + 1.6f - x, x - (14f - 1.6f)))
            val ry = max(0f, max(4f + 1.6f - y, y - (15f - 1.6f)))
            rx * rx + ry * ry <= 1.6f * 1.6f
        }
        p.rect(5.4f, 10.6f, 14.6f, 16.4f)
        return p.bake(SOFT) { _, yDp -> top(yDp) }
    }

    fun branch(): ForegroundSprite {
        val p = Plane(BRANCH_W, BRANCH_H)
        // the branch: a curve from above the top edge down and across, tapering
        p.curve(14f, -6f, 38f, 16f, 80f, 40f, 3.4f, 1.1f)
        // its twigs
        p.curve(30f, 11f, 30f, 24f, 35f, 37f, 1.1f, .5f)
        p.curve(50f, 24f, 60f, 18f, 68f, 12f, 1.1f, .4f)
        p.curve(70f, 33f, 82f, 44f, 88f, 55f, 1f, .4f)
        p.curve(60f, 29f, 56f, 36f, 50f, 42f, .9f, .4f)
        // the leaves: clusters at the twigs' ends and along the branch
        p.leaves(35f, 39f, 9f, 6.5f, 1)
        p.leaves(68f, 11f, 8f, 6f, 2)
        p.leaves(84f, 42f, 10f, 7f, 3)
        p.leaves(89f, 56f, 7f, 5f, 4)
        p.leaves(50f, 43f, 8f, 5.5f, 5)
        p.leaves(24f, 18f, 7f, 5f, 6)
        p.leaves(58f, 26f, 6f, 4.5f, 7)
        return p.bake(SOFT * 1.15f)
    }

    fun bytes(): Int =
        px(RAIL_W) * px(RAIL_H) * 4 +
            px(BOLLARDS_W) * px(BOLLARDS_H) * 4 +
            px(POST_W) * px(POST_H) * 4 +
            px(BRANCH_W) * px(BRANCH_H) * 4

    private fun px(dp: Float) = (dp * PX_PER_DP).roundToInt()

    // ---- the raster: dp, x right, y down from the sprite's top

    private class Plane(wDp: Float, hDp: Float) {
        val w = (wDp * PX_PER_DP).roundToInt()
        val h = (hDp * PX_PER_DP).roundToInt()
        val a = FloatArray(w * h)

        inline fun fill(
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            inside: (Float, Float) -> Boolean,
        ) {
            val px0 = floor(x0 * PX_PER_DP).toInt().coerceAtLeast(0)
            val px1 = ceil(x1 * PX_PER_DP).toInt().coerceAtMost(w - 1)
            val py0 = floor(y0 * PX_PER_DP).toInt().coerceAtLeast(0)
            val py1 = ceil(y1 * PX_PER_DP).toInt().coerceAtMost(h - 1)
            for (py in py0..py1) for (px in px0..px1) {
                var n = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val xd = (px + (sx + .5f) / 3f) / PX_PER_DP
                    val yd = (py + (sy + .5f) / 3f) / PX_PER_DP
                    if (inside(xd, yd)) n++
                }
                if (n == 0) continue
                val i = py * w + px
                a[i] = max(a[i], n / 9f)
            }
        }

        fun rect(x0: Float, x1: Float, y0: Float, y1: Float) =
            fill(x0, x1, y0, y1) { x, y -> x >= x0 && x < x1 && y >= y0 && y < y1 }

        fun disc(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
            fill(cx - rx, cx + rx, cy - ry, cy + ry) { x, y ->
                val u = (x - cx) / rx
                val v = (y - cy) / ry
                u * u + v * v <= 1f
            }

        /**
         * A quadratic curve from ([x0], [y0]) through the control ([cx], [cy]) to ([x1], [y1]),
         * [r0] thick at its start and [r1] at its end, drawn as a chain of discs.
         */
        fun curve(
            x0: Float,
            y0: Float,
            cx: Float,
            cy: Float,
            x1: Float,
            y1: Float,
            r0: Float,
            r1: Float,
        ) {
            val steps = 48
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val u = 1f - t
                val x = u * u * x0 + 2f * u * t * cx + t * t * x1
                val y = u * u * y0 + 2f * u * t * cy + t * t * y1
                disc(x, y, r0 + (r1 - r0) * t)
            }
        }

        /**
         * A cluster of leaves about ([cx], [cy]), [rx] by [ry]: a small mass at the heart and
         * single leaves scattered over it, each a pointed ellipse turned outward its own way.
         */
        fun leaves(cx: Float, cy: Float, rx: Float, ry: Float, seed: Int) {
            ellipse(cx, cy, rx * .4f, ry * .4f)
            val count = (rx * ry * .5f).toInt().coerceAtLeast(6)
            for (i in 0 until count) {
                val ang = Noise.value(i * 1.7f, seed * 3f, 600 + seed) * 6.2832f
                val rr = sqrt(Noise.value(i * 2.3f + 5f, seed * 1f, 650 + seed)) * .9f
                val lx = cx + cos(ang) * rx * rr
                val ly = cy + sin(ang) * ry * rr
                val turn = ang + (Noise.value(i * 1f, 2f, 800 + seed) - .5f) * 1.4f
                leaf(lx, ly, 3.4f, 1.5f, turn)
            }
        }

        /** One leaf: a pointed ellipse [lw] long and [lh] wide, its tip [turn] round from +x. */
        fun leaf(lx: Float, ly: Float, lw: Float, lh: Float, turn: Float) {
            val c = cos(turn)
            val s = sin(turn)
            fill(lx - lw, lx + lw, ly - lw, ly + lw) { x, y ->
                val dx = x - lx
                val dy = y - ly
                val a = (dx * c + dy * s) / lw
                val b = (-dx * s + dy * c) / lh
                a >= -1f && a <= 1f && b * b <= (1f - a * a) * (1f - .4f * a)
            }
        }

        /**
         * The sprite: the plane blurred by [sigmaDp], the alpha of each pixel scaled by [fade] of
         * its dp place.
         */
        inline fun bake(
            sigmaDp: Float,
            fade: (Float, Float) -> Float = { _, _ -> 1f },
        ): ForegroundSprite {
            val b = blur(a, w, h, sigmaDp * PX_PER_DP)
            val pixels =
                IntArray(w * h) {
                    val xDp = (it % w + .5f) / PX_PER_DP
                    val yDp = (it / w + .5f) / PX_PER_DP
                    Px.argbF(Px.clamp01(b[it] * fade(xDp, yDp)), 1f, 1f, 1f)
                }
            return ForegroundSprite(w, h, pixels)
        }
    }

    /** A gaussian blur of [sigma] pixels, the edges clamped. */
    fun blur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = ceil(sigma * 3f).toInt()
        val k = FloatArray(2 * r + 1)
        var norm = 0f
        for (i in -r..r) {
            k[i + r] = exp(-(i * i) / (2f * sigma * sigma))
            norm += k[i + r]
        }
        for (i in k.indices) k[i] /= norm
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) {
                val xx = x + i
                if (xx in 0 until w) s += k[i + r] * src[y * w + xx]
            }
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) {
                val yy = y + i
                if (yy in 0 until h) s += k[i + r] * tmp[yy * w + x]
            }
            out[y * w + x] = s
        }
        return out
    }
}
