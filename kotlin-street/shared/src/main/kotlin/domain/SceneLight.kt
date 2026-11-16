package com.lifemax.rpg.feature.habits.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The street's lighting, with no drawing in it. One key light at a time: the sun by day, the moon by night,
 * standing where the sky draws it ([orbAt]), behind the buildings. Its light travels sideways (away from the
 * side it stands on), downward, and out of the facades towards the viewer, so everything that stands up
 * throws a shadow forward onto the pavement that leans away from the light: long and leaning right in the
 * morning, short at noon, long and leaning left in the evening, gone as the sun sets, then faint and cool
 * under the moon. The same projection serves every caster (a lamp post, a swaying tree, a whole building),
 * so their shadows agree with one another and with the hour.
 */

/**
 * The key light at one moment. Light travels along ([across], -[down], [out]), a unit vector:
 * [across] sideways (positive to the right: the light stands on the left), [down] downward, [out]
 * out of the facades towards the viewer. [strength] is how hard it is, 1 full sun to 0 none (the
 * hand-over at the horizon). [cool] is 0 for the sun's shade and 1 for the moon's. [flat] is how
 * much of the sky is one cloud (0 crisp shadows, 1 overcast: the shade grey and shallow, the clouds
 * throwing none of their own): [StreetWeather.light].
 */
class KeyLight(
    val across: Float,
    val down: Float,
    val out: Float,
    val strength: Float,
    val cool: Float,
    val flat: Float = 0f,
) {
    /** How far a shadow leans sideways per unit of the caster's height (positive: to the right). */
    val lean: Float
        get() = across / down

    /**
     * How far down the pavement a shadow reaches per unit of the caster's height, on screen: the
     * ground is seen at a slant ([SceneLight.FLAT]), so a shadow is shorter on screen than it is.
     */
    val reach: Float
        get() = SceneLight.FLAT * out / down
}

object SceneLight {
    /** The ground's foreshortening: a unit of depth along the pavement is this much on screen. */
    const val FLAT = .5f

    /** The sun's (or moon's) elevation at the ends of its arc and at the top of it, in degrees. */
    const val LOW = 10f
    const val HIGH = 52f

    /** How hard the moon's light is next to the sun's. */
    const val MOON = .3f

    /** A low sun at dusk is this much of a noon sun; it brightens with the sky's tone. */
    const val DUSK = .45f

    /** The key light at [hour]. */
    fun key(hour: Float): KeyLight {
        val night = skyPhase(hour) == SkyPhase.NIGHT
        val (acrossSky, up) = orbAt(hour)
        // on the left of the sky the light travels right, and the more it is to one side the more
        // sideways it travels (never quite flat along the facades)
        val side = ((acrossSky - .5f) * 2.2f).coerceIn(-.92f, .92f)
        val elevation = Math.toRadians((LOW + (HIGH - LOW) * up).toDouble())
        val level = cos(elevation).toFloat()
        val across = -side * level
        val out = sqrt(max(0f, 1f - side * side)) * level
        val down = sin(elevation).toFloat()
        // it comes on as it rises, so there is no shadow to pop when the sun hands over to the moon
        val rise = smooth((up / .3f).coerceIn(0f, 1f))
        val strength =
            if (night) MOON * rise
            else rise * (DUSK + (1f - DUSK) * (skyTone(hour) - 1f).coerceIn(0f, 1f))
        return KeyLight(across, down, out, strength, if (night) 1f else 0f)
    }

    /**
     * The light is near, not infinitely far: it stands where the sky draws it, so shadows fan out
     * from it and swing as the street scrolls under it. A caster a whole view's width to the side
     * of the light leans this much further.
     */
    const val SPLAY = .9f

    /**
     * How far a shadow leans per unit of height for a caster standing [offset] view-widths to the
     * right of the light (negative: to the left).
     */
    fun lean(light: KeyLight, offset: Float): Float =
        light.lean + SPLAY * offset.coerceIn(-1.5f, 1.5f)

    /**
     * Which side of a facade the light finds, for a building [offset] view-widths to the right of
     * the light: -1 lit from its left, 1 from its right, 0 face on, between for a little to one
     * side.
     */
    fun sunSide(offset: Float): Float = (-offset * 1.6f).coerceIn(-1f, 1f)

    /**
     * How dark a cloud's shadow is at [light]: only the sun's clouds shade the street, and under an
     * overcast sky (one cloud) none does.
     */
    fun cloudShade(light: KeyLight): Float =
        CLOUD_SHADE * light.strength * (1f - light.cool) * (1f - light.flat)

    /** How far a cloud's shadow stands from the cloud, sideways, in dp: it leans with the light. */
    fun cloudDrop(light: KeyLight): Float = light.lean * CLOUD_HEIGHT

    /** The deepest a cloud's shadow is. */
    const val CLOUD_SHADE = .2f

    /** How high the clouds hang over the street, for where their shadows fall, in dp. */
    const val CLOUD_HEIGHT = 90f

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
}

/*
 * The near lights of the night street, with no drawing in them: a street lamp's lens as a point light over the
 * pavement (a walker's shadow swings from behind them to in front as they pass under it, and stretches as they
 * walk away), and a passing car's headlights as a point light low over the road (the facades are washed as it
 * passes, and the lamp posts and trees throw a second shadow up the walls that swings with it).
 */
object NearLight {
    /** A street lamp's lens: how far right of the post it hangs and how high over the pavement. */
    const val LAMP_LENS = 11.5f
    const val LAMP_HEIGHT = 89.5f

    /** How far from the lens a lamp's light still reaches a walker, in street units. */
    const val LAMP_REACH = 70f

    /**
     * The shear approximation of a point light's projection is taken at a walker's middle: this far
     * up them.
     */
    const val WALKER_MID = 13f

    /**
     * How far a walker's shadow leans sideways per unit of their height, under a lamp whose lens is
     * at [lensX], for a walker standing at [x]: away from the lamp, nothing directly under it.
     */
    fun lampLean(lensX: Float, x: Float, height: Float = LAMP_HEIGHT): Float =
        (x - lensX) / (height - WALKER_MID)

    /**
     * How far down the pavement (on screen) a walker's shadow reaches per unit of height, for a
     * walker standing [depth] units out from the lamp's line: the ground's slant is eased
     * ([STRETCH]) so that the shadow has some body on the thin pavement.
     */
    fun lampReach(depth: Float, height: Float = LAMP_HEIGHT): Float =
        STRETCH * SceneLight.FLAT * depth / (height - WALKER_MID)

    /** How much longer than the ground's slant allows a lamp's shadow is drawn. */
    const val STRETCH = 1.8f

    /**
     * How strong a lamp's light is on a walker [d] units along the street from its lens, 1 under it
     * to 0 at [LAMP_REACH] and beyond: the pool's own falloff.
     */
    fun lampFall(d: Float): Float {
        val u = (d * d) / (LAMP_REACH * LAMP_REACH)
        if (u >= 1f) return 0f
        val k = 1f - u
        return k * k
    }

    // ---- the headlights of a passing car

    /** How high over the road a car's headlights are, in street units. */
    const val HEADLIGHT = 3f

    /** How far out from the facades the near car's lane is, in depth units. */
    const val CAR_DEPTH = 56f

    /** How far out from the facades the pavement's furniture stands, in depth units. */
    const val FURNITURE_DEPTH = 10f

    /** How far along the facades the headlights' wash is seen from the car, in street units. */
    const val SWEEP_REACH = 190f

    /**
     * The scale a caster standing [casterDepth] out from a wall is drawn at on the wall, as a
     * shadow from a light [lightDepth] out: its shadow is that much bigger than it, about the
     * light's own spot on the wall.
     */
    fun wallScale(lightDepth: Float = CAR_DEPTH, casterDepth: Float = FURNITURE_DEPTH): Float =
        lightDepth / (lightDepth - casterDepth)

    /**
     * How strongly the headlights of a car at [carX] facing [dir] (1 right, -1 left) light the
     * facade at [x], 0 to 1: a wide, soft cone ahead of the car that fades with distance along the
     * street, with a little spill beside and behind it.
     */
    fun sweep(carX: Float, dir: Float, x: Float): Float {
        val d = (x - carX) * dir
        val dist = kotlin.math.abs(d)
        if (dist >= SWEEP_REACH) return 0f
        val u = dist / SWEEP_REACH
        val fall = (1f - u) * (1f - u)
        // ahead of the car the beam, behind it only the spill
        val ahead = if (d >= 0f) 1f else .2f
        // the cone opens over the first stretch, so the spot on the wall leads the car
        val cone = if (d <= 0f) 1f else kotlin.math.min(1f, .35f + d / 40f)
        return fall * ahead * cone
    }

    /**
     * Where along the wall the headlights' wash is brightest for a car at [carX] facing [dir]:
     * ahead of the car.
     */
    fun sweepSpot(carX: Float, dir: Float): Float = carX + dir * 26f

    /** How long the wash crosses one building's front, for a car going [speed] units a second. */
    fun sweepSeconds(speed: Float): Float = (SWEEP_REACH * .9f) / speed
}
