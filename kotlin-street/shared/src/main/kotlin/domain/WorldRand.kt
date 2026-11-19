package com.lifemax.rpg.feature.habits.domain

import kotlin.math.floor

/**
 * The game's keyed randomness (src/sim/rng.ts: xmur3 seeding mulberry32), bit for bit, so the Kotlin side can grow the
 * same world as the TypeScript: rand(seed, keys...) is the same number on both (keys joined by "|", numbers written as
 * JavaScript writes whole numbers).
 */
object WorldRand {
    fun rand(seed: String, vararg keys: Any): Double {
        val sb = StringBuilder(seed)
        for (k in keys) { sb.append('|'); sb.append(if (k is Double && k == floor(k)) k.toLong().toString() else k.toString()) }
        val s = sb.toString()
        var h = 1779033703 xor s.length
        for (ch in s) { h = (h xor ch.code) * -862048943; h = (h shl 13) or (h ushr 19) }
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        h = h xor (h ushr 16)
        // mulberry32's first draw
        var a = h
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}

/** A tree, bush or wild flowers between the towns (src/world/wild.ts): x, y in world pixels (32 a tile; y the bottom). */
class WildProp(val kind: String, val x: Int, val y: Int, val look: Double)

/** The wilderness between the towns, chunk by chunk, as src/world/wild.ts wildChunk grows it. */
object WildChunks {
    const val CHUNK = 32

    private fun noise(seed: String, x: Double, y: Double): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        fun h(ix: Int, iy: Int) = WorldRand.rand(seed, "wild-noise", ix, iy)
        val a = h(x0, y0); val b = h(x0 + 1, y0); val c = h(x0, y0 + 1); val d = h(x0 + 1, y0 + 1)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

    fun chunk(worldSeed: String, cx: Int, cy: Int): List<WildProp> {
        val out = ArrayList<WildProp>()
        var ty = 0
        while (ty < CHUNK) {
            var tx = 0
            while (tx < CHUNK) {
                val gx = cx * CHUNK + tx; val gy = cy * CHUNK + ty
                val grove = noise(worldSeed, gx / 22.0, gy / 22.0)
                val p = if (grove > 0.56) 0.25 + (grove - 0.56) * 2.2 else 0.035
                val r = WorldRand.rand(worldSeed, "wild", gx, gy)
                if (r < p) {
                    out += WildProp("tree", jsRound((gx + 0.5 + WorldRand.rand(worldSeed, "wx", gx, gy)) * 32), jsRound((gy + 1.5 + WorldRand.rand(worldSeed, "wy", gx, gy)) * 32), WorldRand.rand(worldSeed, "wl", gx, gy))
                } else if (r > 0.93) {
                    out += WildProp(if (r > 0.97) "flowers" else "bush", jsRound((gx + 1) * 32.0), jsRound((gy + 1.4) * 32), WorldRand.rand(worldSeed, "wl2", gx, gy))
                }
                tx += 2
            }
            ty += 2
        }
        return out
    }
}
