package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** A baked picture: [w] by [h] ARGB pixels. */
internal class GlassStrip(val w: Int, val h: Int, val pixels: IntArray)

/**
 * What a pane of glass mirrors, baked once and coloured by the hour when drawn ([BuildingPainter]
 * lays it over every unlit pane, the shop windows and the door, slid and tilted per pane):
 * - [sky]: the sky in front of the facade as a panorama [W] pixels round, from high overhead (its
 *   top row) down to the horizon (its last row), with soft, blurred cumulus drifting through it.
 *   Opaque; its channels are weights, not colours: red the clouds' lit tops, green their shaded
 *   undersides, blue the horizon's pale band, the rest of each pixel the sky overhead. A colour
 *   matrix turns them into the hour's colours, so one picture serves the whole day.
 * - [street]: the buildings across the street and the lamps in front of them, [W] round and as
 *   seamless, its alpha what covers the sky (the roofs and the water tanks on them, the posts, the
 *   pavement at its foot), its channels again weights: red the glass of their windows (the sky
 *   mirrored back, faintly), green the windows lit at night, blue the lamps' lenses; the rest the
 *   buildings' own dark. Its top row is clear, so it can be clamped above; its last is the street.
 * - [interior]: a dim room seen through the glass of some panes: blinds, curtains, a net.
 * - [glint]: the sun's specular hot spot on glass, white at an alpha, drawn additively. Pure
 *   Kotlin; deterministic.
 */
internal object GlassSprites {
    /** The panoramas' width, round (seamless), in pixels. */
    const val W = 512
    const val SKY_H = 96
    const val STREET_H = 64

    /** The street strip's top row, in the sky strip's rows: its roofs rise into the sky's foot. */
    const val STREET_AT = 60

    const val INTERIOR_W = 32
    const val INTERIOR_H = 72
    const val INTERIORS = 4
    const val GLINT = 32

    fun bytes(): Int =
        (W * SKY_H + W * STREET_H + INTERIORS * INTERIOR_W * INTERIOR_H + GLINT * GLINT) * 4

    /** [f] at [x] blended with itself a period on, so the panorama closes on itself. */
    private inline fun seam(x: Float, f: (Float) -> Float): Float {
        val t = x / W
        return f(x) * (1f - t) + f(x - W) * t
    }

    fun sky(): GlassStrip {
        val n = W * SKY_H
        val cloud = FloatArray(n)
        for (y in 0 until SKY_H) for (x in 0 until W) {
            val v = y / (SKY_H - 1f)
            val fx = x.toFloat()
            // heaps of cumulus, larger and further apart high up, flattening and crowding toward
            // the horizon (they are further away there), with clear sky between the groups
            val squash = 1f + 1.6f * v
            val heap = seam(fx) { Noise.fbm(it / 34f, y * squash / 20f, 911, 4, .45f) }
            val group = seam(fx) { Noise.value(it / 110f, 2.3f, 913) }
            val band = Px.smooth(.05f, .32f, v) * (1f - .45f * Px.smooth(.82f, 1f, v))
            val d = heap * 1.2f + group * .6f - .98f
            cloud[y * W + x] = Px.smooth(0f, .3f, d) * band
        }
        val px = IntArray(n)
        for (y in 0 until SKY_H) for (x in 0 until W) {
            val i = y * W + x
            val c = cloud[i]
            val v = y / (SKY_H - 1f)
            // lit where there is less cloud above than below (the heap's sunny top), shaded under
            val above = cloud[(y - 5).coerceAtLeast(0) * W + x]
            val below = cloud[(y + 5).coerceAtMost(SKY_H - 1) * W + x]
            val lit = Px.clamp01(.62f + (below - above) * 1.5f - .3f * v)
            val horizon = Px.smooth(.15f, 1f, v).pow(1.5f)
            val r = c * lit
            val g = c * (1f - lit)
            val b = (1f - c) * horizon
            px[i] = Px.argbF(1f, r, g, b)
        }
        return GlassStrip(W, SKY_H, px)
    }

    /** One of the buildings across the street, along the strip. */
    private class Block(val x0: Int, val x1: Int, val roof: Int, val seed: Int)

    fun street(): GlassStrip {
        val n = W * STREET_H
        // coverage and the three weights, each multiplied by the coverage (blurred so, then
        // divided)
        val a = FloatArray(n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        fun put(x: Int, y: Int, cov: Float, wr: Float, wg: Float, wb: Float) {
            if (y < 1 || y >= STREET_H) return
            val i = y * W + ((x % W) + W) % W
            a[i] = cov
            r[i] = wr * cov
            g[i] = wg * cov
            b[i] = wb * cov
        }
        val ground = STREET_H - 7
        // the blocks: their own widths and heights, ending exactly at the strip's end
        val blocks = ArrayList<Block>()
        var x0 = 0
        var k = 0
        while (x0 < W) {
            var bw = (44f + 62f * hash(k, 1)).toInt()
            if (W - x0 - bw < 40) bw = W - x0
            blocks += Block(x0, x0 + bw, (5f + 22f * hash(k, 2)).toInt(), k)
            x0 += bw
            k++
        }
        for (bl in blocks) {
            val shade = .04f * hash(bl.seed, 3)
            for (y in bl.roof until STREET_H) for (x in bl.x0 until bl.x1) put(
                x,
                y,
                1f,
                shade,
                0f,
                0f,
            )
            // the cornice: a lighter line under the roof's edge, a dark one under that
            for (x in bl.x0 until bl.x1) {
                put(x, bl.roof, 1f, .42f, 0f, 0f)
                put(x, bl.roof + 1, 1f, .26f, 0f, 0f)
                put(x, bl.roof + 2, 1f, 0f, 0f, 0f)
            }
            // a gap of a darker shadow between two blocks
            for (y in bl.roof until ground) put(bl.x1 - 1, y, 1f, 0f, 0f, 0f)
            // the windows: rows of panes mirroring the sky, some lit at night
            val pitch = 8 + (hash(bl.seed, 4) * 4f).toInt()
            val ww = 3 + (hash(bl.seed, 5) * 2.5f).toInt()
            val wh = 5 + (hash(bl.seed, 6) * 3f).toInt()
            val shopTop = ground - 9
            var wy = bl.roof + 6
            var row = 0
            while (wy + wh < shopTop - 2) {
                var wx = bl.x0 + 4 + (pitch - ww) / 2
                var col = 0
                while (wx + ww < bl.x1 - 3) {
                    val lit = hash(bl.seed * 31 + row, col + 40) < .3f
                    val pale = .4f + .35f * hash(bl.seed * 31 + row, col + 90)
                    for (y in wy until wy + wh) for (x in wx until wx + ww) {
                        val top = if (y < wy + 2) .15f else 0f
                        if (lit) put(x, y, 1f, 0f, .85f + top, 0f)
                        else put(x, y, 1f, pale + top, 0f, 0f)
                    }
                    wx += pitch
                    col++
                }
                wy += wh + 5
                row++
            }
            // the shop fronts at the foot: a band of glass, some lit at night
            val shopLit = hash(bl.seed, 7) < .55f
            for (y in shopTop until ground - 1) for (x in bl.x0 + 3 until bl.x1 - 3) {
                val mull = (x - bl.x0) % 14 == 0
                if (mull) put(x, y, 1f, 0f, 0f, 0f)
                else if (shopLit) put(x, y, 1f, .2f, .6f, 0f) else put(x, y, 1f, .55f, 0f, 0f)
            }
            // a wooden water tank on some roofs: a barrel on legs under a cone
            if (hash(bl.seed, 8) < .5f && bl.x1 - bl.x0 > 50) {
                val tx = bl.x0 + 10 + (hash(bl.seed, 9) * (bl.x1 - bl.x0 - 26)).toInt()
                val foot = bl.roof
                for (y in foot - 4 until foot) {
                    put(tx, y, 1f, 0f, 0f, 0f)
                    put(tx + 6, y, 1f, 0f, 0f, 0f)
                }
                for (y in foot - 11 until foot - 4) for (x in tx - 1..tx + 7) put(
                    x,
                    y,
                    1f,
                    0f,
                    0f,
                    0f,
                )
                for (j in 0..3) for (x in tx - 1 + j..tx + 7 - j) put(
                    x,
                    foot - 12 - j,
                    1f,
                    0f,
                    0f,
                    0f,
                )
            }
        }
        // the street at the foot, lighter where the sky lies on it
        for (y in ground until STREET_H) for (x in 0 until W) put(x, y, 1f, .3f, 0f, 0f)
        // the lamps in front of the buildings: a post, a head, its lens lit at night
        for (l in 0 until 3) {
            val lx = 40 + l * W / 3 + (hash(l, 20) * 30f).toInt()
            for (y in 24 until STREET_H - 3) put(lx, y, 1f, 0f, 0f, 0f)
            for (y in STREET_H - 6 until STREET_H - 3) for (x in lx - 1..lx + 1) put(
                x,
                y,
                1f,
                0f,
                0f,
                0f,
            )
            for (x in lx - 3..lx + 3) put(x, 23, 1f, 0f, 0f, 0f)
            for (x in lx - 2..lx + 2) put(x, 24, 1f, 0f, 0f, 1f)
            for (x in lx - 1..lx + 1) put(x, 25, 1f, 0f, 0f, 1f)
        }
        // a little soft, as a reflection is: a 3 by 3 blur round the strip
        val px = IntArray(n)
        for (y in 0 until STREET_H) for (x in 0 until W) {
            var sa = 0f
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var wsum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= STREET_H) continue
                val wgt = if (dx == 0 && dy == 0) 2f else if (dx == 0 || dy == 0) 1f else .5f
                val i = yy * W + ((x + dx) % W + W) % W
                sa += a[i] * wgt
                sr += r[i] * wgt
                sg += g[i] * wgt
                sb += b[i] * wgt
                wsum += wgt
            }
            val cov = sa / wsum
            px[y * W + x] =
                if (cov < .004f || y == 0) 0 else Px.argbF(cov, sr / sa, sg / sa, sb / sa)
        }
        return GlassStrip(W, STREET_H, px)
    }

    /**
     * Interior [k] of [INTERIORS], opaque, dim (it is seen through glass that mirrors the day): 0
     * venetian blinds half down, 1 curtains drawn back to the sides, 2 a roller blind a third down
     * over a dim room with a lamp's shade and a picture, 3 a net curtain over the lower part.
     */
    fun interior(k: Int): GlassStrip {
        val w = INTERIOR_W
        val h = INTERIOR_H
        val px = IntArray(w * h)
        val seed = 600 + k * 17
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / w
            val v = (y + .5f) / h
            // the room: a dark warm grey, darker toward its back and its floor, a little grain
            val grain = (Noise.value(x * .4f, y * .4f, seed) - .5f) * .03f
            var cr = .2f - .06f * v + grain
            var cg = .19f - .06f * v + grain
            var cb = .2f - .05f * v + grain
            // the back wall's far corner and the floor's line
            if (v > .78f) {
                cr -= .04f
                cg -= .045f
                cb -= .05f
            }
            when (k) {
                0 -> {
                    val drop = .48f
                    if (v < drop) {
                        val slat = (y % 4).toFloat()
                        val l = if (slat < 1f) .38f else if (slat < 3f) .56f - .04f * slat else .3f
                        cr = l * .96f
                        cg = l * .93f
                        cb = l * .86f
                    } else if (v < drop + .025f) {
                        cr = .22f
                        cg = .2f
                        cb = .18f
                    }
                }
                1 -> {
                    val reach = .3f + .05f * Noise.value(v * 3f, 1f, seed)
                    val left = u < reach
                    val right = u > 1f - reach
                    if (left || right) {
                        val e = if (left) u / reach else (1f - u) / reach
                        val fold = .5f + .5f * kotlin.math.cos((u * 7f + .3f) * 6.283f)
                        val l = .55f + .3f * fold - .15f * e
                        cr = .46f * l
                        cg = .2f * l
                        cb = .19f * l
                    }
                    // the rail across the top
                    if (y < 3) {
                        cr = .18f
                        cg = .16f
                        cb = .15f
                    }
                }
                2 -> {
                    if (v < .32f) {
                        val l = .62f - .1f * v + (if (y % 9 == 0) -.05f else 0f)
                        cr = l * .97f
                        cg = l * .93f
                        cb = l * .84f
                    } else if (v < .345f) {
                        cr = .3f
                        cg = .28f
                        cb = .25f
                    } else {
                        // a picture on the back wall and a lamp's shade on its stand
                        val pic = u in .14f..0.42f && v in .42f..0.58f
                        if (pic) {
                            cr = .32f
                            cg = .27f
                            cb = .22f
                        }
                        val shade =
                            v in .52f..0.6f && kotlin.math.abs(u - .72f) < .07f + (v - .52f) * .9f
                        if (shade) {
                            cr = .5f
                            cg = .42f
                            cb = .3f
                        }
                        if (v > .6f && v < .9f && kotlin.math.abs(u - .72f) < .02f) {
                            cr = .1f
                            cg = .1f
                            cb = .1f
                        }
                    }
                }
                else -> {
                    if (v > .36f) {
                        val thread =
                            .04f * kotlin.math.sin(x * 2.1f) +
                                .02f * Noise.value(x * .3f, v * 5f, seed)
                        val sag = Px.smooth(.36f, .4f, v)
                        val l = (.58f + thread) * sag + cr * (1f - sag)
                        cr = l
                        cg = l * .98f
                        cb = l * .94f
                    }
                }
            }
            px[y * w + x] = Px.argbF(1f, Px.clamp01(cr), Px.clamp01(cg), Px.clamp01(cb))
        }
        return GlassStrip(w, h, px)
    }

    /**
     * The sun's glint on glass: a small hot core, a soft halo round it and a faint four-pointed
     * star of the lens, white at an alpha, clear at its rim (so a clamped edge adds nothing).
     */
    fun glint(): GlassStrip {
        val s = GLINT
        val px = IntArray(s * s)
        val mid = s / 2f
        for (y in 0 until s) for (x in 0 until s) {
            val dx = (x + .5f - mid) / mid
            val dy = (y + .5f - mid) / mid
            val r = sqrt(dx * dx + dy * dy)
            val core = exp(-(r / .1f) * (r / .1f))
            val halo = .5f / (1f + (r / .16f) * (r / .16f))
            val star =
                .28f *
                    (exp(-(dy / .035f) * (dy / .035f)) + .6f * exp(-(dx / .035f) * (dx / .035f))) *
                    (1f - Px.smooth(0f, .9f, r))
            val a = (core + halo + star) * (1f - Px.smooth(.7f, .98f, r))
            px[y * s + x] = if (a < .003f) 0 else Px.argbF(Px.clamp01(a), 1f, 1f, 1f)
        }
        return GlassStrip(s, s, px)
    }

    private fun hash(a: Int, b: Int): Float {
        var n = a * 374761393 + b * 668265263 + 977 * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777216f
    }
}

/**
 * Where a pane's reflection comes from in the [GlassSprites] strips, shared by the painter and the
 * JVM harness that looks at it. A facade is a mirror: what a pane shows is the street behind the
 * viewer, as if seen through the glass at [DEPTH] times the pane's distance, so it slides against
 * the facade as the view pans; a higher pane sees higher (more sky, the roofs across the street low
 * in it); and no two panes stand quite in one plane, so each is turned and raised a hair
 * differently and its reflection breaks from its neighbours'.
 */
internal object GlassMap {
    /** Units of a pane one strip pixel spans. */
    const val K = .62f

    /** The mirrored street's distance against the facade's: it moves this much faster. */
    const val DEPTH = 1.5f

    /** The strips' row a pane at the pavement sees at its middle, and how much higher per unit. */
    const val EYE = 122f
    const val RISE = .3f

    /** The clouds move with the view at this fraction of the street's rate, and drift (px/s). */
    const val SKY_PAN = .55f
    const val DRIFT = .9f

    /** How far a pane's tilt slides its reflection (px) and lifts it (rows), and scales it. */
    const val TILT_U = 14f
    const val TILT_V = 5f
    const val TILT_K = .05f

    /**
     * The pane at street x [sx] (its middle), [w] by [h] units, its middle [z] units over the
     * pavement, [seed] its own, in a view whose middle is at [viewX], [phase] the building's own
     * slide, [seconds] the clock: into [out] the scale k (units a strip pixel spans), the street
     * strip's column at the pane's left edge, the sky strip's, the strips' row at its top, and the
     * pane's tilt (-.5 to .5).
     */
    fun place(
        sx: Float,
        z: Float,
        w: Float,
        h: Float,
        seed: Int,
        viewX: Float,
        phase: Float,
        seconds: Float,
        out: FloatArray,
    ) {
        val tilt = tilt(seed)
        val lift = lift(seed)
        val k = K * (1f + TILT_K * 2f * tilt)
        val p = (viewX + (sx - viewX) * DEPTH + phase) / k + tilt * TILT_U * 2f
        val u0 = p - w / 2f / k
        val vc = EYE - z * RISE + lift * TILT_V * 2f
        out[0] = k
        out[1] = round(u0)
        out[2] = round(u0 * SKY_PAN + seconds * DRIFT)
        out[3] = vc - h / 2f / k
        out[4] = tilt
    }

    /** [u] brought into one turn of the strips (they repeat), so no offset grows without end. */
    private fun round(u: Float): Float = u - kotlin.math.floor(u / GlassSprites.W) * GlassSprites.W

    fun tilt(seed: Int): Float = unit(seed, 1) - .5f

    fun lift(seed: Int): Float = unit(seed, 2) - .5f

    /** Whether the pane [seed] has a room's blinds or curtains to see, and which. -1: none. */
    fun interior(seed: Int): Int =
        if (unit(seed, 3) < .5f) -1 else (unit(seed, 4) * GlassSprites.INTERIORS).toInt()

    private fun unit(seed: Int, salt: Int): Float {
        var n = seed * 668265263 + salt * 374761393 + 1013
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777216f
    }

    /**
     * The colours the strips' weights stand for at [sky]: into [out], twelve for the sky strip (its
     * red, green and blue weights' colours, then the rest's: the clouds' lit tops and shade, the
     * horizon, the sky overhead) and twelve for the street's (its windows' glass, the lit windows,
     * the lamps' lenses, the buildings' own dark). As mirrored by dark glass: a little darker and
     * deeper than the sky itself, the lights warm at night.
     */
    fun colours(sky: SkyColors, out: FloatArray) {
        val inside = SceneInk.GlassNight
        val dark = sky.dark
        val zenith = lerp(lerp(sky.sky[0], sky.sky[1], .55f), inside, .3f)
        val horizon = lerp(lerp(sky.sky[2], sky.sky[1], .2f), inside, .14f)
        val cloudLit = lerp(lerp(sky.cloudLit, sky.cloudBody, .45f), inside, .16f + .1f * dark)
        val cloudShade = lerp(lerp(sky.cloudShade, sky.cloudBody, .25f), inside, .22f)
        val across = lerp(lerp(sky.far[2], sky.ground[1], .6f), inside, .5f + .2f * dark)
        val glass = lerp(across, horizon, .42f * (1f - .7f * dark))
        val lit = lerp(glass, SceneInk.WindowLit, Px.smooth(.15f, .8f, dark))
        val lamp =
            lerp(lerp(across, Color.Black, .3f), SceneInk.LampLight, Px.smooth(.2f, .7f, dark))
        var i = 0
        for (c in arrayOf(cloudLit, cloudShade, horizon, zenith, glass, lit, lamp, across)) {
            out[i++] = c.red
            out[i++] = c.green
            out[i++] = c.blue
        }
    }
}
