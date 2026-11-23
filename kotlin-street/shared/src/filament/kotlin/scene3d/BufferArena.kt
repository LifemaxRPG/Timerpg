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
) {
    @Volatile var freed = false; private set

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

    /** One buffer object, its free ranges (start -> length, in vertices or indices) kept merged. */
    internal class Page(val vb: VertexBuffer?, val ib: IndexBuffer?, val capacity: Int) {
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

    private class Pool { val vPages = ArrayList<Page>(); val iPages = ArrayList<Page>() }
    private val pools = java.util.IdentityHashMap<Engine, Pool>()

    /**
     * Puts a packed batch on the GPU: [vb] ([nv] vertices in [TownVertex]'s layout) and [ib] ([ni] indices, 16-bit when
     * [short], counted from 0), into the pages when it is small enough, else into buffers of its own.
     */
    internal fun upload(engine: Engine, vb: ByteBuffer, nv: Int, ib: ByteBuffer, ni: Int, short: Boolean): GeomSlice {
        if (!enabled || !short || nv > MAX_PACKED_VERTICES || nv <= 0 || ni <= 0) {
            val v = TownVertex.buffer(engine, kotlin.math.max(1, nv)); v.setBufferAt(engine, 0, vb)
            val i = TownVertex.indices(engine, kotlin.math.max(1, ni), short); i.setBuffer(engine, ib)
            return GeomSlice(v, i, 0, ni, null, 0, 0, null, 0, 0)
        }
        val pool = synchronized(pools) { pools.getOrPut(engine) { Pool() } }
        val (vp, vo) = place(engine, pool.vPages, nv, PAGE_VERTICES, true)
        // (an index range starts on an even index: Filament updates buffers at offsets of whole 4 bytes)
        val il = (ni + 1) and 1.inv()
        val (ip, io) = place(engine, pool.iPages, il, PAGE_INDICES, false)
        // the vertices at their place in the page
        val vsrc = vb.duplicate().order(vb.order()); vsrc.position(0); vsrc.limit(nv * TownVertex.STRIDE)
        vp.vb!!.setBufferAt(engine, 0, vsrc, vo * TownVertex.STRIDE, nv * TownVertex.STRIDE)
        // the indices counted from there (a copy: the packed batch may be uploaded again, by a layer made anew)
        val src = ib.duplicate().order(ByteOrder.nativeOrder()); src.position(0)
        val dst = ByteBuffer.allocateDirect(il * 2).order(ByteOrder.nativeOrder())
        for (k in 0 until ni) dst.putShort(((src.getShort(k * 2).toInt() and 0xffff) + vo).toShort())
        if (il > ni) dst.putShort(vo.toShort())
        dst.flip()
        ip.ib!!.setBuffer(engine, dst, io * 2, il * 2)
        val s = GeomSlice(vp.vb, ip.ib, io, ni, vp, vo, nv, ip, io, il)
        GpuAccount.addBuffer(s, nv.toLong() * TownVertex.STRIDE + ni * 2L, gpu = false)
        return s
    }

    private fun place(engine: Engine, pages: ArrayList<Page>, n: Int, cap: Int, vertex: Boolean): Pair<Page, Int> {
        for (p in pages) { if (p.capacity - p.used < n) continue; val at = p.take(n); if (at >= 0) return p to at }
        val p = if (vertex) Page(TownVertex.buffer(engine, cap, account = false), null, cap)
        else Page(null, IndexBuffer.Builder().indexCount(cap).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine), cap)
        pages += p
        return p to p.take(n)
    }

    internal fun giveBack(engine: Engine, s: GeomSlice) {
        val pool = synchronized(pools) { pools[engine] } ?: return
        s.vPage?.let { if (!it.destroyed) { it.give(s.vOff, s.vLen); drop(engine, pool.vPages, it) } }
        s.iPage?.let { if (!it.destroyed) { it.give(s.iOff, s.iLen); drop(engine, pool.iPages, it) } }
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
        pools.values.flatMap { pool -> pool.vPages.map { it.capacity.toLong() * TownVertex.STRIDE } + pool.iPages.map { it.capacity * 2L } }
    }

    /** The pages now, over every engine: buffer objects, bytes they hold, bytes of them in use. */
    class Stats(val pages: Int, val bytes: Long, val used: Long)
    fun stats(): Stats = synchronized(pools) {
        var n = 0; var b = 0L; var u = 0L
        for (pool in pools.values) {
            for (p in pool.vPages) { n++; b += p.capacity.toLong() * TownVertex.STRIDE; u += p.used.toLong() * TownVertex.STRIDE }
            for (p in pool.iPages) { n++; b += p.capacity * 2L; u += p.used * 2L }
        }
        Stats(n, b, u)
    }
}
