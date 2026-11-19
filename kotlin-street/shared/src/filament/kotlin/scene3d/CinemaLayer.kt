package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.MaterialInstance
import kotlin.math.floor

/**
 * The movie theater in Filament: hands a [CinemaModel]'s parts to a [PropLayer] (one batch each: its tiled walls, its
 * pictures, its signs, three phases of marquee bulbs) and lights them: windows and lobby glass warm from nightfall, signs,
 * the letter boards and the poster cases from dusk in their own colours, the bulbs chasing round the marquee.
 *
 * Each frame call [PropLayer.update] first, then [update] (it sets the glow of its own batches over the layer's).
 * The model's pictures are only read while adding: drop the model afterwards and its arrays go with it.
 */
internal class CinemaLayer(private val props: PropLayer) {
    private class Lit(val mi: MaterialInstance, val role: CinemaPart.Role)
    private val lits = ArrayList<Lit>()

    /** Adds [model] standing at [at] (world x, z, along x, z, out x, z, turn: as [PropLayer.addMesh]). */
    fun add(model: CinemaModel, at: FloatArray) {
        for (p in model.parts) {
            val mi = props.addMesh(p.mesh, listOf(at), p.casts, p.tiled) ?: continue
            if (p.role != CinemaPart.Role.BODY) lits += Lit(mi, p.role)
        }
    }

    /** [dark] how dark it is (0 day, 1 night), [seconds] the clock the bulbs chase by. */
    fun update(dark: Float, seconds: Double) {
        val night = smooth((dark - .3f) / .4f)
        val dusk = smooth((dark - .12f) / .3f)
        val step = (floor(seconds * CHASE_RATE).toLong() % 3).toInt()
        for (l in lits) when (l.role) {
            CinemaPart.Role.WINDOWS -> l.mi.setParameter("glow", 1.7f, 1.25f, .7f, night)
            CinemaPart.Role.SIGN -> l.mi.setParameter("glow", PropLayer.GLOW, PropLayer.GLOW, PropLayer.GLOW, dusk)
            CinemaPart.Role.CHASE0, CinemaPart.Role.CHASE1, CinemaPart.Role.CHASE2 -> {
                val k = l.role.ordinal - CinemaPart.Role.CHASE0.ordinal
                l.mi.setParameter("glow", 1.9f, 1.7f, 1.3f, dusk * if (k == step) 1f else .2f)
            }
            CinemaPart.Role.BODY -> {}
        }
    }

    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    companion object {
        /** Chase steps a second. */
        const val CHASE_RATE = 7.0
    }
}
