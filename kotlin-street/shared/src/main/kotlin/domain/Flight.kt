package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a flock is at a moment of its pass, for the painter. Positions are in a plan of the world
 * seen from above: [wx] across (dp at the start's distance, 0 under the middle of the view), [wz]
 * away from the viewer (dp; the flock starts [Flight.Z0] off). [near] is how near it is against
 * where it starts, 1 there and smaller the further (its on-screen size and the share of the way
 * from the vanishing height up to its starting height); [x] its centre as a share of the view's
 * width; [ux] and [uz] its heading (across the screen, right positive, and into it, away positive;
 * a unit vector); [fade] 1 clear to 0 gone, the air thickening round it and the pass's ends.
 */
class FlockPose {
    var wx = 0f
    var wz = Flight.Z0
    var near = 1f
    var x = .5f
    var ux = -1f
    var uz = 0f
    var fade = 1f

    /** The part of [fade] that is the pass's ends, not the air. */
    var edge = 1f
}

/**
 * One bird of the flock: its centre ([x] a share of the width, [dy] dp from the flock's height on
 * screen), its [near] and its [fade], all with its own place in the formation.
 */
class BirdPose {
    var x = .5f
    var dy = 0f
    var near = 1f
    var fade = 1f
}

/**
 * The flock's flight, as a path in a plan of the world so the picture has depth: it comes in at one
 * edge at mid-sky, near and heading across and a little towards the viewer, then banks in a wide
 * arc until it is flying away, towards the far city; receding, it shrinks (perspective), sinks
 * towards the vanishing height and thins into the air until it is gone. Some passes mirror from the
 * other edge; now and then one is the same flight the other way: a flock arriving out of the
 * distance and leaving at an edge. Pure and closed-form (a straight leg, an arc of steadily turning
 * heading, a straight leg), a function of the pass's share [t] and its [variant].
 */
object Flight {
    /** How far off the flock starts, in dp of the plan; its size is [Z0] over its distance. */
    const val Z0 = 220f

    /**
     * The width of the view the plan is laid against, in dp (a share of the width is x over it).
     */
    const val VIEW_DP = 400f

    /** Where it enters: this far across from the middle, just past the edge. */
    const val ENTER_X = 250f

    /** How far it flies in a pass, in dp of the plan. */
    const val RUN = 860f

    // the heading, as an angle from "straight across" towards "straight away" (radians): a little
    // towards the viewer at first, nearly straight away at the end; the arc's share of the pass
    private const val HEAD_0 = -.17f
    private const val HEAD_1 = 1.42f
    private const val TURN_FROM = .1f
    private const val TURN_TO = .62f

    /** It is gone (thinned into the air) by the time it is this near. */
    const val GONE_NEAR = .24f

    /** Where the thinning begins. */
    const val THIN_NEAR = .45f

    /**
     * The variants: in from the right and away, in from the left and away, and each the other way
     * round.
     */
    const val FROM_RIGHT = 0
    const val FROM_LEFT = 1
    const val OUT_OF_DISTANCE_TO_RIGHT = 2
    const val OUT_OF_DISTANCE_TO_LEFT = 3

    /** Which flight pass [pass] of [seed] makes: most come in from the right. */
    fun variant(seed: Int, pass: Int): Int {
        val r = Ambient.hash(seed, pass, 46)
        return when {
            r < .55f -> FROM_RIGHT
            r < .82f -> FROM_LEFT
            r < .91f -> OUT_OF_DISTANCE_TO_RIGHT
            else -> OUT_OF_DISTANCE_TO_LEFT
        }
    }

    /** Whether the flock flies the arc backwards: out of the distance to an edge. */
    fun towards(variant: Int): Boolean = variant >= OUT_OF_DISTANCE_TO_RIGHT

    /** -1 when the flight's edge is the right one (it enters or leaves there), 1 for the left. */
    fun side(variant: Int): Float =
        if (variant == FROM_RIGHT || variant == OUT_OF_DISTANCE_TO_RIGHT) -1f else 1f

    /** The heading angle at [s] of the run (0 to 1). */
    private fun heading(s: Float): Float {
        val u = ((s - TURN_FROM) / (TURN_TO - TURN_FROM)).coerceIn(0f, 1f)
        return HEAD_0 + (HEAD_1 - HEAD_0) * u
    }

    /**
     * Writes the flock at [t] of a pass of [variant] into [out]. The forward flight runs from the
     * edge into the distance; the backward one is the same path with [t] reversed and the heading
     * turned round.
     */
    fun flock(t: Float, variant: Int, out: FlockPose) {
        val back = towards(variant)
        val s = (if (back) 1f - t else t).coerceIn(0f, 1f)
        val sideSign = side(variant)
        // integrate the heading: closed form on each leg
        var across = 0f
        var away = 0f
        val k = (HEAD_1 - HEAD_0) / (TURN_TO - TURN_FROM)
        val s1 = minOf(s, TURN_FROM)
        across += cos(HEAD_0) * s1
        away += sin(HEAD_0) * s1
        if (s > TURN_FROM) {
            val s2 = minOf(s, TURN_TO) - TURN_FROM
            across += (sin(HEAD_0 + k * s2) - sin(HEAD_0)) / k
            away += (cos(HEAD_0) - cos(HEAD_0 + k * s2)) / k
        }
        if (s > TURN_TO) {
            val s3 = s - TURN_TO
            across += cos(HEAD_1) * s3
            away += sin(HEAD_1) * s3
        }
        out.wx = sideSign * (-ENTER_X + across * RUN)
        out.wz = Z0 + away * RUN
        val h = heading(s)
        // the heading, turned round when the flight is flown backwards
        val flip = if (back) -1f else 1f
        out.ux = sideSign * cos(h) * flip
        out.uz = sin(h) * flip
        out.near = Z0 / out.wz
        out.x = .5f + out.wx / VIEW_DP * out.near
        // thinning into the air, and a short fade at the edge so nothing pops
        val air = smooth(GONE_NEAR, THIN_NEAR, out.near)
        val edgeT = if (back) t else 1f - t
        out.edge = smooth(0f, .03f, edgeT)
        out.fade = air * out.edge
    }

    /** The formation: how far behind the leader bird [i] flies, and how far out and up. */
    fun behind(i: Int): Float = ((i + 1) / 2) * 13f

    fun outboard(i: Int): Float = ((i + 1) / 2) * 7.5f * if (i % 2 == 0) 1f else -1f

    fun above(i: Int): Float = ((i + 1) / 2) * (if (i % 2 == 0) -1.4f else 1.1f)

    /**
     * Writes bird [i] of the flock at [flock] into [out]: its own place in the V, laid along the
     * flock's heading in the plan, so a bird behind the leader is nearer the viewer when the flock
     * flies away and the arms open across the screen; one on the near arm is bigger when it flies
     * across.
     */
    fun bird(flock: FlockPose, i: Int, out: BirdPose) {
        val a = -behind(i)
        val l = outboard(i)
        // the lateral unit is the heading turned a quarter: (uz, -ux)
        val wx = flock.wx + a * flock.ux + l * flock.uz
        val wz = (flock.wz + a * flock.uz - l * flock.ux).coerceAtLeast(Z0 * .5f)
        out.near = Z0 / wz
        out.x = .5f + wx / VIEW_DP * out.near
        out.dy = above(i) * out.near
        out.fade = smooth(GONE_NEAR, THIN_NEAR, out.near) * flock.edge
    }

    /** How much the flock is seen from behind or in front rather than from the side, 0 to 1. */
    fun endOn(flock: FlockPose): Float = abs(flock.uz)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
