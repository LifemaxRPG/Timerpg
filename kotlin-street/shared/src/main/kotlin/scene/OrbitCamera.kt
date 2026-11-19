package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The open world's camera (the owner's decision: the player turns the camera, nothing else does). It orbits the player:
 * the point it looks at is the player's feet plus [EYE] up, it stands [dist] street units from that point, turned
 * [yaw] degrees (0 looking north, clockwise, as the walk's heading) and looking [pitch] degrees down.
 *
 * Only the player moves it: a one-finger drag turns it ([drag]), a pinch zooms it ([pinch]), a double tap or the
 * recentre button puts it behind the player ([recentre]). Each of those sets a goal; [step] eases the camera to its goal
 * (the player's own input smoothed, nothing more: it never turns by itself, at corners or anywhere). It follows the
 * player's position exactly ([follow]).
 *
 * Pure state and arithmetic (no drawing, no platform), so the walk, the renderer, the stage's sky and the tests share it.
 * The renderer turns it into its camera (scene3d TownFrame.orbit) and pulls it in when a wall stands between it and the
 * player ([pulled]: how far it really stands this frame, at most [dist]).
 */
object OrbitCamera {
    /** The open world (true, the default) or the old street view (false): the AI panel's "View" switch, kept in prefs by the app. */
    @Volatile var open = true

    /** The point looked at: this far above the player's feet (street units; a person is 36, their chest about 26). */
    const val EYE = 30f
    /** How far down it may look (degrees). */
    const val PITCH_MIN = 10f
    const val PITCH_MAX = 60f
    /** How near and far it may stand from the player (street units). */
    const val DIST_MIN = 140f
    const val DIST_MAX = 1400f
    /** Where it starts: a third of the way up, a few houses' width back. */
    const val PITCH0 = 24f
    const val DIST0 = 420f
    /** Degrees turned per screen width dragged across (a full swipe is about a half turn), and down per screen height. */
    const val YAW_PER_WIDTH = 200f
    const val PITCH_PER_HEIGHT = 90f
    /** How quickly it eases to the goal (seconds to cover most of the way: the player's input smoothed, not lagged). */
    const val EASE = .07f
    /** Its vertical field of view (degrees). */
    const val FOV_Y = 50f

    /** The camera now (eased), and the goal the player's input set. */
    @Volatile var yaw = 0f; private set
    @Volatile var pitch = PITCH0; private set
    @Volatile var dist = DIST0; private set
    @Volatile var goalYaw = 0f; private set
    @Volatile var goalPitch = PITCH0; private set
    @Volatile var goalDist = DIST0; private set

    /** It is still easing to the player's last input (the stage keeps frames coming while it is). */
    @Volatile var moving = false; private set

    /** The point it orbits (the player's feet, world x, z), as last followed. */
    @Volatile var tx = 0f; private set
    @Volatile var tz = 0f; private set
    /** How far it stands this frame after the renderer pulled it in front of a wall (== [dist] when nothing was in the way). */
    @Volatile var pulled = DIST0

    /**
     * What the renderer last drew, for the stage's sky under it: the lens (pixels per unit of tangent) and where the
     * horizon fell on the screen (pixels from the top; may be above it). Set by the renderer each frame.
     */
    @Volatile var lensPx = 0f
    @Volatile var horizonY = Float.NaN

    /** Places it behind a player at ([x], [z]) heading [headingDeg] (0 north, clockwise), at the start distance and pitch, at once. */
    fun reset(x: Float, z: Float, headingDeg: Float) {
        tx = x; tz = z
        goalYaw = headingDeg; yaw = headingDeg
        goalPitch = PITCH0; pitch = PITCH0
        goalDist = DIST0; dist = DIST0; pulled = DIST0
    }

    /** Sets the camera outright (a preview, a restored snapshot): no easing. */
    fun set(yawDeg: Float, pitchDeg: Float, distance: Float) {
        goalYaw = yawDeg; yaw = yawDeg
        goalPitch = pitchDeg.coerceIn(PITCH_MIN, PITCH_MAX); pitch = goalPitch
        goalDist = distance.coerceIn(DIST_MIN, DIST_MAX); dist = goalDist; pulled = dist
    }

    /** The player is at ([x], [z]): it follows (exactly, without turning). */
    fun follow(x: Float, z: Float) { tx = x; tz = z }

    /** A drag of ([dx], [dy]) pixels on a [w] x [h] screen: right turns the camera right (the world swings left), down looks further down. */
    fun drag(dx: Float, dy: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        goalYaw += dx / w * YAW_PER_WIDTH
        goalPitch = (goalPitch + dy / h * PITCH_PER_HEIGHT).coerceIn(PITCH_MIN, PITCH_MAX)
        moving = true
    }

    /** A pinch by [scale] (fingers apart: > 1, nearer). */
    fun pinch(scale: Float) {
        if (scale <= 0f || scale.isNaN()) return
        goalDist = (goalDist / scale).coerceIn(DIST_MIN, DIST_MAX)
        moving = true
    }

    /** Behind a player heading [headingDeg]: the shortest way round (eased, as the player's own input). */
    fun recentre(headingDeg: Float) { goalYaw = yaw + wrap(headingDeg - yaw); moving = true }

    /** Eases the camera [dt] seconds towards the goal the player set. Returns true while it still moves. */
    fun step(dt: Float): Boolean {
        val k = 1f - exp(-dt.coerceAtLeast(0f) / EASE)
        val dy = goalYaw - yaw; val dp = goalPitch - pitch; val dd = goalDist - dist
        val m = abs(dy) > .01f || abs(dp) > .01f || abs(dd) > .05f
        moving = m
        if (!m) { yaw = goalYaw; pitch = goalPitch; dist = goalDist } else { yaw += dy * k; pitch += dp * k; dist += dd * k }
        // (kept in -180..180 together, so they never drift apart)
        if (abs(yaw) > 720f) { val s = 360f * (yaw / 360f).toInt(); yaw -= s; goalYaw -= s }
        return m
    }

    /** The looking direction on the ground (unit x, z; north is -z), and the camera's right. */
    val fx: Float get() = sin(Math.toRadians(yaw.toDouble())).toFloat()
    val fz: Float get() = -cos(Math.toRadians(yaw.toDouble())).toFloat()
    val rx: Float get() = -fz
    val rz: Float get() = fx

    /**
     * The stick's push (x right, lane down: [GameState.moveX], [GameState.moveLane]) as a direction on the ground relative
     * to the camera: up the screen walks away from it, right walks to its right. Returns (x, z), as long as the push.
     */
    fun worldMove(moveX: Float, moveLane: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val fwd = -moveLane
        out[0] = fx * fwd + rx * moveX
        out[1] = fz * fwd + rz * moveX
        return out
    }

    /** The heading (degrees, 0 north, clockwise) of a direction on the ground. */
    fun headingOf(x: Float, z: Float): Float = Math.toDegrees(atan2(x.toDouble(), (-z).toDouble())).toFloat()

    /** [deg] in -180..180. */
    fun wrap(deg: Float): Float { var d = deg % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }

    /** The yaw in radians (for the sky's turn). */
    val yawRad: Float get() = (yaw * PI / 180.0).toFloat()

    /**
     * The sun's (or with [moon], the moon's) azimuth at [hour] in the open world (degrees, 0 north, clockwise: 90 east at
     * six, 180 south at noon, 270 west at six). The town's light comes from it, and the stage draws the orb there.
     */
    fun sunAzimuth(hour: Float, moon: Boolean): Float = 90f + ((if (moon) hour - 18f else hour - 6f).mod(24f)) * 15f

    /**
     * Where the sun (by day) or the moon (by night) stands on a [w] x [h] screen through the camera as last drawn (its
     * azimuth [sunAzimuth], its height the street's key light's): (x, y) into [out], false when it is behind the camera.
     * The stage draws its orb there, so the orb is where the town's light comes from.
     */
    fun orbAt(hour: Float, w: Float, h: Float, out: FloatArray): Boolean {
        val f = lensPx.takeIf { it > 0f } ?: return false
        val key = com.lifemax.rpg.feature.habits.domain.SceneLight.key(hour)
        val az = Math.toRadians(sunAzimuth(hour, key.cool > .5f).toDouble())
        val el = kotlin.math.asin(key.down.coerceIn(-1f, 1f).toDouble())
        val dx = (sin(az) * cos(el)).toFloat(); val dy = sin(el).toFloat(); val dz = (-cos(az) * cos(el)).toFloat()
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val lx = sin(ya).toFloat() * cp; val ly = -sp; val lz = -cos(ya).toFloat() * cp
        val rx = cos(ya).toFloat(); val rz = sin(ya).toFloat()
        // up = right x look
        val ux = -rz * ly; val uy = rz * lx - rx * lz; val uz = rx * ly
        val zc = dx * lx + dy * ly + dz * lz
        if (zc < .05f) return false
        out[0] = w / 2f + f * (dx * rx + dz * rz) / zc
        out[1] = h / 2f - f * (dx * ux + dy * uy + dz * uz) / zc
        return true
    }

    /** One line for screenshots and crash snapshots. */
    fun describe(): String = "camera %s yaw %.1f pitch %.1f dist %.0f (pulled to %.0f) at (%.0f, %.0f)".format(
        java.util.Locale.US, if (open) "open" else "street", wrap(yaw), pitch, dist, pulled, tx, tz)
}
