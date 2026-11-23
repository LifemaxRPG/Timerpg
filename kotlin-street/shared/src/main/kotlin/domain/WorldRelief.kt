package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The ground's relief (WORLD_GEN.md W5a), the cross-language part of the near terrain ([WorldTerrain]): src/world/gen/near.ts
 * computes the same doubles (tools/world-fixture.ts writes samples for the test). From the skeleton's bilinear cell
 * heights ([WorldSkeleton.height]) and the integer-hashed noise: W4's detail, gentle rolling hills everywhere, rocky
 * outcrops where a slow roughness noise is high, ridged mountains on high ground, all fading out at the shore ([smooth]);
 * then, where that is steep, terraced into ledges and risers ([raw]: the owner's smooth ground with hard cliffs). The far
 * view's finest level samples it too, so the near terrain and the far view are one ground where they meet.
 */
class WorldRelief(val sk: WorldSkeleton) {
    private val seed = sk.seed
    private val detail = WorldFbm(seed, "w4-detail", WorldSkeleton.W / 2, 3)
    private val hills = WorldFbm(seed, "w5-hills", WorldSkeleton.W * 4, 4)
    private val ridge = WorldFbm(seed, "w5-ridge", WorldSkeleton.W * 2, 3)
    private val rough = WorldFbm(seed, "w5-rough", WorldSkeleton.W / 2, 2)
    private val steps = WorldLattice(seed, "w5-step", WorldSkeleton.W * 8)

    /**
     * The ground's height (m above sea level) before cliffs, at ([cx], [cz]) in atlas cell units. Only +, -, *, / and
     * the noise (which is the TypeScript's bit for bit), so near.ts gives the same doubles.
     */
    fun smooth(cx: Double, cz: Double): Double {
        val base = sk.height(cx, cz)
        val coast = smoothstep(sat((base - 1.0) / 24.0))
        val w4 = (detail.at(cx, cz) - .5) * 36.0 * coast
        val ro = rough.at(cx, cz)
        val r = sat((ro - .4) / .3)
        // (outcrops: where the roughness is highest, ridged knolls and bluffs tall enough to break into cliffs)
        val crop = smoothstep(sat((ro - .62) / .12))
        val mount = smoothstep(sat((base - 450.0) / 900.0))
        val hil = (hills.at(cx, cz) - .5) * (10.0 + 26.0 * r)
        val rv = ridge.at(cx, cz)
        val ra = 2.0 * rv - 1.0
        // (a ridge's crest rounded over a few metres, so the slope, and the cliffs it makes, do not jump there)
        var rr = 1.0 - sqrt(ra * ra + .003)
        rr *= rr
        val peaks = rr * (320.0 * mount + 160.0 * crop)
        return base + w4 + (hil + peaks) * coast
    }

    /** How much of a cliff ([raw]'s terracing) is at ([cx], [cz]): 0 on ground under [WorldTerrain.CLIFF_FROM]'s slope, 1 past [WorldTerrain.CLIFF_FULL]. */
    fun cliff(cx: Double, cz: Double): Double = cliffOf(slope(cx, cz))

    /** [smooth]'s slope (rise over run) at ([cx], [cz]), by central differences [WorldTerrain.SLOPE_E_M] either side. */
    fun slope(cx: Double, cz: Double): Double {
        val e = WorldTerrain.SLOPE_E_M / Planet.CELL_M
        val dx = (smooth(cx + e, cz) - smooth(cx - e, cz)) / (2.0 * WorldTerrain.SLOPE_E_M)
        val dz = (smooth(cx, cz + e) - smooth(cx, cz - e)) / (2.0 * WorldTerrain.SLOPE_E_M)
        return sqrt(dx * dx + dz * dz)
    }

    fun cliffOf(s: Double): Double = smoothstep(sat((s - WorldTerrain.CLIFF_FROM) / (WorldTerrain.CLIFF_FULL - WorldTerrain.CLIFF_FROM)))

    /**
     * The ground's height (m above sea level) at ([cx], [cz]) with its cliffs: where steep, terraced into ledges (a
     * gentle tread) and risers (the rest of the rise, much steeper) [WorldTerrain.STEP_M] tall, more of it the steeper ([cliff]).
     */
    fun raw(cx: Double, cz: Double): Double {
        val h0 = smooth(cx, cz)
        val c = cliffOf(slope(cx, cz))
        if (c <= 0.0) return h0
        return h0 + (terrace(h0, cx, cz) - h0) * c
    }

    fun terrace(h0: Double, cx: Double, cz: Double): Double {
        val step = WorldTerrain.STEP_M + WorldTerrain.STEP_SWING_M * steps.at(cx, cz)
        val t = h0 / step
        val k = floor(t)
        val f = t - k
        val g = if (f < WorldTerrain.TREAD) f * WorldTerrain.TREAD_RISE / WorldTerrain.TREAD else WorldTerrain.TREAD_RISE + (f - WorldTerrain.TREAD) * (1.0 - WorldTerrain.TREAD_RISE) / (1.0 - WorldTerrain.TREAD)
        return (k + g) * step
    }

    private fun sat(t: Double): Double = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
}
