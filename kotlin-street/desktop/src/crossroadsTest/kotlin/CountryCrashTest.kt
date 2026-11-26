import com.lifemax.rpg.feature.habits.domain.HubFrame
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.WorldCountry
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.Statics
import com.lifemax.rpg.feature.habits.vehicle.CountrySolids
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpots
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The open country is solid (docs/WORLD_STREAM.md, step 1b's collision): a car driven at full throttle into a tree, a power
 * pole, a post-and-wire fence and a farm's barn, each made by [CountrySolids] from a country cell's things (the same records
 * WorldProps draws), crashes into it and stops in front of it, the crash heard ([VehicleWorld.onImpact]); with the country
 * not solid the same car drives on through, as it did before. And the batch add and removal: many statics in and out at
 * once, their handles given back. Times are this build server's, not the S25's.
 *
 * gradle :desktop:crossroadsTest --tests CountryCrashTest
 */
class CountryCrashTest {
    private val U = VehicleWorld.U
    private val spec = VehicleSpec.parse(File("../app/src/main/assets/vehicles/northcairn-1956/model-info.json").readText())
    private val shapes = ModelShape.parseAll(File("../app/src/main/assets/farm/buildings/models.json").readText())
    private val dt = VehicleWorld.STEP
    private val ci = 4000; private val cj = 3000

    /** A frame whose origin is cell ([ci], [cj])'s corner, a physics metre a metre. */
    private val frame = HubFrame("test", WorldPos(ci * WorldCountry.CELL_M, cj * WorldCountry.CELL_M), U * 2.1, 2.1, 0.0, floatArrayOf(-1e6f, -1e6f, 1e6f, 1e6f))

    private class Walk : VehicleWorld.Carried {
        override var wx = 0f; override var wz = 0f
        override fun carry(x: Float, z: Float, dx: Float, dz: Float, speed: Float) { wx = x; wz = z }
        override fun release(x: Float, z: Float, dx: Float, dz: Float) {}
    }

    private class Run(val maxX: Float, val endSpeed: Float, val impacts: List<Float>, val statics: Int, val report: String)

    /** A car at ([x], [z]) m heading +x, driven at full throttle for [seconds]; the country's cell [cell] solid if [solid]. */
    private fun drive(cell: WorldCountry.Scatter, x: Float, z: Float, solid: Boolean, seconds: Float = 9f): Run {
        val ph = Physics.create(VehicleWorld.WORLD_BODIES) ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        val vw = VehicleWorld(ph, null, listOf(spec), null, floatArrayOf(-2000 * U, -2000 * U, 2000 * U, 2000 * U))
        val cs = CountrySolids(frame, { null }, shapes) { i, j -> if (i == ci && j == cj) cell else null }
        if (solid) vw.country = cs
        val impacts = ArrayList<Float>()
        vw.onImpact = { impacts += it }
        val car = vw.add(spec, 0, false, VehicleSpots.Spot("t", x * U, z * U, 1f, 0f, 8f * U))
        val walk = Walk()
        assertTrue(vw.enter(car, walk))
        // (getting in: the cells round the car are made and added meanwhile)
        repeat(90) { vw.step(dt, walk, 0f, 0f); Thread.sleep(1) }
        var maxX = -1e9f
        var t = 0f
        while (t < seconds) { vw.step(dt, walk, 0f, -1f); maxX = max(maxX, car.x / U); t += dt }
        val r = Run(maxX, car.forwardSpeed, impacts, cs.bodies, cs.report)
        vw.close()
        return r
    }

    private fun scene(): WorldCountry.Scatter {
        val s = WorldCountry.Scatter(ci, cj)
        // an oak 15 m tall (its foot sunk as the scatter sinks it), a power pole, a wire fence across the way, a red barn
        s.tree(45f, -.35f, 20f, WorldCountry.OAK, 0, 15f)
        s.poles += floatArrayOf(45f, 0f, 50f, Float.NaN, Float.NaN, Float.NaN, 9f, WorldCountry.POWER.toFloat())
        var zz = 70f
        while (zz < 95f) { s.fences += floatArrayOf(45f, 0f, zz, 45f, 0f, zz + 3.6f, WorldCountry.WIRE.toFloat()); zz += 3.6f }
        // (the barn's front faces the car, its body behind the front)
        s.buildings += floatArrayOf(45f, -.15f, 115f, -1f, 0f, WorldCountry.FARM_MODELS.indexOf("fm_barn_red").toFloat())
        // a bush and a hedge's bushes: not solid
        s.tree(45f, -.1f, 4f, WorldCountry.BUSH_ROUND, 0, 2.4f)
        return s
    }

    @Test fun aCarCrashesIntoATreeAPoleAFenceAndABarnAndStops() {
        val s = scene()
        val cases = listOf(Triple("an oak", 20f, 45f - .52f), Triple("a power pole", 50f, 45f - .15f), Triple("a wire fence", 82f, 45f - .1f), Triple("a red barn", 115f, 45f))
        for ((what, z, face) in cases) {
            val hit = drive(s, 10f, z, solid = true)
            val through = drive(s, 10f, z, solid = false)
            println("full throttle from 35 m into $what: stopped with its middle at x %.2f m (the obstacle's face at %.2f), speed %.2f m/s, impacts %s; %d statics; %s; not solid: on to x %.1f m"
                .format(hit.maxX, face, hit.endSpeed, hit.impacts.joinToString { "%.1f m/s".format(it) }, hit.statics, hit.report, through.maxX))
            assertTrue("$what stops the car before it (its middle at ${hit.maxX}, the face at $face)", hit.maxX < face - 1.2f)
            assertTrue("$what: the car stands (speed ${hit.endSpeed})", abs(hit.endSpeed) < 1.5f)
            assertTrue("$what: the crash is heard (${hit.impacts})", hit.impacts.any { it > 6f })
            assertTrue("without the country solid the car drives through $what (to ${through.maxX})", through.maxX > face + 20f)
        }
        // the bush at z 4 is not solid: a car on that line goes through it
        val bush = drive(s, 10f, 4f, solid = true)
        assertTrue("the bush is not solid (on to ${bush.maxX})", bush.maxX > 65f)
    }

    @Test fun staticsGoInAndOutAsBatches() {
        val ph = Physics.create(VehicleWorld.WORLD_BODIES) ?: error("the physics library did not load")
        val n = 5000
        val d = FloatArray(n * Statics.COUNT)
        for (i in 0 until n) {
            val o = i * Statics.COUNT
            d[o + Statics.KIND] = (i % 2).toFloat()
            d[o + Statics.X] = (i % 70) * 3f; d[o + Statics.Y] = 1.5f; d[o + Statics.Z] = (i / 70) * 3f
            d[o + Statics.HX] = .3f + (i % 7) * .02f; d[o + Statics.HY] = 1.5f; d[o + Statics.HZ] = .2f; d[o + Statics.YAW] = i * .1f; d[o + Statics.FRICTION] = .5f
        }
        val before = ph.bodies
        val h = IntArray(n)
        var t0 = System.nanoTime()
        assertEquals(n, ph.addStatics(d, n, h))
        val addMs = (System.nanoTime() - t0) / 1e6
        assertEquals(before + n, ph.bodies)
        assertEquals("every handle its own", n, h.toSet().size)
        t0 = System.nanoTime(); ph.optimize(); val optMs = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime(); ph.removeAll(h, n); val removeMs = (System.nanoTime() - t0) / 1e6
        assertEquals(before, ph.bodies)
        // (the handles come back, lowest first, as one at a time would give them)
        val again = IntArray(3)
        ph.addStatics(d, 3, again)
        assertEquals(h.sorted().take(3), again.toList())
        // one at a time, for comparison
        t0 = System.nanoTime()
        val one = IntArray(n) { i -> val o = i * Statics.COUNT; if (i % 2 == 0) ph.addBox(d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 6], d[o + 7], .5f) else ph.addCylinder(d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], .5f) }
        val oneMs = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime(); for (x in one) ph.remove(x); val oneRemoveMs = (System.nanoTime() - t0) / 1e6
        println("$n statics: added as one batch in %.2f ms, optimised in %.2f ms, removed in %.2f ms; one at a time: added in %.2f ms, removed in %.2f ms (this server)".format(addMs, optMs, removeMs, oneMs, oneRemoveMs))
        ph.close()
    }
}
