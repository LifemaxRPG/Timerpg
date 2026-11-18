package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene.StageCamera
import com.lifemax.rpg.feature.habits.presentation.scene.TurnTuning
import kotlin.math.abs
import kotlin.math.atan

/**
 * The one true camera Filament draws the whole crossroads through: a pinhole at a real height, looking level (never
 * pitched, so walls stay upright), with a shifted lens that puts the street where the classic puts it on screen. Every
 * thing (both streets, their ground, the cards) goes through this same projection, so all the ground is one level plane
 * seen one way: nothing dives or climbs except as true perspective makes it.
 *
 * It follows the stage's own camera ([StageCamera], aimed by the game: the point looked at along the street, the corner
 * turn's yaw): it stands [back] street units behind that point, [eye] units up, looking the same way, and its lens
 * makes one street unit at that point's depth `unit` pixels, as the classic's street. The lens is shifted so the ground
 * at that depth (the fronts' foot at rest) lands on the stage's ground line; the horizon is then [eye] units above it
 * on screen, wherever that falls.
 *
 * Physics decides the rest: an eye above the roofs sees them, but then the horizon is high and the side street climbs
 * the screen towards it; an eye at a person's height keeps the horizon low and the side street level-looking, but the
 * roofs are hidden and the ground in front is short. [Preset] holds three such cameras. A nearer camera ([back] smaller)
 * shows more of the road in front of the fronts at the cost of a wider lens.
 *
 * Through the corner turn it backs off to where the stage's own camera stands ([TurnTuning.R]), so the block it swings
 * round fades by the stage's cut exactly as it passes (the cut is measured from there), and it never stands inside that
 * block; and it slides sideways when the player would leave the frame.
 */
object CameraRig {
    /** The three cameras the owner can pick between: eye height and how far back from the street (street units; a person is 36). */
    enum class Preset(val eye: Float, val back: Float) {
        /** A person's eye across the road: low horizon, level side street, roofs hidden. */
        LOW(36f, 420f),
        /** About a first floor up. */
        MEDIUM(80f, 420f),
        /** Level with the eaves, further back: more of the roofs and billboards, a higher horizon, the side street climbing to it. */
        HIGH(150f, 700f),
    }

    /** The eye's height above the ground (street units). */
    @JvmStatic var eye = Preset.MEDIUM.eye
    /** How far behind the point it looks at the camera stands (street units). */
    @JvmStatic var back = Preset.MEDIUM.back

    /** Draw through the classic's per-thing leaned looks ([LookMatrix.of]) instead (-Dlook=classic, for comparison). */
    @JvmStatic val classic = System.getProperty("look") == "classic"

    /** The reversed depth reaches 1 at this depth (street units): nothing nearer is drawn. */
    const val NEAR = 4f
    /** Over how many degrees at either end of the turn it eases between its own distance and the stage camera's. */
    const val EASE = 18f
    /** The player is kept at least this share of the screen's width in from either edge. */
    const val MARGIN = .14f

    /** The camera this frame (a plain pinhole, its matrix [StageCamera.m]); set by [aim]. */
    val view = StageCamera()

    init {
        pick(System.getProperty("eye"))
        System.getProperty("back")?.toFloatOrNull()?.let { back = it }
    }

    /** Picks a preset by name (low, medium, high) or an eye height in street units (the distance back kept as is). */
    fun pick(name: String?) {
        if (name == null) return
        val p = Preset.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (p != null) { eye = p.eye; back = p.back; return }
        name.toFloatOrNull()?.let { eye = it }
    }

    /** The current preset's name, or the eye height when it is none of them. */
    val label: String get() = Preset.values().firstOrNull { it.eye == eye && it.back == back }?.name?.lowercase() ?: "eye%.0f".format(eye)

    /** How far back it stands this frame: [back] at rest, the stage camera's own distance mid-turn (street units). */
    var backNow = back; private set

    /** How much of the way round the corner it is, as a weight: 0 at rest on either street, 1 from [EASE] degrees in to as far from the end. */
    fun turning(yaw: Float): Float {
        val deg = Math.toDegrees(abs(yaw).toDouble()).toFloat()
        val t = (kotlin.math.min(deg, 90f - deg) / EASE).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Aims [view] for this frame from the stage's camera [stage] (updated), the stage's ground line at [groundY] (screen),
     * on a screen [width] pixels wide (0: no keeping the player in frame).
     */
    fun aim(stage: StageCamera, groundY: Float, width: Float = 0f) {
        backNow = back + (kotlin.math.max(back, TurnTuning.R) - back) * turning(stage.yaw)
        view.cx = stage.cx
        view.unit = stage.unit
        view.r = backNow
        view.eye = eye
        view.cy = groundY - eye * stage.unit
        view.yaw = stage.yaw
        view.tx = stage.tx
        view.tz = stage.tz
        view.cutBack = 0f
        view.keepBack = 0f
        view.update()
        // the player (the stage keeps its cut off them while they walk round the corner) stays inside the frame: the
        // camera slides across its view (no turning, no zoom) just far enough
        if (width > 0f && stage.keepBack > 0f) {
            val d = view.depth(stage.keepX, stage.keepZ)
            if (d > NEAR) {
                val sx = view.cx + view.f * (view.rx * (stage.keepX - view.camX) + view.rz * (stage.keepZ - view.camZ)) / d
                val lo = width * MARGIN; val hi = width * (1f - MARGIN)
                val want = sx.coerceIn(lo, hi)
                if (want != sx) {
                    val s = (sx - want) * d / view.f
                    view.tx += view.rx * s; view.tz += view.rz * s
                    view.update()
                }
            }
        }
    }

    /** The lens's field of view across a screen [width] pixels wide (degrees). */
    fun fovX(width: Float): Float = Math.toDegrees(2.0 * atan((width / 2f / view.f).toDouble())).toFloat()
}
