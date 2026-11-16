package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Act
import com.lifemax.rpg.feature.habits.domain.Gesture
import com.lifemax.rpg.feature.habits.domain.Layers
import com.lifemax.rpg.feature.habits.domain.Part
import com.lifemax.rpg.feature.habits.domain.StreetActs
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLayers
import com.lifemax.rpg.feature.habits.domain.StreetLooks
import com.lifemax.rpg.feature.habits.domain.StreetRig
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetActsTest {
    @Test
    fun theActsEaseInAndOutWithNoJumpAndTheSlowingAddsUp() {
        val g = Gesture()
        val dt = 1f / 60f
        for (k in 0 until 12) {
            val look = k % StreetLooks.COUNT
            var lastW = 0f
            var lastLost = 0f
            var t = 0f
            while (t < 120f) {
                StreetActs.at(7, k, 0, t, look, false, g)
                assertTrue("slot $k at $t", abs(g.w - lastW) < .09f)
                assertTrue(g.lost >= lastLost - 1e-4f)
                assertTrue(g.lost - lastLost <= StreetActs.SLOW * dt + 1e-3f)
                lastW = g.w
                lastLost = g.lost
                t += dt
            }
            // the same moment is the same every time it is asked for
            val a = Gesture()
            StreetActs.at(7, k, 0, 47.3f, look, false, a)
            StreetActs.at(7, k, 0, 47.3f, look, false, g)
            assertEquals(a.act, g.act)
            assertEquals(a.w, g.w, 0f)
        }
        // the envelope's sum, worked out, is the sum of the envelope
        var sum = 0f
        var x = 0f
        while (x < 5f) {
            sum += StreetActs.envelope(x + .0005f, 5f, .45f) * .001f
            x += .001f
        }
        assertEquals(StreetActs.integral(5f, 5f, .45f), sum, .01f)
        assertEquals(StreetActs.integral(2f, 5f, .45f), 2f - .225f, 1e-4f)
    }

    @Test
    fun mostOfTheTimeTheyJustWalkThePhoneIsCommonAndBusyHandsStayBusy() {
        val g = Gesture()
        var acting = 0
        var all = 0
        var phones = 0
        val walkers = 200
        for (k in 0 until walkers) {
            val look = k % StreetLooks.COUNT
            var phoned = false
            var t = 0f
            while (t < 90f) {
                StreetActs.at(3, k, 0, t, look, false, g)
                if (g.w > .5f) acting++
                all++
                if (g.act == Act.PHONE || g.act == Act.TEXT) phoned = true
                if (g.w > 0f && StreetActs.quick(g.act)) assertTrue(g.dur < 2.7f)
                // a lead or a handbag in the near hand: only the far hand and the head act
                StreetActs.at(3, k, 0, t, look, true, g)
                if (g.w > 0f)
                    assertTrue(
                        g.act.name,
                        g.act == Act.FAR_PHONE || g.act == Act.FAR_WATCH || g.act == Act.GLANCE,
                    )
                t += .25f
            }
            if (phoned) phones++
        }
        val share = acting / all.toFloat()
        assertTrue("acting $share of the time", share > .2f && share < .5f)
        assertTrue("$phones of $walkers looked at their phone", phones > walkers / 3)
    }

    @Test
    fun theArmsMoveSmoothlyIntoAnActAndTheHandReachesTheHeadForACall() {
        val out = Layers()
        val g = Gesture()
        for (look in 0 until StreetLooks.COUNT) for (act in Act.values()) {
            if (act == Act.RELAX) continue
            // nobody with a stick kneels to a shoe
            if (act == Act.TIE && StreetLooks.ALL[look].stick) continue
            var lastDeg = Float.NaN
            var t = -.2f
            val dur = 3f
            while (t < dur + .2f) {
                g.clear()
                g.act = act
                g.t = t
                g.dur = dur
                g.w = StreetActs.envelope(t, dur, StreetActs.blend(act))
                g.cup = act == Act.SIP
                val phase = (t / 1.09f).let { it - kotlin.math.floor(it) }
                val f = (phase * StreetGait.FRAMES).toInt() % StreetGait.FRAMES
                // what a stop is for is done standing
                val still = if (act.ordinal >= Act.PEER.ordinal) 1f else 0f
                StreetLayers.lay(look, f, (f + 1) % StreetGait.FRAMES, 0f, still, g, out)
                // the near upper arm's turn from one sixtieth of a second to the next
                var deg = Float.NaN
                for (i in 0 until out.count) if (out.part[i] == Part.UPPER_N) deg = out.deg[i]
                if (!lastDeg.isNaN()) assertTrue("$act look $look at $t", abs(deg - lastDeg) < 9f)
                lastDeg = deg
                t += 1f / 60f
            }
        }
        // on a call the hand is at the side of the head
        g.clear()
        g.act = Act.CALL
        g.w = 1f
        g.t = 1f
        g.dur = 5f
        StreetLayers.lay(3, 0, 1, 0f, 0f, g, out)
        val j = StreetRig.joints(3)[0]
        assertTrue(hypot(out.handX - j.neckX, out.handY - (j.neckY - 2.5f)) < 3.2f)
    }
}
