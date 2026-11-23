package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldTerrain
import com.lifemax.rpg.feature.habits.domain.WorldNoise
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The world beyond the town (WORLD_GEN.md phase W4: the first simple pass; W5 is the real terrain renderer): a
 * [TownExtra] drawing [WorldFarPlan] (the skeleton sampled round the town) in the town's lit material, to the horizon.
 *
 * - the terrain's three levels as faceted low-poly ground (a flat normal a triangle, so the relief reads in the sun), a
 *   texel a quad of the biome palette, nearest-filtered; skirts on the finer levels' rims so no crack shows;
 * - the ocean a flat plane at sea level; rivers, highways, county roads and rail lines as ribbons on the ground, wider
 *   further from the town so they still read as lines far off; the other towns as a few blocks each, their windows lit
 *   at night.
 *
 * Camera-relative (WORLD_GEN.md 1): every mesh is packed in its own local frame (a level's from its corner, the lines'
 * and blocks' from the far view's middle), each an entity whose root transform is its own origin less [origin]'s render
 * origin, set again whenever that moves ([RenderOrigin.generation]). While the player is in the town the render origin
 * is the town's own, so nothing of the town moves.
 *
 * Built on the town's workers once ([step] starts it, uploads one mesh a call), drawn every frame without shadows (the
 * town's cascades stay on the town); its memory goes to [WorldMemory] (the breadcrumbs' and the fit test's world line).
 */
internal class WorldFar(
    private val kit: TownExtra.Kit, private val planOf: () -> WorldFarPlan, private val origin: RenderOrigin,
    /**
     * The town's own grass (m from the plan's origin: x0, z0, x1, z1; TownGround's extent): with the near terrain the town
     * draws no fields past it, so the finest level covers everything but this; null: the town's ground ([WorldFarPlan.outside]).
     */
    private val townGrass: DoubleArray? = null,
) : TownExtra {
    /** The plan, made on a worker at the first [step]. */
    @Volatile var plan: WorldFarPlan? = null; private set
    private val u = origin.unitsPerMetre.toFloat()

    /** A mesh of the far view; [block]: the near terrain's root block ([blockKey]) it lies in, hidden where that is drawn near. */
    private class Part(val name: String, val anchor: WorldPos, val packed: PropLayer.Packed, val block: Long? = null) { var layer: PropLayer? = null; var gen = -1 }
    private var job: java.util.concurrent.Future<List<Part>>? = null
    private var parts: List<Part>? = null
    private var buildMs = 0f

    override val pending: Boolean get() = parts?.any { it.layer == null } ?: true
    override val report: String get() = "world far view: ${plan?.report ?: "not planned yet"}; ${WorldMemory.triangles} triangles in ${WorldMemory.draws} draws, built in %.0f ms".format(java.util.Locale.US, buildMs)

    override fun step() {
        if (job == null && parts == null) job = kit.pool.submit(java.util.concurrent.Callable { val t0 = System.nanoTime(); build().also { buildMs = (System.nanoTime() - t0) / 1e6f } })
        val j = job
        if (j != null && j.isDone) {
            parts = try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("building the world's far view", e.cause ?: e) }
            job = null
            WorldMemory.plan = parts!!.sumOf { it.packed.heldBytes }
        }
        // one upload a call (a big mesh is a frame's work on the phone)
        val p = parts?.firstOrNull { it.layer == null } ?: return
        val l = PropLayer(kit.engine, kit.scene, kit.lit, null, sharedTextures = kit.textures, cull = false)
        l.seeThrough = false
        l.upload(p.packed, casts = false)
        p.layer = l
        val all = parts!!
        WorldMemory.gpuBuffers = all.sumOf { it.packed.bytes.toLong() * if (it.layer != null) 1 else 0 }
        WorldMemory.gpuTextures = all.filter { it.layer != null }.sumOf { (it.packed.tex.buf.capacity()).toLong() }
        WorldMemory.triangles = all.sumOf { it.layer?.triangles ?: 0 }
        WorldMemory.draws = all.sumOf { it.layer?.draws ?: 0 }
        if (all.all { it.layer != null }) WorldMemory.plan = 0
    }

    private val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val at = FloatArray(2)

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        var draws = 0; var tris = 0
        for (p in parts ?: emptyList()) {
            val l = p.layer ?: continue
            if (p.gen != origin.generation) {
                // (the root transform: the part's own origin less the render origin, in doubles, then floats)
                origin.toRender(p.anchor, at)
                m[12] = at[0]; m[13] = 0f; m[14] = at[1]
                l.setTransform(m)
                p.gen = origin.generation
            }
            val shown = !hidden && (p.block == null || p.block !in covered)
            l.update(f.look, f.dark, shown, 1f, false)
            if (shown) { draws += l.draws; tris += l.triangles }
        }
        lastFrame = f
        out[0] = draws; out[1] = tris; out[2] = 0
        return out
    }

    private var lastFrame: TownExtra.Frame? = null
    /** The root blocks the near terrain draws this frame ([blockKey]); their parts of the far view are hidden. */
    private var covered: Set<Long> = emptySet()

    /**
     * The near terrain covers [blocks] from now on (WorldNear calls it each frame, before or after this frame's [frame]:
     * the parts' visibility is set again at once, so the two never both draw a block, nor neither).
     */
    fun cover(blocks: Set<Long>) {
        if (blocks == covered) return
        covered = blocks
        val f = lastFrame ?: return
        for (p in parts ?: return) {
            val b = p.block ?: continue
            val l = p.layer ?: continue
            l.update(f.look, f.dark, !hidden && b !in blocks, 1f, false)
        }
    }

    /** Whether every part of the far view is uploaded. */
    val ready: Boolean get() = parts?.all { it.layer != null } == true

    /** Leaves it out of the frames (a comparison). */
    var hidden = false

    /** The near terrain drawn with it (W5a), if any. */
    var near: WorldNear? = null

    override fun destroy() { job?.cancel(true); parts?.forEach { it.layer?.destroy() }; parts = null; WorldMemory.gpuBuffers = 0; WorldMemory.gpuTextures = 0; WorldMemory.triangles = 0; WorldMemory.draws = 0 }

    // ---------------------------------------------------------------- building (a worker)

    /** The near terrain's ground (W5a), made with the plan when the near terrain is on: its finest level wears the near's materials. */
    @Volatile var terrain: WorldTerrain? = null; private set

    private fun build(): List<Part> {
        val plan = planOf().also { this.plan = it }
        if (townGrass != null) terrain = WorldTerrain(plan.sk, plan, townGrass)
        val out = ArrayList<Part>()
        for ((k, lv) in plan.levels.withIndex()) out += if (k == 0) blocks(lv) else listOfNotNull(level(k, lv))
        val mid = plan.levels.first().let { doubleArrayOf(it.ox + it.n * it.spacing / 2, it.oz + it.n * it.spacing / 2) }
        val anchor = plan.origin.plus(mid[0], mid[1])
        ocean()?.let { out += Part("ocean", anchor, it) }
        out += lines(mid, anchor)
        boxes(mid)?.let { out += Part("towns", anchor, it) }
        return out
    }

    /**
     * Triangles written straight into the vertex and index buffers (no face objects in between: the far view is about
     * 85,000 triangles, and the town's memory check saw the general mesh builder's faces hold 90 MB at the build's peak):
     * each a flat normal (facing up for the ground), its corners' uv in [0, 1] of [tex] ([texW] x [texH]). Count first
     * ([count]), then [begin], then the same triangles again.
     */
    private class Pack(private val tex: IntArray, private val texW: Int, private val texH: Int, private val mask: IntArray? = null, private val mips: Boolean = true) {
        private var nv = 0
        private var counting = true
        private var vb: java.nio.ByteBuffer? = null
        private var ib: java.nio.ByteBuffer? = null
        private var short = true
        private var written = 0
        private val q = FloatArray(4)
        private val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        fun begin() {
            counting = false
            short = TownVertex.short(nv)
            vb = TownMemory.direct(nv * TownVertex.STRIDE).order(java.nio.ByteOrder.nativeOrder())
            ib = TownMemory.direct(nv * (if (short) 2 else 4)).order(java.nio.ByteOrder.nativeOrder())
        }

        /** A triangle (a, b, c: x, y, z each) wearing uv ([ua], [va])... ; [up]: its normal turned to face up. */
        fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
                ua: Float, va: Float, ub: Float, vb_: Float, uc: Float, vc: Float, up: Boolean = true) {
            if (counting) { nv += 3; return }
            // normal = (c - a) x (b - a), as MeshBuilder.quad's
            val e1x = cx - ax; val e1y = cy - ay; val e1z = cz - az
            val e2x = bx - ax; val e2y = by - ay; val e2z = bz - az
            var nx = e1y * e2z - e1z * e2y; var ny = e1z * e2x - e1x * e2z; var nz = e1x * e2y - e1y * e2x
            var l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-9f) { nx = 0f; ny = 1f; nz = 0f; l = 1f }
            nx /= l; ny /= l; nz /= l
            if (up && ny < 0f) { nx = -nx; ny = -ny; nz = -nz }
            // tangent along a to b, made square to the normal
            var tx = e2x; var ty = e2y; var tz = e2z
            val d = tx * nx + ty * ny + tz * nz
            tx -= d * nx; ty -= d * ny; tz -= d * nz
            val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= tl; ty /= tl; tz /= tl
            val bx2 = ny * tz - nz * ty; val by2 = nz * tx - nx * tz; val bz2 = nx * ty - ny * tx
            com.google.android.filament.MathUtils.packTangentFrame(tx, ty, tz, bx2, by2, bz2, nx, ny, nz, q)
            val v = vb!!
            fun put(x: Float, y: Float, z: Float, u: Float, w: Float) {
                TownVertex.put(v, x, y, z, u, w, q)
                if (x < box[0]) box[0] = x; if (y < box[1]) box[1] = y; if (z < box[2]) box[2] = z
                if (x > box[3]) box[3] = x; if (y > box[4]) box[4] = y; if (z > box[5]) box[5] = z
            }
            put(ax, ay, az, ua, va); put(bx, by, bz, ub, vb_); put(cx, cy, cz, uc, vc)
            val i = ib!!
            for (k in 0 until 3) if (short) i.putShort((written + k).toShort()) else i.putInt(written + k)
            written += 3
        }

        /** A flat-coloured triangle: texel ([tu], [tv]) (its centre) of the picture. */
        fun flat(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, tu: Float, tv: Float, up: Boolean = true) {
            val u = tu / texW; val v = tv / texH
            tri(ax, ay, az, bx, by, bz, cx, cy, cz, u, v, u, v, u, v, up)
        }

        fun done(): PropLayer.Packed? {
            if (nv == 0) return null
            val v = vb!!; val i = ib!!
            v.flip(); i.flip()
            val b = floatArrayOf((box[0] + box[3]) / 2f, (box[1] + box[4]) / 2f, (box[2] + box[5]) / 2f, (box[3] - box[0]) / 2f + .5f, (box[4] - box[1]) / 2f + .5f, (box[5] - box[2]) / 2f + .5f)
            val t = StreetTextures.prepareShared(tex, texW, texH, linear = false, share = null, mips = mips).first
            val m = mask?.let { StreetTextures.prepareShared(it, texW, texH, linear = true, share = null, mips = false).first }
            return PropLayer.Packed(v, i, nv, nv, b, t, m, true, short)
        }
    }

    /** Runs [draw] twice on a [Pack]: counting, then writing. */
    private inline fun pack(p: Pack, draw: (Pack) -> Unit): PropLayer.Packed? { draw(p); p.begin(); draw(p); return p.done() }

    private fun level(k: Int, lv: WorldFarPlan.Level): Part? {
        val plan = plan!!
        val n = lv.n
        val img = IntArray(n * n) { lv.colours[it].takeIf { c -> c != 0 } ?: 0xFF000000.toInt() }
        val s = (lv.spacing * u).toFloat()
        fun y(i: Int, j: Int) = lv.h(i, j) * u
        val drop = (if (k == 0) 160.0 else 500.0).toFloat() * u
        val skirts = k < plan.levels.size - 1
        val packed = pack(Pack(img, n, n)) { p ->
            for (j in 0 until n) for (i in 0 until n) {
                if (lv.skipped(i, j) || lv.colours[j * n + i] == 0) continue
                val x0 = i * s; val x1 = (i + 1) * s; val z0 = j * s; val z1 = (j + 1) * s
                val tu = i + .5f; val tv = j + .5f
                // (split from (i, j) to (i+1, j+1), as WorldFarPlan.surface has it)
                p.flat(x0, y(i, j), z0, x1, y(i + 1, j), z0, x1, y(i + 1, j + 1), z1, tu, tv)
                p.flat(x0, y(i, j), z0, x1, y(i + 1, j + 1), z1, x0, y(i, j + 1), z1, tu, tv)
            }
            // the rim's skirt (the finer levels): straight down, the edge quad's colour, so the coarser level's edge never shows sky
            if (skirts) {
                fun skirt(i0: Int, j0: Int, i1: Int, j1: Int, ti: Int, tj: Int) {
                    val ax = i0 * s; val az = j0 * s; val bx = i1 * s; val bz = j1 * s
                    val ay = y(i0, j0); val by = y(i1, j1)
                    p.flat(ax, ay, az, bx, by, bz, bx, by - drop, bz, ti + .5f, tj + .5f, up = false)
                    p.flat(ax, ay, az, bx, by - drop, bz, ax, ay - drop, az, ti + .5f, tj + .5f, up = false)
                }
                for (i in 0 until n) { skirt(i, 0, i + 1, 0, i, 0); skirt(i + 1, n, i, n, i, n - 1) }
                for (j in 0 until n) { skirt(n, j, n, j + 1, n - 1, j); skirt(0, j + 1, 0, j, 0, j) }
            }
        } ?: return null
        return Part("level $k", plan.origin.plus(lv.ox, lv.oz), packed)
    }

    /** Whether quad ([i], [j]) of the finest level is drawn: not wholly on the town's grass (or, without it, the town's ground). */
    private fun drawn(lv: WorldFarPlan.Level, i: Int, j: Int): Boolean {
        val g = townGrass ?: return lv.colours[j * lv.n + i] != 0
        val x0 = lv.ox + i * lv.spacing; val z0 = lv.oz + j * lv.spacing
        return !(x0 >= g[0] && x0 + lv.spacing <= g[2] && z0 >= g[1] && z0 + lv.spacing <= g[3])
    }

    /**
     * The finest level as a part a root block of the near terrain (eight quads a side: [WorldTerrain.ROOT_TILES]), each
     * with a skirt round its rim, so the near terrain can take any block's place and neither leaves a crack.
     */
    private fun blocks(lv: WorldFarPlan.Level): List<Part> {
        val plan = plan!!
        val n = lv.n
        // (a quad wholly on the town's ground has no colour of its own in the plan: the town's grass, where it is drawn;
        // with the near terrain, each quad wears the near ground's material at its middle, in that picture's mean colour, so
        // the far view goes on as the near ground looks: the same fields, rock and water)
        val t = terrain
        val pt = WorldTerrain.Point()
        val img = IntArray(n * n) { k ->
            val own = lv.colours[k].takeIf { c -> c != 0 } ?: WorldFarPlan.TOWN_GRASS
            if (t == null) return@IntArray own
            val i = k % n; val j = k / n
            // (towards the level's rim the biome's own colours take over, dithered a quad at a time, so the next level's
            // biome colours meet it with no square edge)
            val rim = min(min(i, n - 1 - i), min(j, n - 1 - j))
            if (rim < RIM_FADE && WorldNoise.hashU(RIM_SEED, i, j) * RIM_FADE > rim) return@IntArray own
            val x = lv.ox + (i + .5) * lv.spacing; val z = lv.oz + (j + .5) * lv.spacing
            val cx = (plan.origin.x + x) / Planet.CELL_M; val cz = (plan.origin.z + z) / Planet.CELL_M
            t.pointAt(cx, cz, x, z, pt)
            val sl = max(abs(lv.h(i + 1, j + 1) - lv.h(i, j)), abs(lv.h(i + 1, j) - lv.h(i, j + 1))) / (lv.spacing * 1.414)
            WorldTerrain.MEAN[t.material(cx, cz, x, z, pt, sl)]
        }
        val s = (lv.spacing * u).toFloat()
        fun y(i: Int, j: Int) = lv.h(i, j) * u
        val drop = 160f * u
        val per = Math.round(WorldTerrain.ROOT_TILES * Planet.TILE_M / lv.spacing)
        val q0x = Math.round((plan.origin.x + lv.ox) / lv.spacing); val q0z = Math.round((plan.origin.z + lv.oz) / lv.spacing)
        val by = LinkedHashMap<Long, MutableList<Int>>()
        for (j in 0 until n) for (i in 0 until n) {
            if (!drawn(lv, i, j)) continue
            by.getOrPut(blockKey(Math.floorDiv(q0x + i, per).toInt(), Math.floorDiv(q0z + j, per).toInt())) { ArrayList() } += j * n + i
        }
        val out = ArrayList<Part>()
        for ((b, quads) in by) {
            val set = quads.toHashSet()
            val packed = pack(Pack(img, n, n)) { p ->
                for (k in quads) {
                    val i = k % n; val j = k / n
                    val x0 = i * s; val x1 = (i + 1) * s; val z0 = j * s; val z1 = (j + 1) * s
                    val tu = i + .5f; val tv = j + .5f
                    // (split from (i, j) to (i+1, j+1), as WorldFarPlan.surface has it)
                    p.flat(x0, y(i, j), z0, x1, y(i + 1, j), z0, x1, y(i + 1, j + 1), z1, tu, tv)
                    p.flat(x0, y(i, j), z0, x1, y(i + 1, j + 1), z1, x0, y(i, j + 1), z1, tu, tv)
                    // the block's rim: a skirt straight down wherever the next quad is not in this block
                    fun skirt(i0: Int, j0: Int, i1: Int, j1: Int) {
                        val ax = i0 * s; val az = j0 * s; val bx = i1 * s; val bz = j1 * s
                        val ay = y(i0, j0); val by2 = y(i1, j1)
                        p.flat(ax, ay, az, bx, by2, bz, bx, by2 - drop, bz, tu, tv, up = false)
                        p.flat(ax, ay, az, bx, by2 - drop, bz, ax, ay - drop, az, tu, tv, up = false)
                    }
                    if (j == 0 || (j - 1) * n + i !in set) skirt(i, j, i + 1, j)
                    if (j == n - 1 || (j + 1) * n + i !in set) skirt(i + 1, j + 1, i, j + 1)
                    if (i == n - 1 || j * n + i + 1 !in set) skirt(i + 1, j, i + 1, j + 1)
                    if (i == 0 || j * n + i - 1 !in set) skirt(i, j + 1, i, j)
                }
            } ?: continue
            out += Part("level 0 ${blockName(b)}", plan.origin.plus(lv.ox, lv.oz), packed, b)
        }
        return out
    }

    private fun ocean(): PropLayer.Packed? {
        val plan = plan!!
        val r = (plan.reach * u).toFloat(); val y = plan.seaLevel * u
        return pack(Pack(intArrayOf(OCEAN, OCEAN, OCEAN, OCEAN), 2, 2)) { p ->
            p.flat(-r, y, -r, r, y, -r, r, y, r, 1f, 1f)
            p.flat(-r, y, -r, r, y, r, -r, y, r, 1f, 1f)
        }
    }

    /**
     * The lines as ribbons on the ground: those over the finest level cut at the root blocks' edges, a part a block (hidden
     * with its block where the near terrain paints the lines itself), the rest one part.
     */
    private fun lines(mid: DoubleArray, anchor: WorldPos): List<Part> {
        val plan = plan!!
        val cols = WorldFarPlan.LINE_COLOURS
        val by = LinkedHashMap<Long?, Tris>()
        // (drawn in order: county roads, highways, rail, then the rivers over all)
        for (kind in intArrayOf(WorldFarPlan.COUNTY, WorldFarPlan.HIGHWAY, WorldFarPlan.RAIL, WorldFarPlan.RIVER)) for (l in plan.lines) {
            if (l.kind != kind) continue
            val lift = when (kind) { WorldFarPlan.RIVER -> 2.0; WorldFarPlan.RAIL -> 3.5; WorldFarPlan.HIGHWAY -> 3.0; else -> 2.5 }
            ribbon(by, plan, l, kind + .5f, lift, mid)
        }
        // (no mip levels: a level's texel would be two lines' colours mixed)
        return by.mapNotNull { (b, t) -> pack(Pack(cols.copyOf(), cols.size, 1, mips = false)) { p -> t.draw(p) }?.let { Part(if (b == null) "lines" else "lines ${blockName(b)}", anchor, it, b) } }
    }

    /** Flat-coloured triangles kept to be packed later (x, y, z thrice, the texel's u, v). */
    private class Tris {
        val f = ArrayList<FloatArray>()
        fun add(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, tu: Float, tv: Float) { f += floatArrayOf(ax, ay, az, bx, by, bz, cx, cy, cz, tu, tv) }
        fun draw(p: Pack) { for (t in f) p.flat(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10]) }
    }

    /** The root block ([blockKey]) holding plan-local ([x], [z]) if it is on the finest level, else null. */
    private fun blockAt(plan: WorldFarPlan, x: Double, z: Double): Long? {
        if (!plan.levels.first().inside(x, z)) return null
        val s = WorldTerrain.ROOT_TILES * Planet.TILE_M
        return blockKey(floor((plan.origin.x + x) / s).toInt(), floor((plan.origin.z + z) / s).toInt())
    }

    /** A line as a ribbon on the surface: cut into pieces short enough to follow the ground (and at the blocks' edges), each a flat quad. */
    private fun ribbon(by: MutableMap<Long?, Tris>, plan: WorldFarPlan, l: WorldFarPlan.Line, tu: Float, lift: Double, mid: DoubleArray) {
        val pts = l.pts
        val bs = WorldTerrain.ROOT_TILES * Planet.TILE_M
        for (k in 0 until pts.size / 2 - 1) {
            val sx0 = pts[k * 2]; val sz0 = pts[k * 2 + 1]; val sx1 = pts[k * 2 + 2]; val sz1 = pts[k * 2 + 3]
            // (the stretch cut where it crosses a block's edge: each piece in one block)
            val cuts = ArrayList<Double>()
            cuts += 0.0; cuts += 1.0
            for (axis in 0..1) {
                val a = if (axis == 0) sx0 + plan.origin.x else sz0 + plan.origin.z
                val b = if (axis == 0) sx1 + plan.origin.x else sz1 + plan.origin.z
                if (abs(b - a) < 1e-9) continue
                var g = ceil(min(a, b) / bs) * bs
                while (g < max(a, b)) { cuts += (g - a) / (b - a); g += bs }
            }
            cuts.sort()
            for (c in 0 until cuts.size - 1) {
                val ta = cuts[c]; val tb = cuts[c + 1]
                if (tb - ta < 1e-9) continue
                val x0 = sx0 + (sx1 - sx0) * ta; val z0 = sz0 + (sz1 - sz0) * ta; val x1 = sx0 + (sx1 - sx0) * tb; val z1 = sz0 + (sz1 - sz0) * tb
                val t = by.getOrPut(blockAt(plan, (x0 + x1) / 2, (z0 + z1) / 2)) { Tris() }
                stretch(t, plan, l, x0, z0, x1, z1, tu, lift, mid)
            }
        }
    }

    private fun stretch(t: Tris, plan: WorldFarPlan, l: WorldFarPlan.Line, x0: Double, z0: Double, x1: Double, z1: Double, tu: Float, lift: Double, mid: DoubleArray) {
        val len = hypot(x1 - x0, z1 - z0)
        if (len < .01) return
        val far = hypot((x0 + x1) / 2 - mid[0], (z0 + z1) / 2 - mid[1])
        // (wider far off: at least about a pixel and a half of a phone's view, so a line still reads as one)
        val w = max(l.width.toDouble(), far * .0016)
        val step = when { far < 8000 -> 120.0; far < 50000 -> 500.0; else -> 1800.0 }
        val pieces = max(1, (len / step).toInt())
        val dx = (x1 - x0) / len; val dz = (z1 - z0) / len
        val nx = -dz * w / 2; val nz = dx * w / 2
        // (each piece a little longer at both ends: no gap shows at a bend)
        val ext = min(w / 2, len / pieces / 2)
        val up = lift + far * .0009
        for (q in 0 until pieces) {
            val t0 = q.toDouble() / pieces; val t1 = (q + 1.0) / pieces
            val ax = x0 + (x1 - x0) * t0 - dx * ext; val az = z0 + (z1 - z0) * t0 - dz * ext
            val bx = x0 + (x1 - x0) * t1 + dx * ext; val bz = z0 + (z1 - z0) * t1 + dz * ext
            val ya = (max(plan.surface(ax, az).toDouble(), plan.seaLevel.toDouble()) + up) * u
            val yb = (max(plan.surface(bx, bz).toDouble(), plan.seaLevel.toDouble()) + up) * u
            fun x(v: Double) = ((v - mid[0]) * u).toFloat()
            fun z(v: Double) = ((v - mid[1]) * u).toFloat()
            t.add(x(ax + nx), ya.toFloat(), z(az + nz), x(bx + nx), yb.toFloat(), z(bz + nz), x(bx - nx), yb.toFloat(), z(bz - nz), tu, .5f)
            t.add(x(ax + nx), ya.toFloat(), z(az + nz), x(bx - nx), yb.toFloat(), z(bz - nz), x(ax - nx), ya.toFloat(), z(az - nz), tu, .5f)
        }
    }

    private fun boxes(mid: DoubleArray): PropLayer.Packed? {
        val plan = plan!!
        if (plan.boxes.isEmpty()) return null
        // one picture: each wall colour's 6 x 4 front (its windows, some lit at night: the mask) in a column, the roofs under them
        val walls = WorldFarPlan.WALLS; val roofs = WorldFarPlan.ROOFS
        val tw = 6; val th = walls.size * 4 + roofs.size
        val tex = IntArray(tw * th); val mask = IntArray(tw * th)
        val lit = (255 shl 24) or (255 shl 16) or 255
        for ((k, c) in walls.withIndex()) {
            for (y in 0 until 4) for (x in 0 until tw) tex[(k * 4 + y) * tw + x] = c
            val win = shade(c, .55f)
            for (y in 1..2) for (x in intArrayOf(1, 3, 4)) tex[(k * 4 + y) * tw + x] = win
            for ((x, y) in listOf(1 to 1, 4 to 2, 3 to 1)) { tex[(k * 4 + y) * tw + x] = 0xFFFFD890.toInt(); mask[(k * 4 + y) * tw + x] = lit }
        }
        for ((k, c) in roofs.withIndex()) for (x in 0 until tw) tex[(walls.size * 4 + k) * tw + x] = c
        return pack(Pack(tex, tw, th, mask, mips = false)) { p ->
            for (b in plan.boxes) {
                val x0 = ((b.x - b.w / 2 - mid[0]) * u).toFloat(); val x1 = ((b.x + b.w / 2 - mid[0]) * u).toFloat()
                val z0 = ((b.z - b.d / 2 - mid[1]) * u).toFloat(); val z1 = ((b.z + b.d / 2 - mid[1]) * u).toFloat()
                val y0 = b.y * u; val y1 = (b.y + b.h) * u
                val wk = walls.indexOf(b.wall).coerceAtLeast(0); val rk = roofs.indexOf(b.roof).coerceAtLeast(0)
                val v0 = wk * 4f / th; val v1 = (wk * 4f + 4f) / th
                // the four walls (the picture's top at the wall's top), then the roof flat
                fun wall(ax: Float, az: Float, bx: Float, bz: Float) {
                    p.tri(ax, y1, az, bx, y1, bz, bx, y0, bz, 0f, v0, 1f, v0, 1f, v1, up = false)
                    p.tri(ax, y1, az, bx, y0, bz, ax, y0, az, 0f, v0, 1f, v1, 0f, v1, up = false)
                }
                wall(x0, z1, x1, z1); wall(x1, z0, x0, z0); wall(x1, z1, x1, z0); wall(x0, z0, x0, z1)
                p.flat(x0, y1, z0, x1, y1, z0, x1, y1, z1, 3f, walls.size * 4 + rk + .5f)
                p.flat(x0, y1, z0, x1, y1, z1, x0, y1, z1, 3f, walls.size * 4 + rk + .5f)
            }
        }
    }

    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 255) * k).toInt(); val g = ((c shr 8 and 255) * k).toInt(); val b = ((c and 255) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val OCEAN = 0xFF2C6296.toInt()

        /** A root block's key: the near terrain's top-level node ([WorldTerrain.Node]) at ([i], [j]), x wrapped round the planet. */
        fun blockKey(i: Int, j: Int): Long = (Math.floorMod(i, BLOCKS_ROUND).toLong() shl 32) or (j.toLong() and 0xffffffffL)
        private val BLOCKS_ROUND = (Planet.C / (WorldTerrain.ROOT_TILES * Planet.TILE_M)).roundToInt()
        fun blockName(b: Long): String = "${b shr 32},${b.toInt()}"

        /**
         * Adds the world round the town to [town] ([plan] made on a worker) (its far plane reaching the horizon, its own grass apron left out:
         * the world's ground runs on from the town's edge instead); [origin] is the app's render origin.
         */
        fun attach(town: FilamentTown, plan: () -> WorldFarPlan, origin: RenderOrigin, near: Boolean = nearOn): WorldFar {
            town.worldReach = (WorldFarPlan.REACH_M * origin.unitsPerMetre * 1.02).toFloat()
            town.worldUnitsPerMetre = origin.unitsPerMetre
            town.worldBaseM = origin.town?.elevM ?: 0.0
            // the town's own grass, in metres from the plan's origin (the town's frame while the plan is made in it)
            val u = origin.unitsPerMetre
            val grass = if (near) TownGround.boundsOf(town.town).let { b -> doubleArrayOf(b[0] / u, b[1] / u, b[2] / u, b[3] / u) } else null
            val far = town.addExtra { WorldFar(it, plan, origin, grass) }
            if (near) { far.near = WorldNear.attach(town, far, origin, grass); town.farFields = false }
            return far
        }

        /** The finest level's rim over which its colours fade from the near ground's materials to the biomes' (quads). */
        private const val RIM_FADE = 40
        private const val RIM_SEED = 0x5EED05AL

        /** Whether the near terrain (W5a) comes with the far view (previews: -DworldNear=0 for W4's far view alone). */
        @JvmStatic var nearOn = true
    }
}
