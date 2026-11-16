package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.KeyLight
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.StreetWeather
import com.lifemax.rpg.feature.habits.domain.WeatherKind
import com.lifemax.rpg.feature.habits.domain.WeatherState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The street's weather is the date's own: the same every time, in sane shares over a year, every
 * factor smooth in the hour, rain leaving the pavement wet and the wet drying; the screenshot day
 * stays clear; the light under cloud is flatter and under mist softer.
 */
class StreetWeatherTest {
    private val year: List<String> = (0 until 365).map { HabitsRules.addDays("2026-01-01", it) }

    @Test
    fun theSameDateHasTheSameWeather() {
        for (day in year.take(40)) {
            val a = StreetWeather.ofDay(day)
            val b = StreetWeather.ofDay(day)
            assertEquals(a.kind, b.kind)
            assertEquals(a.rainFrom, b.rainFrom, 0f)
            assertEquals(a.rainTo, b.rainTo, 0f)
            for (h in 0..47) {
                val x = StreetWeather.at(day, h / 2f)
                val y = StreetWeather.at(day, h / 2f)
                assertEquals(x.cloudiness, y.cloudiness, 0f)
                assertEquals(x.rain, y.rain, 0f)
                assertEquals(x.wet, y.wet, 0f)
                assertEquals(x.mist, y.mist, 0f)
            }
        }
    }

    @Test
    fun theYearHasSaneSharesOfEachKind() {
        val counts = IntArray(WeatherKind.entries.size)
        for (day in year) counts[StreetWeather.ofDay(day).kind.ordinal]++
        val clear = counts[WeatherKind.CLEAR.ordinal]
        val overcast = counts[WeatherKind.OVERCAST.ordinal]
        val rain = counts[WeatherKind.RAIN.ordinal]
        val mist = counts[WeatherKind.MIST.ordinal]
        assertTrue("clear $clear", clear in 170..255)
        assertTrue("overcast $overcast", overcast in 40..100)
        assertTrue("rain $rain", rain in 30..80)
        assertTrue("mist $mist", mist in 18..60)
        // neighbouring days are not all alike: the mixing spreads a run of dates
        var changes = 0
        for (i in 1 until year.size) if (
            StreetWeather.ofDay(year[i]).kind != StreetWeather.ofDay(year[i - 1]).kind
        )
            changes++
        assertTrue("changes $changes", changes > 120)
    }

    @Test
    fun theScreenshotDayIsClear() {
        assertEquals(WeatherKind.CLEAR, StreetWeather.ofDay(HabitsScenarios.TODAY).kind)
        for (h in 0..24) {
            val s = StreetWeather.at(HabitsScenarios.TODAY, h.toFloat())
            assertEquals(0f, s.cloudiness, 0f)
            assertEquals(0f, s.rain, 0f)
            assertEquals(0f, s.wet, 0f)
            assertEquals(0f, s.mist, 0f)
        }
    }

    @Test
    fun everyFactorIsContinuousInTheHourAndInRange() {
        for (day in year) {
            var last: WeatherState? = null
            var h = 0f
            while (h <= 24f) {
                val s = StreetWeather.at(day, h)
                for (v in listOf(s.cloudiness, s.rain, s.wet, s.mist)) assertTrue(v in 0f..1f)
                if (last != null) {
                    // a minute moves nothing by more than a few hundredths
                    assertTrue("$day $h", abs(s.cloudiness - last.cloudiness) < .03f)
                    assertTrue("$day $h", abs(s.rain - last.rain) < .09f)
                    assertTrue("$day $h", abs(s.wet - last.wet) < .12f)
                    assertTrue("$day $h", abs(s.mist - last.mist) < .03f)
                }
                last = s
                h += 1f / 60f
            }
        }
    }

    @Test
    fun rainWetsThePavementAndTheWetDriesAfter() {
        var rainyDays = 0
        for (day in year) {
            val d = StreetWeather.ofDay(day)
            if (d.kind != WeatherKind.RAIN) continue
            rainyDays++
            assertTrue(d.rainFrom >= 6f && d.rainTo <= 24f && d.rainTo > d.rainFrom + 1f)
            var peak = 0f
            var h = 0f
            while (h <= 24f) {
                val s = StreetWeather.at(day, h)
                if (s.rain > 0f) assertTrue("$day $h rain ${s.rain} wet ${s.wet}", s.wet > 0f)
                // the sky is heavy whenever it rains
                if (s.rain > .5f * d.heavy) assertTrue(s.cloudiness > .8f)
                peak = maxOf(peak, s.rain)
                h += 1f / 60f
            }
            assertEquals(d.heavy, peak, .02f)
            // dry before, wet through the rain, drying over about two hours after it
            assertEquals(0f, StreetWeather.at(day, d.rainFrom - .5f).wet, 0f)
            assertTrue(StreetWeather.at(day, d.rainFrom + .5f).wet > .95f)
            val justAfter = StreetWeather.at(day, d.rainTo + .1f)
            assertTrue(justAfter.rain < d.heavy * .95f && justAfter.wet > .9f)
            if (d.rainTo + 1f <= 24f) {
                val w1 = StreetWeather.at(day, d.rainTo + 1f).wet
                assertTrue("$w1", w1 in .3f..0.7f)
            }
            if (d.rainTo + 2.2f <= 24f)
                assertEquals(0f, StreetWeather.at(day, d.rainTo + 2.2f).wet, 0f)
        }
        assertTrue(rainyDays > 0)
    }

    @Test
    fun mistComesAtDawnAndClearsByMidMorning() {
        for (day in year) {
            val d = StreetWeather.ofDay(day)
            if (d.kind != WeatherKind.MIST) continue
            assertEquals(0f, StreetWeather.at(day, 1f).mist, 0f)
            assertTrue(StreetWeather.at(day, 5f).mist > .95f)
            assertTrue(StreetWeather.at(day, 7f).mist > .5f)
            assertEquals(0f, StreetWeather.at(day, 10.5f).mist, 0f)
            assertEquals(0f, StreetWeather.at(day, 12f).rain, 0f)
            // a little cloud with the mist, none at noon
            assertTrue(StreetWeather.at(day, 5f).cloudiness in .2f..0.4f)
            assertEquals(0f, StreetWeather.at(day, 12f).cloudiness, 0f)
        }
    }

    @Test
    fun overcastDaysAreGreyAllDayAndDry() {
        for (day in year) {
            val d = StreetWeather.ofDay(day)
            if (d.kind != WeatherKind.OVERCAST) continue
            for (h in 0..24) {
                val s = StreetWeather.at(day, h.toFloat())
                assertTrue(s.cloudiness >= .75f)
                assertEquals(0f, s.rain + s.wet + s.mist, 0f)
            }
        }
    }

    @Test
    fun cloudFlattensTheLightAndMistSoftensIt() {
        val noon = SceneLight.key(13f)
        val clear = StreetWeather.light(noon, WeatherState.CLEAR)
        assertEquals(noon.strength, clear.strength, 1e-6f)
        assertEquals(0f, clear.flat, 0f)
        val overcast = StreetWeather.light(noon, WeatherState(1f, 0f, 0f, 0f))
        assertEquals(noon.strength * .2f, overcast.strength, 1e-5f)
        assertEquals(1f, overcast.flat, 0f)
        // the direction is the sun's still
        assertEquals(noon.across, overcast.across, 0f)
        assertEquals(noon.down, overcast.down, 0f)
        assertEquals(noon.out, overcast.out, 0f)
        assertEquals(noon.cool, overcast.cool, 0f)
        // no cloud throws its own shadow when the sky is one cloud
        assertEquals(0f, SceneLight.cloudShade(overcast), 0f)
        assertTrue(SceneLight.cloudShade(clear) > 0f)
        val half = StreetWeather.light(noon, WeatherState(.5f, 0f, 0f, 0f))
        assertTrue(SceneLight.cloudShade(half) < SceneLight.cloudShade(clear) * .4f)
        val dawn = SceneLight.key(7f)
        val misty = StreetWeather.light(dawn, WeatherState(.3f, 0f, 0f, 1f))
        assertTrue(misty.strength < dawn.strength * .4f && misty.strength > 0f)
        // a hand-made light keeps its own flatness if the weather adds none
        val flatAlready = KeyLight(0f, 1f, 0f, 1f, 0f, .6f)
        assertEquals(.6f, StreetWeather.light(flatAlready, WeatherState.CLEAR).flat, 0f)
    }
}
