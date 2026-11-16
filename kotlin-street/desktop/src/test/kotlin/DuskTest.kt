package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.domain.occupancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dusk done properly: every light comes on at its own moment across the dusk hour, ramping over a
 * few minutes and never flickering back; the lamps first, the shops next, the homes trickling in
 * with a few late; by 19:30 everything that will be on is on, and off again in reverse by dawn. The
 * golden and the blue hour sit where the sky's tone puts them. The small life is bounded: the
 * television's steps, the pigeons' scare and return.
 */
class DuskTest {
    private val seeds = (0 until 400).map { it * 7919 + 13 }

    @Test
    fun everyLightRampsUpOnceInTheEveningAndDownOnceInTheMorning() {
        for (seed in seeds) for (kind in listOf(Dusk.LAMP, Dusk.SHOP, Dusk.HOME, Dusk.SIGN)) {
            var last = 0f
            var h = 12f
            while (h < 24f) {
                val v = Dusk.lightsOn(seed, h, kind)
                assertTrue("$v at $h", v in 0f..1f && v >= last - 1e-6f)
                last = v
                h += 1f / 120f
            }
            assertEquals(1f, last, 1e-6f)
            last = 1f
            h = 0f
            while (h < 12f) {
                val v = Dusk.lightsOn(seed, h, kind)
                assertTrue("$v at $h", v in 0f..1f && v <= last + 1e-6f)
                last = v
                h += 1f / 120f
            }
            assertEquals(0f, last, 1e-6f)
        }
    }

    @Test
    fun theRampTakesAFewMinutes() {
        for (seed in seeds.take(50)) {
            val m = Dusk.moment(seed, Dusk.HOME)
            assertEquals(0f, Dusk.lightsOn(seed, m - .001f), 1e-6f)
            assertEquals(1f, Dusk.lightsOn(seed, m + Dusk.RAMP), 1e-6f)
            val mid = Dusk.lightsOn(seed, m + Dusk.RAMP / 2f)
            assertTrue(mid > .3f && mid < .7f)
        }
    }

    @Test
    fun theMomentsSpreadAcrossTheDuskHourLampsFirstThenShopsThenHomes() {
        val lamps = seeds.map { Dusk.moment(it, Dusk.LAMP) }
        val shops = seeds.map { Dusk.moment(it, Dusk.SHOP) }
        val homes = seeds.map { Dusk.moment(it, Dusk.HOME) }
        val signs = seeds.map { Dusk.moment(it, Dusk.SIGN) }
        for (m in lamps + shops + homes + signs) assertTrue(m in Dusk.ON_FROM..Dusk.ON_TO)
        assertTrue(lamps.max() < shops.min() + .01f)
        assertTrue(shops.average() < homes.average())
        assertTrue(lamps.max() - lamps.min() < .1f)
        // the homes spread over more than an hour, and some are late
        assertTrue(homes.max() - homes.min() > 1f)
        val late = homes.count { it >= 19f }
        assertTrue("$late late", late in 20..80)
        assertTrue(homes.count { it < 18.5f } > 100)
    }

    @Test
    fun byHalfPastSevenEverythingIsOnAndByDayNothingIs() {
        for (seed in seeds) for (kind in 0..3) {
            assertEquals(1f, Dusk.lightsOn(seed, 19.5f, kind), 0f)
            assertEquals(1f, Dusk.lightsOn(seed, 23f, kind), 0f)
            assertEquals(1f, Dusk.lightsOn(seed, 2f, kind), 0f)
            assertEquals(0f, Dusk.lightsOn(seed, 17.7f, kind), 0f)
            assertEquals(0f, Dusk.lightsOn(seed, 12f, kind), 0f)
            assertEquals(0f, Dusk.lightsOn(seed, 7.3f, kind), 0f)
        }
    }

    @Test
    fun theMorningMirrorsTheEvening() {
        for (seed in seeds.take(40)) {
            val on = Dusk.moment(seed, Dusk.HOME)
            val off = Dusk.offMoment(seed, Dusk.HOME)
            assertEquals(25f, on + off, 1e-5f)
            // homes that came on late go off first; the lamps last
            assertTrue(Dusk.offMoment(seed, Dusk.LAMP) > Dusk.offMoment(seed, Dusk.HOME))
        }
    }

    @Test
    fun theSameHourIsTheSameEveryTimeAndTheScreenshotHoursAreSettled() {
        for (seed in seeds.take(20)) for (h in listOf(18.4f, 19f, 19.67f, 23f)) {
            assertEquals(Dusk.lightsOn(seed, h), Dusk.lightsOn(seed, h), 0f)
            assertEquals(Ambient.lightsOn(seed, h), Dusk.lightsOn(seed, h), 0f)
        }
        // at 19:00 some homes are on and some not (the dusk screenshot shows both)
        val at19 = seeds.map { Dusk.lightsOn(it, 19f) }
        assertTrue(at19.count { it == 1f } > 200)
        assertTrue(at19.count { it == 0f } > 20)
        // at 23:00 every light is on, as before this pass
        assertTrue(seeds.all { Dusk.lightsOn(it, 23f) == 1f })
    }

    @Test
    fun theLampsRunAlongTheStreetWhenTheHourCrossesTheirMoment() {
        // the hour crossed at 100 s: lamp 0 first, then 1, then 2, a gap apart, each over its ramp
        val run = 100f
        assertEquals(0f, Dusk.lampRun(0, 1, 17.8f, run, run), 0f)
        assertEquals(1f, Dusk.lampRun(0, 1, 17.8f, run + Dusk.LAMP_RAMP, run), 0f)
        assertEquals(0f, Dusk.lampRun(1, 1, 17.8f, run + Dusk.LAMP_RAMP, run), 0f)
        assertEquals(1f, Dusk.lampRun(1, 1, 17.8f, run + Dusk.LAMP_GAP + Dusk.LAMP_RAMP, run), 0f)
        assertEquals(0f, Dusk.lampRun(2, 1, 17.8f, run + Dusk.LAMP_GAP + Dusk.LAMP_RAMP, run), 0f)
        var last = 0f
        var t = run
        while (t < run + 20f) {
            val v = Dusk.lampRun(3, 1, 17.8f, t, run)
            assertTrue(v >= last)
            last = v
            t += .05f
        }
        assertEquals(1f, last, 0f)
        // without a crossing (the tab opened later), the hour alone decides
        assertEquals(1f, Dusk.lampRun(5, 1, 18.5f, 3f, -1f), 0f)
        assertEquals(0f, Dusk.lampRun(5, 1, 17f, 3f, -1f), 0f)
    }

    @Test
    fun theGoldenHourSitsBeforeDuskAndAfterDawnTheBlueHourAfterSunsetAndBeforeDawn() {
        assertEquals(0f, Dusk.gold(12f), 0f)
        assertEquals(0f, Dusk.gold(17.5f), 1e-6f)
        assertTrue(Dusk.gold(18f) > .95f)
        assertEquals(0f, Dusk.gold(18.5f), 1e-6f)
        assertTrue(Dusk.gold(7.5f) > .95f)
        assertEquals(0f, Dusk.gold(19.67f), 0f)
        assertEquals(0f, Dusk.blue(18.4f), 0f)
        assertEquals(0f, Dusk.blue(18.75f), 0f)
        assertTrue(Dusk.blue(19.67f) > .95f)
        assertTrue(Dusk.blue(20.2f) in .2f..1f)
        assertEquals(0f, Dusk.blue(20.6f), 0f)
        assertEquals(0f, Dusk.blue(23f), 0f)
        assertTrue(Dusk.blue(6.2f) > .95f)
        assertEquals(0f, Dusk.blue(14f), 0f)
        // never both at once
        var h = 0f
        while (h < 24f) {
            assertTrue(Dusk.gold(h) * Dusk.blue(h) < .05f)
            assertTrue(Dusk.gold(h) in 0f..1f && Dusk.blue(h) in 0f..1f)
            h += .01f
        }
    }

    @Test
    fun theTelevisionIsOnlyOnLateInSomeWindowsAndStepsBetweenAFifthAndSixTenthsOfASecond() {
        val sets = seeds.count { Dusk.hasTv(it) }
        assertTrue("$sets sets", sets in 50..110)
        val seed = seeds.first { Dusk.hasTv(it) }
        for (h in listOf(12f, 19f, 20.9f, .6f, 6f)) assertEquals(0f, Dusk.tv(seed, h, 12.3f), 0f)
        for (h in listOf(21f, 23f, 23.9f, 0f, .4f)) assertTrue(Dusk.tv(seed, h, 12.3f) >= .3f)
        assertEquals(0f, Dusk.tv(seeds.first { !Dusk.hasTv(it) }, 23f, 12.3f), 0f)
        // the steps, sampled finely over a minute
        var prev = Dusk.tv(seed, 23f, 0f)
        var since = 0f
        var steps = 0
        var bright = 0
        var t = 0f
        while (t < 60f) {
            val v = Dusk.tv(seed, 23f, t)
            assertTrue(v in .3f..1f)
            if (v != prev) {
                assertTrue("a step of $since s", since >= .19f && since <= .61f)
                steps++
                since = 0f
                prev = v
            }
            if (v > .9f) bright++
            t += .01f
            since += .01f
        }
        assertTrue("$steps steps", steps in 100..260)
        assertTrue("$bright bright samples", bright > 100)
        assertEquals(Dusk.tv(seed, 23f, 31.7f), Dusk.tv(seed, 23f, 31.7f), 0f)
    }

    @Test
    fun theLateNightScreenshotHasADimTelevisionAndABrightOne() {
        // the tall scenario at 23:00: the first lit window of each done floor is a candidate, and
        // the second once the floor has two lit (the painter's seed is the block's plus 7 a window)
        val sets = tallSets()
        assertTrue("${sets.size} sets", sets.size >= 2)
        val at = HabitsScenarios.TV_AT
        val levels = sets.map { Dusk.tv(it, 23f, at) }
        assertTrue("$levels", levels.any { it < .5f } && levels.any { it > .9f })
    }

    companion object {
        /** The windows with a television in the tall scenario, as the painter seeds them. */
        fun tallSets(): List<Int> =
            HabitsScenarios.projection("tall")
                .stacks
                .flatMap { stack ->
                    val layout = buildingLayout(stack)
                    stack.blocks
                        .filter { it.done }
                        .flatMap { b ->
                            val piece =
                                layout.pieces.first { it.id == b.id && it.kind == PieceKind.FLOOR }
                            val lit = occupancy(b.reps, floorWindows(piece.height).size).lit
                            List(lit) { k -> b.id.hashCode() + k * 7 }
                        }
                }
                .distinct()
                .filter { Dusk.hasTv(it) }
    }

    @Test
    fun theSteamIsDenserAtNightAndInTheCold() {
        assertEquals(1f, Dusk.steam(23f, 150), 0f)
        assertEquals(1f, Dusk.steam(7f, 150), 0f)
        assertEquals(.25f, Dusk.steam(13f, 150), 1e-6f)
        assertTrue(Dusk.steam(13f, 20) > Dusk.steam(13f, 150))
        assertTrue(Dusk.steam(10f, 150) in .25f..1f)
        for (j in 0 until Dusk.PUFFS) {
            assertTrue(Dusk.puff(5, j, 12.3f) in 0f..1f)
            assertEquals(Dusk.puff(5, j, 12.3f), Dusk.puff(5, j, 12.3f + Dusk.PUFF_SECONDS), 1e-4f)
        }
        val vents = seeds.count { Dusk.ventOn(it) }
        assertTrue("$vents vents", vents in 160..240)
    }

    @Test
    fun thePigeonsScatterWithinTheirReachAndComeBack() {
        assertTrue(Dusk.scared(24.9f))
        assertFalse(Dusk.scared(25f))
        assertFalse(Dusk.scared(60f))
        assertEquals(Dusk.IDLE, Dusk.pigeonPhase(-1e9f))
        assertEquals(Dusk.FLYING, Dusk.pigeonPhase(0f))
        assertEquals(Dusk.FLYING, Dusk.pigeonPhase(Dusk.FLIGHT - .01f))
        assertEquals(Dusk.GONE, Dusk.pigeonPhase(Dusk.FLIGHT + 1f))
        assertEquals(Dusk.LANDING_PHASE, Dusk.pigeonPhase(Dusk.FLIGHT + Dusk.AWAY + .1f))
        assertEquals(Dusk.IDLE, Dusk.pigeonPhase(Dusk.FLIGHT + Dusk.AWAY + Dusk.LANDING + .1f))
        // the lift rises through the take-off and falls through the landing, for each bird
        for (i in 0 until 3) {
            var last = 0f
            var t = 0f
            while (t < Dusk.FLIGHT) {
                val v = Dusk.pigeonLift(t, i)
                assertTrue(v in 0f..1f && v >= last)
                last = v
                t += .02f
            }
            assertEquals(1f, last, 1e-5f)
            assertEquals(1f, Dusk.pigeonLift(Dusk.FLIGHT + 3f, i), 0f)
            last = 1f
            t = Dusk.FLIGHT + Dusk.AWAY
            while (t < Dusk.FLIGHT + Dusk.AWAY + Dusk.LANDING) {
                val v = Dusk.pigeonLift(t, i)
                assertTrue(v in 0f..1f && v <= last)
                last = v
                t += .02f
            }
            assertTrue("$last", last < .05f)
            assertEquals(0f, Dusk.pigeonLift(Dusk.FLIGHT + Dusk.AWAY + Dusk.LANDING, i), 0f)
            assertEquals(0f, Dusk.pigeonLift(-5f, i), 0f)
        }
        assertEquals(3, Dusk.pigeons(1, true))
        assertTrue(seeds.count { Dusk.pigeons(it, false) == 2 } in 100..180)
        for (i in 0 until 3) {
            assertTrue(Dusk.pigeonAt(9, i) in .1f..9f)
            assertTrue(Dusk.pigeonFrame(9, i, 4.4f) in 0..2)
            assertTrue(Dusk.wingFrame(4.4f, i) in 3..4)
            assertTrue(Dusk.pigeonFacing(9, i, 4.4f) == 1f || Dusk.pigeonFacing(9, i, 4.4f) == -1f)
        }
    }
}
