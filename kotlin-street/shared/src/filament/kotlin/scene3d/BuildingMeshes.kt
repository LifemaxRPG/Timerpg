package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.DEPTH
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One building made into Filament geometry in its own frame (see [com.lifemax.rpg.feature.habits.domain.TownPlace]):
 * standing on the ground at local y 0, its front facing local -z, its picture running along local -x, the street's
 * building line at local z 0, its slot's middle at local x 0. The caller gives [entity] (and [proxy], its shadow twin) a
 * transform and adds them to its scene.
 */
internal class BuiltBuilding(
    val entity: Int,
    val proxy: Int,
    val mis: Array<MaterialInstance>,
    val proxyMis: Array<MaterialInstance>,
    val vb: VertexBuffer,
    val ib: IndexBuffer,
    /** Its windows' night light by the hour (rebuilds its mask only when the set of lit windows changes). */
    val night: ((Float) -> Unit)?,
    /** Its bounds in its own frame: min x, y, z, max x, y, z. */
    val bounds: FloatArray,
    /** How many draws it costs (its primitives), and its triangles. */
    val primitives: Int,
    val triangles: Int,
)

/**
 * The town's buildings in building-local space: the same three kinds the crossroads draws ([FilamentStreet]: the box of
 * a shop or a stack of flats with its sign, gables and rooftop billboard; the mobile home with its pitched roof; the
 * modelled villas, apartment blocks and landmarks of [FilamentModels]) built once at the origin, so the town places each
 * with one transform. [yarded]: the yard kit draws this lot's ground and fences, so the house's and the model's own front
 * lawn and fence are left off (TOWN.md: the models still draw their own across their width).
 */
internal class BuildingMeshes(
    private val engine: Engine,
    private val visibleMat: Material,
    private val proxyMat: Material?,
    private val textures: StreetTextures,
) {
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    /** The local frame's axes: the art's x runs along local -x, the front faces local -z. */
    private val ax = -1f
    private val az = 0f
    private val nx = 0f
    private val nz = -1f
    private val kk = B.W / SpriteArt.SRC_W

    /**
     * Builds [sb] (drawn as [sprite]) with its front wall [setback] street units behind the building line (its yard in
     * between); [seed] picks which windows are lit when.
     */
    fun build(sprite: String, sb: SpriteBuilding, setback: Float, yarded: Boolean, seed: Int): BuiltBuilding? {
        val ma = sb.model
        if (ma != null) return model(ma, setback, yarded, seed)
        val faces = ArrayList<Face>()
        val win = if (sb.house != null) house(sb, setback, yarded, seed, faces) else box(sb, setback, seed, faces)
        if (faces.isEmpty()) return null
        return upload(faces, win)
    }

    // ------------------------------------------------------------------ the three kinds

    private fun model(ma: ModelArt, setback: Float, yarded: Boolean, seed: Int): BuiltBuilding {
        val art = if (yarded) ModelArt(ma.shape, ma.atlas, ma.glow, null, null, null) else ma
        val sh = ma.shape
        // the art's left edge, from the slot's middle along the art's x
        val left = -B.W / 2f + sh.frontX * kk
        val place = ModelPlace(ax * left, setback, ax, az, nx, nz, kk)
        val m = FilamentModels.build(engine, visibleMat, proxyMat, textures, art, place, seed, linear = true)
        val rm = engine.renderableManager
        val prims = rm.getInstance(m.entity).let { if (it != 0) rm.getPrimitiveCount(it) else 1 }
        return BuiltBuilding(m.entity, m.proxy, m.mis, m.proxyMis, m.vb, m.ib, m.night, m.bounds, prims, m.ib.indexCount / 3)
    }

    /** A box (a shop or a stack of flats): its front, sign, sides, back, roof or pitched roof and gables, rooftop billboard. */
    private fun box(sb: SpriteBuilding, setback: Float, seed: Int, out: MutableList<Face>): Windows {
        val ox = 0f; val oz = setback
        val hf = sb.front.height * kk
        val hw = B.W / 2f
        val win = Windows(sb, seed)
        out += face(ox - ax * hw, hf, oz - az * hw, ax * B.W / sb.front.width, 0f, az * B.W / sb.front.width, 0f, -kk, 0f,
            sb.front.width.toFloat(), sb.front.height.toFloat(), win.albedo, win.mask, nx, 0f, nz)
        sb.sign?.let { sg ->
            val h = sg.height * kk
            out += face(ox - ax * hw, hf + h, oz - az * hw, ax * B.W / sg.width, 0f, az * B.W / sg.width, 0f, -kk, 0f,
                sg.width.toFloat(), sg.height.toFloat(), textures.of(sg), null, nx, 0f, nz)
        }
        val side = sb.side
        val sw = side.width.toFloat(); val sh = side.height.toFloat()
        out += face(ox + ax * hw - nx * DEPTH, hf, oz + az * hw - nz * DEPTH, -ax * B.W / sw, 0f, -az * B.W / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, -nx, 0f, -nz)
        out += face(ox - ax * hw, hf, oz - az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, -ax, 0f, -az)
        out += face(ox + ax * hw, hf, oz + az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, ax, 0f, az)
        val pitch = sb.pitch
        if (pitch != null) {
            val rows = pitch.height.toFloat(); val pw = pitch.width.toFloat()
            val rise = (rows * kk - B.DY / 2f).coerceAtLeast(2f)
            val rx = ox - ax * hw - nx * DEPTH / 2f; val rz = oz - az * hw - nz * DEPTH / 2f
            val slope = sqrt((DEPTH / 2f) * (DEPTH / 2f) + rise * rise)
            val cy = DEPTH / 2f / slope; val sy = rise / slope
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, nx * DEPTH / 2f / rows, -rise / rows, nz * DEPTH / 2f / rows, pw, rows, textures.of(pitch), null, nx * sy, cy, nz * sy)
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, -nx * DEPTH / 2f / rows, -rise / rows, -nz * DEPTH / 2f / rows, pw, rows, textures.of(pitch), null, -nx * sy, cy, -nz * sy)
            val gp = sb.gablePic
            if (gp != null) for (end in 0..1) {
                val e = if (end == 0) -hw else hw
                val bx0 = ox + ax * e; val bz0 = oz + az * e
                out += Face(
                    floatArrayOf(bx0, hf, bz0, bx0 - nx * DEPTH, hf, bz0 - nz * DEPTH, bx0 - nx * DEPTH / 2f, hf + rise, bz0 - nz * DEPTH / 2f),
                    floatArrayOf(0f, 1f, 1f, 1f, .5f, 0f), textures.of(gp), null,
                    if (end == 0) -ax else ax, 0f, if (end == 0) -az else az, -nx, 0f, -nz,
                )
            }
            val pl = sb.postL; val pr = sb.postR; val posts = (sb.pitchInfo?.getOrNull(2) ?: 0f) * kk
            if (pl != null && pr != null && posts > 0f) {
                val ph = pl.height * kk
                out += face(ox - ax * hw + nx * .3f, hf - 3f * kk + ph, oz - az * hw + nz * .3f, ax * posts / pl.width, 0f, az * posts / pl.width, 0f, -kk, 0f, pl.width.toFloat(), pl.height.toFloat(), textures.of(pl), null, nx, 0f, nz)
                out += face(ox + ax * (hw - posts) + nx * .3f, hf - 3f * kk + ph, oz + az * (hw - posts) + nz * .3f, ax * posts / pr.width, 0f, az * posts / pr.width, 0f, -kk, 0f, pr.width.toFloat(), pr.height.toFloat(), textures.of(pr), null, nx, 0f, nz)
            }
        } else sb.roof?.let { roof ->
            out += face(ox - ax * hw - nx * DEPTH, hf, oz - az * hw - nz * DEPTH, ax * B.W / roof.width, 0f, az * B.W / roof.width,
                nx * DEPTH / roof.height, 0f, nz * DEPTH / roof.height, roof.width.toFloat(), roof.height.toFloat(), textures.of(roof), null, 0f, 1f, 0f)
        }
        sb.prop?.let { bill ->
            if (sb.sign == null) {
                val bw = 160f * kk
                val bh = bill.height * kk
                val back = DEPTH / 2f
                val ridge = sb.pitch?.let { (it.height * kk - B.DY / 2f).coerceAtLeast(2f) } ?: 0f
                val (empty, backPic) = boardBack(bill)
                val foot = (empty + 1) * kk
                val bx0 = ox - ax * bw / 2f - nx * back; val bz0 = oz - az * bw / 2f - nz * back
                out += face(bx0, hf + ridge - foot + bh, bz0, ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), textures.of(bill), null, nx, 0f, nz)
                out += face(bx0 - nx * .4f, hf + ridge - foot + bh, bz0 - nz * .4f, ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), backPic, null, -nx, 0f, -nz)
            }
        }
        return win
    }

    /** A billboard picture's empty rows under its legs, and its back: the same outline in the frame's grey. */
    private fun boardBack(bill: ImageBitmap): Pair<Int, Texture> {
        val px = StreetTextures.argb(bill)
        val w = bill.width; val h = bill.height
        var empty = 0
        while (empty < h - 1 && (0 until w).none { (px[(h - 1 - empty) * w + it] ushr 24) > 0 }) empty++
        val grey = Color(0xFF5C6070).toArgb() and 0xFFFFFF
        val back = IntArray(px.size) { i -> if ((px[i] ushr 24) > 0) (px[i] and 0xFF000000.toInt()) or grey else 0 }
        return empty to textures.make(back, w, h, mips = true)
    }

    /** A mobile home: its walls, gabled ends, the roof's two slopes from the ridge; its lawn and fence unless yarded. */
    private fun house(sb: SpriteBuilding, setback: Float, yarded: Boolean, seed: Int, out: MutableList<Face>): Windows? {
        val house = sb.house ?: return null
        val sh = house.shape
        val ox = 0f; val oz = 0f
        val hw = B.W / 2f
        val fw = sb.front.width * kk
        val depth = sh.depth * kk
        val back = setback
        val left = -hw + sh.frontX * kk
        val fx = ox + ax * left - nx * back; val fz = oz + az * left - nz * back
        val h = sh.h * kk
        val win = Windows(sb, seed)
        out += face(fx, h, fz, ax * kk, 0f, az * kk, 0f, -kk, 0f, sb.front.width.toFloat(), sb.front.height.toFloat(), win.albedo, win.mask, nx, 0f, nz)
        val side = sb.side
        val sideH = side.height * kk
        for (end in 0..1) {
            val ex = fx + ax * (if (end == 0) 0f else fw); val ez = fz + az * (if (end == 0) 0f else fw)
            out += face(ex, sideH, ez, -nx * depth / side.width, 0f, -nz * depth / side.width, 0f, -kk, 0f, side.width.toFloat(), side.height.toFloat(), textures.of(side), null,
                if (end == 0) -ax else ax, 0f, if (end == 0) -az else az)
        }
        out += face(fx + ax * fw - nx * depth, h, fz + az * fw - nz * depth, -ax * kk, 0f, -az * kk, 0f, -kk, 0f, sb.front.width.toFloat(), sb.front.height.toFloat(), textures.of(sb.front), null, -nx, 0f, -nz)
        val slopeImg = house.slope
        val rows = slopeImg.height.toFloat(); val pw = slopeImg.width.toFloat()
        val over = sh.eaveOver * kk
        val run = depth / 2f + over
        val rise = sh.rise * kk
        val ridgeY = h + rise
        val rx = fx - nx * depth / 2f; val rz = fz - nz * depth / 2f
        val len = sqrt(run * run + rise * rise); val cyy = run / len; val syy = rise / len
        val sx = fw / pw
        out += face(rx, ridgeY, rz, ax * sx, 0f, az * sx, nx * run / rows, -rise / rows, nz * run / rows, pw, rows, textures.of(slopeImg), null, nx * syy, cyy, nz * syy)
        out += face(rx, ridgeY, rz, ax * sx, 0f, az * sx, -nx * run / rows, -rise / rows, -nz * run / rows, pw, rows, textures.of(slopeImg), null, -nx * syy, cyy, -nz * syy)
        if (!yarded) {
            house.lawn?.let { lawn ->
                out += face(ox - ax * hw, .15f, oz - az * hw, ax * B.W / lawn.width, 0f, az * B.W / lawn.width, -nx * back / lawn.height, 0f, -nz * back / lawn.height,
                    lawn.width.toFloat(), lawn.height.toFloat(), textures.of(lawn), null, 0f, 1f, 0f)
            }
            house.fence?.let { fence ->
                val d = sh.fenceD * kk
                val top = sh.fenceFoot * kk
                out += face(ox - ax * hw - nx * d, top, oz - az * hw - nz * d, ax * B.W / fence.width, 0f, az * B.W / fence.width, 0f, -kk, 0f,
                    fence.width.toFloat(), fence.height.toFloat(), textures.of(fence), null, nx, 0f, nz)
            }
        }
        return win
    }

    // ------------------------------------------------------------------ faces and their upload

    private class Face(
        val p: FloatArray, val uv: FloatArray, val tex: Texture, val mask: Texture?,
        val nx: Float, val ny: Float, val nz: Float, val tx: Float, val ty: Float, val tz: Float,
    )

    /** The plane through ([ox], [oy], [oz]) whose picture runs [tw] px along u and [th] px along v. */
    private fun face(
        ox: Float, oy: Float, oz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        tw: Float, th: Float, tex: Texture, mask: Texture?, nx: Float, ny: Float, nz: Float,
    ): Face {
        val p = FloatArray(12)
        for (c in 0..3) {
            val a = if (c == 1 || c == 2) tw else 0f
            val b = if (c >= 2) th else 0f
            p[c * 3] = ox + ux * a + vx * b; p[c * 3 + 1] = oy + uy * a + vy * b; p[c * 3 + 2] = oz + uz * a + vz * b
        }
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        return Face(p, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), tex, mask, nx, ny, nz, ux / ul, uy / ul, uz / ul)
    }

    private fun upload(faces: List<Face>, windows: Windows?): BuiltBuilding {
        val nv = faces.fold(0) { a, f -> a + f.p.size / 3 }
        val vb = ByteBuffer.allocateDirect(nv * STRIDE).order(ByteOrder.nativeOrder())
        val ni = faces.fold(0) { a, f -> a + if (f.p.size == 9) 3 else 6 }
        val ib = ByteBuffer.allocateDirect(ni * 2).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        val bmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val bmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var base = 0
        val ranges = ArrayList<IntArray>()
        var io = 0
        for (f in faces) {
            val nx = f.nx; val ny = f.ny; val nz = f.nz
            var tx = f.tx; var ty = f.ty; var tz = f.tz
            val dot = tx * nx + ty * ny + tz * nz
            tx -= dot * nx; ty -= dot * ny; tz -= dot * nz
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= tl; ty /= tl; tz /= tl
            val bxv = ny * tz - nz * ty; val byv = nz * tx - nx * tz; val bzv = nx * ty - ny * tx
            MathUtils.packTangentFrame(tx, ty, tz, bxv, byv, bzv, nx, ny, nz, q)
            val n = f.p.size / 3
            for (v in 0 until n) {
                val x = f.p[v * 3]; val y = f.p[v * 3 + 1]; val z = f.p[v * 3 + 2]
                vb.putFloat(x).putFloat(y).putFloat(z).putFloat(f.uv[v * 2]).putFloat(f.uv[v * 2 + 1])
                vb.putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
                bmin[0] = min(bmin[0], x); bmin[1] = min(bmin[1], y); bmin[2] = min(bmin[2], z)
                bmax[0] = max(bmax[0], x); bmax[1] = max(bmax[1], y); bmax[2] = max(bmax[2], z)
            }
            val idx = if (n == 3) shortArrayOf(0, 1, 2) else shortArrayOf(0, 1, 2, 0, 2, 3)
            for (s in idx) ib.putShort((base + s).toShort())
            ranges += intArrayOf(io, idx.size)
            io += idx.size
            base += n
        }
        vb.flip(); ib.flip()
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(nv)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .build(engine)
        vertices.setBufferAt(engine, 0, vb)
        val indices = IndexBuffer.Builder().indexCount(ni).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indices.setBuffer(engine, ib)
        val box = Box((bmin[0] + bmax[0]) / 2f, (bmin[1] + bmax[1]) / 2f, (bmin[2] + bmax[2]) / 2f,
            (bmax[0] - bmin[0]) / 2f + .1f, (bmax[1] - bmin[1]) / 2f + .1f, (bmax[2] - bmin[2]) / 2f + .1f)
        val mis = Array(faces.size) { j ->
            val f = faces[j]
            visibleMat.createInstance().also { mi ->
                mi.setParameter("albedo", f.tex, crisp)
                mi.setParameter("mask", f.mask ?: textures.black, nearest)
                mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
                mi.setParameter("tint", 1f, 1f, 1f, 1f)
                mi.setParameter("glow", 0f, 0f, 0f, 0f)
                mi.setParameter("glass", 1f, 1f, 1f, 1f)
                mi.setParameter("fade", 1f)
                mi.setParameter("cell", 2f)
            }
        }
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(faces.size).boundingBox(box).culling(false).castShadows(false).receiveShadows(true).priority(4)
        for (j in faces.indices) rb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, mis[j])
        rb.build(engine, e)
        var proxy = 0
        var pmis: Array<MaterialInstance> = emptyArray()
        if (proxyMat != null) {
            pmis = Array(faces.size) { j -> proxyMat.createInstance().also { mi -> mi.setParameter("albedo", faces[j].tex, crisp); mi.setParameter("uvRect", 0f, 0f, 1f, 1f) } }
            proxy = EntityManager.get().create()
            val pb = RenderableManager.Builder(faces.size).boundingBox(box).castShadows(true).receiveShadows(false)
            for (j in faces.indices) pb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, pmis[j])
            pb.build(engine, proxy)
        }
        return BuiltBuilding(e, proxy, mis, pmis, vertices, indices, windows?.let { w -> val f: (Float) -> Unit = { h -> w.update(h) }; f },
            floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]), faces.size, ni / 3)
    }

    // ------------------------------------------------------------------ the windows' glass and night light

    /**
     * A front's pictures for Filament, as [FilamentStreet]'s: its art with the windows' cut-out glass filled (the sky
     * above, the far city's blocks below), and a mask (red: glows at night; green: glass; blue: a lit display).
     */
    private inner class Windows(val sb: SpriteBuilding, val seed: Int) {
        private val w = sb.front.width
        private val h = sb.front.height
        private val art = StreetTextures.argb(sb.front)
        private val emis = runCatching { StreetTextures.argb(sb.emis) }.getOrNull()?.takeIf { sb.emis.width == w && sb.emis.height == h }
        private val glass = sb.glass?.let { g -> runCatching { StreetTextures.argb(g) }.getOrNull()?.takeIf { g.width == w && g.height == h } }
        private val panes = sb.house?.shape?.panes ?: emptyList()
        private val rects = sb.windows + panes
        private val rooms: Set<Int> = run {
            val n = sb.windows.size
            val on = if (n == 0) 0 else maxOf(1, kotlin.math.ceil(n * .7f).toInt())
            (0 until n).sortedBy { Ambient.hash(seed, it, 301) }.take(on).toSet()
        }
        private val cityLights = IntArray(w * h)
        private var lit = -1L
        private val maskPx = IntArray(w * h)
        val albedo: Texture
        val mask: Texture

        init {
            val px = art.copyOf()
            val skyHi = Color(0xFF8FA9C8); val skyLo = Color(0xFF34435C)
            val city = Color(0xFF2E3B52)
            for ((j, r) in rects.withIndex()) {
                val x0 = r[0].toInt(); val y0 = r[1].toInt(); val x1 = (r[0] + r[2]).toInt().coerceAtMost(w); val y1 = (r[1] + r[3]).toInt().coerceAtMost(h)
                val rh = (y1 - y0).coerceAtLeast(1)
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    if (px[i] ushr 24 >= 250) continue
                    val t = (y - y0).toFloat() / rh
                    var g = lerp(skyHi, skyLo, (t * .8f + .2f).coerceIn(0f, 1f))
                    val col = (x - x0) / 2
                    val top = .45f + .3f * Ambient.hash(seed + j, col, 77)
                    if (t > top) {
                        g = lerp(g, city, .65f)
                        if ((x - x0) % 2 == 0 && (y - y0) % 2 == 0 && Ambient.hash(seed + j, x * 31 + y, 78) > .86f) cityLights[i] = 1
                    }
                    px[i] = blend(g.toArgb(), px[i])
                }
            }
            albedo = textures.make(px, w, h, mips = true)
            mask = textures.make(IntArray(w * h), w, h, linear = true)
        }

        fun update(hour: Float) {
            var bits = 0L
            val nw = sb.windows.size
            for (j in rects.indices) {
                if (j >= 63) break
                val room = if (j < nw) StageTuning.interiors && j in rooms else true
                if (room && Dusk.lightsOn(seed * 31 + j, hour) > .5f) bits = bits or (1L shl j)
            }
            if (bits == lit) return
            lit = bits
            for (i in maskPx.indices) {
                val e = emis?.let { (it[i] ushr 24) * 3 / 5 } ?: 0
                val g = glass?.let { (it[i] ushr 24) * 2 / 5 } ?: 0
                maskPx[i] = (255 shl 24) or (e shl 16) or (g shl 8) or (if (e > 0) 255 else 0)
            }
            for ((j, r) in rects.withIndex()) {
                val on = j < 63 && (bits shr j) and 1L == 1L
                for (y in r[1].toInt() until (r[1] + r[3]).toInt().coerceAtMost(h)) for (x in r[0].toInt() until (r[0] + r[2]).toInt().coerceAtMost(w)) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    if ((art[i] ushr 24) >= 250) continue
                    val red = if (on) 255 else if (cityLights[i] != 0) 150 else 0
                    maskPx[i] = (255 shl 24) or (red shl 16) or (255 shl 8)
                }
            }
            textures.update(mask, maskPx)
        }

        private fun blend(under: Int, over: Int): Int {
            val a = (over ushr 24) / 255f
            fun ch(s: Int) = (((under shr s) and 255) * (1f - a) + ((over shr s) and 255) * a).toInt().coerceIn(0, 255)
            return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    companion object {
        private const val STRIDE = 36
    }
}
