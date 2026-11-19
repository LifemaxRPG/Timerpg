package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * The whole town as street.json v2 carries it (its `graph` section: tools/town-graph.ts, docs/TOWN.md), with no drawing in
 * it. World axes, in street units: x east, z south (the town map's y), y up. A ROW is one side of one road between two
 * decision points, in its own side-scroller frame: x along it (0 at the node it starts from, [Row.length] at the one it
 * runs to), the buildings at +normal, the lanes of the pavement out from the building line towards the road. Bends (the
 * main street's steps) are corners of the row's polyline, not decision points.
 */

/** A point on the ground (world x, z), or a direction. */
data class TownPoint(val x: Float, val z: Float)

/** Where the player starts: on [row], [x] along it, in [lane]. */
data class Spawn(val row: String, val x: Float, val lane: Float)

class Neighbourhood(val id: String, val name: String, val palette: String, val yard: String, val shopColour: String)

enum class NodeKind { CROSS, TEE, BEND, END }

/** One road leaving a node: which road, its heading (unit, world x and z) and its mouth's half width. */
class Arm(val road: Int, val hx: Float, val hz: Float, val mouthHalf: Float)

class Node(val id: Int, val x: Float, val z: Float, val kind: NodeKind, val arms: List<Arm>) {
    fun arm(road: Int): Arm? = arms.firstOrNull { it.road == road }
}

class Road(val id: Int, val name: String, val main: Boolean, val a: Int, val b: Int, val pts: List<TownPoint>, val width: Float)

enum class PlacementKind { SHOP, HOME, LANDMARK }

/**
 * A building on a row: its front from [x0] to [x1] along the row, its front wall [setback] behind the building line (its
 * yard), [depth] deep; [group] the rigid run it moved with; [lot] its lot's (or landmark's) id.
 */
class Placement(
    val sprite: String,
    val x0: Float,
    val x1: Float,
    val setback: Float,
    val depth: Float,
    val group: Int,
    val neighbourhood: String,
    val kind: PlacementKind,
    val lot: String,
) {
    val mid: Float get() = (x0 + x1) / 2f
    val width: Float get() = x1 - x0
}

class RowFurniture(val kind: FurnitureKind, val x: Float, val look: Float)

enum class RowEnd { START, END }

enum class TransitionKind {
    /** Across the side street on the zebra to the same side's next row. */
    STRAIGHT,
    /** Round the corner building into the side street: a quarter turn (the up key). */
    TURN_IN,
    /** Over the road to the row facing this one. */
    CROSS,
}

/** A way off [from] at its [end]: to row [to]. */
data class Transition(val from: String, val end: RowEnd, val kind: TransitionKind, val to: String)

/** One end of a row: whether a road opens there (the buildings keep clear of it) and where each way off leads. */
class RowEndInfo(val mouth: Boolean, val straight: String?, val turnIn: String?, val cross: String?)

class Row(
    val id: String,
    val road: Int,
    /** 'L' or 'R'. */
    val side: Char,
    /** The node at x 0 and the one at [length]. */
    val from: Int,
    val to: Int,
    val origin: TownPoint,
    val along: TownPoint,
    val normal: TownPoint,
    val length: Float,
    /** The building line as a polyline (bends are its corners), from [origin] to the far end. */
    val pts: List<TownPoint>,
    val buildings: List<Placement>,
    val furniture: List<RowFurniture>,
    val start: RowEndInfo,
    val end: RowEndInfo,
) {
    /** Where each corner of [pts] is along the row (its x). */
    private val at: FloatArray = FloatArray(pts.size).also { a ->
        for (i in 1 until pts.size) a[i] = a[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].z - pts[i - 1].z)
    }

    /** +1 or -1: which way the normal turns from the direction of travel (the same for every segment of the row). */
    private val hand: Float = if (along.x * normal.z - along.z * normal.x >= 0f) 1f else -1f

    /** Each segment's direction and normal. */
    private val dirs: List<TownPoint> = (0 until pts.size - 1).map { i ->
        val dx = pts[i + 1].x - pts[i].x
        val dz = pts[i + 1].z - pts[i].z
        val l = hypot(dx, dz).coerceAtLeast(1e-6f)
        TownPoint(dx / l, dz / l)
    }.ifEmpty { listOf(along) }
    private val norms: List<TownPoint> = dirs.map { TownPoint(-hand * it.z, hand * it.x) }

    fun ends(e: RowEnd): RowEndInfo = if (e == RowEnd.START) start else end
    fun node(e: RowEnd): Int = if (e == RowEnd.START) from else to

    /** The ways off this row at both ends. */
    val transitions: List<Transition> = buildList {
        for (e in RowEnd.values()) {
            val i = ends(e)
            i.straight?.let { add(Transition(id, e, TransitionKind.STRAIGHT, it)) }
            i.turnIn?.let { add(Transition(id, e, TransitionKind.TURN_IN, it)) }
            i.cross?.let { add(Transition(id, e, TransitionKind.CROSS, it)) }
        }
    }

    private fun segment(x: Float): Int {
        var i = 0
        while (i < dirs.size - 1 && x > at[i + 1]) i++
        return i
    }

    /** The direction of travel (+x) at [x]: the segment's there. */
    fun alongAt(x: Float): TownPoint = dirs[segment(x)]

    /** The way the buildings face away from (+normal: into the buildings) at [x]. */
    fun normalAt(x: Float): TownPoint = norms[segment(x)]

    /**
     * The ground point [x] along the row, [lane] lanes out from the building line towards the road ([Crossroads.LANE]
     * units each). Along each segment it is exact; within [BLEND] of a bend the offset swings from one segment's normal
     * through the corner's mitre to the next one's, so a walk in any lane goes round the corner without a jump. Past
     * either end the row carries straight on.
     */
    fun pointAt(x: Float, lane: Float): TownPoint {
        val i = segment(x)
        val d = dirs[i]
        val p = pts[minOf(i, pts.size - 1)]
        val s = x - at[minOf(i, at.size - 1)]
        val bx = p.x + d.x * s
        val bz = p.z + d.z * s
        val off = -Crossroads.LANE * lane
        if (off == 0f) return TownPoint(bx, bz)
        var nx = norms[i].x
        var nz = norms[i].z
        val segLen = if (pts.size >= 2) at[i + 1] - at[i] else 0f
        val w = minOf(BLEND, segLen / 2f)
        if (w > 0f) {
            // near the corner ahead (not the row's own end) or the one behind (not its start)
            if (i < dirs.size - 1 && at[i + 1] - x < w) {
                val m = mitre(i)
                val t = 1f - (at[i + 1] - x) / w
                nx += (m.x - nx) * t; nz += (m.z - nz) * t
            } else if (i > 0 && x - at[i] < w) {
                val m = mitre(i - 1)
                val t = 1f - (x - at[i]) / w
                nx += (m.x - nx) * t; nz += (m.z - nz) * t
            }
        }
        return TownPoint(bx + nx * off, bz + nz * off)
    }

    /** The offset direction at the corner between segment [i] and the next: the mitre, at the lanes' distance from both. */
    private fun mitre(i: Int): TownPoint {
        val a = norms[i]
        val b = norms[i + 1]
        val c = 1f + a.x * b.x + a.z * b.z
        return if (c < 1e-3f) a else TownPoint((a.x + b.x) / c, (a.z + b.z) / c)
    }

    /** The bends' places along the row (x of each inner corner). */
    val bends: List<Float> get() = (1 until pts.size - 1).map { at[it] }

    /**
     * The row as the stage lays a street: each building's middle and front's left edge from the exported x0/x1, the
     * lamps and benches, and the row's whole extent (node to node, 0 to [length]). Mirrored ([mirrored]) it is the row
     * seen walked the other way: x' = [length] - x, so a left turn runs as a right one (see [JunctionFrame]).
     */
    fun layout(mirrored: Boolean = false): StreetLayout {
        val bs = buildings.sortedBy { if (mirrored) -it.x1 else it.x0 }
        val centers = bs.map { if (mirrored) length - it.mid else it.mid }
        val lefts = bs.map { if (mirrored) length - it.x1 else it.x0 }
        val furn = furniture.mapIndexed { i, f ->
            Furniture(f.kind, if (mirrored) length - f.x else f.x, (f.look * 1_000_003f).toInt() xor (i * 7919))
        }.sortedBy { it.x }
        return StreetLayout(centers, lefts, furn, 0f, length)
    }

    /** The building whose front covers [x], else the nearest by its front's middle (null on a row with none). */
    fun buildingAt(x: Float): Placement? =
        buildings.firstOrNull { x >= it.x0 && x <= it.x1 } ?: buildings.minByOrNull { abs(it.mid - x) }

    companion object {
        /** How far either side of a bend the lanes' offset swings round the corner (about a pavement's depth). */
        const val BLEND = 40f
    }
}

class Lot(
    val id: String,
    val sprite: String,
    val row: String,
    /** World rectangle: x0, z0, x1, z1. */
    val rect: FloatArray,
    val kind: String,
    val neighbourhood: String,
    val yard: String,
)

class Landmark(val id: String, val kind: String, val name: String, val sprite: String, val art: String, val row: String, val rect: FloatArray)

/** Something standing on the ground (a tree, a bench, a fountain) at world ([x], [z]); [look] picks its variant. */
class Prop(val kind: String, val x: Float, val z: Float, val look: Float)

class Bridge(val rect: FloatArray, val horizontal: Boolean)

class Town(
    val seed: String,
    val year: Int,
    /** Positions are tiles x [scale] (k x [unitPerTile]). */
    val k: Float,
    val unitPerTile: Float,
    val scale: Float,
    val spawn: Spawn,
    /** The civic avenue (where the player spawns): road id and name, if the export named one. */
    val civicRoad: Int?,
    val civicName: String?,
    val neighbourhoods: List<Neighbourhood>,
    val nodes: List<Node>,
    val roads: List<Road>,
    val rows: List<Row>,
    val landmarks: List<Landmark>,
    val lots: List<Lot>,
    val plaza: FloatArray?,
    val plazaProps: List<Prop>,
    /** The river as rectangles' (or polygons') corners. */
    val water: List<List<TownPoint>>,
    val bridges: List<Bridge>,
    val props: List<Prop>,
) {
    /**
     * Ground kept clear of the forest's trees and bushes (the railroad's bed: [RailScene.onBed]): set before the town is
     * drawn or its collision built; null keeps everything.
     */
    @Volatile var keepClear: ((Float, Float) -> Boolean)? = null

    private val rowIndex: Map<String, Row> = rows.associateBy { it.id }
    private val nodeIndex: Map<Int, Node> = nodes.associateBy { it.id }
    private val roadIndex: Map<Int, Road> = roads.associateBy { it.id }
    private val hoodIndex: Map<String, Neighbourhood> = neighbourhoods.associateBy { it.id }
    private val bySprite: Map<String, Pair<Row, Placement>> =
        rows.flatMap { r -> r.buildings.map { it.sprite to (r to it) } }.toMap()

    fun row(id: String): Row = rowIndex[id] ?: error("no row $id")
    fun rowOrNull(id: String?): Row? = id?.let { rowIndex[it] }
    fun node(id: Int): Node = nodeIndex[id] ?: error("no node $id")
    fun road(id: Int): Road = roadIndex[id] ?: error("no road $id")
    fun neighbourhood(id: String): Neighbourhood? = hoodIndex[id]

    /** The row and placement of the building drawn with [sprite] (b0, b1, ...). */
    fun placement(sprite: String): Pair<Row, Placement>? = bySprite[sprite]

    /** Every placed building. */
    val buildings: List<Placement> get() = rows.flatMap { it.buildings }

    /** The rows with an end at [node]. */
    fun rowsAt(node: Int): List<Row> = rows.filter { it.from == node || it.to == node }

    /** The row [kind] leads to from [row]'s [end], if there is one. */
    fun next(row: Row, end: RowEnd, kind: TransitionKind): Row? {
        val e = row.ends(end)
        return rowOrNull(
            when (kind) {
                TransitionKind.STRAIGHT -> e.straight
                TransitionKind.TURN_IN -> e.turnIn
                TransitionKind.CROSS -> e.cross
            },
        )
    }

    /** The row facing [row] across its road. */
    fun facing(row: Row): Row? = rowOrNull(row.start.cross ?: row.end.cross)

    /** The neighbourhood at [x] along [row]: its building's there, else the nearest lot's to that point. */
    fun neighbourhoodAt(row: Row, x: Float): Neighbourhood? {
        row.buildingAt(x)?.let { b -> neighbourhood(b.neighbourhood)?.let { return it } }
        val p = row.pointAt(x, 0f)
        return lots.minByOrNull { l ->
            val cx = (l.rect[0] + l.rect[2]) / 2f
            val cz = (l.rect[1] + l.rect[3]) / 2f
            hypot(cx - p.x, cz - p.z)
        }?.let { neighbourhood(it.neighbourhood) }
    }

    /** The row nearest world point ([wx], [wz]) and how far along it that point is (its x, within the row). */
    fun nearestRow(wx: Float, wz: Float): Pair<Row, Float>? {
        var best: Row? = null
        var bestX = 0f
        var bestD = Float.MAX_VALUE
        for (r in rows) {
            var acc = 0f
            for (i in 0 until r.pts.size - 1) {
                val a = r.pts[i]
                val b = r.pts[i + 1]
                val dx = b.x - a.x
                val dz = b.z - a.z
                val l2 = dx * dx + dz * dz
                val t = if (l2 > 0f) (((wx - a.x) * dx + (wz - a.z) * dz) / l2).coerceIn(0f, 1f) else 0f
                val d = hypot(a.x + dx * t - wx, a.z + dz * t - wz)
                if (d < bestD) { bestD = d; best = r; bestX = acc + t * sqrt(l2) }
                acc += sqrt(l2)
            }
        }
        return best?.let { it to bestX }
    }

    /** The rows reachable from [row] in at most [steps] transitions (itself included), nearest first. */
    fun rowsAround(row: Row, steps: Int): List<Row> {
        val seen = LinkedHashSet<String>()
        var frontier = listOf(row.id)
        seen += row.id
        repeat(steps) {
            val next = ArrayList<String>()
            for (id in frontier) for (t in row(id).transitions) if (seen.add(t.to)) next += t.to
            frontier = next
        }
        return seen.map { row(it) }
    }

    companion object {
        /** The town in a street.json: null for a v1 file (no `graph` section). */
        fun parse(streetJson: String): Town? = parse(JSONObject(streetJson))

        fun parse(root: JSONObject): Town? {
            val g = root.optJSONObject("graph") ?: return null
            val sp = g.getJSONObject("spawn")
            val civic = g.optJSONObject("civic")
            val plaza = g.optJSONObject("plaza")
            val water = g.optJSONObject("water")
            return Town(
                seed = g.getString("seed"),
                year = g.getInt("year"),
                k = g.getDouble("k").toFloat(),
                unitPerTile = g.getDouble("unitPerTile").toFloat(),
                scale = g.optDouble("scale", g.getDouble("k") * g.getDouble("unitPerTile")).toFloat(),
                spawn = Spawn(sp.getString("row"), sp.getDouble("x").toFloat(), sp.getDouble("lane").toFloat()),
                civicRoad = civic?.optInt("road"),
                civicName = civic?.optString("name"),
                neighbourhoods = g.getJSONArray("neighbourhoods").objects().map {
                    Neighbourhood(it.getString("id"), it.getString("name"), it.optString("palette"), it.optString("yard"), it.optString("shopColour"))
                },
                nodes = g.getJSONArray("nodes").objects().map { n ->
                    Node(
                        n.getInt("id"), n.f("x"), n.f("z"), NodeKind.valueOf(n.getString("kind").uppercase()),
                        n.getJSONArray("arms").objects().map { a ->
                            val h = a.getJSONArray("heading")
                            Arm(a.getInt("road"), h.f(0), h.f(1), a.f("mouthHalf"))
                        },
                    )
                },
                roads = g.getJSONArray("roads").objects().map {
                    Road(it.getInt("id"), it.getString("name"), it.optBoolean("main"), it.getInt("a"), it.getInt("b"), points(it.getJSONArray("pts")), it.f("width"))
                },
                rows = g.getJSONArray("rows").objects().map { row(it, cinemaSprites(g)) },
                landmarks = g.optJSONArray("landmarks")?.objects()?.map {
                    Landmark(it.getString("id"), it.getString("kind"), it.optString("name"), it.getString("sprite"), it.optString("art"), it.getString("row"), it.getJSONArray("rect").floats())
                } ?: emptyList(),
                lots = g.optJSONArray("lots")?.objects()?.map {
                    Lot(it.getString("id"), it.optString("sprite"), it.getString("row"), it.getJSONArray("rect").floats(), it.optString("kind"), it.optString("neighbourhood"), it.optString("yard"))
                } ?: emptyList(),
                plaza = plaza?.optJSONArray("rect")?.floats(),
                plazaProps = plaza?.optJSONArray("props")?.let { props(it) } ?: emptyList(),
                water = water?.optJSONArray("polys")?.let { ps -> (0 until ps.length()).map { points(ps.getJSONArray(it)) } } ?: emptyList(),
                bridges = g.optJSONArray("bridges")?.objects()?.map { Bridge(it.getJSONArray("rect").floats(), it.optBoolean("horizontal")) } ?: emptyList(),
                props = g.optJSONArray("props")?.let { props(it) } ?: emptyList(),
            )
        }

        /**
         * The sprites of the rows' buildings whose fronts are kept clear: cinemas (the landmark of kind cinema) and stores
         * ([StoreLots]: their porches, glass fronts, car parks' edges and road signs are their own).
         */
        private fun cinemaSprites(g: JSONObject): Set<String> =
            (g.optJSONArray("landmarks")?.objects()?.filter { it.optString("kind") == "cinema" }?.map { it.optString("sprite") }?.toSet() ?: emptySet()) +
                StoreLots.spritesOf(g)

        /**
         * [list] kept off a cinema's or a store's front: the lamps and benches standing along it are moved to its two
         * ends (a lamp each end, if none stands there), so nothing stands before its doors, ticket booth or poster cases,
         * a store's porch, glass or the mouth of its car park.
         */
        private fun clearOfCinemas(list: List<RowFurniture>, buildings: List<Triple<String, Float, Float>>, cinemas: Set<String>): List<RowFurniture> {
            val fronts = buildings.filter { it.first in cinemas }.map { it.second to it.third }
            if (fronts.isEmpty()) return list
            val out = list.filter { fu -> fronts.none { b -> fu.x > b.first + 24f && fu.x < b.second - 24f } }.toMutableList()
            for (b in fronts) for (x in listOf(b.first + 12f, b.second - 12f))
                if (out.none { it.kind == FurnitureKind.LAMP && kotlin.math.abs(it.x - x) < 60f }) out += RowFurniture(FurnitureKind.LAMP, x, 0f)
            return out.sortedBy { it.x }
        }

        private fun row(r: JSONObject, cinemas: Set<String> = emptySet()): Row {
            val ends = r.getJSONObject("ends")
            return Row(
                id = r.getString("id"),
                road = r.getInt("road"),
                side = r.getString("side")[0],
                from = r.getInt("from"),
                to = r.getInt("to"),
                origin = r.getJSONArray("origin").let { TownPoint(it.f(0), it.f(1)) },
                along = r.getJSONArray("along").let { TownPoint(it.f(0), it.f(1)) },
                normal = r.getJSONArray("normal").let { TownPoint(it.f(0), it.f(1)) },
                length = r.f("length"),
                pts = r.optJSONArray("pts")?.let { points(it) }
                    ?: r.getJSONArray("origin").let { o ->
                        val a = r.getJSONArray("along")
                        val l = r.f("length")
                        listOf(TownPoint(o.f(0), o.f(1)), TownPoint(o.f(0) + a.f(0) * l, o.f(1) + a.f(1) * l))
                    },
                buildings = r.getJSONArray("buildings").objects().map { b ->
                    Placement(
                        b.getString("sprite"), b.f("x0"), b.f("x1"), b.f("setback"), b.f("depth"), b.optInt("group"),
                        b.optString("neighbourhood"), PlacementKind.valueOf(b.getString("kind").uppercase()), b.optString("lot"),
                    )
                },
                furniture = clearOfCinemas(r.optJSONArray("furniture")?.objects()?.map {
                    RowFurniture(FurnitureKind.valueOf(it.getString("kind").uppercase()), it.f("x"), it.optDouble("look", 0.0).toFloat())
                } ?: emptyList(), r.getJSONArray("buildings").objects().map { b -> Triple(b.getString("sprite"), b.f("x0"), b.f("x1")) }, cinemas),
                start = end(ends.getJSONObject("start")),
                end = end(ends.getJSONObject("end")),
            )
        }

        private fun end(e: JSONObject) = RowEndInfo(
            e.optBoolean("mouth"),
            e.optString("straight").ifEmpty { null },
            e.optString("turnIn").ifEmpty { null },
            e.optString("cross").ifEmpty { null },
        )

        private fun props(a: JSONArray): List<Prop> =
            a.objects().map { Prop(it.getString("kind"), it.f("x"), it.f("z"), it.optDouble("look", 0.0).toFloat()) }

        private fun points(a: JSONArray): List<TownPoint> = (0 until a.length()).map { a.getJSONArray(it).let { p -> TownPoint(p.f(0), p.f(1)) } }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.floats(): FloatArray = FloatArray(length()) { f(it) }
        private fun JSONArray.f(i: Int): Float = getDouble(i).toFloat()
        private fun JSONObject.f(k: String): Float = getDouble(k).toFloat()
    }
}
