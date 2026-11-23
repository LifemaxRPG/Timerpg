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
 * The mip levels are made here too (a 2 x 2 box, in linear light for an sRGB picture, as the GPU makes them) and encoded
 * the same way. [decode] reads back exactly what this writes (the desktop draws that where its GL has no ASTC).
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
    fun encodeChain(px: IntArray, w: Int, h: Int, levels: Int, srgb: Boolean, stats: Stats? = null): ByteBuffer {
        val out = TownMemory.direct(chainBytes(w, h, levels)).order(ByteOrder.nativeOrder())
        var cur = px; var cw = w; var ch = h
        for (l in 0 until levels) {
            val st = Stats()
            encodeLevel(cur, cw, ch, out, st)
            (if (l == 0) level0 else mips).add(st)
            stats?.add(st)
            if (l + 1 < levels) {
                val nw = maxOf(1, cw shr 1); val nh = maxOf(1, ch shr 1)
                cur = halve(cur, cw, ch, nw, nh, srgb); cw = nw; ch = nh
            }
        }
        out.flip()
        return out
    }

    /** One level into [out] at its position: blocks bottom row first (Filament's v = 0 at the bottom), left to right. */
    fun encodeLevel(px: IntArray, w: Int, h: Int, out: ByteBuffer, stats: Stats? = null) {
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val blk = IntArray(16)
        val bytes = ByteArray(16)
        // (the picture flipped: the texture's row 0 is the picture's last row)
        for (by in 0 until bh) for (bx in 0 until bw) {
            for (j in 0 until 16) {
                val x = minOf(bx * 4 + (j and 3), w - 1)
                val ty = minOf(by * 4 + (j shr 2), h - 1)
                blk[j] = px[(h - 1 - ty) * w + x]
            }
            encodeBlock(blk, bytes, stats)
            out.put(bytes)
        }
    }

    private fun ch(c: Int, k: Int) = (c ushr (8 * k)) and 255 // k: 0 b, 1 g, 2 r, 3 a

    /** One block of 16 ARGB texels (row by row, the texture's bottom row first) into [b] (16 bytes). */
    fun encodeBlock(t: IntArray, b: ByteArray, stats: Stats? = null) {
        var c0 = t[0]; var c1 = t[0]; var two = true
        for (i in 1 until 16) {
            val c = t[i]
            if (c == c0) continue
            if (c1 == c0) { c1 = c; continue }
            if (c != c1) { two = false; break }
        }
        val wts = IntArray(16)
        if (two) {
            for (i in 0 until 16) wts[i] = if (t[i] == c0) 0 else 3
            stats?.let { it.exact++ }
        } else {
            val (e0, e1) = fitLine(t)
            c0 = e0; c1 = e1
            var err = 0L
            val pal = IntArray(4) { lerp(c0, c1, WEIGHTS[it]) }
            for (i in 0 until 16) {
                var best = 0; var bestE = Long.MAX_VALUE
                for (q in 0 until 4) { val e = dist(t[i], pal[q]); if (e < bestE) { bestE = e; best = q } }
                wts[i] = best; err += bestE
            }
            stats?.let { it.near++; it.sse += err }
        }
        // (endpoint mode 12 swaps its endpoints and contracts blue unless the second's r + g + b is at least the first's)
        if (ch(c1, 2) + ch(c1, 1) + ch(c1, 0) < ch(c0, 2) + ch(c0, 1) + ch(c0, 0)) {
            val s = c0; c0 = c1; c1 = s
            for (i in 0 until 16) wts[i] = 3 - wts[i]
        }
        write(b, c0, c1, wts)
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
        var s = 0L
        for (k in 0 until 4) { val d = ch(a, k) - ch(b, k); s += d * d }
        return s
    }

    /**
     * The two endpoints of the line through [t]'s colours that fits it best with four steps: the candidates are the
     * farthest pair of its distinct colours and every pair of its four commonest, each scored over the distinct colours
     * (weighted by how many texels wear them).
     */
    private fun fitLine(t: IntArray): Pair<Int, Int> {
        val cs = IntArray(16); val ns = IntArray(16); var n = 0
        for (c in t) { var k = 0; while (k < n && cs[k] != c) k++; if (k == n) { cs[n] = c; ns[n] = 1; n++ } else ns[k]++ }
        var fa = 0; var fb = 1; var fd = -1L
        for (i in 0 until n) for (j in i + 1 until n) { val d = dist(cs[i], cs[j]); if (d > fd) { fd = d; fa = i; fb = j } }
        val pal = IntArray(4)
        fun score(a: Int, b: Int, cap: Long): Long {
            for (q in 0 until 4) pal[q] = lerp(a, b, WEIGHTS[q])
            var s = 0L
            for (k in 0 until n) {
                var m = Long.MAX_VALUE
                for (q in 0 until 4) { val e = dist(cs[k], pal[q]); if (e < m) m = e }
                s += m * ns[k]
                if (s >= cap) return s
            }
            return s
        }
        var ba = cs[fa]; var bb = cs[fb]; var be = score(ba, bb, Long.MAX_VALUE)
        // (the commonest four: a few passes of selection over at most 16)
        val order = (0 until n).sortedByDescending { ns[it] }
        val m = minOf(4, n)
        for (i in 0 until m) for (j in i + 1 until m) {
            val a = cs[order[i]]; val b = cs[order[j]]
            val e = score(a, b, be)
            if (e < be) { be = e; ba = a; bb = b }
        }
        return ba to bb
    }

    /** The block's bits: mode, one partition, endpoint mode 12, eight 8-bit endpoint values, weights from the top down. */
    private fun write(b: ByteArray, c0: Int, c1: Int, wts: IntArray) {
        java.util.Arrays.fill(b, 0)
        bits(b, 0, 11, BLOCK_MODE)
        bits(b, 11, 2, 0)
        bits(b, 13, 4, CEM_RGBA)
        // r0 r1 g0 g1 b0 b1 a0 a1
        var p = 17
        for (k in intArrayOf(2, 1, 0, 3)) { bits(b, p, 8, ch(c0, k)); bits(b, p + 8, 8, ch(c1, k)); p += 16 }
        // the weights' stream runs from bit 127 down: its bit n at block bit 127 - n
        for (i in 0 until 16) for (k in 0 until 2) {
            if ((wts[i] shr k) and 1 == 1) { val n = 127 - (i * 2 + k); b[n shr 3] = (b[n shr 3].toInt() or (1 shl (n and 7))).toByte() }
        }
    }

    private fun bits(b: ByteArray, at: Int, n: Int, v: Int) {
        for (k in 0 until n) if ((v shr k) and 1 == 1) { val p = at + k; b[p shr 3] = (b[p shr 3].toInt() or (1 shl (p and 7))).toByte() }
    }

    private fun get(b: ByteArray, at: Int, n: Int): Int {
        var v = 0
        for (k in 0 until n) { val p = at + k; if ((b[p shr 3].toInt() shr (p and 7)) and 1 == 1) v = v or (1 shl k) }
        return v
    }

    /** A block written by [encodeBlock] back to its 16 ARGB texels (only this encoder's mode: null for any other block). */
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

    /**
     * One level of [w] x [h] from [src] (at [off], as [encodeLevel] wrote it) back to RGBA bytes as Filament takes them
     * (bottom row first): what the GPU shows.
     */
    fun decodeLevel(src: ByteBuffer, off: Int, w: Int, h: Int): ByteBuffer {
        val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val b = ByteArray(16); val t = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = off + (by * bw + bx) * 16
            for (k in 0 until 16) b[k] = src.get(o + k)
            check(decodeBlock(b, t)) { "not a block of ours" }
            for (j in 0 until 16) {
                val x = bx * 4 + (j and 3); val y = by * 4 + (j shr 2)
                if (x >= w || y >= h) continue
                val c = t[j]; val p = (y * w + x) * 4
                out.put(p, (c shr 16).toByte()); out.put(p + 1, (c shr 8).toByte()); out.put(p + 2, c.toByte()); out.put(p + 3, (c ushr 24).toByte())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ mip levels

    private val toLin = FloatArray(256) { val c = it / 255f; if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat() }
    /** The linear value at which the sRGB byte rounds up to the next: (c + 0.5) / 255 in sRGB, made linear. */
    private val upAt = FloatArray(255) { val c = (it + .5f) / 255f; if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat() }
    /** Linear light [l] as the nearest sRGB byte (a binary search of [upAt]: the same as converting and rounding). */
    private fun toSrgb(l: Float): Int {
        var lo = 0; var hi = 255
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (l < upAt[mid]) hi = mid else lo = mid + 1 }
        return lo
    }

    /** The next level: each texel the mean of the 2 x 2 under it (rgb in linear light for [srgb]), rows top first. */
    fun halve(px: IntArray, w: Int, h: Int, nw: Int, nh: Int, srgb: Boolean): IntArray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
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
