package com.timerpg.street.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundRulesTest {
    @Test
    fun theStreetSoundsLikeItsHourAndWeather() {
        val noon = SoundRules.at(12f, 0f, 0f, 0f, 1)
        assertEquals("day", noon.music)
        assertTrue(noon.beds["city_day"]!! > .4f && noon.beds["crickets_night"]!! < .01f && noon.beds["rain_light"]!! < .01f)
        val night = SoundRules.at(23f, 0f, 0f, 0f, 1)
        assertEquals("night", night.music)
        assertTrue(night.beds["crickets_night"]!! > .3f && night.beds["city_day"]!! < .05f)
        val dawn = SoundRules.at(6.8f, 0f, 0f, 0f, 0)
        assertTrue(dawn.beds["birds_morning"]!! > .3f)
        val storm = SoundRules.at(15f, .9f, .9f, 0f, 1)
        assertEquals("rain", storm.music)
        assertTrue(storm.beds["rain_heavy"]!! > .6f && storm.beds["birds_morning"]!! < .01f)
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
}
