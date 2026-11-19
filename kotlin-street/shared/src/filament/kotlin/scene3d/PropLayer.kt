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

    private class Batch(val entity: Int, val proxy: Int, val mi: MaterialInstance, val pmi: MaterialInstance?, val glows: Boolean, val vb: VertexBuffer, val ib: IndexBuffer, val casts: Boolean)
    private val batches = ArrayList<Batch>()

    /** How many props were placed, and drawn in how many batches. */
    var count = 0; private set
    val draws: Int get() = batches.size
    /** How many of the batches throw a shadow (have a shadow twin), and the triangles of all of them. */
    val casters: Int get() = batches.count { it.proxy != 0 }
    val triangles: Int get() = batches.sumOf { it.ib.indexCount / 3 }

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
        Packing.pack(mesh, at)?.let { upload(it, casts, tiled) }

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
    ) {
        val bytes: Int get() = vb.capacity() + ib.capacity()
        /** What it alone holds until its upload: vertices, indices and, unless shared, its pictures (on the phone, Java heap). */
        val heldBytes: Long get() = bytes.toLong() + if (!ownPictures) 0L else tex.buf.capacity().toLong() + (mask?.buf?.capacity() ?: 0)
    }

    /** Uploads a [Packed] batch (the engine's thread); [casts]: it throws a shadow; [tiled]: its picture repeats. */
    fun upload(p: Packed, casts: Boolean, tiled: Boolean = false): MaterialInstance {
        val vertices = TownVertex.buffer(engine, p.vertices)
        vertices.setBufferAt(engine, 0, p.vb)
        val indices = TownVertex.indices(engine, p.indices, p.shortIndices)
        indices.setBuffer(engine, p.ib)
        textures.gpu.buffers(p.vb.limit(), p.ib.limit())
        val b = p.box
        val box = Box(b[0], b[1], b[2], b[3], b[4], b[5])
        val tex: Texture = textures.make(p.tex)
        val mask: Texture = p.mask?.let { textures.make(it) } ?: textures.black
        return finish(vertices, indices, box, tex, mask, p.mask != null, casts, tiled)
    }

    object Packing {
        /** [mesh] at each of [at] (see [addMesh]) packed for [upload], on any thread; null for nothing to add. */
        fun pack(mesh: PropMesh, at: List<FloatArray>, share: MutableMap<IntArray, StreetTextures.Prepared>? = null): Packed? {
        if (at.isEmpty()) return null
        val nv = mesh.vertexCount * at.size
        val vb = ByteBuffer.allocateDirect(nv * TownVertex.STRIDE).order(ByteOrder.nativeOrder())
        val short = TownVertex.short(nv)
        val ib = ByteBuffer.allocateDirect(mesh.idx.size * at.size * (if (short) 2 else 4)).order(ByteOrder.nativeOrder())
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
            val base = k * mesh.vertexCount
            if (short) for (i in mesh.idx) ib.putShort((base + i).toShort()) else for (i in mesh.idx) ib.putInt(base + i)
        }
        vb.flip(); ib.flip()
        val box = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f + .5f, (maxY - minY) / 2f + .5f, (maxZ - minZ) / 2f + .5f)
        // (one mesh packed for many chunks shares one packed picture: the content key makes them one texture anyway, but
        // each chunk once carried its own copy of the bytes, the town's trees' atlas over and over, until uploaded)
        val (tex, fresh) = StreetTextures.prepareShared(mesh.tex, mesh.texW, mesh.texH, linear = false, share)
        // (the mask is sampled nearest, its level 0 only: no mipmaps for it)
        val mask = mesh.mask?.let { StreetTextures.prepareShared(it, mesh.texW, mesh.texH, linear = true, share, mips = false).first }
        return Packed(vb, ib, nv, mesh.idx.size * at.size, box, tex, mask, fresh, short)
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

    private fun finish(vertices: VertexBuffer, indices: IndexBuffer, box: Box, tex: Texture, mask: Texture, glows: Boolean, casts: Boolean, tiled: Boolean): MaterialInstance {
        val sampler = if (tiled) TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also { it.anisotropy = min(8f, anisotropy) } else crisp
        val mi = visible.createInstance().also { m ->
            m.setParameter("albedo", tex, sampler); m.setParameter("mask", mask, nearest)
            m.setParameter("uvRect", 0f, 0f, 1f, 1f); m.setParameter("tint", 1f, 1f, 1f, 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f)
            m.setParameter("fade", 1f); m.setParameter("cell", 2f)
        }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(box).culling(cull).castShadows(false).receiveShadows(true).priority(4)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices).material(0, mi).build(engine, e)
        scene.addEntity(e)
        var pe = 0
        var pmi: MaterialInstance? = null
        if (casts && proxy != null) {
            pmi = proxy.createInstance().also { m -> m.setParameter("albedo", tex, crisp); m.setParameter("uvRect", 0f, 0f, 1f, 1f) }
            pe = EntityManager.get().create()
            RenderableManager.Builder(1).boundingBox(box).castShadows(true).receiveShadows(false)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices).material(0, pmi).build(engine, pe)
            scene.addEntity(pe)
        }
        batches += Batch(e, pe, mi, pmi, glows, vertices, indices, casts)
        return mi
    }

    /**
     * This frame: [look] the true camera's clip-space matrix, [dark] how dark it is (0 day, 1 night), [shown] whether
     * the props are drawn at all (not through the classic's leaned looks, which they do not follow), [fade] how far they
     * are faded out (1: whole; the screen-door cut of the street view), [shadows] whether their shadow twins cast.
     */
    fun update(look: FloatArray, dark: Float, shown: Boolean = true, fade: Float = 1f, shadows: Boolean = shown, castFade: Float = 1f) {
        val rm = engine.renderableManager
        val on = smooth((dark - .3f) / .4f)
        // (no boxing: thousands of batches a frame, and an instance boxed for takeIf was a garbage object each)
        for (b in batches) {
            // ([castFade]: the batches of things that throw shadows, the tall ones, cut away on their own)
            val f = if (b.casts && castFade < 1f) min(fade, castFade) else fade
            val drawn = shown && f > .02f
            val bi = rm.getInstance(b.entity)
            if (bi != 0) rm.setLayerMask(bi, 0xff, if (drawn) 1 else 0)
            // (a cut thing's shadow goes with it: a shed's shadow on an empty lawn reads wrong)
            if (b.proxy != 0) { val pi = rm.getInstance(b.proxy); if (pi != 0) rm.setLayerMask(pi, 0xff, if (shadows && (castFade >= .5f || !b.casts)) 1 else 0) }
            if (!drawn) continue
            b.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
            if (b.glows) b.mi.setParameter("glow", GLOW, GLOW, GLOW, on)
            if (f < 1f || faded) b.mi.setParameter("fade", f)
        }
        if (shown) faded = fade < 1f || castFade < 1f
    }

    fun destroy() {
        val em = EntityManager.get()
        for (b in batches) {
            scene.remove(b.entity); engine.destroyEntity(b.entity); em.destroy(b.entity)
            if (b.proxy != 0) { scene.remove(b.proxy); engine.destroyEntity(b.proxy); em.destroy(b.proxy) }
        }
        for (b in batches) { engine.destroyMaterialInstance(b.mi); b.pmi?.let { engine.destroyMaterialInstance(it) } }
        for (b in batches) { engine.destroyVertexBuffer(b.vb); engine.destroyIndexBuffer(b.ib) }
        batches.clear()
        if (ownTextures) textures.destroy(all = true)
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
