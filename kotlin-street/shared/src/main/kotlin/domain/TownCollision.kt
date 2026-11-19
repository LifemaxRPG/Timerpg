package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * What a walker in the open world cannot walk through (docs/TOWN.md, "Open world"): a static map built once from the
 * town's data (street.json v2: rows, yards, props, water and bridges, the cinema's site; buildings/models.json for the
 * modelled buildings' parts) and nothing drawn. Every solid thing is one of two shapes on the ground (world x east, z
 * south, street units): an oriented box or a circle. They are kept in flat arrays and a uniform grid of cells, so a query
 * looks at the few shapes near it and allocates nothing.
 *
 * What blocks, by kind (the rest is walkable ground: streets, pavements, lawns, paths, drives, patios, decks, flower
 * beds, sandpits, the plaza, back lanes, bridges' decks):
 *  - buildings: a box or mobile home is its slot's rigid box (TownWalk's footprint, the yards' `house`); a modelled one
 *    (villas, flats, landmarks) its parts that stand on the ground below head height, so a porch's posts, a fire
 *    station's apparatus bay or a school's front block are where they are drawn; the cinema its body, drum, stair tower,
 *    canopy posts and its site's walls and rails ([CinemaSiteShape]); a store's plot ([StoreLots]) is left to what its
 *    store, as built, stands there ([setExtra]: the building, posts, fences, corrals, the garden centre, parked cars);
 *  - yard edges: fences (front, side, back, post and rail), hedges and low walls; gates and side gates are gaps in them
 *    (the export splits the fences round the gates);
 *  - yard objects: sheds, garages, greenhouses, play sets, pools, ponds, trampolines, tables, benches, grills, bins,
 *    raised vegetable beds, compost, log piles... as their rectangles; trees and fruit trees by their trunks; bushes as
 *    small circles; carports and gazebos by their corner posts; washing lines by their posts;
 *  - the street: the rows' lamps, benches and trees (as the town ground stands them), the props by [PropRules]
 *    (hydrants, mailboxes, bins, signs, benches...), the plaza's fountain, benches, trees and planters, the forest's trees
 *    (trunks: the forest is walkable between them) and bushes;
 *  - water: the river, less the bridges over it; each bridge's parapets;
 *  - the world's edge: a wall round the town's ground well past the forest.
 */

/** Which kind of thing a shape is (for tests, debugging and the HUD; the walk treats them all as solid). */
enum class SolidKind { BUILDING, FENCE, HEDGE, WALL, YARD_OBJECT, TREE, PROP, WATER, PARAPET, EDGE }

/**
 * The town's solid shapes and the grid over them. Build with [TownCollision.build]. Queries ([blocked], [clearance],
 * [move], [resolve]) allocate nothing and are safe to call from one thread at a time (they share scratch).
 */
class TownCollision private constructor(
    /** Per shape: 0 a box, 1 a circle. */
    private val type: ByteArray,
    /** Per shape, 6 floats: a box's centre x, z, its unit axis u (x, z), its half extents along u and along v (u turned a quarter); a circle's centre x, z and radius. */
    private val data: FloatArray,
    private val kinds: Array<SolidKind>,
    private val owners: Array<String?>,
    val minX: Float, val minZ: Float,
    private val cell: Float,
    val cols: Int, val rows: Int,
    private val cellStart: IntArray,
    private val cellItems: IntArray,
) {
    /** How many shapes (those the map was built with; [extraSize] more were added since). */
    val size: Int get() = type.size

    /** The shapes added since by key ([setExtra]: the stores' sites and their parked cars), as one map of their own. */
    @Volatile private var extra: TownCollision? = null
    /** The overlay this query reads (taken once at its start, so a swap by another thread does not mix two). */
    private var q: TownCollision? = null
    private val extraSets = LinkedHashMap<String, Triple<FloatArray, SolidKind, String?>>()
    /** How many shapes were added since the map was built ([setExtra]); their indices follow [size]. */
    val extraSize: Int get() = extra?.size ?: 0

    /**
     * Sets the solid shapes kept under [key] (replacing what it had; null or empty removes them): oriented boxes, six
     * floats each as the constructor's (centre x, z, unit axis x, z, half extents along it and across), of [kind], owned
     * by [owner]. For what is built after the map: a store's site once its model is up, its parked cars each hour. Safe
     * to call from another thread than the queries' (the overlay is rebuilt and swapped in whole).
     */
    fun setExtra(key: String, boxes: FloatArray?, kind: SolidKind = SolidKind.BUILDING, owner: String? = null) {
        synchronized(extraSets) {
            if (boxes == null || boxes.isEmpty()) { if (extraSets.remove(key) == null) return } else extraSets[key] = Triple(boxes, kind, owner)
            val b = Builder()
            for ((bx, k, o) in extraSets.values) {
                var i = 0
                while (i + 5 < bx.size) { b.box(bx[i], bx[i + 1], bx[i + 2], bx[i + 3], bx[i + 4], bx[i + 5], k, o); i += 6 }
            }
            extra = if (extraSets.isEmpty()) null else b.finishGrid(minX, minZ, cols, rows)
        }
    }

    /** The doors added since by key ([setExtraDoors]: the stores' entrances), read by [TownDoors]. */
    @Volatile var extraDoors: Array<TownDoor> = emptyArray(); private set
    private val doorSets = LinkedHashMap<String, List<TownDoor>>()

    /** Sets the doors kept under [key] (null or empty removes them). */
    fun setExtraDoors(key: String, doors: List<TownDoor>?) {
        synchronized(doorSets) {
            if (doors.isNullOrEmpty()) doorSets.remove(key) else doorSets[key] = doors
            extraDoors = doorSets.values.flatten().toTypedArray()
        }
    }

    /** The cinema's site the map was built with, if the town has one (for its door). */
    var cinema: CinemaSiteShape? = null
        internal set
    /** The sprites of the stores' plots the map was built with (left open: their stores add their shapes, [setExtra]). */
    var stores: Set<String> = emptySet()
        internal set

    /** The world's edges (inside them the walk may go): min x, min z, max x, max z. */
    val maxX: Float get() = minX + cols * cell
    val maxZ: Float get() = minZ + rows * cell

    fun kind(i: Int): SolidKind = if (i >= size) extra!!.kinds[i - size] else kinds[i]
    /** What the shape belongs to (a building's or a yard's sprite, a row, "river"...), if anything. */
    fun owner(i: Int): String? = if (i >= size) extra!!.owners[i - size] else owners[i]
    fun isCircle(i: Int): Boolean = if (i >= size) extra!!.type[i - size].toInt() == 1 else type[i].toInt() == 1
    /** The shape's six numbers (see the constructor), into [out]. */
    fun shape(i: Int, out: FloatArray): FloatArray {
        if (i >= size) System.arraycopy(extra!!.data, (i - size) * 6, out, 0, 6) else System.arraycopy(data, i * 6, out, 0, 6)
        return out
    }

    // a per-query stamp, so a shape lying in several cells is looked at once a query
    private val seen = IntArray(type.size)
    private var stamp = 0

    private inline fun forNear(x0: Float, z0: Float, x1: Float, z1: Float, f: (Int) -> Boolean) {
        val c0 = ((x0 - minX) / cell).toInt().coerceIn(0, cols - 1)
        val c1 = ((x1 - minX) / cell).toInt().coerceIn(0, cols - 1)
        val r0 = ((z0 - minZ) / cell).toInt().coerceIn(0, rows - 1)
        val r1 = ((z1 - minZ) / cell).toInt().coerceIn(0, rows - 1)
        stamp++
        if (stamp == Int.MAX_VALUE) { seen.fill(0); stamp = 1 }
        for (r in r0..r1) for (c in c0..c1) {
            val k = r * cols + c
            for (j in cellStart[k] until cellStart[k + 1]) {
                val i = cellItems[j]
                if (seen[i] == stamp) continue
                seen[i] = stamp
                if (f(i)) return
            }
        }
        // the shapes added since (the same grid)
        val ex = extra
        q = ex
        if (ex == null) return
        ex.stamp++
        if (ex.stamp == Int.MAX_VALUE) { ex.seen.fill(0); ex.stamp = 1 }
        val n = size
        for (r in r0..r1) for (c in c0..c1) {
            val k = r * cols + c
            for (j in ex.cellStart[k] until ex.cellStart[k + 1]) {
                val i = ex.cellItems[j]
                if (ex.seen[i] == ex.stamp) continue
                ex.seen[i] = ex.stamp
                if (f(n + i)) return
            }
        }
    }

    /**
     * The signed distance from ([x], [z]) to shape [i]'s edge (negative inside), and in [push] (if given) the unit
     * direction out of it from there.
     */
    private fun distance(i: Int, x: Float, z: Float, push: FloatArray?): Float {
        if (i >= size) return q!!.distance(i - size, x, z, push)
        val o = i * 6
        if (type[i].toInt() == 1) {
            val dx = x - data[o]; val dz = z - data[o + 1]
            val l = sqrt(dx * dx + dz * dz)
            if (push != null) { if (l > 1e-5f) { push[0] = dx / l; push[1] = dz / l } else { push[0] = 1f; push[1] = 0f } }
            return l - data[o + 2]
        }
        val ux = data[o + 2]; val uz = data[o + 3]
        val dx = x - data[o]; val dz = z - data[o + 1]
        val lu = dx * ux + dz * uz
        val lv = -dx * uz + dz * ux
        val hu = data[o + 4]; val hv = data[o + 5]
        val eu = abs(lu) - hu; val ev = abs(lv) - hv
        if (eu <= 0f && ev <= 0f) {
            // inside: out through the nearer side
            if (push != null) {
                if (eu > ev) { val s = if (lu >= 0f) 1f else -1f; push[0] = ux * s; push[1] = uz * s }
                else { val s = if (lv >= 0f) 1f else -1f; push[0] = -uz * s; push[1] = ux * s }
            }
            return max(eu, ev)
        }
        val qu = max(eu, 0f) * (if (lu >= 0f) 1f else -1f)
        val qv = max(ev, 0f) * (if (lv >= 0f) 1f else -1f)
        val l = sqrt(qu * qu + qv * qv)
        if (push != null) {
            val wx = qu * ux - qv * uz; val wz = qu * uz + qv * ux
            push[0] = wx / l; push[1] = wz / l
        }
        return l
    }

    /** True if a circle of [radius] at ([x], [z]) overlaps anything solid (or leaves the world). */
    fun blocked(x: Float, z: Float, radius: Float): Boolean {
        if (x - radius < minX || z - radius < minZ || x + radius > maxX || z + radius > maxZ) return true
        var hit = false
        val r = radius - EPS
        forNear(x - radius, z - radius, x + radius, z + radius) { i -> if (distance(i, x, z, null) < r) { hit = true; true } else false }
        return hit
    }

    /** How far ([x], [z]) is from the nearest solid shape within [reach] (negative: inside one); [reach] if none is that near. */
    fun clearance(x: Float, z: Float, reach: Float = 64f): Float {
        var best = reach
        forNear(x - reach, z - reach, x + reach, z + reach) { i -> best = min(best, distance(i, x, z, null)); false }
        best = min(best, min(min(x - minX, maxX - x), min(z - minZ, maxZ - z)))
        return best
    }

    /** The shape nearest ([x], [z]) within [reach], or -1. */
    fun nearest(x: Float, z: Float, reach: Float = 64f): Int {
        var best = reach
        var at = -1
        forNear(x - reach, z - reach, x + reach, z + reach) { i -> val d = distance(i, x, z, null); if (d < best) { best = d; at = i }; false }
        return at
    }

    private val push = FloatArray(2)
    private val scratch = FloatArray(2)

    /**
     * Pushes a circle of [radius] at ([x], [z]) out of whatever it overlaps (a few rounds, for corners between shapes);
     * the place into [out] (x, z). Returns true if it ends clear.
     */
    fun resolve(x: Float, z: Float, radius: Float, out: FloatArray): Boolean {
        var px = x; var pz = z
        for (round in 0 until 6) {
            var moved = false
            forNear(px - radius, pz - radius, px + radius, pz + radius) { i ->
                val d = distance(i, px, pz, push)
                if (d < radius - EPS) {
                    val by = radius - d
                    px += push[0] * by; pz += push[1] * by
                    moved = true
                }
                false
            }
            // the world's edge
            px = px.coerceIn(minX + radius, maxX - radius); pz = pz.coerceIn(minZ + radius, maxZ - radius)
            if (!moved) break
        }
        out[0] = px; out[1] = pz
        return !blocked(px, pz, radius)
    }

    /**
     * Moves a circle of [radius] from ([x], [z]) by ([dx], [dz]), sliding along what it meets (no sticking), in steps of
     * at most half its radius so nothing thin is passed through; where it ends into [out] (x, z). A walker that starts
     * clear never ends inside anything. Returns the share of the asked move that was really made along it (0..1).
     */
    fun move(x: Float, z: Float, dx: Float, dz: Float, radius: Float, out: FloatArray): Float {
        val len = hypot(dx, dz)
        if (len < 1e-6f) { out[0] = x; out[1] = z; return 1f }
        val n = max(1, ceil(len / (radius * .5f)).toInt())
        val sx = dx / n; val sz = dz / n
        var cx = x; var cz = z
        val startClear = !blocked(x, z, radius)
        for (k in 0 until n) {
            val ok = resolve(cx + sx, cz + sz, radius, scratch)
            var nx = scratch[0]; var nz = scratch[1]
            if (startClear && !ok) {
                // caught in a corner the push could not settle: try each axis alone, else stay put
                if (resolve(cx + sx, cz, radius, scratch)) { nx = scratch[0]; nz = scratch[1] }
                else if (resolve(cx, cz + sz, radius, scratch)) { nx = scratch[0]; nz = scratch[1] }
                else { nx = cx; nz = cz }
            }
            // never pushed back further than the step itself (a push out of a corner can throw a circle sideways)
            val jx = nx - cx; val jz = nz - cz
            val jl = hypot(jx, jz)
            val most = hypot(sx, sz) * 1.5f + .01f
            if (jl > most && startClear) {
                val f = most / jl
                val tx = cx + jx * f; val tz = cz + jz * f
                if (!blocked(tx, tz, radius)) { nx = tx; nz = tz } else { nx = cx; nz = cz }
            }
            cx = nx; cz = nz
        }
        out[0] = cx; out[1] = cz
        val along = ((cx - x) * dx + (cz - z) * dz) / (len * len)
        return along.coerceIn(0f, 1f)
    }

    /**
     * The clear spot nearest ([x], [z]) for a circle of [radius] (itself if it is clear), searched outward in rings of
     * [step] up to [reach]; into [out]. Returns false if none was found (out is then the start).
     */
    fun nearestClear(x: Float, z: Float, radius: Float, out: FloatArray, step: Float = 4f, reach: Float = 2000f): Boolean {
        if (!blocked(x, z, radius)) { out[0] = x; out[1] = z; return true }
        var ring = 1
        while (ring * step <= reach) {
            val r = ring * step
            val n = max(8, (2f * PI.toFloat() * r / step).toInt())
            var best = Float.MAX_VALUE
            for (k in 0 until n) {
                val a = 2f * PI.toFloat() * k / n
                val px = x + cos(a) * r; val pz = z + sin(a) * r
                if (!blocked(px, pz, radius)) { out[0] = px; out[1] = pz; best = 0f; break }
            }
            if (best == 0f) return true
            ring++
        }
        out[0] = x; out[1] = z
        return false
    }

    companion object {
        private const val EPS = .01f
        /** The grid's cell (street units). */
        const val CELL = 64f
        /** How far past the town's outermost data the world's edge stands (past the fields' first trees). */
        const val EDGE_MARGIN = 1200f

        /** The town's collision from its street.json text (v2) and its buildings' models.json text (or null). */
        fun fromStreetJson(streetJson: String, modelsJson: String?): TownCollision? {
            val root = org.json.JSONObject(streetJson)
            val town = Town.parse(root) ?: return null
            return build(town, TownYards.parse(root), ModelShape.parseAll(modelsJson), CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet())
        }

        /**
         * Builds the map: [town]'s rows (their buildings, furniture, street props), [yards] (edges and objects), the
         * forest and plaza props, the river less its bridges and the bridges' parapets, [models] (by sprite) for the
         * modelled buildings' parts, [cinema] for the theater's site, if the town has one; [stores] the sprites of the
         * stores' plots ([StoreLots]), left open here: their stores add what stands on them when built ([setExtra]).
         */
        fun build(town: Town, yards: TownYards, models: Map<String, ModelShape> = emptyMap(), cinema: CinemaSiteShape? = null,
                  stores: Set<String> = emptySet()): TownCollision {
            val b = Builder()
            buildings(b, town, models, cinema, stores)
            for (y in yards.yards) yardItems(b, y.items, y.sprite)
            for (l in yards.lanes) yardItems(b, l.items, l.id)
            furniture(b, town)
            streetProps(b, town)
            greenery(b, town)
            water(b, town)
            return b.finish(town).also { it.cinema = cinema; it.stores = stores }
        }

        // ------------------------------------------------------------------ buildings

        private fun buildings(b: Builder, town: Town, models: Map<String, ModelShape>, cinema: CinemaSiteShape?, stores: Set<String>) {
            val kk = BuildingSpec.W / 224f
            for (row in town.rows) for (p in row.buildings) {
                val place = TownPlace.of(row, p)
                if (cinema != null && p.sprite == cinema.sprite) { cinema.addTo(b, place, p); continue }
                if (p.sprite in stores || p.flat) continue
                val m = models[p.sprite]
                val solid = m?.parts?.filter { standsOnGround(it) }.orEmpty()
                if (m == null || solid.isEmpty()) {
                    // the slot's rigid box: its front wall at the setback, its width along the row, its depth back
                    b.localBox(place, -p.width / 2f, p.setback, p.width / 2f, p.setback + p.depth, SolidKind.BUILDING, p.sprite)
                    continue
                }
                // the model's parts in its own pixels (x across its front picture, d back from its front line) to the
                // slot's local frame, as BuildingMeshes stands it: local x = -(left + x k), local z = setback + d k
                val left = -BuildingSpec.W / 2f + m.frontX * kk
                for (part in solid) {
                    val a = -(left + part.x0 * kk); val c = -(left + part.x1 * kk)
                    val z0 = p.setback + part.d0 * kk; val z1 = p.setback + part.d1 * kk
                    if (part.type == ModelPart.CYL && part.arc != null) {
                        val arc = part.arc
                        val cx = -(left + arc[0] * kk); val cz = p.setback + arc[1] * kk
                        val w = place.world(cx, cz)
                        b.circle(w.x, w.z, max(arc[2], arc[3]) * kk, SolidKind.BUILDING, p.sprite)
                    } else if (part.type == ModelPart.WALL) {
                        // a wall turned off the street's line: a thin box from one plan point to the other
                        val pa = place.world(a, z0); val pc = place.world(c, z1)
                        b.segment(pa.x, pa.z, pc.x, pc.z, 2f, SolidKind.BUILDING, p.sprite)
                    } else b.localBox(place, min(a, c), min(z0, z1), max(a, c), max(z0, z1), SolidKind.BUILDING, p.sprite)
                }
            }
        }

        /**
         * A model's part that a walker meets: something standing on the ground (its foot below head height) and taller
         * than a step, a box, prism, rounded wall or wall (roofs, decals, canopies and railings up high are overhead).
         */
        private fun standsOnGround(p: ModelPart): Boolean {
            val kinds = p.type == ModelPart.BOX || p.type == ModelPart.PRISM || p.type == ModelPart.CYL || p.type == ModelPart.WALL || p.type == ModelPart.RAIL
            return kinds && p.y0 < HEAD_PX && p.y1 > STEP_PX && (p.type == ModelPart.WALL || p.x1 > p.x0)
        }

        /** Head height and a step's in the models' pixels (the 36-unit figure: 44 pixels; a step or a porch's deck: up to 14). */
        private const val HEAD_PX = 44f
        private const val STEP_PX = 14f

        // ------------------------------------------------------------------ yards

        /** The yard's things that block, by kind: edges as their rectangles, objects as their rectangles, trunks or posts. */
        private fun yardItems(b: Builder, items: List<TownYardItem>, owner: String) {
            for (it in items) {
                val r = it.rect
                val w = r[2] - r[0]; val d = r[3] - r[1]
                val cx = (r[0] + r[2]) / 2f; val cz = (r[1] + r[3]) / 2f
                when (it.kind) {
                    "backFence", "frontFence", "sideFence", "postRail" -> b.rect(r, SolidKind.FENCE, owner)
                    "hedge" -> b.rect(r, SolidKind.HEDGE, owner)
                    "gate", "sideGate" -> gate(b, it, owner)
                    "lowWall" -> b.rect(r, SolidKind.WALL, owner)
                    "tree", "fruitTree" -> b.circle(cx, cz, TRUNK, SolidKind.TREE, owner)
                    "bush" -> b.circle(cx, cz, min(w, d) * .35f, SolidKind.YARD_OBJECT, owner)
                    "birdbath", "firePit" -> b.circle(cx, cz, min(w, d) / 2f, SolidKind.YARD_OBJECT, owner)
                    "chair" -> b.circle(cx, cz, min(6f, min(w, d) / 2f), SolidKind.YARD_OBJECT, owner)
                    "mailbox" -> b.circle(cx, cz, 4f, SolidKind.YARD_OBJECT, owner)
                    "carport", "gazebo" -> {
                        val i = 4f
                        for (px in floatArrayOf(r[0] + i, r[2] - i)) for (pz in floatArrayOf(r[1] + i, r[3] - i)) b.circle(px, pz, POST, SolidKind.YARD_OBJECT, owner)
                    }
                    "washingLine" -> {
                        if (abs(w - d) < 8f) b.circle(cx, cz, POST, SolidKind.YARD_OBJECT, owner)
                        else if (w > d) { b.circle(r[0] + 3f, cz, POST, SolidKind.YARD_OBJECT, owner); b.circle(r[2] - 3f, cz, POST, SolidKind.YARD_OBJECT, owner) }
                        else { b.circle(cx, r[1] + 3f, POST, SolidKind.YARD_OBJECT, owner); b.circle(cx, r[3] - 3f, POST, SolidKind.YARD_OBJECT, owner) }
                    }
                    "bench", "bikeShed", "bins", "compost", "doghouse", "garage", "greenhouse", "grill", "hammock", "logPile",
                    "pallets", "planter", "playSet", "pond", "pool", "shed", "swing", "table", "trampoline", "vegBed" ->
                        b.rect(r, SolidKind.YARD_OBJECT, owner)
                    // walkable: lawn, meadow, gravel, paths, drives, kerb cuts, patios, decks, pads, the pool's deck,
                    // flower beds (low), sandpits, gates and side gates (the gaps in the fences)
                    else -> {}
                }
            }
        }

        private const val TRUNK = 6f
        private const val POST = 3f

        /**
         * A gate (a gap in its fence, drawn open: YardKit's gates swing [GATE_OPEN] degrees into the yard on their hinge
         * posts): its posts at either end are solid (when the gap leaves a walker room between them). The frame is the
         * yard kit's for an edge: along its long side, out the way it faces.
         */
        private fun gate(b: Builder, it: TownYardItem, owner: String) {
            val r = it.rect
            val rw = r[2] - r[0]; val rd = r[3] - r[1]
            val cx = (r[0] + r[2]) / 2f; val cz = (r[1] + r[3]) / 2f
            val fx: Float; val fz: Float
            if (rw >= rd) { fx = 0f; fz = if (it.faceZ < 0f) -1f else 1f } else { fx = if (it.faceX < 0f) -1f else 1f; fz = 0f }
            val ax = -fz; val az = fx
            val w = max(rw, rd); val hw = w / 2f
            fun wx(lx: Float, lz: Float) = cx + ax * lx + fx * lz
            fun wz(lx: Float, lz: Float) = cz + az * lx + fz * lz
            val inner = w - 2f * (GATE_POST_OUT + GATE_POST)
            if (inner < GATE_ROOM) return
            for (sx in floatArrayOf(-1f, 1f)) b.circle(wx(sx * (hw - GATE_POST_OUT), 0f), wz(sx * (hw - GATE_POST_OUT), 0f), GATE_POST, SolidKind.FENCE, owner)
            // (the open leaves lie back along the gap's edges, flat to the fence or the house: not solid, so they make no
            // wedge to get caught in)
        }

        /** Where a gate's posts stand in from its ends, how thick they are, and the room a walker needs between them. */
        private const val GATE_POST_OUT = 1.5f
        private const val GATE_POST = 1.6f
        private const val GATE_ROOM = 13f
        /** How far the gates stand open (degrees): YardKit.GATE_OPEN. */
        const val GATE_OPEN = 90f

        // ------------------------------------------------------------------ the street

        /** The rows' lamps, benches and trees where the town's ground stands them (TownGround.furniture). */
        private fun furniture(b: Builder, town: Town) {
            val half = Crossroads.MOUTH / 2f
            val seen = HashSet<Long>()
            for (row in town.rows) for (f in row.furniture) {
                val lo = if (row.start.mouth) half - 20f else 10f
                val hi = if (row.end.mouth) row.length - half + 20f else row.length - 10f
                if (hi <= lo) continue
                val x = f.x.coerceIn(lo, hi)
                val out = when (f.kind) { FurnitureKind.LAMP -> Crossroads.PAVE - 3f; FurnitureKind.BENCH -> 9f; FurnitureKind.TREE -> Crossroads.PAVE - 9f }
                val p = row.pointAt(x, out / Crossroads.LANE)
                val k = ((p.x / 12f).roundToInt().toLong() shl 32) or ((p.z / 12f).roundToInt().toLong() and 0xffffffffL)
                if (!seen.add(k * 4 + f.kind.ordinal)) continue
                val n = row.normalAt(x)
                when (f.kind) {
                    FurnitureKind.LAMP -> b.circle(p.x, p.z, 3.5f, SolidKind.PROP, row.id)
                    FurnitureKind.TREE -> b.circle(p.x, p.z, TRUNK, SolidKind.TREE, row.id)
                    FurnitureKind.BENCH -> b.box(p.x, p.z, -n.z, n.x, PropKind.BENCH.w / 2f, PropKind.BENCH.d / 2f, SolidKind.PROP, row.id)
                }
            }
        }

        /** The street props by [PropRules] along every row; one street name sign a crossing (as the town draws them). */
        private fun streetProps(b: Builder, town: Town) {
            val signs = ArrayList<TownPoint>()
            for (row in town.rows) {
                val spots = runCatching { PropRules.forRow(town, row) }.getOrElse { emptyList() }
                for (sp in spots) {
                    val w = PropRules.world(row, sp)
                    if (sp.kind == PropKind.STREET_SIGN) {
                        if (signs.any { hypot(it.x - w[0], it.z - w[1]) < 260f }) continue
                        signs += TownPoint(w[0], w[1])
                    }
                    val fp = sp.footprint()
                    val hw = (fp[1] - fp[0]) / 2f; val hd = (fp[3] - fp[2]) / 2f
                    val c = row.pointAt((fp[0] + fp[1]) / 2f, (fp[2] + fp[3]) / 2f / Crossroads.LANE)
                    val a = row.alongAt(sp.x)
                    if (max(hw, hd) < 4.5f) b.circle(c.x, c.z, max(hw, hd), SolidKind.PROP, row.id)
                    else b.box(c.x, c.z, a.x, a.z, hw, hd, SolidKind.PROP, row.id)
                }
            }
        }

        /** The forest's and the plaza's trees (trunks), bushes, planters (the flower bushes), benches and fountain. */
        private fun greenery(b: Builder, town: Town) {
            val clear = town.keepClear
            for (p in town.props) if (clear == null || !clear(p.x, p.z)) prop(b, p, edge = true)
            for (p in town.plazaProps) prop(b, p, edge = false)
        }

        private fun prop(b: Builder, p: Prop, edge: Boolean) {
            // the forest's trees stand shaken off the map's grid (as TownGround stands them), bigger ones among them
            val jx = if (edge) ((p.look * 3571f) % 1f - .5f) * 30f else 0f
            val jz = if (edge) ((p.look * 2287f) % 1f - .5f) * 30f else 0f
            when {
                p.kind == "tree" -> {
                    val scale = if (edge) (if (((p.look * 7919f).toInt() and 1) == 0) 1.25f else 1.6f) else 1f
                    b.circle(p.x + jx, p.z + jz, TRUNK * scale, SolidKind.TREE, "forest")
                }
                p.kind == "bush" -> b.circle(p.x + jx, p.z + jz, 8f, SolidKind.PROP, "forest")
                p.kind == "flowerBush" -> {
                    val q = ((p.look * 7919f).toInt() and 1) != 0
                    val hw = PropKind.PLANTER.w / 2f; val hd = PropKind.PLANTER.d / 2f
                    if (q) b.box(p.x, p.z, 0f, 1f, hw, hd, SolidKind.PROP, "plaza") else b.box(p.x, p.z, 1f, 0f, hw, hd, SolidKind.PROP, "plaza")
                }
                p.kind == "bench" -> b.box(p.x, p.z, 1f, 0f, PropKind.BENCH.w / 2f, PropKind.BENCH.d / 2f, SolidKind.PROP, "plaza")
                p.kind.startsWith("fountain") -> b.circle(p.x, p.z, 30f, SolidKind.PROP, "plaza")
                // flowers are flat
                else -> {}
            }
        }

        // ------------------------------------------------------------------ water

        /** The river's rectangles less the bridges over them, and each bridge's two parapets (as TownGround builds them). */
        private fun water(b: Builder, town: Town) {
            var pieces = town.water.map { poly ->
                floatArrayOf(poly.minOf { it.x }, poly.minOf { it.z }, poly.maxOf { it.x }, poly.maxOf { it.z })
            }
            for (br in town.bridges) pieces = pieces.flatMap { subtract(it, br.rect) }
            for (r in pieces) if (r[2] - r[0] > .5f && r[3] - r[1] > .5f) b.rect(r, SolidKind.WATER, "river")
            for (br in town.bridges) {
                val r = br.rect
                val t = 3f
                if (br.horizontal) {
                    b.rect(floatArrayOf(r[0], r[1], r[2], r[1] + t), SolidKind.PARAPET, "bridge")
                    b.rect(floatArrayOf(r[0], r[3] - t, r[2], r[3]), SolidKind.PARAPET, "bridge")
                } else {
                    b.rect(floatArrayOf(r[0], r[1], r[0] + t, r[3]), SolidKind.PARAPET, "bridge")
                    b.rect(floatArrayOf(r[2] - t, r[1], r[2], r[3]), SolidKind.PARAPET, "bridge")
                }
            }
        }

        /** Rectangle [a] less rectangle [c] (both x0, z0, x1, z1): up to four rectangles. */
        internal fun subtract(a: FloatArray, c: FloatArray): List<FloatArray> {
            val ix0 = max(a[0], c[0]); val iz0 = max(a[1], c[1]); val ix1 = min(a[2], c[2]); val iz1 = min(a[3], c[3])
            if (ix0 >= ix1 || iz0 >= iz1) return listOf(a)
            val out = ArrayList<FloatArray>(4)
            if (a[1] < iz0) out += floatArrayOf(a[0], a[1], a[2], iz0)
            if (iz1 < a[3]) out += floatArrayOf(a[0], iz1, a[2], a[3])
            if (a[0] < ix0) out += floatArrayOf(a[0], iz0, ix0, iz1)
            if (ix1 < a[2]) out += floatArrayOf(ix1, iz0, a[2], iz1)
            return out
        }
    }

    /** Gathers the shapes, then lays the grid over them ([finish]). */
    internal class Builder {
        private val type = ArrayList<Byte>()
        private val data = ArrayList<Float>()
        private val kinds = ArrayList<SolidKind>()
        private val owners = ArrayList<String?>()

        /** An oriented box: centre ([cx], [cz]), its axis ([ux], [uz]) (normalised here), half extents along it and across. */
        fun box(cx: Float, cz: Float, ux: Float, uz: Float, hu: Float, hv: Float, kind: SolidKind, owner: String?) {
            val l = hypot(ux, uz).takeIf { it > 1e-6f } ?: return
            if (hu <= 0f || hv <= 0f) return
            type += 0; data += cx; data += cz; data += ux / l; data += uz / l; data += hu; data += hv
            kinds += kind; owners += owner
        }

        fun circle(cx: Float, cz: Float, r: Float, kind: SolidKind, owner: String?) {
            if (r <= 0f) return
            type += 1; data += cx; data += cz; data += r; data += 0f; data += 0f; data += 0f
            kinds += kind; owners += owner
        }

        /** A world rectangle (x0, z0, x1, z1). */
        fun rect(r: FloatArray, kind: SolidKind, owner: String?) =
            box((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, 1f, 0f, (r[2] - r[0]) / 2f, (r[3] - r[1]) / 2f, kind, owner)

        /** A thin box [thick] wide from one point to another. */
        fun segment(ax: Float, az: Float, bx: Float, bz: Float, thick: Float, kind: SolidKind, owner: String?) {
            val l = hypot(bx - ax, bz - az)
            if (l < 1e-3f) { circle(ax, az, thick / 2f, kind, owner); return }
            box((ax + bx) / 2f, (az + bz) / 2f, bx - ax, bz - az, l / 2f, thick / 2f, kind, owner)
        }

        /** A box in a building's local frame ([TownPlace]: x along, z into the lot), from ([lx0], [lz0]) to ([lx1], [lz1]). */
        fun localBox(place: TownPlace, lx0: Float, lz0: Float, lx1: Float, lz1: Float, kind: SolidKind, owner: String?) {
            val c = place.world((lx0 + lx1) / 2f, (lz0 + lz1) / 2f)
            // local x runs along (nz, -nx) in the world
            box(c.x, c.z, place.nz, -place.nx, abs(lx1 - lx0) / 2f, abs(lz1 - lz0) / 2f, kind, owner)
        }

        fun finish(town: Town): TownCollision {
            // the world: the town's data and every shape, and a margin past it
            var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            fun take(x: Float, z: Float) { x0 = min(x0, x); z0 = min(z0, z); x1 = max(x1, x); z1 = max(z1, z) }
            for (r in town.rows) for (p in r.pts) take(p.x, p.z)
            for (p in town.props) take(p.x, p.z)
            for (nd in town.nodes) take(nd.x, nd.z)
            if (x0 > x1) { x0 = 0f; z0 = 0f; x1 = 1f; z1 = 1f }
            x0 -= EDGE_MARGIN; z0 -= EDGE_MARGIN; x1 += EDGE_MARGIN; z1 += EDGE_MARGIN
            return finishGrid(x0, z0, max(1, ceil((x1 - x0) / CELL).toInt()), max(1, ceil((z1 - z0) / CELL).toInt()))
        }

        /** The map of these shapes over a grid of [cols] x [rows] cells from ([x0], [z0]). */
        fun finishGrid(x0: Float, z0: Float, cols: Int, rows: Int): TownCollision {
            val n = type.size
            val d = FloatArray(data.size) { data[it] }
            val t = ByteArray(n) { type[it] }
            val counts = IntArray(cols * rows + 1)
            val bounds = FloatArray(4)
            fun cellsOf(i: Int, f: (Int) -> Unit) {
                aabb(t, d, i, bounds)
                val c0 = floor((bounds[0] - x0) / CELL).toInt().coerceIn(0, cols - 1)
                val c1 = floor((bounds[2] - x0) / CELL).toInt().coerceIn(0, cols - 1)
                val r0 = floor((bounds[1] - z0) / CELL).toInt().coerceIn(0, rows - 1)
                val r1 = floor((bounds[3] - z0) / CELL).toInt().coerceIn(0, rows - 1)
                for (r in r0..r1) for (c in c0..c1) f(r * cols + c)
            }
            for (i in 0 until n) cellsOf(i) { counts[it + 1]++ }
            for (k in 1 until counts.size) counts[k] += counts[k - 1]
            val items = IntArray(counts[cols * rows])
            val fill = counts.copyOf()
            for (i in 0 until n) cellsOf(i) { items[fill[it]++] = i }
            return TownCollision(t, d, kinds.toTypedArray(), owners.toTypedArray(), x0, z0, CELL, cols, rows, counts, items)
        }

        private fun aabb(t: ByteArray, d: FloatArray, i: Int, out: FloatArray) {
            val o = i * 6
            if (t[i].toInt() == 1) { val r = d[o + 2]; out[0] = d[o] - r; out[1] = d[o + 1] - r; out[2] = d[o] + r; out[3] = d[o + 1] + r; return }
            val ux = d[o + 2]; val uz = d[o + 3]; val hu = d[o + 4]; val hv = d[o + 5]
            val ex = abs(ux) * hu + abs(uz) * hv
            val ez = abs(uz) * hu + abs(ux) * hv
            out[0] = d[o] - ex; out[1] = d[o + 1] - ez; out[2] = d[o] + ex; out[3] = d[o + 1] + ez
        }
    }
}

/**
 * The movie theater's site as solid shapes, from its plot and era (street.json v2's `cinema` and its landmark's
 * `footprint`), in the theater model's frame as scene3d's CinemaKit lays it: the plot's middle at x 0 (x the art's
 * left-to-right seen from the street), the front wall's line at z 0, z out towards the street (the plot runs back to
 * -deep). Kept to the same numbers as CinemaKit's plot and CinemaGrounds' walls and rails (change them together): the
 * main block, the multiplex's drum, stair tower, atrium and canopy posts, and
 * the site's boundary walls (the palace's brick, the modern's chain link) or rails (the twin's and the multiplex's car
 * parks). Parked cars, dumpsters and bins are not in it yet.
 */
class CinemaSiteShape(val sprite: String, val era: String, val lot: Float, val deep: Float) {
    /** The main block on the plot: its width, its middle along x and the auditorium's back (negative z). */
    class Body(val w: Float, val cx: Float, val back: Float)

    fun body(): Body {
        val l = lot; val d = deep
        fun backOf(want: Float) = -(d - 4f - max(0f, min(want, d - 4f - 90f - 330f)))
        return when (era) {
            "palace" -> Body((l - 150f).coerceIn(340f, 540f).coerceAtMost(l - 16f), 0f, backOf(120f))
            "modern" -> Body((l - 150f).coerceIn(360f, 540f).coerceAtMost(l - 16f), 0f, backOf(120f))
            "twin" -> {
                val w = (l - 240f).coerceIn(360f, 560f).coerceAtMost(l - 16f)
                Body(w, if (l - w >= 200f) -l / 2f + 14f + w / 2f else 0f, backOf(140f))
            }
            else -> {
                val w = (l - 2f * DRUM_REACH - 110f).coerceIn(440f, 760f).coerceAtMost(l - 2f * DRUM_REACH - 8f)
                val x0 = -l / 2f + DRUM_REACH + 4f
                Body(w, x0 + w / 2f, backOf(250f))
            }
        }
    }

    /**
     * Each solid piece in the model's frame: boxes as (x0, z0, x1, z1) with kind, circles as (x, z, r). The palace's and
     * the modern's back gate (-40..40) is left open.
     */
    fun pieces(boxes: MutableList<Pair<FloatArray, SolidKind>>, circles: MutableList<Pair<FloatArray, SolidKind>>) {
        val bd = body()
        val bx0 = bd.cx - bd.w / 2f; val bx1 = bd.cx + bd.w / 2f
        val px0 = -lot / 2f + 2f; val px1 = lot / 2f - 2f; val pzb = -deep + 2f
        boxes += floatArrayOf(bx0, bd.back, bx1, 0f) to SolidKind.BUILDING
        fun wall(x0: Float, z0: Float, x1: Float, z1: Float, k: SolidKind) { boxes += floatArrayOf(min(x0, x1), min(z0, z1), max(x0, x1), max(z0, z1)) to k }
        fun rail(x0: Float, z0: Float, x1: Float, z1: Float) =
            if (x0 == x1) wall(x0 - 1f, z0, x0 + 1f, z1, SolidKind.FENCE) else wall(x0, z0 - 1f, x1, z0 + 1f, SolidKind.FENCE)
        when (era) {
            "palace" -> {
                val zFrom = -60f
                wall(px0, pzb, px0 + 5f, zFrom, SolidKind.WALL); wall(px1 - 5f, pzb, px1, zFrom, SolidKind.WALL)
                wall(px0 + 5f, pzb, -40f, pzb + 5f, SolidKind.WALL); wall(40f, pzb, px1 - 5f, pzb + 5f, SolidKind.WALL)
            }
            "modern" -> {
                val zFrom = -60f
                rail(px0 + 1f, zFrom, px0 + 1f, pzb + 1f); rail(px1 - 1f, zFrom, px1 - 1f, pzb + 1f)
                rail(px0 + 1f, pzb + 1f, -40f, pzb + 1f); rail(40f, pzb + 1f, px1 - 1f, pzb + 1f)
            }
            "twin" -> {
                rail(px0 + 1f, pzb + 1f, px1 - 1f, pzb + 1f)
                rail(px0 + 1f, -60f, px0 + 1f, pzb + 1f)
            }
            else -> {
                // the main block's front: the atrium of glass stands 18 out of it, the canopy's two posts further out
                boxes += floatArrayOf(bd.cx - 112f, -1f, bd.cx + 112f, 18f) to SolidKind.BUILDING
                for (sx in floatArrayOf(-1f, 1f)) circles += floatArrayOf(bd.cx + sx * 90f, 48f, 2.5f) to SolidKind.BUILDING
                // the drum at the left corner and the stair tower up the halls' left side
                circles += floatArrayOf(bx0 - 12f, -24f, 18f) to SolidKind.BUILDING
                boxes += floatArrayOf(bx0 + 4f - 28f, -80f - 220f - 22f, bx0 + 4f, -80f - 220f + 22f) to SolidKind.BUILDING
                // the low rail round the car park behind
                rail(px0 + 1f, pzb + 1f, px1 - 1f, pzb + 1f)
                rail(px0 + 1f, bd.back, px0 + 1f, pzb + 1f)
                rail(px1 - 1f, bd.back, px1 - 1f, pzb + 1f)
            }
        }
    }

    /**
     * Adds the site at [b] (the cinema's slot on its row, [place] its frame) to the map: the model's frame stands at the
     * slot's front wall's middle (the setback), x along the art's left-to-right, z out towards the street.
     */
    internal fun addTo(builder: TownCollision.Builder, place: TownPlace, b: Placement) {
        val boxes = ArrayList<Pair<FloatArray, SolidKind>>()
        val circles = ArrayList<Pair<FloatArray, SolidKind>>()
        pieces(boxes, circles)
        // model (x, z) to the slot's local frame (x along local -x... see TownPlace: local -x is the art's left-to-right;
        // local z into the lot): local x = -x, local z = setback - z
        for ((r, k) in boxes) builder.localBox(place, -r[2], b.setback - r[3], -r[0], b.setback - r[1], k, sprite)
        for ((c, k) in circles) { val w = place.world(-c[0], b.setback - c[1]); builder.circle(w.x, w.z, c[2], k, sprite) }
    }

    companion object {
        /** How far the multiplex's drum stands out past the front block's side (CinemaKit's). */
        const val DRUM_REACH = 32f
        /** The plot when the town gives none (CinemaKit's). */
        const val LOT = 630f
        const val DEEP = 920f

        /** The town's cinema site in a street.json root (its graph's `cinema` and landmark footprint), or null. */
        fun parse(root: org.json.JSONObject): CinemaSiteShape? {
            val g = root.optJSONObject("graph") ?: return null
            val c = g.optJSONObject("cinema") ?: return null
            val lms = g.optJSONArray("landmarks") ?: return null
            val lm = (0 until lms.length()).map { lms.getJSONObject(it) }.firstOrNull { it.optString("id") == c.optString("landmark") } ?: return null
            val sprite = lm.optString("sprite").ifBlank { return null }
            val fp = lm.optJSONObject("footprint")
            val year = c.optInt("year")
            val era = c.optString("era").ifBlank { when { year < 1960 -> "palace"; year < 1980 -> "modern"; year < 2000 -> "twin"; else -> "multiplex" } }
            val w = fp?.optDouble("width")?.toFloat()?.takeIf { it > 0f } ?: LOT
            val d = fp?.optDouble("depth")?.toFloat()?.takeIf { it > 0f } ?: DEEP
            return CinemaSiteShape(sprite, era, w, d)
        }
    }
}
