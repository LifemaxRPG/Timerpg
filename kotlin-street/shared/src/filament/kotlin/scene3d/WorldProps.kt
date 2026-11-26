package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldCountry
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
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
 *    looks), in tiles of [TILE] x [TILE] cells, packed again as the eye goes round a tile; a tile leaves out the cells drawn
 *    in 3D and swaps in the same frame as they come and go, so a tree is never drawn twice nor missing. Past them the woods
 *    are the near terrain's forest floor and the far view's dark green.
 *  - **Things** (to [R_PROPS]): the fences, utility and telegraph poles with their wires, mile markers and billboards, in
 *    the pixel-art kit ([WorldPropKit]), wearing its one shared atlas.
 *  - **Farmsteads** (to [R_FARMS]): the capital's farm models, meshed once each into one atlas of their own
 *    ([WorldFarmKit]), a cell's or a block's of them one mesh.
 *
 * **Batching** (the things and the farmsteads, [Kind]): where they stand whole (inside the reach's fade), the cells of a
 * block of [BLOCK] x [BLOCK] cells are merged into one mesh, one batch for the block (culled by its box); the cells in the
 * fade at the reach's edge are a batch each, faded on their own as before. A block leaves out the cells it does not hold
 * whole and swaps in the same frame as their own batches come and go (the impostor tiles' way): never a thing twice, never
 * a gap. The step 1b drive's chase view 12 km out: the country's 1,029 draws (about 900 of them the farm models' groups,
 * a draw each) down to well under 200.
 *
 * Everything is made on the town's workers (a cell's scatter, its meshes, a block's or a tile's merge), nearest first,
 * and uploaded within [UPLOAD_NS] a frame (at least one); each mesh is in its cell's, block's or tile's own frame, its
 * root transform its corner less the render origin. What goes out of reach is freed (the scatter's data a little later,
 * so a turn back is cheap).
 */
internal class WorldProps(private val kit: TownExtra.Kit, private val far: WorldFar, private val origin: RenderOrigin) : TownExtra {
    private val u = origin.unitsPerMetre.toFloat()
    private val engine get() = kit.engine

    /** One kind of merged thing ([PROPS], [FARMS]): its reach, where it stands whole, how its cells' meshes are made. */
    private inner class Kind(val id: Int, val reach: Float, val band: Float) {
        /** Faded whole within this (m): the blocks hold the cells nearer than it. */
        val solid: Float get() = reach + HYST / 2 - band
        fun fade(dist: Float): Float = ((reach + HYST / 2 - dist) / band).coerceIn(0f, 1f)
        /** The farm kit it wears (the farmhouses' or the other buildings'), or null for the things. */
        val farm: WorldFarmKit? get() = if (id == PROPS || !farmDone) null else farmKits[id - FARMS]
        fun has(d: WorldCountry.Scatter): Boolean = if (id == PROPS) d.poles.isNotEmpty() || d.fences.isNotEmpty() || d.signs.isNotEmpty()
            else farm?.let { k -> d.buildings.any { k.has(it[5].toInt()) } } == true
        /** The cell's mesh in its own frame (a worker), or null for none. */
        fun part(c: Cell, d: WorldCountry.Scatter, year: Int): WorldFarmKit.Part? =
            if (id == PROPS) (c.propsMesh ?: WorldPropKit.mesh(d, u, year)?.also { c.propsMesh = it })?.let { WorldFarmKit.Part(it, -1) }
            else farm?.let { k -> k.Builder().also { b -> k.add(d, u, 0f, 0f, b) }.build() }
        fun pack(p: WorldFarmKit.Part): PropLayer.Packed? = if (id == PROPS) WorldPropKit.pack(p.mesh) else PropLayer.Packing.pack(p.mesh, IDENTITY, farmShare, p.opaque)
        fun merge(parts: List<Triple<WorldFarmKit.Part, Float, Float>>): WorldFarmKit.Part? =
            if (id == PROPS) WorldPropKit.merge(parts.map { Triple(it.first.mesh, it.second, it.third) })?.let { WorldFarmKit.Part(it, -1) } else WorldFarmKit.merge(parts)
    }

    private inner class Cell(val ci: Int, val cj: Int) {
        val key = key(ci, cj)
        var data: WorldCountry.Scatter? = null
        var dataJob: Future<WorldCountry.Scatter>? = null
        var trees: PropLayer? = null
        var treesJob: Future<PropLayer.Packed?>? = null
        var treesBytes = 0L
        /** The things' mesh, kept (small) for the blocks' merges and the cell's own batch. */
        @Volatile var propsMesh: PropMesh? = null
        /** Each kind's own batch (in the fade, or until its block holds it), its job, its bytes; whether it stands whole. */
        val single = arrayOfNulls<PropLayer>(KINDS)
        val singleJob = arrayOfNulls<Future<PropLayer.Packed?>>(KINDS)
        val singleBytes = LongArray(KINDS)
        val whole = BooleanArray(KINDS)
        var dist = 0f
        var gen = -1
        /** Its trees are shown in 3D (its tile's impostors leave it out). */
        var shown3d = false
    }

    /** A block of [BLOCK] x [BLOCK] cells: each kind's merged batch of the cells it holds whole. */
    private inner class Block(val bi: Int, val bj: Int) {
        val layer = arrayOfNulls<PropLayer>(KINDS)
        val bytes = LongArray(KINDS)
        val job = arrayOfNulls<Future<Pair<PropLayer.Packed?, Set<Long>>>>(KINDS)
        /** The cells each kind's batch holds now, and those its job in flight will. */
        val with = Array<Set<Long>>(KINDS) { emptySet() }
        val pending = Array<Set<Long>>(KINDS) { emptySet() }
        var dist = 0f
        var gen = -1
        /** Whether its batch of kind [k] holds [cell] (or would: a cell whose things made no mesh is held with nothing to draw). */
        fun holds(k: Int, cell: Long): Boolean = cell in with[k]
    }

    private inner class Tile(val ti: Int, val tj: Int) {
        var imp: PropLayer? = null
        var bytes = 0L
        var job: Future<Triple<PropLayer.Packed?, Set<Long>, Int>>? = null
        /** Its impostors were packed at least once (for the cells and the 3D set they had then). */
        var built = false
        /** The cells its impostors leave out (drawn in 3D). */
        var with3d: Set<Long> = emptySet()
        /** Which of its 2 x 2 quarters' trees it holds (a bit each), the impostors' reach kept as step 1b's 2 x 2 tiles had it. */
        var quarters = 0
        var ex = Float.NaN; var ez = Float.NaN
        var dist = 0f
        var gen = -1
    }

    private val kinds = arrayOf(Kind(PROPS, R_PROPS, R_PROPS * .2f), Kind(FARMS, R_FARMS, R_FARMS * .15f), Kind(HOUSES, R_FARMS, R_FARMS * .15f))
    private val cells = HashMap<Long, Cell>()
    private val blocks = HashMap<Long, Block>()
    private val tiles = HashMap<Long, Tile>()
    private var country: WorldCountry? = null
    @Volatile private var impAtlas: TreeImpostors.Atlas? = null
    private var impJob: Future<*>? = null
    /** The farm models merged: the barns, silos, sheds and the rest in one atlas, the farmhouses (with their windows' mask) in another. */
    private val farmKits = arrayOfNulls<WorldFarmKit>(2)
    private var farmBytes = 0L
    private var farmJob: Future<*>? = null
    @Volatile private var farmDone = false
    private val impShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private val treeShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private val farmShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private var impBytes = 0L
    /** Each tree look's simple mesh at its reference height (the town's TreeKit cache is the town's). */
    private val looks = java.util.concurrent.ConcurrentHashMap<Int, PropMesh>()
    private var eyeX = Double.NaN; private var eyeZ = Double.NaN
    private var planX = Double.NaN; private var planZ = Double.NaN; private var planFrame = 0L
    private val wanted = ArrayList<Cell>()
    private val near = ArrayList<Tile>()
    private val nearBlocks = ArrayList<Block>()
    /** A 3D cell or a tile came up this frame: which cells show in 3D is worked out again. */
    private var changed = false
    private var frameNo = 0L

    // ---- measurements
    private val dataMs = ArrayList<Float>()
    var drawnTrees3d = 0; private set
    var drawnImpostors = 0; private set
    private var lastDraws = 0; private var lastTris = 0
    /** This frame's draws by part: 3D trees, impostor tiles, the things' blocks and cells, the farmsteads' blocks and cells. */
    private val drawsBy = IntArray(6)
    /** Frames in which a wanted 3D or props cell, or impostor tile, was not up yet. */
    var lateFrames = 0L; private set
    private var late = false

    override val pending: Boolean get() = country == null || (TreeImpostors.on && impAtlas == null) || (farmArt != null && !farmDone) || late

    override val report: String get() {
        val d = synchronized(dataMs) { dataMs.sorted() }
        val st = if (d.isEmpty()) "none made" else "%d cells' scatter made: median %.1f ms, p90 %.1f, max %.1f (a worker each)".format(java.util.Locale.US, d.size, d[d.size / 2], d[d.size * 9 / 10], d.last())
        val tr = cells.values.sumOf { it.data?.treeCount ?: 0 }
        val b = drawsBy
        return "world country: ${cells.size} cells (${cells.values.count { it.trees != null }} in 3D), ${tiles.values.count { it.imp != null }} impostor tiles, " +
            "${blocks.values.count { it.layer[PROPS] != null }} things' blocks and ${cells.values.count { it.single[PROPS] != null }} cells, " +
            "${blocks.values.sumOf { b -> (FARMS until KINDS).count { b.layer[it] != null } }} farmsteads' blocks' batches and ${cells.values.sumOf { c -> (FARMS until KINDS).count { c.single[it] != null } }} cells' batches; " +
            "$tr trees and bushes held; $drawnTrees3d 3D tree cells and $drawnImpostors impostor tiles drawn ($lastDraws draws: 3D trees ${b[0]}, impostors ${b[1]}, " +
            "things ${b[2]} in blocks + ${b[3]} cells, farmsteads ${b[4]} in blocks + ${b[5]} cells; $lastTris triangles); $st; " +
            "GPU %.1f MB (3D trees %.1f, impostors %.1f + atlas %.1f, things %.1f, farmsteads %.1f + atlas %s); frames with something wanted not up %d".format(java.util.Locale.US,
                WorldMemory.propsGpu / 1048576.0, cells.values.sumOf { it.treesBytes } / 1048576.0, tiles.values.sumOf { it.bytes } / 1048576.0, impBytes / 1048576.0,
                bytesOf(PROPS) / 1048576.0, (bytesOf(FARMS) + bytesOf(HOUSES)) / 1048576.0,
                farmKits.filterNotNull().joinToString(" and ") { "%dx%d".format(it.w, it.h) } + " %.1f MB".format(java.util.Locale.US, farmBytes / 1048576.0), lateFrames)
    }

    private fun bytesOf(k: Int): Long = cells.values.sumOf { it.singleBytes[k] } + blocks.values.sumOf { it.bytes[k] }

    override fun step() {
        val c = far.terrain?.country ?: return
        // (a re-plan's terrain is the same ground: the cells made stay valid; only new ones use the new terrain)
        country = c
        if (impAtlas == null && impJob == null && TreeImpostors.on) impJob = kit.pool.submit(Callable { bakeImpostors() })
        if (farmArt != null && farmJob == null) farmJob = kit.pool.submit(Callable {
            try {
                for (h in 0..1) {
                    val fk = WorldFarmKit.make(shapes, ::artOf, kit.textures) { m -> WorldCountry.FARM_MODELS[m].startsWith("fm_house") == (h == 1) } ?: continue
                    // (its pictures made ready for the GPU here, once: every batch wearing it shares them)
                    val t = GpuAccount.scoped("world country") { StreetTextures.prepareShared(fk.tex, fk.w, fk.h, linear = false, farmShare).first }
                    val m = fk.mask?.let { StreetTextures.prepareShared(it, fk.w, fk.h, linear = true, farmShare, mips = false).first }
                    farmBytes += t.buf.capacity() + (m?.buf?.capacity() ?: 0)
                    farmKits[h] = fk
                }
            } finally { farmDone = true }
        })
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
    private fun blockOf(c: Cell): Long = key(Math.floorDiv(c.ci, BLOCK), Math.floorDiv(c.cj, BLOCK))

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
        // 3D trees, by distance
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
        }
        // the things and the farmsteads: each cell's own batch where its block does not hold it whole, the blocks' merges
        for (k in kinds) merged(k, ctry.year, full, ::budget) { uploads++ }
        // the impostor tiles
        val ia = impAtlas
        val tm = cm * TILE
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
                    val (p, with, qs) = j.get()
                    t.quarters = qs
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
                // (the tile's quarters within reach, as step 1b's 2 x 2 tiles were: one is drawn once all its cells are made,
                // kept a little past the reach; which of their cells are up in 3D)
                var qs = 0
                var waiting = false
                for (q in 0 until 4) {
                    val qi = t.ti * TILE + (q and 1) * 2; val qj = t.tj * TILE + (q shr 1) * 2
                    val qd = rectDistance(qi * cm, qj * cm, cm * 2, eyeX, eyeZ)
                    if (qd > R_IMP && !(t.quarters and (1 shl q) != 0 && qd < R_IMP + HYST * 2)) continue
                    val qc = (0 until 4).map { k -> cells[key(qi + (k and 1), qj + (k shr 1))] }
                    if (qc.any { it == null }) continue
                    if (qc.any { it!!.data == null }) { waiting = true; continue }
                    qs = qs or (1 shl q)
                }
                if (waiting) { if (t.dist < R_PROPS) late = true; continue }
                val members = ArrayList<Long>()
                for (q in 0 until 4) if (qs and (1 shl q) != 0) for (k in 0 until 4) members += key(t.ti * TILE + (q and 1) * 2 + (k and 1), t.tj * TILE + (q shr 1) * 2 + (k shr 1))
                // (a tile with no trees at all: nothing to pack, ever)
                if (members.all { cells[it]!!.data!!.treeCount == 0 }) { if (t.job == null) { t.built = true; t.with3d = emptySet(); t.quarters = qs; if (t.imp != null) { t.imp!!.destroy(later = true); t.imp = null; t.bytes = 0 } }; continue }
                val want3d = members.filter { k -> cells[k]!!.let { it.trees != null && it.dist < R3D + HYST3D } }.toSet()
                val ex = ((eyeX - t.ti * tm) * u).toFloat(); val ez = ((eyeZ - t.tj * tm) * u).toFloat()
                val cxu = (tm * u / 2).toFloat()
                val stale = !t.built || want3d != t.with3d || qs != t.quarters || TreeImpostors.needsRebuild(cxu, cxu, t.ex, t.ez, ex, ez)
                if (t.job == null && stale && tilesInFlight < TILES_IN_FLIGHT) {
                    t.ex = ex; t.ez = ez
                    val list = ArrayList<Pair<TreeKit.Spec, FloatArray>>()
                    for (mk in members) {
                        if (mk in want3d) continue
                        val c = cells[mk]!!
                        addTrees(c.data!!, ((c.ci - t.ti * TILE) * cm * u).toFloat(), ((c.cj - t.tj * TILE) * cm * u).toFloat(), list)
                    }
                    t.job = kit.pool.submit(Callable { Triple(if (list.isEmpty()) null else TreeImpostors.mesh(ia, list, ex, ez)?.let { PropLayer.Packing.pack(it, IDENTITY, impShare) }, want3d, qs) })
                    tilesInFlight++
                }
                if (t.dist < R3D * 2 && !t.built) late = true
            }
        }
        // which cells show their 3D trees: those their tile leaves out (or with no tile yet)
        if (full || changed) for (c in cells.values) {
            val t = tiles[key(Math.floorDiv(c.ci, TILE), Math.floorDiv(c.cj, TILE))]
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

    /**
     * Kind [k]'s cells and blocks this frame: each cell within reach whole or not ([Cell.whole], with a little hysteresis),
     * its own batch made while its block does not hold it, each block's merge of the cells it should hold made again when
     * that set changes; finished jobs uploaded within the [budget] ([uploaded] counts each).
     */
    private fun merged(k: Kind, year: Int, full: Boolean, budget: () -> Boolean, uploaded: () -> Unit) {
        val id = k.id
        val cm = WorldCountry.CELL_M
        val bm = cm * BLOCK
        // the cells' own batches
        for (c in wanted) {
            val d = c.data ?: continue
            if (c.dist < k.solid - WHOLE_HYST) c.whole[id] = true else if (c.dist > k.solid) c.whole[id] = false
            if (c.dist >= k.reach + HYST && c.single[id] == null && c.singleJob[id] == null) continue
            val b = blocks[blockOf(c)]
            c.singleJob[id]?.let { j -> if (j.isDone && budget()) {
                c.singleJob[id] = null
                val p = j.get()
                if (p != null && c.dist < k.reach + HYST && c.single[id] == null) {
                    val l = layer(); GpuAccount.scoped("world country") { l.upload(p, casts = true) }
                    if (id != PROPS) l.seeThrough = false
                    c.single[id] = l; c.singleBytes[id] = p.bytes.toLong(); place(l, d.x0, d.z0); c.gen = origin.generation; uploaded()
                }
            } }
            if (!full) continue
            if (!k.has(d)) continue
            // (its own batch: wanted while within reach and not whole; a whole one waits for its block, keeping its own till then)
            val want = c.dist < k.reach && !c.whole[id]
            if (want && c.single[id] == null && c.singleJob[id] == null) {
                c.singleJob[id] = kit.pool.submit(Callable { k.part(c, d, year)?.let { k.pack(it) } })
            }
            if (c.dist < k.reach && c.single[id] == null && !(b != null && b.holds(id, c.key))) late = true
        }
        if (!full) {
            for (b in nearBlocks) takeBlock(k, b, budget, uploaded)
            return
        }
        // the blocks round the eye
        if (id == 0) {
            nearBlocks.clear()
            val rr = max(R_PROPS, R_FARMS) + HYST
            val bi0 = floor((eyeX - rr) / bm).toInt(); val bi1 = floor((eyeX + rr) / bm).toInt()
            val bj0 = floor((eyeZ - rr) / bm).toInt(); val bj1 = floor((eyeZ + rr) / bm).toInt()
            for (bj in bj0..bj1) for (bi in bi0..bi1) {
                val d = rectDistance(bi * bm, bj * bm, bm, eyeX, eyeZ)
                if (d > rr) continue
                val b = blocks.getOrPut(key(bi, bj)) { Block(bi, bj) }
                b.dist = d.toFloat()
                nearBlocks += b
            }
            nearBlocks.sortBy { it.dist }
        }
        var inFlight = blocks.values.count { it.job[id] != null }
        for (b in nearBlocks) {
            takeBlock(k, b, budget, uploaded)
            if (b.dist > k.reach + HYST && b.layer[id] == null) continue
            // the cells it should hold: whole ones, and those it holds whose own batch is not up yet (never a gap)
            val members = HashSet<Long>()
            var waiting = false
            for (n in 0 until BLOCK * BLOCK) {
                val c = cells[key(b.bi * BLOCK + n % BLOCK, b.bj * BLOCK + n / BLOCK)] ?: continue
                val d = c.data ?: run { if (c.dist < k.solid) waiting = true; null } ?: continue
                if (!k.has(d)) continue
                // (one it holds that has left the whole: until its own batch is up, while it would show at all)
                if (c.whole[id] || (c.key in b.with[id] && c.single[id] == null && c.dist < k.reach + HYST / 2)) members += c.key
            }
            // (a block whose cells are still being made waits for them: one merge, not one a cell)
            if (waiting || b.job[id] != null || members == b.with[id]) continue
            if (inFlight >= BLOCKS_IN_FLIGHT) { late = true; continue }
            val list = members.mapNotNull { cells[it] }.map { it to it.data!! }
            val x0 = b.bi * bm; val z0 = b.bj * bm
            b.pending[id] = members
            b.job[id] = kit.pool.submit(Callable {
                val parts = list.mapNotNull { (c, d) -> k.part(c, d, year)?.let { Triple(it, ((d.x0 - x0) * u).toFloat(), ((d.z0 - z0) * u).toFloat()) } }
                k.merge(parts)?.let { k.pack(it) } to members
            })
            inFlight++
            late = true
        }
    }

    /** Block [b]'s finished merge of kind [k] swapped in, within the [budget]. */
    private fun takeBlock(k: Kind, b: Block, budget: () -> Boolean, uploaded: () -> Unit) {
        val id = k.id
        val j = b.job[id] ?: return
        if (!j.isDone || !budget()) return
        b.job[id] = null
        val (p, with) = j.get()
        b.layer[id]?.destroy(later = true); b.layer[id] = null; b.bytes[id] = 0
        if (p != null) {
            val l = layer(); GpuAccount.scoped("world country") { l.upload(p, casts = true) }
            if (id != PROPS) l.seeThrough = false
            b.layer[id] = l; b.bytes[id] = p.bytes.toLong(); place(l, b.bi * WorldCountry.CELL_M * BLOCK, b.bj * WorldCountry.CELL_M * BLOCK); b.gen = origin.generation
        }
        b.with[id] = with
        b.pending[id] = emptySet()
        uploaded()
    }

    private fun draw(f: TownExtra.Frame) {
        var tris = 0; var n3 = 0; var ni = 0
        java.util.Arrays.fill(drawsBy, 0)
        val pose = f.pose
        for (c in cells.values) {
            if (c.gen != origin.generation) c.data?.let { d ->
                c.trees?.let { place(it, d.x0, d.z0) }
                for (l in c.single) if (l != null) place(l, d.x0, d.z0)
                c.gen = origin.generation
            }
            c.trees?.let { l ->
                l.update(f.look, f.dark, c.shown3d, 1f, c.shown3d)
                if (c.shown3d) { drawsBy[0] += l.primitives; tris += l.triangles; n3++ }
            }
            val b = blocks[blockOf(c)]
            for (k in kinds) {
                val l = c.single[k.id] ?: continue
                // (held by its block: not drawn, let go below; else faded by screen door over the last part of its reach)
                val held = b != null && b.holds(k.id, c.key)
                val fade = if (held) 0f else k.fade(c.dist)
                wear(k, l, f)
                // (the things' shadows go at half their fade, the farmsteads' with them, as before)
                l.update(f.look, f.dark, fade > .02f, fade, (if (k.id == PROPS) fade > .5f else fade > .02f) && c.dist < SHADOW_M)
                if (fade > .02f) { drawsBy[if (k.id == PROPS) 3 else 5] += l.primitives; tris += l.triangles }
            }
        }
        for (b in blocks.values) for (k in kinds) {
            val l = b.layer[k.id] ?: continue
            if (b.gen != origin.generation) { place(l, b.bi * WorldCountry.CELL_M * BLOCK, b.bj * WorldCountry.CELL_M * BLOCK) }
            wear(k, l, f)
            l.update(f.look, f.dark, true, 1f, b.dist < SHADOW_M)
            drawsBy[if (k.id == PROPS) 2 else 4] += l.primitives; tris += l.triangles
        }
        for (b in blocks.values) b.gen = origin.generation
        for (t in tiles.values) t.imp?.let { l ->
            if (t.gen != origin.generation) { place(l, t.ti * WorldCountry.CELL_M * TILE, t.tj * WorldCountry.CELL_M * TILE); t.gen = origin.generation }
            l.update(f.look, f.dark, true, 1f, false)
            drawsBy[1] += l.primitives; tris += l.triangles; ni++
        }
        lastDraws = drawsBy.sum(); lastTris = tris; drawnTrees3d = n3; drawnImpostors = ni
        // (a cell's own batch its block now holds is let go)
        for (c in cells.values) {
            val b = blocks[blockOf(c)] ?: continue
            for (k in kinds) {
                val l = c.single[k.id] ?: continue
                if (c.whole[k.id] && b.holds(k.id, c.key)) { l.destroy(later = true); c.single[k.id] = null; c.singleBytes[k.id] = 0 }
            }
        }
        if (pose.ex.isNaN()) return
    }

    /** The farmsteads' windows and glass wear the town's night glow and glass tint, as the town's models do (the things keep their own). */
    private fun wear(k: Kind, l: PropLayer, f: TownExtra.Frame) {
        if (k.id == PROPS) return
        l.glowParam = f.glow; l.glassParam = f.glass
    }

    /** Leaves it out of the frames (a comparison: the bare country). */
    var hidden = false

    private fun hideAll() {
        val z = FloatArray(16)
        for (c in cells.values) { c.trees?.update(z, 0f, false, 1f, false); for (l in c.single) l?.update(z, 0f, false, 1f, false) }
        for (b in blocks.values) for (l in b.layer) l?.update(z, 0f, false, 1f, false)
        for (t in tiles.values) t.imp?.update(z, 0f, false, 1f, false)
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

    // ------------------------------------------------------------------ the farmsteads' art (the capital's farm models)

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

    /** Frees what is out of reach: 3D trees past [R3D], things and farmsteads past their reach, tiles past [R_IMP], the data past all of it. */
    private fun evict() {
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val c = it.next().value
            if (c.trees != null && c.dist > R3D + HYST3D && (!c.shown3d || c.dist > R3D + 2 * HYST3D)) { c.trees!!.destroy(later = true); c.trees = null; c.treesBytes = 0 }
            if (c.treesJob != null && c.dist > R3D + HYST3D) { c.treesJob!!.cancel(false); c.treesJob = null }
            for (k in kinds) {
                val id = k.id
                if (c.single[id] != null && c.dist > k.reach + HYST) { c.single[id]!!.destroy(later = true); c.single[id] = null; c.singleBytes[id] = 0 }
                if (c.singleJob[id] != null && c.dist > k.reach + HYST) { c.singleJob[id]!!.cancel(false); c.singleJob[id] = null }
            }
            if (c.dist > R_PROPS + HYST) c.propsMesh = null
            // (the cell's distance is set only while wanted: one not seen this frame is measured here)
            val d = rectDistance(c.ci * WorldCountry.CELL_M, c.cj * WorldCountry.CELL_M, WorldCountry.CELL_M, eyeX, eyeZ)
            c.dist = d.toFloat()
            if (d > R_IMP + WorldCountry.CELL_M + KEEP_M && c.trees == null && c.single.all { it == null } && c.singleJob.all { it == null }) {
                c.dataJob?.cancel(false); it.remove(); wanted.remove(c)
            }
        }
        val bt = blocks.entries.iterator()
        while (bt.hasNext()) {
            val b = bt.next().value
            val bm = WorldCountry.CELL_M * BLOCK
            val d = rectDistance(b.bi * bm, b.bj * bm, bm, eyeX, eyeZ)
            b.dist = d.toFloat()
            for (k in kinds) if (d > k.reach + HYST * 2) {
                b.layer[k.id]?.destroy(later = true); b.layer[k.id] = null; b.bytes[k.id] = 0; b.with[k.id] = emptySet()
                b.job[k.id]?.cancel(false); b.job[k.id] = null; b.pending[k.id] = emptySet()
            }
            if (b.layer.all { it == null } && b.job.all { it == null } && d > max(R_PROPS, R_FARMS) + HYST * 2) { bt.remove(); nearBlocks.remove(b) }
        }
        val ti = tiles.entries.iterator()
        while (ti.hasNext()) {
            val t = ti.next().value
            val d = rectDistance(t.ti * WorldCountry.CELL_M * TILE, t.tj * WorldCountry.CELL_M * TILE, WorldCountry.CELL_M * TILE, eyeX, eyeZ)
            t.dist = d.toFloat()
            if (d > R_IMP + HYST * 2) { t.imp?.destroy(later = true); t.job?.cancel(false); ti.remove(); near.remove(t) }
        }
    }

    private fun account() {
        WorldMemory.propsBuffers = cells.values.sumOf { it.treesBytes + it.singleBytes.sum() } + blocks.values.sumOf { it.bytes.sum() } + tiles.values.sumOf { it.bytes }
        WorldMemory.propsTextures = impBytes + farmBytes
        WorldMemory.propsCells = cells.size
    }

    override fun destroy() {
        impJob?.cancel(true); farmJob?.cancel(true)
        for (c in cells.values) {
            c.dataJob?.cancel(true); c.treesJob?.cancel(true); c.trees?.destroy()
            for (j in c.singleJob) j?.cancel(true)
            for (l in c.single) l?.destroy()
        }
        for (b in blocks.values) { for (j in b.job) j?.cancel(true); for (l in b.layer) l?.destroy() }
        for (t in tiles.values) { t.job?.cancel(true); t.imp?.destroy() }
        cells.clear(); blocks.clear(); tiles.clear()
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
        const val BLOCKS_IN_FLIGHT = 3
        const val UPLOAD_NS = 1_500_000L
        /** The whole pass (what is wanted, what is stale, what goes) every this many frames, or once the eye has moved this far (m). */
        const val PLAN_FRAMES = 6
        const val PLAN_M = 8.0
        /** Variants of each look the country grows (WorldCountry's variants are 0..7). */
        const val VARIANTS = 8
        /** Each species' reference height (m) its look is grown and baked at (TreeKit's species order). */
        val REF_M = floatArrayOf(15f, 14f, 16f, 15f, 14f, 5f, 19f, 17f, 19f, 2.4f, 2.6f)
        /**
         * The impostor tiles' and the merged blocks' side (cells): a tile of 4 x 4 cells is 538 m (step 1b's first tiles
         * were 2 x 2: 56 tiles in the 12 km chase view, a draw each), a block the same.
         */
        const val TILE = 4
        const val BLOCK = 4
        /** A cell joins its block this far (m) inside where it stands whole, and leaves it once past it. */
        const val WHOLE_HYST = 10f
        /** The merged kinds. */
        private const val PROPS = 0
        private const val FARMS = 1
        private const val HOUSES = 2
        private const val KINDS = 3
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
