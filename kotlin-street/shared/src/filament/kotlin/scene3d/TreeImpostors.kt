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
 * Each tree look ([look]: its shape, its height scaled) is drawn once from [VIEWS] directions round it by a small software rasterizer: its
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

    /**
     * The atlas of every look's views: [px] ARGB rows top first, each look's ([look]) row of views at [at] (x, y of view 0,
     * w, h; views side by side), baked at [ref] height with [texel] street units a pixel; [half] and [tall] its quad's size
     * at that height (a tree of another height wears it scaled).
     */
    class Atlas(val w: Int, val h: Int, val px: IntArray, val at: Map<Int, IntArray>, val half: Map<Int, Float>, val tall: Map<Int, Float>,
                val ref: Map<Int, Float> = emptyMap(), val texel: Float = TEXEL, val trees: Int = 0)

    /**
     * A tree's impostor look: its species and variant (its shape), not its height. Each look is baked once at its trees'
     * middle height and worn scaled to each tree's own: the capital has 55k far trees in 166 shapes but 2,472 shape and
     * height pairs, whose views at the near trees' own pixel size (the first version) would have been a 1.9 GB atlas
     * (it never finished baking at a phone's heap, and held ~150 MB of the build's heap at its peak while it tried).
     */
    fun look(s: TreeKit.Spec): Int = s.species * 64 + (s.variant and 63)

    /** Bakes [specs]' views (any thread), in an atlas of at most [ATLAS_W] x [maxRows] pixels. */
    fun bake(specs: Collection<TreeKit.Spec>, maxRows: Int = ATLAS_ROWS): Atlas {
        // each look at its trees' middle height
        val byLook = LinkedHashMap<Int, ArrayList<TreeKit.Spec>>()
        for (s in specs) byLook.getOrPut(look(s)) { ArrayList() } += s
        val reps = byLook.mapValues { (_, l) -> l.sortedBy { it.height }[l.size / 2] }
        class View(val look: Int, val spec: TreeKit.Spec, val b: FloatArray) { var w = 0; var h = 0; var half = 0f; var tall = 0f }
        val views = reps.map { (k, s) -> View(k, s, TreeKit.mesh(s, detail = false).bounds) }
        // the pixel size: the near trees' own ([TEXEL]) if every look fits, else as little coarser as makes them fit (in
        // steps of an eighth; far trees only, past the 3D line, where a texel is about a screen pixel already)
        fun layout(texel: Float): Pair<Int, Map<Int, IntArray>> {
            for (v in views) {
                v.half = max(max(abs(v.b[0]), abs(v.b[3])), max(abs(v.b[2]), abs(v.b[5]))) + texel
                v.tall = v.b[4] + texel
                v.w = ceil(2f * v.half / texel).toInt().coerceIn(2, 256); v.h = ceil(v.tall / texel).toInt().coerceIn(2, 256)
            }
            // shelves: each look's row of views, as many to a shelf as fit in a width of [ATLAS_W]
            val at = HashMap<Int, IntArray>()
            var x = 0; var y = 0; var shelf = 0
            for (v in views.sortedByDescending { it.h }) {
                val rw = v.w * VIEWS
                if (x > 0 && x + rw > ATLAS_W) { y += shelf; x = 0; shelf = 0 }
                at[v.look] = intArrayOf(x, y, v.w, v.h)
                x += rw; shelf = max(shelf, v.h)
            }
            return (y + shelf) to at
        }
        var texel = TEXEL
        var (rows, at) = layout(texel)
        while (rows > maxRows) { texel *= 1.125f; layout(texel).let { rows = it.first; at = it.second } }
        val aw = max(4, at.values.maxOfOrNull { it[0] + it[2] * VIEWS } ?: 0); val ah = max(4, rows)
        val px = IntArray(aw * ah)
        val depth = FloatArray(256 * 256)
        for (v in views) {
            val r = at[v.look]!!
            val m = TreeKit.mesh(v.spec, detail = false)
            for (k in 0 until VIEWS) render(m, k, v.w, v.h, v.half, texel, px, r[1] * aw + r[0] + k * v.w, aw, depth)
        }
        lastBake = "%d trees in %d looks, atlas %d x %d (%.1f MB), pixels %.2f x the near trees'".format(java.util.Locale.US,
            specs.size, views.size, aw, ah, aw.toLong() * ah * 4 / 1048576.0, texel / TEXEL)
        return Atlas(aw, ah, px, at, views.associate { it.look to it.half }, views.associate { it.look to it.tall },
            views.associate { it.look to it.spec.height }, texel, specs.size)
    }

    /**
     * [m] seen from view [k] (from local direction (sin, cos) of k / [VIEWS] of a turn, looking at its foot), [w] x [h]
     * pixels of [texel] units, drawn into [out] from [at] with rows [stride] apart; [depth] is scratch of at least [w] x [h].
     */
    private fun render(m: PropMesh, k: Int, w: Int, h: Int, half: Float, texel: Float, out: IntArray, at: Int, stride: Int, depth: FloatArray) {
        val th = 2.0 * PI * k / VIEWS
        val dx = sin(th).toFloat(); val dz = cos(th).toFloat()
        // the viewer's right: (dz, -dx); towards the viewer: (dx, dz)
        java.util.Arrays.fill(depth, 0, w * h, -Float.MAX_VALUE)
        val sx = FloatArray(3); val sy = FloatArray(3); val sd = FloatArray(3)
        val tex = m.tex; val tw = m.texW; val thh = m.texH
        var t = 0
        while (t + 2 < m.idx.size) {
            for (c in 0 until 3) {
                val i = m.idx[t + c]
                val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]
                sx[c] = (x * dz - z * dx + half) / texel
                sy[c] = h - y / texel
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
                    out[at + py * stride + pxi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            t += 3
        }
    }

    /**
     * [trees] (each a look and its place: x, z, along x, z, out x, z) as quads facing the eye at ([ex], [ez]), each wearing
     * its view nearest the eye's direction from it; null if none has a view in [atlas].
     */
    fun mesh(atlas: Atlas, trees: List<Pair<TreeKit.Spec, FloatArray>>, ex: Float, ez: Float): PropMesh? {
        val list = trees.filter { atlas.at.containsKey(look(it.first)) }
        if (list.isEmpty()) return null
        val n = list.size
        val pos = FloatArray(n * 12); val uv = FloatArray(n * 8); val nrm = FloatArray(n * 12); val tan = FloatArray(n * 12); val idx = IntArray(n * 6)
        val bb = floatArrayOf(Float.MAX_VALUE, 0f, Float.MAX_VALUE, -Float.MAX_VALUE, 0f, -Float.MAX_VALUE)
        for ((i, p) in list.withIndex()) {
            val (s, a) = p
            // (the look baked at its middle height, scaled to this tree's own)
            val lk = look(s)
            val sc = atlas.ref[lk]?.takeIf { it > 0f }?.let { s.height / it } ?: 1f
            val r = atlas.at[lk]!!; val half = atlas.half[lk]!! * sc; val tall = atlas.tall[lk]!! * sc
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
    /**
     * Its height at most (pixels): 2048 x 4096 RGBA is 32 MB of Java heap for the bake (the GPU's copy ASTC 4x4, 8 MB and its
     * mips). The capital's 166 looks fit at 1.60 x the near trees' pixel size.
     */
    const val ATLAS_ROWS = 4096
    /** The last bake in a line (reports). */
    @Volatile var lastBake = ""
    /** On or off (a preview's -Dimpostors=0: the pack's crossed cards far off, as before). */
    @JvmStatic var on = true
}
