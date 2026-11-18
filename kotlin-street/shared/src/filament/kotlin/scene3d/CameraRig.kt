package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene.StageCamera
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
 */
object CameraRig {
    /** The three cameras the owner can pick between: eye height and how far back from the street (street units; a person is 36). */
    enum class Preset(val eye: Float, val back: Float) {
        /** A person's eye across the road: low horizon, level side street, roofs hidden. */
        LOW(36f, 300f),
        /** About a first floor up. */
        MEDIUM(80f, 420f),
        /** Above the roofs: roofs and billboards seen from above, a high horizon. */
        HIGH(300f, 900f),
    }

    /** The eye's height above the ground (street units). */
    @JvmStatic var eye = Preset.MEDIUM.eye
    /** How far behind the point it looks at the camera stands (street units). */
    @JvmStatic var back = Preset.MEDIUM.back

    /** Draw through the classic's per-thing leaned looks ([LookMatrix.of]) instead (-Dlook=classic, for comparison). */
    @JvmStatic var classic = System.getProperty("look") == "classic"

    /** The reversed depth reaches 1 at this depth (street units): nothing nearer is drawn. */
    const val NEAR = 4f

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

    /** Aims [view] for this frame from the stage's camera [stage] (updated), the stage's ground line at [groundY] (screen). */
    fun aim(stage: StageCamera, groundY: Float) {
        view.cx = stage.cx
        view.unit = stage.unit
        view.r = back
        view.eye = eye
        view.cy = groundY - eye * stage.unit
        view.yaw = stage.yaw
        view.tx = stage.tx
        view.tz = stage.tz
        view.cutBack = 0f
        view.keepBack = 0f
        view.update()
    }

    /** The lens's field of view across a screen [width] pixels wide (degrees). */
    fun fovX(width: Float): Float = Math.toDegrees(2.0 * atan((width / 2f / view.f).toDouble())).toFloat()
}
