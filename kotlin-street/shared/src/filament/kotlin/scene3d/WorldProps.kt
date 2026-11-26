package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldCountry
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.google.android.filament.MaterialInstance
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import java.util.concurrent.Callable
import java.util.concurrent.Future
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The open country's trees and things (docs/WORLD_STREAM.md, step 1b): [WorldCountry.scatter]'s cells streamed round the
 * player beside the near terrain, a [TownExtra].
 *
 *  - **Trees near by** ([R3D]): each cell's trees and bushes as the town's 3D trees ([TreeKit]'s simple level, every look
 *    grown once at a reference height and scaled to each tree), merged into one mesh a cell (one draw and its shadow).
 *  - **Trees far off** (to [R_IMP]): the town's tree impostors ([TreeImpostors], their views baked once for the country's
 *    looks), in tiles of 2 x 2 cells, packed again as the eye goes round a tile; a tile leaves out the cells drawn in 3D
 *    and swaps in the same frame as they come and go, so a tree is never drawn twice nor missing. Past them the woods are
 *    the near terrain's forest floor and the far view's dark green.
 *  - **Things** (to [R_PROPS]): the fences, utility and telegraph poles with their wires, mile markers and billboards, in
 *    the pixel-art kit below ([Kit]), one mesh a cell wearing one shared atlas.
 *
 * Everything is made on the town's workers (a cell's scatter, its meshes, a tile's quads), nearest first, and uploaded
 * within [UPLOAD_NS] a frame (at least one); each mesh is in its cell's or tile's own frame, its root transform its corner
 * less the render origin. What goes out of reach is freed (the scatter's data a little later, so a turn back is cheap).
 */
internal class WorldProps(private val kit: TownExtra.Kit, private val far: WorldFar, private val origin: RenderOrigin) : TownExtra {
    private val u = origin.unitsPerMetre.toFloat()
    private val engine get() = kit.engine

    private inner class Cell(val ci: Int, val cj: Int) {
        var data: WorldCountry.Scatter? = null
        var dataJob: Future<WorldCountry.Scatter>? = null
        var trees: PropLayer? = null
        var treesJob: Future<PropLayer.Packed?>? = null
        var treesBytes = 0L
        var props: PropLayer? = null
        var propsJob: Future<PropLayer.Packed?>? = null
        var propsBytes = 0L
        var models: List<Pair<ModelMesh, Float>>? = null
        var modelsJob: Future<List<Pair<FloatArray, FilamentModels.Ready?>>>? = null
        var modelsBytes = 0L
        var modelsGen = -1
        var dist = 0f
        var gen = -1
        /** Its trees are shown in 3D (its tile's impostors leave it out). */
        var shown3d = false
    }

    private inner class Tile(val ti: Int, val tj: Int) {
        var imp: PropLayer? = null
        var bytes = 0L
        var job: Future<Pair<PropLayer.Packed?, Set<Long>>>? = null
        /** Its impostors were packed at least once (for the cells and the 3D set they had then). */
        var built = false
        /** The cells its impostors leave out (drawn in 3D). */
        var with3d: Set<Long> = emptySet()
        var ex = Float.NaN; var ez = Float.NaN
        var dist = 0f
        var gen = -1
    }

    private val cells = HashMap<Long, Cell>()
    private val tiles = HashMap<Long, Tile>()
    private var country: WorldCountry? = null
    @Volatile private var impAtlas: TreeImpostors.Atlas? = null
    private var impJob: Future<*>? = null
    private val impShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private val treeShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private var impBytes = 0L
    /** Each tree look's simple mesh at its reference height (the town's TreeKit cache is the town's). */
    private val looks = java.util.concurrent.ConcurrentHashMap<Int, PropMesh>()
    private var eyeX = Double.NaN; private var eyeZ = Double.NaN
    private var planX = Double.NaN; private var planZ = Double.NaN; private var planFrame = 0L
    private val wanted = ArrayList<Cell>()
    private val near = ArrayList<Tile>()
    /** A 3D cell or a tile came up this frame: which cells show in 3D is worked out again. */
    private var changed = false
    private var frameNo = 0L

    // ---- measurements
    private val dataMs = ArrayList<Float>()
    var drawnTrees3d = 0; private set
    var drawnImpostors = 0; private set
    private var lastDraws = 0; private var lastTris = 0
    /** Frames in which a wanted 3D or props cell, or impostor tile, was not up yet. */
    var lateFrames = 0L; private set
    private var late = false

    override val pending: Boolean get() = country == null || (TreeImpostors.on && impAtlas == null) || late

    override val report: String get() {
        val d = synchronized(dataMs) { dataMs.sorted() }
        val st = if (d.isEmpty()) "none made" else "%d cells' scatter made: median %.1f ms, p90 %.1f, max %.1f (a worker each)".format(java.util.Locale.US, d.size, d[d.size / 2], d[d.size * 9 / 10], d.last())
        val tr = cells.values.sumOf { it.data?.treeCount ?: 0 }
        return "world country: ${cells.size} cells (${cells.values.count { it.trees != null }} in 3D, ${cells.values.count { it.props != null }} with things), ${tiles.values.count { it.imp != null }} impostor tiles; " +
            "$tr trees and bushes held; $drawnTrees3d 3D tree cells and $drawnImpostors impostor tiles drawn ($lastDraws draws, $lastTris triangles); $st; " +
            "GPU %.1f MB (3D trees %.1f, impostors %.1f + atlas %.1f, things %.1f); frames with something wanted not up %d".format(java.util.Locale.US,
                WorldMemory.propsGpu / 1048576.0, cells.values.sumOf { it.treesBytes } / 1048576.0, tiles.values.sumOf { it.bytes } / 1048576.0, impBytes / 1048576.0,
                cells.values.sumOf { it.propsBytes } / 1048576.0, lateFrames)
    }

    override fun step() {
        val c = far.terrain?.country ?: return
        // (a re-plan's terrain is the same ground: the cells made stay valid; only new ones use the new terrain)
        country = c
        if (impAtlas == null && impJob == null && TreeImpostors.on) impJob = kit.pool.submit(Callable { bakeImpostors() })
    }

    /** Bakes the views of every look the country grows (a worker, once). */
    private fun bakeImpostors() {
        val specs = ArrayList<TreeKit.Spec>()
        for (sp in 0..10) for (v in 0 until VARIANTS) specs += TreeKit.Spec(sp, v, REF_M[sp] * u)
        val a = TreeImpostors.bake(specs)
        val (packed, _) = GpuAccount.scoped("world country") { StreetTextures.prepareShared(a.px, a.w, a.h, linear = false, impShare) }
        val token = IntArray(1)
        impShare[token] = packed; impShare.remove(a.px)
        impBytes = packed.buf.capacity().toLong()
        impAtlas = TreeImpostors.Atlas(a.w, a.h, token, a.at, a.half, a.tall, a.ref, a.texel, a.trees)
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

    private val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val at = FloatArray(2)

    /** Places [l] at the world corner ([x], [z]) m (its root transform: the corner less the render origin). */
    private fun place(l: PropLayer, x: Double, z: Double) {
        origin.toRender(WorldPos(Planet.wrapX(x), z), at)
        m[12] = at[0]; m[13] = 0f; m[14] = at[1]
        l.setTransform(m)
    }

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out[0] = 0; out[1] = 0; out[2] = 0
        frameNo++
        val ctry = country
        if (ctry == null || hidden) { hideAll(); return out }
        val w = origin.toWorld(f.pose.ex.toDouble(), f.pose.ez.toDouble())
        eyeX = w.x; eyeZ = w.z
        val cm = WorldCountry.CELL_M
        val t0 = System.nanoTime()
        var uploads = 0
        fun budget(): Boolean = uploads == 0 || System.nanoTime() - t0 < UPLOAD_NS
        // (the whole pass, what is wanted and what is stale, every [PLAN_FRAMES] frames or once the eye has moved [PLAN_M];
        // between them only the finished jobs are taken and uploaded: the frame's CPU stays small)
        val full = frameNo - planFrame >= PLAN_FRAMES || planX.isNaN() || hypot(eyeX - planX, eyeZ - planZ) > PLAN_M
        if (full) { planFrame = frameNo; planX = eyeX; planZ = eyeZ; late = false }
        // the cells within reach: their scatter made, nearest first
        val r = R_IMP + cm
        val i0 = floor((eyeX - r) / cm).toInt(); val i1 = floor((eyeX + r) / cm).toInt()
        val j0 = floor((eyeZ - r) / cm).toInt(); val j1 = floor((eyeZ + r) / cm).toInt()
        if (full) wanted.clear()
        if (full) for (j in j0..j1) for (i in i0..i1) {
            val d = rectDistance(i * cm, j * cm, cm, eyeX, eyeZ)
            if (d > r) continue
            val c = cells.getOrPut(key(i, j)) { Cell(i, j) }
            c.dist = d.toFloat()
            wanted += c
        }
        if (full) wanted.sortBy { it.dist }
        // (every cell's finished job taken, wanted or not: a job left unread would hold its place in flight)
        for (c in cells.values) {
            val j = c.dataJob ?: continue
            if (!j.isDone) continue
            c.dataJob = null
            if (j.isCancelled) continue
            c.data = try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("the country's cell ${c.ci}, ${c.cj}", e.cause ?: e) }
            synchronized(dataMs) { dataMs += c.data!!.ms }
        }
        var inFlight = cells.values.count { it.dataJob != null }
        for (c in wanted) {
            if (c.data == null && c.dataJob == null && inFlight < DATA_IN_FLIGHT) {
                val ci = c.ci; val cj = c.cj
                c.dataJob = kit.pool.submit(Callable { ctry.scatter(ci, cj) }); inFlight++
            }
        }
        // 3D trees and things, by distance
        for (c in wanted) {
            val d = c.data ?: run { if (full && c.dist < R_PROPS) late = true; null } ?: continue
            if (c.dist < R3D && c.trees == null && c.treesJob == null && d.treeCount > 0) c.treesJob = kit.pool.submit(Callable { packTrees(d) })
            c.treesJob?.let { j -> if (j.isDone && budget()) {
                c.treesJob = null
                val p = j.get()
                if (p != null && c.dist < R3D + HYST3D) {
                    val l = layer(); GpuAccount.scoped("world country") { l.upload(p, casts = true) }
                    c.trees = l; c.treesBytes = p.bytes.toLong(); place(l, d.x0, d.z0); c.gen = origin.generation; uploads++; changed = true
                }
            } }
            if (full && c.dist < R3D && d.treeCount > 0 && c.trees == null) late = true
            if (c.dist < R_PROPS && c.props == null && c.propsJob == null && (d.poles.isNotEmpty() || d.fences.isNotEmpty() || d.signs.isNotEmpty()))
                c.propsJob = kit.pool.submit(Callable { WorldPropKit.pack(d, u, ctry.year) })
            c.propsJob?.let { j -> if (j.isDone && budget()) {
                c.propsJob = null
                val p = j.get()
                if (p != null && c.dist < R_PROPS + HYST) {
                    val l = layer(); GpuAccount.scoped("world country") { l.upload(p, casts = true) }
                    c.props = l; c.propsBytes = p.bytes.toLong(); place(l, d.x0, d.z0); uploads++
                }
            } }
            if (full && c.dist < R_PROPS && c.props == null && c.propsJob != null) late = true
        }
        // the farmsteads (the farm models)
        if (farmArt != null) for (c in wanted) {
            val d = c.data ?: continue
            if (c.dist < R_FARMS && c.models == null && c.modelsJob == null && d.buildings.isNotEmpty()) c.modelsJob = kit.pool.submit(Callable { prepareModels(d) })
            c.modelsJob?.let { j -> if (j.isDone && budget()) { c.modelsJob = null; if (c.dist < R_FARMS + HYST) { finishModels(c, d, j.get()); uploads++ } } }
        }
        // the impostor tiles
        val ia = impAtlas
        val tm = cm * 2
        if (ia != null) {
            val ti0 = floor((eyeX - R_IMP) / tm).toInt(); val ti1 = floor((eyeX + R_IMP) / tm).toInt()
            val tj0 = floor((eyeZ - R_IMP) / tm).toInt(); val tj1 = floor((eyeZ + R_IMP) / tm).toInt()
            var tilesInFlight = tiles.values.count { it.job != null }
            if (full) near.clear()
            if (full) for (tj in tj0..tj1) for (ti in ti0..ti1) {
                val d = rectDistance(ti * tm, tj * tm, tm, eyeX, eyeZ)
                if (d > R_IMP) continue
                val t = tiles.getOrPut(key(ti, tj)) { Tile(ti, tj) }
                t.dist = d.toFloat()
                near += t
            }
            if (full) near.sortBy { it.dist }
            for (t in near) {
                val j = t.job
                if (j != null && j.isDone && budget()) {
                    t.job = null; tilesInFlight--
                    val (p, with) = j.get()
                    t.imp?.destroy(later = true); t.imp = null; t.bytes = 0
                    if (p != null) {
                        val l = layer(); GpuAccount.scoped("world country") { l.upload(p, casts = false) }
                        t.imp = l; t.bytes = p.bytes.toLong(); place(l, t.ti * tm, t.tj * tm); t.gen = origin.generation
                    }
                    t.with3d = with
                    t.built = true
                    uploads++; changed = true
                }
                if (!full) continue
                // (the tile's cells: all made; which of them are up in 3D)
                val members = LongArray(4) { k -> key(t.ti * 2 + (k and 1), t.tj * 2 + (k shr 1)) }
                val cs = members.map { cells[it] }
                if (cs.any { it?.data == null }) { if (t.dist < R_PROPS) late = true; continue }
                // (a tile with no trees at all: nothing to pack, ever)
                if (cs.all { it!!.data!!.treeCount == 0 }) { if (t.job == null) { t.built = true; t.with3d = emptySet() }; continue }
                val want3d = members.filter { k -> cells[k]!!.let { it.trees != null && it.dist < R3D + HYST3D } }.toSet()
                val ex = ((eyeX - t.ti * tm) * u).toFloat(); val ez = ((eyeZ - t.tj * tm) * u).toFloat()
                val cxu = (tm * u / 2).toFloat()
                val stale = !t.built || want3d != t.with3d || TreeImpostors.needsRebuild(cxu, cxu, t.ex, t.ez, ex, ez)
                if (t.job == null && stale && tilesInFlight < TILES_IN_FLIGHT) {
                    t.ex = ex; t.ez = ez
                    val list = ArrayList<Pair<TreeKit.Spec, FloatArray>>()
                    for ((k, c) in cs.withIndex()) {
                        if (members[k] in want3d) continue
                        addTrees(c!!.data!!, ((k and 1) * cm * u).toFloat(), ((k shr 1) * cm * u).toFloat(), list)
                    }
                    t.job = kit.pool.submit(Callable { (if (list.isEmpty()) null else TreeImpostors.mesh(ia, list, ex, ez)?.let { PropLayer.Packing.pack(it, IDENTITY, impShare) }) to want3d })
                    tilesInFlight++
                }
                if (t.dist < R3D * 2 && !t.built) late = true
            }
        }
        // which cells show their 3D trees: those their tile leaves out (or with no tile yet)
        if (full || changed) for (c in cells.values) {
            val t = tiles[key(Math.floorDiv(c.ci, 2), Math.floorDiv(c.cj, 2))]
            // (a cell far past the 3D reach whose tile is gone is let go below, never shown)
            c.shown3d = c.trees != null && c.dist < R3D + 3 * HYST3D && (t == null || ia == null || !t.built || key(c.ci, c.cj) in t.with3d)
        }
        changed = false
        draw(f)
        if (full) { evict(); account() }
        if (late) lateFrames++
        out[0] = lastDraws; out[1] = lastTris
        return out
    }

    private fun draw(f: TownExtra.Frame) {
        var draws = 0; var tris = 0; var n3 = 0; var ni = 0
        val pose = f.pose
        for (c in cells.values) {
            c.trees?.let { l ->
                if (c.gen != origin.generation) { c.data?.let { d -> place(l, d.x0, d.z0); c.props?.let { place(it, d.x0, d.z0) } }; c.gen = origin.generation }
                l.update(f.look, f.dark, c.shown3d, 1f, c.shown3d)
                if (c.shown3d) { draws += l.draws; tris += l.triangles; n3++ }
            }
            c.props?.let { l ->
                // (the things fade out by screen door over the last part of their reach)
                val fade = ((R_PROPS + HYST / 2 - c.dist) / (R_PROPS * .2f)).coerceIn(0f, 1f)
                l.update(f.look, f.dark, fade > .02f, fade, fade > .5f && c.dist < SHADOW_M)
                if (fade > .02f) { draws += l.draws; tris += l.triangles }
            }
        }
        for (t in tiles.values) t.imp?.let { l ->
            if (t.gen != origin.generation) { place(l, t.ti * WorldCountry.CELL_M * 2, t.tj * WorldCountry.CELL_M * 2); t.gen = origin.generation }
            l.update(f.look, f.dark, true, 1f, false)
            draws += l.draws; tris += l.triangles; ni++
        }
        lastDraws = draws; lastTris = tris; drawnTrees3d = n3; drawnImpostors = ni
        drawModels(f)
        if (pose.ex.isNaN()) return
    }

    /** Leaves it out of the frames (a comparison: the bare country). */
    var hidden = false

    private fun hideAll() {
        for (c in cells.values) { c.trees?.update(FloatArray(16), 0f, false, 1f, false); c.props?.update(FloatArray(16), 0f, false, 1f, false) }
        for (t in tiles.values) t.imp?.update(FloatArray(16), 0f, false, 1f, false)
        lastDraws = 0; lastTris = 0
    }

    private fun layer() = PropLayer(engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true)

    /** [d]'s trees as (look, place) pairs in street units from the corner offset by ([ox], [oz]), into [out]. */
    private fun addTrees(d: WorldCountry.Scatter, ox: Float, oz: Float, out: MutableList<Pair<TreeKit.Spec, FloatArray>>) {
        val tr = d.trees
        for (k in 0 until d.treeCount) {
            val o = k * WorldCountry.TREE
            val sp = tr[o + 3].toInt(); val v = tr[o + 4].toInt(); val h = tr[o + 5]
            val turn = (sp * 7 + v * 13 + k * 37) * .618f * 6.2832f
            val c = cos(turn); val s = sin(turn)
            out += TreeKit.Spec(sp, v, h * u) to floatArrayOf(ox + tr[o] * u, oz + tr[o + 2] * u, c, s, -s, c, 0f, tr[o + 1] * u)
        }
    }

    /** One look's simple mesh at its reference height. */
    private fun look(sp: Int, v: Int): PropMesh = looks.getOrPut(sp * 64 + v) { TreeKit.mesh(TreeKit.Spec(sp, v, REF_M[sp] * u), detail = false) }

    /** [d]'s trees in 3D as one mesh in the cell's frame (a worker). */
    private fun packTrees(d: WorldCountry.Scatter): PropLayer.Packed? {
        val list = ArrayList<Pair<TreeKit.Spec, FloatArray>>()
        addTrees(d, 0f, 0f, list)
        if (list.isEmpty()) return null
        var nv = 0; var ni = 0
        val meshes = list.map { (s, _) -> look(s.species, s.variant).also { nv += it.vertexCount; ni += it.idx.size } }
        val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3); val idx = IntArray(ni)
        var vi = 0; var ii = 0
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for ((k, pr) in list.withIndex()) {
            val (s, a) = pr
            val mm = meshes[k]
            val sc = s.height / (REF_M[s.species] * u)
            for (i in 0 until mm.vertexCount) {
                val lx = mm.pos[i * 3] * sc; val ly = mm.pos[i * 3 + 1] * sc + a[7]; val lz = mm.pos[i * 3 + 2] * sc
                val wx = a[0] + a[2] * lx + a[4] * lz; val wz = a[1] + a[3] * lx + a[5] * lz
                val o = (vi + i) * 3
                pos[o] = wx; pos[o + 1] = ly; pos[o + 2] = wz
                nr[o] = a[2] * mm.nrm[i * 3] + a[4] * mm.nrm[i * 3 + 2]; nr[o + 1] = mm.nrm[i * 3 + 1]; nr[o + 2] = a[3] * mm.nrm[i * 3] + a[5] * mm.nrm[i * 3 + 2]
                tn[o] = a[2] * mm.tan[i * 3] + a[4] * mm.tan[i * 3 + 2]; tn[o + 1] = mm.tan[i * 3 + 1]; tn[o + 2] = a[3] * mm.tan[i * 3] + a[5] * mm.tan[i * 3 + 2]
                uv[(vi + i) * 2] = mm.uv[i * 2]; uv[(vi + i) * 2 + 1] = mm.uv[i * 2 + 1]
                if (wx < b[0]) b[0] = wx; if (ly < b[1]) b[1] = ly; if (wz < b[2]) b[2] = wz; if (wx > b[3]) b[3] = wx; if (ly > b[4]) b[4] = ly; if (wz > b[5]) b[5] = wz
            }
            for (q in mm.idx) idx[ii++] = vi + q
            vi += mm.vertexCount
        }
        val f = meshes[0]
        return PropLayer.Packing.pack(PropMesh(pos, uv, nr, tn, idx, f.texW, f.texH, f.tex, f.mask, b), IDENTITY, treeShare)
    }

    // ------------------------------------------------------------------ the farmsteads (the capital's farm models)

    private val shapes: Map<String, com.lifemax.rpg.feature.habits.domain.ModelShape> by lazy { com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(farmArt?.text("buildings/models.json")) }
    private val arts = HashMap<String, ModelArt?>()

    /** A farm model's art, read once (TownFarms' way: only the farmhouse keeps its lawn, fence and lit windows). */
    private fun artOf(key: String): ModelArt? = synchronized(arts) {
        arts.getOrPut(key) {
            val a = farmArt ?: return@getOrPut null
            val sh = shapes[key] ?: return@getOrPut null
            val atlas = a.image("buildings/${key}_atlas.png") ?: return@getOrPut null
            val house = key.startsWith("fm_house")
            ModelArt(sh, atlas, if (house) a.image("buildings/${key}_glow.png") else null, if (house) a.image("buildings/${key}_lawn.png") else null,
                if (house) a.image("buildings/${key}_fence.png") else null, null)
        }
    }

    /** [d]'s farm buildings made ready (a worker): each in the cell's frame, with its ground height. */
    private fun prepareModels(d: WorldCountry.Scatter): List<Pair<FloatArray, FilamentModels.Ready?>> = d.buildings.map { b ->
        val key = WorldCountry.FARM_MODELS[b[5].toInt()]
        val ready = runCatching {
            val sh = shapes[key] ?: return@runCatching null
            val a = artOf(key) ?: return@runCatching null
            val t = com.lifemax.rpg.feature.habits.domain.FarmThing(key, b[0] * u, b[2] * u, b[3], b[4], false, false)
            val pl = com.lifemax.rpg.feature.habits.domain.FarmSolids.place(t, sh)
            FilamentModels.prepare(kit.textures, a, ModelPlace(pl[0], pl[1], pl[2], pl[3], pl[4], pl[5], com.lifemax.rpg.feature.habits.domain.FarmSolids.K), (d.ci * 31 + d.cj + key.hashCode()), linear = true)
        }.getOrNull()
        b to ready
    }

    private fun finishModels(c: Cell, d: WorldCountry.Scatter, list: List<Pair<FloatArray, FilamentModels.Ready?>>) {
        val out = ArrayList<Pair<ModelMesh, Float>>()
        var bytes = 0L
        for ((b, r) in list) {
            r ?: continue
            val mm = try { GpuAccount.scoped("world country") { FilamentModels.finish(engine, kit.lit, kit.proxy, kit.textures, r) } } finally { kit.textures.drop(r.warmed) }
            val rm = engine.renderableManager
            rm.getInstance(mm.entity).takeIf { it != 0 }?.let { rm.setCulling(it, true) }
            kit.scene.addEntity(mm.entity); if (mm.proxy != 0) kit.scene.addEntity(mm.proxy)
            bytes += r.vb.capacity().toLong() + r.ib.capacity()
            out += mm to b[1] * u
        }
        c.models = out; c.modelsBytes = bytes
        placeModels(c, d)
    }

    /** Each model's root: the cell's corner less the render origin, raised to its ground. */
    private fun placeModels(c: Cell, d: WorldCountry.Scatter) {
        val tm = engine.transformManager
        origin.toRender(WorldPos(Planet.wrapX(d.x0), d.z0), at)
        for ((mm, y) in c.models ?: return) for (e in intArrayOf(mm.entity, mm.proxy)) {
            if (e == 0) continue
            if (!tm.hasComponent(e)) tm.create(e)
            m[12] = at[0]; m[13] = y; m[14] = at[1]
            tm.setTransform(tm.getInstance(e), m)
        }
        m[13] = 0f
        c.modelsGen = origin.generation
    }

    private fun freeModels(c: Cell) {
        val list = c.models ?: return
        val em = com.google.android.filament.EntityManager.get()
        for ((mm, _) in list) {
            for (e in intArrayOf(mm.entity, mm.proxy)) if (e != 0) { kit.scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            for (mi in mm.mis) Graveyard.bury(engine, mi)
            mm.twins?.idle?.forEach { Graveyard.bury(engine, it) }
            for (mi in mm.proxyMis) Graveyard.bury(engine, mi)
            mm.geo.free(engine)
        }
        c.models = null; c.modelsBytes = 0
    }

    private fun drawModels(f: TownExtra.Frame) {
        val rm = engine.renderableManager
        for (c in cells.values) {
            val list = c.models ?: continue
            if (c.modelsGen != origin.generation) c.data?.let { placeModels(c, it) }
            val fade = ((R_FARMS + HYST / 2 - c.dist) / (R_FARMS * .15f)).coerceIn(0f, 1f)
            val on = fade > .02f
            for ((mm, _) in list) {
                rm.getInstance(mm.entity).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (on) 1 else 0) }
                if (mm.proxy != 0) rm.getInstance(mm.proxy).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (on && c.dist < SHADOW_M) 1 else 0) }
                if (!on) continue
                mm.twins?.wear(engine, mm.entity, fade < 1f)
                for (mi in mm.mis) {
                    mi.setParameter("look", MaterialInstance.FloatElement.MAT4, f.look, 0, 1)
                    mi.setParameter("fade", fade)
                    mi.setParameter("glow", f.glow[0], f.glow[1], f.glow[2], f.glow[3])
                    mi.setParameter("glass", f.glass[0], f.glass[1], f.glass[2], 1f)
                }
                mm.night?.invoke(f.hour)
                lastDraws += mm.mis.size; lastTris += mm.geo.triangles
            }
        }
    }

    /** Frees what is out of reach: 3D trees past [R3D], things past [R_PROPS], tiles past [R_IMP], the data past all of it. */
    private fun evict() {
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val c = it.next().value
            if (c.trees != null && c.dist > R3D + HYST3D && (!c.shown3d || c.dist > R3D + 2 * HYST3D)) { c.trees!!.destroy(later = true); c.trees = null; c.treesBytes = 0 }
            if (c.treesJob != null && c.dist > R3D + HYST3D) { c.treesJob!!.cancel(false); c.treesJob = null }
            if (c.props != null && c.dist > R_PROPS + HYST) { c.props!!.destroy(later = true); c.props = null; c.propsBytes = 0 }
            if (c.propsJob != null && c.dist > R_PROPS + HYST) { c.propsJob!!.cancel(false); c.propsJob = null }
            if (c.models != null && c.dist > R_FARMS + HYST) freeModels(c)
            if (c.modelsJob != null && c.dist > R_FARMS + HYST) { c.modelsJob!!.cancel(false); c.modelsJob = null }
            // (the cell's distance is set only while wanted: one not seen this frame is measured here)
            val d = rectDistance(c.ci * WorldCountry.CELL_M, c.cj * WorldCountry.CELL_M, WorldCountry.CELL_M, eyeX, eyeZ)
            c.dist = d.toFloat()
            if (d > R_IMP + WorldCountry.CELL_M + KEEP_M && c.trees == null && c.props == null && c.models == null) { c.dataJob?.cancel(false); it.remove(); wanted.remove(c) }
        }
        val ti = tiles.entries.iterator()
        while (ti.hasNext()) {
            val t = ti.next().value
            val d = rectDistance(t.ti * WorldCountry.CELL_M * 2, t.tj * WorldCountry.CELL_M * 2, WorldCountry.CELL_M * 2, eyeX, eyeZ)
            t.dist = d.toFloat()
            if (d > R_IMP + HYST * 2) { t.imp?.destroy(later = true); t.job?.cancel(false); ti.remove(); near.remove(t) }
        }
    }

    private fun account() {
        WorldMemory.propsBuffers = cells.values.sumOf { it.treesBytes + it.propsBytes + it.modelsBytes } + tiles.values.sumOf { it.bytes }
        WorldMemory.propsTextures = impBytes
        WorldMemory.propsCells = cells.size
    }

    override fun destroy() {
        impJob?.cancel(true)
        for (c in cells.values) { c.dataJob?.cancel(true); c.treesJob?.cancel(true); c.propsJob?.cancel(true); c.trees?.destroy(); c.props?.destroy() }
        for (c in cells.values) { c.modelsJob?.cancel(true); freeModels(c) }
        for (t in tiles.values) { t.job?.cancel(true); t.imp?.destroy() }
        cells.clear(); tiles.clear()
        WorldMemory.propsBuffers = 0; WorldMemory.propsTextures = 0; WorldMemory.propsCells = 0
    }

    companion object {
        /** 3D trees within this of the eye (m, to the cell's square); impostors to [R_IMP]; things to [R_PROPS]. */
        const val R3D = 50.0
        const val R_IMP = 1600.0
        const val R_PROPS = 750f
        /** A little past each reach before a cell lets go (m). */
        const val HYST = 60f
        /** The 3D trees' own: kept this little past their reach (each cell's are a few MB). */
        const val HYST3D = 25f
        /** Things throw shadows within this (m). */
        const val SHADOW_M = 400f
        /** Farmsteads within this (m). */
        const val R_FARMS = 1500f
        /** The farm models' pictures and shapes (the app's and the previews' assets/farm; null: no farmsteads drawn). */
        @JvmStatic var farmArt: FarmArt? = null
        /** A cell's data is kept this far past where it is wanted (m): a turn back is cheap. */
        const val KEEP_M = 400.0
        /** Cells' scatter and tiles' quads being made at once; the uploads' time a frame (the first always). */
        const val DATA_IN_FLIGHT = 4
        const val TILES_IN_FLIGHT = 3
        const val UPLOAD_NS = 1_500_000L
        /** The whole pass (what is wanted, what is stale, what goes) every this many frames, or once the eye has moved this far (m). */
        const val PLAN_FRAMES = 6
        const val PLAN_M = 8.0
        /** Variants of each look the country grows (WorldCountry's variants are 0..7). */
        const val VARIANTS = 8
        /** Each species' reference height (m) its look is grown and baked at (TreeKit's species order). */
        val REF_M = floatArrayOf(15f, 14f, 16f, 15f, 14f, 5f, 19f, 17f, 19f, 2.4f, 2.6f)
        private val IDENTITY = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))

        private fun rectDistance(x0: Double, z0: Double, size: Double, x: Double, z: Double): Double {
            val dx = max(0.0, max(x0 - x, x - (x0 + size)))
            val dz = max(0.0, max(z0 - z, z - (z0 + size)))
            return hypot(dx, dz)
        }

        /** Adds the country's trees and things beside [far] (WorldFar.attach does; -DworldProps=0 leaves them out). */
        fun attach(town: FilamentTown, far: WorldFar, origin: RenderOrigin): WorldProps = town.addExtra { WorldProps(it, far, origin) }
    }
}
