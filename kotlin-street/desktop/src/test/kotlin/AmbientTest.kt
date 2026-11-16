package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Flight
import com.lifemax.rpg.feature.habits.domain.Pass
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The street's own life: every passing thing is a function of the seconds and a seed, the same
 * every time, rare where it should be rare, and nothing at all in a still picture.
 */
class AmbientTest {
    private val seed = HabitsScenarios.TODAY.hashCode()

    /** The share of an hour (sampled every tenth of a second) that [up] is true. */
    private fun share(up: (Float) -> Boolean): Float {
        var n = 0
        for (i in 0 until 36000) if (up(i / 10f)) n++
        return n / 36000f
    }

    @Test
    fun theSameMomentIsTheSameEveryTime() {
        val a = Pass()
        val b = Pass()
        for (t in listOf(12.5f, 400f, 793.8797f, 2000.25f)) {
            assertEquals(Ambient.plane(t, seed, a), Ambient.plane(t, seed, b))
            assertEquals(a.x, b.x, 0f)
            assertEquals(a.y, b.y, 0f)
            assertEquals(Ambient.birds(t, seed, a), Ambient.birds(t, seed, b))
            assertEquals(a.x, b.x, 0f)
        }
        // another day, another sky
        val today = (0 until 600).map { Ambient.plane(it * 5f, seed, a) }
        val other = (0 until 600).map { Ambient.plane(it * 5f, seed + 1, a) }
        assertNotEquals(today, other)
    }

    @Test
    fun aStillPictureHasNothingPassing() {
        val p = Pass()
        assertFalse(Ambient.plane(0f, seed, p))
        assertFalse(Ambient.birds(0f, seed, p))
        assertFalse(Ambient.shootingStar(0f, seed, p))
        assertFalse(Ambient.blimp(0f, seed, p))
        assertFalse(Ambient.car(0f, seed, p))
        assertFalse(p.on)
    }

    @Test
    fun shootingStarsAreRareAndQuick() {
        val p = Pass()
        val up = share { Ambient.shootingStar(it, seed, p) }
        // about one a minute or so, each about a second: never more than a few percent of the time
        assertTrue(up > .005f && up < .04f)
        // one streak runs down and across the upper sky
        var t = 0f
        while (!Ambient.shootingStar(t, seed, p)) t += .01f
        val x0 = p.x
        val y0 = p.y
        Ambient.shootingStar(t + .5f, seed, p)
        assertTrue(p.on)
        assertTrue(p.y > y0)
        assertEquals(p.dir > 0f, p.x > x0)
        assertTrue(y0 < .5f)
        // and is over within its time
        assertFalse(Ambient.shootingStar(t + Ambient.SHOOT_SECONDS + .01f, seed, p))
    }

    @Test
    fun anAirplaneCrossesNowAndThenSteadilyFromEdgeToEdge() {
        val p = Pass()
        val up = share { Ambient.plane(it, seed, p) }
        assertTrue(up > .08f && up < .35f)
        var t = 1f
        while (!Ambient.plane(t, seed, p)) t += .5f
        val dir = p.dir
        var last = p.x
        // it keeps its line: the same way, a little at a time, from past one edge to past the other
        var steps = 0
        while (Ambient.plane(t + 1f, seed, p)) {
            t += 1f
            assertEquals(dir, p.dir, 0f)
            assertTrue((p.x - last) * dir > 0f)
            assertTrue(abs(p.x - last) < .06f)
            assertTrue(p.y in 0f..0.5f)
            last = p.x
            steps++
        }
        assertTrue(steps > 20)
        assertTrue(p.x < -.05f || p.x > 1.05f || last < .05f || last > .95f)
    }

    @Test
    fun birdsFlyInAVAndBeatTheirWings() {
        val p = Pass()
        assertTrue(share { Ambient.birds(it, seed, p) } in .05f..0.3f)
        // the leader in front, the others on alternate arms behind it
        assertEquals(0f, Flight.behind(0), 0f)
        for (i in 1 until Ambient.FLOCK) assertTrue(Flight.behind(i) > 0f)
        assertTrue(Flight.outboard(1) * Flight.outboard(2) < 0f)
        // each beats its wings on its own time, between down and up
        val now = (0 until Ambient.FLOCK).map { Ambient.wing(it, 3f) }
        assertTrue(now.all { it in -1f..1f })
        assertTrue(now.toSet().size == Ambient.FLOCK)
        // it flies in at a height of its own in the sky's band, the same all through the pass
        var t = 1f
        while (!Ambient.birds(t, seed, p)) t += .5f
        val y0 = p.y
        assertTrue(y0 in .2f..0.5f)
        while (Ambient.birds(t, seed, p)) {
            assertEquals(y0, p.y, 0f)
            assertTrue(p.x in -.3f..1.3f)
            t += .5f
        }
    }

    @Test
    fun everyStarTwinklesAtItsOwnPace() {
        val stars = Ambient.stars(72, seed)
        assertEquals(stars, Ambient.stars(72, seed))
        assertTrue(stars.all { it.x in 0f..1f && it.y in 0f..1f && it.alpha in 0f..1f })
        val at = stars.map { Ambient.twinkle(it, 5f) }
        assertTrue(at.all { it in .39f..1.001f })
        // not in step: at one moment they differ, and each one changes over time
        assertTrue(at.toSet().size > 60)
        assertTrue(stars.all { s -> abs(Ambient.twinkle(s, 1f) - Ambient.twinkle(s, 2.3f)) > 0f })
        assertTrue(stars.map { it.speed }.toSet().size > 60)
    }

    @Test
    fun warningLightsBlinkAndFarWindowsChangeSlowly() {
        // a short flash every 1.6 seconds
        val on = (0 until 160).map { Ambient.warning(0, it / 100f) > .5f }
        assertTrue(on.count { it } in 10..40)
        assertEquals(Ambient.warning(3, 1f), Ambient.warning(3, 2.6f), .0001f)
        // each light out of step with the next
        assertNotEquals(Ambient.warning(0, .1f), Ambient.warning(1, .1f))
        // far windows: about the share asked for are lit, and in a second almost none switch
        var lit = 0
        var switched = 0
        for (b in 0 until 50) for (w in 0 until 6) {
            val a = Ambient.farWindowLit(b, w, 100f, .4f)
            if (a) lit++
            if (a != Ambient.farWindowLit(b, w, 101f, .4f)) switched++
        }
        assertTrue(lit in 80..160)
        assertTrue(switched < 15)
        // but over ten minutes many do
        var later = 0
        for (b in 0 until 50) for (w in 0 until 6) if (
            Ambient.farWindowLit(b, w, 100f, .4f) != Ambient.farWindowLit(b, w, 700f, .4f)
        )
            later++
        assertTrue(later > 40)
    }

    @Test
    fun cloudsDriftAndComeRound() {
        for (i in 0 until 3) {
            val a = Ambient.cloudX(i, 0f, seed)
            val b = Ambient.cloudX(i, 10f, seed)
            assertTrue(b > a || b < a - .5f)
            for (t in 0 until 2000 step 7) assertTrue(
                Ambient.cloudX(i, t.toFloat(), seed) in -0.26f..1.26f
            )
        }
        // at rest they stand apart
        assertTrue(abs(Ambient.cloudX(0, 0f, seed) - Ambient.cloudX(1, 0f, seed)) > .2f)
    }

    @Test
    fun theFarCityRepeatsAlongTheStreet() {
        val row = Ambient.skyline(5, 34, 84, 0f, Ambient.SKYLINE_SPAN)
        assertEquals(row, Ambient.skyline(5, 34, 84, 0f, Ambient.SKYLINE_SPAN))
        row.zipWithNext().forEach { (p, q) -> assertEquals(p.x + p.w, q.x, .001f) }
        assertTrue(row.last().x + row.last().w >= Ambient.SKYLINE_SPAN)
        // wherever the street has slid to, a building comes back to where it was a span later
        assertEquals(Ambient.wrap(100f, 30f), Ambient.wrap(100f, 30f + Ambient.SKYLINE_SPAN), .01f)
        for (slide in listOf(-5000f, -10f, 0f, 333f, 9000f)) {
            val x = Ambient.wrap(100f, slide)
            assertTrue(
                x >= -Ambient.SKYLINE_SPAN / 2f - .01f && x < Ambient.SKYLINE_SPAN / 2f + .01f
            )
        }
    }

    @Test
    fun aMissedFloorFlickersAndAFloorMissedTwiceSputters() {
        val flicker = share { Ambient.flicker(7, it, false) }
        val sputter = share { Ambient.flicker(7, it, true) }
        assertTrue(flicker > .7f)
        assertTrue(sputter < flicker - .1f)
        assertTrue(sputter > .2f)
    }

    @Test
    fun treesSwayAndTheFlagWavesGently() {
        for (t in 0 until 600) {
            assertTrue(abs(Ambient.sway(3, t / 10f)) <= 2f)
            assertTrue(abs(Ambient.flag(t / 10f)) <= 1f)
        }
        assertEquals(0, Ambient.step(0f))
        assertEquals(12, Ambient.step(1.01f))
    }

    @Test
    fun theScreenshotMomentsShowWhatTheyPromise() {
        val p = Pass()
        // the night sky's screenshot: an airplane and a shooting star at once, both in view
        assertTrue(Ambient.plane(HabitsScenarios.NIGHT_SKY_AT, seed, p))
        assertTrue(p.x in .1f..0.9f)
        assertTrue(Ambient.shootingStar(HabitsScenarios.NIGHT_SKY_AT, seed, p))
        assertTrue(p.x in .1f..0.9f)
        // the day sky's: an airplane with its contrail and a flock of birds
        assertTrue(Ambient.plane(HabitsScenarios.DAY_SKY_AT, seed, p))
        assertTrue(p.x in .1f..0.9f)
        assertTrue(Ambient.birds(HabitsScenarios.DAY_SKY_AT, seed, p))
        assertTrue(p.x in .1f..0.9f)
    }
}
