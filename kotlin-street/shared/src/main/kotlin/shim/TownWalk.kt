package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.Furniture
import com.lifemax.rpg.feature.habits.domain.JunctionFrame
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.RowEnd
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPoint
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.domain.at
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Walking the whole town (docs/TOWN.md, T7). The player's place on the ground in the world (x east, z south) and the way
 * they head are the truth; the street view's row, x along it and lane across its pavement are derived from it (and kept,
 * so a frame's step need not search). On a row the player walks as on today's street ([GameState.step]: the lanes, the
 * furniture, the ends); at a row's ends the town's decision points lead on:
 *
 *  - straight: walking on past the node over the side street's zebra hands over to the same side's next row, in the same
 *    lane (the rows are on one line; tests check it), with no turn of the camera;
 *  - turn-in: the up key in the mouth walks round the corner building ([CornerWalk] on [Crossroads.at]'s plan, mirrored
 *    for a left turn at a row's start), the camera turning a quarter with the walk;
 *  - cross: the down key at the kerb walks over the road to the row facing this one (at the zebras by the row's ends, and
 *    mid-block while [TownWalk.midBlock] allows it), the camera swinging half round to face the other side;
 *  - a dead end, or an end with no straight on, stops the walk as a street's end does today.
 *
 * Nothing here draws: a renderer reads [TownWalkView] each frame.
 */

/** What the camera of the street view looks at in the world: the point [tx], [tz] (on the ground) and its looking direction. */
data class TownCamera(val tx: Float, val tz: Float, val fx: Float, val fz: Float) {
    /** The camera's right (the street view's +x), from its looking direction: x east, z south seen from above. */
    val rx: Float get() = -fz
    val rz: Float get() = fx

    /** The looking direction as an angle: 0 north (-z), PI/2 east, clockwise seen from above. */
    val yaw: Float get() = atan2(fx, -fz)
}

/**
 * A walk from one row to another that takes time (a turn round a corner, a crossing over the road): which, from where to
 * where, how far along (0..1) and the camera's ease (0..1) along it. A straight walk on over the zebra is no transit: the
 * row changes in the step that passes the node, and nothing turns.
 */
class TownTransit(
    val kind: TransitionKind,
    val from: Row,
    val to: Row,
    /** The node a turn goes round (the crossing's: -1). */
    val node: Int,
    /** For a turn: the corner's plan and its place in the world (the plan is mirrored for a left turn). */
    val crossroads: Crossroads?,
    val frame: JunctionFrame?,
) {
    var progress = 0f
        internal set
    var ease = 0f
        internal set
}

/**
 * What a renderer of the town needs from the walk each frame (the Filament town scene, T8). All positions are world
 * units (x east, z south). Read after [TownWalk.step].
 */
interface TownWalkView {
    val town: Town
    /** The row the street view is on: during a transit the one it began on, until the step that arrives. */
    val row: Row
    /** Along [row] and across its pavement (lanes out from the building line). */
    val x: Float
    val lane: Float
    /** The player on the ground (the truth) and the way they head (unit; also as an angle: 0 north, clockwise). */
    val wx: Float
    val wz: Float
    val hx: Float
    val hz: Float
    val heading: Float
    /** A turn or crossing under way, else null. */
    val transit: TownTransit?
    /** The camera's target and direction now (eased through a transit). The street view's look-ahead is the renderer's. */
    val camera: TownCamera
    /** The rows to keep built and alive, nearest first: [row], the rows one transition off it, and a transit's target. */
    fun liveRows(): List<Row>
    /** The building whose front the player stands in front of on [row], if any (for doors and "Talk to"). */
    val front: Placement?
    /** How many times the row has changed (a renderer swaps its street when this moves). */
    val rowChanges: Int
}

/** The walk through [town], for the one player ([GameState]'s keys and legs, [CornerWalk]'s turns). */
class TownWalk(override val town: Town) : TownWalkView {
    override var row: Row = town.row(town.spawn.row)
        private set
    override var x: Float = town.spawn.x
        private set
    override var lane: Float = town.spawn.lane
        private set
    override var wx = 0f
        private set
    override var wz = 0f
        private set
    override var hx = 0f
        private set
    override var hz = -1f
        private set
    override val heading: Float get() = atan2(hx, -hz)
    override var transit: TownTransit? = null
        private set
    override var camera = TownCamera(0f, 0f, 0f, -1f)
        private set
    override var rowChanges = 0
        private set
    override val front: Placement? get() = row.buildings.firstOrNull { x >= it.x0 && x <= it.x1 }

    /** Crossing the road anywhere along a row (true), or only at the zebras by its ends ([ZEBRA] from either node). */
    var midBlock = true

    /** The keys as the last frame saw them, and whether they must be let go before the next transit. */
    private var upBefore = false
    private var downBefore = false
    private var needRelease = false
    /** Arrived over the road with the down key held: it walks on in (up) until let go. */
    private var downWalks = false

    /** The crossing's ends (world) and speed. */
    private var sx = 0f
    private var sz = 0f
    private var ex = 0f
    private var ez = 0f
    private var crossLen = 1f
    private var crossToX = 0f
    private var crossV = 0f
    private var crossSpin = 1f

    /** The row's layout (furniture) and the corners' plans, kept. */
    private val furnitureCache = HashMap<String, List<Furniture>>()
    private val plans = HashMap<String, Crossroads>()

    init { spawn() }

    /** Stands the player where the town says ([Town.spawn]). */
    fun spawn() = placeOn(town.row(town.spawn.row), town.spawn.x, town.spawn.lane)

    /** Stands the player on [r], [atX] along it, in [atLane]. */
    fun placeOn(r: Row, atX: Float, atLane: Float) {
        abandon()
        if (r.id != row.id) rowChanges++
        row = r; x = atX; lane = atLane
        val p = r.pointAt(x, lane)
        wx = p.x; wz = p.z
        val a = r.alongAt(x)
        hx = a.x * GameState.dir; hz = a.z * GameState.dir
        sync()
        restCamera()
    }

    /**
     * Stands the player at world point ([px], [pz]) (the truth), heading ([dx], [dz]): the street view's row is the one whose
     * pavement that is, nearest building line first, and its x and lane follow.
     */
    fun placeAt(px: Float, pz: Float, dx: Float = hx, dz: Float = hz) {
        val (r, _) = town.nearestRow(px, pz) ?: return
        val (lx, ll) = r.locate(px, pz)
        placeOn(r, lx, ll)
        wx = px; wz = pz
        val l = hypot(dx, dz)
        if (l > 1e-6f) { hx = dx / l; hz = dz / l }
    }

    private fun abandon() {
        if (transit != null) CornerWalk.reset()
        transit = null
        GameState.street = 0
    }

    /** The street view's place for [GameState] (the HUD, the legs): x along the row and the lane. */
    private fun sync() { GameState.x = x; GameState.lane = lane }

    /**
     * One frame: the keys ([GameState.moveX], [GameState.moveLane]) walk the player along the pavement, start a turn or a
     * crossing at a decision point, or carry one on. Returns the transition that completed this frame (a straight walk on,
     * a turn or a crossing arriving), else null.
     */
    fun step(dt: Float): TownStepResult? {
        val t = transit
        val up = CornerWalk.isUp(GameState.moveX, GameState.moveLane)
        val down = GameState.moveLane > .5f && GameState.moveLane > abs(GameState.moveX)
        val upPressed = up && !upBefore
        val downPressed = down && !downBefore
        upBefore = up; downBefore = down
        if (needRelease && !up && !down) needRelease = false
        if (t != null) return when (t.kind) {
            TransitionKind.TURN_IN -> turnStep(dt, t)
            else -> crossStep(dt, t)
        }
        if (!CornerWalk.blocked && !needRelease) {
            if (upPressed) startTurn()?.let { return null }
            if (downPressed) startCross()?.let { return null }
        }
        return walkStep(dt)
    }

    /** What a step completed: [kind] from row [from] to row [to] (arrived: false when a turn or crossing was walked back). */
    data class TownStepResult(val kind: TransitionKind, val from: String, val to: String, val arrived: Boolean)

    // --- the pavement ---

    private fun walkStep(dt: Float): TownStepResult? {
        val r = row
        sync()
        val before = x
        val laneBefore = lane
        val wBefore = wx
        val zBefore = wz
        // the ends: walked on past a node with a row straight on, else stopped as at a street's end
        val from = if (r.start.straight != null) -OPEN else 0f
        val to = if (r.end.straight != null) r.length + OPEN else r.length
        // arrived over the road with the down key still held: it walks on in across the new pavement until let go
        val heldLane = GameState.moveLane
        if (downWalks) { if (GameState.moveLane > .05f) GameState.moveLane = -GameState.moveLane else downWalks = false }
        try { GameState.step(dt, from, to, LANE_MAX) } finally { GameState.moveLane = heldLane }
        GameState.avoid(furniture(r), before, laneBefore, dt)
        // the town's real buildings: a step that would end inside one is not taken
        val p = r.pointAt(GameState.x, GameState.lane)
        if (blockedAt(p.x, p.z, r) && !blockedAt(wBefore, zBefore, r)) {
            GameState.x = before; GameState.lane = laneBefore; GameState.vx = 0f
        }
        GameState.settle()
        x = GameState.x; lane = GameState.lane
        var done: TownStepResult? = null
        // over the node line onto the next row straight on (the same line: the lane carries over, x restarts)
        if (x < 0f && r.start.straight != null) done = handStraight(RowEnd.START)
        else if (x > r.length && r.end.straight != null) done = handStraight(RowEnd.END)
        val q = row.pointAt(x, lane)
        moveTo(q.x, q.z)
        restCamera()
        return done
    }

    private fun handStraight(e: RowEnd): TownStepResult {
        val old = row
        val next = town.next(old, e, TransitionKind.STRAIGHT)!!
        val w = old.pointAt(x, lane)
        val (nx, nl) = next.locate(w.x, w.z)
        row = next; x = nx; lane = nl
        rowChanges++
        sync()
        return TownStepResult(TransitionKind.STRAIGHT, old.id, next.id, true)
    }

    private fun moveTo(px: Float, pz: Float) {
        val dx = px - wx
        val dz = pz - wz
        val l = hypot(dx, dz)
        if (l > 1e-4f) { hx = dx / l; hz = dz / l }
        wx = px; wz = pz
    }

    /** At rest on a row: the camera looks square at its buildings, at the player's place on the building line. */
    private fun restCamera() {
        val b = row.pointAt(x, 0f)
        val n = row.lookAt(x)
        camera = TownCamera(b.x, b.z, n.x, n.z)
    }

    // --- round the corner ---

    /** The end of [row] whose mouth the player stands in (before the node line), if a turn-in leads off it. */
    private fun mouthEnd(): RowEnd? = when {
        row.end.turnIn != null && x <= row.length && x >= row.length - Crossroads.MOUTH_ZONE -> RowEnd.END
        row.start.turnIn != null && x >= 0f && x <= Crossroads.MOUTH_ZONE -> RowEnd.START
        else -> null
    }

    /** The corner's plan for turning from [from] into [to] at [node] (kept). */
    fun plan(node: Int, from: Row, to: Row): Crossroads =
        plans.getOrPut("$node:${from.id}:${to.id}") { Crossroads.at(town, node, from, to) }

    private fun startTurn(): TownTransit? {
        val e = mouthEnd() ?: return null
        val to = town.next(row, e, TransitionKind.TURN_IN) ?: return null
        val node = row.node(e)
        val cr = plan(node, row, to)
        val f = cr.frame!!
        // the walk runs in the plan: the main street's layout x (mirrored for a left turn), walking +X into the mouth
        val sign = if (f.mirrored) -1f else 1f
        GameState.x = f.mainLayoutX(x); GameState.lane = lane
        GameState.vx *= sign
        GameState.dir *= sign
        if (!CornerWalk.begin(cr)) { sync(); GameState.vx *= sign; GameState.dir *= sign; return null }
        CornerWalk.fromX = cr.mainX(GameState.x); CornerWalk.fromZ = 0f
        CornerWalk.toX = 0f; CornerWalk.toZ = cr.crossZ(cr.arriveX)
        val tr = TownTransit(TransitionKind.TURN_IN, row, to, node, cr, f)
        transit = tr
        turnPose(tr)
        return tr
    }

    private fun turnStep(dt: Float, t: TownTransit): TownStepResult? {
        val cr = t.crossroads!!
        val f = t.frame!!
        val y = CornerWalk.yaw
        // (the camera in the plan: the walk picks the sheet's view from the walk against it)
        val r = CornerWalk.walk(dt, cr, cos(y), sin(y), -sin(y), cos(y))
        // a left turn is the plan's mirror image: what is the plan's right is the world's left
        if (f.mirrored && GameState.facing == 0 && r == 0) GameState.dir = -GameState.dir
        turnPose(t)
        GameState.street = 0
        return when (r) {
            1 -> {
                transit = null
                needRelease = true
                row = t.to; x = f.crossRowX(GameState.x); lane = GameState.lane
                rowChanges++
                GameState.dir = if (f.mirrored) -1f else 1f
                GameState.walkOnDir = GameState.dir
                GameState.vx *= GameState.dir
                sync()
                val p = row.pointAt(x, lane)
                moveTo(p.x, p.z)
                restCamera()
                TownStepResult(TransitionKind.TURN_IN, t.from.id, t.to.id, true)
            }
            -1 -> {
                transit = null
                needRelease = true
                x = f.mainRowX(GameState.x); lane = GameState.lane
                if (f.mirrored && GameState.facing == 0) GameState.dir = -GameState.dir
                sync()
                val p = row.pointAt(x, lane)
                moveTo(p.x, p.z)
                restCamera()
                TownStepResult(TransitionKind.TURN_IN, t.from.id, t.to.id, false)
            }
            else -> null
        }
    }

    /** The player and the camera where the walk round the corner has them, in the world. */
    private fun turnPose(t: TownTransit) {
        val f = t.frame!!
        val p = f.toWorld(CornerWalk.wx, CornerWalk.wz)
        val back = CornerWalk.v < 0f
        val h = f.dirToWorld(CornerWalk.hx, CornerWalk.hz)
        wx = p.x; wz = p.z
        hx = if (back) -h.x else h.x; hz = if (back) -h.z else h.z
        val e = CornerWalk.ease
        val y = CornerWalk.yaw
        val tgt = f.toWorld(CornerWalk.fromX + (CornerWalk.toX - CornerWalk.fromX) * e, CornerWalk.fromZ + (CornerWalk.toZ - CornerWalk.fromZ) * e)
        val look = f.dirToWorld(-sin(y), cos(y))
        camera = TownCamera(tgt.x, tgt.z, look.x, look.z)
        t.progress = CornerWalk.p
        t.ease = e
    }

    // --- over the road ---

    /** True if the road may be crossed from [atX] along [r]: by the zebras at its ends, or anywhere while [midBlock]. */
    fun mayCross(r: Row, atX: Float): Boolean = midBlock || atX < ZEBRA || atX > r.length - ZEBRA

    private fun startCross(): TownTransit? {
        if (lane < LANE_MAX - .5f || !mayCross(row, x)) return null
        val to = town.facing(row) ?: return null
        val a = row.pointAt(x, lane)
        val (bx, _) = to.locate(a.x, a.z)
        crossToX = bx.coerceIn(EDGE, to.length - EDGE)
        val b = to.pointAt(crossToX, LANE_MAX)
        sx = a.x; sz = a.z; ex = b.x; ez = b.z
        crossLen = hypot(ex - sx, ez - sz).coerceAtLeast(1f)
        crossV = 0f
        // the camera swings round the way the player faced: looking along the road the way they walk at its middle
        crossSpin = if (GameState.dir >= 0f) 1f else -1f
        val tr = TownTransit(TransitionKind.CROSS, row, to, -1, null, null)
        transit = tr
        GameState.facing = 3
        crossPose(tr)
        return tr
    }

    private fun crossStep(dt: Float, t: TownTransit): TownStepResult? {
        // the keys: on (down, towards the road and over it), back (up), else on by itself (as round a corner)
        val on = GameState.moveLane > .5f
        val back = GameState.moveLane < -.5f
        val want = when {
            back -> -GameState.SPEED
            on || TurnTuning.autoWalk ->
                if (hypot(GameState.moveX, GameState.moveLane) >= MoveTuning.runAt) MoveTuning.run else MoveTuning.walk
            else -> 0f
        }
        crossV += (want - crossV) * min(1f, dt * 16f)
        if (want == 0f && abs(crossV) < .5f) crossV = 0f
        val was = t.progress
        t.progress = (t.progress + crossV * dt / crossLen).coerceIn(0f, 1f)
        val walked = (t.progress - was) * crossLen
        GameState.travel += abs(walked)
        GameState.walking = abs(walked) > 1e-3f
        // towards the camera at first, away from it once it has swung past the middle
        GameState.facing = if ((crossV >= 0f) == (t.progress < .5f)) 3 else 1
        crossPose(t)
        return when {
            t.progress >= 1f -> {
                transit = null
                needRelease = true
                downWalks = GameState.moveLane > .05f
                val old = row
                row = t.to; x = crossToX; lane = LANE_MAX
                rowChanges++
                GameState.dir = -GameState.dir
                GameState.facing = 1
                GameState.vx = 0f
                sync()
                restCamera()
                TownStepResult(TransitionKind.CROSS, old.id, t.to.id, true)
            }
            t.progress <= 0f && crossV < 0f -> {
                transit = null
                needRelease = true
                GameState.facing = 1
                GameState.vx = 0f
                sync()
                val p = row.pointAt(x, lane)
                moveTo(p.x, p.z)
                restCamera()
                TownStepResult(TransitionKind.CROSS, t.from.id, t.to.id, false)
            }
            else -> null
        }
    }

    private fun crossPose(t: TownTransit) {
        val p = t.progress
        moveTo(sx + (ex - sx) * p, sz + (ez - sz) * p)
        val s = smootherstep(p)
        t.ease = s
        val a = t.from.pointAt(x, 0f)
        val b = t.to.pointAt(crossToX, 0f)
        val n = t.from.lookAt(x)
        // the camera's right at the start (the row's along), its look swung by half a turn through it
        val ang = PI.toFloat() * s * crossSpin
        val c = cos(ang)
        val sn = sin(ang)
        val fx = n.x * c + (-n.z) * sn
        val fz = n.z * c + n.x * sn
        camera = TownCamera(a.x + (b.x - a.x) * s, a.z + (b.z - a.z) * s, fx, fz)
    }

    // --- what is around ---

    override fun liveRows(): List<Row> {
        val out = LinkedHashSet<Row>()
        out += town.rowsAround(row, 1)
        transit?.let { out += town.rowsAround(it.to, 1) }
        return out.toList()
    }

    /** The world point of [b]'s door on [r]: the middle of its front wall. */
    fun door(r: Row, b: Placement): TownPoint {
        val n = r.normalAt(b.mid)
        val p = r.pointAt(b.mid, 0f)
        return TownPoint(p.x + n.x * b.setback, p.z + n.z * b.setback)
    }

    /** True if world point ([px], [pz]) is inside (or within [RADIUS] of) a building of the rows around [r]. */
    fun blockedAt(px: Float, pz: Float, r: Row = row): Boolean =
        town.rowsAround(r, 1).any { rr -> footprints(rr).any { it.near(px, pz, RADIUS) } }

    /**
     * The buildings of [r] as world quads: each a rigid box standing on its front's middle (as the export moved it), its
     * front wall at the setback, [Placement.width] wide along the row there and [Placement.depth] deep. (A building over a
     * bend of the main street's steps, 6L's b18 in gamma, stands square to the row at its middle, in the corner's outside.)
     */
    fun footprints(r: Row): List<Footprint> = footprintCache.getOrPut(r.id) {
        r.buildings.map { b ->
            val n = r.normalAt(b.mid)
            val a = r.alongAt(b.mid)
            val m = r.pointAt(b.mid, 0f)
            val h = b.width / 2f
            val f = b.setback
            val k = b.setback + b.depth
            fun at(u: Float, v: Float) = TownPoint(m.x + a.x * u + n.x * v, m.z + a.z * u + n.z * v)
            Footprint(r.id, b, listOf(at(-h, f), at(h, f), at(h, k), at(-h, k)))
        }
    }

    private val footprintCache = HashMap<String, List<Footprint>>()

    private fun furniture(r: Row): List<Furniture> = furnitureCache.getOrPut(r.id) { r.layout().furniture }

    /** A building's footprint on the ground (a convex quad, world). */
    class Footprint(val row: String, val building: Placement, val corners: List<TownPoint>) {
        /** True if ([px], [pz]) is inside the quad grown by [margin]. */
        fun near(px: Float, pz: Float, margin: Float): Boolean {
            val n = corners.size
            var area = 0f
            for (i in 0 until n) { val a = corners[i]; val b = corners[(i + 1) % n]; area += a.x * b.z - b.x * a.z }
            val s = if (area >= 0f) 1f else -1f
            for (i in 0 until n) {
                val a = corners[i]
                val b = corners[(i + 1) % n]
                val dx = b.x - a.x
                val dz = b.z - a.z
                val l = hypot(dx, dz).coerceAtLeast(1e-6f)
                // inward distance (positive inside)
                val d = s * (dx * (pz - a.z) - dz * (px - a.x)) / l
                if (d < -margin) return false
            }
            return true
        }
    }

    companion object {
        /** The kerb's lane: the pavement's front band, as the street's [GameState.step] keeps it. */
        val LANE_MAX: Float get() = BuildingPainter.PAVEMENT - 2f
        /** How far either side of a row's end the zebra over its own road is (street units from the node). */
        const val ZEBRA = 180f
        /** How near a building's walls the player's feet may come (street units). */
        const val RADIUS = 2f
        /** How far past a node with a row straight on the step may carry before the hand-over (one step's worth at most). */
        private const val OPEN = 1000f
        /** A crossing arrives at least this far in from the facing row's ends. */
        private const val EDGE = 30f

        private fun smootherstep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * t * (t * (t * 6f - 15f) + 10f)
        }
    }
}

/**
 * The way a camera square to this row looks at [x] (into the buildings): its normal, swung round a bend over the same
 * stretch as the lanes' offset ([Row.BLEND] either side), so walking round the main street's steps turns the view smoothly.
 */
fun Row.lookAt(x: Float): TownPoint {
    val a = pointAt(x, 0f)
    val b = pointAt(x, -1f)
    val dx = b.x - a.x
    val dz = b.z - a.z
    val l = hypot(dx, dz)
    return if (l < 1e-6f) normalAt(x.coerceIn(0f, length)) else TownPoint(dx / l, dz / l)
}

/**
 * Where world point ([wx], [wz]) is in this row's frame: its x along the row and its lane out from the building line (the
 * inverse of [Row.pointAt], past either end too).
 */
fun Row.locate(wx: Float, wz: Float): Pair<Float, Float> {
    var best = Float.MAX_VALUE
    var bx = 0f
    var bl = 0f
    var acc = 0f
    val last = pts.size - 2
    for (i in 0..last) {
        val a = pts[i]
        val b = pts[i + 1]
        val len = hypot(b.x - a.x, b.z - a.z).coerceAtLeast(1e-6f)
        val dx = (b.x - a.x) / len
        val dz = (b.z - a.z) / len
        var t = (wx - a.x) * dx + (wz - a.z) * dz
        if (i > 0) t = maxOf(t, 0f)
        if (i < last) t = minOf(t, len)
        val px = a.x + dx * t
        val pz = a.z + dz * t
        val d = hypot(wx - px, wz - pz)
        if (d < best) {
            best = d
            bx = acc + t
            val n = normalAt(acc + len / 2f)
            bl = -((wx - px) * n.x + (wz - pz) * n.z) / Crossroads.LANE
        }
        acc += len
    }
    // a few Newton steps on pointAt (the lanes swing round a bend's mitre)
    for (k in 0 until 4) {
        val p = pointAt(bx, bl)
        val fx = p.x - wx
        val fz = p.z - wz
        if (abs(fx) + abs(fz) < 1e-3f) break
        val h = .25f
        val px = pointAt(bx + h, bl)
        val pl = pointAt(bx, bl + h)
        val a11 = (px.x - p.x) / h
        val a21 = (px.z - p.z) / h
        val a12 = (pl.x - p.x) / h
        val a22 = (pl.z - p.z) / h
        val det = a11 * a22 - a12 * a21
        if (abs(det) < 1e-6f) break
        bx -= (a22 * fx - a12 * fz) / det
        bl -= (-a21 * fx + a11 * fz) / det
    }
    return bx to bl
}
