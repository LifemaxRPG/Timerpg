package com.timerpg.street

import com.lifemax.rpg.feature.habits.presentation.scene.CameraLead
import com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom
import com.lifemax.rpg.feature.habits.presentation.scene.Locomotion
import com.lifemax.rpg.feature.habits.presentation.scene.MoveTuning
import com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker
import com.lifemax.rpg.feature.habits.presentation.scene.RunFrames
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.Stick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * The player's movement as the integrator runs it, frame by frame at the phone's 120 Hz (and 60 Hz): top speeds, how
 * quickly it starts, stops and turns, the run, which view the figure shows, the legs against the ground, the rooms, and the
 * camera's look ahead.
 */
class MovementTest {
    private val WALK = MoveTuning.walk
    private val RUN = MoveTuning.run

    /** A player going along the street: x and the across-the-street ground position, stepped like the stage steps it. */
    private class Walker(val hz: Int = 120) {
        val m = Locomotion()
        var x = 0f
        var y = 0f
        var t = 0f
        val dt get() = 1f / hz

        fun go(seconds: Float, mx: Float, my: Float = 0f, each: (Walker) -> Unit = {}) {
            val n = round(seconds * hz).toInt()
            repeat(n) {
                m.drive(dt, mx, my)
                val dx = m.vx * dt; val dy = m.vy * dt
                x += dx; y += dy; t += dt
                m.advance(hypot(dx, dy))
                each(this)
            }
        }
    }

    @Test fun topSpeeds() {
        Walker().also { it.go(2f, 1f); assertEquals(RUN, it.m.vx, .5f) }
        Walker().also { it.go(2f, .7f); assertEquals(WALK, it.m.vx, .01f) }
        Walker().also { it.go(2f, .25f); assertEquals(WALK / 2f, it.m.vx, .01f) }
        // a full push on a diagonal is as fast as along the street
        Walker().also { it.go(2f, .7071f, .7071f); assertEquals(RUN, it.m.speed, .5f) }
    }

    /** Seconds until the speed first reaches [share] of [top]. */
    private fun timeTo(w: Walker, mx: Float, top: Float, share: Float): Float {
        var at = -1f
        w.go(1f, mx) { if (at < 0f && it.m.speed >= share * top) at = it.t }
        return at
    }

    @Test fun startingIsQuick() {
        val walk = timeTo(Walker(), .7f, WALK, .9f)
        assertTrue("0 to 90% of a walk took $walk s", walk in .10f..0.16f)
        val run = timeTo(Walker(), 1f, RUN, .9f)
        assertTrue("0 to 90% of a run took $run s", run in .15f..0.25f)
        // from a walk into a run takes a moment, not a jolt
        val w = Walker(); w.go(1f, .7f)
        val t0 = w.t
        var at = -1f
        w.go(1f, 1f) { if (at < 0f && it.m.speed >= .9f * RUN) at = it.t - t0 }
        assertTrue("walk to 90% of a run took $at s", at in .12f..0.25f)
    }

    @Test fun neverJerksOrOvershoots() {
        for (hz in listOf(60, 120)) {
            val w = Walker(hz)
            var last = 0f
            val plan = listOf(.6f to .7f, .6f to 1f, .4f to 0f, .8f to -1f, .5f to .3f, .5f to 0f)
            for ((secs, mx) in plan) {
                val top = if (abs(mx) >= MoveTuning.runAt) RUN else if (abs(mx) >= MoveTuning.walkFrom) WALK else WALK * abs(mx) / MoveTuning.walkFrom
                w.go(secs, mx) {
                    val dv = abs(it.m.vx - last)
                    assertTrue("a change of $dv in one frame", dv <= MoveTuning.brake * it.dt * 1.0001f)
                    assertTrue("faster than asked: ${it.m.vx}", abs(it.m.vx) <= RUN + 1e-3f)
                    last = it.m.vx
                }
                if (mx != 0f) assertTrue(abs(w.m.vx) <= top + 1e-3f)
            }
        }
    }

    /** Distance from letting go to standing still, and when it stood still. */
    private fun stopFrom(mx: Float): Pair<Float, Float> {
        val w = Walker(); w.go(1.5f, mx)
        val x0 = w.x; val t0 = w.t
        var still = -1f
        w.go(1f, 0f) { if (still < 0f && it.m.vx == 0f) still = it.t - t0 }
        assertEquals("a creep after stopping", 0f, w.m.vx, 0f)
        return (w.x - x0) to still
    }

    @Test fun stopping() {
        val (walkD, walkT) = stopFrom(.7f)
        assertTrue("stopped from a walk in $walkD units", walkD in 2f..5f)
        assertTrue("still after $walkT s", walkT in 0f..0.3f)
        val (runD, runT) = stopFrom(1f)
        assertTrue("stopped from a run in $runD units", runD in 6f..12f)
        assertTrue("still after $runT s", runT in 0f..0.35f)
    }

    @Test fun turningRoundFromARun() {
        val w = Walker(); w.go(1.5f, 1f)
        val x0 = w.x; val t0 = w.t
        var furthest = x0
        var back = -1f
        w.go(1f, -1f) { furthest = maxOf(furthest, it.x); if (back < 0f && it.m.vx < 0f) back = it.t - t0 }
        assertTrue("carried ${furthest - x0} past letting go", furthest - x0 <= 10f)
        assertTrue("moving back after $back s", back in 0f..0.15f)
    }

    @Test fun theSameAt60And120Hz() {
        val a = Walker(120); a.go(1f, 1f)
        val b = Walker(60); b.go(1f, 1f)
        assertTrue("120 Hz ${a.x}, 60 Hz ${b.x}", abs(a.x - b.x) < 1.5f)
        a.go(.5f, 0f); b.go(.5f, 0f)
        assertTrue("after stopping: 120 Hz ${a.x}, 60 Hz ${b.x}", abs(a.x - b.x) < 1.5f)
    }

    @Test fun aDiagonalStaysStraight() {
        val w = Walker()
        val c = cos(Math.toRadians(45.0)).toFloat(); val s = sin(Math.toRadians(45.0)).toFloat()
        w.go(.5f, c, -s) {
            if (it.m.speed > 1f) assertEquals(-1f, it.m.vy / it.m.vx, .02f)
        }
        // and the lanes it crosses at a full run: about 17 lanes a second across, 106 along
        assertEquals(106f, w.m.vx, 1f)
        assertEquals(-106f * MoveTuning.laneRatio, w.m.vy * MoveTuning.laneRatio, .3f)
    }

    @Test fun theRunLatches() {
        val m = Locomotion()
        assertFalse(m.running.also { m.targetSpeed(.8f) })
        m.targetSpeed(.8f); assertFalse(m.running)
        m.targetSpeed(.9f); assertTrue(m.running)
        m.targetSpeed(.8f); assertTrue("a relaxing thumb keeps running", m.running)
        assertEquals(RUN, m.targetSpeed(.78f), 0f)
        m.targetSpeed(.7f); assertFalse(m.running)
        assertEquals(WALK, m.targetSpeed(.7f), 0f)
    }

    @Test fun facing() {
        fun push(deg: Double, up: Boolean = true) = Math.toRadians(deg).let { cos(it).toFloat() to (if (up) -1 else 1) * sin(it).toFloat() }
        val (x30, y30) = push(30.0)
        assertEquals(0, Locomotion.streetFacing(1, x30, y30))
        val (x75, y75) = push(75.0)
        assertEquals(1, Locomotion.streetFacing(0, x75, y75))
        val (x75d, y75d) = push(75.0, up = false)
        assertEquals(3, Locomotion.streetFacing(0, x75d, y75d))
        val (x64, y64) = push(64.0)
        assertEquals(0, Locomotion.streetFacing(0, x64, y64))
        assertEquals(1, Locomotion.streetFacing(1, x64, y64))
        // in the dead zone: as it was
        assertEquals(3, Locomotion.streetFacing(3, 0f, 0f))
        // pushing up with a wobble either way never shows the side
        var f = 1
        for (k in -10..10) { f = Locomotion.streetFacing(f, k / 100f, -.9f); assertEquals(1, f) }
        // rooms: the axis pushed a quarter more
        assertEquals(0, Locomotion.roomFacing(0, 1f, .9f))
        assertEquals(1, Locomotion.roomFacing(0, .7f, -.9f))
        assertEquals(1, Locomotion.roomFacing(1, .9f, -.8f))
        assertEquals(2, Locomotion.roomFacing(1, -1f, .7f))
        assertEquals(3, Locomotion.roomFacing(3, 0f, 0f))
    }

    @Test fun theFeetStayOnTheGround() {
        // at a steady walk, a cycle of the legs covers exactly the stride
        val w = Walker(); w.go(1f, .7f)
        val x0 = w.x; val g0 = w.m.gait; var cycles = 0f; var last = g0
        w.go(2f, .7f) { if (it.m.gait < last) cycles += 1f; last = it.m.gait }
        cycles += last - g0
        assertEquals(w.x - x0, cycles * MoveTuning.stride, (w.x - x0) * .01f)
        // at a run the stride lengthens, and the cadence stays a runner's
        val r = Walker(); r.go(1f, 1f)
        assertEquals(41.4f, r.m.stride(), .3f)
        val cadence = r.m.speed / r.m.stride()
        assertTrue("$cadence cycles a second", cadence <= 3.8f)
        // against the art: a cycle is two steps of the planted foot (art pixels, two sheet pixels each). The run's frames
        // splay the legs, so at a full run they carry at least as much of the ground as the walk's do at a walk
        fun drawn(step: Float) = 2f * step * 2f * SpriteArt.PIXEL
        val walkCarried = drawn(RunFrames.WALK_STEP) / MoveTuning.stride
        val runCarried = drawn(RunFrames.RUN_STEP) / r.m.stride()
        assertTrue("the legs carry $walkCarried of a walk, $runCarried of a run", runCarried >= walkCarried && runCarried in .75f..1.05f)
        assertTrue(r.m.runPose)
        // walking straight in across the pavement: the same ground cadence (the lanes are deep ground)
        assertEquals(hypot(3f, 1f / MoveTuning.laneRatio), Locomotion.streetDistance(3f, 1f), 1e-4f)
    }

    @Test fun twoFootfallsACycle() {
        val w = Walker(); w.go(.3f, .7f)
        val f0 = w.m.footfalls; var cycles = 0; var last = w.m.gait
        w.go(3f, .7f) { if (it.m.gait < last) cycles++; last = it.m.gait }
        val f = w.m.footfalls - f0
        assertTrue("$f footfalls in $cycles cycles", f == 2 * cycles || f == 2 * cycles + 1 || f == 2 * cycles - 1)
        // and none standing
        val before = w.m.footfalls
        w.go(.5f, 0f); w.go(1f, 0f)
        assertTrue(w.m.footfalls - before <= 1)
    }

    @Test fun theLeanAndTheHop() {
        val w = Walker(); w.go(1.5f, 1f) { it.m.tilt(it.dt, 1f, true) }
        assertEquals(MoveTuning.leanRun, w.m.lean, .3f)
        assertEquals(1f, w.m.runShare, 1e-3f)
        // braking leans back
        var least = 99f
        w.go(.15f, 0f) { it.m.tilt(it.dt, 1f, true); least = minOf(least, it.m.lean) }
        assertTrue("lean while braking $least", least < MoveTuning.leanRun * .5f)
        // only from the side
        val d = Walker(); d.go(1f, 0f, -1f) { it.m.tilt(it.dt, 1f, false) }
        assertEquals(0f, d.m.lean, 0f)
        val k = Walker(); k.go(1f, .7f) { it.m.tilt(it.dt, 1f, true) }
        assertEquals(0f, k.m.runShare, 1e-4f)
        assertTrue(abs(k.m.lean) < .1f)
        // drawn by whole pixels of the art: a pixel forward at a run, none at a walk
        val r = Walker(); r.go(1.5f, 1f) { it.m.tilt(it.dt, 1f, true) }
        assertEquals(1, r.m.leanPixels())
        assertEquals(0, k.m.leanPixels())
        assertFalse(k.m.runPose)
    }

    @Test fun roomsUseTheSameLegs() {
        val room = InteriorRoom("t", "t", "t", "home", 640, 576, "floorWood", emptyList(), emptyList(), emptyList(), 320f, 300f, 320f, 560f)
        // rooms are short walks: a little quicker than the street, in sprite pixels (and not slower than the old 96 a second)
        val perPixel = MoveTuning.roomPace / SpriteArt.PIXEL
        assertTrue(WALK * perPixel >= 96f)
        fun speedAfter(mx: Float, my: Float): Float {
            val w = RoomWalker(320f, 300f)
            repeat(120) { w.step(room, mx, my, 1f / 120f) }
            val x0 = w.x; val y0 = w.y
            w.step(room, mx, my, 1f / 120f)
            return hypot(w.x - x0, w.y - y0) * 120f
        }
        assertEquals(WALK * perPixel, speedAfter(.7f, 0f), .5f)
        assertEquals(WALK * perPixel / 2f, speedAfter(.25f, 0f), .5f)
        assertEquals(WALK * perPixel, speedAfter(0f, -.7f), .5f)
        // a thumb resting in the stick's dead zone does not move them
        val out = FloatArray(2); Stick.shape(.08f, .05f, out)
        assertEquals(0f, speedAfter(out[0], out[1]), 0f)
        // against a wall: no stored speed bursting out along it
        val w = RoomWalker(320f, 110f)
        val walled = InteriorRoom("t", "t", "t", "home", 640, 576, "floorWood", listOf(com.lifemax.rpg.feature.habits.presentation.scene.IRect(0, 0, 640, 100)),
            emptyList(), emptyList(), 320f, 300f, 320f, 560f)
        repeat(60) { w.step(walled, 0f, -1f, 1f / 120f) }
        assertEquals(0f, w.motion.vy, 0f)
    }

    @Test fun theCameraLooksAhead() {
        val view = 961f
        val c = CameraLead()
        repeat(120) { assertEquals(0f, c.step(1f / 120f, 0f, view), 0f) }
        // running: most of the way to 8% of the view ahead within 1.5 s, never faster than the cap
        val w = Walker()
        var at = -1f
        var prev = 0f
        w.go(3f, 1f) {
            val l = c.step(it.dt, it.m.vx, view)
            val cap = MoveTuning.leadBase + MoveTuning.leadPerSpeed * abs(it.m.vx)
            assertTrue("lead moved ${(l - prev) / it.dt} a second", abs(l - prev) <= cap * it.dt + 1e-4f)
            prev = l
            if (at < 0f && l >= .9f * MoveTuning.lead * view) at = it.t
        }
        assertTrue("90% of the lead after $at s", at in 0f..1.5f)
        // the lead the camera uses is a whole number of device pixels
        val unit = 2.4348f
        val px = c.snapped(unit) * unit
        assertEquals(round(px), px, 1e-3f)
    }

    /** The view's place (the player plus the lead) through [plan], frame by frame: each frame's (time, player x, view). */
    private fun viewThrough(hz: Int, warm: Float, warmPush: Float, plan: List<Pair<Float, Float>>): List<Triple<Float, Float, Float>> {
        val view = 961f
        val c = CameraLead()
        val w = Walker(hz)
        w.go(warm, warmPush) { c.step(it.dt, it.m.vx, view) }
        val out = ArrayList<Triple<Float, Float, Float>>()
        val t0 = w.t
        for ((secs, mx) in plan) w.go(secs, mx) { out += Triple(it.t - t0, it.x, it.x + c.step(it.dt, it.m.vx, view)) }
        return out
    }

    @Test fun theViewStopsWithThePlayer() {
        for (hz in listOf(60, 120)) for (push in listOf(.7f, 1f)) {
            val f = viewThrough(hz, 2f, push, listOf(4f to 0f))
            // the player is still within about a third of a second; the view stops within a tenth of a second of them
            val playerStill = f.indexOfFirst { (_, x, _) -> x == f.last().second }
            val viewStill = f.indexOfFirst { (_, _, v) -> abs(v - f.last().third) < .05f }
            assertTrue("$hz Hz, push $push: player still at ${f[playerStill].first} s", f[playerStill].first <= .35f)
            assertTrue("$hz Hz, push $push: view still at ${f[viewStill].first} s", f[viewStill].first <= f[playerStill].first + .1f)
            // and it never once moves back against them (no slide back, no overshoot)
            for (i in 1 until f.size) assertTrue("$hz Hz: the view moved back at ${f[i].first} s", f[i].third >= f[i - 1].third - 1e-4f)
        }
    }

    @Test fun slowingNeverRunsTheViewBack() {
        // a run, a slow walk on (the lead shrinks towards it), a run back the other way, stops
        for (hz in listOf(60, 120)) {
            val f = viewThrough(hz, 2f, 1f, listOf(3f to .2f, .5f to 0f, 2f to -1f, 1f to 0f))
            var prevX = f[0].second; var prevV = f[0].third
            for (i in 1 until f.size) {
                val (t, x, v) = f[i]
                val dx = x - prevX; val dv = v - prevV
                // the view goes the player's way or stands; never the other way while they move
                if (abs(dx) > 1e-4f) assertTrue("$hz Hz at $t s: player $dx, view $dv", dv * dx >= -1e-5f)
                prevX = x; prevV = v
            }
        }
    }

    @Test fun theEndsAreEasedInto() {
        val lo = 500f; val hi = 3000f
        // never past an end, the same as before in the middle, and continuous with no jump in speed
        var prev = CameraLead.withinEnds(2000f, lo, hi)
        assertEquals(2000f, prev, 0f)
        var prevSpeed = 1f
        var cx = 2000f
        while (cx < hi + 1000f) {
            cx += 1f
            val v = CameraLead.withinEnds(cx, lo, hi)
            assertTrue(v <= hi && v >= prev)
            val speed = v - prev
            assertTrue("a jump in the camera's speed at $cx: $prevSpeed to $speed", abs(speed - prevSpeed) < .02f)
            prev = v; prevSpeed = speed
        }
        assertEquals(hi, prev, .01f)
        assertEquals(lo, CameraLead.withinEnds(-1e4f, lo, hi), .01f)
        // a street shorter than the screen: its middle
        assertEquals(1000f, CameraLead.withinEnds(5f, 1200f, 800f), 0f)
    }
}
