package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One chunk's trees and bushes in 3D near by ([TreeKit]), in cells of [SIZE] so the detail follows the camera finely
 * while the draws stay few: each cell is one merged mesh (one draw, one shadow twin) at either level, all wearing the kit's
 * one shared atlas. The chunk shows its cards far off; once it is near, each cell shows its simple trees, and its
 * detailed ones within [TownFrame.LOD_DETAIL] of the eye. The simple level is made with the chunk's near greenery
 * ([pack] on a worker, [build] here); the detailed one is packed on a worker as a cell comes within reach, uploaded a few
 * cells a frame, and let go again once the cell is well past it (so a walk through the woods holds only the detail
 * round the camera).
 */
internal class TreeCells(
    private val engine: Engine, private val scene: Scene, private val lit: Material, private val proxy: Material?,
    private val textures: StreetTextures, private val pool: java.util.concurrent.ExecutorService,
) {
    private inner class Cell {
        val trees = ArrayList<Pair<TreeKit.Spec, FloatArray>>()
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE; var top = 0f
        var cx = 0f; var cz = 0f; var r = 0f
        var simple: PropLayer? = null
        var simplePacked: PropLayer.Packed? = null
        var detail: PropLayer? = null
        var detailJob: java.util.concurrent.Future<PropLayer.Packed?>? = null
        fun add(s: TreeKit.Spec, at: FloatArray) {
            trees += s to at
            val reach = s.height * .45f
            x0 = min(x0, at[0] - reach); z0 = min(z0, at[1] - reach); x1 = max(x1, at[0] + reach); z1 = max(z1, at[1] + reach)
            top = max(top, s.height)
            cx = (x0 + x1) / 2f; cz = (z0 + z1) / 2f; r = hypot(x1 - x0, z1 - z0) / 2f + top / 2f
        }
        fun packLevel(detail: Boolean): PropLayer.Packed? =
            TreeKit.merge(trees.map { (s, a) -> TreeKit.mesh(s, detail) to a })?.let { PropLayer.Packing.pack(it, IDENTITY) }
        fun layer(p: PropLayer.Packed): PropLayer =
            PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true).also { it.upload(p, casts = true) }
    }

    private val cells = LinkedHashMap<Long, Cell>()
    /** How many trees and bushes. */
    var count = 0; private set
    private var simpleBuilt = false

    /** This frame's draws, triangles and shadow casters ([update]). */
    var draws = 0; private set
    var triangles = 0; private set
    var casters = 0; private set
    /** Cells shown in detail this frame. */
    var detailed = 0; private set

    /** [spec] standing at [at] (world x, z, along x, z, out x, z, turn). After [build], its cell's simple level is made again at once. */
    fun add(spec: TreeKit.Spec, at: FloatArray) {
        val key = (floor(at[0] / SIZE).toLong() shl 32) or (floor(at[1] / SIZE).toLong() and 0xffffffffL)
        val c = cells.getOrPut(key) { Cell() }
        c.add(spec, at)
        count++
        if (simpleBuilt) {
            c.simple?.destroy(); c.simple = c.packLevel(false)?.let { c.layer(it) }
            c.detail?.destroy(); c.detail = null; c.detailJob = null
        }
    }

    /** (A worker) packs every cell's simple level, for [build]. */
    fun pack() { for (c in cells.values) if (c.simple == null && c.simplePacked == null) c.simplePacked = c.packLevel(false) }

    /** Uploads the simple level ([pack]ed, or packed here for what was not). */
    fun build() {
        for (c in cells.values) if (c.simple == null) {
            val p = c.simplePacked ?: c.packLevel(false)
            c.simplePacked = null
            c.simple = p?.let { c.layer(it) }
        }
        simpleBuilt = true
    }

    /**
     * This frame: [near] whether the chunk shows its 3D greenery (else every cell is off: its cards stand in), [fade] its
     * fade, [shadows] whether its shadow twins cast. The detail comes and goes with the distance from the eye.
     */
    fun update(pose: TownPose, look: FloatArray, dark: Float, near: Boolean, fade: Float, shadows: Boolean) {
        draws = 0; triangles = 0; casters = 0; detailed = 0
        for (c in cells.values) {
            val d = pose.distance(c.cx, c.top / 2f, c.cz) - c.r
            val on = near && pose.sees(c.cx, c.top / 2f, c.cz, c.r)
            // the detail: packed on a worker as the cell comes within reach, uploaded a few cells a frame, let go far past it
            if (near && d < TownFrame.LOD_DETAIL + REACH_AHEAD && c.detail == null && c.detailJob == null) c.detailJob = pool.submit(java.util.concurrent.Callable { c.packLevel(true) })
            val job = c.detailJob
            if (job != null && job.isDone && c.detail == null && uploadsLeft > 0) {
                uploadsLeft--
                c.detail = try { job.get() } catch (e: java.util.concurrent.ExecutionException) { null }?.let { c.layer(it) }
                c.detailJob = null
            }
            if (c.detail != null && (!near || d > TownFrame.LOD_DETAIL + LET_GO)) { c.detail?.destroy(); c.detail = null }
            val showDetail = on && c.detail != null && d < TownFrame.LOD_DETAIL
            val showSimple = on && !showDetail
            c.detail?.update(look, dark, showDetail, fade, shadows && showDetail)
            c.simple?.update(look, dark, showSimple, fade, shadows && showSimple)
            val l = if (showDetail) c.detail else if (showSimple) c.simple else null
            if (l != null) { draws += l.draws; triangles += l.triangles }
            if (shadows) (if (showDetail) c.detail else c.simple)?.let { casters += it.casters }
            if (showDetail) detailed++
        }
    }

    /** Makes the detail [pose] would show at once (a preview's or a teleport's; unbudgeted). */
    fun prepare(pose: TownPose) {
        for (c in cells.values) {
            if (c.detail != null || pose.distance(c.cx, c.top / 2f, c.cz) - c.r >= TownFrame.LOD_DETAIL) continue
            val p = c.detailJob?.let { try { it.get() } catch (e: Exception) { null } } ?: c.packLevel(true)
            c.detailJob = null
            c.detail = p?.let { c.layer(it) }
        }
    }

    fun destroy() {
        for (c in cells.values) { c.simple?.destroy(); c.detail?.destroy(); c.detailJob?.cancel(false) }
        cells.clear()
    }

    companion object {
        /** A cell's side (street units). */
        const val SIZE = 420f
        /** The detail is packed this far ahead of [TownFrame.LOD_DETAIL], and let go this far past it. */
        const val REACH_AHEAD = 300f
        const val LET_GO = 700f
        private val IDENTITY = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
        /** Detailed cells uploaded per frame, all chunks together ([newFrame] sets it). */
        private var uploadsLeft = 2
        fun newFrame(uploads: Int = 2) { uploadsLeft = uploads }
    }
}
