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
import com.lifemax.rpg.feature.habits.domain.BoxPart
import com.lifemax.rpg.feature.habits.domain.BoxPoly
import com.lifemax.rpg.feature.habits.domain.BoxShell
import com.lifemax.rpg.feature.habits.domain.HouseShell
import com.lifemax.rpg.feature.habits.domain.ShellPart
import com.lifemax.rpg.feature.habits.domain.ShellQuad
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
    /** Its vertices and indices ([BufferArena]): free them with [GeomSlice.free]. */
    val geo: GeomSlice,
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
    // (anisotropic: the side walls are seen at grazing angles from the street and would smear into streaks)
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
        .also { it.anisotropy = 16f }
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    /** A sign's thickness behind its face: how many layers (the last its back), and how far apart (sprite pixels). */
    private val SIGN_LAYERS = 4
    private val SIGN_STEP = .75f

    /** The local frame's axes: the art's x runs along local -x, the front faces local -z. */
    private val ax = -1f
    private val az = 0f
    private val nx = 0f
    private val nz = -1f
    private val kk = B.W / SpriteArt.SRC_W

    /**
     * Builds [sb] (drawn as [sprite]) with its front wall [setback] street units behind the building line (its yard in
     * between); [seed] picks which windows are lit when. ([prepare] then [finish], at once.)
     */
    fun build(sprite: String, sb: SpriteBuilding, setback: Float, yarded: Boolean, seed: Int): BuiltBuilding? =
        prepare(sb, setback, yarded, seed)?.let { finish(it) }

    /**
     * A building made ready off the engine's thread ([prepare]): a model's geometry packed, or a box's or a mobile
     * home's faces packed, their pictures read ahead. Null from [prepare]: nothing to draw.
     */
    class Ready internal constructor(internal val model: FilamentModels.Ready?, internal val faces: Packed?) {
        /** The pictures read ahead for it: let go once it is finished ([StreetTextures.drop]). */
        internal var warmed: List<ImageBitmap> = emptyList()
    }

    /** The pictures [pic] reads ahead on this thread, for the [Ready] being made. */
    private val warming = ThreadLocal<MutableList<ImageBitmap>?>()

    /**
     * The part of [build] with no engine call (any thread: the town's workers): the geometry made and its vertices packed,
     * the pictures read and packed ([StreetTextures.warm], [StreetTextures.prepare]), the windows' glass filled. Only
     * buffers, textures, materials and renderables are left for [finish] on the engine's thread.
     */
    fun prepare(sb: SpriteBuilding, setback: Float, yarded: Boolean, seed: Int): Ready? {
        val ma = sb.model
        if (ma != null) return Ready(prepareModel(ma, setback, yarded, seed), null)
        val faces = ArrayList<Face>()
        val got = ArrayList<ImageBitmap>()
        warming.set(got)
        val win = try { if (sb.house != null) house(sb, setback, yarded, seed, faces) else box(sb, setback, seed, faces) } finally { warming.set(null) }
        if (faces.isEmpty()) { textures.drop(got); return null }
        return Ready(null, pack(faces, win)).also { it.warmed = got }
    }

    /** Makes a [prepare]d building Filament's (the engine's thread). */
    fun finish(r: Ready): BuiltBuilding? {
        try {
            r.model?.let { return finishModel(it) }
            return r.faces?.let { upload(it) }
        } finally { textures.drop(r.warmed) }
    }

    /** A picture for a face: read ahead now ([StreetTextures.warm]), made a texture when the building is finished. */
    private fun pic(img: ImageBitmap): () -> Texture { textures.warm(img); warming.get()?.add(img); return { textures.of(img) } }

    /** A picture packed now, made a texture (or shared by its content) when the building is finished, once. */
    private fun pic(p: StreetTextures.Prepared): () -> Texture { val t = lazy(LazyThreadSafetyMode.NONE) { textures.make(p) }; return { t.value } }

    // ------------------------------------------------------------------ the three kinds

    private fun prepareModel(ma: ModelArt, setback: Float, yarded: Boolean, seed: Int): FilamentModels.Ready {
        val art = if (yarded) ModelArt(ma.shape, ma.atlas, ma.glow, null, null, null) else ma
        val sh = ma.shape
        // the art's left edge, from the slot's middle along the art's x
        val left = -B.W / 2f + sh.frontX * kk
        val place = ModelPlace(ax * left, setback, ax, az, nx, nz, kk)
        return FilamentModels.prepare(textures, art, place, seed, linear = true)
    }

    private fun finishModel(r: FilamentModels.Ready): BuiltBuilding {
        val m = FilamentModels.finish(engine, visibleMat, proxyMat, textures, r)
        val rm = engine.renderableManager
        val prims = rm.getInstance(m.entity).let { if (it != 0) rm.getPrimitiveCount(it) else 1 }
        // (the town draws through Filament's own camera: it may cull the model by its box)
        rm.getInstance(m.entity).takeIf { it != 0 }?.let { rm.setCulling(it, true) }
        return BuiltBuilding(m.entity, m.proxy, m.mis, m.proxyMis, m.geo, m.night, m.bounds, prims, m.geo.triangles)
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
                sg.width.toFloat(), sg.height.toFloat(), pic(sg), null, nx, 0f, nz)
            // the board has a thickness: its edge (the art's outline colour) a few layers deep behind it, its back the
            // frame's grey (from behind, not the art mirrored)
            val (edge, back) = signBody(sg)
            for (k in 1..SIGN_LAYERS) {
                val d = k * SIGN_STEP * kk
                out += face(ox - ax * hw - nx * d, hf + h, oz - az * hw - nz * d, ax * B.W / sg.width, 0f, az * B.W / sg.width, 0f, -kk, 0f,
                    sg.width.toFloat(), sg.height.toFloat(), if (k == SIGN_LAYERS) back else edge, null, if (k == SIGN_LAYERS) -nx else nx, 0f, if (k == SIGN_LAYERS) -nz else nz)
            }
        }
        // the stone ledges across the front, standing out of the wall (BoxShell)
        if (sb.ledges.isNotEmpty()) for (q in BoxShell.ledges(sb.ledges, sb.front.width.toFloat(), sb.front.height.toFloat()))
            out += polyFace(q, ox - ax * hw, oz - az * hw, win.albedo)
        val side = sb.side
        val sw = side.width.toFloat(); val sh = side.height.toFloat()
        // the back wall: the side's picture carried on at its own scale across the whole width ([backOf])
        val bp = backOf(side)
        out += face(ox + ax * hw - nx * DEPTH, hf, oz + az * hw - nz * DEPTH, -ax * B.W / bp.second, 0f, -az * B.W / bp.second, 0f, -hf / sh, 0f, bp.second.toFloat(), sh, bp.first, null, -nx, 0f, -nz)
        out += face(ox - ax * hw, hf, oz - az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, pic(side), null, -ax, 0f, -az)
        out += face(ox + ax * hw, hf, oz + az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, pic(side), null, ax, 0f, az)
        val pitch = sb.pitch
        if (pitch != null) {
            val rows = pitch.height.toFloat(); val pw = pitch.width.toFloat()
            val rise = (rows * kk - B.DY / 2f).coerceAtLeast(2f)
            val rx = ox - ax * hw - nx * DEPTH / 2f; val rz = oz - az * hw - nz * DEPTH / 2f
            val slope = sqrt((DEPTH / 2f) * (DEPTH / 2f) + rise * rise)
            val cy = DEPTH / 2f / slope; val sy = rise / slope
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, nx * DEPTH / 2f / rows, -rise / rows, nz * DEPTH / 2f / rows, pw, rows, pic(pitch), null, nx * sy, cy, nz * sy)
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, -nx * DEPTH / 2f / rows, -rise / rows, -nz * DEPTH / 2f / rows, pw, rows, pic(pitch), null, -nx * sy, cy, -nz * sy)
            val gp = sb.gablePic
            val posts = sb.pitchInfo?.getOrNull(2) ?: 0f
            val walls = posts > 0f && sb.parts["wcap"] != null && sb.parts["pier"] != null
            val fx = ox - ax * hw; val fz = oz - az * hw
            if (walls) {
                // the stone end walls as real walls following the slope, capped, with a pier at each end (BoxShell)
                val cap = pic(sb.parts.getValue("wcap")); val pier = pic(sb.parts.getValue("pier"))
                val gable = gp?.let { pic(it) } ?: pic(sb.side)
                for (q in BoxShell.endWalls(sb.front.width.toFloat(), sb.front.height.toFloat(), DEPTH / kk, rise / kk, posts)) {
                    val tex = when (q.part) {
                        BoxPart.GABLE -> gable
                        BoxPart.CAP_TOP, BoxPart.CAP_EDGE, BoxPart.PIER_TOP -> cap
                        else -> pier
                    }
                    out += polyFace(q, fx, fz, tex)
                }
            } else {
                // (old art with posts but no end walls' pictures: the posts as cards on the front, the gables inside them)
                val inset = if (sb.postL != null && sb.postR != null) posts * kk else 0f
                if (gp != null) for (end in 0..1) {
                    val e = if (end == 0) -(hw - inset) else hw - inset
                    val bx0 = ox + ax * e; val bz0 = oz + az * e
                    out += Face(
                        floatArrayOf(bx0, hf, bz0, bx0 - nx * DEPTH, hf, bz0 - nz * DEPTH, bx0 - nx * DEPTH / 2f, hf + rise, bz0 - nz * DEPTH / 2f),
                        floatArrayOf(0f, 1f, 1f, 1f, .5f, 0f), pic(gp), null,
                        if (end == 0) -ax else ax, 0f, if (end == 0) -az else az, -nx, 0f, -nz,
                    )
                }
                val pl = sb.postL; val pr = sb.postR; val pk = posts * kk
                if (pl != null && pr != null && pk > 0f) {
                    val ph = pl.height * kk
                    out += face(ox - ax * hw + nx * .3f, hf - 3f * kk + ph, oz - az * hw + nz * .3f, ax * pk / pl.width, 0f, az * pk / pl.width, 0f, -kk, 0f, pl.width.toFloat(), pl.height.toFloat(), pic(pl), null, nx, 0f, nz)
                    out += face(ox + ax * (hw - pk) + nx * .3f, hf - 3f * kk + ph, oz + az * (hw - pk) + nz * .3f, ax * pk / pr.width, 0f, az * pk / pr.width, 0f, -kk, 0f, pr.width.toFloat(), pr.height.toFloat(), pic(pr), null, nx, 0f, nz)
                }
                if (pk <= 0f) {
                    // the rake boards along both gables: the roof's edge standing out past them (BoxShell)
                    val rc = sb.pitchInfo?.getOrNull(0)?.toInt() ?: 0x6A4A40
                    val top = colour(rc); val edge = colour(BoxShell.shade(rc, .8f)); val under = colour(BoxShell.shade(rc, .55f))
                    for (q in BoxShell.verges(sb.front.width.toFloat(), sb.front.height.toFloat(), DEPTH / kk, rise / kk))
                        out += polyFace(q, fx, fz, when (q.part) { BoxPart.RAKE_TOP -> top; BoxPart.RAKE_UNDER -> under; else -> edge })
                }
            }
        } else sb.roof?.let { roof ->
            out += face(ox - ax * hw - nx * DEPTH, hf, oz - az * hw - nz * DEPTH, ax * B.W / roof.width, 0f, az * B.W / roof.width,
                nx * DEPTH / roof.height, 0f, nz * DEPTH / roof.height, roof.width.toFloat(), roof.height.toFloat(), pic(roof), null, 0f, 1f, 0f)
        }
        // the balconies the art paints flat on a middle floor, built: slab, floor, parapet, brackets (BoxShell)
        if (sb.balconies.isNotEmpty()) {
            val par = sb.parts["balp"]; val slab = sb.parts["bals"]; val floor = sb.parts["balf"]
            if (par != null && slab != null && floor != null) {
                val pPar = pic(par); val pSlab = pic(slab); val pFloor = pic(floor)
                for (b in sb.balconies) {
                    val cap = colour(b[7].toInt()); val under = colour(b[8].toInt()); val bracket = colour(b[9].toInt())
                    for (q in BoxShell.balcony(b, sb.front.height.toFloat())) out += polyFace(q, ox - ax * hw, oz - az * hw, when (q.part) {
                        BoxPart.BAL_PARAPET -> pPar
                        BoxPart.BAL_SLAB -> pSlab
                        BoxPart.BAL_FLOOR -> pFloor
                        BoxPart.BAL_CAP -> cap
                        BoxPart.BAL_UNDER -> under
                        else -> bracket
                    })
                }
            }
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
                out += face(bx0, hf + ridge - foot + bh, bz0, ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), pic(bill), null, nx, 0f, nz)
                out += face(bx0 - nx * .4f, hf + ridge - foot + bh, bz0 - nz * .4f, ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), backPic, null, -nx, 0f, -nz)
            }
        }
        return win
    }

    /** A sign's edge layers (its outline in the art's dark outline colour) and its back (in the frame's grey). */
    private fun signBody(sign: ImageBitmap): Pair<() -> Texture, () -> Texture> {
        val px = StreetTextures.argb(sign)
        fun fill(rgb: Int) = IntArray(px.size) { i -> if ((px[i] ushr 24) > 0) (px[i] and 0xFF000000.toInt()) or rgb else 0 }
        return pic(StreetTextures.prepare(fill(0x3A3A50), sign.width, sign.height, mips = true)) to
            pic(StreetTextures.prepare(fill(0x5C6070), sign.width, sign.height, mips = true))
    }

    /** A billboard picture's empty rows under its legs, and its back: the same outline in the frame's grey. */
    private fun boardBack(bill: ImageBitmap): Pair<Int, () -> Texture> {
        val px = StreetTextures.argb(bill)
        val w = bill.width; val h = bill.height
        var empty = 0
        while (empty < h - 1 && (0 until w).none { (px[(h - 1 - empty) * w + it] ushr 24) > 0 }) empty++
        val grey = Color(0xFF5C6070).toArgb() and 0xFFFFFF
        val back = IntArray(px.size) { i -> if ((px[i] ushr 24) > 0) (px[i] and 0xFF000000.toInt()) or grey else 0 }
        return empty to pic(StreetTextures.prepare(back, w, h, mips = true))
    }

    /** A mobile home: its walls, gabled ends, the roof as a slab over them ([HouseShell]); its lawn and fence unless yarded. */
    private fun house(sb: SpriteBuilding, setback: Float, yarded: Boolean, seed: Int, out: MutableList<Face>): Windows? {
        val house = sb.house ?: return null
        val sh = house.shape
        val ox = 0f; val oz = 0f
        val hw = B.W / 2f
        val back = setback
        val left = -hw + sh.frontX * kk
        val fx = ox + ax * left - nx * back; val fz = oz + az * left - nz * back
        val win = Windows(sb, seed)
        // the walls, the gable ends and the roof as a slab (HouseShell): the roof meets the walls from every side
        val rake = pic(StreetTextures.prepare(intArrayOf(0xFF000000.toInt() or sh.rake), 1, 1, mips = true))
        val soffit = pic(StreetTextures.prepare(intArrayOf(0xFF000000.toInt() or sh.rakeEdge), 1, 1, mips = true))
        val sidePic = pic(sb.side); val slopePic = pic(house.slope)
        for (q in HouseShell.quads(sh, sb.front.width.toFloat(), sb.side.height.toFloat())) {
            val (tex, mask) = when (q.part) {
                ShellPart.FRONT -> win.albedo to win.mask
                ShellPart.BACK, ShellPart.EAVE_BACK -> win.back to null
                ShellPart.EAVE_FRONT -> win.albedo to null
                ShellPart.GABLE -> sidePic to null
                ShellPart.SLOPE -> slopePic to null
                ShellPart.RAKE -> rake to null
                ShellPart.SOFFIT -> soffit to null
            }
            out += shellFace(q, fx, fz, tex, mask)
        }
        if (!yarded) {
            house.lawn?.let { lawn ->
                out += face(ox - ax * hw, .15f, oz - az * hw, ax * B.W / lawn.width, 0f, az * B.W / lawn.width, -nx * back / lawn.height, 0f, -nz * back / lawn.height,
                    lawn.width.toFloat(), lawn.height.toFloat(), pic(lawn), null, 0f, 1f, 0f)
            }
            house.fence?.let { fence ->
                val d = sh.fenceD * kk
                val top = sh.fenceFoot * kk
                out += face(ox - ax * hw - nx * d, top, oz - az * hw - nz * d, ax * B.W / fence.width, 0f, az * B.W / fence.width, 0f, -kk, 0f,
                    fence.width.toFloat(), fence.height.toFloat(), pic(fence), null, nx, 0f, nz)
            }
        }
        return win
    }

    /**
     * A box's back wall picture: [side] (120 px for the box's depth) carried on across the front's 224 px at the same
     * texel size, as two copies of it joined (overlapping) at the column where they match best, so its windows and brick
     * keep their scale and no window is cut through. Returns the texture and its width in pixels. (Any thread: the
     * picture is shared by its content when made, so the same side makes one back however many buildings wear it.)
     */
    private fun backOf(side: ImageBitmap): Pair<() -> Texture, Int> {
        val sw = side.width; val sh = side.height
        val w = SpriteArt.SRC_W.toInt()
        val px = StreetTextures.argb(side)
        if (sw >= w || sw * 2 < w) return pic(side) to sw
        val overlap = 2 * sw - w
        // the right copy starts at w - sw; in the overlap the left copy's column c and the right copy's column c - (w - sw)
        val shift = w - sw
        fun diff(c: Int): Long {
            var d = 0L
            for (y in 0 until sh) {
                val a = px[y * sw + c]; val b = px[y * sw + c - shift]
                for (k in 0..2) d += kotlin.math.abs(((a shr (k * 8)) and 255) - ((b shr (k * 8)) and 255))
            }
            return d
        }
        // keep a few columns clear of either copy's own edge (its corner trim)
        val margin = min(4, overlap / 4)
        val seam = (shift + margin until sw - margin).minByOrNull { diff(it) } ?: (shift + overlap / 2)
        val out = IntArray(w * sh)
        for (y in 0 until sh) for (x in 0 until w) out[y * w + x] = if (x < seam) px[y * sw + x] else px[y * sw + x - shift]
        return pic(StreetTextures.prepare(out, w, sh, mips = true)) to w
    }

    // ------------------------------------------------------------------ faces and their upload

    internal class Face(
        val p: FloatArray, val uv: FloatArray, val tex: () -> Texture, val mask: (() -> Texture)?,
        val nx: Float, val ny: Float, val nz: Float, val tx: Float, val ty: Float, val tz: Float,
    )

    /** The plane through ([ox], [oy], [oz]) whose picture runs [tw] px along u and [th] px along v. */
    private fun face(
        ox: Float, oy: Float, oz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        tw: Float, th: Float, tex: () -> Texture, mask: (() -> Texture)?, nx: Float, ny: Float, nz: Float,
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

    /** A house's [ShellQuad] (sprite pixels in the house's frame) as a face, its front wall's left end at ([fx], [fz]). */
    private fun shellFace(q: ShellQuad, fx: Float, fz: Float, tex: () -> Texture, mask: (() -> Texture)?): Face {
        fun wx(x: Float, d: Float) = (ax * x - nx * d) * kk
        fun wz(x: Float, d: Float) = (az * x - nz * d) * kk
        val p = FloatArray(12)
        for (c in 0..3) {
            val a = if (c == 1 || c == 2) 1f else 0f
            val b = if (c >= 2) 1f else 0f
            val x = q.o[0] + q.u[0] * a + q.v[0] * b; val y = q.o[1] + q.u[1] * a + q.v[1] * b; val d = q.o[2] + q.u[2] * a + q.v[2] * b
            p[c * 3] = fx + wx(x, d); p[c * 3 + 1] = y * kk; p[c * 3 + 2] = fz + wz(x, d)
        }
        val (u0, v0, u1, v1) = q.uv
        val tx = wx(q.u[0], q.u[2]); val ty = q.u[1] * kk; val tz = wz(q.u[0], q.u[2])
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        val nxw = ax * q.n[0] - nx * q.n[2]; val nzw = az * q.n[0] - nz * q.n[2]
        return Face(p, floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), tex, mask, nxw, q.n[1], nzw, tx / tl, ty / tl, tz / tl)
    }

    /** A [BoxPoly] (sprite pixels in the box's frame) as a face, the front wall's left end at ([fx], [fz]). */
    private fun polyFace(q: BoxPoly, fx: Float, fz: Float, tex: () -> Texture): Face {
        fun wx(x: Float, d: Float) = (ax * x - nx * d) * kk
        fun wz(x: Float, d: Float) = (az * x - nz * d) * kk
        val n = q.p.size / 3
        val p = FloatArray(n * 3)
        for (c in 0 until n) {
            val x = q.p[c * 3]; val y = q.p[c * 3 + 1]; val d = q.p[c * 3 + 2]
            p[c * 3] = fx + wx(x, d); p[c * 3 + 1] = y * kk; p[c * 3 + 2] = fz + wz(x, d)
        }
        val tx = p[3] - p[0]; val ty = p[4] - p[1]; val tz = p[5] - p[2]
        val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
        val nxw = ax * q.n[0] - nx * q.n[2]; val nzw = az * q.n[0] - nz * q.n[2]
        return Face(p, q.uv.copyOf(n * 2), tex, null, nxw, q.n[1], nzw, tx / tl, ty / tl, tz / tl)
    }

    /** A one-pixel picture of [rgb] (0xRRGGBB). */
    private fun colour(rgb: Int): () -> Texture = pic(StreetTextures.prepare(intArrayOf(0xFF000000.toInt() or (rgb and 0xFFFFFF)), 1, 1, mips = true))

    /** A box's or a mobile home's faces packed off the engine's thread ([pack]), waiting for [upload]. */
    class Packed internal constructor(
        internal val faces: List<Face>, internal val windows: Windows?,
        internal val vb: ByteBuffer, internal val ib: ByteBuffer, internal val nv: Int, internal val ni: Int,
        internal val ranges: List<IntArray>, internal val bmin: FloatArray, internal val bmax: FloatArray,
    )

    private fun pack(faces: List<Face>, windows: Windows?): Packed {
        val nv = faces.fold(0) { a, f -> a + f.p.size / 3 }
        val vb = TownMemory.direct(nv * TownVertex.STRIDE).order(ByteOrder.nativeOrder())
        val ni = faces.fold(0) { a, f -> a + if (f.p.size == 9) 3 else 6 }
        val ib = TownMemory.direct(ni * 2).order(ByteOrder.nativeOrder())
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
                TownVertex.put(vb, x, y, z, f.uv[v * 2], f.uv[v * 2 + 1], q)
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
        return Packed(faces, windows, vb, ib, nv, ni, ranges, bmin, bmax)
    }

    private fun upload(pk: Packed): BuiltBuilding {
        val faces = pk.faces; val windows = pk.windows; val nv = pk.nv; val ni = pk.ni; val vb = pk.vb; val ib = pk.ib
        val ranges = pk.ranges; val bmin = pk.bmin; val bmax = pk.bmax
        val tex = Array(faces.size) { faces[it].tex() }
        val masks = Array(faces.size) { faces[it].mask?.invoke() ?: textures.black }
        val geo = BufferArena.upload(engine, vb, nv, ib, ni, true)
        val vertices = geo.vb; val indices = geo.ib; val first = geo.first
        textures.gpu.buffers(vb.limit(), ib.limit())
        val box = Box((bmin[0] + bmax[0]) / 2f, (bmin[1] + bmax[1]) / 2f, (bmin[2] + bmax[2]) / 2f,
            (bmax[0] - bmin[0]) / 2f + .1f, (bmax[1] - bmin[1]) / 2f + .1f, (bmax[2] - bmin[2]) / 2f + .1f)
        val mis = Array(faces.size) { j ->
            visibleMat.createInstance().also { mi ->
                mi.setParameter("albedo", tex[j], crisp)
                mi.setParameter("mask", masks[j], nearest)
                mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
                mi.setParameter("tint", 1f, 1f, 1f, 1f)
                mi.setParameter("glow", 0f, 0f, 0f, 0f)
                mi.setParameter("glass", 1f, 1f, 1f, 1f)
                mi.setParameter("fade", 1f)
                mi.setParameter("cell", 2f)
            }
        }
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(faces.size).boundingBox(box).culling(true).castShadows(false).receiveShadows(true).priority(4)
        for (j in faces.indices) rb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, first + ranges[j][0], ranges[j][1]).material(j, mis[j])
        rb.build(engine, e)
        var proxy = 0
        var pmis: Array<MaterialInstance> = emptyArray()
        if (proxyMat != null) {
            pmis = Array(faces.size) { j -> proxyMat.createInstance().also { mi -> mi.setParameter("albedo", tex[j], crisp); mi.setParameter("uvRect", 0f, 0f, 1f, 1f) } }
            proxy = EntityManager.get().create()
            val pb = RenderableManager.Builder(faces.size).boundingBox(box).castShadows(true).receiveShadows(false)
            for (j in faces.indices) pb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, first + ranges[j][0], ranges[j][1]).material(j, pmis[j])
            pb.build(engine, proxy)
        }
        return BuiltBuilding(e, proxy, mis, pmis, geo, windows?.let { w -> val f: (Float) -> Unit = { h -> w.update(h) }; f },
            floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]), faces.size, ni / 3)
    }

    // ------------------------------------------------------------------ the windows' glass and night light

    /**
     * A front's pictures for Filament, as [FilamentStreet]'s: its art with the windows' cut-out glass filled (the sky
     * above, the far city's blocks below), and a mask (red: glows at night; green: glass; blue: a lit display).
     */
    internal inner class Windows(sb: SpriteBuilding, val seed: Int) {
        // (made on a worker, and kept for the hourly light: so it keeps what it needs of [sb], not its pictures)
        private val w = sb.front.width
        private val h = sb.front.height
        // Kept for the building's whole life, so kept small: the first version kept five front-sized int arrays (the art,
        // the emissive and glass pictures, the far city's lights and the mask: 20 bytes a texel, some 70 MB over the
        // town's fronts, on the phone's Java heap). Now one byte a texel says which pixels are a window's see-through
        // glass (and which of them show a far city light), the emissive and glass pictures' pixels are kept only where
        // they are not clear, and each hour's mask is written straight into the buffer Filament takes.
        /** 0: not a window's see-through pixel; [SEE]: one (the art's alpha under 250 inside a window); [CITY]: one showing a far light. */
        private val win = ByteArray(w * h)
        /** The pixels where the emissive or the glass picture shows (sorted), and their mask's red (emissive) and green (glass). */
        private val egAt: IntArray
        private val egE: ByteArray
        private val egG: ByteArray
        private val panes = sb.house?.shape?.panes ?: emptyList()
        private val nw = sb.windows.size
        private val rects = sb.windows + panes
        private val rooms: Set<Int> = run {
            val n = sb.windows.size
            val on = if (n == 0) 0 else maxOf(1, kotlin.math.ceil(n * .7f).toInt())
            (0 until n).sortedBy { Ambient.hash(seed, it, 301) }.take(on).toSet()
        }
        private var lit = -1L
        /**
         * Each glowing pixel's window (its 4-connected patch of the emissive picture; -1 none) and how many: a tall front
         * ([TALL_WINDOWS] or more, the towers and hotels) lights a share of them by the hour ([towerShare]) instead of
         * every one all night (0.1.402: "high rises look weird at night", flat orange boxes).
         */
        private val egComp: IntArray
        private val nComp: Int
        private val compLit: BooleanArray
        val albedo: () -> Texture
        val mask: () -> Texture
        /** The back wall's picture (a mobile home's: [plainBack]; otherwise the front's). */
        val back: () -> Texture
        /** Heap bytes kept for the night light. */
        val bytes: Long get() = win.size + egAt.size * 6L
        /**
         * The mask's channels: red and green only (half the bytes, the same on screen) unless the emissive picture lights
         * a pixel, which marks it blue (a lit display in its own colours).
         */
        private val maskFmt: StreetTextures.Fmt get() = if (!TownQuality.maskFormats || egE.any { it.toInt() != 0 }) StreetTextures.Fmt.RGBA8 else StreetTextures.Fmt.RG8

        init {
            val art = StreetTextures.argb(sb.front)
            val emis = runCatching { StreetTextures.argb(sb.emis) }.getOrNull()?.takeIf { sb.emis.width == w && sb.emis.height == h }
            val glass = sb.glass?.let { g -> runCatching { StreetTextures.argb(g) }.getOrNull()?.takeIf { g.width == w && g.height == h } }
            var n = 0
            for (i in 0 until w * h) if ((emis?.get(i)?.ushr(24) ?: 0) * 3 / 5 > 0 || (glass?.get(i)?.ushr(24) ?: 0) * 2 / 5 > 0) n++
            egAt = IntArray(n); egE = ByteArray(n); egG = ByteArray(n)
            var k = 0
            for (i in 0 until w * h) {
                val e = emis?.let { (it[i] ushr 24) * 3 / 5 } ?: 0
                val g = glass?.let { (it[i] ushr 24) * 2 / 5 } ?: 0
                if (e == 0 && g == 0) continue
                egAt[k] = i; egE[k] = e.toByte(); egG[k] = g.toByte(); k++
            }
            // the glowing pixels' patches (one a window, or a band of them), numbered by a flood fill
            val comp = IntArray(w * h) { -1 }
            val isE = BooleanArray(w * h)
            for (j in 0 until n) if (egE[j].toInt() != 0) isE[egAt[j]] = true
            var nc = 0
            val stack = IntArray(w * h)
            for (i0 in 0 until w * h) {
                if (!isE[i0] || comp[i0] >= 0) continue
                var sp = 0; stack[sp++] = i0; comp[i0] = nc
                while (sp > 0) {
                    val i = stack[--sp]; val x = i % w; val y = i / w
                    if (x > 0 && isE[i - 1] && comp[i - 1] < 0) { comp[i - 1] = nc; stack[sp++] = i - 1 }
                    if (x < w - 1 && isE[i + 1] && comp[i + 1] < 0) { comp[i + 1] = nc; stack[sp++] = i + 1 }
                    if (y > 0 && isE[i - w] && comp[i - w] < 0) { comp[i - w] = nc; stack[sp++] = i - w }
                    if (y < h - 1 && isE[i + w] && comp[i + w] < 0) { comp[i + w] = nc; stack[sp++] = i + w }
                }
                nc++
            }
            nComp = nc
            egComp = IntArray(n) { comp[egAt[it]] }
            compLit = BooleanArray(nc) { true }
            for (r in rects) {
                for (y in r[1].toInt() until (r[1] + r[3]).toInt().coerceAtMost(h)) for (x in r[0].toInt() until (r[0] + r[2]).toInt().coerceAtMost(w)) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    if ((art[i] ushr 24) < 250) win[i] = SEE
                }
            }
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
                        if ((x - x0) % 2 == 0 && (y - y0) % 2 == 0 && Ambient.hash(seed + j, x * 31 + y, 78) > .86f) win[i] = CITY
                    }
                    px[i] = blend(g.toArgb(), px[i])
                }
            }
            albedo = pic(StreetTextures.prepare(px, w, h, mips = true))
            mask = pic(StreetTextures.blankShared(w, h, linear = true, fmt = maskFmt))
            back = if (sb.house != null) pic(StreetTextures.prepare(plainBack(art, px), w, h, mips = true)) else albedo
        }

        /**
         * A mobile home's back wall (the art has none): its front with the door and the porch's gap taken out. Each row
         * becomes the siding's commonest colour along it (siding runs level, so a row is one colour), mirrored as the back
         * is seen, and only the windows (their glass filled as on the front) are kept, two thirds of them.
         */
        private fun plainBack(art: IntArray, front: IntArray): IntArray {
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val counts = HashMap<Int, Int>()
                // (the end columns are the corner trim: kept as they are)
                for (x in 2 until w - 2) { val c = art[y * w + x]; if ((c ushr 24) >= 250) counts.merge(c, 1, Int::plus) }
                val mode = counts.maxByOrNull { it.value }?.key ?: 0
                for (x in 0 until w) {
                    val c = art[y * w + x]
                    out[y * w + x] = if ((c ushr 24) < 250) c else if (x < 2 || x >= w - 2) c else mode
                }
            }
            for ((j, r) in panes.withIndex()) {
                if (j % 3 == 2) continue
                val x0 = r[0].toInt().coerceAtLeast(0); val y0 = r[1].toInt().coerceAtLeast(0)
                val x1 = (r[0] + r[2]).toInt().coerceAtMost(w); val y1 = (r[1] + r[3]).toInt().coerceAtMost(h)
                // the pane with a pixel's frame round it
                for (y in (y0 - 1).coerceAtLeast(0) until (y1 + 1).coerceAtMost(h)) for (x in (x0 - 1).coerceAtLeast(0) until (x1 + 1).coerceAtMost(w))
                    out[y * w + x] = front[y * w + x]
            }
            // seen from behind, the picture runs the other way
            for (y in 0 until h) for (x in 0 until w / 2) { val a = out[y * w + x]; out[y * w + x] = out[y * w + w - 1 - x]; out[y * w + w - 1 - x] = a }
            return out
        }

        fun update(hour: Float) {
            var bits = 0L
            for (j in rects.indices) {
                if (j >= 63) break
                val room = if (j < nw) StageTuning.interiors && j in rooms else true
                if (room && Dusk.lightsOn(seed * 31 + j, hour) > .5f) bits = bits or (1L shl j)
            }
            // a tall front's windows: a share lit by the hour, each window by its own draw, so the set changes a few at a time
            if (nComp >= TALL_WINDOWS) {
                val share = towerShare(hour, seed)
                var key = 1469598103934665603L
                for (c in 0 until nComp) {
                    val on = Ambient.hash(seed, c, 911) < share
                    compLit[c] = on
                    if (on) key = (key xor c.toLong()) * 1099511628211L
                }
                bits = bits xor key
            }
            if (bits == lit) return
            lit = bits
            // the mask as Filament takes it (RGBA, bottom row first): opaque, the emissive's red (and blue) and the
            // glass's green where they show, then the windows' see-through pixels over them
            // (in RG8 only red and green: no pixel is then blue, and a missing alpha reads opaque)
            val fmt = maskFmt
            val st = fmt.bytes
            val buf = StreetTextures.blank(w, h, linear = true, fmt = fmt).buf
            if (st == 4) for (i in 0 until w * h) buf.put(StreetTextures.offset(i, w, h) + 3, 255.toByte())
            for (k in egAt.indices) {
                val o = StreetTextures.offset(egAt[k], w, h, st)
                val ec = egComp[k]
                val e = if (ec >= 0 && !compLit[ec]) 0.toByte() else egE[k]
                buf.put(o, e).put(o + 1, egG[k])
                if (e.toInt() != 0) buf.put(o + 2, 255.toByte())
            }
            for ((j, r) in rects.withIndex()) {
                val on = j < 63 && (bits shr j) and 1L == 1L
                for (y in r[1].toInt() until (r[1] + r[3]).toInt().coerceAtMost(h)) for (x in r[0].toInt() until (r[0] + r[2]).toInt().coerceAtMost(w)) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    val c = win[i]
                    if (c == NONE) continue
                    val red = if (on) 255 else if (c == CITY) 150 else 0
                    val o = StreetTextures.offset(i, w, h, st)
                    buf.put(o, red.toByte()).put(o + 1, 255.toByte())
                    if (st == 4) buf.put(o + 2, 0).put(o + 3, 255.toByte())
                }
            }
            textures.update(mask(), buf)
        }

        private fun blend(under: Int, over: Int): Int {
            val a = (over ushr 24) / 255f
            fun ch(s: Int) = (((under shr s) and 255) * (1f - a) + ((over shr s) and 255) * a).toInt().coerceIn(0, 255)
            return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    companion object {
        /** A front with this many glowing windows (patches) or more lights a share of them by the hour: a tower, a hotel. */
        const val TALL_WINDOWS = 24

        /**
         * The share of a tall building's windows lit at [hour] (each building [seed] a little more or less): offices and
         * hotels at dusk about half, fewer through the evening, a few cleaners' and night owls' at night.
         */
        fun towerShare(hour: Float, seed: Int): Float {
            val h = ((hour % 24f) + 24f) % 24f
            val base = when {
                h < 5f -> .08f
                h < 7f -> .08f + (h - 5f) / 2f * .17f
                h < 17f -> .25f
                h < 19f -> .25f + (h - 17f) / 2f * .3f
                h < 21f -> .55f - (h - 19f) / 2f * .2f
                h < 23.5f -> .35f - (h - 21f) / 2.5f * .22f
                else -> .13f - (h - 23.5f) / .5f * .05f
            }
            return (base * (.7f + .6f * Ambient.hash(seed, 7, 913))).coerceIn(0f, 1f)
        }

        private const val NONE: Byte = 0
        private const val SEE: Byte = 1
        private const val CITY: Byte = 2
    }
}
