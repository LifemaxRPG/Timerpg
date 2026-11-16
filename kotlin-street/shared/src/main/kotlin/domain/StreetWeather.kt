package com.lifemax.rpg.feature.habits.domain

import kotlin.math.max

/*
 * The street's weather, with no drawing in it. Each day has its own, seeded from the date as the cat's
 * is: most days are clear, some overcast under one grey ceiling, some rainy (a spell of rain that starts
 * and ends within the day, leaving the pavement wet for a couple of hours after), and some begin in a
 * morning mist that clears by mid-morning. Everything the scene reads is a smooth factor of the hour
 * ([WeatherState]), so nothing pops as the clock moves.
 */

/** What kind of day it is. */
enum class WeatherKind {
    CLEAR,
    OVERCAST,
    RAIN,
    MIST,
}

/**
 * One day's weather: its [kind]; for a rainy day the hours the rain [rainFrom] and [rainTo]
 * (fractional, within the day) and how [heavy] it is (0.45 a drizzle to 1 a downpour); for a misty
 * day when the mist has [mistGone] (fractional hour). [grey] is how heavy an overcast day's ceiling
 * is (0.75 to 1).
 */
class DayWeather(
    val kind: WeatherKind,
    val rainFrom: Float,
    val rainTo: Float,
    val heavy: Float,
    val mistGone: Float,
    val grey: Float,
)

/**
 * The weather's smooth factors at one hour, each 0 to 1: [cloudiness] (0 a clear sky, 1 one grey
 * ceiling), [rain] (how hard it falls), [wet] (how wet the pavement is: rising with the rain,
 * drying over about two hours after it), [mist] (the morning fog).
 */
class WeatherState(val cloudiness: Float, val rain: Float, val wet: Float, val mist: Float) {
    companion object {
        val CLEAR = WeatherState(0f, 0f, 0f, 0f)
    }
}

/**
 * A weather forced on the stage by a test or a screenshot: set, the stage draws this instead of the
 * day's own. Null (the normal case) leaves the date in charge.
 */
object WeatherOverride {
    @Volatile var forced: WeatherState? = null
}

object StreetWeather {
    /** Shares of days, in a hundred. */
    const val CLEAR_IN_100 = 58
    const val OVERCAST_IN_100 = 18
    const val RAIN_IN_100 = 14
    // the rest (10) are misty mornings

    /** Rain comes on and goes off over this many hours (20 minutes). */
    const val RAIN_RAMP = 1f / 3f

    /** The pavement wets over this many hours of rain, and dries over this many after it. */
    const val WET_RISE = .25f
    const val DRY_HOURS = 2f

    /**
     * A morning mist is at its fullest from this hour, and clears over two hours before [mistGone].
     */
    const val MIST_FROM = 3.5f
    const val MIST_CLEARING = 2f

    /** The weather of [day] (a date, "2026-03-02"): the same every time for the same date. */
    fun ofDay(day: String): DayWeather {
        val seed = mix(day.hashCode())
        val roll = Math.floorMod(seed, 100)
        val kind =
            when {
                roll < CLEAR_IN_100 -> WeatherKind.CLEAR
                roll < CLEAR_IN_100 + OVERCAST_IN_100 -> WeatherKind.OVERCAST
                roll < CLEAR_IN_100 + OVERCAST_IN_100 + RAIN_IN_100 -> WeatherKind.RAIN
                else -> WeatherKind.MIST
            }
        // rain starts between 6 and 19 and lasts one and a half to five hours, never past midnight
        val from = 6f + 13f * Ambient.hash(seed, 1, 71)
        val length = 1.5f + 3.5f * Ambient.hash(seed, 2, 71)
        val to = (from + length).coerceAtMost(23.6f)
        val heavy = .45f + .55f * Ambient.hash(seed, 3, 71)
        val mistGone = 8.5f + 1.5f * Ambient.hash(seed, 4, 71)
        val grey = .75f + .25f * Ambient.hash(seed, 5, 71)
        return DayWeather(kind, from, to, heavy, mistGone, grey)
    }

    /** The weather of [day] at [hour] (0 to 24, fractional). */
    fun at(day: DayWeather, hour: Float): WeatherState =
        when (day.kind) {
            WeatherKind.CLEAR -> WeatherState.CLEAR
            WeatherKind.OVERCAST -> WeatherState(day.grey, 0f, 0f, 0f)
            WeatherKind.RAIN -> {
                // the rain itself, ramping in and out
                val on = smooth((hour - day.rainFrom) / RAIN_RAMP)
                val off = 1f - smooth((hour - day.rainTo) / RAIN_RAMP)
                val rain = on * off * day.heavy
                // the pavement: wet soon after the rain starts, dry two hours after it stops
                val wet =
                    smooth((hour - day.rainFrom) / WET_RISE) *
                        (1f - smooth((hour - day.rainTo) / DRY_HOURS))
                // the sky thickens two hours before the rain and thins for two hours after it
                val cloud =
                    .25f +
                        .75f *
                            smooth((hour - day.rainFrom + 2f) / 2f) *
                            (1f - smooth((hour - day.rainTo) / 2f))
                WeatherState(cloud, rain, wet, 0f)
            }
            WeatherKind.MIST -> {
                val mist =
                    smooth((hour - MIST_FROM + .5f) / 1f) *
                        (1f - smooth((hour - day.mistGone + MIST_CLEARING) / MIST_CLEARING))
                WeatherState(.3f * mist, 0f, 0f, mist)
            }
        }

    /** The weather of the date [day] at [hour]. */
    fun at(day: String, hour: Float): WeatherState = at(ofDay(day), hour)

    /**
     * The light under this weather: [light] with its strength cut by the cloud (a full overcast
     * leaves a fifth of it) and softened by the mist (half of it), and its shade flattened by the
     * cloud. The sun's and the moon's own arithmetic is untouched.
     */
    fun light(light: KeyLight, w: WeatherState): KeyLight =
        KeyLight(
            light.across,
            light.down,
            light.out,
            light.strength * (1f - .8f * w.cloudiness) * (1f - .5f * w.mist),
            light.cool,
            max(light.flat, w.cloudiness),
        )

    private fun smooth(t: Float): Float {
        val x = if (t < 0f) 0f else if (t > 1f) 1f else t
        return x * x * (3f - 2f * x)
    }

    /** A well-mixed int from a string's hash, so neighbouring dates roll differently. */
    private fun mix(h: Int): Int {
        var n = h * -1640531527
        n = n xor (n ushr 15)
        n *= 374761393
        n = n xor (n ushr 13)
        return n
    }
}
