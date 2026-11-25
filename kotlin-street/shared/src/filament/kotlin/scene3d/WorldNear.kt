package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldTerrain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The near terrain (WORLD_GEN.md phase W5a): [WorldTerrain]'s chunked ground streamed round the camera, a [TownExtra]
 * beside the far view ([WorldFar]), drawn in terrain.mat (the ground's pixel-art pictures by material, dithered borders,
 * cliffs faceted and textured on the wall).
 *
 * Each frame ([frame]) it picks the nodes for the camera ([WorldTerrain.select]: a quadtree from 2.1 m quads near the
 * camera to 33.6 m ones 3 km off), starts the missing ones on the town's workers (coarse first, then nearest; at most
 * [IN_FLIGHT] at a time, nothing heavy on the engine's thread), uploads at most [UPLOADS] a frame, and draws for each root
 * block the finest cover that is wholly ready (a parent stays until all four children are up, and the other way round), so
 * a hole never shows. A root block drawn here is hidden in the far view ([WorldFar.cover]) in the same frame; both drop
 * skirts at their edges, so where they meet, and where two levels meet, no crack shows.
 *
 * Every node is its own entity in its own local frame (its corner), its root transform its corner less the render origin.
 * Uploaded nodes no longer drawn are kept a while (up to [CACHE]) for a quick way back; its memory and build times go to
 * [WorldMemory].
 */
internal class WorldNear(
    private val kit: TownExtra.Kit,
    private val far: WorldFar,
    private val origin: RenderOrigin,
    /** The town's own grass (m from the plan's origin), left to the town. */
    private val townGrass: DoubleArray?,
) : TownExtra {
    private val u = origin.unitsPerMetre
    private val engine get() = kit.engine

    @Volatile var terrain: WorldTerrain? = null; private set
    private var terrainJob: Future<WorldTerrain>? = null

    // ---- the atlas: a layer a material, made one layer at a time on a worker and uploaded as each is ready
    private var atlas: Texture? = null
    private var atlasLayer = 0
    private var atlasJob: Future<ByteArray>? = null
    @Volatile private var atlasGrass: PixImg? = null
    /** Each layer's mean colour (its last mip level), for reports (WorldTerrain.MEAN is what the far view wears). */
    private val means = IntArray(WorldTerrain.LAYERS)
    private val atlasSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also { it.anisotropy = 8f }
    private val idSampler = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val atlasReady: Boolean get() = atlas != null && atlasLayer >= WorldTerrain.LAYERS

    /** A node made ready on a worker: its vertices, indices and material ids packed. */
    class Packed(val node: WorldTerrain.Node, val vb: ByteBuffer?, val ib: ByteBuffer?, val vertices: Int, val indices: Int, val ids: ByteBuffer?, val box: FloatArray, val buildMs: Float, val packMs: Float) {
        val bytes: Long get() = (vb?.capacity() ?: 0).toLong() + (ib?.capacity() ?: 0) + (ids?.capacity() ?: 0)
        val triangles: Int get() = indices / 3
    }

    private class Live(val node: WorldTerrain.Node, val entity: Int, val vb: VertexBuffer?, val ib: IndexBuffer?, val ids: Texture?, val mi: MaterialInstance?, val bytes: Long, val texBytes: Long, val triangles: Int) {
        var gen = -1
        var used = 0L
        var shown = false
    }

    private val live = HashMap<WorldTerrain.Node, Live>()
    private val jobs = HashMap<WorldTerrain.Node, Future<Packed>>()
    private var desired: List<WorldTerrain.Node> = emptyList()
    private var desiredSet: Set<WorldTerrain.Node> = emptySet()
    /** The nodes the eye will want in [LEAD_S] at its speed (made ahead, kept from eviction; drawn once wanted). */
    private var ahead: List<WorldTerrain.Node> = emptyList()
    private var keepSet: Set<WorldTerrain.Node> = emptySet()
    /** Nodes drawn coarser than wanted (a wanted one not up yet) in the last frame, and over the whole run (frames). */
    var coarseNow = 0; private set
    var coarseFrames = 0L; private set
    private var eye: WorldTerrain.Eye? = null
    private var eyeGround = 0.0
    private var frameNo = 0L
    private val drawn = ArrayList<WorldTerrain.Node>()
    private val covered = HashSet<Long>()

    // ---- measurements
    private val builds = ArrayList<Float>()
    private var packedMs = 0.0
    var drawnTriangles = 0; private set
    var drawnNodes = 0; private set

    override val pending: Boolean get() = terrain == null || !atlasReady || desired.any { it !in live }

    override val report: String get() {
        val b = synchronized(builds) { builds.sorted() }
        val st = if (b.isEmpty()) "none built" else "%d nodes built: median %.1f ms, p90 %.1f, max %.1f (one worker's time each)".format(java.util.Locale.US, b.size, b[b.size / 2], b[(b.size * 9) / 10], b.last())
        return "world near terrain: ${desired.size} nodes wanted, ${live.size} up, $drawnNodes drawn ($drawnTriangles triangles); $st; " +
            "levels drawn ${drawn.groupingBy { it.level }.eachCount().toSortedMap()}; ${covered.size} root blocks covered; the pictures' means ${means.joinToString { "%06X".format(it and 0xFFFFFF) }}"
    }

    // ------------------------------------------------------------------ the engine's thread

    override fun step() {
        // the terrain's plan once the far view has its own (the near ground meets the far's finest level exactly)
        // (the far view makes it with its plan: its finest level wears the same materials)
        if (terrain == null && terrainJob == null) {
            terrain = far.terrain
            if (terrain == null && far.ready) { val plan = far.plan ?: return; terrainJob = kit.pool.submit(Callable { WorldTerrain(plan.sk, plan, townGrass) }) }
            if (terrain == null) return
        }
        terrainJob?.let { j -> if (j.isDone) { terrain = j.get(); terrainJob = null } }
        // (the world streamer planned the far view again: its terrain is the same ground (WORLD_STREAM.md), its roots the
        // new plan's; the nodes up stay valid)
        far.terrain?.let { ft -> if (terrain != null && ft !== terrain) { terrain = ft; eye = null } }
        stepAtlas(block = false)
    }

    /** Makes and uploads the atlas's next layer (waiting for it if [block]). */
    private fun stepAtlas(block: Boolean) {
        if (atlasReady) return
        val n = ATLAS
        if (atlas == null) {
            atlas = Texture.Builder().width(n).height(n).depth(WorldTerrain.LAYERS).levels(levels(n)).sampler(Texture.Sampler.SAMPLER_2D_ARRAY)
                .format(Texture.InternalFormat.SRGB8_A8).build(engine)
            GpuAccount.add(atlas!!, n, n * WorldTerrain.LAYERS, "SRGB8_A8 array", levels(n), chainBytes(n) * WorldTerrain.LAYERS)
            WorldMemory.nearTextures += chainBytes(n) * WorldTerrain.LAYERS
        }
        while (atlasLayer < WorldTerrain.LAYERS) {
            if (atlasJob == null) {
                val k = atlasLayer
                atlasJob = kit.pool.submit(Callable { layerBytes(k) })
            }
            val j = atlasJob!!
            if (!j.isDone && !block) return
            val bytes = j.get()
            atlasJob = null
            // (each mip level of this layer: one slice of the array)
            var off = 0; var s = n; var level = 0
            while (s >= 1) {
                val len = s * s * 4
                val buf = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
                buf.put(bytes, off, len).flip()
                atlas!!.setImage(engine, level, 0, 0, atlasLayer, s, s, 1, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
                off += len; s /= 2; level++
            }
            atlasLayer++
            if (!block) return
        }
        atlasGrass = null
    }

    /** One layer's texels and mip levels as RGBA bytes (a worker). */
    private fun layerBytes(k: Int): ByteArray {
        val n = ATLAS
        val pic: (String) -> PixImg? = { p -> kit.art?.image(p)?.let { im -> PixImg(im.width, im.height).also { StreetTextures.argb(im).copyInto(it.px) } } }
        val px = when (k) {
            WorldTerrain.GRASS, WorldTerrain.DRY -> {
                val g = atlasGrass ?: TownGround.grassPicture(n, pic).also { atlasGrass = it }
                if (k == WorldTerrain.GRASS) g.px else TerrainPics.layer(k, n, pic, g)
            }
            else -> TerrainPics.layer(k, n, pic, null)
        }
        val chain = TerrainPics.mips(px, n)
        means[k] = chain.last()[0]
        val out = ByteArray(chain.sumOf { it.size } * 4)
        var o = 0
        for (lv in chain) for (p in lv) { out[o++] = (p shr 16).toByte(); out[o++] = (p shr 8).toByte(); out[o++] = p.toByte(); out[o++] = (p ushr 24).toByte() }
        return out
    }

    private val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val at = FloatArray(2)

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out[0] = 0; out[1] = 0; out[2] = 0
        frameNo++
        val t = terrain
        if (t == null || !atlasReady || hidden) { far.cover(emptySet()); hideAll(); return out }
        aimAt(t, f.pose)
        schedule(t)
        upload(UPLOADS, UPLOAD_BUDGET_NS)
        // the cover: per root block, the finest wholly ready nodes
        drawn.clear(); covered.clear()
        val e = eye!!
        for (r in t.roots(e)) if (coverOf(t, r, e)) covered += WorldFar.blockKey(r.i, r.j)
        coarseNow = desired.count { it !in live }
        if (coarseNow > 0) coarseFrames++
        far.cover(HashSet(covered))
        val rm = engine.renderableManager
        val show = HashSet(drawn)
        var draws = 0; var tris = 0
        for (l in live.values) {
            val on = l.node in show
            if (on) l.used = frameNo
            if (l.entity == 0) { l.shown = on; continue }
            if (on != l.shown) { val ri = rm.getInstance(l.entity); if (ri != 0) rm.setLayerMask(ri, 0xff, if (on) 1 else 0); l.shown = on }
            if (!on) continue
            if (l.gen != origin.generation) {
                origin.toRender(corner(l.node), at)
                m[12] = at[0]; m[13] = 0f; m[14] = at[1]
                val tm = engine.transformManager
                if (!tm.hasComponent(l.entity)) tm.create(l.entity)
                tm.setTransform(tm.getInstance(l.entity), m)
                l.gen = origin.generation
            }
            l.mi!!.setParameter("look", MaterialInstance.FloatElement.MAT4, f.look, 0, 1)
            draws++; tris += l.triangles
        }
        drawnNodes = draws; drawnTriangles = tris
        evict()
        account()
        out[0] = draws; out[1] = tris
        return out
    }

    /** Leaves it out of the frames (a comparison: the far view alone). */
    var hidden = false

    private fun hideAll() {
        val rm = engine.renderableManager
        for (l in live.values) if (l.shown && l.entity != 0) { val ri = rm.getInstance(l.entity); if (ri != 0) rm.setLayerMask(ri, 0xff, 0); l.shown = false }
        drawnNodes = 0; drawnTriangles = 0
    }

    /** The world position of [n]'s corner. */
    private fun corner(n: WorldTerrain.Node): WorldPos = WorldPos(Planet.wrapX(n.gx0 * Planet.TILE_M), n.gz0 * Planet.TILE_M)

    /** The camera of [pose] in world terms; the nodes wanted for it, worked out again once it has moved a little. */
    private fun aimAt(t: WorldTerrain, pose: TownPose) {
        val w = origin.toWorld(pose.ex.toDouble(), pose.ez.toDouble())
        val e = WorldTerrain.Eye(w.x, w.z, pose.ey / u)
        val last = eye
        if (last != null && kotlin.math.hypot(Planet.wrapDx(e.x - last.x), e.z - last.z) < RESELECT_M && kotlin.math.abs(e.y - last.y) < RESELECT_M) return
        eye = e
        eyeGround = t.heightLocal(t.localX(Math.round(e.x / Planet.TILE_M)), t.localZ(Math.round(e.z / Planet.TILE_M)))
        desired = t.select(e, eyeGround)
        desiredSet = desired.toHashSet()
        // (the eye [LEAD_S] ahead at the streamer's eased velocity: those nodes are made before they are wanted)
        val vx = far.stream.vx; val vz = far.stream.vz
        ahead = if (vx * vx + vz * vz < 4.0) emptyList() else {
            val a = WorldTerrain.Eye(e.x + vx * LEAD_S, e.z + vz * LEAD_S, e.y)
            t.select(a, eyeGround).filter { it !in desiredSet }
        }
        keepSet = desiredSet + ahead
    }

    /** Starts the wanted nodes not yet made: coarse first (a cover comes soonest), then nearest. */
    private fun schedule(t: WorldTerrain) {
        // (jobs for nodes no longer wanted are dropped: a worker already on one finishes it, unused)
        val it = jobs.entries.iterator()
        while (it.hasNext()) { val (n, j) = it.next(); if (n !in keepSet && !j.isDone) { j.cancel(false); it.remove() } }
        if (jobs.size >= IN_FLIGHT) return
        val e = eye ?: return
        val todo = desired.filter { it !in live && it !in jobs }.sortedWith(compareBy({ -it.level }, { t.distance2d(it, e) })) +
            ahead.filter { it !in live && it !in jobs }.sortedWith(compareBy({ -it.level }, { t.distance2d(it, e) }))
        for (n in todo) {
            if (jobs.size >= IN_FLIGHT) break
            jobs[n] = kit.pool.submit(Callable { pack(t, n, u) })
        }
    }

    /** Uploads finished nodes: at most [max] and while [budgetNs] lasts (the first always). */
    private fun upload(max: Int, budgetNs: Long) {
        val t0 = System.nanoTime()
        var n = 0
        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            val (node, j) = it.next()
            if (!j.isDone) continue
            it.remove()
            if (j.isCancelled) continue
            val p = try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("building the near terrain's node $node", e.cause ?: e) }
            if (node !in keepSet || node in live) continue
            live[node] = make(p)
            n++
            if (n >= max || System.nanoTime() - t0 > budgetNs) break
        }
    }

    /** A packed node made Filament's (the engine's thread). */
    private fun make(p: Packed): Live {
        val vb0 = p.vb; val ib0 = p.ib
        if (vb0 == null || ib0 == null || p.indices == 0) return Live(p.node, 0, null, null, null, null, 0, 0, 0)
        val vb = VertexBuffer.Builder().bufferCount(1).vertexCount(p.vertices)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.SHORT4, 12, STRIDE)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS).build(engine)
        vb.setBufferAt(engine, 0, vb0)
        GpuAccount.addBuffer(vb, vb0.limit().toLong())
        val short = p.vertices <= 65536
        val ib = IndexBuffer.Builder().indexCount(p.indices).bufferType(if (short) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT).build(engine)
        ib.setBuffer(engine, ib0)
        GpuAccount.addBuffer(ib, ib0.limit().toLong())
        val w = WorldTerrain.QUADS + 2
        val ids = Texture.Builder().width(w).height(w).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.R8).build(engine)
        ids.setImage(engine, 0, Texture.PixelBufferDescriptor(p.ids!!, Texture.Format.R, Texture.Type.UBYTE, 1))
        GpuAccount.add(ids, w, w, "R8", 1, (w * w).toLong())
        val sp = p.node.step * Planet.TILE_M
        val mi = kit.material("terrain").createInstance().also { mi ->
            mi.setParameter("atlas", atlas!!, atlasSampler)
            mi.setParameter("ids", ids, idSampler)
            mi.setParameter("node", (sp * u).toFloat(), (WorldTerrain.TEXEL_M * u).toFloat(), WorldTerrain.QUADS.toFloat(), (DITHER_M / sp).toFloat())
            mi.setParameter("corner", Math.floorMod(p.node.gx0 * WorldTerrain.TEXELS_A_TILE, ATLAS.toLong()).toFloat(), Math.floorMod(p.node.gz0 * WorldTerrain.TEXELS_A_TILE, ATLAS.toLong()).toFloat(), ATLAS.toFloat(), 0f)
        }
        val e = EntityManager.get().create()
        val b = p.box
        RenderableManager.Builder(1).boundingBox(Box(b[0], b[1], b[2], b[3], b[4], b[5])).culling(true).castShadows(false).receiveShadows(System.getProperty("nearShadows") != "0").priority(2)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, mi).build(engine, e)
        kit.scene.addEntity(e)
        val rm = engine.renderableManager
        rm.setLayerMask(rm.getInstance(e), 0xff, 0)
        synchronized(builds) { builds += p.buildMs }
        packedMs += p.packMs
        return Live(p.node, e, vb, ib, ids, mi, (vb0.capacity() + ib0.capacity()).toLong(), (w * w).toLong(), p.triangles)
    }

    private fun destroy(l: Live) {
        if (l.entity == 0) return
        kit.scene.remove(l.entity); engine.destroyEntity(l.entity); EntityManager.get().destroy(l.entity)
        l.mi?.let { engine.destroyMaterialInstance(it) }
        l.vb?.let { engine.destroyVertexBuffer(it) }; l.ib?.let { engine.destroyIndexBuffer(it) }
        l.ids?.let { GpuAccount.remove(it); engine.destroyTexture(it) }
    }

    /**
     * Keeps the [CACHE] most recently drawn nodes no longer wanted, within [WorldMemory.NEAR_GPU_BUDGET] for all the nodes
     * up; frees the rest, the least recently drawn first.
     */
    private fun evict() {
        val spare = live.values.filter { !it.shown && it.node !in keepSet }
        var bytes = live.values.sumOf { it.bytes + it.texBytes }
        if (spare.size <= CACHE && bytes <= WorldMemory.NEAR_GPU_BUDGET) return
        var drop = spare.size - CACHE
        for (l in spare.sortedBy { it.used }) {
            if (drop <= 0 && bytes <= WorldMemory.NEAR_GPU_BUDGET) break
            destroy(l); live.remove(l.node); drop--; bytes -= l.bytes + l.texBytes
        }
    }

    private fun account() {
        WorldMemory.nearBuffers = live.values.sumOf { it.bytes }
        WorldMemory.nearIds = live.values.sumOf { it.texBytes }
        WorldMemory.nearNodes = live.size
        WorldMemory.nearTriangles = drawnTriangles
        WorldMemory.nearDraws = drawnNodes
    }

    private fun ready(n: WorldTerrain.Node): Boolean = n in live

    /** Covers [n]'s square with ready nodes, as finely as wanted (into [drawn]); false if it cannot be covered yet. */
    private fun coverOf(t: WorldTerrain, n: WorldTerrain.Node, e: WorldTerrain.Eye): Boolean {
        val mark = drawn.size
        if (t.split(n, e, eyeGround)) {
            var ok = true
            for (k in 0 until 4) if (!coverOf(t, n.child(k), e)) { ok = false; break }
            if (ok) return true
            truncate(mark)
            if (ready(n)) { drawn += n; return true }
            return false
        }
        if (ready(n)) { drawn += n; return true }
        // (finer nodes still up from before: drawn until this one is)
        if (n.level > 0 && finer(n)) return true
        truncate(mark)
        return false
    }

    private fun finer(n: WorldTerrain.Node): Boolean {
        for (k in 0 until 4) {
            val c = n.child(k)
            if (ready(c)) drawn += c else if (c.level == 0 || !finer(c)) return false
        }
        return true
    }

    private fun truncate(size: Int) { while (drawn.size > size) drawn.removeAt(drawn.size - 1) }

    /**
     * (Previews, a teleport) Makes everything [pose] wants at once, waiting for the workers: the terrain's plan, the atlas
     * and every wanted node, uploaded.
     */
    fun settle(pose: TownPose) {
        while (terrain == null) {
            step()
            terrainJob?.get()
            if (terrain == null) Thread.sleep(2)
        }
        val t = terrain ?: return
        stepAtlas(block = true)
        eye = null
        aimAt(t, pose)
        while (desired.any { it !in live }) {
            schedule(t)
            for (j in jobs.values.toList()) try { j.get() } catch (_: java.util.concurrent.CancellationException) {}
            upload(Int.MAX_VALUE, Long.MAX_VALUE)
        }
    }

    override fun destroy() {
        terrainJob?.cancel(true); atlasJob?.cancel(true)
        for (j in jobs.values) j.cancel(true)
        jobs.clear()
        for (l in live.values) destroy(l)
        live.clear()
        atlas?.let { GpuAccount.remove(it); engine.destroyTexture(it) }
        atlas = null
        WorldMemory.nearBuffers = 0; WorldMemory.nearIds = 0; WorldMemory.nearTextures = 0; WorldMemory.nearNodes = 0; WorldMemory.nearTriangles = 0; WorldMemory.nearDraws = 0
    }

    companion object {
        /** The atlas's layers' size (texels: 39.8 m before a picture repeats). */
        const val ATLAS = 1024
        /** Bytes a vertex: position (3 floats), the tangent frame (4 shorts). */
        const val STRIDE = 20
        /** Nodes being made at once, uploaded a frame, and the upload's time budget a frame. */
        const val IN_FLIGHT = 6
        const val UPLOADS = 2
        const val UPLOAD_BUDGET_NS = 2_500_000L
        /** Uploaded nodes kept when no longer wanted. */
        const val CACHE = 48
        /** The nodes the eye will want this far ahead (s) at its speed are made before they are wanted. */
        const val LEAD_S = 4.0
        /** The camera moves this far (m) before the nodes are chosen again. */
        const val RESELECT_M = 6.0
        /** How far (m) the dither reaches across a border between two materials. */
        const val DITHER_M = 1.1

        private fun levels(n: Int): Int { var l = 1; var s = n; while (s > 1) { s /= 2; l++ }; return l }
        private fun chainBytes(n: Int): Long { var b = 0L; var s = n; while (s >= 1) { b += s.toLong() * s * 4; s /= 2 }; return b }

        /**
         * Packs [node]'s ground for the GPU (any thread): shared vertices with smooth normals on rolling ground, a flat
         * normal a triangle on cliffs (their own vertices), the quads left to the town left out, a skirt down each edge.
         */
        fun pack(t: WorldTerrain, node: WorldTerrain.Node, unitsPerMetre: Double): Packed {
            val d = t.build(node)
            val t1 = System.nanoTime()
            val n = WorldTerrain.QUADS
            val spM = node.step * Planet.TILE_M
            val sp = (spM * unitsPerMetre).toFloat()
            val u = unitsPerMetre.toFloat()
            if (d.empty) return Packed(node, null, null, 0, 0, null, FloatArray(6), d.ms, 0f)
            var facets = 0
            for (b in 0 until n) for (a in 0 until n) if (!d.skipped[b * n + a] && d.faceted[b * n + a]) facets++
            val shared = (n + 1) * (n + 1)
            val skirtV = 4 * (n + 1)
            val nv = shared + facets * 6 + skirtV
            val vb = ByteBuffer.allocateDirect(nv * STRIDE).order(ByteOrder.nativeOrder())
            val q = FloatArray(4)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                // the tangent along x, made square to the normal; the bitangent the normal across it
                var tx = 1f - nx * nx; var ty = -nx * ny; var tz = -nx * nz
                val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
                tx /= tl; ty /= tl; tz /= tl
                val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
                MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
                vb.putFloat(x).putFloat(y).putFloat(z)
                for (c in q) vb.putShort((c.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                lo = min(lo, y); hi = max(hi, y)
            }
            // the shared vertices, their normals from the heights round them (the ring outside included)
            val nrm = FloatArray(shared * 3)
            for (b in 0..n) for (a in 0..n) {
                val gx = -(d.h(a + 1, b) - d.h(a - 1, b)) / (2f * spM.toFloat())
                val gz = -(d.h(a, b + 1) - d.h(a, b - 1)) / (2f * spM.toFloat())
                val l = sqrt(gx * gx + 1f + gz * gz)
                val k = (b * (n + 1) + a) * 3
                nrm[k] = gx / l; nrm[k + 1] = 1f / l; nrm[k + 2] = gz / l
                put(a * sp, d.h(a, b) * u, b * sp, nrm[k], nrm[k + 1], nrm[k + 2])
            }
            val idx = ArrayList<Int>(n * n * 6 + 4 * n * 6)
            var next = shared
            fun v(a: Int, b: Int) = b * (n + 1) + a
            for (b in 0 until n) for (a in 0 until n) {
                if (d.skipped[b * n + a]) continue
                if (!d.faceted[b * n + a]) {
                    // (split from (a, b) to (a+1, b+1); counter-clockwise from above)
                    idx += v(a, b); idx += v(a, b + 1); idx += v(a + 1, b + 1)
                    idx += v(a, b); idx += v(a + 1, b + 1); idx += v(a + 1, b)
                    continue
                }
                // a cliff's quad: each triangle its own flat normal
                for (tri in 0..1) {
                    val c = if (tri == 0) intArrayOf(a, b, a, b + 1, a + 1, b + 1) else intArrayOf(a, b, a + 1, b + 1, a + 1, b)
                    val ax = c[0] * sp; val ay = d.h(c[0], c[1]) * u; val az = c[1] * sp
                    val bx = c[2] * sp; val by = d.h(c[2], c[3]) * u; val bz = c[3] * sp
                    val cx = c[4] * sp; val cy = d.h(c[4], c[5]) * u; val cz = c[5] * sp
                    val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
                    val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
                    var fx = e1y * e2z - e1z * e2y; var fy = e1z * e2x - e1x * e2z; var fz = e1x * e2y - e1y * e2x
                    val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9f)
                    fx /= fl; fy /= fl; fz /= fl
                    put(ax, ay, az, fx, fy, fz); put(bx, by, bz, fx, fy, fz); put(cx, cy, cz, fx, fy, fz)
                    idx += next; idx += next + 1; idx += next + 2
                    next += 3
                }
            }
            // the skirts: round the rim clockwise from above (north edge west to east, east north to south, ...), facing out,
            // each hanging its edge's depth, wearing its top vertex's normal (it reads as the ground going on)
            for (e in 0 until 4) {
                val depth = d.skirt[e] * u
                val base = next
                for (k in 0..n) {
                    val (a, b) = when (e) { 0 -> k to 0; 1 -> n to k; 2 -> (n - k) to n; else -> 0 to (n - k) }
                    val s = v(a, b) * 3
                    put(a * sp, d.h(a, b) * u - depth, b * sp, nrm[s], nrm[s + 1], nrm[s + 2])
                }
                for (k in 0 until n) {
                    val (a0, b0) = when (e) { 0 -> k to 0; 1 -> n to k; 2 -> (n - k) to n; else -> 0 to (n - k) }
                    val (a1, b1) = when (e) { 0 -> (k + 1) to 0; 1 -> n to (k + 1); 2 -> (n - k - 1) to n; else -> 0 to (n - k - 1) }
                    // (only under a quad that is drawn)
                    val qa = min(a0, a1).coerceAtMost(n - 1); val qb = min(b0, b1).coerceAtMost(n - 1)
                    if (d.skipped[qb * n + qa]) continue
                    val top0 = v(a0, b0); val top1 = v(a1, b1); val bot0 = base + k; val bot1 = base + k + 1
                    idx += top0; idx += top1; idx += bot1
                    idx += top0; idx += bot1; idx += bot0
                }
                next += n + 1
                lo = min(lo, d.minY * u - depth)
            }
            vb.flip()
            val short = nv <= 65536
            val ib = ByteBuffer.allocateDirect(idx.size * (if (short) 2 else 4)).order(ByteOrder.nativeOrder())
            if (short) for (i in idx) ib.putShort(i.toShort()) else for (i in idx) ib.putInt(i)
            ib.flip()
            val w = n + 2
            val ids = ByteBuffer.allocateDirect(w * w).order(ByteOrder.nativeOrder())
            ids.put(d.ids).flip()
            val size = n * sp
            val box = floatArrayOf(size / 2f, (lo + hi) / 2f, size / 2f, size / 2f + 1f, (hi - lo) / 2f + 1f, size / 2f + 1f)
            return Packed(node, vb, ib, nv, idx.size, ids, box, d.ms, (System.nanoTime() - t1) / 1e6f)
        }

        /**
         * Adds the near terrain beside [far] to [town] (WorldFar.attach does, unless the near terrain is turned off:
         * -DworldNear=0 in the previews); [townGrass]: the town's own grass in metres from the plan's origin.
         */
        fun attach(town: FilamentTown, far: WorldFar, origin: RenderOrigin, townGrass: DoubleArray?): WorldNear = town.addExtra { WorldNear(it, far, origin, townGrass) }
    }
}
