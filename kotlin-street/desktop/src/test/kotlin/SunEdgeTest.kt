package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.SunEdge
import com.lifemax.rpg.feature.habits.presentation.scene.GlareSprites
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sun at a building's edge: how much of the disc shows past the boxes and where (the peek hugs
 * the edge on the open side), the sweep of a building's shown side and roof, the open sky round a
 * hidden sun, the glare's strengths at each share shown, and the ray's sprite (small, white, clear
 * of its border, the same every time).
 */
class SunEdgeTest {
    private val r = 20f

    @Test
    fun theOpenSunShowsWholeAndAHiddenOneNot() {
        val e = SunEdge()
        e.box(200f, 100f, 400f, 600f)
        e.measure(100f, 300f, r)
        assertEquals(1f, e.visible, 0f)
        e.measure(300f, 300f, r)
        assertEquals(0f, e.visible, 0f)
        // deep inside, no open sky near it
        assertEquals(0f, e.near, 0f)
    }

    @Test
    fun aSunHalfBehindAnEdgePeeksOnItsOpenSide() {
        val e = SunEdge()
        // the building's left edge at x = 200, the sun's middle on it
        e.box(200f, 100f, 400f, 600f)
        e.measure(200f, 300f, r)
        assertEquals(.5f, e.visible, .06f)
        assertTrue("the peek is left of the edge", e.peekX < 200f)
        assertEquals(300f, e.peekY, 1f)
        // a sliver: the peek hugs the edge
        e.measure(216f, 300f, r)
        assertTrue(e.visible > 0f && e.visible < .1f)
        assertTrue(e.peekX < 200f && e.peekX > 194f)
    }

    @Test
    fun overTheRoofTheSliverIsAbove() {
        val e = SunEdge()
        e.box(200f, 100f, 400f, 600f)
        e.measure(300f, 110f, r)
        assertTrue(e.visible in .05f..0.5f)
        assertTrue(e.peekY < 100f)
        assertEquals(300f, e.peekX, 1f)
    }

    @Test
    fun theSweptSideAndRoofHide() {
        val e = SunEdge()
        // a front 200..400 up to 100, its side shown to the right 20 px and the roof 10 px deep
        e.box(200f, 100f, 400f, 600f, 20f, 10f)
        assertTrue(e.inside(410f, 300f))
        assertTrue(e.inside(415f, 95f))
        assertFalse(e.inside(415f, 595f)) // under the side's foot, which rises with the sweep
        assertFalse(e.inside(205f, 95f)) // above the front's left corner, before the roof's skew
        assertFalse(e.inside(430f, 300f))
        val m = SunEdge()
        m.box(200f, 100f, 400f, 600f, -20f, 10f)
        assertTrue(m.inside(185f, 300f))
        assertFalse(m.inside(395f, 95f))
    }

    @Test
    fun aHiddenSunNearTheEdgeSeesTheSkyOnThatSide() {
        val e = SunEdge()
        e.box(200f, 100f, 400f, 600f)
        e.measure(235f, 300f, r)
        assertEquals(0f, e.visible, 0f)
        assertTrue(e.near > 0f && e.near < .5f)
        assertTrue(e.nearX < 200f)
    }

    @Test
    fun theGlareIsAtItsHottestForASliver() {
        assertEquals(0f, SunEdge.spikes(0f), 0f)
        assertEquals(0f, SunEdge.spikes(1f), 0f)
        assertEquals(0f, SunEdge.bloom(0f), 0f)
        assertEquals(0f, SunEdge.bloom(1f), 0f)
        assertTrue(SunEdge.spikes(.03f) > .95f)
        assertTrue(SunEdge.spikes(.03f) > SunEdge.spikes(.5f))
        assertTrue(SunEdge.spikes(.5f) > SunEdge.spikes(.9f))
        assertTrue(SunEdge.bloom(.5f) > .9f)
        // the rim: none deep behind a building, strongest just behind its edge
        assertEquals(0f, SunEdge.rim(0f, 0f), 0f)
        assertTrue(SunEdge.rim(0f, .4f) > SunEdge.rim(1f, .4f))
        assertEquals(SunEdge.RAY_ANGLE.size, SunEdge.RAY_LENGTH.size)
        assertEquals(SunEdge.RAY_ANGLE.size, SunEdge.RAY_WIDTH.size)
        assertEquals(SunEdge.RAY_ANGLE.size, SunEdge.RAY_ALPHA.size)
    }

    @Test
    fun theRayIsSmallWhiteAndTheSameEveryTime() {
        val a = GlareSprites.spike()
        assertArrayEquals(a, GlareSprites.spike())
        assertTrue(GlareSprites.bytes() < 12_000)
        val w = GlareSprites.W
        val h = GlareSprites.H
        var root = 0
        for (j in 0 until h) for (i in 0 until w) {
            val c = a[j * w + i]
            if (c ushr 24 == 0) continue
            assertEquals(0xFFFFFF, c and 0xFFFFFF)
            assertTrue(i < w - 1 && j > 0 && j < h - 1)
            if (i < 8) root = maxOf(root, c ushr 24)
        }
        // hot at its root, faint at its tip
        assertTrue(root > 150)
        assertTrue((a[(h / 2) * w + w - 6] ushr 24) < 10)
    }
}
