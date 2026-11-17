package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Matrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * One camera for the crossroads ([com.lifemax.rpg.feature.habits.domain.Crossroads]): a pinhole looking along the ground
 * from [r] street units behind the point it looks at, at the height that puts the vanishing point on the stage's
 * horizon, turned by [yaw] (0 looking north, the main street's own view; a quarter turn looking west, the cross
 * street's). At the target's depth one street unit is [unit] pixels, as on the stage.
 *
 * The stage draws its own street as a cabinet view (fronts flat, side faces and roofs as parallelograms, the pavement a
 * band), which no pinhole gives. So a row of buildings, or a stretch of ground, can keep that look: [leaned] replaces
 * the camera's column for the row's depth axis with the stage's own offset per unit of depth, by a weight. At weight 1
 * and the street's own yaw the camera reproduces the stage's drawing exactly; between, it eases into plain perspective,
 * which is what the turn swings through.
 *
 * Everything is a 3x4 matrix taking world (X, Y, Z, 1) to the screen as (x w, y w, w), row-major; a plane is drawn as
 * one projective transform of its own picture ([plane]).
 */
class StageCamera {
    /** The middle of the view and the horizon (screen pixels). */
    var cx = 0f
    var cy = 0f
    /** Screen pixels per street unit at the target's depth. */
    var unit = 1f
    /** How far behind its target the camera stands (street units). */
    var r = TurnTuning.R
    /** The eye's height above the ground (street units). */
    var eye = 0f
    /** Which way it looks (radians): 0 north, PI/2 west. */
    var yaw = 0f
    /** The point on the ground it looks at (world X, Z). */
    var tx = 0f
    var tz = 0f
    /**
     * The cut: nothing nearer the camera than [cutBack] in front of world point ([cutX], [cutZ]) (the corner the camera
     * turns round) is drawn (0 for no cut). It clears the cross street's far side out of its own view, and the block
     * the camera swings behind mid-turn.
     */
    var cutBack = 0f
    var cutX = 0f
    var cutZ = 0f
    /** The cut's depth this frame (set by [update]). */
    var cutDepth = 0f; private set

    /** Its lens (pixels), its looking and right directions on the ground, and where it stands. */
    var f = 0f; private set
    var dx = 0f; private set
    var dz = 1f; private set
    var rx = 1f; private set
    var rz = 0f; private set
    var camX = 0f; private set
    var camZ = 0f; private set

    /** The plain pinhole: world to screen. */
    val m = FloatArray(12)

    fun update() {
        f = r * unit
        dx = -sin(yaw); dz = cos(yaw)
        rx = cos(yaw); rz = sin(yaw)
        camX = tx - r * dx; camZ = tz - r * dz
        val wc = -(dx * camX + dz * camZ)
        val xc = -(rx * camX + rz * camZ)
        m[8] = dx; m[9] = 0f; m[10] = dz; m[11] = wc
        m[0] = f * rx + cx * dx; m[1] = 0f; m[2] = f * rz + cx * dz; m[3] = f * xc + cx * wc
        m[4] = cy * dx; m[5] = -f; m[6] = cy * dz; m[7] = f * eye + cy * wc
        cutDepth = depth(cutX, cutZ) - cutBack
    }

    /** How deep in front of the camera world point ([x], [z]) is (street units). */
    fun depth(x: Float, z: Float): Float = dx * (x - camX) + dz * (z - camZ)

    /** True if world point ([x], [z]) is beyond the cut (the near side is left out mid-turn). */
    fun kept(x: Float, z: Float): Boolean = cutBack <= 0f || depth(x, z) >= cutDepth

    /**
     * [out] = the camera with the Z column leaned [wz] of the way to the stage's offset ([zx], [zy]) pixels per unit
     * of +Z, and the X column [wx] of the way to ([xx], [xy]) per unit of +X. The offsets are what the stage draws at
     * rest, where every point of a row's front is [r] deep.
     */
    fun leaned(out: FloatArray, wz: Float, zx: Float, zy: Float, wx: Float, xx: Float, xy: Float) {
        m.copyInto(out)
        if (wz > 0f) {
            out[2] = m[2] + (zx * r - m[2]) * wz
            out[6] = m[6] + (zy * r - m[6]) * wz
            out[10] = m[10] * (1f - wz)
        }
        if (wx > 0f) {
            out[0] = m[0] + (xx * r - m[0]) * wx
            out[4] = m[4] + (xy * r - m[4]) * wx
            out[8] = m[8] * (1f - wx)
        }
    }

    /**
     * The plane through world point ([ox], [oy], [oz]) whose picture's x runs along ([ux], [uy], [uz]) and its y along
     * ([vx], [vy], [vz]), seen through [mat]: [out] takes the picture's own coordinates to the screen.
     */
    fun plane(
        mat: FloatArray,
        ox: Float, oy: Float, oz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        out: Matrix,
    ) {
        val v = out.values
        v.fill(0f)
        v[0] = mat[0] * ux + mat[1] * uy + mat[2] * uz
        v[1] = mat[4] * ux + mat[5] * uy + mat[6] * uz
        v[3] = mat[8] * ux + mat[9] * uy + mat[10] * uz
        v[4] = mat[0] * vx + mat[1] * vy + mat[2] * vz
        v[5] = mat[4] * vx + mat[5] * vy + mat[6] * vz
        v[7] = mat[8] * vx + mat[9] * vy + mat[10] * vz
        v[12] = mat[0] * ox + mat[1] * oy + mat[2] * oz + mat[3]
        v[13] = mat[4] * ox + mat[5] * oy + mat[6] * oz + mat[7]
        v[15] = mat[8] * ox + mat[9] * oy + mat[10] * oz + mat[11]
        v[10] = 1f
    }

    /** World point ([x], [y], [z]) through [mat]: [out] = screen x, screen y, depth (w). */
    fun project(mat: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        val w = mat[8] * x + mat[9] * y + mat[10] * z + mat[11]
        val iw = if (w > 1e-4f) 1f / w else 1e4f
        out[0] = (mat[0] * x + mat[1] * y + mat[2] * z + mat[3]) * iw
        out[1] = (mat[4] * x + mat[5] * y + mat[6] * z + mat[7]) * iw
        out[2] = w
    }
}

/** The corner turn's camera and walk, tuned by eye on the turn's frames. */
object TurnTuning {
    /** How far behind its target the camera stands (street units): about 49 degrees across the preview, 69 on the phone. */
    var R = 700f
    /** Over how much of the yaw (degrees) a street's own cabinet look gives way to plain perspective, at either end. */
    var LEAN_BAND = 32f
    /** How far in front of the target the near block is cut away (street units): the road's middle. */
    var CUT = 92f
    /** Once started, the turn walks itself to the end if the key is let go (false: hold to walk, let go to pause). */
    var autoWalk = true
}
