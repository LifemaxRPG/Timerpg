package com.lifemax.rpg.feature.habits.vehicle

import com.lifemax.rpg.feature.habits.domain.FarmSolids
import com.lifemax.rpg.feature.habits.domain.FarmThing
import com.lifemax.rpg.feature.habits.domain.HubFrame
import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldCountry
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.Statics
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The open country's solid things in the cars' physics (docs/WORLD_STREAM.md, step 1b): what [WorldCountry.scatter] stands
 * in a cell, as Jolt statics round the driven car, so it crashes into a tree, a pole, a fence or a farm building instead of
 * driving through it. Made from the same cells the country is drawn from (a function of the world position alone), so
 * what is hit is what is seen.
 *
 *  - **Trees**: an upright cylinder each, the trunk (its radius by the tree's height, as TreeKit grows it), [TRUNK_M]
 *    tall from its foot; bushes and the hedges' bushes are not solid (a car goes through a hedge, as through the town's).
 *  - **Poles** (power and telegraph): a cylinder each, its whole height.
 *  - **Fences**: their runs as thin boxes as tall as the posts, runs on one line joined up to [FENCE_JOIN_M] (a wire fence
 *    stops a car as a wall would: the car crashes, it does not phase through; a break-through is for later).
 *  - **Billboards**: their two legs; **mile markers** are left out (a post a car snaps).
 *  - **Farm buildings** ([shapes] given): each model's standing parts as boxes, FarmSolids' way (walls, posts, tanks,
 *    bales, the tractor; not roofs or cards).
 *
 * Cells of [WorldCountry.CELL_M] within [REACH_M] of the car, or of where it will be [LEAD_S] ahead, are made on a worker
 * ([pool]: the scatter, mostly the one the drawn country made already, [WorldCountry.scatterShared], and its records) and
 * added on the game's thread as one batch ([Physics.addStatics]: Jolt's broad-phase batch add); cells past [REACH_M] +
 * [KEEP_M] go as one batch ([Physics.removeAll]); the broad phase is optimised again after [OPTIMIZE_AFTER] bodies.
 *
 * Coordinates: the physics' metres in the capital's frame ([frame]: street units over [VehicleWorld.U]), heights the
 * terrain's metres (the cars' ground, TownPlay.groundFor).
 */
class CountrySolids(
    private val frame: HubFrame,
    /** The country now (the far view's terrain's, swapped at re-plans), or null before the terrain is made. */
    private val country: () -> WorldCountry?,
    /** The farm models' shapes (assets/farm/buildings/models.json): their walls solid; null: the farm buildings are not. */
    private val shapes: Map<String, ModelShape>? = null,
    /** A cell's things (a test's own); null: [country]'s. */
    private val source: ((Int, Int) -> WorldCountry.Scatter?)? = null,
) {
    private class Cell(val ci: Int, val cj: Int) {
        var handles: IntArray? = null
        var job: java.util.concurrent.Future<FloatArray?>? = null
        var count = 0
    }

    private val cells = HashMap<Long, Cell>()
    /** World metres in a physics metre (the capital's street units: [VehicleWorld.U] a physics metre, the frame's its metre). */
    private val k = VehicleWorld.U / frame.unitsPerMetre
    private val want = HashSet<Long>()
    private val keep = HashSet<Long>()
    private var sinceOptimize = 0

    // ---- measurements
    /** Statics held now, cells in the physics, batches added, the longest add, remove and optimise (ms, this thread), the workers' time a cell (ms). */
    var bodies = 0; private set
    val cellCount: Int get() = cells.values.count { it.handles != null }
    var batches = 0; private set
    var addMaxMs = 0f; private set
    var removeMaxMs = 0f; private set
    var optimizeMaxMs = 0f; private set
    var optimizes = 0; private set
    private val buildMs = java.util.Collections.synchronizedList(ArrayList<Float>())
    /** A cell wanted at the car itself was not made yet (the worker behind). */
    var late = 0; private set
    val report: String get() {
        val b = synchronized(buildMs) { buildMs.sorted() }
        return "country solids: $bodies statics in $cellCount cells, $batches batches added (longest %.2f ms), removals longest %.2f ms, %d optimisations (longest %.2f ms); a cell's records on the worker: %s; late %d".format(java.util.Locale.US,
            addMaxMs, removeMaxMs, optimizes, optimizeMaxMs, if (b.isEmpty()) "none" else "median %.1f ms, max %.1f".format(java.util.Locale.US, b[b.size / 2], b.last()), late)
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

    /** The cell's corner in physics metres from the frame's origin (x east, z south). */
    private fun cellX(ci: Int): Double = Planet.wrapDx(ci * WorldCountry.CELL_M - frame.origin.x) / k
    private fun cellZ(cj: Int): Double = (cj * WorldCountry.CELL_M - frame.origin.z) / k

    /**
     * Keeps the cells round the car at ([xm], [zm]) physics metres, moving at ([vx], [vz]) m/s, in [physics]: the game's
     * thread, each step of the drive (cheap when nothing changed: a few look-ups).
     */
    fun stream(physics: Physics, xm: Float, zm: Float, vx: Float, vz: Float) {
        val wx = frame.origin.x + xm * k; val wz = frame.origin.z + zm * k
        val cm = WorldCountry.CELL_M
        want.clear(); keep.clear()
        // (now, halfway to the lead and the lead)
        for (pass in 0..2) {
            val t = LEAD_S * pass / 2
            val px = wx + vx * k * t; val pz = wz + vz * k * t
            val i0 = floor((px - REACH_M - KEEP_M) / cm).toInt(); val i1 = floor((px + REACH_M + KEEP_M) / cm).toInt()
            val j0 = floor((pz - REACH_M - KEEP_M) / cm).toInt(); val j1 = floor((pz + REACH_M + KEEP_M) / cm).toInt()
            for (j in j0..j1) for (i in i0..i1) {
                val d = rectDistance(i * cm, j * cm, cm, px, pz)
                if (d < REACH_M) want += key(Math.floorMod(i, WorldCountry.CELLS_ROUND), j)
                if (d < REACH_M + KEEP_M) keep += key(Math.floorMod(i, WorldCountry.CELLS_ROUND), j)
            }
        }
        // the car's own cell first, then the rest: made on the worker
        val ctry = country()
        if (ctry != null || source != null) for (kk in want) {
            val c = cells.getOrPut(kk) { Cell((kk shr 32).toInt(), kk.toInt()) }
            if (c.handles == null && c.job == null) {
                val ci = c.ci; val cj = c.cj
                c.job = pool.submit(java.util.concurrent.Callable { records(ci, cj, ctry) })
            }
        }
        // finished cells added, one batch a step (the car's own at once whatever came before)
        val ci0 = Math.floorMod(floor(wx / cm).toInt(), WorldCountry.CELLS_ROUND); val cj0 = floor(wz / cm).toInt()
        var added = 0
        for (kk in want) {
            val c = cells[kk] ?: continue
            val j = c.job ?: continue
            val own = c.ci == ci0 && c.cj == cj0
            if (!j.isDone) { if (own) late++; continue }
            if (added > 0 && !own) continue
            c.job = null
            val rec = runCatching { j.get() }.getOrNull()
            val n = (rec?.size ?: 0) / Statics.COUNT
            val h = IntArray(n)
            if (n > 0) {
                val t0 = System.nanoTime()
                physics.addStatics(rec!!, n, h)
                addMaxMs = max(addMaxMs, (System.nanoTime() - t0) / 1e6f)
                batches++
                sinceOptimize += n
            }
            c.handles = h; c.count = h.count { it >= 0 }
            bodies += c.count
            added++
        }
        // cells past the keep: out in one batch each
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val (kk, c) = it.next()
            if (kk in keep) continue
            c.job?.cancel(false)
            c.handles?.let { h ->
                val t0 = System.nanoTime()
                physics.removeAll(h, h.size)
                removeMaxMs = max(removeMaxMs, (System.nanoTime() - t0) / 1e6f)
                bodies -= c.count
            }
            it.remove()
        }
        if (sinceOptimize >= OPTIMIZE_AFTER) {
            val t0 = System.nanoTime()
            physics.optimize()
            optimizeMaxMs = max(optimizeMaxMs, (System.nanoTime() - t0) / 1e6f)
            optimizes++
            sinceOptimize = 0
        }
    }

    /** Everything out of [physics] (the world closed, or its ground made again). */
    fun clear(physics: Physics) {
        for (c in cells.values) { c.job?.cancel(false); c.handles?.let { physics.removeAll(it, it.size) } }
        cells.clear(); bodies = 0
    }

    /** Cell ([ci], [cj])'s statics' records (a worker), in physics metres; null for none. */
    private fun records(ci: Int, cj: Int, ctry: WorldCountry?): FloatArray? {
        val t0 = System.nanoTime()
        val d = source?.invoke(ci, cj) ?: ctry?.scatterShared(ci, cj) ?: return null
        val ox = cellX(ci); val oz = cellZ(cj)
        val out = Records()
        fun px(lx: Float): Float = (ox + lx / k).toFloat()
        fun pz(lz: Float): Float = (oz + lz / k).toFloat()
        // the trunks (not the bushes)
        val tr = d.trees
        for (t in 0 until d.treeCount) {
            val o = t * WorldCountry.TREE
            val sp = tr[o + 3].toInt()
            if (sp == WorldCountry.BUSH_ROUND || sp == WorldCountry.BUSH_TALL) continue
            val h = tr[o + 5]
            val r = q(min(max(h * TRUNK_K, TRUNK_MIN), TRUNK_MAX), .02f)
            out.cylinder(px(tr[o]), tr[o + 1] + TRUNK_M / 2, pz(tr[o + 2]), r, TRUNK_M / 2)
        }
        // the poles
        for (p in d.poles) {
            val h = p[6] + .3f
            out.cylinder(px(p[0]), p[1] - .3f + h / 2, pz(p[2]), POLE_R, h / 2)
        }
        // the fences: runs on one line joined, as thin boxes as tall as the posts
        var i = 0
        val fs = d.fences
        while (i < fs.size) {
            val a = fs[i]
            // (a run: x0, y0, z0, x1, y1, z1, kind)
            var bx = a[3]; var bz = a[5]; var lo = min(a[1], a[4]); var hi = max(a[1], a[4])
            val ux = a[3] - a[0]; val uz = a[5] - a[2]
            val ul = hypot(ux, uz).coerceAtLeast(1e-3f)
            var j = i + 1
            while (j < fs.size) {
                val n = fs[j]
                // (the next run goes on from this one's end, the same kind, on the same line, and the whole not too long)
                if (n[6] != a[6] || abs(n[0] - bx) > .01f || abs(n[2] - bz) > .01f) break
                val vx = n[3] - n[0]; val vz = n[5] - n[2]
                val vl = hypot(vx, vz).coerceAtLeast(1e-3f)
                if ((ux * vx + uz * vz) / (ul * vl) < .995f) break
                if (hypot(n[3] - a[0], n[5] - a[2]) > FENCE_JOIN_M) break
                bx = n[3]; bz = n[5]; lo = min(lo, min(n[1], n[4])); hi = max(hi, max(n[1], n[4]))
                j++
            }
            val dx = bx - a[0]; val dz = bz - a[2]
            val l = hypot(dx, dz)
            if (l > .05f) {
                val bottom = lo - .2f; val top = hi + FENCE_H
                out.box(px((a[0] + bx) / 2), (bottom + top) / 2, pz((a[2] + bz) / 2), l / k.toFloat() / 2 + POST_HALF, (top - bottom) / 2, FENCE_HALF, atan2(-dz, dx))
            }
            i = j
        }
        // the billboards' legs (WorldPropKit's: two legs 3.4 m either side of the middle, a hand behind it, to the board's top)
        for (s in d.signs) {
            if (s[5].toInt() != WorldCountry.BILLBOARD) continue
            val fl = hypot(s[3], s[4]).coerceAtLeast(1e-3f)
            val nx = s[3] / fl; val nz = s[4] / fl
            val rx = nz; val rz = -nx
            for (side in floatArrayOf(-3.4f, 3.4f)) {
                val lx = s[0] + rx * side - nx * .15f; val lz = s[2] + rz * side - nz * .15f
                out.box(px(lx), s[1] + 2.77f, pz(lz), .12f, 3.07f, .12f, 0f)
            }
        }
        // the farm buildings' standing parts
        val sh = shapes
        if (sh != null) {
            val u = frame.unitsPerMetre.toFloat()
            for (b in d.buildings) {
                val key = WorldCountry.FARM_MODELS.getOrNull(b[5].toInt()) ?: continue
                val shape = sh[key] ?: continue
                val pl = FarmSolids.place(FarmThing(key, b[0] * u, b[2] * u, b[3], b[4], false, false), shape)
                val kk = FarmSolids.K
                for (p in shape.parts) {
                    if (p.card || p.type == ModelPart.DECAL || p.type == ModelPart.RAIL || p.type == ModelPart.SLOPE || p.type == ModelPart.GABLE || p.type == ModelPart.HIP) continue
                    // (what stands on the ground up to a car's roof: not a sign high on a wall)
                    if (p.y0 * kk / u > 1.2f) continue
                    val xc = (p.x0 + p.x1) / 2f; val dc = (p.d0 + p.d1) / 2f
                    val wx = (pl[0] + pl[2] * xc * kk - pl[4] * dc * kk) / u; val wz = (pl[1] + pl[3] * xc * kk - pl[5] * dc * kk) / u
                    val hx = abs(p.x1 - p.x0) / 2f * kk / u; val hd = abs(p.d1 - p.d0) / 2f * kk / u
                    if (hx < .1f || hd < .1f) continue
                    val y0 = b[1] + p.y0 * kk / u - .3f; val y1 = b[1] + p.y1 * kk / u
                    if (y1 - y0 < .2f) continue
                    out.box(px(wx), (y0 + y1) / 2, pz(wz), hx / k.toFloat(), (y1 - y0) / 2, hd / k.toFloat(), atan2(-pl[3], pl[2]))
                }
            }
        }
        buildMs += (System.nanoTime() - t0) / 1e6f
        return out.array()
    }

    private class Records {
        var f = FloatArray(Statics.COUNT * 256); var n = 0
        private fun put(kind: Int, x: Float, y: Float, z: Float, a: Float, b: Float, c: Float, yaw: Float) {
            if ((n + 1) * Statics.COUNT > f.size) f = f.copyOf(f.size * 2)
            val o = n * Statics.COUNT
            f[o + Statics.KIND] = kind.toFloat(); f[o + Statics.X] = x; f[o + Statics.Y] = y; f[o + Statics.Z] = z
            f[o + Statics.HX] = a; f[o + Statics.HY] = b; f[o + Statics.HZ] = c; f[o + Statics.YAW] = yaw; f[o + Statics.FRICTION] = FRICTION
            n++
        }
        fun cylinder(x: Float, y: Float, z: Float, r: Float, hh: Float) = put(Statics.CYLINDER, x, y, z, r, hh, 0f, 0f)
        fun box(x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float, yaw: Float) = put(Statics.BOX, x, y, z, hx, hy, hz, yaw)
        fun array(): FloatArray? = if (n == 0) null else f.copyOf(n * Statics.COUNT)
    }

    companion object {
        /** Cells within this of the car (or its lead) are in the physics (m); kept this much further. */
        const val REACH_M = 70.0
        const val KEEP_M = 40.0
        /** The lead (s): the cells round where the car will be are made before it gets there. */
        const val LEAD_S = 2.0
        /** A trunk's collider: this tall from its foot (m), its radius this share of the tree's height, within these (m). */
        const val TRUNK_M = 3.5f
        const val TRUNK_K = .035f
        const val TRUNK_MIN = .12f
        const val TRUNK_MAX = .6f
        const val POLE_R = .15f
        /** A fence's box: as tall as its posts over the ground (m), this thick (half, m), a post's half more at each end; runs joined to this long (m). */
        const val FENCE_H = 1.25f
        const val FENCE_HALF = .1f
        const val POST_HALF = .06f
        const val FENCE_JOIN_M = 18f
        const val FRICTION = .5f
        /** The broad phase is optimised again once this many bodies were added since the last time. */
        const val OPTIMIZE_AFTER = 4000

        private fun q(v: Float, step: Float): Float = (v / step).roundToInt() * step

        private fun rectDistance(x0: Double, z0: Double, size: Double, x: Double, z: Double): Double {
            val dx = max(0.0, max(x0 - x, x - (x0 + size)))
            val dz = max(0.0, max(z0 - z, z - (z0 + size)))
            return hypot(dx, dz)
        }

        /** The worker making the cells' records (one, below the game's priority). */
        private val pool: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-country").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 } }
        }
    }
}
