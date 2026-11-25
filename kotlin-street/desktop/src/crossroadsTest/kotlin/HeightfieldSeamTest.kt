import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.physics.VehicleState
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The ground's streamed chunks meet exactly (2026-11-25, found by the Dove Beach job): VehicleWorld streams heightfield
 * chunks of [VehicleWorld.CHUNK_N] (33) samples over [VehicleWorld.CHUNK_M] (64 m); Jolt wants a sample count that is
 * a multiple of its block size (4), and physics_core used to pad to 36 by repeating the far edge, a flat shelf of ~6 m
 * reaching into the next chunk at the edge's height. Where the ground fell away, a car crossing the border rode out
 * onto the shelf and dropped off its end. The padding is now holes (no collision), so a chunk ends at its last sample.
 * Here a car drives downhill (10%) across a chunk border, laid out as the game streams it: its height over the slope
 * must stay level through the border.
 */
class HeightfieldSeamTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val dt = VehicleWorld.STEP

    @Test fun aCarCrossesASlopedChunkBorderWithoutABump() {
        val ph = Physics.create() ?: error("the physics library did not load (-Dtimerpg.physics.lib: gradle :desktop:physicsHost)")
        val n = VehicleWorld.CHUNK_N; val m = VehicleWorld.CHUNK_M
        val cell = m / (n - 1)
        val slope = -.10f
        fun ground(x: Float) = 20f + slope * x
        // two chunks in a row along x (and their neighbours in z, as the game's 3 x 3), as VehicleWorld.streamGround makes them
        val hs = FloatArray(n * n)
        for (i in 0..1) for (j in -1..1) {
            val x0 = i * m; val z0 = j * m
            for (b in 0 until n) for (a in 0 until n) hs[b * n + a] = ground(x0 + a * cell)
            assertTrue(ph.addHeightfield(x0, z0, n, cell, hs) >= 0)
        }
        val spec = VehicleSpec.parse(File(assets, "northcairn-1956/model-info.json").readText())
        // the car heading +x (yaw: +z forward turned to +x), on the slope's surface
        val car = ph.addVehicle(spec.desc(), spec.boxes, 10f, ground(10f) + .1f, 32f, (Math.PI / 2).toFloat())
        val s = FloatArray(VehicleState.COUNT)
        // settle, then roll downhill under a light throttle to ~35 km/h across the border at x = 64
        for (k in 0 until 60) { ph.input(car, 0f, 0f, 0f, 1f); ph.step(dt); ph.state(car, s) }
        var off0 = Float.NaN
        var worst = 0f; var worstAt = 0f; var crossed = false; var maxVy = 0f
        var t = 0f
        while (t < 20f && s[VehicleState.X] < 110f) {
            val v = s[VehicleState.FORWARD_SPEED]
            ph.input(car, if (v < 10f) .4f else 0f, 0f, if (v > 11f) .3f else 0f, 0f); ph.step(dt); ph.state(car, s); t += dt
            val x = s[VehicleState.X]
            val off = s[VehicleState.Y] - ground(x)
            if (x > 30f && off0.isNaN()) off0 = off
            if (x > 30f) {
                if (abs(off - off0) > worst) { worst = abs(off - off0); worstAt = x }
                if (x in 56f..80f) maxVy = max(maxVy, abs(s[VehicleState.VY] - slope * s[VehicleState.VX]))
            }
            if (x > 70f) crossed = true
        }
        ph.close()
        println("downhill 10%% across the chunk border at x = 64 m: height over the slope varies by %.3f m at most (at x %.1f), vertical speed off the slope <= %.2f m/s".format(worst, worstAt, maxVy))
        assertTrue("it crossed the border", crossed)
        assertTrue("no step at the border (height over the slope varies ${worst} m, at x $worstAt)", worst < .05f)
        assertTrue("no drop at the border ($maxVy m/s)", maxVy < .3f)
    }
}
