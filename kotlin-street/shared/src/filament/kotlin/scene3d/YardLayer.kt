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
        for ((mi, mesh) in water) shimmer(mi, mesh, seconds)
    }

    fun destroy() { layer.destroy(); water.clear(); items = 0 }

    companion object {
        /** The baked meshes are in world coordinates already. */
        private val IDENTITY = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)

        /** The water's shimmer at [seconds]: a texel's step every quarter second, ripples one way and sparkle the other. */
        fun shimmer(mi: MaterialInstance, mesh: PropMesh, seconds: Double) {
            val step = (seconds * 4.0).toLong()
            val du = (step % mesh.texW).toFloat() / mesh.texW
            val dv = ((step / 3) % mesh.texH).toFloat() / mesh.texH
            mi.setParameter("uvRect", du, dv, 1f, 1f)
        }
    }
}

/**
 * The yard kit plugged into the Filament town ([FilamentTown.yardBuilder] = YardKitBuilder): every lot's yard and the
 * lanes' and verges' items baked by [YardKit.bake] (a batch for the things that throw a shadow, one for those that do not,
 * one per ground tile) a lot or a lane at a time, so the
 * town culls them with the view and cuts a lot with its house in the street view ([YardPatch]); the water's batches
 * shimmer through the sink's [YardSink.animate].
 */
object YardKitBuilder : YardBuilder {
    override fun build(town: com.lifemax.rpg.feature.habits.domain.Town, yards: com.lifemax.rpg.feature.habits.domain.TownYards, sink: YardSink) {
        fun item(i: com.lifemax.rpg.feature.habits.domain.TownYardItem) =
            YardItem(i.kind, i.rect[0], i.rect[1], i.rect[2], i.rect[3], i.faceX, i.faceZ, i.variant, i.layer, i.on, i.zone)
        val at = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
        // a lot at a time (culled with the view, cut with its house), then each lane's and verge's items
        val work = ArrayList<Pair<List<YardItem>, YardPatch>>()
        for (y in yards.yards) {
            if (y.items.isEmpty()) continue
            val p = y.parcel
            var x0 = p[0]; var z0 = p[1]; var x1 = p[2]; var z1 = p[3]
            for (i in y.items) { x0 = minOf(x0, i.rect[0]); z0 = minOf(z0, i.rect[1]); x1 = maxOf(x1, i.rect[2]); z1 = maxOf(z1, i.rect[3]) }
            work += y.items.map(::item) to YardPatch(y.lot, (x0 + x1) / 2f, (z0 + z1) / 2f, kotlin.math.hypot(x1 - x0, z1 - z0) / 2f, y.sprite)
        }
        for (l in yards.lanes) {
            if (l.items.isEmpty()) continue
            val r = l.rect
            work += l.items.map(::item) to YardPatch(l.id, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, kotlin.math.hypot(r[2] - r[0], r[3] - r[1]) / 2f)
        }
        // baked on every core (the kit's caches are shared under locks) a few lots per core at a time, handed to the sink
        // in order (so only those few lots' meshes are held at once)
        val step = 2 * Runtime.getRuntime().availableProcessors()
        for (from in work.indices step step) {
            val group = work.subList(from, minOf(work.size, from + step))
            val baked = group.parallelStream().map { (items, _) -> YardKit.bake(items, perKind = false) }.collect(java.util.stream.Collectors.toList())
            for ((k, batches) in baked.withIndex()) {
                val patch = group[k].second
                for (b in batches) {
                    val mi = sink.mesh("yard:${patch.id}:${b.group}", b.mesh, at, b.casts, b.tiled, patch, water = b.water)
                    if (b.water && mi != null) sink.animate { s -> YardLayer.shimmer(mi, b.mesh, s) }
                }
            }
        }
    }
}
