package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
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
internal class WorldFar(private val kit: TownExtra.Kit, private val planOf: () -> WorldFarPlan, private val origin: RenderOrigin) : TownExtra {
    /** The plan, made on a worker at the first [step]. */
    @Volatile var plan: WorldFarPlan? = null; private set
    private val u = origin.unitsPerMetre.toFloat()

    private class Part(val name: String, val anchor: WorldPos, val packed: PropLayer.Packed) { var layer: PropLayer? = null; var gen = -1 }
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
            l.update(f.look, f.dark, !hidden, 1f, false)
            if (!hidden) { draws += l.draws; tris += l.triangles }
        }
        out[0] = draws; out[1] = tris; out[2] = 0
        return out
    }

    /** Leaves it out of the frames (a comparison). */
    var hidden = false

    override fun destroy() { job?.cancel(true); parts?.forEach { it.layer?.destroy() }; parts = null; WorldMemory.gpuBuffers = 0; WorldMemory.gpuTextures = 0; WorldMemory.triangles = 0; WorldMemory.draws = 0 }

    // ---------------------------------------------------------------- building (a worker)

    private fun build(): List<Part> {
        val plan = planOf().also { this.plan = it }
        val out = ArrayList<Part>()
        for ((k, lv) in plan.levels.withIndex()) out += level(k, lv) ?: continue
        val mid = plan.levels.first().let { doubleArrayOf(it.ox + it.n * it.spacing / 2, it.oz + it.n * it.spacing / 2) }
        val anchor = plan.origin.plus(mid[0], mid[1])
        ocean()?.let { out += Part("ocean", anchor, it) }
        lines(mid)?.let { out += Part("lines", anchor, it) }
        boxes(mid)?.let { out += Part("towns", anchor, it) }
        return out
    }

    private fun level(k: Int, lv: WorldFarPlan.Level): Part? {
        val plan = plan!!
        val n = lv.n
        val img = PixImg(n, n)
        for (i in 0 until n * n) img.px[i] = lv.colours[i].takeIf { it != 0 } ?: 0xFF000000.toInt()
        val mb = MeshBuilder()
        val s = (lv.spacing * u).toFloat()
        fun y(i: Int, j: Int) = lv.h(i, j) * u
        val a = FloatArray(3); val b = FloatArray(3); val c = FloatArray(3); val d = FloatArray(3)
        for (j in 0 until n) for (i in 0 until n) {
            if (lv.skipped(i, j) || lv.colours[j * n + i] == 0) continue
            a[0] = i * s; a[1] = y(i, j); a[2] = j * s
            b[0] = (i + 1) * s; b[1] = y(i + 1, j); b[2] = j * s
            c[0] = (i + 1) * s; c[1] = y(i + 1, j + 1); c[2] = (j + 1) * s
            d[0] = i * s; d[1] = y(i, j + 1); d[2] = (j + 1) * s
            val tu = i + .5f; val tv = j + .5f
            tri(mb, a, b, c, img, tu, tv)
            tri(mb, a, c, d, img, tu, tv)
        }
        // the rim's skirt (the finer levels): straight down, the edge quad's colour, so the coarser level's edge never shows sky
        if (k < plan.levels.size - 1) {
            val drop = (if (k == 0) 160.0 else 500.0).toFloat() * u
            fun skirt(i0: Int, j0: Int, i1: Int, j1: Int, ti: Int, tj: Int) {
                val p0 = floatArrayOf(i0 * s, y(i0, j0), j0 * s); val p1 = floatArrayOf(i1 * s, y(i1, j1), j1 * s)
                val q0 = floatArrayOf(p0[0], p0[1] - drop, p0[2]); val q1 = floatArrayOf(p1[0], p1[1] - drop, p1[2])
                val tu = ti + .5f; val tv = tj + .5f
                mb.poly(floatArrayOf(*p0, *p1, *q1, *q0), floatArrayOf(tu, tv, tu, tv, tu, tv, tu, tv), img, MeshBuilder.cross(MeshBuilder.sub(q0, p0), MeshBuilder.sub(p1, p0)), MeshBuilder.sub(p1, p0))
            }
            for (i in 0 until n) { skirt(i, 0, i + 1, 0, i, 0); skirt(i + 1, n, i, n, i, n - 1) }
            for (j in 0 until n) { skirt(n, j, n, j + 1, n - 1, j); skirt(0, j + 1, 0, j, 0, j) }
        }
        val packed = PropLayer.Packing.packLocal(mb.build()) ?: return null
        return Part("level $k", plan.origin.plus(lv.ox, lv.oz), packed)
    }

    /** A triangle with its own flat normal (facing up), wearing texel ([tu], [tv]) of [img] whole. */
    private fun tri(mb: MeshBuilder, a: FloatArray, b: FloatArray, c: FloatArray, img: PixImg, tu: Float, tv: Float) {
        var nrm = MeshBuilder.cross(MeshBuilder.sub(c, a), MeshBuilder.sub(b, a))
        if (nrm[1] < 0f) nrm = MeshBuilder.mul(nrm, -1f)
        if (MeshBuilder.len(nrm) < 1e-6f) return
        mb.poly(floatArrayOf(*a, *b, *c), floatArrayOf(tu, tv, tu, tv, tu, tv), img, nrm, MeshBuilder.sub(b, a))
    }

    private fun ocean(): PropLayer.Packed? {
        val plan = plan!!
        val r = (plan.reach * u).toFloat(); val y = plan.seaLevel * u
        val img = PixImg(2, 2).also { it.fill(OCEAN) }
        val mb = MeshBuilder()
        mb.poly(floatArrayOf(-r, y, -r, r, y, -r, r, y, r, -r, y, r), floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), img, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f))
        return PropLayer.Packing.packLocal(mb.build())
    }

    private fun lines(mid: DoubleArray): PropLayer.Packed? {
        val plan = plan!!
        val imgs = WorldFarPlan.LINE_COLOURS.map { c -> PixImg(2, 2).also { it.fill(c) } }
        val mb = MeshBuilder()
        // (drawn in order: county roads, highways, rail, then the rivers over all)
        for (kind in intArrayOf(WorldFarPlan.COUNTY, WorldFarPlan.HIGHWAY, WorldFarPlan.RAIL, WorldFarPlan.RIVER)) for (l in plan.lines) {
            if (l.kind != kind) continue
            val img = imgs[kind]
            val lift = when (kind) { WorldFarPlan.RIVER -> 2.0; WorldFarPlan.RAIL -> 3.5; WorldFarPlan.HIGHWAY -> 3.0; else -> 2.5 }
            ribbon(mb, l, img, lift, mid)
        }
        return PropLayer.Packing.packLocal(mb.build())
    }

    /** A line as a ribbon on the surface: cut into pieces short enough to follow the ground, each a flat quad. */
    private fun ribbon(mb: MeshBuilder, l: WorldFarPlan.Line, img: PixImg, lift: Double, mid: DoubleArray) {
        val plan = plan!!
        val p = l.pts
        for (k in 0 until p.size / 2 - 1) {
            val x0 = p[k * 2]; val z0 = p[k * 2 + 1]; val x1 = p[k * 2 + 2]; val z1 = p[k * 2 + 3]
            val len = hypot(x1 - x0, z1 - z0)
            if (len < .01) continue
            val far = hypot((x0 + x1) / 2 - mid[0], (z0 + z1) / 2 - mid[1])
            // (wider far off: at least about a pixel and a half of a phone's view, so a line still reads as one)
            val w = max(l.width.toDouble(), far * .0016)
            val step = max(60.0, when { far < 8000 -> 120.0; far < 50000 -> 500.0; else -> 1800.0 })
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
                val ya = (plan.surface(ax, az).toDouble() + up).let { max(it, plan.seaLevel + up) }
                val yb = (plan.surface(bx, bz).toDouble() + up).let { max(it, plan.seaLevel + up) }
                fun v(x: Double, y: Double, z: Double) = floatArrayOf(((x - mid[0]) * u).toFloat(), (y * u).toFloat(), ((z - mid[1]) * u).toFloat())
                val pa = v(ax + nx, ya, az + nz); val pb = v(bx + nx, yb, bz + nz); val pc = v(bx - nx, yb, bz - nz); val pd = v(ax - nx, ya, az - nz)
                var n = MeshBuilder.cross(MeshBuilder.sub(pd, pa), MeshBuilder.sub(pb, pa))
                if (n[1] < 0f) n = MeshBuilder.mul(n, -1f)
                mb.poly(floatArrayOf(*pa, *pb, *pc, *pd), floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), img, n, MeshBuilder.sub(pb, pa))
            }
        }
    }

    private fun boxes(mid: DoubleArray): PropLayer.Packed? {
        val plan = plan!!
        if (plan.boxes.isEmpty()) return null
        // the walls' pictures: each wall colour with its windows (a third lit at night), the roofs flat
        val walls = WorldFarPlan.WALLS.map { c -> wall(c) }
        val roofs = WorldFarPlan.ROOFS.map { c -> PixImg(2, 2).also { it.fill(c) } }
        val mb = MeshBuilder()
        for (b in plan.boxes) {
            val x0 = ((b.x - b.w / 2 - mid[0]) * u).toFloat(); val x1 = ((b.x + b.w / 2 - mid[0]) * u).toFloat()
            val z0 = ((b.z - b.d / 2 - mid[1]) * u).toFloat(); val z1 = ((b.z + b.d / 2 - mid[1]) * u).toFloat()
            val wi = walls[WorldFarPlan.WALLS.indexOf(b.wall).coerceAtLeast(0)]; val ro = roofs[WorldFarPlan.ROOFS.indexOf(b.roof).coerceAtLeast(0)]
            mb.box(x0, b.y * u, z0, x1, (b.y + b.h) * u, z1) { side, _, _ -> when (side) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> ro; else -> wi } }
        }
        return PropLayer.Packing.packLocal(mb.build())
    }

    private fun wall(c: Int): PixImg {
        val im = PixImg(6, 4)
        im.fill(c)
        val win = shade(c, .55f)
        for (y in 1..2) for (x in intArrayOf(1, 3, 4)) im[x, y] = win
        // (a few windows glow at night, in their own warm colour)
        im[1, 1] = 0xFFFFD890.toInt(); im.glow(1, 1, 1, 1)
        im[4, 2] = 0xFFFFD890.toInt(); im.glow(4, 2, 4, 2)
        im[3, 1] = 0xFFFFD890.toInt(); im.glow(3, 1, 3, 1)
        return im
    }

    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 255) * k).toInt(); val g = ((c shr 8 and 255) * k).toInt(); val b = ((c and 255) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val OCEAN = 0xFF2C6296.toInt()

        /**
         * Adds the world round the town to [town] ([plan] made on a worker) (its far plane reaching the horizon, its own grass apron left out:
         * the world's ground runs on from the town's edge instead); [origin] is the app's render origin.
         */
        fun attach(town: FilamentTown, plan: () -> WorldFarPlan, origin: RenderOrigin): WorldFar {
            town.worldReach = (WorldFarPlan.REACH_M * origin.unitsPerMetre * 1.02).toFloat()
            return town.addExtra { WorldFar(it, plan, origin) }
        }
    }
}
