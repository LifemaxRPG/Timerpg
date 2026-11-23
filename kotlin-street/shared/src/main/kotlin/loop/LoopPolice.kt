package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.util.PriorityQueue
import kotlin.math.hypot

/**
 * An officer acted out (docs/LOOP.md: Kotlin moves the officers along the facade's plans): the plan's route (its node
 * ids, `routePlaceIds`) walked along the town's roads, on the pavement beside them, there and back, at a walking pace
 * in real time since the plan came. A placeholder for the plan language's executor (src/plan): the officer only walks
 * the route; "watch", "ask" and the stakeout's waiting are not acted out yet.
 */
class LoopOfficerWalk(val officerId: String, val assignment: String, val reason: String, val path: FloatArray, val startedNs: Long) {
    /** The path's length (street units). */
    val length: Float = run { var l = 0f; for (i in 2 until path.size step 2) l += hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1]); l }

    /** Where the officer is at [nowNs], and the way they face: x, z, dx, dz. */
    fun at(nowNs: Long, out: FloatArray = FloatArray(4)): FloatArray {
        if (path.size < 4 || length <= 0f) { out[0] = path.getOrElse(0) { 0f }; out[1] = path.getOrElse(1) { 0f }; out[2] = 0f; out[3] = 1f; return out }
        val walked = (nowNs - startedNs) / 1e9f * SPEED
        val lap = walked % (2 * length)
        val back = lap > length
        var s = if (back) 2 * length - lap else lap
        var i = 2
        while (i < path.size) {
            val dx = path[i] - path[i - 2]; val dz = path[i + 1] - path[i - 1]
            val l = hypot(dx, dz)
            if (s <= l || i == path.size - 2) {
                val t = if (l > 0f) (s / l).coerceIn(0f, 1f) else 0f
                out[0] = path[i - 2] + dx * t; out[1] = path[i - 1] + dz * t
                val k = if (l > 0f) (if (back) -1f else 1f) / l else 0f
                out[2] = dx * k; out[3] = dz * k
                return out
            }
            s -= l
            i += 2
        }
        return out
    }

    companion object {
        /** A patrolling walk (street units a second: 1.5 m/s). */
        val SPEED: Float = 1.5f * VehicleWorld.U
    }
}

/** Routes over the town's roads between the loop's street-graph nodes ([LoopTown.nodes]). */
class LoopRoutes(private val loop: LoopTown) {
    private val town: Town = loop.town
    /** Loop node id -> the town's node id (the station's door has none). */
    private val townNode: Map<String, Int> = loop.nodes.mapNotNull { n -> town.nodes.firstOrNull { it.x == n.x && it.z == n.z }?.let { n.id to it.id } }.toMap()
    private val adj: Map<Int, List<Pair<Int, Int>>> = HashMap<Int, MutableList<Pair<Int, Int>>>().also { m ->
        for (r in town.roads) { m.getOrPut(r.a) { ArrayList() } += r.b to r.id; m.getOrPut(r.b) { ArrayList() } += r.a to r.id }
    }
    private fun len(roadId: Int): Float { val r = town.road(roadId); var l = 0f; for (i in 1 until r.pts.size) l += hypot(r.pts[i].x - r.pts[i - 1].x, r.pts[i].z - r.pts[i - 1].z); return l }

    /** The town node nearest a loop node (the station's door: the junction nearest it). */
    private fun nodeFor(id: String): Int? = townNode[id] ?: loop.nodes.firstOrNull { it.id == id }?.let { n -> town.nodes.minByOrNull { hypot(it.x - n.x, it.z - n.z) }?.id }

    /** Roads (as polylines, street units) from town node [a] to [b], by length (Dijkstra). */
    private fun roads(a: Int, b: Int): List<Pair<Int, Boolean>> {
        if (a == b) return emptyList()
        val dist = HashMap<Int, Float>(); val prev = HashMap<Int, Pair<Int, Int>>()
        val q = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        dist[a] = 0f; q += a to 0f
        while (q.isNotEmpty()) {
            val (n, d) = q.poll()
            if (n == b) break
            if (d > (dist[n] ?: Float.MAX_VALUE)) continue
            for ((m, road) in adj[n].orEmpty()) {
                val nd = d + len(road)
                if (nd < (dist[m] ?: Float.MAX_VALUE)) { dist[m] = nd; prev[m] = n to road; q += m to nd }
            }
        }
        if (b !in prev) return emptyList()
        val out = ArrayList<Pair<Int, Boolean>>()
        var at = b
        while (at != a) { val (p, road) = prev.getValue(at); out += road to (town.road(road).a == p); at = p }
        return out.reversed()
    }

    /** The route through loop nodes [ids] as one polyline on the pavement (x, z pairs, street units). */
    fun path(ids: List<String>): FloatArray {
        val pts = ArrayList<Float>()
        fun add(x: Float, z: Float) { if (pts.size < 2 || pts[pts.size - 2] != x || pts[pts.size - 1] != z) { pts += x; pts += z } }
        val stops = ids.mapNotNull { id -> loop.nodes.firstOrNull { it.id == id } }
        var last: Int? = null
        for (s in stops) {
            val n = nodeFor(s.id) ?: continue
            if (last == null) add(s.x, s.z)
            else for ((road, forward) in roads(last, n)) {
                val r = town.road(road)
                // (on the pavement: half the road's width and a little more to the right of the way walked)
                val p = if (forward) r.pts else r.pts.reversed()
                for (i in p.indices) {
                    val a = p[maxOf(0, i - 1)]; val b = p[minOf(p.size - 1, i + 1)]
                    val dx = b.x - a.x; val dz = b.z - a.z; val l = hypot(dx, dz).coerceAtLeast(1e-3f)
                    val off = r.width / 2f + 30f
                    add(p[i].x - dz / l * off, p[i].z + dx / l * off)
                }
            }
            if (s.kind == "station") add(s.x, s.z)
            last = n
        }
        return pts.toFloatArray()
    }
}
