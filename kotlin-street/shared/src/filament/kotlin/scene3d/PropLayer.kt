package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.PropCorner
import com.lifemax.rpg.feature.habits.domain.PropFront
import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.PropRules
import com.lifemax.rpg.feature.habits.domain.PropSpot
import com.lifemax.rpg.feature.habits.domain.PropStreet
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.presentation.scene.CrossSide
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A prop standing in the world: [spot] (its kind, look, turn, words) at world ([x], [z]) on the ground, with the street's
 * along direction ([ax], [az]: the prop's +x) and its out direction towards the road ([ox], [oz]: the prop's +z).
 */
class WorldProp(val spot: PropSpot, val x: Float, val z: Float, val ax: Float, val az: Float, val ox: Float, val oz: Float)

/**
 * The street props drawn by Filament: every placed prop of one look baked into one mesh in the world (so a whole street
 * of them is a few dozen draws), wearing its pixel atlas through the street's own lit material, lit and shadowed by the
 * sun like the buildings (a shadow twin in the shadow proxy material throws its shadow), its glowing parts lit at night.
 *
 * Make one with the street's materials, [add] what stands where, then each frame [update] it with the true camera's
 * clip-space matrix (the `look` everything shares) and how dark it is; [destroy] frees it.
 */
internal class PropLayer(
    private val engine: Engine,
    private val scene: Scene,
    private val visible: Material,
    /** The shadow proxy material, or null for no shadows (the albedo comparison). */
    private val proxy: Material?,
    srgb: Boolean = true,
    /** Pictures shared with others (by content; its owner frees them), or null for the layer's own. */
    sharedTextures: StreetTextures? = null,
    /** Whether Filament may cull each batch by its box (true only where Filament's camera is the camera drawn through). */
    private val cull: Boolean = false,
    /**
     * The pictures' anisotropic filtering (16 by default). The desktop's software GL (llvmpipe) lets it blur a magnified
     * picture despite the nearest magnification; a close-up preview passes 1 to see the texels as the phone draws them.
     */
    private val anisotropy: Float = 16f,
) {
    private val ownTextures = sharedTextures == null
    private val textures = sharedTextures ?: StreetTextures(engine, srgb)
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE).also { it.anisotropy = anisotropy }
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    /** Whether the last frame drew them faded (the next whole one sets the fade back). */
    private var faded = false

    private class Batch(
        val entity: Int, val proxy: Int,
        /** Its masked instance (every triangle while it fades or is cut; its cut-out triangles always), or null until wanted. */
        var mi: MaterialInstance?,
        /** Its opaque instance (on its first primitive: the triangles with no cut-out texels, [Cutouts]), or null: none. */
        val omi: MaterialInstance?,
        val pmi: MaterialInstance?,
        /** The shadow twin's opaque instance (its first primitive), or null. */
        val pomi: MaterialInstance?,
        val glows: Boolean, val geo: GeomSlice, val casts: Boolean,
        /** Its world box (centre, half extents): whether the see-through can reach it. */
        val box: FloatArray,
        /** Makes its masked instance with its pictures ([mi] made late). */
        val init: ((MaterialInstance) -> Unit)?,
        /** It has a second primitive (its cut-out triangles, always masked) after the opaque one. */
        val split: Boolean,
        var visible: Boolean = true, var layerShown: Boolean = true, var layerShadow: Boolean = true,
        var ti: Int = 0, var pti: Int = 0,
        /** Its entities, material instances and buffers are gone ([release]): its slot stays so the other batches keep their index. */
        var released: Boolean = false,
        /** Its first primitive wears [mi] now (fading, or cut by the see-through or the cutaway). */
        var masked: Boolean = false,
        /** Stood by [setBatchTransform]: its [box] is not where it stands. */
        var moved: Boolean = false,
    ) {
        // what was last handed to Filament, so an unchanged frame (standing still) sends nothing again: the layer masks
        // (-1 not yet), the look's and the see-through's generation and the glow the drawn instances hold (-1 / NaN: not yet)
        var mask = -1; var pmask = -1
        var lookAt = -1; var seeAt = -1; var glowAt = Float.NaN
        /** The instances drawn now (the per-frame parameters go to these). */
        val first: MaterialInstance get() = if (omi != null && !masked) omi else mi!!
        val second: MaterialInstance? get() = if (omi != null && !masked && split) mi else null
    }
    private val batches = ArrayList<Batch>()

    /** How many props were placed, and drawn in how many batches. */
    var count = 0; private set
    val draws: Int get() = batches.size
    val batchCount: Int get() = batches.size
    /** How many of the batches throw a shadow (have a shadow twin), and the triangles of all of them. */
    val casters: Int get() = batches.count { it.proxy != 0 && !it.released }
    val triangles: Int get() = batches.sumOf { if (it.released) 0 else it.geo.triangles }

    /** Adds [props]: those of one look (the same mesh) go into one batch. */
    fun add(props: List<WorldProp>) {
        for ((packed, casts, n) in Packing.packProps(props)) { upload(packed, casts); count += n }
    }

    /**
     * Adds [mesh] at each of [at] (world x, z, along x, z, out x, z, turn), as one batch; [casts]: it throws a shadow.
     * (The desktop preview lays its ground this way too.) Returns the batch's material instance (for a caller that
     * animates its picture, as [YardLayer] scrolls its water), or null for nothing to add.
     */
    fun addMesh(mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean = false): MaterialInstance? =
        Packing.pack(mesh, at)?.let { upload(it, casts, tiled, own = true) }

    /**
     * A batch made ready for the GPU on any thread ([pack]: no engine call): its vertices and indices, its box, its
     * pictures; [upload] hands it to Filament on the engine's thread.
     */
    class Packed internal constructor(
        internal val vb: ByteBuffer, internal val ib: ByteBuffer, val vertices: Int, val indices: Int, internal val box: FloatArray,
        internal val tex: StreetTextures.Prepared, internal val mask: StreetTextures.Prepared?,
        /** Whether its pictures were packed for it (not shared with an earlier batch of the same mesh). */
        private val ownPictures: Boolean = true,
        /** Its indices are 16-bit ([TownVertex.short]). */
        internal val shortIndices: Boolean = false,
        /** [vb] quantised ([TownVertex.quantize], made on the packing worker): how its materials read it back; null: floats. */
        internal val quant: TownVertex.Quant? = null,
        /** How many of its indices, from the first, are triangles with no cut-out texels ([Cutouts]): drawn opaque where it can. */
        internal val opaqueIndices: Int = 0,
    ) {
        val bytes: Int get() = vb.capacity() + ib.capacity()
        /** What it alone holds until its upload: vertices, indices and, unless shared, its pictures (on the phone, Java heap). */
        val heldBytes: Long get() = bytes.toLong() + if (!ownPictures) 0L else tex.buf.capacity().toLong() + (mask?.buf?.capacity() ?: 0)
    }

    /**
     * Uploads a [Packed] batch (the engine's thread); [casts]: it throws a shadow; [tiled]: its picture repeats; [own]: the
     * caller sets parameters on the instance returned (a pool's water), so it stays masked, every triangle, as before.
     */
    fun upload(p: Packed, casts: Boolean, tiled: Boolean = false, own: Boolean = false): MaterialInstance {
        val geo = BufferArena.upload(engine, p.vb, p.vertices, p.ib, p.indices, p.shortIndices, p.quant)
        textures.gpu.buffers(p.vb.limit(), p.ib.limit())
        com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.bytes += p.vb.limit() + p.ib.limit()
        val b = p.box
        val box = Box(b[0], b[1], b[2], b[3], b[4], b[5])
        val tex: Texture = textures.make(p.tex)
        val mask: Texture = p.mask?.let { textures.make(it) } ?: textures.black
        return finish(geo, box, tex, mask, p.mask != null, casts, tiled, if (own) 0 else p.opaqueIndices)
    }

    object Packing {
        /** [mesh] at each of [at] (see [addMesh]) packed for [upload], on any thread; null for nothing to add. */
        fun pack(mesh: PropMesh, at: List<FloatArray>, share: MutableMap<IntArray, StreetTextures.Prepared>? = null): Packed? {
        if (at.isEmpty()) return null
        val nv = mesh.vertexCount * at.size
        val vb = TownMemory.direct(nv * TownVertex.STRIDE).order(ByteOrder.nativeOrder())
        val short = TownVertex.short(nv)
        val ib = TownMemory.direct(mesh.idx.size * at.size * (if (short) 2 else 4)).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for ((k, a) in at.withIndex()) {
            val c = cos(a[6]); val s = sin(a[6])
            // the prop's own x, y, z to the world: x along, y up, z out (turned by its turn about y first)
            fun wx(lx: Float, lz: Float) = a[2] * (lx * c + lz * s) + a[4] * (-lx * s + lz * c)
            fun wz(lx: Float, lz: Float) = a[3] * (lx * c + lz * s) + a[5] * (-lx * s + lz * c)
            for (i in 0 until mesh.vertexCount) {
                val lx = mesh.pos[i * 3]; val ly = mesh.pos[i * 3 + 1]; val lz = mesh.pos[i * 3 + 2]
                val x = a[0] + wx(lx, lz); val y = ly; val z = a[1] + wz(lx, lz)
                val nx = wx(mesh.nrm[i * 3], mesh.nrm[i * 3 + 2]); val ny = mesh.nrm[i * 3 + 1]; val nz = wz(mesh.nrm[i * 3], mesh.nrm[i * 3 + 2])
                var tx = wx(mesh.tan[i * 3], mesh.tan[i * 3 + 2]); var ty = mesh.tan[i * 3 + 1]; var tz = wz(mesh.tan[i * 3], mesh.tan[i * 3 + 2])
                val d = tx * nx + ty * ny + tz * nz
                tx -= d * nx; ty -= d * ny; tz -= d * nz
                val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
                tx /= tl; ty /= tl; tz /= tl
                val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
                MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
                TownVertex.put(vb, x, y, z, mesh.uv[i * 2], mesh.uv[i * 2 + 1], q)
                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z); maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            }
        }
        // (the triangles with no cut-out texels first, in every copy, then the rest: [Cutouts], drawn opaque where it can)
        val (idx, no) = opaqueFirst(mesh)
        for (part in 0..1) for (k in at.indices) {
            val base = k * mesh.vertexCount
            val from = if (part == 0) 0 else no; val to = if (part == 0) no else idx.size
            if (short) for (j in from until to) ib.putShort((base + idx[j]).toShort()) else for (j in from until to) ib.putInt(base + idx[j])
        }
        vb.flip(); ib.flip()
        val box = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f + .5f, (maxY - minY) / 2f + .5f, (maxZ - minZ) / 2f + .5f)
        // (one mesh packed for many chunks shares one packed picture: the content key makes them one texture anyway, but
        // each chunk once carried its own copy of the bytes, the town's trees' atlas over and over, until uploaded)
        val (tex, fresh) = StreetTextures.prepareShared(mesh.tex, mesh.texW, mesh.texH, linear = false, share)
        // (the mask is sampled nearest, its level 0 only: no mipmaps for it)
        val mask = mesh.mask?.let { StreetTextures.prepareShared(it, mesh.texW, mesh.texH, linear = true, share, mips = false).first }
        // (quantised here on the worker: the float vertices let go at once, only the 20-byte ones wait for the upload)
        val qt = BufferArena.quantized(vb, nv, mesh.texW, mesh.texH)
        return Packed(qt?.vb ?: vb, ib, nv, mesh.idx.size * at.size, box, tex, mask, fresh, short, qt, no * at.size)
        }

        /** [mesh]'s indices with its opaque triangles first ([Cutouts.partition]), and how many indices those are. */
        private fun opaqueFirst(mesh: PropMesh): Pair<IntArray, Int> {
            if (!Cutouts.on) return mesh.idx to 0
            val idx = mesh.idx.copyOf()
            return idx to Cutouts.partition(idx, idx.size, mesh.uv, Cutouts.of(mesh.tex, mesh.texW, mesh.texH))
        }

        /**
         * [mesh] packed in its own local frame at the origin (for per-frame transforms via [setBatchTransform]), on any
         * thread. When [mirrorZ] is true, negates local z (and flips triangle winding) so callers in a mirrored street
         * frame can cancel the reflection and pass positive-determinant matrices to Filament's TransformManager.
         */
        fun packLocal(mesh: PropMesh, mirrorZ: Boolean = false, share: MutableMap<IntArray, StreetTextures.Prepared>? = null): Packed? {
            val nv = mesh.vertexCount
            if (nv == 0 || mesh.idx.isEmpty()) return null
            val vb = ByteBuffer.allocateDirect(nv * TownVertex.STRIDE).order(ByteOrder.nativeOrder())
            val short = TownVertex.short(nv)
            val ib = ByteBuffer.allocateDirect(mesh.idx.size * (if (short) 2 else 4)).order(ByteOrder.nativeOrder())
            val q = FloatArray(4)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            val sz = if (mirrorZ) -1f else 1f
            var opaqueN = 0
            for (i in 0 until nv) {
                val x = mesh.pos[i * 3]; val y = mesh.pos[i * 3 + 1]; val z = mesh.pos[i * 3 + 2] * sz
                val nx = mesh.nrm[i * 3]; val ny = mesh.nrm[i * 3 + 1]; val nz = mesh.nrm[i * 3 + 2] * sz
                var tx = mesh.tan[i * 3]; var ty = mesh.tan[i * 3 + 1]; var tz = mesh.tan[i * 3 + 2] * sz
                val d = tx * nx + ty * ny + tz * nz
                tx -= d * nx; ty -= d * ny; tz -= d * nz
                val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
                tx /= tl; ty /= tl; tz /= tl
                val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
                MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
                TownVertex.put(vb, x, y, z, mesh.uv[i * 2], mesh.uv[i * 2 + 1], q)
                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z); maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            }
            if (mirrorZ) {
                var t = 0
                while (t + 2 < mesh.idx.size) {
                    val i0 = mesh.idx[t]; val i1 = mesh.idx[t + 2]; val i2 = mesh.idx[t + 1]; t += 3
                    if (short) { ib.putShort(i0.toShort()); ib.putShort(i1.toShort()); ib.putShort(i2.toShort()) }
                    else { ib.putInt(i0); ib.putInt(i1); ib.putInt(i2) }
                }
            } else {
                val (idx, no) = opaqueFirst(mesh)
                opaqueN = no
                if (short) for (i in idx) ib.putShort(i.toShort()) else for (i in idx) ib.putInt(i)
            }
            vb.flip(); ib.flip()
            val box = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f + .5f, (maxY - minY) / 2f + .5f, (maxZ - minZ) / 2f + .5f)
            val (tex, fresh) = StreetTextures.prepareShared(mesh.tex, mesh.texW, mesh.texH, linear = false, share)
            val mask = mesh.mask?.let { StreetTextures.prepareShared(it, mesh.texW, mesh.texH, linear = true, share, mips = false).first }
            val qt = BufferArena.quantized(vb, nv, mesh.texW, mesh.texH)
            return Packed(qt?.vb ?: vb, ib, nv, mesh.idx.size, box, tex, mask, fresh, short, qt, opaqueN)
        }

        /** [props] packed for [upload] by look (one batch each), on any thread: each batch, whether it casts, how many props. */
        fun packProps(props: List<WorldProp>, share: MutableMap<IntArray, StreetTextures.Prepared>? = null): List<Triple<Packed, Boolean, Int>> {
            val groups = LinkedHashMap<PropMesh, MutableList<WorldProp>>()
            for (p in props) groups.getOrPut(PropKit.mesh(p.spot.kind, p.spot.variant, p.spot.text, p.spot.seed)) { ArrayList() } += p
            return groups.mapNotNull { (mesh, list) ->
                pack(mesh, list.map { p -> floatArrayOf(p.x, p.z, p.ax, p.az, p.ox, p.oz, p.spot.turn) }, share)?.let { Triple(it, list.first().spot.kind.shadow, list.size) }
            }
        }
    }

    private fun finish(geo: GeomSlice, box: Box, tex: Texture, mask: Texture, glows: Boolean, casts: Boolean, tiled: Boolean, opaqueIndices: Int): MaterialInstance {
        val sampler = if (tiled) TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also { it.anisotropy = min(8f, anisotropy) } else crisp
        val init: (MaterialInstance) -> Unit = { m ->
            m.setParameter("albedo", tex, sampler); m.setParameter("mask", mask, nearest)
            m.setParameter("uvRect", 0f, 0f, 1f, 1f); m.setParameter("tint", 1f, 1f, 1f, 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f)
            m.setParameter("fade", 1f); m.setParameter("cell", 2f)
            geo.decode(m)
        }
        // (its triangles with no cut-out texels drawn opaque, the rest masked: two primitives over its one index range)
        val opaqueMat = if (opaqueIndices > 0) Cutouts.twin(visible) else null
        val no = if (opaqueMat != null) min(opaqueIndices, geo.count) else 0
        val split = opaqueMat != null && no < geo.count
        val omi = opaqueMat?.createInstance()?.also(init)
        val mi = if (omi == null || split) visible.createInstance().also(init) else null
        if (omi != null) Cutouts.primsOpaque.incrementAndGet()
        if (mi != null) Cutouts.primsMasked.incrementAndGet()
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(if (split) 2 else 1).boundingBox(box).culling(cull).castShadows(false).receiveShadows(true).priority(4)
        if (split) {
            rb.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first, no).material(0, omi!!)
            rb.geometry(1, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first + no, geo.count - no).material(1, mi!!)
        } else rb.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first, geo.count).material(0, omi ?: mi!!)
        rb.build(engine, e)
        scene.addEntity(e)
        var pe = 0
        var pmi: MaterialInstance? = null
        var pomi: MaterialInstance? = null
        if (casts && proxy != null) {
            val pinit: (MaterialInstance) -> Unit = { m -> m.setParameter("albedo", tex, crisp); m.setParameter("uvRect", 0f, 0f, 1f, 1f); geo.decode(m) }
            // (the shadow twin is never faded nor cut: its opaque part stays opaque, its shadow pass depth only)
            pomi = if (no > 0) Cutouts.twin(proxy)?.createInstance()?.also(pinit) else null
            val psplit = pomi != null && no < geo.count
            if (pomi == null || psplit) pmi = proxy.createInstance().also(pinit)
            pe = EntityManager.get().create()
            val pb = RenderableManager.Builder(if (psplit) 2 else 1).boundingBox(box).castShadows(true).receiveShadows(false)
            if (psplit) {
                pb.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first, no).material(0, pomi!!)
                pb.geometry(1, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first + no, geo.count - no).material(1, pmi!!)
            } else pb.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, geo.vb, geo.ib, geo.first, geo.count).material(0, pomi ?: pmi!!)
            pb.build(engine, pe)
            scene.addEntity(pe)
        }
        val c = FloatArray(3); val hx = FloatArray(3)
        box.getCenter(c); box.getHalfExtent(hx)
        batches += Batch(e, pe, mi, omi, pmi, pomi, glows, geo, casts, floatArrayOf(c[0], c[1], c[2], hx[0], hx[1], hx[2]), if (omi != null) init else null, split)
        return mi ?: omi!!
    }

    /**
     * Puts [b]'s first primitive on its masked instance (made now if it has none yet) or back on its opaque one. A batch
     * goes masked while it fades, while the see-through can reach it or while its layer is cut away: those are the
     * material's screen doors, which the opaque twin does not have.
     */
    private fun wear(b: Batch, masked: Boolean, ri: Int) {
        if (b.omi == null || b.masked == masked) return
        if (masked && b.mi == null) { b.mi = visible.createInstance().also { m -> b.init!!(m) }; Cutouts.twinsMade.incrementAndGet() }
        b.masked = masked
        // (the instance now drawn gets this frame's parameters whatever it held)
        b.lookAt = -1; b.seeAt = -1; b.glowAt = Float.NaN
        if (ri != 0) engine.renderableManager.setMaterialInstanceAt(ri, 0, if (masked) b.mi!! else b.omi)
    }

    /** Whether the see-through (or this layer's cutaway) may thin batch [b] this frame. */
    private fun cut(b: Batch): Boolean {
        if (cutaway != null) return true
        if (!seeThrough || !SeeThrough.cuts) return false
        if (b.moved) return true
        val x = b.box
        return SeeThrough.reaches(x[0] - x[3], x[1] - x[4], x[2] - x[5], x[0] + x[3], x[1] + x[4], x[2] + x[5])
    }

    /**
     * This frame: [look] the true camera's clip-space matrix, [dark] how dark it is (0 day, 1 night), [shown] whether
     * the props are drawn at all (not through the classic's leaned looks, which they do not follow), [fade] how far they
     * are faded out (1: whole; the screen-door cut of the street view), [shadows] whether their shadow twins cast.
     */
    /** Whether the open world's see-through cone ([SeeThrough]) cuts this layer (not the ground's). */
    var seeThrough = true
    /**
     * The cutaway of this layer's building while the player is inside it ([SeeThrough.cutaway]: 8 floats), or null.
     * Only a landmark's layer sets it.
     */
    var cutaway: FloatArray? = null
    // (it had one lately: its material instances are set back to none once)
    private var cutWas = false
    private fun applyCut(mi: MaterialInstance) {
        val c = cutaway ?: return
        mi.setParameter("seeCut", c[0], c[1], c[2], c[3]); mi.setParameter("seeCutY", c[4], c[5], c[6], c[7])
    }
    /**
     * Its fade is the inverse screen door (the far boxes): at a fade of t it keeps exactly the pixels a thing faded to
     * 1 - t drops, so a crossover between the two never shows both, nor neither, at a pixel.
     */
    var inverseFade = false
    private val lastLook = FloatArray(16)
    /** Bumped whenever the look changes (the batches remember which they hold). */
    private var lookGen = 0
    private var hasLook = false
    private var lastGlowOn = 0f

    fun update(look: FloatArray, dark: Float, shown: Boolean = true, fade: Float = 1f, shadows: Boolean = shown, castFade: Float = 1f) {
        val rm = engine.renderableManager
        val on = smooth((dark - .3f) / .4f)
        if (!hasLook || !look.contentEquals(lastLook)) { System.arraycopy(look, 0, lastLook, 0, 16); lookGen++ }
        hasLook = true
        lastGlowOn = on
        // (its cutaway gone: every batch's set back to none, drawn or not)
        if (cutaway != null) cutWas = true
        else if (cutWas) {
            cutWas = false
            for (i in 0 until batches.size) { val b = batches[i]; if (!b.released) b.mi?.setParameter("seeCut", 0f, 0f, 0f, 0f) }
        }
        // (index loop, no boxing: zero allocations per frame)
        for (i in 0 until batches.size) {
            val b = batches[i]
            if (b.released) continue
            // ([castFade]: the batches of things that throw shadows, the tall ones, cut away on their own)
            val f = if (b.casts && castFade < 1f) min(fade, castFade) else fade
            val layerVis = shown && f > .02f
            val layerShad = shadows && (castFade >= .5f || !b.casts)
            b.layerShown = layerVis
            b.layerShadow = layerShad
            val drawn = b.visible && layerVis
            // (instances are looked up each frame: Filament's component arrays are compacted as other renderables go)
            val bi = rm.getInstance(b.entity)
            // (only what changed goes to Filament: standing still, a frame sends next to nothing; the "green" part's
            // ~3.7 ms CPU a frame on the S25, 0.1.909, was mostly these calls for every batch of every chunk)
            val mk = if (drawn) 1 else 0
            if (bi != 0 && mk != b.mask) { rm.setLayerMask(bi, 0xff, mk); b.mask = mk }
            // (a cut thing's shadow goes with it: a shed's shadow on an empty lawn reads wrong)
            val pk = if (b.visible && layerShad) 1 else 0
            if (b.proxy != 0 && pk != b.pmask) { val pi = rm.getInstance(b.proxy); if (pi != 0) { rm.setLayerMask(pi, 0xff, pk); b.pmask = pk } }
            if (!drawn) continue
            if (b.omi != null) wear(b, f < 1f || cut(b), bi)
            val m0 = b.first; val m1 = b.second
            if (b.lookAt != lookGen) {
                m0.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                m1?.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                b.lookAt = lookGen
            }
            // (the see-through, the cutaway and the fade are the masked instance's alone: the opaque twin has none of them)
            val mm = b.mi
            if (mm != null && (m0 === mm || m1 === mm) && seeThrough) {
                if (b.seeAt != SeeThrough.gen) { SeeThrough.apply(mm); b.seeAt = SeeThrough.gen }
                applyCut(mm)
            }
            if (b.glows && b.glowAt != on) { m0.setParameter("glow", GLOW, GLOW, GLOW, on); m1?.setParameter("glow", GLOW, GLOW, GLOW, on); b.glowAt = on }
            if (f < 1f || faded) mm?.setParameter("fade", if (inverseFade) -f else f)
        }
        if (shown) faded = fade < 1f || castFade < 1f
    }

    /** Shows or hides batch [index] (e.g. a night-only beacon beam); allocates nothing and takes effect immediately. */
    fun setBatchVisible(index: Int, visible: Boolean) {
        val b = batches[index]
        if (b.released || b.visible == visible) return
        b.visible = visible
        val rm = engine.renderableManager
        val bi = rm.getInstance(b.entity)
        if (bi != 0) { b.mask = if (visible && b.layerShown) 1 else 0; rm.setLayerMask(bi, 0xff, b.mask) }
        if (visible && b.layerShown && hasLook) {
            for (m in arrayOf(b.first, b.second)) {
                if (m == null) continue
                m.setParameter("look", MaterialInstance.FloatElement.MAT4, lastLook, 0, 1)
                if (seeThrough && m === b.mi) { SeeThrough.apply(m); applyCut(m) }
                if (b.glows) m.setParameter("glow", GLOW, GLOW, GLOW, lastGlowOn)
            }
            b.lookAt = lookGen; b.seeAt = SeeThrough.gen; b.glowAt = if (b.glows) lastGlowOn else Float.NaN
        }
        if (b.proxy != 0) { val pi = rm.getInstance(b.proxy); if (pi != 0) { b.pmask = if (visible && b.layerShadow) 1 else 0; rm.setLayerMask(pi, 0xff, b.pmask) } }
    }

    /** Stands batch [index] at [m] (column-major 4x4, local to world) via TransformManager; allocates nothing. */
    fun setBatchTransform(index: Int, m: FloatArray) {
        val b = batches[index]
        if (b.released) return
        b.moved = true
        val tm = engine.transformManager
        var ti = b.ti
        if (ti == 0) {
            if (!tm.hasComponent(b.entity)) tm.create(b.entity)
            ti = tm.getInstance(b.entity)
            b.ti = ti
        }
        if (ti != 0) tm.setTransform(ti, m)
        if (b.proxy != 0) {
            var pti = b.pti
            if (pti == 0) {
                if (!tm.hasComponent(b.proxy)) tm.create(b.proxy)
                pti = tm.getInstance(b.proxy)
                b.pti = pti
            }
            if (pti != 0) tm.setTransform(pti, m)
        }
    }

    /**
     * Stands every batch where [m] (a column-major 4x4, model to world) puts it: a layer packed in its own frame at the
     * origin and moved as one each frame (a train's car, a crossing gate's arm). Its box goes with it for culling.
     */
    fun setTransform(m: FloatArray) {
        for (i in 0 until batches.size) setBatchTransform(i, m)
    }

    /**
     * Frees batch [index] for good: out of the scene, its material instances, vertices and indices given back to the GPU
     * (its pictures stay: they are shared). Its slot stays, so the other batches keep their index. What replaces a batch
     * packed again (a street thing the cars took away: [FilamentTown.hideProp]) frees the old one this way; hiding it
     * only kept its buffers on the GPU until the town was let go, a whole batch more for every thing knocked down.
     */
    fun release(index: Int) {
        val b = batches[index]
        if (b.released) return
        b.released = true
        b.visible = false
        val em = EntityManager.get()
        scene.remove(b.entity); engine.destroyEntity(b.entity); em.destroy(b.entity)
        if (b.proxy != 0) { scene.remove(b.proxy); engine.destroyEntity(b.proxy); em.destroy(b.proxy) }
        for (m in arrayOf(b.mi, b.omi, b.pmi, b.pomi)) if (m != null) engine.destroyMaterialInstance(m)
        b.geo.free(engine)
    }

    /**
     * Lets go of everything: its entities at once; [later] (streaming: [Graveyard]) its material instances and own textures
     * over the next frames, in order, instead of now.
     */
    fun destroy(later: Boolean = false) {
        val em = EntityManager.get()
        val held = batches.filter { !it.released }
        for (b in held) {
            scene.remove(b.entity); engine.destroyEntity(b.entity); em.destroy(b.entity)
            if (b.proxy != 0) { scene.remove(b.proxy); engine.destroyEntity(b.proxy); em.destroy(b.proxy) }
        }
        val e = engine
        for (b in held) {
            for (m in arrayOf(b.mi, b.omi, b.pmi, b.pomi)) if (m != null) { if (later) Graveyard.bury(e, m) else e.destroyMaterialInstance(m) }
        }
        for (b in held) b.geo.free(engine)
        batches.clear()
        if (ownTextures) { val t = textures; if (later) Graveyard.later { t.destroy(all = true) } else t.destroy(all = true) }
        count = 0
    }

    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    companion object {
        internal const val STRIDE = TownVertex.STRIDE
        /** How strongly the glowing parts shine at night (their own colours, times the material's 1.8). */
        const val GLOW = 1.6f

        /** The main street's name for its signs (the crossroads' layouts carry the side street's only). */
        @JvmStatic var mainName = "Main Street"

        /**
         * The props of the crossroads drawn today (the main street and the side street's west row), placed by the rules
         * ([PropRules]) and stood in the crossroads' world (main street: x along +X, out -Z; the side street: along +Z, out
         * +X), for [FilamentStreet] to draw.
         */
        fun crossroads(cr: Crossroads, sides: List<CrossSide>): List<WorldProp> {
            val out = ArrayList<WorldProp>()
            val crossName = sides.getOrNull(0)?.stacks?.getOrNull(cr.junction)?.cue?.takeIf { it.isNotBlank() } ?: "Side Street"
            for (k in 0..1) {
                val s = sides.getOrNull(k) ?: continue
                val layout = if (k == 0) cr.main else cr.cross
                val fronts = layout.lefts.indices.filter { k != 0 || it != cr.junction }.mapNotNull { i ->
                    val id = s.stacks.getOrNull(i)?.id ?: return@mapNotNull null
                    val home = s.arts.getOrNull(i)?.stack?.id?.let { SpriteArt.buildings[it] }?.house != null
                    PropFront(layout.lefts[i], layout.lefts[i] + BuildingSpec.W, if (home) PlacementKind.HOME else PlacementKind.SHOP, "", null, "$k:$id")
                }
                val street = if (k == 0) {
                    val m0 = cr.westEdge; val m1 = cr.westEdge + Crossroads.MOUTH
                    PropStreet(
                        "crossroads:main", mainName, layout.start, layout.end, fronts, s.street.furniture.map { it.x },
                        listOf(PropCorner(m0, -1, crossName, stop = false, ped = false, sign = true), PropCorner(m1, 1, null, stop = false, ped = true, sign = false)),
                        listOf(m0 + PropRules.CORNER to m1 - PropRules.CORNER), main = true,
                    )
                } else {
                    // the side street meets the main street's building line (world z 0) at its x -zOff
                    val c = -cr.zOff
                    PropStreet(
                        "crossroads:cross", crossName, c - PropRules.CORNER, layout.end, fronts, s.street.furniture.map { it.x },
                        listOf(PropCorner(c, 1, mainName, stop = true, ped = false, sign = false)), emptyList(),
                    )
                }
                for (sp in PropRules.place(street)) {
                    out += if (k == 0) WorldProp(sp, cr.mainX(sp.x), -sp.out, 1f, 0f, 0f, -1f)
                    else WorldProp(sp, sp.out, cr.crossZ(sp.x), 0f, 1f, 1f, 0f)
                }
            }
            return out
        }
    }
}
