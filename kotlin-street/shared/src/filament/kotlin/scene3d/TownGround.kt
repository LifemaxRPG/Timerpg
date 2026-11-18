package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.PropRules
import com.lifemax.rpg.feature.habits.domain.PropSpot
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The game's own pictures the town's ground and greenery wear (the LimeZu tiles and sprites under public/assets), opened
 * by the platform: [image] takes a path such as "grass/grass_9.png" and gives null when it is missing (the ground then
 * paints its own).
 */
class TownArt(val image: (String) -> ImageBitmap?)

/** One batch for a [PropLayer]: [mesh] at each of [at] (world x, z, along x, z, out x, z, turn). */
internal class GroundPiece(
    val name: String, val mesh: PropMesh, val at: List<FloatArray>, val casts: Boolean, val tiled: Boolean = false,
    /**
     * Where each of [at] belongs (the same length), so the town can split the batch into chunks it culls by distance and
     * cuts in the street view (a row's furniture with the rest of its row side); null: one batch for the whole town.
     */
    val anchors: List<Anchor>? = null,
    /** The same thing in full 3D for close by (a tree), standing in the same places; [mesh] is then the far one. */
    val near: PropMesh? = null,
)

/** What a placed thing belongs to: the row (its pavement, at [x] along it), or nothing but its place (the town's edge). */
internal class Anchor(val row: com.lifemax.rpg.feature.habits.domain.Row?, val x: Float)

/**
 * The whole town's ground, in the pack's crisp pixels (one texel [TEXEL] street units, sampled nearest), as batches for a
 * [PropLayer] ([pieces]):
 *  - grass everywhere that is not water: one seamless picture 2048 texels a side made from the grass tiles (their tufts
 *    and pebbles scattered, the base broken into a few shades by a slow wrapped noise), so it does not tile visibly;
 *  - every road as a strip along each of its segments (pavement, kerb, carriageway with the centre's dashes, kerb,
 *    pavement: 178 units, the building lines' distance), cut back where it meets a junction patch;
 *  - a patch at every node and polyline corner, painted by which of its four sides a road leaves (a crossing, a tee, a
 *    bend, a dead end's turning head), kerbs round its pavement corners;
 *  - zebras across every arm of a crossing or a tee, at the mouth;
 *  - the plaza paved, the river a water plane below the grass with stone banks, the bridges' decks' edges and parapets;
 *  - greenery: the town edge's trees, bushes and flowers (graph.props) and the plaza's, as crossed cards (two upright
 *    sprites at right angles: they read from every side); the plaza's fountain in 3D;
 *  - the rows' furniture: lamps (3D, their lanterns lit at night), benches (the props' bench), trees (cards);
 *  - the street props by [PropRules] along every row ([props]).
 */
internal class TownGround(private val town: Town, private val art: TownArt) {
    /** The ground's extent (world x0, z0, x1, z1): everything in the town and a margin of fields round it. */
    val bounds: FloatArray
    private val tile = town.scale
    private val water = HashSet<Long>()
    private val i0: Int; private val j0: Int; private val i1: Int; private val j1: Int

    init {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        fun take(x: Float, z: Float) { x0 = min(x0, x); z0 = min(z0, z); x1 = max(x1, x); z1 = max(z1, z) }
        for (n in town.nodes) take(n.x, n.z)
        for (p in town.props) take(p.x, p.z)
        for (r in town.rows) for (p in r.pts) take(p.x, p.z)
        for (poly in town.water) for (p in poly) take(p.x, p.z)
        i0 = floor((x0 - MARGIN) / tile).toInt(); j0 = floor((z0 - MARGIN) / tile).toInt()
        i1 = ceil((x1 + MARGIN) / tile).toInt(); j1 = ceil((z1 + MARGIN) / tile).toInt()
        bounds = floatArrayOf(i0 * tile, j0 * tile, i1 * tile, j1 * tile)
        for (poly in town.water) {
            val ax = poly.minOf { it.x }; val bx = poly.maxOf { it.x }; val az = poly.minOf { it.z }; val bz = poly.maxOf { it.z }
            for (i in floor(ax / tile + .01f).toInt() until ceil(bx / tile - .01f).toInt())
                for (j in floor(az / tile + .01f).toInt() until ceil(bz / tile - .01f).toInt()) water += key(i, j)
        }
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
    fun isWater(i: Int, j: Int): Boolean = key(i, j) in water

    /** Every batch of the ground, greenery and furniture. */
    fun pieces(): List<GroundPiece> {
        val out = ArrayList<GroundPiece>()
        out += grass()
        out += roads()
        out += patches()
        out += zebras()
        plaza()?.let { out += it }
        landmarkGrounds()?.let { out += it }
        out += waterPieces()
        out += bridges()
        out += greenery()
        out += furniture()
        return out
    }

    // ------------------------------------------------------------------ a flat mesh in world coordinates

    private class Flat(val img: PixImg) {
        val pos = ArrayList<Float>(); val uv = ArrayList<Float>(); val nrm = ArrayList<Float>(); val tan = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val bmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val bmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        /** A quad: corners [p] (4 x xyz), their picture coordinates [t] (4 x uv, in pictures: 1 is the picture's width), normal [n], tangent [tg]. */
        fun quad(p: FloatArray, t: FloatArray, n: FloatArray, tg: FloatArray) {
            val base = pos.size / 3
            for (k in 0..3) {
                for (c in 0..2) { pos += p[k * 3 + c]; nrm += n[c]; tan += tg[c]; bmin[c] = min(bmin[c], p[k * 3 + c]); bmax[c] = max(bmax[c], p[k * 3 + c]) }
                uv += t[k * 2]; uv += t[k * 2 + 1]
            }
            idx += base; idx += base + 1; idx += base + 2; idx += base; idx += base + 2; idx += base + 3
        }

        /** A level quad from ([x0], [z0]) to ([x1], [z1]) at [y]; its picture [u0], [v0] at (x0, z0) to [u1], [v1] at (x1, z1). */
        fun level(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, u0: Float, v0: Float, u1: Float, v1: Float) =
            quad(floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), UP, EAST)

        val empty: Boolean get() = idx.isEmpty()

        fun mesh(): PropMesh = PropMesh(
            pos.toFloatArray(), uv.toFloatArray(), nrm.toFloatArray(), tan.toFloatArray(), idx.toIntArray(),
            img.w, img.h, img.px, if (img.glows) img.mask else null, floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]),
        )
    }

    private val identity = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
    private fun piece(name: String, f: Flat, casts: Boolean = false, tiled: Boolean = true) = GroundPiece(name, f.mesh(), identity, casts, tiled)

    // ------------------------------------------------------------------ pictures

    /** A picture of the pack's as texels (null when missing). */
    private fun pic(path: String): PixImg? = art.image(path)?.let { im ->
        val px = StreetTextures.argb(im)
        PixImg(im.width, im.height).also { px.copyInto(it.px) }
    }

    /** A slow noise that wraps every [n] texels: a lattice of [cells] a side, smoothly blended. */
    private fun wrapNoise(n: Int, cells: Int, seed: Int): (Int, Int) -> Float {
        val r = Paint.Rnd(seed)
        val g = FloatArray(cells * cells) { r.next() }
        return { x, y ->
            val fx = x.toFloat() * cells / n; val fy = y.toFloat() * cells / n
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
            val tx = fx - ix; val ty = fy - iy
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            fun at(i: Int, j: Int) = g[(j.mod(cells)) * cells + i.mod(cells)]
            val a = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * sx
            val b = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * sx
            a + (b - a) * sy
        }
    }

    /**
     * The grass: [GRASS] texels a side, seamless. The grass tiles' base colour broken into four shades by two wrapped
     * noises (a slow one for patches across tens of metres, a quicker one for clumps), dithered at their edges a texel at
     * a time; the tiles' own tufts, pebbles and leaves stamped over it at random, a few more tiny flowers.
     */
    private fun grassPicture(): PixImg {
        val n = GRASS
        val im = PixImg(n, n)
        val shades = intArrayOf(Paint.c(0x3F8E50), Paint.c(0x439353), Paint.c(0x479757), Paint.c(0x4C9C5A), Paint.c(0x51A15D))
        val slow = wrapNoise(n, 6, 31)
        val mid = wrapNoise(n, 22, 32)
        val fine = wrapNoise(n, 96, 33)
        val dither = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        for (y in 0 until n) for (x in 0 until n) {
            val v = slow(x, y) * .55f + mid(x, y) * .3f + fine(x, y) * .15f
            val t = ((v - .3f) / .4f * .6f + .2f).coerceIn(0f, .999f) * (shades.size - 1)
            val k = t.toInt()
            val frac = t - k
            val d = (dither[(y and 3) * 4 + (x and 3)] + .5f) / 16f
            im.px[y * n + x] = shades[(if (frac > d) k + 1 else k).coerceAtMost(shades.size - 1)]
        }
        // the tiles' details: every pixel of theirs that is not the base, stamped where it falls, the tile turned
        val base = Paint.c(0x479757)
        val stamps = listOf("grass/grass_9.png", "grass/grass_11.png", "grass/grass_12.png", "grassPatch/grass_patch_8.png", "grassPatch/grass_patch_9.png")
            .mapNotNull { pic(it) }
        val details = stamps.map { s ->
            val pts = ArrayList<IntArray>()
            for (y in 0 until s.h) for (x in 0 until s.w) { val c = s.px[y * s.w + x]; if ((c ushr 24) > 200 && (c or 0xFF000000.toInt()) != base) pts += intArrayOf(x, y, c) }
            pts
        }.filter { it.isNotEmpty() }
        val r = Paint.Rnd(77)
        if (details.isNotEmpty()) repeat(n * n / 2200) {
            val d = details[r.int(details.size)]
            val ox = r.int(n); val oy = r.int(n)
            val turn = r.int(4)
            // only the tiles' small things (a stamp's pixels within 6 of one picked at random): a tuft, a stone, a leaf
            val c0 = d[r.int(d.size)]
            for (p in d) {
                if (abs(p[0] - c0[0]) > 5 || abs(p[1] - c0[1]) > 5) continue
                val lx = p[0] - c0[0]; val ly = p[1] - c0[1]
                val (tx, ty) = when (turn) { 0 -> lx to ly; 1 -> -ly to lx; 2 -> -lx to -ly; else -> ly to -lx }
                im.px[(oy + ty).mod(n) * n + (ox + tx).mod(n)] = p[2]
            }
        }
        // a scatter of tiny flowers and darker tufts
        val petals = intArrayOf(Paint.c(0xF2E9A0), Paint.c(0xFFFFFF), Paint.c(0xE8A0C8), Paint.c(0xF7D650))
        val tuft = Paint.c(0x37854E)
        repeat(n * n / 260) {
            val x = r.int(n); val y = r.int(n)
            im.px[y * n + x] = tuft; im.px[((y + 1) % n) * n + x] = tuft
            im.px[y * n + (x + 1) % n] = tuft
        }
        repeat(n * n / 2600) {
            val x = r.int(n); val y = r.int(n)
            im.px[y * n + x] = petals[r.int(petals.size)]
        }
        return im
    }

    /** A tile of the pack's as a repeating source (its own colour if missing). */
    private class Source(val img: PixImg?, val fallback: Int) {
        fun at(x: Int, y: Int): Int = img?.let { it.px[y.mod(it.h) * it.w + x.mod(it.w)] } ?: fallback
    }

    private val asphalt by lazy { Source(pic("asphalt/asphalt_24.png"), Paint.c(0x534F52)) }
    private val sidewalk by lazy { Source(pic("sidewalk/sidewalk_25.png"), Paint.c(0xDAD4CB)) }
    private val plazaTile by lazy { Source(pic("sidewalk/sidewalk_27.png"), Paint.c(0xD5CEC6)) }

    private val kerbHi = Paint.c(0xEFEAE2)
    private val kerb = Paint.c(0xB4AEA8)
    private val gutter = Paint.c(0x45414A)
    private val paint = Paint.c(0xE9E6DA)
    private val wallFoot = Paint.c(0x9A958F)

    /**
     * A road's cross-section as a picture, [ROAD_W] texels across (the 178 units between the building lines) and
     * [ROAD_L] along (one dash and gap of the centre line): pavement, kerb, carriageway, kerb, pavement.
     */
    private fun roadPicture(): PixImg {
        val w = ROAD_W; val h = ROAD_L
        val im = PixImg(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = when (val k = band(x, w)) {
                Band.PAVE -> if (x == 0 || x == w - 1) wallFoot else sidewalk.at(if (x < w / 2) x else w - 1 - x, y)
                Band.KERB_TOP -> kerbHi
                Band.KERB -> kerb
                Band.GUTTER -> gutter
                Band.ROAD -> asphalt.at(x, y).let { a ->
                    // the centre line's dashes: two texels wide, half the length on
                    val mid = w / 2
                    if ((x == mid - 1 || x == mid) && y < h / 2) paint else a
                }
                else -> k.hashCode()
            }
            im[x, y] = c
        }
        return im
    }

    private enum class Band { PAVE, KERB_TOP, KERB, GUTTER, ROAD }

    /** Which band texel [x] of [w] across is in (symmetric about the middle). */
    private fun band(x: Int, w: Int): Band {
        val d = min(x, w - 1 - x)
        return when {
            d < PAVE_T -> Band.PAVE
            d < PAVE_T + 2 -> Band.KERB_TOP
            d < PAVE_T + KERB_T -> Band.KERB
            d < PAVE_T + KERB_T + 1 -> Band.GUTTER
            else -> Band.ROAD
        }
    }

    /** Every road, segment by segment, cut back [HALF] at both ends (each end has its junction patch). */
    private fun roads(): List<GroundPiece> {
        val f = Flat(roadPicture())
        val along = ROAD_L * (2 * HALF / ROAD_W)
        for (road in town.roads) for (s in 0 until road.pts.size - 1) {
            val a = road.pts[s]; val b = road.pts[s + 1]
            val dx = b.x - a.x; val dz = b.z - a.z
            val l = hypot(dx, dz)
            if (l <= 2 * HALF + .5f) continue
            val ux = dx / l; val uz = dz / l
            // across: from the left side (seen walking a to b) to the right
            val px = uz; val pz = -ux
            val s0 = HALF; val s1 = l - HALF
            fun pt(s: Float, o: Float) = floatArrayOf(a.x + ux * s + px * o, LIFT_ROAD, a.z + uz * s + pz * o)
            val p0 = pt(s0, -HALF); val p1 = pt(s0, HALF); val p2 = pt(s1, HALF); val p3 = pt(s1, -HALF)
            val v0 = s0 / along; val v1 = s1 / along
            f.quad(floatArrayOf(*p0, *p1, *p2, *p3), floatArrayOf(0f, v0, 1f, v0, 1f, v1, 0f, v1), UP, floatArrayOf(px, 0f, pz))
        }
        return listOf(piece("roads", f))
    }

    /** Every place a patch goes (each node and polyline corner) and which of its sides (N, E, S, W bits) a road leaves by. */
    private fun junctions(): Map<TownPoint, Int> {
        val out = LinkedHashMap<TownPoint, Int>()
        fun side(dx: Float, dz: Float): Int = if (abs(dx) >= abs(dz)) (if (dx > 0) E else W) else (if (dz > 0) S else N)
        for (road in town.roads) for (s in 0 until road.pts.size - 1) {
            val a = road.pts[s]; val b = road.pts[s + 1]
            val ka = TownPoint(a.x, a.z); val kb = TownPoint(b.x, b.z)
            out[ka] = (out[ka] ?: 0) or side(b.x - a.x, b.z - a.z)
            out[kb] = (out[kb] ?: 0) or side(a.x - b.x, a.z - b.z)
        }
        return out
    }

    private val patchPictures = HashMap<Int, PixImg>()

    /**
     * A junction patch's picture for the sides [arms] (bits [N] [E] [S] [W]): rows run south (+z), columns east (+x).
     * The carriageway fills the middle and runs out through every side a road leaves by; the rest is pavement, a kerb
     * round it, the corners rounded a little.
     */
    private fun patchPicture(arms: Int): PixImg = patchPictures.getOrPut(arms) {
        val n = ROAD_W
        val road0 = PAVE_T + KERB_T + 1; val road1 = n - 1 - road0
        val isRoad = Array(n) { BooleanArray(n) }
        for (y in 0 until n) for (x in 0 until n) {
            val inX = x in road0..road1; val inY = y in road0..road1
            isRoad[y][x] = (inX && inY) || (inX && ((arms and N != 0 && y < road0) || (arms and S != 0 && y > road1))) ||
                (inY && ((arms and W != 0 && x < road0) || (arms and E != 0 && x > road1)))
        }
        // the corners of the carriageway between two arms rounded: a pavement corner square's inner corner cut back
        val rr = 10
        for ((cxs, cys) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
            val armX = if (cxs < 0) W else E; val armY = if (cys < 0) N else S
            if (arms and armX == 0 || arms and armY == 0) continue
            // the corner of the pavement square nearest the middle
            val px = if (cxs < 0) road0 - 1 else road1 + 1; val py = if (cys < 0) road0 - 1 else road1 + 1
            val ox = px - cxs * rr; val oy = py - cys * rr
            for (y in 0 until n) for (x in 0 until n) {
                val inCorner = (if (cxs < 0) x <= px else x >= px) && (if (cys < 0) y <= py else y >= py)
                val nearX = if (cxs < 0) x > ox else x < ox; val nearY = if (cys < 0) y > oy else y < oy
                if (inCorner && nearX && nearY && hypot((x - ox).toFloat(), (y - oy).toFloat()) > rr) isRoad[y][x] = true
            }
        }
        // each pavement texel's distance (in texels, chessboard) to the carriageway, for the kerb's bands
        val dist = Array(n) { IntArray(n) { 99 } }
        for (y in 0 until n) for (x in 0 until n) if (isRoad[y][x]) dist[y][x] = 0
        repeat(2) {
            for (y in 0 until n) for (x in 0 until n) {
                var d = dist[y][x]
                if (x > 0) d = min(d, dist[y][x - 1] + 1); if (y > 0) d = min(d, dist[y - 1][x] + 1)
                if (x > 0 && y > 0) d = min(d, dist[y - 1][x - 1] + 1); if (x < n - 1 && y > 0) d = min(d, dist[y - 1][x + 1] + 1)
                dist[y][x] = d
            }
            for (y in n - 1 downTo 0) for (x in n - 1 downTo 0) {
                var d = dist[y][x]
                if (x < n - 1) d = min(d, dist[y][x + 1] + 1); if (y < n - 1) d = min(d, dist[y + 1][x] + 1)
                if (x < n - 1 && y < n - 1) d = min(d, dist[y + 1][x + 1] + 1); if (x > 0 && y < n - 1) d = min(d, dist[y + 1][x - 1] + 1)
                dist[y][x] = d
            }
        }
        val im = PixImg(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val d = dist[y][x]
            im[x, y] = when {
                d == 0 -> asphalt.at(x, y)
                d == 1 -> gutter
                d <= KERB_T - 1 -> kerb
                d <= KERB_T + 1 -> kerbHi
                else -> sidewalk.at(x, y)
            }
            // the pavement's outer edge where no road leaves: the building line's shade
            if (d > 0 && ((y == 0 && arms and N == 0) || (y == n - 1 && arms and S == 0) || (x == 0 && arms and W == 0) || (x == n - 1 && arms and E == 0))) im[x, y] = wallFoot
        }
        im
    }

    private fun patches(): List<GroundPiece> {
        val byArms = HashMap<Int, Flat>()
        for ((p, arms) in junctions()) {
            val f = byArms.getOrPut(arms) { Flat(patchPicture(arms)) }
            f.level(p.x - HALF, p.z - HALF, p.x + HALF, p.z + HALF, LIFT_ROAD, 0f, 0f, 1f, 1f)
        }
        return byArms.map { (arms, f) -> piece("junction$arms", f, tiled = false) }
    }

    /** The zebra's stripes along the road over the carriageway: white bars with gaps (masked out), the road showing through. */
    private fun zebraPicture(): PixImg {
        val w = ROAD_W - 2 * (PAVE_T + KERB_T + 1) - 4
        val h = ZEBRA_T
        val im = PixImg(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val on = (x % 11) < 6
            im[x, y] = if (!on) 0 else if (y == 0 || y == h - 1 || (x % 11) == 0) Paint.c(0xCFCBC0) else paint
        }
        return im
    }

    private fun zebras(): List<GroundPiece> {
        val f = Flat(zebraPicture())
        val halfW = f.img.w * UNIT / 2f
        val d0 = HALF + 4f; val d1 = d0 + ZEBRA_T * UNIT
        for (node in town.nodes) {
            if (node.arms.size < 3) continue
            for (arm in node.arms) {
                val hx = arm.hx; val hz = arm.hz
                // across the arm: its right as seen going out along it
                val px = -hz; val pz = hx
                fun pt(d: Float, o: Float) = floatArrayOf(node.x + hx * d + px * o, LIFT_MARK, node.z + hz * d + pz * o)
                f.quad(floatArrayOf(*pt(d1, -halfW), *pt(d1, halfW), *pt(d0, halfW), *pt(d0, -halfW)), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), UP, floatArrayOf(px, 0f, pz))
            }
        }
        return listOf(piece("zebras", f, tiled = false))
    }

    private fun plaza(): GroundPiece? {
        val r = town.plaza ?: return null
        val n = 64
        val im = PixImg(n, n)
        for (y in 0 until n) for (x in 0 until n) im[x, y] = plazaTile.at(x, y)
        val f = Flat(im)
        val s = n * UNIT
        f.level(r[0], r[1], r[2], r[3], LIFT_PLAZA, r[0] / s, r[1] / s, r[2] / s, r[3] / s)
        return piece("plaza", f)
    }

    /**
     * The landmarks' grounds (the yards cover only the homes' lots): a paved path from the pavement to the front door (the
     * front wall's middle) and an apron along the front wall, in the plaza's stone, so the clock tower, the post office,
     * the hotel and the police do not stand on bare lawn.
     */
    private fun landmarkGrounds(): GroundPiece? {
        val n = 64
        val im = PixImg(n, n)
        for (y in 0 until n) for (x in 0 until n) im[x, y] = plazaTile.at(x, y)
        val f = Flat(im)
        val s = n * UNIT
        for (row in town.rows) for (b in row.buildings) {
            if (b.kind != com.lifemax.rpg.feature.habits.domain.PlacementKind.LANDMARK || b.setback < 12f) continue
            val place = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b)
            fun rect(lx0: Float, lz0: Float, lx1: Float, lz1: Float) {
                val c = listOf(place.world(lx0, lz0), place.world(lx1, lz0), place.world(lx1, lz1), place.world(lx0, lz1))
                val p = FloatArray(12); val t = FloatArray(8)
                for ((k, q) in c.withIndex()) { p[k * 3] = q.x; p[k * 3 + 1] = LIFT_PLAZA; p[k * 3 + 2] = q.z; t[k * 2] = q.x / s; t[k * 2 + 1] = q.z / s }
                // (its corners wind as the plaza's level quads do, seen from above)
                val ax = c[1].x - c[0].x; val az = c[1].z - c[0].z; val bx = c[3].x - c[0].x; val bz = c[3].z - c[0].z
                if (az * bx - ax * bz < 0f) f.quad(p, t, UP, EAST)
                else f.quad(floatArrayOf(p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8], p[3], p[4], p[5]),
                    floatArrayOf(t[0], t[1], t[6], t[7], t[4], t[5], t[2], t[3]), UP, EAST)
            }
            val hw = b.width / 2f
            val apron = min(26f, b.setback * .4f)
            // the path from the pavement's back to the apron, then the apron along the front
            rect(-22f, -1f, 22f, b.setback - apron + 1f)
            rect(-hw * .92f, b.setback - apron, hw * .92f, b.setback + 2f)
        }
        return if (f.empty) null else piece("landmark-grounds", f)
    }

    /** Grass over every tile of the ground that is not water: one quad a run of tiles along each row of the grid. */
    private fun grass(): GroundPiece {
        val f = Flat(grassPicture())
        val s = GRASS * UNIT
        for (j in j0 until j1) {
            var i = i0
            while (i < i1) {
                if (isWater(i, j)) { i++; continue }
                var k = i
                while (k < i1 && !isWater(k, j)) k++
                val x0 = i * tile; val x1 = k * tile; val z0 = j * tile; val z1 = (j + 1) * tile
                f.level(x0, z0, x1, z1, 0f, x0 / s, z0 / s, x1 / s, z1 / s)
                i = k
            }
        }
        // the fields on to the horizon round it (the haze takes them)
        val far = 60000f
        val (bx0, bz0, bx1, bz1) = bounds.toList()
        f.level(bx0 - far, bz0 - far, bx1 + far, bz0, 0f, (bx0 - far) / s, (bz0 - far) / s, (bx1 + far) / s, bz0 / s)
        f.level(bx0 - far, bz1, bx1 + far, bz1 + far, 0f, (bx0 - far) / s, bz1 / s, (bx1 + far) / s, (bz1 + far) / s)
        f.level(bx0 - far, bz0, bx0, bz1, 0f, (bx0 - far) / s, bz0 / s, bx0 / s, bz1 / s)
        f.level(bx1, bz0, bx1 + far, bz1, 0f, bx1 / s, bz0 / s, (bx1 + far) / s, bz1 / s)
        return piece("grass", f)
    }

    /** The river: its water down at [WATER_Y] (a run of tiles a quad), and stone banks wherever water meets land. */
    private fun waterPieces(): List<GroundPiece> {
        if (water.isEmpty()) return emptyList()
        val n = 64
        val wim = PixImg(n, n)
        val deep = Paint.c(0x369AB0); val mid = Paint.c(0x3CA3B2); val dark = Paint.c(0x3690AE); val glint = Paint.c(0x9FE0E2)
        val nz = wrapNoise(n, 4, 91)
        val r = Paint.Rnd(5)
        for (y in 0 until n) for (x in 0 until n) { val v = nz(x, y); wim[x, y] = if (v < .35f) dark else if (v < .7f) deep else mid }
        // ripples: short light dashes, the pack's way
        repeat(26) {
            val x = r.int(n); val y = r.int(n); val l = 2 + r.int(4)
            for (k in 0 until l) wim[(x + k) % n, y] = if (k == 0 || k == l - 1) mid else glint
        }
        val wf = Flat(wim)
        val s = n * UNIT
        val keys = water.map { (it shr 32).toInt() to it.toInt() }.sortedWith(compareBy({ it.second }, { it.first }))
        for ((j, row) in keys.groupBy { it.second }) {
            val is_ = row.map { it.first }.sorted()
            var a = 0
            while (a < is_.size) {
                var b = a
                while (b + 1 < is_.size && is_[b + 1] == is_[b] + 1) b++
                val x0 = is_[a] * tile; val x1 = (is_[b] + 1) * tile; val z0 = j * tile; val z1 = (j + 1) * tile
                wf.level(x0, z0, x1, z1, WATER_Y, x0 / s, z0 / s, x1 / s, z1 / s)
                a = b + 1
            }
        }
        // the banks: stone in courses, a grass lip at the top
        val bh = ceil((-WATER_Y + 2f) / UNIT).toInt()
        val bim = PixImg(32, bh)
        val stone = intArrayOf(Paint.c(0x7A7466), Paint.c(0x8A8474), Paint.c(0x6B6558))
        for (y in 0 until bh) for (x in 0 until 32) {
            val course = y / 3
            val joint = y % 3 == 2 || (x + course * 5) % 8 == 0
            bim[x, y] = if (y == 0) Paint.c(0x37854E) else if (y == 1) Paint.c(0x5A5446) else if (joint) Paint.c(0x4F4A40) else stone[(x / 8 + course) % 3]
        }
        val bf = Flat(bim)
        val top = 0f; val bottom = WATER_Y - 2f
        val bw = 32 * UNIT
        for (k in water) {
            val i = (k shr 32).toInt(); val j = k.toInt()
            val x0 = i * tile; val x1 = (i + 1) * tile; val z0 = j * tile; val z1 = (j + 1) * tile
            // each side of this water tile that faces land: a wall from the grass down past the water, facing the water
            if (!isWater(i, j - 1)) bf.quad(floatArrayOf(x0, top, z0, x1, top, z0, x1, bottom, z0, x0, bottom, z0), floatArrayOf(x0 / bw, 0f, x1 / bw, 0f, x1 / bw, 1f, x0 / bw, 1f), SOUTH, EAST)
            if (!isWater(i, j + 1)) bf.quad(floatArrayOf(x1, top, z1, x0, top, z1, x0, bottom, z1, x1, bottom, z1), floatArrayOf(x1 / bw, 0f, x0 / bw, 0f, x0 / bw, 1f, x1 / bw, 1f), NORTH, WEST)
            if (!isWater(i - 1, j)) bf.quad(floatArrayOf(x0, top, z1, x0, top, z0, x0, bottom, z0, x0, bottom, z1), floatArrayOf(z1 / bw, 0f, z0 / bw, 0f, z0 / bw, 1f, z1 / bw, 1f), EAST, NORTH)
            if (!isWater(i + 1, j)) bf.quad(floatArrayOf(x1, top, z0, x1, top, z1, x1, bottom, z1, x1, bottom, z0), floatArrayOf(z0 / bw, 0f, z1 / bw, 0f, z1 / bw, 1f, z0 / bw, 1f), WEST, SOUTH)
        }
        return listOf(piece("water", wf), piece("banks", bf))
    }

    /** The bridges: the road runs over as their deck; its edges (stone, down towards the water) and a parapet each side. */
    private fun bridges(): List<GroundPiece> {
        if (town.bridges.isEmpty()) return emptyList()
        val m = MeshBuilder()
        val stone = Ramp(Paint.c(0x4F4A40), Paint.c(0x6B6558), Paint.c(0x8A8474), Paint.c(0x9C9686), Paint.c(0xB4AE9E))
        for (b in town.bridges) {
            val r = b.rect
            // the deck along x (horizontal) or z; its two long edges
            val h = 7f; val t = 3f; val drop = 5f
            if (b.horizontal) {
                for (z in listOf(r[1], r[3])) {
                    val inward = if (z == r[1]) 1f else -1f
                    m.panelBox(r[0], -drop, min(z, z + inward * t), r[2], LIFT_ROAD + h, max(z, z + inward * t), stone, 3, edges = true)
                }
            } else {
                for (x in listOf(r[0], r[2])) {
                    val inward = if (x == r[0]) 1f else -1f
                    m.panelBox(min(x, x + inward * t), -drop, r[1], max(x, x + inward * t), LIFT_ROAD + h, r[3], stone, 3, edges = true)
                }
            }
        }
        return listOf(GroundPiece("bridges", m.build(), identity, casts = true))
    }

    // ------------------------------------------------------------------ greenery and furniture

    /** A crossed card: [img] standing upright twice at right angles, its foot at the origin, [scale] times its pixels. */
    private fun crossCard(img: PixImg, scale: Float): PropMesh {
        val m = MeshBuilder()
        val w = img.w * TEXEL * scale; val h = img.h * TEXEL * scale
        // the empty rows under the picture's foot go under the ground
        var empty = 0
        while (empty < img.h - 1 && (0 until img.w).none { (img.px[(img.h - 1 - empty) * img.w + it] ushr 24) > 0 }) empty++
        val foot = empty * TEXEL * scale
        for (k in 0..1) {
            val (ax, az) = if (k == 0) 1f to 0f else 0f to 1f
            val a = floatArrayOf(-ax * w / 2, h - foot, -az * w / 2); val b = floatArrayOf(ax * w / 2, h - foot, az * w / 2)
            val c = floatArrayOf(ax * w / 2, -foot, az * w / 2); val d = floatArrayOf(-ax * w / 2, -foot, -az * w / 2)
            m.quad(a, b, c, d, img)
        }
        // a canopy seen from above: a round crown of the sprite's own leaves, level at two thirds of its height (from
        // above the two upright cards alone read as a plus sign)
        canopy(img)?.let { top ->
            val r = w * .42f
            val y = (h - foot) * .66f
            m.quad(floatArrayOf(-r, y, -r), floatArrayOf(r, y, -r), floatArrayOf(r, y, r), floatArrayOf(-r, y, r), top)
        }
        return m.build()
    }

    private val canopies = java.util.IdentityHashMap<PixImg, PixImg?>()

    /**
     * A tree sprite's crown from above: a disc as wide as the sprite whose texels are the sprite's own leaves (its crown's
     * rows, the opaque ones), lit from the top left and darker at the rim, its edge ragged; null for a sprite with no crown.
     */
    private fun canopy(img: PixImg): PixImg? = canopies.getOrPut(img) {
        val n = img.w
        // the crown's rows: the opaque texels of the top 60%
        val leaves = ArrayList<Int>()
        for (y in 0 until (img.h * .6f).toInt()) for (x in 0 until img.w) { val c = img.px[y * img.w + x]; if ((c ushr 24) >= 200) leaves += c }
        if (leaves.size < 8) return@getOrPut null
        val out = PixImg(n, n)
        val rnd = Paint.Rnd(n * 31 + leaves.size)
        val half = n / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + .5f - half) / half; val dy = (y + .5f - half) / half
            val d = sqrt(dx * dx + dy * dy)
            val rim = .86f + .14f * rnd.next()
            if (d > rim) continue
            val c = leaves[(rnd.next() * leaves.size).toInt().coerceIn(0, leaves.size - 1)]
            // light from the top left, darker towards the rim
            val shade = (1.08f - .32f * d - .14f * (dx + dy) / 2f).coerceIn(.55f, 1.2f)
            fun ch(sh: Int) = (((c shr sh) and 255) * shade).toInt().coerceIn(0, 255)
            out[x, y] = (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        out
    }

    /** The yard kit's 3D tree (one of its round broadleaves, or a conifer) about as big as [img] at [scale]. */
    private fun tree3d(img: PixImg, scale: Float, v: Int): PropMesh? {
        var empty = 0
        while (empty < img.h - 1 && (0 until img.w).none { (img.px[(img.h - 1 - empty) * img.w + it] ushr 24) > 0 }) empty++
        val h = (img.h - empty) * TEXEL * scale
        val variant = TREE_VARIANTS[v.mod(TREE_VARIANTS.size)]
        // the kit's broadleaf stands about 2.6 times its half width tall, a conifer 3.6, a poplar 3.9
        val s = h / (when (variant) { 5, 6 -> 3.6f; 8 -> 3.9f; else -> 2.6f })
        val w = 2f * (s + .3f)
        return YardKit.model("tree", variant, w, w).mesh
    }

    /** A flat card on the ground (flowers seen from above), [scale] times its pixels. */
    private fun groundCard(img: PixImg, scale: Float): PropMesh {
        val m = MeshBuilder()
        val w = img.w * TEXEL * scale; val h = img.h * TEXEL * scale
        val y = LIFT_DECAL
        m.quad(floatArrayOf(-w / 2, y, -h / 2), floatArrayOf(w / 2, y, -h / 2), floatArrayOf(w / 2, y, h / 2), floatArrayOf(-w / 2, y, h / 2), img)
        return m.build()
    }

    private val sprites = HashMap<String, PixImg?>()
    private fun sprite(path: String): PixImg? = sprites.getOrPut(path) { pic(path) }

    private fun variants(kind: String): List<String> = when (kind) {
        "tree" -> listOf(1, 2, 3, 4, 5, 6, 11, 12).map { "tree/tree_$it.png" }
        "bush" -> listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16).map { "bush/bush_$it.png" }
        "flowerBush" -> (1..6).map { "flowerBush/flowerBush_$it.png" }
        "flowers" -> (1..5).map { "flowers/flowers_$it.png" }
        else -> emptyList()
    }.filter { sprite(it) != null }

    /** Where greenery stands: by sprite and size class, each a place and a turn. */
    private class Plant(val path: String, val scale: Float, val flat: Boolean)

    private fun greenery(): List<GroundPiece> {
        val groups = LinkedHashMap<Plant, MutableList<FloatArray>>()
        val anchors = HashMap<Plant, MutableList<Anchor>>()
        val keys = HashMap<String, Plant>()
        fun plant(path: String, scale: Float, flat: Boolean) = keys.getOrPut("$path@$scale@$flat") { Plant(path, scale, flat) }
        fun put(kind: String, x: Float, z: Float, look: Float, edge: Boolean) {
            val vs = variants(kind)
            if (vs.isEmpty()) return
            val v = vs[((look * 997f).toInt()).mod(vs.size)]
            val big = kind == "tree" && edge
            // the forest's trees in two sizes (the street's own at the street's scale)
            val scale = if (big) (if (((look * 7919f).toInt() and 1) == 0) 1.25f else 1.6f) else 1f
            val turn = (look * 6151f) % (PI.toFloat() / 2f)
            // the forest's trees stand on the map's grid: shaken off it so they read as grown, not planted
            val jx = if (edge) ((look * 3571f) % 1f - .5f) * 30f else 0f
            val jz = if (edge) ((look * 2287f) % 1f - .5f) * 30f else 0f
            val pl = plant(v, scale, kind == "flowers")
            groups.getOrPut(pl) { ArrayList() } += floatArrayOf(x + jx, z + jz, cos(turn), sin(turn), -sin(turn), cos(turn), 0f)
            anchors.getOrPut(pl) { ArrayList() } += Anchor(null, 0f)
        }
        // (a flower bush is the pack's planter box: the props' planter in 3D, not a card)
        val planters = ArrayList<Pair<Int, FloatArray>>()
        val edgeProps = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()).also { it.addAll(town.props) }
        for (p in town.props + town.plazaProps) {
            if (p.kind == "flowerBush") {
                val turn = if (((p.look * 7919f).toInt() and 1) == 0) 0f else PI.toFloat() / 2f
                planters += ((p.look * 997f).toInt().mod(PropKind.PLANTER.variants)) to floatArrayOf(p.x, p.z, 1f, 0f, 0f, 1f, turn)
                continue
            }
            if (p.kind == "bench" || p.kind.startsWith("fountain")) continue
            put(p.kind, p.x, p.z, p.look, edge = p in edgeProps)
        }
        // the forest's outer edge: the map's forest stops in a straight line; trees thin out past it into the fields instead
        for ((x, z, look) in feather()) put("tree", x, z, look, edge = true)
        val out = ArrayList<GroundPiece>()
        for ((pl, at) in groups) {
            val img = sprite(pl.path) ?: continue
            val isTree = pl.path.startsWith("tree/")
            val near = if (isTree) tree3d(img, pl.scale, variants("tree").indexOf(pl.path)) else null
            out += GroundPiece("green:${pl.path}@${pl.scale}", if (pl.flat) groundCard(img, pl.scale) else crossCard(img, pl.scale), at, casts = !pl.flat,
                anchors = anchors[pl], near = near)
        }
        for ((v, at) in planters.groupBy({ it.first }, { it.second })) out += GroundPiece("planters$v", PropKit.mesh(PropKind.PLANTER, v, seed = v * 5 + 2), at, casts = true)
        // the plaza's benches and its fountain
        val benches = town.plazaProps.filter { it.kind == "bench" }.map { floatArrayOf(it.x, it.z, 1f, 0f, 0f, 1f, 0f) }
        if (benches.isNotEmpty()) out += GroundPiece("plaza-benches", PropKit.mesh(PropKind.BENCH), benches, casts = true)
        val fountains = town.plazaProps.filter { it.kind.startsWith("fountain") }.map { floatArrayOf(it.x, it.z, 1f, 0f, 0f, 1f, 0f) }
        if (fountains.isNotEmpty()) out += GroundPiece("fountain", fountain(), fountains, casts = true)
        return out
    }

    /**
     * Trees past the forest's straight outer edge (the box round the map's edge trees): fewer the further out, on a shaken
     * grid, by a slow noise so they stand in clumps and fingers; each its place and a look (0..1).
     */
    private fun feather(): List<Triple<Float, Float, Float>> {
        val trees = town.props.filter { it.kind == "tree" }
        if (trees.size < 50) return emptyList()
        val fx0 = trees.minOf { it.x }; val fx1 = trees.maxOf { it.x }; val fz0 = trees.minOf { it.z }; val fz1 = trees.maxOf { it.z }
        val noise = wrapNoise(4096, 16, 913)
        val out = ArrayList<Triple<Float, Float, Float>>()
        val step = 52f
        var i = 0
        var x = fx0 - FEATHER
        while (x <= fx1 + FEATHER) {
            var z = fz0 - FEATHER
            while (z <= fz1 + FEATHER) {
                val dx = max(fx0 - x, x - fx1); val dz = max(fz0 - z, z - fz1)
                val d = max(dx, dz)
                if (d > 0f) {
                    val h = Ambient.hash(91, i, 17)
                    val n = noise((x / 3f).toInt(), (z / 3f).toInt())
                    val p = (1f - d / FEATHER).coerceIn(0f, 1f).let { it * it } * (.35f + .9f * n)
                    if (h < p) out += Triple(x + (Ambient.hash(91, i, 18) - .5f) * step * .8f, z + (Ambient.hash(91, i, 19) - .5f) * step * .8f, Ambient.hash(91, i, 20))
                }
                i++
                z += step
            }
            x += step
        }
        return out
    }

    /** The plaza's fountain: a stone basin of water, a column in it, a bowl on that, water in the bowl. */
    private fun fountain(): PropMesh {
        val m = MeshBuilder()
        val stone = Ramp(Paint.c(0x4D4D66), Paint.c(0x9C9686), Paint.c(0xB4AE9E), Paint.c(0xC8C2B2), Paint.c(0xDAD4C4))
        val water = Ramp(Paint.c(0x1F5F7A), Paint.c(0x3690AE), Paint.c(0x369AB0), Paint.c(0x3CA3B2), Paint.c(0x9FE0E2))
        val (sw, sh) = Paint.stripSize(30f, 7f)
        m.upright(0f, 0f, 0f, 7f, 30f, 30f, 16, Paint.strip(sw, sh, stone, 4), cap = null)
        m.upright(0f, 0f, 0f, 6f, 27f, 27f, 16, null, swatch = Paint.swatch(water.d), cap = Paint.cap(27f, water))
        val (cw, ch) = Paint.stripSize(3.5f, 20f)
        m.upright(0f, 0f, 6f, 26f, 3.5f, 3f, 8, Paint.strip(cw, ch, stone, 5), cap = null)
        val (bw, bh) = Paint.stripSize(12f, 4f)
        m.upright(0f, 0f, 24f, 28f, 8f, 12f, 12, Paint.strip(bw, bh, stone, 6), cap = Paint.cap(12f, water))
        m.upright(0f, 0f, 28f, 33f, 1.5f, .6f, 6, null, swatch = Paint.swatch(water.h))
        return m.build()
    }

    /** A street lamp: an iron post, a bracket and a lantern whose glass glows at night. */
    private fun lamp(): PropMesh {
        val m = MeshBuilder()
        val (pw, ph) = Paint.stripSize(1.4f, 58f)
        m.upright(0f, 0f, 0f, 58f, 1.6f, 1.1f, 8, Paint.strip(pw, ph, Paint.IRON, 2), cap = null)
        m.upright(0f, 0f, 0f, 5f, 2.6f, 2.2f, 8, null, swatch = Paint.swatch(Paint.IRON.d))
        // the bracket out over the pavement towards the road (+z) and the lantern hanging from it
        m.solidBox(-.6f, 57f, 0f, .6f, 58.4f, 9f, Paint.swatch(Paint.IRON.b))
        // (the glass glows warm and not blown out: the light it throws is the town's point lights')
        val glass = PixImg(4, 4).also { it.fill(Paint.c(0xFFC878)); it.glow(0, 0, 3, 3, amount = 120) }
        m.solidBox(-2.4f, 50f, 6.6f, 2.4f, 56f, 11.4f, glass)
        m.solidBox(-3f, 56f, 6f, 3f, 57.6f, 12f, Paint.swatch(Paint.IRON.d))
        m.solidBox(-1.8f, 48.8f, 7.2f, 1.8f, 50f, 10.8f, Paint.swatch(Paint.IRON.d))
        return m.build()
    }

    private fun furniture(): List<GroundPiece> {
        val lamps = ArrayList<FloatArray>(); val benches = ArrayList<FloatArray>()
        val lampAnchors = ArrayList<Anchor>(); val benchAnchors = ArrayList<Anchor>()
        val trees = HashMap<String, MutableList<FloatArray>>()
        val treeAnchors = HashMap<String, MutableList<Anchor>>()
        val seen = HashSet<Long>()
        for (row in town.rows) for (f in row.furniture) {
            // (a row's ends are the crossing roads' middles: what stands there moves back onto the corner's pavement)
            val lo = if (row.start.mouth) HALF - 20f else 10f
            val hi = if (row.end.mouth) row.length - HALF + 20f else row.length - 10f
            if (hi <= lo) continue
            val x = f.x.coerceIn(lo, hi)
            val out = when (f.kind) { FurnitureKind.LAMP -> Crossroads.PAVE - 3f; FurnitureKind.BENCH -> 9f; FurnitureKind.TREE -> Crossroads.PAVE - 9f }
            val p = row.pointAt(x, out / Crossroads.LANE)
            val k = ((p.x / 12f).roundToInt().toLong() shl 32) or ((p.z / 12f).roundToInt().toLong() and 0xffffffffL)
            if (!seen.add(k * 4 + f.kind.ordinal)) continue
            val n = row.normalAt(x)
            // the art's right as seen from the road, the way out to the road
            val rx = -n.z; val rz = n.x; val ox = -n.x; val oz = -n.z
            when (f.kind) {
                FurnitureKind.LAMP -> { lamps += floatArrayOf(p.x, p.z, rx, rz, ox, oz, 0f); lampAnchors += Anchor(row, x); lampSpots += floatArrayOf(p.x, p.z, ox, oz) }
                FurnitureKind.BENCH -> { benches += floatArrayOf(p.x, p.z, rx, rz, ox, oz, 0f); benchAnchors += Anchor(row, x) }
                FurnitureKind.TREE -> {
                    val vs = variants("tree").take(3).ifEmpty { variants("tree") }
                    if (vs.isEmpty()) continue
                    val v = vs[((f.look * 997f).toInt()).mod(vs.size)]
                    trees.getOrPut(v) { ArrayList() } += floatArrayOf(p.x, p.z, rx, rz, ox, oz, 0f)
                    treeAnchors.getOrPut(v) { ArrayList() } += Anchor(row, x)
                }
            }
        }
        val out = ArrayList<GroundPiece>()
        if (lamps.isNotEmpty()) out += GroundPiece("lamps", lamp(), lamps, casts = true, anchors = lampAnchors)
        if (benches.isNotEmpty()) out += GroundPiece("benches", PropKit.mesh(PropKind.BENCH), benches, casts = true, anchors = benchAnchors)
        for ((v, at) in trees) sprite(v)?.let {
            out += GroundPiece("street-trees:$v", crossCard(it, 1f), at, casts = true, anchors = treeAnchors[v], near = tree3d(it, 1f, variants("tree").indexOf(v)))
        }
        return out
    }

    /**
     * The rows' street lamps' lanterns (after [pieces]): world x, z of the post and its out direction (x, z), the lantern
     * hanging [LANTERN_OUT] out from the post at [LANTERN_Y] (the town lights the nearest at night).
     */
    val lampSpots = ArrayList<FloatArray>()

    /**
     * The street props along every row by [PropRules], stood in the world. A prop is built with its x to the right as
     * seen from the road and its z out to the road: on a row whose +x runs the other way (the buildings on its right),
     * its along is turned round and its turn mirrored, so it keeps its handedness (no mirrored signs) and faces as the
     * rules turned it.
     */
    fun props(): List<WorldProp> = anchoredProps().map { it.second }

    /**
     * [props] each with its row and place along it. The corner's street name signs: every row meeting a corner asks
     * for one, so one corner could get two to four; one sign stands for a crossing (the first asked), the rest are left
     * out.
     */
    fun anchoredProps(): List<Pair<Anchor, WorldProp>> {
        val out = ArrayList<Pair<Anchor, WorldProp>>()
        val signs = ArrayList<WorldProp>()
        for (row in town.rows) {
            val spots = runCatching { PropRules.forRow(town, row) }.getOrElse { emptyList() }
            for (sp in spots) {
                val w = PropRules.world(row, sp)
                val n = row.normalAt(sp.x)
                val rx = -n.z; val rz = n.x
                val flip = rx * w[2] + rz * w[3] < 0f
                val spot: PropSpot = if (flip) sp.copy(turn = -sp.turn) else sp
                val wp = WorldProp(spot, w[0], w[1], rx, rz, w[4], w[5])
                if (sp.kind == PropKind.STREET_SIGN) {
                    if (signs.any { hypot(it.x - wp.x, it.z - wp.z) < SIGN_APART }) continue
                    signs += wp
                }
                out += Anchor(row, sp.x) to wp
            }
        }
        return out
    }

    companion object {
        /** Street units per texel of the ground's pictures (the road's 216 texels across its 178 units). */
        const val UNIT = Crossroads.MOUTH / 216f
        const val ROAD_W = 216
        const val ROAD_L = 64
        /** The pavement's texels across (33 units) and the kerb's (5). */
        const val PAVE_T = 40
        const val KERB_T = 6
        const val ZEBRA_T = 20
        const val HALF = Crossroads.MOUTH / 2f
        const val GRASS = 2048
        /** Fields round the town (street units). */
        const val MARGIN = 2600f
        const val WATER_Y = -9f
        /** Each level of the ground a hair over the one under it (the depth buffer keeps them apart). */
        const val LIFT_PLAZA = .12f
        const val LIFT_ROAD = .25f
        const val LIFT_MARK = .4f
        const val LIFT_DECAL = .3f
        const val N = 1; const val E = 2; const val S = 4; const val W = 8
        /** How far past the forest's edge its trees thin out to none. */
        const val FEATHER = 1100f
        /** Street name signs nearer each other than this are one corner's (or one crossing's): one is kept. */
        const val SIGN_APART = 260f
        /** The kit's tree each of [variants]' sprites becomes close by: the round ones broadleaves, the tall ones poplars. */
        private val TREE_VARIANTS = intArrayOf(0, 1, 1, 3, 8, 8, 0, 3)
        /** Where a lamp's lantern hangs: out from its post and up (the lamp's mesh). */
        const val LANTERN_OUT = 9f
        const val LANTERN_Y = 53f
        private val UP = floatArrayOf(0f, 1f, 0f)
        private val EAST = floatArrayOf(1f, 0f, 0f)
        private val WEST = floatArrayOf(-1f, 0f, 0f)
        private val NORTH = floatArrayOf(0f, 0f, -1f)
        private val SOUTH = floatArrayOf(0f, 0f, 1f)
    }
}
