package com.lifemax.rpg.feature.habits.domain

import java.time.LocalDate
import kotlin.math.cos

/**
 * The moon's phase on a day, from a mean synodic month: good to about half a day, which is all a
 * picture of the moon needs. Pure arithmetic, so the scene's moon is a function of its date.
 */
object MoonPhase {
    /** The mean length of a lunation, in days. */
    const val SYNODIC_MONTH = 29.530588853

    /** A known new moon, 2000-01-06 18:14 UTC, as a day count from 1970-01-01. */
    private const val EPOCH_NEW_MOON = 10962.76

    /** How many pictures of the terminator the scene tells apart (one per 1/32 of the cycle). */
    const val STEPS = 32

    /**
     * Where in its cycle the moon stands at midday of [day] ("yyyy-MM-dd"): 0 new, .25 first
     * quarter (lit on the right, as seen from the north), .5 full, .75 last quarter. A day that
     * does not parse is taken as full.
     */
    fun fraction(day: String): Float {
        val epochDay =
            try {
                LocalDate.parse(day).toEpochDay()
            } catch (e: Exception) {
                return .5f
            }
        val age = (epochDay + .5 - EPOCH_NEW_MOON) % SYNODIC_MONTH
        return ((if (age < 0) age + SYNODIC_MONTH else age) / SYNODIC_MONTH).toFloat()
    }

    /** The share of the disc that is lit at [fraction] (0 new, 1 full). */
    fun illumination(fraction: Float): Float =
        (.5 * (1.0 - cos(fraction * 2.0 * Math.PI))).toFloat()

    /** The nearest of the [STEPS] pictures to [fraction]. */
    fun step(fraction: Float): Int = ((fraction * STEPS + .5f).toInt() % STEPS + STEPS) % STEPS
}
