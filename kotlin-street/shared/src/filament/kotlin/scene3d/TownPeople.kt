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
import com.lifemax.rpg.feature.habits.presentation.scene.BikeState
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.TownFigure
import com.lifemax.rpg.feature.habits.presentation.scene.TownFolk
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

    /** How dark it is (0 day .. 1 night; the bike's lamp), set by the town each frame. */
    var dark = 0f
    /** The bicycle the player rides (made on first need). */
    private var bike: TownBike? = null
    private val local = FloatArray(3)

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
    /** Where a townsperson's sheet is read when first seen ([StreetTextures.warmLater]); null: read in the frame. */
    var warmPool: java.util.concurrent.Executor? = null

    fun update(pose: TownPose, look: FloatArray, figures: List<TownFigure>, n: Int, sheets: List<ImageBitmap>, sun: FloatArray, fade: (Float, Float) -> Float) {
        while (cards.size < n) card()
        var px = -sun[2]; var pz = sun[0]
        val pl = sqrt(px * px + pz * pz)
        if (pl < 1e-3f) { px = 1f; pz = 0f } else { px /= pl; pz /= pl }
        val k = SpriteArt.PIXEL
        drawn = 0
        var riding = false
        for (i in cards.indices) {
            val c = cards[i]
            val f = if (i < n) figures[i] else null
            if (f == null || sheets.isEmpty()) { show(c.e, false); show(c.proxy, false); continue }
            // (the player is never cut away: the camera follows them, also through a turn where it backs off past the corner)
            val fd = if (f.me) 1f else fade(f.x, f.z) * pose.rangeFade(f.x, 30f, f.z, 40f)
            var on = fd > .02f && pose.sees(f.x, 30f, f.z, 40f) && pose.distance(f.x, 30f, f.z) > CameraRig.NEAR + 10f
            // (the townspeople wear their own baked sheets, by their seed, sex and age; the player their chosen one)
            val folk = TownFolk.sheets
            val sheet = if (!f.me && f.folk in folk.indices) folk[f.folk] else sheets[TownPlay.sheetOf(f, sheets.size)]
            // (a townsperson whose sheet is not read yet waits a frame or two for a worker to read it, rather than the
            // frame reading it: they come into view at the live rows' edge, far off)
            val wp = warmPool
            if (on && !f.me && wp != null && !textures.ready(sheet)) { textures.warmLater(sheet, wp); on = false }
            show(c.e, on); show(c.proxy, on && fd > .5f)
            if (!on) continue
            drawn++
            val bk = f.bike
            if (bk != null) {
                riding = true
                ride(c, f, bk, pose, look, sheet, fd)
                continue
            }
            // (the sheet's front, back or side by the angle between the way they face and the camera's look; in the open world
            // the player's own too: the street view's up-and-down-the-pavement override is the street view's)
            val code = if (pose.open && f.me && f.facing != 0) {
                val facing = f.facing; f.facing = 0
                TownPlay.viewCode(f, pose.groundX, pose.groundZ, pose.rx, pose.rz).also { f.facing = facing }
            } else TownPlay.viewCode(f, pose.groundX, pose.groundZ, pose.rx, pose.rz)
            val view = code shr 1; val flip = if (code and 1 == 1) -1f else 1f
            val sx = (view * 6 + f.frame) * 32f
            // (a baked sheet holds only the idle and walk rows; ours have the pack's preview row above them)
            val sy = (if (f.still) 1 else 2) * 64f - (if (sheet.height <= 128) 64f else 0f)
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
        if (!riding) bike?.hide()
    }

    /**
     * The rider: the bike stood and turned from the ride ([TownBike]), and the player's card on it, leaning with it. From the
     * side the sheet's seated frames (row 4: right-facing 0-5, left 6-11) sit on the saddle; from the front or back the
     * walk's frames stand on the pedals and follow the cranks (one walk cycle a crank turn), the idle ones when coasting.
     */
    private fun ride(c: Card, f: TownFigure, bk: BikeState, pose: TownPose, look: FloatArray, sheet: ImageBitmap, fd: Float) {
        val bd = bike ?: TownBike(engine, scene, lit, proxyMat, textures).also { bike = it }
        val glow = ((dark - .3f) / .4f).coerceIn(0f, 1f)
        val bm = bd.update(bk, f.x, f.z, f.dx, f.dz, look, glow, fd)
        val g = bd.geometry ?: return
        val k = SpriteArt.PIXEL
        val across = f.dx * pose.rx + f.dz * pose.rz
        val away = f.dx * pose.groundX + f.dz * pose.groundZ
        val side = kotlin.math.abs(away) < .8f
        val sx: Float; val sy: Float
        if (side) {
            // (seated, facing the way it rides on the screen)
            sx = ((if (across >= 0f) 0 else 6) + f.frame.mod(6)) * 32f
            sy = 4 * 64f
            local[0] = g.hips[0] + 3f * k / BikeState.SCALE; local[1] = g.hips[1] - 13f * k / BikeState.SCALE; local[2] = 0f
        } else {
            val view = if (away > 0f) 1 else 3
            val pedal = if (bk.pedalling && bk.speed > 1f) ((bk.crank / (2f * Math.PI.toFloat()) * 6f).toInt().mod(6)) else -1
            sx = (view * 6 + (if (pedal >= 0) pedal else f.frame.mod(6))) * 32f
            sy = (if (pedal >= 0) 2 else 1) * 64f
            local[0] = g.hips[0] + 1f; local[1] = g.bb[1] - 2f; local[2] = 0f
        }
        val u0 = sx / sheet.width; val du = 32f / sheet.width; val dv = 64f / sheet.height; val v0 = 1f - sy / sheet.height - dv
        val tex = textures.of(sheet)
        // the card's foot in the bike's frame, to the world; it stands up the bike's leaned up, across the camera's right
        val px = bm[0] * local[0] + bm[4] * local[1] + bm[8] * local[2] + bm[12]
        val py = bm[1] * local[0] + bm[5] * local[1] + bm[9] * local[2] + bm[13]
        val pz = bm[2] * local[0] + bm[6] * local[1] + bm[10] * local[2] + bm[14]
        val ux = bm[4] / BikeState.SCALE; val uy = bm[5] / BikeState.SCALE; val uz = bm[6] / BikeState.SCALE
        var rx = pose.rx; var ry = 0f; var rz = pose.rz
        val d = rx * ux + ry * uy + rz * uz
        rx -= d * ux; ry -= d * uy; rz -= d * uz
        val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-4f)
        rx /= rl; ry /= rl; rz /= rl
        val w = 32f * k; val h = 64f * k
        for (e in intArrayOf(c.e, c.proxy)) {
            xf[0] = rx * w; xf[1] = ry * w; xf[2] = rz * w; xf[3] = 0f
            xf[4] = ux * h; xf[5] = uy * h; xf[6] = uz * h; xf[7] = 0f
            xf[8] = -pose.groundX; xf[9] = 0f; xf[10] = -pose.groundZ; xf[11] = 0f
            xf[12] = px - rx * 16f * k; xf[13] = py - ry * 16f * k; xf[14] = pz - rz * 16f * k; xf[15] = 1f
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(e), xf)
        }
        c.mi.setParameter("albedo", tex, nearest)
        c.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
        c.mi.setParameter("uvRect", u0, v0, du, dv)
        c.mi.setParameter("fade", fd)
        c.pmi.setParameter("albedo", tex, nearest)
        c.pmi.setParameter("uvRect", u0, v0, du, dv)
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
        bike?.destroy(); bike = null
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
