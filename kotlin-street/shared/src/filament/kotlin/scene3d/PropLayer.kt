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
) {
    private val textures = StreetTextures(engine, srgb)
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    private class Batch(val entity: Int, val proxy: Int, val mi: MaterialInstance, val pmi: MaterialInstance?, val glows: Boolean, val vb: VertexBuffer, val ib: IndexBuffer)
    private val batches = ArrayList<Batch>()

    /** How many props were placed, and drawn in how many batches. */
    var count = 0; private set
    val draws: Int get() = batches.size

    /** Adds [props]: those of one look (the same mesh) go into one batch. */
    fun add(props: List<WorldProp>) {
        val groups = LinkedHashMap<PropMesh, MutableList<WorldProp>>()
        for (p in props) groups.getOrPut(PropKit.mesh(p.spot.kind, p.spot.variant, p.spot.text, p.spot.seed)) { ArrayList() } += p
        for ((mesh, list) in groups) {
            addMesh(mesh, list.map { p -> floatArrayOf(p.x, p.z, p.ax, p.az, p.ox, p.oz, p.spot.turn) }, list.first().spot.kind.shadow)
            count += list.size
        }
    }

    /**
     * Adds [mesh] at each of [at] (world x, z, along x, z, out x, z, turn), as one batch; [casts]: it throws a shadow.
     * (The desktop preview lays its ground this way too.)
     */
    fun addMesh(mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean = false) {
        if (at.isEmpty()) return
        val nv = mesh.vertexCount * at.size
        val vb = ByteBuffer.allocateDirect(nv * STRIDE).order(ByteOrder.nativeOrder())
        val ib = ByteBuffer.allocateDirect(mesh.idx.size * at.size * 4).order(ByteOrder.nativeOrder())
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
                vb.putFloat(x).putFloat(y).putFloat(z).putFloat(mesh.uv[i * 2]).putFloat(mesh.uv[i * 2 + 1])
                vb.putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z); maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            }
            val base = k * mesh.vertexCount
            for (i in mesh.idx) ib.putInt(base + i)
        }
        vb.flip(); ib.flip()
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(nv)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .build(engine)
        vertices.setBufferAt(engine, 0, vb)
        val indices = IndexBuffer.Builder().indexCount(mesh.idx.size * at.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        indices.setBuffer(engine, ib)
        val box = Box((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f + .5f, (maxY - minY) / 2f + .5f, (maxZ - minZ) / 2f + .5f)
        val tex: Texture = textures.make(mesh.tex, mesh.texW, mesh.texH, mips = true)
        val mask: Texture = mesh.mask?.let { textures.make(it, mesh.texW, mesh.texH, linear = true) } ?: textures.black
        val sampler = if (tiled) TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also { it.anisotropy = 8f } else crisp
        val mi = visible.createInstance().also { m ->
            m.setParameter("albedo", tex, sampler); m.setParameter("mask", mask, nearest)
            m.setParameter("uvRect", 0f, 0f, 1f, 1f); m.setParameter("tint", 1f, 1f, 1f, 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f)
            m.setParameter("fade", 1f); m.setParameter("cell", 2f)
        }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(box).culling(false).castShadows(false).receiveShadows(true).priority(4)
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
        batches += Batch(e, pe, mi, pmi, mesh.mask != null, vertices, indices)
    }

    /**
     * This frame: [look] the true camera's clip-space matrix, [dark] how dark it is (0 day, 1 night), [shown] whether
     * the props are drawn at all (not through the classic's leaned looks, which they do not follow).
     */
    fun update(look: FloatArray, dark: Float, shown: Boolean = true) {
        val rm = engine.renderableManager
        val on = smooth((dark - .3f) / .4f)
        for (b in batches) {
            rm.getInstance(b.entity).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (shown) 1 else 0) }
            if (b.proxy != 0) rm.getInstance(b.proxy).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (shown) 1 else 0) }
            if (!shown) continue
            b.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
            if (b.glows) b.mi.setParameter("glow", GLOW, GLOW, GLOW, on)
        }
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
        textures.destroy(all = true)
        count = 0
    }

    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    companion object {
        private const val STRIDE = 36
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
