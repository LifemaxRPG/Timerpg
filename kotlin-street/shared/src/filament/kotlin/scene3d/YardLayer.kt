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
    /** How far an open gate's leaf may reach past its lot's items (a patch's circle is that much wider). */
    const val GATE_SWING = 20f

    override fun build(town: com.lifemax.rpg.feature.habits.domain.Town, yards: com.lifemax.rpg.feature.habits.domain.TownYards, sink: YardSink) {
        fun item(i: com.lifemax.rpg.feature.habits.domain.TownYardItem) =
            YardItem(i.kind, i.rect[0], i.rect[1], i.rect[2], i.rect[3], i.faceX, i.faceZ, i.variant, i.layer, i.on, i.zone)
        // a lot at a time (culled with the view, cut with its house), then each lane's and verge's items. A lot's tall
        // things (sheds, trees, play sets, back hedges: FilamentTown.tallHeight) are each a patch of their own beside it,
        // so the open world's camera cuts away only the one truly in the way; the lot's far version keeps them all
        val work = ArrayList<Job>()
        fun add(id: String, items: List<com.lifemax.rpg.feature.habits.domain.TownYardItem>, p: YardPatch) {
            val tall = items.indices.filter { FilamentTown.tallHeight(items[it].kind, items[it].zone) > 0f }.toSet()
            work += Job(items.filterIndexed { k, _ -> k !in tall }.map(::item), items.map(::item), p, tall.map { item(items[it]) })
            for (k in tall) {
                val r = items[k].rect
                work += Job(listOf(item(items[k])), null, YardPatch(YardPatch.tallId(id, k), (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f,
                    kotlin.math.hypot(r[2] - r[0], r[3] - r[1]) / 2f + 40f, p.sprite, follows = id))
            }
        }
        for (y in yards.yards) {
            if (y.items.isEmpty()) continue
            add(y.lot, y.items, patchOf(y, y.items))
        }
        for (l in yards.lanes) {
            if (l.items.isEmpty()) continue
            add(l.id, l.items, patchOf(l))
        }
        // (the far versions' own pictures, the whole town's: one atlas every lot's far version shares)
        farPictures = if (far) YardKit.farPictures(work.mapNotNull { it.far }) else emptyList()
        bakeAll(work, sink)
        YardKit.trim()
    }

    /** The far versions' own pictures ([YardKit.farPictures]) of the town last built, for its lots baked again ([rebuild]). */
    @Volatile private var farPictures: List<PropMesh> = emptyList()

    /** Lot [y]'s patch for [items] (its parcel widened to them, and to its gates' swing). */
    private fun patchOf(y: com.lifemax.rpg.feature.habits.domain.TownYard, items: List<com.lifemax.rpg.feature.habits.domain.TownYardItem>): YardPatch {
        val p = y.parcel
        var x0 = p[0]; var z0 = p[1]; var x1 = p[2]; var z1 = p[3]
        for (i in items) { x0 = minOf(x0, i.rect[0]); z0 = minOf(z0, i.rect[1]); x1 = maxOf(x1, i.rect[2]); z1 = maxOf(z1, i.rect[3]) }
        // (a gate is drawn open, its leaves swung through 90 degrees on their hinges: a back gate's can reach past the lot)
        val swing = if (items.any { it.kind == "gate" || it.kind == "sideGate" || it.kind == "backGate" }) GATE_SWING else 0f
        return YardPatch(y.lot, (x0 + x1) / 2f, (z0 + z1) / 2f, kotlin.math.hypot(x1 - x0, z1 - z0) / 2f + swing, y.sprite)
    }

    private fun patchOf(l: com.lifemax.rpg.feature.habits.domain.TownLane): YardPatch {
        val r = l.rect
        return YardPatch(l.id, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, kotlin.math.hypot(r[2] - r[0], r[3] - r[1]) / 2f)
    }

    /**
     * One lot's (or lane's) yard again with [items] (what the cars left of it: docs/VEHICLES.md, "Destruction"), baked
     * here and handed to [sink] as [build] does it: the lot's patch, its tall things' own patches, its far version. The
     * town swaps them in for the lot's old patches ([FilamentTown.replaceYard]).
     */
    fun rebuild(owner: String, yards: com.lifemax.rpg.feature.habits.domain.TownYards, items: List<com.lifemax.rpg.feature.habits.domain.TownYardItem>, sink: YardSink,
        /** Only its near version and its tall things' (streaming it back in: its far version stayed resident). */
        nearOnly: Boolean = false): String? {
        val y = yards.of(owner)
        val l = if (y == null) yards.lanes.firstOrNull { it.id == owner } else null
        val id = y?.lot ?: l?.id ?: return null
        val patch = if (y != null) patchOf(y, items) else patchOf(l!!)
        fun item(i: com.lifemax.rpg.feature.habits.domain.TownYardItem) =
            YardItem(i.kind, i.rect[0], i.rect[1], i.rect[2], i.rect[3], i.faceX, i.faceZ, i.variant, i.layer, i.on, i.zone)
        val work = ArrayList<Job>()
        val tall = items.indices.filter { FilamentTown.tallHeight(items[it].kind, items[it].zone) > 0f }.toSet()
        work += Job(items.filterIndexed { k, _ -> k !in tall }.map(::item), if (nearOnly) null else items.map(::item), patch, tall.map { item(items[it]) })
        for (k in tall) {
            val r = items[k].rect
            work += Job(listOf(item(items[k])), null, YardPatch(YardPatch.tallId(id, k), (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f,
                kotlin.math.hypot(r[2] - r[0], r[3] - r[1]) / 2f + 40f, patch.sprite, follows = id))
        }
        bakeAll(work, sink)
        return id
    }

    /**
     * A patch to bake: its [near] items, all its lot's items for its far version ([far]; null: none, a tall thing's own
     * patch), and the lot's [tall] things (baked as patches of their own: the far version wears their atlases too).
     */
    private class Job(val near: List<YardItem>, val far: List<YardItem>?, val patch: YardPatch, val tall: List<YardItem> = emptyList())

    /** Bakes each [Job] and hands the batches to [sink]. */
    private fun bakeAll(work: List<Job>, sink: YardSink) {
        val at = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
        // baked a few lots at a time on the common pool (the kit's caches are shared under locks), handed to the sink in
        // order, so only those few lots' meshes are held at once (each lot's atlases and vertices: up to some 5 MB); the
        // sink packs them for the GPU and waits while the engine's thread is behind (FilamentTown.offer). The next few
        // lots bake while the sink packs these (the gamma town's 2062: some 4.7 s of baking and 4.5 s of packing on the
        // build server, one after the other until 2026-11-24): two groups held at once
        val step = maxOf(2, Runtime.getRuntime().availableProcessors() / 4)
        // (each lot twice: as near by, and its far version for the open world's distance, [YardKit.bake] far)
        fun bake(from: Int): java.util.concurrent.Future<MutableList<Pair<List<YardBatch>, List<YardBatch>>?>> {
            val group = work.subList(from, minOf(work.size, from + step))
            return java.util.concurrent.ForkJoinPool.commonPool().submit(java.util.concurrent.Callable {
                group.parallelStream().map { w ->
                    YardKit.bake(w.near, perKind = false) to (if (far && w.far != null) YardKit.bakeFar(w.near, w.tall, w.far, farPictures) else emptyList())
                }.collect(java.util.stream.Collectors.toList<Pair<List<YardBatch>, List<YardBatch>>?>())
            })
        }
        var next = if (work.isEmpty()) null else bake(0)
        for (from in work.indices step step) {
            val group = work.subList(from, minOf(work.size, from + step))
            val baked = try { next!!.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            next = if (from + step < work.size) bake(from + step) else null
            for (k in baked.indices) {
                val patch = group[k].patch
                val (near, farOnes) = baked[k]!!
                for ((p, list) in listOf(patch to near, patch.farTwin() to farOnes)) for (b in list) {
                    val mi = sink.mesh("yard:${p.id}:${b.group}", b.mesh, at, b.casts, b.tiled, p, water = b.water)
                    if (b.water && mi != null) sink.animate { s -> YardLayer.shimmer(mi, b.mesh, s) }
                }
                // (each lot's meshes let go as soon as the sink has them)
                baked[k] = null
            }
        }
    }
}
