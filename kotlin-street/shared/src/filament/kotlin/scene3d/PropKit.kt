package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.PropRules
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The street props' meshes and pictures, made by code (no engine in here: [PropLayer] hands them to Filament). Each prop
 * is a handful of low-poly parts (boxes, 6-12 sided prisms and frustums, square beams) and every face wears a pixel
 * picture painted texel by texel at the buildings' own density ([TEXEL] street units a texel: the sprites' 32 px tile is
 * 26.3 units) in the LimeZu pack's palette: the navy outline, two or three tones, a lit top lip, a few specks of wear.
 * The faces are flat-shaded and the pictures sampled nearest, so it reads as the pixel art cut into simple 3D.
 *
 * A prop's own frame: its footprint's middle on the ground at the origin, x along the street, y up, z out towards the
 * road (its front faces +z). Pictures are drawn as seen from outside each face (top-left first).
 */

/** Street units per texel, as the buildings' (BuildingSpec.W 184 per 224 px). */
internal const val TEXEL = 184f / 224f

/** A picture painted texel by texel: colours (ARGB) and a glow mask (red: how much it glows; blue: in its own colours). */
internal class PixImg(val w: Int, val h: Int) {
    val px = IntArray(w * h)
    /**
     * What glows at night, made only for a picture that glows ([glow]): most do not, and the town's 2048-texel grass
     * once carried a 16 MB mask of zeros (read only where [glows]).
     */
    val mask: IntArray get() = maskArr ?: IntArray(w * h).also { maskArr = it }
    private var maskArr: IntArray? = null
    var glows = false; private set

    operator fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
    operator fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0
    fun fill(c: Int) = px.fill(c)
    /** A filled rectangle, both corners inclusive. */
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0..y1) for (x in x0..x1) this[x, y] = c }
    fun hline(x0: Int, x1: Int, y: Int, c: Int) = rect(x0, y, x1, y, c)
    fun vline(x: Int, y0: Int, y1: Int, c: Int) = rect(x, y0, x, y1, c)
    fun border(c: Int) { hline(0, w - 1, 0, c); hline(0, w - 1, h - 1, c); vline(0, 0, h - 1, c); vline(w - 1, 0, h - 1, c) }
    /** Marks a rectangle to glow at night, [amount] 0-255, in its own colours. */
    fun glow(x0: Int, y0: Int, x1: Int, y1: Int, amount: Int = 255) {
        glows = true
        for (y in y0..y1) for (x in x0..x1) if (x in 0 until w && y in 0 until h) mask[y * w + x] = (255 shl 24) or (amount shl 16) or 255
    }
    fun glowAt(x: Int, y: Int, amount: Int = 255) = glow(x, y, x, y, amount)

    /** [s] in the 3x5 pixel font, its top-left at ([x], [y]). */
    fun text(s: String, x: Int, y: Int, c: Int) {
        var cx = x
        for (ch in s) {
            val g = FONT[ch.uppercaseChar()]
            if (g != null) for (r in 0 until 5) for (col in 0 until 3) if (g[r * 3 + col] == '1') this[cx + col, y + r] = c
            cx += 4
        }
    }

    companion object {
        fun textWidth(s: String) = if (s.isEmpty()) 0 else s.length * 4 - 1

        /** A 3x5 pixel font: rows top first. */
        val FONT: Map<Char, String> = mapOf(
            'A' to "010101111101101", 'B' to "110101110101110", 'C' to "011100100100011", 'D' to "110101101101110",
            'E' to "111100110100111", 'F' to "111100110100100", 'G' to "011100101101011", 'H' to "101101111101101",
            'I' to "111010010010111", 'J' to "001001001101010", 'K' to "101101110101101", 'L' to "100100100100111",
            'M' to "101111101101101", 'N' to "101111111111101", 'O' to "010101101101010", 'P' to "110101110100100",
            'Q' to "010101101110011", 'R' to "110101110101101", 'S' to "011100010001110", 'T' to "111010010010010",
            'U' to "101101101101111", 'V' to "101101101101010", 'W' to "101101101111101", 'X' to "101101010101101",
            'Y' to "101101010010010", 'Z' to "111001010100111",
            '0' to "111101101101111", '1' to "010110010010111", '2' to "110001010100111", '3' to "110001010001110",
            '4' to "101101111001001", '5' to "111100110001110", '6' to "011100110101010", '7' to "111001010010010",
            '8' to "010101010101010", '9' to "010101011001110", '.' to "000000000000010", '-' to "000000111000000",
            '\'' to "010010000000000", '&' to "010101010101011", ' ' to "000000000000000",
        )
    }
}

/** A colour ramp in the pack's way: outline, dark, base, light, highlight. */
internal class Ramp(val o: Int, val d: Int, val b: Int, val l: Int, val h: Int)

/** One mesh ready for the engine: its vertices (position, picture uv, normal, tangent), triangles and its picture. */
class PropMesh(
    val pos: FloatArray, val uv: FloatArray, val nrm: FloatArray, val tan: FloatArray, val idx: IntArray,
    val texW: Int, val texH: Int, val tex: IntArray, val mask: IntArray?,
    /** Its bounds: min x, y, z, max x, y, z. */
    val bounds: FloatArray,
) {
    val vertexCount: Int get() = pos.size / 3
}

/** Builds a prop from parts; each face takes a picture (or a part of one) by its texel coordinates. */
internal class MeshBuilder {
    private class Poly(val p: FloatArray, val uv: FloatArray, val img: PixImg, val n: FloatArray, val t: FloatArray)
    private val polys = ArrayList<Poly>()

    /** A flat polygon (convex, its corners in order) wearing [img] at texel coords [uv] (pairs), normal [n], tangent [t]. */
    fun poly(p: FloatArray, uv: FloatArray, img: PixImg, n: FloatArray, t: FloatArray) { polys += Poly(p, uv, img, nrm(n), nrm(t)) }

    /**
     * A quad: [a] top-left, [b] top-right, [c] bottom-right, [d] bottom-left as seen from outside; its picture's texels
     * [u0], [v0] to [u1], [v1] (all of [img] by default).
     */
    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, img: PixImg, u0: Float = 0f, v0: Float = 0f, u1: Float = img.w.toFloat(), v1: Float = img.h.toFloat()) {
        val n = cross(sub(d, a), sub(b, a))
        if (len(n) < 1e-7f) return
        poly(floatArrayOf(*a, *b, *c, *d), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), img, n, sub(b, a))
    }

    /** A flat polygon whose picture is laid on it from [origin] along [u] (right) and [v] (down), [TEXEL] a texel. */
    fun flat(pts: List<FloatArray>, img: PixImg, origin: FloatArray, u: FloatArray, v: FloatArray, n: FloatArray) {
        val p = FloatArray(pts.size * 3); val uv = FloatArray(pts.size * 2)
        for ((i, q) in pts.withIndex()) {
            q.copyInto(p, i * 3)
            val r = sub(q, origin)
            uv[i * 2] = dot(r, u) / TEXEL; uv[i * 2 + 1] = dot(r, v) / TEXEL
        }
        poly(p, uv, img, n, u)
    }

    enum class Side { FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM }

    private val Z = floatArrayOf(0f, 0f, 1f)

    /**
     * A box from ([x0], [y0], [z0]) to ([x1], [y1], [z1]); [paint] gives each side's picture for its size in texels
     * (width, height as seen from outside; the top's "up" is the far side, -z), or null to leave that side off.
     */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, paint: (Side, Int, Int) -> PixImg?) {
        val w = x1 - x0; val h = y1 - y0; val d = z1 - z0
        fun tx(l: Float) = max(1, (l / TEXEL).roundToInt())
        paint(Side.FRONT, tx(w), tx(h))?.let { quad(v(x0, y1, z1), v(x1, y1, z1), v(x1, y0, z1), v(x0, y0, z1), it) }
        paint(Side.BACK, tx(w), tx(h))?.let { quad(v(x1, y1, z0), v(x0, y1, z0), v(x0, y0, z0), v(x1, y0, z0), it) }
        paint(Side.RIGHT, tx(d), tx(h))?.let { quad(v(x1, y1, z1), v(x1, y1, z0), v(x1, y0, z0), v(x1, y0, z1), it) }
        paint(Side.LEFT, tx(d), tx(h))?.let { quad(v(x0, y1, z0), v(x0, y1, z1), v(x0, y0, z1), v(x0, y0, z0), it) }
        paint(Side.TOP, tx(w), tx(d))?.let { quad(v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1), it) }
        paint(Side.BOTTOM, tx(w), tx(d))?.let { quad(v(x0, y0, z1), v(x1, y0, z1), v(x1, y0, z0), v(x0, y0, z0), it) }
    }

    /** A box all in one ramp's panels (outlined, a lit top lip), its top lit, no bottom. */
    fun panelBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Ramp, seed: Int, edges: Boolean = true) =
        box(x0, y0, z0, x1, y1, z1) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.TOP -> Paint.top(w, h, r); else -> Paint.panel(w, h, r, seed + s.ordinal, edges) } }

    /** A box in one flat colour swatch (thin rails, legs). */
    fun solidBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, sw: PixImg) =
        box(x0, y0, z0, x1, y1, z1) { s, _, _ -> if (s == Side.BOTTOM) null else sw }

    /** A hexahedron from its bottom corners [b] and top corners [t] (each: (x0,z0), (x1,z0), (x1,z1), (x0,z1) in order). */
    fun hexa(b: List<FloatArray>, t: List<FloatArray>, front: PixImg, back: PixImg, left: PixImg, right: PixImg, top: PixImg?) {
        // corners: 0 (x0 z0) 1 (x1 z0) 2 (x1 z1) 3 (x0 z1)
        quad(t[3], t[2], b[2], b[3], front)
        quad(t[1], t[0], b[0], b[1], back)
        quad(t[2], t[1], b[1], b[2], right)
        quad(t[0], t[3], b[3], b[0], left)
        top?.let { quad(t[0], t[1], t[2], t[3], it) }
    }

    /**
     * A prism or frustum about [axis] (unit) from [base], [length] long, radius [r0] there and [r1] at its far end, [n]
     * sides; its picture [strip] runs round it (its middle column facing [e2], the picture's top at the far end), or a
     * flat [swatch]. Angles [a0] to [a1] (radians from [e2] towards e1 = axis x e2) for a part of one (an arch). Caps at
     * either end in [capFar] / [capNear] (planar pictures, [e1] right, [e2] down... as seen from outside).
     */
    fun prism(
        base: FloatArray, axis: FloatArray, e2: FloatArray, r0: Float, r1: Float, length: Float, n: Int,
        strip: PixImg?, swatch: PixImg? = null, capFar: PixImg? = null, capNear: PixImg? = null,
        a0: Float = (-PI).toFloat(), a1: Float = PI.toFloat(),
    ) {
        val ax = nrm(axis); val ez = nrm(e2); val ex = cross(ax, ez)
        val full = a1 - a0 > 6.28f
        fun at(a: Float, t: Float): FloatArray {
            val r = r0 + (r1 - r0) * t
            return add(add(base, mul(ax, length * t)), add(mul(ex, sin(a) * r), mul(ez, cos(a) * r)))
        }
        val img = strip ?: swatch ?: return
        val sw = img.w.toFloat(); val sh = img.h.toFloat()
        fun u(a: Float): Float = if (strip == null) sw * (a - a0) / (a1 - a0).coerceAtLeast(1e-4f) else if (full) sw * (a + PI.toFloat()) / (2f * PI.toFloat()) else sw * (a - a0) / (a1 - a0)
        val step = (a1 - a0) / n
        val start = if (full) -PI.toFloat() / n else a0
        for (i in 0 until n) {
            var aa = start + step * i; var ab = aa + step
            if (full && aa >= PI.toFloat() - 1e-4f) { aa -= 2f * PI.toFloat(); ab -= 2f * PI.toFloat() }
            val am = (aa + ab) / 2f
            val radial = add(mul(ex, sin(am)), mul(ez, cos(am)))
            val nn = sub(mul(radial, length), mul(ax, r1 - r0))
            fun side(p: Float, q: Float, up: Float, uq: Float) {
                val tl = at(p, 1f); val tr = at(q, 1f); val br = at(q, 0f); val bl = at(p, 0f)
                poly(floatArrayOf(*tl, *tr, *br, *bl), floatArrayOf(up, 0f, uq, 0f, uq, sh, up, sh), img, nn, sub(tr, tl))
            }
            if (full && strip != null && ab > PI.toFloat() + 1e-4f) {
                // the face across the picture's seam (at the back) in two halves
                val pi = PI.toFloat()
                side(aa, pi, u(aa), sw)
                side(-pi, ab - 2f * pi, 0f, u(ab - 2f * pi))
            } else side(aa, ab, if (strip == null) 0f else u(aa), if (strip == null) sw else u(ab))
        }
        for ((t, cap) in listOf(1f to capFar, 0f to capNear)) {
            cap ?: continue
            val r = if (t == 1f) r1 else r0
            if (r <= 1e-3f) continue
            val pts = (0..n).map { i -> at(start + step * i, t) }.let { if (full) it.dropLast(1) else it }
            val c = add(base, mul(ax, length * t))
            val nOut = if (t == 1f) ax else mul(ax, -1f)
            // its picture as seen from outside: a horizontal cap's down is +z (towards the road), an upright one's is down
            val want = if (abs(nOut[1]) > .7f) Z else floatArrayOf(0f, -1f, 0f)
            val down = nrm(sub(want, mul(nOut, dot(want, nOut))))
            val right = cross(nOut, down)
            flat(pts, cap, sub(sub(c, mul(right, r)), mul(down, r)), right, down, nOut)
        }
    }

    /** An upright prism (axis +y) on the ground plan at ([cx], [cz]) from [y0] to [y1]; its strip's middle faces +z. */
    fun upright(cx: Float, cz: Float, y0: Float, y1: Float, r0: Float, r1: Float, n: Int, strip: PixImg?, swatch: PixImg? = null, cap: PixImg? = null) =
        prism(v(cx, y0, cz), v(0f, 1f, 0f), v(0f, 0f, 1f), r0, r1, y1 - y0, n, strip, swatch, cap)

    /** A square beam from [a] to [b], [t] thick, in [sw]. */
    fun beam(a: FloatArray, b: FloatArray, t: Float, sw: PixImg) {
        val d = sub(b, a)
        if (len(d) < 1e-4f) return
        val ax = nrm(d)
        val ref = if (abs(ax[1]) < .9f) v(0f, 1f, 0f) else v(0f, 0f, 1f)
        val s1 = nrm(cross(ax, ref)); val s2 = cross(s1, ax)
        val h = t / 2f
        fun c(e: FloatArray, i: Int, j: Int) = add(e, add(mul(s1, h * i), mul(s2, h * j)))
        val corners = listOf(1 to 1, -1 to 1, -1 to -1, 1 to -1)
        for (k in 0 until 4) {
            val (i0, j0) = corners[k]; val (i1, j1) = corners[(k + 1) % 4]
            quad(c(b, i0, j0), c(b, i1, j1), c(a, i1, j1), c(a, i0, j0), sw)
        }
        quad(c(b, 1, 1), c(b, 1, -1), c(b, -1, -1), c(b, -1, 1), sw)
        quad(c(a, -1, 1), c(a, -1, -1), c(a, 1, -1), c(a, 1, 1), sw)
    }

    /** Packs every picture into one atlas (each with a texel of its own edge round it) and makes the mesh. */
    fun build(): PropMesh {
        val imgs = LinkedHashMap<PixImg, IntArray>() // img -> atlas x, y of its texel (0, 0)
        val list = polys.map { it.img }.distinct().sortedByDescending { it.h }
        val maxW = list.maxOfOrNull { it.w } ?: 1
        var width = 64
        while (width < maxW + 2) width *= 2
        val area = list.sumOf { (it.w + 2) * (it.h + 2) }
        while (width * width < area * 2 && width < 512) width *= 2
        var x = 0; var y = 0; var shelf = 0
        for (im in list) {
            if (x + im.w + 2 > width) { x = 0; y += shelf; shelf = 0 }
            imgs[im] = intArrayOf(x + 1, y + 1)
            x += im.w + 2; shelf = max(shelf, im.h + 2)
        }
        val height = y + shelf
        val tex = IntArray(width * height)
        // (the atlas's mask only if a picture in it glows; a picture that does not glows nowhere: zeros)
        val anyGlow = imgs.keys.any { it.glows }
        val mask = if (anyGlow) IntArray(width * height) else null
        for ((im, at) in imgs) {
            val m = if (im.glows) im.mask else null
            for (yy in -1..im.h) for (xx in -1..im.w) {
                val sx = xx.coerceIn(0, im.w - 1); val sy = yy.coerceIn(0, im.h - 1)
                val di = (at[1] + yy) * width + at[0] + xx
                tex[di] = im.px[sy * im.w + sx]
                if (mask != null && m != null) mask[di] = m[sy * im.w + sx]
            }
        }
        val nv = polys.sumOf { it.p.size / 3 }
        val ni = polys.sumOf { (it.p.size / 3 - 2) * 3 }
        val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3)
        val idx = IntArray(ni)
        var vi = 0; var ii = 0
        val bmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val bmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in polys) {
            val at = imgs[p.img]!!
            val n = p.p.size / 3
            for (k in 0 until n) {
                for (c in 0..2) {
                    pos[(vi + k) * 3 + c] = p.p[k * 3 + c]; nr[(vi + k) * 3 + c] = p.n[c]; tn[(vi + k) * 3 + c] = p.t[c]
                    bmin[c] = min(bmin[c], p.p[k * 3 + c]); bmax[c] = max(bmax[c], p.p[k * 3 + c])
                }
                uv[(vi + k) * 2] = (at[0] + p.uv[k * 2]) / width
                // (v = 0 at the picture's top row, as FilamentStreet's faces take their pictures)
                uv[(vi + k) * 2 + 1] = (at[1] + p.uv[k * 2 + 1]) / height
            }
            for (k in 1 until n - 1) { idx[ii++] = vi; idx[ii++] = vi + k; idx[ii++] = vi + k + 1 }
            vi += n
        }
        return PropMesh(pos, uv, nr, tn, idx, width, height, tex, mask, floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]))
    }

    companion object {
        fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun mul(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun len(a: FloatArray) = sqrt(dot(a, a))
        fun nrm(a: FloatArray): FloatArray { val l = len(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    }
}

/** The pack's colours and the ways faces are painted. */
internal object Paint {
    fun c(rgb: Long): Int = (0xFF000000L or rgb).toInt()
    val OUT = c(0x3A3A50)
    val IRON = Ramp(c(0x24243A), c(0x3A3A50), c(0x46465E), c(0x565972), c(0x6C6E85))
    val STEEL = Ramp(c(0x3A3A50), c(0x6C6E85), c(0x8B8FA6), c(0x9DA3B7), c(0xC6CBDA))
    val RED = Ramp(c(0x4A1A2A), c(0xA82B2D), c(0xCB2A2A), c(0xE63F38), c(0xF2836B))
    val YELLOW = Ramp(c(0x5E3A1E), c(0xC98A1C), c(0xEDB52A), c(0xF7D650), c(0xFFF59A))
    val BLUE = Ramp(c(0x1A2440), c(0x243A6E), c(0x2D4C8E), c(0x416EB0), c(0x8FA9D8))
    val WOOD = Ramp(c(0x6B5052), c(0xA85F46), c(0xC18452), c(0xE0B870), c(0xF1CE8E))
    val WHITE = Ramp(c(0x3A3A50), c(0xA8A6BE), c(0xD6D2E2), c(0xEBE4F2), c(0xFFFFFF))
    val CONCRETE = Ramp(c(0x4D4D66), c(0x8B8B9E), c(0xA6A6B4), c(0xBCBCC8), c(0xD2D2DC))
    val TERRACOTTA = Ramp(c(0x4A2A2A), c(0x8E4434), c(0xB45A3C), c(0xCF7A4E), c(0xE59E6C))
    val ORANGE = Ramp(c(0x5A2418), c(0xC4471C), c(0xEE6420), c(0xF88A3A), c(0xFFB066))
    val UTIL = Ramp(c(0x2E3A36), c(0x55685E), c(0x6E8276), c(0x84998C), c(0xA4B8AA))
    val BEIGE = Ramp(c(0x4A4436), c(0x9C9276), c(0xB8AD8E), c(0xCCC2A4), c(0xE2DAC0))
    val SIGN = Ramp(c(0x14302A), c(0x1C6040), c(0x237A4E), c(0x2E9460), c(0x5CB880))
    val LEAF = Ramp(c(0x1A3A36), c(0x1F614F), c(0x268B49), c(0x49A92F), c(0x64B63B))
    val BINGREEN = Ramp(c(0x14302E), c(0x1A5249), c(0x1F614F), c(0x2A7A5C), c(0x3E9470))
    val BLACK = Ramp(c(0x16161F), c(0x23232F), c(0x2E2E40), c(0x3C3C52), c(0x56566E))
    val BROWN = Ramp(c(0x3A2A22), c(0x5E4030), c(0x7A5238), c(0x94684A), c(0xB08460))
    val GREY = Ramp(c(0x2E3040), c(0x565972), c(0x6C6E85), c(0x7D7F99), c(0x9DA3B7))
    val BARK = Ramp(c(0x3A2A2A), c(0x5A3E36), c(0x6B5052), c(0x86665E), c(0xA08070))

    /** A tiny pseudo-random stream for wear. */
    class Rnd(seed: Int) {
        private var s = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        fun next(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f }
        fun int(n: Int) = (next() * n).toInt().coerceIn(0, n - 1)
    }

    /** One flat colour (for thin parts: rails, legs, bars). */
    fun swatch(c: Int) = PixImg(1, 1).also { it.fill(c) }

    /** A side panel: its base, a lit top lip, a darker foot, outlined; a few specks of wear. */
    fun panel(w: Int, h: Int, r: Ramp, seed: Int, edges: Boolean = true, wear: Float = 1f): PixImg {
        val im = PixImg(w, h)
        im.fill(r.b)
        if (h >= 4) { im.hline(0, w - 1, 1, r.l); im.hline(0, w - 1, h - 2, r.d) }
        if (edges) im.border(r.o) else if (h >= 3) { im.hline(0, w - 1, 0, r.l); im.hline(0, w - 1, h - 1, r.d) }
        speckle(im, r, seed, wear)
        return im
    }

    /** A top: the light tone, its edge a shade down (no outline: the sides carry it). */
    fun top(w: Int, h: Int, r: Ramp): PixImg {
        val im = PixImg(w, h)
        im.fill(r.l)
        if (w >= 3 && h >= 3) { im.border(r.b); im[1, 1] = r.h }
        return im
    }

    /** A strip round a prism: outlined rows at both ends, a lit row under the top, a dark one over the foot. */
    fun strip(w: Int, h: Int, r: Ramp, seed: Int, ends: Boolean = true): PixImg {
        val im = PixImg(w, h)
        im.fill(r.b)
        if (h >= 5) { im.hline(0, w - 1, 1, r.l); im.hline(0, w - 1, h - 2, r.d) }
        // (a strip only a few texels high keeps its colour: outlined rows would swallow it)
        if (ends && h >= 4) { im.hline(0, w - 1, 0, r.o); im.hline(0, w - 1, h - 1, r.o) }
        else if (ends && h == 3) im.hline(0, w - 1, h - 1, r.d)
        speckle(im, r, seed, 1f)
        return im
    }

    /** A strip's size for a prism of radius [r] and height [h]. */
    fun stripSize(r: Float, h: Float): Pair<Int, Int> = max(4, (2f * PI.toFloat() * r / TEXEL).roundToInt()) to max(1, (h / TEXEL).roundToInt())

    /** A round cap: the light tone, the rim a shade down, the outline at its edge. */
    fun cap(r: Float, ramp: Ramp): PixImg {
        val s = max(2, (2f * r / TEXEL).roundToInt())
        val im = PixImg(s, s)
        val c = (s - 1) / 2f
        if (s <= 4) { im.fill(ramp.l); im[0, 0] = ramp.b; im[s - 1, s - 1] = ramp.b; im[0, s - 1] = ramp.b; im[s - 1, 0] = ramp.b; return im }
        for (y in 0 until s) for (x in 0 until s) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
            im[x, y] = when { d > c - .6f -> ramp.o; d > c - 1.6f -> ramp.b; x + y < s / 2 -> ramp.h; else -> ramp.l }
        }
        return im
    }

    /** The round end of an arch (its upper half is used): the light tone, outlined along the curve. */
    fun archCap(r: Float, ramp: Ramp): PixImg {
        val s = max(2, (2f * r / TEXEL).roundToInt())
        val im = PixImg(s, s)
        val c = s / 2f
        for (y in 0 until s) for (x in 0 until s) {
            val d = sqrt((x + .5f - c) * (x + .5f - c) + (y + .5f - c) * (y + .5f - c))
            im[x, y] = if (d > c - 1f) ramp.o else if (y < c / 2f) ramp.h else ramp.l
        }
        return im
    }

    fun speckle(im: PixImg, r: Ramp, seed: Int, amount: Float) {
        val rnd = Rnd(seed)
        val n = (im.w * im.h / 28f * amount).toInt()
        repeat(n) {
            val x = 1 + rnd.int(max(1, im.w - 2)); val y = 1 + rnd.int(max(1, im.h - 2))
            if (im[x, y] == r.b) im[x, y] = if (rnd.next() < .6f) r.d else r.l
        }
    }

    /** A white envelope, 9x6, its top-left at ([x], [y]): its flap's V meeting in the middle. */
    fun envelope(im: PixImg, x: Int, y: Int, ink: Int, paper: Int) {
        im.rect(x, y, x + 8, y + 5, paper)
        im.hline(x, x + 8, y, ink); im.hline(x, x + 8, y + 5, ink); im.vline(x, y, y + 5, ink); im.vline(x + 8, y, y + 5, ink)
        for (k in 1..3) { im[x + k, y + k] = ink; im[x + 8 - k, y + k] = ink }
        im[x + 4, y + 3] = ink
    }
}

/** Each kind's mesh and pictures. */
object PropKit {
    private val cache = HashMap<String, PropMesh>()

    /** The mesh of [kind] in [variant] ([text]: a sign's words; [seed]: a bike's or the flowers' colours), made once. */
    fun mesh(kind: PropKind, variant: Int = 0, text: List<String> = emptyList(), seed: Int = 0): PropMesh {
        val key = "${kind.name}/$variant/${text.joinToString("|")}/${if (kind.usesSeed()) seed % 7 else 0}"
        return synchronized(cache) { cache.getOrPut(key) { make(kind, variant, text, if (kind.usesSeed()) seed % 7 else 0) } }
    }

    private fun PropKind.usesSeed() = this == PropKind.PLANTER || this == PropKind.BIKE_RACK || this == PropKind.WHEELIE_BIN

    private fun make(kind: PropKind, variant: Int, text: List<String>, seed: Int): PropMesh {
        val m = MeshBuilder()
        when (kind) {
            PropKind.HYDRANT -> hydrant(m, variant)
            PropKind.MAILBOX -> mailbox(m)
            PropKind.HOME_MAILBOX -> homeMailbox(m, variant)
            PropKind.WHEELIE_BIN -> wheelieBin(m, variant, seed)
            PropKind.LITTER_BIN -> litterBin(m, variant)
            PropKind.NEWS_BOX -> newsBox(m, variant)
            PropKind.BOLLARD -> bollard(m, variant)
            PropKind.PLANTER -> planter(m, variant, seed)
            PropKind.BIKE_RACK -> bikeRack(m, variant, seed)
            PropKind.STREET_SIGN -> streetSign(m, text)
            PropKind.STOP_SIGN -> stopSign(m)
            PropKind.PED_SIGN -> pedSign(m)
            PropKind.CALL_BOX -> callBox(m)
            PropKind.UTILITY_BOX -> utilityBox(m, variant)
            PropKind.CONE -> cone(m)
            PropKind.BENCH -> bench(m)
            PropKind.PARKING_METER -> parkingMeter(m)
            PropKind.TREE_GUARD -> treeGuard(m)
        }
        return m.build()
    }

    private val v = MeshBuilder.Companion::v
    private val Y = floatArrayOf(0f, 1f, 0f)
    private val Z = floatArrayOf(0f, 0f, 1f)
    private val X = floatArrayOf(1f, 0f, 0f)

    /** An upright prism wearing its ramp's strip (and [cap] on top unless false); [deco] paints over the strip. */
    private fun MeshBuilder.post(cx: Float, cz: Float, y0: Float, y1: Float, r0: Float, r1: Float, n: Int, r: Ramp, seed: Int, cap: Boolean = true, ends: Boolean = true, deco: (PixImg) -> Unit = {}) {
        val (w, h) = Paint.stripSize(max(r0, r1), y1 - y0)
        val s = Paint.strip(w, h, r, seed, ends).also(deco)
        upright(cx, cz, y0, y1, r0, r1, n, s, cap = if (cap && r1 > .05f) Paint.cap(r1, r) else null)
    }

    // ------------------------------------------------------------------ the kit

    private fun hydrant(m: MeshBuilder, variant: Int) {
        val body = if (variant == 1) Paint.YELLOW else Paint.RED
        val caps = Paint.STEEL
        // the flange on the ground, its bolts
        m.post(0f, 0f, 0f, 1.6f, 4.2f, 4.0f, 8, body, 1) { s -> for (x in 1 until s.w step 4) s[x, 1] = caps.l }
        // the barrel: a chain looped across its front, a lit stripe down its left
        m.post(0f, 0f, 1.6f, 11.2f, 3.1f, 3.1f, 8, body, 2, cap = false, ends = false) { s ->
            val c = s.w / 2
            for (y in 0 until s.h) { s[c - 5, y] = body.h; s[c - 4, y] = body.l; s[c + 4, y] = body.d; s[c + 5, y] = body.d }
            s.hline(0, s.w - 1, s.h - 1, body.o)
            for (x in c - 3..c + 3) s[x, 3 + abs(x - c) / 2] = caps.d
        }
        // the ring, the bonnet, the nut
        m.post(0f, 0f, 11.2f, 12.4f, 3.8f, 3.8f, 8, body, 3)
        m.post(0f, 0f, 12.4f, 15.2f, 3.3f, 1.6f, 8, body, 4, cap = false) { s -> s.hline(0, s.w - 1, 1, body.h) }
        m.post(0f, 0f, 15.2f, 16.6f, 1.0f, 1.0f, 6, caps, 5)
        // the outlets: two hose caps either side, the pumper cap in front
        for (sx in listOf(-1f, 1f)) {
            val (w, h) = Paint.stripSize(1.3f, 1.4f)
            m.prism(v(sx * 3.0f, 8f, 0f), v(sx, 0f, 0f), Y, 1.3f, 1.3f, 1.4f, 6, Paint.strip(w, h, caps, 6), capFar = Paint.cap(1.3f, caps))
        }
        val (pw, ph) = Paint.stripSize(1.8f, 1.4f)
        m.prism(v(0f, 7.4f, 3.0f), Z, Y, 1.8f, 1.8f, 1.4f, 8, Paint.strip(pw, ph, caps, 7), capFar = Paint.cap(1.8f, caps).also { it[it.w / 2, it.h / 2] = caps.o })
    }

    private fun mailbox(m: MeshBuilder) {
        val r = Paint.BLUE
        val legs = Paint.swatch(Paint.IRON.b)
        for (x in listOf(-5.6f, 5.6f)) for (z in listOf(-5.6f, 5.6f)) m.solidBox(x - .9f, 0f, z - .9f, x + .9f, 3.2f, z + .9f, legs)
        val white = Paint.WHITE.l
        m.box(-7f, 3f, -7f, 7f, 19f, 7f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(w, h, r)
                MeshBuilder.Side.FRONT -> Paint.panel(w, h, r, 11).also { im ->
                    // the pull-down chute: its dark mouth and lit lip, the handle
                    im.rect(2, 2, w - 3, 4, r.o); im.hline(2, w - 3, 5, Paint.STEEL.h); im.hline(2, w - 3, 6, Paint.STEEL.d)
                    im.rect(w / 2 - 1, 3, w / 2 + 1, 3, Paint.STEEL.l)
                    // a white band and MAIL under it
                    im.hline(1, w - 2, 9, white)
                    im.text("MAIL", (w - 15) / 2, 12, white)
                }
                MeshBuilder.Side.BACK -> Paint.panel(w, h, r, 12).also { im -> im.rect(w / 2 - 1, h - 6, w / 2, h - 5, Paint.STEEL.l); im.hline(1, w - 2, 9, white) }
                else -> Paint.panel(w, h, r, 13 + s.ordinal).also { im ->
                    im.hline(1, w - 2, 9, white)
                    Paint.envelope(im, w / 2 - 4, 12, r.o, white)
                }
            }
        }
        // the domed top, front to back, its ends rounded
        val (w, h) = Paint.stripSize(7f, 14f)
        val dome = Paint.strip(w / 2 + 1, h, r, 14, ends = true)
        val end = Paint.archCap(7f, r)
        m.prism(v(-7f, 19f, 0f), X, Y, 7f, 7f, 14f, 6, dome, capFar = end, capNear = end, a0 = (-PI / 2).toFloat(), a1 = (PI / 2).toFloat())
    }

    private fun homeMailbox(m: MeshBuilder, variant: Int) {
        val r = when (variant) { 0 -> Paint.BLACK; 1 -> Paint.WHITE; 2 -> Paint.SIGN; 3 -> Paint.RED; 4 -> Paint.BLUE; else -> Paint.STEEL }
        val post = if (variant == 1) Paint.WHITE else Paint.WOOD
        // the post and its arm
        m.box(-1.1f, 0f, -1.1f, 1.1f, 16.6f, 1.1f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.top(w, h, post); else -> Paint.panel(w, h, post, 21 + s.ordinal).also { im -> for (y in 3 until h step 5) im[1, y] = post.d } } }
        m.panelBox(-1.3f, 16.6f, -3.6f, 1.3f, 17.4f, 3.6f, post, 22)
        // the box: straight sides and a rounded top, its door to the road, the flag on its side
        m.box(-2.3f, 17.4f, -5f, 2.3f, 19.8f, 5f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.FRONT -> PixImg(w, h).also { im -> im.fill(r.l); im.border(r.o); im[w / 2, 1] = Paint.STEEL.h }
                else -> Paint.panel(w, h, r, 23, edges = false).also { im -> im.hline(0, w - 1, h - 1, r.o) }
            }
        }
        val (w, h) = Paint.stripSize(2.3f, 10f)
        val top = Paint.strip(w / 2 + 1, h, r, 24, ends = true).also { im -> im.hline(0, im.w - 1, 1, r.h) }
        val door = Paint.archCap(2.3f, r).also { it[it.w / 2, it.h / 2 - 1] = Paint.STEEL.h }
        val back = Paint.archCap(2.3f, r)
        m.prism(v(0f, 19.8f, -5f), Z, Y, 2.3f, 2.3f, 10f, 6, top, capFar = door, capNear = back, a0 = (-PI / 2).toFloat(), a1 = (PI / 2).toFloat())
        val flag = Paint.swatch(Paint.RED.l)
        m.solidBox(2.3f, 18.2f, -2.4f, 2.7f, 22.6f, -1.9f, flag)
        m.box(2.3f, 21.0f, -1.9f, 2.7f, 22.6f, 1.2f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.RED, 25) }
    }

    private fun wheelieBin(m: MeshBuilder, variant: Int, seed: Int) {
        val (body, lid) = when (variant) { 0 -> Paint.BINGREEN to Paint.BINGREEN; 1 -> Paint.GREY to Paint.BLUE; 2 -> Paint.GREY to Paint.GREY; else -> Paint.BLACK to Paint.BROWN }
        val b = listOf(v(-5f, 1f, -6.2f), v(5f, 1f, -6.2f), v(5f, 1f, 5.6f), v(-5f, 1f, 5.6f))
        val t = listOf(v(-5.8f, 19.6f, -7f), v(5.8f, 19.6f, -7f), v(5.8f, 19.6f, 6.6f), v(-5.8f, 19.6f, 6.6f))
        fun side(seed: Int, ribs: Boolean, w: Int, h: Int) = Paint.panel(w, h, body, seed).also { im -> if (ribs) for (x in 3 until w - 2 step 4) im.vline(x, 3, h - 4, body.d) }
        val front = side(31, false, 14, 23).also { im ->
            // the handle's recess, the number, the recycling arrows on a blue lid's bin
            im.hline(2, 11, 2, body.o)
            val num = ((seed * 37 + variant * 11) % 89 + 10).toString()
            im.text(num, 7 - PixImg.textWidth(num) / 2, 8, Paint.WHITE.l)
            // a recycling bin's white band
            if (variant == 1) { im.hline(2, 11, 16, Paint.WHITE.l); im.hline(2, 11, 17, Paint.WHITE.l) }
        }
        m.hexa(b, t, front, side(32, false, 14, 23), side(33, true, 16, 23), side(34, true, 16, 23), null)
        // the lid, overhanging at the handle's side, and the handle bar
        m.box(-6f, 19.6f, -7.25f, 6f, 21.3f, 7.25f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> Paint.panel(w, h, lid, 36); MeshBuilder.Side.TOP -> Paint.top(w, h, lid).also { im -> im.hline(1, w - 2, h - 3, lid.d) }; else -> Paint.panel(w, h, lid, 35 + s.ordinal, wear = .5f) } }
        m.beam(v(-5f, 19.1f, 6.6f), v(5f, 19.1f, 6.6f), 1f, Paint.swatch(lid.d))
        // the wheels under the handle
        val tyre = Paint.BLACK
        for (sx in listOf(-1f, 1f)) {
            val (w, h) = Paint.stripSize(2.2f, 1.2f)
            m.prism(v(sx * 4.7f, 2.2f, 4.6f), v(sx, 0f, 0f), Y, 2.2f, 2.2f, 1.2f, 8, Paint.strip(w, h, tyre, 37, ends = false), capFar = Paint.cap(2.2f, tyre).also { it[it.w / 2, it.h / 2] = Paint.STEEL.l })
        }
    }

    private fun litterBin(m: MeshBuilder, variant: Int) {
        val r = if (variant == 1) Paint.IRON else Paint.BINGREEN
        m.post(0f, 0f, 0f, 1.2f, 4.3f, 4.3f, 10, Paint.IRON, 41)
        // the slatted body: a slat every other column, a band at top and foot
        m.post(0f, 0f, 1.2f, 16f, 5.2f, 5.2f, 10, r, 42, cap = false) { s ->
            for (x in 0 until s.w) if (x % 3 == 2) s.vline(x, 3, s.h - 4, r.o)
            for (rr in listOf(2, s.h - 3)) s.hline(0, s.w - 1, rr, Paint.STEEL.d)
            // a small plate on its front
            val c = s.w / 2
            s.rect(c - 3, 6, c + 3, 9, Paint.STEEL.l); s.hline(c - 2, c + 2, 7, Paint.STEEL.d)
        }
        m.post(0f, 0f, 16f, 17f, 5.5f, 5.5f, 10, r, 43, cap = false)
        // the domed lid with its dark mouth all round
        m.post(0f, 0f, 17f, 19.4f, 5.3f, 2.6f, 10, r, 44) { s -> s.hline(0, s.w - 1, s.h - 1, Paint.OUT); s.hline(0, s.w - 1, s.h - 2, Paint.BLACK.b) }
    }

    private fun newsBox(m: MeshBuilder, variant: Int) {
        val r = when (variant) { 0 -> Paint.RED; 1 -> Paint.BLUE; 2 -> Paint.YELLOW; else -> Paint.SIGN }
        m.panelBox(-4f, 0f, -3.5f, 4f, .6f, 3.5f, Paint.IRON, 51)
        m.panelBox(-2.4f, .6f, -2.4f, 2.4f, 6f, 2.4f, Paint.IRON, 52)
        m.box(-6.8f, 6f, -4.6f, 6.8f, 19f, 4.6f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> Paint.panel(w, h, r, 53)
                MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.FRONT -> Paint.panel(w, h, r, 54).also { im ->
                    // the name band, the window with today's paper in it, the coin slot and the handle
                    im.text("NEWS", (w - 15) / 2, 1, Paint.WHITE.h)
                    im.rect(2, 7, w - 3, 12, Paint.OUT)
                    im.rect(3, 8, w - 4, 12, Paint.WHITE.l)
                    im.hline(4, w - 5, 8, Paint.BLACK.b)
                    for (y in 10..12 step 2) im.hline(4, w - 6, y, Paint.WHITE.d)
                    im.rect(w - 8, 10, w - 5, 12, Paint.CONCRETE.d)
                    im.hline(2, w - 3, 13, r.h)
                    im.rect(w - 4, 7, w - 3, 8, Paint.STEEL.l)
                    im.hline(4, 8, 14, Paint.STEEL.h)
                }
                else -> Paint.panel(w, h, r, 55 + s.ordinal)
            }
        }
        // the sloping lid
        m.box(-7f, 19f, -4.8f, 7f, 20.6f, 4.8f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.top(w, h, r); else -> Paint.panel(w, h, r, 59, wear = .3f) } }
    }

    private fun bollard(m: MeshBuilder, variant: Int) {
        val r = Paint.IRON
        m.post(0f, 0f, 0f, 16.4f, 2.3f, 2.3f, 8, r, 61, cap = false) { s ->
            // a lit band near the top (glowing warm at night), or a plain steel ring
            if (variant == 1) {
                s.hline(0, s.w - 1, 2, Paint.STEEL.d)
                for (x in 0 until s.w) for (y in 3..5) s[x, y] = if (x % 3 == 1) Paint.OUT else if (y == 3) Paint.c(0xFFF0C0) else Paint.c(0xF7C860)
                s.glow(0, 3, s.w - 1, 5)
                s.hline(0, s.w - 1, 6, Paint.STEEL.d)
            } else { s.hline(0, s.w - 1, 3, Paint.STEEL.l); s.hline(0, s.w - 1, 4, Paint.STEEL.d) }
            s.hline(0, s.w - 1, s.h - 3, r.l)
        }
        m.post(0f, 0f, 16.4f, 18.3f, 2.3f, 1.0f, 8, r, 62) { s -> s.hline(0, s.w - 1, 1, r.h) }
    }

    private fun planter(m: MeshBuilder, variant: Int, seed: Int) {
        val r = when (variant) { 0 -> Paint.WOOD; 1 -> Paint.TERRACOTTA; else -> Paint.CONCRETE }
        m.box(-10f, 0f, -5f, 10f, 10f, 5f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null
                else -> Paint.panel(w, h, r, 71 + s.ordinal).also { im ->
                    // planks with their joints and nail heads; or the terracotta's rolled bands; or stone courses
                    when (variant) {
                        0 -> { for (y in 3 until h - 1 step 3) im.hline(1, w - 2, y, r.d); im.vline(2, 1, h - 2, r.d); im.vline(w - 3, 1, h - 2, r.d) }
                        1 -> { im.hline(1, w - 2, 2, r.h); im.hline(1, w - 2, 3, r.d) }
                        else -> { for (y in 4 until h - 1 step 4) im.hline(1, w - 2, y, r.d); for (x in 5 until w - 1 step 8) im.vline(x, 1, 3, r.d) }
                    }
                }
            }
        }
        // its rim and the soil
        m.box(-10.4f, 10f, -5.4f, 10.4f, 11f, 5.4f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> Paint.swatch(r.d); MeshBuilder.Side.TOP -> PixImg(w, h).also { im -> im.fill(Paint.c(0x4A3428)); im.border(r.l); for (x in 2 until w - 2 step 3) im[x, h / 2] = Paint.c(0x5E4434) }; else -> Paint.panel(w, h, r, 76, wear = 0f) } }
        // the flowers: leafy clumps dotted with blooms in the planter's colours
        val blooms = listOf(
            intArrayOf(Paint.c(0xE63F38), Paint.c(0xF2836B)), intArrayOf(Paint.c(0xF7D650), Paint.c(0xFFF59A)),
            intArrayOf(Paint.c(0xC870D8), Paint.c(0xE8A8F0)), intArrayOf(Paint.c(0xF0F0F8), Paint.c(0xFFFFFF)), intArrayOf(Paint.c(0xF48CB0), Paint.c(0xFFC0D8)),
        )
        val rnd = Paint.Rnd(seed * 131 + variant)
        for ((i, x) in listOf(-6.6f, -2.2f, 2.2f, 6.6f).withIndex()) {
            val z = if (i % 2 == 0) -1f else 1f
            val bl = blooms[(seed + variant * 2 + i % 2) % blooms.size]
            val tall = 4.6f + rnd.next() * 1.4f
            fun leaves(w: Int, h: Int, sd: Int) = Paint.strip(w, h, Paint.LEAF, sd, ends = false).also { im ->
                for (y in 0 until h) for (x2 in 0 until w) if ((x2 * 7 + y * 3 + sd) % 5 == 0) im[x2, y] = Paint.LEAF.d
                val rr = Paint.Rnd(sd)
                repeat(w * h / 5) { val bx = rr.int(w); val by = rr.int(h); im[bx, by] = bl[0]; if (by > 0) im[bx, by - 1] = bl[1] }
            }
            val (w1, h1) = Paint.stripSize(3f, 2.4f)
            m.upright(x, z, 10.6f, 13f, 2.2f, 3f, 7, leaves(w1, h1, 80 + i))
            val (w2, h2) = Paint.stripSize(3f, tall - 2.4f)
            m.upright(x, z, 13f, 10.6f + tall, 3f, 1.1f, 7, leaves(w2, h2, 90 + i), cap = Paint.cap(1.1f, Paint.LEAF).also { it.fill(bl[0]) })
        }
    }

    private fun bikeRack(m: MeshBuilder, variant: Int, seed: Int) {
        val steel = Paint.swatch(Paint.STEEL.b)
        val hi = Paint.swatch(Paint.STEEL.l)
        // two hoops along the kerb
        for (c in listOf(-9f, 9f)) {
            val z = -2f
            m.beam(v(c - 6.4f, 0f, z), v(c - 6.4f, 12.6f, z), 1f, steel)
            m.beam(v(c + 6.4f, 0f, z), v(c + 6.4f, 12.6f, z), 1f, steel)
            m.beam(v(c - 6.4f, 12.6f, z), v(c - 4.6f, 15.2f, z), 1f, steel)
            m.beam(v(c + 6.4f, 12.6f, z), v(c + 4.6f, 15.2f, z), 1f, steel)
            m.beam(v(c - 4.6f, 15.2f, z), v(c + 4.6f, 15.2f, z), 1f, hi)
            for (x in listOf(c - 6.4f, c + 6.4f)) m.solidBox(x - 1f, 0f, z - 1f, x + 1f, .5f, z + 1f, Paint.swatch(Paint.IRON.b))
        }
        if (variant != 1) return
        // a bike against them: wheels of tyre segments, a hub and spokes, the frame in its colour, saddle and bars
        val frame = listOf(Paint.c(0x2D4C8E), Paint.c(0xCB2A2A), Paint.c(0x2E9460), Paint.c(0xEDB52A), Paint.c(0x8E5AA8), Paint.c(0x3C3C52), Paint.c(0xE0E0EA))[seed % 7]
        val fr = Paint.swatch(frame)
        val tyre = Paint.swatch(Paint.BLACK.b)
        val spoke = Paint.swatch(Paint.STEEL.d)
        val z = 1.7f
        val r = 6.6f
        // (the wheels' middle: the tyre's thickness above the ground)
        val cy = r + .45f
        for (cx in listOf(-11f, 11f)) {
            val n = 12
            for (i in 0 until n) {
                val a = 2f * PI.toFloat() * i / n; val b = 2f * PI.toFloat() * (i + 1) / n
                m.beam(v(cx + r * cos(a), cy + r * sin(a), z), v(cx + r * cos(b), cy + r * sin(b), z), .9f, tyre)
            }
            for (i in 0 until 4) { val a = PI.toFloat() * i / 4f; m.beam(v(cx - (r - .5f) * cos(a), cy - (r - .5f) * sin(a), z), v(cx + (r - .5f) * cos(a), cy + (r - .5f) * sin(a), z), .25f, spoke) }
            m.solidBox(cx - .6f, cy - .6f, z - .8f, cx + .6f, cy + .6f, z + .8f, Paint.swatch(Paint.STEEL.l))
        }
        val rear = v(-11f, cy, z); val front = v(11f, cy, z); val bb = v(-1.5f, 5.8f, z)
        val seat = v(-4.6f, 15.8f, z); val head = v(7.2f, 15.6f, z); val headLow = v(8.2f, 12.6f, z)
        for ((a, b) in listOf(rear to bb, rear to seat, bb to seat, seat to head, bb to headLow, headLow to head, headLow to front)) m.beam(a, b, 1.1f, fr)
        m.box(-6.4f, 16.2f, z - 1f, -2.8f, 17.2f, z + 1f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.BLACK, 91, wear = 0f) }
        m.beam(seat, v(-4.6f, 16.4f, z), .8f, spoke)
        m.beam(head, v(6.6f, 18.2f, z), .8f, spoke)
        m.beam(v(6.6f, 18.2f, z - 2.4f), v(6.6f, 18.2f, z + 2.2f), .8f, Paint.swatch(Paint.STEEL.d))
        for (s in listOf(-1f, 1f)) m.solidBox(6.0f, 17.6f, z + s * 2.2f - .5f, 7.2f, 18.8f, z + s * 2.2f + .5f, tyre)
        // the crank and pedals
        m.beam(v(-3.4f, 4.4f, z + 1f), v(.4f, 7.2f, z + 1f), .6f, spoke)
        m.solidBox(-4.4f, 3.9f, z + .8f, -2.4f, 4.6f, z + 2.4f, tyre)
    }

    private fun streetSign(m: MeshBuilder, text: List<String>) {
        val pole = Paint.IRON
        // (the pole stops under the blades, which sit on its cap one over the other: nothing in front of the letters)
        m.post(0f, 0f, 0f, 50.6f, 1f, 1f, 6, pole, 101, cap = false) { s -> for (y in 6 until s.h step 9) s.hline(0, s.w - 1, y, pole.l) }
        m.post(0f, 0f, 50.6f, 51.2f, 1.6f, 1.6f, 6, pole, 102)
        m.post(0f, 0f, 0f, 1.2f, 1.6f, 1.4f, 6, pole, 103)
        // the blades: this street's along it, the crossing street's across, white letters on green with a white rim
        fun blade(s: String): PixImg {
            val tw = PixImg.textWidth(s)
            val im = PixImg(tw + 6, 9)
            im.fill(Paint.SIGN.b); im.border(Paint.SIGN.o)
            im.hline(1, im.w - 2, 1, Paint.WHITE.d); im.hline(1, im.w - 2, 7, Paint.WHITE.d)
            im.vline(1, 1, 7, Paint.WHITE.d); im.vline(im.w - 2, 1, 7, Paint.WHITE.d)
            im.text(s, 3, 2, Paint.WHITE.h)
            return im
        }
        val edge = Paint.swatch(Paint.SIGN.o)
        text.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { s ->
            val im = blade(s); val l = im.w * TEXEL; val h = im.h * TEXEL
            m.box(-l / 2f, 51.2f, -.35f, l / 2f, 51.2f + h, .35f) { side, _, _ -> if (side == MeshBuilder.Side.FRONT || side == MeshBuilder.Side.BACK) im else edge }
        }
        text.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { s ->
            val im = blade(s); val l = im.w * TEXEL; val h = im.h * TEXEL
            m.box(-.35f, 51.2f + h, -l / 2f, .35f, 51.2f + 2 * h, l / 2f) { side, _, _ -> if (side == MeshBuilder.Side.LEFT || side == MeshBuilder.Side.RIGHT) im else edge }
        }
    }

    /** A sign plate facing +z (front picture [front], back [back]) as the polygon [pts] (x, y pairs about its middle at [cy]). */
    private fun MeshBuilder.plate(pts: List<Pair<Float, Float>>, cy: Float, z: Float, thick: Float, front: PixImg, back: PixImg, edge: PixImg) {
        val half = front.w * TEXEL / 2f
        val fz = z + thick
        flat(pts.map { v(it.first, cy + it.second, fz) }, front, v(-half, cy + half, fz), v(1f, 0f, 0f), v(0f, -1f, 0f), v(0f, 0f, 1f))
        flat(pts.reversed().map { v(it.first, cy + it.second, z) }, back, v(half, cy + half, z), v(-1f, 0f, 0f), v(0f, -1f, 0f), v(0f, 0f, -1f))
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            quad(v(a.first, cy + a.second, fz), v(b.first, cy + b.second, fz), v(b.first, cy + b.second, z), v(a.first, cy + a.second, z), edge)
        }
    }

    private fun signPost(m: MeshBuilder, top: Float, seed: Int) {
        m.box(-.8f, 0f, -.8f, .8f, top, .8f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.swatch(Paint.STEEL.l); else -> Paint.panel(w, h, Paint.STEEL, seed + s.ordinal, wear = .4f).also { im -> for (y in 2 until h step 3) im[w / 2, y] = Paint.STEEL.o } } }
    }

    private fun stopSign(m: MeshBuilder) {
        signPost(m, 48f, 111)
        val r = 8.4f
        val s = (2f * r / TEXEL).roundToInt() + 1
        val front = PixImg(s, s)
        val back = PixImg(s, s).also { it.fill(Paint.STEEL.b) }
        val c = (s - 1) / 2f
        // the octagon in pixels: its outline, the white rim, red inside, STOP in white
        fun inside(x: Int, y: Int, k: Float): Boolean {
            val dx = abs(x - c); val dy = abs(y - c)
            return dx <= k && dy <= k && dx + dy <= k * 1.414f
        }
        for (y in 0 until s) for (x in 0 until s) front[x, y] = when { !inside(x, y, c) -> 0; !inside(x, y, c - 1f) -> Paint.RED.o; !inside(x, y, c - 2f) -> Paint.WHITE.h; else -> Paint.RED.b }
        for (y in 0 until s) for (x in 0 until s) if (front[x, y] == Paint.RED.b && x + y < s - 3 && !inside(x, y, c - 3f)) front[x, y] = Paint.RED.l
        front.text("STOP", (s - 15) / 2, (s - 5) / 2, Paint.WHITE.h)
        val pts = (0 until 8).map { i -> val a = (PI / 8 + PI / 4 * i).toFloat(); (r * cos(a)) to (r * sin(a)) }
        m.plate(pts, 41f, .8f, .45f, front, back, Paint.swatch(Paint.STEEL.d))
    }

    private fun pedSign(m: MeshBuilder) {
        signPost(m, 50f, 121)
        val h = 8.4f
        val s = (2f * h / TEXEL).roundToInt() + 1
        val front = PixImg(s, s)
        val c = (s - 1) / 2f
        val yel = Paint.c(0xD4E02C); val yelD = Paint.c(0xA8B420); val ink = Paint.c(0x1E1E28)
        for (y in 0 until s) for (x in 0 until s) {
            val d = abs(x - c) + abs(y - c)
            front[x, y] = when { d > c -> 0; d > c - 1f -> ink; d > c - 2f -> yel; d > c - 2.9f -> ink; else -> if (x + y < s - 2) yel else yelD }
        }
        // the walking figure, mid stride
        val fx = (c - 2).toInt(); val fy = (c - 6).toInt()
        val fig = listOf("0110", "0110", "0000", "1110", "1111", "0110", "0111", "0101", "1001", "1001")
        for ((yy, row) in fig.withIndex()) for ((xx, ch) in row.withIndex()) if (ch == '1') front[fx + xx, fy + 1 + yy] = ink
        val back = PixImg(s, s).also { it.fill(Paint.STEEL.b) }
        val pts = listOf(0f to h, h to 0f, 0f to -h, -h to 0f)
        m.plate(pts, 43f, .8f, .45f, front, back, Paint.swatch(Paint.STEEL.d))
        // the arrow plate under it
        val aw = 10; val ah = 5
        val arrow = PixImg(aw, ah).also { im -> im.fill(yel); im.border(ink); im.hline(2, 7, 2, ink); im[3, 1] = ink; im[3, 3] = ink; im[2, 2] = ink }
        val ab = PixImg(aw, ah).also { it.fill(Paint.STEEL.b) }
        val hw = aw * TEXEL / 2f; val hh = ah * TEXEL / 2f
        m.plate(listOf(-hw to hh, hw to hh, hw to -hh, -hw to -hh), 32.4f, .8f, .4f, arrow, ab, Paint.swatch(Paint.STEEL.d))
    }

    private fun callBox(m: MeshBuilder) {
        val r = Paint.RED
        m.post(0f, 0f, 0f, 1.6f, 2.9f, 2.1f, 8, r, 131)
        m.post(0f, 0f, 1.6f, 17f, 2.0f, 2.0f, 8, r, 132, cap = false) { s -> s.hline(0, s.w - 1, 4, r.l); s.hline(0, s.w - 1, 5, r.d) }
        m.box(-3.2f, 17f, -3.2f, 3.2f, 25f, 3.2f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> Paint.panel(w, h, r, 133)
                MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.FRONT -> Paint.panel(w, h, r, 134).also { im ->
                    // the glass to break: white behind a dark frame, the pull handle in it
                    im.rect(1, 2, w - 2, 7, Paint.OUT); im.rect(2, 3, w - 3, 6, Paint.WHITE.h)
                    im.rect(w / 2 - 1, 4, w / 2, 5, r.b)
                    im.hline(2, w - 3, 8, r.h)
                }
                else -> Paint.panel(w, h, r, 135 + s.ordinal).also { im -> im.rect(w / 2 - 1, 3, w / 2, 6, Paint.WHITE.l) }
            }
        }
        m.panelBox(-3.5f, 25f, -3.5f, 3.5f, 25.7f, 3.5f, Paint.IRON, 139)
        // the lamp on top: a red-orange globe that glows at night
        m.post(0f, 0f, 25.7f, 28.2f, 1.4f, 1.4f, 8, r, 140, cap = false) { s ->
            for (y in 0 until s.h) for (x in 0 until s.w) s[x, y] = if (y == 0) Paint.c(0xFF9A5A) else Paint.c(0xF0503A)
            s.hline(0, s.w - 1, s.h - 1, r.o)
            s.glow(0, 0, s.w - 1, s.h - 2)
        }
        m.post(0f, 0f, 28.2f, 29f, 1.6f, .7f, 8, Paint.IRON, 141)
    }

    private fun utilityBox(m: MeshBuilder, variant: Int) {
        val r = if (variant == 1) Paint.BEIGE else Paint.UTIL
        m.panelBox(-9.4f, 0f, -4.8f, 9.4f, 1.6f, 4.8f, Paint.CONCRETE, 151)
        m.box(-9f, 1.6f, -4.5f, 9f, 23.4f, 4.5f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> Paint.panel(w, h, r, 152 + s.ordinal).also { im ->
                    // two doors: the seam, the hinges, handles, vents high up, the warning sticker
                    im.vline(w / 2, 2, h - 3, r.o); im.vline(w / 2 + 1, 2, h - 3, r.l)
                    for (x in listOf(w / 2 - 2, w / 2 + 3)) im.rect(x, h / 2 - 1, x, h / 2 + 1, Paint.STEEL.h)
                    for (y in 3..6 step 1) if (y % 2 == 1) im.hline(2, w / 2 - 3, y, r.d)
                    for (y in listOf(3, h - 5)) { im[1, y] = Paint.STEEL.d; im[w - 2, y] = Paint.STEEL.d }
                    if (s == MeshBuilder.Side.FRONT) {
                        val sx = w / 2 + 5; val sy = 4
                        for (k in 0..3) im.hline(sx + 3 - k, sx + 3 + k, sy + k, Paint.YELLOW.l)
                        im.hline(sx, sx + 6, sy + 3, Paint.OUT); im[sx + 3, sy] = Paint.OUT
                        im[sx + 3, sy + 1] = Paint.OUT; im[sx + 2, sy + 2] = Paint.OUT
                        // grime along the foot and a stray sticker
                        for (x in 1 until w - 1) if ((x * 7) % 5 < 2) im[x, h - 3] = r.d
                        im.rect(4, h - 9, 6, h - 8, Paint.WHITE.l)
                    }
                }
                else -> Paint.panel(w, h, r, 156 + s.ordinal).also { im -> for (y in 3..9 step 2) im.hline(2, w - 3, y, r.d) }
            }
        }
        m.panelBox(-9.4f, 23.4f, -4.9f, 9.4f, 24.2f, 4.9f, r, 158)
    }

    private fun cone(m: MeshBuilder) {
        val r = Paint.ORANGE
        m.box(-3.7f, 0f, -3.7f, 3.7f, .9f, 3.7f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> PixImg(w, h).also { it.fill(r.d); it.border(r.o) }; else -> Paint.panel(w, h, r, 161, wear = 0f).also { it.hline(0, w - 1, 0, r.d) } } }
        m.post(0f, 0f, .9f, 14.2f, 3.1f, .55f, 8, r, 162) { s ->
            // the two reflective white bands (they catch the light at night, faintly)
            for (y in listOf(4, 5, 9)) { s.hline(0, s.w - 1, y, Paint.WHITE.h); s.glow(0, y, s.w - 1, y, 50) }
            s.hline(0, s.w - 1, 1, r.h)
        }
    }

    private fun bench(m: MeshBuilder) {
        val wood = Paint.WOOD
        val iron = Paint.swatch(Paint.IRON.b)
        val ironL = Paint.swatch(Paint.IRON.l)
        fun slat(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, seed: Int) = m.box(x0, y0, z0, x1, y1, z1) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> Paint.swatch(wood.d)
                MeshBuilder.Side.TOP, MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> PixImg(w, h).also { im ->
                    // the bench sprite's slats: light wood, a lit lip, grain streaks, knots
                    im.fill(wood.l); if (h >= 3) { im.hline(0, w - 1, 0, wood.h); im.hline(0, w - 1, h - 1, wood.b) } else im.hline(0, w - 1, 0, wood.h)
                    val rnd = Paint.Rnd(seed + s.ordinal)
                    repeat(w / 4) { val x = rnd.int(w); val y = rnd.int(h); im.hline(x, (x + 2 + rnd.int(4)).coerceAtMost(w - 1), y, wood.b) }
                    repeat(w / 12) { im[rnd.int(w), rnd.int(h)] = wood.d }
                    im.vline(0, 0, h - 1, wood.o); im.vline(w - 1, 0, h - 1, wood.o)
                }
                else -> Paint.swatch(wood.o)
            }
        }
        // seat slats and the back's two
        for ((i, z) in listOf(-4.5f to -2.1f, -1.6f to .8f, 1.3f to 3.7f).withIndex()) slat(-17.5f, 8.4f, z.first, 17.5f, 9.5f, z.second, 171 + i)
        slat(-17.5f, 11f, -5.8f, 17.5f, 13.6f, -4.9f, 175)
        slat(-17.5f, 14.2f, -5.8f, 17.5f, 16.8f, -4.9f, 176)
        // the iron ends: legs, the seat rail, the arm and the back post
        for (x in listOf(-15f, 15f)) {
            m.beam(v(x, 0f, 3.6f), v(x, 8.4f, 3.2f), 1.1f, iron)
            m.beam(v(x, 0f, -4.4f), v(x, 8.4f, -4.4f), 1.1f, iron)
            m.beam(v(x, 8.4f, -4.4f), v(x, 17.4f, -5.9f), 1.1f, iron)
            m.beam(v(x, 7.9f, 3.9f), v(x, 7.9f, -5f), 1f, iron)
            m.beam(v(x, 8.4f, 3.4f), v(x, 12.2f, 3.4f), 1f, iron)
            m.beam(v(x, 12.4f, 4f), v(x, 12.4f, -5.2f), 1.2f, ironL)
            m.solidBox(x - 1.1f, 0f, 2.6f, x + 1.1f, .5f, 4.6f, iron)
            m.solidBox(x - 1.1f, 0f, -5.4f, x + 1.1f, .5f, -3.4f, iron)
        }
    }

    private fun parkingMeter(m: MeshBuilder) {
        m.post(0f, 0f, 0f, .8f, 1.5f, 1.2f, 6, Paint.IRON, 181)
        m.post(0f, 0f, .8f, 15.6f, .8f, .8f, 6, Paint.IRON, 182, cap = false)
        m.box(-2.2f, 15.6f, -1.8f, 2.2f, 21.4f, 1.8f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> Paint.swatch(Paint.STEEL.d)
                MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.FRONT -> Paint.panel(w, h, Paint.STEEL, 183).also { im ->
                    // the display (green digits that glow at night), the coin slot, the knob
                    im.rect(1, 1, w - 2, 2, Paint.c(0x1A2A24)); im[2, 1] = Paint.c(0x7CF08C); im[3, 1] = Paint.c(0x7CF08C); im[2, 2] = Paint.c(0x4CC060)
                    im.glow(1, 1, w - 2, 2)
                    im.hline(2, 3, 4, Paint.OUT); im[w / 2, 5] = Paint.IRON.l
                }
                else -> Paint.panel(w, h, Paint.STEEL, 184 + s.ordinal).also { im -> im.hline(1, w - 2, 3, Paint.STEEL.d) }
            }
        }
        val (w, h) = Paint.stripSize(1.8f, 4.4f)
        val end = Paint.archCap(1.8f, Paint.STEEL)
        m.prism(v(-2.2f, 21.4f, 0f), X, Y, 1.8f, 1.8f, 4.4f, 5, Paint.strip(w / 2 + 1, h, Paint.STEEL, 186), capFar = end, capNear = end, a0 = (-PI / 2).toFloat(), a1 = (PI / 2).toFloat())
    }

    private fun treeGuard(m: MeshBuilder) {
        // the grate round the tree's foot
        m.box(-7f, 0f, -7f, 7f, .35f, 7f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.TOP -> PixImg(w, h).also { im ->
                    im.fill(Paint.IRON.b); im.border(Paint.IRON.o)
                    for (y in 2 until h - 2) for (x in 2 until w - 2) if ((x + y) % 3 == 0 || (x - y + 99) % 3 == 0) im[x, y] = Paint.IRON.d
                    val c = w / 2
                    im.rect(c - 2, c - 2, c + 1, c + 1, Paint.c(0x4A3428))
                }
                MeshBuilder.Side.BOTTOM -> null
                else -> Paint.swatch(Paint.IRON.o)
            }
        }
        // the sapling: its trunk and a crown of three tiers
        m.post(0f, 0f, .35f, 26f, .75f, .6f, 6, Paint.BARK, 191, cap = false) { s -> for (y in 2 until s.h step 3) s[(y * 3) % s.w, y] = Paint.BARK.h }
        fun crown(y0: Float, y1: Float, r0: Float, r1: Float, seed: Int) {
            val (w, h) = Paint.stripSize(max(r0, r1), y1 - y0)
            val im = Paint.strip(w, h, Paint.LEAF, seed, ends = false)
            val rnd = Paint.Rnd(seed)
            for (y in 0 until h) for (x in 0 until w) {
                val t = rnd.next()
                im[x, y] = when { t < .18f -> Paint.LEAF.d; t < .32f -> Paint.LEAF.l; t < .36f -> Paint.LEAF.h; t < .42f -> Paint.LEAF.o; else -> Paint.LEAF.b }
            }
            if (r0 < r1) im.hline(0, w - 1, h - 1, Paint.LEAF.o)
            m.upright(0f, 0f, y0, y1, r0, r1, 8, im, cap = if (r1 > .9f) Paint.cap(r1, Paint.LEAF) else null)
        }
        crown(24f, 31f, 3.4f, 6.6f, 192)
        crown(31f, 36.5f, 6.6f, 5f, 193)
        crown(36.5f, 43.6f, 5f, .7f, 194)
        // the guard: eight bars and two rings
        val bar = Paint.swatch(Paint.IRON.b)
        val ring = Paint.swatch(Paint.IRON.l)
        val gr = 5.6f
        for (i in 0 until 8) {
            val a = PI.toFloat() / 8f + PI.toFloat() / 4f * i
            m.beam(v(gr * sin(a), .3f, gr * cos(a)), v(gr * sin(a), 24f, gr * cos(a)), .7f, bar)
        }
        for (y in listOf(8f, 23.6f)) for (i in 0 until 8) {
            val a = PI.toFloat() / 8f + PI.toFloat() / 4f * i; val b = a + PI.toFloat() / 4f
            m.beam(v(gr * sin(a), y, gr * cos(a)), v(gr * sin(b), y, gr * cos(b)), .7f, ring)
        }
    }

    /** Every kind and look, for the lineup. */
    fun lineup(): List<Pair<PropKind, Int>> = PropKind.values().flatMap { k -> (0 until k.variants).map { k to it } }

    /** The default words on a sign in the lineup. */
    fun sampleText(kind: PropKind): List<String> = if (kind == PropKind.STREET_SIGN) listOf(PropRules.signText("Main Street"), PropRules.signText("Orchard Lane")) else emptyList()
}
