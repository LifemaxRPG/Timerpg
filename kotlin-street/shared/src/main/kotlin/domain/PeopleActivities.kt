package com.lifemax.rpg.feature.habits.domain

import com.lifemax.rpg.feature.habits.domain.FigBuilder.Companion.capsule
import com.lifemax.rpg.feature.habits.domain.FigBuilder.Companion.ellipse
import com.lifemax.rpg.feature.habits.domain.FigBuilder.Companion.rad
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What someone in a window is doing. */
enum class Activity {
    TYPING,
    READING,
    WRITING,
    STRETCHING,
    LIFTING,
    SQUATTING,
    PUSHUPS,
    JOGGING,
    MEDITATING,
    DRINKING,
    COOKING,
    BRUSHING,
    SWEEPING,
    PHONE_CALL,
    DANCING,
    MAKING_BED,
    PLANNING,
    WATERING,
    GUITAR,
    PIANO,
    WALKING,
    LOOKING_OUT,
    WAVING,
    TV_WATCHING,
    TREE_POSE,
    CHILD_PLAYING,
    TALKING,
}

/** The kind of room behind a window: the furniture and light a person is seen against. */
enum class RoomKind {
    OFFICE,
    DESK,
    STUDY,
    GYM,
    YOGA,
    KITCHEN,
    BATH,
    BEDROOM,
    LIVING,
    MUSIC,
    PIANO,
    PLANTS,
    BOARD,
    HALL,
    DINING,
}

/** A light on a figure besides the room's own: a screen's cool glow, a lamp's warm one. */
class Glow(val x: Float, val y: Float, val radius: Float, val color: Int, val strength: Float)

/**
 * How one activity looks and moves: its keyframes (a closed loop: the last is the first), the
 * [frames] baked from them (interpolated by a smooth spline), the [fps] they play at (a slow
 * activity plays fewer and blends between frames), the [room] it is seen in, what is held ([props])
 * and the lights falling on the figure ([glows]). [energy] sets how far hair swings; [still] is a
 * pose that plays no motion when time is frozen.
 */
class ActivitySpec(
    val activity: Activity,
    val room: RoomKind,
    val keys: List<FloatArray>,
    val frames: Int,
    val fps: Float,
    val blend: Boolean,
    val energy: Float,
    val glows: List<Glow>,
    val props: PropMaker?,
    val extra: List<FloatArray> = emptyList(),
    val smooth: Boolean = false,
    val lookKeys: List<List<FloatArray>>? = null,
    val lookExtra: List<List<FloatArray>>? = null,
    /**
     * Every kind of room the activity can be seen in, besides [room] (its natural one): what a
     * window gets when its floor would otherwise repeat a room (see `Silhouettes.cast`).
     */
    val alsoIn: List<RoomKind> = emptyList(),
    /** A small figure (a child), drawn from the child looks instead of the grown-up ones. */
    val child: Boolean = false,
    /**
     * Two people: the figure and, mirrored across the room's middle and out of step, a second one
     * facing them.
     */
    val pair: Boolean = false,
) {
    /** The rooms the activity is seen in, its natural one first. */
    val rooms: List<RoomKind> = listOf(room) + alsoIn.filter { it != room }

    /**
     * The pose [frame] shows: the loop sampled evenly for the first [frames], then the [extra]
     * poses (a turn, say) that are not part of the loop. With [look], that look's own keys and
     * extras ([lookKeys], [lookExtra]: a walk's gait style) where the activity has them.
     */
    fun poseAt(frame: Int, look: Int = -1): FloatArray =
        if (frame >= frames) extrasFor(look)[frame - frames]
        else Spline.sample(keysFor(look), frame / frames.toFloat())

    /** The pose [phase] (0 to 1, wrapping) of the loop shows, between the baked frames too. */
    fun poseAtPhase(phase: Float, look: Int = -1): FloatArray = Spline.sample(keysFor(look), phase)

    /** The loop's keys for [look]: its own when it has them, else the shared ones. */
    fun keysFor(look: Int): List<FloatArray> =
        if (look >= 0 && lookKeys != null) lookKeys[look] else keys

    private fun extrasFor(look: Int): List<FloatArray> =
        if (look >= 0 && lookExtra != null) lookExtra[look] else extra

    /** Every frame that can be baked: the loop's and the extras. */
    val totalFrames: Int
        get() = frames + extra.size

    /** A loop's length in seconds. */
    val seconds: Float
        get() = frames / fps
}

/** Smooth interpolation round a closed loop of keys. */
object Spline {
    fun sample(keys: List<FloatArray>, phase: Float): FloatArray {
        val n = keys.size - 1
        val t = ((phase % 1f) + 1f) % 1f * n
        val i = floor(t).toInt().coerceIn(0, n - 1)
        val u = t - i
        val p0 = keys[(i - 1 + n) % n]
        val p1 = keys[i]
        val p2 = keys[(i + 1) % n]
        val p3 = keys[(i + 2) % n]
        val out = FloatArray(K.SIZE)
        for (k in 0 until K.SIZE) {
            val a = p0[k]
            val b = p1[k]
            val c = p2[k]
            val d = p3[k]
            out[k] =
                .5f *
                    (2f * b +
                        (-a + c) * u +
                        (2f * a - 5f * b + 4f * c - d) * u * u +
                        (-a + 3f * b - 3f * c + d) * u * u * u)
        }
        return out
    }

    /** Whether a loop's last key is its first, within [tol]. */
    fun closed(keys: List<FloatArray>, tol: Float): Boolean =
        keys.size >= 3 && FigBuilder.same(keys.first(), keys.last(), tol)
}

internal object Props {
    fun box(cx: Float, cy: Float, w: Float, h: Float, rot: Float = 0f): FloatArray = run {
        val c = cos(rad(rot))
        val s = sin(rad(rot))
        val hw = w / 2f
        val hh = h / 2f
        floatArrayOf(
            cx + (-hw) * c - (-hh) * s,
            cy + (-hw) * s + (-hh) * c,
            cx + hw * c - (-hh) * s,
            cy + hw * s + (-hh) * c,
            cx + hw * c - hh * s,
            cy + hw * s + hh * c,
            cx + (-hw) * c - hh * s,
            cy + (-hw) * s + hh * c,
        )
    }

    fun quad(vararg p: Float): FloatArray = p
}

/** Every activity's spec. */
object PeopleActivities {
    private fun loop(vararg k: FloatArray): List<FloatArray> = k.toList() + listOf(k[0])

    private val COOL = 0xBFD8FF
    private val WARM = 0xFFCF8A
    private val WHITE = 0xFFF2D8

    private const val SIT_N = 88f
    private const val SIT_L = 80f

    // ---- typing at a laptop on a desk, the screen's glow on their face
    private val TYPING_KEYS =
        loop(
            pose(
                x = 3f,
                turn = .82f,
                lean = 7f,
                head = 12f,
                an = 17f,
                en = 62f,
                af = 14f,
                ef = 64f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .82f,
                lean = 6f,
                head = 11f,
                an = 15f,
                en = 68f,
                af = 18f,
                ef = 58f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .82f,
                lean = 7f,
                head = 14f,
                an = 19f,
                en = 60f,
                af = 13f,
                ef = 66f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .82f,
                lean = 5f,
                head = 10f,
                an = 16f,
                en = 66f,
                af = 17f,
                ef = 60f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .82f,
                lean = 4f,
                head = 8f,
                an = 14f,
                en = 70f,
                af = 12f,
                ef = 68f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .82f,
                lean = 6f,
                head = 12f,
                an = 18f,
                en = 61f,
                af = 16f,
                ef = 63f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
        )

    private val LAPTOP = PropMaker { f, _, _ ->
        f.add(Shape.PROP, Props.box(6.4f, -9.55f, 4.8f, .7f), .45f)
        f.add(Shape.PROP, Props.quad(8.3f, -9.8f, 8.95f, -9.8f, 9.9f, -16.4f, 9.25f, -16.4f), .5f)
    }

    // ---- reading a book in an armchair, a page turned now and then
    private val READ_KEYS =
        loop(
            pose(
                x = 3.6f,
                turn = .8f,
                lean = -6f,
                head = 16f,
                an = 22f,
                en = 88f,
                af = 26f,
                ef = 80f,
                ln = 84f,
                kn = 74f,
                lf = 76f,
                kf = 64f,
                pin = 1f,
            ),
            pose(
                x = 3.6f,
                turn = .8f,
                lean = -5f,
                head = 15f,
                an = 24f,
                en = 86f,
                af = 34f,
                ef = 84f,
                ln = 84f,
                kn = 74f,
                lf = 76f,
                kf = 64f,
                pin = 1f,
            ),
            pose(
                x = 3.6f,
                turn = .8f,
                lean = -6f,
                head = 12f,
                an = 22f,
                en = 90f,
                af = 22f,
                ef = 98f,
                ln = 84f,
                kn = 74f,
                lf = 76f,
                kf = 64f,
                pin = 1f,
            ),
            pose(
                x = 3.6f,
                turn = .8f,
                lean = -7f,
                head = 17f,
                an = 22f,
                en = 88f,
                af = 26f,
                ef = 80f,
                ln = 84f,
                kn = 74f,
                lf = 76f,
                kf = 64f,
                pin = 1f,
            ),
        )

    private val BOOK = PropMaker { f, j, phase ->
        val cx = (j.handNX + j.handFX) / 2f + .8f
        val cy = (j.handNY + j.handFY) / 2f - 1.2f
        f.add(Shape.PROP, Props.box(cx, cy, 3.4f, 4.3f, -62f), .35f)
        // a page lifting as it turns
        if (phase in .3f..0.7f)
            f.add(Shape.PROP, Props.box(cx + .9f, cy - 1.3f, 2.2f, 3.6f, -30f), .3f)
    }

    // ---- writing in a journal at a desk
    private val WRITE_KEYS =
        loop(
            pose(
                x = 3f,
                turn = .85f,
                lean = 15f,
                head = 26f,
                an = 24f,
                en = 62f,
                af = 34f,
                ef = 52f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .85f,
                lean = 15f,
                head = 25f,
                an = 26f,
                en = 56f,
                af = 34f,
                ef = 52f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .85f,
                lean = 16f,
                head = 27f,
                an = 24f,
                en = 66f,
                af = 34f,
                ef = 52f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .85f,
                lean = 14f,
                head = 24f,
                an = 22f,
                en = 60f,
                af = 34f,
                ef = 52f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
        )

    private val PEN = PropMaker { f, j, _ ->
        f.add(Shape.PROP, Props.box(6.2f, -9.5f, 3.6f, .45f, 2f), .3f)
        f.add(
            Shape.PROP,
            capsule(j.handNX, j.handNY, .22f, j.handNX + 1.6f, j.handNY + .5f, .15f, 3),
            .2f,
        )
    }

    // ---- stretching: arms up, folding forward, a side bend
    private val STRETCH_KEYS =
        loop(
            pose(
                turn = .12f,
                an = 8f,
                en = 6f,
                af = -8f,
                ef = -6f,
                ln = 1f,
                kn = 2f,
                lf = -1f,
                kf = 2f,
            ),
            pose(
                turn = .1f,
                lean = 0f,
                head = -4f,
                an = 168f,
                en = 0f,
                af = -168f,
                ef = 0f,
                ln = 1f,
                kn = 2f,
                lf = -1f,
                kf = 2f,
            ),
            pose(
                x = -2f,
                turn = .1f,
                lean = 18f,
                head = 0f,
                an = 160f,
                en = -8f,
                af = -150f,
                ef = -8f,
                ln = 1f,
                kn = 2f,
                lf = -1f,
                kf = 2f,
            ),
            pose(
                x = -1f,
                turn = .55f,
                lean = 70f,
                head = 25f,
                an = 20f,
                en = 4f,
                af = 10f,
                ef = 6f,
                ln = -4f,
                kn = 2f,
                lf = -6f,
                kf = 2f,
            ),
            pose(
                x = -2f,
                turn = .55f,
                lean = 100f,
                head = 40f,
                an = 6f,
                en = 4f,
                af = 2f,
                ef = 4f,
                ln = -6f,
                kn = 2f,
                lf = -8f,
                kf = 2f,
            ),
            pose(
                x = -1f,
                turn = .55f,
                lean = 60f,
                head = 22f,
                an = 18f,
                en = 4f,
                af = 10f,
                ef = 6f,
                ln = -4f,
                kn = 2f,
                lf = -6f,
                kf = 2f,
            ),
        )

    // ---- dumbbell curls
    private val LIFT_KEYS =
        loop(
            pose(
                turn = .35f,
                an = 6f,
                en = 10f,
                af = -6f,
                ef = -10f,
                ln = 3f,
                kn = 6f,
                lf = -3f,
                kf = 6f,
            ),
            pose(
                turn = .35f,
                an = 12f,
                en = 62f,
                af = -8f,
                ef = -40f,
                ln = 3f,
                kn = 8f,
                lf = -3f,
                kf = 8f,
            ),
            pose(
                turn = .35f,
                head = -3f,
                an = 18f,
                en = 128f,
                af = -10f,
                ef = -118f,
                ln = 3f,
                kn = 9f,
                lf = -3f,
                kf = 9f,
            ),
            pose(
                turn = .35f,
                an = 12f,
                en = 62f,
                af = -8f,
                ef = -40f,
                ln = 3f,
                kn = 8f,
                lf = -3f,
                kf = 8f,
            ),
        )

    private val DUMBBELLS = PropMaker { f, j, _ ->
        for ((hx, hy, a, l) in
            listOf(
                listOf(j.handNX, j.handNY, j.foreN, 1f),
                listOf(j.handFX, j.handFY, j.foreF, 0f),
            )) {
            f.add(Shape.PROP, ellipse(hx, hy, 1.15f, .85f, a + 90f, 8), if (l > 0f) .5f else .4f)
        }
    }

    // ---- goblet squats
    private val SQUAT_KEYS =
        loop(
            pose(
                x = 1f,
                turn = .2f,
                an = 52f,
                en = 62f,
                af = -52f,
                ef = -62f,
                ln = 2f,
                kn = 4f,
                lf = -2f,
                kf = 4f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .35f,
                lean = 16f,
                head = -6f,
                an = 62f,
                en = 56f,
                af = -30f,
                ef = -50f,
                ln = 40f,
                kn = 60f,
                lf = 36f,
                kf = 56f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .5f,
                lean = 30f,
                head = -10f,
                an = 70f,
                en = 48f,
                af = 40f,
                ef = 50f,
                ln = 78f,
                kn = 112f,
                lf = 72f,
                kf = 106f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .35f,
                lean = 16f,
                head = -6f,
                an = 62f,
                en = 56f,
                af = -30f,
                ef = -50f,
                ln = 40f,
                kn = 60f,
                lf = 36f,
                kf = 56f,
                pin = 1f,
            ),
        )

    private val GOBLET = PropMaker { f, j, _ ->
        f.add(
            Shape.PROP,
            ellipse(
                (j.handNX + j.handFX) / 2f + .5f,
                (j.handNY + j.handFY) / 2f,
                1.3f,
                1.4f,
                0f,
                8,
            ),
            .5f,
        )
    }

    // ---- push-ups on the floor
    private val PUSH_KEYS =
        loop(
            pose(
                x = -9f,
                turn = .9f,
                lean = 74f,
                head = -26f,
                an = 0f,
                en = 0f,
                af = 2f,
                ef = -2f,
                ln = -66f,
                kn = 2f,
                lf = -68f,
                kf = 2f,
                pin = 1f,
            ),
            pose(
                x = -9f,
                turn = .9f,
                lean = 78f,
                head = -28f,
                an = -22f,
                en = 24f,
                af = -20f,
                ef = 22f,
                ln = -70f,
                kn = 2f,
                lf = -72f,
                kf = 2f,
                pin = 1f,
            ),
            pose(
                x = -9f,
                turn = .9f,
                lean = 88f,
                head = -34f,
                an = -62f,
                en = 70f,
                af = -60f,
                ef = 68f,
                ln = -82f,
                kn = 2f,
                lf = -84f,
                kf = 2f,
                pin = 1f,
            ),
            pose(
                x = -9f,
                turn = .9f,
                lean = 78f,
                head = -28f,
                an = -22f,
                en = 24f,
                af = -20f,
                ef = 22f,
                ln = -70f,
                kn = 2f,
                lf = -72f,
                kf = 2f,
                pin = 1f,
            ),
        )

    // ---- running on a treadmill
    // the jog is worked out in Gait

    // ---- sitting cross-legged on a mat, breathing
    private val MEDITATE_KEYS =
        loop(
            pose(
                turn = .1f,
                lean = 0f,
                head = 3f,
                an = 26f,
                en = -10f,
                af = -26f,
                ef = 10f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .1f,
                lean = -1f,
                head = 1f,
                an = 25f,
                en = -8f,
                af = -25f,
                ef = 8f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .1f,
                lean = -2f,
                head = 0f,
                an = 24f,
                en = -6f,
                af = -24f,
                ef = 6f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .1f,
                lean = -1f,
                head = 2f,
                an = 25f,
                en = -8f,
                af = -25f,
                ef = 8f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
        )

    // ---- drinking a glass of water or tea
    private val DRINK_KEYS =
        loop(
            pose(
                turn = .55f,
                lean = 0f,
                head = 0f,
                an = 18f,
                en = 58f,
                af = -6f,
                ef = -14f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
            ),
            pose(
                turn = .6f,
                lean = -1f,
                head = 2f,
                an = 36f,
                en = 96f,
                af = -6f,
                ef = -14f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
            ),
            pose(
                turn = .6f,
                lean = -3f,
                head = -8f,
                an = 48f,
                en = 112f,
                af = -6f,
                ef = -14f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
            ),
            pose(
                turn = .6f,
                lean = -3f,
                head = -10f,
                an = 50f,
                en = 114f,
                af = -6f,
                ef = -14f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
            ),
            pose(
                turn = .6f,
                lean = -1f,
                head = 0f,
                an = 34f,
                en = 92f,
                af = -6f,
                ef = -14f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
            ),
        )

    private val CUP = PropMaker { f, j, _ ->
        f.add(
            Shape.PROP,
            Props.box(j.handNX + .3f, j.handNY - .6f, 1.7f, 2.2f, j.foreN - 90f + 90f),
            .45f,
        )
    }

    // ---- chopping at a counter
    private val COOK_KEYS =
        loop(
            pose(
                x = 2.5f,
                turn = .78f,
                lean = 13f,
                head = 24f,
                an = 22f,
                en = 76f,
                af = 28f,
                ef = 44f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .78f,
                lean = 13f,
                head = 24f,
                an = 30f,
                en = 52f,
                af = 28f,
                ef = 44f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .78f,
                lean = 14f,
                head = 25f,
                an = 22f,
                en = 72f,
                af = 28f,
                ef = 44f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .78f,
                lean = 13f,
                head = 24f,
                an = 32f,
                en = 46f,
                af = 28f,
                ef = 44f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
        )

    private val KNIFE = PropMaker { f, j, _ ->
        f.add(Shape.PROP, Props.box(j.handNX + 1.1f, j.handNY + .5f, 2.8f, .5f, 20f), .3f)
    }

    // ---- brushing teeth at a sink
    private val BRUSH_KEYS =
        loop(
            pose(
                x = 2.5f,
                turn = .8f,
                lean = 8f,
                head = 10f,
                an = 50f,
                en = 104f,
                af = 14f,
                ef = 18f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .8f,
                lean = 8f,
                head = 10f,
                an = 46f,
                en = 112f,
                af = 14f,
                ef = 18f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .8f,
                lean = 9f,
                head = 11f,
                an = 52f,
                en = 100f,
                af = 14f,
                ef = 18f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 2.5f,
                turn = .8f,
                lean = 8f,
                head = 10f,
                an = 47f,
                en = 109f,
                af = 14f,
                ef = 18f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
        )

    private val TOOTHBRUSH = PropMaker { f, j, _ ->
        f.add(
            Shape.PROP,
            capsule(j.handNX, j.handNY, .2f, j.handNX + 1.9f, j.handNY - .2f, .22f, 3),
            .25f,
        )
    }

    // ---- sweeping with a broom
    private val SWEEP_KEYS =
        loop(
            pose(
                x = 0f,
                turn = .8f,
                lean = 16f,
                head = 14f,
                an = 42f,
                en = 18f,
                af = 48f,
                ef = 14f,
                ln = 8f,
                kn = 6f,
                lf = -6f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .8f,
                lean = 19f,
                head = 16f,
                an = 34f,
                en = 20f,
                af = 40f,
                ef = 16f,
                ln = 12f,
                kn = 8f,
                lf = -8f,
                kf = 8f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .8f,
                lean = 22f,
                head = 18f,
                an = 28f,
                en = 22f,
                af = 34f,
                ef = 18f,
                ln = 14f,
                kn = 8f,
                lf = -10f,
                kf = 8f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .8f,
                lean = 19f,
                head = 16f,
                an = 34f,
                en = 20f,
                af = 40f,
                ef = 16f,
                ln = 12f,
                kn = 8f,
                lf = -8f,
                kf = 8f,
                pin = 1f,
            ),
        )

    private val BROOM = PropMaker { f, j, phase ->
        val sweep = sin(phase * 6.2832f) * 1.6f
        val hx = (j.handNX + j.handFX) / 2f
        val hy = (j.handNY + j.handFY) / 2f
        val fx = hx + 4.2f + sweep
        val fy = -.2f - 0f
        val tx = hx - (fx - hx) * .45f
        val ty = hy - (fy - hy) * .45f
        f.add(Shape.PROP, capsule(tx, ty, .28f, fx, fy - .6f, .28f, 3), .3f)
        f.add(
            Shape.PROP,
            Props.quad(fx - 1.6f, fy - .9f, fx + 1.2f, fy - 1.0f, fx + 2.2f, fy, fx - 2.4f, fy),
            .22f,
        )
    }

    // ---- on the phone: a hand to the ear, the other talking
    private val PHONE_KEYS =
        loop(
            pose(
                x = 0f,
                turn = .6f,
                lean = 0f,
                head = 8f,
                an = 60f,
                en = 108f,
                af = -10f,
                ef = -20f,
                ln = 6f,
                kn = 6f,
                lf = -8f,
                kf = 6f,
            ),
            pose(
                x = .4f,
                turn = .5f,
                lean = 1f,
                head = 10f,
                an = 62f,
                en = 106f,
                af = 30f,
                ef = 40f,
                ln = 14f,
                kn = 10f,
                lf = -12f,
                kf = 8f,
            ),
            pose(
                x = 0f,
                turn = .6f,
                lean = 0f,
                head = 6f,
                an = 60f,
                en = 108f,
                af = 46f,
                ef = 26f,
                ln = 4f,
                kn = 4f,
                lf = 2f,
                kf = 4f,
            ),
            pose(
                x = -.4f,
                turn = .5f,
                lean = -1f,
                head = 8f,
                an = 58f,
                en = 110f,
                af = 24f,
                ef = 50f,
                ln = -8f,
                kn = 8f,
                lf = 12f,
                kf = 8f,
            ),
        )

    private val PHONE = PropMaker { f, j, _ ->
        f.add(Shape.PROP, Props.box(j.handNX - .1f, j.handNY - .9f, .9f, 2.4f, j.foreN - 90f), .25f)
    }

    // ---- dancing to music
    private val DANCE_KEYS =
        loop(
            pose(
                x = 0f,
                turn = .2f,
                lean = 5f,
                head = 4f,
                an = 136f,
                en = -26f,
                af = -70f,
                ef = -40f,
                ln = 14f,
                kn = 16f,
                lf = -10f,
                kf = 14f,
                lift = .2f,
            ),
            pose(
                x = .6f,
                turn = .2f,
                lean = -3f,
                head = 0f,
                an = 100f,
                en = -46f,
                af = -118f,
                ef = -30f,
                ln = 4f,
                kn = 40f,
                lf = 2f,
                kf = 40f,
                lift = 0f,
            ),
            pose(
                x = 0f,
                turn = .2f,
                lean = -5f,
                head = -4f,
                an = 72f,
                en = 46f,
                af = -136f,
                ef = 26f,
                ln = -10f,
                kn = 14f,
                lf = 14f,
                kf = 16f,
                lift = .2f,
            ),
            pose(
                x = -.6f,
                turn = .2f,
                lean = 3f,
                head = 0f,
                an = 116f,
                en = 26f,
                af = -104f,
                ef = 46f,
                ln = 2f,
                kn = 40f,
                lf = 4f,
                kf = 40f,
                lift = 0f,
            ),
        )

    // ---- making a bed: leaning over, then shaking the sheet out
    private val BED_KEYS =
        loop(
            pose(
                x = 0f,
                turn = .85f,
                lean = 52f,
                head = 20f,
                an = 62f,
                en = 24f,
                af = 70f,
                ef = 18f,
                ln = 4f,
                kn = 8f,
                lf = -6f,
                kf = 8f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .85f,
                lean = 30f,
                head = 10f,
                an = 140f,
                en = 10f,
                af = 148f,
                ef = 6f,
                ln = 4f,
                kn = 8f,
                lf = -6f,
                kf = 8f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .85f,
                lean = 46f,
                head = 14f,
                an = 96f,
                en = 20f,
                af = 104f,
                ef = 14f,
                ln = 4f,
                kn = 8f,
                lf = -6f,
                kf = 8f,
                pin = 1f,
            ),
            pose(
                x = 0f,
                turn = .85f,
                lean = 60f,
                head = 22f,
                an = 56f,
                en = 30f,
                af = 62f,
                ef = 28f,
                ln = 4f,
                kn = 8f,
                lf = -6f,
                kf = 8f,
                pin = 1f,
            ),
        )

    private val SHEET = PropMaker { f, j, phase ->
        val ax = j.handNX
        val ay = j.handNY
        val bx = j.handFX + 1.2f
        val by = j.handFY
        val droop = 2.6f + 1.6f * sin(phase * 6.2832f)
        f.add(
            Shape.PROP,
            Props.quad(ax, ay, bx, by, bx + 3.4f, by + droop, ax + 3.2f, ay + droop * .85f),
            .55f,
        )
    }

    // ---- planning the day on a whiteboard
    private val PLAN_KEYS =
        loop(
            pose(
                x = 1f,
                turn = .8f,
                lean = -2f,
                head = -4f,
                an = 128f,
                en = -12f,
                af = -16f,
                ef = -72f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .8f,
                lean = -1f,
                head = -4f,
                an = 134f,
                en = -4f,
                af = -16f,
                ef = -72f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .8f,
                lean = -2f,
                head = -2f,
                an = 122f,
                en = -8f,
                af = -16f,
                ef = -72f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .8f,
                lean = -3f,
                head = -4f,
                an = 130f,
                en = -16f,
                af = -16f,
                ef = -72f,
                ln = 3f,
                kn = 5f,
                lf = -3f,
                kf = 5f,
                pin = 1f,
            ),
        )

    // ---- watering the plants
    private val WATER_KEYS =
        loop(
            pose(
                x = 1f,
                turn = .85f,
                lean = 4f,
                head = 8f,
                an = 56f,
                en = 14f,
                af = 40f,
                ef = 20f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .85f,
                lean = 8f,
                head = 14f,
                an = 70f,
                en = 6f,
                af = 46f,
                ef = 14f,
                ln = 5f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .85f,
                lean = 10f,
                head = 16f,
                an = 74f,
                en = 4f,
                af = 48f,
                ef = 12f,
                ln = 5f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
            pose(
                x = 1f,
                turn = .85f,
                lean = 6f,
                head = 10f,
                an = 62f,
                en = 10f,
                af = 42f,
                ef = 16f,
                ln = 4f,
                kn = 6f,
                lf = -4f,
                kf = 6f,
                pin = 1f,
            ),
        )

    private val CAN = PropMaker { f, j, phase ->
        val tilt = 8f + 26f * (if (phase in .25f..0.75f) 1f else .3f)
        val cx = j.handNX + .6f
        val cy = j.handNY + .2f
        f.add(Shape.PROP, Props.box(cx, cy, 3.2f, 2.6f, 0f), .45f)
        f.add(
            Shape.PROP,
            capsule(cx + 1.2f, cy - .2f, .35f, cx + 4.4f, cy + 0.6f + tilt * .06f, .22f, 3),
            .4f,
        )
    }

    // ---- guitar: a chord held, a strum
    private val GUITAR_KEYS =
        loop(
            pose(
                x = 3f,
                turn = .9f,
                lean = 6f,
                head = 18f,
                an = 24f,
                en = 50f,
                af = 54f,
                ef = 36f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .9f,
                lean = 5f,
                head = 16f,
                an = 18f,
                en = 62f,
                af = 56f,
                ef = 34f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .9f,
                lean = 6f,
                head = 18f,
                an = 26f,
                en = 46f,
                af = 54f,
                ef = 38f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .9f,
                lean = 7f,
                head = 20f,
                an = 20f,
                en = 58f,
                af = 56f,
                ef = 34f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
        )

    private val GUITAR = PropMaker { f, j, _ ->
        val bx = j.pelvisX + 2.6f
        val by = j.pelvisY - 3.6f
        f.add(Shape.PROP, ellipse(bx, by + .4f, 2.9f, 2.4f, -20f, 12), .4f)
        f.add(Shape.PROP, ellipse(bx + 1.6f, by - 1.5f, 1.7f, 1.4f, -20f, 10), .4f)
        f.add(Shape.PROP, capsule(bx + 2.6f, by - 2.4f, .5f, bx + 9.2f, by - 6.6f, .45f, 3), .3f)
    }

    // ---- piano
    private val PIANO_KEYS =
        loop(
            pose(
                x = 3f,
                turn = .86f,
                lean = 8f,
                head = 8f,
                an = 22f,
                en = 62f,
                af = 18f,
                ef = 66f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .86f,
                lean = 11f,
                head = 4f,
                an = 28f,
                en = 56f,
                af = 14f,
                ef = 72f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .86f,
                lean = 7f,
                head = 10f,
                an = 18f,
                en = 68f,
                af = 24f,
                ef = 58f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
            pose(
                x = 3f,
                turn = .86f,
                lean = 4f,
                head = 14f,
                an = 24f,
                en = 60f,
                af = 16f,
                ef = 68f,
                ln = SIT_N,
                kn = 88f,
                lf = SIT_L,
                kf = 84f,
                pin = 1f,
            ),
        )

    // ---- walking past: the keys are worked out from the feet in Gait

    // ---- standing at the glass, arms folded, looking out at the street
    private val LOOK_KEYS =
        loop(
            pose(
                turn = .06f,
                head = 0f,
                an = -6f,
                en = -86f,
                af = 8f,
                ef = 82f,
                ln = 2f,
                kn = 3f,
                lf = -2f,
                kf = 3f,
            ),
            pose(
                turn = .12f,
                head = 4f,
                lean = 1f,
                an = -6f,
                en = -88f,
                af = 8f,
                ef = 84f,
                ln = 3f,
                kn = 3f,
                lf = -3f,
                kf = 5f,
            ),
            pose(
                turn = .06f,
                head = -2f,
                an = -5f,
                en = -84f,
                af = 9f,
                ef = 80f,
                ln = 1f,
                kn = 3f,
                lf = -1f,
                kf = 3f,
            ),
            pose(
                turn = .0f,
                head = -5f,
                lean = -1f,
                an = -6f,
                en = -86f,
                af = 8f,
                ef = 82f,
                ln = 2f,
                kn = 5f,
                lf = -2f,
                kf = 3f,
            ),
        )

    /** Standing, looking at a watch: the near forearm raised across the chest, the head down. */
    private val WATCH =
        pose(
            turn = .08f,
            head = 7f,
            lean = 3f,
            an = 52f,
            en = -128f,
            af = 5f,
            ef = 74f,
            ln = 2f,
            kn = 3f,
            lf = -2f,
            kf = 3f,
        )

    // ---- waving
    private val WAVE_KEYS =
        loop(
            pose(
                turn = .08f,
                head = 3f,
                an = 128f,
                en = 24f,
                af = -8f,
                ef = -12f,
                ln = 2f,
                kn = 3f,
                lf = -2f,
                kf = 3f,
            ),
            pose(
                turn = .08f,
                head = 4f,
                an = 130f,
                en = -6f,
                af = -8f,
                ef = -12f,
                ln = 2f,
                kn = 3f,
                lf = -2f,
                kf = 3f,
            ),
            pose(
                turn = .08f,
                head = 3f,
                an = 126f,
                en = -34f,
                af = -8f,
                ef = -12f,
                ln = 2f,
                kn = 3f,
                lf = -2f,
                kf = 3f,
            ),
            pose(
                turn = .08f,
                head = 2f,
                an = 130f,
                en = -6f,
                af = -8f,
                ef = -12f,
                ln = 2f,
                kn = 3f,
                lf = -2f,
                kf = 3f,
            ),
        )

    // ---- watching television from the sofa, slumped a little, the remote in hand
    private val TV_KEYS =
        loop(
            pose(
                x = 3.2f,
                turn = .78f,
                lean = -12f,
                head = 8f,
                an = 14f,
                en = 72f,
                af = 10f,
                ef = 60f,
                ln = 86f,
                kn = 82f,
                lf = 80f,
                kf = 74f,
                pin = 1f,
            ),
            pose(
                x = 3.2f,
                turn = .78f,
                lean = -12f,
                head = 10f,
                an = 12f,
                en = 74f,
                af = 10f,
                ef = 60f,
                ln = 86f,
                kn = 82f,
                lf = 80f,
                kf = 74f,
                pin = 1f,
            ),
            pose(
                x = 3.2f,
                turn = .78f,
                lean = -11f,
                head = 6f,
                an = 36f,
                en = 40f,
                af = 10f,
                ef = 62f,
                ln = 86f,
                kn = 82f,
                lf = 80f,
                kf = 74f,
                pin = 1f,
            ),
            pose(
                x = 3.2f,
                turn = .78f,
                lean = -13f,
                head = 9f,
                an = 14f,
                en = 72f,
                af = 12f,
                ef = 58f,
                ln = 86f,
                kn = 82f,
                lf = 80f,
                kf = 74f,
                pin = 1f,
            ),
        )

    private val REMOTE = PropMaker { f, j, _ ->
        f.add(Shape.PROP, Props.box(j.handNX + .3f, j.handNY - .4f, .8f, 2.6f, j.foreN - 70f), .25f)
    }

    // ---- the tree pose: one foot on the other knee, the hands together overhead, a slow sway
    private val TREE_KEYS =
        loop(
            pose(
                turn = .08f,
                lean = 0f,
                head = -2f,
                an = 164f,
                en = 24f,
                af = 162f,
                ef = -24f,
                ln = 0f,
                kn = 0f,
                lf = 38f,
                kf = 112f,
                anf = -30f,
            ),
            pose(
                turn = .08f,
                lean = 1f,
                head = -1f,
                an = 166f,
                en = 22f,
                af = 164f,
                ef = -22f,
                ln = 1f,
                kn = 1f,
                lf = 40f,
                kf = 114f,
                anf = -30f,
            ),
            pose(
                turn = .08f,
                lean = -1f,
                head = -3f,
                an = 163f,
                en = 26f,
                af = 161f,
                ef = -26f,
                ln = 0f,
                kn = 0f,
                lf = 37f,
                kf = 110f,
                anf = -30f,
            ),
            pose(
                turn = .08f,
                lean = 0f,
                head = -2f,
                an = 165f,
                en = 23f,
                af = 163f,
                ef = -23f,
                ln = 0f,
                kn = 0f,
                lf = 39f,
                kf = 113f,
                anf = -30f,
            ),
        )

    // ---- a child cross-legged on the floor, stacking blocks
    private val CHILD_KEYS =
        loop(
            pose(
                turn = .2f,
                lean = 16f,
                head = 22f,
                an = 46f,
                en = 30f,
                af = 30f,
                ef = 40f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .2f,
                lean = 20f,
                head = 26f,
                an = 58f,
                en = 16f,
                af = 32f,
                ef = 38f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .2f,
                lean = 12f,
                head = 14f,
                an = 40f,
                en = 48f,
                af = 26f,
                ef = 46f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
            pose(
                turn = .2f,
                lean = 18f,
                head = 24f,
                an = 50f,
                en = 24f,
                af = 34f,
                ef = 36f,
                ln = 54f,
                kn = 118f,
                lf = -54f,
                kf = -118f,
            ),
        )

    private val BLOCKS = PropMaker { f, _, phase ->
        f.add(Shape.PROP, Props.box(4.2f, -.75f, 1.5f, 1.5f), .3f)
        f.add(Shape.PROP, Props.box(4.3f, -2.2f, 1.4f, 1.4f, 6f), .42f)
        f.add(Shape.PROP, Props.box(6.1f, -.7f, 1.4f, 1.4f, -4f), .36f)
        if (phase > .5f) f.add(Shape.PROP, Props.box(4.4f, -3.6f, 1.3f, 1.3f, -8f), .3f)
    }

    // ---- two people at a table, talking: a hand raised, a nod
    private val TALK_KEYS =
        loop(
            pose(
                x = -3f,
                turn = .82f,
                lean = 5f,
                head = 4f,
                an = 34f,
                en = 96f,
                af = 22f,
                ef = 74f,
                ln = 78f,
                kn = 84f,
                lf = 74f,
                kf = 78f,
                pin = 1f,
            ),
            pose(
                x = -3f,
                turn = .82f,
                lean = 7f,
                head = 8f,
                an = 46f,
                en = 84f,
                af = 22f,
                ef = 74f,
                ln = 78f,
                kn = 84f,
                lf = 74f,
                kf = 78f,
                pin = 1f,
            ),
            pose(
                x = -3f,
                turn = .82f,
                lean = 4f,
                head = 2f,
                an = 28f,
                en = 104f,
                af = 26f,
                ef = 70f,
                ln = 78f,
                kn = 84f,
                lf = 74f,
                kf = 78f,
                pin = 1f,
            ),
            pose(
                x = -3f,
                turn = .82f,
                lean = 6f,
                head = 10f,
                an = 30f,
                en = 90f,
                af = 20f,
                ef = 78f,
                ln = 78f,
                kn = 84f,
                lf = 74f,
                kf = 78f,
                pin = 1f,
            ),
        )

    private fun glow(x: Float, y: Float, r: Float, c: Int, k: Float) = Glow(x, y, r, c, k)

    val ALL: Array<ActivitySpec> =
        arrayOf(
            ActivitySpec(
                Activity.TYPING,
                RoomKind.OFFICE,
                TYPING_KEYS,
                4,
                10f,
                false,
                .3f,
                listOf(glow(8.4f, -13f, 8.5f, COOL, .62f)),
                LAPTOP,
                alsoIn = listOf(RoomKind.DESK),
            ),
            ActivitySpec(
                Activity.READING,
                RoomKind.STUDY,
                READ_KEYS,
                4,
                .8f,
                true,
                0f,
                listOf(glow(9f, -14f, 9f, WARM, .3f)),
                BOOK,
                alsoIn = listOf(RoomKind.LIVING),
            ),
            ActivitySpec(
                Activity.WRITING,
                RoomKind.DESK,
                WRITE_KEYS,
                4,
                6f,
                false,
                .1f,
                listOf(glow(7f, -14f, 8f, WARM, .35f)),
                PEN,
                alsoIn = listOf(RoomKind.OFFICE),
            ),
            ActivitySpec(
                Activity.STRETCHING,
                RoomKind.YOGA,
                STRETCH_KEYS,
                6,
                1.1f,
                true,
                .2f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.GYM, RoomKind.LIVING),
            ),
            ActivitySpec(
                Activity.LIFTING,
                RoomKind.GYM,
                LIFT_KEYS,
                4,
                6f,
                false,
                .3f,
                emptyList(),
                DUMBBELLS,
            ),
            ActivitySpec(
                Activity.SQUATTING,
                RoomKind.GYM,
                SQUAT_KEYS,
                4,
                5f,
                false,
                .3f,
                emptyList(),
                GOBLET,
                alsoIn = listOf(RoomKind.YOGA),
            ),
            ActivitySpec(
                Activity.PUSHUPS,
                RoomKind.YOGA,
                PUSH_KEYS,
                4,
                5f,
                false,
                0f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.GYM),
            ),
            ActivitySpec(
                Activity.JOGGING,
                RoomKind.GYM,
                Gait.JOG_KEYS,
                Gait.JOG_FRAMES,
                Gait.JOG_FRAMES / Gait.JOG_CYCLE,
                false,
                1f,
                emptyList(),
                null,
                smooth = true,
            ),
            ActivitySpec(
                Activity.MEDITATING,
                RoomKind.YOGA,
                MEDITATE_KEYS,
                4,
                .5f,
                true,
                0f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.LIVING),
            ),
            ActivitySpec(
                Activity.DRINKING,
                RoomKind.KITCHEN,
                DRINK_KEYS,
                5,
                .9f,
                true,
                0f,
                emptyList(),
                CUP,
                alsoIn = listOf(RoomKind.LIVING, RoomKind.OFFICE, RoomKind.HALL, RoomKind.DINING),
            ),
            ActivitySpec(
                Activity.COOKING,
                RoomKind.KITCHEN,
                COOK_KEYS,
                4,
                8f,
                false,
                .1f,
                listOf(glow(7f, -14f, 8f, WARM, .3f)),
                KNIFE,
            ),
            ActivitySpec(
                Activity.BRUSHING,
                RoomKind.BATH,
                BRUSH_KEYS,
                4,
                10f,
                false,
                0f,
                listOf(glow(6.5f, -17f, 8f, WHITE, .3f)),
                TOOTHBRUSH,
            ),
            ActivitySpec(
                Activity.SWEEPING,
                RoomKind.HALL,
                SWEEP_KEYS,
                4,
                8f,
                false,
                .4f,
                emptyList(),
                BROOM,
                alsoIn = listOf(RoomKind.KITCHEN),
            ),
            ActivitySpec(
                Activity.PHONE_CALL,
                RoomKind.HALL,
                PHONE_KEYS,
                4,
                1.6f,
                true,
                .3f,
                listOf(glow(2.5f, -20f, 5f, COOL, .35f)),
                PHONE,
                alsoIn = listOf(RoomKind.OFFICE, RoomKind.LIVING, RoomKind.BOARD, RoomKind.BEDROOM),
            ),
            ActivitySpec(
                Activity.DANCING,
                RoomKind.LIVING,
                DANCE_KEYS,
                6,
                10f,
                false,
                1f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.MUSIC),
            ),
            ActivitySpec(
                Activity.MAKING_BED,
                RoomKind.BEDROOM,
                BED_KEYS,
                4,
                5f,
                true,
                .1f,
                emptyList(),
                SHEET,
            ),
            ActivitySpec(
                Activity.PLANNING,
                RoomKind.BOARD,
                PLAN_KEYS,
                4,
                5f,
                false,
                0f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.OFFICE),
            ),
            ActivitySpec(
                Activity.WATERING,
                RoomKind.PLANTS,
                WATER_KEYS,
                4,
                1.4f,
                true,
                0f,
                emptyList(),
                CAN,
            ),
            ActivitySpec(
                Activity.GUITAR,
                RoomKind.MUSIC,
                GUITAR_KEYS,
                4,
                8f,
                false,
                .2f,
                emptyList(),
                GUITAR,
                alsoIn = listOf(RoomKind.LIVING, RoomKind.BEDROOM),
            ),
            ActivitySpec(
                Activity.PIANO,
                RoomKind.PIANO,
                PIANO_KEYS,
                4,
                6f,
                false,
                .2f,
                listOf(glow(9f, -16f, 8f, WARM, .3f)),
                null,
            ),
            ActivitySpec(
                Activity.WALKING,
                RoomKind.HALL,
                Gait.WALK_KEYS,
                Gait.FRAMES,
                Gait.FRAMES / Gait.CYCLE,
                false,
                .6f,
                emptyList(),
                null,
                extra = walkExtra(Gait.NEUTRAL),
                smooth = true,
                // every look walks in its own style: its own frames
                lookKeys = Gait.LOOK_WALK_KEYS,
                lookExtra = List(Looks.COUNT) { walkExtra(Gait.style(it)) },
                alsoIn = listOf(RoomKind.OFFICE, RoomKind.LIVING, RoomKind.BOARD),
            ),
            ActivitySpec(
                Activity.LOOKING_OUT,
                RoomKind.HALL,
                LOOK_KEYS,
                4,
                .8f,
                true,
                0f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.LIVING, RoomKind.BEDROOM, RoomKind.OFFICE, RoomKind.STUDY),
            ),
            ActivitySpec(
                Activity.WAVING,
                RoomKind.HALL,
                WAVE_KEYS,
                4,
                8f,
                false,
                .1f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.OFFICE, RoomKind.KITCHEN),
            ),
            ActivitySpec(
                Activity.TV_WATCHING,
                RoomKind.LIVING,
                TV_KEYS,
                4,
                .7f,
                true,
                0f,
                listOf(glow(7.6f, -17f, 9f, COOL, .5f)),
                REMOTE,
            ),
            ActivitySpec(
                Activity.TREE_POSE,
                RoomKind.YOGA,
                TREE_KEYS,
                4,
                .5f,
                true,
                0f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.LIVING, RoomKind.BEDROOM),
            ),
            ActivitySpec(
                Activity.CHILD_PLAYING,
                RoomKind.LIVING,
                CHILD_KEYS,
                4,
                1.6f,
                true,
                0f,
                emptyList(),
                BLOCKS,
                alsoIn = listOf(RoomKind.BEDROOM),
                child = true,
            ),
            ActivitySpec(
                Activity.TALKING,
                RoomKind.DINING,
                TALK_KEYS,
                4,
                1.3f,
                true,
                .1f,
                emptyList(),
                null,
                alsoIn = listOf(RoomKind.BOARD),
                pair = true,
            ),
        )

    fun of(a: Activity): ActivitySpec = ALL[a.ordinal]

    /**
     * The walk's extra frames: the turn from the passing pose (in [style]) round to standing, then
     * the look at the watch.
     */
    private fun walkExtra(style: Gait.Style): List<FloatArray> =
        Gait.between(Gait.walkPose(Gait.PASSING, style), LOOK_KEYS[0], Gait.TURN_FRAMES) +
            Gait.between(LOOK_KEYS[0], WATCH, Gait.WATCH_FRAMES)

    /** The shapes of [look] doing [a], frame [frame]. */
    fun figure(look: Int, a: Activity, frame: Int): FigBuilder {
        val spec = of(a)
        val phase = (frame % spec.frames) / spec.frames.toFloat()
        var swing = spec.energy * 16f * sin(phase * 6.2832f * 2f + 1f)
        if (frame >= spec.frames) {
            // the extra frames (a turn) ease from the passing frame's swing to none
            val s = (frame - spec.frames + 1f) / spec.extra.size
            swing =
                spec.energy *
                    16f *
                    sin(Gait.PASSING * 6.2832f * 2f + 1f) *
                    (1f - s * s * (3f - 2f * s))
        }
        return FigBuilder(lookOf(spec, look), spec.poseAt(frame, look), swing)
            .build(spec.props, phase)
    }

    /** The look [look] takes doing [spec]: a child's for a child's activity. */
    private fun lookOf(spec: ActivitySpec, look: Int): Look =
        if (spec.child) Looks.CHILDREN[look % Looks.CHILDREN.size] else Looks.ALL[look]

    /**
     * Which look number is baked for [look] doing [a]: a child's activity has only the few child
     * looks, so the same frames serve several of the grown-up look numbers.
     */
    fun lookFor(a: Activity, look: Int): Int = if (of(a).child) look % Looks.CHILDREN.size else look

    /** How many distinct looks [a] is baked in. */
    fun looksOf(a: Activity): Int = if (of(a).child) Looks.CHILDREN.size else Looks.COUNT

    /** The activities whose natural room is [room]. */
    fun inRoom(room: RoomKind): List<Activity> = Activity.entries.filter { of(it).room == room }

    /** The activities that can be seen in [room], the ones at home there first. */
    fun seenIn(room: RoomKind): List<Activity> =
        inRoom(room) + Activity.entries.filter { it !in inRoom(room) && room in of(it).rooms }

    /** The rooms [a] can be seen in, its natural one first. */
    fun roomsOf(a: Activity): List<RoomKind> = of(a).rooms

    private val fits = FloatArray(Activity.entries.size) { Float.NaN }

    /**
     * How far a room's people and furniture are moved sideways to sit in the middle of a window:
     * part of each room's design (its furniture is drawn at the same shift), so that the people of
     * the room's activities stand where its furniture expects them.
     */
    fun shift(room: RoomKind): Float =
        when (room) {
            RoomKind.OFFICE -> -1.5f
            RoomKind.DESK -> -.8f
            RoomKind.STUDY -> 1f
            RoomKind.GYM -> -.4f
            RoomKind.YOGA -> -1f
            RoomKind.KITCHEN -> -4.1f
            RoomKind.BATH -> -5.3f
            RoomKind.BEDROOM -> -3.2f
            RoomKind.LIVING -> -.5f
            RoomKind.MUSIC -> -1.6f
            RoomKind.PIANO -> .2f
            RoomKind.PLANTS -> -5.2f
            RoomKind.BOARD -> -.9f
            RoomKind.HALL -> -1.3f
            RoomKind.DINING -> 0f
        }

    /** [fit] for a window 15.5 wide (the window's width less a margin), made once. */
    @Synchronized
    fun fitted(a: Activity): Float {
        if (!fits[a.ordinal].isNaN()) return fits[a.ordinal]
        return fit(a, 15.5f).also { fits[a.ordinal] = it }
    }

    /**
     * The widest a room's people and furniture spread, in units: what [roomFit] squeezes into a
     * window.
     */
    private fun extent(room: RoomKind): Float =
        when (room) {
            RoomKind.OFFICE,
            RoomKind.DESK -> 19f
            RoomKind.PIANO,
            RoomKind.MUSIC,
            RoomKind.KITCHEN,
            RoomKind.BATH -> 18f
            RoomKind.BOARD,
            RoomKind.PLANTS,
            RoomKind.LIVING,
            RoomKind.BEDROOM -> 17f
            else -> 16f
        }

    /**
     * How much a room's contents are drawn smaller so that they sit in a window 17 wide (1 when
     * they do).
     */
    fun roomFit(room: RoomKind): Float = minOf(1f, 16.6f / extent(room))

    /**
     * How much [a]'s figure is drawn smaller to fit across a window [width] wide (1 when it fits).
     * A pair's two figures, mirrored about the room's middle, both have to fit.
     */
    fun fit(a: Activity, width: Float): Float {
        val r = reach(a)
        val span = if (of(a).pair) 2f * maxOf(-r[0], r[2]) else r[2] - r[0]
        return minOf(1f, width / span)
    }

    /**
     * The union of the shapes' bounds over every look and frame of [a], in units: x0, y0, x1, y1.
     */
    private val reaches = arrayOfNulls<FloatArray>(Activity.entries.size)

    @Synchronized
    fun reach(a: Activity): FloatArray =
        reaches[a.ordinal] ?: computeReach(a).also { reaches[a.ordinal] = it }

    private fun computeReach(a: Activity): FloatArray {
        val spec = of(a)
        var x0 = 1e9f
        var y0 = 1e9f
        var x1 = -1e9f
        var y1 = -1e9f
        for (l in 0 until looksOf(a)) for (f in 0 until spec.totalFrames) {
            val b = FigBuilder.bounds(figure(l, a, f).shapes())
            x0 = minOf(x0, b[0])
            y0 = minOf(y0, b[1])
            x1 = maxOf(x1, b[2])
            y1 = maxOf(y1, b[3])
        }
        return floatArrayOf(x0, y0, x1, y1)
    }
}
