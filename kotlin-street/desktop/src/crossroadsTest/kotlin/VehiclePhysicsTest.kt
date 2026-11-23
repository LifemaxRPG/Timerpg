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
 * :desktop:physicsHost): each of the catalogue's cars runs up to its era's top speed on flat ground and no further, stops under
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
        // (the catalogue's cars: VehicleSpec.V1, every one parsed)
        assertEquals(VehicleSpec.V1.size, specs.size)
        assertEquals(7, specs.size)
        for (s in specs) {
            assertTrue("${s.key}: wheels", listOf("wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr", "steer_fl", "steer_fr").all { s.node(it) != null })
            // (a four-door's driver's door is door_front_l, a two-seater's door_l: VehicleSpec.driverDoor)
            assertTrue("${s.key}: a driver's door, seat and exit", s.driverDoor?.endsWith("_l") == true && s.node(s.driverDoor!!) != null && s.node("seat_driver") != null && s.node("exit_left") != null)
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
        // 2025 (the capital's year): every car of the catalogue, as classics; the 1956 sedan first, on Main Street by the spawn
        val cars = vw.park(town, yards)
        assertEquals(specs.size, cars.size)
        assertTrue(cars.all { it.classic })
        val first = cars.first()
        assertTrue(first.spec.key.startsWith("northcairn"))
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        for (c in cars) println("parked 2025: ${c.spec.key} at ${c.spot} (${c.x.toInt()}, ${c.z.toInt()}), ${"%.1f".format(hypot(c.x - sp.x, c.z - sp.z) / VehicleWorld.U)} m from the spawn (${sp.x.toInt()}, ${sp.z.toInt()}), facing (${"%.2f".format(c.fx)}, ${"%.2f".format(c.fz)})")
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

    /**
     * Whether anything solid in [col] (the walk's whole map: buildings, fences, props, the parked cars) stands within the
     * rectangle centred at ([x], [z]), [hl] along ([fx], [fz]) and [hw] across: sampled every 8 cm with a 6 cm circle,
     * independently of [VehicleSpots.clear].
     */
    private fun overlapsSomething(col: TownCollision, x: Float, z: Float, fx: Float, fz: Float, hl: Float, hw: Float): Boolean {
        val u = VehicleWorld.U
        val step = .08f * u
        val lx = fz; val lz = -fx
        var a = -hl
        while (a <= hl) {
            var b = -hw
            while (b <= hw) {
                if (col.blocked(x + fx * a + lx * b, z + fz * a + lz * b, .06f * u)) return true
                b += step
            }
            a += step
        }
        return false
    }

    @Test fun theTestButtonsSpawnedCarNeverOverlapsAnything() {
        val col = collision()
        val vw = VehicleWorld(world(), col, specs)
        vw.park(town, yards)
        val u = VehicleWorld.U
        // where the player might stand: the spawn, and a spread of clear points along the town's streets, from the
        // pavement to the yards behind (by seed)
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        val places = arrayListOf(sp.x to sp.z)
        val rnd = java.util.Random(7)
        while (places.size < 60) {
            val r = town.rows[rnd.nextInt(town.rows.size)]
            val p = r.pointAt(rnd.nextFloat() * r.length, -40f + rnd.nextFloat() * 500f)
            if (!col.blocked(p.x, p.z, .5f * u)) places += p.x to p.z
        }
        var spawned = 0
        val names = HashSet<String>()
        for ((k, p) in places.withIndex()) {
            val (x, z) = p
            // the spot it would use, checked against the town before the car is in it
            val next = specs[k % specs.size]
            val spot = vw.spawnSpot(town, next, x, z, yards)
            val car = vw.spawnNear(x, z)
            if (spot == null) { assertTrue("no car where no spot (${vw.spawnNote})", car == null); continue }
            assertNotNull(car); car!!
            spawned++; names += car.spec.key
            assertEquals("the next of the catalogue", next.key, car.spec.key)
            assertEquals(vw.spawnNote, car.spec.name)
            // facing along the street (the spot's way) and standing where the spot is
            assertTrue("faces along the kerb", car.fx * spot.fx + car.fz * spot.fz > .99f)
            val f = car.spec.footprint
            val cx = car.x + car.fx * f[2] * u; val cz = car.z + car.fz * f[2] * u
            assertTrue("stands on its spot", hypot(cx - spot.x, cz - spot.z) < .1f * u)
            // beside the player, not on them
            assertTrue("near the player (${hypot(cx - x, cz - z) / u} m)", hypot(cx - x, cz - z) < VehicleWorld.SPAWN_REACH_M * u)
            assertTrue("not on the player", VehicleSpots.rectDistance(cx, cz, car.fx, car.fz, f[0] * u, f[1] * u, x, z) > .5f * u)
            // the car's own footprint is in the map now: take it out to look under it, then put it back
            col.setExtra(VehicleWorld.KEY + car.spot, null)
            assertFalse("${car.spec.key} at ${car.spot} (player at ${x.toInt()}, ${z.toInt()}) overlaps something",
                overlapsSomething(col, cx, cz, car.fx, car.fz, f[0] * u, f[1] * u))
            col.setExtra(VehicleWorld.KEY + car.spot, floatArrayOf(cx, cz, car.fx, car.fz, f[0] * u, f[1] * u), com.lifemax.rpg.feature.habits.domain.SolidKind.PROP, VehicleWorld.KEY + car.spot)
            // one spawned car at a time: the parked ones (the whole catalogue) and this
            assertEquals(specs.size + 1, vw.cars.size)
            // and it sits still on its springs
            assertTrue("upright on the ground (y ${car.pose.y})", car.pose.y in -2f..8f)
        }
        assertTrue("spawned at most places ($spawned of ${places.size})", spawned > places.size * 2 / 3)
        assertEquals("every car of the catalogue cycled", specs.size, names.size)
        // at the spawn, on Main Street, the car is right beside the player
        val walk = FakeWalk().also { it.wx = sp.x; it.wz = sp.z }
        vw.requestSpawn()
        vw.step(dt, walk, 0f, 0f)
        val c = vw.spawned!!
        println("spawned at the start: ${c.spec.name} at ${c.spot}, ${hypot(c.x - sp.x, c.z - sp.z) / u} m from the player")
        assertTrue("beside the player at the start", hypot(c.x - sp.x, c.z - sp.z) < 12f * u)
        vw.close()
    }

    @Test fun theFirstCarComesToAPlayerWhoStartsFarFromIt() {
        val col = collision()
        val vw = VehicleWorld(world(), col, specs)
        vw.park(town, yards)
        val u = VehicleWorld.U
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        // at the spawn it stays where it is, on Main Street beside the player
        assertTrue(vw.bringFirstNear(sp.x, sp.z) == null)
        // a game restored on another street, far away: the sedan comes to the kerb beside the player, still first
        val far = town.rows.map { it.pointAt(it.length / 2f, 60f) }.filter { hypot(it.x - sp.x, it.z - sp.z) > 400f * u && !col.blocked(it.x, it.z, .5f * u) }.first()
        val moved = vw.bringFirstNear(far.x, far.z)
        assertNotNull(moved); moved!!
        assertTrue(moved.spec.key.startsWith("northcairn"))
        assertTrue(vw.cars.first() === moved)
        assertEquals(specs.size, vw.cars.size)
        val d = hypot(moved.x - far.x, moved.z - far.z) / u
        println("the sedan brought to a player restored ${hypot(far.x - sp.x, far.z - sp.z) / u} m from the spawn: now $d m from them at ${moved.spot}")
        assertTrue("beside the player ($d m)", d < 40f)
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

    // ------------------------------------------------------------------ the safety net and the ground beyond the town

    /** A town-less world: a car of [key] on a flat box 1,000 street units (47 m) each way, [ground] beyond it, driven. */
    private fun openWorld(key: String, ground: ((Float, Float) -> Float?)?, ph: Physics = world()): Triple<VehicleWorld, VehicleWorld.Car, FakeWalk> {
        val vw = VehicleWorld(ph, null, specs, ground, floatArrayOf(-1000f, -1000f, 1000f, 1000f))
        val car = vw.add(spec(key), 0, false, VehicleSpots.Spot("t", 0f, 0f, 0f, 1f, 8f * VehicleWorld.U))
        val walk = FakeWalk()
        assertTrue(vw.enter(car, walk))
        repeat(80) { vw.step(dt, walk, 0f, 0f) }
        return Triple(vw, car, walk)
    }

    @Test fun aCarDrivenOffTheGroundWithNoTerrainIsPutBack() {
        val (vw, car, walk) = openWorld("tallmere", null)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        var t = 0f; var minY = 0f
        // full throttle straight on: off the end of the flat box (z 47 m) into nothing
        while (t < 14f && events.isEmpty()) { vw.step(dt, walk, 0f, -1f); minY = min(minY, car.pose.y / VehicleWorld.U); t += dt }
        assertTrue("it was rescued (${events})", events.isNotEmpty() && events[0].startsWith("car: rescued from"))
        // (put back still)
        assertTrue("at rest when put back (${car.forwardSpeed})", abs(car.forwardSpeed) < 1f)
        // let go: it stays on the box
        repeat(180) { vw.step(dt, walk, 0f, 0f) }
        val zm = car.z / VehicleWorld.U
        println("fell off the end at full throttle: ${events[0]}; at rest at z %.1f m, y %.2f m, lowest %.1f m".format(zm, car.pose.y / VehicleWorld.U, minY))
        assertTrue("near its last good spot, on the box (z $zm)", zm in 5f..47f)
        assertTrue("on the ground (y ${car.pose.y / VehicleWorld.U} m)", car.pose.y / VehicleWorld.U in -.5f..2f)
        assertTrue("rescued once, not over and over (${events.size})", events.size == 1)
        // a player's reset does the same on demand
        repeat(120) { vw.step(dt, walk, 0f, -1f) }
        vw.resetDriven()
        vw.step(dt, walk, 0f, 0f)
        assertTrue("reset logged", events.last().contains("player's reset"))
        assertTrue("at rest after the reset", abs(car.forwardSpeed) < 1f)
        vw.close()
    }

    @Test fun withTerrainBeyondTheTownTheCarStaysOnItAtASaneSpeed() {
        // gentle ground from the box's edge (z 47 m): 4% up, a swell across
        val ground: (Float, Float) -> Float? = { x, z -> .04f * max(0f, z - 47f) + if (z > 47f) .3f * kotlin.math.sin(x * .1f) * min(1f, (z - 47f) / 20f) else 0f }
        val (vw, car, walk) = openWorld("tallmere", ground)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        var t = 0f; var top = 0f; var worst = 0f
        while (t < 20f) {
            vw.step(dt, walk, 0f, -.6f)
            val zm = car.z / VehicleWorld.U; val xm = car.x / VehicleWorld.U
            if (zm > 52f) worst = max(worst, abs(car.pose.y / VehicleWorld.U - ground(xm, zm)!!))
            top = max(top, abs(car.forwardSpeed)); t += dt
        }
        val zm = car.z / VehicleWorld.U
        println("drove past the box's edge: z %.0f m, top %.1f m/s, off the terrain by at most %.2f m, events ${events}".format(zm, top, worst))
        assertTrue("past the edge (z $zm)", zm > 80f)
        assertTrue("no rescue was needed: $events", events.isEmpty())
        assertTrue("a sane speed ($top m/s)", top < 45f)
        assertTrue("on the terrain, not above or under it ($worst m)", worst < 1.2f)
        vw.close()
    }

    @Test fun aStateThatIsNotANumberIsPutBack() {
        class Poisoned(val p: Physics) : Physics by p { var poison = 0
            override fun state(v: Int, out: FloatArray) { p.state(v, out); if (poison > 0) { poison--; out[VehicleState.X] = Float.NaN; out[VehicleState.VY] = Float.NaN } } }
        val ph = Poisoned(world())
        val (vw, car, walk) = openWorld("tallmere", null, ph)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        repeat(60) { vw.step(dt, walk, 0f, -.5f) }
        ph.poison = 1
        repeat(30) { vw.step(dt, walk, 0f, -.5f) }
        assertTrue("rescued from NaN (${events})", events.any { it.contains("not a number") })
        assertTrue("its place is a number again", car.x.isFinite() && car.z.isFinite() && car.pose.y.isFinite() && car.forwardSpeed.isFinite())
        vw.close()
    }
}
