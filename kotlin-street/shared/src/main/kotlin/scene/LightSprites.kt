package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The street's light as soft fields, white at every alpha so the light's colour tints them, with no
 * edge anywhere: light in air and on the ground falls off smoothly, and a shadow's edge (its
 * penumbra) widens the further it falls from what casts it. Each is dithered (a fraction of a level
 * of noise before it is rounded) so a dark night sky never shows bands.
 * - [lampAir]: the glow of the air under a street lamp's lens, brightest round the lens and fading
 *   down and out as a cone with no sides ([AIR_W] by [AIR_H], lamp units [AIR_X0]..[AIR_X1] across
 *   and [AIR_Y0]..0 down to the pavement, the lens at the origin's top).
 * - [pool]: a lamp's light on the ground, a bright middle under the lens and a long soft tail.
 * - [spill]: a lit window's light thrown out on the pavement, as wide as the window at the wall and
 *   spreading, its sides blurring and its light fading as it goes ([SPILL_W] by [SPILL_H]; across,
 *   [SPILL_SPAN] window half-widths each side of the middle; down, the pavement's depth).
 */
internal object LightSprites {
    const val AIR_W = 96
    const val AIR_H = 128
    const val AIR_X0 = -60f
    const val AIR_X1 = 60f
    const val AIR_Y0 = -98f

    const val POOL_W = 128
    const val POOL_H = 32

    const val SPILL_W = 96
    const val SPILL_H = 40
    const val SPILL_SPAN = 2.2f

    /** The lamp's lens, in lamp units (the pavement at 0). */
    const val LENS_X = 11.5f
    const val LENS_Y = -90.5f

    /** A soft edge: [RAMP] pixels from clear to full, one high (stretched to any edge). */
    const val RAMP = 64

    fun bytes(): Int =
        (AIR_W * AIR_H + POOL_W * POOL_H + SPILL_W * SPILL_H + RAMP + POINT * POINT) * 4

    /**
     * A point light's fall-off as a mask, [POINT] pixels across for [POINT_R] street units of
     * radius: 1 at the light, an inverse square (softened by [POINT_CORE]) out to nothing at the
     * rim. What a lamp lights is lit through it, so the light fades across a walker from the head
     * and the side nearest the lamp down to the feet and the far side.
     */
    const val POINT = 64
    const val POINT_R = 170f
    const val POINT_CORE = 45f

    fun point(): IntArray {
        val px = IntArray(POINT * POINT)
        val mid = POINT / 2f
        for (j in 0 until POINT) for (i in 0 until POINT) {
            val x = (i + .5f - mid) / mid
            val y = (j + .5f - mid) / mid
            val rn = sqrt(x * x + y * y)
            val r = rn * POINT_R / POINT_CORE
            px[j * POINT + i] = white(Px.smooth(1f, .8f, rn) / (1f + r * r), i, j)
        }
        return px
    }

    fun ramp(): IntArray =
        IntArray(RAMP) { Px.argbF(Px.smooth(0f, 1f, (it + .5f) / RAMP), 1f, 1f, 1f) }

    fun lampAir(): IntArray {
        val px = IntArray(AIR_W * AIR_H)
        for (j in 0 until AIR_H) for (i in 0 until AIR_W) {
            val x = AIR_X0 + (i + .5f) / AIR_W * (AIR_X1 - AIR_X0) - LENS_X
            val y = AIR_Y0 + (j + .5f) / AIR_H * -AIR_Y0 - LENS_Y
            val d = sqrt(x * x + y * y)
            // the light leaves the hood downward: its angle from straight down, spread soft
            val theta = atan2(x, y + 6f)
            val cone = exp(-(theta / .5f) * (theta / .5f))
            // scattered by the air, so it fades with distance (an inverse square, softened)
            val fall = 1f / (1f + (d / 26f) * (d / 26f))
            // and the air close round the lens glows whatever the direction
            val core = exp(-(d / 7f) * (d / 7f))
            val above = Px.smooth(-14f, 2f, y)
            var a = (.55f * cone * fall * above + .45f * core) * edge(i, j, AIR_W, AIR_H, 10f)
            // what light reaches the pavement fades into it rather than stopping at a line
            a *= Px.smooth(0f, -10f, y + LENS_Y)
            px[j * AIR_W + i] = white(a, i, j)
        }
        return px
    }

    fun pool(): IntArray {
        val px = IntArray(POOL_W * POOL_H)
        for (j in 0 until POOL_H) for (i in 0 until POOL_W) {
            val x = (i + .5f) / POOL_W * 2f - 1f
            val y = (j + .5f) / POOL_H * 2f - 1f
            val r = sqrt(x * x + y * y)
            // the ground lit by a point above it: brightest under it, an inverse-cube fall
            val q = 1f + (r / .22f) * (r / .22f)
            val a = (1f / (q * sqrt(q))) * .85f + exp(-(r / .55f) * (r / .55f)) * .15f
            px[j * POOL_W + i] = white(a * Px.smooth(1f, .75f, r), i, j)
        }
        return px
    }

    fun spill(): IntArray {
        val px = IntArray(SPILL_W * SPILL_H)
        for (j in 0 until SPILL_H) for (i in 0 until SPILL_W) {
            val u = ((i + .5f) / SPILL_W * 2f - 1f) * SPILL_SPAN
            val v = (j + .5f) / SPILL_H
            // the patch spreads as it leaves the glass, and its sides blur as they spread
            val half = 1f + .45f * v
            val blur = .18f + .9f * v
            val across = Px.smooth(half + blur, half - blur, abs(u))
            val along = (1f - v) * (1f - v) * (.4f + .6f * (1f - v)) * Px.smooth(0f, .05f, v)
            px[j * SPILL_W + i] = white(across * along, i, j)
        }
        return px
    }

    /** 1 inside, falling to 0 over [soft] pixels to the border, so nothing ends at a line. */
    private fun edge(i: Int, j: Int, w: Int, h: Int, soft: Float): Float {
        val b = minOf(minOf(i, w - 1 - i), minOf(j, h - 1 - j)).toFloat()
        return Px.smooth(0f, soft, b)
    }

    /** White at alpha [a], dithered by a hash of the pixel so slow falloffs do not band. */
    private fun white(a: Float, i: Int, j: Int): Int {
        var n = i * 374761393 + j * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        val dither = ((n and 0xFFFF) / 65536f - .5f) / 255f
        val k = Px.clamp01(Px.clamp01(a) + dither)
        return if (k < .002f) 0 else Px.argbF(k, 1f, 1f, 1f)
    }
}
