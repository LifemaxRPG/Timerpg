import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The race Torrel (C046, docs/CARS.md) on Jolt, through the JNI library the phone loads, against the road Torrel it was
 * built from: faster (top speed and 0-100), grippier through a fast bend (the tyres and the downforce, which presses the
 * car down harder the faster it goes), shorter stops; and it stays flat: no flip at full lock at speed, no bounce on
 * landing or at 250 km/h. The numbers it prints are this build server's, not the S25's.
 */
class RaceTorrelPhysicsTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val rs = VehicleSpec.parse(File(assets, "torrel-1988-rs/model-info.json").readText())
    private val road = VehicleSpec.parse(File(assets, "torrel-1988/model-info.json").readText())
    private val dt = VehicleWorld.STEP

    private class Rig(val ph: Physics, val car: Int) {
        val s = FloatArray(VehicleState.COUNT)
        var minUp = 1f
        val v: Float get() = s[VehicleState.FORWARD_SPEED]
        /** The body's up's height (1 upright, 0 on its side, -1 on its roof). */
        val up: Float get() = 1f - 2f * (s[VehicleState.QX] * s[VehicleState.QX] + s[VehicleState.QZ] * s[VehicleState.QZ])
        fun run(seconds: Float, forward: Float, right: Float = 0f, brake: Float = 0f, each: (Rig) -> Unit = {}) {
            for (i in 0 until (seconds / VehicleWorld.STEP).toInt()) {
                ph.input(car, forward, right, brake, 0f); ph.step(VehicleWorld.STEP); ph.state(car, s); minUp = min(minUp, up); each(this)
            }
        }
    }

    private fun rig(s: VehicleSpec, y: Float = .05f): Rig {
        val ph = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        ph.addBox(0f, -1f, 0f, 20000f, 1f, 20000f)
        val v = ph.addVehicle(s.desc(), s.boxes, 0f, y, 0f, 0f)
        assertTrue(v >= 0)
        return Rig(ph, v)
    }

    /** Top speed (km/h) after a minute flat out, and the time to 100 km/h (s). */
    private fun pace(s: VehicleSpec): Pair<Float, Float> {
        val r = rig(s)
        r.run(1f, 0f)
        var t = 0f; var t100 = Float.NaN; var top = 0f
        r.run(60f, 1f) { t += dt; if (t100.isNaN() && it.v * 3.6f >= 100f) t100 = t; top = max(top, it.v * 3.6f) }
        assertTrue("${s.key} stayed upright flat out (${r.minUp})", r.minUp > .97f)
        r.ph.close()
        return top to t100
    }

    @Test fun itIsFasterThanTheRoadCar() {
        val (rTop, r100) = pace(road)
        val (sTop, s100) = pace(rs)
        println("top speed: road %.0f km/h, race %.0f km/h; 0-100: road %.1f s, race %.1f s".format(rTop, sTop, r100, s100))
        assertTrue("the race car's top speed is 20-30%% over the road car's ($sTop vs $rTop)", sTop > rTop * 1.18f && sTop < rTop * 1.32f)
        assertTrue("near its model's 278 km/h ($sTop)", sTop in 255f..285f)
        assertTrue("it gets to 100 quicker ($s100 vs $r100)", s100 < r100 - .5f)
    }

    /** The most lateral acceleration (m/s^2) it holds through a bend taken at [kmh] with [right] steering, and whether it spun. */
    private fun bend(s: VehicleSpec, kmh: Float, right: Float): Triple<Float, Float, Boolean> {
        val r = rig(s)
        var g = 0
        while (r.v * 3.6f < kmh && g++ < 60 * 40) r.run(dt, 1f)
        var best = 0f; var px = r.s[VehicleState.VX]; var pz = r.s[VehicleState.VZ]
        var spun = false
        r.run(3f, .55f, right = right) {
            // (the velocity's turn rate times the speed: the lateral acceleration it holds)
            val vx = it.s[VehicleState.VX]; val vz = it.s[VehicleState.VZ]
            val sp = hypot(vx, vz)
            val turn = abs(kotlin.math.atan2(px * vz - pz * vx, px * vx + pz * vz)) / dt
            best = max(best, turn * sp)
            if (abs(it.v) < sp * .7f) spun = true
            px = vx; pz = vz
        }
        val up = r.minUp
        r.ph.close()
        return Triple(best, up, spun)
    }

    @Test fun itGripsHarderThroughAFastBendAndStaysFlat() {
        for (kmh in listOf(90f, 150f)) {
            val (ra, rUp, rSpun) = bend(road, kmh, .5f)
            val (sa, sUp, sSpun) = bend(rs, kmh, .5f)
            println("bend at %.0f km/h, half lock: road %.1f m/s^2 (up %.2f%s), race %.1f m/s^2 (up %.2f%s)".format(
                kmh, ra, rUp, if (rSpun) ", spun" else "", sa, sUp, if (sSpun) ", spun" else ""))
            assertTrue("the race car holds more lateral grip at $kmh km/h ($sa vs $ra)", sa > ra * 1.1f)
            assertTrue("no roll-over at $kmh ($sUp)", sUp > .85f)
            assertTrue("it does not spin at $kmh", !sSpun)
        }
        // full lock at 120 km/h: whatever it does, it stays on its wheels
        val (_, up, _) = bend(rs, 120f, 1f)
        assertTrue("full lock at 120 km/h keeps it on its wheels ($up)", up > .7f)
    }

    @Test fun theWingPressesItDownAtSpeed() {
        val r = rig(rs)
        r.run(2f, 0f)
        fun susp() = (0 until 4).map { r.s[VehicleState.WHEELS + it * VehicleState.W_COUNT + VehicleState.W_SUSPENSION] }.average().toFloat()
        val rest = susp()
        var g = 0
        while (r.v * 3.6f < 230f && g++ < 60 * 60) r.run(dt, 1f)
        // steady at speed: the suspension squeezed by the downforce, and no bouncing (the body's vertical speed small)
        var vy = 0f; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        r.run(3f, .9f) { vy = max(vy, abs(it.s[VehicleState.VY])); y0 = min(y0, it.s[VehicleState.Y]); y1 = max(y1, it.s[VehicleState.Y]) }
        val fast = susp()
        println("race Torrel: suspension %.3f m at rest, %.3f m at %.0f km/h; body bobs %.3f m, vertical speed <= %.2f m/s".format(rest, fast, r.v * 3.6f, y1 - y0, vy))
        assertTrue("pressed down at speed ($fast vs $rest)", fast < rest - .004f)
        assertTrue("no bounce at speed (${y1 - y0} m, $vy m/s)", y1 - y0 < .03f && vy < .25f)
        assertTrue("upright", r.minUp > .97f)
        r.ph.close()
    }

    @Test fun itSettlesWhenDroppedWithoutBouncing() {
        // put down from 30 cm (a spawn a little high): it lands, settles within a second and a half, does not hop
        val r = rig(rs, y = .3f)
        var leftGround = 0; var touched = false
        r.run(3f, 0f) {
            val contacts = (0 until 4).count { k -> it.s[VehicleState.WHEELS + k * VehicleState.W_COUNT + VehicleState.W_CONTACT] > .5f }
            if (contacts == 4) touched = true else if (touched && contacts == 0) leftGround++
        }
        val vy = abs(r.s[VehicleState.VY])
        println("race Torrel dropped 30 cm: wheels all off again %d steps after landing, vertical speed %.3f m/s at 3 s".format(leftGround, vy))
        assertTrue("it landed", touched)
        assertTrue("it did not hop back off the ground ($leftGround steps)", leftGround == 0)
        assertTrue("settled ($vy m/s)", vy < .02f)
        assertTrue("the body clears the ground (y ${r.s[VehicleState.Y]})", r.s[VehicleState.Y] > -.05f)
        r.ph.close()
    }

    @Test fun itStopsShorterThanTheRoadCar() {
        fun stop(s: VehicleSpec): Float {
            val r = rig(s)
            var g = 0
            while (r.v * 3.6f < 100f && g++ < 60 * 30) r.run(dt, 1f)
            val z0 = r.s[VehicleState.Z]
            var t = 0f
            while (r.v > .05f && t < 15f) { r.run(dt, 0f, brake = 1f); t += dt }
            val d = r.s[VehicleState.Z] - z0
            assertTrue("${s.key} braked straight (${r.minUp})", r.minUp > .95f)
            r.ph.close()
            return d
        }
        val dr = stop(road); val ds = stop(rs)
        println("100-0 km/h: road %.1f m, race %.1f m".format(dr, ds))
        assertTrue("the race car stops shorter ($ds vs $dr m)", ds < dr - 2f)
    }
}
