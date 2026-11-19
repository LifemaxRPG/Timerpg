package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * Riding the bicycle in the open world (docs/TOWN.md "Open world: the bicycle"), in street units (21.18 a metre; the walk
 * is 72 a second, the run 150). Plain vars so they can be tried out live. Simulated at 60 and 120 Hz on the build server
 * (desktop BikeRideTest), not yet felt on the S25.
 */
object RideTuning {
    /** The speed a full push holds (about 4x the walk: ~13.7 m/s) and the sprint's ([SPRINT_AFTER] s at a full push). */
    var cruise = 290f
    var sprint = 400f
    /** A push this strong counts as full (the sprint starts after [sprintAfter] s of it, ends below [sprintOff]). */
    var full = .92f
    var sprintOff = .8f
    var sprintAfter = .8f
    /** Pedalling: speeding up to the cruise, and on to the sprint (street units a second, each second). */
    var accel = 230f
    var accelSprint = 110f
    /** Coasting with the stick let go: rolling resistance and air drag (a second: [roll] + [drag] x speed squared). */
    var roll = 18f
    var drag = .00035f
    /** Easing off with a lighter push (to its lower speed), and braking (the stick pulled away from where the bike goes). */
    var ease = 150f
    var brake = 420f
    /** The push is "away" (a brake and a turn round) past this angle from where the bike heads (degrees). */
    var brakeAngle = 115f
    /** Turning round: the bike slows to this to turn tightly. */
    var turnRound = 70f
    /** The steering: how quickly the heading follows the push (s), the most it turns at low speed (rad/s). */
    var steerTime = .16f
    var rateMax = 3.2f
    /** The grip: sideways acceleration a turn may take (units/s^2: ~2.5 g, arcade), so the turning radius grows with speed. */
    var lateral = 520f
    /** Below this speed the rider walks the bike round: it turns at [pivotRate] without rolling. */
    var pivotBelow = 25f
    var pivotRate = 2.4f
    /** With the front wheel at something and the push away from it, the rider walks the bike back this fast (units a second). */
    var backUp = 40f
    /** Held fast this long with the stick pushed, the rider lifts the front wheel round (s). */
    var liftAfter = .6f
    /** A wall scraped along takes this share of the speed a second (on top of what it stops). */
    var scrape = 1.2f
    /** How far the rider leans for a turn is real (atan of v * yaw rate / g), eased this quickly (s), at most [leanMax] rad. */
    var leanTime = .12f
    var leanMax = .6f

    /** The fastest the heading may turn at [v] (rad/s): the grip's [lateral] / v, at most [rateMax]; walked round below [pivotBelow]. */
    fun maxRate(v: Float): Float = if (v < pivotBelow) pivotRate else kotlin.math.min(rateMax, lateral / v)
}

/**
 * What a surface does to a bicycle: its top speed, pedalling and rolling resistance as shares of the road's. Grass is
 * slow (the tyres sink, the rider pushes hard), gravel a little, a deck hardly.
 */
object RideSurface {
    fun top(s: Int): Float = when (s) { Surface.GRASS -> .55f; Surface.GRAVEL -> .8f; Surface.WOOD -> .95f; else -> 1f }
    fun accel(s: Int): Float = when (s) { Surface.GRASS -> .6f; Surface.GRAVEL -> .85f; else -> 1f }
    fun roll(s: Int): Float = when (s) { Surface.GRASS -> 5f; Surface.GRAVEL -> 2.5f; Surface.WOOD -> 1.3f; else -> 1f }
}

/** The bicycle's styles, one an era: each town rides the one of its year (the player's bike is of their time). */
enum class BikeStyle(val label: String) {
    /** The 1950s: a heavy cruiser on balloon tyres, swept-back bars, mudguards, a chain guard, a basket. */
    CRUISER("cruiser"),
    /** The 1970s: a ten-speed on thin tyres, drop bars, a level top tube. */
    TEN_SPEED("ten-speed"),
    /** The 1980s: a BMX on small wheels, a high-rise bar with its crossbar and pads, knobbly tyres. */
    BMX("BMX"),
    /** Today: a hybrid on 700c wheels, a flat bar, a sloping top tube, a rack. */
    HYBRID("hybrid");

    companion object {
        fun of(year: Int): BikeStyle = when {
            year < 1965 -> CRUISER
            year < 1980 -> TEN_SPEED
            year < 1995 -> BMX
            else -> HYBRID
        }
    }
}

/**
 * The bicycle's state as the ride moves it and the renderer draws it: the speed along its heading, how far it leans
 * (radians, right positive) and its bar is turned (radians, left positive), the wheels' and cranks' turns (radians), and
 * what the sound follows (pedalling or coasting: the freewheel's ticks).
 */
class BikeState {
    var style = BikeStyle.HYBRID
    /** Speed along the heading (street units a second, never backwards). */
    var speed = 0f
    /** The heading's turn rate (rad/s, clockwise from above positive: a right turn). */
    var rate = 0f
    /** The speed the last turn was limited at ([RideTuning.maxRate]). */
    var turnSpeed = 0f
    var lean = 0f
    var steer = 0f
    /** The wheels' turn (rad; a wheel of [wheelRadius] rolls its circumference a turn) and the cranks' (geared down). */
    var wheel = 0f
    var crank = 0f
    /** The rider pedals (the cranks turn), else coasts (the freewheel ticks). */
    var pedalling = false
    var sprinting = false
    /** Seconds the push has been full (for the sprint). */
    var fullFor = 0f
    /** Seconds held fast with the stick pushed, and whether the rider has lifted the front wheel round (its probe off). */
    var stuckFor = 0f
    var anchorX = 0f
    var anchorZ = 0f
    var lifted = false
    /** The side a turn round goes (1 right, -1 left), kept while the push is behind the bike. */
    var roundSide = 0f
    /** The wheels' radius (street units) and how many wheel turns a crank turn makes. */
    val wheelRadius: Float get() = (if (style == BikeStyle.BMX) 5.4f else 7f) * SCALE
    val gear: Float get() = when (style) { BikeStyle.CRUISER -> 2.2f; BikeStyle.BMX -> 2.0f; BikeStyle.TEN_SPEED -> 3.0f; BikeStyle.HYBRID -> 2.6f }

    fun stop() { stuckFor = 0f; lifted = false; speed = 0f; rate = 0f; lean = 0f; steer = 0f; pedalling = false; sprinting = false; fullFor = 0f; roundSide = 0f }

    companion object {
        /**
         * The bike is drawn this much bigger than a real one would be beside a 1.7 m person (its model is made to real
         * measure): our people are the pack's big-headed figures, and a true-size bike vanishes under them.
         */
        const val SCALE = 1.45f
        /** The bike's body on the ground: a circle this wide at its middle (it fits gates: 28 wide, 21.8 between posts). */
        const val RADIUS = 7f
        /** The front wheel's probe: a circle of [FRONT_R] this far ahead of the middle (the tyre's front at ~18). */
        const val FRONT = 17f
        const val FRONT_R = 5f
        /** How far the front probe may graze something (units) before the frame is undone. */
        const val FRONT_SLACK = .5f
        /** Getting on needs a clear spot for the bike within this of where the player stands. */
        const val MOUNT_REACH = 14f
    }
}
