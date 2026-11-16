package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Act
import com.lifemax.rpg.feature.habits.domain.Gait
import com.lifemax.rpg.feature.habits.domain.Gesture
import com.lifemax.rpg.feature.habits.domain.Halt
import com.lifemax.rpg.feature.habits.domain.Layers
import com.lifemax.rpg.feature.habits.domain.Manner
import com.lifemax.rpg.feature.habits.domain.Part
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLayers
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.StreetLooks
import com.lifemax.rpg.feature.habits.domain.StreetManners
import com.lifemax.rpg.feature.habits.domain.StreetRig
import com.lifemax.rpg.feature.habits.domain.Stroller
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.streetLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetMannersTest {
    private val seed = HabitsScenarios.TODAY.hashCode()
    private val city = HabitsScenarios.projection("city")
    private val street = streetLayout(city.stacks.map { buildingLayout(it) })

    /** The first walker of [m] (on foot, alone) from slot [after] on. */
    private fun walkerOf(m: Manner, after: Int = 0): Int {
        val p = Passer()
        var k = after
        while (k < after + 4000) {
            if (
                StreetLife.happens(seed, k) &&
                    StreetLife.kind(seed, k) == Stroller.WALKER &&
                    StreetLife.fill(seed, k, .01f, street.start, street.end, p, street.centers) &&
                    p.manner == m
            )
                return k
            k++
        }
        error("no $m walker")
    }

    @Test
    fun everyMannerKeepsItsPlantedFootWhereItLands() {
        val p = Passer()
        for (m in Manner.entries) {
            val k = walkerOf(m)
            var t = 2f
            var lastX = Float.NaN
            var checked = 0
            while (t < 40f) {
                assertTrue(StreetLife.fill(seed, k, t, street.start, street.end, p, street.centers))
                val j = StreetRig.joints(p.look)[StreetLayers.shown(p.frame, p.still, p.act)]
                // the foot down on the pavement, where it is in the street
                val down = j.footNH < .05f && p.still == 0f
                val x = p.x + p.dir * j.footNX * StreetLife.SIZE
                if (down && !lastX.isNaN()) {
                    // between neighbouring frames the planted foot stays within a baked frame's
                    // rounding of where it was
                    assertEquals("$m slot $k at $t", lastX, x, .5f)
                    checked++
                }
                lastX = if (down) x else Float.NaN
                t += 1f / 60f
            }
            assertTrue("$m checked $checked", checked > 300)
        }
    }

    @Test
    fun theMannersWalkAtTheirOwnPacesTheOlderWithShortSteps() {
        val v = Manner.entries.associateWith { StreetManners.tempo(it) }
        assertTrue(v.getValue(Manner.BRISK) > v.getValue(Manner.YOUNG))
        assertTrue(v.getValue(Manner.YOUNG) > v.getValue(Manner.AVERAGE))
        assertTrue(v.getValue(Manner.AVERAGE) > v.getValue(Manner.STROLLER))
        for (look in 0 until StreetLooks.COUNT) {
            val l = StreetLooks.ALL[look]
            if (l.elder) {
                assertEquals(StreetGait.ELDER_STRIDE, StreetGait.style(look).stride, 0f)
                assertTrue(StreetGait.style(look).lean > 6f)
                // an older walker covers about two thirds of the ground an average one does
                val ratio =
                    StreetLife.speed(look) * StreetManners.tempo(Manner.ELDERLY) /
                        StreetLife.speed(0)
                assertTrue("elder pace $ratio", ratio in .55f..0.72f)
            } else {
                // the street's own step, a third of the walker's height, at the same speed as
                // before: fewer steps for it
                assertEquals(StreetGait.STRIDE, StreetGait.style(look).stride, 0f)
                assertEquals(
                    StreetLife.pace(look) * Gait.SPEED / StreetGait.STRIDE,
                    StreetLife.speed(look),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun theMannersAndTheStopsAreSharedOutAsPlanned() {
        val p = Passer()
        val count = HashMap<Manner, Int>()
        val halts = HashMap<Halt, Int>()
        var walkers = 0
        var stopping = 0
        for (k in 0 until 3000) {
            if (!StreetLife.happens(seed, k)) continue
            val kind = StreetLife.kind(seed, k)
            if (kind == Stroller.CYCLIST) continue
            assertTrue(StreetLife.fill(seed, k, .01f, street.start, street.end, p, street.centers))
            if (kind != Stroller.WALKER) continue
            walkers++
            count[p.manner] = (count[p.manner] ?: 0) + 1
            if (p.stops > 0) stopping++
            for (i in 0 until p.stops) halts[p.stopHalt[i]] = (halts[p.stopHalt[i]] ?: 0) + 1
            // the same walk is the same however often it is asked for
            val q = Passer()
            StreetLife.fill(seed, k, 37.5f, street.start, street.end, q, street.centers)
            StreetLife.fill(seed, k, 37.5f, street.start, street.end, p, street.centers)
            assertEquals(q.x, p.x, 0f)
            assertEquals(q.frame, p.frame)
            assertEquals(q.act.act, p.act.act)
        }
        for (m in Manner.entries) {
            val share = (count[m] ?: 0) / walkers.toFloat()
            assertTrue("$m is $share of the walkers", share in .1f..0.32f)
        }
        val share = stopping / walkers.toFloat()
        assertTrue("$share stop on the way", share in .3f..0.55f)
        for (h in listOf(Halt.PHONE, Halt.WINDOW, Halt.LOOK, Halt.SHOE)) assertTrue(
            "$h ${halts[h]}",
            (halts[h] ?: 0) > 20,
        )
    }

    @Test
    fun aStopEasesInLandsOnThePassingPoseAndEasesOut() {
        val p = Passer()
        var seen = 0
        var k = 0
        val kinds = HashSet<Halt>()
        while (k < 600 && seen < 12) {
            if (
                !StreetLife.happens(seed, k) ||
                    (StreetLife.kind(seed, k) != Stroller.WALKER &&
                        StreetLife.kind(seed, k) != Stroller.PAIR)
            ) {
                k++
                continue
            }
            StreetLife.fill(seed, k, .01f, street.start, street.end, p, street.centers)
            if (p.stops == 0) {
                k++
                continue
            }
            seen++
            var t = 0f
            val dt = 1f / 60f
            var lastX = Float.NaN
            var lastV = Float.NaN
            var lastStill = 0f
            var lastW = 0f
            var held = 0
            while (StreetLife.fill(seed, k, t, street.start, street.end, p, street.centers)) {
                if (!lastX.isNaN()) {
                    val v = (p.x - lastX) * p.dir / dt
                    assertTrue("never backwards", v > -1e-3f)
                    // no jolt: the pace changes smoothly into a stop and out of it
                    if (!lastV.isNaN())
                        assertTrue("slot $k at $t: $lastV to $v", abs(v - lastV) < 2.2f)
                    lastV = v
                }
                assertTrue(abs(p.still - lastStill) < .06f)
                assertTrue("slot $k at $t", abs(p.act.w - lastW) < .1f)
                if (p.still >= 1f) {
                    held++
                    kinds += p.halt
                    assertEquals("standing still", lastX, p.x, 1e-3f)
                    // settled from the passing pose
                    assertEquals(Gait.nearest(Gait.PASSING, StreetGait.FRAMES), p.frame)
                    if (p.kind == Stroller.PAIR)
                        assertEquals(Gait.nearest(Gait.PASSING, StreetGait.FRAMES), p.frame2)
                }
                lastX = p.x
                lastStill = p.still
                lastW = p.act.w
                t += dt
            }
            assertTrue(held > 60)
            k++
        }
        assertTrue(seen >= 8)
        assertTrue("$kinds", kinds.size >= 3)
    }

    @Test
    fun theWalkingStickIsPlantedWithTheFarFoot() {
        val look = (0 until StreetLooks.COUNT).first { StreetLooks.ALL[it].stick }
        val size = StreetLife.pace(look)
        val out = Layers()
        val g = Gesture()
        val len = StreetRig.stickLength(look)
        var lastX = Float.NaN
        var checked = 0
        var travel = 0f
        while (travel < 3f * Gait.STRIDE * size) {
            val phase = StreetGait.phase(travel, size)
            val f = Gait.nearest(phase, StreetGait.FRAMES)
            val n = Gait.neighbour(phase, StreetGait.FRAMES)
            val mix = abs(Gait.offset(phase, StreetGait.FRAMES))
            StreetLayers.lay(look, f, n, mix, 0f, g, out)
            var tipX = Float.NaN
            var tipY = 0f
            for (i in 0 until out.count) if (out.part[i] == Part.STICK) {
                val a = -out.deg[i] * (Math.PI.toFloat() / 180f)
                tipX = out.x[i] + sin(a) * len
                tipY = out.y[i] + cos(a) * len
            }
            assertTrue(!tipX.isNaN())
            assertTrue("tip under the pavement $tipY", tipY < .05f)
            val x = travel + tipX * StreetLife.SIZE
            if (tipY > -.02f) {
                if (!lastX.isNaN()) {
                    assertEquals("at $travel", lastX, x, .4f)
                    checked++
                }
                lastX = x
            } else lastX = Float.NaN
            travel += .05f
        }
        assertTrue("checked $checked", checked > 100)
    }

    @Test
    fun aKneelForAShoeGoesDownAndComesBackUpWithTheHandsAtTheShoe() {
        val out = Layers()
        val g = Gesture()
        g.act = Act.TIE
        g.dur = 4f
        g.t = 2f
        g.w = 1f
        StreetLayers.lay(0, 0, 1, 0f, 1f, g, out)
        var body = -1
        for (i in 0 until out.count) if (out.part[i] < 0) body = out.body(i)
        assertEquals(StreetRig.TOTAL_FRAMES - 1, body)
        // down on one knee: the head well below its standing height
        val stand = StreetRig.joints(0)[StreetRig.STAND_AT]
        val down = StreetRig.joints(0)[StreetRig.TOTAL_FRAMES - 1]
        assertTrue(down.neckY - stand.neckY > 5f)
        assertEquals(
            StreetRig.CROUCH_AT + StreetRig.CROUCH_FRAMES - 1,
            StreetLayers.shown(3, 1f, g),
        )
        // the near hand down at the near shoe
        assertTrue(
            "hand ${out.handX},${out.handY} shoe ${down.footNX}",
            abs(out.handX - down.footNX) < 3f,
        )
        assertTrue(out.handY > -5f)
    }
}
