package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The open country between the towns (docs/WORLD_STREAM.md, step 1b): what the land is used for at any point, and what
 * stands on it, both functions of the world position alone (so the near ground's materials, the trees and the things by
 * the roads agree wherever and whenever they are made). Engine-agnostic: [WorldTerrain.material] wears [cover] as its
 * ground pictures, the Filament side (WorldProps) draws [scatter]'s trees and things.
 *
 *  - **Fields** on farmed land (the skeleton's crop belt on a farmable biome): plots of about 200 m ([WorldTerrain.FIELD_M],
 *    each split in two at a random place), each one use by its crop belt ([USES]: wheat, corn and other row crops,
 *    ploughed ground, fallow, pasture, hay meadow, orchards, paddies), its rows along its long side, a grass verge round it.
 *    Steep plots are left to woods and pasture, as farmers do.
 *  - **Woods** by a slow noise (woods of a few hundred metres, ragged at their edges), more of them on steep ground and
 *    along rivers, and the odd rectangular woodlot plot; the wild biomes' own ground elsewhere (forest, meadow, scrub,
 *    dry grass, marsh, dunes).
 *  - **What stands** ([scatter], in cells of [CELL_M]): the woods' trees (species by biome), orchards in rows, hedgerows
 *    and fences along the plots' edges (hedges of bushes with a tree now and then; post and wire round pastures), tree
 *    lines along the rivers, lone trees in the pastures, scrub's bushes; by the roads utility poles with their wires (from
 *    1925; telegraph poles along the railways), mile markers on the highways (from 1930) and billboards (from 1915).
 */
class WorldCountry(private val t: WorldTerrain) {
    private val sk = t.sk
    private val seed = sk.seed
    /** The terrain's own plot hash (its plots, kept as they were). */
    private val tone = WorldNoise.hashStr("$seed|w5-tone")
    private val hs = WorldNoise.hashStr("$seed|w1b")
    private val woodN = WorldFbm(seed, "w1b-wood", WorldSkeleton.W * 2, 3)
    private val ragN = WorldLattice(seed, "w1b-rag", WorldSkeleton.W * 32)
    private val patch = WorldFbm(seed, "w5-patch", WorldSkeleton.W * 8, 2)
    /** The year the country stands as (the far view's: its roads are that year's). */
    val year: Int = t.plan.year

    // ------------------------------------------------------------------ plots

    /** A field plot: its index ([fx], [fz], [side] of its split) and its rectangle in world metres. */
    class Plot {
        var fx = 0; var fz = 0; var side = 0
        var x0 = 0.0; var z0 = 0.0; var x1 = 0.0; var z1 = 0.0
        val key: Long get() = ((fx.toLong() * 2 + side) shl 32) or (fz.toLong() and 0xffffffffL)
        /** Its rows run along x (its long side). */
        val rowsX: Boolean get() = x1 - x0 >= z1 - z0
    }

    /** The plot at world ([wx], [wz]) (m), into [o]. */
    fun plot(wx: Double, wz: Double, o: Plot): Plot {
        val f = WorldTerrain.FIELD_M
        val fx = floor(wx / f).toInt(); val fz = floor(wz / f).toInt()
        o.fx = fx; o.fz = fz
        o.x0 = fx * f; o.z0 = fz * f; o.x1 = o.x0 + f; o.z1 = o.z0 + f
        val cut = .3 + .4 * WorldNoise.hashU(tone, fx, fz, 6)
        if (WorldNoise.hashU(tone, fx, fz, 5) < .5) { val c = o.x0 + cut * f; if (wx < c) { o.side = 0; o.x1 = c } else { o.side = 1; o.x0 = c } }
        else { val c = o.z0 + cut * f; if (wz < c) { o.side = 0; o.z1 = c } else { o.side = 1; o.z0 = c } }
        return o
    }

    private val uses = ThreadLocal.withInitial { object : LinkedHashMap<Long, Int>(1024, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Int>?) = size > 8192 } }

    /** [o]'s use ([WHEAT]...), or [NONE] where the land is not farmed (cached a thread). */
    fun plotUse(o: Plot): Int = plotValue(o).let { if (it == NONE) NONE else it and 0xff }

    /** Whether plot [o] has a farmstead ([farmSite]): its farmhouse, barn, silo and shed round a farmyard in one corner. */
    fun farm(o: Plot): Boolean = plotValue(o).let { it != NONE && (it shr 8) and 1 != 0 }

    /** The farmstead's middle in plot [o] (world x, z m) and the way its house faces (x, z): the corner it stands in, set in. */
    fun farmSite(o: Plot, out: DoubleArray): DoubleArray {
        val h = WorldNoise.hash3(hs, o.fx * 2 + o.side, o.fz, 52)
        val east = h and 1L != 0L; val south = h and 2L != 0L
        out[0] = if (east) o.x1 - FARM_IN_M else o.x0 + FARM_IN_M
        out[1] = if (south) o.z1 - FARM_IN_M else o.z0 + FARM_IN_M
        // (the house faces out of the plot across the nearer of the corner's two edges)
        if (o.x1 - o.x0 < o.z1 - o.z0) { out[2] = if (east) 1.0 else -1.0; out[3] = 0.0 } else { out[2] = 0.0; out[3] = if (south) 1.0 else -1.0 }
        return out
    }

    private fun plotValue(o: Plot): Int {
        val m = uses.get()
        m[o.key]?.let { return it }
        val u = plotUseOf(o)
        var v = u
        if (u != NONE && u != WOOD && u != PADDY && WorldNoise.hashU(hs, o.fx * 2 + o.side, o.fz, 51) < FARM_P && min(o.x1 - o.x0, o.z1 - o.z0) > 2.5 * FARM_IN_M) {
            val s = farmSite(o, DoubleArray(4))
            if (t.relief.slope(s[0] / Planet.CELL_M, s[1] / Planet.CELL_M) < .09 && sk.land(floor(s[0] / Planet.CELL_M).toInt(), floor(s[1] / Planet.CELL_M).toInt())) v = u or 0x100
        }
        m[o.key] = v
        return v
    }
    private fun crop(i: Int, j: Int): Int = if (sk.has("crop")) sk.raw("crop", Math.floorMod(i, WorldSkeleton.W) + j.coerceIn(0, WorldSkeleton.H - 1) * WorldSkeleton.W) else 0

    private fun plotUseOf(o: Plot): Int {
        val cx = (o.x0 + o.x1) / 2 / Planet.CELL_M; val cz = (o.z0 + o.z1) / 2 / Planet.CELL_M
        val i = floor(cx).toInt(); val j = floor(cz).toInt()
        if (!sk.land(i, j)) return NONE
        val b = sk.biome(i, j)
        val crop = crop(i, j)
        if (crop == 0 || b !in FARMED) return NONE
        val r = WorldNoise.hashU(tone, o.fx * 2 + o.side, o.fz, 3)
        // (steep ground is left to woods and grazing; ploughing it would wash it away)
        val slope = t.relief.slope(cx, cz)
        if (slope > .3) return if (r < .55) WOOD else if (r < .85) PASTURE else MEADOW
        val table = USES[crop.coerceIn(0, USES.size - 1)]
        var acc = 0.0
        for (k in 0 until table.size / 2) { acc += table[k * 2 + 1]; if (r < acc) return table[k * 2].toInt() }
        return PASTURE
    }

    // ------------------------------------------------------------------ the cover at a point

    /** How wooded the land is at ([cx], [cz]) whose point is [p]: woods where this passes the biome's threshold. */
    fun woodiness(cx: Double, cz: Double, p: WorldTerrain.Point): Double {
        var w = woodN.at(cx, cz) + (ragN.at(cx, cz) - .5) * .12
        // (steep ground and river banks keep their trees)
        w += smoothstep(sat((p.slope - .18) / .3)) * .14
        if (p.river < 60) w += .07 * (1 - p.river.coerceAtLeast(0.0) / 60)
        return w
    }

    /**
     * What covers the ground at ([cx], [cz]) in cells (world ([wx], [wz]) m), whose point is [p]: one of [WOOD]...
     * [edgeM]: the verge's least width (a coarse quad's, so the field edges stay whole lines from afar). Roads, rivers,
     * beaches, rock and snow are the terrain's own ([WorldTerrain.material] asks this only for the land between).
     */
    fun cover(cx: Double, cz: Double, wx: Double, wz: Double, p: WorldTerrain.Point, edgeM: Double, o: Plot = Plot()): Int {
        val i = floor(cx).toInt(); val j = floor(cz).toInt()
        val b = sk.biome(i, j)
        val w = woodiness(cx, cz, p)
        plot(wx, wz, o)
        val use = plotUse(o)
        if (use != NONE) {
            // (a farmstead's yard: beaten dirt, a ring of grass round it)
            if (farm(o)) {
                val s = farmSite(o, siteOf.get())
                val d = hypot(wx - s[0], wz - s[1])
                if (d < YARD_M) return DIRT
                if (d < YARD_M + 8) return GRASS
            }
            if (use == WOOD || w > WOOD_FARMED) return WOOD
            val d = min(min(wx - o.x0, o.x1 - wx), min(wz - o.z0, o.z1 - wz))
            if (d < max(VERGE_M, edgeM)) return VERGE
            return use
        }
        val pn = patch.at(cx, cz)
        return when (b) {
            0, 15 -> SAND
            1, 18 -> GRAVEL
            2 -> if (pn > .62) GRAVEL else if (pn < .3) MEADOW else DRY
            3 -> if (w > .42) WOOD else if (pn > .6) SCRUB else if (pn < .3) MEADOW else GRASS
            4 -> if (w > .4) WOOD else if (pn > .55) MEADOW else GRASS
            5 -> if (w > .3) WOOD else MEADOW
            6 -> if (w > .8) WOOD else if (pn > .6) DRY else if (pn < .35) GRASS else MEADOW
            7 -> if (w > .86) WOOD else if (pn > .58) SCRUB else DRY
            8 -> if (pn > .66) SCRUB else if (pn > .45) DIRT else SAND
            9 -> if (w > .78) WOOD else if (pn > .62) SCRUB else SPARSE
            10, 11 -> if (w > .22) WOOD else MEADOW
            12 -> if (w > .55 && p.abs < 2200) WOOD else if (pn > .66) GRAVEL else if (pn < .3) ROCK else MEADOW
            13 -> if (w > .72) WOOD else if (pn > .45) MARSH else MEADOW
            14 -> MARSH
            16 -> if (pn > .5) GRAVEL else ROCK
            17 -> if (w > .7) WOOD else if (pn > .55) SCRUB else DRY
            else -> GRASS
        }
    }

    // ------------------------------------------------------------------ what stands

    /**
     * One cell's things ([CELL_M] a side, its corner at world ([x0], [z0]) m): positions in metres from that corner, heights
     * (y) in the scene's metres (above the town's ground height, as the terrain draws).
     */
    class Scatter(val ci: Int, val cj: Int) {
        val x0: Double get() = ci * CELL_M
        val z0: Double get() = cj * CELL_M
        /** Trees and bushes: x, y, z, species (TreeKit's), variant, height (m), [TREE] floats each. */
        var trees = FloatArray(256); private set
        var treeCount = 0; private set
        /** Poles: x, y, z, the next pole's x, y, z (NaN: none), its height, its kind ([POWER], [TELEGRAPH]). */
        val poles = ArrayList<FloatArray>()
        /** Fences: x0, y0, z0, x1, y1, z1, kind ([WIRE], [RAIL]) (a run between two posts' rows). */
        val fences = ArrayList<FloatArray>()
        /** Signs: x, y, z, facing x, z, kind ([MILE], [BILLBOARD]), its picture's seed. */
        val signs = ArrayList<FloatArray>()
        /** Farm buildings: x, y, z, facing x, z, the model ([FARM_MODELS]). */
        val buildings = ArrayList<FloatArray>()
        /** The worker's time it took (ms). */
        var ms = 0f
        fun tree(x: Float, y: Float, z: Float, species: Int, variant: Int, h: Float) {
            if ((treeCount + 1) * TREE > trees.size) trees = trees.copyOf(trees.size * 2)
            val o = treeCount * TREE
            trees[o] = x; trees[o + 1] = y; trees[o + 2] = z; trees[o + 3] = species.toFloat(); trees[o + 4] = variant.toFloat(); trees[o + 5] = h
            treeCount++
        }
        val bytes: Long get() = trees.size * 4L + (poles.size + fences.size + signs.size) * 48L + 64
    }

    private val pt = ThreadLocal.withInitial { WorldTerrain.Point() }
    private val siteOf = ThreadLocal.withInitial { DoubleArray(4) }

    /** The point at world ([wx], [wz]) m (any thread; [p] reused). */
    private fun at(wx: Double, wz: Double, p: WorldTerrain.Point = pt.get()): WorldTerrain.Point =
        t.pointAt(wx / Planet.CELL_M, wz / Planet.CELL_M, Planet.wrapDx(wx - t.plan.origin.x), wz - t.plan.origin.z, p)

    /** Whether a thing may stand at [p]: on land, off the roads, rails, rivers, cliffs and the town's own ground. */
    private fun free(p: WorldTerrain.Point, clear: Double = 2.5): Boolean =
        p.road > clear && p.county > clear && p.rail > clear && p.river > 1.5 && p.cliff < .3 && p.outside > 0 && p.abs > 1.5 && p.y > -WorldTerrain.TOWN_UNDER_M + .01

    /** Cell ([ci], [cj])'s things (any thread). */
    fun scatter(ci: Int, cj: Int): Scatter {
        val t0 = System.nanoTime()
        val s = Scatter(ci, cj)
        val x0 = s.x0; val z0 = s.z0
        val p = WorldTerrain.Point()
        val o = Plot()
        // the woods, scrub, savanna and pasture trees: a jittered lattice, the cover deciding at each point
        run {
            val g = WOOD_STEP
            val i0 = ceil(x0 / g).toInt(); val i1 = ceil((x0 + CELL_M) / g).toInt() - 1
            val j0 = ceil(z0 / g).toInt(); val j1 = ceil((z0 + CELL_M) / g).toInt() - 1
            for (gj in j0..j1) for (gi in i0..i1) {
                val hx = WorldNoise.hashU(hs, gi, gj, 1); val hz = WorldNoise.hashU(hs, gi, gj, 2); val hk = WorldNoise.hashU(hs, gi, gj, 3)
                val wx = (gi + hx * .8) * g; val wz = (gj + hz * .8) * g
                val cx = wx / Planet.CELL_M; val cz = wz / Planet.CELL_M
                at(wx, wz, p)
                if (!free(p)) continue
                val c = cover(cx, cz, wx, wz, p, 0.0, o)
                val b = sk.biome(floor(cx).toInt(), floor(cz).toInt())
                val hv = WorldNoise.hashU(hs, gi, gj, 4)
                val (sp, h) = when (c) {
                    WOOD -> {
                        // (a clearing now and then; the wood's edge in smaller trees and bushes)
                        if (hk < .12) continue
                        val edge = woodiness(cx, cz, p) - (if (o.fx == 0 && o.fz == 0) .4 else WOOD_FARMED)
                        if (edge < .025 && hk < .5) BUSH_ROUND to (2.2 + hv * 1.6) else woodTree(b, hv, hk)
                    }
                    SCRUB -> if (hk < .3) (if (hv < .7) BUSH_ROUND else BUSH_TALL) to (1.4 + hv * 1.4) else if (hk > .985) woodTree(b, hv, .5) else continue
                    SPARSE -> if (hk > .965) (if (hv < .6) OAK else PINE) to (8.0 + hv * 7) else if (hk < .04) BUSH_ROUND to 1.8 else continue
                    PASTURE, MEADOW, GRASS, DRY -> if (hk > .997) (if (hv < .5) OAK else if (hv < .75) LINDEN else MAPLE) to (14.0 + hv * 7) else continue
                    MARSH -> if (hk > .9) (if (hv < .5) BIRCH else POPLAR) to (9.0 + hv * 6) else if (hk < .12) BUSH_TALL to 2.0 else continue
                    else -> continue
                }
                s.tree((wx - x0).toFloat(), (p.y - SINK_M).toFloat(), (wz - z0).toFloat(), sp, (hv * 997).toInt() and 7, h.toFloat())
            }
        }
        // the river banks' tree lines
        run {
            val g = 9.0
            val i0 = ceil(x0 / g).toInt(); val i1 = ceil((x0 + CELL_M) / g).toInt() - 1
            val j0 = ceil(z0 / g).toInt(); val j1 = ceil((z0 + CELL_M) / g).toInt() - 1
            // (only where a river runs near the cell)
            at(x0 + CELL_M / 2, z0 + CELL_M / 2, p)
            if (p.river < CELL_M) for (gj in j0..j1) for (gi in i0..i1) {
                val hk = WorldNoise.hashU(hs, gi, gj, 11)
                if (hk < .45) continue
                val wx = (gi + WorldNoise.hashU(hs, gi, gj, 12) * .8) * g; val wz = (gj + WorldNoise.hashU(hs, gi, gj, 13) * .8) * g
                at(wx, wz, p)
                if (p.river < 1.5 || p.river > 11 || !free(p)) continue
                val hv = WorldNoise.hashU(hs, gi, gj, 14)
                val sp = when { hv < .35 -> POPLAR; hv < .6 -> BIRCH; hv < .8 -> LINDEN; else -> OAK }
                s.tree((wx - x0).toFloat(), (p.y - SINK_M).toFloat(), (wz - z0).toFloat(), sp, (hv * 991).toInt() and 7, (12 + hv * 9).toFloat())
            }
        }
        plots(s, p, o)
        roads(s, p)
        s.ms = (System.nanoTime() - t0) / 1e6f
        synchronized(recent) { recent[key(ci, cj)] = s }
        return s
    }

    /**
     * Cell ([ci], [cj])'s things, the one [scatter] made lately if it is still held (the drawn country's and the cars'
     * statics' cells are mostly the same ones: made once), else made now (any thread).
     */
    fun scatterShared(ci: Int, cj: Int): Scatter = synchronized(recent) { recent[key(ci, cj)] } ?: scatter(ci, cj)

    private fun key(ci: Int, cj: Int): Long = (ci.toLong() shl 32) or (cj.toLong() and 0xffffffffL)
    /** The cells [scatter] made last ([RECENT] of them: a few hundred kB). */
    private val recent = object : LinkedHashMap<Long, Scatter>(64, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Scatter>?) = size > RECENT
    }

    private fun woodTree(b: Int, hv: Double, hk: Double): Pair<Int, Double> {
        val sp = when (b) {
            3 -> if (hv < .5) SPRUCE else if (hv < .8) PINE else BIRCH
            12 -> if (hv < .6) SPRUCE else PINE
            5 -> if (hv < .45) SPRUCE else if (hv < .65) LINDEN else if (hv < .85) MAPLE else OAK
            13 -> if (hv < .4) BIRCH else if (hv < .7) POPLAR else LINDEN
            9, 17 -> if (hv < .7) OAK else PINE
            6, 7 -> if (hv < .5) OAK else if (hv < .8) POPLAR else MAPLE
            else -> when { hv < .3 -> OAK; hv < .55 -> MAPLE; hv < .7 -> LINDEN; hv < .85 -> BIRCH; hv < .88 -> COPPER; hv < .95 -> SPRUCE; else -> PINE }
        }
        val h = when (sp) { SPRUCE -> 17.0; PINE -> 19.0; POPLAR -> 19.0; BIRCH -> 14.0; else -> 15.0 } * (.8 + .45 * hk)
        return sp to h
    }

    /** The plots' orchards, hedgerows, fences and tree lines in [s]'s cell. */
    private fun plots(s: Scatter, p: WorldTerrain.Point, o: Plot) {
        val f = WorldTerrain.FIELD_M
        val x0 = s.x0; val z0 = s.z0; val x1 = x0 + CELL_M; val z1 = z0 + CELL_M
        val q = Plot()
        for (fz in floor(z0 / f).toInt()..floor(z1 / f).toInt()) for (fx in floor(x0 / f).toInt()..floor(x1 / f).toInt()) {
            for (side in 0..1) {
                // (the sub-plot: found from a point inside it)
                val along = WorldNoise.hashU(tone, fx, fz, 5) < .5
                val cut = .3 + .4 * WorldNoise.hashU(tone, fx, fz, 6)
                val px = fx * f + (if (along) (if (side == 0) cut * f / 2 else cut * f + (1 - cut) * f / 2) else f / 2)
                val pz = fz * f + (if (!along) (if (side == 0) cut * f / 2 else cut * f + (1 - cut) * f / 2) else f / 2)
                plot(px, pz, o)
                val use = plotUse(o)
                if (use == NONE) continue
                if (use == ORCHARD) orchard(s, o, p)
                if (farm(o)) farmstead(s, o, p)
                // its west and north edges, and its split edge for side 1 (each edge drawn once, by the plot east or south of it)
                edge(s, o.x0, o.z0, o.x0, o.z1, o, use, p, q, 0)
                edge(s, o.x0, o.z0, o.x1, o.z0, o, use, p, q, 1)
            }
        }
    }

    /**
     * Plot [o]'s farmstead, if its yard's middle is in [s]'s cell: the farmhouse facing out of the plot, the barn beside it,
     * a silo or two by the barn, the machine shed, and by the era a tractor (from 1920), hay and troughs. Each stands on the
     * lowest of the ground under it (sunk a little: never a corner in the air).
     */
    private fun farmstead(s: Scatter, o: Plot, p: WorldTerrain.Point) {
        val site = farmSite(o, DoubleArray(4))
        if (floor(site[0] / CELL_M).toInt() != Math.floorMod(s.ci, CELLS_ROUND) || floor(site[1] / CELL_M).toInt() != s.cj) return
        val fx = site[2]; val fz = site[3]
        // (along: the house's front's way to its right; the yard runs back from the front)
        val rx = -fz; val rz = fx
        val h = WorldNoise.hash3(hs, o.fx * 2 + o.side, o.fz, 53)
        fun hb(k: Int): Int = ((h shr (k * 3)) and 7L).toInt()
        fun put(model: Int, along: Double, back: Double, faceX: Double, faceZ: Double, half: Double) {
            val x = site[0] + rx * along - fx * back; val z = site[1] + rz * along - fz * back
            var y = Double.MAX_VALUE
            for ((dx, dz) in CORNERS) { at(x + dx * half, z + dz * half, p); y = min(y, p.y) }
            at(x, z, p)
            if (!free(p, 4.0)) return
            s.buildings += floatArrayOf((x - s.x0).toFloat(), (y - .15).toFloat(), (z - s.z0).toFloat(), faceX.toFloat(), faceZ.toFloat(), model.toFloat())
        }
        put(if (hb(0) < 5) 0 else 1, -10.0, 4.0, fx, fz, 6.0)
        val barn = 2 + (if (hb(1) < 4) 0 else hb(1) % 4)
        put(barn, 14.0, 12.0, rx, rz, 9.0)
        put(if (hb(2) < 5) 6 else 7, 14.0, -1.0, fx, fz, 3.0)
        if (hb(3) < 4) put(6, 20.0, -1.0, fx, fz, 3.0)
        put(8, -12.0, 24.0, -fx, -fz, 6.0)
        if (year >= 1920 && hb(4) < 5) put(10, 0.0, 14.0, rx, rz, 2.5)
        if (hb(5) < 5) put(9, 26.0, 22.0, fx, fz, 3.0)
        if (hb(6) < 4) put(11, -2.0, 30.0, rx, rz, 2.0)
    }

    /** An orchard's trees in rows along its long side, in the part of plot [o] inside [s]'s cell. */
    private fun orchard(s: Scatter, o: Plot, p: WorldTerrain.Point) {
        val along = 6.0; val across = 7.5; val margin = 5.0
        val xs = if (o.rowsX) along else across; val zs = if (o.rowsX) across else along
        val ax0 = max(o.x0 + margin, s.x0); val ax1 = min(o.x1 - margin, s.x0 + CELL_M)
        val az0 = max(o.z0 + margin, s.z0); val az1 = min(o.z1 - margin, s.z0 + CELL_M)
        if (ax0 >= ax1 || az0 >= az1) return
        val kind = WorldNoise.hashU(hs, o.fx * 2 + o.side, o.fz, 21)
        val site = DoubleArray(4)
        var k = ceil((ax0 - o.x0 - margin) / xs).toInt()
        while (o.x0 + margin + k * xs < ax1) {
            val wx = o.x0 + margin + k * xs
            var m = ceil((az0 - o.z0 - margin) / zs).toInt()
            while (o.z0 + margin + m * zs < az1) {
                val wz = o.z0 + margin + m * zs
                at(wx, wz, p)
                if (free(p) && p.slope < .5 && !(farm(o) && farmSite(o, site).let { hypot(wx - it[0], wz - it[1]) < YARD_M + 6 })) {
                    val hv = WorldNoise.hashU(hs, k, m, o.fx xor (o.fz shl 8))
                    // (an orchard is one kind of fruit: apple, cherry in blossom..., its trees a little different each)
                    s.tree((wx - s.x0).toFloat(), (p.y - SINK_M).toFloat(), (wz - s.z0).toFloat(), FRUIT, ((kind * 4).toInt() + (if (hv < .25) 4 else 0)) and 7, (4.2 + hv * 1.6).toFloat())
                }
                m++
            }
            k++
        }
    }

    /**
     * Plot [o]'s edge from ([ax], [az]) to ([bx], [bz]) (west or north: [dir] 0 or 1), what of it lies in [s]'s cell: a
     * hedgerow, a fence or a tree line, by the crop belt and the uses either side, or nothing.
     */
    private fun edge(s: Scatter, ax: Double, az: Double, bx: Double, bz: Double, o: Plot, use: Int, p: WorldTerrain.Point, q: Plot, dir: Int) {
        val len = hypot(bx - ax, bz - az)
        val ux = (bx - ax) / len; val uz = (bz - az) / len
        // the plot on the other side
        val mx = (ax + bx) / 2 - (if (dir == 0) 3.0 else 0.0)
        val mz = (az + bz) / 2 - (if (dir == 1) 3.0 else 0.0)
        plot(mx, mz, q)
        val other = plotUse(q)
        val id = WorldNoise.hash3(hs, o.fx * 2 + o.side, o.fz, 31 + dir)
        val r = (id and 0xffff) / 65536.0
        val cx = (ax + bx) / 2 / Planet.CELL_M; val cz = (az + bz) / 2 / Planet.CELL_M
        val crop = crop(floor(cx).toInt(), floor(cz).toInt())
        val hedgeP = when (crop) { 4, 6 -> .5; 1, 2, 3 -> .12; 7 -> .04; else -> .25 }
        val pasture = use == PASTURE || other == PASTURE
        val kind = when {
            use == WOOD && (other == WOOD || other == NONE) -> return
            r < hedgeP -> HEDGE
            r < hedgeP + (if (pasture) .6 else .08) -> if (year >= 1880 && (id shr 16 and 3) != 0L) WIRE else RAIL
            r < hedgeP + (if (pasture) .6 else .08) + .08 -> TREELINE
            else -> return
        }
        // the part of the edge in the cell
        val t0 = max(0.0, if (abs(ux) > .5) (s.x0 - ax) / ux else (s.z0 - az) / uz)
        val t1 = min(len, if (abs(ux) > .5) (s.x0 + CELL_M - ax) / ux else (s.z0 + CELL_M - az) / uz)
        // (and the edge must cross the cell the other way too)
        val across = if (abs(ux) > .5) az else ax
        val lo = if (abs(ux) > .5) s.z0 else s.x0
        if (across < lo || across >= lo + CELL_M || t1 <= t0) return
        val step = when (kind) { HEDGE -> 1.9; TREELINE -> 9.0; else -> 3.6 }
        // (a fence starts from its last post before the cell: the run across the cell's edge is this cell's)
        val fence = kind == WIRE || kind == RAIL
        var k = max(0, ceil(t0 / step).toInt() - (if (fence) 1 else 0))
        var px = Double.NaN; var py = 0.0; var pz = 0.0
        while (k * step < t1) {
            val tt = k * step
            val hv = WorldNoise.hashU(hs, (ax * 7 + tt).toInt(), (az * 7).toInt(), 41)
            // (a hedge's bushes and trees stand a little off the line, not on a ruler)
            val wob = if (kind == HEDGE) (hv - .5) * .6 else 0.0
            val wx = ax + ux * tt - uz * wob; val wz = az + uz * tt + ux * wob
            at(wx, wz, p)
            val ok = free(p, 3.5) && p.slope < .7
            if (ok && tt < t0) { px = wx; py = p.y; pz = wz; k++; continue }
            if (ok) {
                val lx = (wx - s.x0).toFloat(); val lz = (wz - s.z0).toFloat(); val y = p.y.toFloat()
                when (kind) {
                    HEDGE -> {
                        if (hv > .93) s.tree(lx, y - SINK_M.toFloat(), lz, if (hv > .975) LINDEN else OAK, (hv * 4001).toInt() and 7, (11 + hv * 6).toFloat())
                        else if (hv > .04) s.tree(lx, y - .1f, lz, if (hv < .62) BUSH_ROUND else BUSH_TALL, (hv * 3001).toInt() and 7, (1.9 + hv * 1.3).toFloat())
                    }
                    TREELINE -> s.tree(lx, y - SINK_M.toFloat(), lz, if (hv < .5) POPLAR else if (hv < .8) OAK else LINDEN, (hv * 2003).toInt() and 7, (13 + hv * 7).toFloat())
                    else -> if (!px.isNaN()) s.fences += floatArrayOf((px - s.x0).toFloat(), py.toFloat(), (pz - s.z0).toFloat(), lx, y, lz, kind.toFloat())
                }
                px = wx; py = p.y; pz = wz
            } else px = Double.NaN
            k++
        }
    }

    /** The poles, wires, mile markers and billboards along the roads and railways in [s]'s cell. */
    private fun roads(s: Scatter, p: WorldTerrain.Point) {
        val ox = t.plan.origin.x; val oz = t.plan.origin.z
        val segs = t.segsNear(Planet.wrapDx(s.x0 - ox), s.z0 - oz, CELL_M)
        if (segs.isEmpty()) return
        val out = DoubleArray(4)
        // (each line's stretch near the cell: its arc range)
        val ranges = HashMap<Int, DoubleArray>()
        for (g in segs) {
            if (g.kind == WorldFarPlan.RIVER) continue
            val r = ranges.getOrPut(g.line) { doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE) }
            r[0] = min(r[0], g.s0); r[1] = max(r[1], g.s0 + g.length)
        }
        fun inCell(x: Double, z: Double): Boolean {
            val wx = Planet.wrapX(x + ox); val wz = z + oz
            return floor(wx / CELL_M).toInt() == Math.floorMod(s.ci, CELLS_ROUND) && floor(wz / CELL_M).toInt() == s.cj
        }
        fun lx(x: Double): Float = Planet.wrapDx(x + ox - s.x0).toFloat()
        fun lz(z: Double): Float = (z + oz - s.z0).toFloat()
        fun pointLocal(x: Double, z: Double, q: WorldTerrain.Point) = t.pointAt(Planet.wrapX(x + ox) / Planet.CELL_M, (z + oz) / Planet.CELL_M, x, z, q)
        val q = WorldTerrain.Point()
        for ((l, r) in ranges) {
            val kind = t.lineKind(l)
            val half = t.lineHalf(l)
            val lh = WorldNoise.hash3(hs, l, 77)
            val side = if (lh and 1L == 0L) 1.0 else -1.0
            // the poles: power lines along the roads (from 1925; not every highway), telegraph poles along the railways
            val rail = kind == WorldFarPlan.RAIL
            val polesOn = if (rail) year >= 1870 else year >= 1925 && (kind == WorldFarPlan.COUNTY || (lh shr 3) % 3 != 0L)
            if (polesOn) {
                val gap = if (rail) 48.0 else 42.0
                val off = half + (if (rail) 6.0 else 4.5)
                for (k in ceil((r[0] - CELL_M) / gap).toInt().coerceAtLeast(0)..floor((r[1] + CELL_M) / gap).toInt()) {
                    if (!t.lineAt(l, k * gap, out)) continue
                    val px = out[0] - out[3] * off * side; val pz = out[1] + out[2] * off * side
                    if (!inCell(px, pz)) continue
                    pointLocal(px, pz, p)
                    if (!poleOk(p, half, off)) continue
                    var nx = Float.NaN; var ny = Float.NaN; var nz = Float.NaN
                    if (t.lineAt(l, (k + 1) * gap, out)) {
                        val qx = out[0] - out[3] * off * side; val qz = out[1] + out[2] * off * side
                        pointLocal(qx, qz, q)
                        if (poleOk(q, half, off)) { nx = lx(qx); ny = q.y.toFloat(); nz = lz(qz) }
                    }
                    s.poles += floatArrayOf(lx(px), p.y.toFloat(), lz(pz), nx, ny, nz, if (rail) 7.5f else 9f, if (rail) TELEGRAPH.toFloat() else POWER.toFloat())
                }
            }
            if (kind != WorldFarPlan.HIGHWAY) continue
            // mile markers on the right of each direction (from 1930), a mile apart
            if (year >= 1930) {
                val mile = 1609.344
                for (k in ceil((r[0] - CELL_M) / mile).toInt().coerceAtLeast(1)..floor((r[1] + CELL_M) / mile).toInt()) {
                    if (!t.lineAt(l, k * mile, out)) continue
                    for (dir in DIRS) {
                        val off = half + 2.2
                        val px = out[0] - out[3] * off * dir; val pz = out[1] + out[2] * off * dir
                        if (!inCell(px, pz)) continue
                        pointLocal(px, pz, p)
                        if (p.river < 1.5 || p.outside <= 0) continue
                        s.signs += floatArrayOf(lx(px), p.y.toFloat(), lz(pz), (out[3] * dir).toFloat(), (-out[2] * dir).toFloat(), MILE.toFloat(), k.toFloat())
                    }
                }
            }
            // billboards set back from the highway (from 1915), now and then
            if (year >= 1915) {
                val gap = 1900.0
                for (k in ceil((r[0] - CELL_M - 400) / gap).toInt().coerceAtLeast(0)..floor((r[1] + CELL_M) / gap).toInt()) {
                    val hb = WorldNoise.hashU(hs, l, k, 91)
                    if (hb > .55) continue
                    if (!t.lineAt(l, k * gap + 300, out)) continue
                    val dir = if (hb < .27) 1.0 else -1.0
                    val off = half + 18 + hb * 20
                    val px = out[0] - out[3] * off * dir; val pz = out[1] + out[2] * off * dir
                    if (!inCell(px, pz)) continue
                    pointLocal(px, pz, p)
                    if (!free(p, 6.0) || p.slope > .35) continue
                    // (facing the oncoming traffic a little: turned 30 degrees from square to the road)
                    val fx = out[3] * dir * .87 - out[2] * .5; val fz = -out[2] * dir * .87 - out[3] * .5
                    s.signs += floatArrayOf(lx(px), p.y.toFloat(), lz(pz), fx.toFloat(), fz.toFloat(), BILLBOARD.toFloat(), (WorldNoise.hash3(hs, l, k, 92) % 1000).toFloat())
                }
            }
        }
    }
    private fun poleOk(p: WorldTerrain.Point, half: Double, off: Double): Boolean =
        p.river > 2.0 && p.cliff < .4 && p.outside > 0 && p.abs > 1.0 && p.slope < .9 && p.y > -WorldTerrain.TOWN_UNDER_M + .01 &&
            min(p.road, min(p.county, p.rail)) > off - half - 2.5

    companion object {
        /** The cells [scatterShared] can find made ([scatter]'s last ones). */
        const val RECENT = 96
        /** A scatter cell's side (m): 64 tiles. */
        const val CELL_M = 64 * Planet.TILE_M
        /** Scatter cells round the planet (east-west). */
        val CELLS_ROUND: Int = Math.round(Planet.C / CELL_M).toInt()
        /** Floats a tree in [Scatter.trees]. */
        const val TREE = 6
        /** The woods' lattice (m): a tree about every this many metres. */
        const val WOOD_STEP = 7.2
        /** Trees stand this far into the ground (m): the terrain's coarser levels never leave a root in the air. */
        const val SINK_M = .35
        /** The woods' threshold on farmed land ([woodiness]). */
        const val WOOD_FARMED = .66
        /** A field's grass verge (m). */
        const val VERGE_M = 2.4
        /** A plot has a farmstead this often; its yard's middle this far in from its corner, its yard this wide (radius, m). */
        const val FARM_P = .07
        const val FARM_IN_M = 34.0
        const val YARD_M = 30.0
        /** The farm models a farmstead is built of (assets/farm/buildings, the capital's farms' own). */
        val FARM_MODELS = listOf("fm_house1", "fm_house2", "fm_barn_red", "fm_barn_grey", "fm_barn_teal", "fm_barn_yellow", "fm_silo1", "fm_silo2", "fm_shed", "fm_hay", "fm_tractor", "fm_troughs")

        /** The covers ([cover]). */
        const val NONE = -1
        const val GRASS = 0; const val MEADOW = 1; const val PASTURE = 2; const val DRY = 3; const val SCRUB = 4; const val WOOD = 5
        const val WHEAT = 6; const val CORN = 7; const val PLOUGHED = 8; const val FALLOW = 9; const val ORCHARD = 10; const val MARSH = 11
        const val SAND = 12; const val GRAVEL = 13; const val ROCK = 14; const val DIRT = 15; const val PADDY = 16; const val VERGE = 17; const val SPARSE = 18

        /** Each crop belt's uses and their shares (the skeleton's crops: none, wheat, corn, cotton, orchards, rice, dairy, ranching). */
        val USES: Array<DoubleArray> = arrayOf(
            doubleArrayOf(PASTURE.toDouble(), 1.0),
            doubleArrayOf(WHEAT.toDouble(), .42, PLOUGHED.toDouble(), .14, CORN.toDouble(), .1, FALLOW.toDouble(), .06, PASTURE.toDouble(), .14, MEADOW.toDouble(), .1, WOOD.toDouble(), .04),
            doubleArrayOf(CORN.toDouble(), .42, WHEAT.toDouble(), .12, PLOUGHED.toDouble(), .14, FALLOW.toDouble(), .05, PASTURE.toDouble(), .14, MEADOW.toDouble(), .09, WOOD.toDouble(), .04),
            doubleArrayOf(CORN.toDouble(), .36, PLOUGHED.toDouble(), .24, FALLOW.toDouble(), .1, WHEAT.toDouble(), .08, PASTURE.toDouble(), .14, WOOD.toDouble(), .08),
            doubleArrayOf(ORCHARD.toDouble(), .34, PASTURE.toDouble(), .2, MEADOW.toDouble(), .14, CORN.toDouble(), .1, WHEAT.toDouble(), .07, PLOUGHED.toDouble(), .06, WOOD.toDouble(), .09),
            doubleArrayOf(PADDY.toDouble(), .55, CORN.toDouble(), .15, PLOUGHED.toDouble(), .1, PASTURE.toDouble(), .1, WOOD.toDouble(), .1),
            doubleArrayOf(PASTURE.toDouble(), .46, MEADOW.toDouble(), .22, CORN.toDouble(), .1, WHEAT.toDouble(), .08, PLOUGHED.toDouble(), .06, WOOD.toDouble(), .08),
            doubleArrayOf(PASTURE.toDouble(), .55, DRY.toDouble(), .2, SCRUB.toDouble(), .12, WHEAT.toDouble(), .06, WOOD.toDouble(), .07),
        )

        /** The biomes farmed where the skeleton has a crop (temperate forest, prairie, steppe, savanna, chaparral). */
        private val FARMED = setOf(4, 6, 7, 9, 17)

        /** TreeKit's species (the Filament side grows them). */
        const val OAK = 0; const val MAPLE = 1; const val LINDEN = 2; const val COPPER = 3; const val BIRCH = 4; const val FRUIT = 5
        const val PINE = 6; const val SPRUCE = 7; const val POPLAR = 8; const val BUSH_ROUND = 9; const val BUSH_TALL = 10

        /** Edge kinds, fences' and poles' and signs' kinds. */
        const val HEDGE = 0; const val WIRE = 1; const val RAIL = 2; const val TREELINE = 3
        const val POWER = 0; const val TELEGRAPH = 1
        private val DIRS = doubleArrayOf(1.0, -1.0)
        private val CORNERS = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
        const val MILE = 0; const val BILLBOARD = 1

        private fun sat(t: Double): Double = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
        private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
    }
}
