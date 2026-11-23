import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.vehicle.VehicleEra
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpots
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The cars on Jolt (docs/VEHICLES.md V1), through the same JNI library the phone loads (built for this computer by
 * :desktop:physicsHost): each of the five cars runs up to its era's top speed on flat ground and no further, stops under
 * braking, turns at a sensible radius, stops against a wall, climbs a slope of heightfield ground, spins its wheels by the
 * distance it rolls; getting in and out works; on the shipped town the cars park where they should, block the walker
 * while parked and stop against the town's buildings when driven into them. Times are this build server's, not the S25's.
 */
class VehiclePhysicsTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val specs = VehicleSpec.V1.map { VehicleSpec.parse(File(assets, "$it/model-info.json").readText()) }
    private fun spec(k: String) = specs.first { it.key.startsWith(k) }
    private val dt = VehicleWorld.STEP

    private fun world(): Physics = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")

    /** A car of [s] alone on a big flat box, heading +z. */
    private class Rig(val ph: Physics, val car: Int) { val s = FloatArray(VehicleState.COUNT) }
    private fun rig(s: VehicleSpec, ground: Boolean = true): Rig {
        val ph = world()
        if (ground) ph.addBox(0f, -1f, 0f, 20000f, 1f, 20000f)
        val v = ph.addVehicle(s.desc(), s.boxes, 0f, .05f, 0f, 0f)
        assertTrue("vehicle added", v >= 0)
        return Rig(ph, v)
    }
    private fun Rig.run(seconds: Float, forward: Float, right: Float = 0f, brake: Float = 0f, hand: Float = 0f, each: (Rig) -> Unit = {}) {
        val n = (seconds / dt).toInt()
        for (i in 0 until n) { ph.input(car, forward, right, brake, hand); ph.step(dt); ph.state(car, s); each(this) }
    }
    private val Rig.v: Float get() = s[VehicleState.FORWARD_SPEED]

    @Test fun theFiveCarsParse() {
        assertEquals(5, specs.size)
        for (s in specs) {
            assertTrue("${s.key}: wheels", listOf("wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr", "steer_fl", "steer_fr").all { s.node(it) != null })
            assertTrue("${s.key}: a driver's door, seat and exit", s.node("door_front_l") != null && s.node("seat_driver") != null && s.node("exit_left") != null)
            assertTrue("${s.key}: chassis boxes", s.boxes.size >= 12)
            assertTrue("${s.key}: head and brake lamps", s.lamps["head"]?.isNotEmpty() == true && s.lamps["brake"]?.isNotEmpty() == true)
            assertTrue("${s.key}: paint colours", s.colours.isNotEmpty())
        }
        // the eras: the 1956 sedan is not about in 1950, a working car in 1960, a collector's classic by 2025
        val n = spec("northcairn")
        assertEquals(VehicleEra.NONE, n.era(1950)); assertEquals(VehicleEra.COMMON, n.era(1960)); assertEquals(VehicleEra.CLASSIC, n.era(2025))
    }

    @Test fun eachCarRunsUpToItsErasTopSpeedAndNoFurther() {
        val lines = ArrayList<String>()
        for (s in specs) {
            val r = rig(s)
            var top = 0f; var t60 = Float.NaN; var t = 0f
            r.run(120f, 1f) { top = max(top, it.v); t += dt; if (t60.isNaN() && it.v * 3.6f >= 60f) t60 = t }
            val want = s.topSpeedKmh
            lines += "%s: top %.0f km/h (era %.0f), 0-60 km/h %.1f s".format(s.key, top * 3.6f, want, t60)
            assertTrue("${s.key} reaches ${top * 3.6f} of $want km/h", top * 3.6f >= want * .9f)
            assertTrue("${s.key} passes its top speed: ${top * 3.6f} of $want km/h", top * 3.6f <= want * 1.04f)
            r.ph.close()
        }
        println(lines.joinToString("\n"))
    }

    @Test fun itStopsUnderBraking() {
        for (s in specs) {
            val r = rig(s)
            // from 50 km/h
            var guard = 0
            while (r.v * 3.6f < 50f && guard++ < 6000) r.run(dt, 1f)
            val z0 = r.s[VehicleState.Z]; val v0 = r.v
            var t = 0f
            while (r.v > .05f && t < 15f) { r.run(dt, 0f, brake = 1f); t += dt }
            val d = r.s[VehicleState.Z] - z0
            println("%s: braked from %.0f km/h in %.1f s, %.1f m".format(s.key, v0 * 3.6f, t, d))
            assertTrue("${s.key} stops (${r.v} m/s after $t s)", r.v <= .05f)
            assertTrue("${s.key} stops within 45 m from 50 km/h: $d m", d < 45f)
            r.ph.close()
        }
    }

    @Test fun itTurnsAtASensibleRadius() {
        for (s in specs) {
            val r = rig(s)
            r.run(4f, .35f)
            // full lock at a walking-to-jogging pace: the circle it draws
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            var speed = 0f
            r.run(4f, .25f, right = 1f)
            r.run(14f, .25f, right = 1f) {
                x0 = min(x0, it.s[VehicleState.X]); x1 = max(x1, it.s[VehicleState.X]); z0 = min(z0, it.s[VehicleState.Z]); z1 = max(z1, it.s[VehicleState.Z]); speed = it.v
            }
            val radius = ((x1 - x0) + (z1 - z0)) / 4f
            println("%s: full lock at %.0f km/h, radius %.1f m".format(s.key, speed * 3.6f, radius))
            assertTrue("${s.key} turns round at full lock ($radius m)", radius in 3.5f..10f)
            // right is right: the car's right is -x (its +x is its left)
            assertTrue("${s.key} turned right", x0 < -1f)
            r.ph.close()
        }
    }

    @Test fun itStopsAgainstAWall() {
        val s = spec("northcairn")
        val r = rig(s)
        // a wall across the road 40 m ahead, a building's 8 m tall
        r.ph.addBox(0f, 4f, 40f, 20f, 4f, .5f)
        var maxNose = 0f
        r.run(12f, 1f) { maxNose = max(maxNose, it.s[VehicleState.Z] + s.footprint[0] + s.footprint[2]) }
        assertTrue("the car's nose stays out of the wall ($maxNose m)", maxNose < 39.5f + .3f)
        r.run(2f, 0f, brake = 1f)
        assertTrue("it is stopped at the wall (${r.v})", abs(r.v) < .5f)
        assertTrue("still upright", r.s[VehicleState.Y] in -.2f..1f)
        r.ph.close()
    }

    @Test fun itReverses() {
        val s = spec("rowanvale")
        val r = rig(s)
        r.run(4f, -1f)
        assertTrue("reverse gear backs it up (${r.v} m/s, z ${r.s[VehicleState.Z]})", r.v < -1f && r.s[VehicleState.Z] < -2f)
        assertEquals(-1f, r.s[VehicleState.GEAR], .01f)
        r.ph.close()
    }

    @Test fun wheelsSpinByTheDistanceRolled() {
        val s = spec("fenmere")
        val r = rig(s)
        r.run(3f, .3f)
        val z0 = r.s[VehicleState.Z]
        // coasting (no torque at the wheels: no slip); Jolt keeps each angle within a turn, so it is unwrapped step by step
        val fi = VehicleState.WHEELS + VehicleState.W_ANGLE; val ri = VehicleState.WHEELS + 2 * VehicleState.W_COUNT + VehicleState.W_ANGLE
        var lf = r.s[fi]; var lr = r.s[ri]; var front = 0f; var rear = 0f
        fun unwrap(d: Float) = if (d > Math.PI) d - 2f * Math.PI.toFloat() else if (d < -Math.PI) d + 2f * Math.PI.toFloat() else d
        r.run(2f, 0f) { front += unwrap(it.s[fi] - lf); rear += unwrap(it.s[ri] - lr); lf = it.s[fi]; lr = it.s[ri] }
        val rolled = r.s[VehicleState.Z] - z0
        val want = rolled / s.wheelRadius
        println("fenmere: rolled %.2f m, front wheel %.2f rad, rear %.2f rad, distance / radius %.2f".format(rolled, front, rear, want))
        assertTrue("it rolled ($rolled m)", rolled > 3f)
        assertEquals("the front wheel turns by distance / radius", want, front, want * .05f)
        assertEquals("the rear wheel too", want, rear, want * .05f)
        r.ph.close()
    }

    @Test fun itClimbsHeightfieldGround() {
        val s = spec("tallmere")
        val ph = world()
        // ground rising 6% to the north (+z) from z 10 m, 129 samples a side 1 m apart
        val n = 129
        val h = FloatArray(n * n) { i -> val z = i / n; ((z - 10).coerceAtLeast(0) * .06f) }
        assertTrue(ph.addHeightfield(-64f, -5f, n, 1f, h) >= 0)
        val v = ph.addVehicle(s.desc(), s.boxes, 0f, .1f, 0f, 0f)
        val st = FloatArray(VehicleState.COUNT)
        // (up to 90 m along: the field ends at 124 m)
        var g = 0
        while (st[VehicleState.Z] < 90f && g++ < (40f / dt).toInt()) { ph.input(v, .6f, 0f, 0f, 0f); ph.step(dt); ph.state(v, st) }
        val z = st[VehicleState.Z]; val y = st[VehicleState.Y]
        println("tallmere on the slope: at z %.0f m, y %.2f m (ground %.2f)".format(z, y, (z + 5f - 10f) * .06f))
        assertTrue("it drove up the slope (z $z)", z > 60f)
        assertEquals("on the ground, not under it or flying", ((z + 5f) - 10f) * .06f, y, .4f)
        ph.close()
    }

    // ------------------------------------------------------------------ the town

    private val townDir = File("../app/src/main/assets/town/gamma")
    private val root by lazy { JSONObject(File(townDir, "street.json").readText()) }
    private val town by lazy { Town.parse(root)!! }
    private val yards by lazy { TownYards.parse(root) }
    private fun collision() = TownCollision.build(town, yards, ModelShape.parseAll(File(townDir, "buildings/models.json").readText()),
        CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet())

    /** The walk as the game hands it to the cars. */
    private class FakeWalk : VehicleWorld.Carried {
        override var wx = 0f; override var wz = 0f
        var carried = false; var hx = 0f; var hz = 1f; var released = 0
        override fun carry(x: Float, z: Float, dx: Float, dz: Float, speed: Float) { carried = true; wx = x; wz = z; hx = dx; hz = dz }
        override fun release(x: Float, z: Float, dx: Float, dz: Float) { carried = false; wx = x; wz = z; released++ }
    }

    @Test fun theCarsParkInTheCapitalByTheYear() {
        val col = collision()
        val vw = VehicleWorld(world(), col, specs)
        // 2025 (the capital's year): all five, as classics; the 1956 sedan first, on Main Street by the spawn
        val cars = vw.park(town, yards)
        assertEquals(5, cars.size)
        assertTrue(cars.all { it.classic })
        val first = cars.first()
        assertTrue(first.spec.key.startsWith("northcairn"))
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        assertTrue("the first car is near the spawn (${hypot(first.x - sp.x, first.z - sp.z) / VehicleWorld.U} m)", hypot(first.x - sp.x, first.z - sp.z) < 40f * VehicleWorld.U)
        assertTrue("driveways used", cars.any { it.spot.startsWith("drive:") })
        // the walker walks round a parked car: its middle is solid
        for (c in cars) assertTrue("${c.spec.key} blocks the walker", col.blocked(c.x + c.fx * c.spec.footprint[2] * VehicleWorld.U, c.z + c.fz * c.spec.footprint[2] * VehicleWorld.U, 4f))
        // and they stood still on their springs (no drift, no fall)
        val walk = FakeWalk().also { it.wx = -1e6f }
        val before = cars.map { it.x to it.z }
        repeat(120) { vw.step(dt, walk, 0f, 0f) }
        for ((c, b) in cars.zip(before)) assertTrue("${c.spec.key} stays put", hypot(c.x - b.first, c.z - b.second) < .05f * VehicleWorld.U && c.pose.y in -2f..8f)
        vw.close()
        // 1950: only the 1928 and the 1938 cars are about, as working cars
        val vw2 = VehicleWorld(world(), collision(), specs)
        val old = vw2.park(town, yards, 1950)
        assertEquals(setOf("alderwick-1928", "fenmere-1938"), old.map { it.spec.key }.toSet())
        assertFalse(old.any { it.classic })
        vw2.close()
        // 1920: none
        val vw3 = VehicleWorld(world(), collision(), specs)
        assertTrue(vw3.park(town, yards, 1920).isEmpty())
        vw3.close()
    }

    @Test fun getInDriveAndGetOut() {
        val col = collision()
        val vw = VehicleWorld(world(), col, specs)
        val car = vw.park(town, yards).first()
        val walk = FakeWalk()
        // standing by the driver's door (exit_left) the car can be entered; far away it cannot
        val p = FloatArray(2)
        val ex = car.spec.node("exit_left")!!
        car.world(ex.x, ex.z, p)
        walk.wx = p[0] + 5f; walk.wz = p[1]
        vw.step(dt, walk, 0f, 0f)
        assertTrue("Enter offered at the door", vw.enterable === car)
        walk.wx = p[0] + 300f
        vw.step(dt, walk, 0f, 0f)
        assertTrue("not from across the street", vw.enterable == null)
        walk.wx = p[0]; vw.step(dt, walk, 0f, 0f)
        assertTrue(vw.enter(car, walk))
        assertTrue(walk.carried)
        // the door opens, then shuts; then the drive
        var opened = 0f
        var t = 0f
        while (!vw.driving && t < 3f) { vw.step(dt, walk, 0f, 0f); opened = max(opened, car.doorOpen[car.spec.doors.indexOf("door_front_l")]); t += dt }
        assertTrue("driving after getting in", vw.driving)
        assertTrue("the driver's door swung open ($opened)", opened > .9f)
        repeat(30) { vw.step(dt, walk, 0f, 0f) }
        assertEquals("and shut again", 0f, car.doorOpen[car.spec.doors.indexOf("door_front_l")], .01f)
        // the parked car's footprint left the walk's map while it is driven
        assertFalse(col.blocked(car.x + car.fx * car.spec.footprint[2] * VehicleWorld.U, car.z + car.fz * car.spec.footprint[2] * VehicleWorld.U, 4f))
        // drive: the stick's up (pedal) for 3 s; the walk is carried along
        val x0 = car.x; val z0 = car.z
        repeat((3f / dt).toInt()) { vw.step(dt, walk, 0f, -1f) }
        val moved = hypot(car.x - x0, car.z - z0) / VehicleWorld.U
        assertTrue("it drove ($moved m)", moved > 5f)
        assertEquals("the walk is where the car is", car.pose.x, walk.wx, 1f)
        assertTrue("the town's shapes are streamed round it (${vw.staticCount})", vw.staticCount > 50)
        assertFalse("no getting out at speed", vw.exit())
        // brake to a stop, get out at exit_left
        var g = 0
        while (!vw.canExit && g++ < 600) vw.step(dt, walk, 0f, 1f)
        assertTrue(vw.exit())
        repeat((2f / dt).toInt()) { vw.step(dt, walk, 0f, 0f) }
        assertFalse(vw.driving)
        assertEquals(1, walk.released)
        car.world(ex.x, ex.z, p)
        // (the exit point as the car stands now: it may have settled a few centimetres on its springs since)
        assertTrue("stood at the driver's side (${hypot(walk.wx - p[0], walk.wz - p[1])} units off)", hypot(walk.wx - p[0], walk.wz - p[1]) < .3f * VehicleWorld.U)
        assertEquals("the door shut", 0f, car.doorOpen[car.spec.doors.indexOf("door_front_l")], .01f)
        assertTrue("parked again: it blocks the walker", col.blocked(car.x + car.fx * car.spec.footprint[2] * VehicleWorld.U, car.z + car.fz * car.spec.footprint[2] * VehicleWorld.U, 4f))
        vw.close()
    }

    @Test fun theOpenWalkIsCarriedAndStoodAtTheExit() {
        val col = collision()
        val ow = OpenWalk(town, col, yards)
        ow.spawn()
        GameState.moveX = 0f; GameState.moveLane = 0f
        val vw = VehicleWorld(world(), col, specs)
        val car = vw.park(town, yards).first()
        assertTrue(vw.enter(car, ow))
        assertTrue(ow.carried)
        repeat(90) { vw.step(dt, ow, 0f, 0f); ow.step(dt, 1f, 0f) }
        assertEquals("the walk does not walk while carried", car.pose.x, ow.wx, 1f)
        assertTrue(vw.exit())
        repeat(90) { vw.step(dt, ow, 0f, 0f); ow.step(dt, 0f, 0f) }
        assertFalse(ow.carried)
        assertTrue("stood where a walker can stand", ow.standable(ow.wx, ow.wz))
        vw.close()
    }

    @Test fun drivenIntoABuildingItStops() {
        val col = collision()
        val vw = VehicleWorld(world(), col, specs)
        val car = vw.park(town, yards).first()
        val walk = FakeWalk()
        vw.enter(car, walk)
        repeat(80) { vw.step(dt, walk, 0f, 0f) }
        // turn hard right off the street, full throttle: into the building line
        var t = 0f; var maxIn = 0
        val stepMs = ArrayList<Float>()
        while (t < 12f) {
            vw.step(dt, walk, if (t < 1.4f) 1f else 0f, -1f)
            stepMs += vw.stepMs
            t += dt
            val c = car
            // the car's middle never inside a building's shape
            if (col.blocked(c.x, c.z, 1f)) maxIn++
        }
        println("drove into the town for 12 s: %d streamed statics, step %.3f ms (this server), %d bodies; streaming: first %.2f ms, last %.2f ms".format(vw.staticCount, vw.stepMs, vw.physics.bodies, vw.streamMaxMs, vw.streamMs))
        assertTrue("its middle never went into a building or fence (${maxIn} frames)", maxIn < 3)
        vw.close()
    }

    @Test fun kerbsideSpotsLieOnTheRoad() {
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        val spots = VehicleSpots.street(town, sp.x, sp.z, collision())
        assertTrue(spots.size > 100)
        // the nearest is on Main Street, along it (east-west), within the roadway
        val s = spots.first()
        assertTrue("along Main Street", abs(s.fx) > .99f)
        assertEquals("at the kerb, inside the roadway", 9656.5f, s.z, VehicleSpots.HALF_ROADWAY)
        assertNotNull(VehicleSpots.driveways(yards, sp.x, sp.z, null).firstOrNull())
    }
}
