package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.CarPass
import com.lifemax.rpg.feature.habits.domain.Gait
import com.lifemax.rpg.feature.habits.domain.Halt
import com.lifemax.rpg.feature.habits.domain.NearLight
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.StreetLooks
import com.lifemax.rpg.feature.habits.domain.Stroller
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.streetLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetLifeTest {
    private val seed = HabitsScenarios.TODAY.hashCode()
    private val city = HabitsScenarios.projection("city")
    private val street = streetLayout(city.stacks.map { buildingLayout(it) })
    private val out = Array(StreetLife.MOST) { Passer() }

    @Test
    fun nobodyAtRestAndEveryoneWithinTheStreetWithValidFramesOtherwise() {
        assertEquals(0, StreetLife.passers(0f, seed, street.start, street.end, out))
        var t = .25f
        var seen = 0
        val kinds = HashSet<Stroller>()
        while (t < 3 * 3600f) {
            val n = StreetLife.passers(t, seed, street.start, street.end, out)
            assertTrue(n <= StreetLife.MOST)
            for (i in 0 until n) {
                val p = out[i]
                assertTrue(p.on)
                assertTrue(
                    "x ${p.x} at $t",
                    p.x >= street.start - StreetLife.MARGIN - 1f &&
                        p.x <= street.end + StreetLife.MARGIN + 1f,
                )
                assertTrue(p.lane in 7f..14f)
                assertTrue(
                    p.frame in 0 until StreetGait.FRAMES && p.next in 0 until StreetGait.FRAMES
                )
                assertTrue(p.mix in 0f..0.5f)
                assertTrue(p.look in 0 until StreetLooks.COUNT)
                assertTrue(p.still in 0f..1f)
                kinds += p.kind
                seen++
            }
            t += 1f
        }
        assertTrue(seen > 1000)
        assertEquals(Stroller.entries.toSet(), kinds)
    }

    @Test
    fun someoneSetsOutEveryFortyToSeventySeconds() {
        var last = -1f
        var gaps = 0
        for (k in 0 until 200) {
            if (!StreetLife.happens(seed, k)) continue
            val s = StreetLife.start(seed, k)
            if (last >= 0f && StreetLife.happens(seed, k - 1)) {
                val gap = s - last
                assertTrue(
                    "gap $gap",
                    gap >= StreetLife.PERIOD - StreetLife.JITTER &&
                        gap <= StreetLife.PERIOD + StreetLife.JITTER,
                )
                gaps++
            }
            last = s
        }
        assertTrue(gaps > 100)
    }

    @Test
    fun theQuickOvertakeTheSlowAndNobodyWalksThroughAnybody() {
        var t = .5f
        var overtakes = 0
        val was = HashMap<Long, Float>()
        while (t < 3 * 3600f) {
            val n = StreetLife.passers(t, seed, street.start, street.end, out, street.centers)
            for (i in 0 until n) for (j in i + 1 until n) {
                val a = out[i]
                val b = out[j]
                // side by side along the street: apart across the pavement
                val ax =
                    if (a.kind == Stroller.PAIR) a.x - a.dir * StreetLife.SIDE_BACK / 2f else a.x
                val bx =
                    if (b.kind == Stroller.PAIR) b.x - b.dir * StreetLife.SIDE_BACK / 2f else b.x
                if (abs(ax - bx) < 4f) {
                    val lowA = a.lane0 <= b.lane0
                    val low = if (lowA) a else b
                    val high = if (lowA) b else a
                    val reach = if (low.kind == Stroller.PAIR) StreetLife.SIDE_OUT else 0f
                    assertTrue(
                        "$t: lanes ${low.lane} (+$reach) and ${high.lane}",
                        high.lane - (low.lane + reach) > 1.5f,
                    )
                }
                // one walking past another going the same way
                if (a.dir == b.dir && a.kind != Stroller.CYCLIST && b.kind != Stroller.CYCLIST) {
                    val key = min(a.slot, b.slot).toLong() * 100_000L + max(a.slot, b.slot)
                    val first = if (a.slot < b.slot) a else b
                    val second = if (a.slot < b.slot) b else a
                    val ahead = (first.x - second.x) * first.dir
                    val before = was[key]
                    if (before != null && before * ahead < 0f) overtakes++
                    was[key] = ahead
                }
            }
            t += .5f
        }
        assertTrue("overtakes $overtakes", overtakes >= 3)
    }

    @Test
    fun aWalkerMovesAtTheGaitsPaceAndTheFramesFollowTheGround() {
        // find a plain walker and follow them for a few seconds
        var t = 1f
        var checked = 0
        while (t < 3600f && checked < 40) {
            val n = StreetLife.passers(t, seed, street.start, street.end, out)
            for (i in 0 until n) {
                val p = out[i]
                if (p.kind != Stroller.WALKER || p.halt != Halt.NONE) continue
                val x0 = p.x
                val travel0 = p.travel
                val dir = p.dir
                val look = p.look
                val v = StreetLife.speed(look)
                val n2 = StreetLife.passers(t + .4f, seed, street.start, street.end, out)
                for (j in 0 until n2) {
                    val q = out[j]
                    if (
                        q.look != look ||
                            q.dir != dir ||
                            q.kind != Stroller.WALKER ||
                            q.halt != Halt.NONE ||
                            abs(q.x - x0) > 20f
                    )
                        continue
                    // the walk is lived, not metered (Gait.liveTravel): never backwards, never
                    // much faster than the pace, and the ground covered is exactly what places them
                    val moved = (q.x - x0) * dir
                    assertTrue("forward: $moved", moved > 0f)
                    assertTrue("pace: $moved vs ${v * .4f}", moved < v * .4f * 1.6f)
                    assertEquals(q.travel - travel0, moved, .01f)
                    val phase = StreetGait.phase(q.travel, q.size)
                    assertEquals(Gait.nearest(phase, StreetGait.FRAMES), q.frame)
                    assertEquals(Gait.neighbour(phase, StreetGait.FRAMES), q.next)
                    checked++
                }
                break
            }
            t += 7f
        }
        assertTrue("checked $checked", checked >= 20)
    }

    @Test
    fun theDogWalkerStopsOnceOnThePassingPoseWithTheDogAhead() {
        var stops = 0
        var k = 0
        while (k < 400 && stops < 5) {
            if (StreetLife.happens(seed, k) && StreetLife.kind(seed, k) == Stroller.DOG_WALKER) {
                val p = Passer()
                var t = 0f
                var held = 0f
                var lastStill = 0f
                var rises = 0
                while (StreetLife.fill(seed, k, t, street.start, street.end, p)) {
                    if (p.still >= .999f) {
                        held += .05f
                        val strides = p.travel / (Gait.STRIDE * p.size)
                        assertEquals("on the passing pose", round(strides), strides, 1e-3f)
                        assertEquals(p.x + p.dir * StreetLife.DOG_LEAD, p.dogX, 1e-3f)
                        assertEquals(1f, p.sniff, 1e-3f)
                    }
                    if (p.still > .5f && lastStill <= .5f) rises++
                    lastStill = p.still
                    t += .05f
                }
                assertEquals("one stop", 1, rises)
                assertEquals(StreetLife.SNIFF, held, .15f)
                stops++
            }
            k++
        }
        assertTrue(stops >= 3)
    }

    @Test
    fun aLampsShadowFallsBehindAWalkerComingTowardsItAndAheadOnceTheyHavePassed() {
        val lens = 100f
        // walking right: before the lamp the shadow leans left (behind), after it right (ahead)
        assertTrue(NearLight.lampLean(lens, 70f) < 0f)
        assertTrue(NearLight.lampLean(lens, 130f) > 0f)
        assertEquals(0f, NearLight.lampLean(lens, 100f), 1e-6f)
        // the further from the lamp, the longer the shadow
        assertTrue(abs(NearLight.lampLean(lens, 140f)) > abs(NearLight.lampLean(lens, 120f)))
        assertTrue(abs(NearLight.lampLean(lens, 60f)) > abs(NearLight.lampLean(lens, 80f)))
        // the reach down the pavement grows with how far out the walker stands, and is modest
        assertTrue(NearLight.lampReach(20f) > NearLight.lampReach(10f))
        assertTrue(NearLight.lampReach(26f) < .5f)
        // the pool's falloff: full under the lamp, nothing past its reach, never rising
        assertEquals(1f, NearLight.lampFall(0f), 1e-6f)
        assertEquals(0f, NearLight.lampFall(NearLight.LAMP_REACH), 1e-6f)
        assertEquals(0f, NearLight.lampFall(300f), 1e-6f)
        var last = 1f
        var d = 0f
        while (d < 100f) {
            val f = NearLight.lampFall(d)
            assertTrue(f <= last + 1e-6f)
            last = f
            d += 1f
        }
    }

    @Test
    fun theHeadlightsWashLeadsTheCarAndCrossesABuildingInAboutTwoSeconds() {
        val car = 0f
        assertEquals(0f, NearLight.sweep(car, 1f, NearLight.SWEEP_REACH + 1f), 1e-6f)
        assertEquals(0f, NearLight.sweep(car, 1f, -NearLight.SWEEP_REACH - 1f), 1e-6f)
        // ahead of the car it is brighter than the same way behind it
        assertTrue(NearLight.sweep(car, 1f, 40f) > 3f * NearLight.sweep(car, 1f, -40f))
        assertTrue(NearLight.sweep(car, -1f, -40f) > 3f * NearLight.sweep(car, -1f, 40f))
        // the brightest spot is ahead of the car, and the far reach is faint
        assertTrue(NearLight.sweepSpot(car, 1f) > car)
        assertTrue(NearLight.sweep(car, 1f, 150f) < .1f)
        val seconds = NearLight.sweepSeconds(StreetLife.CAR_SPEED)
        assertTrue("$seconds", seconds in 1.4f..2.3f)
        // a shadow on the wall is a little bigger than its caster, scaled about the light
        assertTrue(NearLight.wallScale() in 1.1f..1.4f)
    }

    /** How many cars set out a minute over three hours at [hour], and checks every moment. */
    private fun carsAMinute(hour: Float, seen: HashSet<String>): Float {
        val cars = Array(StreetLife.CAR_MOST) { CarPass() }
        val busy = StreetLife.traffic(hour)
        val dt = .25f
        val length = street.end - street.start + 2f * StreetLife.CAR_MARGIN
        var t = dt
        var starts = 0
        while (t < 3 * 3600f) {
            val n = StreetLife.cars(t, seed, street.start, street.end, busy, cars)
            // the pool never fills, so no car is ever dropped mid-pass
            assertTrue(n < StreetLife.CAR_MOST)
            for (i in 0 until n) {
                val c = cars[i]
                assertTrue(c.on)
                assertTrue(
                    c.x >= street.start - StreetLife.CAR_MARGIN - 1f &&
                        c.x <= street.end + StreetLife.CAR_MARGIN + 1f
                )
                assertTrue(c.body in 0 until StreetLife.CAR_BODIES)
                assertTrue(c.paint in 0 until StreetLife.CAR_PAINTS)
                assertEquals(c.paint == StreetLife.TAXI_PAINT, c.taxi)
                // the near lane drives right, the far one left; far lane first, to draw in order
                assertEquals(if (c.lane == 0) 1f else -1f, c.dir)
                if (i > 0) assertTrue(cars[i - 1].lane >= c.lane)
                assertTrue(abs(c.bob) < .2f)
                // the wheels roll the ground covered
                assertEquals(c.t * length, c.wheel * StreetLife.carWheel(c.body), .05f)
                if (c.t * length < c.speed * dt) starts++
                // nobody drives into the car ahead
                for (j in 0 until i) if (cars[j].lane == c.lane)
                    assertTrue(abs(cars[j].x - c.x) > 75f)
                seen.add("body ${c.body}")
                seen.add("paint ${c.paint}")
                if (c.rack) seen.add("rack")
            }
            for (i in n until cars.size) assertFalse(cars[i].on)
            t += dt
        }
        return starts / 180f
    }

    @Test
    fun carsComeBothWaysSeveralAMinuteByDayFewerAtNightInEveryBodyAndPaint() {
        val seen = HashSet<String>()
        val day = carsAMinute(12f, seen)
        val rush = carsAMinute(8.5f, seen)
        val night = carsAMinute(23.5f, seen)
        assertTrue("$day by day", day in 4f..8f)
        assertTrue("$rush in the rush", rush > day)
        assertTrue("$night at night", night in .8f..3f)
        for (b in 0 until StreetLife.CAR_BODIES) assertTrue("body $b", "body $b" in seen)
        for (p in 0 until StreetLife.CAR_PAINTS) assertTrue("paint $p", "paint $p" in seen)
        assertTrue("rack" in seen)
        val cars = Array(StreetLife.CAR_MOST) { CarPass() }
        assertEquals(0, StreetLife.cars(0f, seed, street.start, street.end, 1f, cars))
    }

    @Test
    fun theScreenshotMomentsShowWhatTheyPromise() {
        val n =
            StreetLife.passers(
                HabitsScenarios.STREET_WALKER_AT,
                seed,
                street.start,
                street.end,
                out,
            )
        var mid = false
        for (i in 0 until n) if (out[i].kind == Stroller.WALKER && abs(out[i].x) < 40f) mid = true
        assertTrue("a walker mid-screen", mid)
        // at 23:00, as the screenshot has it: a car in the near lane mid-screen
        val cars = Array(StreetLife.CAR_MOST) { CarPass() }
        val m =
            StreetLife.cars(
                HabitsScenarios.NEAR_CAR_AT,
                seed,
                street.start,
                street.end,
                StreetLife.traffic(23f),
                cars,
            )
        var near = false
        for (i in 0 until m) if (cars[i].lane == 0 && abs(cars[i].x) < 40f) near = true
        assertTrue("a near car mid-screen", near)
    }
}
