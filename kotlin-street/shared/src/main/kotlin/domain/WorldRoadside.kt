package com.lifemax.rpg.feature.habits.domain

import kotlin.math.floor
import kotlin.math.hypot

/**
 * The highways' roadside sites in the world (planet step 2, docs/TOWNS_EVERYWHERE.md "Highways", WORLD_STREAM.md section
 * 10): what stands beside the roads between the towns in a [year], each with the hand-made model it wears (the landmark
 * catalogue's) and the way it faces (its front to the road):
 *
 *  - Highways v2's own sites (src/world/gen/highways.ts step 13, baked in the skeleton): truck stops, motels and diners in
 *    the quadrants round the expressways' exits, rest areas beside them (weigh stations and toll plazas have no model yet);
 *  - the stops along the ordinary highways and county roads, as src/road `roadsideAlong` spaces them (a stop every 14 miles
 *    of highway, 9 of county road; wider before 1940, closer from 1980; diners, gas stations and, from 1940, motels; the
 *    same keyed dice, [rand], so the game's road encounters and the world agree on what stands where), each on the side the
 *    dice say, its lot 40 m off the road.
 *
 * None inside a town's ground (the towns have their own) or the capital's, on water, or within 400 m of another. Each
 * site's ground is levelled to the road's grade under its pad ([pads]: [WorldTownSet] rows the terrain flattens as it does
 * a town's). Engine-agnostic; the drawing is WorldTowns'.
 */
object WorldRoadside {
    class Site(
        val id: String, val kind: String,
        /** Its lot's middle (planet metres) and its front's heading (unit, x east z south: towards the road). */
        val x: Double, val z: Double, val fx: Double, val fz: Double,
        /** The landmark model it wears ([LandmarkSpec.kind]), the ground's height under it (m above sea level). */
        val model: String, val heightM: Double,
    ) {
        override fun toString(): String = "$kind ($model) at %.2f, %.2f km".format(java.util.Locale.US, x / 1000, z / 1000)
    }

    /** The model a site of [kind] wears in [year] ([models]: Highways v2's own for it), or null (none drawn). */
    fun modelOf(kind: String, year: Int, models: List<WorldHighways.Model> = emptyList()): String? {
        models.firstOrNull { year >= it.from && (it.to == null || year <= it.to) }?.let { return it.id }
        return when (kind) {
            "gas_station", "gas station" -> if (year >= 2005) "landmark_glb_modern_gas_station" else if (year >= 1950) "landmark_glb_main_street_service_station" else null
            "diner" -> if (year >= 1940) "landmark_glb_junction_diner" else null
            "motel" -> if (year >= 1950) "landmark_glb_roadside_motel" else null
            "truck stop" -> if (year >= 1990) "landmark_glb_truck_stop_1990" else if (year >= 1960) "landmark_glb_truck_stop_1960" else null
            "rest area" -> if (year >= 1975) "landmark_glb_highway_rest_area" else null
            else -> null
        }
    }

    /**
     * Every site in [year] on [sk] (the capital's ground [capital], planet metres x0, z0, x1, z1 unrotated, and the generated
     * towns [towns] left alone).
     */
    fun sites(sk: WorldSkeleton, year: Int, towns: WorldTownSet, capital: HubFrame?): List<Site> {
        val hw = sk.highways ?: return emptyList()
        val segs = hw.at(year).filter { (_, st) -> st.cls != "abandoned" }
        // the segments' points by 2 km cell, for a site's nearest road
        val grid = HashMap<Long, MutableList<DoubleArray>>()
        fun key(x: Double, z: Double) = (Math.floorMod(floor(x / 2000).toInt(), 4096).toLong() shl 32) or (floor(z / 2000).toLong() and 0xffffffffL)
        val pathsM = HashMap<String, DoubleArray>()
        for ((s, _) in segs) {
            val p = sk.points(s.path)
            val m = DoubleArray(p.size) { if (it % 2 == 0) Planet.wrapX(p[it] * Planet.CELL_M) else p[it] * Planet.CELL_M }
            pathsM[s.id] = m
            for (k in 0 until m.size / 2 - 1) grid.getOrPut(key(m[k * 2], m[k * 2 + 1])) { ArrayList() } += doubleArrayOf(m[k * 2], m[k * 2 + 1], m[k * 2 + 2], m[k * 2 + 3])
        }
        /** The nearest point on a road to ([x], [z]) and the unit direction from it to there; null if none within 2 km. */
        fun nearestRoad(x: Double, z: Double): DoubleArray? {
            var best: DoubleArray? = null; var bd = Double.MAX_VALUE
            for (dj in -1..1) for (di in -1..1) for (q in grid[key(x + di * 2000, z + dj * 2000)] ?: continue) {
                val ax = q[0]; val az = q[1]; val bx = ax + Planet.wrapDx(q[2] - ax); val bz = q[3]
                val px = ax + Planet.wrapDx(x - ax)
                val dx = bx - ax; val dz = bz - az
                val l2 = dx * dx + dz * dz
                val t = if (l2 < 1e-9) 0.0 else (((px - ax) * dx + (z - az) * dz) / l2).coerceIn(0.0, 1.0)
                val cx = ax + dx * t; val cz = az + dz * t
                val d = hypot(px - cx, z - cz)
                if (d < bd) { bd = d; best = doubleArrayOf(cx, cz, (px - cx) / d.coerceAtLeast(1e-6), (z - cz) / d.coerceAtLeast(1e-6), d) }
            }
            return best
        }
        val out = ArrayList<Site>()
        fun clear(x: Double, z: Double): Boolean {
            val i = floor(x / Planet.CELL_M).toInt(); val j = floor(z / Planet.CELL_M).toInt()
            if (!sk.land(i, j)) return false
            if (towns.at(x, z, 250.0) != null) return false
            if (capital != null && capital.near(WorldPos(x, z), 600.0)) return false
            return out.none { hypot(Planet.wrapDx(it.x - x), it.z - z) < 400 }
        }
        fun add(id: String, kind: String, x: Double, z: Double, model: String?) {
            model ?: return
            val r = nearestRoad(x, z) ?: return
            // (the lot's middle 40 m off the road's centre, facing it)
            var ux = r[2]; var uz = r[3]
            if (r[4] < 1) { ux = 1.0; uz = 0.0 }
            val sx = Planet.wrapX(r[0] + ux * LOT_OFF_M); val sz = r[1] + uz * LOT_OFF_M
            if (!clear(sx, sz)) return
            // (its pad at the road's grade there: the cells' smooth height the road is graded to)
            out += Site(id, kind, sx, sz, -ux, -uz, model, sk.height(r[0] / Planet.CELL_M, r[1] / Planet.CELL_M))
        }
        // Highways v2's own (the exits' truck stops, motels and diners; the rest areas)
        for (s in hw.sites) if (s.open(year)) add(s.id, s.kind, Planet.wrapX(s.x * Planet.CELL_M), s.z * Planet.CELL_M, modelOf(s.kind, year, s.models))
        // the stops along the highways and county roads (src/road roadsideAlong's spacing and dice)
        for ((s, st) in segs) {
            val kind = when (st.cls) { "highway" -> "highway"; "county" -> "county"; else -> continue }
            val m = pathsM[s.id] ?: continue
            val cum = DoubleArray(m.size / 2)
            for (k in 1 until cum.size) cum[k] = cum[k - 1] + hypot(Planet.wrapDx(m[k * 2] - m[k * 2 - 2]), m[k * 2 + 1] - m[k * 2 - 1])
            val miles = cum.last() / 1609.344
            val stride = (if (kind == "highway") 14.0 else 9.0) * (if (year < 1940) 1.35 else if (year >= 1980) .9 else 1.0)
            val kinds = arrayListOf("diner", "gas_station", "tourist_trap")
            if (year >= 1940) kinds += "motel"
            var slot = 0
            while ((slot + 1) * stride < miles) {
                val mile = (slot + .5 + .4 * rand(sk.seed, "roadside_mile", s.id, slot)) * stride
                val k = kinds[(rand(sk.seed, "roadside_kind", s.id, slot) * kinds.size).toInt().coerceIn(0, kinds.size - 1)]
                val at = mile * 1609.344
                var q = 1
                while (q < cum.size - 1 && cum[q] < at) q++
                val t = ((at - cum[q - 1]) / (cum[q] - cum[q - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val ax = m[q * 2 - 2]; val az = m[q * 2 - 1]
                val dx = Planet.wrapDx(m[q * 2] - ax); val dz = m[q * 2 + 1] - az
                val l = hypot(dx, dz).coerceAtLeast(1e-6)
                val side = if (rand(sk.seed, "roadside_side", s.id, slot) < .5) 1.0 else -1.0
                // (the dice's point, a lot's width off the road: [add] finds the road again and stands the lot beside it)
                add("${s.id}:stop:$slot", k, Planet.wrapX(ax + dx * t - dz / l * side * 60), az + dz * t + dx / l * side * 60, modelOf(k, year))
                slot++
            }
        }
        return out
    }

    /** Each site's pad as a [WorldTownSet.Town] the terrain is levelled under ([TownGroundShape]): [PAD_M] square, turned with its front. */
    fun pads(sites: List<Site>): List<WorldTownSet.Town> = sites.map { s ->
        // (plan east along the road: the front (local +z, plan south) is (fx, fz), so plan east is (-fz, fx))
        val deg = Math.toDegrees(kotlin.math.atan2(s.fx, -s.fz))
        val half = PAD_M / 2 / Planet.TILE_M
        WorldTownSet.Town("site:${s.id}", s.kind, "roadside", s.kind, s.id, s.x, s.z, deg, 0.0, 0.0, 0.0, 0.0,
            -half, -half, half, half, 1.0, -1.0, -1.0, 1.0, s.heightM, "", 0L, 0, under = PAD_UNDER_M)
    }

    /** A lot's middle stands this far off its road's centre (m); its pad is this big (m); the terrain this far under it (m). */
    const val LOT_OFF_M = 40.0
    const val PAD_M = 90.0
    const val PAD_UNDER_M = .12

    // ---- the TypeScript's keyed dice (src/sim/rng.ts: xmur3 seeding mulberry32), so a site stands where the sim's road has it
    fun rand(seed: String, vararg keys: Any): Double {
        val str = (listOf(seed) + keys.map { it.toString() }).joinToString("|")
        var h = 1779033703 xor str.length
        for (ch in str) { h = (h xor ch.code) * -862048943; h = (h shl 13) or (h ushr 19) }
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        h = h xor (h ushr 16)
        var a = h
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}
