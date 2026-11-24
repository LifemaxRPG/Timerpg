package com.lifemax.rpg.feature.habits.maps

import com.lifemax.rpg.feature.habits.domain.GlbPlaced
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a map label names, which decides its look and the tiers that show it. */
enum class LabelKind { STREET, LANDMARK, CIVIC, BUSINESS, TOWN, CAPITAL, CITY, WONDER, SEA, ROUTE }

/**
 * A name on a map at street position ([x], [z]) (the town's frame), turned [angle] degrees (streets run along their
 * road; the rest 0), with [priority] (bigger first when labels collide) and, for a street, the [span] of road it names
 * (street units: a label longer than its road on screen is left out).
 */
class MapLabel(val text: String, val x: Float, val z: Float, val kind: LabelKind, val angle: Float = 0f, val priority: Float = 0f, val span: Float = 0f)

/** A route along the roads: its points (x, z pairs, street units, from the start to the end) and its length (street units). */
class MapRoute(val pts: FloatArray, val length: Float)

/** The flat palette of a map: the phone's GPS look, or a printed paper map's. */
enum class MapStyle { GPS, PAPER }

/**
 * The town's city map (docs/MAPS.md, the city tier) made from the town's own data (TownMap: roads, rows' buildings,
 * lots, the plaza, the paved downtown, the river and its bridges, the yards' pools, the forest's trees; the landmarks and
 * the businesses' names; with the town's collision, the buildings' real footprints): [raster] draws it once into pixels (by [MapStyle]); [labels] names the streets, landmarks and
 * businesses; [route] finds the way along the roads (A* over the town's junctions); [streetAt] says which street the
 * player stands on (the pre-GPS rule: where they can read a street sign).
 *
 * Built once per town (at load) off the UI thread; nothing here is per frame.
 */
class TownMapModel private constructor(
    val town: Town,
    val labels: List<MapLabel>,
    private val glb: List<GlbPlaced>,
    private val yards: TownYards?,
    private val civicSprites: Set<String>,
    private val bounds: FloatArray,
    /** The buildings' footprints: each a quad (x, z four times) or a circle (x, z, r), with the building's sprite. */
    private val feet: List<Pair<String, FloatArray>>,
) {
    /** The town's extent (x0, z0, x1, z1, street units), the forest round it left out. */
    val x0: Float get() = bounds[0]
    val z0: Float get() = bounds[1]
    val x1: Float get() = bounds[2]
    val z1: Float get() = bounds[3]
    fun inside(x: Float, z: Float, margin: Float = 0f): Boolean = x >= x0 - margin && z >= z0 - margin && x <= x1 + margin && z <= z1 + margin

    // ------------------------------------------------------------------ the roads as a graph

    private class Edge(val road: Int, val a: Int, val b: Int, val pts: FloatArray, val cum: FloatArray) { val len: Float get() = cum.last() }
    private val edges: List<Edge> = town.roads.mapNotNull { r ->
        if (r.pts.size < 2) return@mapNotNull null
        val na = runCatching { town.node(r.a) }.getOrNull(); val nb = runCatching { town.node(r.b) }.getOrNull()
        var p = r.pts
        // (the polyline runs from node a to node b: turned round if the export has it the other way)
        if (na != null && nb != null && hypot(p.first().x - na.x, p.first().z - na.z) > hypot(p.last().x - na.x, p.last().z - na.z)) p = p.reversed()
        val pts = FloatArray(p.size * 2); for (i in p.indices) { pts[i * 2] = p[i].x; pts[i * 2 + 1] = p[i].z }
        val cum = FloatArray(p.size); for (i in 1 until p.size) cum[i] = cum[i - 1] + hypot(p[i].x - p[i - 1].x, p[i].z - p[i - 1].z)
        Edge(r.id, r.a, r.b, pts, cum)
    }
    private val byNode: Map<Int, List<Edge>> = HashMap<Int, MutableList<Edge>>().also { m -> for (e in edges) { m.getOrPut(e.a) { ArrayList() } += e; m.getOrPut(e.b) { ArrayList() } += e } }
    private val nodeAt: Map<Int, FloatArray> = town.nodes.associate { it.id to floatArrayOf(it.x, it.z) }

    /** The nearest point on a road to ([x], [z]): which edge, how far along it, the point and the distance. */
    private class Snap(val e: Edge, val s: Float, val x: Float, val z: Float, val d: Float)

    private fun snap(x: Float, z: Float): Snap? {
        var best: Snap? = null
        for (e in edges) {
            val p = e.pts
            for (i in 0 until p.size / 2 - 1) {
                val ax = p[i * 2]; val az = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val bz = p[i * 2 + 3]
                val dx = bx - ax; val dz = bz - az; val l2 = dx * dx + dz * dz
                val t = if (l2 > 0f) (((x - ax) * dx + (z - az) * dz) / l2).coerceIn(0f, 1f) else 0f
                val qx = ax + dx * t; val qz = az + dz * t
                val d = hypot(x - qx, z - qz)
                if (best == null || d < best.d) best = Snap(e, e.cum[i] + t * (e.cum[i + 1] - e.cum[i]), qx, qz, d)
            }
        }
        return best
    }

    /** The name of the street the player at ([x], [z]) stands on, within [reach] of its edge (the pavement), else null. */
    fun streetAt(x: Float, z: Float, reach: Float = PAVEMENT): String? {
        val s = snap(x, z) ?: return null
        val r = runCatching { town.road(s.e.road) }.getOrNull() ?: return null
        return if (s.d <= r.width / 2f + reach) r.name else null
    }

    /** The part of [e] from [s0] to [s1] along it (either way), into [out] as x, z pairs (the end points included). */
    private fun along(e: Edge, s0: Float, s1: Float, out: ArrayList<Float>) {
        fun at(s: Float) { val i = e.cum.indexOfLast { it <= s }.coerceIn(0, e.cum.size - 2); val t = ((s - e.cum[i]) / (e.cum[i + 1] - e.cum[i]).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            out += e.pts[i * 2] + (e.pts[i * 2 + 2] - e.pts[i * 2]) * t; out += e.pts[i * 2 + 1] + (e.pts[i * 2 + 3] - e.pts[i * 2 + 1]) * t }
        at(s0)
        if (s1 >= s0) { for (i in e.cum.indices) if (e.cum[i] > s0 && e.cum[i] < s1) { out += e.pts[i * 2]; out += e.pts[i * 2 + 1] } }
        else { for (i in e.cum.indices.reversed()) if (e.cum[i] < s0 && e.cum[i] > s1) { out += e.pts[i * 2]; out += e.pts[i * 2 + 1] } }
        at(s1)
    }

    /**
     * The way from ([ax], [az]) to ([bx], [bz]) along the town's roads (the GPS's route line): each end joined to the
     * nearest point of a road, then A* over the junctions (the straight distance as the estimate, so the first path
     * found is the shortest). Null when the two are not connected.
     */
    fun route(ax: Float, az: Float, bx: Float, bz: Float): MapRoute? {
        val sa = snap(ax, az) ?: return null; val sb = snap(bx, bz) ?: return null
        val out = ArrayList<Float>()
        out += ax; out += az
        if (sa.e === sb.e) {
            along(sa.e, sa.s, sb.s, out)
        } else {
            // A* from a virtual start (to either end of the start's road) to a virtual goal (from either end of the goal's road)
            val g = HashMap<Int, Float>(); val from = HashMap<Int, Pair<Int, Edge>?>()
            val goalCost = HashMap<Int, Float>().also { it[sb.e.a] = sb.s; it[sb.e.b] = (sb.e.len - sb.s) }
            class Q(val n: Int, val f: Float)
            val open = PriorityQueue<Q>(compareBy { it.f })
            fun h(n: Int) = nodeAt[n]?.let { hypot(it[0] - sb.x, it[1] - sb.z) } ?: 0f
            fun push(n: Int, cost: Float, prev: Pair<Int, Edge>?) { if (cost < (g[n] ?: Float.MAX_VALUE)) { g[n] = cost; from[n] = prev; open += Q(n, cost + h(n)) } }
            push(sa.e.a, sa.s, null); push(sa.e.b, sa.e.len - sa.s, null)
            var bestEnd = -1; var bestTotal = Float.MAX_VALUE
            val done = HashSet<Int>()
            while (open.isNotEmpty()) {
                val q = open.poll()
                if (q.f >= bestTotal) break
                if (!done.add(q.n)) continue
                val gn = g.getValue(q.n)
                goalCost[q.n]?.let { c -> if (gn + c < bestTotal) { bestTotal = gn + c; bestEnd = q.n } }
                for (e in byNode[q.n].orEmpty()) { val o = if (e.a == q.n) e.b else e.a; push(o, gn + e.len, q.n to e) }
            }
            if (bestEnd < 0) return null
            // the junctions back from the goal's end to the start's
            val chain = ArrayList<Pair<Int, Edge>>()
            var n = bestEnd
            while (true) { val p = from[n] ?: break; chain += n to p.second; n = p.first }
            chain.reverse()
            val first = n
            along(sa.e, sa.s, if (first == sa.e.a) 0f else sa.e.len, out)
            for ((to, e) in chain) along(e, if (to == e.b) 0f else e.len, if (to == e.b) e.len else 0f, out)
            along(sb.e, if (bestEnd == sb.e.a) 0f else sb.e.len, sb.s, out)
        }
        out += bx; out += bz
        val pts = out.toFloatArray()
        var len = 0f; for (i in 0 until pts.size / 2 - 1) len += hypot(pts[i * 2 + 2] - pts[i * 2], pts[i * 2 + 3] - pts[i * 2 + 1])
        return MapRoute(pts, len)
    }

    // ------------------------------------------------------------------ the picture

    /** The colours of each [MapStyle]. */
    class Palette(val outside: Int, val ground: Int, val lot: Int, val home: Int, val shop: Int, val landmark: Int, val civic: Int,
                  val plaza: Int, val paved: Int, val water: Int, val waterEdge: Int, val tree: Int, val road: Int, val mainRoad: Int,
                  val roadEdge: Int, val lane: Int, val pool: Int, val buildingEdgeK: Float)

    val palette: (MapStyle) -> Palette = { s -> if (s == MapStyle.PAPER) PAPER else GPS }

    /**
     * The city map as pixels, [unitsPerPx] street units a pixel (the default: about 1.7 m), north up, the town's extent
     * and a margin. Drawn in layers: the ground, the lots, the paved downtown and the plaza, the river (its edge a pixel
     * darker), the trees, the back lanes, the roads (an edge, then the road, main roads wider), the bridges, the
     * buildings by kind (homes, shops, landmarks, civic buildings) with a darker edge, the yards' pools.
     */
    fun raster(style: MapStyle = MapStyle.GPS, unitsPerPx: Float = DEFAULT_UPP): MapRaster {
        val p = palette(style)
        val m = MARGIN
        val upp = max(unitsPerPx, max(x1 - x0 + 2 * m, z1 - z0 + 2 * m) / MAX_PX)
        val r = MapRaster(((x1 - x0 + 2 * m) / upp).toInt() + 1, ((z1 - z0 + 2 * m) / upp).toInt() + 1, upp, x0 - m, z0 - m)
        r.fill(p.ground)
        // the lots (each building's ground and yard)
        for (l in town.lots) r.rectU(l.rect, p.lot)
        for ((rect, kind) in town.paved) r.rectU(rect, if (kind == "square") p.plaza else p.paved)
        town.plaza?.let { r.rectU(it, p.plaza) }
        // the river (an edge a pixel wide)
        val wet = HashSet<Int>()
        for (poly in town.water) { val a = FloatArray(poly.size * 2); poly.forEachIndexed { i, q -> a[i * 2] = q.x; a[i * 2 + 1] = q.z }; r.polyU(a, p.water) }
        wet += p.water
        r.outline({ it == p.water }, { p.waterEdge })
        // the trees (the forest and the streets' trees: a pixel or two of dark green), not on water
        val tr = max(.6f, 90f / upp)
        for (pr in town.props) if (pr.kind == "tree" && r.contains(pr.x, pr.z)) {
            val cx = r.toPxX(pr.x); val cy = r.toPxY(pr.z)
            val under = r.get(cx.toInt(), cy.toInt())
            if (under != p.water && under != p.waterEdge && under != p.paved && under != p.plaza) r.disc(cx, cy, tr, p.tree)
        }
        // back lanes (narrow)
        yards?.lanes?.forEach { l -> r.rectU(l.rect, p.lane) }
        // the roads: an edge, then the road; main roads (Main Street, the civic avenue) a little wider
        fun width(rd: com.lifemax.rpg.feature.habits.domain.Road) = (rd.width * (if (rd.main || rd.id == town.civicRoad) 1.35f else 1f)) / upp
        for (e in edges) { val rd = town.road(e.road); r.polylineU(e.pts, width(rd) + 2f, p.roadEdge) }
        for (e in edges) { val rd = town.road(e.road); r.polylineU(e.pts, width(rd), if (rd.main || rd.id == town.civicRoad) p.mainRoad else p.road) }
        for (b in town.bridges) r.rectU(b.rect, p.road)
        // the buildings by kind, each with an edge a shade darker: their solid parts as the walk meets them (the town's
        // collision: the models' real walls, the cinema, the stores as built), else each slot's box
        val xs = FloatArray(4); val ys = FloatArray(4)
        val kindOf = HashMap<String, Int>()
        for (row in town.rows) for (b in row.buildings) kindOf[b.sprite] = when {
            b.sprite in civicSprites -> p.civic
            b.kind == PlacementKind.LANDMARK -> p.landmark
            b.kind == PlacementKind.SHOP -> p.shop
            else -> p.home
        }
        for ((sprite, f) in feet) {
            val c = kindOf[sprite] ?: p.home
            if (f.size == 3) { r.disc(r.toPxX(f[0]), r.toPxY(f[1]), f[2] / upp, c); continue }
            for (i in 0..3) { xs[i] = r.toPxX(f[i * 2]); ys[i] = r.toPxY(f[i * 2 + 1]) }
            r.poly(xs, ys, 4, c)
        }
        val built = setOf(p.home, p.shop, p.landmark, p.civic)
        r.outline({ it in built }, { MapRaster.shade(it, p.buildingEdgeK) })
        // the rich homes' pools
        yards?.yards?.forEach { y -> for (it in y.items) if (it.kind == "pool") r.rectU(it.rect, p.pool) }
        return r
    }

    companion object {
        /** How far from a road's edge the player still reads its sign (street units: the pavement and a step; about 12 m). */
        const val PAVEMENT = 260f
        const val DEFAULT_UPP = 36f
        const val MAX_PX = 2400f
        const val MARGIN = 1200f

        val GPS = Palette(
            outside = MapRaster.argb(0x24331F), ground = MapRaster.argb(0x3F5A35), lot = MapRaster.argb(0x4B6A3E),
            home = MapRaster.argb(0xC9B68E), shop = MapRaster.argb(0xE08A4C), landmark = MapRaster.argb(0xB86FA8), civic = MapRaster.argb(0x7FA6DB),
            plaza = MapRaster.argb(0xB9B19F), paved = MapRaster.argb(0x5E5F63), water = MapRaster.argb(0x3D7FCB), waterEdge = MapRaster.argb(0x2A5C9C),
            tree = MapRaster.argb(0x2C4526), road = MapRaster.argb(0xE9E9E4), mainRoad = MapRaster.argb(0xF7D774), roadEdge = MapRaster.argb(0x1E2024),
            lane = MapRaster.argb(0x8A8C88), pool = MapRaster.argb(0x5FD0E8), buildingEdgeK = .62f,
        )
        val PAPER = Palette(
            outside = MapRaster.argb(0xE3D6B4), ground = MapRaster.argb(0xEFE4C6), lot = MapRaster.argb(0xE9DDBC),
            home = MapRaster.argb(0xD9B98F), shop = MapRaster.argb(0xD08C64), landmark = MapRaster.argb(0xB07A9C), civic = MapRaster.argb(0x9AA7C0),
            plaza = MapRaster.argb(0xD8CBA8), paved = MapRaster.argb(0xD2C6A6), water = MapRaster.argb(0x9CC3DF), waterEdge = MapRaster.argb(0x5E8DB3),
            tree = MapRaster.argb(0xB5C98F), road = MapRaster.argb(0xFFFBEF), mainRoad = MapRaster.argb(0xF3C66B), roadEdge = MapRaster.argb(0x7A5A3A),
            lane = MapRaster.argb(0xC9B993), pool = MapRaster.argb(0x9CC3DF), buildingEdgeK = .72f,
        )

        private val CIVIC_WORDS = listOf("post_office", "fire_station", "police", "library", "school", "elementary", "church", "courthouse", "town_hall", "city_hall", "clocktower", "station")

        /**
         * The city map of [town]: [glb] its hand-made landmarks (GlbLandmarks.parse of street.json), [yards] its yards and
         * back lanes, [names] each building's business name by sprite (street.json's shops and the loop's places).
         */
        fun build(town: Town, glb: List<GlbPlaced> = emptyList(), yards: TownYards? = null, names: Map<String, String> = emptyMap(),
                  collision: com.lifemax.rpg.feature.habits.domain.TownCollision? = null): TownMapModel {
            // the extent: the junctions, the buildings' rows and the river, not the forest round them
            var ax = Float.MAX_VALUE; var az = Float.MAX_VALUE; var bx = -Float.MAX_VALUE; var bz = -Float.MAX_VALUE
            fun grow(x: Float, z: Float) { ax = min(ax, x); az = min(az, z); bx = max(bx, x); bz = max(bz, z) }
            for (n in town.nodes) grow(n.x, n.z)
            for (l in town.lots) { grow(l.rect[0], l.rect[1]); grow(l.rect[2], l.rect[3]) }
            for (r in town.roads) for (q in r.pts) grow(q.x, q.z)
            val civic = HashSet<String>()
            for (g in glb) if (CIVIC_WORDS.any { it in g.kind }) civic += g.sprite
            for (l in town.landmarks) if (CIVIC_WORDS.any { it in l.kind }) civic += l.sprite
            // the labels: every road piece (named by its street; collisions and length are decided when drawn), the
            // landmarks, the civic buildings and the businesses
            val labels = ArrayList<MapLabel>()
            labels += streetLabels(town)
            val named = HashSet<String>()
            fun at(sprite: String): Pair<Float, Float>? = town.placement(sprite)?.let { (row, p) -> TownPlace.of(row, p).world(0f, p.setback + p.depth / 2f).let { it.x to it.z } }
            for (l in town.landmarks) {
                if (l.name.isBlank()) continue
                val x = (l.rect[0] + l.rect[2]) / 2f; val z = (l.rect[1] + l.rect[3]) / 2f
                val nm = names[l.sprite] ?: l.name
                labels += MapLabel(nm, x, z, if (l.sprite in civic) LabelKind.CIVIC else LabelKind.LANDMARK, 0f, 900f); named += l.sprite
            }
            for (g in glb) {
                if (g.name.isBlank() || g.sprite in named) continue
                val (x, z) = at(g.sprite) ?: continue
                labels += MapLabel(names[g.sprite] ?: g.name, x, z, if (g.sprite in civic) LabelKind.CIVIC else LabelKind.LANDMARK, 0f, 800f); named += g.sprite
            }
            for ((sprite, nm) in names) {
                if (sprite in named || nm.isBlank()) continue
                val (x, z) = at(sprite) ?: continue
                labels += MapLabel(nm, x, z, LabelKind.BUSINESS, 0f, 300f); named += sprite
            }
            return TownMapModel(town, labels, glb, yards, civic, floatArrayOf(ax, az, bx, bz), footprints(town, collision))
        }

        /**
         * The buildings' footprints, read once (at the town's load, on its thread: the collision answers one thread at a
         * time): the solid parts the walk meets ([collision]: the models' real walls, the cinema, the stores as built),
         * and each slot's box for a building the collision does not hold.
         */
        private fun footprints(town: Town, collision: com.lifemax.rpg.feature.habits.domain.TownCollision?): List<Pair<String, FloatArray>> {
            val out = ArrayList<Pair<String, FloatArray>>()
            val drawn = HashSet<String>()
            collision?.let { col ->
                val tmp = FloatArray(6)
                col.forEachNear(col.minX, col.minZ, col.maxX, col.maxZ, tmp) { _, kind, owner, circle, d ->
                    if (kind != com.lifemax.rpg.feature.habits.domain.SolidKind.BUILDING || owner == null) return@forEachNear
                    drawn += owner
                    if (circle) { out += owner to floatArrayOf(d[0], d[1], d[2]); return@forEachNear }
                    val ux = d[2]; val uz = d[3]; val hu = d[4]; val hv = d[5]
                    val vx = -uz; val vz = ux
                    out += owner to floatArrayOf(d[0] - ux * hu - vx * hv, d[1] - uz * hu - vz * hv, d[0] + ux * hu - vx * hv, d[1] + uz * hu - vz * hv,
                        d[0] + ux * hu + vx * hv, d[1] + uz * hu + vz * hv, d[0] - ux * hu + vx * hv, d[1] - uz * hu + vz * hv)
                }
            }
            for (row in town.rows) for (b in row.buildings) {
                if (b.flat || b.sprite in drawn) continue
                val place = TownPlace.of(row, b)
                val hw = b.width / 2f
                val c = arrayOf(place.world(-hw, b.setback), place.world(hw, b.setback), place.world(hw, b.setback + b.depth), place.world(-hw, b.setback + b.depth))
                out += b.sprite to floatArrayOf(c[0].x, c[0].z, c[1].x, c[1].z, c[2].x, c[2].z, c[3].x, c[3].z)
            }
            return out
        }

        /**
         * The streets' names: each street's road pieces joined into runs through the junctions where the street goes on
         * nearly straight (so a name is not held to one block), a candidate label at the middle of each run (and, on a
         * long run, at a sixth and five sixths along) along it, read left to right. Which of them are drawn is decided
         * on screen (room, the run's length there, the same name close by).
         */
        private fun streetLabels(town: Town): List<MapLabel> {
            fun dir(a: com.lifemax.rpg.feature.habits.domain.TownPoint, b: com.lifemax.rpg.feature.habits.domain.TownPoint) = atan2((b.z - a.z).toDouble(), (b.x - a.x).toDouble())
            fun turn(a: Double, b: Double): Double { var d = abs(a - b) % (2 * Math.PI); if (d > Math.PI) d = 2 * Math.PI - d; return d }
            val out = ArrayList<MapLabel>()
            for ((name, roads) in town.roads.filter { it.pts.size >= 2 && it.name.isNotBlank() }.groupBy { it.name }) {
                val left = roads.toMutableList()
                while (left.isNotEmpty()) {
                    val first = left.removeAt(0)
                    val run = ArrayList(first.pts)
                    var main = first.main || first.id == town.civicRoad
                    // grow the run at its end, then at its start, through a junction where a piece of the same street goes on straight
                    for (atEnd in listOf(true, false)) while (true) {
                        val tip = if (atEnd) run.last() else run.first()
                        val heading = if (atEnd) dir(run[run.size - 2], run.last()) else dir(run[1], run[0])
                        var pick: Pair<Int, List<com.lifemax.rpg.feature.habits.domain.TownPoint>>? = null
                        for ((i, r) in left.withIndex()) {
                            val pts = when { hypot(r.pts.first().x - tip.x, r.pts.first().z - tip.z) < 60f -> r.pts; hypot(r.pts.last().x - tip.x, r.pts.last().z - tip.z) < 60f -> r.pts.reversed(); else -> continue }
                            if (turn(heading, dir(pts[0], pts[1])) < Math.toRadians(25.0)) { pick = i to pts; break }
                        }
                        val (i, pts) = pick ?: break
                        main = main || left[i].main || left[i].id == town.civicRoad
                        left.removeAt(i)
                        if (atEnd) run.addAll(pts.drop(1)) else run.addAll(0, pts.reversed().dropLast(1))
                    }
                    var len = 0f; val cum = FloatArray(run.size); for (i in 1 until run.size) { len += hypot(run[i].x - run[i - 1].x, run[i].z - run[i - 1].z); cum[i] = len }
                    val at = if (len > 5000f) listOf(1f / 6f, .5f, 5f / 6f) else listOf(.5f)
                    for (f in at) {
                        val s = len * f
                        val i = (1 until run.size).firstOrNull { cum[it] >= s } ?: (run.size - 1)
                        val a = run[i - 1]; val b = run[i]
                        val t = ((s - cum[i - 1]) / (cum[i] - cum[i - 1]).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
                        var ang = Math.toDegrees(dir(a, b)).toFloat()
                        if (ang > 90f) ang -= 180f else if (ang < -90f) ang += 180f
                        out += MapLabel(name, a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t, LabelKind.STREET, ang, (if (main) 4000f else 0f) + len, len / at.size)
                    }
                }
            }
            return out
        }

        /** Each shop's name by its sprite, from street.json v2's root (its `shops`, those that are not homes). */
        fun shopNames(root: org.json.JSONObject): Map<String, String> {
            val out = HashMap<String, String>()
            root.optJSONArray("shops")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { s -> if (!s.optBoolean("home") && s.optString("name").isNotBlank()) out[s.optString("sprite")] = s.optString("name") } }
            // the store sites under the names they trade as in the town's year
            val g = root.optJSONObject("graph")
            val site = HashMap<String, String>()
            g?.optJSONArray("landmarks")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { site[it.optString("id")] = it.optString("sprite") } }
            g?.optJSONArray("stores")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { s ->
                val sp = site[s.optString("landmark")] ?: return@let
                val nm = when (s.optString("state")) { "reused" -> s.optJSONObject("reuse")?.optString("name"); "open" -> s.optString("name"); else -> s.optString("sign").ifEmpty { null } }
                if (!nm.isNullOrBlank()) out[sp] = nm
            } }
            return out
        }
    }
}

/** The straight distance between two street points, in metres (VehicleWorld.U street units a metre). */
fun streetMetres(ax: Float, az: Float, bx: Float, bz: Float): Float = hypot(bx - ax, bz - az) / com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U

/** A distance in metres as a map writes it ("80 m", "1.4 km"). */
fun distanceText(m: Float): String = when {
    m < 1000f -> "${(Math.round(m / 10f) * 10).coerceAtLeast(10)} m"
    m < 10_000f -> "%.1f km".format(java.util.Locale.US, m / 1000f)
    else -> "${Math.round(m / 1000f)} km"
}


