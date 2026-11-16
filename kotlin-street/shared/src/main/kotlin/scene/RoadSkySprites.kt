package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The small painted things of the far road and the night sky: the vehicles that pass on the horizon, the light
 * they throw (a soft glow, a headlight beam) and the meteor's streak. Like the clouds, each is baked once by pure
 * Kotlin from its spec, as ARGB pixels, and drawn as a tinted image under a transform; nothing here runs per
 * frame. The vehicles are painted at four pixels a dp (they are a few dp long on screen, so they must hold up at
 * a 3x density), the lights and the streak at two.
 */

/** The body shapes of the far road's vehicles. */
internal enum class VehicleKind {
    HATCH,
    SEDAN,
    SUV,
    VAN,
}

/**
 * One vehicle, nose to the right: its picture by [day] (its own colour, lit from above) and by
 * [night] (a dark body, its lenses lit), the same shape, and where its lights are in the picture's
 * pixels: the headlight at ([frontX], [lightY]), the tail-light at ([backX], [lightY]), its wheels
 * on the road at [roadY]. Draw the sprite mirrored for a vehicle going left.
 */
internal class VehicleSprite(
    val w: Int,
    val h: Int,
    val day: IntArray,
    val night: IntArray,
    val frontX: Float,
    val backX: Float,
    val lightY: Float,
    val roadY: Float,
) {
    val dpW: Float
        get() = w / RoadSkySprites.CAR_PX_PER_DP

    val dpH: Float
        get() = h / RoadSkySprites.CAR_PX_PER_DP
}

/** What one vehicle of the pool is: its shape, its pixel size and its paint (RGB). */
internal class VehicleSpec(val kind: VehicleKind, val w: Int, val h: Int, val paint: Int)

/**
 * The meteor's streak, head to the right: its hot [core] and its wider, cooler [halo], both white.
 */
internal class StreakSprite(
    val w: Int,
    val h: Int,
    /** Where the head is, in pixels from the left. */
    val headX: Float,
    val core: IntArray,
    val halo: IntArray,
)

internal object RoadSkySprites {
    /** Pixels in a dp of a vehicle sprite. */
    const val CAR_PX_PER_DP = 4f

    /** Pixels in a dp of the light sprites and the streak. */
    const val PX_PER_DP = 2f

    /**
     * The vehicles of the far road: three cars of different shapes and colours, and a people
     * carrier, each the front road's car of that shape made small ([generate]).
     */
    val VEHICLES: List<VehicleSpec> =
        listOf(
            VehicleSpec(VehicleKind.HATCH, 44, 20, 0xA3201F),
            VehicleSpec(VehicleKind.SEDAN, 48, 20, 0xD2D6DB),
            VehicleSpec(VehicleKind.SUV, 48, 22, 0x2A4E80),
            VehicleSpec(VehicleKind.VAN, 52, 24, 0xE6E4DE),
        )

    /** The soft round glow: a light's bloom, a pool on the road, a sparkle. Square. */
    const val GLOW = 64

    /** The headlight's beam: from its origin on the left, opening to the right. */
    const val BEAM_W = 128
    const val BEAM_H = 40

    /** The meteor's streak. */
    const val STREAK_W = 320
    const val STREAK_H = 56

    /** Bytes the whole pool holds. */
    fun bytes(): Int =
        VEHICLES.sumOf { it.w * it.h * 4 * 2 } +
            GLOW * GLOW * 4 +
            BEAM_W * BEAM_H * 4 +
            STREAK_W * STREAK_H * 4 * 2

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = Px.mix((a shr s and 255).toFloat(), (b shr s and 255).toFloat(), t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- the vehicles

    /**
     * Pixels a street unit the far road's vehicles are made at: about a fifth of the near car's.
     */
    private const val SMALL_PPU = .62f

    /** The near road's body ([CarArt]) each far one is made from. */
    private fun body(kind: VehicleKind): Int =
        when (kind) {
            VehicleKind.HATCH -> 1
            VehicleKind.SEDAN -> 0
            VehicleKind.SUV -> 2
            VehicleKind.VAN -> 5
        }

    /**
     * Vehicle [index] of [VEHICLES]: the front road's car of that shape, in its paint, made small
     * (each pixel the average of the full-size picture under it), with its lenses marked so they
     * read at this size; by night the paint is mostly the night's blue and the lenses are lit.
     */
    fun generate(index: Int): VehicleSprite {
        val spec = VEHICLES[index]
        val b = body(spec.kind)
        val w = spec.w
        val h = spec.h
        val roadY = h - 4f
        val cx = w / 2f
        val half = CarArt.length(b) / 2f * SMALL_PPU
        val lightY = roadY - CarArt.lampUp(b) * SMALL_PPU
        val frontX = cx + half - 1.5f
        val backX = cx - half + 1.5f
        val day = CarArt.small(b, spec.paint, false, SMALL_PPU, w, h, cx, roadY)
        val night =
            CarArt.small(b, mix(spec.paint, 0x0B0E18, .82f), true, SMALL_PPU, w, h, cx, roadY)
        // the same shape by night as by day, to the pixel
        for (i in night.indices) night[i] = (day[i] and -0x1000000) or (night[i] and 0xFFFFFF)
        lens(day, w, frontX, lightY, 0xFFF1C8, .9f)
        lens(day, w, backX, lightY, 0xB8302A, .9f)
        lens(night, w, frontX, lightY, 0xFFF8E6, 1f)
        lens(night, w, backX, lightY, 0xFF5A48, 1f)
        return VehicleSprite(w, h, day, night, frontX, backX, lightY, roadY)
    }

    /** A lens of [rgb] laid over the pixel at ([x], [y]) where the body already is. */
    private fun lens(px: IntArray, w: Int, x: Float, y: Float, rgb: Int, a: Float) {
        val i = y.toInt() * w + x.toInt()
        val p = px[i]
        val pa = p ushr 24
        if (pa == 0) return
        val q = mix(p and 0xFFFFFF, rgb, a)
        px[i] = (pa shl 24) or q
    }

    // ---- the lights

    /** The glow: a soft round bloom, white, with a tighter bright core; clear at the border. */
    fun glow(): IntArray {
        val n = GLOW
        val r0 = n / 2f
        return IntArray(n * n) {
            val x = it % n + .5f - r0
            val y = it / n + .5f - r0
            val r = sqrt(x * x + y * y) / r0
            val a =
                (.55f * exp(-(r / .24f) * (r / .24f)) + .45f * exp(-(r / .52f) * (r / .52f))) *
                    Px.smooth(1f, .8f, r) *
                    border(it % n, it / n, n, n)
            if (a <= .002f) 0 else Px.argbF(a, 1f, 1f, 1f)
        }
    }

    /** 0 on the outermost row and column of a [w] by [h] picture, 1 inside it. */
    private fun border(x: Int, y: Int, w: Int, h: Int): Float =
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) 0f else 1f

    /**
     * The beam: light leaving its origin (at the left, half way down) in a cone to the right,
     * bright and narrow where it leaves, wider and fainter the further it goes, soft at its sides.
     */
    fun beam(): IntArray {
        val w = BEAM_W
        val h = BEAM_H
        val oy = h / 2f
        return IntArray(w * h) {
            val x = it % w + .5f - 6f
            val y = it / w + .5f - oy
            if (x <= 0f) 0
            else {
                val spread = 1.3f + x * .105f
                val across = exp(-(y * y) / (2f * spread * spread))
                val along = exp(-x / 52f) * Px.smooth(0f, 4f, x)
                val a =
                    across *
                        along *
                        Px.smooth(w - 6f, w - 30f, x + 6f) *
                        Px.smooth(0f, 3f, oy - abs(y)) *
                        border(it % w, it / w, w, h)
                if (a <= .002f) 0 else Px.argbF(a, 1f, 1f, 1f)
            }
        }
    }

    // ---- the meteor

    /**
     * The streak: a line that is hottest and thinnest just behind the head and tapers back to
     * nothing along a slight curve, with a fine ripple in its brightness. The core is the burning
     * trail itself, the halo the glow of the air round it: wider, softer, drawn in a cooler tint.
     */
    fun streak(): StreakSprite {
        val w = STREAK_W
        val h = STREAK_H
        val headX = w - 14f
        val tailX = 10f
        val core = IntArray(w * h)
        val halo = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val cx = x + .5f
            val cy = y + .5f
            // how far back along the streak, 0 at the head to 1 at the tail's end
            val s = (headX - cx) / (headX - tailX)
            if (s > 1f) continue
            val sc = s.coerceAtLeast(0f)
            // the streak sags a little towards its tail: a curve, not a ruler
            val mid = h * .42f + sc * sc * h * .22f
            // how far off the streak's line; ahead of the head, how far from the head itself
            val dy = cy - mid
            val ax = (cx - headX).coerceAtLeast(0f)
            val d2 = dy * dy + ax * ax
            val ripple =
                1f +
                    .3f * (Noise.fbm(cx * .09f, 3f, 17, 3) - .5f) +
                    .15f * (Noise.value(cx * .35f, 1f, 5) - .5f)
            val coreW =
                (.45f + 2.3f * (1f - sc).pow(.9f)) *
                    (1f + .25f * (Noise.fbm(cx * .05f, 7f, 23, 2) - .5f))
            val coreI = (1f - sc).pow(1.5f) * ripple * (1f + .6f * Px.smooth(.14f, 0f, sc))
            val haloW = 1f + 5.5f * (1f - sc).pow(.65f)
            val haloI = .5f * (1f - sc).pow(1.25f) * (1f + .12f * (ripple - 1f))
            val edge = Px.smooth(0f, 4f, min(min(cx, w - cx), min(cy, h - cy))) * border(x, y, w, h)
            val c = coreI * exp(-d2 / (2f * coreW * coreW)) * edge
            val g = haloI * exp(-d2 / (2f * haloW * haloW)) * edge
            val i = y * w + x
            if (c > .002f) core[i] = Px.argbF(Px.clamp01(c), 1f, 1f, 1f)
            if (g > .002f) halo[i] = Px.argbF(Px.clamp01(g), 1f, 1f, 1f)
        }
        return StreakSprite(w, h, headX, core, halo)
    }
}
