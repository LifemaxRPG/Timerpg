package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The town's buildings far off: each one a cheap box (its footprint, as tall as the building, wearing its own front and
 * side pictures shrunk four times, its roof a flat colour, its lit windows still lit at night), the boxes of a [CELL] square
 * of the town merged into one mesh (one draw a cell, about ten triangles a building). Past [TownFrame]'s detail distance a
 * cell's buildings are drawn as this instead of in full, out to [REACH] (the far end of the world), where the haze (the
 * sky's own horizon colour) takes them: nothing pops or fades at the old draw distance any more. A cell switches with
 * [SLACK] either side of the line (no flicker as the camera moves) and crosses over in [SWITCH_SECONDS] by the lit
 * material's screen door, the boxes' the inverse of the buildings' ([PropLayer.inverseFade]: every pixel shows one or the
 * other, never both, which z-fought and read as glitching, nor neither, which read as ghosts).
 *
 * The town ([FilamentTown]) hands each building in as it is built ([add]: its corners, height and pictures, the pictures
 * made from its [SpriteBuilding] on a worker by [pics]); a cell is packed on a worker once all its buildings are in, and
 * uploaded on the engine's thread ([upload]).
 */
internal class TownFar(
    private val engine: Engine,
    private val scene: Scene,
    private val lit: Material,
    private val textures: StreetTextures,
    private val pool: java.util.concurrent.ExecutorService,
) {
    /** A building's far pictures: its front and side shrunk, its roof's colour (ARGB). */
    class Pics(val front: PixImg, val side: PixImg, val roof: Int)

    internal class Item(val corners: FloatArray, val height: Float, val pics: Pics)

    inner class Cell(val key: Long, val cx: Float, val cz: Float) {
        var expected = 0
        var done = 0
        val items = ArrayList<Item>()
        var packing: java.util.concurrent.Future<PropLayer.Packed?>? = null
        var layer: PropLayer? = null
        /** 0: its buildings in full; 1: its far boxes; between: crossing over. */
        var t = 0f
        var far = false
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE; var top = 0f
        val ready: Boolean get() = layer != null
        var triangles = 0
        /**
         * Its buildings wanted in full but not resident yet (streaming, [TownStream]): while any are, it stays in its far
         * boxes whatever the distance, so a block streaming in never shows a hole, only its boxes a moment longer.
         */
        var waiting = 0
        /** The nearest its rectangle comes to the streaming's points this frame (street units; [TownStream]). */
        var streamD = 0f
        /** Its district ([District]: the coarser level past [SLOD_FROM]), and whether it is counted done there. */
        var district: District? = null
        var counted = false
    }

    private val cells = HashMap<Long, Cell>()
    private fun keyOf(x: Float, z: Float): Long = (floor(x / CELL).toLong() shl 32) xor (floor(z / CELL).toLong() and 0xffffffffL)

    /** The cell a building whose middle is at ([x], [z]) goes in (counted: [add] or [skip] it later). */
    fun expect(x: Float, z: Float): Cell = cells.getOrPut(keyOf(x, z)) {
        Cell(keyOf(x, z), (floor(x / CELL) + .5f) * CELL, (floor(z / CELL) + .5f) * CELL).also { c ->
            val dk = (floor(x / (CELL * DISTRICT)).toLong() shl 32) xor (floor(z / (CELL * DISTRICT)).toLong() and 0xffffffffL)
            c.district = districts.getOrPut(dk) { District() }.also { it.cells++ }
        }
    }.also { it.expected++ }

    /**
     * The coarser far level (SLOD, GTA V's "super LOD"; docs/STREAMING.md): [DISTRICT] x [DISTRICT] cells' far boxes merged
     * again into one mesh, their pictures shrunk four times more (a texel a 16 x 16 block of the art), past [SLOD_FROM]
     * from the eye. One draw for nine cells' at the far end of a long or high view; crossed over by the same screen door.
     */
    inner class District {
        var cells = 0
        var done = 0
        val items = ArrayList<Item>()
        var packing: java.util.concurrent.Future<PropLayer.Packed?>? = null
        var layer: PropLayer? = null
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE; var top = 0f
        var far = false
        var t = 0f
        var triangles = 0
    }
    private val districts = HashMap<Long, District>()

    private fun countDone(c: Cell) {
        if (c.counted || c.done < c.expected) return
        c.counted = true
        val d = c.district ?: return
        if (c.items.isNotEmpty()) {
            d.items += c.items
            d.x0 = min(d.x0, c.x0); d.z0 = min(d.z0, c.z0); d.x1 = max(d.x1, c.x1); d.z1 = max(d.z1, c.z1); d.top = max(d.top, c.top)
        }
        d.done++
        if (d.done >= d.cells && d.items.isNotEmpty() && slod) {
            val items = d.items.toList()
            d.items.clear()
            d.packing = pool.submit(java.util.concurrent.Callable { pack(items.map { Item(it.corners, it.height, Pics(shrink4(it.pics.front, true), shrink4(it.pics.side, false), it.pics.roof)) }) })
        }
    }

    /** A building of [cell] with nothing to draw far off (its art is missing, or it is drawn by code). */
    fun skip(cell: Cell) { cell.done++; startIfDone(cell) }

    /**
     * A building of [cell]: its footprint's corners in the world [corners] (x, z four times: front left, front right, back
     * right, back left, as seen from the street), [height] tall, wearing [pics].
     */
    fun add(cell: Cell, corners: FloatArray, height: Float, pics: Pics?) {
        if (pics != null && height > 4f) {
            cell.items += Item(corners, height, pics)
            windowLights(corners, height, pics.front)
            for (i in 0 until 4) { cell.x0 = min(cell.x0, corners[i * 2]); cell.x1 = max(cell.x1, corners[i * 2]); cell.z0 = min(cell.z0, corners[i * 2 + 1]); cell.z1 = max(cell.z1, corners[i * 2 + 1]) }
            cell.top = max(cell.top, height)
        }
        cell.done++
        startIfDone(cell)
    }

    /**
     * Every lit window texel of the far boxes as a light far off at night ([FarLights]): its place on the box's front
     * (x, y, z each), a little out from the wall. Kept for the town's life (a few floats a window).
     */
    var lights = FloatArray(0); private set
    var lightCount = 0; private set

    private fun windowLights(c: FloatArray, height: Float, front: PixImg) {
        if (!front.glows) return
        val m = front.mask
        // the front runs from its left corner (0) to its right (1); out of it, away from the back (3)
        val ax = c[2] - c[0]; val az = c[3] - c[1]
        var nx = c[0] - c[6]; var nz = c[1] - c[7]
        val nl = hypot(nx, nz).coerceAtLeast(1e-3f); nx /= nl; nz /= nl
        for (oy in 0 until front.h) for (ox in 0 until front.w) {
            if (((m[oy * front.w + ox] ushr 16) and 255) == 0) continue
            val u = (ox + .5f) / front.w; val v = (oy + .5f) / front.h
            if (lightCount * 3 + 3 > lights.size) lights = lights.copyOf(maxOf(768, lights.size * 2))
            lights[lightCount * 3] = c[0] + ax * u + nx * 1.5f
            lights[lightCount * 3 + 1] = height * (1f - v)
            lights[lightCount * 3 + 2] = c[1] + az * u + nz * 1.5f
            lightCount++
        }
    }

    private fun startIfDone(c: Cell) {
        countDone(c)
        if (c.done < c.expected || c.packing != null || c.layer != null || c.items.isEmpty()) return
        val items = c.items.toList()
        c.packing = pool.submit(java.util.concurrent.Callable { pack(items) })
    }

    /** Uploads a cell packed since (the engine's thread; one a call: a cell's upload was a 20 ms frame on the build server); true if any. */
    fun upload(): Boolean {
        var any = false
        for (c in cells.values) {
            if (any) break
            val f = c.packing ?: continue
            if (!f.isDone) continue
            c.packing = null
            val p = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("packing the far buildings of a cell", e.cause ?: e) }
            if (p == null) continue
            val l = PropLayer(engine, scene, lit, null, sharedTextures = textures, cull = true)
            l.inverseFade = true
            l.upload(p, casts = false)
            c.layer = l; c.triangles = l.triangles
            com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.count(com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.UP_FAR)
            c.items.clear(); c.items.trimToSize()
            any = true
        }
        // and a district's coarser level, one a call
        if (!any) for (d in districts.values) {
            val f = d.packing ?: continue
            if (!f.isDone) continue
            d.packing = null
            val p = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("packing the coarser far level of a district", e.cause ?: e) } ?: continue
            val l = PropLayer(engine, scene, lit, null, sharedTextures = textures, cull = true)
            l.upload(p, casts = false)
            d.layer = l; d.triangles = l.triangles
            any = true
            break
        }
        return any
    }

    /** Every cell is packed and uploaded (or has nothing far). */
    val allReady: Boolean get() = cells.values.all { it.done >= it.expected && it.packing == null } && districts.values.all { it.packing == null }

    private fun pack(items: List<Item>): PropLayer.Packed? {
        val mb = MeshBuilder()
        for (it in items) {
            val c = it.corners; val h = it.height
            fun p(i: Int, y: Float) = floatArrayOf(c[i * 2], y, c[i * 2 + 1])
            // corners as hexa wants them: 0 back left (x0 z0) 1 back right 2 front right 3 front left, the front between 3 and 2
            val b = listOf(p(3, 0f), p(2, 0f), p(1, 0f), p(0, 0f))
            val t = listOf(p(3, h), p(2, h), p(1, h), p(0, h))
            val roof = PixImg(2, 2).also { im -> im.fill(it.pics.roof) }
            mb.hexa(b, t, it.pics.front, it.pics.front, it.pics.side, it.pics.side, roof)
        }
        val mesh = mb.build()
        return PropLayer.Packing.pack(mesh, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)))
    }

    /**
     * This frame, for the camera at [pose] whose detail reaches [detail] (street units from the eye to a cell's nearest
     * edge): each cell's crossing eased by [step] (a share of the switch a frame), its far boxes drawn or not; [look] and
     * [dark] as every layer's. Returns draws and triangles drawn.
     */
    fun frame(pose: TownPose, look: FloatArray, dark: Float, detail: Float, step: Float, out: IntArray, hide: Boolean = false) {
        var draws = 0; var tris = 0
        // the districts first: past [SLOD_FROM] (with [SLOD_SLACK] either side) a district's coarse mesh stands in for its cells'
        for (d in districts.values) {
            val l = d.layer
            if (l == null) { d.t = 0f; d.far = false; continue }
            val dx = max(0f, max(d.x0 - pose.ex, pose.ex - d.x1)); val dz = max(0f, max(d.z0 - pose.ez, pose.ez - d.z1))
            val dd = hypot(dx, dz)
            d.far = slod && if (d.far) dd > SLOD_FROM - SLOD_SLACK else dd > SLOD_FROM + SLOD_SLACK
            d.t = if (d.far) min(1f, d.t + step) else max(0f, d.t - step)
            val r = hypot(d.x1 - d.x0, d.z1 - d.z0) / 2f + d.top / 2f
            val on = d.t > .02f && dd < REACH && pose.sees((d.x0 + d.x1) / 2f, d.top / 2f, (d.z0 + d.z1) / 2f, r) && !hide
            l.update(look, dark, on, d.t, false)
            if (on) { draws += l.draws; tris += d.triangles }
        }
        for (c in cells.values) {
            val l = c.layer
            if (l == null) { c.t = 0f; c.far = false; continue }
            val dx = max(0f, max(c.x0 - pose.ex, pose.ex - c.x1)); val dz = max(0f, max(c.z0 - pose.ez, pose.ez - c.z1))
            val d = hypot(dx, dz)
            c.far = c.waiting > 0 || if (c.far) d > detail - SLACK else d > detail + SLACK
            c.t = if (c.far) min(1f, c.t + step) else max(0f, c.t - step)
            val r = hypot(c.x1 - c.x0, c.z1 - c.z0) / 2f + c.top / 2f
            val mx = (c.x0 + c.x1) / 2f; val mz = (c.z0 + c.z1) / 2f
            // (crossing over to its district's coarse mesh: the inverse of the district's screen door)
            val f = min(c.t, 1f - (c.district?.t ?: 0f))
            val on = f > .02f && d < REACH && pose.sees(mx, c.top / 2f, mz, r) && !hide
            l.update(look, dark, on, f, false)
            if (on) { draws += l.draws; tris += c.triangles }
        }
        out[0] = draws; out[1] = tris
    }

    /** How far the full building in [cell] is drawn now (1: whole, 0: its cell's far box has it). */
    fun nearFade(cell: Cell?): Float = if (cell == null || cell.layer == null) 1f else 1f - cell.t

    /** Whether [cell]'s far boxes stand in for its buildings now (they need not be drawn, nor their range fade applied). */
    fun covers(cell: Cell?): Boolean = cell?.layer != null

    /** Every cell (streaming walks them). */
    val all: Collection<Cell> get() = cells.values

    val cellCount: Int get() = cells.size
    val readyCount: Int get() = cells.values.count { it.layer != null }
    val triangles: Int get() = cells.values.sumOf { it.triangles }
    /** The districts ([District]): how many, how many up, their triangles. */
    val districtReport: String get() = "%d districts, %d up, %d triangles".format(districts.size, districts.values.count { it.layer != null }, districts.values.sumOf { it.triangles })

    fun destroy() { for (c in cells.values) { c.layer?.destroy(); c.packing?.cancel(true) }; cells.clear(); for (d in districts.values) { d.layer?.destroy(); d.packing?.cancel(true) }; districts.clear() }

    companion object {
        /** A cell's side (street units). */
        const val CELL = 900f
        /** A district is this many cells a side ([District]). */
        const val DISTRICT = 3
        /** Past this from the eye a district's coarse mesh stands in for its cells' far boxes (street units), switching this far either side. */
        const val SLOD_FROM = 5200f
        const val SLOD_SLACK = 300f
        /** The coarser level on or off (a preview's -Dslod=0 to compare). */
        @JvmStatic var slod = true

        /** [img] shrunk four times more (each texel the mean of its 4 x 4 block; [glows]: a texel glows where a quarter of its block did). */
        fun shrink4(img: PixImg, glows: Boolean): PixImg {
            val ow = max(1, img.w / 4); val oh = max(1, img.h / 4)
            val out = PixImg(ow, oh)
            val m = if (glows && img.glows) img.mask else null
            for (oy in 0 until oh) for (ox in 0 until ow) {
                var r = 0; var g = 0; var b = 0; var n = 0; var lit = 0
                for (y in oy * 4 until min(img.h, oy * 4 + 4)) for (x in ox * 4 until min(img.w, ox * 4 + 4)) {
                    val c = img.px[y * img.w + x]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                    if (m != null && ((m[y * img.w + x] ushr 16) and 255) > 0) lit++
                }
                out.px[oy * ow + ox] = if (n == 0) 0xFF707078.toInt() else (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (lit * 4 >= n && lit > 0) out.glowAt(ox, oy)
            }
            return out
        }
        /** The far boxes are drawn out to this from the eye (street units): the far end of the town and its woods. */
        const val REACH = 24000f
        /** A cell switches this far either side of the detail line (street units). */
        const val SLACK = 200f
        /** How long a cell's crossing over takes. */
        const val SWITCH_SECONDS = .3f
        /** The share of a far box's windows lit at night. */
        const val FAR_LIT = .3f
        /** The far pictures are the building's shrunk this many times. */
        const val SHRINK = 4

        /**
         * A building's far pictures, from its own (any thread): the front and side shrunk [SHRINK] times (each texel the
         * mean of its block, see-through texels left out), the lit windows of [SpriteBuilding.emis] glowing at night, the
         * roof the colour of the front's top rows.
         */
        fun pics(sb: SpriteBuilding): Pics? {
            val front = shrink(sb.front, sb.emis) ?: return null
            val side = shrink(sb.side, null) ?: front
            // the roof: the front's top band's mean colour, darkened a little (seen from above it reads as the roof's)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until min(2, front.h)) for (x in 0 until front.w) {
                val c = front.px[y * front.w + x]; if ((c ushr 24) < 128) continue
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
            }
            val roof = if (n == 0L) 0xFF6A6A70.toInt() else (0xFF shl 24) or (((r / n) * 8 / 10).toInt() shl 16) or (((g / n) * 8 / 10).toInt() shl 8) or ((b / n) * 8 / 10).toInt()
            return Pics(front, side, roof)
        }

        private fun shrink(img: androidx.compose.ui.graphics.ImageBitmap, glow: androidx.compose.ui.graphics.ImageBitmap?): PixImg? {
            val w = img.width; val h = img.height
            if (w < 2 || h < 2) return null
            val px = IntArray(w * h); img.readPixels(px, 0, 0, w, h)
            val gp = glow?.takeIf { it.width == w && it.height == h }?.let { g -> IntArray(w * h).also { g.readPixels(it, 0, 0, w, h) } }
            val ow = max(1, w / SHRINK); val oh = max(1, h / SHRINK)
            val out = PixImg(ow, oh)
            for (oy in 0 until oh) for (ox in 0 until ow) {
                var r = 0; var g = 0; var b = 0; var n = 0; var lit = 0
                for (y in oy * SHRINK until min(h, oy * SHRINK + SHRINK)) for (x in ox * SHRINK until min(w, ox * SHRINK + SHRINK)) {
                    val c = px[y * w + x]
                    if ((c ushr 24) < 128) continue
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                    if (gp != null && (gp[y * w + x] ushr 24) > 100) lit++
                }
                // (a block with no solid texel takes its neighbours' wall: the far box has no holes)
                out.px[oy * ow + ox] = if (n == 0) 0 else (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                // (a far box's windows are a fixed picture, not by the hour: a third of them lit, as an evening's tower
                // is, not every one: 0.1.402's glowing white towers at midnight)
                if (lit * 3 >= SHRINK * SHRINK && com.lifemax.rpg.feature.habits.domain.Ambient.hash(w * 31 + h, ox * 131 + oy, 917) < FAR_LIT) out.glowAt(ox, oy)
            }
            // fill the holes from the texel above (else below), so the box is solid all over
            for (x in 0 until ow) {
                var last = 0
                for (y in 0 until oh) { val i = y * ow + x; if (out.px[i] == 0) out.px[i] = last else last = out.px[i] }
                last = 0
                for (y in oh - 1 downTo 0) { val i = y * ow + x; if (out.px[i] == 0) out.px[i] = last else last = out.px[i] }
            }
            for (i in out.px.indices) if (out.px[i] == 0) out.px[i] = 0xFF707078.toInt()
            return out
        }

        @Suppress("unused") private fun len(x: Float, z: Float) = sqrt(x * x + z * z)
    }
}
