package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.TownYardItem
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import com.lifemax.rpg.feature.habits.vehicle.Wreckage
import java.util.concurrent.Future

/**
 * What the cars broke, drawn ([TownExtra], docs/VEHICLES.md "Destruction"): the town told to take away what broke (a
 * yard baked again without its broken fence stretch or bin, [FilamentTown.replaceYard]; a street lamp, bench or prop
 * taken out of its batch, [FilamentTown.hideProp]) and the loose pieces ([Wreckage.pieces]) drawn moving every frame,
 * each with the original's own pixel-art mesh (a fence panel is the yard kit's fence of one panel's length, a bin the
 * yard kit's bin, a lamp post the town's lamp), stood where the physics puts its box. A piece whose original cannot be
 * found is a plain box of its size.
 */
internal class TownWreck(
    private val kit: TownExtra.Kit,
    private val town: FilamentTown,
    private val cars: () -> VehicleWorld?,
) : TownExtra {
    private val layer = PropLayer(kit.engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true).also { it.seeThrough = false }

    private class Draw(val piece: Wreckage.Piece) {
        var job: Future<List<Pair<PropLayer.Packed, Boolean>>>? = null
        var first = -1
        var count = 0
        var gone = false
    }
    private val draws = HashMap<Int, Draw>()
    private var seen: Wreckage? = null
    private var yardsSeen = -1
    private val yardLists = HashMap<String, List<TownYardItem>>()
    private var hiddenDone = 0
    /** How each taken-away street thing was drawn, by its place and detail (its pieces wear it). */
    private val drawnAt = HashMap<String, FilamentTown.Drawn?>()
    private val pose = FloatArray(7)
    private val m = FloatArray(16)

    override val pending: Boolean get() = town.yardsPending || draws.values.any { it.first < 0 && !it.gone } ||
        cars()?.wreck?.let { w -> w.pieces.any { it.id !in draws } || w.yardVersion != yardsSeen } == true
    override val report: String get() = cars()?.wreck?.let { w -> "wreck: ${w.pieces.size} pieces (${w.live} live, $plain drawn as plain boxes), ${w.events.size} things broken, ${w.yardEdits.size} yards changed, ${drawnAt.values.count { it != null }} of ${drawnAt.size} street things found to take away" } ?: "wreck: none"
    /** Pieces drawn as plain boxes (their original not found). */
    @Volatile private var plain = 0

    override fun step() { sync() }

    /** The cars' wreckage this frame: the town's edits, new pieces' meshes started, the made ones uploaded. */
    private fun sync() {
        val w = cars()?.wreck
        if (w !== seen) { reset(); seen = w }
        if (w == null) return
        town.pollYards()
        if (w.yardVersion != yardsSeen) {
            yardsSeen = w.yardVersion
            for ((owner, items) in w.yardEdits) if (yardLists[owner] !== items) { yardLists[owner] = items; town.replaceYard(owner, items) }
            while (hiddenDone < w.hidden.size) {
                val h = w.hidden[hiddenDone++]
                drawnAt[key(h.x, h.z, h.detail)] = town.hideProp(h.x, h.z, h.detail)
            }
        }
        for (p in w.pieces) {
            val d = draws.getOrPut(p.id) { Draw(p) }
            if (d.first >= 0 || d.gone) continue
            val j = d.job
            if (j == null) { d.job = make(p); continue }
            if (!j.isDone) continue
            val packed = runCatching { j.get() }.getOrNull().orEmpty()
            d.first = layer.batchCount
            for ((pk, casts) in packed) layer.upload(pk, casts)
            d.count = layer.batchCount - d.first
        }
        // (pieces the wreckage let go of: freed, their buffers given back to the GPU, not only hidden)
        if (draws.size > w.pieces.size) {
            val ids = w.pieces.mapTo(HashSet()) { it.id }
            for (d in draws.values) if (!d.gone && d.piece.id !in ids) { d.gone = true; for (k in 0 until d.count) layer.release(d.first + k) }
        }
    }

    private fun key(x: Float, z: Float, detail: String) = "%.1f/%.1f/%s".format(java.util.Locale.ROOT, x, z, detail)

    /** A piece's mesh as its original stood, relative to the ground under its spawn point, packed on a worker. */
    private fun make(p: Wreckage.Piece): Future<List<Pair<PropLayer.Packed, Boolean>>> {
        val lk = p.look
        val drawn = if (lk.source == "prop") drawnAt[key(lk.srcX, lk.srcZ, lk.kind)] else null
        return kit.pool.submit(java.util.concurrent.Callable {
            val out = ArrayList<Pair<PropLayer.Packed, Boolean>>()
            val r = lk.rect
            if (lk.source == "yard" && r != null) {
                val item = YardItem(lk.kind, r[0], r[1], r[2], r[3], lk.faceX, lk.faceZ, lk.variant, lk.layer, null, lk.zone)
                for (b in YardKit.bake(listOf(item), perKind = false)) PropLayer.Packing.pack(b.mesh, IDENTITY, YardKit.sharedAtlas?.shareFor(b.mesh))?.let { out += it to b.casts }
            } else if (drawn != null) {
                val a = drawn.at.copyOf()
                a[0] -= lk.spawnX; a[1] -= lk.spawnZ
                PropLayer.Packing.pack(drawn.mesh, listOf(a))?.let { out += it to true }
            }
            if (out.isEmpty()) {
                plain++
                // (nothing to wear: a plain box of its size, weathered wood)
                val u = VehicleWorld.U
                val mb = MeshBuilder()
                val hx = p.hx * u; val hz = p.hz * u
                mb.solidBox(-hx, 0f, -hz, hx, p.hy * 2f * u, hz, Paint.swatch(Paint.WOOD.b))
                PropLayer.Packing.pack(mb.build(), IDENTITY)?.let { out += it to true }
            }
            out
        })
    }

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out.fill(0)
        sync()
        if (draws.isEmpty()) { layer.update(f.look, f.dark, false); return out }
        for (d in draws.values) {
            if (d.first < 0 || d.gone) continue
            d.piece.read(pose)
            matrix(d.piece, pose, m)
            for (k in 0 until d.count) layer.setBatchTransform(d.first + k, m)
        }
        layer.update(f.look, f.dark, true)
        out[0] = layer.draws; out[1] = layer.triangles; out[2] = layer.casters
        return out
    }

    /**
     * The piece's matrix: its pose now times its rotation at spawn undone, its mesh's ground (half its height below the
     * box's middle) brought to the box's foot: M = T(p) R(q q0⁻¹) T(0, -hy, 0).
     */
    private fun matrix(p: Wreckage.Piece, s: FloatArray, o: FloatArray) {
        val q0 = p.look.q0
        // q * conj(q0)
        val ax = s[3]; val ay = s[4]; val az = s[5]; val aw = s[6]
        val bx = -q0[0]; val by = -q0[1]; val bz = -q0[2]; val bw = q0[3]
        val x = aw * bx + ax * bw + ay * bz - az * by
        val y = aw * by - ax * bz + ay * bw + az * bx
        val z = aw * bz + ax * by - ay * bx + az * bw
        val w = aw * bw - ax * bx - ay * by - az * bz
        o[0] = 1f - 2f * (y * y + z * z); o[1] = 2f * (x * y + w * z); o[2] = 2f * (x * z - w * y); o[3] = 0f
        o[4] = 2f * (x * y - w * z); o[5] = 1f - 2f * (x * x + z * z); o[6] = 2f * (y * z + w * x); o[7] = 0f
        o[8] = 2f * (x * z + w * y); o[9] = 2f * (y * z - w * x); o[10] = 1f - 2f * (x * x + y * y); o[11] = 0f
        val hy = p.look.hy
        o[12] = s[0] - o[4] * hy; o[13] = s[1] - o[5] * hy; o[14] = s[2] - o[6] * hy; o[15] = 1f
    }

    private fun reset() {
        for (d in draws.values) d.job?.cancel(true)
        draws.clear()
        layer.destroy()
        yardsSeen = -1; yardLists.clear(); hiddenDone = 0; drawnAt.clear()
    }

    override fun destroy() { reset() }

    companion object {
        /** The meshes are made relative to the ground under the piece's spawn point: no turn. */
        private val IDENTITY = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))

        /** Adds the cars' wreckage [cars] gives to [town]. */
        fun attach(town: FilamentTown, cars: () -> VehicleWorld?): TownWreck = town.addExtra { TownWreck(it, town, cars) }
    }
}
