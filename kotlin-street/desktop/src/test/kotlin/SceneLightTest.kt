package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.SceneLight
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLightTest {
    @Test
    fun theLightIsAUnitVectorTravellingDownAndOutAtEveryHour() {
        var h = 0f
        while (h < 24f) {
            val l = SceneLight.key(h)
            val n = sqrt(l.across * l.across + l.down * l.down + l.out * l.out)
            assertEquals("length at $h", 1f, n, 1e-3f)
            assertTrue("down at $h", l.down > .15f)
            assertTrue("out at $h", l.out >= 0f)
            assertTrue("strength at $h", l.strength in 0f..1f)
            h += .25f
        }
    }

    @Test
    fun morningShadowsLeanRightNoonShadowsAreShortEveningShadowsLeanLeft() {
        val morning = SceneLight.key(8f)
        val noon = SceneLight.key(13f)
        val evening = SceneLight.key(17f)
        assertTrue(morning.lean > .5f)
        assertTrue(evening.lean < -.5f)
        assertTrue(abs(noon.lean) < .2f)
        assertTrue(noon.reach < morning.reach)
        // a lamp post's shadow is never longer than a few times its height
        assertTrue(hypot(morning.lean, morning.reach) < 4.5f)
    }

    @Test
    fun theSunHandsOverToTheMoonWithNoShadowToPop() {
        // the sun sets to nothing, then the moon rises from nothing: strength is continuous
        var h = 17f
        var last = SceneLight.key(h).strength
        while (h <= 23f) {
            h += .05f
            val s = SceneLight.key(h).strength
            assertTrue("jump at $h: $last to $s", abs(s - last) < .03f)
            last = s
        }
        assertEquals(0f, SceneLight.key(20.5f).strength, 1e-3f)
        assertEquals(0f, SceneLight.key(5.5f).strength, 1e-3f)
        val moon = SceneLight.key(1f)
        assertEquals(1f, moon.cool, 0f)
        assertTrue(moon.strength > .2f && moon.strength <= SceneLight.MOON)
        val sun = SceneLight.key(13f)
        assertEquals(0f, sun.cool, 0f)
        assertTrue(sun.strength > .95f)
        assertTrue(SceneLight.key(19f).strength < .5f)
    }

    @Test
    fun cloudsShadeByDayOnlyAndTheirShadowsLeanWithTheLight() {
        assertTrue(SceneLight.cloudShade(SceneLight.key(13f)) > .1f)
        assertEquals(0f, SceneLight.cloudShade(SceneLight.key(1f)), 0f)
        assertTrue(SceneLight.cloudDrop(SceneLight.key(8f)) > 20f)
        assertTrue(SceneLight.cloudDrop(SceneLight.key(17f)) < -20f)
    }
}

class NearLightTest {
    @Test
    fun shadowsFanOutFromTheLightAndSwingAsTheStreetScrollsUnderIt() {
        val noon = SceneLight.key(13f)
        assertEquals(noon.lean, SceneLight.lean(noon, 0f), 1e-6f)
        // a caster to the right of the light leans right, further the further it stands
        assertTrue(SceneLight.lean(noon, .5f) > SceneLight.lean(noon, .2f))
        assertTrue(SceneLight.lean(noon, .2f) > 0f)
        assertTrue(SceneLight.lean(noon, -.2f) < 0f)
        // and never without end: a caster far off the view leans no more than one at its edge
        assertEquals(SceneLight.lean(noon, 1.5f), SceneLight.lean(noon, 4f), 1e-6f)
        // continuous through the light itself: no jump as a building passes under the sun
        var last = SceneLight.lean(noon, -1f)
        var o = -1f
        while (o <= 1f) {
            o += .01f
            val l = SceneLight.lean(noon, o)
            assertTrue(abs(l - last) < .02f)
            last = l
        }
    }

    @Test
    fun aFacadeIsLitFromTheSideTheLightStandsOn() {
        assertEquals(0f, SceneLight.sunSide(0f), 0f)
        // a building to the right of the light is lit from its left
        assertTrue(SceneLight.sunSide(.3f) < 0f)
        assertTrue(SceneLight.sunSide(-.3f) > 0f)
        assertEquals(-1f, SceneLight.sunSide(2f), 0f)
        assertEquals(1f, SceneLight.sunSide(-2f), 0f)
    }
}
