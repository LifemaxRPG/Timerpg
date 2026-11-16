package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * How people walk. One cycle is two steps: the classic key poses (contact, down, passing, up, then the same on the
 * other leg) are worked out, not guessed, from where the feet are: the planted foot is fixed on the ground while
 * the body moves over it at a steady speed, the swinging foot is lifted over the floor and brought forward, and the
 * pelvis rides at the height the two legs allow. That makes the stride, the speed and the knees agree, so a walker
 * drawn at [Gait.SPEED] has no foot that slides.
 *
 * Everything in a walk is a function of the DISTANCE walked: the phase of the cycle is distance over [Gait.STRIDE],
 * so a walker that starts, stops or is drawn smaller takes shorter or quicker steps and still never skates. Pure
 * arithmetic with no Compose and no Android.
 *
 * Nobody walks like a machine, so a walk is irregular on three timescales:
 *  - each LOOK has its own [Gait.Style], baked into its frames: a longer or shorter stride, how far each arm swings
 *    (one may swing less than the other), how much the body bobs, how far forward they lean, how much the shoulders
 *    roll, and a little asymmetry between the two steps;
 *  - each PERSON (a seed) has a [Gait.Signature] applied as they are drawn, costing no memory: their tempo, a tiny
 *    forward or backward lean (a skew), how much their bob varies, and for a rare few a hint of a limp;
 *  - and no two STEPS are alike: the distance walked wanders a few per cent either side of the steady pace
 *    ([Gait.liveTravel]), a step now and then lands a fraction late (a hitch), and each step bobs a little more or
 *    less ([Gait.bob]). All of it is a function of the distance walked, so the planted foot still never slides: a
 *    caller uses the SAME live distance for where the walker is and for which frame shows.
 */

object Gait {
    /** Frames baked for one cycle of a walk. */
    const val FRAMES = 16

    /** A walk's cycle (two steps) takes this long, in seconds: 110 steps a minute. */
    const val CYCLE = 1.09f

    /** How far one step goes, in units. */
    const val STEP = 7.0f

    /** How far a whole cycle goes: the foot that landed lands again. */
    const val STRIDE = 2f * STEP

    /** The speed of a walk at the rig's own size, in units a second. */
    const val SPEED = STRIDE / CYCLE

    /**
     * The phase of the passing pose (legs together under the body): where a walk starts and stops.
     */
    const val PASSING = .25f

    /**
     * The jog on the treadmill: 16 frames to a cycle of this many seconds (about 165 steps a
     * minute).
     */
    const val JOG_FRAMES = 16
    const val JOG_CYCLE = .73f

    /**
     * The extra frames of the turn that takes a walker from the passing pose in profile round to
     * facing the street (standing): four on the way and the standing pose itself, so that the
     * activity that takes over (looking out) begins on exactly the picture the turn ends on.
     */
    const val TURN_FRAMES = 5

    /**
     * The extra frames after the turn's that take the standing visitor from looking out to looking
     * at their watch: the last is the watch pose itself.
     */
    const val WATCH_FRAMES = 3

    /**
     * The poses between [from] and [to] (not including [from]), [count] of them, the last being
     * [to]: eased, so that a turn starts and ends gently.
     */
    fun between(from: FloatArray, to: FloatArray, count: Int): List<FloatArray> =
        List(count) { i ->
            val s = (i + 1f) / count
            val e = s * s * (3f - 2f * s)
            FloatArray(K.SIZE) { k -> from[k] + (to[k] - from[k]) * e }
        }

    /** The phase [distance] along a walk is at, for a walker drawn [size] times the rig. */
    fun phase(distance: Float, size: Float, from: Float = PASSING): Float {
        val p = from + distance / (STRIDE * size)
        return p - floor(p)
    }

    /** The baked frame nearest to [phase] (0 to 1, wrapping) of a loop of [frames]. */
    fun nearest(phase: Float, frames: Int): Int {
        val i = floor((phase - floor(phase)) * frames + .5f).toInt()
        return if (i >= frames) i - frames else i
    }

    /**
     * How far [phase] is from the frame [nearest] picks, in frames (-.5 up to .5): the sign says
     * which side the next nearest frame is on.
     */
    fun offset(phase: Float, frames: Int): Float {
        val t = (phase - floor(phase)) * frames
        return t - floor(t + .5f)
    }

    /** The frame beside [nearest] that [offset] leans towards. */
    fun neighbour(phase: Float, frames: Int): Int {
        val n = nearest(phase, frames)
        val o = offset(phase, frames)
        val i = if (o >= 0f) n + 1 else n - 1
        return if (i >= frames) 0 else if (i < 0) frames - 1 else i
    }

    // ------------------------------------------------------------------------------ the walk
    //
    // Measured against the gait lab (Perry's phases; Winter's sagittal angles), one foot's own
    // cycle, 0 at its heel strike: the heel lands with the leg nearly straight (knee about 5
    // degrees) and the toes up; the knee gives 15 to 20 degrees as the weight comes on (the
    // loading response, to about 12 per cent) and the foot rolls down flat (about 10); the body
    // vaults over the planted leg, the knee straightening again; the heel rises from about a third
    // of the way round while the thigh carries on back behind the body; the knee folds as the
    // other foot takes the weight, and the toes leave the ground at about 62 per cent (stance 62,
    // swing 38, two spells of both feet down of about 12 each). In the swing the knee folds to
    // about 60 degrees early (72 per cent) to clear the toe, which passes a centimetre or two over
    // the floor beside the other foot, and then the shank swings out ahead and the knee straightens
    // and slows as the thigh eases back a little (it reaches forward and is drawn back to land).
    // The pelvis rides lowest just after each heel strike and highest over the planted foot, 4 or
    // 5 cm between, as a smooth wave.
    //
    // Here: the planted foot (heel down, flat, then rolling over the ball) is fixed on the ground.
    // The pelvis rides a smooth wave whose size is what the step's own geometry gives: as high as
    // a straight leg lets it be over the planted foot and as low as the knee's give as the weight
    // comes on, worked out once per style. While the heel is down the planted leg is solved to
    // reach its foot; once the heel may rise, the knee follows a smooth curve (settling to nearly
    // straight, then folding to push off) and the heel rises exactly as far as that leg needs to
    // reach the floor behind the body. The swinging leg follows the lab's joint curves from the
    // very angles and speeds it leaves the ground with to the very ones it lands with, the toes
    // kept clear over the floor and the heel let down onto it from above, so the two halves meet
    // without a jolt.

    /** The legs of the standard figure, as [FigBuilder] draws them. */
    private const val THIGH = 5.9
    private const val SHIN = 5.7
    private const val LEG = THIGH + SHIN

    /**
     * Where the foot sits: from the ankle, the heel and the ball, so that the sole can be found
     * (flat, the two are equally low, as the street's shoes are drawn: the foot rocks onto the ball
     * as soon as the heel lifts).
     */
    private fun heelX(t: Double) = .3 * sin(t) - .25 * cos(t)

    private fun toeX(t: Double) = .38 * sin(t) + 2.1 * cos(t)

    private fun heelBottom(t: Double) = .3 * cos(t) + .25 * sin(t) + .62

    private fun toeBottom(t: Double) = .38 * cos(t) - 2.1 * sin(t) + .54

    /** How far below the ankle the lowest part of a foot toes-up by [t] radians is. */
    private fun sole(t: Double) = max(heelBottom(t), toeBottom(t))

    private fun rad(d: Double) = d * PI / 180.0

    private fun deg(r: Double) = r * 180.0 / PI

    private fun frac(x: Double) = x - floor(x)

    private fun smooth(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return .5 - .5 * cos(PI * c)
    }

    /** The cubic from [p0] (slope [m0]) to [p1] (slope [m1]) over [t] 0 to 1. */
    private fun hermite(t: Double, p0: Double, m0: Double, p1: Double, m1: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p0 +
            (t3 - 2 * t2 + t) * m0 +
            (-2 * t3 + 3 * t2) * p1 +
            (t3 - t2) * m1
    }

    // when in a foot's own cycle (0 at its heel strike) the foot comes down flat, the heel starts
    // to rise and the toes leave the ground
    private const val U_FLAT = .1
    private const val U_HEEL_OFF = .3
    private const val U_TOE_OFF = .62

    /** The foot's tilt from the floor at the heel strike, degrees, toes up. */
    private const val HEEL_STRIKE = 15.0

    private fun heelStrike(st: Style) = HEEL_STRIKE * (.4 + .6 * st.spring)

    /** The foot flat on the floor: heel and ball both down. */
    private const val FLAT = 0.0

    /**
     * The planted foot's tilt up to the heel rising ([U_HEEL_OFF]): down from the heel strike at
     * once, slowing to lie flat, then flat.
     */
    private fun tilt(u: Double, st: Style): Double =
        if (u < U_FLAT) FLAT + (heelStrike(st) - FLAT) * sq(1 - u / U_FLAT) else FLAT

    /** The heel's x at the heel strike, from the pelvis: where it lands, ahead of the body. */
    private const val LAND = 2.9

    /** The planted heel's x in the world, the pelvis at 0 at the heel strike. */
    private fun landing(st: Style) = LAND * st.stride

    /** Where the ball of the planted foot is in the world, the pelvis at 0 at the heel strike. */
    private fun ball(st: Style) = landing(st) - heelX(rad(FLAT)) + toeX(rad(FLAT))

    /** How far the pelvis has gone from the planted foot's heel strike at its own [u]. */
    private fun travel(u: Double, st: Style) = STRIDE * st.stride * u

    /** The planted ankle's x (from the pelvis's middle) and height, the foot tilted [t] degrees. */
    private fun ankleX(u: Double, t: Double, st: Style): Double {
        val r = rad(t)
        val x = if (u < U_FLAT) landing(st) - heelX(r) else ball(st) - toeX(r)
        return x - travel(u, st)
    }

    private fun ankleY(t: Double): Double = sole(rad(t))

    /**
     * The straightest a planted knee is ever asked to be, degrees, and the straightest while the
     * heel is down (a little more bent, so it can still settle as the heel rises).
     */
    private const val KNEE_MIN = 4.0
    private const val KNEE_FLAT = 7.0

    /** How long a leg is from hip to ankle with the knee bent [k] degrees. */
    private fun span(k: Double): Double {
        val c = cos(rad(k))
        return sqrt(THIGH * THIGH + SHIN * SHIN + 2 * THIGH * SHIN * c)
    }

    /**
     * How much the trailing knee bends from [PUSH_AT] on, degrees over its straightest, and how
     * fast, a cycle: nearly straight as the body goes on over the foot (the heel peeling up so that
     * it can stay so), folding as the other foot takes the weight, about 38 as the toes leave.
     */
    private val PUSH_AT = doubleArrayOf(.44, .5, .56, .62)
    private val PUSH_KNEE = doubleArrayOf(0.0, 5.5, 16.0, 33.0)
    private val PUSH_RATE = doubleArrayOf(0.0, 125.0, 230.0, 260.0)

    /**
     * The trailing knee at [u] past the heel's rising: settling from [k0] (bending [m0] a cycle),
     * as it was with the foot flat at [U_HEEL_OFF], to its straightest without a jolt, then folding
     * as [PUSH_KNEE] says.
     */
    private fun pushKnee(u: Double, k0: Double, m0: Double, st: Style): Double {
        // an older walker keeps the knee a little bent and folds it less
        val floor = KNEE_MIN + (1 - st.spring) * 16.0
        val give = k0 - floor
        val rate = if (m0 < 0 && give > .1) -m0 / give else 30.0
        var k = floor + give * exp(-rate * (u - U_HEEL_OFF))
        if (u > PUSH_AT[0]) {
            val fold = .6 + .4 * st.spring
            var i = 1
            while (i < PUSH_AT.size - 1 && u > PUSH_AT[i]) i++
            val d = PUSH_AT[i] - PUSH_AT[i - 1]
            k +=
                fold *
                    hermite(
                        ((u - PUSH_AT[i - 1]) / d).coerceAtMost(1.0),
                        PUSH_KNEE[i - 1],
                        PUSH_RATE[i - 1] * d,
                        PUSH_KNEE[i],
                        PUSH_RATE[i] * d,
                    )
        }
        return k
    }

    /**
     * The tilt of the planted foot, pivoting on its ball, at which the ankle is [d] from a hip at
     * ([hx], [h]) at the stance's [u]: the higher the heel, the nearer. Never past flat.
     */
    private fun rise(u: Double, hx: Double, h: Double, d: Double, st: Style): Double {
        var lo = -89.0
        var hi = FLAT
        for (i in 0 until 36) {
            val m = (lo + hi) / 2
            val r = rad(m)
            val ax = ball(st) - toeX(r) - travel(u, st) - hx
            val ay = h - sole(r)
            if (ax * ax + ay * ay > d * d) hi = m else lo = m
        }
        return (lo + hi) / 2
    }

    /** The far foot's own cycle at [phi]. */
    private fun farU(phi: Double, st: Style) = frac(phi + .5 + st.split)

    /**
     * The pelvis's height over the floor at [phi]: a smooth wave twice a cycle, lowest just after
     * each heel strike as the knee gives, highest as the body passes over the planted foot, its
     * size the style's bob; as high as the planted legs let it be with their knees no straighter
     * than [KNEE_MIN] while the heel is down. Worked out once per style.
     */
    private fun pelvisHeight(phi: Double, st: Style): Double {
        val r = rideOf(st)
        return r[0] + r[1] * wave(phi)
    }

    /**
     * The pelvis's path, -1 to 1, twice a cycle: rising over the planted leg in a broad arc as an
     * inverted pendulum does and dipping more sharply where the weight passes from foot to foot.
     */
    private fun wave(phi: Double): Double {
        val c2 = sq(cos(2 * PI * (phi - PHI_LOW)))
        return 1 - 2 * c2 * c2.pow(ARC)
    }

    /** Where in the cycle the pelvis is lowest. */
    private const val PHI_LOW = .03

    /** How much broader than a sine the pelvis's arc is at its top (0: a sine). */
    private const val ARC = .5

    private val RIDES = java.util.IdentityHashMap<Style, DoubleArray>()

    /** The pelvis's mean height and half its rise and fall, for [st]. */
    private fun rideOf(st: Style): DoubleArray =
        synchronized(RIDES) { RIDES.getOrPut(st) { ride(st) } }

    /**
     * Half the pelvis's rise and fall for [st]: what the geometry of its step gives (the hip at the
     * top of its arc over the straight planted leg, and at its lowest as the weight comes on, at
     * [PHI_LOW], the knee given [KNEE_LOW]: a longer step lands with the leg more slanted, so the
     * body vaults higher), give or take the style's own bob.
     */
    private fun bob(st: Style): Double {
        val bend = (1 - st.spring) * 14.0
        val d = span(KNEE_MIN + bend)
        var top = 0.0
        for (i in 0 until 100) {
            val u = i / 100.0 * U_HEEL_OFF
            val t = tilt(u, st)
            val x = ankleX(u, t, st) - hipX(u, st)
            top = max(top, ankleY(t) + sqrt(max(0.0, d * d - x * x)))
        }
        val t = tilt(PHI_LOW, st)
        val x = ankleX(PHI_LOW, t, st) - hipX(PHI_LOW, st)
        val dl = span(KNEE_LOW * (.5 + .5 * st.spring) + bend)
        val low = ankleY(t) + sqrt(max(0.0, dl * dl - x * x))
        return max(.04, (top - low) / 2 * (.7 + .3 * st.bob))
    }

    /** The knee's bend as the weight comes onto it, degrees (the loading response). */
    private const val KNEE_LOW = 14.0

    private fun ride(st: Style): DoubleArray {
        val bob = bob(st)
        var r = Double.MAX_VALUE
        for (i in 0 until 400) {
            val phi = i / 400.0
            val hip = hipX(phi, st)
            for (side in 0 until 2) {
                val u = if (side == 0) phi else farU(phi, st)
                if (u >= U_HEEL_OFF) continue
                val t = tilt(u, st)
                val d = span(KNEE_FLAT + (1 - st.spring) * 16.0)
                val x = ankleX(u, t, st) - if (side == 0) hip else -hip
                val top = ankleY(t) + sqrt(max(0.0, d * d - x * x))
                r = min(r, top - bob * wave(phi))
            }
        }
        return doubleArrayOf(r, bob)
    }

    /** The near hip's x from the pelvis's middle: the pelvis turns a little with each step. */
    private fun hipX(phi: Double, st: Style): Double = HIP_SWING * st.hip * cos(2 * PI * phi)

    /** Where the arms' swing sits against the legs', in cycles (negative: a touch ahead). */
    private const val ARM_LAG = -.03

    /**
     * A swing, -1 to 1 like a cosine of [u] cycles, but lingering at its ends and quicker through
     * its middle, as a pendulum is.
     */
    private fun pendulum(u: Double): Double {
        val c = cos(2 * PI * u)
        return c * (1.25 - .25 * c * c)
    }

    private const val HIP_SWING = .32
    private const val SHOULDER_SWING = .7
    private const val BASE_HIP = 1.5 - 1.1 * .94
    private const val BASE_SHOULDER = 2.9 - 2.4 * .94

    private fun sq(x: Double) = x * x

    /**
     * One leg's angles worked out so the ankle is at ([x], [y]) from the hip: thigh, knee, shin,
     * all in degrees.
     */
    private fun leg(x: Double, y: Double, out: DoubleArray) {
        val d0 = sqrt(x * x + y * y)
        val d = min(d0, LEG * .9995)
        val g =
            acos(((THIGH * THIGH + SHIN * SHIN - d * d) / (2 * THIGH * SHIN)).coerceIn(-1.0, 1.0))
        val knee = PI - g
        val a = acos(((THIGH * THIGH + d * d - SHIN * SHIN) / (2 * THIGH * d)).coerceIn(-1.0, 1.0))
        val dir = atan2(x, y)
        val thigh = dir + a
        out[0] = deg(thigh)
        out[1] = deg(knee)
        out[2] = deg(thigh - knee)
    }

    /**
     * A planted leg at its own [u] (the leg's cycle starting [o] after the near one's, its hip on
     * the [side] +1 near, -1 far): thigh, knee, shin and the foot's tilt into [out].
     */
    private fun planted(u: Double, o: Double, side: Double, st: Style, out: DoubleArray) {
        if (u < U_HEEL_OFF) {
            flat(u, o, side, st, out)
            return
        }
        // the knee as it was when the heel began to rise, and how fast it was going
        val e = 1e-4
        flat(U_HEEL_OFF, o, side, st, out)
        val k0 = out[1]
        flat(U_HEEL_OFF - e, o, side, st, out)
        val m0 = (k0 - out[1]) / e
        val k = pushKnee(u, k0, m0, st)
        val phi = frac(u - o)
        val h = pelvisHeight(phi, st)
        val hx = side * hipX(phi, st)
        val t = rise(u, hx, h, span(k), st)
        leg(ankleX(u, t, st) - hx, h - ankleY(t), out)
        out[3] = t
    }

    /** A planted leg with its heel down (or coming down) at [u]: as [planted]. */
    private fun flat(u: Double, o: Double, side: Double, st: Style, out: DoubleArray) {
        val phi = frac(u - o)
        val h = pelvisHeight(phi, st)
        val hx = side * hipX(phi, st)
        val t = tilt(u, st)
        leg(ankleX(u, t, st) - hx, h - ankleY(t), out)
        out[3] = t
    }

    // the swing: when (of the swing) the knee is most bent and the thigh furthest forward, and how
    // far the ankle drops below square just after the toe-off before the foot comes up level
    private const val SWING_KNEE = 61.0
    private const val S_KNEE = .27
    private const val S_OPEN = .72
    private const val OPEN = .35
    private const val S_THIGH = .7
    private const val S_THRUST = .3
    private const val THRUST = .55
    private const val THIGH_OVER = 2.0
    private const val S_ANKLE = .08
    private const val S_LEVEL = .45
    private const val ANKLE_LEVEL = 1.0

    /**
     * A swinging leg at its own [u] (past the toe-off): thigh, knee, shin and foot tilt into [out],
     * on the lab's curves from how it left the ground to how it lands. [a] and [b] are scratch.
     */
    private fun swing(
        u: Double,
        o: Double,
        side: Double,
        st: Style,
        out: DoubleArray,
        a: DoubleArray,
        b: DoubleArray,
    ) {
        val span = 1.0 - U_TOE_OFF
        val e = 1e-4
        // leaving the ground: the angles and how fast they are going
        planted(U_TOE_OFF - e, o, side, st, a)
        planted(U_TOE_OFF - 2 * e, o, side, st, b)
        val t0 = a[0]
        val k0 = a[1]
        val a0 = a[3] - a[2]
        val dt0 = (a[0] - b[0]) / e * span
        val dk0 = (a[1] - b[1]) / e * span
        val da0 = ((a[3] - a[2]) - (b[3] - b[2])) / e * span
        // landing
        planted(0.0, o, side, st, a)
        planted(e, o, side, st, b)
        val t1 = a[0]
        val k1 = a[1]
        val a1 = a[3] - a[2]
        val dt1 = (b[0] - a[0]) / e * span
        val dk1 = (b[1] - a[1]) / e * span
        val da1 = ((b[3] - b[2]) - (a[3] - a[2])) / e * span
        val s = (u - U_TOE_OFF) / span
        // the knee folds to its most, then opens to land
        val kp = max(SWING_KNEE * (.7 + .3 * st.spring), k0 + 8.0)
        // (most of the way open by the time the shin hangs straight down, then easing to land)
        val ko = k1 + OPEN * (kp - k1)
        val mo = -1.25 * (kp - k1) / (1 - S_KNEE)
        val m1 = min(dk1 * (1 - S_OPEN), 2.5 * (ko - k1))
        val knee =
            when {
                s < S_KNEE -> hermite(s / S_KNEE, k0, dk0 * S_KNEE, kp, 0.0)
                s < S_OPEN ->
                    hermite((s - S_KNEE) / (S_OPEN - S_KNEE), kp, 0.0, ko, mo * (S_OPEN - S_KNEE))
                else -> hermite((s - S_OPEN) / (1 - S_OPEN), ko, mo * (1 - S_OPEN), k1, m1)
            }
        // the thigh swings forward past where it lands and is drawn back to it
        val tp = t1 + THIGH_OVER * st.spring
        // (quickly at first: half way there by [S_THRUST], the hip flexing hardest just after
        // the toes leave)
        val tm = t0 + THRUST * (tp - t0)
        val mm = 1.5 * (tp - t0) / S_THIGH
        val thigh =
            when {
                s < S_THRUST -> hermite(s / S_THRUST, t0, dt0 * S_THRUST, tm, mm * S_THRUST)
                s < S_THIGH ->
                    hermite(
                        (s - S_THRUST) / (S_THIGH - S_THRUST),
                        tm,
                        mm * (S_THIGH - S_THRUST),
                        tp,
                        0.0,
                    )
                else -> {
                    // (landing as it goes on in the stance, but never by first dipping under)
                    val m = dt1 * (1 - S_THIGH)
                    val cap = 2 * (tp - t1) + 1
                    hermite(
                        (s - S_THIGH) / (1 - S_THIGH),
                        tp,
                        0.0,
                        t1,
                        m.coerceIn(-3 * cap, 3 * cap),
                    )
                }
            }
        // the ankle runs on down a moment after the push, then comes up square and holds there
        val am = a0 + .5 * da0 * S_ANKLE
        val ankle =
            when {
                s < S_ANKLE -> hermite(s / S_ANKLE, a0, da0 * S_ANKLE, am, 0.0)
                s < S_LEVEL ->
                    hermite((s - S_ANKLE) / (S_LEVEL - S_ANKLE), am, 0.0, ANKLE_LEVEL, 0.0)
                else -> {
                    val m = da1 * (1 - S_LEVEL)
                    val cap = 2 * abs(a1 - ANKLE_LEVEL) + 4
                    hermite(
                        (s - S_LEVEL) / (1 - S_LEVEL),
                        ANKLE_LEVEL,
                        0.0,
                        a1,
                        m.coerceIn(-cap, cap),
                    )
                }
            }
        // and the toes kept clear of the floor as the foot swings through low beside the other
        // one: the ankle flexes up as much as it must (a smooth floor under the need, no corner)
        val shin = thigh - knee
        val h = pelvisHeight(frac(u - o), st)
        val toe = h - THIGH * cos(rad(thigh)) - SHIN * cos(rad(shin)) - toeBottom(rad(shin + ankle))
        val need = CLEAR * smooth(s / S_CLEAR) - toe
        // (none at all as the toes leave, so the swing starts just as the stance ends)
        val lift = deg(SOFT * ln(1 + exp(need / SOFT)) / 2.1) * smooth(s / .06)
        // and the heel let down onto the floor from above, never skimming it before it lands:
        // the thigh carried a touch further forward if it must
        val t = shin + ankle + lift
        val heel = h - THIGH * cos(rad(thigh)) - SHIN * cos(rad(shin)) - heelBottom(rad(t))
        val want = HEEL_CLEAR * ((1 - s) / (1 - S_DESCENT)).coerceIn(0.0, 1.0)
        val short = want - heel
        val g = max(1.5, THIGH * sin(rad(thigh)) + SHIN * sin(rad(shin)))
        // (none at all as it lands, so the stance takes over just where the swing ends)
        val more =
            deg(SOFT_HEEL * ln(1 + exp(short / SOFT_HEEL)) / g) *
                smooth((s - .5) / .2) *
                smooth((1 - s) / .06)
        out[0] = thigh + more
        out[1] = knee
        out[2] = shin + more
        out[3] = t + more
    }

    /** The heel's way down to the floor: no lower than this, falling to nothing as it lands. */
    private const val HEEL_CLEAR = .35
    private const val S_DESCENT = .74
    private const val SOFT_HEEL = .05

    /**
     * How far the swinging toes keep over the floor (2 cm or so on a person: just enough to see),
     * from [S_CLEAR] of the swing on, and how soft the floor under the ankle's give is.
     */
    private const val CLEAR = .35
    private const val S_CLEAR = .2
    private const val SOFT = .04

    /** One leg at its own [u]: thigh, knee, shin, tilt into [out]. */
    private fun legAt(
        u: Double,
        o: Double,
        side: Double,
        st: Style,
        out: DoubleArray,
        a: DoubleArray,
        b: DoubleArray,
    ) {
        if (u < U_TOE_OFF) planted(u, o, side, st, out) else swing(u, o, side, st, out, a, b)
    }

    /**
     * The walk's pose at [phi] of the cycle (0 = the near heel strikes), walked in [style] (the
     * neutral walk when none is given).
     */
    fun walkPose(phi: Float, style: Style = NEUTRAL): FloatArray {
        val st = style
        val f = frac(phi.toDouble())
        val ln = DoubleArray(4)
        val lf = DoubleArray(4)
        val a = DoubleArray(4)
        val b = DoubleArray(4)
        val of = .5 + st.split
        legAt(f, 0.0, 1.0, st, ln, a, b)
        legAt(frac(f + of), of, -1.0, st, lf, a, b)
        val hip = hipX(f, st)
        val ann = ln[3] - ln[2]
        val anf = lf[3] - lf[2]
        // the arms are pendulums the shoulders drive: crossing as the legs pass, they hang a
        // moment at each end of the swing and fall through the middle (the forearms trail them)
        val c = pendulum(f - ARM_LAG)
        val an = -st.armNear * c
        val af = st.armFar * c
        val en = 13.0 + 17.0 * (-c + 1) / 2
        val ef = 13.0 + 17.0 * (c + 1) / 2
        val sway = .8 * cos(4 * PI * (f - .125))
        val lean = st.lean + sway
        return pose(
            turn = .94f,
            lean = lean.toFloat(),
            // the head holds the eyes level: it takes back most of the body's rocking
            head = (-st.lean * .7 - sway * .95).toFloat(),
            an = an.toFloat(),
            en = en.toFloat(),
            af = af.toFloat(),
            ef = ef.toFloat(),
            ln = ln[0].toFloat(),
            kn = ln[1].toFloat(),
            lf = lf[0].toFloat(),
            kf = lf[1].toFloat(),
            ann = ann.toFloat(),
            anf = anf.toFloat(),
            twist = (hip - BASE_HIP).toFloat(),
            shx = (-SHOULDER_SWING * st.roll * cos(2 * PI * f) - BASE_SHOULDER).toFloat(),
        )
    }

    // ------------------------------------------------------------------------ the signature

    /**
     * How one look walks: what is baked into its frames. [stride] scales the step (1 is the
     * standard [STEP]: the stride, and so the pace at a given cadence, scales with it); [armNear]
     * and [armFar] are how far each arm swings, in degrees; [bob] scales how much the body rises
     * and falls; [lean] is the posture's forward lean in degrees; [split] moves the far foot's
     * landing from exactly half way round the cycle (one step a little longer than the other);
     * [roll] scales the shoulders' counter-swing and [hip] the pelvis's; [spring] is how much
     * spring the legs have (1: a heel strike, a straight knee in mid-stance, a push off the toes
     * and a high knee in the swing; less, as an older walker: a flatter foot, knees kept a little
     * bent, a feebler push and a lower swing).
     */
    class Style(
        val stride: Float,
        val armNear: Float,
        val armFar: Float,
        val bob: Float,
        val lean: Float,
        val split: Float,
        val roll: Float,
        val hip: Float,
        val spring: Float = 1f,
    )

    /** The standard walk: what the tests of the gait's mechanics are written against. */
    val NEUTRAL = Style(1f, 25f, 25f, 1f, 3.5f, 0f, 1f, 1f)

    private fun spread(look: Int, salt: Int, lo: Float, hi: Float): Float =
        lo + (hi - lo) * Ambient.hash(look + 1, 50, salt)

    private val STYLES: Array<Style> =
        Array(Looks.COUNT) { look ->
            val arm = spread(look, 1, 15f, 33f)
            val less = spread(look, 2, .72f, 1f)
            val nearLess = Ambient.hash(look + 1, 50, 3) < .5f
            Style(
                stride = spread(look, 4, .88f, 1.12f),
                armNear = if (nearLess) arm * less else arm,
                armFar = if (nearLess) arm else arm * less,
                bob = spread(look, 5, .65f, 1.4f),
                lean = spread(look, 6, 1.8f, 5.4f),
                split = spread(look, 7, -.03f, .03f),
                roll = spread(look, 8, .6f, 1.35f),
                hip = spread(look, 9, .75f, 1.25f),
            )
        }

    /** The [Style] look [look] (one of [Looks.ALL]) walks in: the same every time. */
    fun style(look: Int): Style = STYLES[look]

    /** How far look [look]'s cycle goes, at the rig's own size: its own stride. */
    fun stride(look: Int): Float = STRIDE * style(look).stride

    /**
     * The size [phase] and [liveTravel] want for look [look] drawn [size] times the rig: their legs
     * are as long as they are tall, and their stride is their own.
     */
    fun pace(size: Float, look: Int): Float =
        size * Looks.ALL[look].body.height * style(look).stride

    /**
     * How one person walks, beyond their look: applied as they are drawn, so it costs nothing
     * baked. [tempo] scales the cadence (and so the speed: the stride stays); [lean] is a tiny
     * forward (positive) or backward lean of the whole figure in degrees, drawn as a skew; [bob]
     * scales how much the bob varies from step to step; [limp] is 0 for most, else how marked the
     * limp is (one step quicker and a little lower every cycle); [hitch] is how often a step lands
     * late (0 to 1, a share of the strides).
     */
    class Signature(
        val tempo: Float,
        val lean: Float,
        val bob: Float,
        val limp: Float,
        val hitch: Float,
    )

    /** The share of people who walk with a hint of a limp. */
    const val LIMPERS = .06f

    /** The cadence scale of [seed]'s walk, .92 to 1.08. */
    fun tempo(seed: Int): Float = .92f + .16f * Ambient.hash(seed, 51, 1)

    /** The whole-figure lean of [seed]'s walk, -1 to 2.5 degrees, forward positive. */
    fun lean(seed: Int): Float = -1f + 3.5f * Ambient.hash(seed, 51, 2)

    /** How much [seed]'s bob varies from step to step, .6 to 1.4. */
    fun bobScale(seed: Int): Float = .6f + .8f * Ambient.hash(seed, 51, 3)

    /** How marked [seed]'s limp is: 0 for all but [LIMPERS] of people, else .6 to 1. */
    fun limp(seed: Int): Float =
        if (Ambient.hash(seed, 51, 4) < LIMPERS) .6f + .4f * Ambient.hash(seed, 51, 5) else 0f

    /** How often one of [seed]'s steps lands late: none for someone with a limp. */
    fun hitchRate(seed: Int): Float =
        if (limp(seed) > 0f) 0f else .08f + .12f * Ambient.hash(seed, 51, 6)

    /** The [Signature] of [seed]: the same every time. Not for every frame: hold on to it. */
    fun signature(seed: Int): Signature =
        Signature(tempo(seed), lean(seed), bobScale(seed), limp(seed), hitchRate(seed))

    // ------------------------------------------------------------------------ the live walk

    /**
     * The strides (cycles, unwrapped) [distance] along a walk is at, for a walker drawn [size]
     * times the rig, starting [from]: [phase] is its fraction.
     */
    fun strides(distance: Float, size: Float, from: Float = PASSING): Float =
        from + distance / (STRIDE * size)

    private fun smoothF(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** A delay of one step: a slow dip over [rise] strides and a slower catch-up over [fall]. */
    private fun delay(u: Float, rise: Float, fall: Float): Float =
        when {
            u <= 0f -> 0f
            u < rise -> smoothF(u / rise)
            u < rise + fall -> 1f - smoothF((u - rise) / fall)
            else -> 0f
        }

    private const val WANDER = .03f
    private const val WANDER_F1 = .37f
    private const val WANDER_F2 = .23f
    private const val HITCH = .07f
    private const val HITCH_RISE = .3f
    private const val HITCH_FALL = 1f
    private const val LIMP = .045f
    private const val LIMP_RISE = .22f
    private const val LIMP_FALL = .45f

    /** Where in each stride [seed]'s limp has its bad step land. */
    private fun limpAt(seed: Int): Float = Ambient.hash(seed, 51, 7)

    /**
     * How far, in strides, [seed]'s walk is ahead (positive) or behind the steady one at [s]
     * strides: a slow wander of a few per cent, the occasional step that lands late, and a limp for
     * those who have one. Always more than -1 a stride in slope, so the walk never stops or goes
     * backwards, and never further than [LIVE_BOUND] strides from steady.
     */
    fun liveOffset(s: Float, seed: Int): Float {
        val p1 = Ambient.hash(seed, 51, 8) * 2f * PI.toFloat()
        val p2 = Ambient.hash(seed, 51, 9) * 2f * PI.toFloat()
        var off =
            WANDER *
                .5f *
                (sin(2f * PI.toFloat() * WANDER_F1 * s + p1) +
                    sin(2f * PI.toFloat() * WANDER_F2 * s + p2))
        val k0 = floor(s).toInt()
        val limp = limp(seed)
        if (limp > 0f) {
            val at = limpAt(seed)
            for (k in k0 - 1..k0) off -= LIMP * limp * delay(s - (k + at), LIMP_RISE, LIMP_FALL)
        } else {
            val rate = hitchRate(seed)
            for (k in k0 - 2..k0) {
                if (Ambient.hash(seed, k, 70) >= rate) continue
                val c = k + Ambient.hash(seed, k, 71)
                off -= HITCH * delay(s - c, HITCH_RISE, HITCH_FALL)
            }
        }
        return off
    }

    /** The furthest [liveOffset] goes from steady, in strides. */
    const val LIVE_BOUND = WANDER + 2f * HITCH

    /**
     * The distance [seed] has really walked when a steady walk would have gone [travel], drawn at
     * [pace] (see [pace]): steady plus [liveOffset]. Use it BOTH for where the walker is and for
     * the frame ([phase] of it), and the feet never slide. Monotonic in [travel].
     */
    fun liveTravel(travel: Float, pace: Float, seed: Int): Float {
        val stride = STRIDE * pace
        return travel + liveOffset(travel / stride, seed) * stride
    }

    /** [phase] of [liveTravel]: for a walker placed at that live distance. */
    fun livePhase(travel: Float, pace: Float, seed: Int, from: Float = PASSING): Float =
        phase(liveTravel(travel, pace, seed), pace, from)

    /**
     * The extra rise (positive up) of the whole figure, in the rig's units, at [s] strides (as
     * [strides] counts them: the baked bob is lowest at each contact): each step bobs a little more
     * or less than the frames show, and a limp dips on its bad step. Scale by the size drawn.
     */
    fun bob(s: Float, seed: Int): Float {
        val k = floor(2f * s).toInt()
        val a = .18f * bobScale(seed) * (2f * Ambient.hash(seed, k, 75) - 1f)
        var up = a * (.5f - .5f * cos(4f * PI.toFloat() * s))
        val limp = limp(seed)
        if (limp > 0f) {
            val u = s - limpAt(seed)
            val f = u - floor(u)
            if (f < .5f) {
                val d = sin(2f * PI.toFloat() * f)
                up -= .3f * limp * d * d
            }
        }
        return up
    }

    // ------------------------------------------------------------------------ standing

    /** How long one shift of the weight from foot to foot takes, in seconds, for [seed]. */
    fun swayPeriod(seed: Int): Float = 3.2f + 1.8f * Ambient.hash(seed, 51, 10)

    /** How far the weight shift moves the whole figure sideways, at most, in the rig's units. */
    const val SWAY = .32f

    /**
     * Where someone standing has their weight at [seconds]: the whole figure drifts this far
     * sideways (the rig's units, scale by the size drawn), to and fro every [swayPeriod], resting a
     * moment on each foot. Starts at 0 at [seconds] 0.
     */
    fun sway(seconds: Float, seed: Int): Float {
        val s = sin(2f * PI.toFloat() * seconds / swayPeriod(seed))
        return SWAY * s * (3f - s * s) / 2f
    }

    /**
     * How far the figure settles (down, as a positive number in the rig's units) while the weight
     * is between the feet: 0 when it rests on one.
     */
    fun settle(seconds: Float, seed: Int): Float {
        val s = sin(2f * PI.toFloat() * seconds / swayPeriod(seed))
        return .07f * (1f - s * s)
    }

    // ------------------------------------------------------------------------------ the jog

    /** A periodic curve through [t] (one value at each of its equal steps round a cycle) at [u]. */
    private fun round(t: DoubleArray, u: Double): Double {
        val n = t.size
        val x = (u - floor(u)) * n
        val i = floor(x).toInt()
        val f = x - i
        val a = t[(i - 1 + n) % n]
        val b = t[i % n]
        val c = t[(i + 1) % n]
        val d = t[(i + 2) % n]
        return .5 *
            (2 * b +
                (-a + c) * f +
                (2 * a - 5 * b + 4 * c - d) * f * f +
                (-a + 3 * b - 3 * c + d) * f * f * f)
    }

    /**
     * The knee's bend and the foot's tilt of a running leg, 16 steps round its own cycle (0 = the
     * foot lands).
     */
    private val JOG_KNEE =
        doubleArrayOf(
            16.0,
            30.0,
            40.0,
            38.0,
            32.0,
            28.0,
            34.0,
            58.0,
            84.0,
            104.0,
            108.0,
            100.0,
            82.0,
            62.0,
            40.0,
            24.0,
        )
    private val JOG_TILT =
        doubleArrayOf(
            6.0,
            2.0,
            -2.0,
            -8.0,
            -16.0,
            -28.0,
            -38.0,
            -30.0,
            -18.0,
            -8.0,
            -4.0,
            0.0,
            4.0,
            8.0,
            10.0,
            8.0,
        )

    private fun jogThigh(u: Double) = 15.0 + 27.0 * cos(2 * PI * (u - .85))

    /** The jog's pose at [phi] of the cycle (0 = the near foot lands). */
    fun jogPose(phi: Float): FloatArray {
        val f = phi.toDouble() - floor(phi.toDouble())
        val uf = (f + .5) % 1.0
        val ln = jogThigh(f)
        val lf = jogThigh(uf)
        val kn = round(JOG_KNEE, f)
        val kf = round(JOG_KNEE, uf)
        val ann = round(JOG_TILT, f) - (ln - kn)
        val anf = round(JOG_TILT, uf) - (lf - kf)
        // in flight (after one foot leaves and before the other lands) the whole body is off the
        // floor
        var lift = 0.0
        for (s in doubleArrayOf(.36, .86)) {
            val q = (f - s) / .14
            if (q in 0.0..1.0) lift = 1.15 * sin(PI * q) * sin(PI * q)
        }
        val c = cos(2 * PI * (f - .03))
        val an = -36.0 * c
        val af = 36.0 * c
        val sw = cos(2 * PI * f)
        return pose(
            turn = .92f,
            lean = 9f,
            head = -3f,
            an = an.toFloat(),
            en = (84.0 + 10.0 * (an + 36) / 72).toFloat(),
            af = af.toFloat(),
            ef = (84.0 + 10.0 * (af + 36) / 72).toFloat(),
            ln = ln.toFloat(),
            kn = kn.toFloat(),
            lf = lf.toFloat(),
            kf = kf.toFloat(),
            lift = lift.toFloat(),
            ann = ann.toFloat(),
            anf = anf.toFloat(),
            twist = (.5 * sw - BASE_HIP * 0).toFloat(),
            shx = (-.7 * sw).toFloat(),
        )
    }

    /** The jog's key poses: eight, round to the first. */
    val JOG_KEYS: List<FloatArray> = List(9) { jogPose((it % 8) / 8f) }

    /**
     * The key poses of a walk, one for each of its [FRAMES] (contact, down, passing, up and the
     * in-betweens, and again on the other leg) and back to the first: the walk's curves (the toes
     * leaving at 62 per cent, the knee most bent at 72, the heel let down onto the floor) are too
     * quick for a spline through only the eight classic poses to follow without the feet skating.
     */
    val WALK_KEYS: List<FloatArray> = walkKeys(NEUTRAL)

    /** [WALK_KEYS] walked in [style]. */
    fun walkKeys(style: Style): List<FloatArray> =
        List(FRAMES + 1) { walkPose((it % FRAMES) / FRAMES.toFloat(), style) }

    /** The key poses of each look's own walk. */
    val LOOK_WALK_KEYS: List<List<FloatArray>> = List(Looks.COUNT) { walkKeys(style(it)) }
}
