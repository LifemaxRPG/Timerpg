package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What a walker meets on a store's plot, read off the store's own geometry as built (so it is always what is drawn,
 * whatever the builders put there: the building, its vestibules and piers, canopy and lamp posts, the porch's posts and
 * railings, fences and the garden centre, corrals, cart rows, benches, the pylon's legs, docks, dumpsters, and the parked
 * cars of [StoreCars]): every triangle of [models] is cut by a few level planes between a step and head height
 * ([HEIGHTS]), the cuts are drawn into a grid of [CELL] on the plot, whatever the grid's border cannot reach (a building's
 * inside, a car's) is filled, and the cells are merged into rectangles. Ground, kerbs, painted lines, decks and steps lie
 * below the first plane and stay walkable; canopies, roofs, signs and tree crowns lie above the last.
 *
 * The rectangles are in the model's frame (x along the street, the plot's middle at 0; z out towards the road, the plot's
 * front edge at 0): x0, z0, x1, z1 each. Runs on a worker (some 10-60 ms for a superstore on a computer).
 */
object StoreSolids {
    /** The planes cut (street units above the ground: a step or porch deck is 12; the figure's head 36). */
    val HEIGHTS = floatArrayOf(16f, 25f, 34f)
    /** The grid's cell (street units: about 12 cm). */
    const val CELL = 2.5f

    fun of(models: List<CinemaModel>, lot: Float, deep: Float, fill: Boolean = true): FloatArray {
        val pad = 8f
        val gx0 = -lot / 2f - pad; val gz0 = -deep - pad
        val cols = ceil((lot + 2f * pad) / CELL).toInt() + 1
        val rows = ceil((deep + 2f * pad + 40f) / CELL).toInt() + 1
        val g = BooleanArray(cols * rows)
        fun mark(x: Float, z: Float) {
            val c = floor((x - gx0) / CELL).toInt(); val r = floor((z - gz0) / CELL).toInt()
            if (c in 0 until cols && r in 0 until rows) g[r * cols + c] = true
        }
        fun seg(ax: Float, az: Float, bx: Float, bz: Float) {
            val n = max(1, ceil(max(abs(bx - ax), abs(bz - az)) / (CELL * .5f)).toInt())
            for (k in 0..n) { val t = k.toFloat() / n; mark(ax + (bx - ax) * t, az + (bz - az) * t) }
        }
        val px = FloatArray(6)
        for (m in models) for (part in m.parts) {
            val mesh = part.mesh
            val bd = mesh.bounds
            if (bd[4] < HEIGHTS[0] || bd[1] > HEIGHTS[HEIGHTS.size - 1]) continue
            val p = mesh.pos; val idx = mesh.idx
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
                t += 3
                val ya = p[a + 1]; val yb = p[b + 1]; val yc = p[c + 1]
                val lo = min(ya, min(yb, yc)); val hi = max(ya, max(yb, yc))
                for (h in HEIGHTS) {
                    if (h < lo || h > hi || hi - lo < 1e-4f) continue
                    // the triangle's edges that cross the plane give the cut's two ends
                    var k = 0
                    fun edge(i: Int, j: Int) {
                        val yi = p[i + 1]; val yj = p[j + 1]
                        if ((yi - h) * (yj - h) > 0f || yi == yj || k >= 6) return
                        val s = (h - yi) / (yj - yi)
                        px[k] = p[i] + (p[j] - p[i]) * s; px[k + 1] = p[i + 2] + (p[j + 2] - p[i + 2]) * s; k += 2
                    }
                    edge(a, b); edge(b, c); edge(c, a)
                    if (k >= 4) seg(px[0], px[1], px[2], px[3]) else if (k == 2) mark(px[0], px[1])
                }
            }
        }
        if (fill) {
            // what the border cannot reach through free cells is inside something: solid
            val seen = BooleanArray(cols * rows)
            val queue = IntArray(cols * rows)
            var head = 0; var tail = 0
            fun push(i: Int) { if (!g[i] && !seen[i]) { seen[i] = true; queue[tail++] = i } }
            for (c in 0 until cols) { push(c); push((rows - 1) * cols + c) }
            for (r in 0 until rows) { push(r * cols); push(r * cols + cols - 1) }
            while (head < tail) {
                val i = queue[head++]; val c = i % cols; val r = i / cols
                if (c > 0) push(i - 1); if (c < cols - 1) push(i + 1)
                if (r > 0) push(i - cols); if (r < rows - 1) push(i + cols)
            }
            for (i in g.indices) if (!seen[i]) g[i] = true
        }
        // merge: each row's runs, a run carried down while the next row has the very same run
        val out = ArrayList<Float>()
        var prev = HashMap<Long, Int>()
        for (r in 0..rows) {
            val cur = HashMap<Long, Int>()
            if (r < rows) {
                var c = 0
                while (c < cols) {
                    if (!g[r * cols + c]) { c++; continue }
                    val s = c
                    while (c < cols && g[r * cols + c]) c++
                    val key = (s.toLong() shl 32) or c.toLong()
                    cur[key] = prev[key] ?: r
                }
            }
            for ((key, r0) in prev) if (cur[key] != r0) {
                val c0 = (key shr 32).toInt(); val c1 = (key and 0xffffffffL).toInt()
                out += gx0 + c0 * CELL; out += gz0 + r0 * CELL; out += gx0 + c1 * CELL; out += gz0 + r * CELL
            }
            prev = cur
        }
        return out.toFloatArray()
    }

    /**
     * [rects] (the model's frame) as world boxes for [com.lifemax.rpg.feature.habits.domain.TownCollision.setExtra]: six
     * floats each, the frame standing at [at] (world x, z, along x, z, out x, z: as [PropLayer.addMesh]).
     */
    fun world(rects: FloatArray, at: FloatArray): FloatArray {
        val n = rects.size / 4
        val o = FloatArray(n * 6)
        for (i in 0 until n) {
            val x0 = rects[i * 4]; val z0 = rects[i * 4 + 1]; val x1 = rects[i * 4 + 2]; val z1 = rects[i * 4 + 3]
            val mx = (x0 + x1) / 2f; val mz = (z0 + z1) / 2f
            o[i * 6] = at[0] + at[2] * mx + at[4] * mz; o[i * 6 + 1] = at[1] + at[3] * mx + at[5] * mz
            o[i * 6 + 2] = at[2]; o[i * 6 + 3] = at[3]
            o[i * 6 + 4] = (x1 - x0) / 2f; o[i * 6 + 5] = (z1 - z0) / 2f
        }
        return o
    }
}
