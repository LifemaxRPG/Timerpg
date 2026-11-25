package com.lifemax.rpg.feature.habits.domain

import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The world's roads as a graph (Highways v2's segments as they are in a [year]), and the fastest way along them between
 * two points: what the drive test follows from the capital to the next town (docs/WORLD_STREAM.md), and the start of
 * step 3's travel (a route, a sat-nav, the AI's drives). Engine-agnostic.
 *
 * Nodes are the segments' ends, merged within [MERGE_M]; an edge is a segment's polyline (world metres, x unwrapped from
 * its first point), its cost the time at the class's speed ([speedOf]).
 */
class WorldRoute(val sk: WorldSkeleton, val year: Int) {
    class Edge(val a: Int, val b: Int, val pts: DoubleArray, val cls: String, val lanes: Int, val lengthM: Double, val seconds: Double)

    private val nodeX = ArrayList<Double>()
    private val nodeZ = ArrayList<Double>()
    private val grid = HashMap<Long, MutableList<Int>>()
    val edges = ArrayList<Edge>()
    private val out = ArrayList<MutableList<Int>>()

    val nodes: Int get() = nodeX.size

    init {
        val hw = sk.highways
        class Line(val m: DoubleArray, val st: WorldHighways.State)
        val lines = ArrayList<Line>()
        if (hw != null) for (s in hw.segs) {
            val st = s.at(year) ?: continue
            if (st.cls == "abandoned") continue
            val p = sk.points(s.path)
            if (p.size < 4) continue
            // (world metres, x unwrapped along the path from its first point)
            val m = DoubleArray(p.size)
            val x0 = Planet.wrapX(p[0] * Planet.CELL_M)
            for (k in 0 until p.size / 2) { m[k * 2] = x0 + (p[k * 2] - p[0]) * Planet.CELL_M; m[k * 2 + 1] = p[k * 2 + 1] * Planet.CELL_M }
            lines += Line(m, st)
            node(m[0], m[1]); node(m[m.size - 2], m[m.size - 1])
        }
        // the ends on a coarse grid: a road that ends on another one's middle (a T junction) splits that one there
        val coarse = HashMap<Long, MutableList<Int>>()
        fun ck(i: Long, j: Long) = (Math.floorMod(i, (Planet.C / COARSE).toLong() + 1) shl 32) or (j and 0xffffffffL)
        for (n in 0 until nodes) coarse.getOrPut(ck(Math.floorDiv(nodeX[n].toLong(), COARSE.toLong()), Math.floorDiv(nodeZ[n].toLong(), COARSE.toLong()))) { ArrayList() } += n
        for (l in lines) {
            val m = l.m
            val pts = ArrayList<Double>(); var from = -1; var len = 0.0
            fun close(to: Int) {
                if (from >= 0 && to != from && pts.size >= 4) {
                    val e = Edge(from, to, pts.toDoubleArray(), l.st.cls, l.st.lanes, len, len / speedOf(l.st.cls, l.st.lanes))
                    out[from].add(edges.size); out[to].add(edges.size); edges += e
                }
                pts.clear(); len = 0.0; from = to
            }
            from = node(m[0], m[1]); pts += m[0]; pts += m[1]
            for (k in 0 until m.size / 2 - 1) {
                val ax = m[k * 2]; val az = m[k * 2 + 1]; val bx = m[k * 2 + 2]; val bz = m[k * 2 + 3]
                val dx = bx - ax; val dz = bz - az; val l2 = dx * dx + dz * dz
                // (the ends lying on this stretch, in order along it)
                val hits = ArrayList<Pair<Double, Int>>()
                val i0 = Math.floorDiv((minOf(ax, bx) - MERGE_M).toLong(), COARSE.toLong()); val i1 = Math.floorDiv((maxOf(ax, bx) + MERGE_M).toLong(), COARSE.toLong())
                val j0 = Math.floorDiv((minOf(az, bz) - MERGE_M).toLong(), COARSE.toLong()); val j1 = Math.floorDiv((maxOf(az, bz) + MERGE_M).toLong(), COARSE.toLong())
                if ((i1 - i0 + 1) * (j1 - j0 + 1) < 400) for (j in j0..j1) for (i in i0..i1) for (n in coarse[ck(i, j)] ?: continue) {
                    val nx = ax + Planet.wrapDx(nodeX[n] - ax); val nz = nodeZ[n]
                    val t = if (l2 < 1e-9) 0.0 else (((nx - ax) * dx + (nz - az) * dz) / l2).coerceIn(0.0, 1.0)
                    if (hypot(ax + dx * t - nx, az + dz * t - nz) < MERGE_M && t > 0.0) hits += t to n
                }
                hits.sortBy { it.first }
                var px = ax; var pz = az
                for ((t, n) in hits) {
                    val x = ax + dx * t; val z = az + dz * t
                    if (n == from && pts.size <= 2) continue
                    pts += x; pts += z; len += hypot(x - px, z - pz); px = x; pz = z
                    close(n); pts += x; pts += z
                }
                if (hits.lastOrNull()?.first != 1.0) { pts += bx; pts += bz; len += hypot(bx - px, bz - pz) }
            }
            close(node(m[m.size - 2], m[m.size - 1]))
        }
    }

    private fun key(x: Double, z: Double): Long = ((Planet.wrapX(x) / CELL).roundToLong() shl 32) or ((z / CELL).roundToLong() and 0xffffffffL)

    private fun node(x: Double, z: Double): Int {
        val kx = (Planet.wrapX(x) / CELL).roundToLong(); val kz = (z / CELL).roundToLong()
        for (dz in -1..1) for (dx in -1..1) {
            val l = grid[((kx + dx) shl 32) or ((kz + dz) and 0xffffffffL)] ?: continue
            for (n in l) if (hypot(Planet.wrapDx(nodeX[n] - x), nodeZ[n] - z) < MERGE_M) return n
        }
        nodeX += Planet.wrapX(x); nodeZ += z; out.add(ArrayList())
        grid.getOrPut(key(x, z)) { ArrayList() } += nodeX.size - 1
        return nodeX.size - 1
    }

    /** The node nearest [p] (m), or -1. */
    fun nearest(p: WorldPos): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (n in 0 until nodes) { val d = hypot(Planet.wrapDx(nodeX[n] - p.x), nodeZ[n] - p.z); if (d < bd) { bd = d; best = n } }
        return best
    }

    fun at(n: Int): WorldPos = WorldPos(nodeX[n], nodeZ[n])

    /** A route: its points (world metres, x continuous from the start), length and time at the classes' speeds, and its edges' classes. */
    class Path(val pts: DoubleArray, val lengthM: Double, val seconds: Double, val classes: List<String>)

    /** The fastest path from node [from] to node [to], or null if none. */
    fun fastest(from: Int, to: Int): Path? {
        val dist = DoubleArray(nodes) { Double.MAX_VALUE }
        val via = IntArray(nodes) { -1 }
        dist[from] = 0.0
        val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        q += 0.0 to from
        while (q.isNotEmpty()) {
            val (d, n) = q.poll()
            if (d > dist[n]) continue
            if (n == to) break
            for (ei in out[n]) {
                val e = edges[ei]
                val m = if (e.a == n) e.b else e.a
                val nd = d + e.seconds
                if (nd < dist[m]) { dist[m] = nd; via[m] = ei; q += nd to m }
            }
        }
        if (dist[to] == Double.MAX_VALUE) return null
        // walk back, then lay the edges' points end to end (each the right way round), x kept continuous
        val chain = ArrayList<Int>()
        var n = to
        while (n != from) { val ei = via[n]; chain += ei; val e = edges[ei]; n = if (e.a == n) e.b else e.a }
        chain.reverse()
        val pts = ArrayList<Double>()
        var at = from
        var len = 0.0; var secs = 0.0
        val classes = ArrayList<String>()
        for (ei in chain) {
            val e = edges[ei]
            val fwd = e.a == at
            val k = e.pts.size / 2
            for (i in 0 until k) {
                val idx = if (fwd) i else k - 1 - i
                var x = e.pts[idx * 2]; val z = e.pts[idx * 2 + 1]
                if (pts.isNotEmpty()) { val px = pts[pts.size - 2]; x = px + Planet.wrapDx(x - px) }
                if (i == 0 && pts.isNotEmpty()) continue
                pts += x; pts += z
            }
            len += e.lengthM; secs += e.seconds; classes += e.cls
            at = if (fwd) e.b else e.a
        }
        return Path(pts.toDoubleArray(), len, secs, classes)
    }

    companion object {
        const val MERGE_M = 3.0
        private const val CELL = 4.0
        private const val COARSE = 256.0
        /** A class's speed (m/s): an expressway 110 km/h, a highway 90 (100 with 4 lanes), a county road 70, a track 40. */
        fun speedOf(cls: String, lanes: Int): Double = when (cls) {
            "expressway" -> 110 / 3.6
            "highway" -> (if (lanes >= 4) 100.0 else 90.0) / 3.6
            "county", "local", "frontage" -> 70 / 3.6
            else -> 40 / 3.6
        }
    }
}
