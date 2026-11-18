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
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.QuadMap
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a modelled building stands in the world: the point of its model's (x 0, d 0) on the ground ([ox], [oz]), the
 * street's direction along its front ([ax], [az]: the model's x), the way its front faces ([nx], [nz]: towards the
 * street; the model's d runs the other way) and street units per sprite pixel ([k]).
 */
internal class ModelPlace(val ox: Float, val oz: Float, val ax: Float, val az: Float, val nx: Float, val nz: Float, val k: Float)

/** One modelled building made into Filament geometry ([FilamentModels.build]): drawn as one renderable, its shadow by [proxy]. */
internal class ModelMesh(
    val entity: Int,
    val proxy: Int,
    val mis: Array<MaterialInstance>,
    val proxyMis: Array<MaterialInstance>,
    val vb: VertexBuffer,
    val ib: IndexBuffer,
    /** Its windows' night light: call with the hour (it rebuilds its mask only when the set of lit windows changes). */
    val night: ((Float) -> Unit)?,
    /** Its world bounds (min x, y, z, max x, y, z), and those of the building alone (not its yard). */
    val bounds: FloatArray,
    val body: FloatArray,
)

/**
 * Our villas and landmarks ([ModelArt], buildings/models.json) as real geometry for the Filament street. Every part
 * type the classic street draws ([com.lifemax.rpg.feature.habits.presentation.scene.BuildingPainter]'s modelBox,
 * frontGable, hippedRoof, modelRail, modelDecal, modelSlope, modelPrism and stripPart) becomes faces in the model's own
 * pixels (x across its front picture, y up, d back from its front line), each wearing its piece of the building's atlas
 * as the classic lays it, then placed in the world by [ModelPlace]:
 *
 * - box (0): its front (the piece may be wider and taller than the box: a gable's face over a wall) with a plain backing
 *   in its own colour (no see-through where the art has a hole), both sides unless one stands against another volume
 *   (`hs`), its side picture repeated along the depth as the classic repeats it, its top (picture, back row first, or
 *   colour), its back (the side picture across it). A card is its front only.
 * - gable (1): each slope the art's slope from above laid on its quad by one projective map (pts, in the quad's own
 *   plane, finely divided), a plain underlay in the roof's colour under it, the verge along the rake, the eave boards,
 *   the back gable end and the walls between a roof and a lower wall under it; a single-pitch roof (apexX at one side)
 *   has its high side as a wall; the face piece if it has its own.
 * - hip (2): the front face (the art's, its trapezoid in the picture's alpha), the side faces on their quads, the back
 *   wearing the front's picture when the roof is symmetric (else its colour), the flat top, creases along the hips and a
 *   plain core under all of it.
 * - rail (3): the handrail and the posts as thin boxes, the glass panel screen-doored by its alpha.
 * - decal (4), slope (5), prism (6), cyl (7), wall (8): as their pictures say; a rounded wall's columns evenly along its
 *   angle with smooth normals, its top a sector or a ring's band.
 *
 * Coplanar parts are kept apart as the classic's drawing order keeps them: each part's faces stand out from it by a
 * hundredth of a pixel more than the part before it.
 */
internal object FilamentModels {
    private const val VERGE = 2f
    private const val GABLE_LIP = 3f
    /** The green (glass) a model's square panes get in the mask: their art keeps its curtains, so only partly mirror. */
    private const val PANE_GLASS = 110

    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE).also { it.anisotropy = 16f }
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val repeat = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT)

    /**
     * Builds [art] standing at [place] into a renderable (added to nothing: the caller adds [ModelMesh.entity] and
     * [ModelMesh.proxy] to its scene). [proxyMat] null: no shadow twin (the unlit comparison). [linear]: tints in linear
     * colour (the lit material's pictures are sRGB). [seed] picks which windows are lit when.
     */
    fun build(
        engine: Engine, visibleMat: Material, proxyMat: Material?, textures: StreetTextures, art: ModelArt,
        place: ModelPlace, seed: Int, linear: Boolean,
    ): ModelMesh = finish(engine, visibleMat, proxyMat, textures, prepare(textures, art, place, seed, linear))

    /**
     * The part of [build] with no engine call (any thread: the town's workers): the geometry made and packed, the night
     * light's map worked out, the pictures read ahead ([StreetTextures.warm]). [finish] makes it Filament's.
     */
    fun prepare(textures: StreetTextures, art: ModelArt, place: ModelPlace, seed: Int, linear: Boolean): Ready {
        val g = Mesher(art)
        g.all()
        val night = Night(art, seed, textures, linear)
        textures.warm(art.atlas)
        for (k in g.groups.keys) if (k is Key.Pic) textures.warm(k.img)
        return pack(g, place, night, linear)
    }

    /** A model made ready off the engine's thread ([prepare]): its vertices and indices packed, by group. */
    class Ready internal constructor(
        internal val atlas: ImageBitmap,
        internal val groups: List<Group>,
        internal val vb: ByteBuffer, internal val ib: ByteBuffer, internal val nv: Int, internal val ni: Int,
        internal val ranges: List<IntArray>,
        internal val bounds: FloatArray, internal val body: FloatArray,
        internal val night: Night, internal val linear: Boolean,
    )

    // ------------------------------------------------------------------ the geometry, in the model's pixels

    /** What a group of faces wears: the atlas, a plain colour, one of the yard's pictures, a screen-doored glass. */
    sealed class Key {
        object Atlas : Key()
        /** The atlas on faces seen from their front only (a card's front and back: through its holes, not the other). */
        object Card : Key()
        data class Plain(val rgb: Int) : Key()
        data class Pic(val img: ImageBitmap) : Key()
        data class Glass(val rgb: Int, val alpha: Int) : Key()
        /** A clock's hands (its [ModelPart.clock]), painted at the hour. */
        class Clock(val c: IntArray) : Key()
    }

    class Group(val key: Key) {
        /** Per vertex: x, y, d, u, v, then the normal and the tangent (model axes). */
        var v = FloatArray(1024); var nv = 0
        var idx = IntArray(1024); var ni = 0
        fun vert(x: Float, y: Float, d: Float, u: Float, w: Float, nx: Float, ny: Float, nd: Float, tx: Float, ty: Float, td: Float): Int {
            if ((nv + 1) * 11 > v.size) v = v.copyOf(v.size * 2)
            val o = nv * 11
            v[o] = x; v[o + 1] = y; v[o + 2] = d; v[o + 3] = u; v[o + 4] = w
            v[o + 5] = nx; v[o + 6] = ny; v[o + 7] = nd; v[o + 8] = tx; v[o + 9] = ty; v[o + 10] = td
            return nv++
        }
        fun tri(a: Int, b: Int, c: Int) {
            if (ni + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
            idx[ni++] = a; idx[ni++] = b; idx[ni++] = c
        }
    }

    class Mesher(private val art: ModelArt) {
        val atlas: ImageBitmap get() = art.atlas
        private val sh: ModelShape = art.shape
        private val aw = art.atlas.width.toFloat()
        private val ah = art.atlas.height.toFloat()
        val groups = LinkedHashMap<Key, Group>()
        /** How far the current part's faces stand out along their normals (pixels): its place in the drawing order. */
        private var eps = 0f

        private fun group(k: Key) = groups.getOrPut(k) { Group(k) }

        // a few float triples for corners
        private fun p(x: Float, y: Float, d: Float) = floatArrayOf(x, y, d)

        /** The unit normal of the polygon [c] (its first three corners), turned to agree with [hint]. */
        private fun normal(c: Array<FloatArray>, hint: FloatArray?): FloatArray {
            var nx = 0f; var ny = 0f; var nd = 0f
            // Newell's method: robust for any planar polygon
            for (i in c.indices) {
                val a = c[i]; val b = c[(i + 1) % c.size]
                nx += (a[1] - b[1]) * (a[2] + b[2])
                ny += (a[2] - b[2]) * (a[0] + b[0])
                nd += (a[0] - b[0]) * (a[1] + b[1])
            }
            val l = sqrt(nx * nx + ny * ny + nd * nd)
            if (l < 1e-6f) return hint?.let { norm(it) } ?: floatArrayOf(0f, 0f, -1f)
            nx /= l; ny /= l; nd /= l
            if (hint != null && nx * hint[0] + ny * hint[1] + nd * hint[2] < 0f) { nx = -nx; ny = -ny; nd = -nd }
            return floatArrayOf(nx, ny, nd)
        }

        private fun norm(a: FloatArray): FloatArray {
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6f)
            return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }

        /**
         * A planar polygon [c] (corners in order, a fan from the first) in group [k], its picture coordinates [uv] (atlas
         * or picture fractions) per corner; outward along [hint]; [inset] pushes it back against its normal (pixels).
         */
        fun poly(k: Key, c: Array<FloatArray>, uv: FloatArray, hint: FloatArray?, inset: Float = 0f) {
            val n = normal(c, hint)
            val gp = group(k)
            // the tangent: along the picture's u (from the first corner to the second, as most faces are laid)
            var t = floatArrayOf(c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2])
            if (t[0] * t[0] + t[1] * t[1] + t[2] * t[2] < 1e-8f && c.size > 2) t = floatArrayOf(c[2][0] - c[1][0], c[2][1] - c[1][1], c[2][2] - c[1][2])
            t = norm(t)
            val off = eps - inset
            val base = gp.nv
            for (i in c.indices) {
                gp.vert(c[i][0] + n[0] * off, c[i][1] + n[1] * off, c[i][2] + n[2] * off, uv[i * 2], uv[i * 2 + 1], n[0], n[1], n[2], t[0], t[1], t[2])
            }
            for (i in 1 until c.size - 1) gp.tri(base, base + i, base + i + 1)
        }

        /** A quad wearing the atlas piece [r] whole: [c] its corners for the piece's top left, top right, bottom right, bottom left. */
        fun piece(r: IntArray, c: Array<FloatArray>, hint: FloatArray?, key: Key = Key.Atlas) {
            val u0 = r[0] / aw; val u1 = (r[0] + r[2]) / aw; val v0 = r[1] / ah; val v1 = (r[1] + r[3]) / ah
            poly(key, c, floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), hint)
        }

        /** A plain quad or polygon in colour [rgb]. */
        fun plain(rgb: Int, c: Array<FloatArray>, hint: FloatArray?, inset: Float = 0f) =
            poly(Key.Plain(rgb and 0xFFFFFF), c, FloatArray(c.size * 2), hint, inset)

        /**
         * The atlas piece [r] laid on the planar quad [c] by the projective map taking its points [pts] (in the piece's
         * own pixels, one for each corner of [c] in order) onto it, as the classic lays a roof's slope on its screen quad;
         * divided [n] by [n] so the map holds across it.
         */
        fun mapped(r: IntArray, pts: FloatArray, c: Array<FloatArray>, hint: FloatArray?, n: Int = 8) {
            val nrm = normal(c, hint)
            // the quad in its own plane: e1 along its first edge, e2 across it in the plane
            val o = c[0]
            val e1 = norm(floatArrayOf(c[1][0] - o[0], c[1][1] - o[1], c[1][2] - o[2]))
            val e2 = floatArrayOf(nrm[1] * e1[2] - nrm[2] * e1[1], nrm[2] * e1[0] - nrm[0] * e1[2], nrm[0] * e1[1] - nrm[1] * e1[0])
            fun plane(q: FloatArray, out: FloatArray, i: Int) {
                val dx = q[0] - o[0]; val dy = q[1] - o[1]; val dz = q[2] - o[2]
                out[i] = dx * e1[0] + dy * e1[1] + dz * e1[2]; out[i + 1] = dx * e2[0] + dy * e2[1] + dz * e2[2]
            }
            val from = FloatArray(8)
            for (i in 0..3) plane(c[i], from, i * 2)
            val m = DoubleArray(9)
            if (!QuadMap.quadToQuad(from, pts, m, DoubleArray(9), DoubleArray(9))) return
            val gp = group(Key.Atlas)
            val off = eps
            val base = gp.nv
            val q = FloatArray(2); val pp = FloatArray(2)
            for (j in 0..n) for (i in 0..n) {
                val s = i.toFloat() / n; val t = j.toFloat() / n
                // bilinear over the corners: (0,0) c0, (1,0) c1, (1,1) c2, (0,1) c3
                val x = (1 - s) * (1 - t) * c[0][0] + s * (1 - t) * c[1][0] + s * t * c[2][0] + (1 - s) * t * c[3][0]
                val y = (1 - s) * (1 - t) * c[0][1] + s * (1 - t) * c[1][1] + s * t * c[2][1] + (1 - s) * t * c[3][1]
                val d = (1 - s) * (1 - t) * c[0][2] + s * (1 - t) * c[1][2] + s * t * c[2][2] + (1 - s) * t * c[3][2]
                plane(floatArrayOf(x, y, d), q, 0)
                QuadMap.apply(m, q[0], q[1], pp)
                // (kept on the piece: the points may reach a pixel or two past it, into its neighbours in the atlas)
                val px = pp[0].coerceIn(0f, r[2].toFloat()); val py = pp[1].coerceIn(0f, r[3].toFloat())
                gp.vert(x + nrm[0] * off, y + nrm[1] * off, d + nrm[2] * off, (r[0] + px) / aw, (r[1] + py) / ah, nrm[0], nrm[1], nrm[2], e1[0], e1[1], e1[2])
            }
            for (j in 0 until n) for (i in 0 until n) {
                val a = base + j * (n + 1) + i
                gp.tri(a, a + 1, a + n + 2); gp.tri(a, a + n + 2, a + n + 1)
            }
        }

        // ---------------------------------------------------------------- the parts

        fun all() {
            yard()
            for (i in sh.parts.indices) {
                val pt = sh.parts[i]
                eps = .01f * (i + 1)
                when (pt.type) {
                    ModelPart.BOX -> box(pt)
                    ModelPart.GABLE -> gable(pt)
                    ModelPart.HIP -> hip(pt)
                    ModelPart.RAIL -> rail(pt)
                    ModelPart.DECAL -> decal(pt)
                    ModelPart.SLOPE -> slope(pt)
                    ModelPart.PRISM -> prism(pt)
                    ModelPart.CYL -> cyl(pt)
                    ModelPart.WALL -> wall(pt)
                }
            }
            eps = 0f
        }

        /** The yard: its lawn (or paving) from the pavement back past the building, and its fence or hedge along the street. */
        private fun yard() {
            val back = sh.depth + 8f
            art.lawn?.let { lawn ->
                poly(Key.Pic(lawn), arrayOf(p(0f, .05f, back), p(sh.width, .05f, back), p(sh.width, .05f, -sh.setback), p(0f, .05f, -sh.setback)),
                    floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), UP)
            }
            art.fence?.let { fe ->
                val d = sh.fenceD - sh.setback
                val foot = sh.fenceFoot.coerceIn(1f, fe.height.toFloat())
                // (the picture's rows under its foot are the ground in front of it, which the yard's own ground is)
                poly(Key.Pic(fe), arrayOf(p(0f, foot, d), p(fe.width.toFloat(), foot, d), p(fe.width.toFloat(), 0f, d), p(0f, 0f, d)),
                    floatArrayOf(0f, 0f, 1f, 0f, 1f, foot / fe.height, 0f, foot / fe.height), FRONT)
            }
        }

        private fun box(b: ModelPart) {
            val d0 = b.d0
            val d1 = if (b.card) b.d0 else b.d1
            val ft = b.ft
            if (ft != null) {
                val top = b.y0 + ft[3]
                piece(ft, arrayOf(p(b.fx, top, d0), p(b.fx + ft[2], top, d0), p(b.fx + ft[2], b.y0, d0), p(b.fx, b.y0, d0)), FRONT, if (b.card) Key.Card else Key.Atlas)
                // its own colour behind the art (a hole in the picture shows the wall, not the box's inside)
                if (!b.card && d1 > d0 + .5f) plain(b.front, arrayOf(p(b.x0, b.y1, d0), p(b.x1, b.y1, d0), p(b.x1, b.y0, d0), p(b.x0, b.y0, d0)), FRONT, inset = .2f)
                // a card seen from behind (a sign on a roof, a helicopter): its picture the right way round, lit from
                // there (each of its two faces seen from its own side only: else each reads mirrored through the other)
                if (b.card) piece(ft, arrayOf(p(b.fx + ft[2], top, d0), p(b.fx, top, d0), p(b.fx, b.y0, d0), p(b.fx + ft[2], b.y0, d0)), BACK, Key.Card)
            } else {
                plain(b.front, arrayOf(p(b.x0, b.y1, d0), p(b.x1, b.y1, d0), p(b.x1, b.y0, d0), p(b.x0, b.y0, d0)), FRONT)
            }
            val ck = b.clock
            if (ft != null && ck != null) {
                // the clock's hands over its dial (the art's painted ones are taken off), a hair in front of it
                val nc = com.lifemax.rpg.feature.habits.domain.ClockHands.size(ck[3]); val mid = nc / 2
                val x0 = b.fx + ck[0] - mid * ck[2]; val ytop = b.y0 + ft[3] - (ck[1] - mid * ck[2]); val sz = nc * ck[2].toFloat()
                poly(Key.Clock(ck), arrayOf(p(x0, ytop, d0 - .1f), p(x0 + sz, ytop, d0 - .1f), p(x0 + sz, ytop - sz, d0 - .1f), p(x0, ytop - sz, d0 - .1f)),
                    floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), FRONT)
            }
            if (d1 <= d0 + .5f) return
            val st = b.st
            for (side in 0..1) {
                val left = side == 0
                if ((b.hideSide and (if (left) 1 else 2)) != 0) continue
                val xs = if (left) b.x0 else b.x1
                val hint = if (left) LEFT else RIGHT
                if (st != null) {
                    val reps = if (b.y1 - b.y0 < st[3] - .5f || st[2] >= (d1 - d0) - 1f) 1 else max(1, kotlin.math.round((d1 - d0) / st[2]).toInt())
                    for (j in 0 until reps) {
                        val da = d0 + (d1 - d0) * j / reps; val db = d0 + (d1 - d0) * (j + 1) / reps
                        piece(st, arrayOf(p(xs, b.y1, da), p(xs, b.y1, db), p(xs, b.y0, db), p(xs, b.y0, da)), hint)
                    }
                    // its colour behind the picture: no pinholes of sky along the corners
                    plain(b.side, arrayOf(p(xs, b.y1, d0), p(xs, b.y1, d1), p(xs, b.y0, d1), p(xs, b.y0, d0)), hint, inset = .2f)
                    // the clock on this side too, when it wears the front's picture (the clock tower's); mirrored on the
                    // left, whose picture runs back from the front as the classic lays it
                    val ck = b.clock
                    if (ck != null && reps == 1 && st.contentEquals(ft)) {
                        val nc = com.lifemax.rpg.feature.habits.domain.ClockHands.size(ck[3]); val mid = nc / 2
                        val per = (d1 - d0) / st[2]; val vy = (b.y1 - b.y0) / st[3]
                        val c0 = ck[0] - mid * ck[2]; val r0 = ck[1] - mid * ck[2]; val sz = nc * ck[2]
                        val da = d0 + c0 * per; val db = d0 + (c0 + sz) * per
                        val ya = b.y1 - r0 * vy; val yb = b.y1 - (r0 + sz) * vy
                        val out = if (left) -.1f else .1f
                        val uv = if (left) floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f) else floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                        poly(Key.Clock(ck), arrayOf(p(xs + out, ya, da), p(xs + out, ya, db), p(xs + out, yb, db), p(xs + out, yb, da)), uv, hint)
                    }
                } else plain(b.side, arrayOf(p(xs, b.y1, d0), p(xs, b.y1, d1), p(xs, b.y0, d1), p(xs, b.y0, d0)), hint)
            }
            val tt = b.tt
            if (tt != null) piece(tt, arrayOf(p(b.x0, b.y1, d1), p(b.x1, b.y1, d1), p(b.x1, b.y1, d0), p(b.x0, b.y1, d0)), UP)
            else if (b.top >= 0) plain(b.top, arrayOf(p(b.x0, b.y1, d1), p(b.x1, b.y1, d1), p(b.x1, b.y1, d0), p(b.x0, b.y1, d0)), UP)
            // the back: no view of the classic's sees it; the side's picture across it (its windows carry on round)
            if (st != null) {
                val reps = max(1, kotlin.math.round((b.x1 - b.x0) / st[2]).toInt())
                // repeated, the picture's outermost columns (its corner's outline) would stand as seams across the back
                val trim = if (reps > 1 && st[2] > 16) 1 else 0
                val r = intArrayOf(st[0] + trim, st[1], st[2] - 2 * trim, st[3])
                for (j in 0 until reps) {
                    val xa = b.x1 - (b.x1 - b.x0) * j / reps; val xb = b.x1 - (b.x1 - b.x0) * (j + 1) / reps
                    piece(r, arrayOf(p(xa, b.y1, d1), p(xb, b.y1, d1), p(xb, b.y0, d1), p(xa, b.y0, d1)), BACK)
                }
                plain(b.side, arrayOf(p(b.x1, b.y1, d1), p(b.x0, b.y1, d1), p(b.x0, b.y0, d1), p(b.x1, b.y0, d1)), BACK, inset = .2f)
                // the clock on the back too, when it wears the front's picture
                val ck = b.clock
                if (ck != null && reps == 1 && st.contentEquals(ft)) {
                    val nc = com.lifemax.rpg.feature.habits.domain.ClockHands.size(ck[3]); val mid = nc / 2
                    val per = (b.x1 - b.x0) / st[2]; val vy = (b.y1 - b.y0) / st[3]
                    val c0 = ck[0] - mid * ck[2]; val r0 = ck[1] - mid * ck[2]; val sz = nc * ck[2]
                    val xa = b.x1 - c0 * per; val xb = b.x1 - (c0 + sz) * per
                    val ya = b.y1 - r0 * vy; val yb = b.y1 - (r0 + sz) * vy
                    poly(Key.Clock(ck), arrayOf(p(xa, ya, d1 + .1f), p(xb, ya, d1 + .1f), p(xb, yb, d1 + .1f), p(xa, yb, d1 + .1f)),
                        floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), BACK)
                }
            } else plain(b.side, arrayOf(p(b.x1, b.y1, d1), p(b.x0, b.y1, d1), p(b.x0, b.y0, d1), p(b.x1, b.y0, d1)), BACK)
            // the underside of a box standing clear of the ground (an overhang, a ledge, a balcony's ceiling): in its
            // shade colour, a hair up into it (a box under it shares the plane)
            if (b.y0 > .5f) plain(darker(b.side, .25f), arrayOf(p(b.x0, b.y0, d0), p(b.x1, b.y0, d0), p(b.x1, b.y0, d1), p(b.x0, b.y0, d1)), DOWN, inset = .3f)
        }

        /** The box a roof stands on (its top at the roof's eave, under it), the highest such, or null. */
        private fun under(r: ModelPart): ModelPart? = sh.parts.filter { b ->
            b.type == ModelPart.BOX && !b.card && b.d1 - b.d0 > 1f && b.y1 <= r.y0 + .5f &&
                b.x0 < r.x1 - .5f && b.x1 > r.x0 + .5f && b.d0 < r.d1 - .5f && b.d1 > r.d0 + .5f
        }.maxByOrNull { it.y1 }

        private fun gable(g: ModelPart) {
            val ax = g.apex
            val base = under(g)
            val wall = base?.side ?: g.rake
            val lip = if (g.lip >= 0) g.lip else darker(g.front, .35f)
            val back = g.eaveBack
            for (side in 0..1) {
                val left = side == 0
                val xe = if (left) g.x0 else g.x1
                // a single-pitch roof has no slope on its high side
                if (abs(xe - ax) < .5f) continue
                val c = arrayOf(p(xe, g.y0, g.d0), p(ax, g.y1, g.d0), p(ax, g.y1, g.d1), p(xe, g.y0, back))
                val hint = floatArrayOf(if (left) -1f else 1f, 1f, 0f)
                val r = if (left) g.left else g.right
                val pts = if (left) g.leftP else g.rightP
                if (r != null && pts != null) mapped(r, pts, c, hint)
                // its underlay in the roof's colour: where the art's slope has a hole, the roof, not the sky
                plain(g.front, c, hint, inset = .25f)
                // the verge: the roof's edge seen edge on along the rake, a little proud of the slope's front edge and
                // down past the face's stepped rake (the face, in front, covers the rest)
                plain(lip, arrayOf(p(xe, g.y0 + VERGE, g.d0 + .15f), p(ax, g.y1 + VERGE, g.d0 + .15f), p(ax, g.y1 - GABLE_LIP, g.d0 + .15f), p(xe, g.y0 - GABLE_LIP, g.d0 + .15f)), FRONT)
                // the eave's board along the side
                if (back > g.d0 + 1f) {
                    plain(darker(g.rake, .1f), arrayOf(p(xe, g.y0, g.d0), p(xe, g.y0, back), p(xe, g.y0 - 2.5f, back), p(xe, g.y0 - 2.5f, g.d0)), if (left) LEFT else RIGHT)
                }
            }
            // between the eave and a lower wall under it (a terraced house's front stands taller than its side walls):
            // the wall carried up on each side, and at the back
            val wallTop = base?.y1 ?: g.y0
            val wx0 = base?.x0?.coerceAtLeast(g.x0) ?: g.x0
            val wx1 = base?.x1?.coerceAtMost(g.x1) ?: g.x1
            val wd0 = max(g.d0, base?.d0 ?: g.d0)
            val wd1 = min(back, base?.d1 ?: back)
            for (side in 0..1) {
                val left = side == 0
                val xe = if (left) g.x0 else g.x1
                val xw = if (left) wx0 else wx1
                val high = abs(xe - ax) < .5f
                val top = if (high) g.y1 else g.y0
                if (top - wallTop > .5f && wd1 > wd0 + .5f) {
                    plain(wall, arrayOf(p(xw, top, wd0), p(xw, top, wd1), p(xw, wallTop, wd1), p(xw, wallTop, wd0)), if (left) LEFT else RIGHT)
                }
            }
            // the back gable end, where the roof ends (not where it runs into another)
            if (back >= g.d1 - .5f) {
                val d = g.d1
                val pts = arrayListOf(p(wx1, wallTop, d), p(wx0, wallTop, d))
                if (g.y0 > wallTop + .5f) { pts += p(wx0, g.y0, d) }
                if (abs(ax - g.x0) > .5f) pts += p(if (abs(ax - g.x1) < .5f) wx1 else ax, g.y1, d) else pts += p(wx0, g.y1, d)
                if (g.y0 > wallTop + .5f && abs(ax - g.x1) > .5f) pts += p(wx1, g.y0, d)
                val c = pts.toTypedArray()
                // it wears the front's gable (the box's front carrying it, its picture reaching up into the gable, or
                // the gable's own face), seen from behind: only what is the gable's own (its cladding, not the tops of
                // the front's windows or a lower roof painted over it), carried down over the rest
                val carrier = sh.parts.firstOrNull { bx ->
                    bx.type == ModelPart.BOX && !bx.card && bx.y1 <= g.y0 + .5f && abs(g.d0 - bx.d0) < 1f && bx.ft != null &&
                        bx.y0 + bx.ft[3] >= g.y0 + (g.y1 - g.y0) * .5f && bx.x0 < g.x1 && bx.x1 > g.x0
                }
                val pic = carrier?.ft ?: g.face
                if (pic != null) {
                    val fx = carrier?.fx ?: g.fx
                    val top = (carrier?.y0 ?: g.faceY) + pic[3]
                    backGable(c, pic, fx, top, wallTop, wall, carrier?.panes ?: g.panes, g, carrier, g.d0)
                } else plain(wall, c, BACK)
            }
            // its face, when it stands on its own (a box's front carries the main one)
            val face = g.face
            if (face != null) {
                val top = g.faceY + face[3]
                piece(face, arrayOf(p(g.fx, top, g.d0), p(g.fx + face[2], top, g.d0), p(g.fx + face[2], g.faceY, g.d0), p(g.fx, g.faceY, g.d0)), FRONT)
            }
        }

        private val atlasPx: IntArray by lazy { StreetTextures.argb(art.atlas) }

        /** The polygon [c]'s extent across (min x, max x) at height [y] (its corners' x, d: a back face, d constant). */
        private fun across(c: Array<FloatArray>, y: Float): FloatArray? {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in c.indices) {
                val a = c[i]; val b = c[(i + 1) % c.size]
                if ((a[1] - y) * (b[1] - y) > 0f || abs(b[1] - a[1]) < 1e-4f) continue
                val x = a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1])
                lo = min(lo, x); hi = max(hi, x)
            }
            return if (hi > lo) floatArrayOf(lo, hi) else null
        }

        /** [c] cut to what lies above (or, [above] false, below) [v] along axis [ax] (0 x, 1 y). */
        private fun clip(c: Array<FloatArray>, ax: Int, v: Float, above: Boolean): Array<FloatArray> {
            val out = ArrayList<FloatArray>()
            fun inside(q: FloatArray) = if (above) q[ax] >= v else q[ax] <= v
            for (i in c.indices) {
                val a = c[i]; val b = c[(i + 1) % c.size]
                if (inside(a)) out += a
                if (inside(a) != inside(b)) {
                    val t = (v - a[ax]) / (b[ax] - a[ax])
                    out += floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
                }
            }
            return out.toTypedArray()
        }
        private fun clipY(c: Array<FloatArray>, y: Float, above: Boolean) = clip(c, 1, y, above)

        /**
         * The back gable end [c] (from the wall's top [wallTop] up) wearing the front's gable [pic] (its left edge at
         * [fx], its top at [top]), seen from behind. The front picture's gable often carries what is not the gable's: the
         * tops of the upper windows, a lower roof's peak painted over it. So its rows are read from the top down, and the
         * first that holds much of what the gable's upper part does not (its colours) ends what is used as it is; below
         * that, the clean rows just above are carried down in bands (each band's ends on the rakes, as a gable's rows are
         * the same shape, only wider), so the cladding runs on to the wall. [wall] fills behind it (holes in the art).
         */
        private fun backGable(c: Array<FloatArray>, pic: IntArray, fx: Float, top: Float, wallTop: Float, wall: Int, panes: List<FloatArray>, self: ModelPart, carrier: ModelPart?, dFront: Float) {
            val iw = art.atlas.width
            fun px(x: Float, y: Float): Int {
                val u = (x - fx).toInt(); val v = (top - y).toInt()
                if (u < 0 || v < 0 || u >= pic[2] || v >= pic[3]) return 0
                return atlasPx[(pic[1] + v) * iw + pic[0] + u]
            }
            fun uvOf(x: Float, y: Float, out: FloatArray, i: Int) {
                out[i] = (pic[0] + (x - fx).coerceIn(0f, pic[2].toFloat())) / aw
                out[i + 1] = (pic[1] + (top - y).coerceIn(0f, pic[3].toFloat())) / ah
            }
            val yHi = c.maxOf { it[1] }
            // what of the picture is not the gable's own: a window whose pane reaches up into the gable from the wall
            // under it (its top and frame), and whatever stands in front of the gable's foot (a lower roof, a dormer),
            // painted over it. The cut goes above them all; what is above it is the gable's
            var cut = wallTop
            for (pn in panes) {
                val pt = top - pn[1]; val pb = pt - pn[3]
                if (pt > wallTop + .5f && pb < wallTop + 2f) cut = max(cut, pt + 8f)
            }
            val gx0 = c.minOf { it[0] }; val gx1 = c.maxOf { it[0] }
            for (q in sh.parts) {
                if (q === self || q === carrier || q.card || q.type == ModelPart.RAIL || q.type == ModelPart.DECAL) continue
                if (q.d0 >= dFront - .5f) continue
                if (q.x1 <= gx0 + 1f || q.x0 >= gx1 - 1f) continue
                if (q.y1 > wallTop + .5f && q.y0 < wallTop + 1f) cut = max(cut, q.y1 + 4f)
            }
            cut = min(cut, yHi)
            val counts = HashMap<Int, Int>()
            for (r in 0 until (yHi - cut).toInt()) {
                val y = yHi - r - .5f
                val ex = across(c, y) ?: continue
                var x = ex[0] + 3f
                while (x < ex[1] - 3f) { val q = px(x, y); if ((q ushr 24) > 128) { val k = q and 0xFFFFFF; counts[k] = (counts[k] ?: 0) + 1 }; x += 1f }
            }
            val run = min(24, (yHi - cut).toInt() - 2)
            // the gable's body colour (behind the art, and the fill when too little of it is clean)
            val body = counts.maxByOrNull { it.value }?.key ?: wall
            plain(body, c, BACK, inset = .2f)
            val clean = yHi - cut
            if (cut <= wallTop + .5f) {
                val uv = FloatArray(c.size * 2)
                for (m in c.indices) uvOf(c[m][0], c[m][1], uv, m * 2)
                poly(Key.Atlas, c, uv, BACK)
                return
            }
            if (clean < 6f || run < 2) return
            // (at least a few of the art's doubled rows: a step of one board would carry a single stripe down as a slab)
            var period = min(run, 12)
            val upper = clipY(c, cut, true)
            if (upper.size >= 3) {
                val uv = FloatArray(upper.size * 2)
                for (m in upper.indices) uvOf(upper[m][0], upper[m][1], uv, m * 2)
                poly(Key.Atlas, upper, uv, BACK)
            }
            // the cladding's period: the row step that repeats best in the clean part
            fun rowAt(y: Float, x: Float) = px(x, y)
            var best = Float.MAX_VALUE
            val mid = (c.minOf { it[0] } + c.maxOf { it[0] }) / 2f
            for (pp in min(8, run)..min(16, run - 2)) {
                var diff = 0f; var n = 0
                var y = cut + .5f
                while (y + pp < cut + run && n < 400) {
                    val ex = across(c, y + pp) ?: break
                    var x = max(ex[0] + 3f, mid - 30f)
                    while (x < min(ex[1] - 3f, mid + 30f)) {
                        val a = rowAt(y, x); val b = rowAt(y + pp, x)
                        diff += (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))).toFloat()
                        n++; x += 2f
                    }
                    y += 1f
                }
                if (n > 0 && diff / n < best - 1f) { best = diff / n; period = pp }
            }
            // under the cut, the cladding just above it carried down band by band and across tile by tile (each tile a
            // plain shift of its source: a few columns where the rows are most even, away from any ornament)
            val lower = clipY(c, cut, false)
            if (lower.size < 3) return
            val srcB = cut
            val ext = across(c, cut + period - .01f) ?: return
            val room = (ext[1] - ext[0] - 6f).toInt()
            if (room < 4) return
            // the tile's width: the step across that repeats best (boards, battens), else as wide as there is room for
            var tw = min(24, room)
            var bestW = Float.MAX_VALUE
            for (cand in 12..min(32, room / 2)) {
                var diff = 0f; var n = 0
                for (r in 0 until period) {
                    var x = ext[0] + 3f
                    while (x + cand < ext[1] - 3f) {
                        val a = px(x, srcB + r + .5f); val b = px(x + cand, srcB + r + .5f)
                        diff += (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))).toFloat()
                        n++; x += 1f
                    }
                }
                if (n > 0 && diff / n < bestW - 2f) { bestW = diff / n; tw = cand }
            }
            var sx0 = ext[0] + 3f; var bestErr = Float.MAX_VALUE
            val rowMed = IntArray(period) { r ->
                val y = srcB + r + .5f
                val cs = ArrayList<Int>()
                var x = ext[0] + 3f
                while (x < ext[1] - 3f) { cs += px(x, y); x += 1f }
                cs.sortedBy { ((it shr 16) and 255) + ((it shr 8) and 255) + (it and 255) }.getOrElse(cs.size / 2) { 0 }
            }
            var xs = ext[0] + 3f
            while (xs + tw <= ext[1] - 3f) {
                var err = 0f
                for (r in 0 until period) for (k in 0 until tw) {
                    val a = px(xs + k + .5f, srcB + r + .5f); val b = rowMed[r]
                    err += if ((a ushr 24) < 128) 300f else (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))).toFloat()
                }
                if (err < bestErr) { bestErr = err; sx0 = xs }
                xs += 1f
            }
            val xMin = lower.minOf { it[0] }; val xMax = lower.maxOf { it[0] }
            var yt = cut
            while (yt > wallTop + .01f) {
                val yb = max(wallTop, yt - period)
                val band = clipY(clipY(lower, yt, false), yb, true)
                var xa = xMin
                while (band.size >= 3 && xa < xMax - .01f) {
                    val tile = clip(clip(band, 0, xa, true), 0, xa + tw, false)
                    if (tile.size >= 3) {
                        val uv = FloatArray(tile.size * 2)
                        for (m in tile.indices) uvOf(sx0 + (tile[m][0] - xa), srcB + (tile[m][1] - yb) + (period - (yt - yb)), uv, m * 2)
                        poly(Key.Atlas, tile, uv, BACK)
                    }
                    xa += tw
                }
                yt = yb
            }
        }

        private fun hip(h: ModelPart) {
            val fl = p(h.x0, h.y0, h.d0); val fr = p(h.x1, h.y0, h.d0); val bl = p(h.x0, h.y0, h.d1); val br = p(h.x1, h.y0, h.d1)
            val tfl = p(h.tx0, h.y1, h.td0); val tfr = p(h.tx1, h.y1, h.td0); val tbl = p(h.tx0, h.y1, h.td1); val tbr = p(h.tx1, h.y1, h.td1)
            val roof = h.front
            val crease = h.hip?.getOrNull(1) ?: darker(roof, .3f)
            // the core under the faces, in the roof's colour: no sky through a hip's steps
            plain(roof, arrayOf(fl, fr, tfr, tfl), floatArrayOf(0f, 1f, -1f), inset = .3f)
            plain(roof, arrayOf(br, bl, tbl, tbr), floatArrayOf(0f, 1f, 1f), inset = .3f)
            plain(roof, arrayOf(fl, tfl, tbl, bl), floatArrayOf(-1f, 1f, 0f), inset = .3f)
            plain(roof, arrayOf(fr, br, tbr, tfr), floatArrayOf(1f, 1f, 0f), inset = .3f)
            // the side faces on their quads (front eave, back eave, the top's back end, its front end)
            for (side in 0..1) {
                val left = side == 0
                val r = (if (left) h.left else h.right) ?: continue
                val pts = (if (left) h.leftP else h.rightP) ?: continue
                val c = if (left) arrayOf(fl, bl, tbl, tfl) else arrayOf(fr, br, tbr, tfr)
                mapped(r, pts, c, floatArrayOf(if (left) -1f else 1f, 1f, 0f))
            }
            // the front face: the art's rows, the trapezoid in its alpha, on the plane through the eave and the top's front
            val fp = h.frontPic
            if (fp != null) {
                piece(fp, arrayOf(p(h.x0, h.y1, h.td0), p(h.x1, h.y1, h.td0), fr, fl), floatArrayOf(0f, 1f, -1f))
                // the back: the front's picture when the roof is the same both ways
                if (abs((h.td0 - h.d0) - (h.d1 - h.td1)) < 1.5f && abs(h.d1 - h.td1) > .5f)
                    piece(fp, arrayOf(p(h.x1, h.y1, h.td1), p(h.x0, h.y1, h.td1), bl, br), floatArrayOf(0f, 1f, 1f))
                // the creases along the front hips, just under the face
                if (abs(h.tx0 - h.x0) > .5f || abs(h.td0 - h.d0) > .5f) {
                    plain(crease, arrayOf(p(h.x0 - 1.5f, h.y0, h.d0), p(h.tx0 - 1.5f, h.y1, h.td0), p(h.tx0 + 1.5f, h.y1, h.td0), p(h.x0 + 1.5f, h.y0, h.d0)), floatArrayOf(0f, 1f, -1f), inset = .1f)
                    plain(crease, arrayOf(p(h.x1 - 1.5f, h.y0, h.d0), p(h.tx1 - 1.5f, h.y1, h.td0), p(h.tx1 + 1.5f, h.y1, h.td0), p(h.x1 + 1.5f, h.y0, h.d0)), floatArrayOf(0f, 1f, -1f), inset = .1f)
                }
            }
            if (h.td1 > h.td0 + .5f && h.tx1 > h.tx0 + .5f) {
                val flat = h.flat
                if (flat != null) piece(flat, arrayOf(tbl, tbr, tfr, tfl), UP)
                else plain(roof, arrayOf(tbl, tbr, tfr, tfl), UP)
            }
        }

        private fun rail(r: ModelPart) {
            val len = sqrt((r.x1 - r.x0) * (r.x1 - r.x0) + (r.d1 - r.d0) * (r.d1 - r.d0)).coerceAtLeast(.01f)
            // across the rail in plan
            val cx = -(r.d1 - r.d0) / len; val cd = (r.x1 - r.x0) / len
            val w = r.railW / 2f
            val bar = r.front
            // the glass panel under the handrail (screen-doored by its alpha)
            if (r.side >= 0 && r.top > 0) {
                val h = r.railH - r.railW
                val c = arrayOf(p(r.x0, r.y0 + h, r.d0), p(r.x1, r.y1 + h, r.d1), p(r.x1, r.y1, r.d1), p(r.x0, r.y0, r.d0))
                // its texture repeats every 4 pixels: (u, v) in units of that
                val lu = len / 4f; val lv = h / 4f
                poly(Key.Glass(r.side and 0xFFFFFF, r.top), c, floatArrayOf(0f, 0f, lu, 0f, lu, lv, 0f, lv), floatArrayOf(-cx, 0f, -cd))
            }
            // the handrail: a bar railW square along the line, railH over it
            fun bar(x0: Float, y0: Float, d0: Float, x1: Float, y1: Float, d1: Float, lo: Float, hi: Float) {
                val a0 = p(x0 - cx * w, y0 + hi, d0 - cd * w); val a1 = p(x1 - cx * w, y1 + hi, d1 - cd * w)
                val b0 = p(x0 + cx * w, y0 + hi, d0 + cd * w); val b1 = p(x1 + cx * w, y1 + hi, d1 + cd * w)
                val a0l = p(a0[0], y0 + lo, a0[2]); val a1l = p(a1[0], y1 + lo, a1[2]); val b0l = p(b0[0], y0 + lo, b0[2]); val b1l = p(b1[0], y1 + lo, b1[2])
                plain(bar, arrayOf(a0, a1, a1l, a0l), floatArrayOf(-cx, 0f, -cd))
                plain(bar, arrayOf(b1, b0, b0l, b1l), floatArrayOf(cx, 0f, cd))
                plain(bar, arrayOf(a0, b0, b1, a1), UP)
                plain(bar, arrayOf(a0l, a1l, b1l, b0l), DOWN)
                plain(bar, arrayOf(a0, a0l, b0l, b0), null)
                plain(bar, arrayOf(a1, b1, b1l, a1l), null)
            }
            bar(r.x0, r.y0, r.d0, r.x1, r.y1, r.d1, r.railH - r.railW, r.railH)
            val n = if (r.posts > 0f) max(1, kotlin.math.round(len / r.posts).toInt()) else 1
            for (i in 0..n) {
                val f = i.toFloat() / n
                val x = r.x0 + (r.x1 - r.x0) * f; val y = r.y0 + (r.y1 - r.y0) * f; val d = r.d0 + (r.d1 - r.d0) * f
                // a post: railW square, from the line up under the handrail (along the line by its own width)
                val ux = (r.x1 - r.x0) / len * w; val ud = (r.d1 - r.d0) / len * w
                bar(x - ux, y, d - ud, x + ux, y, d + ud, 0f, r.railH - r.railW)
            }
        }

        private fun decal(dc: ModelPart) {
            val r = dc.tt ?: return
            val y = dc.y0 + .2f
            piece(r, arrayOf(p(dc.x0, y, dc.d1), p(dc.x1, y, dc.d1), p(dc.x1, y, dc.d0), p(dc.x0, y, dc.d0)), UP)
        }

        private fun slope(s: ModelPart) {
            val th = s.thick
            val top = arrayOf(p(s.x0, s.y1, s.d1), p(s.x1, s.y1, s.d1), p(s.x1, s.y0, s.d0), p(s.x0, s.y0, s.d0))
            val tt = s.tt
            if (tt != null) piece(tt, top, UP) else plain(s.top.takeIf { it >= 0 } ?: s.side, top, UP)
            if (th <= .01f) return
            plain(s.side, arrayOf(p(s.x0, s.y1 - th, s.d1), p(s.x0, s.y0 - th, s.d0), p(s.x1, s.y0 - th, s.d0), p(s.x1, s.y1 - th, s.d1)), DOWN)
            val ft = s.ft
            val front = arrayOf(p(s.x0, s.y0, s.d0), p(s.x1, s.y0, s.d0), p(s.x1, s.y0 - th, s.d0), p(s.x0, s.y0 - th, s.d0))
            if (ft != null) piece(ft, front, FRONT) else plain(s.front, front, FRONT)
            plain(s.side, arrayOf(p(s.x1, s.y1, s.d1), p(s.x0, s.y1, s.d1), p(s.x0, s.y1 - th, s.d1), p(s.x1, s.y1 - th, s.d1)), BACK)
            plain(s.side, arrayOf(p(s.x0, s.y0, s.d0), p(s.x0, s.y1, s.d1), p(s.x0, s.y1 - th, s.d1), p(s.x0, s.y0 - th, s.d0)), LEFT)
            plain(s.side, arrayOf(p(s.x1, s.y1, s.d1), p(s.x1, s.y0, s.d0), p(s.x1, s.y0 - th, s.d0), p(s.x1, s.y1 - th, s.d1)), RIGHT)
        }

        private fun prism(q: ModelPart) {
            val pl = q.plan ?: return
            val n = pl.size / 2
            for (i in 0 until n) {
                if (q.hidden and (1 shl i) != 0) continue
                val j = (i + 1) % n
                val ax = pl[2 * i]; val ad = pl[2 * i + 1]; val bx = pl[2 * j]; val bd = pl[2 * j + 1]
                val len = sqrt((bx - ax) * (bx - ax) + (bd - ad) * (bd - ad)).coerceAtLeast(.001f)
                val hint = floatArrayOf((bd - ad) / len, 0f, -(bx - ax) / len)
                val c = arrayOf(p(ax, q.y1, ad), p(bx, q.y1, bd), p(bx, q.y0, bd), p(ax, q.y0, ad))
                val ft = if (i == 0) q.ft else null
                if (ft != null) piece(ft, c, hint)
                else plain(if (abs(bd - ad) / len > .5f) q.side else q.front, c, hint)
            }
            if (q.top < 0 && q.tt == null) return
            // its top: the plan's polygon (cut into triangles: it may be concave), its picture over the plan's bounds
            val tris = earClip(pl)
            val tt = q.tt
            val k = if (tt != null) Key.Atlas else Key.Plain(q.top and 0xFFFFFF)
            for (t in tris) {
                val c = Array(3) { m -> p(pl[2 * t[m]], q.y1, pl[2 * t[m] + 1]) }
                val uv = FloatArray(6)
                if (tt != null) for (m in 0..2) {
                    val x = c[m][0]; val d = c[m][2]
                    uv[m * 2] = (tt[0] + (x - q.x0) / (q.x1 - q.x0).coerceAtLeast(.01f) * tt[2]) / aw
                    uv[m * 2 + 1] = (tt[1] + (q.d1 - d) / (q.d1 - q.d0).coerceAtLeast(.01f) * tt[3]) / ah
                }
                poly(k, c, uv, UP)
            }
        }

        private fun cyl(cy: ModelPart) {
            val arc = cy.arc ?: return
            val ft = cy.ft
            val n = if (ft != null) ft[2] else 24
            val cx = arc[0]; val cd = arc[1]; val ra = arc[2]; val rb = arc[3]
            val a0 = arc[4]; val a1 = arc[5]
            fun at(j: Int, a: Float, b: Float): FloatArray {
                val t = Math.toRadians((a0 + (a1 - a0) * j / n).toDouble())
                return floatArrayOf(cx + a * sin(t).toFloat(), cd - b * cos(t).toFloat())
            }
            fun strip(a: Float, b: Float, inward: Boolean, key: Key, rect: IntArray?) {
                val gp = group(key)
                val base = gp.nv
                for (j in 0..n) {
                    val q = at(j, a, b)
                    // the ellipse's outward normal there
                    var nx = (q[0] - cx) / (a * a); var nd = (q[1] - cd) / (b * b)
                    val l = sqrt(nx * nx + nd * nd).coerceAtLeast(1e-6f); nx /= l; nd /= l
                    if (inward) { nx = -nx; nd = -nd }
                    val q2 = at(min(j + 1, n), a, b); val q1 = at(max(j - 1, 0), a, b)
                    var tx = q2[0] - q1[0]; var td = q2[1] - q1[1]
                    val tl = sqrt(tx * tx + td * td).coerceAtLeast(1e-6f); tx /= tl; td /= tl
                    val u = if (rect != null) (rect[0] + rect[2] * j.toFloat() / n) / aw else 0f
                    val v0 = if (rect != null) rect[1] / ah else 0f
                    val v1 = if (rect != null) (rect[1] + rect[3]) / ah else 0f
                    val x = q[0] + nx * eps; val d = q[1] + nd * eps
                    gp.vert(x, cy.y1, d, u, v0, nx, 0f, nd, tx, 0f, td)
                    gp.vert(x, cy.y0, d, u, v1, nx, 0f, nd, tx, 0f, td)
                }
                for (j in 0 until n) {
                    val a2 = base + j * 2
                    gp.tri(a2, a2 + 2, a2 + 3); gp.tri(a2, a2 + 3, a2 + 1)
                }
            }
            strip(ra, rb, false, if (ft != null) Key.Atlas else Key.Plain(cy.front and 0xFFFFFF), ft)
            if (cy.card) return
            val ring = !arc[6].isNaN()
            if (ring) strip(arc[6], arc[7], true, Key.Plain(cy.front and 0xFFFFFF), null)
            if (cy.top < 0) return
            val top = Key.Plain(cy.top and 0xFFFFFF)
            for (j in 0 until n) {
                val o0 = at(j, ra, rb); val o1 = at(j + 1, ra, rb)
                if (ring) {
                    val i0 = at(j, arc[6], arc[7]); val i1 = at(j + 1, arc[6], arc[7])
                    poly(top, arrayOf(p(o0[0], cy.y1, o0[1]), p(o1[0], cy.y1, o1[1]), p(i1[0], cy.y1, i1[1]), p(i0[0], cy.y1, i0[1])), FloatArray(8), UP)
                } else poly(top, arrayOf(p(o0[0], cy.y1, o0[1]), p(o1[0], cy.y1, o1[1]), p(cx, cy.y1, cd)), FloatArray(6), UP)
            }
        }

        private fun wall(wl: ModelPart) {
            val len = sqrt((wl.x1 - wl.x0) * (wl.x1 - wl.x0) + (wl.d1 - wl.d0) * (wl.d1 - wl.d0)).coerceAtLeast(.001f)
            val hint = floatArrayOf((wl.d1 - wl.d0) / len, 0f, -(wl.x1 - wl.x0) / len)
            val c = arrayOf(p(wl.x0, wl.y1, wl.d0), p(wl.x1, wl.y1, wl.d1), p(wl.x1, wl.y0, wl.d1), p(wl.x0, wl.y0, wl.d0))
            val ft = wl.ft
            if (ft != null) piece(ft, c, hint) else plain(wl.front, c, hint)
            plain(wl.front, c, hint, inset = .2f)
        }
    }

    private val UP = floatArrayOf(0f, 1f, 0f)
    private val DOWN = floatArrayOf(0f, -1f, 0f)
    private val FRONT = floatArrayOf(0f, 0f, -1f)
    private val BACK = floatArrayOf(0f, 0f, 1f)
    private val LEFT = floatArrayOf(-1f, 0f, 0f)
    private val RIGHT = floatArrayOf(1f, 0f, 0f)

    private fun darker(rgb: Int, by: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * (1f - by)).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** A simple polygon's triangles (corner indices), by ear clipping; [pl] its corners (x, d pairs) in either winding. */
    fun earClip(pl: FloatArray): List<IntArray> {
        val n = pl.size / 2
        if (n < 3) return emptyList()
        var area = 0f
        for (i in 0 until n) { val j = (i + 1) % n; area += pl[2 * i] * pl[2 * j + 1] - pl[2 * j] * pl[2 * i + 1] }
        val idx = (0 until n).toMutableList()
        val out = ArrayList<IntArray>()
        fun cross(a: Int, b: Int, c: Int): Float = (pl[2 * b] - pl[2 * a]) * (pl[2 * c + 1] - pl[2 * a + 1]) - (pl[2 * b + 1] - pl[2 * a + 1]) * (pl[2 * c] - pl[2 * a])
        fun inside(pq: Int, a: Int, b: Int, c: Int): Boolean {
            val s = if (area > 0) 1f else -1f
            return s * cross(a, b, pq) >= 0f && s * cross(b, c, pq) >= 0f && s * cross(c, a, pq) >= 0f
        }
        var guard = 0
        while (idx.size > 3 && guard++ < 1000) {
            var cut = false
            for (i in idx.indices) {
                val a = idx[(i + idx.size - 1) % idx.size]; val b = idx[i]; val c = idx[(i + 1) % idx.size]
                val cr = cross(a, b, c)
                if ((area > 0 && cr <= 0f) || (area < 0 && cr >= 0f)) continue
                if (idx.any { it != a && it != b && it != c && inside(it, a, b, c) }) continue
                out += intArrayOf(a, b, c); idx.removeAt(i); cut = true; break
            }
            if (!cut) break
        }
        if (idx.size >= 3) for (i in 1 until idx.size - 1) out += intArrayOf(idx[0], idx[i], idx[i + 1])
        return out
    }

    // ------------------------------------------------------------------ the night: which windows are lit

    /**
     * The building's windows' light at night as the material's mask (atlas-sized; red: lit, blue: a lit picture that
     * keeps its own colours, green: glass), from its glow picture: each pane of a front or a gable's face on its own
     * hours (as the classic's modelPanes lights them, pane by pane), each side, prism face or rounded wall whose windows
     * light (`sg`) on the part's, and whatever else glows in the picture (a hotel's sign letters) on a lamp's hours.
     */
    class Night(art: ModelArt, private val seed: Int, private val textures: StreetTextures, private val linear: Boolean = true) {
        private val w = art.atlas.width
        private val h = art.atlas.height
        private val glow: IntArray? = art.glow?.takeIf { it.width == w && it.height == h }?.let { StreetTextures.argb(it) }
        private val kind = when (art.shape.lights) { "shop" -> Dusk.SHOP; "lamp" -> Dusk.LAMP; else -> Dusk.HOME }
        /** Each light: its seed and its kind. The last is the building's signs. */
        private val seeds = ArrayList<Int>()
        private val kinds = ArrayList<Int>()
        /** Which light each atlas pixel belongs to (-1: none). */
        private val owner = IntArray(w * h) { -1 }
        private val glassG = ByteArray(w * h)
        /**
         * The pixels that light in their own colours (as the classic overlays its glow picture): whatever glows cool or
         * white rather than a room's warm lamplight (a sign's letters, a spotlight's lens), on a lamp's hours, whatever
         * pane they stand in. Their colour (linear when the pictures are sRGB), or -1.
         */
        private val own = IntArray(w * h) { -1 }
        /** The glow picture's brightness, stretched so its brightest is full. */
        private val lum: IntArray = glow?.let { gp ->
            val l = IntArray(gp.size) { val c = gp[it]; (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100 }
            val top = (l.maxOrNull() ?: 255).coerceAtLeast(1)
            IntArray(l.size) { (l[it] * 255 / top).coerceIn(0, 255) }
        } ?: IntArray(0)
        private var lit = BooleanArray(0)
        private val maskPx = IntArray(w * h)
        /** The mask (made on the engine's thread the first time it is asked for: [Night] itself is made on a worker). */
        val mask: Texture? by lazy(LazyThreadSafetyMode.NONE) { if (glow == null) null else textures.make(IntArray(w * h), w, h, linear = true) }

        init {
            if (glow != null) {
                val parts = art.shape.parts
                fun region(r: IntArray, x: Int, y: Int, rw: Int, rh: Int, light: Int, glass: Boolean) {
                    for (yy in r[1] + y until min(r[1] + y + rh, h)) for (xx in r[0] + x until min(r[0] + x + rw, w)) {
                        if (xx < 0 || yy < 0) continue
                        val i = yy * w + xx
                        if (glass) glassG[i] = PANE_GLASS.toByte()
                        if ((glow[i] ushr 24) > 8) owner[i] = light
                    }
                }
                fun light(s: Int, k: Int = kind): Int { seeds += s; kinds += k; return seeds.size - 1 }
                for (i in parts.indices) {
                    val pt = parts[i]
                    val si = seed + i * 7
                    // whole pieces first (sides, strips, prisms), panes over them
                    val whole = when (pt.type) {
                        ModelPart.BOX -> if (pt.sideGlow) pt.st else null
                        ModelPart.PRISM -> if (pt.panes.isNotEmpty()) pt.ft else null
                        ModelPart.CYL, ModelPart.WALL -> if (pt.sideGlow) pt.ft else null
                        else -> null
                    }
                    if (whole != null) region(whole, 0, 0, whole[2], whole[3], light(si * 17 + 5), false)
                }
                for (i in parts.indices) {
                    val pt = parts[i]
                    val si = seed + i * 7
                    val (r, pseed) = when (pt.type) {
                        ModelPart.BOX -> (pt.ft ?: continue) to si + pt.x0.toInt()
                        ModelPart.GABLE -> (pt.face ?: continue) to si + 77
                        else -> continue
                    }
                    for ((j, pn) in pt.panes.withIndex()) {
                        val square = pn.size < 5 || pn[4] > .5f
                        region(r, pn[0].toInt(), pn[1].toInt(), kotlin.math.ceil(pn[2]).toInt(), kotlin.math.ceil(pn[3]).toInt(), light(pseed * 31 + j), square)
                    }
                }
                val signs = light(seed * 13 + 3, Dusk.LAMP)
                fun lin(ch: Int): Int = if (!linear) ch else (Math.pow(((ch / 255.0) + .055) / 1.055, 2.4) * 255.0).toInt().coerceIn(0, 255)
                for (i in owner.indices) {
                    val gp = glow[i]
                    if ((gp ushr 24) <= 8) continue
                    val r = (gp shr 16) and 255; val b = gp and 255
                    if (b > r * .7f) {
                        owner[i] = signs
                        val a = (gp ushr 24)
                        own[i] = (lin(r) * a / 255 shl 16) or (lin((gp shr 8) and 255) * a / 255 shl 8) or (lin(b) * a / 255)
                    } else if (owner[i] < 0) owner[i] = signs
                }
                lit = BooleanArray(seeds.size)
            }
        }

        fun update(hour: Float) {
            val g = glow ?: return
            val m = mask ?: return
            var changed = false
            val now = BooleanArray(seeds.size) { Dusk.lightsOn(seeds[it], hour, kinds[it]) > .5f }
            for (i in now.indices) if (now[i] != lit[i]) { changed = true; break }
            if (!changed && maskPx[0] != 0) return
            lit = now
            val signs = seeds.size - 1
            for (i in maskPx.indices) {
                val o = owner[i]
                val on = o >= 0 && now[o]
                // (as bright as the glow picture is there: its frames and bars stay darker than its panes)
                val oc = own[i]
                if (oc >= 0) { maskPx[i] = if (on) (128 shl 24) or oc else (255 shl 24); continue }
                val red = if (on) (g[i] ushr 24) * lum[i] / 255 else 0
                val blue = if (on && o == signs) 255 else 0
                maskPx[i] = (255 shl 24) or (red shl 16) or ((glassG[i].toInt() and 255) shl 8) or blue
            }
            textures.update(m, maskPx)
        }
    }

    // ------------------------------------------------------------------ into Filament

    private const val STRIDE = 36

    private fun pack(g: Mesher, pl: ModelPlace, night: Night, linear: Boolean): Ready {
        val groups = g.groups.values.filter { it.ni > 0 }
        val nv = groups.sumOf { it.nv }
        val ni = groups.sumOf { it.ni }
        val vb = ByteBuffer.allocateDirect(max(1, nv) * STRIDE).order(ByteOrder.nativeOrder())
        val ib = ByteBuffer.allocateDirect(max(1, ni) * 4).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        val k = pl.k
        val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val body = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val ranges = ArrayList<IntArray>()
        var base = 0; var io = 0
        for (gp in groups) {
            val yard = gp.key is Key.Pic
            for (i in 0 until gp.nv) {
                val o = i * 11
                val x = gp.v[o]; val y = gp.v[o + 1]; val d = gp.v[o + 2]
                val wx = pl.ox + pl.ax * x * k - pl.nx * d * k
                val wy = y * k
                val wz = pl.oz + pl.az * x * k - pl.nz * d * k
                // the frame: normal and tangent turned into the world (x along the street, d away from it)
                fun wx(a: Float, c: Float) = pl.ax * a - pl.nx * c
                fun wz(a: Float, c: Float) = pl.az * a - pl.nz * c
                val nx = wx(gp.v[o + 5], gp.v[o + 7]); val ny = gp.v[o + 6]; val nz = wz(gp.v[o + 5], gp.v[o + 7])
                var tx = wx(gp.v[o + 8], gp.v[o + 10]); var ty = gp.v[o + 9]; var tz = wz(gp.v[o + 8], gp.v[o + 10])
                val dot = tx * nx + ty * ny + tz * nz
                tx -= dot * nx; ty -= dot * ny; tz -= dot * nz
                var tl = sqrt(tx * tx + ty * ty + tz * tz)
                if (tl < 1e-5f) {
                    // (a tangent along the normal: any direction across it)
                    if (abs(ny) < .9f) { tx = 0f; ty = 1f; tz = 0f } else { tx = 1f; ty = 0f; tz = 0f }
                    val d2 = tx * nx + ty * ny + tz * nz
                    tx -= d2 * nx; ty -= d2 * ny; tz -= d2 * nz
                    tl = sqrt(tx * tx + ty * ty + tz * tz)
                }
                tx /= tl; ty /= tl; tz /= tl
                val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
                MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
                vb.putFloat(wx).putFloat(wy).putFloat(wz).putFloat(gp.v[o + 3]).putFloat(gp.v[o + 4])
                vb.putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
                bounds[0] = min(bounds[0], wx); bounds[1] = min(bounds[1], wy); bounds[2] = min(bounds[2], wz)
                bounds[3] = max(bounds[3], wx); bounds[4] = max(bounds[4], wy); bounds[5] = max(bounds[5], wz)
                if (!yard) {
                    body[0] = min(body[0], wx); body[1] = min(body[1], wy); body[2] = min(body[2], wz)
                    body[3] = max(body[3], wx); body[4] = max(body[4], wy); body[5] = max(body[5], wz)
                }
            }
            for (i in 0 until gp.ni) ib.putInt(base + gp.idx[i])
            ranges += intArrayOf(io, gp.ni)
            io += gp.ni; base += gp.nv
        }
        vb.flip(); ib.flip()
        // (the groups' own arrays go: they are packed, and only their keys and counts are wanted from here)
        for (gp in groups) { gp.v = FloatArray(0); gp.idx = IntArray(0) }
        return Ready(g.atlas, groups, vb, ib, nv, ni, ranges, bounds, body, night, linear)
    }

    /** Makes a [prepare]d model Filament's (the engine's thread): its buffers, pictures, materials and renderables. */
    fun finish(engine: Engine, visibleMat: Material, proxyMat: Material?, textures: StreetTextures, r: Ready): ModelMesh {
        val groups = r.groups; val nv = r.nv; val ni = r.ni; val vb = r.vb; val ib = r.ib; val ranges = r.ranges
        val bounds = r.bounds; val body = r.body; val night = r.night; val linear = r.linear
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(max(1, nv))
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .build(engine)
        vertices.setBufferAt(engine, 0, vb)
        val indices = IndexBuffer.Builder().indexCount(max(1, ni)).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        indices.setBuffer(engine, ib)
        val bbox = Box((bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f, (bounds[2] + bounds[5]) / 2f,
            (bounds[3] - bounds[0]) / 2f + .1f, (bounds[4] - bounds[1]) / 2f + .1f, (bounds[5] - bounds[2]) / 2f + .1f)
        fun lin(c: Float): Float = if (!linear) c else if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
        val atlas = textures.of(r.atlas)
        // the clocks' hands: a picture each, painted again when the minute changes
        val clocks = groups.mapNotNull { (it.key as? Key.Clock)?.c }.distinct()
        val clockTex = clocks.associateWith { c -> val nc = com.lifemax.rpg.feature.habits.domain.ClockHands.size(c[3]); textures.make(IntArray(nc * nc), nc, nc) }
        var clockMinute = Int.MIN_VALUE
        fun paintClocks(hour: Float) {
            val minute = kotlin.math.floor(hour * 60f).toInt()
            if (minute == clockMinute) return
            clockMinute = minute
            for ((c, t) in clockTex) {
                val nc = com.lifemax.rpg.feature.habits.domain.ClockHands.size(c[3])
                val px = IntArray(nc * nc)
                com.lifemax.rpg.feature.habits.domain.ClockHands.paint(px, nc, minute / 60f, c[3], c[4], c[5], c[6], c[7])
                textures.update(t, px)
            }
        }
        paintClocks(12f)
        // what each group wears, and how it is sampled
        fun albedoOf(key: Key): Pair<Texture, TextureSampler> = when (key) {
            is Key.Atlas, is Key.Card -> atlas to crisp
            is Key.Plain -> textures.white to nearest
            is Key.Pic -> textures.of(key.img) to crisp
            is Key.Glass -> glassTexture(textures, key.alpha) to repeat
            is Key.Clock -> clockTex.getValue(key.c) to nearest
        }
        val mis = Array(groups.size) { j ->
            val key = groups[j].key
            visibleMat.createInstance().also { mi ->
                val (tex, smp) = albedoOf(key)
                mi.setParameter("albedo", tex, smp)
                mi.setParameter("mask", if (key is Key.Atlas || key is Key.Card) night.mask ?: textures.black else if (key is Key.Glass) glassMask(textures) else textures.black, if (key is Key.Glass) repeat else nearest)
                mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
                val rgb = when (key) { is Key.Plain -> key.rgb; is Key.Glass -> key.rgb; else -> 0xFFFFFF }
                mi.setParameter("tint", lin(((rgb shr 16) and 255) / 255f), lin(((rgb shr 8) and 255) / 255f), lin((rgb and 255) / 255f), 1f)
                mi.setParameter("glow", 0f, 0f, 0f, 0f)
                mi.setParameter("glass", 1f, 1f, 1f, 1f)
                mi.setParameter("fade", 1f)
                mi.setParameter("cell", 2f)
                mi.setParameter("oneSided", if (key is Key.Card) 1f else 0f)
            }
        }
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(groups.size).boundingBox(bbox).culling(false).castShadows(false).receiveShadows(true).priority(4)
        for (j in groups.indices) rb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, mis[j])
        rb.build(engine, e)
        var proxy = 0
        var pmis: Array<MaterialInstance> = emptyArray()
        if (proxyMat != null) {
            pmis = Array(groups.size) { j ->
                proxyMat.createInstance().also { mi ->
                    val (tex, smp) = albedoOf(groups[j].key)
                    mi.setParameter("albedo", tex, smp); mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
                }
            }
            proxy = EntityManager.get().create()
            val pb = RenderableManager.Builder(groups.size).boundingBox(bbox).castShadows(true).receiveShadows(false)
            for (j in groups.indices) pb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, pmis[j])
            pb.build(engine, proxy)
        }
        val hourly: ((Float) -> Unit)? = when {
            clocks.isNotEmpty() -> { h: Float -> night.update(h); paintClocks(h) }
            night.mask != null -> night::update
            else -> null
        }
        return ModelMesh(e, proxy, mis, pmis, vertices, indices, hourly, bounds, body)
    }

    /** A 4x4 screen door at [alpha] (0..255) of white: a glass panel's pixels, every one of a model's pixels a cell. */
    private fun glassTexture(textures: StreetTextures, alpha: Int): Texture {
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val px = IntArray(16) { i -> if ((bayer[i] + .5f) / 16f < alpha / 255f) -1 else 0x00FFFFFF }
        return textures.make(px, 4, 4, linear = true)
    }

    /** The glass panel's mask: all of it glass (smooth, mirroring the sky). */
    private fun glassMask(textures: StreetTextures): Texture = textures.make(IntArray(16) { (255 shl 24) or (160 shl 8) }, 4, 4, linear = true)
}
