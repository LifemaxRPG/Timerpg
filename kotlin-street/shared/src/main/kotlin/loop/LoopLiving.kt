package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.hypot

/**
 * Living costs and street transit for the loop (docs/LOOP.md "Living costs and transit"): where the boarding house is, and
 * the stops of the streetcar/bus lines on Main Street and two other busy streets of the capital. The rules themselves
 * (rent, arrears, fares, timetables) are src/economy/living.ts and src/transit, read in [LoopGame].
 */

/** The room the player can rent: a big house near Main Street, labelled a boarding house. [placeId] is its home place. */
class LoopLodging(val placeId: String, val name: String, val doorX: Float, val doorZ: Float, val kind: String = "boarding_house")

/** A stop of [line] (the street's name); [x], [z] are in street units, on the pavement beside the road. */
class LoopStop(val id: String, val name: String, val line: String, val x: Float, val z: Float)

/** A line along one street: its stops in order. */
class LoopLine(val id: String, val street: String, val stops: List<LoopStop>)

/** One ride from a stop: where to, the fare (cents of the town's year and as the era writes it), minutes on board (wait included). */
class RideOption(val toId: String, val toName: String, val cents: Int, val text: String, val minutes: Int)

/** What the panel at a stop shows (made on the sim's thread): the mode, the next departures, the rides, or why there is no service. */
class StopInfo(val stop: LoopStop, val mode: String, val departures: List<String>, val rides: List<RideOption>, val noService: String?)

/** The rented room as the HUD shows it. [line] is the warning (null when all is well). */
class RentView(
    val status: String, val owedCents: Int, val owedText: String, val rentText: String, val nextDue: String,
    val line: String?, val lockedOut: Boolean, val nights: Int, val roomItems: List<LoopItem>,
)

object LoopLiving {
    private const val U = VehicleWorld.U

    /** The nearest distance from ([x], [z]) to the polyline [pts] (x, z pairs). */
    private fun distTo(pts: List<Pair<Float, Float>>, x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 1 until pts.size) {
            val (ax, az) = pts[i - 1]; val (bx, bz) = pts[i]
            val dx = bx - ax; val dz = bz - az
            val l2 = dx * dx + dz * dz
            val t = if (l2 <= 0f) 0f else (((x - ax) * dx + (z - az) * dz) / l2).coerceIn(0f, 1f)
            best = minOf(best, hypot(x - (ax + t * dx), z - (az + t * dz)))
        }
        return best
    }

    /**
     * The boarding house: the biggest home (building front x depth) with its door within 200 m of Main Street, else the
     * biggest home in the town; deterministic (ties by id). There is no hotel or boarding house in the town's export, so
     * a house is made one (docs/LOOP.md).
     */
    fun lodging(t: LoopTown): LoopLodging? {
        val homes = t.places.filter { it.kind == "home" }
        if (homes.isEmpty()) return null
        val main = t.town.roads.filter { it.main }.map { r -> r.pts.map { it.x to it.z } }
        fun size(p: LoopPlace) = t.town.placement(p.sprite)?.second?.let { it.width * it.depth } ?: 0f
        fun near(p: LoopPlace) = main.isNotEmpty() && main.any { distTo(it, p.doorX, p.doorZ) <= 200f * U }
        val pick = (homes.filter { near(it) }.ifEmpty { homes }).sortedWith(compareBy({ -size(it) }, { it.id })).first()
        val street = t.town.roads.minByOrNull { r -> distTo(r.pts.map { it.x to it.z }, pick.doorX, pick.doorZ) }?.name
        return LoopLodging(pick.id, "Boarding house" + (street?.let { " on $it" } ?: ""), pick.doorX, pick.doorZ)
    }

    /**
     * The speakeasy's back door (1920-1933 only): behind a shop that makes a plausible front (a dress shop, a clothing store,
     * a toy store, a music store, a butcher, a bakery, in that order; else any small shop), two metres past its back wall,
     * facing away from it. Null outside the Prohibition years or when no such shop has a placement.
     */
    fun speakeasy(t: LoopTown): LoopSpeakeasy? {
        if (t.year !in 1920..1933) return null
        val shops = t.places.filter { it.kind == "general_store" && it.keeperId != null }
        val order = listOf("dress shop", "clothing store", "toy store", "music store", "butcher", "bakery")
        val pick = order.firstNotNullOfOrNull { k -> shops.filter { it.shopKind == k }.minByOrNull { it.id } } ?: shops.minByOrNull { it.id } ?: return null
        val (r, b) = t.town.placement(pick.sprite) ?: return null
        val front = r.pointAt(b.mid, 0f); val n = r.normalAt(b.mid)
        val d = b.setback + b.depth + 2f * U
        return LoopSpeakeasy(pick.id, "A back door behind ${pick.name}", front.x + n.x * d, front.z + n.z * d, n.x, n.z)
    }

    /**
     * The lines: Main Street and the two other streets with the most shop doors beside them (up to 4, 3 and 3 stops, at
     * least 200 m apart, nearest the shops), each stop on the pavement just off the road, named for the shop it is by.
     */
    fun lines(t: LoopTown): List<LoopLine> {
        val shops = t.places.filter { it.kind != "home" }
        val byStreet = t.town.roads.groupBy { it.name }
        fun roadPts(name: String) = byStreet[name].orEmpty().flatMap { r -> r.pts.map { it.x to it.z } }
        val busy = byStreet.keys.filter { n -> byStreet[n]!!.none { it.main } }
            .sortedWith(compareBy({ -shops.count { s -> byStreet[it]!!.any { r -> distTo(r.pts.map { p -> p.x to p.z }, s.doorX, s.doorZ) < 50f * U } } }, { it }))
        val mainName = t.town.roads.firstOrNull { it.main }?.name
        val streets = listOfNotNull(mainName) + busy.take(2)
        return streets.mapNotNull { name ->
            val limit = if (name == mainName) 4 else 3
            // candidates: segment midpoints of the street's roads, best (most shops within 60 m) first
            class C(val x: Float, val z: Float, val nx: Float, val nz: Float, val width: Float, val score: Int)
            val cands = ArrayList<C>()
            for (r in byStreet[name].orEmpty()) for (i in 1 until r.pts.size) {
                val a = r.pts[i - 1]; val b = r.pts[i]
                val mx = (a.x + b.x) / 2f; val mz = (a.z + b.z) / 2f
                val len = hypot(b.x - a.x, b.z - a.z).takeIf { it > 1f } ?: continue
                cands += C(mx, mz, -(b.z - a.z) / len, (b.x - a.x) / len, r.width, shops.count { hypot(it.doorX - mx, it.doorZ - mz) < 60f * U })
            }
            val chosen = ArrayList<C>()
            for (c in cands.sortedWith(compareBy({ -it.score }, { it.x }, { it.z }))) {
                if (chosen.size >= limit) break
                if (chosen.none { hypot(it.x - c.x, it.z - c.z) < 200f * U }) chosen += c
            }
            if (chosen.size < 2) return@mapNotNull null
            val ordered = chosen.sortedWith(compareBy({ it.x + it.z }, { it.x }))
            val stops = ordered.mapIndexed { k, c ->
                // the side with the nearer shop, just past the road's edge
                val off = c.width / 2f + 25f
                val a = (c.x + c.nx * off) to (c.z + c.nz * off); val b = (c.x - c.nx * off) to (c.z - c.nz * off)
                fun nearest(p: Pair<Float, Float>) = shops.minOfOrNull { hypot(it.doorX - p.first, it.doorZ - p.second) } ?: Float.MAX_VALUE
                val p = if (nearest(a) <= nearest(b)) a else b
                val shop = shops.filter { it.kind != "police_station" }.minByOrNull { hypot(it.doorX - p.first, it.doorZ - p.second) }
                    ?.takeIf { hypot(it.doorX - p.first, it.doorZ - p.second) < 90f * U }
                LoopStop("stop:$name:$k", if (shop != null) "$name, by ${shop.name}" else "$name, stop ${k + 1}", name, p.first, p.second)
            }
            LoopLine("line:$name", name, stops)
        }
    }
}
