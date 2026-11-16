package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/*
 * The life of the pavement and the front road, with no drawing in it: now and then someone walks the whole street
 * from one end to the other (a plain walker, someone with a dog that stops once to sniff, a cyclist, two people
 * side by side), and now and then a car passes on the near road. Like [Ambient], everything is a function of the
 * seconds the tab has been showing and a seed, so a moment is the same every time it is asked for, and nothing
 * is made per frame: the painter keeps the [Passer]s and the [CarPass] this fills in. A walker's steps follow
 * the ground they cover ([StreetGait.phase]), so nobody skates, whatever their size or pace.
 */

/** Who is passing. */
enum class Stroller {
    WALKER,
    DOG_WALKER,
    CYCLIST,
    PAIR,
}

/** Someone on the pavement this moment, written by [StreetLife.passers]. */
class Passer {
    var on = false

    /** Which of the street's slots they set out in ([StreetLife.start]): who they are. */
    var slot = 0
    var kind = Stroller.WALKER

    /**
     * Where their feet are along the street, and how far out on the pavement (y, 0 at the wall).
     */
    var x = 0f
    var lane = StreetLife.LANE

    /** 1 walking towards the street's far end (right), -1 towards its start. */
    var dir = 1f
    var look = 0

    /** The rig's scale for this walker ([StreetLife.SIZE] times their height factor). */
    var size = 1f

    /** How far they have walked, in street units. */
    var travel = 0f

    /** The walk's frame, the one beside it and how much of that one shows. */
    var frame = 0
    var next = 0
    var mix = 0f

    /**
     * How settled into standing they are, 0 walking to 1 standing still (a dog walker's pause): the
     * baked settle frames show it.
     */
    var still = 0f

    /** A companion walking beside them (a [Stroller.PAIR]): a little behind and nearer the kerb. */
    var look2 = 0
    var size2 = 1f
    var frame2 = 0
    var next2 = 0
    var mix2 = 0f

    /**
     * The dog ahead of a [Stroller.DOG_WALKER]: where it is, its trot frames, how much it sniffs.
     */
    var dogX = 0f
    var dogFrame = 0
    var dogNext = 0
    var dogMix = 0f
    var sniff = 0f

    /** A [Stroller.CYCLIST]'s cranks and wheels, in radians. */
    var crank = 0f
    var wheel = 0f

    /** What the walker's hands and head are doing ([StreetActs]), and their companion's. */
    val act = Gesture()
    val act2 = Gesture()

    /** How they walk ([StreetManners]), and how far they lean into it, degrees forward. */
    var manner = Manner.AVERAGE
    var lean = 0f

    /** What they have stopped for this moment (easing to it or from it), [Halt.NONE] walking. */
    var halt = Halt.NONE

    /** Their own way along the pavement, before they step aside for someone ([lane]). */
    var lane0 = StreetLife.LANE

    /**
     * The stops planned on their walk: how many, how far along each is, the walk's clock as they
     * begin to ease into it, how long they stand and why.
     */
    var stops = 0
    val stopD = FloatArray(StreetLife.MOST_STOPS)
    val stopC = FloatArray(StreetLife.MOST_STOPS)
    val stopH = FloatArray(StreetLife.MOST_STOPS)
    val stopHalt = Array(StreetLife.MOST_STOPS) { Halt.NONE }

    /** Which walk the plan is for (worked out once, not every frame). */
    internal var planK = Int.MIN_VALUE
    internal var planSeed = 0
    internal var planFrom = 0f
    internal var planTo = 0f
    internal var planShops = 0
}

/**
 * A car on the front road this moment, one of the few [StreetLife.cars] writes: which lane, where
 * and which way, its body, paint and extras, its wheels' angle and the body's rise on its springs.
 */
class CarPass {
    var on = false

    /** The middle of the car along the street. */
    var x = 0f

    /** 1 driving right, -1 left. */
    var dir = 1f

    /** 0 the near lane, 1 the far one: a little further up the road, drawn a little smaller. */
    var lane = 0

    /** Which body ([StreetLife.CAR_BODIES]) and which paint ([StreetLife.CAR_PAINTS]). */
    var body = 0
    var paint = 0

    /** A taxi's roof sign, a roof rack. */
    var taxi = false
    var rack = false

    /** Its speed in street units a second, and its wheels' angle in radians. */
    var speed = 0f
    var wheel = 0f

    /** The body's rise over its wheels on the springs, in street units (up is positive). */
    var bob = 0f

    /** How far through the pass, 0 to 1. */
    var t = 0f
}

object StreetLife {
    /** The street walkers are drawn this many times the rig's size: a person at street scale. */
    const val SIZE = 1.14f

    /** How far out on the pavement they walk (y, the wall at 0 and the kerb at 16). */
    const val LANE = 10f

    /** Someone sets out about every this many seconds (within [JITTER] of the period's start). */
    const val PERIOD = 55f
    const val JITTER = 15f
    const val CHANCE = .9f

    /** How far beyond the street's ends they appear and vanish. */
    const val MARGIN = 40f

    /** The most passers kept at once. */
    const val MOST = 5

    /** The most stops planned on one walk. */
    const val MOST_STOPS = 2

    /** A cyclist's speed next to a walker's. */
    const val CYCLIST = 2.4f

    /** How far ahead of its walker a dog trots, how big it is, and how long it stops to sniff. */
    const val DOG_LEAD = 12f
    const val DOG_SIZE = .9f
    const val SNIFF = 1.8f

    /**
     * Settling to a stand and setting off again take this long, and easing to a stop and from it.
     */
    const val SETTLE = .35f
    const val STOP = .55f
    const val START = .3f

    /** How far behind and how much nearer the kerb a pair's companion walks. */
    const val SIDE_BACK = 4.5f
    const val SIDE_OUT = 3f

    /**
     * The front road: two lanes, the near one driving right and the far one left. Each lane has a
     * slot every [CAR_PERIOD] seconds (starting within [CAR_JITTER] of it) that the hour's
     * [traffic] fills or not; a filled slot sends one car, or a short bunch of two or three nose to
     * tail. Speeds vary a little from bunch to bunch, never enough for one to catch another.
     */
    const val CAR_PERIOD = 22f
    const val CAR_JITTER = 8f
    const val CAR_SPEED = 100f
    const val CAR_MARGIN = 90f
    const val CAR_LANES = 2

    /**
     * The most cars kept at once (both lanes, the whole street and its margins): room for two
     * bunches of three a lane on a long street; the painter draws only those in view.
     */
    const val CAR_MOST = 12

    /** Sedan, hatchback, SUV, city car, estate, van. */
    const val CAR_BODIES = 6

    /**
     * White, black, silver, gunmetal, navy, red, green, champagne, blue, burgundy and taxi yellow.
     */
    const val CAR_PAINTS = 11
    const val TAXI_PAINT = 10

    /** How much higher up the road the far lane is, and how much smaller its cars are drawn. */
    const val FAR_LANE_UP = 6f
    const val FAR_LANE_SCALE = .9f

    /** How many slots back a passer can still be walking: a long street takes a few. */
    private const val LOOKBACK = 8

    /**
     * The scale of [look]'s stride: everyone is about as tall as the street's people are, their
     * legs as long as they are tall, and an older look's steps are short ([StreetGait.style]).
     */
    fun pace(look: Int): Float = SIZE * StreetLooks.ALL[look].height * StreetGait.style(look).stride

    /**
     * How fast [look] walks at their own cadence ([StreetGait.cadence]), in street units a second.
     */
    fun speed(look: Int): Float = pace(look) * Gait.SPEED * StreetGait.cadence(look)

    fun kind(seed: Int, k: Int): Stroller {
        val h = Ambient.hash(seed, k, 201)
        return when {
            h < .5f -> Stroller.WALKER
            h < .7f -> Stroller.DOG_WALKER
            h < .85f -> Stroller.CYCLIST
            else -> Stroller.PAIR
        }
    }

    fun look(seed: Int, k: Int): Int = StreetManners.look(Ambient.hash(seed, k, 202))

    fun dir(seed: Int, k: Int): Float = if (Ambient.hash(seed, k, 203) < .5f) -1f else 1f

    /** When the passer of slot [k] sets out, in seconds. */
    fun start(seed: Int, k: Int): Float = k * PERIOD + Ambient.hash(seed, k, 204) * JITTER

    fun happens(seed: Int, k: Int): Boolean = k >= 0 && Ambient.hash(seed, k, 200) < CHANCE

    /** How far along the street from [from] to [to] (margins included) a walk goes. */
    fun length(from: Float, to: Float): Float = to - from + 2f * MARGIN

    /**
     * Everyone on the pavement at [seconds] on a street from [from] to [to] (its buildings' middles
     * at [shops], for those who stop at a window), written into [out]; how many there are. At rest
     * ([seconds] 0) nobody.
     */
    fun passers(
        seconds: Float,
        seed: Int,
        from: Float,
        to: Float,
        out: Array<Passer>,
        shops: List<Float>? = null,
    ): Int {
        var n = 0
        for (p in out) p.on = false
        if (seconds <= 0f) return 0
        val k0 = floor(seconds / PERIOD).toInt()
        var k = k0
        while (k > k0 - LOOKBACK && n < out.size && n < MOST) {
            if (happens(seed, k)) {
                val t = seconds - start(seed, k)
                if (t >= 0f && fill(seed, k, t, from, to, out[n], shops)) n++
            }
            k--
        }
        makeWay(out, n)
        return n
    }

    /**
     * Fills [p] with the passer of slot [k], [t] seconds into their walk, on a street whose
     * buildings stand at [shops] (their middles: someone looking in a window stops at one); false
     * once it is over.
     */
    fun fill(
        seed: Int,
        k: Int,
        t: Float,
        from: Float,
        to: Float,
        p: Passer,
        shops: List<Float>? = null,
    ): Boolean {
        p.on = false
        p.still = 0f
        p.sniff = 0f
        p.halt = Halt.NONE
        val kind = kind(seed, k)
        var look = look(seed, k)
        // a lead in the hand that holds a stick: someone else walks the dog
        if (kind == Stroller.DOG_WALKER && StreetLooks.ALL[look].stick) look = ELDER_LOOK
        val dir = dir(seed, k)
        val pair = kind == Stroller.PAIR
        val look2 = if (pair) companion(seed, k, look) else look
        val manner = manner(seed, k, kind, look)
        val size = pace(look)
        val length = length(from, to)
        val v = speed(seed, k, kind, look, look2, manner)
        val busy =
            kind == Stroller.DOG_WALKER ||
                StreetLooks.ALL[look].bag == Bag.HAND ||
                StreetLooks.ALL[look].stick
        val busy2 = StreetLooks.ALL[look2].bag == Bag.HAND || StreetLooks.ALL[look2].stick
        var travel: Float
        when (kind) {
            Stroller.DOG_WALKER -> {
                p.stops = 0
                // they stop once, on the passing pose (a whole number of strides along), settle to
                // a stand while the dog sniffs, and set off again
                val stride = Gait.STRIDE * size
                val at = round(length * (.3f + .4f * Ambient.hash(seed, k, 205)) / stride) * stride
                val tc = max(0f, (at - v * STOP / 2f) / v)
                val tStop = tc + STOP
                val tHold = tStop + SNIFF
                if (t < tStop) {
                    travel = gone(t, v, 0f, tc, STOP)
                    if (t > tStop - SETTLE) p.still = (t - (tStop - SETTLE)) / SETTLE
                } else if (t < tHold) {
                    travel = at
                    p.still = 1f
                } else {
                    travel = at + gone(t - tHold, v, START, 1e9f, 0f)
                    p.still = (1f - (t - tHold) / SETTLE).coerceIn(0f, 1f)
                }
                p.sniff = p.still
                StreetActs.at(seed, k, 0, t, look, true, p.act, manner)
            }
            Stroller.CYCLIST -> {
                p.stops = 0
                travel = v * t
                p.act.clear()
            }
            else -> {
                // on foot: the walk's own clock runs with the time but stands still through each
                // stop (easing down to it and up from it), and what the hands do and the ground
                // covered both follow that clock, so a stop lands where it was planned
                plan(seed, k, kind, manner, look, from, to, dir, v, busy, shops, p)
                var off = 0f
                var c = t
                var halted = -1
                var u = 0f
                val pi = PI.toFloat()
                for (i in 0 until p.stops) {
                    val r = p.stopC[i] + off
                    if (t < r) {
                        c = t - off
                        break
                    }
                    u = t - r
                    val h = p.stopH[i]
                    if (u < HALT_STOP + h + HALT_START) {
                        halted = i
                        c =
                            if (u < HALT_STOP)
                                p.stopC[i] +
                                    u / 2f +
                                    HALT_STOP / (2f * pi) * sin(pi * u / HALT_STOP)
                            else if (u < HALT_STOP + h) p.stopC[i] + HALT_STOP / 2f
                            else {
                                val w = u - HALT_STOP - h
                                p.stopC[i] + HALT_STOP / 2f + w / 2f -
                                    HALT_START / (2f * pi) * sin(pi * w / HALT_START)
                            }
                        p.still =
                            if (u < HALT_STOP)
                                ((u - (HALT_STOP - SETTLE)) / SETTLE).coerceIn(0f, 1f)
                            else if (u < HALT_STOP + h) 1f
                            else (1f - (u - HALT_STOP - h) / SETTLE).coerceIn(0f, 1f)
                        break
                    }
                    off += HALT_STOP / 2f + h + HALT_START / 2f
                    c = t - off
                }
                StreetActs.at(seed, k, 0, c, look, busy, p.act, manner)
                travel = v * (c - p.act.lost)
                val q = quiet(c, p)
                p.act.w *= q
                if (halted >= 0) {
                    p.halt = p.stopHalt[halted]
                    halting(p.act, p.halt, busy, u, p.stopH[halted], seed, k, halted, 0)
                }
                if (pair) {
                    StreetActs.at(seed, k, 1, c, look2, busy2, p.act2, manner)
                    p.act2.w *= q
                    if (halted >= 0)
                        halting(p.act2, p.halt, busy2, u, p.stopH[halted], seed, k, halted, 1)
                }
            }
        }
        if (travel > length) return false
        p.act.swing = StreetManners.swing(manner)
        p.on = true
        p.slot = k
        p.kind = kind
        p.look = look
        p.manner = manner
        p.lean = if (kind == Stroller.CYCLIST) 0f else StreetManners.lean(manner)
        p.dir = dir
        p.size = size
        // on foot the ground covered is lived, not metered: the steps drift and hesitate a little
        // ([Gait.liveTravel]), less so near a stop so it still lands on the passing pose; the
        // same live distance places them and picks the frame, so the feet never slide. A cyclist
        // rolls steadily, and the dog walker keeps the metered pace so the stop lands exactly on
        // the passing pose.
        val live =
            if (kind == Stroller.CYCLIST || kind == Stroller.DOG_WALKER) travel
            else live(travel, size, seed * 31 + k, p)
        p.travel = live
        val laneH = Ambient.hash(seed, k, 206)
        p.lane0 =
            if (kind == Stroller.CYCLIST) LANE + 1f + 2f * laneH
            else if (pair) min(StreetManners.lane(manner, laneH), PAIR_LANE)
            else StreetManners.lane(manner, laneH)
        p.lane = p.lane0
        p.x = if (dir > 0f) from - MARGIN + live else to + MARGIN - live
        walk(StreetGait.phase(live, size), p)
        when (kind) {
            Stroller.DOG_WALKER -> {
                p.dogX = p.x + dir * DOG_LEAD
                val dp = live / (StreetGait.DOG_STRIDE * DOG_SIZE)
                val phase = dp - floor(dp)
                p.dogFrame = Gait.nearest(phase, StreetGait.DOG_FRAMES)
                p.dogNext = Gait.neighbour(phase, StreetGait.DOG_FRAMES)
                p.dogMix = abs(Gait.offset(phase, StreetGait.DOG_FRAMES))
            }
            Stroller.CYCLIST -> {
                p.crank = StreetGait.crank(travel)
                p.wheel = StreetGait.wheel(travel)
            }
            Stroller.PAIR -> {
                p.look2 = look2
                p.size2 = pace(look2)
                p.act2.swing = p.act.swing
                // the companion's steps are their own; when the two stop to talk, the
                // companion's land on the passing pose there too, so both settle to a stand
                val from2 =
                    if (p.stops > 0) Gait.PASSING - p.stopD[0] / (Gait.STRIDE * p.size2)
                    else Gait.PASSING + Ambient.hash(seed, k, 208)
                val phase = StreetGait.phase(travel, p.size2, from2)
                p.frame2 = Gait.nearest(phase, StreetGait.FRAMES)
                p.next2 = Gait.neighbour(phase, StreetGait.FRAMES)
                p.mix2 = abs(Gait.offset(phase, StreetGait.FRAMES))
            }
            else -> {}
        }
        return true
    }

    /** The older woman's look: she walks the dog (a stick and a lead want the same hand). */
    private const val ELDER_LOOK = 4

    /** How many looks are not the older people's (the first ones). */
    private const val YOUNGER = 4

    /** The farthest out a pair walks (the companion is [SIDE_OUT] nearer the kerb). */
    const val PAIR_LANE = 10.5f

    /** Easing down to a planned stop and up from it, seconds: a slower start than the dog's. */
    const val HALT_STOP = .8f
    const val HALT_START = .95f

    /** The hands and the head start on a stop's business this long before they stand. */
    const val HALT_LEAD = .3f

    /** How far either side of a lobby's middle its shop windows are, street units. */
    const val WINDOW = 48f

    /** How [look] of slot [k] ([kind]) walks. */
    fun manner(seed: Int, k: Int, kind: Stroller, look: Int): Manner {
        if (kind == Stroller.CYCLIST) return Manner.AVERAGE
        if (StreetLooks.ALL[look].elder) return Manner.ELDERLY
        if (kind == Stroller.PAIR)
            return if (Ambient.hash(seed, k, 210) < .55f) Manner.STROLLER else Manner.AVERAGE
        return StreetManners.manner(look, Ambient.hash(seed, k, 210))
    }

    /** The look of a pair's companion: the other older look for an older walker, else another. */
    fun companion(seed: Int, k: Int, look: Int): Int {
        if (StreetLooks.ALL[look].elder)
            return if (look == ELDER_LOOK) ELDER_LOOK + 1 else ELDER_LOOK
        return (look + 1 + (Ambient.hash(seed, k, 207) * (YOUNGER - 1)).toInt()) % YOUNGER
    }

    /**
     * How fast the passer of slot [k] goes, street units a second: their look's pace at their
     * manner's cadence, a little of their own; a pair at the slower one's.
     */
    fun speed(seed: Int, k: Int, kind: Stroller, look: Int, look2: Int, m: Manner): Float {
        if (kind == Stroller.CYCLIST) return speed(look) * CYCLIST
        val own = StreetManners.tempo(m) * (.95f + .1f * Ambient.hash(seed, k, 209))
        val v = speed(look) * own
        return if (kind == Stroller.PAIR) min(v, speed(look2) * own) else v
    }

    /**
     * Plans the stops of slot [k]'s walk into [p] (how many, where, for what and how long, and the
     * walk's clock as each begins), unless it holds them already.
     */
    private fun plan(
        seed: Int,
        k: Int,
        kind: Stroller,
        m: Manner,
        look: Int,
        from: Float,
        to: Float,
        dir: Float,
        v: Float,
        busy: Boolean,
        shops: List<Float>?,
        p: Passer,
    ) {
        val shopsKey = shops?.size ?: -1
        if (
            p.planK == k &&
                p.planSeed == seed &&
                p.planFrom == from &&
                p.planTo == to &&
                p.planShops == shopsKey
        )
            return
        p.planK = k
        p.planSeed = seed
        p.planFrom = from
        p.planTo = to
        p.planShops = shopsKey
        p.stops = 0
        val pair = kind == Stroller.PAIR
        val n = StreetManners.stops(m, pair, Ambient.hash(seed, k, 240))
        if (n == 0) return
        val length = length(from, to)
        val stride = Gait.STRIDE * pace(look)
        val stick = StreetLooks.ALL[look].stick
        var lastD = 0f
        var lastEnd = 0f
        for (i in 0 until n) {
            val frac =
                if (n == 1) .2f + .6f * Ambient.hash(seed, k, 241)
                else if (i == 0) .14f + .28f * Ambient.hash(seed, k, 241)
                else .58f + .28f * Ambient.hash(seed, k, 242)
            val halt = StreetManners.halt(m, pair, stick, Ambient.hash(seed, k, 243 + i))
            var d = length * frac
            if (halt == Halt.WINDOW && !shops.isNullOrEmpty()) {
                // the nearest shop window (either side of a lobby's door) to where they would be
                val x = if (dir > 0f) from - MARGIN + d else to + MARGIN - d
                var best = x
                var bestD = Float.MAX_VALUE
                for (s in shops.indices) {
                    val c = shops[s]
                    for (side in 0..1) {
                        val w = c + (if (side == 0) -WINDOW else WINDOW)
                        if (w < from || w > to) continue
                        val e = abs(w - x)
                        if (e < bestD) {
                            bestD = e
                            best = w
                        }
                    }
                }
                d = if (dir > 0f) best - from + MARGIN else to + MARGIN - best
            }
            // a whole number of strides along: they settle to a stand from the passing pose
            d = round(d / stride) * stride
            if (d < lastD + 4f * stride || d > length - 2f * MARGIN) continue
            // the clock at which the cruise gets there: the walk less what reading the phone has
            // slowed it (a contraction: a few rounds settle it)
            var c = d / v
            for (r in 0 until 8) {
                StreetActs.at(seed, k, 0, c, look, busy, p.act, m)
                c = d / v + p.act.lost
            }
            val start = c - HALT_STOP / 2f
            if (start < lastEnd + 1f) continue
            val j = p.stops
            p.stopD[j] = d
            p.stopC[j] = start
            p.stopH[j] = StreetManners.hold(m, halt, Ambient.hash(seed, k, 245 + i))
            p.stopHalt[j] = halt
            p.stops = j + 1
            lastD = d
            lastEnd = start + HALT_STOP / 2f + HALT_START / 2f
        }
    }

    /**
     * How much of what the hands are doing shows at the walk's clock [c]: none from just before a
     * stop to just after it (the stop's own business has the hands then), easing back either side.
     */
    private fun quiet(c: Float, p: Passer): Float {
        var q = 1f
        for (i in 0 until p.stops) {
            val a = p.stopC[i] - .15f
            val b = p.stopC[i] + HALT_STOP / 2f + HALT_START / 2f + .15f
            val d = if (c < a) a - c else if (c > b) c - b else 0f
            q = min(q, smooth(d / .5f))
        }
        return q
    }

    /**
     * Writes into [g] what the hands and the head do [u] seconds into a stop for [halt] that holds
     * [hold] seconds: the business begins just before they stand and is over as they set off.
     */
    private fun halting(
        g: Gesture,
        halt: Halt,
        busy: Boolean,
        u: Float,
        hold: Float,
        seed: Int,
        k: Int,
        i: Int,
        who: Int,
    ) {
        val x = u - (HALT_STOP - HALT_LEAD)
        val dur = hold + HALT_LEAD
        if (x <= 0f || x >= dur) return
        val act = StreetManners.act(halt, busy)
        g.act = act
        g.t = x
        g.dur = dur
        g.w = StreetActs.envelope(x, dur, StreetActs.blend(act))
        g.vary = Ambient.hash(seed * 131 + who * 17, k, 250 + i)
    }

    private fun smooth(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /**
     * The ground [p] has covered, lived: [travel] metered with [Gait.liveTravel]'s drift, which
     * fades out within a couple of strides of a planned stop.
     */
    private fun live(travel: Float, size: Float, seed: Int, p: Passer): Float {
        val stride = Gait.STRIDE * size
        var w = 1f
        for (i in 0 until p.stops) {
            val d = abs(travel - p.stopD[i]) / stride
            w = min(w, smooth((d - .3f) / 1.7f))
        }
        if (w <= 0f) return travel
        return travel + w * (Gait.liveTravel(travel, size, seed) - travel)
    }

    /**
     * Two people on the pavement where they would walk through each other (one overtaking the
     * other, or two coming the other way) step apart across the pavement: each moves half of what
     * keeps a little room between them, easing in as they draw near and out once past.
     */
    private fun makeWay(out: Array<Passer>, n: Int) {
        for (i in 0 until n) out[i].lane = out[i].lane0
        // a few rounds, so that someone against the wall or the kerb leaves the moving to the other
        for (round in 0 until 3) for (i in 0 until n) for (j in i + 1 until n) {
            val a = out[i]
            val b = out[j]
            val gap = max(lo(b) - hi(a), lo(a) - hi(b))
            val w = 1f - smooth((gap - 2f) / 14f)
            if (w <= 0f) continue
            // the one nearer the wall stays nearer it
            val aLow = a.lane0 < b.lane0 || (a.lane0 == b.lane0 && a.dir < b.dir)
            val low = if (aLow) a else b
            val high = if (aLow) b else a
            val base = high.lane0 - low.lane0
            val want = max(base, base + (2f * BAND + ROOM + outer(low) - base) * w)
            val short = want - (high.lane - low.lane)
            if (short <= 0f) continue
            val downRoom = max(0f, low.lane - LANE_MIN)
            val upRoom = max(0f, laneMax(high) - high.lane)
            var down = min(short / 2f, downRoom)
            val up = min(short - down, upRoom)
            down = min(short - up, downRoom)
            low.lane -= down
            high.lane += up
        }
    }

    /**
     * The nearest the wall and the kerb anyone walks, making way (a pair's companion [SIDE_OUT]
     * further out, at the kerb's edge when two pairs pass).
     */
    private const val LANE_MIN = 7f
    private const val LANE_MAX = 13.5f
    private const val PAIR_MAX = 12f

    private fun laneMax(p: Passer): Float = if (p.kind == Stroller.PAIR) PAIR_MAX else LANE_MAX

    /** How much of the pavement's depth one person takes either side of their lane. */
    private const val BAND = .8f

    /** The room kept between two people's bands when one passes another. */
    private const val ROOM = .6f

    /** How much further out than its lane a passer reaches: a pair's companion. */
    private fun outer(p: Passer): Float = if (p.kind == Stroller.PAIR) SIDE_OUT else 0f

    /** Where [p] begins along the street and where it ends, with a little room. */
    private fun lo(p: Passer): Float =
        when (p.kind) {
            Stroller.PAIR -> min(p.x, p.x - p.dir * SIDE_BACK) - 3f
            Stroller.DOG_WALKER -> min(p.x, p.dogX) - 4f
            Stroller.CYCLIST -> p.x - 12f
            else -> p.x - 3f
        }

    private fun hi(p: Passer): Float =
        when (p.kind) {
            Stroller.PAIR -> max(p.x, p.x - p.dir * SIDE_BACK) + 3f
            Stroller.DOG_WALKER -> max(p.x, p.dogX) + 4f
            Stroller.CYCLIST -> p.x + 12f
            else -> p.x + 3f
        }

    private fun walk(phase: Float, p: Passer) {
        p.frame = Gait.nearest(phase, StreetGait.FRAMES)
        p.next = Gait.neighbour(phase, StreetGait.FRAMES)
        p.mix = abs(Gait.offset(phase, StreetGait.FRAMES))
    }

    /**
     * How far a walk of cruising speed [v] has gone [t] seconds in: [up] seconds to come up to
     * pace, [tc] at pace, [down] to stop (as [Visit] walks).
     */
    fun gone(t: Float, v: Float, up: Float, tc: Float, down: Float): Float {
        val pi = PI.toFloat()
        if (up > 0f && t < up) return v * (t / 2f - up / (2f * pi) * sin(pi * t / up))
        val a = v * up / 2f
        if (t < up + tc || down <= 0f) return a + v * (t - up)
        val tau = min(t - up - tc, down)
        return a + v * tc + v * (tau / 2f + down / (2f * pi) * sin(pi * tau / down))
    }

    // ---- the front road

    /** How common each body is, and each paint (white the most, then black, the greys...). */
    private val BODY_SHARE = floatArrayOf(.24f, .2f, .22f, .12f, .11f, .11f)
    private val PAINT_SHARE =
        floatArrayOf(.24f, .17f, .14f, .14f, .09f, .08f, .03f, .04f, .04f, .03f)

    /** Each body's wheel radius, in street units. */
    private val WHEEL_R = floatArrayOf(4.7f, 4.6f, 5.2f, 4.3f, 4.7f, 4.8f)

    /** The largest wheel's radius. */
    const val CAR_WHEEL = 5.2f

    fun carWheel(body: Int): Float = WHEEL_R[body]

    private val TRAFFIC_AT = floatArrayOf(0f, 5f, 7f, 8.5f, 10f, 13f, 16f, 18f, 20f, 22f, 24f)
    private val TRAFFIC =
        floatArrayOf(.24f, .18f, .7f, .95f, .72f, .8f, .82f, .95f, .62f, .4f, .24f)

    /** The share of a lane's slots that send a car at [hour]: busiest in the rush hours. */
    fun traffic(hour: Float): Float {
        val h = ((hour % 24f) + 24f) % 24f
        var i = 0
        while (i < TRAFFIC_AT.size - 2 && h >= TRAFFIC_AT[i + 1]) i++
        val u = ((h - TRAFFIC_AT[i]) / (TRAFFIC_AT[i + 1] - TRAFFIC_AT[i])).coerceIn(0f, 1f)
        return TRAFFIC[i] + (TRAFFIC[i + 1] - TRAFFIC[i]) * u
    }

    private fun pick(h: Float, shares: FloatArray): Int {
        var total = 0f
        for (s in shares) total += s
        var acc = 0f
        for (i in shares.indices) {
            acc += shares[i] / total
            if (h < acc) return i
        }
        return shares.size - 1
    }

    /** How many cars [slot] sends: mostly one, now and then two or three nose to tail. */
    fun bunch(seed: Int, slot: Int): Int {
        val h = Ambient.hash(seed, slot, 226)
        return if (h < .62f) 1 else if (h < .88f) 2 else 3
    }

    /**
     * The cars on the front road at [seconds] between [from] and [to] (and [CAR_MARGIN] beyond),
     * [busy] ([traffic]) of the slots sending one, written into [out] far lane first (the order to
     * draw them in); returns how many. Nothing is made: [out]'s cars are refilled.
     */
    fun cars(
        seconds: Float,
        seed: Int,
        from: Float,
        to: Float,
        busy: Float,
        out: Array<CarPass>,
    ): Int {
        for (c in out) c.on = false
        if (seconds <= 0f) return 0
        val length = to - from + 2f * CAR_MARGIN
        var n = 0
        val k0 = floor(seconds / CAR_PERIOD).toInt()
        for (lane in CAR_LANES - 1 downTo 0) {
            val base = if (lane == 0) CAR_SPEED else CAR_SPEED * .92f
            // the slots far enough back that their last car may still be on the road
            val longest = length / (base * .9f) + CAR_JITTER + 8f
            val back = (longest / CAR_PERIOD).toInt() + 1
            for (k in k0 - back..k0) {
                if (k < 0) continue
                val slot = k * CAR_LANES + lane
                if (Ambient.hash(seed, slot, 220) >= busy) continue
                val start = k * CAR_PERIOD + Ambient.hash(seed, slot, 221) * CAR_JITTER
                val speed = base * (.9f + .2f * Ambient.hash(seed, slot, 223))
                var gap = 0f
                for (j in 0 until bunch(seed, slot)) {
                    if (j > 0) gap += 1.1f + 1.3f * Ambient.hash(seed, slot, 230 + j)
                    val t = seconds - start - gap
                    val travel = speed * t
                    if (t < 0f || travel > length) continue
                    if (n >= out.size) return n
                    val c = out[n++]
                    c.lane = lane
                    c.speed = speed
                    c.dir = if (lane == 0) 1f else -1f
                    c.x = if (c.dir > 0f) from - CAR_MARGIN + travel else to + CAR_MARGIN - travel
                    c.t = travel / length
                    dress(c, seed, slot * 4 + j, travel)
                }
            }
        }
        return n
    }

    /** Car [id]'s body, paint and extras, its wheels and springs [travel] units along. */
    private fun dress(c: CarPass, seed: Int, id: Int, travel: Float) {
        c.on = true
        c.body = pick(Ambient.hash(seed, id, 224), BODY_SHARE)
        c.taxi = (c.body == 0 || c.body == 4) && Ambient.hash(seed, id, 227) < .22f
        c.paint = if (c.taxi) TAXI_PAINT else pick(Ambient.hash(seed, id, 225), PAINT_SHARE)
        c.rack = c.body != 0 && c.body != 3 && Ambient.hash(seed, id, 228) < .18f
        c.wheel = travel / WHEEL_R[c.body]
        // the springs: a slow heave and a quicker patter from the road, out of step car to car
        val ph = Ambient.hash(seed, id, 229) * 6.28f
        c.bob = .11f * sin(travel * .095f + ph) + .045f * sin(travel * .31f + 2f * ph)
    }
}
