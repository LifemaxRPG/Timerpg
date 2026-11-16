package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Dusk done properly, and the street's small signs of life, as pure functions of the hour, the
 * scene's seconds and a seed (the same every time, nothing allocated):
 * - every light has its own switch-on moment across the dusk hour ([lightsOn]): the street lamps
 *   first (in a run along the street when the hour crosses their moment while the tab shows,
 *   [lampRun]), then the shops and the lobby signs, then the homes trickling in, a few late; off in
 *   reverse around dawn (a light's off moment mirrors its on moment about 12:30);
 * - the golden hour's warmth on the sun side of the walls ([gold]) and the blue hour's cool cast
 *   over the whole street ([blue]);
 * - a television's cool flicker in a few lit windows late in the evening ([tv]), a steam vent's
 *   plume on some roofs ([ventOn], [steam], [puff]), and pigeons that peck on the pavement by the
 *   park or on a cornice, scattering when a walker comes near ([pigeons], [pigeonPhase]).
 */
object Dusk {
    const val LAMP = 0
    const val SHOP = 1
    const val HOME = 2
    const val SIGN = 3

    /** A light takes this long to come on (hours): about three minutes. */
    const val RAMP = .05f

    /** The first and the last moment anything switches on in the evening (hours). */
    const val ON_FROM = 17.75f
    const val ON_TO = 19.25f

    /** The lamps' moment: the run along the street starts when the hour crosses it. */
    const val LAMP_AT = 17.78f

    /** In the run, each lamp comes on this many seconds after the one before, over this long. */
    const val LAMP_GAP = 2.5f
    const val LAMP_RAMP = .7f

    /** The share of homes whose lights come on late (after 19:00). */
    const val LATE = .12f

    /**
     * The moment (hours, 17:45 to 19:15) the light with [seed] of [kind] comes on in the evening.
     */
    fun moment(seed: Int, kind: Int): Float =
        when (kind) {
            LAMP -> LAMP_AT + .04f * Ambient.hash(seed, kind, 290)
            SHOP -> 17.95f + .45f * Ambient.hash(seed, kind, 290)
            SIGN -> 18f + .5f * Ambient.hash(seed, kind, 290)
            else ->
                if (Ambient.hash(seed, kind, 291) < LATE) 19f + .25f * Ambient.hash(seed, kind, 290)
                else 18.05f + .95f * Ambient.hash(seed, kind, 290)
        }

    /**
     * The moment the same light goes off in the morning: its evening moment mirrored about 12:30.
     */
    fun offMoment(seed: Int, kind: Int): Float = 25f - moment(seed, kind)

    private fun ramp(x: Float): Float {
        val t = (x / RAMP).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun wrap(hour: Float): Float = ((hour % 24f) + 24f) % 24f

    /**
     * How far on the light with [seed] of [kind] is at [hour], 0 to 1: 0 by day, ramping up over
     * [RAMP] at its own moment in the evening, 1 through the night, and down again at its mirrored
     * moment in the morning.
     */
    fun lightsOn(seed: Int, hour: Float, kind: Int = HOME): Float {
        val h = wrap(hour)
        return if (h >= 12f) ramp(h - moment(seed, kind)) else 1f - ramp(h - offMoment(seed, kind))
    }

    /**
     * Lamp number [k] along the street at [hour] and [seconds]: when the hour crossed [LAMP_AT]
     * while the tab showed ([runStart] is the seconds then, negative if it did not), the lamps come
     * on one after another a few seconds apart; otherwise by the hour alone.
     */
    fun lampRun(k: Int, seed: Int, hour: Float, seconds: Float, runStart: Float): Float {
        if (runStart < 0f) return lightsOn(seed, hour, LAMP)
        val t = ((seconds - runStart - k * LAMP_GAP) / LAMP_RAMP).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The golden hour, 0 to 1: the hour before the sky turns to dusk (17:30 to 18:30) and the hour
     * after dawn (7 to 8), strongest in the middle of each, when the low sun's wash is warm.
     */
    fun gold(hour: Float): Float {
        val tone = skyTone(hour)
        if (tone <= 1f || tone >= 2f) return 0f
        return sin(PI.toFloat() * (tone - 1f))
    }

    /**
     * The blue hour, 0 to 1: the half hour after sunset when the whole street goes cool (full from
     * about 19:20 to 19:50, fading out by 20:30 as the lamps take over) and the half hour before
     * dawn.
     */
    fun blue(hour: Float): Float {
        val h = wrap(hour)
        return if (h >= 12f) smooth(18.8f, 19.3f, h) * (1f - smooth(19.9f, 20.6f, h))
        else smooth(5.5f, 5.9f, h) * (1f - smooth(6.5f, 7f, h))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------ the television

    /** At most this share of lit windows has a television on. */
    const val TV_SHARE = .2f

    /** A television's picture holds a level for this long at most, and at least a third of it. */
    const val TV_SLOT = .6f

    /** The television is on from 21:00 until 00:30. */
    fun tvHours(hour: Float): Boolean {
        val h = wrap(hour)
        return h >= 21f || h < .5f
    }

    /** Whether the window with [seed] has a television at all. */
    fun hasTv(seed: Int): Boolean = Ambient.hash(seed, 0, 300) < TV_SHARE

    /**
     * The television's light in the window with [seed] at [hour] and [seconds]: 0 for none (no set,
     * or outside its hours), else .3 to 1, changing in steps every .2 to .6 s with the occasional
     * brighter cut.
     */
    fun tv(seed: Int, hour: Float, seconds: Float): Float {
        if (!hasTv(seed) || !tvHours(hour)) return 0f
        val t = seconds / TV_SLOT + Ambient.hash(seed, 0, 301)
        val k = floor(t).toInt()
        val u = t - k
        // the slot holds one picture, or cuts once a third to two thirds of the way through
        val sub =
            if (Ambient.hash(seed, k, 302) < .4f) 0
            else if (u < .33f + .34f * Ambient.hash(seed, k, 303)) 0 else 1
        val cut = Ambient.hash(seed, k, 310 + sub)
        return if (cut < .12f) .92f + .08f * Ambient.hash(seed, k, 312 + sub)
        else .3f + .4f * Ambient.hash(seed, k, 314 + sub)
    }

    // ------------------------------------------------------------------ the steam vent

    /** The share of roofs with a steam vent. */
    const val VENT_SHARE = .5f

    /** How many puffs a plume has in the air, and how long each takes to rise and fade. */
    const val PUFFS = 6
    const val PUFF_SECONDS = 2.6f

    /** Whether the building with [seed] has a vent beside its water tank. */
    fun ventOn(seed: Int): Boolean = Ambient.hash(seed, 0, 320) < VENT_SHARE

    /**
     * How dense the plume is at [hour] on day [dayOfYear], 0 to 1: full at night and in the
     * morning, a quarter through the day, and more in the cold months (December to mid-March).
     */
    fun steam(hour: Float, dayOfYear: Int): Float {
        val h = wrap(hour)
        val time =
            when {
                h < 9f || h >= 18f -> 1f
                h < 11f -> 1f - .75f * (h - 9f) / 2f
                h < 16f -> .25f
                else -> .25f + .75f * (h - 16f) / 2f
            }
        val cold = dayOfYear < 75 || dayOfYear > 335
        return (time * (if (cold) 1.5f else 1f)).coerceAtMost(1f)
    }

    /**
     * Where puff [j] of the plume with [seed] is on its way up at [seconds], 0 at the pipe to 1
     * gone.
     */
    fun puff(seed: Int, j: Int, seconds: Float): Float {
        val t = seconds / PUFF_SECONDS + j.toFloat() / PUFFS + Ambient.hash(seed, j, 321)
        return t - floor(t)
    }

    // ------------------------------------------------------------------ the pigeons

    /** A walker this close (street units) sends the pigeons up. */
    const val SCARE_REACH = 25f

    /** How long the take-off lasts, how long they stay away, how long the landing takes (s). */
    const val FLIGHT = 1.4f
    const val AWAY = 14f
    const val LANDING = .9f

    const val IDLE = 0
    const val FLYING = 1
    const val GONE = 2
    const val LANDING_PHASE = 3

    /** How many pigeons the building with [seed] has: three by a park, two on some cornices. */
    fun pigeons(seed: Int, park: Boolean): Int =
        if (park) 3 else if (Ambient.hash(seed, 0, 330) < .35f) 2 else 0

    /** Where the pigeons stand, 0 to 1 along their perch, and which way each faces (-1 or 1). */
    fun pigeonAt(seed: Int, i: Int): Float = .1f + .8f * Ambient.hash(seed, i, 331)

    fun pigeonFacing(seed: Int, i: Int, seconds: Float): Float {
        // they turn round now and then, every few slots
        val k = floor(seconds / 2.3f + Ambient.hash(seed, i, 332)).toInt()
        val base = if (Ambient.hash(seed, i, 333) < .5f) -1f else 1f
        return if (Ambient.hash(seed, k, 334 + i) < .3f) -base else base
    }

    /** The idle frame of pigeon [i] at [seconds]: 0 standing, 1 pecking, 2 looking round. */
    fun pigeonFrame(seed: Int, i: Int, seconds: Float): Int {
        val k = floor(seconds / .55f + Ambient.hash(seed, i, 335)).toInt()
        val r = Ambient.hash(seed, k, 336 + i)
        return if (r < .5f) 0 else if (r < .82f) 1 else 2
    }

    /** Whether a walker [distance] away (street units) scares the pigeons. */
    fun scared(distance: Float): Boolean = distance < SCARE_REACH

    /**
     * Which phase the pigeons are in, [since] seconds after they were last scared (negative: idle).
     */
    fun pigeonPhase(since: Float): Int =
        when {
            since < 0f -> IDLE
            since < FLIGHT -> FLYING
            since < FLIGHT + AWAY -> GONE
            since < FLIGHT + AWAY + LANDING -> LANDING_PHASE
            else -> IDLE
        }

    /**
     * How far through its flight pigeon [i] is, 0 on its perch to 1 out of sight, [since] seconds
     * after the scare: up and away on the take-off, back down on the landing, 1 while gone.
     */
    fun pigeonLift(since: Float, i: Int): Float =
        when (pigeonPhase(since)) {
            FLYING -> ((since - i * .08f) / (FLIGHT - .16f)).coerceIn(0f, 1f)
            GONE -> 1f
            LANDING_PHASE ->
                1f - ((since - FLIGHT - AWAY - i * .12f) / (LANDING - .24f)).coerceIn(0f, 1f)
            else -> 0f
        }

    /** The wing frame while flying: 3 and 4 by turns, fast. */
    fun wingFrame(seconds: Float, i: Int): Int = 3 + (floor(seconds * 11f + i * .5f).toInt() and 1)
}
