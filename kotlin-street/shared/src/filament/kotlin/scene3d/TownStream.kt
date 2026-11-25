package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The town's streaming (docs/STREAMING.md): what is resident in full is what is near the camera, the player and where the
 * player will be in a few seconds; everything else exists only as its far form ([TownFar]'s boxes, the yards' far
 * versions), so the town's GPU memory follows the area round the player, not the capital's size (GTA's HD/LOD split,
 * Zelda's streaming ahead of the player).
 *
 * This holds the points residency is measured from and the radii, the same for every tier: the eye and the player as last
 * seen, and both moved on by the player's velocity times [LEAD_SECONDS] (riding or driving faster loads further ahead:
 * TotK's prediction; with no fast travel all movement is predictable). Something comes in when it is within its tier's
 * need plus [margin] of any point, and goes once past it by [HYSTERESIS] from all of them (no load and unload on a line).
 * The speed is eased ([SPEED_EASE]) so a stop does not drop what is ahead at once.
 *
 * Engine thread only.
 */
internal class TownStream {
    /** The player's last position and clock, the velocity eased (street units a second). */
    private var px = Float.NaN; private var pz = 0f; private var pt = Double.NaN
    var vx = 0f; private set
    var vz = 0f; private set
    /** The points this frame: eye, player, eye ahead, player ahead (x, z each). */
    val pts = FloatArray(8)
    var n = 0; private set
    /** The extra reach for speed this frame (street units). */
    var ahead = 0f; private set

    /** Where things are measured from this frame: the player at ([x], [z]) at clock [seconds], the eye ([eye], or none yet). */
    fun focus(x: Float, z: Float, seconds: Double, eye: TownPose?) {
        if (!px.isNaN() && !pt.isNaN() && seconds > pt) {
            val dt = (seconds - pt).toFloat()
            if (dt < 1f) {
                val k = 1f - kotlin.math.exp(-dt / SPEED_EASE)
                val ix = (x - px) / dt; val iz = (z - pz) / dt
                // (a jump, a teleport of a preview: not a speed)
                if (hypot(ix, iz) < MAX_SPEED) { vx += (ix - vx) * k; vz += (iz - vz) * k }
            }
        }
        if (seconds != pt) { px = x; pz = z; pt = seconds }
        val lx = vx * LEAD_SECONDS; val lz = vz * LEAD_SECONDS
        ahead = hypot(lx, lz)
        n = 0
        fun put(a: Float, b: Float) { pts[n * 2] = a; pts[n * 2 + 1] = b; n++ }
        put(x, z)
        if (eye != null) put(eye.ex, eye.ez)
        if (ahead > 20f) {
            put(x + lx, z + lz)
            if (eye != null) put(eye.ex + lx, eye.ez + lz)
        }
    }

    /** The nearest the rectangle ([x0], [z0])-([x1], [z1]) comes to any point. */
    fun rect(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            val x = pts[i * 2]; val z = pts[i * 2 + 1]
            val dx = max(0f, max(x0 - x, x - x1)); val dz = max(0f, max(z0 - z, z - z1))
            best = min(best, sqrt(dx * dx + dz * dz))
        }
        return best
    }

    /** The nearest a circle at ([x], [z]) of radius [r] comes to any point. */
    fun circle(x: Float, z: Float, r: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until n) best = min(best, hypot(pts[i * 2] - x, pts[i * 2 + 1] - z) - r)
        return max(0f, best)
    }

    /** The margin past a tier's need something comes in at (more with speed: what is ahead loads further out). */
    val margin: Float get() = MARGIN + ahead * .5f

    /** Whether something at [d] from the points with its tier needing [need] should be resident ([was]: it is now). */
    fun wants(d: Float, need: Float, was: Boolean): Boolean {
        if (need == Float.MAX_VALUE) return true
        val inR = need + margin
        return if (was) d < inR + HYSTERESIS else d < inR
    }

    // ------------------------------------------------------------------ counts for the reports and the route test

    var buildingsIn = 0; var buildingsOut = 0; var yardsIn = 0; var yardsOut = 0; var chunksOut = 0
    /** The worst time a frame spent letting things go and bringing yards in (ms), and frames that did any. */
    var worstMs = 0f; var busyFrames = 0
    /** Of which the worst swap of yards coming back in (ms). */
    var worstYardUpMs = 0f
    /** The worst single building let go, lot let go, and lot's upload coming in (ms): what one item costs. */
    var worstUnbuildMs = 0f; var worstLotOutMs = 0f; var worstLotInMs = 0f
    /** The worst time of each section of a stream step (ms): what is wanted, buildings out, lots out, lots in. */
    val worstSection = FloatArray(4)
    fun section(i: Int, t0: Long): Long { val n = System.nanoTime(); worstSection[i] = kotlin.math.max(worstSection[i], (n - t0) / 1e6f); return n }

    fun report(): String = "streaming: buildings in %d / out %d, yards in %d / out %d, chunks' 3D greenery out %d, worst stream step %.1f ms (yards' swap-in %.1f; one building out %.1f, one lot out %.1f, one lot in %.1f) over %d frames (sections: wanted %.1f, buildings out %.1f, lots out %.1f, lots in %.1f), speed %.0f u/s (ahead %.0f); destroyed later %d (at most %d waiting)".format(
        java.util.Locale.US, buildingsIn, buildingsOut, yardsIn, yardsOut, chunksOut, worstMs, worstYardUpMs, worstUnbuildMs, worstLotOutMs, worstLotInMs, busyFrames, worstSection[0], worstSection[1], worstSection[2], worstSection[3], hypot(vx, vz), ahead, Graveyard.buried, Graveyard.peak)

    companion object {
        /** (Debugging: -DstreamOff=b,y,c leaves the buildings', yards' or chunks' greenery's letting go out.) */
        val OFF: String = System.getProperty("streamOff") ?: ""
        /** How far ahead the player's movement is projected (s): a block's buildings take a second or two to come in. */
        const val LEAD_SECONDS = 4f
        /** The speed's easing (s). */
        const val SPEED_EASE = .6f
        /** Faster than this (street units a second) is a jump, not movement. */
        const val MAX_SPEED = 3000f
        /** Something comes in this far (street units) before its tier needs it: a building takes a few frames to come in. */
        const val MARGIN = 300f
        /** And goes only this much further out (no load and unload on a line). */
        const val HYSTERESIS = 500f
        /** How long a frame may spend letting things go (ms) beyond the first. */
        const val EVICT_MS = 2f
        /** How many yards may be baked again at once for coming in (each a lot's near version, on the workers). */
        const val YARDS_IN_FLIGHT = 6
        /** How long a frame may spend uploading yards coming in (ms) beyond the first batch (a lot's batches over frames). */
        const val YARD_UPLOAD_MS = 3f
        /** The stream step's whole budget a frame (ms): letting go and yards coming in together, beyond the first item. */
        const val STREAM_MS = 4f
        /** How long a frame may spend destroying what streaming let go of ([Graveyard], ms) beyond the first item. */
        const val GRAVE_MS = 1.5f
    }
}
