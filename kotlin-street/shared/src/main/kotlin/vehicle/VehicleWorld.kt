package com.lifemax.rpg.feature.habits.vehicle

import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The town's cars (docs/VEHICLES.md, V1): the models whose era fits the town's year parked at their spots ([park]),
 * each a Jolt vehicle in [physics] on the town's flat ground, the town's solid shapes streamed into the physics round
 * the car being driven ([stream]), getting in at a door and out at `exit_left` ([enter], [exit]), and the drive itself:
 * the left stick steers, the HUD's pedals (or the stick's up and down on a keyboard) drive and brake, reversing from a
 * stop, the handbrake. Stepped at a fixed [STEP] by the town's game loop ([step]); the renderer reads each car's
 * interpolated pose ([Car.pose]). The walk is carried by the car while driving (its camera, HUD and rows follow).
 *
 * World units: the town's street units ([U] a metre) on the outside, metres inside the physics; y up in both.
 */
class VehicleWorld(
    val physics: Physics,
    val collision: TownCollision?,
    /** The cars' specs (the catalogue's: [VehicleSpec.V1]). */
    val specs: List<VehicleSpec>,
    /**
     * The ground's height (m) at a point (m) outside the town's flat ground, or null there is none: the near terrain, which
     * heightfield chunks round the car are made from ([streamGround]). Null: the town's flat ground only, inside its edge.
     */
    val ground: ((xM: Float, zM: Float) -> Float?)? = null,
    /** The town's flat ground (street units: x0, z0, x1, z1): a box at y 0 under it; null: the collision map's bounds. */
    flat: FloatArray? = null,
) : AutoCloseable {
    /** One car in the world. */
    inner class Car(val spec: VehicleSpec, val colour: Int, val classic: Boolean, val spot: String) {
        internal var handle = -1
        /** The physics state now and at the last step (interpolated for drawing). */
        internal val now = FloatArray(VehicleState.COUNT)
        internal val before = FloatArray(VehicleState.COUNT)
        /** What the renderer draws, made each frame ([publish]). */
        val pose = CarPose(spec)
        /** Doors' opening (0 shut .. 1 open), by [VehicleSpec.doors]. */
        val doorOpen = FloatArray(spec.doors.size)
        internal val doorGoal = FloatArray(spec.doors.size)
        /** The lamps: head and tail on, braking, reversing, the turn signals' side (-1 left, 1 right, 0 none). */
        var headlights = false; internal set
        var braking = false; internal set
        var reversing = false; internal set
        var signal = 0; internal set
        /** The steering input now (-1 left .. 1 right), eased. */
        var steer = 0f; internal set
        val index: Int get() = cars.indexOf(this)

        /** Recent poses it stood on the ground in at a sane speed (x, y, z m; yaw), newest at [goodHead] - 1: [rescue] puts it back. */
        internal val good = FloatArray(GOOD_POSES * 4)
        internal var goodCount = 0
        internal var goodHead = 0
        internal var goodAt = -1e9f
        /** Where it was parked (x, y, z m; yaw): the last resort. */
        internal val home = FloatArray(4)

        /** Where it stands (street units) and the way it faces (unit, world x, z). */
        val x: Float get() = now[VehicleState.X] * U
        val z: Float get() = now[VehicleState.Z] * U
        val fx: Float get() = forwardX(now)
        val fz: Float get() = forwardZ(now)
        /** Its speed along its own forward (m/s; negative reversing). */
        val forwardSpeed: Float get() = now[VehicleState.FORWARD_SPEED]
        /** For the sound (docs/SOUND.md "Engines"): its velocity (m/s, world x and z), the engine's speed and gear the physics reports, the gas now (0..1, reverse too). */
        val vx: Float get() = now[VehicleState.VX]
        val vz: Float get() = now[VehicleState.VZ]
        val rpm: Float get() = now[VehicleState.RPM]
        val gear: Int get() = now[VehicleState.GEAR].toInt()
        var throttle = 0f; internal set

        /** A point of the model's frame (metres) in the world (street units), its y ignored: into [out] (x, z). */
        fun world(mx: Float, mz: Float, out: FloatArray): FloatArray {
            val f0 = fx; val f1 = fz
            // the car's left (+x of the model) is the forward turned a quarter anticlockwise seen from above (y up, z south)
            val lx = f1; val lz = -f0
            out[0] = x + (lx * mx + f0 * mz) * U
            out[1] = z + (lz * mx + f1 * mz) * U
            return out
        }
    }

    /** The cars (copied on write: the renderer iterates them on its own thread while a test spawn adds or removes one). */
    val cars = java.util.concurrent.CopyOnWriteArrayList<Car>()

    /** The town the cars were parked in ([park]): where the test button's spawn looks for a kerb. */
    var town: Town? = null; private set
    private var parkedYards: TownYards? = null
    /** The yards the cars were parked among ([park]): what the destruction looks broken yard things up in. */
    val yards: TownYards? get() = parkedYards
    /** Sets the yards without parking (a test's or a preview's scene of its own). */
    internal fun useYards(y: TownYards) { parkedYards = y }

    /** What the cars broke and its pieces (docs/VEHICLES.md, "Destruction"). */
    val wreck = Wreckage(this)

    /** The car being driven (null on foot) and the one being got into or out of. */
    var driven: Car? = null; private set
    val driving: Boolean get() = driven != null && phase == Phase.DRIVING
    /** Getting in or out: the walk waits. */
    val busy: Boolean get() = phase == Phase.ENTERING || phase == Phase.EXITING
    enum class Phase { ON_FOOT, ENTERING, DRIVING, EXITING }
    var phase = Phase.ON_FOOT; private set
    private var phaseT = 0f
    private var doorIndex = -1
    /** The car being got into or out of (its door swings). */
    private var active: Car? = null

    /** The car whose door the walking player stands at (the HUD's "Enter"), updated by [step]. */
    var enterable: Car? = null; private set

    // the HUD's pedals (set by the app; the desktop's keys come through the stick's up and down)
    @Volatile var gas = 0f
    @Volatile var brakePedal = 0f
    @Volatile var handbrake = false

    /** Physics steps' time (ms, eased) and how many steps the last frame took; bodies in the world. */
    var stepMs = 0f; private set
    var stepsLast = 0; private set
    var staticCount = 0; private set
    /** The last restream's time (ms) and the longest so far (the first, getting in, adds the most). */
    var streamMs = 0f; private set
    var streamMaxMs = 0f; private set

    private var acc = 0f
    private val tmp = FloatArray(6)
    private val p2 = FloatArray(2)
    private var flatHandle = -1
    /** The flat ground box's extent (street units: x0, z0, x1, z1). */
    private val flatRect: FloatArray = flat ?: collision?.let { floatArrayOf(it.minX, it.minZ, it.maxX, it.maxZ) } ?: floatArrayOf(-50000f, -50000f, 50000f, 50000f)

    /**
     * The loop's say over the driven car (docs/LOOP.md "Needs, weather and cars"): [engineOff] (a breakdown or an empty tank) takes the
     * gas off; [gripScale] (the sky's `carGripMultiplier`, 1 dry .. .53 in snow) lessens the power put down and the steering at speed (a
     * stand-in: the physics' tyre friction is fixed when the car is made); [onImpact] hears a hard stop of the driven car (the speed lost
     * in one frame, m/s; at most one a second) so the player can be hurt.
     */
    @Volatile var engineOff = false
    @Volatile var gripScale = 1f
    @Volatile var onImpact: ((Float) -> Unit)? = null
    private var lastDrivenSpeed = Float.NaN
    private var impactAt = -1e9f

    /** Changes to the world asked from another thread (a car added or taken away): done at the start of the next [step], on the game loop's thread. */
    private val jobs = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    fun post(job: () -> Unit) { jobs.add(job) }

    /** Takes a parked [c] out of the world (a car towed away); false while it is being driven. */
    fun retire(c: Car): Boolean { if (c === driven) return false; remove(c); return true }

    /** Told of what happened to the cars worth a line in the log ("car: rescued from ..."): set by the app. */
    @Volatile var onEvent: ((String) -> Unit)? = null

    init {
        val b = flatRect
        val cx = (b[0] + b[2]) / 2f / U; val cz = (b[1] + b[3]) / 2f / U
        val hx = (b[2] - b[0]) / 2f / U; val hz = (b[3] - b[1]) / 2f / U
        flatHandle = physics.addBox(cx, -1f, cz, hx, 1f, hz, 0f, 1f)
    }

    // ------------------------------------------------------------------ parking

    /**
     * Parks the cars whose era fits [town]'s year ([VehicleEra]): each model once, the first (the 1950s sedan if the year
     * has it, else the newest) on the spawn's street a few metres ahead of the player, the rest alternately in the
     * driveways and on the streets nearest the spawn; each painted from its palette by the town's seed. [year] overrides
     * the town's (a preview's era).
     */
    fun park(town: Town, yards: TownYards, year: Int = town.year): List<Car> {
        for (c in cars) { physics.removeVehicle(c.handle); collision?.setExtra(KEY + c.spot, null) }
        cars.clear(); driven = null; phase = Phase.ON_FOOT; spawned = null
        if (this.town != null && this.town !== town) wreck.clear()
        this.town = town; parkedYards = yards
        val fits = specs.filter { it.era(year) != VehicleEra.NONE }
        if (fits.isEmpty()) return cars
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        val street = VehicleSpots.street(town, sp.x, sp.z, collision)
        val drives = VehicleSpots.driveways(yards, sp.x, sp.z, collision)
        // the first: the 1950s sedan when the year has it (the one V1 is shown with), else the newest model about
        val order = fits.sortedWith(compareBy<VehicleSpec>({ if (it.key.startsWith("northcairn")) 0 else 1 }, { -it.from }))
        var si = 0; var di = 0
        val used = ArrayList<VehicleSpots.Spot>()
        // (no two cars nose to tail: the first one's way along its street stays clear)
        fun free(sp: VehicleSpots.Spot) = used.none { hypot(it.x - sp.x, it.z - sp.z) < SPACING_M * U }
        fun nextStreet(): VehicleSpots.Spot? { while (si < street.size) { val sp = street[si++]; if (free(sp)) return sp }; return null }
        fun nextDrive(): VehicleSpots.Spot? { while (di < drives.size) { val sp = drives[di++]; if (free(sp)) return sp }; return null }
        for ((k, s) in order.withIndex()) {
            val spot = (if (k % 2 == 1) nextDrive() ?: nextStreet() else nextStreet() ?: nextDrive()) ?: continue
            used += spot
            if (spot.length < s.footprint[0] * 2f * U) continue
            val colour = s.colours[Math.floorMod((town.seed + s.key).hashCode(), s.colours.size)]
            add(s, colour, s.era(year) == VehicleEra.CLASSIC, spot)
        }
        return cars
    }

    /** Adds a car of [s] at [spot] (street units, heading along it). */
    fun add(s: VehicleSpec, colour: Int, classic: Boolean, spot: VehicleSpots.Spot): Car {
        val c = Car(s, colour, classic, spot.name)
        // (the model's origin is between the axles: its footprint's middle may sit a little forward or back of it)
        val yaw = atan2(spot.fx, spot.fz)
        val mx = spot.x / U - spot.fx * s.footprint[2]; val mz = spot.z / U - spot.fz * s.footprint[2]
        c.handle = physics.addVehicle(s.desc(), s.boxes, mx, .02f, mz, yaw)
        c.home[0] = mx; c.home[1] = .1f; c.home[2] = mz; c.home[3] = yaw
        physics.input(c.handle, 0f, 0f, 0f, 1f)
        physics.state(c.handle, c.now); c.now.copyInto(c.before)
        settle(c)
        footprint(c)
        c.pose.set(c, 1f)
        // (in the list once it stands on its springs: the renderer may pick it up on its own thread at once)
        cars += c
        changedFrames = 30
        return c
    }

    /** Lets a car just placed sink onto its springs (a second of steps on its own: it is never seen bouncing). */
    private fun settle(c: Car) {
        repeat(90) { physics.step(STEP) }
        physics.state(c.handle, c.now); c.now.copyInto(c.before)
    }

    /** The parked car's footprint in the walk's collision (the player walks round it), or none while it is driven. */
    private fun footprint(c: Car) {
        val col = collision ?: return
        if (c === driven) { col.setExtra(KEY + c.spot, null); return }
        val f = c.spec.footprint
        val fx = c.fx; val fz = c.fz
        val cx = c.x + fx * f[2] * U; val cz = c.z + fz * f[2] * U
        col.setExtra(KEY + c.spot, floatArrayOf(cx, cz, fx, fz, f[0] * U, f[1] * U), SolidKind.PROP, KEY + c.spot)
    }

    // ------------------------------------------------------------------ the test button's spawn

    /** The car the test button spawned last (replaced by the next tap), and which of [specs] comes next. */
    var spawned: Car? = null; private set
    private var spawnNext = 0
    /** Asked by the HUD (any thread); done at the next [step], on the game loop's thread, while on foot. */
    @Volatile private var spawnWanted = false
    /** What the last spawn did, for the HUD ("Northcairn Terrace", or why none), and a count that moves with each one. */
    @Volatile var spawnNote: String? = null; private set
    @Volatile var spawnCount = 0; private set

    /** The test button: at the next [step], the next of the five cars beside the player (replacing the last one spawned). */
    fun requestSpawn() { spawnWanted = true }

    /**
     * Where a car of [s] can be spawned for a player at ([x], [z]): the nearest kerbside spot ([VehicleSpots.street])
     * whose whole footprint, with a margin, is clear of everything solid ([VehicleSpots.clear]: buildings, fences,
     * props and the parked cars, which are in the collision), not on the player and within [SPAWN_REACH_M]; else the
     * nearest clear driveway. Null: nowhere near.
     */
    fun spawnSpot(town: Town, s: VehicleSpec, x: Float, z: Float, yards: TownYards? = null): VehicleSpots.Spot? {
        val col = collision
        val hl = (s.footprint[0] + SPAWN_MARGIN_M) * U; val hw = (s.footprint[1] + SPAWN_MARGIN_M) * U
        fun ok(sp: VehicleSpots.Spot): Boolean {
            if (hypot(sp.x - x, sp.z - z) > SPAWN_REACH_M * U) return false
            // (not on the player: their middle at least a stride outside the footprint)
            if (VehicleSpots.rectDistance(sp.x, sp.z, sp.fx, sp.fz, hl, hw, x, z) < SPAWN_PLAYER_M * U) return false
            return col == null || VehicleSpots.clear(col, sp.x, sp.z, sp.fx, sp.fz, hl, hw)
        }
        VehicleSpots.street(town, x, z, col).firstOrNull { ok(it) }?.let { return it }
        return yards?.let { y -> VehicleSpots.driveways(y, x, z, col).firstOrNull { it.length >= s.footprint[0] * 2f * U && ok(it) } }
    }

    /**
     * Spawns the next of the five cars (whatever the year: a test) beside the player at ([x], [z]), replacing the one
     * spawned last. Null (and [spawnNote] says why) when there is no town, the player is in a car, or no clear kerb is near.
     */
    fun spawnNear(x: Float, z: Float, yards: TownYards? = parkedYards): Car? {
        val t = town ?: run { spawnNote = "no town"; spawnCount++; return null }
        if (phase != Phase.ON_FOOT || specs.isEmpty()) { spawnNote = "not while in a car"; spawnCount++; return null }
        spawned?.let { remove(it) }
        spawned = null
        val s = specs[Math.floorMod(spawnNext++, specs.size)]
        val spot = spawnSpot(t, s, x, z, yards)
        if (spot == null) { spawnNote = "${s.name}: no clear kerb near here"; spawnCount++; return null }
        val colour = s.colours[Math.floorMod((t.seed + s.key + spawnNext).hashCode(), s.colours.size)]
        val c = add(s, colour, s.era(t.year) == VehicleEra.CLASSIC, VehicleSpots.Spot("spawn:" + spot.name, spot.x, spot.z, spot.fx, spot.fz, spot.length))
        spawned = c
        spawnNote = s.name; spawnCount++
        return c
    }

    /**
     * The first parked car (the 1956 sedan in the capital) is parked beside the spawn on Main Street; a game that
     * starts where the player last stood (TownSession.restore) may be far from it, and the owner could not find the
     * cars. Asked once after the restore: at the next [step], if the player is more than [BRING_FROM_M] from it, it is
     * moved to the clear kerb nearest them ([spawnSpot]), keeping its colour.
     */
    fun requestFirstNearPlayer() { bringWanted = true }
    @Volatile private var bringWanted = false

    /** See [requestFirstNearPlayer]: the moved car, or null when it stayed (near already, driven, or no clear kerb). */
    fun bringFirstNear(x: Float, z: Float): Car? {
        val t = town ?: return null
        val c = cars.firstOrNull { it !== spawned } ?: return null
        if (c === driven || phase != Phase.ON_FOOT || hypot(c.x - x, c.z - z) < BRING_FROM_M * U) return null
        val spot = spawnSpot(t, c.spec, x, z, parkedYards) ?: return null
        remove(c)
        val moved = add(c.spec, c.colour, c.classic, VehicleSpots.Spot("near:" + spot.name, spot.x, spot.z, spot.fx, spot.fz, spot.length))
        // (still the first: the one the town is shown with)
        cars.remove(moved); cars.add(0, moved)
        return moved
    }

    /** Takes [c] out of the world (its body and its footprint). */
    private fun remove(c: Car) {
        if (c === driven) return
        physics.removeVehicle(c.handle)
        collision?.setExtra(KEY + c.spot, null)
        cars.remove(c)
        changedFrames = 30
        if (enterable === c) enterable = null
    }

    // ------------------------------------------------------------------ getting in and out

    /** The car door ([ENTER_REACH] of either side's exit point) nearest a walker at ([wx], [wz]), or null. */
    fun doorNear(wx: Float, wz: Float): Car? {
        var best: Car? = null; var bd = ENTER_REACH * U
        for (c in cars) {
            if (c === driven) continue
            for (side in EXITS) {
                val n = c.spec.node(side) ?: continue
                c.world(n.x, n.z, p2)
                val d = hypot(p2[0] - wx, p2[1] - wz)
                if (d < bd) { bd = d; best = c }
            }
        }
        return best
    }

    /** Gets into [c] (the walk: [walk]): its driver's door opens, the player sits, it shuts; then the drive. */
    fun enter(c: Car, walk: Carried): Boolean {
        if (phase != Phase.ON_FOOT) return false
        walk.carry(c.x, c.z, c.fx, c.fz, 0f)
        driven = c; active = c
        phase = Phase.ENTERING; phaseT = 0f
        doorIndex = c.spec.doors.indexOf(c.spec.driverDoor ?: "")
        footprint(c)
        stream(c, force = true)
        return true
    }

    /** Whether the player can get out now: the car (nearly) still. */
    val canExit: Boolean get() = driving && abs(driven?.forwardSpeed ?: 0f) < EXIT_SPEED

    /** Gets out: the handbrake on, the door opens, the player stands at `exit_left`, it shuts. */
    fun exit(): Boolean {
        if (!canExit) return false
        phase = Phase.EXITING; phaseT = 0f
        active = driven
        return true
    }

    // ------------------------------------------------------------------ the frame

    /**
     * One frame of [dt] s: the drive's input (the stick's [steerIn] -1..1 right, [pedalIn] -1..1 up: the keyboard's pedal,
     * with the HUD's [gas] and [brakePedal]), the physics stepped at [STEP], the cars' poses published, the walk carried.
     */
    fun step(dt: Float, walk: Carried, steerIn: Float, pedalIn: Float) {
        val d = driven
        val a = active
        if (groundDirty) { groundDirty = false; dropStreamed() }
        if (resetWanted) { resetWanted = false; if (d != null && phase == Phase.DRIVING) rescue(d, "the player's reset") }
        while (true) { val j = jobs.poll() ?: break; runCatching { j() } }
        if (spawnWanted) { spawnWanted = false; spawnNear(walk.wx, walk.wz) }
        if (bringWanted) { bringWanted = false; bringFirstNear(walk.wx, walk.wz) }
        when (phase) {
            Phase.ON_FOOT -> enterable = doorNear(walk.wx, walk.wz)
            Phase.ENTERING -> {
                enterable = null
                phaseT += dt
                a?.let { setDoor(it, if (phaseT < ENTER_SECONDS * .6f) 1f else 0f) }
                if (phaseT >= ENTER_SECONDS) { phase = Phase.DRIVING; active = null }
            }
            Phase.EXITING -> {
                phaseT += dt
                a?.let { setDoor(it, if (phaseT < EXIT_SECONDS * .7f) 1f else 0f) }
                if (phaseT >= EXIT_SECONDS * .45f && d != null) {
                    // stands at the exit (the walk finds the clear spot nearest it)
                    val n = d.spec.node("exit_left") ?: d.spec.node("exit_right")
                    if (n != null) d.world(n.x, n.z, p2) else { p2[0] = d.x; p2[1] = d.z }
                    driven = null
                    footprint(d)
                    walk.release(p2[0], p2[1], d.fx, d.fz)
                }
                if (phaseT >= EXIT_SECONDS) { phase = Phase.ON_FOOT; active = null }
            }
            Phase.DRIVING -> {}
        }
        // the drive's input: only while driving (else the handbrake holds it)
        if (d != null && driven === d) {
            if (phase == Phase.DRIVING) drive(d, dt, steerIn, pedalIn) else physics.input(d.handle, 0f, 0f, 0f, 1f)
        }
        for (i in cars.indices) easeDoors(cars[i], dt)
        // the fixed steps (at most MAX_STEPS a frame: a long hitch slows the world rather than spiralling)
        acc += dt.coerceIn(0f, MAX_STEPS * STEP)
        var n = 0
        val t0 = System.nanoTime()
        while (acc >= STEP && n < MAX_STEPS) {
            for (i in cars.indices) cars[i].now.copyInto(cars[i].before)
            val broke = physics.step(STEP)
            if (broke > 0) wreck.onBreaks(simT + (n + 1) * STEP)
            wreck.afterStep(simT + (n + 1) * STEP)
            for (i in cars.indices) physics.state(cars[i].handle, cars[i].now)
            acc -= STEP; n++
        }
        if (n > 0) { simT += n * STEP; for (i in cars.indices) watch(cars[i]) }
        // (a hard stop of the driven car: the speed it lost this frame)
        if (n > 0 && d != null && driven === d && phase == Phase.DRIVING) {
            val sp = sqrt(d.now[VehicleState.VX] * d.now[VehicleState.VX] + d.now[VehicleState.VZ] * d.now[VehicleState.VZ])
            val drop = lastDrivenSpeed - sp
            if (!lastDrivenSpeed.isNaN() && drop > IMPACT_MIN && simT - impactAt > 1f) { impactAt = simT; onImpact?.invoke(drop) }
            lastDrivenSpeed = sp
        } else lastDrivenSpeed = Float.NaN
        if (n > 0) { val ms = (System.nanoTime() - t0) / 1e6f / n; stepMs = if (stepMs == 0f) ms else stepMs + (ms - stepMs) * .05f }
        stepsLast = n
        if (changedFrames > 0) changedFrames--
        val alpha = (acc / STEP).coerceIn(0f, 1f)
        for (i in cars.indices) cars[i].pose.set(cars[i], alpha)
        (driven ?: cars.firstOrNull())?.let { wreck.publish(alpha, simT, it.x, it.z) }
        val c = driven
        if (c != null && phase != Phase.EXITING) {
            walk.carry(c.pose.x, c.pose.z, c.pose.fx, c.pose.fz, abs(c.forwardSpeed) * U)
            stream(c, force = false)
        }
    }

    /** Whether anything about the cars still moves (frames should keep coming). */
    val moving: Boolean get() = phase != Phase.ON_FOOT || changedFrames > 0 || wreck.live > 0 && wreck.awake || cars.any { it.now[VehicleState.ACTIVE] > .5f } || cars.any { c -> c.doorOpen.any { it > 0f && it < 1f } }
    /** Frames still to draw after a car was added or taken away (the scene shows it). */
    private var changedFrames = 0

    private fun drive(c: Car, dt: Float, steerIn: Float, pedalIn: Float) {
        // the pedal: the HUD's gas and brake, or the stick's up (gas) and down (brake, then reverse from a stop)
        val pedal = (gas - brakePedal - pedalIn).coerceIn(-1f, 1f)
        val v = c.forwardSpeed
        var forward = 0f; var brake = 0f
        if (pedal > .02f) { if (v < -REVERSE_BRAKE) brake = pedal else forward = pedal }
        else if (pedal < -.02f) { if (v > REVERSE_BRAKE) brake = -pedal else forward = pedal }
        // (no engine: a breakdown or an empty tank; the wet or the snow loses some of the power put down)
        if (engineOff) forward = 0f else forward *= .5f + .5f * gripScale
        // the steering eased (a thumb is all or nothing), and turned less at speed (what a driver does)
        val k = 1f - kotlin.math.exp(-dt / STEER_TIME)
        c.steer += (steerIn.coerceIn(-1f, 1f) - c.steer) * k
        val atSpeed = 1f / (1f + (abs(v) / STEER_FADE) * (abs(v) / STEER_FADE))
        val hb = if (handbrake) 1f else 0f
        physics.input(c.handle, forward, c.steer * atSpeed * (.7f + .3f * gripScale), brake, hb)
        c.throttle = abs(forward)
        c.braking = brake > .05f || (hb > 0f && abs(v) > .5f)
        c.reversing = forward < 0f
        c.signal = if (abs(c.steer) > .6f && abs(v) < 9f) (if (c.steer > 0f) 1 else -1) else 0
    }

    private fun setDoor(c: Car, open: Float) { if (doorIndex in c.doorGoal.indices) c.doorGoal[doorIndex] = open }

    private fun easeDoors(c: Car, dt: Float) {
        for (i in c.doorOpen.indices) {
            val g = c.doorGoal[i]; val o = c.doorOpen[i]
            if (o != g) c.doorOpen[i] = if (g > o) min(g, o + dt / DOOR_SECONDS) else max(g, o - dt / DOOR_SECONDS)
        }
    }

    /** The lamps for how dark it is (0 day .. 1 night): head and tail lights on the driven car after dusk. */
    fun lights(dark: Float) {
        for (c in cars) c.headlights = c === driven && dark > HEADLIGHTS_FROM
    }

    // ------------------------------------------------------------------ the town in the physics, streamed

    /** The town's shapes in the physics by key (a base shape's index, or an added one's numbers), and where streamed last. */
    private val streamed = HashMap<Long, Int>()
    /** A streamed shape as the destruction needs it: its key, index (past the map's size: an added one), kind, owner, detail, form. */
    internal class Streamed(val handle: Int, val key: Long, val index: Int, val kind: SolidKind, val owner: String?, val detail: String?, val circle: Boolean, val data: FloatArray)
    private val streamedBy = HashMap<Int, Streamed>()
    /** The streamed shape whose static is [handle], if any. */
    internal fun streamedShape(handle: Int): Streamed? = streamedBy[handle]
    /** Takes the static [handle] out of the physics and the streamed set (the destruction put pieces in its place). */
    internal fun dropStreamed(handle: Int) {
        val s = streamedBy.remove(handle) ?: return
        streamed.remove(s.key)
        physics.remove(handle)
        staticCount = streamed.size
    }
    private val keepNow = HashSet<Long>()
    private var streamX = Float.NaN
    private var streamZ = Float.NaN
    private var streamVersion = -1

    /**
     * Keeps the town's solid shapes within [STREAM_M] of [c] in the physics (statics: buildings, fences, hedges, walls,
     * trees, props, the river's banks, the other cars' are not: they are vehicles), done again once it has moved
     * [RESTREAM_M] or the town's added shapes changed (a store built). Shapes further than [STREAM_M] + [RESTREAM_M] go.
     */
    fun stream(c: Car, force: Boolean) {
        val col = collision
        if (col == null) { streamGround(c); return }
        val x = c.x; val z = c.z
        if (!force && !streamX.isNaN() && hypot(x - streamX, z - streamZ) < RESTREAM_M * U && col.extraVersion == streamVersion) return
        streamX = x; streamZ = z; streamVersion = col.extraVersion
        val t0 = System.nanoTime()
        val r = STREAM_M * U
        keepNow.clear()
        // (the town's edge is a wall only where the terrain does not run on past it)
        val edgeOpen = ground?.invoke(x / U, z / U) != null
        col.forEachNearDetail(x - r, z - r, x + r, z + r, tmp) { i, kind, owner, detail, circle, s ->
            // (the parked cars are vehicles, the settled debris pieces bodies of their own)
            if (owner != null && (owner.startsWith(KEY) || owner.startsWith(DEBRIS))) return@forEachNearDetail
            if (kind == SolidKind.EDGE && edgeOpen) return@forEachNearDetail
            val key = if (i < col.size) i.toLong() else keyOf(s, kind)
            keepNow += key
            if (key !in streamed) {
                val h = addShape(s, circle, kind)
                streamed[key] = h
                if (h >= 0) { streamedBy[h] = Streamed(h, key, i, kind, owner, detail, circle, s.copyOf()); wreck.give(h, kind, detail) }
            }
        }
        val it = streamed.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in keepNow) { physics.remove(e.value); streamedBy.remove(e.value); it.remove() } }
        staticCount = streamed.size
        streamGround(c)
        val ms = (System.nanoTime() - t0) / 1e6f
        streamMs = ms; streamMaxMs = max(streamMaxMs, ms)
    }

    /** A shape (street units) as a static body: a box or an upright cylinder [heightOf] its kind tall, on the ground. */
    private fun addShape(s: FloatArray, circle: Boolean, kind: SolidKind): Int {
        val h = heightOf(kind)
        return if (circle) physics.addCylinder(s[0] / U, h / 2f, s[1] / U, s[2] / U, h / 2f, FRICTION_OF_WALLS)
        else physics.addBox(s[0] / U, h / 2f, s[1] / U, s[4] / U, h / 2f, s[5] / U, atan2(-s[3], s[2]), FRICTION_OF_WALLS)
    }


    // the ground outside the town: heightfield chunks round the car
    private val chunks = HashMap<Long, Int>()
    private val heights = FloatArray(CHUNK_N * CHUNK_N)

    private fun streamGround(c: Car) {
        val g = ground ?: return
        val xm = c.x / U; val zm = c.z / U
        val ci = kotlin.math.floor(xm / CHUNK_M).toInt(); val cj = kotlin.math.floor(zm / CHUNK_M).toInt()
        val want = HashSet<Long>()
        for (dj in -1..1) for (di in -1..1) {
            val i = ci + di; val j = cj + dj
            val key = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
            want += key
            if (key in chunks) continue
            val x0 = i * CHUNK_M; val z0 = j * CHUNK_M
            val cell = CHUNK_M / (CHUNK_N - 1)
            var any = false
            for (b in 0 until CHUNK_N) for (a in 0 until CHUNK_N) {
                val h = g(x0 + a * cell, z0 + b * cell)
                if (h != null) any = true
                heights[b * CHUNK_N + a] = h ?: 0f
            }
            // (a chunk the terrain cannot give yet is tried again at the next restream)
            if (any) chunks[key] = physics.addHeightfield(x0, z0, CHUNK_N, cell, heights)
        }
        val it = chunks.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in want) { physics.remove(e.value); it.remove() } }
    }

    // ------------------------------------------------------------------ the safety net

    private var simT = 0f
    @Volatile private var resetWanted = false
    @Volatile private var groundDirty = false

    /** The ground changed (the terrain became ready): the streamed shapes and chunks are made again at the next restream. */
    fun groundChanged() { groundDirty = true }

    private fun dropStreamed() {
        for (h in streamed.values) physics.remove(h)
        streamed.clear()
        streamedBy.clear()
        for (h in chunks.values) physics.remove(h)
        chunks.clear()
        streamX = Float.NaN
    }

    /** The driven car is put back where it last stood on the ground (done at the next step: a stuck or lost car). */
    fun resetDriven() { if (driven != null) resetWanted = true }

    /** The floor (m) under ([xm], [zm]): the flat ground's 0, the terrain's height beyond it, else the flat box's 0. */
    private fun floorAt(xm: Float, zm: Float): Float {
        val xs = xm * U; val zs = zm * U
        if (xs >= flatRect[0] && xs <= flatRect[2] && zs >= flatRect[1] && zs <= flatRect[3]) return 0f
        val h = ground?.invoke(xm, zm)
        return if (h != null && h.isFinite()) h else 0f
    }

    /** Notes where [c] stands well, or puts it back if it fell through the world, flew, or its state is not a number. */
    private fun watch(c: Car) {
        val s = c.now
        var finite = true
        for (k in 0..VehicleState.VZ) if (!s[k].isFinite()) finite = false
        val x = s[VehicleState.X]; val y = s[VehicleState.Y]; val z = s[VehicleState.Z]
        val speed = if (finite) sqrt(s[VehicleState.VX] * s[VehicleState.VX] + s[VehicleState.VY] * s[VehicleState.VY] + s[VehicleState.VZ] * s[VehicleState.VZ]) else 0f
        val floor = if (finite) floorAt(x, z) else 0f
        val why = when {
            !finite -> "a state that is not a number"
            y < floor - FALL_M -> "a fall of %.0f m below the ground".format(java.util.Locale.US, floor - y)
            speed > MAX_SPEED -> "a speed of %.0f m/s".format(java.util.Locale.US, speed)
            else -> null
        }
        if (why != null) { rescue(c, why); return }
        if (simT - c.goodAt < GOOD_EVERY) return
        var contact = 0
        for (w in 0 until 4) if (s[VehicleState.WHEELS + w * VehicleState.W_COUNT + VehicleState.W_CONTACT] > .5f) contact++
        if (contact >= 3 && speed < GOOD_SPEED && abs(y - floor) < 3f) {
            val k = c.goodHead * 4
            c.good[k] = x; c.good[k + 1] = y; c.good[k + 2] = z; c.good[k + 3] = atan2(forwardX(s), forwardZ(s))
            c.goodHead = (c.goodHead + 1) % GOOD_POSES
            c.goodCount = min(GOOD_POSES, c.goodCount + 1)
            c.goodAt = simT
        }
    }

    /** Puts [c] back, upright and at rest, on its last good ground (a second or so before: the newest pose may be the slip). */
    private fun rescue(c: Car, why: String) {
        val s = c.now
        val from = "(%.0f, %.0f, %.0f) m".format(java.util.Locale.US, s[VehicleState.X], s[VehicleState.Y], s[VehicleState.Z])
        val px: Float; val py: Float; val pz: Float; val yaw: Float
        if (c.goodCount > 0) {
            val back = min(c.goodCount, BACK_POSES + 1) - 1
            val k = Math.floorMod(c.goodHead - 1 - back, GOOD_POSES) * 4
            px = c.good[k]; py = c.good[k + 1]; pz = c.good[k + 2]; yaw = c.good[k + 3]
        } else { px = c.home[0]; py = c.home[1]; pz = c.home[2]; yaw = c.home[3] }
        physics.place(c.handle, px, py + .15f, pz, yaw)
        physics.input(c.handle, 0f, 0f, 0f, 1f)
        physics.state(c.handle, c.now); c.now.copyInto(c.before)
        c.steer = 0f
        c.goodCount = 1; c.goodHead = 1
        c.good[0] = px; c.good[1] = py; c.good[2] = pz; c.good[3] = yaw
        c.goodAt = simT
        streamX = Float.NaN
        changedFrames = 30
        onEvent?.invoke("car: rescued from $why at $from, put back at (%.0f, %.0f, %.0f) m".format(java.util.Locale.US, px, py, pz))
    }

    override fun close() {
        for (c in cars) collision?.setExtra(KEY + c.spot, null)
        physics.close()
    }

    /** What the walk does for the cars (OpenWalk: carried while driving, stood at the exit after). */
    interface Carried {
        val wx: Float
        val wz: Float
        fun carry(x: Float, z: Float, dx: Float, dz: Float, speed: Float)
        fun release(x: Float, z: Float, dx: Float, dz: Float)
    }

    companion object {
        /**
         * The V1 cars for [town] ([VehicleSpec.V1] read by [read]: a path under assets/vehicles, e.g.
         * "northcairn-1956/model-info.json"), parked ([park], for [year]); null where the physics library does not load.
         */
        fun start(town: Town, collision: TownCollision?, yards: TownYards, read: (String) -> String?, year: Int = town.year,
                  ground: ((xM: Float, zM: Float) -> Float?)? = null, flat: FloatArray? = null): VehicleWorld? {
            val specs = VehicleSpec.V1.mapNotNull { k -> read("$k/model-info.json")?.let { runCatching { VehicleSpec.parse(it) }.getOrNull() } }
            if (specs.isEmpty()) return null
            val ph = Physics.create() ?: return null
            return VehicleWorld(ph, collision, specs, ground, flat).also { it.park(town, yards, year) }
        }

        /** Street units in a metre (the buildings' 0.0388 m texel is 184/224 units). */
        const val U = (184f / 224f) / .0388f
        /** Parked cars stand at least this far apart (m). */
        const val SPACING_M = 18f
        /** The test button's spawn: a car's footprint keeps this much (m) clear all round, stands this far (m) off the
         *  player at least, and within this reach (m) of them. */
        const val SPAWN_MARGIN_M = .3f
        const val SPAWN_PLAYER_M = .8f
        const val SPAWN_REACH_M = 80f
        /** The first car is brought to a player who starts further than this (m) from it ([requestFirstNearPlayer]). */
        const val BRING_FROM_M = 60f
        /** The physics' fixed step (s). */
        const val STEP = 1f / 60f
        /** The least speed (m/s) lost in one frame that counts as an impact of the driven car. */
        const val IMPACT_MIN = 4f
        const val MAX_STEPS = 4
        /** The town's shapes are streamed into the physics within this of the car (m), again after it moved this far. */
        const val STREAM_M = 110f
        const val RESTREAM_M = 25f
        /** The terrain's heightfield chunks round the car: this wide (m), this many samples a side. */
        const val CHUNK_M = 64f
        const val CHUNK_N = 33
        /** The safety net: a car this far (m) below the ground, or faster than [MAX_SPEED] (m/s), or not a number, is put back. */
        const val FALL_M = 6f
        const val MAX_SPEED = 85f
        /** The poses kept (one each [GOOD_EVERY] s while on the ground below [GOOD_SPEED] m/s); a rescue goes [BACK_POSES] back. */
        const val GOOD_POSES = 8
        const val GOOD_EVERY = .5f
        const val GOOD_SPEED = 70f
        const val BACK_POSES = 2
        /** A door's standing spot within this (m) of the player shows "Enter". */
        const val ENTER_REACH = 1.7f
        private val EXITS = listOf("exit_left", "exit_right")
        /** Getting in and out (s), a door's swing (s). */
        const val ENTER_SECONDS = 1.1f
        const val EXIT_SECONDS = 1.1f
        const val DOOR_SECONDS = .35f
        /** The player can get out below this (m/s). */
        const val EXIT_SPEED = 1.2f
        /** The pedal against the way the car rolls brakes until it is slower than this (m/s), then drives that way. */
        const val REVERSE_BRAKE = .6f
        /** The steering's ease (s) and the speed (m/s) it is halved at. */
        const val STEER_TIME = .12f
        const val STEER_FADE = 22f
        /** Headlights on past this darkness (0 day .. 1 night). */
        const val HEADLIGHTS_FROM = .3f
        const val FRICTION_OF_WALLS = .5f
        /** The keys of the parked cars' shapes in the walk's collision. */
        const val KEY = "vehicle:"
        /** The keys (and owners) of settled debris in the walk's collision ([Wreckage]). */
        const val DEBRIS = "debris:"

        /** A streamed added shape's key (its numbers and kind): the same shape, the same key. */
        internal fun keyOf(s: FloatArray, kind: SolidKind): Long {
            var h = 1469598103934665603L
            for (k in 0..5) { h = (h xor (s[k] * 64f).toLong()) * 1099511628211L }
            return (h xor kind.ordinal.toLong()) or Long.MIN_VALUE
        }

        /** How tall a town shape stands in the physics (m), by kind. */
        fun heightOf(k: SolidKind): Float = when (k) {
            SolidKind.BUILDING -> 8f
            SolidKind.FENCE -> 1.3f
            SolidKind.HEDGE -> 1.6f
            SolidKind.WALL -> .9f
            SolidKind.YARD_OBJECT -> 1.4f
            SolidKind.TREE -> 5f
            SolidKind.PROP -> 1.1f
            SolidKind.WATER -> 1f
            SolidKind.PARAPET -> 1.1f
            SolidKind.EDGE -> 4f
        }

        fun forwardX(s: FloatArray): Float {
            val x = s[VehicleState.QX]; val y = s[VehicleState.QY]; val z = s[VehicleState.QZ]; val w = s[VehicleState.QW]
            return 2f * (x * z + w * y)
        }
        fun forwardZ(s: FloatArray): Float {
            val x = s[VehicleState.QX]; val y = s[VehicleState.QY]
            return 1f - 2f * (x * x + y * y)
        }
    }
}

/**
 * A car's pose as the renderer draws it ([VehicleWorld.Car.pose]): its place (street units), rotation, the wheels' spin,
 * steer and suspension, the doors and the steering wheel, the lamps; interpolated between physics steps. Written by the
 * game loop, read by the renderer: [read] copies it under its lock.
 */
class CarPose(val spec: VehicleSpec) {
    var x = 0f; var y = 0f; var z = 0f
    var qx = 0f; var qy = 0f; var qz = 0f; var qw = 1f
    /** Forward on the ground (unit). */
    var fx = 0f; var fz = 1f
    /** fl, fr, rl, rr: spin about the axle (rad), steer (rad, + left), the wheel's centre's rise from rest (m). */
    val spin = FloatArray(4)
    val steer = FloatArray(4)
    val rise = FloatArray(4)
    val doors = FloatArray(spec.doors.size)
    /** The steering wheel's turn (rad, + anticlockwise seen by the driver). */
    var wheel = 0f
    var head = false; var brake = false; var reverse = false; var signal = 0
    var colour = 0
    var speed = 0f
    private val PI_F = Math.PI.toFloat()
    /** The suspension's length at rest (m): a wheel's rise is measured from it. */
    private val rest = spec.drivetrain.let { (it.travelMin + it.travelMax) / 2f }

    internal fun set(c: VehicleWorld.Car, a: Float) = synchronized(this) {
        val p = c.before; val n = c.now
        fun l(i: Int) = p[i] + (n[i] - p[i]) * a
        x = l(VehicleState.X) * VehicleWorld.U; y = l(VehicleState.Y) * VehicleWorld.U; z = l(VehicleState.Z) * VehicleWorld.U
        // (nlerp, the shorter way round)
        val dot = p[VehicleState.QX] * n[VehicleState.QX] + p[VehicleState.QY] * n[VehicleState.QY] + p[VehicleState.QZ] * n[VehicleState.QZ] + p[VehicleState.QW] * n[VehicleState.QW]
        val s = if (dot < 0f) -1f else 1f
        var ax = p[VehicleState.QX] + (s * n[VehicleState.QX] - p[VehicleState.QX]) * a
        var ay = p[VehicleState.QY] + (s * n[VehicleState.QY] - p[VehicleState.QY]) * a
        var az = p[VehicleState.QZ] + (s * n[VehicleState.QZ] - p[VehicleState.QZ]) * a
        var aw = p[VehicleState.QW] + (s * n[VehicleState.QW] - p[VehicleState.QW]) * a
        val ql = sqrt(ax * ax + ay * ay + az * az + aw * aw).coerceAtLeast(1e-6f)
        ax /= ql; ay /= ql; az /= ql; aw /= ql
        qx = ax; qy = ay; qz = az; qw = aw
        val gx = 2f * (ax * az + aw * ay); val gz = 1f - 2f * (ax * ax + ay * ay)
        val gl = hypot(gx, gz).coerceAtLeast(1e-6f)
        fx = gx / gl; fz = gz / gl
        for (k in 0..3) {
            val o = VehicleState.WHEELS + k * VehicleState.W_COUNT
            // (Jolt keeps the angle within a turn either way: eased the short way round)
            var dw = n[o + VehicleState.W_ANGLE] - p[o + VehicleState.W_ANGLE]
            if (dw > PI_F) dw -= 2f * PI_F else if (dw < -PI_F) dw += 2f * PI_F
            spin[k] = p[o + VehicleState.W_ANGLE] + dw * a
            steer[k] = l(o + VehicleState.W_STEER)
            rise[k] = rest - l(o + VehicleState.W_SUSPENSION)
        }
        for (k in doors.indices) doors[k] = c.doorOpen[k]
        wheel = -c.steer * 2.6f
        head = c.headlights; brake = c.braking; reverse = c.reversing; signal = c.signal
        colour = c.colour
        speed = c.forwardSpeed
    }

    /** Copies it into [o] (the renderer's own), under the lock. */
    fun read(o: CarPose) = synchronized(this) {
        o.x = x; o.y = y; o.z = z; o.qx = qx; o.qy = qy; o.qz = qz; o.qw = qw; o.fx = fx; o.fz = fz
        spin.copyInto(o.spin); steer.copyInto(o.steer); rise.copyInto(o.rise); doors.copyInto(o.doors)
        o.wheel = wheel; o.head = head; o.brake = brake; o.reverse = reverse; o.signal = signal; o.colour = colour; o.speed = speed
    }
}
