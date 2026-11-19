package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.TownFigure
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The town's people (TOWN.md T10): each [TownFigure] of [TownPlay.people] (passers on the live rows, the people to talk to
 * at their doors, the player) drawn as the Filament street draws its people: a card of our 32 x 64 character sheets
 * standing upright on its feet, turned square to the camera, lit with the street's material, with a twin turned across
 * the sun that throws its shadow. Cards are made as the crowd grows and hidden when not needed; the street view's cut
 * fades the near side's people with their row.
 */
internal class TownPeople(
    private val engine: Engine,
    private val scene: Scene,
    private val lit: Material,
    private val proxyMat: Material,
    private val textures: StreetTextures,
) {
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null

    private class Card(val e: Int, val mi: MaterialInstance, val proxy: Int, val pmi: MaterialInstance)
    private val cards = ArrayList<Card>()
    private val xf = FloatArray(16)

    /** How many were drawn last frame. */
    var drawn = 0; private set

    private fun quad() {
        if (vb != null) return
        val stride = 36
        val b = ByteBuffer.allocateDirect(4 * stride).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        MathUtils.packTangentFrame(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, q)
        val pos = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        for (v in 0..3) b.putFloat(pos[v * 2]).putFloat(pos[v * 2 + 1]).putFloat(0f).putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]).putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
        b.flip()
        val v = VertexBuffer.Builder().bufferCount(1).vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, stride)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, stride)
            .build(engine)
        v.setBufferAt(engine, 0, b)
        val ix = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder())
        for (s in shortArrayOf(0, 1, 2, 0, 2, 3)) ix.putShort(s)
        ix.flip()
        val i = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        i.setBuffer(engine, ix)
        vb = v; ib = i
    }

    private fun card(): Card {
        quad()
        val mi = lit.createInstance().also { m ->
            m.setParameter("albedo", textures.white, nearest); m.setParameter("mask", textures.black, nearest)
            m.setParameter("uvRect", 0f, 0f, 1f, 1f); m.setParameter("tint", 1f, 1f, 1f, 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f); m.setParameter("fade", 1f); m.setParameter("cell", 2f)
        }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box(.5f, .5f, 0f, .5f, .5f, .1f)).culling(false).castShadows(false).receiveShadows(true).priority(5)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb!!, ib!!).material(0, mi).build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        val pmi = proxyMat.createInstance().also { m -> m.setParameter("albedo", textures.white, nearest); m.setParameter("uvRect", 0f, 0f, 1f, 1f) }
        val p = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box(.5f, .5f, 0f, .5f, .5f, .1f)).culling(false).castShadows(true).receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb!!, ib!!).material(0, pmi).build(engine, p)
        engine.transformManager.create(p)
        scene.addEntity(p)
        return Card(e, mi, p, pmi).also { cards += it }
    }

    private fun show(e: Int, on: Boolean) {
        val rm = engine.renderableManager
        val i = rm.getInstance(e)
        if (i != 0) rm.setLayerMask(i, 0xff, if (on) 1 else 0)
    }

    /**
     * Stands [n] of [figures] for [pose] (whose [look] matrix the town's frame just made), wearing [sheets]; [sun] the sun's
     * direction (its shadow twins stand across it), [fade] the street view's cut at a world point (1 drawn, 0 cut away).
     */
    fun update(pose: TownPose, look: FloatArray, figures: List<TownFigure>, n: Int, sheets: List<ImageBitmap>, sun: FloatArray, fade: (Float, Float) -> Float) {
        while (cards.size < n) card()
        var px = -sun[2]; var pz = sun[0]
        val pl = sqrt(px * px + pz * pz)
        if (pl < 1e-3f) { px = 1f; pz = 0f } else { px /= pl; pz /= pl }
        val k = SpriteArt.PIXEL
        drawn = 0
        for ((i, c) in cards.withIndex()) {
            val f = if (i < n) figures[i] else null
            if (f == null || sheets.isEmpty()) { show(c.e, false); show(c.proxy, false); continue }
            // (the player is never cut away: the camera follows them, also through a turn where it backs off past the corner)
            val fd = if (f.me) 1f else fade(f.x, f.z) * pose.rangeFade(f.x, 30f, f.z, 40f)
            val on = fd > .02f && pose.sees(f.x, 30f, f.z, 40f) && pose.distance(f.x, 30f, f.z) > CameraRig.NEAR + 10f
            show(c.e, on); show(c.proxy, on && fd > .5f)
            if (!on) continue
            drawn++
            val sheet = sheets[TownPlay.sheetOf(f, sheets.size)]
            // (the sheet's front, back or side by the angle between the way they face and the camera's look; in the open world
            // the player's own too: the street view's up-and-down-the-pavement override is the street view's)
            val (view, flip) = if (pose.open && f.me && f.facing != 0) {
                val facing = f.facing; f.facing = 0
                TownPlay.view(f, pose.groundX, pose.groundZ, pose.rx, pose.rz).also { f.facing = facing }
            } else TownPlay.view(f, pose.groundX, pose.groundZ, pose.rx, pose.rz)
            val sx = (view * 6 + f.frame) * 32f
            val sy = (if (f.still) 1 else 2) * 64f
            // (textures are stored bottom row first, so a row band counts from the bottom)
            var u0 = sx / sheet.width; var du = 32f / sheet.width; val dv = 64f / sheet.height; val v0 = 1f - sy / sheet.height - dv
            if (flip < 0f) { u0 += du; du = -du }
            val tex = textures.of(sheet)
            val w = 32f * k; val h = 64f * k
            val left = -16f * k
            // facing the camera: across its right, towards it
            place(c.e, f.x, f.z, pose.rx, pose.rz, -pose.groundX, -pose.groundZ, left, w, h)
            place(c.proxy, f.x, f.z, px, pz, -pz, px, left, w, h)
            c.mi.setParameter("albedo", tex, nearest)
            c.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
            c.mi.setParameter("uvRect", u0, v0, du, dv)
            c.mi.setParameter("fade", fd)
            c.pmi.setParameter("albedo", tex, nearest)
            c.pmi.setParameter("uvRect", u0, v0, du, dv)
        }
    }

    /** Places card [e] standing at ([x], [z]): its picture across ([ax], [az]), facing ([nx], [nz]), [w] x [h], its left edge [left] from the feet. */
    private fun place(e: Int, x: Float, z: Float, ax: Float, az: Float, nx: Float, nz: Float, left: Float, w: Float, h: Float) {
        val tm = engine.transformManager
        xf[0] = ax * w; xf[1] = 0f; xf[2] = az * w; xf[3] = 0f
        xf[4] = 0f; xf[5] = h; xf[6] = 0f; xf[7] = 0f
        xf[8] = nx; xf[9] = 0f; xf[10] = nz; xf[11] = 0f
        // (the sheet's feet are on its bottom pixel row: the card stands on the ground)
        xf[12] = x + ax * left; xf[13] = 0f; xf[14] = z + az * left; xf[15] = 1f
        tm.setTransform(tm.getInstance(e), xf)
    }

    fun destroy() {
        val em = EntityManager.get()
        for (c in cards) {
            for (e in intArrayOf(c.e, c.proxy)) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            engine.destroyMaterialInstance(c.mi); engine.destroyMaterialInstance(c.pmi)
        }
        cards.clear()
        vb?.let { engine.destroyVertexBuffer(it) }; ib?.let { engine.destroyIndexBuffer(it) }
        vb = null; ib = null
    }
}
