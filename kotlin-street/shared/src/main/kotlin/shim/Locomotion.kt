package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * How the player moves, in street units (the figure is about 36 tall; a building and its gap about 236). Plain vars so
 * they can be tried out live on the phone. The numbers are from simulating the integrator below at 120 and 60 Hz, not
 * measured on the phone yet.
 */
object MoveTuning {
    /** Top walking speed: about 2 body heights a second, 13 s to cross a 2340-px-wide screen (it was 46: 21 s). */
    var walk = 72f
    /** Top running speed, with the stick at its rim: about 4 body heights a second, 6.4 s a screen (it was 87). */
    var run = 150f
    /**
     * In and out across the pavement against along it: the pavement's 11 lanes of play are about 3 m deep, and the 36-unit
     * figure about 1.7 m tall, so a lane is about 6 street units of ground. Walking across it with legs at the walking
     * cadence is then the truth, not treading water.
     */
    var laneRatio = .16f
    /** Speeding up and slowing down follow the push with this time constant (s) ... */
    var ease = .05f
    /** ... but never faster than these (units a second each second): starting a walk, breaking into a run (a moment, not a jolt), slowing or turning. */
    var accel = 1100f
    var accelRun = 480f
    var brake = 1400f
    /**
     * Let go, the stop eases (quick at first) but never slows gentler than this (units a second each second): the last of
     * it is a firm step down to standing, over in under a tenth of a second from a walk, not a glide on one frozen frame.
     */
    var halt = 900f
    /**
     * Pushed the other way, the figure turns round once it is sliding the old way slower than this (units a second): from a
     * run it skids a moment facing the way it was going, instead of facing the new way while sliding backwards.
     */
    var turnBelow = 12f
    /**
     * A push up to this far scales the walk (for lining up at a door or a person); beyond it, a full walk ... On the 48 dp
     * stick a full walk is then held anywhere from 4.3 to 8.5 mm out (it was 5.1 to 7.9, a band too narrow to stay in).
     */
    var walkFrom = .4f
    /**
     * ... and from here a run (the knob at the rim), which lasts until the push drops below [runOff] (a relaxing thumb
     * does not drop out of it).
     */
    var runAt = .92f
    var runOff = .8f
    /** Ground covered by one walk cycle (the sheet's 6 frames, two steps) at a walk and slower: the feet stay on the ground. */
    var stride = 26.67f
    /** Faster than a walk the stride lengthens (as a runner's does) and the cadence rises less: stride x (speed / walk)^this. */
    var stridePow = .6f
    /** The phase of the walk cycle where a foot lands (the sheet's frame 2 of 6, after the step's lift), and half a cycle on. */
    var footAt = 2f / 6f
    /** The figure leans into a run (degrees at full run), and a little more while speeding up or braking, smoothed over [leanTime] s. */
    var leanRun = 6f
    var leanAccel = 3f
    var leanTime = .08f
    /** Leaning is drawn by whole pixels of the art: the body above the hips moves tan(lean) x this many art pixels forward. */
    var leanLever = 10f
    /**
     * From this share of the way from a walk to a run (0 walk .. 1 full run) the figure shows the run's frames (the legs
     * splayed to a runner's stride, see [RunFrames]) and springs on its raised frames.
     */
    var runPose = .3f
    /** The run's spring: the sheet's raised frames (1 and 4) lift this many more sheet pixels (one pixel of the art). */
    var hop = 2f
    /** The camera looks ahead of a moving player: this share of the view's width at a full run (in proportion to speed). */
    var lead = .08f
    /** It gets there with this smoothing time (s), never faster than [leadBase] + [leadPerSpeed] x the player's speed ... */
    var leadTime = .45f
    var leadBase = 20f
    var leadPerSpeed = .4f
    /**
     * ... and when the player slows, the lead shrinks at most this share of their speed, so the view still moves their way
     * (only slower): it never runs back against them. Standing, it stays where it is.
     */
    var leadBack = .5f
    /** On stopping, what the lead was still doing dies out with this time constant (s): the view stops with the player. */
    var leadSettle = .06f
    /**
     * The camera eases into the street's ends instead of stopping dead against them: it starts slowing this many units
     * short of an end and comes to rest exactly on it (a steady slowing while the player walks twice this, with no tail).
     */
    var endEase = 80f
    /**
     * Inside, the legs go this much faster than on the street (in sprite pixels a second; the stride grows with them, so the
     * cadence is the street's): a walk of 105 room pixels a second (the old fixed speed was 96) and a run of 219.
     */
    var roomPace = 1.2f
    /** The longest frame the movement steps in one go (s): a hitch never jumps the player far. */
    var dtMax = .05f
}

/**
 * The player's legs: a push in (-1..1 each way, at most 1 long together) and a ground velocity out, eased in and out and
 * shared by both axes (so a diagonal stays straight as it speeds up). Pure maths, the same for the street and the rooms;
 * [scale] turns the street units of [MoveTuning] into the caller's (room pixels inside).
 */
class Locomotion(val scale: Float = 1f) {
    /** Ground velocity along (right +) and across (down, towards us, +). Across is ground distance, not lanes (see [MoveTuning.laneRatio]). */
    var vx = 0f
    var vy = 0f
    /** The push's strength last step (0..1). */
    var amount = 0f
        private set
    /** Running (the push went past [MoveTuning.runAt] and has not dropped below [MoveTuning.runOff]). */
    var running = false
        private set
    /** Where in the walk cycle the legs are, 0..1 (a cycle is two steps). */
    var gait = 0f
        private set
    /** Feet put down so far (one sound each). */
    var footfalls = 0
        private set
    /** How far the figure leans forward (degrees; negative leans back, as when braking). */
    var lean = 0f
        private set
    /** The along-the-street acceleration last step (units a second each second). */
    var accelX = 0f
        private set

    val speed get() = hypot(vx, vy)

    /** 0 at a walk or slower .. 1 at a full run. */
    val runShare get() = ((speed / scale - MoveTuning.walk) / (MoveTuning.run - MoveTuning.walk)).coerceIn(0f, 1f)

    /** The speed a push of [m] (0..1) asks for, and whether it is a run (the run latch moves here). */
    fun targetSpeed(m: Float): Float {
        running = if (running) m >= MoveTuning.runOff else m >= MoveTuning.runAt
        val s = when {
            running -> MoveTuning.run
            m >= MoveTuning.walkFrom -> MoveTuning.walk
            else -> MoveTuning.walk * m / MoveTuning.walkFrom
        }
        return s * scale
    }

    /** One step of [dt] s with the push ([mx], [my]): eases the velocity towards what the push asks for. */
    fun drive(dt0: Float, mx: Float, my: Float) {
        val dt = min(dt0, MoveTuning.dtMax)
        if (dt <= 0f) return
        val h = hypot(mx, my)
        val m = min(1f, h)
        amount = m
        val want = targetSpeed(m)
        val k = 1f - exp(-dt / MoveTuning.ease)
        if (h <= 1e-4f) {
            // let go: slowing along the way they were going, eased but never gentler than [MoveTuning.halt] and never harder
            // than the brake, down to a dead stop (no creep, and no long last glide with the legs frozen mid-stride)
            val sp = speed
            if (sp <= 0f) { accelX = 0f; return }
            val drop = maxOf(min(sp * k, MoveTuning.brake * scale * dt), MoveTuning.halt * scale * dt)
            val f = maxOf(0f, sp - drop) / sp
            accelX = (vx * f - vx) / dt
            if (f == 0f) { vx = 0f; vy = 0f } else { vx *= f; vy *= f }
            return
        }
        val tx = want * mx / h
        val ty = want * my / h
        var dvx = (tx - vx) * k
        var dvy = (ty - vy) * k
        // how hard it may change: speeding up (slower into a run than into a walk) or slowing and turning round
        val sp = speed
        val speedingUp = want > sp && tx * vx + ty * vy >= 0f
        val a = scale * (if (!speedingUp) MoveTuning.brake else if (sp < MoveTuning.walk * scale) MoveTuning.accel else MoveTuning.accelRun)
        val dv = hypot(dvx, dvy)
        if (dv > a * dt) { dvx *= a * dt / dv; dvy *= a * dt / dv }
        vx += dvx; vy += dvy
        accelX = dvx / dt
    }

    /**
     * Whether a figure moving [v] along one axis may turn to face [want] (1 or -1) on it yet: once it is not sliding the
     * other way faster than [MoveTuning.turnBelow]. Turning round from a run it skids a moment facing the old way (leaning
     * back into the brake), then turns: never a moonwalk, facing the new way while sliding the old.
     */
    fun mayFace(want: Float, v: Float) = v * want >= 0f || abs(v) < MoveTuning.turnBelow * scale

    /** The lean for a figure seen from the side ([side]) facing [dir] (1 right, -1 left); upright in the other views. */
    fun tilt(dt0: Float, dir: Float, side: Boolean) {
        val dt = min(dt0, MoveTuning.dtMax)
        if (dt <= 0f) return
        val want = if (!side) 0f else
            MoveTuning.leanRun * runShare + MoveTuning.leanAccel * (accelX * dir / (MoveTuning.accelRun * scale)).coerceIn(-1f, 1f)
        lean += (want - lean) * (1f - exp(-dt / MoveTuning.leanTime))
    }

    /** Showing the run's frames (and its spring): past [MoveTuning.runPose] of the way from a walk to a run. */
    val runPose get() = runShare > MoveTuning.runPose

    /** The lean in whole pixels of the art (forward positive): how far the body above the hips is drawn ahead of the legs. */
    fun leanPixels(): Int = kotlin.math.round(kotlin.math.tan(lean * (PI.toFloat() / 180f)) * MoveTuning.leanLever).toInt()

    /** Ground covered by a walk cycle at the speed now: [MoveTuning.stride] up to a walk, longer (a runner's stride) beyond. */
    fun stride(): Float {
        val r = speed / (MoveTuning.walk * scale)
        return MoveTuning.stride * scale * (if (r > 1f) r.pow(MoveTuning.stridePow) else 1f)
    }

    /** The legs have really carried the figure [dist] over the ground (after walls and furniture): the cycle moves on that far. */
    fun advance(dist: Float) {
        if (dist <= 0f) return
        val before = gait
        val after = before + dist / stride()
        // a foot lands at footAt and half a cycle on
        val f = MoveTuning.footAt
        footfalls += (floor((after - f) * 2f) - floor((before - f) * 2f)).toInt()
        gait = after - floor(after)
    }

    /** Stops dead along the street (against its end or a lamp), or across it (against the wall or the kerb). */
    fun stopX() { vx = 0f }
    fun stopY() { vy = 0f }

    companion object {
        /** A push within this angle of the street (degrees) shows the figure from the side ... */
        const val SIDE_WITHIN = 60f
        /** ... beyond this, from the back or the front; between them the view does not change (no flicker on a wobbling thumb). */
        const val DEPTH_BEYOND = 70f

        /**
         * Ground covered by a step of [dx] along the street and [dLane] lanes across it: the lanes are deep ground (see
         * [MoveTuning.laneRatio]), so stepping in and out moves the legs as walking along does.
         */
        fun streetDistance(dx: Float, dLane: Float) = hypot(dx, dLane / MoveTuning.laneRatio)

        /** Which view the street figure shows for a push ([mx], [my]), from [prev]: 0 side, 1 back (walking away), 3 front. */
        fun streetFacing(prev: Int, mx: Float, my: Float): Int {
            if (mx == 0f && my == 0f) return prev
            val a = atan2(abs(my), abs(mx)) * (180f / PI.toFloat())
            val depth = if (my < 0f) 1 else 3
            return when {
                a <= SIDE_WITHIN -> 0
                a >= DEPTH_BEYOND -> depth
                prev == 0 -> 0
                else -> depth
            }
        }

        /**
         * Which way a figure in a room faces (0 right, 1 up, 2 left, 3 down) for a push ([mx], [my]), from [prev]: the axis
         * pushed more, but only once it is pushed a quarter more than the other (no flicker on a diagonal).
         */
        fun roomFacing(prev: Int, mx: Float, my: Float): Int {
            if (mx == 0f && my == 0f) return prev
            val sideways = prev == 0 || prev == 2
            val horizontal = if (sideways) abs(my) <= abs(mx) * 1.25f else abs(mx) > abs(my) * 1.25f
            return if (horizontal) { if (mx > 0f) 0 else if (mx < 0f) 2 else prev } else { if (my < 0f) 1 else 3 }
        }
    }
}

/**
 * The camera's look ahead of a moving player (street units, signed along the street): a share of the view in the way they
 * are going, in proportion to their speed. It follows with a critically damped spring whose speed is held down, so the
 * player drifts on the screen gently rather than being thrown about. It never moves the view back against the player, and
 * when they stop it stops with them and stays (the street does not slide on after a stop, or behind a conversation); it
 * changes again once they walk.
 */
class CameraLead {
    var lead = 0f
        private set
    /** How fast the lead is changing (units a second). */
    var rate = 0f
        private set
    /** Which way the player last moved (1 right, -1 left, 0 not yet). */
    private var way = 0f

    /** One step of [dt] s for a player moving at [vx] with [view] units of street on the screen. */
    fun step(dt0: Float, vx: Float, view: Float): Float {
        val dt = min(dt0, MoveTuning.dtMax)
        if (dt <= 0f) return lead
        val full = MoveTuning.lead * view
        if (abs(vx) < STILL) {
            // standing: whatever the lead was still doing the way they went dies out within a tenth of a second; shrinking
            // back towards them (they were slowing) it stops with them, or the view would drift back against the stop
            if (rate * way < 0f) rate = 0f
            rate *= exp(-dt / MoveTuning.leadSettle)
            if (abs(rate) < .5f) rate = 0f
            lead = (lead + rate * dt).coerceIn(-full, full)
            return lead
        }
        way = sign(vx)
        val target = full * (vx / MoveTuning.run).coerceIn(-1f, 1f)
        // a critically damped spring (as game engines' smooth-damp), settling in about [leadTime]
        val omega = 2f / MoveTuning.leadTime
        val x = omega * dt
        val e = 1f / (1f + x + .48f * x * x + .235f * x * x * x)
        val change = lead - target
        val temp = (rate + omega * change) * dt
        var r = (rate - omega * temp) * e
        var next = target + (change + temp) * e
        // never past the target
        if ((change < 0f && next > target) || (change > 0f && next < target)) { next = target; r = 0f }
        // how fast: ahead of them (the lead growing the way they go) at most the cap; back towards them (they slowed) at
        // most [MoveTuning.leadBack] of their speed, so the view keeps moving their way
        val step = next - lead
        val cap = if (step * vx >= 0f) MoveTuning.leadBase + MoveTuning.leadPerSpeed * abs(vx) else MoveTuning.leadBack * abs(vx)
        if (abs(step) > cap * dt) { next = lead + sign(step) * cap * dt; r = sign(step) * cap }
        lead = next
        rate = r
        return lead
    }

    /** Back to the middle at once (the player was moved: round a corner, through a door). */
    fun reset() { lead = 0f; rate = 0f; way = 0f }

    /** [lead] snapped to whole device pixels ([unit] pixels a street unit), so the player never flickers a pixel either way. */
    fun snapped(unit: Float): Float = if (unit > 0f) kotlin.math.round(lead * unit) / unit else lead

    companion object {
        /** Slower than this (units a second) the player is standing, as far as the camera is concerned. */
        const val STILL = 1f

        /**
         * The camera's place [cx] kept within [lo]..[hi] (where the street's built ends reach the screen's edges), easing
         * into each end: from [MoveTuning.endEase] units short of it the view slows steadily (its speed falls in proportion
         * to the ground left) and comes to rest exactly on the end once [cx] is as far past it. So it never halts in one
         * frame, its speed and place stay continuous as the player walks into and back out of an end, and there is no tail
         * creeping on by single pixels for seconds: the whole ease takes about 2.2 s walking in, 1.1 s running.
         */
        fun withinEnds(cx: Float, lo: Float, hi: Float): Float {
            if (lo >= hi) return (lo + hi) / 2f
            val w = min(MoveTuning.endEase, (hi - lo) / 2f)
            if (w <= 0f) return cx.coerceIn(lo, hi)
            return when {
                cx >= hi + w -> hi
                cx > hi - w -> { val u = cx - (hi - w); hi - w + u - u * u / (4f * w) }
                cx <= lo - w -> lo
                cx < lo + w -> { val u = (lo + w) - cx; lo + w - u + u * u / (4f * w) }
                else -> cx
            }
        }
    }
}
