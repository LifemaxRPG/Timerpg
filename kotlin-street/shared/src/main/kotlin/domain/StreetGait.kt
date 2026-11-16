package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * How the people on the pavement move, on top of [Gait]: the street walker is seen close up, so the walk gets the
 * small things a window figure is too small to show. The phase of the cycle still comes from the distance walked
 * ([phase]: one place to route to a walker's own gait signature later), so nobody skates; what is added here lags
 * that phase a little, as loose things do: the wrists trail the arms, the head bobs a moment after the pelvis,
 * and a coat hem, a ponytail or a bag swing behind the body. Also the dog's trot and the cyclist's pedalling.
 * Pure arithmetic, no allocation except the pose arrays made while baking.
 */
object StreetGait {
    /**
     * Frames baked for one cycle of the street walk: four times the window walk's, for the
     * close-up, where a walk shows every jump between poses.
     */
    const val FRAMES = 64

    /**
     * The phase of the street walk [travel] units along, for a walker drawn [size] times the rig.
     */
    fun phase(travel: Float, size: Float, from: Float = Gait.PASSING): Float =
        Gait.phase(travel, size, from)

    /** How far behind the legs the wrists, the head and loose cloth lag, in cycles. */
    const val WRIST_LAG = .06f
    const val HEAD_LAG = .07f
    const val TRAIL_LAG = .09f

    /**
     * The walk's pose at [phi] with the street's refinements: the forearms trail the upper arms a
     * little and the head nods against the body's lean.
     */
    fun pose(phi: Float, look: Int = -1): FloatArray {
        val p = Gait.walkPose(phi, if (look < 0) Gait.NEUTRAL else style(look))
        val f = phi - floor(phi)
        val lag = sin(2f * PI.toFloat() * (f - WRIST_LAG)) - sin(2f * PI.toFloat() * f)
        p[K.EN] += 10f * lag
        p[K.EF] -= 10f * lag
        p[K.HEAD] += 1.4f * nod(f)
        return p
    }

    /**
     * How street look [look] walks: its own arm swing (one arm often swinging less), bob, posture,
     * shoulder roll and hips, so that no two walkers on the street move alike. The stride is the
     * standard one except for an older look's short steps ([ELDER_STRIDE]): the walk's phase comes
     * from the distance at the look's own stride ([StreetLife.pace]), so the feet never slide.
     */
    fun style(look: Int): Gait.Style = STYLES[look]

    /** An older walker's step, of the standard one: short, the feet kept near the ground. */
    const val ELDER_STRIDE = .74f

    /** How much spring an older walker's legs have ([Gait.Style.spring]). */
    const val ELDER_SPRING = .55f

    /**
     * The street walker's step, of the standard one: a person of the street's size at its walking
     * pace steps about a third of their height (a gait lab's 0.57 m at about 1 m/s), not the window
     * figure's shorter, quicker step. Their speed stays the same: they take fewer steps for it
     * ([cadence]).
     */
    const val STRIDE = 1.1f

    /**
     * The cadence of [look]'s walk, of the standard one ([Gait.CYCLE]): fewer steps for the longer
     * stride, so that they go as fast as they did; an older walker's short steps at the usual rate.
     */
    fun cadence(look: Int): Float = if (StreetLooks.ALL[look].elder) 1f else 1f / STRIDE

    private fun spread(look: Int, salt: Int, lo: Float, hi: Float): Float =
        lo + (hi - lo) * Ambient.hash(look + 1, 52, salt)

    private val STYLES: Array<Gait.Style> =
        Array(StreetLooks.COUNT) { look ->
            val l = StreetLooks.ALL[look]
            if (l.elder) {
                // short steps, a stoop, small arm swings (none to speak of on the stick's side),
                // little bob and stiff hips and shoulders, one step a touch longer than the other
                val arm = spread(look, 1, 11f, 14f)
                return@Array Gait.Style(
                    stride = ELDER_STRIDE,
                    armNear = if (l.stick) arm * .5f else arm,
                    armFar = arm * .85f,
                    bob = .55f,
                    lean = if (l.stick) 10f else 7f,
                    split = .025f,
                    roll = .5f,
                    hip = .7f,
                    spring = ELDER_SPRING,
                )
            }
            val arm = spread(look, 1, 17f, 30f)
            val less = spread(look, 2, .78f, 1f)
            val nearLess = Ambient.hash(look + 1, 52, 3) < .5f
            Gait.Style(
                stride = STRIDE,
                armNear = if (nearLess) arm * less else arm,
                armFar = if (nearLess) arm else arm * less,
                bob = spread(look, 5, .75f, 1.3f),
                lean = spread(look, 6, 2.2f, 5f),
                split = spread(look, 7, -.02f, .02f),
                roll = spread(look, 8, .7f, 1.3f),
                hip = spread(look, 9, .8f, 1.25f),
            )
        }

    /** The head's own nod, -1 to 1: twice a cycle, behind the pelvis's rise and fall. */
    fun nod(f: Float): Float = cos(4f * PI.toFloat() * (f - .125f - HEAD_LAG))

    /**
     * How much lower than the body carries it the head rides at [f], in units of height: the bob
     * reaches the head a moment after the pelvis.
     */
    fun headDrop(f: Float): Float =
        .011f *
            (cos(4f * PI.toFloat() * (f - .125f)) - cos(4f * PI.toFloat() * (f - .125f - HEAD_LAG)))

    /**
     * The swing of what hangs from the body once a cycle (a coat's hem, a handbag), -1 to 1, behind
     * the legs' swing: positive when the near leg has just swung forward.
     */
    fun trail(f: Float): Float = sin(2f * PI.toFloat() * (f - TRAIL_LAG))

    /**
     * The swing of what hangs from the head or the shoulders twice a cycle (a ponytail, a strap),
     * -1 to 1, behind the bob.
     */
    fun trail2(f: Float): Float = sin(4f * PI.toFloat() * (f - .125f - TRAIL_LAG))

    // ------------------------------------------------------------------------------ the dog

    /** Frames baked for the dog's trot, and its stride in units at the dog's own size. */
    const val DOG_FRAMES = 6
    const val DOG_STRIDE = 9f

    /**
     * The dog's leg angles at [f] of its trot (0 to 1): fore near, fore far, hind near, hind far.
     */
    fun dogLegs(f: Float, out: FloatArray) {
        val w = 2f * PI.toFloat() * f
        // a trot: diagonal pairs move together (near fore with far hind)
        out[0] = 28f * sin(w)
        out[1] = 28f * sin(w + PI.toFloat())
        out[2] = 26f * sin(w + PI.toFloat() + .3f)
        out[3] = 26f * sin(w + .3f)
    }

    /** The dog's body rise at [f] of its trot, units: it lifts a little twice a stride. */
    fun dogBob(f: Float): Float = .25f * (1f - cos(4f * PI.toFloat() * f))

    /** The tail's wag at [f], degrees from level. */
    fun dogTail(f: Float): Float = 18f * sin(4f * PI.toFloat() * f + 1f)

    // ------------------------------------------------------------------------------ the bicycle

    /** Frames baked for one turn of the cranks. */
    const val CRANK_FRAMES = 32

    /** The wheel's radius in units, and how far the bicycle goes for one turn of the cranks. */
    const val WHEEL_R = 4.6f
    const val CRANK_TRAVEL = 2f * PI.toFloat() * WHEEL_R * 2.6f

    /** The cranks' angle after [travel] units, radians (0: the near pedal at the front). */
    fun crank(travel: Float): Float {
        val t = travel / CRANK_TRAVEL
        return (t - floor(t)) * 2f * PI.toFloat()
    }

    /** The wheels' angle after [travel] units, radians. */
    fun wheel(travel: Float): Float = travel / WHEEL_R
}
