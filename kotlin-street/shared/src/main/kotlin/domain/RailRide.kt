package com.lifemax.rpg.feature.habits.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A ride on the train (docs/RAIL.md "Riding"): the track a planned route's trains follow, at world scale, from the home
 * town's depot to the last station of the route: the town's own track out of town, each line's surveyed route between
 * the towns, and through the towns near enough to have their plans exported their own track (their depot's tracks),
 * elsewhere the surveyed route through their line point. [path] runs in the town frame; [stops] are where the train
 * calls on the way (open stations it passes, the changes, the end), [minutes] the timetable's time aboard.
 */
class RailRide(val net: RailNet, val route: RailRoute) {
    val year: Int = route.year
    val path: RailPath
    /** Where along [path] the train stops: s, the town's name, a change of trains there. The last is the end. */
    val stops = ArrayList<Triple<Float, String, Boolean>>()
    /** The train legs' time aboard and waiting between them, minutes (the ride's clock is compressed to [seconds]). */
    val minutes: Double
    val from: String; val to: String

    init {
        val legs = route.legs.orEmpty().filter { it.mode == "train" }
        require(legs.isNotEmpty()) { "a ride needs a train leg" }
        from = legs.first().from; to = legs.last().to
        minutes = legs.last().arrive - legs.first().depart
        // the towns in order, and the links between them
        val via = ArrayList<String>()
        for (l in legs) for (t in l.via) if (via.lastOrNull() != t) via += t
        val pts = ArrayList<Float>()
        fun add(x: Float, z: Float) { if (pts.size >= 2 && hypot(pts[pts.size - 2] - x, pts[pts.size - 1] - z) < 1f) return; pts += x; pts += z }
        fun addAll(a: FloatArray, rev: Boolean) { val k = a.size / 2; for (j in 0 until k) { val i = if (rev) k - 1 - j else j; add(a[i * 2], a[i * 2 + 1]) } }
        fun nearer(a: FloatArray, x: Float, z: Float): Boolean = hypot(a[0] - x, a[1] - z) <= hypot(a[a.size - 2] - x, a[a.size - 1] - z)
        fun inside(t: RailTownInfo, x: Float, z: Float): Boolean { val r = t.plan?.rect ?: return false; return x > r[0] && x < r[2] && z > r[1] && z < r[3] }
        val start = net.townOf(via[0])!!
        // from the depot (the home town's own; else the first town's line point)
        val d0 = start.rail?.depot ?: if (start.id == "0,0") net.rail.depot else null
        if (d0 != null) add(d0.x, d0.z) else add(start.x, start.z)
        for (i in 0 until via.size - 1) {
            val a = net.townOf(via[i])!!; val b = net.townOf(via[i + 1])!!
            val link = net.linksAt(a.id).first { it.other(a.id) == b.id }
            val rail = (if (a.id == "0,0") net.rail else a.rail)
            // out of town a: its track of this link (from its depot to its portal), when its plan was exported
            val out = rail?.tracks?.firstOrNull { it.link == link.id && it.kind != "siding" }
            if (out != null && out.pts.size >= 4) {
                val x = pts[pts.size - 2]; val z = pts[pts.size - 1]
                addAll(out.pts, rev = !nearer(out.pts, x, z))
            }
            // the surveyed route between the towns, outside both towns' plans
            val al = link.pts
            val rev = link.a != a.id
            val k = al.size / 2
            for (j in 0 until k) {
                val ii = if (rev) k - 1 - j else j
                val x = al[ii * 2]; val z = al[ii * 2 + 1]
                if ((a.plan != null && inside(a, x, z)) || (b.plan != null && inside(b, x, z))) continue
                add(x, z)
            }
            // into town b: its track of this link (portal to depot)
            val brail = if (b.id == "0,0") net.rail else b.rail
            val inn = brail?.tracks?.firstOrNull { it.link == link.id && it.kind != "siding" }
            if (inn != null && inn.pts.size >= 4) {
                val x = pts[pts.size - 2]; val z = pts[pts.size - 1]
                addAll(inn.pts, rev = !nearer(inn.pts, x, z))
            }
        }
        path = RailPath("ride", emptyList(), pts.toFloatArray(), true)
        // the stops: every open station the legs call at, the changes, the end (by the nearest point along the path)
        fun sOf(id: String): Float {
            val t = net.townOf(id)!!
            val dep = (if (id == "0,0") net.rail.depot else t.rail?.depot)
            return if (dep != null) path.project(dep.x, dep.z).first else path.project(t.x, t.z).first
        }
        for ((li, l) in legs.withIndex()) {
            for (stop in l.via.drop(1)) {
                val last = stop == l.to
                val name = net.townOf(stop)!!.name
                if (!last && name !in l.stops) continue
                stops += Triple(sOf(stop), name, last && li < legs.size - 1)
            }
        }
        stops.sortBy { it.first }
    }

    /** Where the train is after [t] of [total] seconds of the ride (eased out of the depot and into each stop). */
    fun at(t: Float, total: Float): Float {
        val f = (t / total).coerceIn(0f, 1f)
        // (a smooth start and end; the stops on the way are passed at speed in this short ride)
        val e = f * f * (3f - 2f * f)
        val end = stops.lastOrNull()?.first ?: path.length
        return min(end, max(0f, e * end))
    }
}
