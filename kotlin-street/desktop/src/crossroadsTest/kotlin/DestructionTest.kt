import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYard
import com.lifemax.rpg.feature.habits.domain.TownYardItem
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.physics.Physics
import com.lifemax.rpg.feature.habits.vehicle.Give
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpots
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import com.lifemax.rpg.feature.habits.vehicle.WreckMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The cars' destruction (docs/VEHICLES.md, "Destruction") through the same JNI library the phone loads: a car at speed
 * goes through a wooden fence, breaking it into its panels, and keeps most of its speed; a slow nudge is stopped by it; a
 * building stops it at any speed; a lamp post hit at speed falls over; the pieces go to sleep; the cap on live pieces
 * holds; the walk's map loses the broken stretch and keeps the rest. A scene of its own: a flat ground, one yard's fence
 * across the car's way 60 m on. Times are this build server's, not the S25's.
 */
class DestructionTest {
    private val assets = File("../app/src/main/assets/vehicles")
    private val sedan = VehicleSpec.parse(File(assets, "northcairn-1956/model-info.json").readText())
    private val dt = VehicleWorld.STEP
    private val u = VehicleWorld.U

    private class Walk : VehicleWorld.Carried {
        override var wx = 0f; override var wz = 0f
        override fun carry(x: Float, z: Float, dx: Float, dz: Float, speed: Float) { wx = x; wz = z }
        override fun release(x: Float, z: Float, dx: Float, dz: Float) { wx = x; wz = z }
    }

    /** The fence's line (m along +z) and half length (m). */
    private val fenceZ = 60f
    private val fenceHalf = 10f

    private class Scene(val vw: VehicleWorld, val car: VehicleWorld.Car, val walk: Walk, val col: TownCollision, val events: MutableList<String>)

    /** A picket fence (a yard's front fence) across +z at [fenceZ] m; [more] adds other shapes; the sedan at the origin heading +z, driven. */
    private fun scene(fence: Boolean = true, more: (TownCollision.Builder) -> Unit = {}): Scene {
        val items = if (fence) listOf(TownYardItem("frontFence", floatArrayOf(-fenceHalf * u, fenceZ * u - 2f, fenceHalf * u, fenceZ * u + 2f), 0f, -1f, 0, "edge", "front", null)) else emptyList()
        val yard = TownYard("lot1", "house1", "row1", "n", "suburban", "house", floatArrayOf(-400f, fenceZ * u - 2f, 400f, fenceZ * u + 600f),
            floatArrayOf(-150f, fenceZ * u + 200f, 150f, fenceZ * u + 450f), null, null, null, items)
        val yards = TownYards(listOf(yard), emptyList())
        val col = TownCollision.of(yards, -4000f, -4000f, 6000f, 6000f, more)
        val ph = Physics.create() ?: error("the physics library did not load (gradle :desktop:physicsHost)")
        val vw = VehicleWorld(ph, col, listOf(sedan), null, floatArrayOf(-4000f, -4000f, 6000f, 6000f))
        vw.useYards(yards)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        val car = vw.add(sedan, 0, false, VehicleSpots.Spot("t", 0f, 0f, 0f, 1f, 8f * u))
        val walk = Walk()
        assertTrue(vw.enter(car, walk))
        repeat(80) { vw.step(dt, walk, 0f, 0f) }
        return Scene(vw, car, walk, col, events)
    }

    /** Drives at [target] m/s (the gas below it, the brake above) until [until] or [seconds]; returns the most it went. */
    private fun Scene.cruise(target: Float, seconds: Float, until: (Scene) -> Boolean = { false }): Float {
        var t = 0f; var top = 0f
        while (t < seconds && !until(this)) {
            val v = car.forwardSpeed
            // (the gas eased in as it nears the speed, the brake above it: the stick's down)
            vw.step(dt, walk, 0f, if (v < target) -((target - v) * .6f).coerceIn(.15f, 1f) else ((v - target) * .8f).coerceAtMost(1f))
            top = max(top, v); t += dt
        }
        return top
    }
    private val Scene.noseZ: Float get() = car.z / u + sedan.footprint[0] + sedan.footprint[2]

    @Test fun theMaterials() {
        assertEquals(Give.BREAK, WreckMaterial.of(SolidKind.FENCE, "frontFence/0/front").give)
        assertEquals(Give.BREAK, WreckMaterial.of(SolidKind.HEDGE, "hedge/1/back").give)
        assertEquals(Give.BEND, WreckMaterial.of(SolidKind.PROP, "lamp").give)
        assertEquals(Give.BEND, WreckMaterial.of(SolidKind.PROP, "STOP_SIGN").give)
        assertEquals(Give.BREAK, WreckMaterial.of(SolidKind.PROP, "WHEELIE_BIN").give)
        assertEquals(Give.SOFT, WreckMaterial.of(SolidKind.YARD_OBJECT, "bush").give)
        assertEquals(Give.SOLID, WreckMaterial.of(SolidKind.PROP, "BOLLARD").give)
        assertEquals(Give.SOLID, WreckMaterial.of(SolidKind.WALL, "lowWall").give)
        assertEquals(Give.SOLID, WreckMaterial.of(SolidKind.BUILDING, null).give)
        assertEquals(Give.SOLID, WreckMaterial.of(SolidKind.TREE, "tree").give)
        assertEquals(Give.SOLID, WreckMaterial.of(SolidKind.FENCE, null).give)
    }

    @Test fun atSpeedItGoesThroughAWoodenFenceAndKeepsMostOfItsSpeed() {
        val s = scene()
        val gapX = 0f
        assertTrue("the fence blocks the walker before", s.col.blocked(gapX * u, fenceZ * u, 4f))
        // up to 15 m/s, then the gas held to keep it there into the fence
        s.cruise(15f, 12f) { it.noseZ > fenceZ - 1.5f }
        val v0 = s.car.forwardSpeed
        s.cruise(v0, .1f)
        var t = 0f
        while (s.car.z / u < fenceZ + 6f && t < 3f) { s.vw.step(dt, s.walk, 0f, 0f); t += dt }
        val v1 = s.car.forwardSpeed
        println("through the picket fence: %.1f m/s before, %.1f m/s after (%.0f%%), %d pieces, %s".format(v0, v1, 100f * v1 / v0, s.vw.wreck.pieces.size, s.events))
        assertTrue("it reached ~15 m/s ($v0)", v0 in 13.5f..16.5f)
        assertTrue("it broke the fence: ${s.events}", s.events.any { it.startsWith("damage: a picket fence") })
        assertTrue("it went through (z ${s.car.z / u} m)", s.car.z / u > fenceZ + 5f)
        assertTrue("it kept at least 60% of its speed ($v1 of $v0)", v1 >= .6f * v0)
        assertTrue("but lost some", v1 < v0)
        assertTrue("a few panels broke loose (${s.vw.wreck.pieces.size})", s.vw.wreck.pieces.size in 2..8)
        assertEquals("one damage event, for the crime system", 1, s.vw.wreck.events.size)
        val e = s.vw.wreck.events[0]
        assertEquals("house1", e.owner); assertEquals(sedan.key, e.car)
        // the walk's map: the gap is open, the fence either side still stands
        assertFalse("the broken stretch no longer blocks the walker", s.col.blocked(gapX * u, fenceZ * u, 4f))
        assertTrue("the fence's far ends still do", s.col.blocked(-8f * u, fenceZ * u, 4f) && s.col.blocked(8f * u, fenceZ * u, 4f))
        // and the renderer is told: the yard's fence is two shorter ones now
        val items = s.vw.wreck.yardEdits["house1"]!!
        assertEquals(2, items.count { it.kind == "frontFence" })
        // the pieces settle and sleep within a few seconds; none sank through the ground or flew off
        s.vw.brakePedal = 1f
        var slept = Float.NaN; t = 0f
        while (t < 8f) { s.vw.step(dt, s.walk, 0f, 0f); t += dt; if (slept.isNaN() && !s.vw.wreck.awake) slept = t }
        println("the pieces slept %.1f s after the car was through".format(slept))
        assertFalse("asleep within 6 s", slept.isNaN() || slept > 6f)
        val q = FloatArray(7)
        for (p in s.vw.wreck.pieces) {
            p.read(q)
            assertTrue("a piece on the ground (y ${q[1] / u} m)", q[1] / u in -.05f..1.2f)
            assertTrue("near the gap (${q[0] / u}, ${q[2] / u})", abs(q[0] / u) < 15f && q[2] / u in fenceZ - 3f..fenceZ + 40f)
        }
        s.vw.close()
    }

    @Test fun aSlowNudgeIsStoppedByTheFence() {
        val s = scene()
        // the car started 60 m back: brought up to 1.5 m/s and held there against the fence for 6 s
        s.cruise(12f, 12f) { it.noseZ > fenceZ - 14f }
        s.vw.brakePedal = 1f
        var g = 0
        while (s.car.forwardSpeed > 1.5f && g++ < 600) s.vw.step(dt, s.walk, 0f, 0f)
        s.vw.brakePedal = 0f
        var maxNose = 0f
        var t = 0f
        while (t < 14f) { s.cruise(1.5f, dt); maxNose = max(maxNose, s.noseZ); t += dt }
        println("a 1.5 m/s nudge: the nose at most at %.2f m (the fence at %.1f m), %.2f m/s now, %s".format(maxNose, fenceZ, s.car.forwardSpeed, s.events))
        assertTrue("it got there (${s.noseZ})", maxNose > fenceZ - 1f)
        assertTrue("no break: ${s.events}", s.events.none { it.startsWith("damage") })
        assertTrue("stopped at the fence (nose $maxNose)", maxNose < fenceZ + .3f)
        assertTrue("held there (${s.car.forwardSpeed} m/s)", abs(s.car.forwardSpeed) < .4f)
        assertTrue("the fence still blocks the walker", s.col.blocked(0f, fenceZ * u, 4f))
        s.vw.close()
    }

    @Test fun aBuildingStopsItAtAnySpeed() {
        // a house's wall across the way at 50 m, no fence
        val s = scene(fence = false) { b -> b.box(0f, 52f * u, 1f, 0f, 12f * u, 2f * u, SolidKind.BUILDING, "house9") }
        s.cruise(20f, 14f) { it.noseZ > 49f }
        val v0 = s.car.forwardSpeed
        var maxNose = 0f; var t = 0f
        while (t < 3f) { s.vw.step(dt, s.walk, 0f, -1f); maxNose = max(maxNose, s.noseZ); t += dt }
        println("into a building at %.1f m/s: the nose at most at %.2f m (the wall at 50 m), %s".format(v0, maxNose, s.events))
        assertTrue("fast ($v0)", v0 > 15f)
        assertTrue("stopped at the wall", maxNose < 50.4f)
        assertTrue("nothing broke", s.events.none { it.startsWith("damage") } && s.vw.wreck.pieces.isEmpty())
        s.vw.close()
    }

    @Test fun aLampPostHitAtSpeedFallsOver() {
        // a street lamp in the car's way 40 m on (no fence)
        val s = scene(fence = false) { b -> b.circle(.3f * u, 40f * u, 3.5f, SolidKind.PROP, "row1", "lamp") }
        s.cruise(12f, 12f) { it.noseZ > 38.5f }
        val v0 = s.car.forwardSpeed
        var t = 0f
        while (t < 3f) { s.vw.step(dt, s.walk, 0f, if (t < .6f) -1f else 0f); t += dt }
        val p = s.vw.wreck.pieces.singleOrNull()
        println("a lamp post at %.1f m/s: %s; the car on at %.1f m/s".format(v0, s.events, s.car.forwardSpeed))
        assertTrue("it snapped: ${s.events}", s.events.any { it.startsWith("damage: a street lamp") } && p != null)
        val q = FloatArray(7); p!!.read(q)
        // its up (the post's length) is far from up now: it lies on the ground
        val upY = 1f - 2f * (q[3] * q[3] + q[5] * q[5])
        println("the post's up now %.2f (1 standing), its middle %.2f m up".format(upY, q[1] / u))
        assertTrue("it fell over (up $upY)", upY < .5f)
        assertTrue("the car went on (${s.car.z / u} m, ${s.car.forwardSpeed} m/s)", s.car.z / u > 41f)
        assertFalse("the walker is no longer blocked where it stood", s.col.blocked(.3f * u, 40f * u, 2f))
        assertTrue("the renderer is told to take it away", s.vw.wreck.hidden.any { it.detail == "lamp" })
        s.vw.close()
    }

    @Test fun theCapOnLivePiecesHolds() {
        // five wheelie bins in a row 8 m apart, the cap on live pieces lowered to 3
        val s = scene(fence = false) { b ->
            for (k in 0 until 5) b.rect(floatArrayOf(-.3f * u, (30f + 8f * k) * u - 6f, .3f * u, (30f + 8f * k) * u + 6f), SolidKind.PROP, "row1", "WHEELIE_BIN")
        }
        // (bins in a row: whole-thing breaks; one piece each, so five pieces; the cap 3)
        s.vw.wreck.liveCap = 3
        s.cruise(14f, 14f) { it.car.z / u > 70f }
        println("through five bins with a cap of 3 live pieces: ${s.vw.wreck.live} live, ${s.vw.wreck.pieces.size} drawn, ${s.events.size} breaks")
        assertEquals(5, s.vw.wreck.events.size)
        assertTrue("at most 3 live (${s.vw.wreck.live})", s.vw.wreck.live <= 3)
        assertEquals("the frozen ones still drawn", 5, s.vw.wreck.pieces.size)
        assertTrue("the oldest frozen", s.vw.wreck.pieces.first().frozen)
        s.vw.close()
    }
}
