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

    /** One part packed for [upload] on any thread ([pack]): its batch, whether it casts, repeats, how it glows. */
    class Packed(val packed: PropLayer.Packed, val casts: Boolean, val tiled: Boolean, val role: CinemaPart.Role)

    /** Uploads parts [pack] made ready (the engine's thread): as [add], without the model. */
    fun upload(parts: List<Packed>) {
        for (p in parts) {
            val mi = props.upload(p.packed, p.casts, p.tiled)
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
            CinemaPart.Role.PAGE0, CinemaPart.Role.PAGE1, CinemaPart.Role.PAGE2 -> {
                val k = l.role.ordinal - CinemaPart.Role.PAGE0.ordinal
                val page = (floor(seconds / PAGE_SECONDS).toLong() % 3).toInt()
                l.mi.setParameter("glow", PropLayer.GLOW, PropLayer.GLOW, PropLayer.GLOW, dusk * if (k == page) 1f else .22f)
            }
            CinemaPart.Role.BODY -> {}
        }
    }

    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    companion object {
        /** [model]'s parts packed at [at] (as [add]) for [upload], on any thread: no engine call; drop the model after. */
        fun pack(model: CinemaModel, at: FloatArray): List<Packed> =
            model.parts.mapNotNull { p -> PropLayer.Packing.pack(p.mesh, listOf(at))?.let { Packed(it, p.casts, p.tiled, p.role) } }

        /** Chase steps a second. */
        const val CHASE_RATE = 7.0
        /** Seconds each page of a digital board stays lit. */
        const val PAGE_SECONDS = 1.6
    }
}

/**
 * The theater's real lights ([CinemaModel.lights]: the marquee's soffit on the pavement, sign washes, the car park's
 * lamps), made in [scene] at the model's place and turned up from dusk; [destroy] removes them.
 */
internal class CinemaLights(private val engine: com.google.android.filament.Engine, private val scene: com.google.android.filament.Scene) {
    private class L(val entity: Int, val lumens: Float)
    private val lights = ArrayList<L>()
    val count: Int get() = lights.size

    /** Adds [list] (the model's frame) standing at [at] (world x, z, along x, z, out x, z, turn: as [PropLayer.addMesh]). */
    fun add(list: List<CinemaLight>, at: FloatArray) {
        for (c in list) {
            val wx = at[0] + at[2] * c.x + at[4] * c.z; val wz = at[1] + at[3] * c.x + at[5] * c.z
            val e = com.google.android.filament.EntityManager.get().create()
            val type = if (c.down) com.google.android.filament.LightManager.Type.SPOT else com.google.android.filament.LightManager.Type.POINT
            val bld = com.google.android.filament.LightManager.Builder(type).color(c.r, c.g, c.b).intensity(0f).falloff(c.reach).castShadows(false).position(wx, c.y, wz)
            if (c.down) bld.direction(0f, -1f, 0f).spotLightCone(c.cone * .5f, c.cone)
            bld.build(engine, e)
            scene.addEntity(e)
            lights += L(e, c.lumens)
        }
    }

    /** [dark]: 0 day, 1 night; [shown]: the theater is drawn (its lights off when it is not). */
    fun update(dark: Float, shown: Boolean = true) {
        val t = ((dark - .25f) / .45f).coerceIn(0f, 1f); val on = t * t * (3f - 2f * t)
        val lm = engine.lightManager
        for (l in lights) lm.getInstance(l.entity).takeIf { it != 0 }?.let { lm.setIntensity(it, if (shown) l.lumens * on else 0f) }
    }

    fun destroy() {
        val em = com.google.android.filament.EntityManager.get()
        for (l in lights) { scene.remove(l.entity); engine.destroyEntity(l.entity); em.destroy(l.entity) }
        lights.clear()
    }
}
