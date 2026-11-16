package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Which side face of a building shows, as a camera standing in the street would see it: a building
 * left of the middle of the view shows its right side, one right of the middle its left side, the
 * one in the middle neither, and the side comes round smoothly as the street scrolls (no pop as a
 * building crosses the middle). The fronts never move: only the side face's width and which edge it
 * hangs from, and the skew of the roof face (its depth [BuildingSpec.DY] stays: the camera looks a
 * little down on the roofs).
 */
object Perspective {
    /**
     * How fast the side comes round with the building's place across the view, on the street in
     * front: 1.2 shows the whole side a little before the view's edge.
     */
    const val K_FOCUS = 1.2f

    /** Zoomed out, where many small buildings make a row: gentler, so the row reads tidy. */
    const val K_STREET = .5f

    /**
     * The rate between [K_FOCUS] and [K_STREET] at [zoom] (0 the building in front, 1 the street).
     */
    fun k(zoom: Float): Float = lerp(K_FOCUS, K_STREET, zoom.coerceIn(0f, 1f))

    /**
     * The side a building whose front's middle is at [u] across the view (0 its left edge, 1 its
     * right) shows: -1 the whole LEFT face, 1 the whole RIGHT face, 0 none, between for a part of
     * one.
     */
    fun side(u: Float, k: Float = K_FOCUS): Float = ((.5f - u) * 2f * k).coerceIn(-1f, 1f)

    /**
     * The same for a building at [x] along the street, in a view [span] street units wide whose
     * middle stands at [middle].
     */
    fun sideAt(x: Float, middle: Float, span: Float, k: Float = K_FOCUS): Float =
        if (span <= 0f) 0f else side(.5f + (x - middle) / span, k)

    /** How wide the shown side face is drawn, 0 to [BuildingSpec.DX]. */
    fun width(side: Float): Float = BuildingSpec.DX * ease(abs(side).coerceIn(0f, 1f))

    /**
     * How far the back edge is drawn to the side of the front, signed: positive (to the right) for
     * the right face, negative for the left one. The one number the drawing needs.
     */
    fun shift(side: Float): Float = if (side < 0f) -width(side) else width(side)

    /**
     * The side's width against how far round it has come: quick off the middle (a building a little
     * to one side already shows an edge) and easing in towards the full width.
     */
    fun ease(s: Float): Float = s * (1.3f - .3f * s)

    /** The left edge of a building as drawn (its front plus the shown side), from its middle. */
    fun left(shift: Float): Float = -BuildingSpec.W / 2f + min(0f, shift)

    /** And its right edge. */
    fun right(shift: Float): Float = BuildingSpec.W / 2f + max(0f, shift)
}
