package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.FarmSolids
import com.lifemax.rpg.feature.habits.domain.FarmThing
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.WorldCountry
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import kotlin.math.max
import kotlin.math.min

/**
 * The open country's farmsteads as merged meshes (docs/WORLD_STREAM.md, step 1b's batching): the capital's farm models
 * ([WorldCountry.FARM_MODELS]) meshed once each by [FilamentModels.Mesher] (the same faces, in the same model pixels, as
 * the town draws them) and their pictures packed into **one atlas** ([atlas]): every model's atlas, the farmhouses' lawn
 * and fence pictures, and a swatch for each plain colour (a plain face is the white picture tinted; here it is that
 * colour's texels, the same linear colour once the sRGB picture is read). A cell's or a block's farmsteads are then one
 * mesh wearing it, drawn as one batch ([WorldProps]) instead of a draw for every group of every model (the step 1b drive's
 * chase view at 12 km: about 900 of the country's 1,029 draws).
 *
 * The farmhouses' glass and windows are the mask's ([FilamentModels.Night]'s mask for the model, the same atlas layout):
 * their panes reflect the sky by day as the town's do; by night a fixed share of their windows is lit (the town's own
 * farmhouses switch theirs by the hour, one by one: the country's are all lit or all dark, a quality difference only seen
 * after dusk, far off).
 *
 * Any thread once made ([make]); [mesh] on the workers.
 */
internal class WorldFarmKit private constructor(
    /** The atlas (ARGB, [w] x [h]) and its mask (the glow and glass), the same layout. */
    val tex: IntArray, val mask: IntArray?, val w: Int, val h: Int,
    private val models: Array<Model?>,
) {
    /** A model meshed once: per vertex its x, y, d (model pixels), the atlas's u, v, normal and tangent (x, y, d); its triangles, the opaque ones first. */
    class Model(val v: FloatArray, val nv: Int, val idx: IntArray, val opaque: Int, val shape: ModelShape)

    /** A mesh and how many of its indices, from the first, are opaque triangles ([Cutouts]). */
    class Part(val mesh: PropMesh, val opaque: Int)

    val bytes: Long get() = tex.size * 4L + (mask?.size ?: 0) * 4L + models.sumOf { (it?.v?.size ?: 0) * 4L + (it?.idx?.size ?: 0) * 4L }

    /** Whether model [m] ([WorldCountry.FARM_MODELS]'s index) was made. */
    fun has(m: Int): Boolean = models.getOrNull(m) != null

    /**
     * [d]'s farm buildings as one mesh in the cell's frame (street units, [u] a metre), offset by ([ox], [oz]) into [out]
     * (the opaque triangles of each model first, gathered by [merge]); false for none.
     */
    fun add(d: WorldCountry.Scatter, u: Float, ox: Float, oz: Float, out: Builder): Boolean {
        var any = false
        for (b in d.buildings) {
            val mi = b[5].toInt()
            val m = models.getOrNull(mi) ?: continue
            val key = WorldCountry.FARM_MODELS[mi]
            val t = FarmThing(key, b[0] * u, b[2] * u, b[3], b[4], false, false)
            val pl = FarmSolids.place(t, m.shape)
            out.model(m, pl[0] + ox, pl[1] + oz, pl[2], pl[3], pl[4], pl[5], FarmSolids.K, b[1] * u)
            any = true
        }
        return any
    }

    /** Gathers models placed into one mesh: opaque triangles first, then the cut-out ones. */
    inner class Builder {
        private var pos = FloatArray(4096); private var uv = FloatArray(4096 / 3 * 2 + 2)
        private var nr = FloatArray(4096); private var tn = FloatArray(4096)
        private var nv = 0
        private var opq = IntArray(4096); private var no = 0
        private var cut = IntArray(1024); private var nc = 0
        private val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val empty: Boolean get() = nv == 0

        private fun room(n: Int) {
            if ((nv + n) * 3 <= pos.size) return
            val c = max((nv + n) * 3, pos.size * 2)
            pos = pos.copyOf(c); nr = nr.copyOf(c); tn = tn.copyOf(c); uv = uv.copyOf(c / 3 * 2 + 2)
        }

        /** Model [m] with its front's (x 0, d 0) at ([ox], [oz]), x along ([ax], [az]), its front facing ([nx], [nz]), [k] units a pixel, its foot at [y]. */
        fun model(m: Model, ox: Float, oz: Float, ax: Float, az: Float, nx: Float, nz: Float, k: Float, y: Float) {
            room(m.nv)
            val v = m.v
            for (i in 0 until m.nv) {
                val o = i * 11
                val x = v[o]; val yy = v[o + 1]; val dd = v[o + 2]
                val q = (nv + i) * 3
                // (FilamentModels.pack's own frame: x along the front, d away from it, into the world)
                val wx = ox + ax * x * k - nx * dd * k; val wy = yy * k + y; val wz = oz + az * x * k - nz * dd * k
                pos[q] = wx; pos[q + 1] = wy; pos[q + 2] = wz
                nr[q] = ax * v[o + 5] - nx * v[o + 7]; nr[q + 1] = v[o + 6]; nr[q + 2] = az * v[o + 5] - nz * v[o + 7]
                tn[q] = ax * v[o + 8] - nx * v[o + 10]; tn[q + 1] = v[o + 9]; tn[q + 2] = az * v[o + 8] - nz * v[o + 10]
                uv[(nv + i) * 2] = v[o + 3]; uv[(nv + i) * 2 + 1] = v[o + 4]
                if (wx < b[0]) b[0] = wx; if (wy < b[1]) b[1] = wy; if (wz < b[2]) b[2] = wz
                if (wx > b[3]) b[3] = wx; if (wy > b[4]) b[4] = wy; if (wz > b[5]) b[5] = wz
            }
            if (no + m.opaque > opq.size) opq = opq.copyOf(max(no + m.opaque, opq.size * 2))
            for (i in 0 until m.opaque) opq[no++] = nv + m.idx[i]
            val rest = m.idx.size - m.opaque
            if (nc + rest > cut.size) cut = cut.copyOf(max(nc + rest, cut.size * 2))
            for (i in m.opaque until m.idx.size) cut[nc++] = nv + m.idx[i]
            nv += m.nv
        }

        fun build(): Part? {
            if (nv == 0) return null
            val idx = IntArray(no + nc)
            System.arraycopy(opq, 0, idx, 0, no); System.arraycopy(cut, 0, idx, no, nc)
            return Part(PropMesh(pos.copyOf(nv * 3), uv.copyOf(nv * 2), nr.copyOf(nv * 3), tn.copyOf(nv * 3), idx, w, h, tex, mask, b.copyOf()), no)
        }
    }

    companion object {
        /** The atlas's width (pixels); its pieces start on this grid ([ALIGN]) with [GUTTER] pixels of their own edge round them. */
        private const val W = 2048
        private const val ALIGN = 16
        private const val GUTTER = 8
        /** A plain colour's swatch (pixels a side). */
        private const val SWATCH = 32

        private class Piece(val px: IntArray, val w: Int, val h: Int, val mask: IntArray?) { var x = 0; var y = 0 }

        /**
         * Meshes the farm models with [shapes] and [art] (the farm's own art: the same as WorldProps' farmsteads), packs
         * their pictures; [textures] lends the night light's maker (nothing is uploaded here). Null if no model could be made.
         */
        fun make(shapes: Map<String, ModelShape>, art: (String) -> ModelArt?, textures: StreetTextures, only: (Int) -> Boolean = { true }): WorldFarmKit? {
            val keys = WorldCountry.FARM_MODELS
            val meshers = arrayOfNulls<FilamentModels.Mesher>(keys.size)
            val arts = arrayOfNulls<ModelArt>(keys.size)
            for ((i, k) in keys.withIndex()) {
                if (!only(i)) continue
                val a = art(k) ?: continue
                if (shapes[k] == null) continue
                val g = runCatching { FilamentModels.Mesher(a).also { it.all() } }.getOrNull() ?: continue
                meshers[i] = g; arts[i] = a
            }
            if (meshers.all { it == null }) return null
            // the pieces: each model's atlas (with its night light's mask for a farmhouse), each picture, each plain colour
            val pieces = LinkedHashMap<Any, Piece>()
            for ((i, g) in meshers.withIndex()) {
                g ?: continue
                val a = arts[i]!!
                val atlasKey: Any = a.atlas
                if (atlasKey !in pieces) {
                    val m = if (a.glow != null) runCatching {
                        val night = FilamentModels.Night(a, 0x5eed + i, textures, true)
                        // (a fixed share of the windows lit, the same for every farmhouse of this model; the last light is the signs)
                        night.maskPixels(BooleanArray(night.lights) { k -> ((k * 2654435761L + i * 40503L) ushr 7) % 5 < 3 })
                    }.getOrNull() else null
                    pieces[atlasKey] = Piece(StreetTextures.argb(a.atlas), a.atlas.width, a.atlas.height, m)
                }
                for (k in g.groups.keys) when (k) {
                    is FilamentModels.Key.Pic -> if (k.img !in pieces) pieces[k.img] = Piece(StreetTextures.argb(k.img), k.img.width, k.img.height, null)
                    is FilamentModels.Key.Plain -> if (k !in pieces) pieces[k] = Piece(IntArray(SWATCH * SWATCH) { (255 shl 24) or k.rgb }, SWATCH, SWATCH, null)
                    else -> {}
                }
            }
            // shelves, tallest first, every piece on the grid
            var x = 0; var y = 0; var shelf = 0
            fun up(n: Int) = (n + ALIGN - 1) / ALIGN * ALIGN
            for (p in pieces.values.sortedByDescending { it.h }) {
                val pw = up(p.w + 2 * GUTTER); val ph = up(p.h + 2 * GUTTER)
                if (x + pw > W) { x = 0; y += shelf; shelf = 0 }
                p.x = x + GUTTER; p.y = y + GUTTER
                x += pw; shelf = max(shelf, ph)
            }
            val h = up(y + shelf)
            val tex = IntArray(W * h)
            // (a mask only if a piece has one: the farmhouses' kit; the rest's would be all black)
            val mask = if (pieces.values.any { it.mask != null }) IntArray(W * h) else null
            for (p in pieces.values) {
                for (yy in -GUTTER until p.h + GUTTER) {
                    val sy = yy.coerceIn(0, p.h - 1)
                    val row = (p.y + yy) * W
                    for (xx in -GUTTER until p.w + GUTTER) {
                        val sx = xx.coerceIn(0, p.w - 1)
                        tex[row + p.x + xx] = p.px[sy * p.w + sx]
                        if (p.mask != null && mask != null) mask[row + p.x + xx] = p.mask[sy * p.w + sx]
                    }
                }
            }
            // the models, their uvs into the atlas (a piece's v is GL's, 0 at its bottom row: so is the atlas's)
            val models = arrayOfNulls<Model>(keys.size)
            val white = intArrayOf(-1)
            for ((i, g) in meshers.withIndex()) {
                g ?: continue
                val a = arts[i]!!
                val groups = g.groups.values.filter { it.ni > 0 && (it.key is FilamentModels.Key.Atlas || it.key is FilamentModels.Key.Card || it.key is FilamentModels.Key.Pic || it.key is FilamentModels.Key.Plain) }
                if (groups.isEmpty()) continue
                val nv = groups.sumOf { it.nv }
                val v = FloatArray(nv * 11)
                val opq = ArrayList<Int>(); val cut = ArrayList<Int>()
                var base = 0
                for (gp in groups) {
                    val p = when (val k = gp.key) {
                        is FilamentModels.Key.Pic -> pieces.getValue(k.img)
                        is FilamentModels.Key.Plain -> pieces.getValue(k)
                        else -> pieces.getValue(a.atlas)
                    }
                    val plain = gp.key is FilamentModels.Key.Plain
                    for (j in 0 until gp.nv) {
                        val o = j * 11; val q = (base + j) * 11
                        System.arraycopy(gp.v, o, v, q, 11)
                        // (a plain face reads its swatch's middle; the others their piece's texel)
                        val pu = if (plain) .5f else gp.v[o + 3]
                        val pv = if (plain) .5f else gp.v[o + 4]
                        v[q + 3] = (p.x + pu * p.w) / W
                        v[q + 4] = 1f - (p.y + (1f - pv) * p.h) / h
                    }
                    // (its opaque triangles first, by its own picture's cut-outs, as FilamentModels.pack does)
                    val idx = gp.idx.copyOf(gp.ni)
                    val cutouts: Cutouts? = if (!Cutouts.on) null else when (val k = gp.key) {
                        is FilamentModels.Key.Atlas -> Cutouts.of(a.atlas, a.atlas.width, a.atlas.height) { StreetTextures.argb(a.atlas) }
                        is FilamentModels.Key.Plain -> Cutouts.of(white, 1, 1) { white }
                        is FilamentModels.Key.Pic -> Cutouts.of(k.img, k.img.width, k.img.height) { StreetTextures.argb(k.img) }
                        else -> null
                    }
                    val no = Cutouts.partition(idx, gp.ni, gp.v, cutouts, stride = 11, at = 3)
                    for (t in 0 until no) opq += base + idx[t]
                    for (t in no until gp.ni) cut += base + idx[t]
                    base += gp.nv
                }
                val all = IntArray(opq.size + cut.size)
                for (t in opq.indices) all[t] = opq[t]
                for (t in cut.indices) all[opq.size + t] = cut[t]
                models[i] = Model(v, nv, all, opq.size, a.shape)
            }
            return WorldFarmKit(tex, mask, W, h, models)
        }

        /** [parts] (each in its own frame) offset by ([ox], [oz]) each, merged into one: opaque triangles first. */
        fun merge(parts: List<Triple<Part, Float, Float>>): Part? {
            if (parts.isEmpty()) return null
            if (parts.size == 1 && parts[0].second == 0f && parts[0].third == 0f) return parts[0].first
            val nv = parts.sumOf { it.first.mesh.vertexCount }
            val ni = parts.sumOf { it.first.mesh.idx.size }
            val f = parts[0].first.mesh
            val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3)
            val idx = IntArray(ni)
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var vo = 0; var oi = 0
            val opaque = parts.sumOf { it.first.opaque }
            var ci = opaque
            for ((p, ox, oz) in parts) {
                val m = p.mesh
                val n = m.vertexCount
                for (i in 0 until n) {
                    pos[(vo + i) * 3] = m.pos[i * 3] + ox; pos[(vo + i) * 3 + 1] = m.pos[i * 3 + 1]; pos[(vo + i) * 3 + 2] = m.pos[i * 3 + 2] + oz
                }
                System.arraycopy(m.uv, 0, uv, vo * 2, n * 2)
                System.arraycopy(m.nrm, 0, nr, vo * 3, n * 3)
                System.arraycopy(m.tan, 0, tn, vo * 3, n * 3)
                for (t in 0 until p.opaque) idx[oi++] = vo + m.idx[t]
                for (t in p.opaque until m.idx.size) idx[ci++] = vo + m.idx[t]
                val mb = m.bounds
                b[0] = min(b[0], mb[0] + ox); b[1] = min(b[1], mb[1]); b[2] = min(b[2], mb[2] + oz)
                b[3] = max(b[3], mb[3] + ox); b[4] = max(b[4], mb[4]); b[5] = max(b[5], mb[5] + oz)
                vo += n
            }
            return Part(PropMesh(pos, uv, nr, tn, idx, f.texW, f.texH, f.tex, f.mask, b), opaque)
        }

    }
}
