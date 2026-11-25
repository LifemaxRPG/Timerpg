package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Which triangles can be drawn opaque (docs/NOVEL_OPTIMIZATIONS.md idea 1): a triangle whose picture has no cut-out
 * texel anywhere `street_lit.mat` could read its alpha from draws the same pixels with `blending : opaque` as with
 * `masked`, and an opaque draw keeps the GPU's early depth and LRZ, and its shadow is depth only.
 *
 * The test is conservative. The masked material keeps a fragment whose alpha `a` is over 0.5, and with 4x MSAA its alpha
 * to coverage (Filament turns it on for every masked material) keeps every sample when `(a - 0.5) / fwidth(a) + 0.5 >= 1`:
 * with every alpha read at least [SOLID]/255 (0.8) both hold, since `fwidth` is then at most 2 x 0.2.
 *
 * By default a picture counts whole: opaque only if every texel is at least [SOLID] (then every mip level is too, at
 * any distance or angle). The ground's tiles, the plain-coloured parts and the solid textures pass; the atlases do not.
 *
 * Per triangle (-DopaqueTriangles=1, measured, not worth its cost): the alpha is read with `textureLod` at the pixel's
 * level of detail less 2, bilinear, so at every level of the picture's mip chain (made as [Astc.halve] makes it, a 2 x 2
 * box) up to [DEEPER] past the one where the triangle's rectangle is a texel, the texels under its coordinates, [REACH]
 * more each way (a multisampled pixel at a triangle's edge is shaded at its middle, up to a pixel off the triangle), must
 * be at least [SOLID]. Coordinates outside 0..1 (a tiled picture) pass only if the whole picture is solid.
 */
internal class Cutouts private constructor(private val w: Int, private val h: Int, px: IntArray, tables: Boolean = TRIANGLES) {
    /** Its triangles are judged one by one ([TRIANGLES]: the tables were made); false: only [solid] counts. */
    val judged: Boolean get() = bad.isNotEmpty()
    /** No texel of the picture is under [SOLID]: every triangle on it is opaque. */
    val solid: Boolean
    // per level: its size and which of its texels are under SOLID (a bit each, rows one after another)
    private val lw: IntArray
    private val lh: IntArray
    private val bad: Array<java.util.BitSet>

    init {
        var any = false
        for (c in px) if ((c ushr 24) < SOLID) { any = true; break }
        solid = !any
        if (solid || !tables) { lw = IntArray(0); lh = IntArray(0); bad = emptyArray() }
        else {
            val n = StreetTextures.levelsOf(w, h)
            lw = IntArray(n); lh = IntArray(n)
            val levels = ArrayList<java.util.BitSet>(n)
            var cw = w; var ch = h
            var a = IntArray(w * h) { px[it] ushr 24 }
            for (l in 0 until n) {
                lw[l] = cw; lh[l] = ch
                val bits = java.util.BitSet(cw * ch)
                for (i in 0 until cw * ch) if (a[i] < SOLID) bits.set(i)
                levels += bits
                if (l + 1 < n) {
                    val nw = max(1, cw shr 1); val nh = max(1, ch shr 1)
                    val b = IntArray(nw * nh)
                    for (y in 0 until nh) for (x in 0 until nw) {
                        val x0 = min(x * 2, cw - 1); val x1 = min(x * 2 + 1, cw - 1); val y0 = min(y * 2, ch - 1); val y1 = min(y * 2 + 1, ch - 1)
                        b[y * nw + x] = (a[y0 * cw + x0] + a[y0 * cw + x1] + a[y1 * cw + x0] + a[y1 * cw + x1] + 2) / 4
                    }
                    a = b; cw = nw; ch = nh
                }
            }
            bad = levels.toTypedArray()
        }
    }

    /** Whether the region u0..u1, v0..v1 (texture coordinates) reads only solid alpha at every level. */
    fun clear(u0: Float, v0: Float, u1: Float, v1: Float): Boolean {
        if (solid) return true
        if (bad.isEmpty()) return false
        if (u0 < -EPS || v0 < -EPS || u1 > 1f + EPS || v1 > 1f + EPS) return false
        return region(u0, v0, u1, v1) && (!MIRROR || region(u0, 1f - v1, u1, 1f - v0))
    }

    private fun region(u0: Float, v0: Float, u1: Float, v1: Float): Boolean {
        // (the levels a triangle's pixels can read: up to where its whole rectangle is a texel, [DEEPER] more)
        val ext = max(1f, max((u1 - u0) * w, (v1 - v0) * h))
        val top = if (LEVELS_ALL) bad.size - 1 else min(bad.size - 1, 32 - Integer.numberOfLeadingZeros(kotlin.math.ceil(ext).toInt() - 1) + DEEPER)
        for (l in 0..top) {
            val cw = lw[l]; val ch = lh[l]
            val x0 = max(0, floor(u0 * cw - .5f).toInt() - REACH); val x1 = min(cw - 1, floor(u1 * cw - .5f).toInt() + 1 + REACH)
            val y0 = max(0, floor(v0 * ch - .5f).toInt() - REACH); val y1 = min(ch - 1, floor(v1 * ch - .5f).toInt() + 1 + REACH)
            if (x1 < x0 || y1 < y0) continue
            val bits = bad[l]
            for (y in y0..y1) { val i = bits.nextSetBit(y * cw + x0); if (i >= 0 && i <= y * cw + x1) return false }
        }
        return true
    }

    /** Whether triangle ([a], [b], [c]: vertex indices into [uv], two floats a vertex) is opaque on this picture. */
    fun triangle(uv: FloatArray, a: Int, b: Int, c: Int, stride: Int = 2, at: Int = 0, memo: FloatArray? = null): Boolean {
        if (solid) return true
        val ua = uv[a * stride + at]; val va = uv[a * stride + at + 1]
        val ub = uv[b * stride + at]; val vb = uv[b * stride + at + 1]
        val uc = uv[c * stride + at]; val vc = uv[c * stride + at + 1]
        val u0 = min(ua, min(ub, uc)); val v0 = min(va, min(vb, vc)); val u1 = max(ua, max(ub, uc)); val v1 = max(va, max(vb, vc))
        // (a quad's two triangles, and a mesh's copies, read the same rectangle: the last answer again)
        if (memo != null && memo[0] == u0 && memo[1] == v0 && memo[2] == u1 && memo[3] == v1) return memo[4] > 0f
        val r = clear(u0, v0, u1, v1)
        if (memo != null) { memo[0] = u0; memo[1] = v0; memo[2] = u1; memo[3] = v1; memo[4] = if (r) 1f else 0f }
        return r
    }

    companion object {
        /** The least alpha (of 255) a texel read by an opaque triangle may have (0.8: see the class). */
        const val SOLID = 204
        /** Texels past the triangle's own read at every level (see the class). */
        @JvmField val REACH = System.getProperty("opaqueReach")?.toIntOrNull() ?: 5
        /**
         * Levels checked past the one where the triangle's texture rectangle fits in a texel: the material reads level
         * lod - 2, so a pixel would have to span 16x the rectangle (a sliver seen edge on, narrower than a 16th of a pixel
         * across) to read further up. -DopaqueLevels=all checks every level (then a picture with much cut-out area has
         * nothing opaque: its smallest levels average the holes in).
         */
        @JvmField val DEEPER = System.getProperty("opaqueDeeper")?.toIntOrNull() ?: 2
        private val LEVELS_ALL = System.getProperty("opaqueLevels") == "all"
        private val MIRROR = System.getProperty("opaqueMirror") == "1"
        /**
         * Per-triangle tables (-DopaqueTriangles=1): each triangle of a picture with cut-outs judged on its own (the mip
         * chain under its coordinates). Off by default: on the capital it found 0.2% more triangles opaque than whole
         * pictures do (the atlases are mostly holes, and their small levels average the holes in) for ~10 s of the build
         * workers' time; by default a picture is opaque only if every texel is solid ([solid]), which costs one scan.
         */
        private val TRIANGLES = System.getProperty("opaqueTriangles") == "1"
        private const val EPS = 1e-4f

        /** Draw with the opaque twins where they apply (-Dopaque=0 / off: every batch masked, as before, to compare). */
        @Volatile @JvmField var on = System.getProperty("opaque") != "0"

        // (the last few pictures' tables, by the pixel array's identity: a model's atlas and the yards' shared sheet serve
        // many batches one after another; bounded, so a big sheet's tables are not kept for the town's life)
        private val recent = ArrayList<Pair<Any, Cutouts>>()
        /** How many pictures' tables are kept (a bit a texel and level: the yards' 2048 x 1024 sheet's are ~350 KB). */
        private const val KEEP = 48

        /** The tables of [px] ([w] x [h], ARGB), made once for a few pictures in a row (any thread). */
        fun of(px: IntArray, w: Int, h: Int): Cutouts? = of(px, w, h) { px }

        /**
         * The same for the picture [key] (by identity: an image, a pixel array), its pixels read by [pixels] only if not at
         * hand; null if the pixels are not all there (a picture packed for the GPU and let go, as the tree impostors' atlas):
         * then nothing on it is opaque.
         */
        fun of(key: Any, w: Int, h: Int, pixels: () -> IntArray): Cutouts? {
            if (!TRIANGLES) {
                // (whole pictures: a yes or no, remembered for as long as the picture lives)
                synchronized(wholes) { wholes[key]?.let { return if (it) SOLID_PIC else CUT_PIC } }
                val t0 = System.nanoTime()
                val solid = if (key is androidx.compose.ui.graphics.ImageBitmap) solidImage(key) else pixels().let { px -> px.size >= w * h && solidPixels(px, w * h) }
                buildNs.addAndGet(System.nanoTime() - t0); built.incrementAndGet(); texels.addAndGet(w.toLong() * h)
                synchronized(wholes) { wholes[key] = solid }
                return if (solid) SOLID_PIC else CUT_PIC
            }
            synchronized(recent) {
                for (i in recent.indices) { val r = recent[i]; if (r.first === key && r.second.w == w && r.second.h == h) { if (i > 0) { recent.removeAt(i); recent.add(0, r) }; return r.second } }
            }
            val t0 = System.nanoTime()
            val px = pixels()
            if (px.size < w * h) return null
            val c = Cutouts(w, h, px)
            buildNs.addAndGet(System.nanoTime() - t0); built.incrementAndGet(); texels.addAndGet(w.toLong() * h)
            synchronized(recent) { recent.add(0, key to c); while (recent.size > KEEP) recent.removeAt(recent.size - 1) }
            return c
        }

        private val wholes = java.util.WeakHashMap<Any, Boolean>()
        private val SOLID_PIC = Cutouts(1, 1, intArrayOf(-1), tables = false)
        private val CUT_PIC = Cutouts(1, 1, intArrayOf(0), tables = false)
        private fun solidPixels(px: IntArray, n: Int): Boolean { for (i in 0 until n) if ((px[i] ushr 24) < SOLID) return false; return true }
        /** Whether every texel of [img] is solid, read 16 rows at a time (a picture with holes usually shows one early). */
        private fun solidImage(img: androidx.compose.ui.graphics.ImageBitmap): Boolean {
            val w = img.width; val h = img.height
            val rows = 16
            val buf = IntArray(w * rows)
            var y = 0
            while (y < h) {
                val n = min(rows, h - y)
                img.readPixels(buf, 0, y, w, n)
                if (!solidPixels(buf, w * n)) return false
                y += n
            }
            return true
        }

        // the opaque twin of each masked town material (FilamentTown registers them; anything else stays masked)
        private val twins = java.util.IdentityHashMap<Material, Material>()
        fun register(masked: Material, opaque: Material) { synchronized(twins) { twins[masked] = opaque } }
        fun unregister(masked: Material) { synchronized(twins) { twins.remove(masked) } }
        /** The opaque twin of [masked], if it has one and the twins are [on]. */
        fun twin(masked: Material?): Material? = if (!on || masked == null) null else synchronized(twins) { twins[masked] }

        /** Counters for the reports: triangles classified opaque and cut, primitives drawn opaque (made), twins made later. */
        @JvmField val trisOpaque = java.util.concurrent.atomic.AtomicLong()
        @JvmField val trisCut = java.util.concurrent.atomic.AtomicLong()
        @JvmField val primsOpaque = java.util.concurrent.atomic.AtomicLong()
        @JvmField val primsMasked = java.util.concurrent.atomic.AtomicLong()
        @JvmField val twinsMade = java.util.concurrent.atomic.AtomicLong()
        @JvmField val built = java.util.concurrent.atomic.AtomicLong()
        @JvmField val buildNs = java.util.concurrent.atomic.AtomicLong()
        @JvmField val texels = java.util.concurrent.atomic.AtomicLong()
        @JvmField val sortNs = java.util.concurrent.atomic.AtomicLong()
        fun report(): String = "opaque: triangles %d opaque / %d cut (%.1f%%), primitives %d opaque / %d masked, masked twins made %d; tables %d (%.1f M texels) in %.0f ms, sorting %.0f ms (all workers)".format(
            trisOpaque.get(), trisCut.get(), 100.0 * trisOpaque.get() / max(1L, trisOpaque.get() + trisCut.get()), primsOpaque.get(), primsMasked.get(), twinsMade.get(),
            built.get(), texels.get() / 1e6, buildNs.get() / 1e6, sortNs.get() / 1e6)

        /**
         * Puts [idx]'s triangles whose texture coordinates ([uv], two floats a vertex) are opaque on [cut] first, each part in
         * its own order; returns how many indices the opaque part holds (all: [idx].size; none: 0). [idx] is changed in place.
         */
        fun partition(idx: IntArray, n: Int, uv: FloatArray, cut: Cutouts?, stride: Int = 2, at: Int = 0): Int {
            if (cut == null) { trisCut.addAndGet(n / 3L); return 0 }
            if (cut.solid) { trisOpaque.addAndGet(n / 3L); return n }
            if (!cut.judged) { trisCut.addAndGet(n / 3L); return 0 }
            val t0 = System.nanoTime()
            val memo = FloatArray(5) { Float.NaN }
            val keep = BooleanArray(n / 3) { t -> cut.triangle(uv, idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2], stride, at, memo) }
            sortNs.addAndGet(System.nanoTime() - t0)
            val no = keep.count { it }
            trisOpaque.addAndGet(no.toLong()); trisCut.addAndGet((n / 3 - no).toLong())
            if (no == 0 || no == n / 3) return no * 3
            val out = IntArray(n)
            var o = 0; var c = no * 3
            for (t in 0 until n / 3) {
                if (keep[t]) { out[o++] = idx[t * 3]; out[o++] = idx[t * 3 + 1]; out[o++] = idx[t * 3 + 2] }
                else { out[c++] = idx[t * 3]; out[c++] = idx[t * 3 + 1]; out[c++] = idx[t * 3 + 2] }
            }
            System.arraycopy(out, 0, idx, 0, n)
            return no * 3
        }
    }
}

/**
 * A renderable's primitives that wear an opaque instance ([opaque], null where a primitive is masked for good), their
 * masked twins (made when first wanted by [make]) and [active], the instances drawn now (the array the per-frame
 * parameters go to, changed in place). [wear] moves the opaque primitives to their masked twins while the thing fades or
 * the see-through may reach it, and back.
 */
internal class Twins(
    private val opaque: Array<MaterialInstance?>,
    val active: Array<MaterialInstance>,
    private val make: (Int) -> MaterialInstance,
) {
    private val masked = arrayOfNulls<MaterialInstance>(opaque.size)
    var isMasked = false; private set

    fun wear(engine: Engine, entity: Int, masked: Boolean) {
        if (masked == isMasked) return
        isMasked = masked
        val rm = engine.renderableManager
        val ri = rm.getInstance(entity)
        for (j in opaque.indices) {
            val o = opaque[j] ?: continue
            val m = if (masked) this.masked[j] ?: make(j).also { this.masked[j] = it; Cutouts.twinsMade.incrementAndGet() } else o
            active[j] = m
            if (ri != 0) rm.setMaterialInstanceAt(ri, j, m)
        }
    }

    /** The instances not in [active] now (the other half of each pair made): destroy them with the renderable. */
    val idle: List<MaterialInstance> get() = opaque.indices.mapNotNull { j -> if (isMasked) opaque[j] else masked[j] }
}
