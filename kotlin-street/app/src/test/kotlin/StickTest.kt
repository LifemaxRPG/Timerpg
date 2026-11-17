package com.timerpg.street

import com.lifemax.rpg.feature.habits.presentation.scene.Stick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The on-screen stick's maths: dead zone, the pull to the axes, the centre following the thumb, holding until neutral. */
class StickTest {
    private val T = 48f
    private val F = 16f

    private fun stick() = Stick(T, F).also { it.down(1L, 500f, 300f) }

    /** The push for a thumb [r] travels out at [deg] degrees (0 right, 90 down the screen). */
    private fun at(r: Float, deg: Double): Stick = stick().also {
        val a = deg * PI / 180.0
        it.move(500f + (r * T * cos(a)).toFloat(), 300f + (r * T * sin(a)).toFloat())
    }

    @Test fun deadZoneIsExactlyNothing() {
        for (k in 0 until 8) {
            val s = at(.11f, k * 45.0)
            assertEquals(0f, s.x, 0f); assertEquals(0f, s.y, 0f); assertEquals(0f, s.amount, 0f)
        }
        // and just outside it the push starts from nothing, with no jump
        for (k in 0 until 8) assertTrue(at(.13f, k * 45.0).amount < .05f)
    }

    @Test fun rimIsAFullPushAndTheKnobStaysOnIt() {
        for (r in listOf(1f, 3f)) for (k in 0 until 8) {
            val deg = k * 45.0 + 30.0
            val s = at(r, deg)
            assertEquals("amount at $r T, $deg°", 1f, s.amount, 1e-4f)
            assertEquals(T, hypot(s.knobX, s.knobY), 1e-3f)
            val a = deg * PI / 180.0
            assertEquals(T * cos(a).toFloat(), s.knobX, 1e-3f)
            assertEquals(T * sin(a).toFloat(), s.knobY, 1e-3f)
        }
    }

    @Test fun theFourWays() {
        at(1f, 0.0).let { assertEquals(1f, it.x, 1e-6f); assertEquals(0f, it.y, 0f) }
        at(1f, 180.0).let { assertEquals(-1f, it.x, 1e-6f); assertEquals(0f, it.y, 0f) }
        // up the screen is away from us, into the buildings: moveLane < 0 (the junction's turn relies on it)
        at(1f, 270.0).let { assertEquals(0f, it.x, 0f); assertEquals(-1f, it.y, 1e-6f) }
        at(1f, 90.0).let { assertEquals(0f, it.x, 0f); assertEquals(1f, it.y, 1e-6f) }
    }

    @Test fun nearAnAxisIsOnIt() {
        // 12° above the street: straight along it
        at(.8f, -12.0).let { assertEquals(0f, it.y, 0f); assertTrue(it.x > .7f) }
        // 82° from the street, upwards: straight in
        at(.8f, -82.0).let { assertEquals(0f, it.x, 0f); assertTrue(it.y < -.7f) }
        at(.8f, 180.0 + 82.0).let { assertEquals(0f, it.x, 0f) }
    }

    @Test fun sweepingRoundIsSmooth() {
        var px = Float.NaN; var py = Float.NaN
        var deg = 0.0
        while (deg <= 360.0) {
            val s = at(1f, deg)
            assertEquals(1f, hypot(s.x, s.y), 1e-4f)
            if (!px.isNaN()) assertTrue("jump at $deg°", hypot(s.x - px, s.y - py) < .02f)
            px = s.x; py = s.y
            deg += .25
        }
    }

    @Test fun theCentreFollowsSoTurningRoundIsShort() {
        val s = stick()
        // dragged three travels right ...
        s.move(500f + 3 * T, 300f)
        assertEquals(1f, s.x, 1e-6f)
        val x = 500f + 3 * T
        // ... then back left: two travels and the slack reach a full push left, not four
        s.move(x - (2 * T + F), 300f)
        assertEquals(-1f, s.x, 1e-5f)
    }

    @Test fun holdUntilNeutral() {
        val s = stick()
        s.move(500f + T, 300f)
        assertEquals(1f, s.x, 1e-6f)
        s.hold()
        assertEquals(0f, s.x, 0f)
        // still pushed: nothing
        s.move(500f + T * .9f, 300f)
        assertEquals(0f, s.x, 0f); assertTrue(s.holding)
        // back in the middle, then out again: it counts again
        s.move(500f + T * .05f, 300f)
        assertFalse(s.holding)
        s.move(500f - T, 300f)
        assertEquals(-1f, s.x, 1e-6f)
        // lifting: nothing, and idle
        s.up()
        assertEquals(0f, s.x, 0f); assertEquals(0f, s.y, 0f); assertFalse(s.active)
        // a move after letting go changes nothing
        s.move(500f + T, 300f)
        assertEquals(0f, s.x, 0f)
    }

    @Test fun aRelaxingThumbCountsAgain() {
        // held at a run; the thumb relaxes back to a third of the way out: it counts again from there, a slow walk
        val s = stick()
        s.move(500f + T, 300f)
        s.hold()
        s.move(500f + T * .6f, 300f)
        assertTrue(s.holding)
        s.move(500f + T * .3f, 300f)
        assertFalse(s.holding)
        assertTrue("a slow walk: ${s.x}", s.x > 0f && s.x < .3f)
    }

    @Test fun nearTheEdgeAFullPushStillFits() {
        // a thumb landing 30 units above the bottom of a 384-high screen: pushing down to 4 from the edge is a full push
        val s = Stick(T, F).also { it.width = 832f; it.height = 384f; it.edge = 4f }
        s.down(1L, 200f, 354f)
        s.move(200f, 380f)
        assertEquals(1f, s.y, 1e-5f)
        // never more sensitive than half the travel: landing on the edge itself, half the travel is a full push
        s.down(2L, 200f, 383f); s.move(200f, 383f + T / 2f)
        assertEquals(1f, s.y, 1e-5f)
        s.down(3L, 200f, 383f); s.move(200f, 383f + T / 4f)
        assertTrue(s.y < 1f)
        // the other ways, and away from the edges, the travel is as ever
        s.down(4L, 200f, 354f); s.move(200f, 354f - T * .5f)
        assertEquals(-(.5f - Stick.DEAD) / (1f - Stick.DEAD), s.y, 1e-4f)
        s.down(5L, 400f, 200f); s.move(400f + T * .5f, 200f)
        assertEquals((.5f - Stick.DEAD) / (1f - Stick.DEAD), s.x, 1e-4f)
    }

    @Test fun aThumbAlreadyNearTheMiddleIsNotHeld() {
        val s = stick()
        s.move(500f + T * .25f, 300f)
        val slow = s.x
        s.hold()
        assertFalse(s.holding)
        assertEquals(slow, s.x, 0f)
        // a quick flick out from there counts at once
        s.move(500f + T, 300f)
        assertEquals(1f, s.x, 1e-6f)
        // and one that had not moved yet (landed, then the door) is not held either
        val t = stick(); t.hold(); assertFalse(t.holding)
        t.move(500f - T, 300f); assertEquals(-1f, t.x, 1e-6f)
    }

    @Test fun theRunsRingFollowsTheEdges() {
        val s = Stick(T, F).also { it.width = 832f; it.height = 384f; it.edge = 4f }
        val r = FloatArray(4)
        // in the open: the travel every way
        s.reaches(400f, 200f, r)
        for (v in r) assertEquals(T, v, 0f)
        // 30 above the bottom: a full push down in 26; landing on the left edge itself, half the travel
        s.reaches(2f, 354f, r)
        assertEquals(T / 2f, r[0], 0f); assertEquals(T, r[1], 0f); assertEquals(T, r[2], 0f); assertEquals(26f, r[3], 1e-4f)
        // and the stick's push agrees: the run's share of the way down is reached that much nearer
        s.down(1L, 200f, 354f); s.move(200f, 354f + Stick.radiusFor(.95f) * 26f)
        assertEquals(.95f, s.amount, 1e-3f)
    }

    @Test fun holdingThenLiftingClears() {
        val s = stick()
        s.move(500f, 300f - T)
        s.hold()
        s.up()
        assertFalse(s.holding)
        s.down(2L, 100f, 100f)
        s.move(100f + T, 100f)
        assertEquals(1f, s.x, 1e-6f)
        assertTrue(abs(s.y) < 1e-6f)
    }
}
