package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Perspective
import com.lifemax.rpg.feature.habits.domain.StreetVolume
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The furniture's depth: its parallax, its boxes' skew and its round posts' light. */
class StreetVolumeTest {
    @Test
    fun furnitureSlidesOutFromTheMiddleALittleFasterThanTheFacades() {
        // at the middle it stands where it is
        assertEquals(0f, StreetVolume.near(Perspective.shift(Perspective.side(.5f))), 0f)
        // left of the middle (the buildings show their right side) it is drawn further left, and
        // right of it further right: nearer things move further across the view
        val left = StreetVolume.near(Perspective.shift(Perspective.side(.25f)))
        val right = StreetVolume.near(Perspective.shift(Perspective.side(.75f)))
        assertTrue(left < 0f)
        assertTrue(right > 0f)
        assertEquals(-left, right, 1e-5f)
        // and only a touch: never more than a third of a building's side, well inside the gap
        for (u in 0..20) {
            val n = StreetVolume.near(Perspective.shift(Perspective.side(u / 20f)))
            assertTrue(abs(n) <= B.DX / 3f)
            assertTrue(abs(n) < B.GAP / 8f)
        }
        // further out never slides it back in
        var last = 0f
        for (u in 10 downTo 0) {
            val n = StreetVolume.near(Perspective.shift(Perspective.side(u / 20f)))
            assertTrue(n <= last + 1e-6f)
            last = n
        }
    }

    @Test
    fun aBoxShowsTheSideAwayFromTheMiddle() {
        assertEquals(0f, StreetVolume.skew(0f), 0f)
        assertTrue(StreetVolume.skew(1f) > 0f)
        assertTrue(StreetVolume.skew(-1f) < 0f)
        assertEquals(StreetVolume.SKEW, StreetVolume.skew(1f), 1e-5f)
        assertEquals(-StreetVolume.skew(.4f), StreetVolume.skew(-.4f), 1e-6f)
    }

    @Test
    fun aRoundPostIsLitOnTheLightsSideAndTurnsWithTheView() {
        for (from in listOf(-1f, 1f)) {
            val lit = StreetVolume.cylinder(from * .8f, 0f, from, 1f)
            val far = StreetVolume.cylinder(-from * .8f, 0f, from, 1f)
            assertTrue("lit $lit far $far", lit > far + .3f)
        }
        // the core shadow is darker than the very edge, which takes light thrown back from the
        // other side
        val core = StreetVolume.cylinder(.55f, 0f, -1f, 1f)
        val edge = StreetVolume.cylinder(1f, 0f, -1f, 1f)
        assertTrue("core $core edge $edge", core <= edge + 1e-4f)
        // seen from the side away from the light, more of the post is in shade
        var fromLit = 0f
        var fromShade = 0f
        for (k in -10..10) {
            fromLit += StreetVolume.cylinder(k / 10f, -1f, -1f, 1f)
            fromShade += StreetVolume.cylinder(k / 10f, 1f, -1f, 1f)
        }
        assertTrue(fromLit > fromShade)
        // with no key light it is evenly dim, and everything stays in 0..1
        for (k in -10..10) {
            val l = StreetVolume.cylinder(k / 10f, 0f, -1f, 0f)
            assertTrue(l in 0f..0.3f)
            for (s in listOf(-1f, 0f, 1f)) assertTrue(
                StreetVolume.cylinder(k / 10f, s, 1f, 1f) in 0f..1f
            )
        }
        assertTrue(StreetVolume.top(1f) > StreetVolume.top(0f))
    }
}
