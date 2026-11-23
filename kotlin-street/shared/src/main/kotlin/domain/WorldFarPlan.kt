package com.lifemax.rpg.feature.habits.domain

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The world round a town, sampled from the skeleton for its far view (WORLD_GEN.md phase W4: "a first simple pass of the
 * world", before W5's terrain renderer): what [com.lifemax.rpg.feature.habits.presentation.scene3d.WorldFar] draws, as
 * plain data, engine-agnostic. Everything is in metres from the render origin [origin] (x east, z south; y up from the
 * town's ground height [baseElev]), each level in its own local frame from its [Level.ox], [Level.oz] corner.
 *
 * - **Terrain**: three nested square grids round the town ([LEVELS]): a quarter atlas cell (268.8 m) to 8.6 km, an atlas
 *   cell (1,075 m, its vertices on the cells' centres: their heights exactly the skeleton's) to 52 km, four cells to
 *   172 km (the horizon). Each coarser level has a hole where the finer one is; the finer one's rim drops a skirt, so no
 *   crack shows where they meet. The finest level adds the skeleton's own integer-hashed detail noise ([WorldFbm], the
 *   TypeScript's, so a later bake can agree with it) between the cells' heights, fading to none at its rim. The town's
 *   ground ([HubFrame.ground]) is the town's: the terrain there is flattened to the town's height just under it and
 *   blends to the land's own over [BLEND_M].
 * - **Colours** a texel a quad, by biome ([BIOME_COLOURS], two tones dithered), the borders broken by noise, the town's
 *   own grass dithered in round its ground.
 * - **Lines**: rivers (width by order), highways and county roads (built by [year]), rail lines, as polylines on the
 *   surface ([surface]), cut where they cross into the town's ground (the town draws its own from there: they meet it at
 *   its pins, paths.ts).
 * - **The ocean** a flat plane at sea level; **towns** as a few boxes each, sized by rank, lit windows at night.
 */
class WorldFarPlan(
    val sk: WorldSkeleton,
    /** The render origin (the town's frame's origin while in town). */
    val origin: WorldPos,
    /** The town whose ground this is (its pad is flattened, its own features are its own). */
    val town: HubFrame?,
    /** The year (roads built by then). */
    val year: Int = 1958,
) {
    /** The ground's height (m) every y is measured from. */
    val baseElev: Double = town?.elevM ?: 0.0

    class Level(
        /** Quad size (m), quads a side; its local frame's corner (m from the origin). */
        val spacing: Double, val n: Int, val ox: Double, val oz: Double,
        /** Quads left out (the finer level's square): i0, j0, i1, j1 (exclusive); null for none. */
        val hole: IntArray?,
        /** Heights (m above [baseElev]) at the (n + 1)^2 vertices, row-major. */
        val heights: FloatArray,
        /** A colour (ARGB) a quad, row-major; 0: no quad (under the town). */
        val colours: IntArray,
    ) {
        fun h(i: Int, j: Int): Float = heights[j * (n + 1) + i]
        fun inside(x: Double, z: Double): Boolean = x >= ox && z >= oz && x < ox + n * spacing && z < oz + n * spacing
        fun skipped(i: Int, j: Int): Boolean { val o = hole ?: return false; return i >= o[0] && i < o[2] && j >= o[1] && j < o[3] }
    }

    /** A polyline on the ground: x, z pairs (m from the origin), its width (m), its kind ([RIVER], [HIGHWAY]...). */
    class Line(val pts: DoubleArray, val width: Float, val kind: Int)

    /** A town's block far off: middle ([x], [z]) from the origin, footprint [w] x [d], [h] tall over its foot [y] (m). */
    class Box(val x: Float, val z: Float, val y: Float, val w: Float, val d: Float, val h: Float, val wall: Int, val roof: Int, val place: String)

    val levels = ArrayList<Level>()
    val lines = ArrayList<Line>()
    val boxes = ArrayList<Box>()
    /** Sea level (m above [baseElev]). */
    val seaLevel: Float get() = (-baseElev).toFloat()

    private val detail = WorldFbm(sk.seed, "w4-detail", WorldSkeleton.W / 2, 3)
    private val jx = WorldLattice(sk.seed, "w4-jitter-x", WorldSkeleton.W)
    private val jz = WorldLattice(sk.seed, "w4-jitter-z", WorldSkeleton.W)
    private val tone = WorldNoise.hashStr("${sk.seed}|w4-tone")

    // the town's ground in metres from the origin (x0, z0, x1, z1), or none
    private val ground: DoubleArray? = town?.let { f ->
        val o = origin.dx(f.origin); val oz = origin.dz(f.origin); val u = f.unitsPerMetre
        doubleArrayOf(o + f.ground[0] / u, oz + f.ground[1] / u, o + f.ground[2] / u, oz + f.ground[3] / u)
    }

    /** How far (m) ([x], [z]) is outside the town's ground (0 or less: on it). */
    fun outside(x: Double, z: Double): Double {
        val g = ground ?: return Double.MAX_VALUE
        val dx = max(g[0] - x, x - g[2]); val dz = max(g[1] - z, z - g[3])
        return if (dx <= 0 && dz <= 0) max(dx, dz) else hypot(max(dx, 0.0), max(dz, 0.0))
    }

    /** The skeleton's cell coordinates of ([x], [z]) m from the origin. */
    private fun cx(x: Double) = (origin.x + x) / Planet.CELL_M
    private fun cz(z: Double) = (origin.z + z) / Planet.CELL_M

    /** The flattening round the town: 0 on its ground, 1 past [BLEND_M]. */
    private fun blend(x: Double, z: Double): Double {
        val d = outside(x, z)
        val t = ((d - FLAT_M) / BLEND_M).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun shape(x: Double, z: Double, raw: Double): Float {
        if (outside(x, z) <= 0) return (-UNDER_M).toFloat()
        val b = blend(x, z)
        return ((raw - baseElev) * b - UNDER_M * (1 - b)).toFloat()
    }

    init { build() }

    private fun build() {
        // the anchor: the lattice point of the coarsest level nearest the town's middle (the origin out in the country)
        val mid = ground?.let { doubleArrayOf((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) } ?: doubleArrayOf(0.0, 0.0)
        val ai = (floor((cx(mid[0]) - .5) / 4).roundToInt()) * 4
        val aj = (floor((cz(mid[1]) - .5) / 4).roundToInt()) * 4
        // (the anchor's centre in metres from the origin; vertices of level 1 and 2 on the cells' centres)
        val ax = origin.dx(WorldPos.ofCells(ai + .5, 0.0)); val az = (aj + .5) * Planet.CELL_M - origin.z
        var inner: Level? = null
        for ((k, spec) in LEVELS.withIndex()) {
            val (cells, n) = spec
            val sp = cells * Planet.CELL_M
            val ox = ax - n / 2 * sp; val oz = az - n / 2 * sp
            val hole = inner?.let { f ->
                val i0 = ((f.ox - ox) / sp).roundToInt(); val j0 = ((f.oz - oz) / sp).roundToInt()
                val m = (f.n * f.spacing / sp).roundToInt()
                intArrayOf(i0, j0, i0 + m, j0 + m)
            }
            val hs = FloatArray((n + 1) * (n + 1))
            for (j in 0..n) for (i in 0..n) {
                val x = ox + i * sp; val z = oz + j * sp
                val raw = if (k == 0) {
                    // the bilinear cells' heights and the detail noise, on land, none at the rim (it meets level 1 there)
                    val c = sk.height(cx(x), cz(z))
                    val rim = min(min(i, n - i), min(j, n - j)) / 4.0
                    val land = sk.land(floor(cx(x)).toInt(), floor(cz(z)).toInt())
                    c + if (land && c > 2) (detail.at(cx(x), cz(z)) - .5) * DETAIL_M * min(1.0, rim) else 0.0
                } else sk.elev(floor(cx(x)).toInt(), floor(cz(z)).toInt()).toDouble()
                hs[j * (n + 1) + i] = shape(x, z, raw)
            }
            val cols = IntArray(n * n)
            for (j in 0 until n) for (i in 0 until n) {
                val x = ox + (i + .5) * sp; val z = oz + (j + .5) * sp
                // (a quad wholly on the town's ground is the town's: none here)
                if (outside(x - sp / 2, z - sp / 2) < 0 && outside(x + sp / 2, z + sp / 2) < 0 && outside(x - sp / 2, z + sp / 2) < 0 && outside(x + sp / 2, z - sp / 2) < 0) continue
                cols[j * n + i] = colourAt(x, z, k)
            }
            val lv = Level(sp, n, ox, oz, hole, hs, cols)
            levels += lv
            inner = lv
        }
        lines()
        towns()
    }

    /** The colour of the ground round ([x], [z]) (m from the origin) on level [k]. */
    private fun colourAt(x: Double, z: Double, k: Int): Int {
        var u = cx(x); var v = cz(z)
        // (the borders broken: the biome looked up a little off, by noise, so they are not the grid's lines)
        u += (jx.at(u, v) - .5) * .9; v += (jz.at(u, v) - .5) * .9
        val i = floor(u).toInt(); val j = floor(v).toInt()
        val b = sk.biome(i, j)
        val dither = WorldNoise.hashU(tone, floor(x / 100).toInt(), floor(z / 100).toInt(), k)
        var c = biomeColour(b, dither < .5)
        val e = sk.elev(i, j)
        if (sk.land(i, j) && e > SNOW_M && b != 0) c = if (dither < .5) 0xFFF2F4FA.toInt() else 0xFFE2E8F2.toInt()
        // the town's own grass dithered in round its ground
        val d = outside(x, z)
        if (d < GRASS_M && sk.land(i, j)) {
            val t = (d / GRASS_M).coerceIn(0.0, 1.0)
            if (WorldNoise.hashU(tone, floor(x / 60).toInt(), floor(z / 60).toInt(), 7) > t) c = TOWN_GRASS
        }
        return c
    }

    /** The surface's height (m above [baseElev]) at ([x], [z]) as the levels' triangles have it (the finest there). */
    fun surface(x: Double, z: Double): Float {
        for (lv in levels) {
            if (!lv.inside(x, z)) continue
            val fx = (x - lv.ox) / lv.spacing; val fz = (z - lv.oz) / lv.spacing
            val i = floor(fx).toInt().coerceIn(0, lv.n - 1); val j = floor(fz).toInt().coerceIn(0, lv.n - 1)
            if (lv.skipped(i, j)) continue
            val u = (fx - i).toFloat(); val v = (fz - j).toFloat()
            val a = lv.h(i, j); val b = lv.h(i + 1, j); val c = lv.h(i + 1, j + 1); val d = lv.h(i, j + 1)
            // (the quads split from (i, j) to (i+1, j+1), as WorldFar builds them)
            return if (u >= v) a + (b - a) * u + (c - b) * v else a + (d - a) * v + (c - d) * u
        }
        return seaLevel
    }

    /** The far view's reach (m from the anchor): the coarsest level's half side. */
    val reach: Double get() = levels.last().n * levels.last().spacing / 2

    private fun inReach(x: Double, z: Double): Boolean = levels.last().inside(x, z)

    /** A path's points in metres from the origin (x unwrapped near the origin). */
    private fun metres(path: IntArray?): DoubleArray {
        val p = sk.points(path)
        if (p.isEmpty()) return p
        val out = DoubleArray(p.size)
        val x0 = origin.dx(WorldPos.ofCells(p[0], p[1]))
        for (k in 0 until p.size / 2) {
            out[k * 2] = x0 + (p[k * 2] - p[0]) * Planet.CELL_M
            out[k * 2 + 1] = p[k * 2 + 1] * Planet.CELL_M - origin.z
        }
        return out
    }

    /** Adds [pts]'s stretches outside the town's ground and in reach (cut at the ground's edge) as lines. */
    private fun addCut(pts: DoubleArray, width: Float, kind: Int) {
        val n = pts.size / 2
        if (n < 2) return
        var cur = ArrayList<Double>()
        fun flush() { if (cur.size >= 4) lines += Line(cur.toDoubleArray(), width, kind); cur = ArrayList() }
        fun keep(x: Double, z: Double) = outside(x, z) > 0 && inReach(x, z)
        for (k in 0 until n - 1) {
            val x0 = pts[k * 2]; val z0 = pts[k * 2 + 1]; val x1 = pts[k * 2 + 2]; val z1 = pts[k * 2 + 3]
            val a = keep(x0, z0); val b = keep(x1, z1)
            if (!a && !b) { flush(); continue }
            // (a stretch crossing the edge is cut where it crosses: found by halving)
            fun cross(inX: Double, inZ: Double, outX: Double, outZ: Double): DoubleArray {
                var lo = 0.0; var hi = 1.0
                repeat(30) { val m = (lo + hi) / 2; if (keep(inX + (outX - inX) * m, inZ + (outZ - inZ) * m)) hi = m else lo = m }
                return doubleArrayOf(inX + (outX - inX) * hi, inZ + (outZ - inZ) * hi)
            }
            if (a) { if (cur.isEmpty()) { cur += x0; cur += z0 } } else { val c = cross(x0, z0, x1, z1); cur += c[0]; cur += c[1] }
            if (b) { cur += x1; cur += z1 } else { val c = cross(x1, z1, x0, z0); cur += c[0]; cur += c[1]; flush() }
        }
        flush()
    }

    private fun near(pts: DoubleArray): Boolean {
        for (k in 0 until pts.size / 2) if (inReach(pts[k * 2], pts[k * 2 + 1])) return true
        return false
    }

    private fun lines() {
        for (r in sk.rivers) { val p = metres(r.path); if (near(p)) addCut(p, RIVER_W[r.order.coerceIn(0, RIVER_W.size - 1)], RIVER) }
        for (r in sk.roads) {
            if (r.built > year) continue
            val p = metres(r.path); if (!near(p)) continue
            val expr = r.expressway?.let { it <= year } == true
            addCut(p, if (r.cls == "highway") (if (expr) 34f else 24f) else 13f, if (r.cls == "highway") HIGHWAY else COUNTY)
        }
        for (l in sk.railLines) { val p = metres(l.path); if (near(p)) addCut(p, if (l.cls == "main") 18f else 13f, RAIL) }
    }

    private fun towns() {
        val skip = town?.hubId
        for (p in sk.places) {
            if (p.id == skip) continue
            val x = origin.dx(WorldPos.ofCells(p.x, p.z)); val z = p.z * Planet.CELL_M - origin.z
            if (!inReach(x, z)) continue
            // (a far town is a stand-in, as TownFar's boxes are for the town's own buildings: its blocks a little bigger than
            // a real block, so the place still reads as a town 10-40 km off; heights by rank)
            val (count, radius, tall) = when {
                p.kind == "hub" -> Triple(30, 1000.0, 70.0)
                p.kind == "hamlet" -> Triple(4, 180.0, 14.0)
                p.rank == "major" -> Triple(16, 600.0, 36.0)
                p.rank == "town" -> Triple(11, 450.0, 26.0)
                else -> Triple(7, 300.0, 18.0)
            }
            val h0 = WorldNoise.hashStr("${sk.seed}|w4-box|${p.id}")
            for (k in 0 until count) {
                // (blocks round the middle, denser near it; the first few the tallest)
                val r = radius * kotlin.math.sqrt(WorldNoise.hashU(h0, k, 1)) * (if (k < 3) .35 else 1.0)
                val a = WorldNoise.hashU(h0, k, 2) * Math.PI * 2
                val bx = x + kotlin.math.cos(a) * r; val bz = z + kotlin.math.sin(a) * r
                val w = 60 + 80 * WorldNoise.hashU(h0, k, 3); val d = 60 + 80 * WorldNoise.hashU(h0, k, 4)
                val hgt = tall * (.35 + .65 * WorldNoise.hashU(h0, k, 5)) * (if (k < 3) 1.0 else .6)
                val wall = WALLS[(WorldNoise.hashU(h0, k, 6) * WALLS.size).toInt().coerceIn(0, WALLS.size - 1)]
                val roof = ROOFS[(WorldNoise.hashU(h0, k, 7) * ROOFS.size).toInt().coerceIn(0, ROOFS.size - 1)]
                val foot = min(surface(bx - w / 2, bz - d / 2), min(surface(bx + w / 2, bz + d / 2), min(surface(bx - w / 2, bz + d / 2), surface(bx + w / 2, bz - d / 2))))
                val top = max(surface(bx, bz), foot)
                if (top < seaLevel + 1) continue
                boxes += Box(bx.toFloat(), bz.toFloat(), foot - 6f, w.toFloat(), d.toFloat(), (top - foot + 6 + hgt).toFloat(), wall, roof, p.id)
            }
        }
    }

    /** The counts, for reports. */
    val report: String get() = "levels ${levels.joinToString { "%.0f m x %d".format(java.util.Locale.US, it.spacing, it.n) }}, reach %.0f km, %d lines (%d rivers), %d town boxes".format(
        java.util.Locale.US, reach / 1000, lines.size, lines.count { it.kind == RIVER }, boxes.size)

    companion object {
        /** The levels: atlas cells a quad, quads a side (finest first). */
        val LEVELS = listOf(.25 to 64, 1.0 to 96, 4.0 to 80)
        /** How far the coarsest level reaches from its middle (m): the horizon, 172 km. */
        val REACH_M: Double = LEVELS.last().let { it.first * it.second / 2 } * Planet.CELL_M
        const val RIVER = 0; const val HIGHWAY = 1; const val COUNTY = 2; const val RAIL = 3
        val RIVER_W = floatArrayOf(30f, 45f, 70f, 110f, 170f, 250f)
        /** The town's ground: flat this far past its edge, then blending to the land's own height over [BLEND_M] (m). */
        const val FLAT_M = 150.0
        const val BLEND_M = 900.0
        /** How far under the town's ground the terrain stays beneath it (m): never seen through it. */
        const val UNDER_M = .05
        /** The finest level's detail noise: its full swing (m). */
        const val DETAIL_M = 36.0
        /** Snow on the ground above this (m), whatever the biome. */
        const val SNOW_M = 2700
        /** The town's grass dithered in this far round its ground (m). */
        const val GRASS_M = 1600.0
        /** The town's own grass (FilamentTown's skirt). */
        const val TOWN_GRASS = 0xFF468E52.toInt()

        /** Two tones a biome (the skeleton's order, [WorldSkeleton.BIOMES]): our pixel-art palette's greens, sands and greys. */
        val BIOME_COLOURS = intArrayOf(
            0xFF2E5A8C.toInt(), 0xFF2A5282.toInt(), // ocean (the sea floor, under the water)
            0xFFE8F0F8.toInt(), 0xFFD8E4F0.toInt(), // ice
            0xFF8E9A72.toInt(), 0xFF7F8B66.toInt(), // tundra
            0xFF3E5E46.toInt(), 0xFF365440.toInt(), // taiga
            0xFF3F7A3E.toInt(), 0xFF37703A.toInt(), // temperate forest
            0xFF2F6438.toInt(), 0xFF2A5A32.toInt(), // temperate rainforest
            0xFF8CAA4E.toInt(), 0xFF80A048.toInt(), // prairie
            0xFFB4AE6A.toInt(), 0xFFA8A260.toInt(), // steppe
            0xFFE0C48A.toInt(), 0xFFD6B87C.toInt(), // desert
            0xFFB8B258.toInt(), 0xFFAEA650.toInt(), // savanna
            0xFF3C8A3A.toInt(), 0xFF347E34.toInt(), // tropical forest
            0xFF2C7230.toInt(), 0xFF26662A.toInt(), // rainforest
            0xFF8A8E78.toInt(), 0xFF7E826C.toInt(), // alpine
            0xFF5A7A4E.toInt(), 0xFF506E46.toInt(), // marsh
            0xFF3A6EA0.toInt(), 0xFF346698.toInt(), // lake
            0xFFE8D8A0.toInt(), 0xFFDCCB92.toInt(), // beach
            0xFF8A8478.toInt(), 0xFF7C766C.toInt(), // rock
            0xFF9A9A5A.toInt(), 0xFF8E8E50.toInt(), // chaparral
            0xFFF0F6FC.toInt(), 0xFFE0EAF4.toInt(), // glacier
        )
        fun biomeColour(b: Int, light: Boolean): Int = BIOME_COLOURS[(b.coerceIn(0, BIOME_COLOURS.size / 2 - 1)) * 2 + if (light) 0 else 1]

        val LINE_COLOURS = intArrayOf(0xFF4A86B8.toInt(), 0xFF5A5866.toInt(), 0xFF9A8A6A.toInt(), 0xFF4A3A34.toInt())
        val WALLS = intArrayOf(0xFFB0705A.toInt(), 0xFFD8CCB0.toInt(), 0xFF9A9AA6.toInt(), 0xFFC8A878.toInt(), 0xFF8A5A4A.toInt())
        val ROOFS = intArrayOf(0xFF5A4A52.toInt(), 0xFF6A3A34.toInt(), 0xFF4A5A6A.toInt(), 0xFF7A7A80.toInt())
    }
}
