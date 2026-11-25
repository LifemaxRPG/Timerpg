import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The race Torrel's steady-state cornering balance on Jolt (the owner's 2026-11-25 note: "it still understeers; it should
 * take corners a little faster"): held at 60, 100 and 150 km/h on a skid pad while the steering is wound on step by step,
 * as the game's own steering (VehicleWorld: its slowdown at speed) lets a thumb wind it. Each step settles, then:
 * - the understeer gradient K (deg of front-wheel angle per g beyond the geometric L/R: + understeer, 0 neutral, - loose),
 * - the most lateral acceleration it holds (g) and whether the yaw rate still grows with more lock at the limit (a car
 *   that pushes stops turning tighter however much lock it gets: the front washes out first),
 * - the rear's slip angle at the limit (how much it rotates),
 * and the yaw response to a step of steering (time to 63% of the settled yaw rate, the overshoot), and it lifts off
 * mid-corner (no snap spin). Numbers are this build server's, not the S25's.
 */
class CorneringBalanceTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val rs = VehicleSpec.parse(File(assets, "torrel-1988-rs/model-info.json").readText())
    private val road = VehicleSpec.parse(File(assets, "torrel-1988/model-info.json").readText())
    private val dt = VehicleWorld.STEP

    private class Rig(val ph: Physics, val car: Int) {
        val s = FloatArray(VehicleState.COUNT)
        var minUp = 1f
        val v: Float get() = s[VehicleState.FORWARD_SPEED]
        val up: Float get() = 1f - 2f * (s[VehicleState.QX] * s[VehicleState.QX] + s[VehicleState.QZ] * s[VehicleState.QZ])
        /** The body's heading (rad) from its forward axis. */
        val heading: Float get() {
            val x = s[VehicleState.QX]; val y = s[VehicleState.QY]; val z = s[VehicleState.QZ]; val w = s[VehicleState.QW]
            val fx = 2f * (x * z + w * y); val fz = 1f - 2f * (x * x + y * y)
            return atan2(fx, fz)
        }
        val frontSteer: Float get() = .5f * (s[VehicleState.WHEELS + VehicleState.W_STEER] + s[VehicleState.WHEELS + VehicleState.W_COUNT + VehicleState.W_STEER])
    }

    private fun rig(s: VehicleSpec): Rig {
        val ph = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        ph.addBox(0f, -1f, 0f, 40000f, 1f, 40000f)
        val v = ph.addVehicle(s.desc(), s.boxes, 0f, .05f, 0f, 0f)
        assertTrue(v >= 0)
        return Rig(ph, v)
    }

    /** The game's steering at [v] m/s as VehicleWorld.drive turns a full thumb into the physics' input (dry). */
    private fun gameSteer(s: VehicleSpec, v: Float): Float {
        val fade = VehicleWorld.steerFadeOf(s)
        return 1f / (1f + (abs(v) / fade) * (abs(v) / fade))
    }

    class Point(val input: Float, val deltaDeg: Float, val ayG: Float, val yawRate: Float, val betaDeg: Float)
    class Pad(val kmh: Float, val points: List<Point>, val kDegPerG: Float, val maxG: Float, val pushes: Boolean, val spun: Boolean, val upMin: Float)

    /**
     * Held at [kmh] (a throttle loop), the steering wound on in steps up to the game's full thumb at that speed: the
     * settled points, the gradient K over the linear range (below 0.5 of the most g), the most g, whether the yaw rate
     * fell as the last steps added lock (pushing), whether it spun.
     */
    fun pad(s: VehicleSpec, kmh: Float): Pad {
        val r = rig(s)
        val target = kmh / 3.6f
        var g = 0
        while (r.v < target && g++ < 60 * 40) { r.ph.input(r.car, 1f, 0f, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s) }
        val wheelbase = s.frontZ - s.rearZ
        val full = gameSteer(s, target)
        val pts = ArrayList<Point>()
        var spun = false
        var throttle = .5f
        var steer = 0f
        val steps = 14
        for (i in 1..steps) {
            val input = full * i / steps
            var ay = 0f; var yr = 0f; var del = 0f; var beta = 0f; var n = 0
            var prevH = r.heading
            var prevV = atan2(r.s[VehicleState.VX], r.s[VehicleState.VZ])
            var t = 0f
            while (t < 3f) {
                // (the steering eased as the game eases it; the throttle holds the speed)
                steer += (input - steer) * (1f - kotlin.math.exp(-dt / VehicleWorld.STEER_TIME))
                val sp = hypot(r.s[VehicleState.VX], r.s[VehicleState.VZ])
                throttle = (throttle + (target - sp) * .08f).coerceIn(0f, 1f)
                r.ph.input(r.car, throttle, steer, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s)
                r.minUp = min(r.minUp, r.up)
                val h = r.heading
                var dh = h - prevH; if (dh > PI) dh -= 2f * PI.toFloat(); if (dh < -PI) dh += 2f * PI.toFloat()
                prevH = h
                t += dt
                if (abs(r.v) < sp * .7f) spun = true
                // (the lateral acceleration from the velocity's own turn: the path's, not the body's, which a slide skews)
                val hv = atan2(r.s[VehicleState.VX], r.s[VehicleState.VZ])
                var dv = hv - prevV; if (dv > PI) dv -= 2f * PI.toFloat(); if (dv < -PI) dv += 2f * PI.toFloat()
                prevV = hv
                if (t > 1.8f) {
                    val w = dh / dt
                    yr += w; ay += dv / dt * sp; del += r.frontSteer
                    // the body's sideslip (velocity against heading) and the rear axle's: beta - rear's lateral speed
                    val vx = r.s[VehicleState.VX]; val vz = r.s[VehicleState.VZ]
                    var b = atan2(vx, vz) - h; if (b > PI) b -= 2f * PI.toFloat(); if (b < -PI) b += 2f * PI.toFloat()
                    val betaRear = b - (s.rearZ * w) / sp.coerceAtLeast(1f) // (+ rear swings out: v_lat at the rear = v*b - w*rearZ)
                    beta += betaRear
                    n++
                }
            }
            pts += Point(input, abs(del / n) * 180f / PI.toFloat(), abs(ay / n) / 9.81f, abs(yr / n), abs(beta / n) * 180f / PI.toFloat())
            if (spun) break
        }
        val maxG = pts.maxOf { it.ayG }
        // K: least squares of (delta - L r / v) against ay over the linear range
        val lin = pts.filter { it.ayG < .5f * maxG && it.ayG > .02f }
        var sxy = 0.0; var sxx = 0.0
        for (p in lin) {
            val geo = wheelbase * p.yawRate / target * 180f / PI.toFloat()
            sxy += (p.deltaDeg - geo).toDouble() * p.ayG; sxx += (p.ayG * p.ayG).toDouble()
        }
        val k = if (sxx > 0) (sxy / sxx).toFloat() else Float.NaN
        val last = pts.takeLast(3)
        val pushes = last.size == 3 && last[2].yawRate <= last[0].yawRate * 1.01f
        val pad = Pad(kmh, pts, k, maxG, pushes, spun, r.minUp)
        r.ph.close()
        return pad
    }

    /** A step of steering at [kmh] to [input] (the pad's first, about half a g): the seconds to 63% of the settled yaw rate, the overshoot. */
    fun step(s: VehicleSpec, kmh: Float, input: Float): Pair<Float, Float> {
        val r = rig(s)
        val target = kmh / 3.6f
        var g = 0
        while (r.v < target && g++ < 60 * 40) { r.ph.input(r.car, 1f, 0f, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s) }
        var steer = 0f; var throttle = .5f
        var prevH = r.heading
        val rates = ArrayList<Float>()
        var t = 0f
        while (t < 3f) {
            steer += (input - steer) * (1f - kotlin.math.exp(-dt / VehicleWorld.STEER_TIME))
            val sp = hypot(r.s[VehicleState.VX], r.s[VehicleState.VZ])
            throttle = (throttle + (target - sp) * .08f).coerceIn(0f, 1f)
            r.ph.input(r.car, throttle, steer, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s)
            val h = r.heading
            var dh = h - prevH; if (dh > PI) dh -= 2f * PI.toFloat(); if (dh < -PI) dh += 2f * PI.toFloat()
            prevH = h; rates += abs(dh / dt); t += dt
        }
        r.ph.close()
        val settled = rates.takeLast(30).average().toFloat()
        val i63 = rates.indexOfFirst { it >= .63f * settled }
        val peak = rates.max()
        return i63 * dt to (peak / settled - 1f)
    }

    /** At the limit at [kmh], the throttle lifted at once: the most the yaw rate rises over its settled value (a snap). */
    fun liftOff(s: VehicleSpec, kmh: Float): Pair<Float, Boolean> {
        val r = rig(s)
        val target = kmh / 3.6f
        var g = 0
        while (r.v < target && g++ < 60 * 40) { r.ph.input(r.car, 1f, 0f, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s) }
        val input = gameSteer(s, target)
        var steer = 0f; var throttle = .5f
        var prevH = r.heading
        var t = 0f; var held = 0f; var n = 0; var peak = 0f; var spun = false; var slide = 0f
        while (t < 6f) {
            steer += (input - steer) * (1f - kotlin.math.exp(-dt / VehicleWorld.STEER_TIME))
            val sp = hypot(r.s[VehicleState.VX], r.s[VehicleState.VZ])
            if (t < 3.5f) throttle = (throttle + (target - sp) * .08f).coerceIn(0f, 1f) else throttle = 0f
            r.ph.input(r.car, throttle, steer, 0f, 0f); r.ph.step(dt); r.ph.state(r.car, r.s)
            r.minUp = min(r.minUp, r.up)
            val h = r.heading
            var dh = h - prevH; if (dh > PI) dh -= 2f * PI.toFloat(); if (dh < -PI) dh += 2f * PI.toFloat()
            prevH = h; t += dt
            val w = abs(dh / dt)
            if (t in 2.5f..3.5f) { held += w; n++ }
            if (t > 3.5f) {
                peak = max(peak, w)
                var b = atan2(r.s[VehicleState.VX], r.s[VehicleState.VZ]) - h; if (b > PI) b -= 2f * PI.toFloat(); if (b < -PI) b += 2f * PI.toFloat()
                slide = max(slide, abs(b) * 180f / PI.toFloat())
            }
            if (abs(r.v) < sp * .6f) spun = true
        }
        assertTrue("${s.key} upright after lifting at $kmh (${r.minUp})", r.minUp > .8f)
        r.ph.close()
        lastSlide = slide
        return (peak / (held / n) - 1f) to spun
    }
    /** The body's largest sideslip (deg) after the last [liftOff]. */
    var lastSlide = 0f

    private class Row(val pad: Pad, val snap: Float, val spunLifting: Boolean, val slide: Float)

    private fun report(name: String, s: VehicleSpec): List<Row> = listOf(60f, 100f, 150f).map { kmh ->
        val p = pad(s, kmh)
        val (t63, over) = step(s, p.kmh, p.points[0].input)
        val (snap, spun) = liftOff(s, p.kmh)
        val slide = lastSlide
        println("%s %3.0f km/h: K %+.2f deg/g, max %.2f g at %.1f deg lock, %.2f g at the full thumb (rear slip %.1f deg)%s%s; yaw 63%% in %.2f s, overshoot %.0f%%; lift-off yaw %+.0f%%, slide %.1f deg%s".format(
            name, p.kmh, p.kDegPerG, p.maxG, p.points.maxBy { it.ayG }.deltaDeg, p.points.last().ayG, p.points.maxBy { it.ayG }.betaDeg,
            if (p.pushes) ", pushes at the limit" else "", if (p.spun) ", SPUN" else "", t63, over * 100f, snap * 100f, slide, if (spun) ", SPUN on lift-off" else ""))
        println("   " + p.points.joinToString(" ") { "%.1f/%.2f".format(it.deltaDeg, it.ayG) })
        Row(p, snap, spun, slide)
    }

    /**
     * Measured 2026-11-25 on the build server (before: the race setup as delivered; after: lateral grip 1.52 front /
     * 1.48 rear, a flat front tail, 40% of the downforce on the front): the most g 1.23 / 1.33 / 1.38 -> 1.33 / 1.42 /
     * 1.49 at 60 / 100 / 150 km/h; at the game's full thumb 1.09 / 1.17 / 1.33 -> 1.23 / 1.33 / 1.44 (the front no longer
     * washes out when the thumb winds on more lock than it can use); K about 0 deg/g throughout; lifting off at the limit
     * slides the body at most ~10 degrees and it catches itself (the road car ~6.5).
     */
    @Test fun theRaceTorrelCornersNeutral() {
        val roadRows = report("road Torrel", road)
        val rsRows = report("race Torrel", rs)
        for ((i, row) in rsRows.withIndex()) {
            val p = row.pad
            assertTrue("no spin winding on lock at ${p.kmh}", !p.spun)
            assertTrue("upright at ${p.kmh} (${p.upMin})", p.upMin > .85f)
            assertTrue("more grip than the road car at ${p.kmh} (${p.maxG} vs ${roadRows[i].pad.maxG})", p.maxG > roadRows[i].pad.maxG * 1.15f)
            assertTrue("neutral in the linear range at ${p.kmh} (K ${p.kDegPerG} deg/g)", p.kDegPerG.isNaN() || p.kDegPerG in -.5f..+.3f)
            assertTrue("the front keeps biting at the full thumb at ${p.kmh} (${p.points.last().ayG} of ${p.maxG} g)", p.points.last().ayG > .88f * p.maxG)
            assertTrue("no snap spin lifting off at ${p.kmh} (slide ${row.slide} deg)", !row.spunLifting && row.slide < 15f)
        }
        // (a little faster than before, not a different car)
        assertTrue(rsRows[1].pad.maxG in 1.36f..1.6f && rsRows[2].pad.maxG in 1.42f..1.7f)
        assertTrue(rsRows[1].pad.points.last().ayG > 1.25f && rsRows[2].pad.points.last().ayG > 1.38f)
    }
}
