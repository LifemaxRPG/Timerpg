import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleDesc
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every car flat out from a standstill through its gears on Jolt (the owner's 2026-11-25 note on the engine sound: "decent,
 * except when it's topped out before it shifts gears"): where each upshift happens, how long the engine sits at the top of
 * its revs (over 97% of its governed speed) before the gearbox moves, and how long a shift leaves the drive cut. The
 * trace of each run is written for the sound's before/after clips (build/shift-traces/KEY.csv: t, rpm, gear, throttle,
 * speed). Numbers are this build server's, not the S25's.
 */
class ShiftPointTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val dt = VehicleWorld.STEP

    class Run(val key: String, val maxRpm: Float, val shifts: List<Float>, val topSeconds: Float, val longestTop: Float, val trace: List<FloatArray>)

    fun run(s: VehicleSpec, seconds: Float = 30f): Run {
        val ph = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        ph.addBox(0f, -1f, 0f, 40000f, 1f, 40000f)
        val car = ph.addVehicle(s.desc(), s.boxes, 0f, .05f, 0f, 0f)
        val st = FloatArray(VehicleState.COUNT)
        val maxRpm = s.desc()[VehicleDesc.ENGINE_MAX_RPM]
        for (i in 0 until 60) { ph.input(car, 0f, 0f, 0f, 0f); ph.step(dt); ph.state(car, st) }
        val shifts = ArrayList<Float>()
        val trace = ArrayList<FloatArray>()
        var lastGear = st[VehicleState.GEAR].toInt()
        var prevRpm = st[VehicleState.RPM]
        var top = 0f; var run = 0f; var longest = 0f
        var t = 0f
        while (t < seconds) {
            ph.input(car, 1f, 0f, 0f, 0f); ph.step(dt); ph.state(car, st); t += dt
            val rpm = st[VehicleState.RPM]; val gear = st[VehicleState.GEAR].toInt()
            if (gear > lastGear && lastGear >= 1) shifts += prevRpm
            lastGear = gear; prevRpm = rpm
            // (at the top in a gear that is not the last: the stretch the owner hears before the shift)
            val gears = s.desc()[VehicleDesc.GEARS].toInt()
            if (rpm > maxRpm * .97f && gear < gears) { top += dt; run += dt; longest = maxOf(longest, run) } else run = 0f
            trace += floatArrayOf(t, rpm, gear.toFloat(), 1f, st[VehicleState.FORWARD_SPEED])
        }
        ph.close()
        return Run(s.key, maxRpm, shifts, top, longest, trace)
    }

    @Test fun theGearboxShiftsBeforeTheLimiter() {
        val out = File("build/shift-traces").apply { mkdirs() }
        val bad = ArrayList<String>()
        val keys = VehicleSpec.V1 + listOf("torrel-1988-rs")
        for (k in keys.distinct()) {
            val s = VehicleSpec.parse(File(assets, "$k/model-info.json").readText())
            val r = run(s)
            File(out, "$k.csv").writeText(r.trace.joinToString("\n") { a -> a.joinToString(",") })
            println("%-16s max %5.0f rpm: upshifts at %s; at the top before a shift %.2f s in all, longest %.2f s".format(
                k, r.maxRpm, r.shifts.joinToString(" ") { "%.0f".format(it) }, r.topSeconds, r.longestTop))
            if (r.longestTop >= .25f) bad += "$k: at the top ${r.longestTop} s before a shift"
            for (x in r.shifts) if (x >= r.maxRpm * .97f) bad += "$k shifts at its limiter ($x of ${r.maxRpm})"
        }
        assertTrue(bad.joinToString("; "), bad.isEmpty())
    }
}
