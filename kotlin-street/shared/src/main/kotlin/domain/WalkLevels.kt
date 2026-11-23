package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The heights a walker on foot stands on (docs/TOWN.md, "Open world: walkable heights"). The town's ground is flat (0) and
 * [TownCollision]'s shapes are flat outlines; a hand-made landmark's broad steps, terraces, plinths and plazas are not: a
 * [LevelField] holds, over a fine grid in the model's own frame, what is solid at which height (spans y0..y1 per cell),
 * made once on a worker from the model's drawn triangles and its listed collision boxes ([LevelField.Builder]). The walk
 * ([com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk]) asks [TownCollision.floorAt] what it stands on (the
 * highest top no more than [RISE] above its feet: it climbs a step, a stair, a kerb) and [TownCollision.levelBlocked]
 * whether something stands in its way between a step's height and its head (a terrace's wall, a balustrade, a pier):
 * a riser up to [RISE] is climbed, a taller face blocks, going down a step follows the tops, walking off an edge falls.
 * The flat outlines the same model put in [TownCollision] (for the cars, the people, the old street view) are marked
 * [TownCollision.STEPPED], and the walk on foot leaves them to the field. Queries allocate nothing.
 */

/**
 * One model's solid spans over a grid: [cell] street units a side, [cols] x [rows] from ([lx0], [lz0]) in its frame (world
 * = ([ox], [oz]) + lx * ([ax], [az]) + lz * ([bx], [bz]), the axes unit and square). Cells come in tiles of [TILE] x [TILE],
 * kept as small as they can be (heights in quarter units, as shorts): a tile of plain ground at one height keeps only that
 * ([plain]); one whose every cell is at most a thin top (lawns, plazas, gentle slopes) a top a cell ([tops]); the rest a
 * start per cell and their spans ([starts], [spans]).
 */
class LevelField internal constructor(
    val ox: Float, val oz: Float, val ax: Float, val az: Float, val bx: Float, val bz: Float,
    val lx0: Float, val lz0: Float, val cell: Float, val cols: Int, val rows: Int,
    private val plain: FloatArray,
    private val tops: Array<ShortArray?>,
    private val starts: Array<CharArray?>,
    private val spans: Array<ShortArray?>,
) {
    private val tcols = (cols + TILE - 1) / TILE
    /** The field's bounds in the world (x0, z0, x1, z1), for a quick test. */
    val wx0: Float; val wz0: Float; val wx1: Float; val wz1: Float
    init {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (k in 0..3) {
            val lx = lx0 + (if (k and 1 == 1) cols * cell else 0f); val lz = lz0 + (if (k and 2 == 2) rows * cell else 0f)
            val x = ox + ax * lx + bx * lz; val z = oz + az * lx + bz * lz
            x0 = min(x0, x); z0 = min(z0, z); x1 = max(x1, x); z1 = max(z1, z)
        }
        wx0 = x0; wz0 = z0; wx1 = x1; wz1 = z1
    }

    /** Bytes held (roughly), for the memory report. */
    val bytes: Long get() = plain.size * 4L + tops.sumOf { (it?.size ?: 0) * 2L } + starts.sumOf { (it?.size ?: 0) * 2L } + spans.sumOf { (it?.size ?: 0) * 2L }

    // the cell [find] found: its spans as a range into [rs], or one thin top [rTop] (NaN: none)
    private var rs: ShortArray? = null
    private var r0 = 0; private var r1 = 0; private var rTop = Float.NaN

    private fun find(x: Float, z: Float): Boolean {
        if (x < wx0 || x > wx1 || z < wz0 || z > wz1) return false
        val dx = x - ox; val dz = z - oz
        val c = floor((dx * ax + dz * az - lx0) / cell).toInt(); val r = floor((dx * bx + dz * bz - lz0) / cell).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return false
        val t = (r / TILE) * tcols + c / TILE
        val k = (r % TILE) * TILE + c % TILE
        val st = starts[t]
        if (st != null) { rs = spans[t]; r0 = st[k].code; r1 = st[k + 1].code; return true }
        rs = null
        val tp = tops[t]
        rTop = if (tp != null) { val q = tp[k]; if (q == EMPTY) Float.NaN else q / Q } else plain[t]
        return true
    }

    /** The highest top at ([x], [z]) no higher than [below] (world, street units), or NaN (outside, or nothing there). */
    fun top(x: Float, z: Float, below: Float): Float {
        if (!find(x, z)) return Float.NaN
        val s = rs ?: return if (!rTop.isNaN() && rTop <= below) rTop else Float.NaN
        var best = Float.NaN
        var i = r0
        while (i < r1) { val t = s[i + 1] / Q; if (t <= below && (best.isNaN() || t > best)) best = t; i += 2 }
        return best
    }

    /** The lowest top at ([x], [z]) above [above], or NaN. */
    fun topAbove(x: Float, z: Float, above: Float): Float {
        if (!find(x, z)) return Float.NaN
        val s = rs ?: return if (!rTop.isNaN() && rTop > above) rTop else Float.NaN
        var best = Float.NaN
        var i = r0
        while (i < r1) { val t = s[i + 1] / Q; if (t > above && (best.isNaN() || t < best)) best = t; i += 2 }
        return best
    }

    /** The spans at ([x], [z]) as text (for previews and tests), or null outside. */
    fun describe(x: Float, z: Float): String? {
        if (!find(x, z)) return null
        val s = rs ?: return if (rTop.isNaN()) "empty" else "top %.1f".format(rTop)
        return (r0 until r1 step 2).joinToString(" ") { "%.1f..%.1f".format(s[it] / Q, s[it + 1] / Q) }
    }

    /** Whether something solid at ([x], [z]) overlaps the heights [y0]..[y1] (open both ends). */
    fun hits(x: Float, z: Float, y0: Float, y1: Float): Boolean {
        if (!find(x, z)) return false
        val s = rs ?: return !rTop.isNaN() && rTop - SLAB < y1 && rTop > y0
        var i = r0
        while (i < r1) { if (s[i] / Q < y1 && s[i + 1] / Q > y0) return true; i += 2 }
        return false
    }

    /**
     * Gathers solid spans in the model's frame and makes the field: triangles ([addMesh]: positions x, y, z and indices,
     * already in the frame) and boxes ([addBox]). Up-facing triangles are sampled at each cell's middle (a top; down-facing
     * ones a bottom: a bottom and the next top above it make a solid span, a lone top a thin slab); upright ones (walls,
     * risers, posts, rails) are drawn along their foot as the heights they cover there. On a worker; tile by tile, so
     * a 120 m model takes a few MB while it is made.
     */
    class Builder(val cell: Float = CELL) {
        private val tris = ArrayList<FloatArray>()
        private var n = 0
        private val pos = FloatArray(9 * 1024)
        private val boxes = ArrayList<FloatArray>()
        private var x0 = Float.MAX_VALUE; private var z0 = Float.MAX_VALUE; private var x1 = -Float.MAX_VALUE; private var z1 = -Float.MAX_VALUE

        private fun flush() { if (n > 0) { tris += pos.copyOf(n * 9); n = 0 } }

        /** Triangles (positions x, y, z in the frame, indices); [water]: a pool's surface, a wall no walker goes into. */
        fun addMesh(p: FloatArray, idx: IntArray, water: Boolean = false) {
            if (!water) { addTris(p, idx); return }
            flush(); val from = tris.size
            addTris(p, idx); flush()
            for (k in from until tris.size) waterChunks += k
        }
        // (the chunks of triangles that are water: a water mesh is kept in chunks of its own)
        private val waterChunks = HashSet<Int>()

        private fun addTris(p: FloatArray, idx: IntArray) {
            var t = 0
            while (t + 2 < idx.size) {
                if (n == 1024) flush()
                val o = n * 9
                for (v in 0..2) { val a = idx[t + v] * 3; pos[o + v * 3] = p[a]; pos[o + v * 3 + 1] = p[a + 1]; pos[o + v * 3 + 2] = p[a + 2]
                    x0 = min(x0, p[a]); x1 = max(x1, p[a]); z0 = min(z0, p[a + 2]); z1 = max(z1, p[a + 2]) }
                n++; t += 3
            }
        }

        fun addBox(bx0: Float, bz0: Float, bx1: Float, bz1: Float, y0: Float, y1: Float) {
            boxes += floatArrayOf(bx0, bz0, bx1, bz1, y0, y1)
            x0 = min(x0, bx0); x1 = max(x1, bx1); z0 = min(z0, bz0); z1 = max(z1, bz1)
        }

        /** The field in [frame] (x, z of the origin, its x axis, its z axis: as the landmarks' frames), or null if empty. */
        fun build(frame: FloatArray): LevelField? {
            flush()
            if (x0 > x1 || z0 > z1) return null
            val gx0 = floor(x0 / cell) * cell - cell; val gz0 = floor(z0 / cell) * cell - cell
            val cols = ceil((x1 - gx0) / cell).toInt() + 2; val rows = ceil((z1 - gz0) / cell).toInt() + 2
            val tc = (cols + TILE - 1) / TILE; val tr = (rows + TILE - 1) / TILE
            // the triangles and boxes each tile's cells may meet (by their bounds)
            val tileTris = arrayOfNulls<IntArrayList>(tc * tr)
            val tileBoxes = arrayOfNulls<IntArrayList>(tc * tr)
            val span = cell * TILE
            fun bin(list: Array<IntArrayList?>, id: Int, ax0: Float, az0: Float, ax1: Float, az1: Float) {
                val c0 = ((ax0 - gx0) / span - .01f).toInt().coerceIn(0, tc - 1); val c1 = ((ax1 - gx0) / span + .01f).toInt().coerceIn(0, tc - 1)
                val r0 = ((az0 - gz0) / span - .01f).toInt().coerceIn(0, tr - 1); val r1 = ((az1 - gz0) / span + .01f).toInt().coerceIn(0, tr - 1)
                for (r in r0..r1) for (c in c0..c1) (list[r * tc + c] ?: IntArrayList().also { list[r * tc + c] = it }).add(id)
            }
            for ((ci, chunk) in tris.withIndex()) {
                var o = 0
                var id = ci * 1024
                while (o < chunk.size) {
                    bin(tileTris, id, min(chunk[o], min(chunk[o + 3], chunk[o + 6])), min(chunk[o + 2], min(chunk[o + 5], chunk[o + 8])),
                        max(chunk[o], max(chunk[o + 3], chunk[o + 6])), max(chunk[o + 2], max(chunk[o + 5], chunk[o + 8])))
                    o += 9; id++
                }
            }
            for ((k, b) in boxes.withIndex()) bin(tileBoxes, k, b[0], b[1], b[2], b[3])
            val plain = FloatArray(tc * tr) { Float.NaN }
            val tops = arrayOfNulls<ShortArray>(tc * tr)
            val starts = arrayOfNulls<CharArray>(tc * tr)
            val spans = arrayOfNulls<ShortArray>(tc * tr)
            val work = TileWork(cell)
            for (t in 0 until tc * tr) {
                val tt = tileTris[t]; val tb = tileBoxes[t]
                if (tt == null && tb == null) continue
                val cx0 = gx0 + (t % tc) * span; val cz0 = gz0 + (t / tc) * span
                work.reset(cx0, cz0)
                tt?.let { l -> for (i in 0 until l.size) { val g = l[i]; work.triangle(tris[g / 1024], (g % 1024) * 9, (g / 1024) in waterChunks) } }
                tb?.let { l -> for (i in 0 until l.size) work.box(boxes[l[i]]) }
                when (work.resolve()) {
                    TileWork.PLAIN -> plain[t] = work.plainY
                    TileWork.TOPS -> tops[t] = work.tops()
                    else -> { starts[t] = work.starts(); spans[t] = work.spans() }
                }
            }
            return LevelField(frame[0], frame[1], frame[2], frame[3], frame[4], frame[5], gx0, gz0, cell, cols, rows, plain, tops, starts, spans)
        }
    }

    /** One tile's gathering: entries (cell, kind, y0, y1), then per cell its merged spans. */
    private class TileWork(val cell: Float) {
        var cx0 = 0f; var cz0 = 0f
        var ent = FloatArray(4 * 4096); var en = 0
        val outStarts = IntArray(TILE * TILE + 1)
        var outSpans = FloatArray(4096); var outN = 0
        private val count = IntArray(TILE * TILE + 1)
        private var order = IntArray(4096)
        private var sy = FloatArray(64); private var sk = IntArray(64)
        private var sp = FloatArray(128)

        fun reset(x: Float, z: Float) { cx0 = x; cz0 = z; en = 0 }

        private fun put(c: Int, kind: Int, y0: Float, y1: Float) {
            if (en * 4 + 4 > ent.size) ent = ent.copyOf(ent.size * 2)
            val o = en * 4; ent[o] = c.toFloat(); ent[o + 1] = kind.toFloat(); ent[o + 2] = y0; ent[o + 3] = y1; en++
        }

        fun box(b: FloatArray) {
            val c0 = ceil((b[0] - cx0) / cell - .5f).toInt().coerceAtLeast(0); val c1 = floor((b[2] - cx0) / cell - .5f).toInt().coerceAtMost(TILE - 1)
            val r0 = ceil((b[1] - cz0) / cell - .5f).toInt().coerceAtLeast(0); val r1 = floor((b[3] - cz0) / cell - .5f).toInt().coerceAtMost(TILE - 1)
            for (r in r0..r1) for (c in c0..c1) put(r * TILE + c, WALL, b[4], b[5])
            // (a box thinner than a cell still stands where it is: its middle's cell)
            if (c0 > c1 || r0 > r1) {
                val c = floor(((b[0] + b[2]) / 2f - cx0) / cell).toInt(); val r = floor(((b[1] + b[3]) / 2f - cz0) / cell).toInt()
                if (c in 0 until TILE && r in 0 until TILE) put(r * TILE + c, WALL, b[4], b[5])
            }
        }

        fun triangle(p: FloatArray, o: Int, water: Boolean) {
            val ax = p[o]; val ay = p[o + 1]; val az = p[o + 2]
            val bx = p[o + 3]; val by = p[o + 4]; val bz = p[o + 5]
            val cx = p[o + 6]; val cy = p[o + 7]; val cz = p[o + 8]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-6f) return
            val upness = ny / nl
            if (abs(upness) >= UPRIGHT) {
                // a top or a bottom: sampled at the middles of the cells it covers
                val kind = if (upness > 0f) UP else DOWN
                if (water && kind == DOWN) return
                val c0 = ceil((min(ax, min(bx, cx)) - cx0) / cell - .5f).toInt().coerceAtLeast(0)
                val c1 = floor((max(ax, max(bx, cx)) - cx0) / cell - .5f).toInt().coerceAtMost(TILE - 1)
                val r0 = ceil((min(az, min(bz, cz)) - cz0) / cell - .5f).toInt().coerceAtLeast(0)
                val r1 = floor((max(az, max(bz, cz)) - cz0) / cell - .5f).toInt().coerceAtMost(TILE - 1)
                val d = ux * vz - uz * vx // twice the signed area on the ground
                if (abs(d) < 1e-9f) return
                for (r in r0..r1) for (c in c0..c1) {
                    val px = cx0 + (c + .5f) * cell - ax; val pz = cz0 + (r + .5f) * cell - az
                    val s = (px * vz - pz * vx) / d; val t = (ux * pz - uz * px) / d
                    if (s < -1e-4f || t < -1e-4f || s + t > 1f + 1e-4f) continue
                    val y = ay + uy * s + vy * t
                    if (water) put(r * TILE + c, WALL, y - SLAB, y + WATER_WALL) else put(r * TILE + c, kind, y, y)
                }
                return
            }
            if (water) return
            // upright: along its foot (the line through its two furthest-apart corners on the ground), the heights it
            // covers at each point (where the cut through it there meets its edges)
            var lx = bx - ax; var lz = bz - az; var sx = ax; var sz = az
            val l1 = lx * lx + lz * lz; val l2 = (cx - ax) * (cx - ax) + (cz - az) * (cz - az); val l3 = (cx - bx) * (cx - bx) + (cz - bz) * (cz - bz)
            if (l2 > l1 && l2 >= l3) { lx = cx - ax; lz = cz - az } else if (l3 > l1 && l3 > l2) { sx = bx; sz = bz; lx = cx - bx; lz = cz - bz }
            val len = sqrt(lx * lx + lz * lz)
            if (len < 1e-5f) {
                // a vertical sliver (a post's corner edge seen end on): its one cell, all its heights
                val c = floor((ax - cx0) / cell).toInt(); val r = floor((az - cz0) / cell).toInt()
                if (c in 0 until TILE && r in 0 until TILE) put(r * TILE + c, WALL, min(ay, min(by, cy)), max(ay, max(by, cy)))
                return
            }
            val dx = lx / len; val dz = lz / len
            val sa = (ax - sx) * dx + (az - sz) * dz; val sb = (bx - sx) * dx + (bz - sz) * dz; val sc = (cx - sx) * dx + (cz - sz) * dz
            val steps = max(1, ceil(len / (cell * .5f)).toInt())
            for (k in 0..steps) {
                val s = len * k / steps
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                fun edge(s0: Float, y0: Float, s1: Float, y1: Float) {
                    if (s < min(s0, s1) - 1e-4f || s > max(s0, s1) + 1e-4f) return
                    val y = if (abs(s1 - s0) < 1e-5f) { lo = min(lo, min(y0, y1)); hi = max(hi, max(y0, y1)); return } else y0 + (y1 - y0) * (s - s0) / (s1 - s0)
                    lo = min(lo, y); hi = max(hi, y)
                }
                edge(sa, ay, sb, by); edge(sb, by, sc, cy); edge(sc, cy, sa, ay)
                if (lo > hi) continue
                // (the cells a quarter of a cell either side of the foot: a wall on a cell's edge stands in both, a post
                // thinner than a cell is never missed)
                val px = sx + dx * s - cx0; val pz = sz + dz * s - cz0
                val c0 = floor(px / cell - .25f).toInt(); val c1 = floor(px / cell + .25f).toInt()
                val r0 = floor(pz / cell - .25f).toInt(); val r1 = floor(pz / cell + .25f).toInt()
                for (r in r0..r1) for (c in c0..c1) if (c in 0 until TILE && r in 0 until TILE) put(r * TILE + c, WALL, lo, hi)
            }
        }

        /** Per cell its spans (into [outStarts], [outSpans]); the tile's one height if it is plain ground throughout, else null. */
        fun resolve(): Int {
            val cells = TILE * TILE
            count.fill(0)
            for (i in 0 until en) count[ent[i * 4].toInt() + 1]++
            for (c in 0 until cells) count[c + 1] += count[c]
            if (order.size < en) order = IntArray(en * 2)
            val at = outStarts // (borrowed as the fill cursor, then rewritten below)
            System.arraycopy(count, 0, at, 0, cells + 1)
            for (i in 0 until en) { val c = ent[i * 4].toInt(); order[at[c]++] = i }
            outN = 0
            topsOnly = true
            var plainY = Float.NaN; var plain = true
            for (c in 0 until cells) {
                val a = count[c]; val b = count[c + 1]
                val first = outN
                // the tops and bottoms in height order; the uprights and boxes as they are
                var ns = 0; var np = 0
                for (j in a until b) {
                    val o = order[j] * 4
                    val kind = ent[o + 1].toInt()
                    if (kind == WALL) { if (np * 2 + 2 > sp.size) sp = sp.copyOf(sp.size * 2); sp[np * 2] = ent[o + 2]; sp[np * 2 + 1] = ent[o + 3]; np++ }
                    else { if (ns + 1 > sy.size) { sy = sy.copyOf(sy.size * 2); sk = sk.copyOf(sk.size * 2) }; sy[ns] = ent[o + 2]; sk[ns] = kind; ns++ }
                }
                // (insertion sort: a cell has a handful)
                for (i in 1 until ns) { val y = sy[i]; val k = sk[i]; var j = i - 1; while (j >= 0 && sy[j] > y) { sy[j + 1] = sy[j]; sk[j + 1] = sk[j]; j-- }; sy[j + 1] = y; sk[j + 1] = k }
                var open = Float.NaN; var prev = -Float.MAX_VALUE
                fun span(y0: Float, y1: Float) { if (np * 2 + 2 > sp.size) sp = sp.copyOf(sp.size * 2); sp[np * 2] = y0; sp[np * 2 + 1] = y1; np++ }
                for (i in 0 until ns) {
                    val y = sy[i]
                    if (sk[i] == DOWN) { if (open.isNaN()) open = y }
                    else { if (!open.isNaN()) { span(open, y); open = Float.NaN } else span(max(prev, y - SLAB), y) }
                    prev = y
                }
                if (!open.isNaN()) span(open, open + SLAB)
                // merged in height order
                for (i in 1 until np) { val y0 = sp[i * 2]; val y1 = sp[i * 2 + 1]; var j = i - 1; while (j >= 0 && sp[j * 2] > y0) { sp[j * 2 + 2] = sp[j * 2]; sp[j * 2 + 3] = sp[j * 2 + 1]; j-- }; sp[j * 2 + 2] = y0; sp[j * 2 + 3] = y1 }
                var i = 0
                while (i < np) {
                    var y0 = sp[i * 2]; var y1 = sp[i * 2 + 1]; i++
                    while (i < np && sp[i * 2] <= y1 + MERGE) { y1 = max(y1, sp[i * 2 + 1]); i++ }
                    if (outN + 2 > outSpans.size) outSpans = outSpans.copyOf(outSpans.size * 2)
                    outSpans[outN++] = y0; outSpans[outN++] = y1
                }
                outStarts[c] = first
                // (plain: one thin top at the same height in every cell, no more; tops: at most one thin top a cell)
                val m = outN - first
                val thin = m == 2 && outSpans[first + 1] - outSpans[first] <= SLAB + MERGE
                if (m > 2 || (m == 2 && !thin)) { plain = false; topsOnly = false }
                else if (m == 0 || (!plainY.isNaN() && abs(outSpans[first + 1] - plainY) > .01f)) plain = false
                else if (plainY.isNaN()) plainY = outSpans[first + 1]
            }
            outStarts[cells] = outN
            this.plainY = plainY
            // (a tile of more spans than its starts can count keeps each cell's highest top only: never met so far)
            return if (plain && !plainY.isNaN()) PLAIN else if (topsOnly || outN > 65535) TOPS else FULL
        }

        var plainY = Float.NaN
        private var topsOnly = true

        fun tops(): ShortArray = ShortArray(TILE * TILE) { c -> if (outStarts[c + 1] - outStarts[c] == 0) EMPTY else q(outSpans[outStarts[c + 1] - 1]) }
        fun starts(): CharArray = CharArray(TILE * TILE + 1) { outStarts[it].toChar() }
        fun spans(): ShortArray = ShortArray(outN) { q(outSpans[it]) }

        companion object { const val PLAIN = 0; const val TOPS = 1; const val FULL = 2 }
    }

    private class IntArrayList { var a = IntArray(8); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i] }

    companion object {
        /** The grid's cell (street units: about 14 cm, finer than a walker's 24 cm half width; walls are drawn a quarter cell thick either side, so posts and rails are met). */
        const val CELL = 3f
        const val TILE = 32
        /** A face this upright or less (the up component of its unit normal) is a wall, more a top or a bottom. */
        const val UPRIGHT = .3f
        /** A top with no bottom under it is taken as a slab this thick (street units); spans this near are one. */
        const val SLAB = 2f
        const val MERGE = .25f
        /** A pool's water stands as a wall this high over its surface: walked round, never into (as the river). */
        const val WATER_WALL = 60f
        private const val UP = 0; private const val DOWN = 1; private const val WALL = 2
        /** Heights are kept in quarter units ([q]); a cell with nothing in a tile of tops is [EMPTY]. */
        private const val Q = 4f
        private const val EMPTY = Short.MIN_VALUE
        private fun q(y: Float): Short = kotlin.math.round(y * Q).coerceIn(-32767f, 32767f).toInt().toShort()
    }
}

/** How high a walker steps and how tall they are (street units, [LandmarkKit]'s 21.2 a metre). */
object WalkHeight {
    /**
     * The highest riser climbed without a thought: a step, a kerb, a stair's (0.66 m: the hand-made monuments' broad
     * steps are 0.53 m, and the Great Meridian's flight to its arch has risers of 0.62 m where a tread dips behind its nosing, measured
     * from the model's own field; an ordinary stair's 0.15-0.2 m is a stroll). A low wall or planter up to this is
     * stepped onto too, as a player would scramble up it.
     */
    const val RISE = 14f
    /** Head height: something lower than this above the feet (and higher than [RISE]) blocks. */
    const val HEAD = 34f
    /** Falling (street units a second squared): 9.8 m/s2. */
    const val GRAVITY = 207f
}
