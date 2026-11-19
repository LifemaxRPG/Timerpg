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
import kotlin.math.atan
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

    /** The bicycle ([riding]: the player rides it; [mount], [dismount]). */
    val bike = BikeState().also { it.style = BikeStyle.of(town.year) }
    /** The player rides the bicycle: [step] rides ([stepRide]) instead of walking. */
    var riding = false
        private set
    /** The collision radius now: the bike's while riding, else the walker's. */
    val radius: Float get() = if (riding) BikeState.RADIUS else RADIUS

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
        collision.nearestClear(px, pz, radius, out)
        wx = out[0]; wz = out[1]
        val l = hypot(dx, dz)
        if (l > 1e-6f) { hx = dx / l; hz = dz / l }
        GameState.motion.vx = 0f; GameState.motion.vy = 0f
        bike.stop()
        derive(force = true)
        door = doors.at(wx, wz, hx, hz)
        yard = yardAt(wx, wz)
        aim()
    }

    /** Takes over from the street view's walk (the AI panel's view switch): where it stands, the way it heads (on foot). */
    fun takeOver(from: TownWalkView) { if (riding) dismount(); placeAt(from.wx, from.wz, from.hx, from.hz) }

    /**
     * Gets on the bicycle where the player stands, heading the way they face, rolling on at their walking speed: the bike
     * needs a clear spot of its own ([BikeState.RADIUS], its front wheel too) within [BikeState.MOUNT_REACH], else the
     * player stays on foot (false: no room here, e.g. between a shed and a fence).
     */
    fun mount(): Boolean {
        if (riding) return true
        if (!collision.nearestClear(wx, wz, BikeState.RADIUS, out, step = 2f, reach = BikeState.MOUNT_REACH)) return false
        val cx = out[0]; val cz = out[1]
        if (hypot(cx - wx, cz - wz) > BikeState.MOUNT_REACH) return false
        // (the front wheel: turned round to the clearest way if it would stand in something)
        var fx = hx; var fz = hz
        if (collision.blocked(cx + fx * BikeState.FRONT, cz + fz * BikeState.FRONT, BikeState.FRONT_R)) {
            var found = false
            for (k in 1..12) {
                val a = (if (k % 2 == 1) 1 else -1) * ((k + 1) / 2) * (Math.PI.toFloat() / 6f)
                val c = cos(a); val s = sin(a)
                val tx = hx * c - hz * s; val tz = hx * s + hz * c
                if (!collision.blocked(cx + tx * BikeState.FRONT, cz + tz * BikeState.FRONT, BikeState.FRONT_R)) { fx = tx; fz = tz; found = true; break }
            }
            if (!found) return false
        }
        val m = GameState.motion
        val along = (m.vx * fx + m.vy * fz).coerceAtLeast(0f)
        wx = cx; wz = cz; hx = fx; hz = fz
        bike.stop()
        bike.speed = along
        riding = true
        GameState.riding = true
        GameState.walking = false; GameState.running = false
        aim()
        return true
    }

    /** Gets off where the bike stands (the walker's circle is smaller: always clear there), standing still, facing on. */
    fun dismount() {
        if (!riding) return
        riding = false
        GameState.riding = false
        bike.stop()
        GameState.motion.vx = 0f; GameState.motion.vy = 0f
        collision.nearestClear(wx, wz, RADIUS, out)
        wx = out[0]; wz = out[1]
        aim()
    }

    /**
     * One frame of [dt] s: the stick's push ([mx] right, [my] down, as [GameState.moveX] and [GameState.moveLane]) turned
     * from the camera's facing into the world, eased into a velocity ([Locomotion], [MoveTuning]'s walk and run), moved
     * against the town's solid shapes (sliding), the legs advanced by what was really walked, the facing turned towards it,
     * and the derived frame, door and yard kept. Allocates nothing.
     */
    fun step(dt: Float, mx: Float = GameState.moveX, my: Float = GameState.moveLane) {
        if (riding) { stepRide(dt, mx, my); return }
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
        GameState.surface = surfaceAt(wx, wz, yard)
        if (walked > 0f) aim()
    }

    // ------------------------------------------------------------------ riding

    /**
     * One frame of riding ([RideTuning]): the stick's push, turned from the camera's facing into the world as the walk's,
     * is where the rider wants to go and how fast. The bike keeps its momentum: it pedals up to the push's speed (the
     * cruise at a full push, the sprint after holding it), coasts with the stick let go (rolling and air), brakes when the
     * push points away from where it goes and then turns round tightly at low speed; its heading turns towards the push no
     * faster than its grip allows at this speed (the radius grows with the speed: no instant turn round). It rides against
     * the town's solid shapes with its own wider circle and a probe for the front wheel, sliding along walls with a loss,
     * stopped dead head on; slower on grass and gravel ([RideSurface]). The wheels, cranks, lean and bar follow, for the
     * renderer and the sound. Allocates nothing.
     */
    private fun stepRide(dt: Float, mx: Float, my: Float) {
        val b = bike
        val m = GameState.motion
        val t = min(dt, MoveTuning.dtMax)
        // (a front wheel left touching something, by a hair: the bike eased back off it first, if its body has the room)
        val f0x = wx + hx * BikeState.FRONT; val f0z = wz + hz * BikeState.FRONT
        if (collision.blocked(f0x, f0z, BikeState.FRONT_R) && collision.resolve(f0x, f0z, BikeState.FRONT_R + .05f, out)) {
            val qx = wx + out[0] - f0x; val qz = wz + out[1] - f0z
            if (hypot(qx - wx, qz - wz) < 2f && !collision.blocked(qx, qz, BikeState.RADIUS)) { wx = qx; wz = qz }
        }
        // (where the frame starts: whatever it does, the front wheel never ends deeper in something than here)
        val sx0 = wx; val sz0 = wz; val shx = hx; val shz = hz
        val startIn = collision.blocked(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, BikeState.FRONT_R)
        val startClear = if (startIn) collision.clearance(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, 16f) else 0f
        val fx = sin(cameraYaw); val fz = -cos(cameraYaw)
        val rx = -fz; val rz = fx
        val px = rx * mx - fx * my
        val pz = rz * mx - fz * my
        val push = min(1f, hypot(px, pz))
        val sf = GameState.surface
        // the push's angle from the heading (clockwise from above positive: a right turn)
        var err = 0f
        if (push > .12f) err = atan2(hx * pz - hz * px, hx * px + hz * pz)
        val away = push > .12f && abs(err) > RideTuning.brakeAngle * (Math.PI.toFloat() / 180f)
        // (pushed right round behind: keep turning the side first chosen, no dithering across the back)
        if (away) { if (b.roundSide == 0f) b.roundSide = if (err >= 0f) 1f else -1f; if (abs(err) > 2.6f) err = b.roundSide * abs(err) } else b.roundSide = 0f
        // the sprint: a full push held
        if (push >= RideTuning.full) b.fullFor += t else if (push < RideTuning.sprintOff) b.fullFor = 0f
        b.sprinting = b.fullFor >= RideTuning.sprintAfter
        // the speed wanted: the push's share of the cruise (or the sprint), less into a sharp turn, little when turning round
        val top = (if (b.sprinting) RideTuning.sprint else RideTuning.cruise) * RideSurface.top(sf)
        var want = push * top
        if (push > .12f) want *= (.45f + .55f * cos(min(abs(err), Math.PI.toFloat() / 2f)))
        if (away) want = min(want, RideTuning.turnRound)
        var v = b.speed
        b.pedalling = false
        when {
            push <= .12f -> v -= (RideTuning.roll * RideSurface.roll(sf) + RideTuning.drag * v * v) * t
            away && v > RideTuning.turnRound -> v -= RideTuning.brake * t
            want > v -> {
                val a = (if (v < RideTuning.cruise) RideTuning.accel else RideTuning.accelSprint) * RideSurface.accel(sf)
                v = min(want, v + a * t); b.pedalling = true
            }
            else -> {
                // (easing off to a lighter push: coasting down, a touch of brake; still pedalling softly at a steady speed)
                v = maxOf(want, v - (RideTuning.ease + RideTuning.roll * RideSurface.roll(sf) + RideTuning.drag * v * v) * t)
                b.pedalling = v - want < 4f
            }
        }
        if (v < 0f) v = 0f
        // the heading: towards the push, no faster than the grip allows at this speed
        val most = RideTuning.maxRate(v)
        b.turnSpeed = v
        val wantRate = if (push > .12f) (err / RideTuning.steerTime).coerceIn(-most, most) else 0f
        var turn = wantRate * t
        // (the front wheel never turns into something: a turn that would put it in a wall waits)
        if (turn != 0f) {
            val c = cos(turn); val s = sin(turn)
            val nhx = hx * c - hz * s; val nhz = hx * s + hz * c
            val nowIn = collision.blocked(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, BikeState.FRONT_R)
            // (a front wheel already touching may turn only the way that frees it)
            var ok = b.lifted || !collision.blocked(wx + nhx * BikeState.FRONT, wz + nhz * BikeState.FRONT, BikeState.FRONT_R) || (nowIn &&
                collision.clearance(wx + nhx * BikeState.FRONT, wz + nhz * BikeState.FRONT, 16f) >= collision.clearance(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, 16f))
            if (!ok && v < RideTuning.pivotBelow) {
                // (nearly still with the front wheel at a wall: the rider swings the bike's back round its front wheel)
                // (or, in a corner, rolls it back a little as it turns)
                val bx = wx + (hx - nhx) * BikeState.FRONT; val bz = wz + (hz - nhz) * BikeState.FRONT
                if (!collision.blocked(bx, bz, BikeState.RADIUS)) { wx = bx; wz = bz; ok = true }
                else for (k in 1..3) {
                    val qx = wx - hx * .6f * k; val qz = wz - hz * .6f * k
                    if (!collision.blocked(qx, qz, BikeState.RADIUS) && !collision.blocked(qx + nhx * BikeState.FRONT, qz + nhz * BikeState.FRONT, BikeState.FRONT_R)) {
                        wx = qx; wz = qz; ok = true; break
                    }
                }
            }
            if (!ok) turn = 0f
            else { val l = hypot(nhx, nhz); hx = nhx / l; hz = nhz / l }
        }
        b.rate = if (t > 0f) turn / t else 0f
        // (nearly still, the front wheel at something and the push away from it: the rider walks the bike back)
        if (away && v < RideTuning.pivotBelow && (blockedShare > .5f || turn == 0f ||
            collision.blocked(wx + hx * (BikeState.FRONT + 2f), wz + hz * (BikeState.FRONT + 2f), BikeState.FRONT_R))) {
            v = 0f
            collision.move(wx, wz, -hx * RideTuning.backUp * push * t, -hz * RideTuning.backUp * push * t, BikeState.RADIUS, out)
            val back = hypot(out[0] - wx, out[1] - wz)
            wx = out[0]; wz = out[1]
            b.wheel -= back / b.wheelRadius
            if (back > 0f) aim()
        }
        // the ride: the front wheel's probe first (a glancing hit slides it along what it meets), the bike's circle after
        // it by the front's own move, then back along the way if the front would still stand in something
        val ax = hx * v * t; val az = hz * v * t
        var walked = 0f
        if (ax != 0f || az != 0f) {
            val ox = wx; val oz = wz
            val f0x = ox + hx * BikeState.FRONT; val f0z = oz + hz * BikeState.FRONT
            var mdx = ax; var mdz = az
            val f0In = !b.lifted && collision.blocked(f0x, f0z, BikeState.FRONT_R)
            if (!f0In) {
                collision.move(f0x, f0z, ax, az, BikeState.FRONT_R, out)
                mdx = out[0] - f0x; mdz = out[1] - f0z
            }
            collision.move(ox, oz, mdx, mdz, BikeState.RADIUS, out)
            var nx = out[0]; var nz = out[1]
            val fIn = !b.lifted && collision.blocked(nx + hx * BikeState.FRONT, nz + hz * BikeState.FRONT, BikeState.FRONT_R)
            // (a front wheel touching already may roll only the way that frees it)
            if (fIn && f0In && collision.clearance(nx + hx * BikeState.FRONT, nz + hz * BikeState.FRONT, 16f) < collision.clearance(f0x, f0z, 16f)) { nx = ox; nz = oz }
            else if (fIn && !f0In) {
                var lo = 0f; var hi = 1f
                for (i in 0 until 8) {
                    val f = (lo + hi) / 2f
                    val qx = ox + (nx - ox) * f; val qz = oz + (nz - oz) * f
                    if (collision.blocked(qx + hx * BikeState.FRONT, qz + hz * BikeState.FRONT, BikeState.FRONT_R) || collision.blocked(qx, qz, BikeState.RADIUS)) hi = f else lo = f
                }
                nx = ox + (nx - ox) * lo; nz = oz + (nz - oz) * lo
            }
            val dx = nx - ox; val dz = nz - oz
            walked = sqrt(dx * dx + dz * dz)
            wx = nx; wz = nz
            val asked = sqrt(ax * ax + az * az)
            blockedShare = (1f - walked / asked).coerceIn(0f, 1f)
            if (walked < asked * .995f) {
                // against something: what was stopped is gone, scraping along takes more; the bike swings along the slide
                v = if (t > 0f) min(v, walked / t) * exp(-RideTuning.scrape * t) else 0f
                if (walked > 1e-3f) {
                    val k = 1f - exp(-t / .12f)
                    val sx = dx / walked; val sz = dz / walked
                    if (sx * hx + sz * hz > .2f) {
                        val nhx = hx + (sx - hx) * k; val nhz = hz + (sz - hz) * k
                        val l = hypot(nhx, nhz)
                        // (only if the front wheel stays clear turned so)
                        if (l > 1e-5f && !collision.blocked(wx + nhx / l * BikeState.FRONT, wz + nhz / l * BikeState.FRONT, BikeState.FRONT_R)) { hx = nhx / l; hz = nhz / l }
                    }
                } else v = 0f
            }
        } else blockedShare = 0f
        // (a hair's touch is let be: the probe is the tyre's front, a few centimetres either way are not seen)
        if (!b.lifted && collision.blocked(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, BikeState.FRONT_R - BikeState.FRONT_SLACK) &&
            collision.clearance(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, 16f) < (if (startIn) min(startClear, BikeState.FRONT_R - BikeState.FRONT_SLACK) else BikeState.FRONT_R - BikeState.FRONT_SLACK)) {
            wx = sx0; wz = sz0; hx = shx; hz = shz; v = 0f; walked = 0f; turn = 0f; b.rate = 0f
        }
        b.speed = v
        // (held fast with the stick pushed: after a moment the rider lifts the front wheel round, as anyone would in a
        // tight spot; its probe is let go until the bike moves freely again)
        // (held fast: pushed, yet not 6 units from where it was a moment ago)
        if (push <= .12f || hypot(wx - b.anchorX, wz - b.anchorZ) > 6f) { b.anchorX = wx; b.anchorZ = wz; b.stuckFor = 0f } else b.stuckFor += t
        if (b.stuckFor > RideTuning.liftAfter) b.lifted = true
        else if (b.lifted && walked > 20f * t && !collision.blocked(wx + hx * BikeState.FRONT, wz + hz * BikeState.FRONT, BikeState.FRONT_R)) b.lifted = false
        // the lean a real turn takes (v x rate against gravity, 207.8 units/s^2), eased; the bar turned for the turn's radius
        val g = 9.81f * 21.18f
        val leanWant = atan(v * b.rate / g).coerceIn(-RideTuning.leanMax, RideTuning.leanMax)
        b.lean += (leanWant - b.lean) * (1f - exp(-t / RideTuning.leanTime))
        // (the bar: the wheelbase over the radius, v / rate; at a standstill the pivot turns it a fixed way)
        val steerWant = if (v > 1f) -atan(22f * b.rate / v).coerceIn(-.7f, .7f) else -.5f * (b.rate / RideTuning.pivotRate)
        b.steer += (steerWant - b.steer) * (1f - exp(-t / .08f))
        b.wheel += walked / b.wheelRadius
        if (b.pedalling) b.crank += walked / (b.wheelRadius * b.gear)
        // the game's state: the velocity, no legs walking (no footsteps), the facing as the walk's
        m.vx = hx * v; m.vy = hz * v
        GameState.walking = false
        GameState.running = false
        GameState.travel += walked
        GameState.facing = 0
        val across = hx * rx + hz * rz
        if (abs(across) > .1f) GameState.dir = if (across > 0f) 1f else -1f
        derive(force = false)
        door = doors.at(wx, wz, hx, hz)
        yard = yardAt(wx, wz)
        GameState.surface = surfaceAt(wx, wz, yard)
        if (walked > 0f || turn != 0f) aim()
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
        // (by index: no iterator a frame)
        val all = yards.yards
        for (i in all.indices) { val y = all[i]; if (inside(y.parcel, px, pz)) return y }
        return null
    }

    /** What is underfoot at ([px], [pz]) in [y] (null: the street, pavement or plaza): the topmost walkable layer there. */
    fun surfaceAt(px: Float, pz: Float, y: TownYard?): Int {
        if (y == null) return Surface.HARD
        var best = Surface.GRASS
        val items = y.items
        for (k in items.indices) {
            val it = items[k]
            if (!inside(it.rect, px, pz)) continue
            when (it.kind) {
                "deck", "porch", "boardwalk", "dock" -> return Surface.WOOD
                "frontPath", "driveway", "patio", "parkingPad", "kerbCut", "path", "terrace" -> best = Surface.HARD
                "gravel" -> if (best == Surface.GRASS) best = Surface.GRAVEL
            }
        }
        return best
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
