package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoor
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownYard
import com.lifemax.rpg.feature.habits.domain.TownYards
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Walking the town in the open world (docs/TOWN.md "Open world"): the player's place on the ground (wx, wz) and the way
 * they face are the truth and every step is taken in the world, against [TownCollision]'s solid shapes (sliding along
 * walls, fences and hedges; nothing is walked through). The stick pushes relative to the camera's facing, which the
 * player's camera sets each frame ([OpenWalk.cameraYaw]); the walk never turns the camera. Corners, crossings and row ends
 * are nothing special: streets, pavements, yards (through their gates), back lanes, the plaza, grass, the forest and the
 * bridges are all one ground.
 *
 * The street view's frame (which row, how far along it, which lane) is derived from the world point for the HUD (the
 * street's and neighbourhood's names), for the renderer's live rows and for handing over to [TownWalk] when the player
 * picks the old street view. Doors and the people to talk to are found by proximity from any direction ([TownDoors]).
 */
class OpenWalk(
    override val town: Town,
    val collision: TownCollision,
    val yards: TownYards = TownYards(emptyList(), emptyList()),
    val doors: TownDoors = TownDoors(town, yards, collision, collision.cinema),
) : TownWalkView {
    /** The player's feet (world). */
    override var wx = 0f
        private set
    override var wz = 0f
        private set
    /** The way the player faces (unit): turns towards where they walk. */
    override var hx = 0f
        private set
    override var hz = -1f
        private set
    override val heading: Float get() = atan2(hx, -hz)

    /**
     * The camera's yaw as the player's camera has it (0 looking north, -z; PI/2 east; clockwise seen from above, as
     * [TownCamera.yaw]): the stick's up walks this way. Set by the camera every frame; the walk never changes it.
     */
    var cameraYaw = 0f
        set(v) { if (v != field) { field = v; aimed = false } }

    /** The ground velocity (world units a second) and speed. */
    val vx: Float get() = GameState.motion.vx
    val vz: Float get() = GameState.motion.vy
    val speed: Float get() = GameState.motion.speed

    // --- the street view's frame, derived ---
    override var row: Row = town.row(town.spawn.row)
        private set
    override var x = 0f
        private set
    override var lane = 0f
        private set
    override val transit: TownTransit? get() = null
    override var rowChanges = 0
        private set

    /** The camera's looking direction from [cameraYaw], aimed at the player (the rig adds its own distance and height). */
    override val camera: TownCamera
        get() {
            if (!aimed) { aimedCamera = TownCamera(wx, wz, sin(cameraYaw), -cos(cameraYaw)); aimed = true }
            return aimedCamera
        }
    // (made when read after the player or the yaw moved: a step allocates nothing itself)
    private var aimedCamera = TownCamera(0f, 0f, 0f, -1f)
    private var aimed = false

    private fun aim() { aimed = false }

    /** The door the player stands at, facing it, if any ("Go inside"); updated each [step]. */
    var door: TownDoor? = null
        private set
    /** The building of [door] ([TownWalkView.front]: its row may not be [row]). */
    override val front: Placement? get() = door?.let { d -> town.placement(d.sprite)?.second }

    /** The yard the player stands in (its parcel), if any; updated each [step]. */
    var yard: TownYard? = null
        private set

    /** How far the last step's move was cut short by something solid (0 free .. 1 stopped dead). */
    var blockedShare = 0f
        private set

    private val out = FloatArray(2)

    /** Stands the player at the town's spawn (the civic avenue), on the nearest clear spot. */
    fun spawn() {
        val r = town.row(town.spawn.row)
        val p = r.pointAt(town.spawn.x, town.spawn.lane)
        val a = r.alongAt(town.spawn.x)
        placeAt(p.x, p.z, a.x, a.z)
    }

    /**
     * Stands the player at world ([px], [pz]) facing ([dx], [dz]), or on the clear spot nearest it if something stands
     * there (never inside anything), and stops their legs.
     */
    fun placeAt(px: Float, pz: Float, dx: Float = hx, dz: Float = hz) {
        collision.nearestClear(px, pz, RADIUS, out)
        wx = out[0]; wz = out[1]
        val l = hypot(dx, dz)
        if (l > 1e-6f) { hx = dx / l; hz = dz / l }
        GameState.motion.vx = 0f; GameState.motion.vy = 0f
        derive(force = true)
        door = doors.at(wx, wz, hx, hz)
        yard = yardAt(wx, wz)
        aim()
    }

    /** Takes over from the street view's walk (the AI panel's view switch): where it stands, the way it heads. */
    fun takeOver(from: TownWalkView) = placeAt(from.wx, from.wz, from.hx, from.hz)

    /**
     * One frame of [dt] s: the stick's push ([mx] right, [my] down, as [GameState.moveX] and [GameState.moveLane]) turned
     * from the camera's facing into the world, eased into a velocity ([Locomotion], [MoveTuning]'s walk and run), moved
     * against the town's solid shapes (sliding), the legs advanced by what was really walked, the facing turned towards it,
     * and the derived frame, door and yard kept. Allocates nothing.
     */
    fun step(dt: Float, mx: Float = GameState.moveX, my: Float = GameState.moveLane) {
        val m = GameState.motion
        val fx = sin(cameraYaw); val fz = -cos(cameraYaw)
        val rx = -fz; val rz = fx
        // up on the stick (my < 0) walks the way the camera looks
        val px = rx * mx - fx * my
        val pz = rz * mx - fz * my
        m.drive(dt, px, pz)
        val t = min(dt, MoveTuning.dtMax)
        val ax = m.vx * t; val az = m.vy * t
        var walked = 0f
        if (ax != 0f || az != 0f) {
            val share = collision.move(wx, wz, ax, az, RADIUS, out)
            val dx = out[0] - wx; val dz = out[1] - wz
            walked = sqrt(dx * dx + dz * dz)
            wx = out[0]; wz = out[1]
            val asked = sqrt(ax * ax + az * az)
            blockedShare = (1f - walked / asked).coerceIn(0f, 1f)
            // what a wall took off is not kept in the legs: the velocity follows the slide (no speed builds up against it)
            if (t > 0f && walked < asked * .999f) { m.vx = dx / t; m.vy = dz / t }
            if (share <= 0f && walked < 1e-3f) { m.vx = 0f; m.vy = 0f }
        } else blockedShare = 0f
        // facing: towards where they walk (or are pushed, if a wall holds them), quickly
        val wantX: Float; val wantZ: Float
        val sp = hypot(m.vx, m.vy)
        if (sp > 2f) { wantX = m.vx / sp; wantZ = m.vy / sp }
        else { val pl = hypot(px, pz); if (pl > .2f) { wantX = px / pl; wantZ = pz / pl } else { wantX = hx; wantZ = hz } }
        // (turned by the angle between, so a push right round swings the short way rather than through nothing)
        val k = 1f - exp(-t / TURN_TIME)
        val turn = atan2(hx * wantZ - hz * wantX, hx * wantX + hz * wantZ) * k
        val c = cos(turn); val s = sin(turn)
        val nhx = hx * c - hz * s; val nhz = hx * s + hz * c
        val hl = hypot(nhx, nhz)
        if (hl > 1e-5f) { hx = nhx / hl; hz = nhz / hl }
        // the legs: the walk cycle follows only what was really walked
        GameState.facing = 0
        val across = hx * rx + hz * rz
        if (abs(across) > .1f) GameState.dir = if (across > 0f) 1f else -1f
        m.tilt(dt, GameState.dir, abs(across) > .5f)
        GameState.running = m.running
        GameState.walking = walked > 1e-3f
        GameState.travel += walked
        val feet = m.footfalls
        m.advance(walked)
        GameState.footfalls += m.footfalls - feet
        derive(force = false)
        door = doors.at(wx, wz, hx, hz)
        yard = yardAt(wx, wz)
        if (walked > 0f) aim()
    }

    // ------------------------------------------------------------------ the derived street frame

    private var rowIndex = -1

    /** Every segment of every row's building line (flat arrays, for the frame each step without allocating). */
    private val segRow = ArrayList<Int>()
    private val seg: FloatArray
    private val nSeg: Int

    init {
        val s = ArrayList<Float>()
        for ((ri, r) in town.rows.withIndex()) {
            var acc = 0f
            for (i in 0 until r.pts.size - 1) {
                val a = r.pts[i]; val b = r.pts[i + 1]
                val l = hypot(b.x - a.x, b.z - a.z)
                if (l < 1e-4f) continue
                val n = r.normalAt(acc + l / 2f)
                segRow += ri
                s += a.x; s += a.z; s += (b.x - a.x) / l; s += (b.z - a.z) / l; s += l; s += acc; s += n.x; s += n.z
                acc += l
            }
        }
        seg = FloatArray(s.size) { s[it] }
        nSeg = segRow.size
    }

    /**
     * The row nearest the player's feet (by its building line; a line the player stands behind, in its lots, counts as
     * further) and where along it and in which lane they are. The row changes only when another is clearly nearer.
     */
    private fun derive(force: Boolean) {
        var best = -1; var bestD = Float.MAX_VALUE; var bestX = 0f; var bestLane = 0f
        var curD = Float.MAX_VALUE; var curX = 0f; var curLane = 0f
        val cur = rowIndex
        for (i in 0 until nSeg) {
            val o = i * 8
            val ax = seg[o]; val az = seg[o + 1]; val dx = seg[o + 2]; val dz = seg[o + 3]; val l = seg[o + 4]
            val t = ((wx - ax) * dx + (wz - az) * dz).coerceIn(0f, l)
            val qx = ax + dx * t; val qz = az + dz * t
            val ex = wx - qx; val ez = wz - qz
            val dist = sqrt(ex * ex + ez * ez)
            val ln = -(ex * seg[o + 6] + ez * seg[o + 7]) / Crossroads.LANE
            val d = if (ln < 0f) dist * BEHIND else dist
            if (d < bestD) { bestD = d; best = segRow[i]; bestX = seg[o + 5] + t; bestLane = ln }
            if (segRow[i] == cur && d < curD) { curD = d; curX = seg[o + 5] + t; curLane = ln }
        }
        if (best < 0) return
        if (!force && cur >= 0 && best != cur && curD < bestD + HYSTERESIS) { x = curX; lane = curLane; GameState.x = x; GameState.lane = lane; return }
        val r = town.rows[best]
        if (r !== row) { row = r; rowChanges++ }
        rowIndex = best
        x = bestX; lane = bestLane
        GameState.x = x; GameState.lane = lane
    }

    // ------------------------------------------------------------------ around the player

    private var live: List<Row> = emptyList()
    private var liveAtX = Float.NaN
    private var liveAtZ = Float.NaN

    /**
     * The rows within [LIVE] of the player (by their building lines), nearest first: kept, and worked out again only
     * once the player has moved [LIVE_STEP] from where it last was.
     */
    override fun liveRows(): List<Row> {
        if (!liveAtX.isNaN() && hypot(wx - liveAtX, wz - liveAtZ) < LIVE_STEP) return live
        liveAtX = wx; liveAtZ = wz
        val d = HashMap<Row, Float>()
        for (i in 0 until nSeg) {
            val o = i * 8
            val t = ((wx - seg[o]) * seg[o + 2] + (wz - seg[o + 1]) * seg[o + 3]).coerceIn(0f, seg[o + 4])
            val dist = hypot(wx - (seg[o] + seg[o + 2] * t), wz - (seg[o + 1] + seg[o + 3] * t))
            val r = town.rows[segRow[i]]
            if (dist <= LIVE && dist < (d[r] ?: Float.MAX_VALUE)) d[r] = dist
        }
        d[row] = 0f
        live = d.entries.sortedBy { it.value }.map { it.key }
        return live
    }

    /** The yard whose parcel holds ([px], [pz]), if any. */
    fun yardAt(px: Float, pz: Float): TownYard? {
        yard?.let { y -> if (inside(y.parcel, px, pz)) return y }
        for (y in yards.yards) if (inside(y.parcel, px, pz)) return y
        return null
    }

    private fun inside(r: FloatArray, px: Float, pz: Float) = px >= r[0] && px <= r[2] && pz >= r[1] && pz <= r[3]

    /** The person to talk to (by building sprite, among [keepers]) nearest the player within [range], any direction. */
    fun nearestKeeper(keepers: Map<String, Int>, range: Float = TalkRange.distance): String? = doors.nearestKeeper(wx, wz, keepers, range)

    /** How far the player stands from the person at [sprite]. */
    fun fromKeeper(sprite: String): Float = doors.fromKeeper(wx, wz, sprite)

    /** The street the player is on (the derived row's road) and the neighbourhood here (the yard's, else the row's). */
    fun streetName(): String = town.road(row.road).name
    fun hoodName(): String? = yard?.let { town.neighbourhood(it.neighbourhood)?.name } ?: town.neighbourhoodAt(row, x)?.name

    // (last: every field above is set before the player is stood at the spawn)
    init { spawn() }

    companion object {
        /** The player's feet on the ground: a circle this wide (street units; the figure is 36 tall, its shoulders ~14 across). */
        const val RADIUS = 5f
        /** How quickly the player turns to face where they walk (s). */
        const val TURN_TIME = .06f
        /** A building line the player stands behind (in its lots) counts this much further for "which street am I on". */
        const val BEHIND = 1.6f
        /** Another row must be this much nearer before the derived row changes (no flicker at corners). */
        const val HYSTERESIS = 12f
        /** The live rows: within this of the player, worked out again every [LIVE_STEP] walked. */
        const val LIVE = 1400f
        const val LIVE_STEP = 120f
    }
}
