package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.LensFlare
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time of year is a smooth function of the date: the leaves come in spring, turn in late
 * October and fall, the snow lies in winter and falls on some days, the blossom has its April
 * window; the same every time, and a test can force a season. And the lens flare's ghosts stand on
 * the far side of the picture from the sun, move with it and with the pan, and go out at night.
 */
class SeasonTest {
    @After
    fun clear() {
        Season.override = null
    }

    private fun factors(s: SeasonState) =
        floatArrayOf(s.leaf, s.mature, s.autumn, s.blossom, s.snow)

    @Test
    fun everyFactorIsSmoothOverTheYearAndAcrossNewYear() {
        var prev = Season.atDay(366f, 2025)
        for (d in 1..366) {
            val s = Season.atDay(d.toFloat(), 2026)
            val a = factors(prev)
            val b = factors(s)
            for (i in a.indices) {
                assertTrue("day $d factor $i ${a[i]} -> ${b[i]}", abs(a[i] - b[i]) < .1f)
                assertTrue(b[i] in 0f..1f)
            }
            assertTrue(s.snowfall in 0f..1f)
            prev = s
        }
    }

    @Test
    fun theSeasonsPeakInTheRightMonths() {
        val january = Season.at("2026-01-15")
        assertEquals(1f, january.snow, 0f)
        assertEquals(0f, january.leaf, 0f)
        assertEquals(0f, january.autumn, 0f)
        assertEquals(0f, january.blossom, 0f)
        val april = Season.at("2026-04-25")
        assertEquals(1f, april.blossom, 0f)
        assertTrue(april.leaf > .3f && april.leaf < 1f)
        assertTrue(april.mature < .3f)
        assertEquals(0f, april.snow, 0f)
        val july = Season.at("2026-07-15")
        assertEquals(1f, july.leaf, 0f)
        assertEquals(1f, july.mature, 0f)
        assertEquals(0f, july.autumn, 0f)
        assertEquals(0f, july.blossom, 0f)
        assertEquals(0f, july.snow, 0f)
        assertTrue(july.quiet)
        val october = Season.at("2026-10-25")
        assertEquals(1f, october.autumn, 0f)
        assertTrue(october.leaf > .5f)
        val december = Season.at("2026-12-05")
        assertTrue(december.leaf < .05f)
        assertTrue(december.autumn < .05f)
        val christmas = Season.at("2026-12-25")
        assertEquals(1f, christmas.snow, 0f)
        // the thaw: snow gone by mid-March, leaves coming by April
        assertEquals(0f, Season.at("2026-03-15").snow, 0f)
        assertTrue(Season.at("2026-04-05").leaf > .05f)
    }

    @Test
    fun itSnowsOnSomeWinterDaysAndNeverInSummer() {
        var days = 0
        for (d in 1..31) if (Season.at("2026-01-%02d".format(d)).snowfall > 0f) days++
        assertTrue("$days snow days in January", days in 6..24)
        for (d in 1..30) assertEquals(0f, Season.at("2026-06-%02d".format(d)).snowfall, 0f)
        // a different year snows on different days
        val a = (1..31).map { Season.at("2026-01-%02d".format(it)).snowfall > 0f }
        val b = (1..31).map { Season.at("2027-01-%02d".format(it)).snowfall > 0f }
        assertNotEquals(a, b)
    }

    @Test
    fun theSameDayIsTheSameSeasonAndABadDayIsSummer() {
        val a = Season.at("2026-10-20")
        val b = Season.at("2026-10-20")
        assertEquals(factors(a).toList(), factors(b).toList())
        assertEquals(a.snowfall, b.snowfall, 0f)
        assertEquals(293, a.dayOfYear)
        assertTrue(Season.at("not a day") === Season.SUMMER)
        assertTrue(Season.at("") === Season.SUMMER)
    }

    @Test
    fun theOverrideForcesASeason() {
        val snow = Season.atDay(20f, 2026)
        Season.override = snow
        assertTrue(Season.at("2026-07-15") === snow)
        Season.override = null
        assertEquals(1f, Season.at("2026-07-15").leaf, 0f)
    }

    // ---- the lens flare

    @Test
    fun flareGhostsStandOnTheFarSideOfTheMiddleInsideThePicture() {
        val w = 1080f
        val h = 2200f
        for (sunX in listOf(90f, 200f, 880f, 990f)) for (sunY in listOf(200f, 400f, 700f)) {
            for (k in 0 until LensFlare.GHOSTS) {
                val gx = LensFlare.ghostX(sunX, w / 2f, k, 0f)
                val gy = LensFlare.ghostY(sunY, h / 2f, k, 0f)
                // across the middle from the sun, both ways
                assertTrue((gx - w / 2f) * (w / 2f - sunX) > 0f)
                assertTrue((gy - h / 2f) * (h / 2f - sunY) > 0f)
                assertTrue(gx > -w * .2f && gx < w * 1.2f)
                // (the furthest may pass the bottom of a tall picture: harmless)
                assertTrue(gy > -h * .2f && gy < h * 1.6f)
            }
            // further along the line in order
            val xs =
                (0 until LensFlare.GHOSTS).map {
                    abs(LensFlare.ghostX(sunX, w / 2f, it, 0f) - w / 2f)
                }
            assertEquals(xs, xs.sorted())
        }
    }

    @Test
    fun flareGhostsMoveWithTheSunAndWithThePan() {
        val k = 3
        assertNotEquals(LensFlare.ghostX(100f, 540f, k, 0f), LensFlare.ghostX(140f, 540f, k, 0f))
        assertNotEquals(LensFlare.ghostX(100f, 540f, k, 0f), LensFlare.ghostX(100f, 540f, k, 120f))
        assertNotEquals(
            LensFlare.ghostY(300f, 1100f, k, 0f),
            LensFlare.ghostY(300f, 1100f, k, 120f),
        )
        // the pan wanders them a little, never far
        for (scroll in 0..4000 step 7) assertTrue(
            abs(LensFlare.wander(k, scroll.toFloat())) <= LensFlare.WANDER
        )
        // the same for the same inputs
        assertEquals(LensFlare.ghostX(100f, 540f, k, 55f), LensFlare.ghostX(100f, 540f, k, 55f), 0f)
    }

    @Test
    fun theFlareIsFullByDayDimAtDuskAndGoneAtNightOrBehindABuilding() {
        assertEquals(1f, LensFlare.strength(0f, 1f), 0f)
        val dusk = LensFlare.strength(.55f, 1f)
        assertTrue("dusk $dusk", dusk > .25f && dusk < .45f)
        assertEquals(0f, LensFlare.strength(1f, 1f), 1e-6f)
        assertEquals(0f, LensFlare.strength(0f, 0f), 0f)
        // the sun inside a building's box is hidden; well outside it, clear; fading between
        assertEquals(0f, LensFlare.clear(50f, 50f, 0f, 0f, 100f, 100f, 20f), 0f)
        assertEquals(1f, LensFlare.clear(150f, 50f, 0f, 0f, 100f, 100f, 20f), 0f)
        assertEquals(1f, LensFlare.clear(50f, -30f, 0f, 0f, 100f, 100f, 20f), 0f)
        val near = LensFlare.clear(108f, 50f, 0f, 0f, 100f, 100f, 20f)
        assertTrue(near > 0f && near < 1f)
        var prev = 0f
        for (x in 100..130) {
            val c = LensFlare.clear(x.toFloat(), 50f, 0f, 0f, 100f, 100f, 20f)
            assertTrue(c >= prev)
            prev = c
        }
    }
}
