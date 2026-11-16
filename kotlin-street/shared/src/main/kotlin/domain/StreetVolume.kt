package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max

/**
 * The pavement's furniture (lamps, trees, benches) as things with depth, seen by the same camera as
 * the buildings ([Perspective]): it stands on the pavement in front of the facades, so it slides a
 * touch further than they do as the street scrolls ([near]); a box (a lamp's plinth, a bench's seat
 * and ends) shows its top and the side away from the middle of the view ([skew], [RISE]); a round
 * thing (a lamp post, a trunk) turns its light and shade with the view ([cylinder]). Pure, so it is
 * tested.
 */
object StreetVolume {
    /**
     * How much nearer than the facades the furniture stands, as a share of a building's side face:
     * a building whose back edge is drawn [Perspective.shift] to one side has its furniture drawn
     * this share of that to the other (away from the middle of the view), so it moves a little
     * faster than the fronts as the street scrolls, as a nearer thing does.
     */
    const val NEAR = .25f

    /** Where the furniture at the place of a building side [shift] is drawn, from its own place. */
    fun near(shift: Float): Float = -shift * NEAR

    /**
     * How far sideways a unit of depth goes on screen for the whole side shown ([Perspective.side]
     * 1), and how far up: the camera looks a little down on the furniture, so tops show.
     */
    const val SKEW = .42f
    const val RISE = .36f

    /** The sideways run of a unit of depth for a thing whose side is [side] (-1 to 1). */
    fun skew(side: Float): Float =
        SKEW * Perspective.ease(abs(side).coerceIn(0f, 1f)) * (if (side < 0f) -1f else 1f)

    /** How far round the view turns a round thing at the whole side, in radians (about 34°). */
    const val TURN = .6f

    /** The angle of the key light round a post from the front, in radians, for a light from +1. */
    const val LIGHT = 1.05f

    /**
     * The light on a round post at [u] across it (-1 its left edge, 1 its right) seen from [side]
     * (as [Perspective.side]: a post left of the middle shows its right flank), lit by a key light
     * from [from] (-1 the left, 1 the right) of strength [key]: 0 (unlit) to 1. A lit flank, the
     * core shadow where the surface turns from the light, and a little light thrown back from the
     * other side along the far edge.
     */
    fun cylinder(u: Float, side: Float, from: Float, key: Float): Float =
        face(asin(u.coerceIn(-1f, 1f)) + side.coerceIn(-1f, 1f) * TURN, from, key)

    /**
     * The light on an upright face turned [theta] radians from facing the street (positive to the
     * right: [FRONT] faces the camera, [RIGHT] is a right-hand side), as [cylinder].
     */
    fun face(theta: Float, from: Float, key: Float): Float {
        val l = from * LIGHT
        val direct = max(0f, cos(theta - l))
        // the light thrown back from the street on the far side: a narrow rim along that edge,
        // past the core shadow
        val back = max(0f, cos(theta + l * BOUNCE))
        val bounce = back * back
        val sky = .5f + .5f * cos(theta)
        return (.08f + .14f * sky + key * (.78f * direct + .2f * bounce)).coerceIn(0f, 1f)
    }

    /** The light on a top face: the open sky's, and the key light's from above. */
    fun top(key: Float): Float = (.42f + .42f * key).coerceIn(0f, 1f)

    /** Where the thrown-back light comes from, as a share of [LIGHT] round the other way. */
    private const val BOUNCE = 1.6f

    const val FRONT = 0f
    const val RIGHT = 1.5708f
}
