import com.lifemax.rpg.feature.habits.domain.FarmSolids
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
    private fun scene(fence: Boolean = true, spec: VehicleSpec = sedan, items0: List<TownYardItem>? = null, extra: (TownCollision) -> Unit = {}, heading: Float = 0f, startX: Float = 0f,
                      more: (TownCollision.Builder) -> Unit = {}): Scene {
        val items = items0 ?: if (fence) listOf(TownYardItem("frontFence", floatArrayOf(-fenceHalf * u, fenceZ * u - 2f, fenceHalf * u, fenceZ * u + 2f), 0f, -1f, 0, "edge", "front", null)) else emptyList()
        val yard = TownYard("lot1", "house1", "row1", "n", "suburban", "house", floatArrayOf(-400f, fenceZ * u - 2f, 400f, fenceZ * u + 600f),
            floatArrayOf(-150f, fenceZ * u + 200f, 150f, fenceZ * u + 450f), null, null, null, items)
        val yards = TownYards(listOf(yard), emptyList())
        val col = TownCollision.of(yards, -4000f, -4000f, 6000f, 6000f, more)
        extra(col)
        val ph = Physics.create() ?: error("the physics library did not load (gradle :desktop:physicsHost)")
        val vw = VehicleWorld(ph, col, listOf(spec), null, floatArrayOf(-4000f, -4000f, 6000f, 6000f))
        vw.useYards(yards)
        val events = ArrayList<String>()
        vw.onEvent = { events += it }
        val car = vw.add(spec, 0, false, VehicleSpots.Spot("t", (startX - kotlin.math.tan(heading) * (fenceZ - 3f)) * u, 0f, kotlin.math.sin(heading), kotlin.math.cos(heading), 8f * u))
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
    private val Scene.noseZ: Float get() = car.z / u + car.spec.footprint[0] + car.spec.footprint[2]

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

    // ------------------------------------------------------------------ the owner's S25 reports (build 0.1.715)

    private val torrel = VehicleSpec.parse(File(assets, "torrel-1968/model-info.json").readText())

    /** A yard's edge across +z at [fenceZ] m from x [x0] to [x1] m: [kind] of [variant] in [zone]. */
    private fun edge(kind: String, x0: Float, x1: Float, variant: Int = 1, zone: String = "side") =
        TownYardItem(kind, floatArrayOf(x0 * u, fenceZ * u - 2f, x1 * u, fenceZ * u + 2f), 0f, -1f, variant, "edge", zone, null)

    /** Up and over at [v] m/s until the nose is at the fence, held there till [past] m beyond it. */
    private fun Scene.through(v: Float, past: Float = 4f) {
        cruise(v + .3f, 14f) { it.noseZ > fenceZ - 1.2f }
        cruise(v, 3f) { it.car.z / u > fenceZ + past }
    }

    private fun Scene.piecePos(): List<FloatArray> { val q = FloatArray(7); return vw.wreck.pieces.map { p -> p.read(q); floatArrayOf(q[0] / u, q[1] / u, q[2] / u) } }
    private fun upOf(q: FloatArray) = 1f - 2f * (q[3] * q[3] + q[5] * q[5])

    /**
     * 163021 "fence stuck to wheels": the low Torrel (16.5 cm under its sills) through a board fence and its garden gate at
     * 10.4 m/s, then on at 5 mph for 10 s: no piece rides along (pushed in front, wedged under or dragged at its side).
     */
    @Test fun debrisNeverRidesAlongWithTheCar() {
        for (case in listOf("driveway gate", "gate", "boards", "picket", "boards 35", "picket 55", "gate 30")) {
            val items = when (case.substringBefore(' ')) {
                "gate" -> listOf(edge("sideFence", -80f, -.66f), edge("gate", -.66f, .66f, 0, "front"), edge("sideFence", .66f, 80f))
                // (163021: a pair of wide white gates across a driveway, b22's; the car's left side on its post)
                "driveway" -> listOf(edge("sideFence", -80f, -1.8f), edge("sideGate", -1.8f, 1.8f, 6, "side"), edge("sideFence", 1.8f, 80f))
                "boards" -> listOf(edge("sideFence", -80f, 80f))
                else -> listOf(edge("frontFence", -80f, 80f, 0, "front"))
            }
            val deg = case.removePrefix("driveway ").substringAfter(' ', "0").toFloat()
            val s = scene(spec = torrel, items0 = items, heading = Math.toRadians(deg.toDouble()).toFloat(), startX = if (case.startsWith("driveway")) -1.2f else 0f)
            s.through(10.4f)
            val atBreak = s.car.z / u
            // stopped, the pieces let settle, then on at 5 mph (2.2 m/s) straight over where they lie, for 16 s
            var t = 0f
            while (t < 4f) { s.vw.brakePedal = if (s.car.forwardSpeed > .3f) 1f else 0f; s.vw.step(dt, s.walk, 0f, 0f); t += dt }
            s.vw.brakePedal = 0f
            val settled = s.piecePos()
            val cz0 = s.car.z / u; val cx0 = s.car.x / u
            t = 0f
            var worst = 0f; var nearest = Float.MAX_VALUE; var carried = 0f
            while (t < 16f) {
                s.cruise(2.2f, dt); t += dt
                // (a piece carried: within reach of the car's middle and moved on from where it lay)
                val cx = s.car.x / u; val cz = s.car.z / u
                for ((a, b) in settled.zip(s.piecePos())) {
                    val moved = kotlin.math.hypot(b[0] - a[0], b[2] - a[2])
                    worst = max(worst, moved)
                    if (kotlin.math.hypot(b[0] - cx, b[2] - cz) < 3f) carried = max(carried, moved)
                }
            }
            val end = s.piecePos()
            val cx = s.car.x / u; val cz = s.car.z / u
            for (b in end) nearest = kotlin.math.min(nearest, kotlin.math.hypot(b[0] - cx, b[2] - cz))
            val lay = settled.joinToString { "(%.1f, %.2f, %.1f)".format(it[0], it[1], it[2]) }
            println("$case: through at %.1f m/s (z %.1f m), stopped at z %.1f m, on to z %.1f m; %d pieces lying at %s; the most one moved since %.2f m (%.2f m by the car), the nearest now %.2f m from the car's middle; %s"
                .format(s.vw.wreck.events.firstOrNull()?.speed ?: 0f, atBreak, cz0, cz, end.size, lay, worst, carried, nearest, s.events))
            assertTrue("$case broke: ${s.events}", s.events.any { it.startsWith("damage") })
            assertTrue("$case: the car drove on over them (from z $cz0 to $cz)", kotlin.math.hypot(cx - cx0, cz - cz0) > 25f)
            assertTrue("$case: no piece rode along with the car (one moved $carried m)", carried < 1.5f)
            assertTrue("$case: none is with the car now ($nearest m)", nearest > 2.6f)
            s.vw.close()
        }
    }

    /**
     * The same report, the ways a loose board can meet a car that drives on at 5 mph: standing just ahead of its nose,
     * leaning on its bumper, lying flat half under its sills, standing at its side: none is pushed along, wedged and
     * carried, or dragged with it (the car may knock it on a little).
     */
    @Test fun aLooseBoardIsNeverCarriedAlong() {
        for (spec in listOf(torrel, sedan)) for (case in listOf("standing", "leaning", "flat", "beside", "gate", "through", "through low", "through behind")) {
            val s = scene(spec = spec, fence = false)
            s.cruise(2.2f, 8f)
            val nose = s.noseZ; val cx = s.car.x / u
            val half = spec.footprint[1]
            // (a board fence's panel: 2.3 m x 1.17 m, 4 cm thick, 37 kg; a garden gate 1.3 m x 1.1 m, 22 kg)
            val d = FloatArray(18)
            var handle = -1
            fun board(x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float, pitch: Float, mass: Float) {
                d[0] = x; d[1] = y; d[2] = z; d[3] = hx; d[4] = hy; d[5] = hz
                d[6] = kotlin.math.sin(pitch / 2f); d[7] = 0f; d[8] = 0f; d[9] = kotlin.math.cos(pitch / 2f)
                d[10] = mass; for (k in 11..16) d[k] = 0f; d[17] = .7f
                handle = s.vw.physics.addDebris(d)
            }
            when (case) {
                "standing" -> board(cx, .6f, nose + .3f, 1.15f, .585f, .094f, 0f, 37f)
                "leaning" -> board(cx, .45f, nose + .45f, 1.15f, .585f, .094f, .7f, 37f)
                "flat" -> board(cx, .1f, nose, 1.15f, .094f, .585f, 0f, 37f)
                "beside" -> board(cx + half + .12f, .6f, nose - 1.5f, .094f, .585f, 1.15f, 0f, 37f)
                "gate" -> board(cx + half * .6f, .56f, nose + .2f, .66f, .55f, .094f, 0f, 22f)
                // (163021: a driveway's pair of gates, 3.6 m wide, spawned across the car's body)
                "through" -> board(cx, .56f, nose - .6f, 1.8f, .55f, .094f, 0f, 22f)
                "through low" -> board(cx, .3f, nose - 1.2f, 1.8f, .3f, .094f, 0f, 22f)
                else -> board(cx, .56f, nose - 3f, 1.8f, .55f, .094f, 0f, 22f)
            }
            val q = FloatArray(8)
            assertTrue("the board is in", handle >= 0)
            s.vw.physics.poses(intArrayOf(handle), 1, q)
            val x0 = q[0]; val z0 = q[2]
            val car0 = s.car.z / u
            var t = 0f
            while (t < 10f) { s.cruise(2.2f, dt); t += dt }
            s.vw.physics.poses(intArrayOf(handle), 1, q)
            val moved = kotlin.math.hypot(q[0] - x0, q[2] - z0)
            val away = kotlin.math.hypot(q[0] - s.car.x / u, q[2] - s.car.z / u)
            println("${spec.key} $case: the car drove %.1f m, the board moved %.2f m, now %.2f m from the car's middle (y %.2f)".format(s.car.z / u - car0, moved, away, q[1]))
            assertTrue("${spec.key} $case: the car drove on (${s.car.z / u - car0} m)", s.car.z / u - car0 > 15f)
            assertTrue("${spec.key} $case: the board was not carried along ($moved m)", moved < 3f)
            s.vw.close()
        }
    }

    /**
     * 163041 "fence doesn't fall when hit, just moved": a board fence hit at 10.6 m/s by the Torrel: its broken panels
     * fall over (lie on the ground), none is left standing, pushed along upright.
     */
    @Test fun aBoardFencesPanelsFallWhenHit() {
        for (spec in listOf(torrel, sedan)) {
            val s = scene(spec = spec, items0 = listOf(edge("sideFence", -fenceHalf, fenceHalf)))
            s.through(10.6f)
            s.vw.brakePedal = 1f
            var t = 0f
            while (t < 6f) { s.vw.step(dt, s.walk, 0f, 0f); t += dt }
            val q = FloatArray(7)
            val ups = s.vw.wreck.pieces.map { it.read(q); upOf(q) }
            println("${spec.key}: board fence at 10.6 m/s: ${ups.size} panels, their up now ${ups.joinToString { "%.2f".format(it) }} (1 standing); ${s.events}")
            assertTrue("it broke", s.events.any { it.startsWith("damage: a board fence") })
            assertTrue("panels came loose (${ups.size})", ups.size >= 2)
            assertTrue("every panel fell over: ${ups}", ups.all { it < .5f })
            s.vw.close()
        }
    }

    /**
     * 163153 "this fence stopped me at full speed": a farm field's post-and-rail fence (the farms' fences were solid: they
     * had no detail) at 11 mph (4.9 m/s): it breaks, the car goes through, the walk's map and the farms' drawing lose the
     * broken stretch, the rest still stands.
     */
    @Test fun aFarmFenceBreaksAt11Mph() {
        for (spec in listOf(torrel, sedan)) {
            val s = scene(spec = spec, fence = false, extra = { c ->
                c.setExtra("farms:fences", floatArrayOf(0f, fenceZ * u, 1f, 0f, 40f * u, FarmSolids.HALF), SolidKind.FENCE, "farms", FarmSolids.DETAIL)
            })
            assertTrue("the farm fence blocks the walker", s.col.blocked(0f, fenceZ * u, 4f))
            s.through(4.9f)
            println("${spec.key}: a farm fence at 4.9 m/s: ${s.events}; the car at z %.1f m, %.1f m/s; cuts ${s.vw.wreck.cuts.map { it.toList() }}".format(s.car.z / u, s.car.forwardSpeed))
            assertTrue("it broke: ${s.events}", s.events.any { it.startsWith("damage: a farm fence") })
            assertTrue("the car went through (${s.car.z / u} m)", s.car.z / u > fenceZ + 3f)
            assertFalse("the gap is open to the walker", s.col.blocked(0f, fenceZ * u, 4f))
            assertTrue("the rest still stands", s.col.blocked(-20f * u, fenceZ * u, 4f) && s.col.blocked(20f * u, fenceZ * u, 4f))
            assertEquals("the farms are told what to stop drawing", 1, s.vw.wreck.cuts.size)
            s.vw.close()
        }
    }

    /** Flimsy wooden fences give from about 7 mph for the lightest car (8 mph: 3.6 m/s); a slow nudge is still stopped. */
    @Test fun woodenFencesGiveFrom8Mph() {
        for (kind in listOf("frontFence", "sideFence", "postRail")) {
            val s = scene(spec = torrel, items0 = listOf(edge(kind, -fenceHalf, fenceHalf, 1, if (kind == "sideFence") "side" else "front")))
            s.through(3.6f)
            println("torrel into a $kind at 3.6 m/s: ${s.events}, z %.1f m".format(s.car.z / u))
            assertTrue("$kind broke at 8 mph: ${s.events}", s.events.any { it.startsWith("damage") })
            s.vw.close()
        }
        // the heaviest car at a walking pace into a board fence: stopped
        val tallmere = VehicleSpec.parse(File(assets, "tallmere-1972/model-info.json").readText())
        val s = scene(spec = tallmere, items0 = listOf(edge("sideFence", -fenceHalf, fenceHalf)))
        s.cruise(12f, 12f) { it.noseZ > fenceZ - 14f }
        s.vw.brakePedal = 1f
        var g = 0
        while (s.car.forwardSpeed > 1.4f && g++ < 600) s.vw.step(dt, s.walk, 0f, 0f)
        s.vw.brakePedal = 0f
        var t = 0f
        while (t < 12f) { s.cruise(1.4f, dt); t += dt }
        println("tallmere nudging a board fence at 1.4 m/s: ${s.events}, nose %.2f m".format(s.noseZ))
        assertTrue("no break: ${s.events}", s.events.none { it.startsWith("damage") })
        s.vw.close()
    }

    /**
     * Every fence and hedge the capital has (gamma's yards and lanes, the farms' fields, the store lots' and the cinema's
     * shapes) and how it gives: printed as a table; all wooden and wire ones break, the farms' fences included (they had
     * no detail and were solid: 163153); only the cinema's rails stay solid (not drawable broken yet).
     */
    @Test fun everyFenceInTheCapitalIsClassed() {
        val dir = File("../app/src/main/assets/town/gamma")
        val root = org.json.JSONObject(File(dir, "street.json").readText())
        val town = com.lifemax.rpg.feature.habits.domain.Town.parse(root)!!
        val yards = TownYards.parse(root)
        val cinema = com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root)
        val col = TownCollision.build(town, yards, com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
            cinema, com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
        // the farms' fences as TownFarms adds them
        val farms = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
        col.setExtra("farms:fences", FarmSolids.fences(farms, town.year), SolidKind.FENCE, "farms", FarmSolids.DETAIL)
        val rows = java.util.TreeMap<String, Int>()
        val solid = ArrayList<String>()
        col.forEachNearDetail(col.minX, col.minZ, col.maxX, col.maxZ, FloatArray(6)) { _, k, owner, d, _, _ ->
            if (k != SolidKind.FENCE && k != SolidKind.HEDGE && !(k == SolidKind.YARD_OBJECT && d?.startsWith("vegBed") == true)) return@forEachNearDetail
            val m = WreckMaterial.of(k, d)
            val what = d?.split('/')?.let { if (it.size >= 3) "${it[0]} v${it[1]} ${it[2]}" else it[0] } ?: "(no detail)"
            val from = when { owner == "farms" -> "farms"; owner == cinema?.sprite -> "cinema"; else -> "yards" }
            rows["$k | $what | $from | ${m.give} | ${m.name} | ${m.impulse.toInt()}"] = (rows["$k | $what | $from | ${m.give} | ${m.name} | ${m.impulse.toInt()}"] ?: 0) + 1
            if (m.give == Give.SOLID) solid += "$k $what of $owner"
        }
        println("kind | what | from | give | name | N s | shapes")
        for ((r, n) in rows) println("$r | $n")
        assertTrue("the farms' fences are in it", rows.keys.any { it.contains("farms") && it.contains("BREAK") })
        assertTrue("only the cinema's rails stay solid: ${solid.distinct()}", solid.all { it.endsWith("of ${cinema?.sprite}") })
    }
}
