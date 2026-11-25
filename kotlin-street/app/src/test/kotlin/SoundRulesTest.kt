package com.timerpg.street.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundRulesTest {
    @Test
    fun theStreetSoundsLikeItsHourAndWeather() {
        val noon = SoundRules.at(12f, 0f, 0f, 0f, 1)
        assertEquals("day", noon.music)
        assertTrue(noon.beds["crickets_night"]!! < .01f && noon.beds["rain_light"]!! < .01f)
        assertTrue(noon.beds["birds_day"]!! > .02f)
        val night = SoundRules.at(23f, 0f, 0f, 0f, 1)
        assertEquals("night", night.music)
        assertTrue(night.beds["crickets_night"]!! > .3f)
        assertTrue(night.beds["birds_day"]!! < .01f)
        val dawn = SoundRules.at(6.8f, 0f, 0f, 0f, 0)
        assertTrue(dawn.beds["birds_garden"]!! > .03f)
        val storm = SoundRules.at(15f, .9f, .9f, 0f, 1)
        assertEquals("rain", storm.music)
        assertTrue(storm.beds["rain_heavy"]!! > .6f && storm.beds["birds_garden"]!! < .01f && storm.beds["birds_day"]!! < .05f)
        assertTrue(storm.scatter["thunder"]!! > .5f)
        // no crickets in winter
        assertTrue(SoundRules.at(23f, 0f, 0f, 0f, 3).beds["crickets_night"]!! < .01f)
    }

    @Test
    fun eachTownHasItsOwnMusic() {
        assertEquals("harbor", SoundRules.townOf("Maple Hollow · Harbor town · 1987"))
        assertEquals("suburb", SoundRules.townOf("eta · Commuter suburb · 2025"))
        assertEquals("harbor_night", SoundRules.at(23f, 0f, 0f, 0f, 1, "harbor").music)
        assertEquals("rain", SoundRules.at(23f, .9f, .9f, 0f, 1).music)
    }

    @Test
    fun theEraDecidesWhatTheTownHums() {
        // 1925: no recorded modern traffic, a few cars, horses still about; 2025: the town's hum, no horses
        val old = SoundRules.at(12f, 0f, 0f, 0f, 1, place = SoundRules.Place(year = 1925, urban = .8f))
        val now = SoundRules.at(12f, 0f, 0f, 0f, 1, place = SoundRules.Place(year = 2025, urban = .8f))
        for (b in listOf("city_day", "residential", "night_town", "highway_far")) assertTrue("$b in 1925", old.beds[b]!! < .01f)
        assertTrue(now.beds["city_day"]!! > .2f)
        assertTrue(old.scatter["horses"]!! > .3f && now.scatter["horses"]!! < .01f)
        assertTrue(old.trafficPerMinute < now.trafficPerMinute * .5f)
        assertTrue(old.trafficPerMinute > 0f)
        // the police's siren of the era
        assertEquals("bell", SoundRules.sirenStyle(1905))
        assertEquals("mechanical", SoundRules.sirenStyle(1938))
        assertEquals("electronic", SoundRules.sirenStyle(1988))
        // the far traffic's engines: nothing modern in 1925, electric cars only lately, old ones linger a while
        val f25 = EngineProfile.fleet(1925).map { it.first }
        assertTrue(EngineProfile.VINTAGE_SIX in f25 && EngineProfile.ELECTRIC !in f25 && EngineProfile.MUSCLE_V8 !in f25 && EngineProfile.MODERN_FOUR !in f25)
        val f70 = EngineProfile.fleet(1970).map { it.first }
        assertTrue(EngineProfile.MUSCLE_V8 in f70 && EngineProfile.OHV_V8 in f70 && EngineProfile.ELECTRIC !in f70)
        val f25b = EngineProfile.fleet(2025)
        assertTrue(f25b.any { it.first == EngineProfile.ELECTRIC } && f25b.none { it.first == EngineProfile.VINTAGE_SIX })
        assertEquals(1f, f25b.sumOf { it.second.toDouble() }.toFloat(), 1e-4f)
    }

    @Test
    fun thePlaceShapesTheAmbience() {
        val street = SoundRules.Place(green = .1f, urban = .9f)
        val park = SoundRules.Place(green = 1f, urban = 0f)
        val a = SoundRules.at(13f, 0f, 0f, 0f, 1, place = street); val b = SoundRules.at(13f, 0f, 0f, 0f, 1, place = park)
        assertTrue(b.beds["birds_park"]!! > a.beds["birds_park"]!! * 4f)
        assertTrue(a.beds["city_day"]!! > b.beds["city_day"]!!)
        assertTrue(a.trafficPerMinute > b.trafficPerMinute)
        // crickets in the gardens at night more than downtown
        assertTrue(SoundRules.at(23f, 0f, 0f, 0f, 1, place = park).beds["crickets_night"]!! > SoundRules.at(23f, 0f, 0f, 0f, 1, place = street).beds["crickets_night"]!!)
        // the river near, a crowd round, indoors muffled, the police out
        assertTrue(SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(waterM = 20f)).water > .6f)
        assertEquals(0f, SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(waterM = 900f)).water)
        assertTrue(SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(crowd = 10)).crowd > .5f)
        assertTrue(SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(indoors = true)).muffle > .7f)
        assertTrue(SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(police = true)).siren)
        assertTrue(!SoundRules.at(13f, 0f, 0f, 0f, 1).siren)
    }

    @Test
    fun theBellStrikesTheHour() {
        assertEquals(3, SoundRules.strikes(14.99f, 15.01f))
        assertEquals(12, SoundRules.strikes(11.98f, 12.02f))
        assertEquals(0, SoundRules.strikes(15.2f, 15.3f))
        // quiet overnight, and no strikes for the debug slider's jumps
        assertEquals(0, SoundRules.strikes(2.99f, 3.01f))
        assertEquals(0, SoundRules.strikes(9.5f, 14.2f))
        assertEquals(0, SoundRules.strikes(Float.NaN, 9f))
    }

    @Test
    fun sirensOnlyForPoliceOnACall() {
        // a patrol, a quiet search, a stakeout: silent; a chase or a call answered: the siren
        for (a in listOf("patrol", "search_suspect", "stakeout", "question", "arrest", "transport", "off_duty")) assertTrue(a, !SoundRules.sirenFor(a))
        for (a in listOf("chase", "respond_crime", "backup", "call_backup")) assertTrue(a, SoundRules.sirenFor(a))
        assertTrue(SoundRules.SIREN_RANGE_M in 200f..500f)
    }

    @Test
    fun theBirdsAreQuieterAndComeAndGo() {
        // over ten minutes at noon in a leafy street: never louder than about a third, often lulled, the kinds taking turns
        val park = SoundRules.Place(green = 1f, urban = 0f)
        var peak = 0f; var lulls = 0; var sum = 0f; var n = 0; var alone = 0
        var s = 0f
        while (s < 600f) {
            val m = SoundRules.at(13f, 0f, 0f, 0f, 1, place = SoundRules.Place(green = park.green, urban = park.urban, secs = s))
            val d = m.beds["birds_day"]!!; val p = m.beds["birds_park"]!!
            peak = maxOf(peak, d, p); sum += d + p; n++
            if (d + p < .12f) lulls++
            if ((d > .15f) != (p > .15f)) alone++
            s += 2f
        }
        assertTrue("peak $peak", peak <= .32f)
        assertTrue("mean ${sum / n}", sum / n < .35f)
        assertTrue("lulls $lulls of $n", lulls > n / 10)
        assertTrue("one kind alone $alone of $n", alone > n / 5)
        val chorus = (0..300).map { SoundRules.birdChorus(0, it * 2f) }
        assertTrue(chorus.min() < .2f && chorus.max() > .9f)
    }
}
