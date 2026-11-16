package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.CloudKind
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One cloud, painted once: four white ARGB layers whose alpha is the cloud's own light. [shade] is
 * the whole cloud (its density, feathered at the edges), [mid] the part of it that catches light
 * and [lit] the part that catches a lot (a rim on the sun's side, the crowns of the heaps); [under]
 * is its belly and base, the part lit from below (by a sun already under the horizon at dusk, by
 * the city's glow at night), brightest along the thin lower edges. Each is drawn with the tint of
 * the hour, so the same sprite is a white afternoon cumulus, a peach dusk one or a moonlit night
 * one. The light comes from the upper left; draw the sprite mirrored for a sun on the right. Sizes
 * are pixels; [dpW] and [dpH] what they come to in dp.
 */
internal class CloudSprite(
    val w: Int,
    val h: Int,
    val dpW: Float,
    val dpH: Float,
    val shade: IntArray,
    val mid: IntArray,
    val lit: IntArray,
    val under: IntArray,
)

/**
 * What one sprite of the pool is: its [kind], its pixel size, and the seed its noise grows from.
 */
internal class CloudSpec(
    val kind: CloudKind,
    val w: Int,
    val h: Int,
    val seed: Int,
    val sharpen: Float = 1f,
) {
    val dpW: Float
        get() = w / CloudSprites.PX_PER_DP

    val dpH: Float
        get() = h / CloudSprites.PX_PER_DP
}

/**
 * The cloud sprite generator. A cloud is a density field (soft metaballs for the heaps, fractal
 * noise eroding their edges, a warp so no heap is round) lit by marching a few steps towards the
 * sun: where little cloud stands between a point and the sun it is bright, where a lot does it is
 * shaded, and the belly, away from the sky's light, is cool. Edges are feathered by the field
 * itself, so there is not a line or a circle anywhere in it. Everything is a function of the spec's
 * seed.
 */
internal object CloudSprites {
    /** Pixels in a dp: the sprites are made at twice the size they are drawn at on a 1x screen. */
    const val PX_PER_DP = 2f

    /** The pool: two cumulus, two stratus, two fair-weather puffs, two cirrus. */
    val SPECS: List<CloudSpec> =
        listOf(
            CloudSpec(CloudKind.CUMULUS, 288, 192, 11),
            CloudSpec(CloudKind.CUMULUS, 256, 176, 29),
            CloudSpec(CloudKind.STRATUS, 320, 80, 47),
            CloudSpec(CloudKind.STRATUS, 288, 68, 83),
            CloudSpec(CloudKind.PUFF, 144, 96, 5),
            CloudSpec(CloudKind.PUFF, 128, 88, 71),
            CloudSpec(CloudKind.CIRRUS, 320, 96, 23),
            CloudSpec(CloudKind.CIRRUS, 288, 80, 61),
        )

    private val BY_KIND: Array<IntArray> =
        Array(CloudKind.entries.size) { k ->
            SPECS.indices.filter { SPECS[it].kind.ordinal == k }.toIntArray()
        }

    /** The sprites of a [kind], by their index in [SPECS] (kept, so asking allocates nothing). */
    fun of(kind: CloudKind): IntArray = BY_KIND[kind.ordinal]

    /** Bytes the whole pool holds (four ARGB layers each). */
    fun bytes(): Int = SPECS.sumOf { it.w * it.h * 4 * 4 }

    // the sun's direction in the picture (up and to the left), and the light the sky gives
    // everywhere
    private const val SUN_X = -.55f
    private const val SUN_Y = -.83f

    fun generate(index: Int): CloudSprite {
        val spec = SPECS[index]
        val w = spec.w
        val h = spec.h
        val dens = FloatArray(w * h)
        when (spec.kind) {
            CloudKind.CUMULUS -> heap(dens, spec, 1f)
            CloudKind.PUFF -> heap(dens, spec, .62f)
            CloudKind.STRATUS -> band(dens, spec)
            CloudKind.CIRRUS -> wisp(dens, spec)
        }
        if (spec.kind == CloudKind.CUMULUS || spec.kind == CloudKind.PUFF) {
            fillHoles(dens, spec.w, spec.h)
            blur(dens, spec.w, spec.h)
            blur(dens, spec.w, spec.h)
            if (spec.kind == CloudKind.PUFF) blur(dens, spec.w, spec.h)
        } else if (spec.kind == CloudKind.STRATUS) {
            blur(dens, spec.w, spec.h)
            blur(dens, spec.w, spec.h)
        }
        return light(dens, spec)
    }

    // ---- the density fields

    private fun kernel(r2: Float): Float {
        if (r2 >= 1f) return 0f
        val k = 1f - r2
        return k * k * k
    }

    /**
     * A cumulus: a flat-based row of heaps with a pile of them on it, warped and eroded by noise.
     */
    private fun heap(dens: FloatArray, spec: CloudSpec, size: Float) {
        val w = spec.w
        val h = spec.h
        val seed = spec.seed
        fun j(k: Int) = Ambient.hash(seed, k, 201) - .5f
        val baseY = h * .86f
        // the metaballs: across, up from the base (shares), radius across, radius up, weight
        val big = size > .8f
        val row = if (big) 5 else 3
        val mid = if (big) 6 else 3
        val crown = if (big) 6 else 3
        val n = row + mid + crown
        val bx = FloatArray(n)
        val by = FloatArray(n)
        val brx = FloatArray(n)
        val bry = FloatArray(n)
        val bw = FloatArray(n)
        var i = 0
        for (k in 0 until row) {
            val t = (k + .5f) / row
            bx[i] = w * (.16f + .68f * t) + j(k) * w * .05f
            by[i] = baseY - h * (.11f + .05f * j(k + 20))
            brx[i] = w * (.17f + .04f * j(k + 40)) * (if (big) 1f else .9f)
            bry[i] = h * (.15f + .03f * j(k + 60))
            bw[i] = 1f
            i++
        }
        for (k in 0 until mid) {
            val t = (k + .5f) / mid
            bx[i] = w * (.22f + .56f * t) + j(k + 80) * w * .07f
            by[i] = baseY - h * (.3f + .1f * j(k + 100))
            brx[i] = w * (.12f + .035f * j(k + 120))
            bry[i] = h * (.15f + .04f * j(k + 140))
            bw[i] = 1.05f
            i++
        }
        for (k in 0 until crown) {
            val t = (k + .5f) / crown
            // the crowns rise towards the middle, a little off-centre: a heap, not a row
            val up = 1f - kotlin.math.abs(t - (.5f + .12f * j(300))) * 1.5f
            bx[i] = w * (.28f + .44f * t) + j(k + 160) * w * .06f
            by[i] = baseY - h * (.45f + .2f * up.coerceAtLeast(0f) + .06f * j(k + 180))
            brx[i] = w * (.085f + .03f * j(k + 200))
            bry[i] = h * (.12f + .035f * j(k + 210))
            bw[i] = 1.1f
            i++
        }
        val fs = if (size > .8f) 1f else 1.5f
        for (y in 0 until h) {
            for (x in 0 until w) {
                // a warp of the place the field is read at: no heap is a circle
                val lump = Noise.billow(x * .034f * fs, y * .034f * fs, seed + 1, 3)
                val lump2 = Noise.billow(x * .034f * fs + 5f, y * .034f * fs + 8f, seed + 2, 3)
                val wx = x + (lump - .6f) * 56f * size
                val wyRaw = y + (lump2 - .6f) * 48f * size
                val wy = wyRaw + (y - wyRaw) * Px.smooth(baseY - h * .3f, baseY, y.toFloat())
                var f = 0f
                for (b in 0 until n) {
                    val dx = (wx - bx[b]) / brx[b]
                    val dy = (wy - by[b]) / bry[b]
                    f += bw[b] * kernel(dx * dx + dy * dy)
                }
                // the body of the cloud: a broad dome on a flat base, heaps piled on it
                val ex = (wx - w * .5f) / (w * .44f)
                val ey =
                    if (wy < baseY) (baseY - wy) / (h * .66f * size.coerceAtLeast(.8f))
                    else (wy - baseY) / (h * .07f)
                val dome = Px.clamp01(1f - sqrt(ex * ex + ey * ey))
                f = dome * 1.15f + f * .5f * Px.smooth(.05f, .4f, dome)
                // cauliflower: billows at two scales raise and eat the edge into heaps
                val coarse = Noise.billow(x * .045f * fs, y * .045f * fs, seed + 3, 3)
                val fine = Noise.fbm(x * .12f * fs, y * .12f * fs, seed + 4, 3)
                val edge = Px.smooth(0f, .2f, f) * (1f - Px.smooth(.45f, .95f, f))
                f += ((coarse - .55f) * .3f + (fine - .5f) * .18f) * edge * (if (big) 1f else .38f)
                // a flat base, soft under it
                f *= Px.smooth(baseY + 7f, baseY - 12f, y.toFloat())
                dens[y * w + x] = max(0f, f - .2f) * 1.5f
            }
        }
    }

    /** A stratus: long, low, streaky, fading at both ends. */
    private fun band(dens: FloatArray, spec: CloudSpec) {
        val w = spec.w
        val h = spec.h
        val seed = spec.seed
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ex = (x - w * .5f) / (w * .48f)
                val ey = (y - h * .56f) / (h * .34f)
                val core = 1f - sqrt(ex * ex + ey * ey)
                val warp = (Noise.fbm(x / 90f, y / 30f, seed + 1, 3) - .5f) * 14f
                val n = Noise.fbm(x / 64f, (y + warp) / 12f, seed + 2, 4)
                val streak = Noise.fbm(x / 26f, y / 5f, seed + 3, 3)
                val f = core * 1.25f + (n - .5f) * .95f + (streak - .5f) * .16f
                dens[y * w + x] = max(0f, f - .08f) * 1.6f
            }
        }
    }

    /** A cirrus: thin, combed streaks on a slight curve, nowhere dense. */
    private fun wisp(dens: FloatArray, spec: CloudSpec) {
        val w = spec.w
        val h = spec.h
        val seed = spec.seed
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ex = (x - w * .5f) / (w * .5f)
                val ey = (y - h * .5f - ex * ex * h * .12f) / (h * .42f)
                val mask = Px.smooth(1f, .1f, sqrt(ex * ex + ey * ey))
                val curve = (Noise.fbm(x / 120f, y / 40f, seed + 1, 3) - .5f) * 30f
                val n = Noise.fbm(x / 70f, (y + curve) / 7f, seed + 2, 5, .55f)
                val fine = Noise.fbm(x / 22f, (y + curve) / 2.4f, seed + 3, 3)
                val f = (n * .75f + fine * .25f)
                dens[y * w + x] = Px.smooth(.42f, .78f, f) * mask * .55f
            }
        }
    }

    /**
     * Fills the pockets of clear sky a cloud has closed round (the noise can fold one in): whatever
     * thin field cannot be reached from the border without crossing cloud becomes thin cloud.
     */
    private fun fillHoles(d: FloatArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var n = 0
        fun push(i: Int) {
            if (!outside[i] && d[i] < .12f) {
                outside[i] = true
                stack[n++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (n > 0) {
            val i = stack[--n]
            val x = i % w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (i >= w) push(i - w)
            if (i < w * (h - 1)) push(i + w)
        }
        for (i in d.indices) if (!outside[i] && d[i] < .12f) d[i] = .12f + (.12f - d[i])
    }

    /** A light blur of the field, so no thread of cloud hangs on its own. */
    private fun blur(d: FloatArray, w: Int, h: Int) {
        val c = d.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            d[i] =
                (c[i] * 4f +
                    c[i - 1] * 2f +
                    c[i + 1] * 2f +
                    c[i - w] * 2f +
                    c[i + w] * 2f +
                    c[i - w - 1] +
                    c[i - w + 1] +
                    c[i + w - 1] +
                    c[i + w + 1]) / 16f
        }
    }

    // ---- the light

    private fun sample(d: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        if (x < 0f || y < 0f || x >= w - 1f || y >= h - 1f) return 0f
        val ix = x.toInt()
        val iy = y.toInt()
        val fx = x - ix
        val fy = y - iy
        val a = d[iy * w + ix]
        val b = d[iy * w + ix + 1]
        val c = d[(iy + 1) * w + ix]
        val e = d[(iy + 1) * w + ix + 1]
        return (a + (b - a) * fx) * (1f - fy) + (c + (e - c) * fx) * fy
    }

    private fun light(dens: FloatArray, spec: CloudSpec): CloudSprite {
        val w = spec.w
        val h = spec.h
        val seed = spec.seed
        val thin = spec.kind == CloudKind.CIRRUS
        val steps = if (spec.kind == CloudKind.STRATUS) 5 else 8
        val stride = (if (spec.kind == CloudKind.STRATUS) 3f else h * .04f)
        val shade = IntArray(w * h)
        val mid = IntArray(w * h)
        val lit = IntArray(w * h)
        val under = IntArray(w * h)
        // the extent of the cloud, to tell its crowns from its belly
        var top = h
        var bottom = 0
        for (y in 0 until h) for (x in 0 until w) if (dens[y * w + x] > .08f) {
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        val span = max(1, bottom - top).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = dens[y * w + x]
                if (d <= 0.003f) continue
                // how much cloud lies between this point and the sun
                var acc = 0f
                for (s in 1..steps) acc +=
                    sample(dens, w, h, x + SUN_X * stride * s, y + SUN_Y * stride * s)
                val trans = exp(-acc * (if (thin) .5f else .5f) * stride / 10f)
                val height = 1f - (y - top) / span
                var l = .2f + .8f * trans
                // a short look towards the sun: the edge that faces it is bright
                l += (d - sample(dens, w, h, x + SUN_X * 5f, y + SUN_Y * 5f)) * -.55f
                // the belly, away from the sky's light, falls into shade
                l *= .66f + .34f * Px.smooth(.0f, .55f, height)
                l += (Noise.fbm(x * .16f, y * .16f, seed + 9, 3) - .5f) * .16f
                // the creases between the heaps are in shade, the heaps' crowns catch the light
                if (!thin && spec.kind != CloudKind.STRATUS)
                    l += (Noise.fbm(x * .05f, y * .05f, seed + 6, 3) - .5f) * .7f
                // a cloud's own thickness darkens its core a little (not its rims)
                l -= Px.smooth(.9f, 2.4f, d) * .1f
                val alpha0 =
                    if (thin) Px.clamp01(d)
                    else
                        Px.smooth(
                            0f,
                            .42f + .3f * (1f - Px.smooth(.1f, .6f, height)),
                            d * spec.sharpen,
                        )
                val alpha =
                    alpha0 *
                        Px.smooth(
                            0f,
                            10f,
                            minOf(minOf(x, w - 1 - x), minOf(y, h - 1 - y)).toFloat(),
                        )
                if (alpha <= 0f) continue
                val i = y * w + x
                shade[i] = Px.argbF(alpha, 1f, 1f, 1f)
                val m = Px.smooth(.12f, .6f, l)
                val t = Px.smooth(.52f, .94f, l)
                mid[i] = Px.argbF(alpha * m, 1f, 1f, 1f)
                lit[i] = Px.argbF(alpha * t * (if (thin) .8f else .96f), 1f, 1f, 1f)
                // lit from below: the lower part of the cloud, most where there is little cloud
                // under the point (a short look down), in uneven patches along the base
                val below = sample(dens, w, h, x.toFloat(), y + 6f)
                val low = Px.smooth(.25f, 1f, 1f - height)
                val thinUnder = Px.smooth(1.1f, .1f, below)
                val patch = .65f + .35f * Noise.fbm(x * .07f, y * .07f, seed + 12, 3)
                val u = low * (.45f + .55f * thinUnder) * patch * (if (thin) .7f else 1f)
                under[i] = Px.argbF(alpha * Px.clamp01(u), 1f, 1f, 1f)
            }
        }
        return CloudSprite(w, h, spec.dpW, spec.dpH, shade, mid, lit, under)
    }
}

/**
 * Where the clouds hang, as a pure function so it can be tested: the lanes of the sky ([lane] is a
 * share of the sky's band from [Ambient.CLOUD_LANES]) are spread between the [top] of the sky and
 * the [ceiling], the roof of the tallest building in view (and the "add a floor" ghost standing on
 * it), less a margin, so no cloud ever hangs where a hint is drawn. All in pixels.
 */
internal object CloudLayout {
    private const val MARGIN_DP = 14f
    private const val ROOM_DP = 90f

    /** The lowest y a cloud's body reaches. */
    fun floor(horizon: Float, ceiling: Float, density: Float): Float =
        min(ceiling, horizon) - MARGIN_DP * density

    /** The y of the foot of a cloud in [lane]. */
    fun base(lane: Float, top: Float, horizon: Float, ceiling: Float, density: Float): Float {
        val span = max(floor(horizon, ceiling, density) - top, 1f)
        return top + span * (lane / (Ambient.CLOUD_LANES.last() + .04f))
    }

    /** 1 with room for a sky, fading to 0 as the building reaches the top. */
    fun room(top: Float, horizon: Float, ceiling: Float, density: Float): Float =
        Px.smooth(0f, 1f, (floor(horizon, ceiling, density) - top) / (ROOM_DP * density))
}
