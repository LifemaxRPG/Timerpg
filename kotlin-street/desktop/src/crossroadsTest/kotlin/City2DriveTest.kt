import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleDesc
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The second city's drivability check (docs/CITY2.md, "The drive"): the race Torrel (C046) driven round the loop the
 * city's arterials make (the Esplanade, Fellside Road and its viaduct, the Crown Road's hairpins, Ridge Drive, Neck Hill,
 * Harbour Road), on Jolt through the JNI library the phone loads, on the prototype's real profile: grades, vertical
 * curves and banked corners (src/town/city2, exported by tools/city2-dump.ts to resources/city2/loop.json).
 *
 * The ground is streamed round the car as 64 m heightfield chunks (2 m cells), as VehicleWorld does beyond the town: on
 * the carriageway the road's own height and crossfall, outside it cut and fill slopes back to the land (ground20.bin).
 * A heightfield has one height a point, so where the loop's roads pass over another only the road being driven is there.
 *
 * The driver is a robot, not a racer: pure pursuit on the centre line (lightly smoothed; the export rounds the junctions,
 * which have no fillet in the data), a speed plan from the line's curvature (lateral 10 m/s^2 plus the bank's share; braking 8.5 m/s^2),
 * no limit at crests, so a crest that throws the car shows. It reports the lap time, each corner's speed against the
 * grip estimate, every moment all four wheels leave the ground, every bump to the suspension's stop, the worst distance
 * from the centre line and whether the car stayed upright. Numbers are this build server's, not the S25's; the race
 * Torrel's tune is whatever model-info.json holds in this tree.
 */
class City2DriveTest {
    private val rs = VehicleSpec.parse(File("../app/src/main/assets/vehicles/torrel-1988-rs/model-info.json").readText())
    private val dt = VehicleWorld.STEP

    private class Loop(json: JSONObject) {
        val n: Int
        val x: FloatArray; val z: FloatArray; val y: FloatArray; val bank: FloatArray; val half: FloatArray
        val roadAt: IntArray
        val names = ArrayList<String>()
        val starts = ArrayList<Int>()
        init {
            val p = json.getJSONArray("pts")
            n = p.length() / 4
            x = FloatArray(n) { p.getDouble(it * 4).toFloat() }
            z = FloatArray(n) { p.getDouble(it * 4 + 1).toFloat() }
            y = FloatArray(n) { p.getDouble(it * 4 + 2).toFloat() }
            bank = FloatArray(n) { p.getDouble(it * 4 + 3).toFloat() }
            val roads = json.getJSONArray("roads")
            roadAt = IntArray(n)
            half = FloatArray(n)
            for (r in 0 until roads.length()) {
                val o = roads.getJSONObject(r)
                names += o.getString("name"); starts += o.getInt("s") / 2
            }
            for (i in 0 until n) {
                var r = 0
                while (r + 1 < starts.size && i >= starts[r + 1]) r++
                roadAt[i] = r
                half[i] = roads.getJSONObject(r).getDouble("width").toFloat() / 2f + 1.5f
            }
        }
        fun wrap(i: Int) = ((i % n) + n) % n
    }

    private class Ground(json: JSONObject, bytes: ByteArray) {
        val x0 = json.getDouble("x0").toFloat(); val z0 = json.getDouble("z0").toFloat()
        val cell = json.getDouble("cell").toFloat(); val nx = json.getInt("nx"); val nz = json.getInt("nz")
        private val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fun at(x: Float, z: Float): Float {
            val fx = ((x - x0) / cell).coerceIn(0f, nx - 1.001f); val fz = ((z - z0) / cell).coerceIn(0f, nz - 1.001f)
            val i = fx.toInt(); val j = fz.toInt(); val u = fx - i; val v = fz - j
            fun g(a: Int, b: Int) = h.get(b * nx + a) / 10f
            return (g(i, j) * (1 - u) + g(i + 1, j) * u) * (1 - v) + (g(i, j + 1) * (1 - u) + g(i + 1, j + 1) * u) * v
        }
    }

    private val json = JSONObject(javaClass.classLoader.getResource("city2/loop.json")!!.readText())
    private val loop = Loop(json)
    private val land = Ground(json.getJSONObject("ground"), javaClass.classLoader.getResource("city2/ground20.bin")!!.readBytes())

    /** The ground at (x, z): the loop's road nearest within [lo, hi] (indices, wrapped), or the land. */
    private fun groundAt(x: Float, z: Float, lo: Int, hi: Int): Float {
        var best = Float.MAX_VALUE; var bi = -1
        var i = lo
        while (i <= hi) {
            val k = loop.wrap(i)
            val d = (loop.x[k] - x) * (loop.x[k] - x) + (loop.z[k] - z) * (loop.z[k] - z)
            if (d < best) { best = d; bi = k }
            i++
        }
        val g = land.at(x, z)
        if (bi < 0 || best > 70f * 70f) return g
        // the offset to the road's right (its normal from the next point), clamped to the carriageway
        val k1 = loop.wrap(bi + 1)
        val hx = loop.x[k1] - loop.x[bi]; val hz = loop.z[k1] - loop.z[bi]
        val hl = hypot(hx, hz).coerceAtLeast(1e-3f)
        val o = ((x - loop.x[bi]) * -hz + (z - loop.z[bi]) * hx) / hl
        val alongT = ((x - loop.x[bi]) * hx + (z - loop.z[bi]) * hz) / hl
        // (the height between this point and the next or the one before, for a smooth surface)
        val k2 = if (alongT >= 0) k1 else loop.wrap(bi - 1)
        val t = (abs(alongT) / 2f).coerceIn(0f, 1f)
        val yc = loop.y[bi] + (loop.y[k2] - loop.y[bi]) * t
        val bk = loop.bank[bi] + (loop.bank[k2] - loop.bank[bi]) * t
        val half = loop.half[bi]
        val oc = o.coerceIn(-half, half)
        val edge = yc + bk * oc
        val out = abs(o) - half
        if (out <= 0f) return edge
        val reach = (abs(g - edge) * 1.5f).coerceIn(3f, 60f)
        return edge + (g - edge) * min(1f, out / reach)
    }

    // ---- the driver's line: the centre line smoothed (the junctions filleted), its curvature and the speed plan
    private val px = FloatArray(loop.n); private val pz = FloatArray(loop.n)
    private val vPlan = FloatArray(loop.n)
    init {
        val w = 3
        for (i in 0 until loop.n) {
            var sx = 0f; var sz = 0f
            for (k in -w..w) { val j = loop.wrap(i + k); sx += loop.x[j]; sz += loop.z[j] }
            px[i] = sx / (2 * w + 1); pz[i] = sz / (2 * w + 1)
        }
        val top = 76f
        for (i in 0 until loop.n) {
            val a = loop.wrap(i - 5); val c = loop.wrap(i + 5)
            val k = curvature(px[a], pz[a], px[i], pz[i], px[c], pz[c])
            val lat = 10f + 9.81f * abs(loop.bank[i])
            vPlan[i] = if (k < 1e-5f) top else min(top, sqrt(lat / k))
        }
        // braking: from every slow point backwards at 8.5 m/s^2, twice round
        repeat(2) {
            for (i in loop.n - 1 downTo 0) {
                val nx = vPlan[loop.wrap(i + 1)]
                vPlan[i] = min(vPlan[i], sqrt(nx * nx + 2f * 8.5f * 2f))
            }
        }
    }

    private fun curvature(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float): Float {
        val a = hypot(bx - ax, bz - az); val b = hypot(cx - bx, cz - bz); val c = hypot(cx - ax, cz - az)
        val cross = abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax))
        return if (a * b * c < 1e-6f) 0f else 2f * cross / (a * b * c)
    }

    private fun forwardX(s: FloatArray) = VehicleWorld.forwardX(s)
    private fun forwardZ(s: FloatArray) = VehicleWorld.forwardZ(s)
    private fun up(s: FloatArray) = 1f - 2f * (s[VehicleState.QX] * s[VehicleState.QX] + s[VehicleState.QZ] * s[VehicleState.QZ])

    /** Which way a positive `right` input turns the car's heading (atan2(vz, vx)): measured once on flat ground. */
    private fun steerSign(): Float {
        val ph = Physics.create() ?: error("the physics library did not load")
        ph.addBox(0f, -1f, 0f, 2000f, 1f, 2000f)
        val v = ph.addVehicle(rs.desc(), rs.boxes, 0f, .05f, 0f, 0f)
        val s = FloatArray(VehicleState.COUNT)
        repeat(240) { ph.input(v, 1f, 0f, 0f, 0f); ph.step(dt); ph.state(v, s) }
        val h0 = atan2(s[VehicleState.VZ], s[VehicleState.VX])
        repeat(30) { ph.input(v, .3f, .4f, 0f, 0f); ph.step(dt); ph.state(v, s) }
        val h1 = atan2(s[VehicleState.VZ], s[VehicleState.VX])
        ph.close()
        var d = h1 - h0
        while (d > Math.PI) d -= (2 * Math.PI).toFloat(); while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return if (d > 0) 1f else -1f
    }

    @Test fun theRaceTorrelDrivesTheLoop() {
        val sign = steerSign()
        val desc = rs.desc()
        val maxSteer = desc[VehicleDesc.MAX_STEER]
        val wheelbase = abs(desc[VehicleDesc.FRONT_Z] - desc[VehicleDesc.REAR_Z])
        val ph = Physics.create() ?: error("the physics library did not load (gradle :desktop:physicsHost)")
        val st = FloatArray(VehicleState.COUNT)
        // the ground round the car: 3 x 3 chunks of 64 m, 33 samples a side
        val chunkM = 62f; val cn = 32; val cell = chunkM / (cn - 1)  // (32 samples: a multiple of Jolt's block of 4, so physics_core pads nothing; 33 is padded to 36 by repeating its far edge, a 6 m flat overhang into the next chunk)
        val chunks = HashMap<Long, Int>()
        val hs = FloatArray(cn * cn)
        var idx = 10
        fun stream(x: Float, z: Float) {
            val ci = floor(x / chunkM).toInt(); val cj = floor(z / chunkM).toInt()
            val want = HashSet<Long>()
            for (dj in -1..1) for (di in -1..1) {
                val i = ci + di; val j = cj + dj
                val key = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
                want += key
                if (key in chunks) continue
                for (b in 0 until cn) for (a in 0 until cn) hs[b * cn + a] = groundAt(i * chunkM + a * cell, j * chunkM + b * cell, idx - 150, idx + 150)
                chunks[key] = ph.addHeightfield(i * chunkM, j * chunkM, cn, cell, hs)
            }
            val it = chunks.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in want) { ph.remove(e.value); it.remove() } }
        }
        fun yawAt(i: Int): Float { val j = loop.wrap(i + 3); return atan2(px[j] - px[i], pz[j] - pz[i]) }
        stream(px[idx], pz[idx])
        val car = ph.addVehicle(desc, rs.boxes, px[idx], loop.y[idx] + .25f, pz[idx], yawAt(idx))
        assertTrue(car >= 0)

        val report = StringBuilder()
        fun line(s: String) { report.appendLine(s); println(s) }
        val trace = StringBuilder("s,t,kmh,plan_kmh,off_m,air,y\n")
        var t = 0f; var minUp = 1f
        var travelled = 0  // points passed since the start
        val lapPts = loop.n
        var lapTime = Float.NaN
        var airSteps = 0; var airRun = 0; var longestAir = 0; var airAt = -1; var airKmh = 0f
        val airEvents = ArrayList<String>()
        var stops = 0; val stopAt = ArrayList<String>()
        var worstOff = 0f; var worstOffAt = 0
        var resets = 0; var offRun = 0
        val roadT = FloatArray(loop.names.size); val roadIn = IntArray(loop.names.size)
        val vAt = FloatArray(loop.n) { -1f }
        val suspMin = desc[VehicleDesc.SUSP_MIN]
        val laps = 2
        var guard = 0
        while (travelled < lapPts * laps && guard++ < 60 * 60 * 40) {
            // where along the line: the nearest point a little round from the last
            var best = Float.MAX_VALUE; var bi = idx
            for (k in -5..40) {
                val j = loop.wrap(idx + k)
                val d = (px[j] - st[VehicleState.X]).let { it * it } + (pz[j] - st[VehicleState.Z]).let { it * it }
                if (d < best) { best = d; bi = idx + k }
            }
            if (guard > 1 && bi > idx) travelled += bi - idx
            if (guard > 1) idx = loop.wrap(bi)
            val v = st[VehicleState.FORWARD_SPEED]
            // pure pursuit
            val look = (6f + .35f * max(0f, v)).coerceIn(8f, 40f)
            val ti = loop.wrap(idx + (look / 2f).toInt())
            val fx = forwardX(st); val fz = forwardZ(st)
            val dx = px[ti] - st[VehicleState.X]; val dz = pz[ti] - st[VehicleState.Z]
            val alpha = atan2(fx * dz - fz * dx, fx * dx + fz * dz)
            val delta = atan(2f * wheelbase * sin(alpha) / look)
            val right = (sign * delta / maxSteer).coerceIn(-1f, 1f)
            // speed: the plan a little ahead (what the driver sees coming)
            val vt = vPlan[loop.wrap(idx + (max(0f, v) * .25f / 2f).toInt() + 2)]
            var fwd = 0f; var brk = 0f
            if (v < vt - .5f) fwd = 1f else if (v > vt + .8f) brk = ((v - vt) / 4f).coerceIn(.15f, 1f) else fwd = .35f
            if (guard < 3) { fwd = 0f; brk = 0f }
            ph.input(car, fwd, right, brk, 0f)
            ph.step(dt)
            ph.state(car, st)
            t += dt
            stream(st[VehicleState.X], st[VehicleState.Z])
            minUp = min(minUp, up(st))
            // the lap: timed from a standing start at the first point back round to it
            if (lapTime.isNaN() && travelled >= lapPts) lapTime = t
            val r = loop.roadAt[idx]
            if (travelled < lapPts) { roadT[r] += dt; roadIn[r]++ } else if (travelled < 2 * lapPts) vAt[idx] = v * 3.6f
            // off the line: the distance to the centre line (not the smoothed line)
            val off = hypot(loop.x[idx] - st[VehicleState.X], loop.z[idx] - st[VehicleState.Z])
            if (off > worstOff) { worstOff = off; worstOffAt = idx }
            // in the air: no wheel touching
            val contacts = (0 until 4).count { st[VehicleState.WHEELS + it * VehicleState.W_COUNT + VehicleState.W_CONTACT] > .5f }
            if (contacts == 0 && v > 3f) { airSteps++; airRun++; if (airRun == 1) { airAt = idx; airKmh = v * 3.6f } } else {
                if (airRun >= 3) airEvents += "%.0f ms airborne at %s s=%d (%.0f km/h)".format(airRun * dt * 1000, loop.names[loop.roadAt[airAt]], airAt * 2, airKmh)
                longestAir = max(longestAir, airRun); airRun = 0
            }
            // the suspension at its stop (a bump through to the bump stop) at speed
            val bottom = (0 until 4).any { st[VehicleState.WHEELS + it * VehicleState.W_COUNT + VehicleState.W_SUSPENSION] <= suspMin + .003f }
            if (bottom && v > 15f) { stops++; if (stopAt.size < 12 && (stopAt.isEmpty() || !stopAt.last().startsWith(loop.names[r]))) stopAt += "${loop.names[r]} s=${idx * 2} (%.0f km/h)".format(v * 3.6f) }
            // lost it: off the carriageway for a second, or on its side: put back on the line, counted
            if (off > loop.half[idx] + 3f) offRun++ else offRun = 0
            if (offRun > 60 || up(st) < .3f) {
                resets++
                line("  reset at ${loop.names[r]} s=${idx * 2}: off %.1f m, up %.2f, %.0f km/h".format(off, up(st), v * 3.6f))
                val j = loop.wrap(idx + 5)
                ph.place(car, px[j], loop.y[j] + .4f, pz[j], yawAt(j))
                idx = j; offRun = 0
            }
            if (travelled < 2 * lapPts && guard % 30 == 0) trace.append("%d,%.2f,%.1f,%.1f,%.2f,%d,%.2f\n".format(idx * 2, t, v * 3.6f, vPlan[idx] * 3.6f, off, if (contacts == 0) 1 else 0, st[VehicleState.Y]))
        }
        ph.close()
        File("build").mkdirs()
        File("build/city2-drive.csv").writeText(trace.toString())

        line("City 2 loop, race Torrel (this tree's tune), Jolt on this build server (not the S25):")
        line("  loop %.2f km; first lap from a standing start %.1f s (%d:%04.1f), average %.0f km/h".format(json.getInt("length") / 1000f, lapTime, (lapTime / 60).toInt(), lapTime % 60, json.getInt("length") / lapTime * 3.6f))
        for (r in loop.names.indices) {
            val len = (if (r + 1 < loop.starts.size) loop.starts[r + 1] else loop.n) - loop.starts[r]
            line("  %-14s %.2f km in %5.1f s, average %3.0f km/h".format(loop.names[r], len * 2 / 1000f, roadT[r], len * 2 / roadT[r] * 3.6f))
        }
        // corners: the flying lap's slowest speed through each, against the grip estimate
        val cs = json.getJSONArray("corners")
        line("  corners (flying lap): road, radius, turn, speed driven (min) / estimate")
        for (k in 0 until cs.length()) {
            val c = cs.getJSONObject(k)
            val a = c.getInt("from") / 2; val b = max(a, c.getInt("to") / 2)
            var mn = Float.MAX_VALUE
            for (i in a..b) if (vAt[loop.wrap(i)] >= 0) mn = min(mn, vAt[loop.wrap(i)])
            line("    %-14s R%4d %4d deg  %4.0f / %3d km/h".format(c.getString("road"), c.getInt("r"), c.getInt("deg"), if (mn == Float.MAX_VALUE) Float.NaN else mn, c.getInt("kmh")))
        }
        line("  airborne (all four wheels off): %d steps in all, longest %.0f ms".format(airSteps, longestAir * dt * 1000))
        for (e in airEvents.take(20)) line("    $e")
        line("  suspension on its stop above 54 km/h: %d steps; first at: %s".format(stops, stopAt.joinToString("; ")))
        line("  worst distance from the centre line: %.1f m at %s s=%d (carriageway half %.1f m)".format(worstOff, loop.names[loop.roadAt[worstOffAt]], worstOffAt * 2, loop.half[worstOffAt]))
        line("  resets (lost the line): %d; lowest upright %.2f".format(resets, minUp))
        File("build/city2-drive.txt").writeText(report.toString())
        assertTrue("a lap was finished", !lapTime.isNaN())
        assertTrue("it stayed on its wheels (up $minUp)", minUp > .3f)
    }
}
