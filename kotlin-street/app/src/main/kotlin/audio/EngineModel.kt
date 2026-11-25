package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * What a car's engine is, as sound (docs/SOUND.md "Engines"): how many cylinders and how they fire (a straight six's even
 * beat, a cross-plane V8's uneven per-bank burble), its idle and its redline, the exhaust's pipes and muffler, how rough it
 * runs, its valve train's clatter, its intake's roar, overrun pops; or an electric motor's whine. Pure (no Android).
 */
class EngineProfile(
    val name: String,
    val cylinders: Int,
    /** Each cylinder's firing point in the four-stroke cycle (0 until 1 = 720 degrees of crank) and its exhaust bank (0 or 1). */
    val firing: FloatArray,
    val bank: IntArray,
    val idleRpm: Float,
    val redline: Float,
    /** Exhaust pipe resonance per bank (ms of round trip) and how much of it comes back (0..1). */
    val pipeMs: FloatArray,
    val pipeFeedback: Float,
    /** The muffler: its low-pass (Hz) and its body's boom (Hz, gain). */
    val mufflerHz: Float,
    val boomHz: Float,
    val boom: Float,
    /** How long a combustion pulse lasts (ms, at idle; shorter as it revs). */
    val pulseMs: Float,
    /** Cycle-to-cycle unevenness (0 smooth .. 1 rough), a lumpy cam's lope at idle, the valve train's clatter. */
    val rough: Float,
    val lope: Float,
    val clatter: Float,
    /** The intake's roar under throttle, overrun pops (chance a cycle off throttle at revs), the starter's crank (s). */
    val intake: Float,
    val pops: Float,
    val crankS: Float,
    val gain: Float,
    val electric: Boolean = false,
) {
    /** Firing pulses a second at [rpm] (a four-stroke fires each cylinder once in two turns). */
    fun fireHz(rpm: Float) = rpm / 120f * cylinders

    companion object {
        private fun even(n: Int) = FloatArray(n) { it / n.toFloat() }

        /** A cross-plane V8 firing 1-8-4-3-6-5-7-2, cylinders 1 3 5 7 on the left bank: each bank's pulses come unevenly. */
        private val CROSS_PLANE_V8_BANKS = intArrayOf(0, 1, 1, 0, 1, 0, 0, 1)

        /** A 1920s side-valve straight six: slow, chuffing, loose, its tappets ticking. */
        val VINTAGE_SIX = EngineProfile("vintage straight six", 6, even(6), IntArray(6), 480f, 3000f, floatArrayOf(7.5f, 7.5f), .45f,
            900f, 95f, .35f, 2.6f, .55f, .05f, .5f, .25f, .02f, 1.4f, 1.05f)
        /** A late-1930s flathead V8: soft, burbling, mellow. */
        val FLATHEAD_V8 = EngineProfile("flathead V8", 8, even(8), CROSS_PLANE_V8_BANKS, 520f, 3600f, floatArrayOf(6.2f, 7.4f), .42f,
            700f, 80f, .45f, 2.2f, .35f, .12f, .25f, .3f, .05f, 1.15f, 1.05f)
        /** A 1950s overhead-valve V8 on a single exhaust: smooth, a deep hum, some burble. */
        val OHV_V8 = EngineProfile("OHV V8 sedan", 8, even(8), CROSS_PLANE_V8_BANKS, 580f, 4400f, floatArrayOf(5.6f, 6.4f), .4f,
            850f, 75f, .4f, 1.8f, .25f, .1f, .12f, .4f, .08f, 1f, 1f)
        /** A 1970 big-block on dual exhausts with a lumpy cam: loud, lopey at idle, crackling on the overrun. */
        val MUSCLE_V8 = EngineProfile("big-block V8", 8, even(8), CROSS_PLANE_V8_BANKS, 720f, 5600f, floatArrayOf(4.4f, 4.9f), .5f,
            1400f, 70f, .55f, 1.4f, .35f, .55f, .1f, .65f, .35f, .9f, 1.15f)
        /** A 1972 pickup's V8: heavier, lower, a truck's single pipe. */
        val TRUCK_V8 = EngineProfile("pickup V8", 8, even(8), CROSS_PLANE_V8_BANKS, 600f, 4400f, floatArrayOf(6.8f, 7.6f), .44f,
            750f, 65f, .5f, 2f, .3f, .2f, .15f, .45f, .1f, 1.05f, 1.05f)
        /** A 1968 twin-carb sports four: rasping, eager, revving high. */
        val SPORTS_FOUR = EngineProfile("twin-carb sports four", 4, even(4), IntArray(4), 820f, 6800f, floatArrayOf(3.1f, 3.1f), .55f,
            2200f, 140f, .3f, 1.1f, .25f, .05f, .15f, .8f, .2f, .8f, .9f)
        /** A 1988 twin-cam sixteen-valve four: smooth, high, much intake. */
        val TWINCAM_FOUR = EngineProfile("twin-cam four", 4, even(4), IntArray(4), 850f, 7000f, floatArrayOf(2.7f, 2.7f), .45f,
            2600f, 160f, .25f, .9f, .12f, .02f, .06f, 1f, .12f, .65f, .85f)
        /** A modern four on a catalytic converter and a big muffler: quiet, smooth, mostly intake and tyres. */
        val MODERN_FOUR = EngineProfile("modern four", 4, even(4), IntArray(4), 750f, 6500f, floatArrayOf(3.4f, 3.4f), .3f,
            1100f, 120f, .2f, 1f, .06f, 0f, .03f, .6f, 0f, .55f, .55f)
        /** An electric car: a motor's whine and the inverter's hum, no firing at all. */
        val ELECTRIC = EngineProfile("electric", 0, FloatArray(0), IntArray(0), 0f, 12000f, floatArrayOf(1f, 1f), 0f,
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, .5f, electric = true)

        val ALL = listOf(VINTAGE_SIX, FLATHEAD_V8, OHV_V8, MUSCLE_V8, TRUCK_V8, SPORTS_FOUR, TWINCAM_FOUR, MODERN_FOUR, ELECTRIC)

        /** The engine of the car [key] (assets/vehicles/KEY) whose model was first built in [from]; [redline] the drivetrain's. */
        fun forCar(key: String, from: Int): EngineProfile = when {
            key.startsWith("alderwick") -> VINTAGE_SIX
            key.startsWith("fenmere") -> FLATHEAD_V8
            key.startsWith("northcairn") -> OHV_V8
            key.startsWith("rowanvale") -> MUSCLE_V8
            key.startsWith("tallmere") -> TRUCK_V8
            key.startsWith("torrel") -> if (from < 1980) SPORTS_FOUR else TWINCAM_FOUR
            key.contains("electric") || key.contains("-ev") -> ELECTRIC
            else -> forYear(from)
        }

        /** An engine typical of cars first built in [year] (the far traffic, a car with no profile of its own). */
        fun forYear(year: Int, pick: Float = .5f): EngineProfile = when {
            year < 1934 -> VINTAGE_SIX
            year < 1950 -> FLATHEAD_V8
            year < 1966 -> if (pick < .7f) OHV_V8 else SPORTS_FOUR
            year < 1980 -> if (pick < .35f) MUSCLE_V8 else if (pick < .7f) TRUCK_V8 else if (pick < .85f) OHV_V8 else SPORTS_FOUR
            year < 2012 -> if (pick < .6f) MODERN_FOUR else if (pick < .8f) TWINCAM_FOUR else TRUCK_V8
            else -> if (pick < .35f) ELECTRIC else if (pick < .85f) MODERN_FOUR else TRUCK_V8
        }

        /**
         * The engines of the cars about in [year] (the far traffic): each kind with its share. Before cars were common
         * there are few of any kind (the [trafficShare]); old models linger for years after they were built.
         */
        fun fleet(year: Int): List<Pair<EngineProfile, Float>> {
            val out = ArrayList<Pair<EngineProfile, Float>>()
            fun add(p: EngineProfile, from: Int, to: Int, w: Float) {
                if (year < from) return
                val age = year - to
                val f = if (age <= 0) 1f else exp(-age / 9f)
                if (f > .03f) out += p to w * f
            }
            add(VINTAGE_SIX, 1910, 1934, 1f)
            add(FLATHEAD_V8, 1932, 1953, 1f)
            add(OHV_V8, 1949, 1975, 1.2f)
            add(SPORTS_FOUR, 1955, 1980, .35f)
            add(MUSCLE_V8, 1964, 1974, .5f)
            add(TRUCK_V8, 1955, 2010, .6f)
            add(TWINCAM_FOUR, 1980, 2005, .6f)
            add(MODERN_FOUR, 1985, 2100, 1.4f)
            add(ELECTRIC, 2012, 2100, if (year < 2020) .1f else .5f)
            val sum = out.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)
            return out.map { it.first to it.second / sum }
        }

        /** How busy the roads are in [year] relative to now (1): the 1920s had a car for a few households, horses still about. */
        fun trafficShare(year: Int): Float = when {
            year < 1905 -> 0f
            year < 1950 -> .1f + .4f * (year - 1905) / 45f
            year < 1975 -> .5f + .4f * (year - 1950) / 25f
            else -> (.9f + .1f * (year - 1975) / 25f).coerceAtMost(1f)
        }

        /** Horses on the streets in [year] (0..1): many before the motor car, gone by the 1940s. */
        fun horseShare(year: Int): Float = when {
            year < 1910 -> 1f
            year < 1945 -> 1f - (year - 1910) / 35f
            else -> 0f
        }
    }
}

/**
 * A gearbox as sound needs it, for a car the physics does not run (the far traffic) or before its engine reports: the
 * engine's speed from the road speed through the gear and the final drive, and an automatic's up and down shifts.
 */
class GearModel(val gears: FloatArray, val finalDrive: Float, val wheelRadius: Float, val idleRpm: Float, val redline: Float) {
    var gear = 1; private set
    /** Seconds since the last shift (a shift's dip in the sound). */
    var sinceShift = 10f; private set

    /** The engine's speed in [g] (1-based) at [speed] m/s, before the idle floor. */
    fun rpmAt(speed: Float, g: Int = gear): Float = abs(speed) / wheelRadius * gears[(g - 1).coerceIn(0, gears.size - 1)] * finalDrive * 60f / (2f * PI.toFloat())

    /** Shifts as an automatic would at [speed] ([throttle] holds a gear longer) and returns the engine's speed. */
    fun update(speed: Float, throttle: Float, dt: Float): Float {
        sinceShift += dt
        val up = redline * (.55f + .3f * throttle.coerceIn(0f, 1f))
        val down = redline * .3f
        if (sinceShift > .6f) {
            if (gear < gears.size && rpmAt(speed) > up) { gear++; sinceShift = 0f }
            else if (gear > 1 && rpmAt(speed) < down) { gear--; sinceShift = 0f }
        }
        return rpmAt(speed).coerceIn(idleRpm, redline)
    }

    companion object {
        /** The final drive that puts [redline] in top gear at [topKmh] (as the game's Drivetrain does). */
        fun finalDrive(gears: FloatArray, redline: Float, topKmh: Float, wheelRadius: Float): Float =
            (redline * 2f * PI.toFloat() / 60f) / (gears.last() * (topKmh / 3.6f / wheelRadius))
    }
}

/**
 * The engine's state as heard, eased from what the car does each frame: off, cranking on the starter, running (its speed,
 * its load, a shift's dip), or running down after it is switched off. The physics' engine speed when there is one.
 */
class EngineState(val profile: EngineProfile) {
    enum class Phase { OFF, CRANK, RUN, STOPPING }
    var phase = Phase.OFF; private set
    var rpm = 0f; private set
    /** How hard it works (0 coasting .. 1 full throttle), eased. */
    var load = 0f; private set
    /** Off the throttle at revs: the overrun (pops, a lighter tone). */
    var overrun = false; private set
    /** Seconds since the gear changed. */
    var sinceShift = 10f; private set
    /** Seconds in the phase. */
    var phaseT = 0f; private set
    /** Failed starts in a row (a breakdown or an empty tank). */
    var failed = 0; private set
    private var lastGear = 0
    private var catchFlare = 0f

    /**
     * One frame: [wantRun] the driver wants it running (in the driver's seat), [canRun] it can (fuel, not broken down);
     * [physRpm] the physics' engine speed (<= 0: none); [gear] its gear; [throttle] 0..1; [speed] m/s.
     */
    fun update(dt: Float, wantRun: Boolean, canRun: Boolean, physRpm: Float, gear: Int, throttle: Float, speed: Float) {
        phaseT += dt; sinceShift += dt
        val idle = if (profile.electric) 0f else profile.idleRpm
        when (phase) {
            Phase.OFF -> {
                rpm += (0f - rpm) * k(dt, .25f)
                // a dead engine is tried again on the gas, a second or two between tries
                if (wantRun && (canRun || (throttle > .3f && phaseT > 2.5f && failed < 6))) go(Phase.CRANK)
            }
            Phase.CRANK -> {
                if (profile.electric) { go(Phase.RUN); return }
                // the starter turns it over at a couple of hundred rpm, each compression a lurch
                rpm = 170f + 50f * kotlin.math.sin(phaseT * 2f * PI.toFloat() * (profile.cylinders / 2f) * 1.6f)
                if (!wantRun) go(Phase.STOPPING)
                else if (canRun && phaseT > profile.crankS) { go(Phase.RUN); catchFlare = 1f; failed = 0 }
                else if (!canRun && phaseT > profile.crankS * 1.6f) { failed++; go(Phase.OFF) }
            }
            Phase.RUN -> {
                if (!wantRun || !canRun) { go(Phase.STOPPING); return }
                val floor = idle * (1f + .7f * catchFlare)
                catchFlare = (catchFlare - dt * 1.2f).coerceAtLeast(0f)
                val target = if (profile.electric) abs(speed) * 360f else maxOf(floor, physRpm)
                val tau = if (target > rpm) .07f else .14f
                rpm += (target - rpm) * k(dt, tau)
                if (!profile.electric) rpm = rpm.coerceIn(idle * .8f, profile.redline * 1.02f)
                if (gear != lastGear) { if (lastGear != 0 && gear != 0) sinceShift = 0f; lastGear = gear }
            }
            Phase.STOPPING -> {
                rpm += (0f - rpm) * k(dt, .3f)
                if (rpm < 40f || phaseT > 2f) { rpm = 0f; go(Phase.OFF) }
                if (wantRun && canRun && phaseT > .5f) go(Phase.CRANK)
            }
        }
        val l = if (phase == Phase.RUN) throttle.coerceIn(0f, 1f) else 0f
        load += (l - load) * k(dt, if (l > load) .06f else .12f)
        overrun = phase == Phase.RUN && throttle < .05f && rpm > idle * 1.6f
    }

    /** Straight to running at [rpm] (a car already driving when it comes within earshot: the far traffic). */
    fun startRunning(rpm: Float) { phase = Phase.RUN; phaseT = 10f; this.rpm = rpm; catchFlare = 0f }

    private fun go(p: Phase) { phase = p; phaseT = 0f }
    private fun k(dt: Float, tau: Float) = 1f - exp(-dt / tau)
}
