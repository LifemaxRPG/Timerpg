package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.floor

/*
 * The small toolbox the scene's pre-rendered art is made with: seeded value noise (smooth, fractal), a few
 * easing helpers and ARGB packing. Pure Kotlin with no Compose and no Android, so every generator that uses it
 * is deterministic, runs on any thread and is tested (and looked at) on a plain JVM. Nothing here is called per
 * frame: sprites are made once and drawn as images.
 */
internal object Noise {
    private fun cell(ix: Int, iy: Int, seed: Int): Float {
        var n = ix * 374761393 + iy * 668265263 + seed * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777216f
    }

    /** Smooth value noise, 0 to 1, with a lattice of one unit. */
    fun value(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x)
        val y0 = floor(y)
        val ix = x0.toInt()
        val iy = y0.toInt()
        val fx = x - x0
        val fy = y - y0
        val u = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val v = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
        val a = cell(ix, iy, seed)
        val b = cell(ix + 1, iy, seed)
        val c = cell(ix, iy + 1, seed)
        val d = cell(ix + 1, iy + 1, seed)
        val top = a + (b - a) * u
        return top + (c + (d - c) * u - top) * v
    }

    /**
     * Fractal noise, 0 to 1: [octaves] of [value], each twice as fine and [gain] as strong, turned
     * a little so the lattice never lines up.
     */
    fun fbm(x: Float, y: Float, seed: Int, octaves: Int, gain: Float = .5f): Float {
        var sum = 0f
        var amp = 1f
        var norm = 0f
        var px = x
        var py = y
        for (o in 0 until octaves) {
            sum += amp * value(px, py, seed + o * 131)
            norm += amp
            amp *= gain
            val nx = (px * .8f - py * .6f) * 2f + 17.3f
            py = (px * .6f + py * .8f) * 2f - 9.1f
            px = nx
        }
        return sum / norm
    }

    /** Billowy noise, 0 to 1: rounded heaps with creases between them (the look of cumulus). */
    fun billow(x: Float, y: Float, seed: Int, octaves: Int, gain: Float = .5f): Float {
        var sum = 0f
        var amp = 1f
        var norm = 0f
        var px = x
        var py = y
        for (o in 0 until octaves) {
            sum += amp * abs(value(px, py, seed + o * 97) * 2f - 1f)
            norm += amp
            amp *= gain
            val nx = (px * .8f - py * .6f) * 2f + 5.7f
            py = (px * .6f + py * .8f) * 2f + 3.3f
            px = nx
        }
        return 1f - sum / norm
    }
}

internal object Px {
    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** ARGB from 0..255 channels. */
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /** ARGB from 0..1 channels. */
    fun argbF(a: Float, r: Float, g: Float, b: Float): Int =
        argb(
            (a * 255f + .5f).toInt(),
            (r * 255f + .5f).toInt(),
            (g * 255f + .5f).toInt(),
            (b * 255f + .5f).toInt(),
        )
}
