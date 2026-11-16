package com.lifemax.rpg.feature.habits.domain

import java.time.LocalDate

/*
 * The time of year, with no drawing in it: from a date, how far the street's trees are in leaf, how far they have
 * turned, whether they are in blossom, how much snow lies and whether it is falling, each a smooth number over the
 * day of the year (northern hemisphere), so nothing pops from one day to the next. The scene reads these to dress
 * its trees, to let leaves, petals and snow fall, and to lay snow on the sills.
 */

/**
 * The season on one day. [leaf] is how full the deciduous canopy is, 0 bare in deep winter to 1 in
 * summer; [mature] how far the spring's thin light green has deepened to summer's; [autumn] how far
 * the leaves have turned (peaking late October, when they also fall); [blossom] the April window
 * the blossom trees flower in; [snow] how much snow lies (December to February); [snowfall] how
 * hard it is falling today, 0 on a clear day. [dayOfYear] is 1 to 366.
 */
class SeasonState(
    val leaf: Float,
    val mature: Float,
    val autumn: Float,
    val blossom: Float,
    val snow: Float,
    val snowfall: Float,
    val dayOfYear: Int,
) {
    /** Whether anything seasonal is falling or lying: the scene can skip its passes otherwise. */
    val quiet: Boolean
        get() = autumn <= 0f && blossom <= 0f && snow <= 0f
}

object Season {
    /**
     * High summer: every tree in full leaf, nothing falling. What a date that does not parse is.
     */
    val SUMMER = SeasonState(1f, 1f, 0f, 0f, 0f, 0f, 200)

    /**
     * When set, every date is this season (tests and screenshots: a snow day in March). Read on the
     * main thread when the stage is composed, so a test sets it before and clears it after.
     */
    @Volatile var override: SeasonState? = null

    /** The season on [day] ("yyyy-MM-dd"), or [SUMMER] for a day that does not parse. */
    fun at(day: String): SeasonState {
        override?.let {
            return it
        }
        val date =
            try {
                LocalDate.parse(day)
            } catch (e: Exception) {
                return SUMMER
            }
        return atDay(date.dayOfYear.toFloat(), date.year)
    }

    /**
     * The season on day [d] of the year (1 to 366, fractions allowed) of [year]: the curves below,
     * and the year's own snow days.
     */
    fun atDay(d: Float, year: Int): SeasonState {
        val leaf = s(LEAF_RISE0, LEAF_RISE1, d) * (1f - s(LEAF_FALL0, LEAF_FALL1, d))
        // (and back to 0 with the leaves, so the year closes where it opened)
        val mature = s(MATURE0, MATURE1, d) * (1f - s(TURN_END0, TURN_END1, d))
        val autumn = s(TURN0, TURN1, d) * (1f - s(TURN_END0, TURN_END1, d))
        val blossom = s(BLOOM0, BLOOM1, d) * (1f - s(BLOOM_END0, BLOOM_END1, d))
        val snow = if (d >= 180f) s(SNOW0, SNOW1, d) else 1f - s(THAW0, THAW1, d)
        val day = d.toInt().coerceIn(1, 366)
        // some days it snows, the same ones every time that year comes round
        val fall = Ambient.hash(year, day, 77)
        val snowfall =
            if (snow > .05f && fall < SNOW_DAYS) snow * (.35f + .65f * Ambient.hash(year, day, 78))
            else 0f
        return SeasonState(leaf, mature, autumn, blossom, snow, snowfall, day)
    }

    private fun s(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // the year's turning points, as days of the year
    /**
     * Leaves come from mid-March to mid-May and are gone from mid-October to the end of November.
     */
    const val LEAF_RISE0 = 75f
    const val LEAF_RISE1 = 135f
    const val LEAF_FALL0 = 288f
    const val LEAF_FALL1 = 335f

    /** Spring's thin light green deepens to summer's from the end of April to late June. */
    const val MATURE0 = 120f
    const val MATURE1 = 175f

    /**
     * The leaves turn from mid-September, are full colour in late October, and are gone by
     * December.
     */
    const val TURN0 = 255f
    const val TURN1 = 295f
    const val TURN_END0 = 305f
    const val TURN_END1 = 340f

    /** Blossom from the first week of April to late May. */
    const val BLOOM0 = 94f
    const val BLOOM1 = 114f
    const val BLOOM_END0 = 126f
    const val BLOOM_END1 = 148f

    /** Snow lies from early December and thaws from mid-February to mid-March. */
    const val SNOW0 = 335f
    const val SNOW1 = 356f
    const val THAW0 = 44f
    const val THAW1 = 70f

    /** The share of winter days it snows on. */
    const val SNOW_DAYS = .45f
}
