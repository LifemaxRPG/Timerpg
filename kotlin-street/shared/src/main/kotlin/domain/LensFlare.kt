package com.lifemax.rpg.feature.habits.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The camera's lens flare, with no drawing in it: where the ghosts stand for a sun at one place on the screen, and
 * how strong the flare is for the hour and for a sun behind a building. A flare's ghosts lie on the line from the
 * light through the middle of the picture, on the far side of it, so they travel across the picture as the sun's
 * place on the screen changes and as the street pans under it.
 */
object LensFlare {
    const val GHOSTS = 6

    /** The kinds of ghost: a soft disc, a thin ring, a soft polygon (the iris). */
    const val DISC = 0
    const val RING = 1
    const val POLY = 2

    /**
     * How far past the picture's middle each ghost stands, as a share of the sun's distance from
     * it.
     */
    val FRACTION = floatArrayOf(.22f, .46f, .68f, .92f, 1.22f, 1.55f)

    /** Each ghost's size against the base ghost (dp), its alpha at full strength, and its kind. */
    val SIZE = floatArrayOf(.55f, 1.15f, .42f, 1.7f, .8f, 2.3f)
    val ALPHA = floatArrayOf(.11f, .07f, .13f, .05f, .09f, .035f)
    val KIND = intArrayOf(DISC, RING, POLY, DISC, POLY, RING)

    /** How far the ghosts wander as the street pans, in dp, and how fast (per street unit). */
    const val WANDER = 7f
    const val WANDER_RATE = 1f / 110f

    /**
     * Where ghost [k] stands for a sun at ([sunX], [sunY]) and a picture middle at ([cx], [cy]),
     * the street scrolled to [scroll] (street units): on the far side of the middle along the line
     * from the sun, plus a small wander that follows the pan so the ghosts slide as the street
     * does.
     */
    fun ghostX(sunX: Float, cx: Float, k: Int, scroll: Float, d: Float = 1f): Float =
        cx + (cx - sunX) * FRACTION[k] + wander(k, scroll) * d

    fun ghostY(sunY: Float, cy: Float, k: Int, scroll: Float, d: Float = 1f): Float =
        cy + (cy - sunY) * FRACTION[k] + wander(k + 3, scroll * 1.3f) * d * .6f

    fun wander(k: Int, scroll: Float): Float = WANDER * sin(scroll * WANDER_RATE + k * 1.9f)

    /**
     * How strong the flare is for a sky this [dark] (0 day, .55 dusk, 1 night) and a sun this
     * [clear] of the buildings (0 behind one, 1 in the open): full by day, a third at dusk, none at
     * night.
     */
    fun strength(dark: Float, clear: Float): Float =
        (1f - smooth(0f, .55f, dark) * .65f - smooth(.55f, .8f, dark) * .35f) *
            clear.coerceIn(0f, 1f)

    /**
     * How clear a sun at ([x], [y]) is of the box [left], [top], [right], [bottom]: 0 inside it, 1
     * further than [feather] outside, between near its edge, so the flare fades as the sun goes
     * behind a building.
     */
    fun clear(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        feather: Float,
    ): Float {
        val dx = max(left - x, max(0f, x - right))
        val dy = max(top - y, max(0f, y - bottom))
        val outside = max(dx, dy)
        if (outside <= 0f) return 0f
        return smooth(0f, feather, min(outside, feather))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
