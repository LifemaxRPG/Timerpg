package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One batch's geometry on the GPU: [vb] and [ib], and its indices from [first] for [count] (a renderable draws exactly
 * that range: `geometry(i, TRIANGLES, vb, ib, first + offset, n)`). Most share [vb] and [ib] with many others
 * ([BufferArena]); a big batch has buffers of its own. [free] gives its range back (or destroys its own buffers).
 */
internal class GeomSlice internal constructor(
    val vb: VertexBuffer, val ib: IndexBuffer, val first: Int, val count: Int,
    internal val vPage: BufferArena.Page?, internal val vOff: Int, internal val vLen: Int,
    internal val iPage: BufferArena.Page?, internal val iOff: Int, internal val iLen: Int,
    /** Quantised ([TownVertex.quantize]): how the materials read its positions and texture coordinates back; null: floats. */
    internal val quant: TownVertex.Quant? = null,
) {
    @Volatile var freed = false; private set

    /** Bytes a vertex of it takes on the GPU. */
    val stride: Int get() = if (quant != null) TownVertex.QSTRIDE else TownVertex.STRIDE

    /**
     * Tells [mi] (any material instance drawing this slice: street_lit, street_albedo, shadow_proxy) how to read its
     * vertices back: the quantised batch's `posMap`, `posScale` and `uvMap`, or nothing for floats (the materials' default, 0, reads
     * them as they are). Every material instance made for a slice must have it.
     */
    fun decode(mi: com.google.android.filament.MaterialInstance) {
        val q = quant ?: return
        val m = mi.material
        if (m.hasParameter("posMap")) mi.setParameter("posMap", q.posMap[0], q.posMap[1], q.posMap[2], q.posMap[3])
        if (m.hasParameter("posScale")) mi.setParameter("posScale", q.posScale[0], q.posScale[1], q.posScale[2], q.posScale[3])
        if (m.hasParameter("uvMap")) mi.setParameter("uvMap", q.uvMap[0], q.uvMap[1], q.uvMap[2], q.uvMap[3])
    }

    /** Its triangles. */
    val triangles: Int get() = count / 3

    /** Gives its range back to its arena (the engine's thread), or destroys its own buffers; twice is harmless. */
    fun free(engine: Engine) {
        if (freed) return
        freed = true
        if (vPage == null) { engine.destroyVertexBuffer(vb); engine.destroyIndexBuffer(ib); return }
        BufferArena.giveBack(engine, this)
    }
}

/**
 * The town's vertex and index buffers packed together (STATUS.md, "The GPU memory by part"). The town once held some
 * 32,000 buffer objects, 28,500 of them under 16 KB (a tree cell, a yard's batch, a lamp row): the GPU driver keeps each
 * allocation apart (its own pages, its own book-keeping), which cost the phone far more than the bytes. Now each engine
 * has pages of [PAGE_VERTICES] vertices in [TownVertex]'s layout (1.75 MB) and of [PAGE_INDICES] 16-bit indices (384 KB),
 * and a batch of up to [MAX_PACKED_VERTICES] vertices takes a range of each: its indices are rebased onto its vertices'
 * place in their page (16-bit indices reach a whole page), and its renderable draws its range of the index page.
 * Freeing a range (a yard baked again, a street thing knocked down, a chunk let go, a tree cell's detail dropped) gives
 * it back to its page's free list, merged with its free neighbours; a page left empty is destroyed (one spare is kept).
 *
 * Everything here runs on the engine's thread (as every Filament call does), one set of pages per engine.
 */
object BufferArena {
    /** Vertices a vertex page holds (all a 16-bit index reaches): 1.75 MB at [TownVertex.STRIDE]. */
    const val PAGE_VERTICES = 65536
    /** Indices an index page holds (16-bit): 384 KB. */
    const val PAGE_INDICES = 196608
    /** The biggest batch packed into the pages; a bigger one (over 448 KB of vertices) keeps buffers of its own. */
    const val MAX_PACKED_VERTICES = 16384

    /** Off: every batch has buffers of its own, as before (to compare). */
    @Volatile var enabled = true

    /** One buffer object, its free ranges (start -> length, in vertices or indices) kept merged; [stride]: bytes a vertex or index. */
    internal class Page(val vb: VertexBuffer?, val ib: IndexBuffer?, val capacity: Int, val lane: Int = 0, val stride: Int = 2) {
        val free = java.util.TreeMap<Int, Int>().also { it[0] = capacity }
        var used = 0
        var destroyed = false
        fun largest(): Int { var m = 0; for (v in free.values) if (v > m) m = v; return m }
        fun take(n: Int): Int {
            for ((s, l) in free) if (l >= n) {
                free.remove(s)
                if (l > n) free[s + n] = l - n
                used += n
                return s
            }
            return -1
        }
        fun give(s: Int, n: Int) {
            var start = s; var len = n
            free.lowerEntry(start)?.let { (ps, pl) -> if (ps + pl == start) { free.remove(ps); start = ps; len += pl } }
            free[start + len]?.let { nl -> free.remove(start + len); len += nl }
            free[start] = len
            used -= n
        }
    }

    /** A lane's pages: float vertices ([vPages]), quantised ones ([qPages]), indices. */
    private class Lane { val vPages = ArrayList<Page>(); val qPages = ArrayList<Page>(); val iPages = ArrayList<Page>() }
    private class Pool { val lanes = Array(LANES) { Lane() }; val vPages: List<Page> get() = lanes.flatMap { it.vPages + it.qPages }; val iPages: List<Page> get() = lanes.flatMap { it.iPages } }

    /**
     * The shared pages hold quantised vertices ([TownVertex.quantize]: 20 bytes instead of 28) for a batch whose picture's
     * size is given and whose box and texture coordinates keep their precision; off: floats, as before (to measure).
     */
    @Volatile var quantize = true
    /** Vertices uploaded quantised, and as floats (the reports'). */
    val quantized = java.util.concurrent.atomic.AtomicLong()
    val unquantized = java.util.concurrent.atomic.AtomicLong()

    /** How many sets of pages an engine keeps: [lane] 0 for what stays for the town's life, 1 for what is streamed in and out. */
    const val LANES = 2
    /**
     * The pages a batch uploaded now goes in (the engine's thread): what the town streams ([TownStream]) has pages of its own,
     * so a block let go empties whole pages instead of leaving holes in pages shared with the ground that stays.
     */
    @JvmStatic var lane = 0

    /** Runs [f] with its uploads in the streamed pages. */
    inline fun <T> streamed(f: () -> T): T { val was = lane; lane = 1; try { return f() } finally { lane = was } }
    private val pools = java.util.IdentityHashMap<Engine, Pool>()

    /**
     * Puts a packed batch on the GPU: [vb] ([nv] vertices in [TownVertex]'s layout) and [ib] ([ni] indices, 16-bit when
     * [short], counted from 0), into the pages when it is small enough, else into buffers of its own.
     */
    internal fun upload(engine: Engine, vb: ByteBuffer, nv: Int, ib: ByteBuffer, ni: Int, short: Boolean, texW: Int = 0, texH: Int = 0): GeomSlice {
        if (!enabled || !short || nv > MAX_PACKED_VERTICES || nv <= 0 || ni <= 0) {
            // (a big batch with buffers of its own: quantised too where it keeps the precision)
            val qo = if (quantize && nv > 0 && ni > 0) TownVertex.quantize(vb.duplicate().order(ByteOrder.nativeOrder()), nv, texW, texH) else null
            if (qo != null) quantized.addAndGet(nv.toLong()) else unquantized.addAndGet(maxOf(nv, 0).toLong())
            val v = if (qo != null) TownVertex.quantBuffer(engine, nv).also { GpuAccount.addBuffer(it, nv.toLong() * TownVertex.QSTRIDE) }
                else TownVertex.buffer(engine, kotlin.math.max(1, nv))
            v.setBufferAt(engine, 0, qo?.vb ?: vb)
            val i = TownVertex.indices(engine, kotlin.math.max(1, ni), short); i.setBuffer(engine, ib)
            return GeomSlice(v, i, 0, ni, null, 0, 0, null, 0, 0, qo)
        }
        val pool = synchronized(pools) { pools.getOrPut(engine) { Pool() } }
        val ln = pool.lanes[lane.coerceIn(0, LANES - 1)]
        // (quantised where it keeps the precision: positions to under half a millimetre, texel edges exact)
        val qt = if (quantize) TownVertex.quantize(vb.duplicate().order(ByteOrder.nativeOrder()), nv, texW, texH) else null
        if (qt != null) quantized.addAndGet(nv.toLong()) else unquantized.addAndGet(nv.toLong())
        val stride = if (qt != null) TownVertex.QSTRIDE else TownVertex.STRIDE
        val (vp, vo) = place(engine, if (qt != null) ln.qPages else ln.vPages, nv, PAGE_VERTICES, if (qt != null) 2 else 1, lane)
        // (an index range starts on an even index: Filament updates buffers at offsets of whole 4 bytes)
        val il = (ni + 1) and 1.inv()
        val (ip, io) = place(engine, ln.iPages, il, PAGE_INDICES, 0, lane)
        // the vertices at their place in the page
        val vsrc = (qt?.vb ?: vb).duplicate().order(vb.order()); vsrc.position(0); vsrc.limit(nv * stride)
        vp.vb!!.setBufferAt(engine, 0, vsrc, vo * stride, nv * stride)
        // the indices counted from there (a copy: the packed batch may be uploaded again, by a layer made anew)
        val src = ib.duplicate().order(ByteOrder.nativeOrder()); src.position(0)
        val dst = ByteBuffer.allocateDirect(il * 2).order(ByteOrder.nativeOrder())
        for (k in 0 until ni) dst.putShort(((src.getShort(k * 2).toInt() and 0xffff) + vo).toShort())
        if (il > ni) dst.putShort(vo.toShort())
        dst.flip()
        ip.ib!!.setBuffer(engine, dst, io * 2, il * 2)
        val s = GeomSlice(vp.vb, ip.ib, io, ni, vp, vo, nv, ip, io, il, qt)
        GpuAccount.addBuffer(s, nv.toLong() * stride + ni * 2L, gpu = false)
        return s
    }

    /** [kind]: 0 indices, 1 float vertices, 2 quantised vertices. */
    private fun place(engine: Engine, pages: ArrayList<Page>, n: Int, cap: Int, kind: Int, lane: Int): Pair<Page, Int> {
        for (p in pages) { if (p.capacity - p.used < n) continue; val at = p.take(n); if (at >= 0) return p to at }
        val p = when (kind) {
            1 -> Page(TownVertex.buffer(engine, cap, account = false), null, cap, lane, TownVertex.STRIDE)
            2 -> Page(TownVertex.quantBuffer(engine, cap), null, cap, lane, TownVertex.QSTRIDE)
            else -> Page(null, IndexBuffer.Builder().indexCount(cap).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine), cap, lane)
        }
        pages += p
        return p to p.take(n)
    }

    internal fun giveBack(engine: Engine, s: GeomSlice) {
        val pool = synchronized(pools) { pools[engine] } ?: return
        s.vPage?.let { if (!it.destroyed) { it.give(s.vOff, s.vLen); pool.lanes[it.lane].let { l -> drop(engine, if (it.stride == TownVertex.QSTRIDE) l.qPages else l.vPages, it) } } }
        s.iPage?.let { if (!it.destroyed) { it.give(s.iOff, s.iLen); drop(engine, pool.lanes[it.lane].iPages, it) } }
    }

    /** Destroys [p] once it is empty, unless it is the only empty page left (kept for what comes next). */
    private fun drop(engine: Engine, pages: ArrayList<Page>, p: Page) {
        if (p.used > 0 || pages.count { it.used == 0 } < 2) return
        pages.remove(p); destroy(engine, p)
    }

    private fun destroy(engine: Engine, p: Page) {
        p.destroyed = true
        p.vb?.let { engine.destroyVertexBuffer(it) }; p.ib?.let { engine.destroyIndexBuffer(it) }
    }

    /** Destroys [engine]'s pages (before the engine itself goes: whatever still points into them is gone too). */
    fun release(engine: Engine) {
        val pool = synchronized(pools) { pools.remove(engine) } ?: return
        for (p in pool.vPages + pool.iPages) destroy(engine, p)
    }

    /** Each page's bytes (over every engine). */
    fun pageSizes(): List<Long> = synchronized(pools) {
        pools.values.flatMap { pool -> pool.vPages.map { it.capacity.toLong() * it.stride } + pool.iPages.map { it.capacity * 2L } }
    }

    /** The pages now, over every engine: buffer objects, bytes they hold, bytes of them in use. */
    class Stats(val pages: Int, val bytes: Long, val used: Long)
    fun stats(): Stats = synchronized(pools) {
        var n = 0; var b = 0L; var u = 0L
        for (pool in pools.values) {
            for (p in pool.vPages) { n++; b += p.capacity.toLong() * p.stride; u += p.used.toLong() * p.stride }
            for (p in pool.iPages) { n++; b += p.capacity * 2L; u += p.used * 2L }
        }
        Stats(n, b, u)
    }
}
