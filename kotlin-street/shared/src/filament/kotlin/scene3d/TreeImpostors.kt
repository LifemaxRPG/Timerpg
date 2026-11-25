package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Far trees as multi-angle impostors (docs/STREAMING.md, "Tree impostors"; Fortnite's octahedral impostors, kept to the
 * horizontal ring a street-level camera sees and snapped to the nearest view, no blend: the stepped, sprite-like look).
 *
 * Each tree look ([TreeKit.Spec.key]) is drawn once from [VIEWS] directions round it by a small software rasterizer: its
 * simple 3D mesh ([TreeKit.mesh]) projected flat, a pixel a texel of the kit's own atlas ([TEXEL]: the near tree's own
 * pixel size, nearest, alpha cut), its faces shaded a little by their normals so the crown keeps its light and shade.
 * The views go into one atlas for the town. Far off, each tree is one upright quad facing the camera, wearing the view
 * nearest the camera's direction from that tree; a chunk's quads are packed again when the camera has moved round it far
 * enough ([needsRebuild]). They stand in for the pack's crossed cards where a chunk's trees have 3D versions, so the
 * trees far off are the same trees as near by (no change of style at the 3D line).
 */
internal object TreeImpostors {
    /** Views round a tree. */
    const val VIEWS = 8

    /** The atlas of every look's views: [px] ARGB rows top first, each look's row of views at [at] (x, y of view 0, w, h; views side by side). */
    class Atlas(val w: Int, val h: Int, val px: IntArray, val at: Map<Int, IntArray>, val half: Map<Int, Float>, val tall: Map<Int, Float>)

    /** Bakes [specs]' views (any thread). */
    fun bake(specs: Collection<TreeKit.Spec>): Atlas {
        val byKey = LinkedHashMap<Int, TreeKit.Spec>()
        for (s in specs) byKey.putIfAbsent(s.key, s)
        class View(val key: Int, val w: Int, val h: Int, val img: Array<IntArray>, val half: Float, val tall: Float)
        val views = byKey.values.map { s ->
            val m = TreeKit.mesh(s, detail = false)
            val b = m.bounds
            val half = max(max(abs(b[0]), abs(b[3])), max(abs(b[2]), abs(b[5]))) + TEXEL
            val tall = b[4] + TEXEL
            val w = ceil(2f * half / TEXEL).toInt().coerceIn(2, 256); val h = ceil(tall / TEXEL).toInt().coerceIn(2, 256)
            View(s.key, w, h, Array(VIEWS) { k -> render(m, k, w, h, half) }, half, tall)
        }.sortedByDescending { it.h }
        // shelves: each look's row of views, as many to a shelf as fit in a width of [ATLAS_W]
        val at = HashMap<Int, IntArray>()
        var x = 0; var y = 0; var shelf = 0; var width = 0
        for (v in views) {
            val rw = v.w * VIEWS
            if (x > 0 && x + rw > ATLAS_W) { y += shelf; x = 0; shelf = 0 }
            at[v.key] = intArrayOf(x, y, v.w, v.h)
            x += rw; shelf = max(shelf, v.h); width = max(width, x)
        }
        val aw = max(4, width); val ah = max(4, y + shelf)
        val px = IntArray(aw * ah)
        for (v in views) {
            val r = at[v.key]!!
            for (k in 0 until VIEWS) for (row in 0 until v.h) System.arraycopy(v.img[k], row * v.w, px, (r[1] + row) * aw + r[0] + k * v.w, v.w)
        }
        return Atlas(aw, ah, px, at, views.associate { it.key to it.half }, views.associate { it.key to it.tall })
    }

    /** [m] seen from view [k] (from local direction (sin, cos) of k / [VIEWS] of a turn, looking at its foot), [w] x [h] pixels. */
    private fun render(m: PropMesh, k: Int, w: Int, h: Int, half: Float): IntArray {
        val th = 2.0 * PI * k / VIEWS
        val dx = sin(th).toFloat(); val dz = cos(th).toFloat()
        // the viewer's right: (dz, -dx); towards the viewer: (dx, dz)
        val out = IntArray(w * h)
        val depth = FloatArray(w * h) { -Float.MAX_VALUE }
        val sx = FloatArray(3); val sy = FloatArray(3); val sd = FloatArray(3)
        val tex = m.tex; val tw = m.texW; val thh = m.texH
        var t = 0
        while (t + 2 < m.idx.size) {
            for (c in 0 until 3) {
                val i = m.idx[t + c]
                val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]
                sx[c] = (x * dz - z * dx + half) / TEXEL
                sy[c] = h - y / TEXEL
                sd[c] = x * dx + z * dz
            }
            val i0 = m.idx[t]; val i1 = m.idx[t + 1]; val i2 = m.idx[t + 2]
            val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
            if (abs(area) > 1e-6f) {
                // (the face's light: a little darker facing down or away, as the near tree's are lit)
                val ny = (m.nrm[i0 * 3 + 1] + m.nrm[i1 * 3 + 1] + m.nrm[i2 * 3 + 1]) / 3f
                val nt = ((m.nrm[i0 * 3] + m.nrm[i1 * 3] + m.nrm[i2 * 3]) * dx + (m.nrm[i0 * 3 + 2] + m.nrm[i1 * 3 + 2] + m.nrm[i2 * 3 + 2]) * dz) / 3f
                val shade = (.78f + .14f * ny + .08f * nt).coerceIn(.6f, 1f)
                val x0 = max(0, floor(min(sx[0], min(sx[1], sx[2]))).toInt()); val x1 = min(w - 1, ceil(max(sx[0], max(sx[1], sx[2]))).toInt())
                val y0 = max(0, floor(min(sy[0], min(sy[1], sy[2]))).toInt()); val y1 = min(h - 1, ceil(max(sy[0], max(sy[1], sy[2]))).toInt())
                for (py in y0..y1) for (pxi in x0..x1) {
                    val qx = pxi + .5f; val qy = py + .5f
                    val w0 = ((sx[1] - qx) * (sy[2] - qy) - (sx[2] - qx) * (sy[1] - qy)) / area
                    val w1 = ((sx[2] - qx) * (sy[0] - qy) - (sx[0] - qx) * (sy[2] - qy)) / area
                    val w2 = 1f - w0 - w1
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                    val d = w0 * sd[0] + w1 * sd[1] + w2 * sd[2]
                    val o = py * w + pxi
                    if (d <= depth[o]) continue
                    val u = w0 * m.uv[i0 * 2] + w1 * m.uv[i1 * 2] + w2 * m.uv[i2 * 2]
                    val v = w0 * m.uv[i0 * 2 + 1] + w1 * m.uv[i1 * 2 + 1] + w2 * m.uv[i2 * 2 + 1]
                    val tu = (u * tw).toInt().coerceIn(0, tw - 1); val tv = (v * thh).toInt().coerceIn(0, thh - 1)
                    val c = tex[tv * tw + tu]
                    if ((c ushr 24) < 128) continue
                    depth[o] = d
                    val r = (((c shr 16) and 255) * shade).roundToInt(); val g = (((c shr 8) and 255) * shade).roundToInt(); val b = ((c and 255) * shade).roundToInt()
                    out[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            t += 3
        }
        return out
    }

    /**
     * [trees] (each a look and its place: x, z, along x, z, out x, z) as quads facing the eye at ([ex], [ez]), each wearing
     * its view nearest the eye's direction from it; null if none has a view in [atlas].
     */
    fun mesh(atlas: Atlas, trees: List<Pair<TreeKit.Spec, FloatArray>>, ex: Float, ez: Float): PropMesh? {
        val list = trees.filter { atlas.at.containsKey(it.first.key) }
        if (list.isEmpty()) return null
        val n = list.size
        val pos = FloatArray(n * 12); val uv = FloatArray(n * 8); val nrm = FloatArray(n * 12); val tan = FloatArray(n * 12); val idx = IntArray(n * 6)
        val bb = floatArrayOf(Float.MAX_VALUE, 0f, Float.MAX_VALUE, -Float.MAX_VALUE, 0f, -Float.MAX_VALUE)
        for ((i, p) in list.withIndex()) {
            val (s, a) = p
            val r = atlas.at[s.key]!!; val half = atlas.half[s.key]!!; val tall = atlas.tall[s.key]!!
            // the eye's direction in the tree's own frame (along, out are orthonormal: the inverse is the transpose)
            val vx = ex - a[0]; val vz = ez - a[1]
            val lx = a[2] * vx + a[3] * vz; val lz = a[4] * vx + a[5] * vz
            var k = (atan2(lx, lz) / (2.0 * PI) * VIEWS).roundToInt() % VIEWS
            if (k < 0) k += VIEWS
            val th = 2.0 * PI * k / VIEWS
            val dxl = sin(th).toFloat(); val dzl = cos(th).toFloat()
            // the view's right and its facing, in the world
            val rx = a[2] * dzl + a[4] * -dxl; val rz = a[3] * dzl + a[5] * -dxl
            val fx = a[2] * dxl + a[4] * dzl; val fz = a[3] * dxl + a[5] * dzl
            val cx = a[0]; val cz = a[1]
            val corners = arrayOf(floatArrayOf(-half, 0f), floatArrayOf(half, 0f), floatArrayOf(half, tall), floatArrayOf(-half, tall))
            val u0 = (r[0] + k * r[2]).toFloat() / atlas.w; val u1 = (r[0] + (k + 1) * r[2]).toFloat() / atlas.w
            val v0 = r[1].toFloat() / atlas.h; val v1 = (r[1] + r[3]).toFloat() / atlas.h
            val uvs = floatArrayOf(u0, v1, u1, v1, u1, v0, u0, v0)
            // (lit as an upright card facing the camera, tipped up a little: the crown's top catches the sky)
            val nl = hypot(fx, fz).coerceAtLeast(1e-4f)
            val nx = fx / nl * .9f; val ny = .44f; val nz = fz / nl * .9f
            for (c in 0 until 4) {
                val o = (i * 4 + c) * 3
                pos[o] = cx + rx * corners[c][0]; pos[o + 1] = corners[c][1]; pos[o + 2] = cz + rz * corners[c][0]
                nrm[o] = nx; nrm[o + 1] = ny; nrm[o + 2] = nz
                tan[o] = rx; tan[o + 1] = 0f; tan[o + 2] = rz
                uv[(i * 4 + c) * 2] = uvs[c * 2]; uv[(i * 4 + c) * 2 + 1] = uvs[c * 2 + 1]
                bb[0] = min(bb[0], pos[o]); bb[2] = min(bb[2], pos[o + 2]); bb[3] = max(bb[3], pos[o]); bb[5] = max(bb[5], pos[o + 2]); bb[4] = max(bb[4], tall)
            }
            val b = i * 4
            idx[i * 6] = b; idx[i * 6 + 1] = b + 1; idx[i * 6 + 2] = b + 2; idx[i * 6 + 3] = b; idx[i * 6 + 4] = b + 2; idx[i * 6 + 5] = b + 3
        }
        return PropMesh(pos, uv, nrm, tan, idx, atlas.w, atlas.h, atlas.px, null, bb)
    }

    /** Whether a chunk at ([cx], [cz]) whose quads faced the eye at ([lx], [lz]) wants packing again for the eye at ([ex], [ez]). */
    fun needsRebuild(cx: Float, cz: Float, lx: Float, lz: Float, ex: Float, ez: Float): Boolean {
        if (lx.isNaN()) return true
        val d = hypot(ex - cx, ez - cz).coerceAtLeast(1f)
        val a0 = atan2(lx - cx, lz - cz); val a1 = atan2(ex - cx, ez - cz)
        var da = abs(a1 - a0); if (da > PI) da = (2 * PI - da).toFloat()
        // (a tenth of a view's turn round the chunk's middle, or a twentieth of the distance)
        return da > (2 * PI / VIEWS) * .1 || hypot(ex - lx, ez - lz) > d * .05f
    }

    /** The atlas's width at most (pixels). */
    const val ATLAS_W = 2048
    /** On or off (a preview's -Dimpostors=0: the pack's crossed cards far off, as before). */
    @JvmStatic var on = true
}
