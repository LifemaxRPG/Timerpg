package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The horizon ring (docs/STREAMING.md, step 4; OPTIMIZATION_RESEARCH.md 2.5): the world past the far view's reach
 * ([WorldFarPlan.REACH_M], 172 km) as a panorama seen from one eye, baked on the CPU from the skeleton and drawn by the
 * sky (sky.mat) where nothing nearer is: mountain ranges and the lights of distant cities on the horizon, for one
 * texture and no draw of its own.
 *
 * The picture is [W] columns of azimuth round the full circle (0 north, east positive, as the sky's `atan(x, -z)`) by
 * [H] rows of elevation from [ELEV0] up, each texel the same angle both ways ([STEP] rad, 0.088 deg), nearest-sampled: a
 * mountain range 300 km off is a stepped pixel-art silhouette. Each column is marched outward from [r0] to [r1] over the
 * skeleton's heights ([WorldSkeleton.height]); the nearest ground that rises over everything before it fills the rows
 * between the old and the new top, so a near ridge hides what is behind it. A texel holds the ground's colour (the
 * biomes' palette, snow above the snow line, a stepped tone by how the slope faces the eye) and in alpha its distance
 * ([code]: 0 empty), so the sky hazes it by the air along the way ([WorldAir], the same law as the far view's fog) and a
 * clear day shows a ghost of the far ranges, a hazy one nothing. Texels of a distant town that the eye sees are marked
 * [LIT] (an exact colour the shader knows): grey by day, a warm light by night.
 *
 * Flat planet (as the far view is): no curvature drop. Baked again when the eye has moved [REBAKE_M] or climbed
 * [REBAKE_UP_M] (the parallax of 172 km is 0.3 deg a km): ~50 ms on a worker (desktop).
 */
class HorizonPanorama(
    val sk: WorldSkeleton,
    /** Where the eye was (world, m) and its height above sea level (m). */
    val centre: WorldPos, val eyeM: Double,
    val r0: Double = WorldFarPlan.REACH_M * .97, val r1: Double = MAX_M,
) {
    /** RGBA8 (not sRGB), row 0 at [ELEV0], [W] x [H]. */
    val pixels = ByteArray(W * H * 4)
    /** Texels filled; distant towns' lit texels. */
    var filled = 0; private set
    var lit = 0; private set
    var bakeMs = 0.0; private set

    init { bake() }

    fun code(d: Double): Int = (1 + ((d - r0) / (r1 - r0) * 253).roundToInt()).coerceIn(1, 254)

    private fun row(angle: Double): Int = floor((angle - ELEV0) / STEP).toInt()

    private fun put(i: Int, j: Int, argb: Int, d: Double) {
        if (j < 0 || j >= H) return
        val o = (j * W + i) * 4
        if (pixels[o + 3].toInt() == 0) filled++
        pixels[o] = (argb shr 16 and 255).toByte(); pixels[o + 1] = (argb shr 8 and 255).toByte(); pixels[o + 2] = (argb and 255).toByte()
        pixels[o + 3] = code(d).toByte()
    }

    private fun cx(x: Double) = (centre.x + x) / Planet.CELL_M
    private fun cz(z: Double) = (centre.z + z) / Planet.CELL_M
    private fun ground(x: Double, z: Double): Double = max(sk.height(cx(x), cz(z)), 0.0)

    private fun colour(x: Double, z: Double, h: Double, facing: Double, d: Double): Int {
        val i = floor(cx(x)).toInt(); val j = floor(cz(z)).toInt()
        val b = sk.biome(i, j)
        val dither = WorldNoise.hashU(TONE, floor(x / 2000).toInt(), floor(z / 2000).toInt(), 1) < .5
        var c = WorldFarPlan.biomeColour(b, dither)
        val line = if (sk.has("snowline")) sk.raw("snowline", Math.floorMod(i, WorldSkeleton.W) + j.coerceIn(0, WorldSkeleton.H - 1) * WorldSkeleton.W) else WorldFarPlan.SNOW_M
        if (h > line && b != 0) c = if (dither) 0xFFF2F4FA.toInt() else 0xFFE2E8F2.toInt()
        // (a stepped tone by the slope toward the eye: faces turned to it lighter, the backs of ridges darker; three steps)
        val k = when { facing > .06 -> 1.0; facing > -.02 -> .86; else -> .72 }
        return shade(c, k)
    }

    private fun bake() {
        val t0 = System.nanoTime()
        val towns = sk.places.filter { it.kind == "hub" || it.rank == "major" || it.rank == "town" }.mapNotNull { p ->
            val dx = centre.dx(WorldPos.ofCells(p.x, p.z)); val dz = p.z * Planet.CELL_M - centre.z
            val d = hypot(dx, dz)
            if (d < r0 || d > r1) null else Town(dx, dz, d, if (p.kind == "hub") 1200.0 else if (p.rank == "major") 700.0 else 450.0, if (p.kind == "hub") 2 else 1)
        }
        for (i in 0 until W) {
            val az = (i + .5) / W * 2 * PI
            val sx = sin(az); val sz = -cos(az)
            var top = Double.NEGATIVE_INFINITY
            var r = r0
            var hPrev = ground(sx * (r - 400), sz * (r - 400))
            var rPrev = r - 400
            while (r < r1) {
                val x = sx * r; val z = sz * r
                val h = ground(x, z)
                val a = atan((h - eyeM) / r)
                if (a > top) {
                    val j0 = max(row(top) + 1, 0); val j1 = row(a)
                    if (j1 >= j0 && h > .5) {
                        // (how the slope faces the eye: the rise toward it per metre less the line of sight's own)
                        val facing = (h - hPrev) / (r - rPrev) - (h - eyeM) / r
                        val c = colour(x, z, h, facing, r)
                        for (j in j0..min(j1, H - 1)) put(i, j, c, r)
                    }
                    top = a
                }
                hPrev = h; rPrev = r
                r += max(STEP_MIN_M, r * STEP_GROW)
            }
        }
        // distant towns: where their middle is in sight, a few lit texels on the ground there
        for (t in towns) {
            val az = kotlin.math.atan2(t.x, -t.z).let { if (it < 0) it + 2 * PI else it }
            val half = atan(t.radius / t.d)
            val iA = floor((az - half) / (2 * PI) * W).toInt(); val iB = floor((az + half) / (2 * PI) * W).toInt()
            val h = ground(t.x, t.z)
            if (h <= .5) continue
            val a = atan((h + 15 - eyeM) / t.d)
            for (ii in iA..iB) {
                val i = Math.floorMod(ii, W)
                // (in sight: nothing nearer in this column rises over it)
                if (hiddenBefore(i, t.d, a)) continue
                val j = row(a)
                for (k in 0 until t.rows) { put(i, j + k, LIT, t.d); lit++ }
            }
        }
        bakeMs = (System.nanoTime() - t0) / 1e6
    }

    private class Town(val x: Double, val z: Double, val d: Double, val radius: Double, val rows: Int)

    /** Whether ground in column [i] nearer than [d] rises over elevation [a]. */
    private fun hiddenBefore(i: Int, d: Double, a: Double): Boolean {
        val az = (i + .5) / W * 2 * PI
        val sx = sin(az); val sz = -cos(az)
        var r = min(r0, d * .5)
        while (r < d - 1500) {
            if (atan((ground(sx * r, sz * r) - eyeM) / r) > a) return true
            r += max(STEP_MIN_M, r * STEP_GROW)
        }
        return false
    }

    /** Whether the eye at [at], [eye] m up has gone far enough from where this was baked to bake it again. */
    fun stale(at: WorldPos, eye: Double): Boolean = hypot(at.dx(centre), at.z - centre.z) > REBAKE_M || kotlin.math.abs(eye - eyeM) > REBAKE_UP_M

    val report: String get() = "horizon ring: %d x %d, %.0f-%.0f km, %d texels of ground, %d lit, baked in %.0f ms".format(
        java.util.Locale.US, W, H, r0 / 1000, r1 / 1000, filled, lit, bakeMs)

    companion object {
        const val W = 4096
        const val H = 64
        /** The angle of a texel (rad), both ways. */
        val STEP: Double = 2 * PI / W
        /** The lowest row's elevation (rad): the sky's below-the-horizon cut. */
        const val ELEV0 = -.004
        /** The farthest ground drawn (m). */
        const val MAX_M = 640_000.0
        /** The march's step: this share of the distance, never under [STEP_MIN_M]. */
        const val STEP_GROW = .004
        const val STEP_MIN_M = 600.0
        const val REBAKE_M = 1500.0
        const val REBAKE_UP_M = 150.0
        /** A distant town's lit texel (the shader's sentinel: exactly this colour). */
        const val LIT = 0xFFFF00FF.toInt()
        private val TONE = WorldNoise.hashStr("horizon-ring-tone")

        private fun shade(c: Int, k: Double): Int {
            val r = ((c shr 16 and 255) * k).toInt(); val g = ((c shr 8 and 255) * k).toInt(); val b = ((c and 255) * k).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
