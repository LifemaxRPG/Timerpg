package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.CycleRig
import com.lifemax.rpg.feature.habits.domain.DogRig
import com.lifemax.rpg.feature.habits.domain.Part
import com.lifemax.rpg.feature.habits.domain.Shape
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.StreetLooks
import com.lifemax.rpg.feature.habits.domain.StreetRig
import com.lifemax.rpg.feature.habits.domain.WalkerJoints
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The street's people as pre-rendered pictures: the walkers ([StreetRig]) at a close-up's resolution, the dog and
 * the cyclist ([DogRig], [CycleRig]) through the same rasteriser (the front road's cars are [CarArt]'s). Pure
 * Kotlin: pixels only; [SceneSprites] turns them into images the first time each is drawn.
 */

/**
 * One baked walker frame: the picture (cut to its pixels, the feet's middle at [Sprite.ox],
 * [Sprite.oy]) and its joints.
 */
internal class WalkerSprite(val sprite: Sprite, val joints: WalkerJoints)

internal object StreetSprites {
    /** Pixels to a street unit for the walkers: what the phone shows at the focus camera, 3x. */
    const val PPU = 6.4f

    /** The bicycle's rider is this look. */
    const val RIDER = 3

    /** The shapes in their own colours ([StreetRaster]), at [size] times the rig. */
    private fun bake(shapes: List<Shape>, size: Float, ppu: Float): Sprite =
        StreetRaster.bake(shapes, size * ppu)

    /**
     * Walker [look]'s frame [frame] ([StreetRig.TOTAL_FRAMES] of them), at [StreetLife.SIZE] times
     * the rig.
     */
    fun walker(look: Int, frame: Int): WalkerSprite {
        val fig = StreetRig.body(look, frame)
        return WalkerSprite(bake(fig.shapes, StreetLife.SIZE, PPU), fig.joints)
    }

    /**
     * Walker [look]'s layer [part] ([Part]): its joint at the sprite's origin, hanging down (a head
     * upright), to be turned about it as it is drawn ([StreetLayers]). A forearm is cut round the
     * elbow, so it closes over the upper arm with no edge.
     */
    fun part(look: Int, part: Int): Sprite {
        val shapes =
            when (part) {
                // shaded as if the limb ran on into the shoulder, then cut back to its round, so
                // its top lies over the body with no dark rim
                Part.UPPER_N,
                Part.UPPER_F ->
                    return cut(
                        bake(StreetRig.upperArm(look, part == Part.UPPER_N), StreetLife.SIZE, PPU),
                        StreetRig.shoulderR(look) * StreetLife.SIZE * PPU,
                    )
                Part.HEAD -> StreetRig.head(look, false)
                Part.HEAD_GLANCE -> StreetRig.head(look, true)
                Part.HEAD_AWAY -> StreetRig.head(look, -1f)
                Part.STICK -> StreetRig.stick(look)
                Part.TAIL -> StreetRig.tail(look)
                Part.HANDBAG -> StreetRig.handbag(look)
                Part.PHONE -> StreetRig.phone()
                Part.CUP -> StreetRig.cup()
                else -> {
                    val near = part < Part.UPPER_F
                    val hand = part - if (near) Part.FORE_N else Part.FORE_F
                    return cut(
                        bake(StreetRig.forearm(look, near, hand), StreetLife.SIZE, PPU),
                        StreetRig.elbowR(look) * StreetLife.SIZE * PPU,
                    )
                }
            }
        if (shapes.isEmpty()) return Sprite(1, 1, StreetLife.SIZE * PPU, 0f, 0f, IntArray(1))
        return bake(shapes, StreetLife.SIZE, PPU)
    }

    /**
     * [s] with what is above its origin and outside a round of [r] pixels about it taken off,
     * softly: a forearm's stub, cut back to the elbow.
     */
    private fun cut(s: Sprite, r: Float): Sprite {
        val px = s.pixels.copyOf()
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val dy = y + .5f - s.oy
            if (dy >= 0f) continue
            val dx = x + .5f - s.ox
            val d = sqrt(dx * dx + dy * dy)
            val k = (r - d + .5f).coerceIn(0f, 1f)
            if (k >= 1f) continue
            val i = y * s.w + x
            val a = ((px[i] ushr 24) * k).toInt()
            px[i] = if (a <= 0) 0 else (a shl 24) or (px[i] and 0xFFFFFF)
        }
        return PeopleSprites.crop(Sprite(s.w, s.h, s.ppu, s.ox, s.oy, px))
    }

    /** The dog's frame [frame] ([DogRig.TOTAL_FRAMES]). */
    fun dog(frame: Int): Sprite = bake(DogRig.frame(frame), StreetLife.DOG_SIZE, PPU)

    /**
     * The bicycle and its rider at crank frame [frame] ([StreetGait.CRANK_FRAMES]), wheels apart.
     */
    fun cycle(frame: Int): Sprite = bake(CycleRig.frame(RIDER, frame), StreetLife.SIZE, PPU)

    /** A spoked wheel of radius [r] units: tyre, rim, spokes and hub, centred. */
    fun wheel(r: Float, ppu: Float, spokes: Int, tyre: Float, hubR: Float, solid: Boolean): Sprite {
        val n = ceil(r * ppu * 2f).toInt() + 4
        val c = Canvas(n, n)
        val cx = n / 2f
        val cy = n / 2f
        val R = r * ppu
        c.fill(0x0B0D12) { x, y -> ring(x, y, cx, cy, R, R - tyre * ppu) }
        c.fill(0x9AA0AC) { x, y -> ring(x, y, cx, cy, R - tyre * ppu, R - tyre * ppu - .45f * ppu) }
        if (solid) {
            c.fill(0x2A2E38, .9f) { x, y -> circle(x, y, cx, cy, R - tyre * ppu - .45f * ppu) }
            c.fill(0x8A8F9A) { x, y -> circle(x, y, cx, cy, hubR * ppu) }
            // five bolt holes, so the turning shows
            for (i in 0 until 5) {
                val a = i / 5f * 2f * Math.PI.toFloat()
                c.fill(0x14171E) { x, y ->
                    circle(
                        x,
                        y,
                        cx + cos(a) * hubR * ppu * .62f,
                        cy + sin(a) * hubR * ppu * .62f,
                        .36f * ppu,
                    )
                }
            }
            c.fill(0x14171E) { x, y -> circle(x, y, cx, cy, .35f * ppu) }
        } else {
            // twice [spokes] fine spokes, laced as a real wheel is: each leaves the hub's flange
            // to one side of the line to its spot on the rim, so they cross
            val rim = R - tyre * ppu - .45f * ppu
            val n = spokes * 2
            for (i in 0 until n) {
                val a = i / n.toFloat() * 2f * Math.PI.toFloat()
                val h = a + (if (i % 2 == 0) .55f else -.55f)
                c.fill(0xAEB4BE, .6f) { x, y ->
                    segment(
                        x,
                        y,
                        cx + cos(h) * hubR * ppu * .8f,
                        cy + sin(h) * hubR * ppu * .8f,
                        cx + cos(a) * rim,
                        cy + sin(a) * rim,
                        .07f * ppu,
                    )
                }
            }
            // the rim's inner, darker lip and the hub's flange
            c.fill(0x5A606A) { x, y -> ring(x, y, cx, cy, rim + .05f * ppu, rim - .16f * ppu) }
            c.fill(0x8E949E) { x, y -> circle(x, y, cx, cy, hubR * ppu) }
            c.fill(0x3A3F4A) { x, y -> circle(x, y, cx, cy, hubR * ppu * .55f) }
            // a reflector clipped to two spokes, so the turning reads (and it catches a lamp)
            val ra = .3f
            c.fill(0xD8862A, .9f) { x, y ->
                roundRect(
                    x,
                    y,
                    cx + cos(ra) * rim * .62f - .45f * ppu,
                    cy + sin(ra) * rim * .62f - .3f * ppu,
                    cx + cos(ra) * rim * .62f + .45f * ppu,
                    cy + sin(ra) * rim * .62f + .3f * ppu,
                    .15f * ppu,
                )
            }
        }
        return Sprite(n, n, ppu, cx, cy, c.argb())
    }

    /** What the whole street set would take, every frame baked, in bytes. */
    fun bytes(): Int {
        var total = walkerBytes()
        for (f in 0 until DogRig.TOTAL_FRAMES) dog(f).let { total += it.w * it.h * 4 }
        for (f in 0 until StreetGait.CRANK_FRAMES) cycle(f).let { total += it.w * it.h * 4 }
        total += CarArt.bytes()
        return total
    }

    /** What the walkers alone take, every frame of every look baked, in bytes. */
    fun walkerBytes(): Int {
        var total = 0
        for (l in 0 until StreetLooks.COUNT) {
            for (f in 0 until StreetRig.TOTAL_FRAMES) {
                val s = walker(l, f).sprite
                total += s.w * s.h * 4
            }
            for (p in 0 until Part.COUNT) {
                val s = part(l, p)
                total += s.w * s.h * 4
            }
        }
        return total
    }

    // ---- a small painter: float RGBA composited over, anti-aliased shapes from signed distances

    internal class Canvas(val w: Int, val h: Int) {
        val px = FloatArray(w * h * 4)

        fun over(i: Int, r: Float, g: Float, b: Float, a: Float) {
            if (a <= 0f) return
            val k = i * 4
            val da = px[k + 3]
            val oa = a + da * (1f - a)
            if (oa <= 0f) return
            px[k] = (r * a + px[k] * da * (1f - a)) / oa
            px[k + 1] = (g * a + px[k + 1] * da * (1f - a)) / oa
            px[k + 2] = (b * a + px[k + 2] * da * (1f - a)) / oa
            px[k + 3] = oa
        }

        fun fill(
            rgb: Int,
            alpha: Float = 1f,
            shade: ((Float, Float) -> Float)? = null,
            sdf: (Float, Float) -> Float,
        ) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            for (y in 0 until h) for (x in 0 until w) {
                val cx = x + .5f
                val cy = y + .5f
                val cov = Px.clamp01(.5f - sdf(cx, cy))
                if (cov <= 0f) continue
                val s = shade?.invoke(cx, cy) ?: 1f
                over(
                    y * w + x,
                    Px.clamp01(r * s),
                    Px.clamp01(g * s),
                    Px.clamp01(b * s),
                    cov * alpha,
                )
            }
        }

        /** Takes away what is inside [sdf]. */
        fun cut(sdf: (Float, Float) -> Float) {
            for (y in 0 until h) for (x in 0 until w) {
                val cov = Px.clamp01(.5f - sdf(x + .5f, y + .5f))
                if (cov <= 0f) continue
                px[(y * w + x) * 4 + 3] *= 1f - cov
            }
        }

        fun soft(rgb: Int, field: (Float, Float) -> Float) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            for (y in 0 until h) for (x in 0 until w) {
                val a = Px.clamp01(field(x + .5f, y + .5f))
                if (a > 0f) over(y * w + x, r, g, b, a)
            }
        }

        fun argb(): IntArray =
            IntArray(w * h) {
                val k = it * 4
                val a = px[k + 3]
                if (a <= 0f) 0 else Px.argbF(a, px[k], px[k + 1], px[k + 2])
            }
    }

    private fun roundRect(
        x: Float,
        y: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r: Float,
    ): Float {
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val hw = abs(x1 - x0) / 2f
        val hh = abs(y1 - y0) / 2f
        val rr = min(r, min(hw, hh))
        val qx = abs(x - cx) - (hw - rr)
        val qy = abs(y - cy) - (hh - rr)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - rr
    }

    private fun circle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return sqrt(dx * dx + dy * dy) - r
    }

    private fun ring(x: Float, y: Float, cx: Float, cy: Float, outer: Float, inner: Float): Float =
        max(circle(x, y, cx, cy, outer), -circle(x, y, cx, cy, inner))

    private fun segment(
        x: Float,
        y: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r: Float,
    ): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = max(1e-6f, dx * dx + dy * dy)
        val t = (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
        val qx = x - (x0 + dx * t)
        val qy = y - (y0 + dy * t)
        return sqrt(qx * qx + qy * qy) - r
    }

    /** A convex polygon, clockwise on a y-down canvas. */
    private fun poly(x: Float, y: Float, xs: FloatArray, ys: FloatArray): Float {
        var d = -1e9f
        val n = xs.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = xs[j] - xs[i]
            val ey = ys[j] - ys[i]
            val len = max(1e-6f, sqrt(ex * ex + ey * ey))
            val nx = ey / len
            val ny = -ex / len
            d = max(d, (x - xs[i]) * nx + (y - ys[i]) * ny)
        }
        return d
    }
}
