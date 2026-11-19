package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The railroad round the town (docs/RAIL.md), read from the town's rail.json (tools/export-rail.ts, made by the game's own
 * src/world/rail.ts and src/town/rail.ts): the town's own track, crossings, depot and siding; the world's towns and lines
 * for RAIL radius cells round it, each station's status in every year, the timetables' clocks. The route planner here
 * is src/world/rail.ts planRoute line for line (the desktop RailNetTest checks it against routes the TypeScript wrote),
 * so the ticket window answers exactly as the game's world does. Positions are in the town's frame: street units, x
 * east, z south, as the street.json graph.
 */

/** A town of the rail world. [status] is its station's status letter for each year from [RailNet.from] ('n' none,
 * 'p' passenger trains stop, 'f' freight only, 'g' the rails are gone). [tile] its centre in world tiles. */
class RailTownInfo(
    val id: String, val cx: Int, val cy: Int, val name: String, val kind: String, val rank: String, val founded: Int,
    val x: Float, val z: Float, val tileX: Double, val tileY: Double, val status: String,
    /** Its plan (towns near the home town only): the plan's rect, streets, lots (x0, z0, x1, z1, kind 0 home, 1 shop, 2 flats), landmarks. */
    val plan: RailPlan?,
    /** Its own railroad in town (towns near the home town only). */
    val rail: TownRailData?,
)

class RailPlan(val rect: FloatArray, val roads: List<FloatArray>, val lots: List<FloatArray>, val landmarks: List<FloatArray>)

class RailLinkInfo(
    val id: String, val a: String, val b: String, val dir: String, val kind: String, val line: String, val built: Int,
    val passengerEnd: Int?, val abandoned: Int?, val trail: Int?, val company: String,
    /** Its length along the track, in world tiles. */
    val length: Double,
    /** The track's route between the two towns' line points (town frame, x and z pairs). */
    val pts: FloatArray,
) {
    val main: Boolean get() = kind == "main"
    fun other(id: String): String = if (a == id) b else a
    fun trackIn(year: Int): Boolean = built <= year && (abandoned == null || year < abandoned)
    fun passengersOn(year: Int): Boolean = trackIn(year) && (passengerEnd == null || year <= passengerEnd)
    /** The state of its track in [year]: none (not built), track, bed (lifted), trail. */
    fun state(year: Int): String = when {
        built > year -> "none"
        trackIn(year) -> "track"
        trail != null && trail <= year -> "trail"
        else -> "bed"
    }
}

class RailTrack(val link: String, val kind: String, val pts: FloatArray)
class RailCrossing(val x: Float, val z: Float, val dx: Float, val dz: Float, val road: Int, val link: String, val lightsFrom: Int, val gatesFrom: Int?) {
    /** The protection standing in [year] on a line whose track is there: crossbuck, lights or gates. */
    fun gear(year: Int): String = if (gatesFrom != null && year >= gatesFrom) "gates" else if (year >= lightsFrom) "lights" else "crossbuck"
}
class RailDepot(val rect: FloatArray, val x: Float, val z: Float, val dx: Float, val dz: Float, val side: Int, val built: Int)
class RailPortal(val link: String, val to: String, val x: Float, val z: Float)

/** A town's own railroad: tracks (with the siding), level crossings, the depot, the elevator's site, where each line leaves. */
class TownRailData(val tracks: List<RailTrack>, val crossings: List<RailCrossing>, val depot: RailDepot?, val elevator: FloatArray?, val portals: List<RailPortal>)

class RailLine(val phases: Map<String, Double>, val along: Map<Int, Double>)

class RailLeg(
    val mode: String, val from: String, val fromName: String, val to: String, val toName: String,
    val depart: Double, val arrive: Double, val km: Double, val line: String? = null, val operator: String? = null,
    val stops: List<String> = emptyList(), val via: List<String> = emptyList(),
)

/** A route ([legs] non-null), or why there is none ([reason]). Times are minutes after midnight of the day of travel. */
class RailRoute(
    val from: String, val to: String, val year: Int, val legs: List<RailLeg>?, val depart: Double = 0.0, val arrive: Double = 0.0,
    val changes: Int = 0, val km: Double = 0.0, val fare: Double = 0.0, val notes: List<String> = emptyList(),
    val reason: String? = null, val nearest: String? = null,
) {
    val ok: Boolean get() = legs != null
}

class RailNet(
    val town: String, val name: String, val worldSeed: String, val radius: Int, val from: Int, val to: Int,
    val scale: Float, val metersPerTile: Double,
    val serviceName: String, val serviceFull: String, val serviceFrom: Int,
    private val transferMin: Double, private val changePenalty: Double, private val firstDeparture: Double, private val serviceHours: Double,
    private val roadFactor: Double, private val roadKmh: Double, private val roadWait: Double,
    val rail: TownRailData,
    val towns: List<RailTownInfo>,
    val links: List<RailLinkInfo>,
    val lines: Map<String, RailLine>,
) {
    private val byId = towns.associateBy { it.id }
    private val linkById = links.associateBy { it.id }
    val home: RailTownInfo get() = byId["0,0"]!!

    fun townOf(id: String): RailTownInfo? = byId[id]
    fun link(id: String): RailLinkInfo? = linkById[id]

    /** The station's status letter of town [id] in [year] ('n' outside the exported towns). */
    fun status(id: String, year: Int): Char {
        val t = byId[id] ?: return 'n'
        return t.status[(year.coerceIn(from, to) - from)]
    }
    fun open(id: String, year: Int): Boolean = status(id, year) == 'p'

    /** Every line ever built into town [id]: east, west, south, north (src/world/rail.ts linksAt's order). */
    fun linksAt(id: String): List<RailLinkInfo> {
        val (cx, cy) = id.split(",").map { it.toInt() }
        return listOfNotNull(linkById["ew:$cx,$cy"], linkById["ew:${cx - 1},$cy"], linkById["ns:$cx,$cy"], linkById["ns:$cx,${cy - 1}"])
    }

    // ------------------------------------------------------------------ timetables (src/world/rail.ts)

    fun trainsPerDay(main: Boolean, year: Int): Int =
        if (main) (if (year < 1950) 6 else if (year < 1960) 5 else if (year < serviceFrom) 3 else if (year < 2000) 2 else 3)
        else (if (year < 1950) 3 else if (year < 1960) 2 else 1)

    fun trainSpeed(main: Boolean, year: Int): Double {
        val v = if (year < 1950) 45.0 else if (year < serviceFrom) 60.0 else if (year < 2000) 75.0 else 95.0
        return if (main) v else v * 0.7
    }

    fun tilesToKm(t: Double): Double = t * metersPerTile / 1000.0
    private fun minutesFor(tiles: Double, main: Boolean, year: Int): Double = tilesToKm(tiles) / trainSpeed(main, year) * 60.0

    /** Departures of [line] at position [i] heading [dir] in [year] (minutes after midnight), earliest first. */
    fun departures(line: String, i: Int, dir: Int, year: Int): DoubleArray {
        val main = !line.contains("b:")
        val n = trainsPerDay(main, year)
        val head = serviceHours * 60.0 / n
        val l = lines[line] ?: return DoubleArray(0)
        val phase = (l.phases["${if (year >= serviceFrom) "n" else "p"}$dir"] ?: 0.0) * head
        val along = l.along[i] ?: return DoubleArray(0)
        val off = dir * minutesFor(along, main, year)
        val first = firstDeparture + (((phase + off) % head) + head) % head
        return DoubleArray(n) { k -> jsRound((first + k * head) * 10.0) / 10.0 }
    }

    private fun nextDeparture(line: String, i: Int, dir: Int, year: Int, t: Double): Double {
        val day = floor(t / 1440.0)
        val deps = departures(line, i, dir, year)
        for (d in listOf(day, day + 1, day + 2)) for (dep in deps) if (d * 1440 + dep >= t - 1e-6) return d * 1440 + dep
        return Double.POSITIVE_INFINITY
    }

    /** What a ticket costs in [year] for [km] (dollars of that year). */
    fun fare(year: Int, km: Double): Double {
        val pts = arrayOf(doubleArrayOf(1900.0, .1, .012), doubleArrayOf(1950.0, .25, .02), doubleArrayOf(1971.0, 1.0, .045), doubleArrayOf(1990.0, 3.0, .09), doubleArrayOf(2025.0, 6.0, .16))
        var lo = pts[0]; val hiLast = pts[pts.size - 1]; var hi = hiLast
        for (i in 0 until pts.size - 1) if (year >= pts[i][0] && year <= pts[i + 1][0]) { lo = pts[i]; hi = pts[i + 1]; break }
        if (year > hi[0]) lo = hi
        val t = if (hi[0] == lo[0]) 0.0 else (min(year.toDouble(), hi[0]) - lo[0]) / (hi[0] - lo[0])
        val mn = lo[1] + (hi[1] - lo[1]) * t; val perKm = lo[2] + (hi[2] - lo[2]) * t
        return jsRound(max(mn, perKm * km) * 100.0) / 100.0
    }

    // ------------------------------------------------------------------ finding towns

    /** The town called [query] (any case, or an id "cx,cy") nearest cell ([cx], [cy]), ring by ring. */
    fun find(query: String, cx: Int = 0, cy: Int = 0, radius: Int = 8): RailTownInfo? {
        val q = query.trim().lowercase()
        if (Regex("^-?\\d+,-?\\d+$").matches(q)) return byId[q]
        for (r in 0..radius) {
            var best: RailTownInfo? = null; var bd = Double.MAX_VALUE
            for (t in towns) {
                if (max(abs(t.cx - cx), abs(t.cy - cy)) != r || t.name.lowercase() != q) continue
                val d = hypot((t.cx - cx).toDouble(), (t.cy - cy).toDouble())
                if (d < bd) { best = t; bd = d }
            }
            if (best != null) return best
        }
        return null
    }

    /** Towns whose name (or a word of it) starts with [prefix], nearest the home town first: the ticket window's search. */
    fun search(prefix: String, limit: Int = 12): List<RailTownInfo> {
        val q = prefix.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return towns.filter { val n = it.name.lowercase(); n.startsWith(q) || n.contains(" $q") }
            .sortedBy { hypot(it.cx.toDouble(), it.cy.toDouble()) }.take(limit)
    }

    // ------------------------------------------------------------------ the route planner (src/world/rail.ts planRoute)

    private fun nearestStation(s: RailTownInfo, year: Int, radius: Int = 4): Pair<RailTownInfo, Double>? {
        var best: Pair<RailTownInfo, Double>? = null
        for (r in 0..radius) {
            for (cy in s.cy - r..s.cy + r) for (cx in s.cx - r..s.cx + r) {
                if (max(abs(cx - s.cx), abs(cy - s.cy)) != r) continue
                val o = byId["$cx,$cy"] ?: continue
                if (status(o.id, year) != 'p') continue
                val km = tilesToKm(hypot(o.tileX - s.tileX, o.tileY - s.tileY) * roadFactor)
                if (best == null || km < best.second) best = o to km
            }
            if (best != null && r >= 1) return best
        }
        return best
    }

    private fun roadLeg(a: RailTownInfo, b: RailTownInfo, km: Double, depart: Double) =
        RailLeg("road", a.id, a.name, b.id, b.name, depart, depart + roadWait + jsRound(km / roadKmh * 60.0), jsRound(km * 10.0) / 10.0)

    /**
     * The quickest way from town [fromQ] to town [toQ] (ids or names; [fromQ] looked up nearest cell [nearX], [nearY],
     * [toQ] nearest the town found) in [year], leaving at [time] minutes after midnight. As src/world/rail.ts planRoute.
     */
    fun planRoute(fromQ: String, toQ: String, year: Int, time: Double = 8.0 * 60, nearX: Int = 0, nearY: Int = 0): RailRoute {
        val idRe = Regex("^-?\\d+,-?\\d+$")
        val a = if (idRe.matches(fromQ)) byId[fromQ] else find(fromQ, nearX, nearY)
        val b = if (a == null) null else if (idRe.matches(toQ)) byId[toQ] else find(toQ, a.cx, a.cy)
        if (a == null || b == null) return RailRoute(fromQ, toQ, year, null, reason = "No town called ${if (a == null) fromQ else toQ} nearby.")
        val notes = ArrayList<String>()
        val legs = ArrayList<RailLeg>()
        if (a.id == b.id) return RailRoute(a.id, b.id, year, legs, time, time, notes = listOf("You are already there."))
        var t = time
        var start = a; var end = b
        if (status(a.id, year) != 'p') {
            val n = nearestStation(a, year) ?: return RailRoute(a.id, b.id, year, null, reason = "No passenger trains anywhere near ${a.name} in $year.")
            notes += "${a.name} has no passenger trains in $year: by road to ${n.first.name} first."
            start = n.first
        }
        if (status(b.id, year) != 'p') {
            val n = nearestStation(b, year) ?: return RailRoute(a.id, b.id, year, null, reason = "Passenger trains go nowhere near ${b.name} in $year.")
            notes += "Passenger trains do not stop at ${b.name} in $year: the nearest station is ${n.first.name}, ${fmt1(n.second)} km by road."
            end = n.first
        }
        if (start.id != a.id) { val l = roadLeg(a, start, nearestStation(a, year)!!.second, t); legs += l; t = l.arrive }
        if (start.id != end.id) {
            val rail = railLegs(start, end, year, t)
            if (rail == null) {
                val km = tilesToKm(hypot(b.tileX - a.tileX, b.tileY - a.tileY) * roadFactor)
                return RailRoute(a.id, b.id, year, null, reason = "No passenger trains run between ${start.name} and ${end.name} in $year; it is ${jsRound(km).toLong()} km by road.", nearest = start.name)
            }
            legs += rail
            t = rail.last().arrive
        }
        if (end.id != b.id) legs += roadLeg(end, b, nearestStation(b, year)!!.second, t)
        val trains = legs.filter { it.mode == "train" }
        val km = trains.sumOf { it.km }
        return RailRoute(a.id, b.id, year, legs, legs.first().depart, legs.last().arrive, max(0, trains.size - 1),
            jsRound(legs.sumOf { it.km } * 10.0) / 10.0, if (trains.isNotEmpty()) fare(year, km) else 0.0, notes)
    }

    private class Edge(val link: RailLinkInfo, val to: String, val line: String, val dir: Int, val pos: Int)
    private class St(val id: String, val line: String, val dir: Int, val t: Double, val c: Double, val prev: String?, val edge: Edge?, val dep: Double)

    private fun edgesFrom(id: String, year: Int): List<Edge> {
        val (cx, cy) = id.split(",").map { it.toInt() }
        val out = ArrayList<Edge>()
        for (l in linksAt(id)) {
            if (!l.passengersOn(year)) continue
            if (year >= serviceFrom && !l.main) continue
            out += Edge(l, l.other(id), l.line, if (l.a == id) 1 else -1, if (l.dir == "ew") cx else cy)
        }
        return out
    }

    private fun railLegs(a: RailTownInfo, b: RailTownInfo, year: Int, t0: Double): List<RailLeg>? {
        val pad = 3
        val x0 = min(a.cx, b.cx) - pad; val x1 = max(a.cx, b.cx) + pad; val y0 = min(a.cy, b.cy) - pad; val y1 = max(a.cy, b.cy) + pad
        val best = HashMap<String, St>()
        fun key(id: String, line: String, dir: Int) = "$id|$line|$dir"
        val openCache = HashMap<String, Boolean>()
        fun isOpen(id: String) = openCache.getOrPut(id) { status(id, year) == 'p' }
        val heap = ArrayList<St>()
        fun push(s: St) {
            val k = key(s.id, s.line, s.dir)
            val bb = best[k]
            if (bb != null && bb.c <= s.c) return
            best[k] = s
            heap += s
            var i = heap.size - 1
            while (i > 0) { val p = (i - 1) shr 1; if (heap[p].c <= heap[i].c) break; val tmp = heap[p]; heap[p] = heap[i]; heap[i] = tmp; i = p }
        }
        fun pop(): St {
            val top = heap[0]; val last = heap.removeAt(heap.size - 1)
            if (heap.isNotEmpty()) {
                heap[0] = last
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1; var m = i
                    if (l < heap.size && heap[l].c < heap[m].c) m = l
                    if (r < heap.size && heap[r].c < heap[m].c) m = r
                    if (m == i) break
                    val tmp = heap[m]; heap[m] = heap[i]; heap[i] = tmp; i = m
                }
            }
            return top
        }
        push(St(a.id, "", 0, t0, t0, null, null, t0))
        var goal: St? = null
        while (heap.isNotEmpty()) {
            val s = pop()
            if (best[key(s.id, s.line, s.dir)] !== s) continue
            if (s.id == b.id) { goal = s; break }
            if (s.t - t0 > 3 * 1440) break
            for (e in edgesFrom(s.id, year)) {
                val (ncx, ncy) = e.to.split(",").map { it.toInt() }
                if (ncx < x0 || ncx > x1 || ncy < y0 || ncy > y1) continue
                if (byId[e.to] == null) continue
                val stay = e.line == s.line && e.dir == s.dir
                if (!stay && !isOpen(s.id)) continue
                val dep = if (stay) s.t else nextDeparture(e.line, e.pos, e.dir, year, s.t + (if (s.line.isNotEmpty()) transferMin else 0.0))
                if (dep.isInfinite()) continue
                val arr = dep + minutesFor(e.link.length, e.link.main, year)
                push(St(e.to, e.line, e.dir, arr, s.c + (arr - s.t) + (if (stay) 0.0 else changePenalty), key(s.id, s.line, s.dir), e, dep))
            }
        }
        if (goal == null) return null
        val chain = ArrayList<St>()
        var s: St? = goal
        while (s != null && s.edge != null) { chain.add(0, s); s = s.prev?.let { best[it] } }
        val legs = ArrayList<RailLeg>()
        var curFrom = ""; var curLine = ""; var curDir = 0; var curDep = 0.0; var via = ArrayList<String>(); var curKm = 0.0; var company = ""
        var have = false
        var prevId = a.id; var prevArrive = t0
        fun close(arrive: Double, to: String) {
            if (!have) return
            val stops = via.subList(1, via.size - 1).filter { isOpen(it) }.map { byId[it]!!.name }
            legs += RailLeg("train", curFrom, byId[curFrom]!!.name, to, byId[to]!!.name, jsRound(curDep * 10) / 10, jsRound(arrive * 10) / 10,
                jsRound(curKm * 10) / 10, curLine, if (year >= serviceFrom) serviceFull else company, stops, via.toList())
        }
        for (st in chain) {
            val e = st.edge!!
            if (!have || curLine != e.line || curDir != e.dir || st.dep != prevArrive) {
                if (have) close(prevArrive, prevId)
                have = true; curFrom = prevId; curLine = e.line; curDir = e.dir; curDep = st.dep; via = arrayListOf(prevId); curKm = 0.0; company = e.link.company
            }
            via += st.id
            curKm += tilesToKm(e.link.length)
            prevArrive = st.t
            prevId = st.id
        }
        close(prevArrive, prevId)
        return legs
    }

    /** The era of the trains in [year]: steam, diesel, national, modern (src/world/rail.ts trainEra). */
    fun trainEra(year: Int, main: Boolean = true): String = when {
        year < 1950 -> "steam"
        year < 1957 || (!main && year < 1959) -> if (year < 1953 || !main) "steam" else "diesel"
        year < serviceFrom -> "diesel"
        year < 2000 -> "national"
        else -> "modern"
    }

    companion object {
        /** JavaScript's Math.round (half up, towards +infinity). */
        fun jsRound(v: Double): Double = floor(v + 0.5)
        fun fmt1(v: Double): String { val r = jsRound(v * 10) / 10; return if (r == floor(r)) r.toLong().toString() else r.toString() }
        /** "8:05" for minutes after midnight (a later day's times wrap). */
        fun clock(min: Double): String { val m = (jsRound(min).toLong() % 1440 + 1440) % 1440; return "%d:%02d".format(m / 60, m % 60) }

        private fun floats(a: JSONArray?): FloatArray = if (a == null) FloatArray(0) else FloatArray(a.length()) { a.getDouble(it).toFloat() }
        private fun pairs(a: JSONArray?): FloatArray {
            if (a == null) return FloatArray(0)
            val out = FloatArray(a.length() * 2)
            for (i in 0 until a.length()) { val p = a.getJSONArray(i); out[i * 2] = p.getDouble(0).toFloat(); out[i * 2 + 1] = p.getDouble(1).toFloat() }
            return out
        }

        fun parseRail(o: JSONObject?): TownRailData? {
            if (o == null) return null
            val tracks = o.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { val t = a.getJSONObject(it); RailTrack(t.getString("link"), t.getString("kind"), pairs(t.getJSONArray("pts"))) } } ?: emptyList()
            val xs = o.optJSONArray("crossings")?.let { a -> (0 until a.length()).map {
                val c = a.getJSONObject(it); val at = c.getJSONArray("at"); val d = c.getJSONArray("dir")
                RailCrossing(at.getDouble(0).toFloat(), at.getDouble(1).toFloat(), d.getDouble(0).toFloat(), d.getDouble(1).toFloat(), c.getInt("road"), c.getString("link"),
                    c.getInt("lightsFrom"), if (c.has("gatesFrom")) c.getInt("gatesFrom") else null)
            } } ?: emptyList()
            val depot = o.optJSONObject("depot")?.let { d -> val at = d.getJSONArray("at"); val dir = d.getJSONArray("dir")
                RailDepot(floats(d.getJSONArray("rect")), at.getDouble(0).toFloat(), at.getDouble(1).toFloat(), dir.getDouble(0).toFloat(), dir.getDouble(1).toFloat(), d.getInt("side"), d.getInt("built")) }
            val portals = o.optJSONArray("portals")?.let { a -> (0 until a.length()).map { val p = a.getJSONObject(it); val at = p.getJSONArray("at"); RailPortal(p.getString("link"), p.getString("to"), at.getDouble(0).toFloat(), at.getDouble(1).toFloat()) } } ?: emptyList()
            return TownRailData(tracks, xs, depot, o.optJSONArray("elevator")?.let { floats(it) }, portals)
        }

        /** Reads a rail.json (null if it is not one). */
        fun parse(json: String): RailNet? = runCatching { parse(JSONObject(json)) }.getOrNull()

        fun parse(root: JSONObject): RailNet {
            val sv = root.getJSONObject("service")
            val towns = root.getJSONArray("towns").let { a -> (0 until a.length()).map {
                val t = a.getJSONObject(it); val c = t.getJSONArray("center"); val tile = t.getJSONArray("tile")
                val plan = t.optJSONObject("plan")?.let { p ->
                    fun rects(k: String) = p.optJSONArray(k)?.let { r -> (0 until r.length()).map { i -> floats(r.getJSONArray(i)) } } ?: emptyList()
                    RailPlan(floats(p.getJSONArray("rect")), rects("roads"), rects("lots"), rects("landmarks"))
                }
                RailTownInfo(t.getString("id"), t.getInt("cx"), t.getInt("cy"), t.getString("name"), t.optString("kind"), t.optString("rank"), t.optInt("founded"),
                    c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), tile.getDouble(0), tile.getDouble(1), t.getString("status"), plan, parseRail(t.optJSONObject("rail")))
            } }
            val links = root.getJSONArray("links").let { a -> (0 until a.length()).map {
                val l = a.getJSONObject(it)
                fun opt(k: String): Int? = if (l.has(k)) l.getInt(k) else null
                RailLinkInfo(l.getString("id"), l.getString("a"), l.getString("b"), l.getString("dir"), l.getString("kind"), l.getString("line"), l.getInt("built"),
                    opt("passengerEnd"), opt("abandoned"), opt("trail"), l.getString("company"), l.getDouble("length"), pairs(l.getJSONArray("pts")))
            } }
            val ls = root.getJSONObject("lines")
            val lines = HashMap<String, RailLine>()
            for (k in ls.keys()) {
                val o = ls.getJSONObject(k)
                val ph = HashMap<String, Double>()
                for (p in listOf("p1", "p-1", "n1", "n-1")) if (o.has(p)) ph[p] = o.getDouble(p)
                val al = HashMap<Int, Double>()
                o.optJSONObject("along")?.let { a -> for (i in a.keys()) al[i.toInt()] = a.getDouble(i) }
                lines[k] = RailLine(ph, al)
            }
            return RailNet(root.getString("town"), root.optString("name"), root.getJSONObject("world").getString("seed"), root.getInt("radius"), root.getInt("from"), root.getInt("to"),
                root.getDouble("scale").toFloat(), root.getDouble("metersPerTile"),
                sv.getString("name"), sv.getString("full"), sv.getInt("from"), sv.getDouble("transferMin"), sv.getDouble("changePenalty"), sv.getDouble("serviceFrom"), sv.getDouble("serviceHours"),
                sv.getDouble("roadFactor"), sv.getDouble("roadKmh"), sv.getDouble("roadWait"),
                parseRail(root.getJSONObject("rail"))!!, towns, links, lines)
        }
    }
}
