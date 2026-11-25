package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The world streamer's say over the far view (docs/WORLD_STREAM.md): it watches the eye move over the planet (its eased
 * velocity, as TownStream's lead), and tells the far view when to plan its rings again and round where. The far view's
 * levels are planned round a middle on the coarsest level's lattice ([WorldFarPlan.ANCHOR_CELLS] cells, 4.3 km), so a
 * re-plan moves no vertex: only the rings' extent follows the player. The near terrain's roots must lie wholly on the
 * finest level (8.6 km each way from the middle), so the far view is planned again once the eye, moved ahead by
 * [LEAD_S] of its velocity, is [RECENTRE_M] from the middle on either axis; the new middle is that point's lattice cell.
 *
 * Engine-agnostic and deterministic (no clock of its own: the frame's seconds), so the drive test and a unit test can
 * check it.
 */
class WorldStream {
    /** The eye's eased velocity (m/s, east and south) and where it was last seen. */
    var vx = 0.0; private set
    var vz = 0.0; private set
    private var last: WorldPos? = null
    private var lastT = 0.0

    /** Re-plans asked for so far (for reports). */
    var replans = 0; private set

    /** The eye at [eye] at the frame's [seconds]. */
    fun observe(eye: WorldPos, seconds: Double) {
        val l = last
        if (l != null) {
            val dt = seconds - lastT
            if (dt > 1e-4 && dt < 2.0) {
                val k = 1.0 - exp(-dt / EASE_S)
                vx += (l.dx(eye) / dt - vx) * k
                vz += (l.dz(eye) / dt - vz) * k
            } else if (dt >= 2.0) { vx = 0.0; vz = 0.0 }
            // (a jump: a teleport, a preview's next view; the velocity is not a jump's)
            if (abs(l.dx(eye)) > JUMP_M || abs(l.dz(eye)) > JUMP_M) { vx = 0.0; vz = 0.0 }
        }
        last = eye; lastT = seconds
    }

    /** Where the eye will be in [LEAD_S]. */
    fun lead(eye: WorldPos): WorldPos = eye.plus(vx * LEAD_S, vz * LEAD_S)

    /**
     * The middle to plan round next, or null while [middle] (the current plan's) still serves [eye]: past [RECENTRE_M]
     * on either axis from the eye, now or [LEAD_S] ahead.
     */
    fun replanFor(eye: WorldPos, middle: WorldPos): WorldPos? {
        val ahead = lead(eye)
        val off = max(max(abs(middle.dx(eye)), abs(middle.dz(eye))), max(abs(middle.dx(ahead)), abs(middle.dz(ahead))))
        if (off <= RECENTRE_M) return null
        replans++
        // the lattice cell (of the four round the way from the eye to its lead) that keeps both nearest the middle
        val a = WorldFarPlan.ANCHOR_CELLS
        val cxm = (eye.x / Planet.CELL_M) + (eye.dx(ahead) / Planet.CELL_M) / 2
        val czm = (eye.z + ahead.z) / 2 / Planet.CELL_M
        val i0 = Math.floorDiv(kotlin.math.floor(cxm - .5).toInt(), a) * a; val j0 = Math.floorDiv(kotlin.math.floor(czm - .5).toInt(), a) * a
        var best: WorldPos? = null; var bo = Double.MAX_VALUE
        for (dj in 0..1) for (di in 0..1) {
            val m = WorldPos.ofCells(i0 + di * a + .5, j0 + dj * a + .5)
            val o = max(max(abs(m.dx(eye)), abs(m.dz(eye))), max(abs(m.dx(ahead)), abs(m.dz(ahead))))
            if (o < bo) { bo = o; best = m }
        }
        return best
    }

    companion object {
        /** The velocity's easing (s), the lead (s), and how far the middle may be from the eye (m, on either axis). */
        const val EASE_S = .8
        const val LEAD_S = 12.0
        /**
         * The finest level reaches 8,601.6 m from its middle; the near terrain's roots reach [WorldTerrain.REACH_M] past the
         * eye plus a root block (2,150.4 m); 8,601.6 - 5,150.4 = 3,451 m is the most the eye may stray. Re-planned when the
         * eye or its lead is 2,800 m out, the plan has the rest (650 m: 18 s at 130 km/h) to be built and uploaded; the
         * best of the lattice's middles is at most 2,150 m plus half the lead's way from either, so a new plan never asks
         * for another at once.
         */
        const val RECENTRE_M = 2800.0
        /** A move this far (m) in one frame is a jump, not a drive. */
        const val JUMP_M = 400.0
    }
}
