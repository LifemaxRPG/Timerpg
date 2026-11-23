package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The near terrain round the camera (WORLD_GEN.md phase W5a: the T0/T1 tiers of section 7, drawn in the pixel-art style
 * of section 8): the ground's height and its material at any point, and the chunked level-of-detail tree that streams it.
 * Engine-agnostic (no Filament): [com.lifemax.rpg.feature.habits.presentation.scene3d.WorldNear] draws what this plans.
 *
 * **The height** has three layers:
 *  1. [smooth] (cross-language: src/world/gen/near.ts computes the same doubles, tools/world-fixture.ts writes them for
 *     the test): the skeleton's bilinear cell heights ([WorldSkeleton.height]), W4's detail noise (the same as the far
 *     view's finest level), gentle rolling hills everywhere (a few metres over a few hundred), rocky outcrops where a slow
 *     roughness noise is high, and ridged mountains on high ground; all of it fading out at the shore.
 *  2. [raw] (cross-language too): the owner's terrain decision (section 14: smooth rolling ground, hard low-poly cliff faces
 *     only where steep): where [smooth]'s slope passes [CLIFF_FROM] the ground is terraced into ledges and risers a few
 *     metres tall ([cliff] says how much), so a steep face breaks into faceted rock with ledges.
 *  3. [height] (Kotlin only): the world's own vectors on it (the rivers' and roads' surroundings calmed and the rivers'
 *     beds carved a little) and the town's ground flattened, exactly as the far view ([WorldFarPlan.shapeRaw]) does.
 *
 * Every value is a function of the world position alone (vertex positions come from integer tile indices), so a vertex
 * shared by two chunks or two levels of detail has the same height in both and nothing depends on the camera.
 *
 * **The chunks** ([Node]): a quadtree whose roots are blocks of [ROOT_TILES] tiles (2,150.4 m: two atlas cells, eight
 * of the far view's finest quads), each node a grid of [QUADS] x [QUADS] quads, a quad 2.1 m at level 0 (a tile) and
 * twice as wide each level up (to 33.6 m at level 4). [select] picks the nodes round the camera: the roots within
 * [REACH_M] that lie wholly on the far view's finest level, split while the camera is nearer than [SPLIT] node sizes.
 * Each node drops a skirt from its four edges ([NodeData.skirt]) deep enough to cover any step to a coarser neighbour
 * or to the far view, so no crack shows; the far view hides its own quads where a root is drawn.
 */
class WorldTerrain(val sk: WorldSkeleton, val plan: WorldFarPlan, /** The town's own flat ground (m from the plan's origin: x0, z0, x1, z1) left to the town; null: none. */ val townGrass: DoubleArray? = null) {
    private val seed = sk.seed
    /** The ground's relief (the cross-language part: [WorldRelief]); the far view's finest level samples it too. */
    val relief = WorldRelief(sk)
    private val patch = WorldFbm(seed, "w5-patch", WorldSkeleton.W * 8, 2)
    private val tone = WorldNoise.hashStr("$seed|w5-tone")
    private val ox = plan.origin.x
    private val oz = plan.origin.z

    /** [WorldRelief.smooth], [WorldRelief.slope], [WorldRelief.cliff], [WorldRelief.raw]. */
    fun smooth(cx: Double, cz: Double): Double = relief.smooth(cx, cz)
    fun slope(cx: Double, cz: Double): Double = relief.slope(cx, cz)
    fun cliff(cx: Double, cz: Double): Double = relief.cliff(cx, cz)
    fun raw(cx: Double, cz: Double): Double = relief.raw(cx, cz)

    // ------------------------------------------------------------------ the height on the town's planet (Kotlin only)

    /** A world line near the camera (a river, road or rail line) in metres from the plan's origin; [half] its half width. */
    private class Seg(val x0: Double, val z0: Double, val x1: Double, val z1: Double, val half: Double, val kind: Int)
    private val segs = HashMap<Long, ArrayList<Seg>>()

    init {
        // the far view's lines (cut at the town's ground already), indexed by the chunk (537.6 m) they reach into
        for (l in plan.lines) {
            val half = nearHalf(l.kind, l.width.toDouble())
            val pts = l.pts
            for (k in 0 until pts.size / 2 - 1) {
                val s = Seg(pts[k * 2], pts[k * 2 + 1], pts[k * 2 + 2], pts[k * 2 + 3], half, l.kind)
                val m = half + CALM_M
                val i0 = floor((min(s.x0, s.x1) - m) / Planet.CHUNK_M).toInt(); val i1 = floor((max(s.x0, s.x1) + m) / Planet.CHUNK_M).toInt()
                val j0 = floor((min(s.z0, s.z1) - m) / Planet.CHUNK_M).toInt(); val j1 = floor((max(s.z0, s.z1) + m) / Planet.CHUNK_M).toInt()
                // (only what could ever be near: within the far view's finest level)
                if ((i1 - i0 + 1).toLong() * (j1 - j0 + 1) > 4096) continue
                for (j in j0..j1) for (i in i0..i1) segs.getOrPut(key(i, j)) { ArrayList() } += s
            }
        }
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

    /** The near terrain's half width of a far view line of [kind] (the far view draws them wider, to read from afar). */
    private fun nearHalf(kind: Int, far: Double): Double = when (kind) {
        WorldFarPlan.HIGHWAY -> HIGHWAY_HALF_M
        WorldFarPlan.COUNTY -> COUNTY_HALF_M
        WorldFarPlan.RAIL -> RAIL_HALF_M
        else -> far * RIVER_SHARE / 2.0
    }

    /** The nearest line of each kind to plan-local ([x], [z]): distance less its half width (negative: on it), into [out]. */
    private fun near(x: Double, z: Double, out: DoubleArray) {
        out.fill(Double.MAX_VALUE)
        val list = segs[key(floor(x / Planet.CHUNK_M).toInt(), floor(z / Planet.CHUNK_M).toInt())] ?: return
        for (s in list) {
            val dx = s.x1 - s.x0; val dz = s.z1 - s.z0
            val l2 = dx * dx + dz * dz
            val t = if (l2 < 1e-9) 0.0 else (((x - s.x0) * dx + (z - s.z0) * dz) / l2).coerceIn(0.0, 1.0)
            val d = hypot(x - (s.x0 + dx * t), z - (s.z0 + dz * t)) - s.half
            if (d < out[s.kind]) out[s.kind] = d
        }
    }

    /** Plan-local metres (from the far view's origin) of tile index ([gx], [gz]) (whole tiles from the planet's corner). */
    fun localX(gx: Long): Double = Planet.wrapDx(gx * Planet.TILE_M - ox)
    fun localZ(gz: Long): Double = gz * Planet.TILE_M - oz

    /**
     * Everything about one point: its height (m above the town's ground height, [WorldFarPlan.baseElev]: the y the scene
     * draws) and what the ground there is ([material], with the cliff, the lines' distances); [p] is reused.
     */
    class Point { var y = 0.0; var abs = 0.0; var cliff = 0.0; var river = 0.0; var road = 0.0; var county = 0.0; var rail = 0.0; var outside = 0.0 }

    private val lineD = ThreadLocal.withInitial { DoubleArray(4) }

    /** The point at tile index ([gx], [gz]) (vertex positions come only from these, so a shared vertex is shared exactly). */
    fun point(gx: Long, gz: Long, p: Point): Point = pointAt(gx * Planet.TILE_M / Planet.CELL_M, gz * Planet.TILE_M / Planet.CELL_M, localX(gx), localZ(gz), p)

    /** The point at ([cx], [cz]) in cells, which is ([lx], [lz]) m from the plan's origin. */
    fun pointAt(cx: Double, cz: Double, lx: Double, lz: Double, p: Point): Point {
        val base = sk.height(cx, cz)
        val h0 = relief.smooth(cx, cz)
        val c = relief.cliffOf(relief.slope(cx, cz))
        var h = if (c <= 0.0) h0 else h0 + (relief.terrace(h0, cx, cz) - h0) * c
        val d = lineD.get()
        near(lx, lz, d)
        // the rivers' and roads' surroundings calmed (the land's detail fading to the cells' own height towards them), the
        // rivers' beds carved
        val calmR = smoothstep(sat(d[WorldFarPlan.RIVER] / CALM_M))
        val calmD = .4 + .6 * smoothstep(sat(min(d[WorldFarPlan.HIGHWAY], min(d[WorldFarPlan.COUNTY], d[WorldFarPlan.RAIL])) / CALM_ROAD_M))
        h = base + (h - base) * min(calmR, calmD)
        val bed = 1.0 - smoothstep(sat((d[WorldFarPlan.RIVER] + BANK_M) / BANK_M))
        h -= RIVER_DEPTH_M * bed
        p.abs = h
        p.cliff = c * min(calmR, calmD)
        p.river = d[WorldFarPlan.RIVER]; p.road = d[WorldFarPlan.HIGHWAY]; p.county = d[WorldFarPlan.COUNTY]; p.rail = d[WorldFarPlan.RAIL]
        p.outside = plan.outside(lx, lz)
        p.y = plan.shapeRaw(lx, lz, h).toDouble()
        // (under the town's own grass the town draws the ground: the terrain kept below it and below its river's water)
        townGrass?.let { g -> if (lx >= g[0] && lx <= g[2] && lz >= g[1] && lz <= g[3]) p.y = -TOWN_UNDER_M }
        return p
    }

    /** The scene's height (m above [WorldFarPlan.baseElev]) at plan-local ([lx], [lz]) m. */
    fun heightLocal(lx: Double, lz: Double): Double = pointAt((ox + lx) / Planet.CELL_M, (oz + lz) / Planet.CELL_M, lx, lz, Point()).y

    // ------------------------------------------------------------------ materials

    /**
     * The ground's material at plan-local ([lx], [lz]) (cells [cx], [cz]) whose point is [p], on a quad whose own slope
     * is [quadSlope]: a layer of the ground's pictures ([GRASS]...). The biome's ground, fields in a patchwork, rock where
     * steep, scree under it, beaches, snow, the rivers' water and the roads' asphalt and gravel.
     */
    fun material(cx: Double, cz: Double, lx: Double, lz: Double, p: Point, quadSlope: Double): Int {
        // the lines first: they lie over everything
        if (p.river < 0) return WATER
        if (p.road < 0) return ASPHALT
        if (p.road < SHOULDER_M || p.county < 0 || p.rail < 0) return GRAVEL
        val i = floor(cx).toInt(); val j = floor(cz).toInt()
        val land = sk.land(i, j)
        val above = p.abs
        if (!land && above < 1.0) return SAND
        if (quadSlope > ROCK_SLOPE || (p.cliff > .8 && quadSlope > ROCK_SLOPE * .5)) return ROCK
        val b = sk.biome(i, j)
        val coast = (sk.flags(i, j) shr 3) and 3
        if (above < 3.5 && (b == 15 || coast == 1 || !land)) return SAND
        if (b == 15 && above < 8.0) return SAND
        // snow above the snowline (where the warmest month freezes), a ragged edge
        val line = if (sk.has("snowline")) sk.raw("snowline", Math.floorMod(i, WorldSkeleton.W) + j.coerceIn(0, WorldSkeleton.H - 1) * WorldSkeleton.W).toDouble() else 2700.0
        val pn = patch.at(cx, cz)
        if (b == 1 || b == 18 || above > line + (pn - .5) * 160.0) return if (quadSlope > ROCK_SLOPE * .8) ROCK else SNOW
        return ground(cx, cz, lx, lz, p, quadSlope, b, i, j, pn)
    }

    private fun ground(cx: Double, cz: Double, lx: Double, lz: Double, p: Point, quadSlope: Double, b: Int, i: Int, j: Int, pn0: Double): Int {
        if (p.cliff > .1 || quadSlope > ROCK_SLOPE * .75) return GRAVEL
        // round the town: its grass, dithered out over [WorldFarPlan.GRASS_M] like the far view's
        val pn = pn0
        if (p.outside < GRASS_NEAR_M) {
            // (in organic patches by the patch noise, thinning out with the distance)
            val t = (p.outside / GRASS_NEAR_M).coerceIn(0.0, 1.0)
            if (pn > .2 + .6 * t) return GRASS
        }
        // fields: a patchwork of plots (about 200 m), each a crop in rows one way or the other, pasture or fallow
        val crop = if (sk.has("crop")) sk.raw("crop", Math.floorMod(i, WorldSkeleton.W) + j.coerceIn(0, WorldSkeleton.H - 1) * WorldSkeleton.W) else 0
        if (crop != 0 && b in FARMED) {
            val wx = lx + plan.origin.x; val wz = lz + plan.origin.z
            val fx = floor(wx / FIELD_M).toInt(); val fz = floor(wz / FIELD_M).toInt()
            // (each plot split in two one way or the other at a random place: plots of different sizes)
            val cut = .3 + .4 * WorldNoise.hashU(tone, fx, fz, 6)
            val along = WorldNoise.hashU(tone, fx, fz, 5) < .5
            val side = if ((if (along) wx - fx * FIELD_M else wz - fz * FIELD_M) < cut * FIELD_M) 0 else 1
            val r = WorldNoise.hashU(tone, fx * 2 + side, fz, 3)
            if (r < .55) return if (WorldNoise.hashU(tone, fx * 2 + side, fz, 4) < .5) FARM_X else FARM_Z
            if (r < .68) return DIRT
        }
        return when (b) {
            0 -> SAND
            2 -> if (pn > .62) GRAVEL else DRY
            3, 5, 10, 11 -> if (pn < .3) GRASS else FOREST
            4 -> if (pn > .58) FOREST else GRASS
            6 -> if (pn > .7) DRY else GRASS
            7, 9 -> if (pn < .3) GRASS else DRY
            8 -> if (pn > .6) DIRT else SAND
            12 -> if (pn > .6) GRAVEL else if (pn < .3) ROCK else DRY
            13 -> if (pn > .6) DIRT else GRASS
            14 -> WATER
            16 -> ROCK
            17 -> if (pn > .66) DIRT else if (pn < .28) GRASS else DRY
            else -> GRASS
        }
    }

    // ------------------------------------------------------------------ the chunks

    /** A node of the quadtree: [level] (0: quads of a tile), its index ([i], [j]) in nodes of its size from the planet's corner. */
    data class Node(val level: Int, val i: Int, val j: Int) {
        /** Tiles a quad, tiles a side. */
        val step: Int get() = 1 shl level
        val tiles: Int get() = QUADS shl level
        val gx0: Long get() = i.toLong() * tiles
        val gz0: Long get() = j.toLong() * tiles
        val sizeM: Double get() = tiles * Planet.TILE_M
        fun child(k: Int): Node = Node(level - 1, i * 2 + (k and 1), j * 2 + (k shr 1))
        val parent: Node get() = Node(level + 1, Math.floorDiv(i, 2), Math.floorDiv(j, 2))
        /** The root block it is in. */
        val root: Node get() = Node(LEVELS - 1, Math.floorDiv(i, 1 shl (LEVELS - 1 - level)), Math.floorDiv(j, 1 shl (LEVELS - 1 - level)))
    }

    /**
     * One node's ground, made on a worker: heights (m above [WorldFarPlan.baseElev]) at its (n + 3)^2 vertices from -1 to
     * n + 1 (the ring outside for the normals), its quads' materials with a ring outside (n + 2)^2 (the shader dithers
     * across their borders), which quads are faceted (cliffs: a flat normal a triangle) and which are left to the town,
     * and each edge's skirt (m: north, east, south, west).
     */
    class NodeData(val node: Node, val heights: FloatArray, val ids: ByteArray, val faceted: BooleanArray, val skipped: BooleanArray, val skirt: FloatArray, val minY: Float, val maxY: Float, val ms: Float) {
        val n: Int get() = QUADS
        fun h(a: Int, b: Int): Float = heights[(b + 1) * (QUADS + 3) + a + 1]
        fun id(a: Int, b: Int): Int = ids[(b + 1) * (QUADS + 2) + a + 1].toInt()
        val empty: Boolean get() = skipped.all { it }
        val bytes: Long get() = heights.size * 4L + ids.size + faceted.size + skipped.size
    }

    /** Builds [node]'s ground (any thread). */
    fun build(node: Node): NodeData {
        val t0 = System.nanoTime()
        val n = QUADS; val s = node.step
        val w = n + 3
        val hs = FloatArray(w * w)
        val pts = arrayOfNulls<Point>(w * w)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (b in -1..n + 1) for (a in -1..n + 1) {
            val p = point(node.gx0 + a.toLong() * s, node.gz0 + b.toLong() * s, Point())
            val y = p.y.toFloat()
            hs[(b + 1) * w + a + 1] = y
            pts[(b + 1) * w + a + 1] = p
            if (a in 0..n && b in 0..n) { lo = min(lo, y); hi = max(hi, y) }
        }
        fun h(a: Int, b: Int) = hs[(b + 1) * w + a + 1]
        val sp = s * Planet.TILE_M
        val wi = n + 2
        val ids = ByteArray(wi * wi)
        val faceted = BooleanArray(n * n)
        val skipped = BooleanArray(n * n)
        for (b in -1..n) for (a in -1..n) {
            // the quad's own slope: the steeper of its two triangles' (split from (a, b) to (a+1, b+1))
            val h00 = h(a, b); val h10 = h(a + 1, b); val h11 = h(a + 1, b + 1); val h01 = h(a, b + 1)
            val s1 = hypot((h10 - h00).toDouble(), (h11 - h10).toDouble()) / sp
            val s2 = hypot((h11 - h01).toDouble(), (h01 - h00).toDouble()) / sp
            val qs = max(s1, s2)
            // (the material from the quad's north-west corner's point: no point of its own, half the work; the shader's
            // dither hides the half quad's shift)
            val q = pts[(b + 1) * w + a + 1]!!
            val gx = node.gx0 + a.toLong() * s; val gz = node.gz0 + b.toLong() * s
            val m = material(gx * Planet.TILE_M / Planet.CELL_M, gz * Planet.TILE_M / Planet.CELL_M, localX(gx), localZ(gz), q, qs)
            ids[(b + 1) * wi + a + 1] = m.toByte()
            if (a in 0 until n && b in 0 until n) {
                faceted[b * n + a] = q.cliff > FACET_CLIFF || qs > FACET_SLOPE
                townGrass?.let { g ->
                    val lx = localX(gx); val lz = localZ(gz)
                    skipped[b * n + a] = lx >= g[0] && lx + sp <= g[2] && lz >= g[1] && lz + sp <= g[3]
                }
            }
        }
        // the skirts: as deep as the edge's largest step to a coarser neighbour's (its vertices are every 2^d-th of these,
        // the same heights) or to the far view's finest level, and a margin
        val skirt = FloatArray(4)
        val edge = FloatArray(n + 1)
        val l0 = plan.levels.first()
        for (e in 0 until 4) {
            for (k in 0..n) edge[k] = when (e) { 0 -> h(k, 0); 1 -> h(n, k); 2 -> h(k, n); else -> h(0, k) }
            var worst = 0f
            var stride = 2
            while (stride <= n) {
                for (k in 0..n) {
                    val k0 = k / stride * stride; val k1 = min(n, k0 + stride)
                    val t = if (k1 == k0) 0f else (k - k0).toFloat() / (k1 - k0)
                    worst = max(worst, abs(edge[k] - (edge[k0] + (edge[k1] - edge[k0]) * t)))
                }
                stride *= 2
            }
            // (against the far view: its finest level's surface along this edge, sampled at the edge's vertices)
            for (k in 0..n) {
                val (ga, gb) = when (e) { 0 -> k to 0; 1 -> n to k; 2 -> k to n; else -> 0 to k }
                val lx = localX(node.gx0 + ga.toLong() * s); val lz = localZ(node.gz0 + gb.toLong() * s)
                if (l0.inside(lx, lz)) worst = max(worst, abs(edge[k] - plan.surface(lx, lz)))
            }
            skirt[e] = worst + SKIRT_MARGIN_M + sp.toFloat() * .05f
        }
        return NodeData(node, hs, ids, faceted, skipped, skirt, lo, hi, (System.nanoTime() - t0) / 1e6f)
    }

    // ------------------------------------------------------------------ choosing the nodes

    /** The camera at world ([x], [z]) m, [y] m above the town's ground height. */
    class Eye(val x: Double, val z: Double, val y: Double)

    /** The roots round [eye]: blocks within [REACH_M] lying wholly on the far view's finest level. */
    fun roots(eye: Eye): List<Node> {
        val l0 = plan.levels.first()
        val out = ArrayList<Node>()
        val size = ROOT_TILES * Planet.TILE_M
        val r = REACH_M + size
        val i0 = floor((eye.x - r) / size).toInt(); val i1 = floor((eye.x + r) / size).toInt()
        val j0 = floor((eye.z - r) / size).toInt(); val j1 = floor((eye.z + r) / size).toInt()
        for (j in j0..j1) for (i in i0..i1) {
            val node = Node(LEVELS - 1, i, j)
            if (distance2d(node, eye) > REACH_M) continue
            val lx = localX(node.gx0); val lz = localZ(node.gz0)
            if (lx < l0.ox - 1 || lz < l0.oz - 1 || lx + size > l0.ox + l0.n * l0.spacing + 1 || lz + size > l0.oz + l0.n * l0.spacing + 1) continue
            out += node
        }
        return out
    }

    /** The horizontal distance (m) from [eye] to [node]'s square (0 inside). */
    fun distance2d(node: Node, eye: Eye): Double {
        val x0 = node.gx0 * Planet.TILE_M; val z0 = node.gz0 * Planet.TILE_M; val s = node.sizeM
        val dx = max(0.0, max(x0 - eye.x, eye.x - (x0 + s)))
        val dz = max(0.0, max(z0 - eye.z, eye.z - (z0 + s)))
        return hypot(dx, dz)
    }

    /** Whether [node] should be split for [eye] (its children drawn instead): nearer than [SPLIT] of its size. */
    fun split(node: Node, eye: Eye, groundY: Double): Boolean {
        if (node.level == 0) return false
        val d = hypot(distance2d(node, eye), max(0.0, eye.y - groundY))
        return d < SPLIT * node.sizeM
    }

    /** The leaves for [eye] (the nodes to draw when everything is ready), coarse to fine. */
    fun select(eye: Eye, groundY: Double): List<Node> {
        val out = ArrayList<Node>()
        fun visit(n: Node) { if (split(n, eye, groundY)) for (k in 0 until 4) visit(n.child(k)) else out += n }
        for (r in roots(eye)) visit(r)
        return out
    }

    companion object {
        /** Quads a node's side, levels of the tree, tiles a root's side. */
        const val QUADS = 64
        const val LEVELS = 5
        const val ROOT_TILES = QUADS shl (LEVELS - 1)
        /** How far round the camera the roots reach (m to the block's nearest edge). */
        const val REACH_M = 3000.0
        /** A node is split while the camera is nearer than this many of its sizes. */
        const val SPLIT = 1.3
        /** The ground's pictures' texel (m): the town's (0.0389 m: 54 a tile). */
        const val TEXEL_M = 7.0 / 180.0
        const val TEXELS_A_TILE = 54

        /** The slope (rise over run) where the cliffs begin, and where they are whole. */
        const val CLIFF_FROM = .62
        const val CLIFF_FULL = .9
        /** The cliffs' steps: [STEP_M] tall and up to [STEP_SWING_M] more by a noise; a step's tread is [TREAD] of it and rises [TREAD_RISE] of its height. */
        const val STEP_M = 7.0
        const val STEP_SWING_M = 6.0
        const val TREAD = .72
        const val TREAD_RISE = .06
        const val SLOPE_E_M = 1.05
        /** A quad is faceted (a flat normal a triangle) past this much cliff or this slope; rock past [ROCK_SLOPE]. */
        const val FACET_CLIFF = .3
        const val FACET_SLOPE = 1.0
        const val ROCK_SLOPE = 1.0

        /** The lines on the near ground: half widths (m); how far round them the land's detail calms. */
        const val HIGHWAY_HALF_M = 4.2
        const val SHOULDER_M = 1.6
        const val COUNTY_HALF_M = 3.0
        const val RAIL_HALF_M = 2.6
        const val RIVER_SHARE = .45
        const val CALM_M = 70.0
        const val CALM_ROAD_M = 36.0
        const val BANK_M = 8.0
        const val RIVER_DEPTH_M = 1.6
        /** The town's grass dithered in round its ground (m); the fields' plots (m). */
        const val GRASS_NEAR_M = 700.0
        const val FIELD_M = 201.6
        /** How far under the town's own grass (and its river's water, 0.42 m down) the terrain stays (m). */
        const val TOWN_UNDER_M = .6
        /** Each skirt's margin below the deepest step it covers (m). */
        const val SKIRT_MARGIN_M = .6f

        /** The ground's pictures (the terrain atlas's layers, terrain.mat). */
        const val GRASS = 0; const val DRY = 1; const val DIRT = 2; const val FARM_X = 3; const val FARM_Z = 4; const val SAND = 5
        const val ROCK = 6; const val SNOW = 7; const val GRAVEL = 8; const val FOREST = 9; const val WATER = 10; const val ASPHALT = 11
        const val LAYERS = 12
        /** Each picture's mean colour (ARGB; the far view's finest level wears them, the pictures' own last mip level). */
        val MEAN = intArrayOf(
            0xFF479656.toInt(), 0xFFA69D5A.toInt(), 0xFF9E7C58.toInt(), 0xFF84603E.toInt(), 0xFF84603E.toInt(), 0xFFE1D199.toInt(),
            0xFF827C72.toInt(), 0xFFE9EFF6.toInt(), 0xFF9C937E.toInt(), 0xFF4C5C38.toInt(), 0xFF3A6E9E.toInt(), 0xFF4F4B4F.toInt(),
        )
        val NAMES = listOf("grass", "dry grass", "dirt", "farmland (rows east-west)", "farmland (rows north-south)", "sand", "rock", "snow", "gravel", "forest floor", "water", "asphalt")
        /** The biomes farmed where the skeleton has a crop (temperate forest, prairie, steppe, savanna, chaparral). */
        private val FARMED = setOf(4, 6, 7, 9, 17)

        private fun sat(t: Double): Double = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
        private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
    }
}
