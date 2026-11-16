package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Building
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One baked strip of a far-city row, one [Ambient.SKYLINE_SPAN] wide and periodic (its right edge
 * runs on into its left), at [ppd] pixels a dp. Its top row of pixels stands [topDp] above the
 * horizon and its bottom row [footDp] above it. A body strip is white with the row's coverage in
 * its alpha, thinned by the horizon's air ([SkylineSprites.haze]); a window strip is opaque, each
 * lit window a soft spot in one colour channel: red the warm ones, green the pale, blue the cool.
 */
internal class SkylineStrip(
    val w: Int,
    val h: Int,
    val ppd: Float,
    val topDp: Float,
    val footDp: Float,
    val pixels: IntArray,
) {
    val bytes: Int
        get() = w * h * 4
}

/** A far row baked: its body, and its windows (none for the furthest row, which is never lit). */
internal class SkylineRow(val body: SkylineStrip, val windows: SkylineStrip?)

/**
 * The far city's three rows, each painted once as a strip and drawn as an image, so the distance
 * reads: the further the row, the coarser it is baked and the softer its edges (depth of field),
 * the more its colour thins into the horizon's air (aerial perspective, baked into its alpha), and
 * the more of its roofline is only a silhouette. Rooflines are broken by water tanks, parapets,
 * set-back top floors, a spire, a dome, a crane and the antennas (the aircraft-warning lights
 * themselves stay points of light, drawn by the backdrop). Pure Kotlin; deterministic; made once.
 */
internal object SkylineSprites {
    /**
     * A row: the skyline's [seed] and its heights [lo] to [hi] (dp, as [Ambient.skyline] takes
     * them), how finely it is baked ([ppd], its windows at [winPpd]: 0 for a row never lit), how
     * soft it is drawn ([blur], dp: the gaussian's sigma, over what the coarse bake itself
     * softens), how far its roof details may rise ([extra], dp), and how much of the horizon's air
     * it is seen through at its top and at its foot ([hazeTop], [hazeFoot]).
     */
    class Spec(
        val seed: Int,
        val lo: Int,
        val hi: Int,
        val ppd: Float,
        val winPpd: Float,
        val blur: Float,
        val extra: Float,
        val hazeTop: Float,
        val hazeFoot: Float,
    )

    /** Furthest first. */
    val ROWS: Array<Spec> =
        arrayOf(
            Spec(5, 34, 84, .5f, 0f, 1.25f, 15f, .34f, .7f),
            Spec(23, 22, 58, .75f, .75f, .85f, 15f, .2f, .58f),
            Spec(41, 12, 38, 1f, .75f, .55f, 11f, .08f, .46f),
        )

    /** The horizon's air thickens over this many dp down to the horizon. */
    const val HAZE_SPAN = 90f

    private val buildings: Array<List<Building>> =
        Array(ROWS.size) { Ambient.skyline(ROWS[it].seed, ROWS[it].lo, ROWS[it].hi, 0f, SPAN) }

    /** The buildings of row [r], as [Ambient.skyline] lays them. */
    fun buildings(r: Int): List<Building> = buildings[r]

    /** The tallest three of the furthest two rows carry the red lights. */
    fun warned(r: Int): BooleanArray {
        val row = buildings[r]
        val sorted = row.indices.sortedByDescending { row[it].h }.take(if (r < 2) 3 else 0)
        return BooleanArray(row.size) { it in sorted }
    }

    /** The height of an antenna on building [i] of row [r] (dp), 0 for none. */
    fun antenna(r: Int, i: Int, warned: Boolean): Float =
        if (r == 2) 0f
        else if (warned) 6f + 4f * Ambient.hash(r, i, 6)
        else if (Ambient.hash(r, i, 7) < .14f) 4f + 4f * Ambient.hash(r, i, 8) else 0f

    /**
     * Building [i] of row [r]'s few windows: (across, down) shares of it and a kind, three numbers
     * each. A kind under .6 is a warm light, under .85 a pale one, else a cool one.
     */
    fun windows(r: Int, i: Int): FloatArray {
        val b = buildings[r][i]
        val n = (b.h / 9f).toInt().coerceIn(1, 7)
        return FloatArray(n * 3) { k ->
            val w = k / 3
            when (k % 3) {
                0 -> .18f + .6f * Ambient.hash(r * 1000 + i, w * 2, 3)
                1 -> .12f + .8f * Ambient.hash(r * 1000 + i, w * 2 + 1, 3)
                else -> Ambient.hash(r * 1000 + i, w, 5)
            }
        }
    }

    /** Which colour channel (0 red warm, 1 green pale, 2 blue cool) a window of [kind] is in. */
    fun channel(kind: Float): Int = if (kind < .6f) 0 else if (kind < .85f) 1 else 2

    /** How wide a window of [kind] is, dp. */
    fun wide(kind: Float): Float = if (kind > .45f && kind < .7f) 2.6f else 1.7f

    /**
     * How much of the horizon's air row [r] is seen through [dpAbove] the horizon: the row's top
     * haze high up, thickening over [HAZE_SPAN] to its foot haze at the horizon (the same curve the
     * backdrop's brush for the row's solid feet follows).
     */
    fun haze(r: Int, dpAbove: Float): Float {
        val s = ROWS[r]
        val t = ((HAZE_SPAN - dpAbove) / HAZE_SPAN).coerceIn(0f, 1f)
        val mid = s.hazeTop + .22f
        return if (t < .5f) lerp(s.hazeTop, mid, t * 2f) else lerp(mid, s.hazeFoot, (t - .5f) * 2f)
    }

    /** Where the baked band stops and the solid feet begin, dp above the horizon. */
    fun footDp(r: Int): Float = max(0f, ROWS[r].lo - margin(r))

    fun topDp(r: Int): Float = ROWS[r].hi + ROWS[r].extra + margin(r)

    private fun margin(r: Int): Float = ceil(3f * ROWS[r].blur * ROWS[r].ppd + 1f) / ROWS[r].ppd

    /** Row [r], baked. */
    fun generate(r: Int): SkylineRow =
        SkylineRow(body(r), if (ROWS[r].winPpd > 0f) lights(r) else null)

    /** Bytes the three rows hold. */
    fun bytes(): Int {
        var n = 0
        for (r in ROWS.indices) {
            val s = ROWS[r]
            n += px(SPAN, s.ppd) * px(topDp(r) - footDp(r), s.ppd) * 4
            if (s.winPpd > 0f) n += px(SPAN, s.winPpd) * px(s.hi + WIN_TOP, s.winPpd) * 4
        }
        return n
    }

    private const val SPAN = Ambient.SKYLINE_SPAN

    /** The window strips reach this far over the tallest roof (dp), so a top window's spot fits. */
    private const val WIN_TOP = 2f

    private fun px(dp: Float, ppd: Float) = (dp * ppd).roundToInt()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- the body

    private fun body(r: Int): SkylineStrip {
        val s = ROWS[r]
        val top = topDp(r)
        val foot = footDp(r)
        val p = Plane(px(SPAN, s.ppd), px(top - foot, s.ppd), s.ppd, top)
        val row = buildings[r]
        val warned = warned(r)
        val crane = craneAt(r)
        for (i in row.indices) {
            val b = row[i]
            val x0 = b.x
            val x1 = b.x + b.w
            val h = b.h
            // the body, and its top floors set back on some
            val setback = b.w >= 16f && h >= s.lo + 8f && Ambient.hash(r * 77 + i, 31) < .28f
            if (setback) {
                val tier = (h * .16f).coerceIn(3f, 10f)
                val inset = b.w * .18f
                p.rect(x0, x1, 0f, h - tier)
                p.rect(x0 + inset, x1 - inset, h - tier - .5f, h)
                if (Ambient.hash(r * 77 + i, 32) < .5f)
                    p.rect(x0 + inset - .5f, x1 - inset + .5f, h, h + 1f)
            } else {
                p.rect(x0, x1, 0f, h)
                // a parapet: a lip a little wider than the front
                if (Ambient.hash(r * 77 + i, 32) < .45f) p.rect(x0 - .5f, x1 + .5f, h, h + 1.1f)
            }
            // a water tank on its legs, with a shallow cap
            if (!setback && b.w >= 18f && Ambient.hash(r * 77 + i, 33) < .3f) {
                val tx = x0 + b.w * (.2f + .5f * Ambient.hash(r * 77 + i, 34))
                p.rect(tx, tx + .7f, h, h + 1.6f)
                p.rect(tx + 3.3f, tx + 4f, h, h + 1.6f)
                p.rect(tx - .3f, tx + 4.3f, h + 1.6f, h + 4.8f)
                p.rect(tx + .6f, tx + 3.4f, h + 4.8f, h + 5.6f)
            }
            // a spire on a tall one
            if (h > s.lo + (s.hi - s.lo) * .55f && Ambient.hash(r * 77 + i, 35) < .1f) {
                val cx = x0 + b.w / 2f
                val ht = 6f + 4f * Ambient.hash(r * 77 + i, 36)
                p.fill(cx - 1.8f, cx + 1.8f, h, h + ht) { x, y ->
                    y >= h && y < h + ht && abs(x - cx) < 1.7f * (1f - (y - h) / ht)
                }
            }
            // a dome, with a finial
            if (!setback && b.w >= 16f && Ambient.hash(r * 77 + i, 37) < .08f) {
                val cx = x0 + b.w / 2f
                val rx = b.w * .36f
                val ry = b.w * .2f
                p.fill(cx - rx, cx + rx, h, h + ry) { x, y ->
                    val u = (x - cx) / rx
                    val v = (y - h) / ry
                    y >= h && u * u + v * v <= 1f
                }
                p.rect(cx - .4f, cx + .4f, h + ry - .2f, h + ry + 1.4f)
            }
            // the tower crane: a mast, a jib with its counter-weight, a cable and its hook
            if (i == crane) {
                val mx = x0 + b.w * .38f
                p.rect(mx - .6f, mx + .6f, h, h + 12f)
                p.rect(mx - 5f, mx + 13f, h + 11.2f, h + 12.1f)
                p.rect(mx - 1f, mx + 1f, h + 12f, h + 13.6f)
                p.rect(mx - 5f, mx - 2.6f, h + 10.2f, h + 11.2f)
                p.rect(mx + 10.6f, mx + 11.1f, h + 5f, h + 11.2f)
                p.rect(mx + 9.9f, mx + 11.8f, h + 4.1f, h + 5.1f)
            }
            // the antenna
            val ant = antenna(r, i, warned[i])
            if (ant > 0f) {
                val ax = x0 + b.w * .6f
                p.rect(ax - .5f, ax + .5f, h, h + ant)
            }
        }
        val a = blur(p.a, p.w, p.h, s.blur * s.ppd)
        val pixels = IntArray(p.w * p.h)
        for (y in 0 until p.h) {
            val dpAbove = top - (y + .5f) / s.ppd
            val air = 1f - haze(r, dpAbove)
            for (x in 0 until p.w) pixels[y * p.w + x] = Px.argbF(a[y * p.w + x] * air, 1f, 1f, 1f)
        }
        return SkylineStrip(p.w, p.h, s.ppd, top, foot, pixels)
    }

    /** The one building of row [r] with a crane on it (-1 for none): a middling one. */
    private fun craneAt(r: Int): Int {
        if (r >= 2) return -1
        val s = ROWS[r]
        val row = buildings[r]
        var best = -1
        var bestScore = 0f
        for (i in row.indices) {
            val h = row[i].h
            if (h < s.lo + (s.hi - s.lo) * .3f || h > s.lo + (s.hi - s.lo) * .7f) continue
            if (row[i].w < 16f) continue
            val score = Ambient.hash(r * 77 + i, 38)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    // ---- the windows

    private fun lights(r: Int): SkylineStrip {
        val s = ROWS[r]
        val top = s.hi + WIN_TOP
        val w = px(SPAN, s.winPpd)
        val h = px(top, s.winPpd)
        val planes = Array(3) { Plane(w, h, s.winPpd, top) }
        val row = buildings[r]
        for (i in row.indices) {
            val b = row[i]
            val win = windows(r, i)
            for (k in 0 until win.size / 3) {
                val kind = win[k * 3 + 2]
                val x0 = b.x + win[k * 3] * b.w
                val x1 = x0 + wide(kind)
                // the last building's windows past the span's end belong to the first's columns
                if (x1 > SPAN) continue
                val y1 = b.h - win[k * 3 + 1] * b.h
                planes[channel(kind)].rect(x0, x1, y1 - 1.7f, y1)
            }
        }
        val c = Array(3) { blur(planes[it].a, w, h, .55f * s.winPpd + .2f) }
        // a soft spot keeps a hot core: lift what the blur spread thin
        val pixels =
            IntArray(w * h) {
                Px.argbF(
                    1f,
                    Px.clamp01(c[0][it] * 1.6f),
                    Px.clamp01(c[1][it] * 1.6f),
                    Px.clamp01(c[2][it] * 1.6f),
                )
            }
        return SkylineStrip(w, h, s.winPpd, top, 0f, pixels)
    }

    // ---- the raster

    /**
     * A coverage plane over the strip: [ppd] pixels a dp, its top row [topDp] above the horizon.
     */
    private class Plane(val w: Int, val h: Int, val ppd: Float, val topDp: Float) {
        val a = FloatArray(w * h)

        /**
         * Fills where [inside] holds (x dp along the span, y dp above the horizon) inside the box
         * [x0]..[x1] by [y0]..[y1], nine samples a pixel, added to what is there (the shapes abut
         * or touch, so two that share a pixel each hold part of it); x wraps round the span.
         */
        inline fun fill(
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            inside: (Float, Float) -> Boolean,
        ) {
            val px0 = floor(x0 * ppd).toInt()
            val px1 = ceil(x1 * ppd).toInt()
            val py0 = floor((topDp - y1) * ppd).toInt().coerceAtLeast(0)
            val py1 = ceil((topDp - y0) * ppd).toInt().coerceAtMost(h - 1)
            for (py in py0..py1) for (px in px0..px1) {
                var n = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val xd = (px + (sx + .5f) / 3f) / ppd
                    val yd = topDp - (py + (sy + .5f) / 3f) / ppd
                    if (inside(xd, yd)) n++
                }
                if (n == 0) continue
                val i = py * w + ((px % w) + w) % w
                a[i] = min(1f, a[i] + n / 9f)
            }
        }

        fun rect(x0: Float, x1: Float, y0: Float, y1: Float) =
            fill(x0, x1, y0, y1) { x, y -> x >= x0 && x < x1 && y >= y0 && y < y1 }
    }

    /**
     * A gaussian blur of [sigma] pixels, wrapping sideways (the strip is periodic), the top and the
     * bottom holding their own value.
     */
    private fun blur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        if (sigma < .05f) return src
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
            for (i in -r..r) s += k[i + r] * src[y * w + (((x + i) % w) + w) % w]
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            // the edges hold their own value: the foot is solid under the strip's bottom row
            for (i in -r..r) s += k[i + r] * tmp[(y + i).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s
        }
        return out
    }
}
