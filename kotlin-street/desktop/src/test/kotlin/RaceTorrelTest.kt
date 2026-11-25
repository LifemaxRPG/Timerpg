import com.lifemax.rpg.feature.habits.physics.VehicleDesc
import com.lifemax.rpg.feature.habits.vehicle.CarPicker
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The race Torrel (C046, docs/CARS.md) as the game reads it: its model-info.json parses into a race spec (its own engine,
 * gears, suspension, grip and downforce), clearly ahead of the road Torrel; the road cars' physics descriptions are what
 * they were (no downforce, the same numbers); the car button's list (CarPicker) offers every car and remembers the pick.
 */
class RaceTorrelTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private fun spec(k: String) = VehicleSpec.parse(File(assets, "$k/model-info.json").readText())
    private val rs = spec("torrel-1988-rs")
    private val road = spec("torrel-1988")

    @Test fun theRaceTorrelParses() {
        assertEquals("torrel-1988-rs", rs.key)
        assertEquals("Torrel 1988 RS", rs.label)
        assertEquals(1988, rs.from)
        assertNotNull(rs.race); val r = rs.race!!
        assertNull("the road car has no race setup", road.race)
        // its files, nodes and lamps: the same set the game drives on the road car
        assertEquals("torrel-1988-rs.glb", rs.near); assertEquals("torrel-1988-rs-mid.glb", rs.mid); assertEquals("torrel-1988-rs-far.glb", rs.far)
        for (f in listOf(rs.near, rs.mid!!, rs.far!!)) assertTrue(f, File(assets, "torrel-1988-rs/$f").isFile)
        assertEquals(road.nodes.keys, rs.nodes.keys)
        assertEquals(road.lamps.mapValues { it.value.size }, rs.lamps.mapValues { it.value.size })
        // the wider track, the lower axle, the wing and the arches in the collision boxes
        assertTrue(rs.halfTrack > road.halfTrack + .05f)
        assertTrue(rs.axleY < road.axleY)
        assertEquals(7, rs.boxes.size / 6)
        val top = (0 until rs.boxes.size / 6).maxOf { rs.boxes[it * 6 + 1] + rs.boxes[it * 6 + 4] / 2 }
        assertTrue("the wing's box is above the roof ($top)", top > 1.2f)
        assertTrue("wider than the road car (the arches)", rs.footprint[1] > road.footprint[1] + .08f)
        // the two liveries, each named by its body colour, which is one of the car's colours
        assertEquals(listOf("a", "b"), rs.liveries.map { it.id })
        for (l in rs.liveries) { assertTrue(l.colour in rs.colours); assertEquals(l.id, rs.liveryFor(l.colour)?.id) }
        assertNull("plain paint wears no livery", rs.liveryFor(VehicleSpec.colour("#b84b47")))
        // race numbers: lighter, faster, more grip, stiffer and lower, downforce
        assertTrue(rs.massKg < road.massKg - 80f)
        assertTrue(rs.topSpeedKmh >= road.topSpeedKmh * 1.2f && rs.topSpeedKmh <= road.topSpeedKmh * 1.3f)
        assertEquals(6, r.gears.size)
        assertEquals(2600f, r.downforceAt200, 1f)
    }

    @Test fun itsPhysicsIsARaceCars() {
        val d = rs.desc(); val o = road.desc()
        assertEquals(VehicleDesc.COUNT, d.size)
        assertTrue("more grip", d[VehicleDesc.FRICTION_LAT] > o[VehicleDesc.FRICTION_LAT] * 1.2f)
        assertTrue("more torque", d[VehicleDesc.ENGINE_TORQUE] > o[VehicleDesc.ENGINE_TORQUE])
        assertTrue("stiffer springs", d[VehicleDesc.SUSP_FREQ] > o[VehicleDesc.SUSP_FREQ])
        assertTrue("less travel", d[VehicleDesc.SUSP_MAX] < o[VehicleDesc.SUSP_MAX])
        assertTrue("a lower centre of mass", d[VehicleDesc.COM_Y] < o[VehicleDesc.COM_Y])
        assertTrue("wider tyres", d[VehicleDesc.WHEEL_WIDTH] > o[VehicleDesc.WHEEL_WIDTH])
        // downforce: 2,600 N at 200 km/h, k v^2, 40% on the front axle (38% before the 2026-11-25 balance work)
        val v200 = 200f / 3.6f
        assertEquals(2600f, d[VehicleDesc.DOWNFORCE] * v200 * v200, 1f)
        assertEquals(650f, d[VehicleDesc.DOWNFORCE] * (v200 / 2) * (v200 / 2), 1f)
        assertEquals(.40f, d[VehicleDesc.DOWNFORCE_FRONT], 1e-4f)
        // the cornering balance (docs/CARS.md): lateral grip per axle, the fronts holding their peak (CorneringBalanceTest)
        assertEquals(1.52f, d[VehicleDesc.FRICTION_LAT], 1e-4f); assertEquals(1.48f, d[VehicleDesc.FRICTION_LAT_REAR], 1e-4f)
        assertEquals(1f, d[VehicleDesc.FRONT_LAT_TAIL], 1e-4f)
        assertEquals(1.34f, d[VehicleDesc.FRICTION_LONG], 1e-4f)
        // its final drive: 8,200 rpm in sixth at 278 km/h (as model-info.json suggests)
        assertEquals(3.63f, rs.drivetrain.finalDrive(rs), .01f)
        assertEquals(6f, d[VehicleDesc.GEARS])
    }

    @Test fun theRoadCarsAreUnchanged() {
        // every road car: no downforce, its tyre width the old estimate from the radius
        for (k in VehicleSpec.V1) {
            val s = spec(k); val d = s.desc()
            assertEquals(k, 0f, d[VehicleDesc.DOWNFORCE])
            assertEquals(k, (s.wheelRadius * .55f).coerceIn(.14f, .26f), d[VehicleDesc.WHEEL_WIDTH])
            // (the same lateral grip front and rear, Jolt's own curve, the road cars' steering fade)
            assertEquals(k, d[VehicleDesc.FRICTION_LAT], d[VehicleDesc.FRICTION_LAT_REAR]); assertEquals(k, 0f, d[VehicleDesc.FRONT_LAT_TAIL])
            assertEquals(k, com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.STEER_FADE, com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.steerFadeOf(s))
        }
        // the road Torrel's numbers as they were before the race build (VehicleSpec.Drivetrain, the 1988 Torrel's line)
        val d = road.desc()
        val want = mapOf(VehicleDesc.MASS to 1088f, VehicleDesc.WHEEL_RADIUS to .311f, VehicleDesc.WHEEL_X to .765f, VehicleDesc.WHEEL_Y to .323f,
            VehicleDesc.FRONT_Z to 1.18f, VehicleDesc.REAR_Z to -1.18f, VehicleDesc.SUSP_MIN to .05f, VehicleDesc.SUSP_MAX to .22f, VehicleDesc.SUSP_FREQ to 2f,
            VehicleDesc.SUSP_DAMP to .52f, VehicleDesc.ENGINE_TORQUE to 260f, VehicleDesc.ENGINE_MIN_RPM to 800f, VehicleDesc.ENGINE_MAX_RPM to 7000f,
            VehicleDesc.DIFF_RATIO to 3.6639f, VehicleDesc.BRAKE_TORQUE to 1700f, VehicleDesc.HANDBRAKE_TORQUE to 2200f, VehicleDesc.GEARS to 5f,
            VehicleDesc.GEAR1 to 3.3f, VehicleDesc.LINEAR_DAMPING to .018f, VehicleDesc.COM_Y to .42f, VehicleDesc.FRICTION_LONG to 1.05f, VehicleDesc.FRICTION_LAT to 1.05f,
            VehicleDesc.FRICTION_LAT_REAR to 1.05f, VehicleDesc.FRONT_LAT_TAIL to 0f)
        for ((i, v) in want) assertEquals("desc[$i]", v, d[i], 1e-3f)
        // and the sedan's
        val n = spec("northcairn-1956").desc()
        assertEquals(.92f, n[VehicleDesc.FRICTION_LAT], 1e-4f); assertEquals(420f, n[VehicleDesc.ENGINE_TORQUE]); assertEquals(0f, n[VehicleDesc.DOWNFORCE])
    }

    @Test fun theCarButtonsList() {
        val all = VehicleSpec.CATALOGUE.map { spec(it) }
        // every car, in the catalogue's order, by its short name
        val p = CarPicker.of(all, null)
        assertEquals(listOf("Alderwick 1928", "Fenmere 1938", "Northcairn 1956", "Rowanvale 1970", "Tallmere 1972", "Torrel 1968", "Torrel 1988", "Torrel 1988 RS"),
            p.entries.map { it.label })
        assertTrue(p.entries.none { it.last })
        // "same again" with nothing picked: the first car
        assertEquals("Same again: Alderwick 1928", p.againLabel)
        // a pick is remembered and marked; same again brings it back
        assertEquals("torrel-1988-rs", p.pick("torrel-1988-rs"))
        assertEquals("torrel-1988-rs", p.last)
        assertEquals(listOf("torrel-1988-rs"), p.entries.filter { it.last }.map { it.key })
        assertEquals("torrel-1988-rs", p.again())
        assertEquals("Same again: Torrel 1988 RS", p.againLabel)
        // an unknown pick changes nothing
        assertNull(p.pick("nosuch-2001")); assertEquals("torrel-1988-rs", p.last)
        // the remembered choice comes back with the next game's list; one the list no longer has is forgotten
        assertEquals("torrel-1968", CarPicker.of(all, "torrel-1968").again())
        assertNull(CarPicker.of(all, "gone-1999").last)
        // a world that loaded fewer cars lists only those, still in the catalogue's order
        assertEquals(listOf("northcairn-1956", "torrel-1988-rs"), CarPicker(listOf("torrel-1988-rs", "northcairn-1956")).keys)
        assertNull(CarPicker(emptyList()).again())
    }
}
