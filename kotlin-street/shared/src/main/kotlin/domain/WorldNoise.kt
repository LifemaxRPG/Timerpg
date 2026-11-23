package com.lifemax.rpg.feature.habits.domain

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The world skeleton's integer-hashed noise (src/world/gen/noise.ts, WORLD_GEN.md section 2), ported bit for bit: the
 * same 32-bit hashes (JavaScript's Math.imul, shifts and xor are Kotlin's Int `*`, `shl`, `ushr`, `xor`) and the same
 * double-precision interpolation in the same order, so a value the TypeScript bake computed at a point is the value this
 * computes there (desktop WorldSkeletonTest checks it against a fixture the TypeScript wrote). Hashes come back as
 * unsigned 32-bit values in a Long, as JavaScript's `>>> 0` gives them.
 */
object WorldNoise {
    /** A 32-bit hash of a string (xmur3's mixing, finalised): noise.ts hashStr. UTF-16 code units, as charCodeAt. */
    fun hashStr(s: String): Long {
        var h = 1779033703 xor s.length
        for (ch in s) {
            h = (h xor ch.code) * -862048943 // 3432918353
            h = (h shl 13) or (h ushr 19)
        }
        h = (h xor (h ushr 16)) * -2048144789 // 2246822507
        h = (h xor (h ushr 13)) * -1028477387 // 3266489909
        return (h xor (h ushr 16)).toLong() and 0xffffffffL
    }

    /** Hash of a 32-bit seed and up to three integers (murmur3's finaliser between the words): noise.ts hash3. */
    fun hash3(seed: Long, a: Int, b: Int = 0, c: Int = 0): Long {
        var h = seed.toInt() xor (a * -1640531535) // 0x9e3779b1
        h = (h xor (h ushr 16)) * -2048144789 // 0x85ebca6b
        h = h xor (b * -1028477387) // 0xc2b2ae35
        h = (h xor (h ushr 13)) * 668265263 // 0x27d4eb2f
        h = h xor (c * 374761393) // 0x165667b1
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        return (h xor (h ushr 16)).toLong() and 0xffffffffL
    }

    /** A double in [0, 1) from a seed and integers: noise.ts hashU. */
    fun hashU(seed: Long, a: Int, b: Int = 0, c: Int = 0): Double = hash3(seed, a, b, c) / 4294967296.0

    internal fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)
}

/**
 * Value noise on a lattice of [nx] cells round the planet (periodic in x), sampled in atlas cell units: noise.ts Lattice.
 * Values in [0, 1).
 */
class WorldLattice(seed: String, key: String, val nx: Int) {
    val ny: Int = ceil(WorldSkeleton.H.toDouble() / WorldSkeleton.W * nx).toInt() + 3
    private val s: Double = nx.toDouble() / WorldSkeleton.W
    private val v = DoubleArray(nx * ny)

    init {
        val h = WorldNoise.hashStr("$seed|noise|$key|$nx")
        for (j in 0 until ny) for (i in 0 until nx) v[j * nx + i] = WorldNoise.hashU(h, i, j)
    }

    fun at(x: Double, z: Double): Double {
        val u = x * s; val w = z * s + 1
        val fu0 = floor(u)
        var j0 = floor(w).toInt()
        if (j0 < 0) j0 = 0 else if (j0 > ny - 2) j0 = ny - 2
        val fu = WorldNoise.fade(u - fu0)
        var tv = w - j0; if (tv < 0) tv = 0.0 else if (tv > 1) tv = 1.0
        val fv = WorldNoise.fade(tv)
        // (JavaScript's ((fu0 % nx) + nx) % nx on a double: the same integer here)
        val i0 = (((fu0 % nx) + nx) % nx).toInt()
        val i1 = if (i0 + 1 == nx) 0 else i0 + 1
        val r0 = j0 * nx; val r1 = r0 + nx
        val a = v[r0 + i0]; val b = v[r0 + i1]; val c = v[r1 + i0]; val d = v[r1 + i1]
        val top = a + (b - a) * fu; val bot = c + (d - c) * fu
        return top + (bot - top) * fv
    }
}

/** Fractal sum of value noise: octaves of [nx], 2nx, 4nx... lattice cells, normalised to [0, 1): noise.ts Fbm. */
class WorldFbm(seed: String, key: String, nx: Int, octaves: Int, gain: Double = .5) {
    private val oct = ArrayList<WorldLattice>()
    private val amp = ArrayList<Double>()
    private val tot: Double

    init {
        var a = 1.0; var t = 0.0
        var n = nx
        for (o in 0 until octaves) { oct += WorldLattice(seed, "$key$o", n); amp += a; t += a; a *= gain; n *= 2 }
        tot = t
    }

    fun at(x: Double, z: Double): Double {
        var s = 0.0
        for (o in oct.indices) s += amp[o] * oct[o].at(x, z)
        return s / tot
    }
}
