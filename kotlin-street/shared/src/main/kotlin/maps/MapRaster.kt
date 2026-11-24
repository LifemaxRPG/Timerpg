package com.lifemax.rpg.feature.habits.maps

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A map drawn as plain pixels (docs/MAPS.md): ARGB in [px], [w] x [h], one pixel [unitsPerPx] street units of the town's
 * frame, its top-left corner at street ([x0], [z0]) (x east, z south: north up). No anti-aliasing anywhere: every edge is
 * a hard pixel edge, so the map wears the game's pixel-art look when it is scaled up with nearest filtering. Platform-free:
 * the app turns it into an ImageBitmap once ([MapImages]), the tests write it as a PNG.
 */
class MapRaster(val w: Int, val h: Int, val unitsPerPx: Float, val x0: Float, val z0: Float) {
    val px = IntArray(w * h)

    /** Street units to this raster's pixels (fractional). */
    fun toPxX(x: Float): Float = (x - x0) / unitsPerPx
    fun toPxY(z: Float): Float = (z - z0) / unitsPerPx
    /** A pixel's centre in street units. */
    fun toX(px: Float): Float = x0 + px * unitsPerPx
    fun toZ(py: Float): Float = z0 + py * unitsPerPx
    /** The street-unit extent. */
    val x1: Float get() = x0 + w * unitsPerPx
    val z1: Float get() = z0 + h * unitsPerPx
    fun contains(x: Float, z: Float): Boolean = x >= x0 && z >= z0 && x < x1 && z < z1

    fun fill(c: Int) = px.fill(c)

    fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
    fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0

    /** Pixels [x0]..[x1) x [y0]..[y1) (pixel coordinates, clipped). */
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        val a = max(0, x0); val b = min(w, x1); val t = max(0, y0); val u = min(h, y1)
        for (y in t until u) { val o = y * w; for (x in a until b) px[o + x] = c }
    }

    /** A street-unit rectangle ([x0], [z0], [x1], [z1]). */
    fun rectU(r: FloatArray, c: Int) = rect(Math.round(toPxX(min(r[0], r[2]))), Math.round(toPxY(min(r[1], r[3]))), Math.round(toPxX(max(r[0], r[2]))), Math.round(toPxY(max(r[1], r[3]))), c)

    /**
     * A polygon in pixel coordinates ([xs], [ys], [n] corners), even-odd, pixel centres inside: a scanline fill, so it
     * works for any simple polygon (the river's bends, a turned building).
     */
    fun poly(xs: FloatArray, ys: FloatArray, n: Int, c: Int) {
        if (n < 3) return
        var ymin = Float.MAX_VALUE; var ymax = -Float.MAX_VALUE
        for (i in 0 until n) { ymin = min(ymin, ys[i]); ymax = max(ymax, ys[i]) }
        val r0 = max(0, floor(ymin).toInt()); val r1 = min(h - 1, ceil(ymax).toInt())
        val cross = FloatArray(n)
        for (y in r0..r1) {
            val sy = y + .5f
            var k = 0
            var j = n - 1
            for (i in 0 until n) {
                val ya = ys[i]; val yb = ys[j]
                if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy)) cross[k++] = xs[i] + (sy - ya) / (yb - ya) * (xs[j] - xs[i])
                j = i
            }
            if (k < 2) continue
            cross.sort(0, k)
            var i = 0
            while (i + 1 < k) {
                val a = max(0, Math.round(cross[i])); val b = min(w, Math.round(cross[i + 1]))
                val o = y * w
                for (x in a until b) px[o + x] = c
                i += 2
            }
        }
    }

    /** A polygon of street-unit corners ([pts]: x, z pairs). */
    fun polyU(pts: FloatArray, c: Int) {
        val n = pts.size / 2
        val xs = FloatArray(n) { toPxX(pts[it * 2]) }; val ys = FloatArray(n) { toPxY(pts[it * 2 + 1]) }
        poly(xs, ys, n, c)
    }

    /** A thick segment in pixel coordinates: every pixel whose centre is within [width] / 2 of it (round ends). */
    fun line(ax: Float, ay: Float, bx: Float, by: Float, width: Float, c: Int) {
        val r = max(.5f, width / 2f)
        val x0 = max(0, floor(min(ax, bx) - r).toInt()); val x1 = min(w - 1, ceil(max(ax, bx) + r).toInt())
        val y0 = max(0, floor(min(ay, by) - r).toInt()); val y1 = min(h - 1, ceil(max(ay, by) + r).toInt())
        if (x0 > x1 || y0 > y1) return
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val r2 = r * r
        for (y in y0..y1) {
            val py = y + .5f
            val o = y * w
            for (x in x0..x1) {
                val pxc = x + .5f
                var t = if (l2 > 0f) ((pxc - ax) * dx + (py - ay) * dy) / l2 else 0f
                t = t.coerceIn(0f, 1f)
                val ex = ax + dx * t - pxc; val ey = ay + dy * t - py
                if (ex * ex + ey * ey <= r2) px[o + x] = c
            }
        }
    }

    /** A polyline of street-unit points ([pts]: x, z pairs), [widthPx] pixels thick. */
    fun polylineU(pts: FloatArray, widthPx: Float, c: Int) {
        for (i in 0 until pts.size / 2 - 1) line(toPxX(pts[i * 2]), toPxY(pts[i * 2 + 1]), toPxX(pts[i * 2 + 2]), toPxY(pts[i * 2 + 3]), widthPx, c)
    }

    /** A dashed polyline (railways): [on] pixels drawn, [off] skipped, along the line. */
    fun dashedU(pts: FloatArray, widthPx: Float, on: Float, off: Float, c: Int) {
        var phase = 0f
        for (i in 0 until pts.size / 2 - 1) {
            val ax = toPxX(pts[i * 2]); val ay = toPxY(pts[i * 2 + 1]); val bx = toPxX(pts[i * 2 + 2]); val by = toPxY(pts[i * 2 + 3])
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            if (len <= 0f) continue
            var s = 0f
            while (s < len) {
                val inOn = phase < on
                val step = min(len - s, if (inOn) on - phase else on + off - phase)
                if (inOn) line(ax + (bx - ax) * s / len, ay + (by - ay) * s / len, ax + (bx - ax) * (s + step) / len, ay + (by - ay) * (s + step) / len, widthPx, c)
                s += step; phase += step
                if (phase >= on + off) phase = 0f
            }
        }
    }

    /** A filled disc in pixel coordinates. */
    fun disc(cx: Float, cy: Float, r: Float, c: Int) {
        val x0 = max(0, floor(cx - r).toInt()); val x1 = min(w - 1, ceil(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt()); val y1 = min(h - 1, ceil(cy + r).toInt())
        val r2 = r * r
        for (y in y0..y1) for (x in x0..x1) { val dx = x + .5f - cx; val dy = y + .5f - cy; if (dx * dx + dy * dy <= r2) px[y * w + x] = c }
    }

    /** Darkens every pixel of colour in [inside] that touches one of colour not in it (a one-pixel outline round areas). */
    fun outline(inside: (Int) -> Boolean, edge: (Int) -> Int) {
        val src = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[y * w + x]
            if (!inside(c)) continue
            val n = (x > 0 && !inside(src[y * w + x - 1])) || (x < w - 1 && !inside(src[y * w + x + 1])) ||
                (y > 0 && !inside(src[(y - 1) * w + x])) || (y < h - 1 && !inside(src[(y + 1) * w + x]))
            if (n) px[y * w + x] = edge(c)
        }
    }

    companion object {
        fun argb(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)
        /** [c] made darker by [k] (0..1). */
        fun shade(c: Int, k: Float): Int {
            val r = ((c shr 16) and 255) * k; val g = ((c shr 8) and 255) * k; val b = (c and 255) * k
            return (c and 0xFF000000.toInt()) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }
}
