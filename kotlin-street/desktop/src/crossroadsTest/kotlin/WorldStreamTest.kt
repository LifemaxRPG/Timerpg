import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldStream
import com.lifemax.rpg.feature.habits.domain.WorldTerrain
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpots
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The world streamer (docs/WORLD_STREAM.md), without the GPU: the far view planned again round a new middle keeps every
 * vertex it shares with the old plan and the near terrain's ground is the same function (so a swap shows no seam and the
 * cars' ground does not change under them); the streamer keeps the eye on the finest level all the way at highway speed;
 * and a car driven along the road from the capital to the next town at 100-130 km/h over the streamed heightfield chunks
 * never falls, is never rescued, and meets no bump at a chunk's seam. Times are the build server's (desktop), not the S25's.
 *
 * gradle :desktop:crossroadsTest --tests WorldStreamTest
 */
class WorldStreamTest {
    private val sk get() = WorldDrive.sk
    private val site get() = WorldDrive.site
    private val year get() = WorldDrive.town.year

    @Test fun theDriveGoesToTheNextTown() {
        val d = WorldDrive.drive
        println("the drive: $d; from the capital's pin at %.3f, %.3f km".format(d.start.x / 1000, d.start.z / 1000))
        assertTrue(d.km >= WorldDrive.MIN_KM)
    }

    @Test fun aReplanKeepsEveryVertexAndTheGround() {
        val a = site.plan(year)
        // (a middle one lattice step east and one south of the town's: the streamer's first re-plan out of town)
        val m = a.middle.plus(WorldFarPlan.ANCHOR_CELLS * Planet.CELL_M, WorldFarPlan.ANCHOR_CELLS * Planet.CELL_M)
        val t0 = System.nanoTime()
        val b = site.plan(year, m)
        val planMs = (System.nanoTime() - t0) / 1e6
        assertEquals(a.anchorI + WorldFarPlan.ANCHOR_CELLS, b.anchorI)
        // every level: a vertex both plans have (away from either's rim, where the finest level fades to the cells) is the same height
        var same = 0
        for (k in a.levels.indices) {
            val la = a.levels[k]; val lb = b.levels[k]
            val di = Math.round((lb.ox - la.ox) / la.spacing).toInt(); val dj = Math.round((lb.oz - la.oz) / la.spacing).toInt()
            assertEquals("level $k on the same lattice", 0.0, (lb.ox - la.ox) - di * la.spacing, 1e-6)
            for (j in 0..lb.n) for (i in 0..lb.n) {
                val ia = i + di; val ja = j + dj
                if (ia < 0 || ja < 0 || ia > la.n || ja > la.n) continue
                val rim = 4
                if (min(min(ia, la.n - ia), min(ja, la.n - ja)) < rim || min(min(i, lb.n - i), min(j, lb.n - j)) < rim) continue
                assertEquals("level $k vertex ($i, $j)", la.h(ia, ja), lb.h(i, j), 0f)
                same++
            }
        }
        // the near terrain's ground round the new middle: the same heights whichever plan it was made with
        val ta = WorldTerrain(sk, a); val t1 = System.nanoTime(); val tb = WorldTerrain(sk, b); val terrainMs = (System.nanoTime() - t1) / 1e6
        val ox = a.origin.dx(b.middle); val oz = a.origin.dz(b.middle)
        var worst = 0.0
        val r = java.util.Random(7)
        repeat(4000) {
            val x = ox + (r.nextDouble() - .5) * 8000; val z = oz + (r.nextDouble() - .5) * 8000
            worst = max(worst, abs(ta.heightLocal(x, z) - tb.heightLocal(x, z)))
        }
        println("re-plan: %d shared vertices the same; the plan %.0f ms, its terrain %.0f ms (desktop, one thread); the ground differs by %.6f m at most over 4,000 points; %s"
            .format(same, planMs, terrainMs, worst, b.report))
        assertTrue(same > 10000)
        assertEquals("the ground is the same after a re-plan", 0.0, worst, 0.0)
    }

    @Test fun theStreamerKeepsTheEyeOnTheFinestLevel() {
        // the whole drive at 130 km/h, a re-plan taking 3 s to be ready (planned, packed and uploaded): the eye must never
        // be further from the drawn plan's middle than the finest level allows the near terrain (WorldStream.RECENTRE_M's note)
        val d = WorldDrive.drive
        val s = WorldStream()
        val v = 130 / 3.6
        var middle = site.plan(year).middle
        var pendingAt = -1.0; var pendingMid: WorldPos? = null
        var worst = 0.0; var t = 0.0; var dist = 0.0
        val p = DoubleArray(4)
        while (dist < d.path.lengthM) {
            WorldDrive.along(d.path.pts, dist, p)
            val eye = WorldPos(Planet.wrapX(p[0]), p[1])
            s.observe(eye, t)
            worst = max(worst, max(abs(middle.dx(eye)), abs(middle.dz(eye))))
            if (pendingMid != null && t >= pendingAt) { middle = WorldPos.ofCells(WorldFarPlan.anchorOf(pendingMid.x / Planet.CELL_M) + .5, WorldFarPlan.anchorOf(pendingMid.z / Planet.CELL_M) + .5); pendingMid = null }
            if (pendingMid == null) s.replanFor(eye, middle)?.let { pendingMid = it; pendingAt = t + 3.0 }
            t += 1 / 60.0; dist += v / 60.0
        }
        val allowed = WorldFarPlan.LEVELS[0].let { it.first * it.second / 2 } * Planet.CELL_M - WorldTerrain.REACH_M - WorldTerrain.ROOT_TILES * Planet.TILE_M
        println("the streamer over %.1f km at 130 km/h: %d re-plans, the eye at most %.0f m from the middle (allowed %.0f)".format(d.km, s.replans, worst, allowed))
        assertTrue("the eye strayed $worst m from the far view's middle (allowed $allowed)", worst < allowed)
    }

    // ------------------------------------------------------------------ the car on the streamed ground

    private class Walk : VehicleWorld.Carried {
        override var wx = 0f; override var wz = 0f
        override fun carry(x: Float, z: Float, dx: Float, dz: Float, speed: Float) { wx = x; wz = z }
        override fun release(x: Float, z: Float, dx: Float, dz: Float) {}
    }

    @Test fun aCarDrivesToTheNextTownAtHighwaySpeedOnTheStreamedGround() {
        val ph = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        val d = WorldDrive.drive
        val f = site.frame
        val u = f.unitsPerMetre
        val U = VehicleWorld.U
        // the world's ground as the app hands it to the cars (TownPlay.groundFor), its terrain swapped at each re-plan
        var plan = site.plan(year)
        var terrain = WorldTerrain(sk, plan)
        val groundCalls = java.util.concurrent.atomic.AtomicLong()
        val ground: (Float, Float) -> Float? = { xm, zm -> groundCalls.incrementAndGet(); terrain.heightLocal(xm * U / u, zm * U / u).toFloat() }
        val spec = VehicleSpec.parse(File("../app/src/main/assets/vehicles/northcairn-1956/model-info.json").readText())
        val vw = VehicleWorld(ph, null, listOf(spec), ground, f.ground)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        // the route in physics metres (the capital's frame)
        val pts = d.street(site).let { s -> DoubleArray(s.size) { s[it] / U.toDouble() } }
        val p = DoubleArray(4)
        WorldDrive.along(pts, 0.0, p)
        val car = vw.add(spec, 0, false, VehicleSpots.Spot("drive", (p[0] * U).toFloat(), (p[1] * U).toFloat(), p[2].toFloat(), p[3].toFloat(), 8f * U))
        val walk = Walk()
        assertTrue(vw.enter(car, walk))
        val dt = VehicleWorld.STEP
        repeat(90) { vw.step(dt, walk, 0f, 0f) }
        val stream = WorldStream()
        var t = 0.0; var along = 0.0
        val stepMs = ArrayList<Float>(); val planMs = ArrayList<Float>()
        var maxSpeed = 0f; var speedSum = 0.0; var speedN = 0
        var worstLat = 0.0; var worstUnder = 0f
        val seamAcc = ArrayList<Float>(); val awayAcc = ArrayList<Float>()
        var lastVy = Float.NaN
        var slow = 0.0; var stuck: String? = null
        var worstAcc = 0f; var worstWhere = ""
        val jolts = ArrayList<String>()
        val total = pts.let { var l = 0.0; for (k in 0 until it.size / 2 - 1) l += hypot(it[k * 2 + 2] - it[k * 2], it[k * 2 + 3] - it[k * 2 + 1]); l }
        val limit = total - 60
        val s = car.now
        while (along < limit && t < 3 * 3600) {
            val x = s[VehicleState.X].toDouble(); val z = s[VehicleState.Z].toDouble()
            // where along the route the car is (searched forward from the last)
            var best = along; var bd = Double.MAX_VALUE
            var q = along - 20
            while (q < along + 200) { WorldDrive.along(pts, max(0.0, q), p); val dd = hypot(p[0] - x, p[1] - z); if (dd < bd) { bd = dd; best = max(0.0, q) }; q += 2.0 }
            along = best
            if (along > 400) worstLat = max(worstLat, bd)
            // pure pursuit: aim at the route a little ahead; the speed held at 120 km/h, less where it bends
            val v = car.forwardSpeed
            val look = 18.0 + v * .9
            WorldDrive.along(pts, along + look, p)
            val hx = car.fx.toDouble(); val hz = car.fz.toDouble()
            val tx = p[0] - x; val tz = p[1] - z
            val ang = atan2(hx * tz - hz * tx, hx * tx + hz * tz)
            val steer = (ang * 2.4).coerceIn(-1.0, 1.0).toFloat()
            // (the tightest bend in the next 300 m: its radius from the heading's turn over 60 m; a driver keeps 4 m/s2 across)
            val a0 = DoubleArray(4); val a1 = DoubleArray(4)
            var radius = 1e9
            var ahead = 0.0
            while (ahead < 300) {
                WorldDrive.along(pts, along + ahead, a0); WorldDrive.along(pts, along + ahead + 60, a1)
                val turn = abs(atan2(a0[2] * a1[3] - a0[3] * a1[2], a0[2] * a1[2] + a0[3] * a1[3]))
                radius = min(radius, 60 / max(turn, 1e-4)); ahead += 20
            }
            val target = min(TARGET_KMH / 3.6, max(MIN_KMH / 3.6, sqrt(4.0 * radius)))
            val pedal = (((target - v) * .35).coerceIn(-1.0, 1.0)).toFloat()
            val t0 = System.nanoTime()
            vw.step(dt, walk, steer, -pedal)
            stepMs += (System.nanoTime() - t0) / 1e6f
            t += dt
            // the streamer: a re-plan round the car's lead (made here at once and timed; on the phone a worker makes it)
            val w = f.world(s[VehicleState.X] * U.toDouble(), s[VehicleState.Z] * U.toDouble())
            stream.observe(w, t)
            stream.replanFor(w, plan.middle)?.let { mid ->
                val p0 = System.nanoTime()
                plan = site.plan(year, mid); terrain = WorldTerrain(sk, plan)
                planMs += (System.nanoTime() - p0) / 1e6f
            }
            val sp = abs(v); maxSpeed = max(maxSpeed, sp); if (along > 1000) { speedSum += sp; speedN++ }
            // (stuck: say where and why, and stop)
            if (sp < 2f) slow += dt else slow = 0.0
            if (slow > 10.0) {
                val pt = WorldTerrain.Point()
                val wx = x * U / u; val wz = z * U / u
                terrain.pointAt((site.frame.origin.x + wx) / Planet.CELL_M, (site.frame.origin.z + wz) / Planet.CELL_M, wx, wz, pt)
                val prof = (-4..8).joinToString { k -> WorldDrive.along(pts, along + k * 5.0, p); "%.1f".format(ground(p[0].toFloat(), p[1].toFloat())) }
                stuck = "stuck at %.2f km (%.0f, %.0f m), %.1f m off the route, car y %.2f ground %.2f; river %.1f m, road %.1f, county %.1f, cliff %.2f; the ground along the route from 20 m back: %s".format(
                    along / 1000, x, z, bd, s[VehicleState.Y], ground(s[VehicleState.X], s[VehicleState.Z]), pt.river, pt.road, pt.county, pt.cliff, prof)
                println(stuck); break
            }
            // under the ground? (the car's body below the ground's surface: a fall through a seam)
            val gy = ground(s[VehicleState.X], s[VehicleState.Z]) ?: 0f
            worstUnder = max(worstUnder, gy - s[VehicleState.Y] - .5f)
            // the vertical acceleration less the slope's own (a bump): near a chunk's seam and away from them
            val vy = s[VehicleState.VY]
            if (!lastVy.isNaN() && along > 400 && sp > 20f) {
                val acc = abs(vy - lastVy) / dt
                val cm = VehicleWorld.CHUNK_M
                val ex = abs(s[VehicleState.X] - Math.round(s[VehicleState.X] / cm) * cm); val ez = abs(s[VehicleState.Z] - Math.round(s[VehicleState.Z] / cm) * cm)
                if (min(ex, ez) < 2.5f) seamAcc += acc else awayAcc += acc
                if (acc > 20f) jolts += "%.0f@%.2fkm/%.1fm".format(acc, along / 1000, min(ex, ez))
                if (acc > worstAcc) {
                    worstAcc = acc
                    val pt = WorldTerrain.Point(); val wx = x * U / u; val wz = z * U / u
                    terrain.pointAt((site.frame.origin.x + wx) / Planet.CELL_M, (site.frame.origin.z + wz) / Planet.CELL_M, wx, wz, pt)
                    worstWhere = "%.1f m/s2 at %.2f km (%.0f, %.0f m; %.1f m from a seam), %.0f km/h; river %.1f m, road %.1f, county %.1f, cliff %.2f".format(acc, along / 1000, x, z, min(ex, ez), sp * 3.6, pt.river, pt.road, pt.county, pt.cliff)
                }
            }
            lastVy = vy
        }
        ph.close()
        fun q(a: List<Float>, k: Double) = if (a.isEmpty()) 0f else a.sorted()[((a.size - 1) * k).toInt()]
        val km = along / 1000
        println(("the drive %s: %.1f of %.1f km in %.1f min, average %.0f km/h (top %.0f); %d re-plans (%.0f ms each at most, desktop); " +
            "physics step with the ground's streaming: median %.3f ms, p99 %.2f, worst %.2f (desktop); %d ground heights; " +
            "farthest off the route %.1f m; deepest under the ground %.2f m; vertical acceleration at chunk seams p99 %.1f, worst %.1f m/s2 (%d samples), " +
            "away from them p99 %.1f, worst %.1f (%d); events %s; the car at %.0f km from the capital's frame: float step %.4f street units").format(java.util.Locale.US,
            d, km, total / 1000, t / 60, speedSum / max(1, speedN) * 3.6, maxSpeed * 3.6, planMs.size, planMs.maxOrNull() ?: 0f,
            q(stepMs, .5), q(stepMs, .99), stepMs.maxOrNull() ?: 0f, groundCalls.get(), worstLat, worstUnder,
            q(seamAcc, .99), seamAcc.maxOrNull() ?: 0f, seamAcc.size, q(awayAcc, .99), awayAcc.maxOrNull() ?: 0f, awayAcc.size, events,
            hypot(s[VehicleState.X], s[VehicleState.Z]) / 1000, Math.ulp(hypot(s[VehicleState.X], s[VehicleState.Z]) * U)))
        println("jolts over 20 m/s2 (m/s2 @ km / m from a seam): $jolts")
        println("the worst jolt: $worstWhere; the ground's chunks made at once ${vw.chunksNow}, ahead ${vw.chunksAhead}")
        File("build/world-stream").mkdirs()
        File("build/world-stream/physics.txt").writeText("the drive $d\n" + "%.1f km driven, %d re-plans, rescues %s".format(km, planMs.size, events))
        assertTrue("the car was rescued: $events", events.none { it.startsWith("car: rescued") })
        assertTrue("it reached the next town (%.1f of %.1f km)".format(km, total / 1000), along >= limit - 1)
        assertTrue("it fell under the ground ($worstUnder m)", worstUnder < .5f)
        assertTrue("a bump at a chunk's seam: p99 %.1f m/s2 against %.1f elsewhere".format(q(seamAcc, .99), q(awayAcc, .99)), q(seamAcc, .99) <= max(3f, q(awayAcc, .99) * 1.5f))
    }

    companion object {
        const val TARGET_KMH = 120.0
        const val MIN_KMH = 50.0
    }
}
