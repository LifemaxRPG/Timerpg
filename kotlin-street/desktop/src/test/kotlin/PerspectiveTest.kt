package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Hit
import com.lifemax.rpg.feature.habits.domain.HitKind
import com.lifemax.rpg.feature.habits.domain.Perspective
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.hit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which side face a building shows from where it stands across the view ([Perspective]). */
class PerspectiveTest {
    @Test
    fun theSideIsZeroAtTheMiddleAndFlipsSignAcrossIt() {
        assertEquals(0f, Perspective.side(.5f), 0f)
        assertEquals(0f, Perspective.shift(Perspective.side(.5f)), 0f)
        // left of the middle the right face shows (positive), right of it the left face
        assertTrue(Perspective.side(.3f) > 0f)
        assertTrue(Perspective.side(.7f) < 0f)
        assertTrue(Perspective.shift(Perspective.side(.3f)) > 0f)
        assertTrue(Perspective.shift(Perspective.side(.7f)) < 0f)
    }

    @Test
    fun theSideIsAntisymmetricAboutTheMiddle() {
        for (u in listOf(0f, .1f, .25f, .4f, .45f, .49f)) {
            assertEquals(Perspective.side(u), -Perspective.side(1f - u), 1e-6f)
            val s = Perspective.side(u)
            assertEquals(Perspective.shift(s), -Perspective.shift(-s), 1e-6f)
            assertEquals(Perspective.width(s), Perspective.width(-s), 1e-6f)
        }
    }

    @Test
    fun theSideIsClampedToAWholeFaceAndTheWidthToDX() {
        assertEquals(1f, Perspective.side(0f), 0f)
        assertEquals(-1f, Perspective.side(1f), 0f)
        assertEquals(1f, Perspective.side(-3f), 0f)
        assertEquals(-1f, Perspective.side(4f), 0f)
        assertEquals(B.DX, Perspective.width(1f), 1e-6f)
        assertEquals(B.DX, Perspective.width(-1f), 1e-6f)
        assertEquals(B.DX, Perspective.width(7f), 1e-6f)
        assertEquals(0f, Perspective.width(0f), 0f)
        // the whole face shows a little before the edge of the view, in front
        assertEquals(1f, Perspective.side(.08f), 0f)
        assertTrue(Perspective.side(.12f) < 1f)
    }

    @Test
    fun theSideComesRoundContinuouslyAndMonotonically() {
        var last = Perspective.shift(Perspective.side(0f))
        var u = 0f
        while (u < 1f) {
            u += .005f
            val s = Perspective.shift(Perspective.side(u))
            // no pop anywhere, the middle included
            assertTrue("jump at $u", abs(s - last) < .3f)
            assertTrue("back at $u", s <= last + 1e-6f)
            last = s
        }
        // the easing is monotonic from nothing to the whole width
        var w = 0f
        var s = 0f
        while (s < 1f) {
            s += .01f
            val next = Perspective.width(s.coerceAtMost(1f))
            assertTrue(next >= w)
            w = next
        }
    }

    @Test
    fun zoomedOutTheSideComesRoundMoreGently() {
        assertEquals(Perspective.K_FOCUS, Perspective.k(0f), 0f)
        assertEquals(Perspective.K_STREET, Perspective.k(1f), 0f)
        assertTrue(Perspective.K_STREET < Perspective.K_FOCUS)
        assertTrue(Perspective.side(.3f, Perspective.K_STREET) < Perspective.side(.3f))
        // a building at the edge of the zoomed-out street still shows a side, not the whole face
        assertTrue(Perspective.side(0f, Perspective.K_STREET) > 0f)
        assertTrue(Perspective.side(0f, Perspective.K_STREET) < 1f)
    }

    @Test
    fun aBuildingIsPlacedInTheViewByTheStreetsMiddleAndWidth() {
        // the view's middle at street x 100, 400 units wide: 100 is the middle, 0 a quarter in
        assertEquals(0f, Perspective.sideAt(100f, 100f, 400f), 0f)
        assertEquals(Perspective.side(.25f), Perspective.sideAt(0f, 100f, 400f), 1e-6f)
        assertEquals(Perspective.side(.75f), Perspective.sideAt(200f, 100f, 400f), 1e-6f)
        assertEquals(0f, Perspective.sideAt(50f, 100f, 0f), 0f)
    }

    @Test
    fun theFootprintIsTheFrontPlusTheShownSide() {
        val half = B.W / 2f
        assertEquals(-half, Perspective.left(B.DX), 0f)
        assertEquals(half + B.DX, Perspective.right(B.DX), 0f)
        assertEquals(-half - B.DX, Perspective.left(-B.DX), 0f)
        assertEquals(half, Perspective.right(-B.DX), 0f)
        assertEquals(-half, Perspective.left(0f), 0f)
        assertEquals(half, Perspective.right(0f), 0f)
    }

    @Test
    fun aTapOnTheShownSideFaceFindsItsFloorOnEitherSide() {
        val coffee = HabitsScenarios.projection("city").stacks[0]
        val b = buildingLayout(coffee, ghost = true)
        val read = b.pieces[1]
        val floor = Hit(HitKind.FLOOR, coffee.blocks[0].id)
        // the right face when it shows, and nothing on the left of the front
        assertEquals(floor, b.hit(B.W / 2f + 5f, read.middle, B.DX))
        assertNull(b.hit(-B.W / 2f - 5f, read.middle, B.DX))
        // mirrored for the left face
        assertEquals(floor, b.hit(-B.W / 2f - 5f, read.middle, -B.DX))
        assertNull(b.hit(B.W / 2f + 5f, read.middle, -B.DX))
        // a part-way side is narrower
        assertEquals(floor, b.hit(-B.W / 2f - 2f, read.middle, -4f))
        assertNull(b.hit(-B.W / 2f - 5f, read.middle, -4f))
        // and with no side showing, neither
        assertNull(b.hit(B.W / 2f + 1f, read.middle, 0f))
        assertNull(b.hit(-B.W / 2f - 1f, read.middle, 0f))
        // the front is the same whichever side shows
        assertEquals(floor, b.hit(-60f, read.middle, -B.DX))
        assertEquals(floor, b.hit(60f, read.middle, 0f))
        // the roof face of the top part skews with the side: just over its top at the skewed edge
        val top = b.pieces.last()
        assertEquals(
            Hit(HitKind.GHOST, coffee.id),
            b.hit(-top.width / 2f - B.DX * .4f, top.top + B.DY * .5f, -B.DX),
        )
        assertNull(b.hit(-top.width / 2f - B.DX * .4f, top.top + B.DY * .5f, B.DX))
    }
}
