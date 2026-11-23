package com.lifemax.rpg.feature.habits.presentation.scene3d

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ASTC 4x4 (LDR, 8 bits a texel: a quarter of RGBA8) for the town's pixel art, encoded on the CPU as the pictures are
 * packed (any thread), so the phone's GPU (Adreno reads ASTC natively) holds a quarter of the bytes (TOWN.md, "GPU memory").
 *
 * Every block is one partition of RGBA endpoints at full 8-bit precision (colour endpoint mode 12, quantised to 256
 * levels: the endpoints are the art's own colours) and sixteen 2-bit weights. A block of one or two colours (most of the
 * art: flat colours, hard edges, art pixels of 2 x 2 texels) is therefore stored exactly: its texels decode to the very
 * same RGBA (weights 0 and 64 give an endpoint exactly, in sRGB and linear alike). A block of more colours gets the line
 * through its colours that fits best with four steps along it: near, not exact ([Stats] counts both).
 *
 * An opaque block that one line fits badly (three or more colours off one line: a flower bed, a busy shop sign) may be
 * written as two partitions instead, each its own line in 6-bit colours ([twoPartitions]): whichever is nearer.
 *
 * The mip levels are made here too (a 2 x 2 box, in linear light for an sRGB picture, as the GPU makes them) and encoded
 * the same way.
 */
object Astc {
    /** Block mode 66: a 4 x 4 weight grid, weights in 2 bits (QUANT_4), one plane. */
    private const val BLOCK_MODE = 66
    private const val CEM_RGBA = 12

    /** Blocks written: those stored exactly (one or two colours) and those approximated, and the latter's squared error. */
    class Stats {
        var exact = 0L; var near = 0L; var sse = 0L
        @Synchronized fun add(o: Stats) { exact += o.exact; near += o.near; sse += o.sse }
        /** "N blocks, x% exact; the rest's RMS error y (8-bit levels a channel)". */
        fun line(): String = "%d blocks, %.1f%% exact; the rest's RMS error %.1f levels a channel".format(java.util.Locale.US,
            exact + near, 100.0 * exact / (exact + near).coerceAtLeast(1), Math.sqrt(sse / (near * 64.0).coerceAtLeast(1.0)))
    }
    /** Level 0 and the mip levels of everything encoded since the start (the reports'). */
    val level0 = Stats()
    val mips = Stats()
    /** Each encoded picture's level 0: its texels and its share of exact blocks (the reports' histogram). */
    private val pictures = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Double>>()

    /** The texels of the pictures encoded, by their share of exact blocks: "100%: n MTexels, 99-100%: ...". */
    fun histogram(): String {
        val bins = doubleArrayOf(1.0, .99, .95, .9, .8, 0.0)
        val names = listOf("100%", "99-100%", "95-99%", "90-95%", "80-90%", "<80%")
        val t = LongArray(bins.size); val n = IntArray(bins.size)
        for ((tx, f) in pictures) { val k = bins.indexOfFirst { f >= it }; t[k] += tx; n[k]++ }
        return names.indices.joinToString(", ") { names[it] + ": ${n[it]} pictures " + "%.1fM texels".format(java.util.Locale.US, t[it] / 1e6) }
    }

    /** Whether every 4 x 4 block of [px] holds one or two colours: [encodeChain] then stores it exactly (a mask's test). */
    fun exact(px: IntArray, w: Int, h: Int): Boolean {
        for (by in 0 until (h + 3) / 4) for (bx in 0 until (w + 3) / 4) {
            val c0 = px[by * 4 * w + bx * 4]; var c1 = c0
            for (j in 0 until 16) {
                val c = px[minOf(by * 4 + (j shr 2), h - 1) * w + minOf(bx * 4 + (j and 3), w - 1)]
                if (c == c0 || c == c1) continue
                if (c1 == c0) c1 = c else return false
            }
        }
        return true
    }

    /** The bytes of one level of [w] x [h] in ASTC 4x4. */
    fun levelBytes(w: Int, h: Int): Int = ((w + 3) / 4) * ((h + 3) / 4) * 16

    /** All levels' bytes, level 0 down to 1 x 1 ([levels] of them). */
    fun chainBytes(w: Int, h: Int, levels: Int): Int { var s = 0; for (l in 0 until levels) s += levelBytes(maxOf(1, w shr l), maxOf(1, h shr l)); return s }

    /**
     * [px] (ARGB, rows top first) encoded with [levels] mip levels into one buffer (level 0 first, each level's rows bottom
     * first as Filament's textures run); [srgb]: the mips are averaged in linear light.
     */
    fun encodeChain(px: IntArray, w: Int, h: Int, levels: Int, srgb: Boolean, stats: Stats? = null, level0Only: Boolean = false): ByteBuffer {
        val t0 = System.nanoTime()
        encoded.incrementAndGet()
        val out = TownMemory.direct(chainBytes(w, h, levels)).order(ByteOrder.LITTLE_ENDIAN)
        var cur = px; var cw = w; var ch = h
        for (l in 0 until levels) {
            val st = Stats()
            encodeLevel(cur, cw, ch, out, st)
            (if (l == 0) level0 else mips).add(st)
            if (l == 0) pictures += w.toLong() * h to st.exact.toDouble() / (st.exact + st.near).coerceAtLeast(1)
            if (l == 0 || !level0Only) stats?.add(st)
            if (l + 1 < levels) {
                val nw = maxOf(1, cw shr 1); val nh = maxOf(1, ch shr 1)
                cur = halve(cur, cw, ch, nw, nh, srgb); cw = nw; ch = nh
            }
        }
        out.flip()
        nanos.addAndGet(System.nanoTime() - t0)
        return out
    }

    /** Pictures read back from [TownQuality.astcCache], and pictures encoded (the reports'). */
    val cacheHits: Int get() = AstcCache.hits.get()
    val cacheMisses: Int get() = encoded.get()
    internal val encoded = java.util.concurrent.atomic.AtomicInteger()

    /** The time spent encoding since the start, over every thread (the reports'). */
    val nanos = java.util.concurrent.atomic.AtomicLong()

    /**
     * One level into [out] at its position: blocks bottom row first (Filament's v = 0 at the bottom), left to right. A big
     * level is split into bands of block rows encoded on the common pool at once (the ground's and the atlases' pictures
     * are made one after another on one worker, on the way to the first view).
     */
    fun encodeLevel(px: IntArray, w: Int, h: Int, out: ByteBuffer, stats: Stats? = null) {
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val st = stats ?: Stats()
        val base = out.position()
        val bands = if (bw * bh < 8192) 1 else minOf(bh, BANDS)
        if (bands == 1) { encodeRows(px, w, h, 0, bh, out, st); return }
        val parts = Array(bands) { Stats() }
        val tasks = (0 until bands).map { k ->
            java.util.concurrent.Callable {
                val o = out.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val r0 = bh * k / bands; val r1 = bh * (k + 1) / bands
                o.position(base + r0 * bw * 16)
                encodeRows(px, w, h, r0, r1, o, parts[k])
            }
        }
        for (f in java.util.concurrent.ForkJoinPool.commonPool().invokeAll(tasks)) f.get()
        for (p in parts) st.add(p)
        out.position(base + bw * bh * 16)
    }

    /** How many bands a big level is split into. */
    private val BANDS = maxOf(2, Runtime.getRuntime().availableProcessors())

    /** Block rows [r0] until [r1] of a level into [out] from its position. */
    private fun encodeRows(px: IntArray, w: Int, h: Int, r0: Int, r1: Int, out: ByteBuffer, st: Stats) {
        val e = scratch.get()
        val bw = (w + 3) / 4
        val blk = e.blk
        // (a run of blocks of one colour, the commonest kind, written once and copied)
        var lastFlat = 0; var haveFlat = false; var flatLo = 0L; var flatHi = 0L
        // (the picture flipped: the texture's row 0 is the picture's last row)
        for (by in r0 until r1) for (bx in 0 until bw) {
            var flat = true
            for (j in 0 until 16) {
                val x = minOf(bx * 4 + (j and 3), w - 1)
                val ty = minOf(by * 4 + (j shr 2), h - 1)
                val c = px[(h - 1 - ty) * w + x]
                blk[j] = c
                if (c != blk[0]) flat = false
            }
            if (flat && haveFlat && blk[0] == lastFlat) { out.putLong(flatLo).putLong(flatHi); st.exact++; continue }
            encodeBlock(blk, e, st)
            out.putLong(e.lo).putLong(e.hi)
            if (flat) { haveFlat = true; lastFlat = blk[0]; flatLo = e.lo; flatHi = e.hi }
        }
    }

    /** A thread's scratch for encoding (no allocation a block). */
    private class Enc {
        val blk = IntArray(16); val wts = IntArray(16); val pal = IntArray(4)
        val cs = IntArray(16); val ns = IntArray(16); var n = 0
        var lo = 0L; var hi = 0L
        val q2 = IntArray(16)
        val ends = IntArray(4); val bestEnds = IntArray(4); val bestW = IntArray(16)
        val idx = IntArray(16); val dd = LongArray(256); val cw = IntArray(16); val ce = LongArray(16)
    }
    private val scratch = ThreadLocal.withInitial { Enc() }

    private fun ch(c: Int, k: Int) = (c ushr (8 * k)) and 255 // k: 0 b, 1 g, 2 r, 3 a

    /** One block of 16 ARGB texels (row by row, the texture's bottom row first) into [b] (16 bytes, little-endian). */
    fun encodeBlock(t: IntArray, b: ByteArray, stats: Stats? = null) {
        val e = scratch.get()
        encodeBlock(t, e, stats ?: Stats())
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(e.lo).putLong(e.hi)
    }

    private fun encodeBlock(t: IntArray, e: Enc, stats: Stats) {
        var c0 = t[0]; var c1 = t[0]; var two = true
        for (i in 1 until 16) {
            val c = t[i]
            if (c == c0) continue
            if (c1 == c0) { c1 = c; continue }
            if (c != c1) { two = false; break }
        }
        val wts = e.wts
        if (two) {
            for (i in 0 until 16) wts[i] = if (t[i] == c0) 0 else 3
            stats.exact++
        } else {
            fitLine(t, e)
            c0 = e.lo.toInt(); c1 = e.hi.toInt()
            var err = 0L
            val pal = e.pal
            for (q in 0 until 4) pal[q] = lerp(c0, c1, WEIGHTS[q])
            for (i in 0 until 16) {
                var best = 0; var bestE = Long.MAX_VALUE
                for (q in 0 until 4) { val d = dist(t[i], pal[q]); if (d < bestE) { bestE = d; best = q } }
                wts[i] = best; err += bestE
            }
            // an opaque block: two partitions, each its own line, may fit better than one ([twoPartitions])
            // (not where one line is already within about 3 levels a channel: 6-bit colours would gain little)
            if (err > 16 * 4 * 9 && opaque(t)) {
                val e2 = twoPartitions(t, e, err)
                if (e2 < err) { stats.near++; stats.sse += e2; return }
            }
            stats.near++; stats.sse += err
        }
        // (endpoint mode 12 swaps its endpoints and contracts blue unless the second's r + g + b is at least the first's)
        if (ch(c1, 2) + ch(c1, 1) + ch(c1, 0) < ch(c0, 2) + ch(c0, 1) + ch(c0, 0)) {
            val s = c0; c0 = c1; c1 = s
            for (i in 0 until 16) wts[i] = 3 - wts[i]
        }
        write(e, c0, c1, wts)
    }

    private fun opaque(t: IntArray): Boolean { for (c in t) if (c ushr 24 != 255) return false; return true }

    // ------------------------------------------------------------------ two partitions (opaque blocks)
    //
    // Block mode 81: a 4 x 4 grid of weights in trits (QUANT_3: 0, 32, 64; 26 bits), two partitions (the spec's hash, its
    // index in 10 bits), both of colour endpoint mode 8 (RGB direct, alpha 255), their 12 values in 6 bits (QUANT_64,
    // 72 bits): every texel within two levels of a colour of its partition's line.

    private const val BLOCK_MODE_2P = 81
    private const val CEM_RGB = 8
    private val W3 = intArrayOf(0, 32, 64)

    /** The ASTC partition hash (the spec's), for 2 partitions of a small block: which partition texel ([x], [y]) is in. */
    private fun partitionOf(seed0: Int, x0: Int, y0: Int): Int {
        val x = x0 shl 1; val y = y0 shl 1
        val seed = seed0 + 1024
        var r = seed
        r = r xor (r ushr 15); r *= 0xEEDE0891.toInt(); r = r xor (r ushr 5); r += r shl 16
        r = r xor (r ushr 7); r = r xor (r ushr 3); r = r xor (r shl 6); r = r xor (r ushr 17)
        fun sq(sh: Int) = ((r ushr sh) and 0xF).let { it * it }
        var s1 = sq(0); var s2 = sq(4); var s3 = sq(8); var s4 = sq(12)
        var s11 = sq(26); var s12 = (((r ushr 30) or (r shl 2)) and 0xF).let { it * it }
        val sh1: Int; val sh2: Int
        if (seed and 1 != 0) { sh1 = if (seed and 2 != 0) 4 else 5; sh2 = 5 } else { sh1 = 5; sh2 = if (seed and 2 != 0) 4 else 5 }
        val sh3 = if (seed and 0x10 != 0) sh1 else sh2
        s1 = s1 shr sh1; s2 = s2 shr sh2; s3 = s3 shr sh1; s4 = s4 shr sh2; s11 = s11 shr sh3; s12 = s12 shr sh3
        val a = (s1 * x + s2 * y + (r ushr 14)) and 0x3F
        val b = (s3 * x + s4 * y + (r ushr 10)) and 0x3F
        return if (a >= b) 0 else 1
    }

    /** Each partition index's texels in partition 1, as a 16-bit mask (texel j = y * 4 + x). */
    private val partMasks = IntArray(1024) { p -> var m = 0; for (j in 0 until 16) if (partitionOf(p, j and 3, j shr 2) == 1) m = m or (1 shl j); m }
    /**
     * For every split of a block's 16 texels (a 16-bit mask), the two partition indices whose masks are nearest it (fewest
     * texels in the other partition, either way round), packed as first | second shl 16: worked out once (some 50 ms).
     */
    private val nearestParts: IntArray by lazy {
        val seen = HashSet<Int>(); val l = ArrayList<Int>()
        for (p in 0 until 1024) { val m = partMasks[p]; if (m != 0 && m != 0xFFFF && seen.add(m)) l += p }
        val ps = l.toIntArray(); val ms = IntArray(ps.size) { partMasks[ps[it]] }
        IntArray(65536) { want ->
            var b0 = 0; var d0 = 99; var b1 = 0; var d1 = 99
            for (k in ps.indices) {
                val d = minOf(Integer.bitCount(ms[k] xor want), 16 - Integer.bitCount(ms[k] xor want))
                if (d < d0) { b1 = b0; d1 = d0; b0 = ps[k]; d0 = d } else if (d < d1) { b1 = ps[k]; d1 = d }
            }
            b0 or (b1 shl 16)
        }
    }

    /** The 6-bit endpoint value nearest each 8-bit one, and the 8 bits each decodes to (bit replication). */
    private val unq6 = IntArray(64) { (it shl 2) or (it shr 4) }
    private val q6 = IntArray(256) { v -> (0 until 64).minByOrNull { kotlin.math.abs(unq6[it] - v) }!! }
    private fun quant6(c: Int): Int = (q6[ch(c, 2)] shl 16) or (q6[ch(c, 1)] shl 8) or q6[ch(c, 0)]
    private fun unquant6(q: Int): Int = (255 shl 24) or (unq6[(q shr 16) and 63] shl 16) or (unq6[(q shr 8) and 63] shl 8) or unq6[q and 63]

    /**
     * [t] (opaque) as two partitions, written into [e] if its squared error beats [cap] (the one-line encoding's): the
     * texels split by their nearer of the block's two farthest colours, the partition index whose mask is nearest that split
     * (a few tried), each partition's line its farthest pair quantised. Returns the error (or [cap] or more: not written).
     */
    private fun twoPartitions(t: IntArray, e: Enc, cap: Long): Long {
        // (e.cs holds the block's distinct colours from the line fit: each texel's colour by its index there, the pairs' distances)
        val n = e.n; val cs = e.cs; val idx = e.idx; val dd = e.dd
        for (i in 0 until 16) { val c = t[i]; var k = 0; while (cs[k] != c) k++; idx[i] = k }
        var fa = 0; var fb = 1; var fd = -1L
        for (i in 0 until n) for (j in i + 1 until n) { val d = dist(cs[i], cs[j]); dd[i * 16 + j] = d; dd[j * 16 + i] = d; if (d > fd) { fd = d; fa = i; fb = j } }
        var want = 0
        for (j in 0 until 16) { val k = idx[j]; if (k != fa && (k == fb || dd[k * 16 + fb] < dd[k * 16 + fa])) want = want or (1 shl j) }
        // the two nearest masks (either way round: the partitions' order does not matter)
        val near = nearestParts[want]
        var bestErr = cap; var bestP = -1
        val ends = e.ends; val bestEnds = e.bestEnds; val bestW = e.bestW; val cw = e.cw
        for (pk in 0..1) {
            val p = if (pk == 0) near and 0xFFFF else near ushr 16
            if (pk == 1 && p == (near and 0xFFFF)) break
            val m = partMasks[p]
            var err = 0L
            for (s in 0..1) {
                // the partition's distinct colours (a bit each) and their farthest pair, quantised
                var cm = 0; var cnt = 0
                for (i in 0 until 16) if (((m shr i) and 1) == s) cm = cm or (1 shl idx[i])
                var a = Integer.numberOfTrailingZeros(cm); var b = a; var d0 = -1L
                var x = cm
                while (x != 0) {
                    val i = Integer.numberOfTrailingZeros(x); x = x and (x - 1)
                    var y = x
                    while (y != 0) { val j = Integer.numberOfTrailingZeros(y); y = y and (y - 1); val d = dd[i * 16 + j]; if (d > d0) { d0 = d; a = i; b = j } }
                    cnt++
                }
                var qa = quant6(cs[a]); var qb = quant6(cs[b])
                var ua = unquant6(qa); var ub = unquant6(qb)
                // (mode 8 contracts blue and swaps unless the second end's sum is at least the first's)
                if (ch(ub, 2) + ch(ub, 1) + ch(ub, 0) < ch(ua, 2) + ch(ua, 1) + ch(ua, 0)) { val q = qa; qa = qb; qb = q; val u = ua; ua = ub; ub = u }
                ends[s * 2] = qa; ends[s * 2 + 1] = qb
                val p0 = ua; val p1 = lerp(ua, ub, 32); val p2 = ub
                // each distinct colour's weight and error, once
                x = cm
                while (x != 0) {
                    val k = Integer.numberOfTrailingZeros(x); x = x and (x - 1)
                    val c = cs[k]
                    val d0x = dist(c, p0); val d1 = dist(c, p1); val d2 = dist(c, p2)
                    cw[k] = if (d0x <= d1 && d0x <= d2) 0 else if (d1 <= d2) 1 else 2
                    e.ce[k] = minOf(d0x, minOf(d1, d2))
                }
                for (i in 0 until 16) if (((m shr i) and 1) == s) { val k = idx[i]; e.q2[i] = cw[k]; err += e.ce[k] }
                if (err >= bestErr) break
            }
            if (err < bestErr) { bestErr = err; bestP = p; ends.copyInto(bestEnds); e.q2.copyInto(bestW) }
        }
        if (bestP < 0) return cap
        write2(e, bestP, bestEnds, bestW)
        return bestErr
    }

    /** Five trits as the 8 bits the spec packs them in (its decoding, inverted once). */
    private val tritPack: IntArray = run {
        val tab = IntArray(243) { -1 }
        for (tv in 0 until 256) {
            fun bit(i: Int) = (tv shr i) and 1
            val c: Int; val t4: Int; val t3: Int
            if ((tv shr 2) and 7 == 7) { c = (((tv shr 5) and 7) shl 2) or (tv and 3); t4 = 2; t3 = 2 }
            else { c = tv and 0x1F; if ((tv shr 5) and 3 == 3) { t4 = 2; t3 = bit(7) } else { t4 = bit(7); t3 = (tv shr 5) and 3 } }
            val t2: Int; val t1: Int; val t0: Int
            if (c and 3 == 3) { t2 = 2; t1 = (c shr 4) and 1; t0 = (((c shr 3) and 1) shl 1) or (((c shr 2) and 1) and ((c shr 3) and 1).inv() and 1) }
            else if ((c shr 2) and 3 == 3) { t2 = 2; t1 = 2; t0 = c and 3 }
            else { t2 = (c shr 4) and 1; t1 = (c shr 2) and 3; t0 = (((c shr 1) and 1) shl 1) or ((c and 1) and ((c shr 1) and 1).inv() and 1) }
            val k = t0 + 3 * (t1 + 3 * (t2 + 3 * (t3 + 3 * t4)))
            if (t0 < 3 && t1 < 3 && t3 < 3 && tab[k] < 0) tab[k] = tv
        }
        tab
    }

    /** The two-partition block's bits into [e]. */
    private fun write2(e: Enc, p: Int, ends: IntArray, w: IntArray) {
        var lo = BLOCK_MODE_2P.toLong() or (1L shl 11) or (p.toLong() shl 13) or (CEM_RGB.toLong() shl 25)
        var hi = 0L
        // the 12 endpoint values (r0 r1 g0 g1 b0 b1 of each partition), 6 bits each from bit 29
        var at = 29
        for (s in 0..1) for (k in intArrayOf(2, 1, 0)) for (end in 0..1) {
            val v = ((ends[s * 2 + end] shr (8 * k)) and 63).toLong()
            if (at >= 64) hi = hi or (v shl (at - 64))
            else { lo = lo or (v shl at); if (at + 6 > 64) hi = hi or (v ushr (64 - at)) }
            at += 6
        }
        // the 16 weights' trits, five to 8 bits (the last one alone in 2), from bit 127 down
        var n = 0
        fun put(bits: Int, count: Int) { for (k in 0 until count) { if ((bits shr k) and 1 == 1) hi = hi or (1L shl (63 - n)); n++ } }
        for (g in 0 until 3) {
            val k = w[g * 5] + 3 * (w[g * 5 + 1] + 3 * (w[g * 5 + 2] + 3 * (w[g * 5 + 3] + 3 * w[g * 5 + 4])))
            put(tritPack[k], 8)
        }
        put(w[15], 2)
        e.lo = lo; e.hi = hi
    }

    /** The unquantised 2-bit weights (0..64). */
    private val WEIGHTS = intArrayOf(0, 21, 43, 64)

    /** Texel [c0] to [c1] at weight [w] (0..64), as the decoder makes it (8-bit, ARGB). */
    private fun lerp(c0: Int, c1: Int, w: Int): Int {
        var r = 0
        for (k in 0 until 4) {
            val a = ch(c0, k); val b = ch(c1, k)
            // (16-bit expansion, interpolation and the top 8 bits, as a UNORM8 decode does)
            val e0 = (a shl 8) or a; val e1 = (b shl 8) or b
            val v = (e0 * (64 - w) + e1 * w + 32) shr 6
            r = r or ((v shr 8) shl (8 * k))
        }
        return r
    }

    private fun dist(a: Int, b: Int): Long {
        var s = 0
        for (k in 0 until 4) { val d = ch(a, k) - ch(b, k); s += d * d }
        return s.toLong()
    }

    /**
     * The two endpoints of the line through [t]'s colours that fits it best with four steps, into [e]'s lo and hi: the
     * candidates are the farthest pair of its distinct colours and every pair of its four commonest, each scored over the
     * distinct colours (weighted by how many texels wear them).
     */
    private fun fitLine(t: IntArray, e: Enc) {
        val cs = e.cs; val ns = e.ns; var n = 0
        for (c in t) { var k = 0; while (k < n && cs[k] != c) k++; if (k == n) { cs[n] = c; ns[n] = 1; n++ } else ns[k]++ }
        // (by count, commonest first: an insertion sort of at most 16)
        for (i in 1 until n) { val c = cs[i]; val m = ns[i]; var j = i - 1; while (j >= 0 && ns[j] < m) { cs[j + 1] = cs[j]; ns[j + 1] = ns[j]; j-- }; cs[j + 1] = c; ns[j + 1] = m }
        e.n = n
        var fa = 0; var fb = 1
        if (n <= 6) {
            var fd = -1L
            for (i in 0 until n) for (j in i + 1 until n) { val d = dist(cs[i], cs[j]); if (d > fd) { fd = d; fa = i; fb = j } }
        } else {
            // (many colours, a mip level's or a dithered patch's: the ends along the channel that spreads most)
            var bk = 0; var br = -1
            for (k in 0 until 4) {
                var lo = 255; var hi = 0
                for (i in 0 until n) { val v = ch(cs[i], k); if (v < lo) lo = v; if (v > hi) hi = v }
                if (hi - lo > br) { br = hi - lo; bk = k }
            }
            var lo = 256; var hi = -1
            for (i in 0 until n) { val v = ch(cs[i], bk); if (v < lo) { lo = v; fa = i }; if (v > hi) { hi = v; fb = i } }
        }
        var ba = cs[fa]; var bb = cs[fb]
        var be = score(e, ba, bb, Long.MAX_VALUE)
        val m = minOf(4, n)
        for (i in 0 until m) for (j in i + 1 until m) {
            if (i == fa && j == fb) continue
            val a = cs[i]; val b = cs[j]
            val s = score(e, a, b, be)
            if (s < be) { be = s; ba = a; bb = b }
        }
        e.lo = ba.toLong(); e.hi = bb.toLong()
    }

    private fun score(e: Enc, a: Int, b: Int, cap: Long): Long {
        val pal = e.pal
        for (q in 0 until 4) pal[q] = lerp(a, b, WEIGHTS[q])
        var s = 0L
        for (k in 0 until e.n) {
            var m = Long.MAX_VALUE
            for (q in 0 until 4) { val d = dist(e.cs[k], pal[q]); if (d < m) m = d }
            s += m * e.ns[k]
            if (s >= cap) return s
        }
        return s
    }

    /**
     * The block's 128 bits into [e]'s lo and hi: mode, one partition, endpoint mode 12, eight 8-bit endpoint values from
     * bit 17 (r0 r1 g0 g1 b0 b1 a0 a1), the weights' stream from bit 127 down (its bit n at block bit 127 - n).
     */
    private fun write(e: Enc, c0: Int, c1: Int, wts: IntArray) {
        var v = 0L
        var sh = 0
        for (k in intArrayOf2) { v = v or (ch(c0, k).toLong() shl sh) or (ch(c1, k).toLong() shl (sh + 8)); sh += 16 }
        var lo = BLOCK_MODE.toLong() or (CEM_RGBA.toLong() shl 13) or (v shl 17)
        var hi = v ushr 47
        for (i in 0 until 16) {
            val w = wts[i]
            if (w and 1 != 0) hi = hi or (1L shl (63 - 2 * i))
            if (w and 2 != 0) hi = hi or (1L shl (62 - 2 * i))
        }
        e.lo = lo; e.hi = hi
    }
    private val intArrayOf2 = intArrayOf(2, 1, 0, 3)

    private fun bits(b: ByteArray, at: Int, n: Int, v: Int) {
        for (k in 0 until n) if ((v shr k) and 1 == 1) { val p = at + k; b[p shr 3] = (b[p shr 3].toInt() or (1 shl (p and 7))).toByte() }
    }

    private fun get(b: ByteArray, at: Int, n: Int): Int {
        var v = 0
        for (k in 0 until n) { val p = at + k; if ((b[p shr 3].toInt() shr (p and 7)) and 1 == 1) v = v or (1 shl k) }
        return v
    }

    /** A one-partition block written by [encodeBlock] back to its 16 ARGB texels (false for any other kind: a test's check). */
    fun decodeBlock(b: ByteArray, out: IntArray): Boolean {
        if (get(b, 0, 11) != BLOCK_MODE || get(b, 11, 2) != 0 || get(b, 13, 4) != CEM_RGBA) return false
        val v = IntArray(8) { get(b, 17 + it * 8, 8) }
        // (r g b a of each endpoint; the encoder never needs blue contraction)
        val c0 = (v[6] shl 24) or (v[0] shl 16) or (v[2] shl 8) or v[4]
        val c1 = (v[7] shl 24) or (v[1] shl 16) or (v[3] shl 8) or v[5]
        if (v[1] + v[3] + v[5] < v[0] + v[2] + v[4]) return false
        for (i in 0 until 16) {
            var w = 0
            for (k in 0 until 2) { val n = 127 - (i * 2 + k); if ((b[n shr 3].toInt() shr (n and 7)) and 1 == 1) w = w or (1 shl k) }
            out[i] = lerp(c0, c1, WEIGHTS[w])
        }
        return true
    }

    // ------------------------------------------------------------------ mip levels

    private val toLin = FloatArray(256) { val c = it / 255f; if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat() }
    /** The linear value at which the sRGB byte rounds up to the next: (c + 0.5) / 255 in sRGB, made linear. */
    private val upAt = FloatArray(255) { val c = (it + .5f) / 255f; if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat() }
    /** Linear light [l] as the nearest sRGB byte (a binary search of [upAt]: the same as converting and rounding). */
    private fun toSrgbExact(l: Float): Int {
        var lo = 0; var hi = 255
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (l < upAt[mid]) hi = mid else lo = mid + 1 }
        return lo
    }
    /** [toSrgbExact] tabled over 16384 steps of linear light (a step off only right at a rounding edge). */
    private val srgbOf = IntArray(16385) { toSrgbExact(it / 16384f) }
    private fun toSrgb(l: Float): Int = srgbOf[(l * 16384f + .5f).toInt().coerceIn(0, 16384)]

    /** The next level: each texel the mean of the 2 x 2 under it (rgb in linear light for [srgb]), rows top first. */
    fun halve(px: IntArray, w: Int, h: Int, nw: Int, nh: Int, srgb: Boolean): IntArray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            // (four texels of one colour, most of the art: that colour)
            val x0 = minOf(x * 2, w - 1); val x1 = minOf(x * 2 + 1, w - 1); val y0 = minOf(y * 2, h - 1); val y1 = minOf(y * 2 + 1, h - 1)
            val c00 = px[y0 * w + x0]
            if (px[y0 * w + x1] == c00 && px[y1 * w + x0] == c00 && px[y1 * w + x1] == c00) { out[y * nw + x] = c00; continue }
            var r = 0f; var g = 0f; var bl = 0f; var a = 0; var n = 0
            for (dy in 0..1) for (dx in 0..1) {
                val sx = minOf(x * 2 + dx, w - 1); val sy = minOf(y * 2 + dy, h - 1)
                val c = px[sy * w + sx]
                if (srgb) { r += toLin[ch(c, 2)]; g += toLin[ch(c, 1)]; bl += toLin[ch(c, 0)] } else { r += ch(c, 2); g += ch(c, 1); bl += ch(c, 0) }
                a += ch(c, 3); n++
            }
            val ri: Int; val gi: Int; val bi: Int
            if (srgb) { ri = toSrgb(r / n); gi = toSrgb(g / n); bi = toSrgb(bl / n) }
            else { ri = (r / n + .5f).toInt(); gi = (g / n + .5f).toInt(); bi = (bl / n + .5f).toInt() }
            out[y * nw + x] = (((a + n / 2) / n) shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return out
    }
}
