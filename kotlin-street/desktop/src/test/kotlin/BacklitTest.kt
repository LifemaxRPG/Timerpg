package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Backlit
import com.lifemax.rpg.feature.habits.domain.SunEdge
import com.lifemax.rpg.feature.habits.domain.orbAt
import com.lifemax.rpg.feature.habits.presentation.scene.ShaftSprites
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shooting into a low sun: how far the camera looks into it by the hour, the weather and how near
 * the buildings it is (smooth, nothing at midday or at night), the exposure's dark (warm, darkest
 * round the sun, never black), the shafts' openness past the buildings, the motes, the shaft's
 * sprite, and the distance from the sun to the nearest building.
 */
class BacklitTest {
    private fun up(hour: Float) = orbAt(hour).second

    private fun clear(hour: Float, gap: Float = 0f) =
        Backlit.factor(up(hour), 1f, 0f, 0f, 0f, gap, 0f, 1f)

    @Test
    fun strongAtSunriseAndSunsetNothingThroughTheDay() {
        assertTrue(clear(19f) > .9f)
        assertTrue(clear(6.5f) > .9f)
        assertTrue(clear(18f) in .2f..0.5f)
        for (h in listOf(9f, 10f, 12f, 13f, 15f, 17f)) assertEquals("at $h", 0f, clear(h), 1e-4f)
    }

    @Test
    fun itFollowsTheHourSmoothly() {
        // a minute at a time from 15:00 to sunset: never a jump
        var last = clear(15f)
        var h = 15f
        while (h < 20.5f) {
            h += 1f / 60f
            val now = clear(h)
            assertTrue("at $h: $last to $now", kotlin.math.abs(now - last) < .02f)
            last = now
        }
    }

    @Test
    fun nothingAtNightOrWithTheSunGone() {
        assertEquals(0f, Backlit.factor(up(19f), 0f, 0f, 0f, 0f, 0f, 0f, 1f), 0f)
        assertEquals(0f, Backlit.factor(up(19f), 1f, 0f, 0f, 0f, 0f, 0f, 0f), 0f)
    }

    @Test
    fun theWeatherTakesItDown() {
        val sun = clear(19f)
        val overcast = Backlit.factor(up(19f), 1f, 1f, 0f, 0f, 0f, 0f, 1f)
        val covered = Backlit.factor(up(19f), 1f, 0f, 1f, 0f, 0f, 0f, 1f)
        val misty = Backlit.factor(up(19f), 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        assertTrue(overcast < .15f * sun)
        assertTrue(covered < .4f * sun)
        assertTrue(misty < .55f * sun && misty > .3f * sun)
    }

    @Test
    fun strongestAtABuildingsEdgeLessInOpenSky() {
        assertTrue(clear(19f, 0f) > clear(19f, 5f))
        assertTrue(clear(19f, 5f) > clear(19f, 20f))
        assertEquals(Backlit.FAR, Backlit.near(50f), 1e-4f)
        // smooth as the gap closes while the street scrolls
        var g = 20f
        var last = clear(19f, g)
        while (g > 0f) {
            g -= .05f
            val now = clear(19f, g)
            assertTrue(now >= last - 1e-5f && now - last < .01f)
            last = now
        }
    }

    @Test
    fun theSunsGlowLeavingThePictureTakesItAway() {
        assertEquals(1f, Backlit.inView(100f, 100f, 20f, 400f, 800f), 0f)
        assertEquals(0f, Backlit.inView(-100f, 100f, 20f, 400f, 800f), 0f)
        assertTrue(Backlit.inView(-20f, 100f, 20f, 400f, 800f) in .1f..0.9f)
    }

    @Test
    fun theExposureIsWarmDarkestRoundTheSunNeverBlack() {
        for (t in listOf(0f, .2f, .5f, 1f)) {
            val r = Backlit.ink(0, t, 1f)
            val g = Backlit.ink(1, t, 1f)
            val b = Backlit.ink(2, t, 1f)
            assertTrue("never black at $t", r > .1f && g > .1f && b > .1f)
        }
        // warm near the sun: more red than blue
        assertTrue(Backlit.ink(0, 0f, 1f) > Backlit.ink(2, 0f, 1f))
        // darker near the sun than far from it, and nothing at strength 0
        assertTrue(Backlit.shade(0f, 1f) < Backlit.shade(.5f, 1f))
        assertTrue(Backlit.shade(.5f, 1f) < Backlit.shade(1f, 1f))
        assertEquals(1f, Backlit.shade(.3f, 0f), 1e-5f)
        assertEquals(Backlit.shade(1f, 1f), Backlit.shade(3f, 1f), 1e-5f)
        // halfway: halfway to the stop
        assertEquals(1f - .5f * (1f - Backlit.INK_G[0]), Backlit.ink(1, 0f, .5f), 1e-5f)
    }

    @Test
    fun theShaftsAreOpenWhereTheBuildingsLetTheLightThrough() {
        val e = SunEdge()
        val open = FloatArray(Backlit.RAYS)
        val probe = BooleanArray(Backlit.PROBE.size)
        // nothing in the way: every shaft open
        Backlit.shafts(e, 300f, 300f, 20f, 0f, open, probe)
        for (o in open) assertEquals(1f, o, 0f)
        // the sun just behind a building's left edge: the shafts to the left open, to the right
        // shut
        e.box(300f, 200f, 900f, 900f)
        Backlit.shafts(e, 305f, 400f, 20f, 0f, open, probe)
        val right = 0 // angle 0: to the right
        val left = Backlit.RAYS / 2 // angle pi: to the left
        assertEquals(0f, open[right], 0f)
        assertEquals(1f, open[left], 0f)
        // a shaft through the gap counts more than one cut off near the sun
        val mean = open.average().toFloat()
        assertTrue(
            Backlit.shaftStrength(left, open[left], mean) >
                Backlit.shaftStrength(right, open[right], mean)
        )
    }

    @Test
    fun theMotesStayNearTheSunAndAreTheSameEveryTime() {
        for (i in 0 until Backlit.MOTES) for (s in listOf(0f, 10f, 100f, 1000f)) {
            val x = Backlit.moteX(i, s)
            val y = Backlit.moteY(i, s)
            assertTrue(kotlin.math.abs(x) < Backlit.MOTE_REACH + 10f)
            assertTrue(kotlin.math.abs(y) < Backlit.MOTE_REACH + 30f)
            assertEquals(x, Backlit.moteX(i, s), 0f)
            assertTrue(Backlit.moteGlint(i, s) in 0f..1f)
            assertTrue(Backlit.moteSize(i) in .5f..2f)
        }
    }

    @Test
    fun theShaftSpriteIsSoftWhiteAndTheSameEveryTime() {
        val a = ShaftSprites.shaft()
        assertArrayEquals(a, ShaftSprites.shaft())
        assertTrue(ShaftSprites.bytes() < 30_000)
        val w = ShaftSprites.W
        val h = ShaftSprites.H
        for (p in a) if (p != 0) assertEquals(0xFFFFFF, p and 0xFFFFFF)
        fun alpha(i: Int, j: Int) = (a[j * w + i] ushr 24) and 0xFF
        // brightest along its middle, nothing at its end, its root faded in (no hot point)
        val mid = (h - 1) / 2
        assertTrue(alpha(w / 4, mid) > alpha(w / 4, 2))
        assertEquals(0, alpha(w - 1, mid))
        assertTrue(alpha(0, mid) < alpha(w / 8, mid))
        // wider at its end than at its root
        assertTrue(alpha(w * 3 / 4, mid - 8) * 4 > alpha(w * 3 / 4, mid))
        assertTrue(alpha(w / 16, mid - 8) < 3)
    }

    @Test
    fun theGapToTheNearestBuilding() {
        val e = SunEdge()
        assertEquals(Float.POSITIVE_INFINITY, e.gap(0f, 0f), 0f)
        e.box(200f, 100f, 400f, 600f)
        assertEquals(0f, e.gap(300f, 300f), 0f)
        assertEquals(50f, e.gap(150f, 300f), 1e-3f)
        assertEquals(50f, e.gap(300f, 50f), 1e-3f)
        // above the ceiling the building is not drawn
        e.ceiling = 300f
        assertEquals(250f, e.gap(300f, 50f), 1e-3f)
    }
}
