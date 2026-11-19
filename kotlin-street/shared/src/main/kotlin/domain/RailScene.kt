package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The railroad of the town as it stands in one year, and its trains through the day (docs/RAIL.md), engine-free: the
 * Filament layer (scene3d/TownRailDraw.kt) draws what this says, the desktop tests check it. From [RailNet]: each line
 * through (or into) the town becomes one [RailPath] from the far side of one portal, along the town's own track, out
 * past the other portal (into the countryside, along the line's surveyed route); the trains of the timetable run along
 * it, stopping at the depot when the station is open to passengers that year, and the freight trains pass through.
 */

/** A polyline (town frame) with its length along it. */
class RailPath(val line: String, val links: List<String>, val pts: FloatArray, val main: Boolean) {
    val n: Int get() = pts.size / 2
    val cum: FloatArray = FloatArray(n).also { c -> for (i in 1 until n) c[i] = c[i - 1] + hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1]) }
    val length: Float get() = cum[n - 1]
    /** Where the depot stands along it (or the middle). */
    var depotS: Float = length / 2f

    /** The point [s] along (clamped), and the way it runs there: out = x, z, dx, dz. */
    fun at(s: Float, out: FloatArray) {
        val t = s.coerceIn(0f, length)
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (cum[m] <= t) lo = m else hi = m }
        val seg = (cum[hi] - cum[lo]).coerceAtLeast(1e-6f)
        val f = (t - cum[lo]) / seg
        val dx = (pts[hi * 2] - pts[lo * 2]) / seg; val dz = (pts[hi * 2 + 1] - pts[lo * 2 + 1]) / seg
        out[0] = pts[lo * 2] + (pts[hi * 2] - pts[lo * 2]) * f; out[1] = pts[lo * 2 + 1] + (pts[hi * 2 + 1] - pts[lo * 2 + 1]) * f
        out[2] = dx; out[3] = dz
    }

    /** How far along the nearest point to ([x], [z]) is, and how far off the path it lies. */
    fun project(x: Float, z: Float): Pair<Float, Float> {
        var best = Float.MAX_VALUE; var bs = 0f
        for (i in 0 until n - 1) {
            val ax = pts[i * 2]; val az = pts[i * 2 + 1]; val bx = pts[i * 2 + 2]; val bz = pts[i * 2 + 3]
            val l2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az)
            val t = if (l2 < 1e-9f) 0f else (((x - ax) * (bx - ax) + (z - az) * (bz - az)) / l2).coerceIn(0f, 1f)
            val d = hypot(ax + (bx - ax) * t - x, az + (bz - az) * t - z)
            if (d < best) { best = d; bs = cum[i] + t * sqrt(l2) }
        }
        return bs to best
    }
}

/** A car of a train: its kind and its length (street units, coupler to coupler). */
enum class RailCar(val length: Float) {
    STEAM(250f), TENDER(150f), DIESEL(260f), NATIONAL(270f), MODERN(300f),
    COACH_ORANGE(270f), COACH_GREEN(275f), COACH_GREY(275f), BOXCAR(230f), HOPPER(190f), TANK(210f), CABOOSE(150f);
    val loco: Boolean get() = this == STEAM || this == DIESEL || this == NATIONAL || this == MODERN
}

/** A train in the town at one moment: the path it runs on, its cars front first, where its front is and which way it goes. */
class TrainNow(val path: RailPath, val cars: List<RailCar>, val front: Float, val dir: Int, val passenger: Boolean, val stopping: Boolean) {
    val length: Float get() = cars.sumOf { (it.length + GAP).toDouble() }.toFloat()
    /** Where car [i]'s middle is along the path. */
    fun carAt(i: Int): Float { var s = 0f; for (k in 0 until i) s += cars[k].length + GAP; return front - dir * (s + cars[i].length / 2f) }
    /** The stretch of path the train covers (low, high). */
    val span: Pair<Float, Float> get() { val rear = front - dir * length; return min(front, rear) to max(front, rear) }
    companion object { const val GAP = 6f }
}

class RailScene(val net: RailNet, val year: Int) {
    /** The tracks to draw: polyline, its state this year (track, bed, trail), main or branch or siding. */
    class Drawn(val pts: FloatArray, val state: String, val kind: String, val link: String)

    val drawn = ArrayList<Drawn>()
    val paths = ArrayList<RailPath>()
    val crossings: List<RailCrossing> get() = net.rail.crossings.filter { net.link(it.link)?.state(year) == "track" }
    val station: Char get() = net.status("0,0", year)
    val depotState: String get() = when {
        net.rail.depot == null -> "none"
        net.rail.depot.built > year || station == 'n' -> "none"
        station == 'p' -> "open"
        station == 'f' -> "closed"
        else -> "reused"
    }
    val era: String get() = net.trainEra(year)

    init {
        val home = net.home
        val rect = home.plan?.rect
        // the line's surveyed route beyond a portal: the link's points outside the town's plan, from the portal outwards
        fun beyond(p: RailPortal): FloatArray {
            val l = net.link(p.link) ?: return FloatArray(0)
            val pts = l.pts
            val k = pts.size / 2
            val outward = if (l.a == "0,0") (0 until k) else (k - 1 downTo 0)
            val out = ArrayList<Float>()
            for (i in outward) {
                val x = pts[i * 2]; val z = pts[i * 2 + 1]
                val inside = rect != null && x > rect[0] && x < rect[2] && z > rect[1] && z < rect[3]
                if (inside) continue
                if (hypot(x - p.x, z - p.z) > EXTEND) break
                out += x; out += z
            }
            return out.toFloatArray()
        }
        val tracks = net.rail.tracks
        for (t in tracks) drawn += Drawn(t.pts, net.link(t.link)?.state(year) ?: "none", t.kind, t.link)
        for (p in net.rail.portals) {
            val b = beyond(p)
            if (b.size >= 4) drawn += Drawn(floatArrayOf(p.x, p.z, *b), net.link(p.link)?.state(year) ?: "none", net.link(p.link)?.kind ?: "main", p.link)
        }
        // the lines through town: a portal's far side, the town's tracks of the line's links in order, out past the other
        val byLine = net.linksAt("0,0").groupBy { it.line }
        for ((line, ls) in byLine) {
            // (west/north link first: it comes in to the town)
            val ordered = ls.sortedBy { if (it.b == "0,0") 0 else 1 }
            val pts = ArrayList<Float>()
            fun add(a: FloatArray, rev: Boolean) {
                val k = a.size / 2
                for (j in 0 until k) { val i = if (rev) k - 1 - j else j; val x = a[i * 2]; val z = a[i * 2 + 1]
                    if (pts.size >= 2 && hypot(pts[pts.size - 2] - x, pts[pts.size - 1] - z) < .5f) continue
                    pts += x; pts += z }
            }
            for ((i, l) in ordered.withIndex()) {
                val portal = net.rail.portals.firstOrNull { it.link == l.id } ?: continue
                val far = beyond(portal)
                val ts = tracks.filter { it.link == l.id && it.kind != "siding" }
                if (ts.isEmpty()) continue
                // the town's track of this link runs from its portal (or to it)
                val t = ts.first().pts
                val startsAtPortal = hypot(t[0] - portal.x, t[1] - portal.z) < hypot(t[t.size - 2] - portal.x, t[t.size - 1] - portal.z)
                if (i == 0) { add(far, rev = true); add(t, rev = !startsAtPortal) } else { add(t, rev = startsAtPortal); add(far, rev = false) }
            }
            if (pts.size < 4) continue
            val path = RailPath(line, ordered.map { it.id }, pts.toFloatArray(), ordered.any { it.main })
            net.rail.depot?.let { d -> path.depotS = path.project(d.x, d.z).first }
            paths += path
        }
    }

    /** Is ([x], [z]) on a track's bed (no tree, prop or yard thing should stand there)? */
    fun onBed(x: Float, z: Float, half: Float = BED_HALF): Boolean {
        for (d in drawn) {
            val p = d.pts
            for (i in 0 until p.size / 2 - 1) {
                val ax = p[i * 2]; val az = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val bz = p[i * 2 + 3]
                if (x < min(ax, bx) - half || x > max(ax, bx) + half || z < min(az, bz) - half || z > max(az, bz) + half) continue
                val l2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az)
                val t = if (l2 < 1e-9f) 0f else (((x - ax) * (bx - ax) + (z - az) * (bz - az)) / l2).coerceIn(0f, 1f)
                if (hypot(ax + (bx - ax) * t - x, az + (bz - az) * t - z) < half) return true
            }
        }
        net.rail.depot?.let { d -> val r = d.rect; if (x > r[0] - 20 && x < r[2] + 20 && z > r[1] - 20 && z < r[3] + 20) return true }
        return false
    }

    /** The consist of a passenger or freight train on [main] line this year. */
    fun consist(passenger: Boolean, main: Boolean, seed: Int): List<RailCar> {
        val e = net.trainEra(year, main)
        val loco = when (e) { "steam" -> listOf(RailCar.STEAM, RailCar.TENDER); "diesel" -> listOf(RailCar.DIESEL); "national" -> listOf(RailCar.NATIONAL); else -> listOf(RailCar.MODERN) }
        if (passenger) {
            val coach = when (e) { "steam", "diesel" -> RailCar.COACH_ORANGE; "national" -> RailCar.COACH_GREEN; else -> RailCar.COACH_GREY }
            return loco + List(if (main) 4 else 2) { coach }
        }
        // freight: a mix of boxcars, hoppers and tank cars, a caboose before the 1980s
        val n = if (main) 10 + seed % 7 else 5 + seed % 3
        val kinds = listOf(RailCar.BOXCAR, RailCar.HOPPER, RailCar.TANK, RailCar.BOXCAR, RailCar.HOPPER)
        val cars = List(n) { kinds[(seed * 7 + it * 3 + it / 2) % kinds.size] }
        val freightLoco = if (e == "modern" || e == "national") listOf(RailCar.DIESEL, RailCar.DIESEL) else loco
        return freightLoco + cars + (if (year < 1985) listOf(RailCar.CABOOSE) else emptyList())
    }

    /** Freight trains a day each way on a line. */
    private fun freightPerDay(main: Boolean): Int = if (main) 4 else 1

    /** The trains in the town at [minute] after midnight (the day's clock, fractions allowed). */
    fun trains(minute: Double): List<TrainNow> {
        val out = ArrayList<TrainNow>()
        val t = minute * 60.0 // seconds
        for (p in paths) {
            val ls = p.links.mapNotNull { net.link(it) }
            if (ls.none { it.trackIn(year) }) continue
            val main = p.main
            val v = (if (main) TOWN_SPEED else TOWN_SPEED * .75f).toDouble()
            val pos = if (p.line.startsWith("ew")) 0 else 0
            for (dir in intArrayOf(1, -1)) {
                // passenger trains: the timetable's departures from the depot (stopping when the station is open)
                val passengers = ls.any { it.passengersOn(year) && (year < net.serviceFrom || it.main) }
                if (passengers) for ((k, dep) in net.departures(p.line, pos, dir, year).withIndex()) {
                    trainAt(p, dir, dep * 60.0, t, v, stop = station == 'p', passenger = true, main = main, seed = k)?.let { out += it }
                }
                // freight: through without stopping, at its own times
                val nf = freightPerDay(main)
                for (k in 0 until nf) {
                    val dep = (WorldRand.rand(net.worldSeed, "rail-freight", p.line, dir, k, year / 10) * 1440.0)
                    trainAt(p, dir, dep * 60.0, t, v * .8, stop = false, passenger = false, main = main, seed = k + 3 * (year % 7))?.let { out += it }
                }
            }
        }
        return out
    }

    /**
     * Where the train passing (or leaving) the depot at [at] seconds is at [t] seconds: braking into the depot over
     * [BRAKE] and pulling out again with a [DWELL] stop between when it stops, else straight through at [v] units a second.
     * Null while it is off the path. The day wraps.
     */
    private fun trainAt(p: RailPath, dir: Int, at: Double, t: Double, v: Double, stop: Boolean, passenger: Boolean, main: Boolean, seed: Int): TrainNow? {
        var dt = t - at
        if (dt > 43200) dt -= 86400.0 else if (dt < -43200) dt += 86400.0
        val brakeT = 2.0 * BRAKE / v
        val off: Double = if (!stop) v * dt else {
            // departs at [at]; arrives DWELL before it
            val arr = -DWELL
            when {
                dt < arr - brakeT -> -BRAKE - v * (arr - brakeT - dt)
                dt < arr -> { val u = (arr - dt) / brakeT; -BRAKE * u * u }
                dt < 0 -> 0.0
                dt < brakeT -> { val u = dt / brakeT; BRAKE * u * u }
                else -> BRAKE + v * (dt - brakeT)
            }
        }
        val cars = consist(passenger, main, seed)
        val front = (p.depotS + dir * (off + (if (stop) cars.first().length * 1.5 + 200 else 0.0))).toFloat()
        val tr = TrainNow(p, cars, front, dir, passenger, stop)
        val (lo, hi) = tr.span
        if (hi < 0f || lo > p.length) return null
        return tr
    }

    /** Whether a train is at or near crossing [c] (its lights flash and its gates come down). */
    fun crossingActive(c: RailCrossing, trains: List<TrainNow>): Boolean {
        for (tr in trains) {
            val (s, off) = tr.path.project(c.x, c.z)
            if (off > 120f) continue
            val (lo, hi) = tr.span
            // (the gates come down well before the train: about 20 s at its speed; they rise once it is clear)
            val ahead = if (tr.dir > 0) s - tr.front else tr.front - s
            if (s in lo - 40f..hi + 40f || (ahead > 0f && ahead < WARN)) return true
        }
        return false
    }

    companion object {
        /** How far the track is drawn and the trains run past the town's portals (into the haze). */
        const val EXTEND = 6500f
        /** The track bed's half width kept clear of trees and props. */
        const val BED_HALF = 70f
        /** Trains' speed through town (street units a second: about 40 km/h). */
        const val TOWN_SPEED = 236f
        const val BRAKE = 900.0
        const val DWELL = 90.0
        /** Lights and gates work this far ahead of a train. */
        const val WARN = 4200f
    }
}
