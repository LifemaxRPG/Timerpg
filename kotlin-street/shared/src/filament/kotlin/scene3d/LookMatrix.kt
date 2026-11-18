package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene.StageCamera

/**
 * Turns one of the classic street's 3x4 screen matrices (world to (x w, y w, w) in screen pixels, row-major, as
 * [StageCamera] and CornerWorld make them) into the clip-space matrix the device-domain materials take as `look`
 * (4x4, column-major, Filament's order).
 *
 * The screen rows are the classic's exactly. The depth row is new: the classic sorted by hand, here the GPU's depth buffer
 * does it, so every thing gets a depth that is close to the plain pinhole's n/d (d its true depth along the camera's look)
 * whatever its own w is: a leaned matrix has a w that hardly changes with depth (a cabinet view), so its depth is the
 * tangent of n/d at the thing's own middle ([refX], [refY], [refZ]); at no lean it is n/d itself. Each thing is then in
 * order within itself, and two things agree to second order near either one's middle.
 *
 * [scale] and [offset] squeeze that depth into a band of the buffer (reversed: 1 nearest), the way the classic draws the
 * street in view after the rest of the crossroads and the ground under everything.
 */
object LookMatrix {
    /** The depth at which the reversed depth reaches 1 (street units). Nothing nearer is ever shown (CornerWorld.NEAR is 30). */
    const val N = 20f

    /**
     * True when drawing to a window (the phone's TextureView): there y must grow up the clip space as GL's does. The
     * desktop's headless off-screen target comes out flipped against a window, so its matrices keep y growing down (false).
     */
    @JvmStatic var windowY = false

    fun of(
        m: FloatArray, cam: StageCamera, width: Float, height: Float,
        refX: Float, refY: Float, refZ: Float, scale: Float, offset: Float, out: FloatArray,
    ) {
        // w and how it changes along the camera's look, at the thing's middle
        val w0 = m[8] * refX + m[9] * refY + m[10] * refZ + m[11]
        val wd = m[8] * cam.dx + m[10] * cam.dz
        val d0 = kotlin.math.max(cam.depth(refX, refZ), N)
        val w = kotlin.math.max(w0, 1f)
        val beta = N * (wd / d0 - w / (d0 * d0))
        val alpha = N * w / d0 - beta * d0
        // the true depth as a row: d = dx (x - camX) + dz (z - camZ)
        val dc = -(cam.dx * cam.camX + cam.dz * cam.camZ)
        // w D = scale (alpha + beta d) + offset w
        val d0r = scale * beta * cam.dx + offset * m[8]
        val d1r = offset * m[9]
        val d2r = scale * beta * cam.dz + offset * m[10]
        val d3r = scale * (alpha + beta * dc) + offset * m[11]
        val sx = 2f / width
        val sy = 2f / height
        // column-major: out[col * 4 + row]
        for (c in 0..3) {
            val mx = m[c]; val my = m[4 + c]; val mw = m[8 + c]
            out[c * 4] = sx * mx - mw
            // (Filament's GL target is flipped against GL's y-up clip space when read back; its own projections carry
            // that flip, so a device-domain matrix must too: y here grows down the screen, as the classic's)
            out[c * 4 + 1] = if (windowY) mw - sy * my else sy * my - mw
            // the device domain turns z into Filament's reversed depth as z' = 0.5 - 0.5 z (on clip z, not z / w), so
            // z = 1 - 2 w D gives z' = w D
            val wD = when (c) { 0 -> d0r; 1 -> d1r; 2 -> d2r; else -> d3r }
            out[c * 4 + 2] = (if (c == 3) 1f else 0f) - 2f * wD
            out[c * 4 + 3] = mw
        }
    }
}
