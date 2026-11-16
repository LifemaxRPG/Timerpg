package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BirdPose
import com.lifemax.rpg.feature.habits.domain.Flight
import com.lifemax.rpg.feature.habits.domain.FlockPose
import com.lifemax.rpg.feature.habits.domain.Pass
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flock's flight has depth: it comes in at an edge, near and large, banks and flies away into
 * the distance, shrinking, sinking towards the vanishing height and fading until it is gone; the
 * mirrored and the reversed variants are the same path the other way; the formation keeps its shape
 * along the heading; and every step of it is small.
 */
class FlightTest {
    private val steps = (Ambient.BIRD_SECONDS * 60f).toInt()

    @Test
    fun theFlightIsContinuousAndNeverTooNear() {
        val a = FlockPose()
        val b = FlockPose()
        val bird = BirdPose()
        for (v in 0 until 4) {
            Flight.flock(0f, v, a)
            for (k in 1..steps) {
                Flight.flock(k.toFloat() / steps, v, b)
                assertTrue("variant $v step $k", abs(b.x - a.x) < .004f)
                assertTrue(abs(b.near - a.near) < .004f)
                assertTrue(abs(b.fade - a.fade) < .03f)
                assertTrue(abs(b.ux - a.ux) < .01f && abs(b.uz - a.uz) < .01f)
                assertEquals(1f, sqrt(b.ux * b.ux + b.uz * b.uz), .001f)
                assertTrue(b.near in .2f..1.15f)
                for (i in 0 until Ambient.FLOCK) {
                    Flight.bird(b, i, bird)
                    assertTrue("bird $i near ${bird.near}", bird.near in .15f..1.35f)
                    assertTrue(bird.fade in 0f..1f)
                }
                Flight.flock(k.toFloat() / steps, v, a)
            }
        }
    }

    @Test
    fun itComesInAtTheEdgeAndGoesAwayIntoTheDistance() {
        val p = FlockPose()
        for (v in listOf(Flight.FROM_RIGHT, Flight.FROM_LEFT)) {
            val right = v == Flight.FROM_RIGHT
            Flight.flock(0f, v, p)
            // in just past the edge, near, heading across and a little towards the viewer
            assertTrue(if (right) p.x > 1.05f else p.x < -.05f)
            assertEquals(1f, p.near, .001f)
            assertTrue(if (right) p.ux < -.9f else p.ux > .9f)
            assertTrue(p.uz < 0f)
            // then it turns away: once turned, it only recedes, shrinking and fading
            var last = Float.MAX_VALUE
            var lastFade = 2f
            var turned = false
            for (k in 0..steps) {
                Flight.flock(k.toFloat() / steps, v, p)
                if (p.uz > .9f) turned = true
                if (turned) {
                    assertTrue(p.near < last)
                    assertTrue(p.fade <= lastFade + .001f)
                    last = p.near
                    lastFade = p.fade
                }
            }
            assertTrue(turned)
            // gone by the end, small, somewhere in the middle of the view
            Flight.flock(1f, v, p)
            assertEquals(0f, p.fade, .001f)
            assertTrue("ends at ${p.near}", p.near < .3f)
            assertTrue(p.x in .25f..0.75f)
        }
    }

    @Test
    fun theMirrorIsExactAndTheReverseFlightIsTheSamePathBackwards() {
        val a = FlockPose()
        val b = FlockPose()
        for (k in 0..20) {
            val t = k / 20f
            Flight.flock(t, Flight.FROM_RIGHT, a)
            Flight.flock(t, Flight.FROM_LEFT, b)
            assertEquals(1f - a.x, b.x, .001f)
            assertEquals(a.near, b.near, .001f)
            assertEquals(-a.ux, b.ux, .001f)
            Flight.flock(1f - t, Flight.OUT_OF_DISTANCE_TO_RIGHT, b)
            assertEquals(a.x, b.x, .001f)
            assertEquals(a.near, b.near, .001f)
            // heading turned round
            assertEquals(-a.ux, b.ux, .001f)
            assertEquals(-a.uz, b.uz, .001f)
        }
        // out of the distance: faded and small at first, out past the edge at the end
        Flight.flock(0f, Flight.OUT_OF_DISTANCE_TO_LEFT, a)
        assertEquals(0f, a.fade, .001f)
        assertTrue(a.near < .3f)
        Flight.flock(1f, Flight.OUT_OF_DISTANCE_TO_LEFT, a)
        assertTrue(a.x < -.05f && a.fade > .99f)
    }

    @Test
    fun theFormationLiesAlongTheHeading() {
        val p = FlockPose()
        val leader = BirdPose()
        val follower = BirdPose()
        // flying away: the followers are nearer the viewer than the leader, and the arms open
        // across the screen
        Flight.flock(.9f, Flight.FROM_RIGHT, p)
        Flight.bird(p, 0, leader)
        Flight.bird(p, 6, follower)
        assertTrue(follower.near > leader.near)
        Flight.bird(p, 1, follower)
        val arm1 = follower.x
        Flight.bird(p, 2, follower)
        assertTrue((arm1 - leader.x) * (follower.x - leader.x) < 0f)
        // flying across: one arm is nearer than the other
        Flight.flock(.05f, Flight.FROM_RIGHT, p)
        Flight.bird(p, 1, leader)
        Flight.bird(p, 2, follower)
        assertTrue(abs(leader.near - follower.near) > .03f)
    }

    @Test
    fun everyVariantHappensAndMostComeInFromTheRight() {
        val counts = IntArray(4)
        for (k in 0 until 400) counts[Flight.variant(7, k)]++
        assertTrue(counts.all { it > 10 })
        assertTrue(counts[Flight.FROM_RIGHT] > counts[Flight.FROM_LEFT])
        assertTrue(counts[Flight.FROM_RIGHT] + counts[Flight.FROM_LEFT] > 300)
    }

    @Test
    fun theScheduleReportsTheFlightAndIsStillAtRestAtZero() {
        val p = Pass()
        val seed = 12345
        assertTrue(!Ambient.birds(0f, seed, p))
        var t = 1f
        while (!Ambient.birds(t, seed, p)) t += .5f
        val pose = FlockPose()
        Flight.flock(p.t, Flight.variant(seed, p.pass), pose)
        assertEquals(pose.x, p.x, .0001f)
        assertEquals(Ambient.birdsHeight(seed, p.pass), p.y, 0f)
        assertTrue(abs(p.dir) == 1f)
    }
}
