package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/*
 * The lobby's visitor, as a short play: they walk out of the door (an easy start: up to pace in a third of a
 * second), slow to a gentle stop with the last half-step, turn to face the street, stand and look, turn back, and
 * walk in at pace. Their footfalls follow the distance they have walked (see [Gait]), so the feet never slide, and the
 * walk out is a whole number of strides so that it ends on the passing pose, the one the turn starts from.
 * They walk like a person: at their own tempo, with the live irregularities of [Gait.liveOffset] (faded in and out
 * so the walk still starts at the door and stops on the passing pose), and while they stand they shift their weight
 * from foot to foot and, most of them, glance at their watch before turning back.
 * Pure arithmetic, no allocation: the scene hands in a [Stroll] to fill.
 */

/** What the visitor is doing and which baked frames show it: filled in by [Visit.at]. */
class Stroll {
    /** Whether they are in the window at all. */
    var here = false

    /** Where the middle of their pelvis is, from the door side. */
    var x = 0f

    /** Which way they face: 1 towards the street's far end, -1 back to the door. */
    var dir = 1f

    /** [WALK] and [TURN] show frames of the walking activity, [STAND] of looking out. */
    var mode = STAND

    /** The frame that shows, the one beside it, and how much of that one is mixed in (0 to .5). */
    var frame = 0
    var next = 0
    var mix = 0f

    /**
     * The whole figure's drift from [x] (the weight shifting from foot to foot while they stand)
     * and its rise (a step's extra bob, a limp's dip, a settle between the feet), in the rig's
     * units: scale by the size the figure is drawn, as [x] already is.
     */
    var sway = 0f
    var lift = 0f

    /** A tiny forward lean of the whole figure, in degrees, while they walk; 0 standing. */
    var lean = 0f

    companion object {
        const val WALK = 0
        const val TURN = 1
        const val STAND = 2
    }
}

object Visit {
    /** How long they take to come up to pace, and to come to rest, in seconds. */
    const val START = .3f
    const val STOP = .55f

    /** How long each turn takes, and how long they stand looking, in seconds. */
    const val TURN = .6f
    const val STAND = 7.5f

    /**
     * The look at the watch, in seconds into the stand: when it starts (where the looking-out loop
     * is on its first frame, so nothing jumps), how long the arm takes to come up, how long they
     * read it, and how long it takes to drop: it ends as the stand does, on the standing pose the
     * turn back starts from.
     */
    const val WATCH_AT = 5f
    const val WATCH_UP = .5f
    const val WATCH_HOLD = 1.4f
    const val WATCH_DOWN = STAND - WATCH_AT - WATCH_UP - WATCH_HOLD

    /** Whether [seed]'s visitor looks at their watch while they stand: most do. */
    fun watches(seed: Int): Boolean = Ambient.hash(seed, 0, 97) < .6f

    /** How long the sway takes to come on after the turn, and to go before the turn back. */
    private const val SWAY_IN = 1.2f

    /**
     * Where they are when they step out of (or into) the door: out of sight at the window's edge.
     */
    const val DOOR = -10f

    /** The seconds between one visit's start and the next. */
    fun cycle(seed: Int): Float = 70f + Ambient.hash(seed, 0, 99) * 40f

    /**
     * How long a whole visit takes when walking at [size] across [width], at the standard tempo (or
     * [seed]'s own).
     */
    fun length(width: Float, size: Float, seed: Int = -1): Float {
        val d = out(width, size)
        val v = speed(size, seed)
        val tc = max(0f, (d - v * (START + STOP) / 2f) / v)
        val back = START + max(0f, (d - v * START / 2f) / v)
        return START + tc + STOP + TURN + STAND + TURN + back
    }

    /** How long the walk out takes, to the end of its stop. */
    fun walkOut(width: Float, size: Float, seed: Int = -1): Float {
        val d = out(width, size)
        val v = speed(size, seed)
        return START + max(0f, (d - v * (START + STOP) / 2f) / v) + STOP
    }

    /** The cruising speed of the visitor [seed] (the standard one for -1) at [size]. */
    private fun speed(size: Float, seed: Int): Float =
        size * Gait.SPEED * (if (seed < 0) 1f else Gait.tempo(seed))

    /**
     * How far out they walk:a whole number of strides, as near the far end of [width] as that goes.
     */
    private fun out(width: Float, size: Float): Float {
        val stride = size * Gait.STRIDE
        return max(1f, round((width * .55f - DOOR) / stride)) * stride
    }

    /**
     * How far a walk of cruising speed [v] has gone [t] seconds in: [up] to come up to pace, [tc]
     * at pace, [down] to stop.
     */
    private fun gone(t: Float, v: Float, up: Float, tc: Float, down: Float): Float {
        if (t < up) return v * (t / 2f - up / (2f * PI.toFloat()) * sin(PI.toFloat() * t / up))
        val a = v * up / 2f
        if (t < up + tc || down <= 0f) return a + v * (t - up)
        val tau = min(t - up - tc, down)
        return a +
            v * tc +
            v * (tau / 2f + down / (2f * PI.toFloat()) * sin(PI.toFloat() * tau / down))
    }

    /**
     * The turn's frames: index 0 is the walk's passing frame, then the [Gait.TURN_FRAMES] turn
     * frames.
     */
    private fun turnFrame(i: Int): Int = if (i == 0) Gait.FRAMES / 4 else Gait.FRAMES + i - 1

    /**
     * Fills [into] with the visitor at [seconds], walking at [size] times the rig's size across a
     * window [width] wide. At rest ([seconds] 0) one building in three has them standing at the far
     * end, and nobody walks.
     */
    fun at(seconds: Float, width: Float, seed: Int, size: Float, into: Stroll) {
        into.here = false
        into.mix = 0f
        into.dir = 1f
        into.mode = Stroll.STAND
        into.frame = 0
        into.next = 0
        into.sway = 0f
        into.lift = 0f
        into.lean = 0f
        val far = width * .55f
        if (seconds <= 0f) {
            if (Ambient.hash(seed, 0, 96) < .35f) {
                into.here = true
                into.x = far
            }
            return
        }
        val stride = size * Gait.STRIDE
        val v = speed(size, seed)
        val d = out(width, size)
        val tc = max(0f, (d - v * (START + STOP) / 2f) / v)
        val tOut = START + tc + STOP
        val tcBack = max(0f, (d - v * START / 2f) / v)
        val tBack = START + tcBack
        val total = tOut + TURN + STAND + TURN + tBack
        val cycle = max(cycle(seed), total + 1f)
        var t = (seconds + Ambient.hash(seed, 0, 100) * cycle) % cycle
        if (t >= total) return
        into.here = true
        val stand = DOOR + d
        if (t < tOut) {
            val g = live(gone(t, v, START, tc, STOP), d, size, seed)
            into.x = DOOR + g
            walk(Gait.PASSING + g / stride, seed, into)
            return
        }
        t -= tOut
        if (t < TURN) {
            into.x = stand
            turn(t / TURN * Gait.TURN_FRAMES, into)
            return
        }
        t -= TURN
        if (t < STAND) {
            into.x = stand
            // the weight shifts from foot to foot, once they have settled and until they go
            val on = ease(t / SWAY_IN) * ease((STAND - t) / SWAY_IN)
            into.sway = Gait.sway(t, seed) * on
            into.lift = -Gait.settle(t, seed) * on
            if (t >= WATCH_AT && watches(seed)) {
                watch(t - WATCH_AT, into)
                return
            }
            into.mode = Stroll.STAND
            // the looking-out loop's own pace: 4 frames at .8 a second, blending on
            val p = t * .8f
            val i = floor(p).toInt()
            into.frame = i % 4
            into.next = (i + 1) % 4
            into.mix = p - i
            return
        }
        t -= STAND
        into.dir = -1f
        if (t < TURN) {
            into.x = stand
            turn((1f - t / TURN) * Gait.TURN_FRAMES, into)
            return
        }
        t -= TURN
        val g = live(gone(t, v, START, tcBack, 0f), d, size, seed)
        into.x = stand - g
        walk(Gait.PASSING + g / stride, seed, into)
    }

    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /**
     * The distance really walked when a steady walk has gone [g] of [d]: [Gait.liveOffset] faded in
     * over the first stride and out over the last, so the walk still leaves the door and stops
     * where it should. The slope stays above 0, so nobody steps backwards.
     */
    private fun live(g: Float, d: Float, size: Float, seed: Int): Float {
        val stride = size * Gait.STRIDE
        val on = ease(g / stride) * ease((d - g) / stride)
        return g + Gait.liveOffset(g / stride, seed) * stride * on
    }

    private fun walk(phase: Float, seed: Int, into: Stroll) {
        into.mode = Stroll.WALK
        into.frame = Gait.nearest(phase, Gait.FRAMES)
        into.next = Gait.neighbour(phase, Gait.FRAMES)
        into.mix = abs(Gait.offset(phase, Gait.FRAMES))
        into.lift = Gait.bob(phase, seed)
        into.lean = Gait.lean(seed)
    }

    /** The first of the watch frames (the standing pose is the one before it). */
    private const val WATCH_FRAME = Gait.FRAMES + Gait.TURN_FRAMES

    /** [t] seconds into the look at the watch: up, read, down. */
    private fun watch(t: Float, into: Stroll) {
        val n = Gait.WATCH_FRAMES
        val q =
            when {
                t < WATCH_UP -> t / WATCH_UP * n
                t < WATCH_UP + WATCH_HOLD -> n.toFloat()
                else -> (1f - (t - WATCH_UP - WATCH_HOLD) / WATCH_DOWN) * n
            }.coerceIn(0f, n.toFloat())
        val i = floor(q + .5f).toInt().coerceIn(0, n)
        val o = q - i
        val j = (if (o >= 0f) i + 1 else i - 1).coerceIn(0, n)
        into.mode = Stroll.TURN
        into.frame = WATCH_FRAME - 1 + i
        into.next = WATCH_FRAME - 1 + j
        into.mix = if (j == i) 0f else abs(o)
    }

    /** [q] runs from 0 (the passing frame) to [Gait.TURN_FRAMES] (standing). */
    private fun turn(q: Float, into: Stroll) {
        val top = Gait.TURN_FRAMES
        val i = floor(q + .5f).toInt().coerceIn(0, top)
        val o = q - i
        val j = (if (o >= 0f) i + 1 else i - 1).coerceIn(0, top)
        into.mode = Stroll.TURN
        into.frame = turnFrame(i)
        into.next = turnFrame(j)
        into.mix = if (j == i) 0f else abs(o)
    }
}
