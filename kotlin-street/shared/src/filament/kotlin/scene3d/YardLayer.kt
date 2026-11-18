package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene

/**
 * The yards drawn by Filament: [YardKit.bake]'s batches (one per kind, one per ground tile) handed to a [PropLayer], so
 * they wear the street's lit material, are lit and shadowed by the sun like the buildings and props, and their lamps,
 * lanterns, embers and pool lights glow at night. The pools' and ponds' water shimmers: its tile steps a texel at a time.
 *
 * Make one with the street's materials, [add] the items (a yard's, a row's, a town's: each call bakes its own batches),
 * then each frame [update] it with the true camera's look, how dark it is and the time; [destroy] frees it.
 */
internal class YardLayer(engine: Engine, scene: Scene, visible: Material, proxy: Material?, srgb: Boolean = true) {
    private val layer = PropLayer(engine, scene, visible, proxy, srgb)
    private val water = ArrayList<Pair<MaterialInstance, PropMesh>>()

    /** How many items were added, and drawn in how many batches. */
    var items = 0; private set
    val draws: Int get() = layer.draws

    fun add(list: List<YardItem>) {
        for (b in YardKit.bake(list)) {
            val mi = layer.addMesh(b.mesh, listOf(IDENTITY), b.casts, tiled = b.tiled)
            if (b.water && mi != null) water += mi to b.mesh
        }
        items += list.count { it.kind in YardKit.KINDS }
    }

    /**
     * This frame: [look] the true camera's clip-space matrix, [dark] 0 day to 1 night, [seconds] the clock (the water's
     * shimmer), [shown] whether drawn at all.
     */
    fun update(look: FloatArray, dark: Float, seconds: Double, shown: Boolean = true) {
        layer.update(look, dark, shown)
        if (!shown) return
        // a texel's step every quarter second, the ripples drifting one way and the sparkle the other
        val step = (seconds * 4.0).toLong()
        for ((mi, mesh) in water) {
            val du = (step % mesh.texW).toFloat() / mesh.texW
            val dv = ((step / 3) % mesh.texH).toFloat() / mesh.texH
            mi.setParameter("uvRect", du, dv, 1f, 1f)
        }
    }

    fun destroy() { layer.destroy(); water.clear(); items = 0 }

    private companion object {
        /** The baked meshes are in world coordinates already. */
        val IDENTITY = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)
    }
}

/**
 * The yard kit plugged into the Filament town ([FilamentTown.yardBuilder] = YardKitBuilder): every lot's yard and the
 * lanes' and verges' items baked by [YardKit.bake] (one batch a kind and a ground tile) into the town's sink. (Through the
 * sink the water stands still: [YardLayer] is the one that shimmers it.)
 */
object YardKitBuilder : YardBuilder {
    override fun build(town: com.lifemax.rpg.feature.habits.domain.Town, yards: com.lifemax.rpg.feature.habits.domain.TownYards, sink: YardSink) {
        fun item(i: com.lifemax.rpg.feature.habits.domain.TownYardItem) =
            YardItem(i.kind, i.rect[0], i.rect[1], i.rect[2], i.rect[3], i.faceX, i.faceZ, i.variant, i.layer, i.on, i.zone)
        val items = yards.yards.flatMap { y -> y.items.map(::item) } + yards.lanes.flatMap { l -> l.items.map(::item) }
        val at = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
        for (b in YardKit.bake(items)) sink.mesh("yard:${b.group}", b.mesh, at, b.casts, b.tiled)
    }
}
