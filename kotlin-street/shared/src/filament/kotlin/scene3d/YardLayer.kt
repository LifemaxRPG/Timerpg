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
        fun shimmer(mi: MaterialInstance, mesh: PropMesh, seconds: Double) = shimmer(mi, mesh.texW, mesh.texH, seconds)

        /** The same for a picture of [texW] x [texH]. */
        fun shimmer(mi: MaterialInstance, texW: Int, texH: Int, seconds: Double) {
            val step = (seconds * 4.0).toLong()
            val du = (step % texW).toFloat() / texW
            val dv = ((step / 3) % texH).toFloat() / texH
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
    /** Also bake each lot's far version (for the open world's distance); off, the town holds only the near ones. */
    @Volatile var far = System.getProperty("townLod") != "0"

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
        // baked a few lots at a time on the common pool (the kit's caches are shared under locks), handed to the sink in
        // order, so only those few lots' meshes are held at once (each lot's atlases and vertices: up to some 5 MB); the
        // sink packs them for the GPU and waits while the engine's thread is behind (FilamentTown.offer). The whole
        // town's yards bake in under a second of one core, so more lots at once would only hold more memory.
        val step = maxOf(2, Runtime.getRuntime().availableProcessors() / 4)
        for (from in work.indices step step) {
            val group = work.subList(from, minOf(work.size, from + step))
            // (each lot twice: as near by, and its far version for the open world's distance, [YardKit.bake] far)
            val baked: MutableList<Pair<List<YardBatch>, List<YardBatch>>?> = group.parallelStream().map { (items, _) ->
                YardKit.bake(items, perKind = false) to (if (far) YardKit.bake(items, perKind = false, far = true) else emptyList())
            }.collect(java.util.stream.Collectors.toList())
            for (k in baked.indices) {
                val patch = group[k].second
                val (near, farOnes) = baked[k]!!
                for ((p, list) in listOf(patch to near, patch.farTwin() to farOnes)) for (b in list) {
                    val mi = sink.mesh("yard:${p.id}:${b.group}", b.mesh, at, b.casts, b.tiled, p, water = b.water)
                    if (b.water && mi != null) sink.animate { s -> YardLayer.shimmer(mi, b.mesh, s) }
                }
                // (each lot's meshes let go as soon as the sink has them)
                baked[k] = null
            }
        }
        YardKit.trim()
    }
}
