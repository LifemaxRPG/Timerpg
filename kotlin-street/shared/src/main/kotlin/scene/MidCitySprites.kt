package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The city between the street and the far skyline, baked once: three periodic strips that stand on the ground
 * between the horizon and the back of the street, each nearer than the last. Pure Kotlin, deterministic, made
 * once for the process and drawn as images; the hour, the weather and the season are put on at draw time by
 * colour filters ([MidCity]), so nothing is baked again as the day turns.
 *
 * Every strip is [MidCitySprites.SPAN] dp long and periodic. Its places are in dp: x along the span, y DOWN from
 * the horizon at full size (the stage's horizon stands [MidCitySprites.BAND] dp over the pavement line; a
 * smaller street scales the whole band down).
 */

/**
 * One baked strip: [w] by [h] pixels at [ppd] pixels a dp, its top row [topDp] dp under the horizon
 * (negative: over it). A body strip is the things' own colours (ARGB, by a flat white light); a
 * channel strip is opaque, three of the layer's channels in its red, green and blue
 * ([MidCitySprites.WIN] and on).
 */
internal class MidStrip(
    val w: Int,
    val h: Int,
    val ppd: Float,
    val topDp: Float,
    val pixels: IntArray,
) {
    val bytes: Int
        get() = w * h * 4
}

/**
 * A layer baked: its [body], its [windows] (lit coverage, switch-on order, warm-to-cool kind), its
 * [glow] (lamps and their pools, signs, signals) and its [masks] (foliage, snow, the tops that
 * catch a low sun); the far layer has no glow.
 */
internal class MidLayer(
    val body: MidStrip,
    val windows: MidStrip?,
    val glow: MidStrip?,
    val masks: MidStrip,
)

internal object MidCitySprites {
    /** The strips' period, dp. */
    const val SPAN = 640f

    /** How far the street's pavement line stands under the horizon at full size, dp. */
    const val BAND = 30f

    // the channels a layer paints besides its colour: a window's coverage, its switch-on order
    // and its kind (warm to cool); a lamp's light and pool, a lit sign, a traffic signal; the
    // foliage (its value the leaf's shade), snow, the tops a low sun catches
    const val WIN = 0
    const val ORDER = 1
    const val KIND = 2
    const val LAMP = 3
    const val SIGN = 4
    const val SIGNAL = 5
    const val LEAF = 6
    const val SNOW = 7
    const val TOP = 8
    private const val CHANNELS = 9

    /**
     * A layer: its strip from [top] to [bottom] (dp under the horizon) at [ppd], its glow and masks
     * baked [glowStep] and [maskStep] times coarser, whether it has windows and a glow, its [foot]
     * (dp under the horizon: what it stands on, for its parallax), and how much of the horizon's
     * air it is seen through by day ([haze]).
     */
    class Spec(
        val top: Float,
        val bottom: Float,
        val ppd: Float,
        val glowStep: Int,
        val maskStep: Int,
        val windows: Boolean,
        val glow: Boolean,
        val foot: Float,
        val haze: Float,
    )

    const val FAR = 0
    const val BLOCKS = 1
    const val KERB = 2

    /**
     * The district, the blocks across the boulevard (with the boulevard), the near kerb: far first.
     */
    val LAYERS: Array<Spec> =
        arrayOf(
            Spec(-34f, 6.5f, 1.25f, 1, 2, true, false, 4.5f, .46f),
            Spec(-27f, 21.6f, 1.75f, 2, 2, true, true, 13f, .22f),
            Spec(7f, 31f, 2.5f, 2, 2, false, true, 22.6f, .08f),
        )

    // the boulevard, dp under the horizon at full size
    /** The district's buildings stand here. */
    const val FAR_FOOT = 4.5f

    /** The elevated railway's deck stands this high over the district's feet. */
    const val VIADUCT = 9f

    /** A carriage of its train: long, tall, and the gap between two, dp. */
    const val CARRIAGE = 8.2f
    const val CARRIAGE_H = 2.3f
    const val CARRIAGE_GAP = .45f

    /** The blocks' fronts stand here, on the far pavement. */
    const val BLOCK_FOOT = 12.6f

    /** The far pavement's walkers walk here. */
    const val FAR_WALK = 13.5f
    const val FAR_KERB = 14f
    const val ROAD_TOP = 14.3f

    /** The lanes the passing cars drive in: the far one going left, the near one going right. */
    const val LANE_LEFT = 17f
    const val LANE_RIGHT = 19.6f
    const val ROAD_BOTTOM = 21f

    /** The near pavement's walkers walk here. */
    const val NEAR_WALK = 23.4f

    /** How tall a walker is on the far and the near pavement, dp. */
    const val FAR_PERSON = 1.9f
    const val NEAR_PERSON = 3.1f

    private val laid: Layout by lazy { layout() }

    /** The steam vents on the blocks' roofs: (x, y) in dp, two numbers each. */
    val vents: FloatArray
        get() = laid.vents

    /**
     * The signal heads at the side streets: (x, y) in dp of each head's top, two numbers each; its
     * three lenses, red over amber over green, are .7 dp apart under it.
     */
    val signals: FloatArray
        get() = laid.signals

    /** Bytes the three layers hold. */
    fun bytes(): Int {
        var n = 0
        for (s in LAYERS) {
            val w = (SPAN * s.ppd).roundToInt()
            val h = ((s.bottom - s.top) * s.ppd).roundToInt()
            n += w * h * 4
            if (s.windows) n += w * h * 4
            if (s.glow) n += (w / s.glowStep) * ceilDiv(h, s.glowStep) * 4
            n += (w / s.maskStep) * ceilDiv(h, s.maskStep) * 4
        }
        return n
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** Layer [l], baked. */
    fun generate(l: Int): MidLayer {
        val s = LAYERS[l]
        val r = Raster(s)
        when (l) {
            FAR -> far(r)
            BLOCKS -> blocks(r)
            else -> kerb(r)
        }
        r.texture(l * 101 + 7)
        return MidLayer(
            r.body(),
            if (s.windows) r.channels(WIN, ORDER, KIND, 1) else null,
            if (s.glow) r.channels(LAMP, SIGN, SIGNAL, s.glowStep) else null,
            r.channels(LEAF, SNOW, TOP, s.maskStep),
        )
    }

    // ------------------------------------------------------------------ the raster

    /**
     * A layer's planes: its colour (premultiplied) and its channels, at the spec's resolution.
     * Every paint covers what is under it (a window behind a lamp post loses its light where the
     * post is); x wraps round the span.
     */
    private class Raster(val s: Spec) {
        val ppd = s.ppd
        val w = (SPAN * ppd).roundToInt()
        val h = ((s.bottom - s.top) * ppd).roundToInt()
        val cr = FloatArray(w * h)
        val cg = FloatArray(w * h)
        val cb = FloatArray(w * h)
        val ca = FloatArray(w * h)
        val ch = Array(CHANNELS) { FloatArray(w * h) }

        // the paint of the moment
        private var pr = 0f
        private var pg = 0f
        private var pb = 0f
        private var pa = 1f
        private var body = true
        private var erase = 1f
        private var c1 = -1
        private var v1 = 0f
        private var c2 = -1
        private var v2 = 0f
        private var glow = false

        /**
         * Sets the paint: colour [col] (RGB, or -1 for none) at [alpha]; channel [a] written [va],
         * channel [b] written [vb]; [glow] writes the channels as the brighter of old and new
         * (lights add up) instead of over; [erase] how much of the other channels under it it
         * covers.
         */
        fun ink(
            col: Int,
            alpha: Float = 1f,
            a: Int = -1,
            va: Float = 1f,
            b: Int = -1,
            vb: Float = 1f,
            glow: Boolean = false,
            erase: Float = if (col == -1) 0f else 1f,
        ): Raster {
            body = col != -1
            if (body) {
                pr = ((col shr 16) and 255) / 255f
                pg = ((col shr 8) and 255) / 255f
                pb = (col and 255) / 255f
            }
            pa = alpha
            c1 = a
            v1 = va
            c2 = b
            v2 = vb
            this.glow = glow
            this.erase = erase
            return this
        }

        private fun put(i: Int, c: Float, shade: Float) {
            if (c <= 0f) return
            val k = c * pa
            if (body) {
                val q = 1f - k
                cr[i] = cr[i] * q + pr * k * shade
                cg[i] = cg[i] * q + pg * k * shade
                cb[i] = cb[i] * q + pb * k * shade
                ca[i] = ca[i] * q + k
            }
            val e = 1f - k * erase
            for (j in 0 until CHANNELS) {
                if (j == c1 || j == c2) continue
                if (e < 1f) ch[j][i] *= e
            }
            if (c1 >= 0) write(ch[c1], i, k, v1 * (if (body) 1f else shade))
            if (c2 >= 0) write(ch[c2], i, k, v2)
        }

        private fun write(p: FloatArray, i: Int, k: Float, v: Float) {
            p[i] = if (glow) max(p[i], v * k) else p[i] * (1f - k) + v * k
        }

        /** A box, its edges anti-aliased exactly. */
        fun rect(x0: Float, x1: Float, y0: Float, y1: Float) {
            if (x1 <= x0 || y1 <= y0) return
            val fx0 = x0 * ppd
            val fx1 = x1 * ppd
            val fy0 = (y0 - s.top) * ppd
            val fy1 = (y1 - s.top) * ppd
            val px0 = floor(fx0).toInt()
            val px1 = ceil(fx1).toInt() - 1
            val py0 = max(0, floor(fy0).toInt())
            val py1 = min(h - 1, ceil(fy1).toInt() - 1)
            for (py in py0..py1) {
                val cy = min(py + 1f, fy1) - max(py.toFloat(), fy0)
                if (cy <= 0f) continue
                for (px in px0..px1) {
                    val cx = min(px + 1f, fx1) - max(px.toFloat(), fx0)
                    if (cx <= 0f) continue
                    put(py * w + Math.floorMod(px, w), min(1f, cx * cy), 1f)
                }
            }
        }

        /**
         * Where [inside] holds (x, y in dp) within the box, nine samples a pixel; [shade] darkens
         * the colour (or scales the first channel when there is no colour) at each pixel's middle.
         */
        inline fun fill(
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            shade: (Float, Float) -> Float = { _, _ -> 1f },
            inside: (Float, Float) -> Boolean,
        ) {
            val px0 = floor(x0 * ppd).toInt()
            val px1 = ceil(x1 * ppd).toInt()
            val py0 = max(0, floor((y0 - s.top) * ppd).toInt())
            val py1 = min(h - 1, ceil((y1 - s.top) * ppd).toInt())
            for (py in py0..py1) for (px in px0..px1) {
                var n = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val xd = (px + (sx + .5f) / 3f) / ppd
                    val yd = s.top + (py + (sy + .5f) / 3f) / ppd
                    if (inside(xd, yd)) n++
                }
                if (n == 0) continue
                val xm = (px + .5f) / ppd
                val ym = s.top + (py + .5f) / ppd
                put(py * w + Math.floorMod(px, w), n / 9f, shade(xm, ym))
            }
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
            fill(cx - rx, cx + rx, cy - ry, cy + ry) { x, y ->
                val u = (x - cx) / rx
                val v = (y - cy) / ry
                u * u + v * v <= 1f
            }

        /** A soft spot of light in the channels only: brightest at its middle. */
        fun spot(cx: Float, cy: Float, rx: Float, ry: Float, peak: Float, channel: Int) {
            val px0 = floor((cx - rx) * ppd).toInt()
            val px1 = ceil((cx + rx) * ppd).toInt()
            val py0 = max(0, floor((cy - ry - s.top) * ppd).toInt())
            val py1 = min(h - 1, ceil((cy + ry - s.top) * ppd).toInt())
            val p = ch[channel]
            for (py in py0..py1) for (px in px0..px1) {
                val u = ((px + .5f) / ppd - cx) / rx
                val v = (s.top + (py + .5f) / ppd - cy) / ry
                val d = u * u + v * v
                if (d >= 1f) continue
                val f = (1f - d)
                val i = py * w + Math.floorMod(px, w)
                p[i] = max(p[i], peak * f * f)
            }
        }

        /** A line [t] dp thick from one point to another. */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, t: Float) {
            val dx = x1 - x0
            val dy = y1 - y0
            val len2 = dx * dx + dy * dy
            if (len2 <= 0f) return
            fill(min(x0, x1) - t, max(x0, x1) + t, min(y0, y1) - t, max(y0, y1) + t) { x, y ->
                val u = (((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0f, 1f)
                val ex = x - (x0 + u * dx)
                val ey = y - (y0 + u * dy)
                ex * ex + ey * ey <= t * t / 4f
            }
        }

        /** A faint grain over the colour, so no wall is a flat field. */
        fun texture(seed: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (ca[i] <= 0f) continue
                // periodic along the span: the noise is read round a circle of the span's length
                val t = x / w.toFloat()
                val n0 = Noise.value(x * .9f, y * .9f, seed)
                val n1 = Noise.value((x - w) * .9f, y * .9f, seed)
                val n = n0 + (n1 - n0) * t - .5f
                val f = 1f + .09f * n
                cr[i] = min(ca[i], cr[i] * f)
                cg[i] = min(ca[i], cg[i] * f)
                cb[i] = min(ca[i], cb[i] * f)
            }
        }

        fun body(): MidStrip {
            val px =
                IntArray(w * h) {
                    val a = ca[it]
                    if (a <= .002f) 0 else Px.argbF(a, cr[it] / a, cg[it] / a, cb[it] / a)
                }
            return MidStrip(w, h, ppd, s.top, px)
        }

        /** Channels [a], [b], [c] in red, green and blue, [step] times coarser (box-filtered). */
        fun channels(a: Int, b: Int, c: Int, step: Int): MidStrip {
            val ow = w / step
            val oh = (h + step - 1) / step
            val out = IntArray(ow * oh)
            val k = arrayOf(ch[a], ch[b], ch[c])
            for (y in 0 until oh) for (x in 0 until ow) {
                val v = FloatArray(3)
                var n = 0
                for (sy in 0 until step) {
                    val yy = y * step + sy
                    if (yy >= h) continue
                    for (sx in 0 until step) {
                        val i = yy * w + x * step + sx
                        for (q in 0 until 3) v[q] += k[q][i]
                        n++
                    }
                }
                out[y * ow + x] = Px.argbF(1f, v[0] / n, v[1] / n, v[2] / n)
            }
            return MidStrip(ow, oh, ppd / step, s.top, out)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hash(a: Int, b: Int, c: Int) = Ambient.hash(a, b, c)

    private fun pick(h: Float, vararg c: Int): Int = c[(h * c.size).toInt().coerceIn(0, c.size - 1)]

    /** [c] darkened (f < 1) or lightened toward white (f > 1). */
    private fun tone(c: Int, f: Float): Int {
        fun ch(v: Int) =
            (if (f <= 1f) v * f else v + (255 - v) * (f - 1f)).roundToInt().coerceIn(0, 255)
        return (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255)
    }

    private fun mixc(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) =
            ((((a shr s) and 255) * (1f - t) + ((b shr s) and 255) * t).roundToInt()) shl s
        return ch(16) or ch(8) or ch(0)
    }

    // the materials' own colours
    private val BRICK = intArrayOf(0x9A4E3A, 0xA65C43, 0x8A4434, 0xB2704F, 0x7E4A3C)
    private val BROWNSTONE = intArrayOf(0x7A5240, 0x6B4636, 0x8A6048, 0x5E4034)
    private val STONE = intArrayOf(0xCDBC9E, 0xDAD0BA, 0xBDAA8C, 0xC8C2B4)
    private val CONCRETE = intArrayOf(0xA9ABA8, 0x8E9395, 0xB9B5AB, 0x9C9A92)
    private val PAINT = intArrayOf(0xC99A62, 0x8FA6A0, 0xD8C8A6, 0x9AA5B8, 0xC27C5E)
    private val CARS =
        intArrayOf(0xB22A26, 0xD8DCE0, 0x2A4E80, 0x2B2D33, 0x8C9096, 0xE4E2DA, 0x3E6B48, 0xC9A23A)
    private const val GLASS = 0x46566A
    private const val GLASS_HI = 0x7D93AA
    private const val ROOF = 0x4A4B52
    private const val TAR = 0x3A3B41
    private const val TRIM = 0xE2DACA
    private const val IRON = 0x2C3036
    private const val TRUNK = 0x4B3A2E
    private const val PAVING = 0xB8B5AE
    private const val KERBSTONE = 0xD4D1C9
    private const val ASPHALT = 0x575A61
    private const val LAWN = 0x6E8A4E
    private const val HEDGE = 0x3D5C36

    /** What the bake leaves for the drawing: where the steam vents and the signals are. */
    private class Layout(val vents: FloatArray, val signals: FloatArray)

    private fun layout(): Layout {
        val v = ArrayList<Float>()
        val s = ArrayList<Float>()
        for (p in parcels()) {
            if (p.kind == Kind.WAREHOUSE || p.kind == Kind.APARTMENT) {
                if (hash(p.seed, 0, 60) < .55f) {
                    v.add(p.x + p.w * (.15f + .2f * hash(p.seed, 1, 60)))
                    v.add(BLOCK_FOOT - p.h - 1.6f)
                }
            }
            if (p.kind == Kind.CROSSING) {
                val sx = p.x + p.w - .3f
                s.add(sx - 3.6f)
                s.add(BLOCK_FOOT - 7.25f)
                s.add(sx)
                s.add(BLOCK_FOOT - 5.6f)
            }
        }
        return Layout(v.toFloatArray(), s.toFloatArray())
    }

    /** A noise along the span that comes round seamlessly at its end. */
    private fun around(x: Float, y: Float, scale: Float, seed: Int, octaves: Int): Float {
        val t = (((x % SPAN) + SPAN) % SPAN) / SPAN
        val xs = t * SPAN * scale
        val a = Noise.fbm(xs, y, seed, octaves)
        val b = Noise.fbm(xs - SPAN * scale, y, seed, octaves)
        return a + (b - a) * t
    }

    // ------------------------------------------------------------------ the district

    private fun far(r: Raster) {
        // the mid-rises, standing on [FAR_FOOT]
        var x = 0f
        var i = 0
        val spires = intArrayOf(9, 31)
        while (x < SPAN - 1f) {
            var bw = 8f + 20f * hash(i, 1, 101)
            if (SPAN - (x + bw) < 8f) bw = SPAN - x
            val tall = hash(i, 2, 101)
            val bh = 9f + 21f * tall * tall + (if (hash(i, 3, 101) < .15f) 6f else 0f)
            val gap = if (hash(i, 4, 101) < .18f) 1f + 2f * hash(i, 5, 101) else 0f
            farBuilding(
                r,
                i,
                x + gap / 2f,
                x + bw - gap / 2f,
                if (i in spires) 10f + 4f * tall else bh,
                i in spires,
            )
            x += bw
            i++
        }
        // the factory's chimney, once
        chimney(r, 402f, FAR_FOOT, 31f)
        // the elevated railway on its girders, crossing the whole district
        val deck = FAR_FOOT - VIADUCT
        var px = 5f
        while (px < SPAN) {
            r.ink(0x464A50).rect(px, px + .8f, deck + 1.2f, FAR_FOOT + .6f)
            r.ink(0x464A50).fill(px - 1.6f, px + 2.4f, deck + 1.1f, deck + 2.6f) { x, y ->
                val t = (y - deck - 1.1f) / 1.5f
                x > px - 1.6f * (1f - t) && x < px + .8f + 1.6f * (1f - t)
            }
            px += 16f
        }
        r.ink(0x50545A).rect(0f, SPAN, deck, deck + 1.25f)
        r.ink(0x3A3D42, alpha = .8f).rect(0f, SPAN, deck + .95f, deck + 1.25f)
        r.ink(0x8A8E94, a = TOP).rect(0f, SPAN, deck - .3f, deck)
        r.ink(-1, a = SNOW).rect(0f, SPAN, deck - .4f, deck)
        var gx = 0f
        while (gx < SPAN) {
            r.ink(0x6A6E74, alpha = .7f, erase = 0f).rect(gx, gx + .25f, deck + .15f, deck + .9f)
            gx += 1.6f
        }
        // the low roofs in front of their feet: sheds, garages, two-storey rows, on solid ground
        r.ink(0x6A6862).rect(0f, SPAN, FAR_FOOT + .5f, 7f)
        x = 0f
        i = 0
        while (x < SPAN - .5f) {
            var bw = 3f + 6f * hash(i, 1, 131)
            if (SPAN - (x + bw) < 3f) bw = SPAN - x
            val foot = 6.6f
            val top = foot - 2f - 3.2f * hash(i, 2, 131)
            val col =
                tone(pick(hash(i, 3, 131), *BRICK, *CONCRETE, *STONE), .82f + .2f * hash(i, 4, 131))
            r.ink(col).rect(x, x + bw, top, foot)
            r.ink(tone(ROOF, 1.25f), a = TOP, va = .7f).rect(x - .2f, x + bw + .2f, top - .4f, top)
            r.ink(-1, a = SNOW).rect(x - .2f, x + bw + .2f, top - .5f, top)
            // a row of small windows
            if (hash(i, 5, 131) < .7f) {
                var wx = x + .8f
                var k = 0
                while (wx + .9f < x + bw - .5f) {
                    r.ink(GLASS, a = WIN, b = ORDER, vb = hash(i * 31 + k, 6, 131))
                        .rect(wx, wx + .8f, top + .8f, top + 1.9f)
                    wx += 1.8f
                    k++
                }
            }
            x += bw
            i++
        }
    }

    private fun farBuilding(r: Raster, i: Int, x0: Float, x1: Float, bh: Float, spire: Boolean) {
        val foot = FAR_FOOT
        val top = foot - bh
        val bw = x1 - x0
        val col =
            tone(
                pick(hash(i, 6, 101), *BRICK, *STONE, *CONCRETE, *PAINT, *BRICK),
                .85f + .25f * hash(i, 7, 101),
            )
        r.ink(col).rect(x0, x1, top, foot)
        // its shaded side: the edge away from the viewer's middle reads as a thin darker face
        r.ink(tone(col, .78f)).rect(x1 - .6f, x1, top, foot)
        // a parapet, a lit top
        r.ink(tone(col, 1.12f), a = TOP).rect(x0 - .3f, x1 + .3f, top - .6f, top)
        r.ink(-1, a = SNOW).rect(x0 - .3f, x1 + .3f, top - .8f, top - .2f)
        // the windows: a grid of small panes, some of them lit at night
        val pitch = 1.9f + .5f * hash(i, 8, 101)
        val cols = ((bw - 1.2f) / pitch).toInt()
        val margin = (bw - cols * pitch) / 2f
        val floors = ((bh - 2f) / 2.5f).toInt()
        val warm = hash(i, 9, 101)
        for (f in 0 until floors) {
            val wy = foot - 1.7f - f * 2.5f
            for (c in 0 until cols) {
                val wx = x0 + margin + c * pitch + (pitch - .95f) / 2f
                val o = hash(i * 97 + c, f, 102)
                val kind = if (hash(i * 97 + c, f, 103) < .25f + .5f * warm) .1f else .85f
                r.ink(GLASS, a = WIN, b = ORDER, vb = o).rect(wx, wx + .95f, wy - 1.25f, wy)
                r.ink(-1, a = KIND, va = kind, erase = 0f).rect(wx, wx + .95f, wy - 1.25f, wy)
            }
        }
        // what stands on the roof
        val h = hash(i, 10, 101)
        if (spire) {
            val cx = (x0 + x1) / 2f
            val sh = 10f + 4f * hash(i, 11, 101)
            r.ink(tone(0x8E8A84, 1f)).rect(cx - 2f, cx + 2f, top - 6f, top)
            r.ink(tone(0x8E8A84, .8f)).rect(cx + 1.2f, cx + 2f, top - 6f, top)
            r.ink(0x6C6F72).fill(cx - 2f, cx + 2f, top - 6f - sh, top - 6f) { x, y ->
                abs(x - cx) < 2f * (y - (top - 6f - sh)) / sh
            }
            r.ink(-1, a = TOP).line(cx, top - 6f - sh, cx - 2f, top - 6f, .4f)
        } else if (bh > 25f) {
            // the tallest keep a plain roof, inside the strip
        } else if (h < .3f && bw > 10f) {
            waterTower(r, x0 + bw * (.2f + .5f * hash(i, 12, 101)), top - .6f, .8f)
        } else if (h < .5f) {
            // a box of plant on the roof
            val bx = x0 + bw * (.2f + .4f * hash(i, 13, 101))
            r.ink(0x8C8E92, a = TOP, va = .6f).rect(bx, bx + 3f, top - 2f, top - .6f)
        } else if (h < .58f && bw > 12f) {
            billboard(r, i, x0 + bw * .2f, top - .6f, 8f, 3.4f, .7f)
        }
        if (hash(i, 14, 101) < .2f && bh < 29f) {
            val ax = x0 + bw * .7f
            r.ink(IRON).rect(ax - .2f, ax + .2f, top - 5f, top)
        }
    }

    private fun chimney(r: Raster, x: Float, foot: Float, tall: Float) {
        r.ink(0x7E4A3C).fill(x - 1.6f, x + 1.6f, foot - tall, foot) { xx, y ->
            val t = (foot - y) / tall
            abs(xx - x) < 1.6f - .6f * t
        }
        r.ink(0x5E3A30).rect(x - 1.15f, x + 1.15f, foot - tall, foot - tall + .8f)
        r.ink(0xC9C2B6).rect(x - 1.3f, x + 1.3f, foot - tall + 3f, foot - tall + 3.6f)
    }

    /** A water tank on its legs ([s] its size), standing on a roof at [y]. */
    private fun waterTower(r: Raster, x: Float, y: Float, s: Float) {
        r.ink(IRON).rect(x, x + .45f * s, y - 2.4f * s, y)
        r.ink(IRON).rect(x + 3.6f * s, x + 4.05f * s, y - 2.4f * s, y)
        r.ink(IRON).rect(x - .1f * s, x + 4.15f * s, y - 2.6f * s, y - 2.3f * s)
        r.ink(0x6E5140).rect(x - .2f * s, x + 4.25f * s, y - 6.4f * s, y - 2.4f * s)
        // the hoops round the staves
        r.ink(0x4A372C).rect(x - .2f * s, x + 4.25f * s, y - 3.4f * s, y - 3.1f * s)
        r.ink(0x4A372C).rect(x - .2f * s, x + 4.25f * s, y - 5.2f * s, y - 4.9f * s)
        r.ink(tone(0x6E5140, .78f)).rect(x + 3.2f * s, x + 4.25f * s, y - 6.4f * s, y - 2.4f * s)
        val cx = x + 2.02f * s
        r.ink(0x55463C, a = TOP, va = .8f).fill(
            x - .5f * s,
            x + 4.55f * s,
            y - 8.2f * s,
            y - 6.3f * s,
        ) { xx, yy ->
            abs(xx - cx) < 2.55f * s * (yy - (y - 8.2f * s)) / (1.9f * s)
        }
        r.ink(-1, a = SNOW).fill(x - .5f * s, x + 4.55f * s, y - 8.2f * s, y - 6.8f * s) { xx, yy ->
            abs(xx - cx) < 2.55f * s * (yy - (y - 8.2f * s)) / (1.9f * s)
        }
    }

    /**
     * A billboard on its frame: a bright panel (lit at night) with a picture and a line of words.
     */
    private fun billboard(
        r: Raster,
        seed: Int,
        x: Float,
        y: Float,
        bw: Float,
        bh: Float,
        s: Float,
    ) {
        r.ink(IRON).rect(x + bw * .2f, x + bw * .2f + .35f * s, y - 2f * s, y)
        r.ink(IRON).rect(x + bw * .75f, x + bw * .75f + .35f * s, y - 2f * s, y)
        r.ink(IRON).rect(x + .2f, x + bw - .2f, y - 1.2f * s, y - .9f * s)
        val top = y - 2f * s - bh
        val bg = pick(hash(seed, 1, 140), 0xE9E1CF, 0xD8433A, 0x2F6FB5, 0xF2C440, 0x2E8A64)
        val fg = pick(hash(seed, 2, 140), 0x1E2430, 0xFFFFFF, 0xF2C440, 0xC8352E)
        r.ink(bg, a = SIGN, va = .85f).rect(x, x + bw, top, top + bh)
        // the picture: a block of colour, and the words as a bar or two
        r.ink(fg, a = SIGN, va = .6f)
            .rect(x + bw * .58f, x + bw * .92f, top + bh * .15f, top + bh * .85f)
        r.ink(mixc(fg, bg, .3f), a = SIGN, va = .7f)
            .rect(x + bw * .08f, x + bw * .5f, top + bh * .25f, top + bh * .42f)
        r.ink(mixc(fg, bg, .5f), a = SIGN, va = .7f)
            .rect(x + bw * .08f, x + bw * .38f, top + bh * .55f, top + bh * .68f)
        r.ink(tone(IRON, 1.4f), a = TOP, va = .5f).rect(x - .2f, x + bw + .2f, top - .35f * s, top)
        r.ink(-1, a = SNOW).rect(x - .2f, x + bw + .2f, top - .4f * s, top)
    }

    // ------------------------------------------------------------------ the blocks

    private enum class Kind {
        BROWNSTONES,
        WAREHOUSE,
        SHOPS,
        APARTMENT,
        CHURCH,
        PARK,
        PARKING,
        CROSSING,
        PLAZA,
    }

    /** One plot along the blocks: what stands on it, where, how wide and how tall (dp). */
    private class Parcel(val kind: Kind, val x: Float, val w: Float, val h: Float, val seed: Int)

    /** The blocks' plots along the span, the same every time. */
    private fun parcels(): List<Parcel> {
        val out = ArrayList<Parcel>()
        var x = 0f
        var i = 0
        var sinceCross = 30f
        var last = Kind.CROSSING
        // a fixed few: one church, one park and one plaza in every span's worth, at their places
        while (x < SPAN - 1f) {
            val h = hash(i, 1, 201)
            var kind =
                when {
                    sinceCross > 70f + 30f * hash(i, 2, 201) && last != Kind.CROSSING ->
                        Kind.CROSSING
                    x in 150f..175f -> Kind.CHURCH
                    x in 300f..330f -> Kind.PARK
                    x in 470f..490f -> Kind.PLAZA
                    h < .3f -> Kind.BROWNSTONES
                    h < .48f -> Kind.SHOPS
                    h < .64f -> Kind.WAREHOUSE
                    h < .82f -> Kind.APARTMENT
                    h < .9f -> Kind.PARKING
                    else -> Kind.BROWNSTONES
                }
            if (kind == last && kind != Kind.BROWNSTONES && kind != Kind.SHOPS) kind = Kind.SHOPS
            if (kind == Kind.CHURCH && out.any { it.kind == Kind.CHURCH }) kind = Kind.APARTMENT
            if (kind == Kind.PARK && out.any { it.kind == Kind.PARK }) kind = Kind.BROWNSTONES
            if (kind == Kind.PLAZA && out.any { it.kind == Kind.PLAZA }) kind = Kind.SHOPS
            val hh = hash(i, 3, 201)
            var (pw, ph) =
                when (kind) {
                    Kind.BROWNSTONES ->
                        (3 + (hh * 4).toInt()) * 6.4f to 10.4f + 3.2f * (hh * 2f).toInt()
                    Kind.SHOPS -> (2 + (hh * 3).toInt()) * 8.2f to 7.6f + 3.4f * (hh * 2f).toInt()
                    Kind.WAREHOUSE -> 20f + 10f * hh to 14.5f + 7.2f * hash(i, 4, 201)
                    Kind.APARTMENT -> 14f + 8f * hh to 19f + 7f * hash(i, 4, 201)
                    Kind.CHURCH -> 22f to 11f
                    Kind.PARK -> 34f + 8f * hh to 0f
                    Kind.PLAZA -> 18f to 0f
                    Kind.PARKING -> 14f + 6f * hh to 0f
                    Kind.CROSSING -> 8f to 0f
                }
            if (SPAN - (x + pw) < 8f) {
                pw = SPAN - x
                if (kind == Kind.CHURCH || kind == Kind.PARK || kind == Kind.PLAZA)
                    kind = Kind.SHOPS
                if (kind == Kind.CROSSING) kind = Kind.PARKING
            }
            out.add(Parcel(kind, x, pw, ph, i * 13 + 5))
            sinceCross = if (kind == Kind.CROSSING) 0f else sinceCross + pw
            last = kind
            x += pw
            i++
        }
        return out
    }

    private fun blocks(r: Raster) {
        val parcels = parcels()
        // the ground behind everything: low roofs and back yards, rising to the district's feet
        r.ink(0x6E6C68).rect(0f, SPAN, FAR_FOOT + .4f, BLOCK_FOOT + .6f)
        var bx = 0f
        var bi = 0
        while (bx < SPAN - .5f) {
            var bw = 4f + 7f * hash(bi, 1, 211)
            if (SPAN - (bx + bw) < 4f) bw = SPAN - bx
            val top = FAR_FOOT + .6f + 2.2f * hash(bi, 2, 211)
            val col =
                tone(
                    pick(hash(bi, 3, 211), *BRICK, *CONCRETE, *STONE),
                    .7f + .15f * hash(bi, 4, 211),
                )
            r.ink(col).rect(bx, bx + bw, top, BLOCK_FOOT)
            r.ink(tone(ROOF, 1.2f), a = TOP, va = .5f).rect(bx, bx + bw, top - .35f, top)
            r.ink(-1, a = SNOW).rect(bx, bx + bw, top - .4f, top + .2f)
            bx += bw
            bi++
        }
        for (p in parcels) {
            when (p.kind) {
                Kind.BROWNSTONES -> brownstones(r, p)
                Kind.SHOPS -> shops(r, p)
                Kind.WAREHOUSE -> warehouse(r, p)
                Kind.APARTMENT -> apartment(r, p)
                Kind.CHURCH -> church(r, p)
                Kind.PARK -> park(r, p)
                Kind.PLAZA -> plaza(r, p)
                Kind.PARKING -> parking(r, p)
                Kind.CROSSING -> crossing(r, p)
            }
        }
        boulevard(r, parcels)
    }

    /** A row of brownstones: stoops, cornices, two or three windows a floor. */
    private fun brownstones(r: Raster, p: Parcel) {
        val n = (p.w / 6.4f).roundToInt().coerceAtLeast(1)
        val each = p.w / n
        for (k in 0 until n) {
            val x0 = p.x + k * each
            val x1 = x0 + each
            val s = p.seed * 7 + k
            val floors = (p.h / 3.2f).roundToInt() + (if (hash(s, 1, 220) < .3f) 1 else 0) - 1
            val top = BLOCK_FOOT - 1.2f - floors * 3.2f
            val col =
                tone(
                    if (hash(s, 2, 220) < .7f) pick(hash(s, 3, 220), *BROWNSTONE)
                    else pick(hash(s, 3, 220), *BRICK),
                    .9f + .2f * hash(s, 4, 220),
                )
            // the raised ground floor and the floors over it
            r.ink(col).rect(x0, x1, top, BLOCK_FOOT)
            r.ink(tone(col, .72f)).rect(x0, x0 + .25f, top, BLOCK_FOOT)
            // the cornice: a dark band with a lit lip, standing a little proud
            r.ink(tone(col, .55f)).rect(x0 - .1f, x1 + .1f, top - .9f, top)
            r.ink(tone(col, 1.25f), a = TOP).rect(x0 - .25f, x1 + .25f, top - 1.1f, top - .85f)
            r.ink(-1, a = SNOW).rect(x0 - .25f, x1 + .25f, top - 1.3f, top - .9f)
            // the windows, with pale lintels
            val cols = if (each > 6f) 3 else 2
            val pitch = (each - 1.2f) / cols
            for (f in 0 until floors) {
                val wy = BLOCK_FOOT - 1.2f - f * 3.2f - .8f
                for (c in 0 until cols) {
                    if (f == 0 && c == 0) continue
                    val wx = x0 + .6f + c * pitch + (pitch - 1.1f) / 2f
                    window(r, s * 11 + f * 5 + c, wx, wx + 1.1f, wy - 1.9f, wy, col)
                }
            }
            // the stoop: steps up to the door on the left
            val dx = x0 + .6f + (pitch - 1.1f) / 2f
            r.ink(0x2A2220).rect(dx, dx + 1.1f, BLOCK_FOOT - 3.6f, BLOCK_FOOT - 1.4f)
            r.ink(tone(col, 1.15f), a = TOP, va = .6f)
                .rect(dx - .3f, dx + 1.6f, BLOCK_FOOT - 1.5f, BLOCK_FOOT - 1.1f)
            r.ink(tone(col, .95f)).fill(dx - .3f, dx + 3f, BLOCK_FOOT - 1.2f, BLOCK_FOOT) { x, y ->
                x < dx + 1.6f + (y - (BLOCK_FOOT - 1.2f)) * 1.1f
            }
            r.ink(IRON).line(dx + 1.6f, BLOCK_FOOT - 1.9f, dx + 3f, BLOCK_FOOT - .3f, .14f)
            // a chimney or two, a pigeon on the cornice now and then
            if (hash(s, 5, 220) < .5f) {
                val cx = x0 + each * (.3f + .4f * hash(s, 6, 220))
                r.ink(tone(col, .8f)).rect(cx, cx + .9f, top - 2.3f, top - .9f)
                r.ink(-1, a = SNOW).rect(cx - .1f, cx + 1f, top - 2.5f, top - 2.1f)
            }
            if (hash(s, 7, 220) < .35f) pigeons(r, s, x0 + 1f, x1 - 1f, top - 1.1f)
        }
    }

    /** A window: dark glass with the sky's light at its top, a pale sill, lit at night. */
    private fun window(r: Raster, s: Int, x0: Float, x1: Float, y0: Float, y1: Float, wall: Int) {
        val o = hash(s, 1, 230)
        val kind = if (hash(s, 2, 230) < .72f) .08f else .9f
        r.ink(GLASS, a = WIN, b = ORDER, vb = o).rect(x0, x1, y0, y1)
        r.ink(-1, a = KIND, va = kind, erase = 0f).rect(x0, x1, y0, y1)
        r.ink(GLASS_HI, alpha = .55f, erase = 0f).rect(x0, x1, y0, y0 + (y1 - y0) * .35f)
        // a drawn blind in some
        if (hash(s, 3, 230) < .25f)
            r.ink(0xD9CDB0, alpha = .8f, erase = 0f)
                .rect(x0, x1, y0, y0 + (y1 - y0) * (.3f + .4f * hash(s, 4, 230)))
        r.ink(tone(wall, 1.3f), a = TOP, va = .5f).rect(x0 - .2f, x1 + .2f, y1, y1 + .3f)
        r.ink(tone(wall, 1.18f)).rect(x0 - .15f, x1 + .15f, y0 - .35f, y0)
    }

    private fun pigeons(r: Raster, s: Int, x0: Float, x1: Float, y: Float) {
        val n = 2 + (hash(s, 1, 240) * 3).toInt()
        for (k in 0 until n) {
            val x = x0 + (x1 - x0) * hash(s, k + 2, 240)
            r.ink(0x55585E).ellipse(x, y - .22f, .28f, .2f)
        }
    }

    /** A run of shops: awnings, lit fronts, signs, a flat over each. */
    private fun shops(r: Raster, p: Parcel) {
        val n = (p.w / 8.2f).roundToInt().coerceAtLeast(1)
        val each = p.w / n
        for (k in 0 until n) {
            val x0 = p.x + k * each
            val x1 = x0 + each
            val s = p.seed * 5 + k
            val two = p.h > 9f || hash(s, 1, 250) < .4f
            val top = BLOCK_FOOT - (if (two) 10.8f else 7.4f)
            val col =
                tone(pick(hash(s, 2, 250), *PAINT, *BRICK, *STONE), .9f + .15f * hash(s, 3, 250))
            r.ink(col).rect(x0, x1, top, BLOCK_FOOT)
            r.ink(tone(col, .75f)).rect(x1 - .3f, x1, top, BLOCK_FOOT)
            r.ink(tone(col, 1.2f), a = TOP).rect(x0 - .2f, x1 + .2f, top - .6f, top)
            r.ink(-1, a = SNOW).rect(x0 - .2f, x1 + .2f, top - .8f, top - .2f)
            // the flats over the shop
            val floors = if (two) 2 else 1
            for (f in 0 until floors) {
                val wy = BLOCK_FOOT - 4.6f - f * 3.2f - .6f
                for (c in 0 until 2) {
                    val wx = x0 + each * (.22f + .4f * c)
                    window(r, s * 7 + f * 3 + c, wx, wx + 1.3f, wy - 1.8f, wy, col)
                }
            }
            // the shop front: big glass lit early, a door, the sign board over it
            val fy = BLOCK_FOOT - 3.6f
            r.ink(0x2B2C30).rect(x0 + .4f, x1 - .4f, fy - 1.1f, BLOCK_FOOT)
            val sign = pick(hash(s, 4, 250), 0x1F3A5A, 0x6B1F24, 0x1E4D3A, 0x3B2A4A, 0x2A2A2A)
            r.ink(sign, a = SIGN, va = .75f).rect(x0 + .5f, x1 - .5f, fy - 1f, fy - .1f)
            r.ink(0xF2E6C8, a = SIGN, va = 1f)
                .rect(
                    x0 + each * .25f,
                    x0 + each * (.55f + .2f * hash(s, 5, 250)),
                    fy - .7f,
                    fy - .4f,
                )
            r.ink(0x5A6E80, a = WIN, b = ORDER, vb = .9f + .1f * hash(s, 6, 250))
                .rect(x0 + .8f, x1 - .8f, fy + .2f, BLOCK_FOOT - .3f)
            r.ink(-1, a = KIND, va = .05f, erase = 0f)
                .rect(x0 + .8f, x1 - .8f, fy + .2f, BLOCK_FOOT - .3f)
            r.ink(0xC9D6E2, alpha = .35f, erase = 0f).rect(x0 + .8f, x1 - .8f, fy + .2f, fy + .9f)
            r.ink(0x221E1C).rect(x0 + each * .66f, x0 + each * .66f + 1f, fy + .5f, BLOCK_FOOT)
            // the awning: striped cloth, sloping out over the pavement
            val aw =
                pick(hash(s, 7, 250), 0xB83A32, 0x2F7A55, 0x2E5A9A, 0xD8A23A, 0x7A3A6A, 0x3A6A7A)
            if (hash(s, 8, 250) < .75f) {
                val ay = fy + .1f
                r.ink(aw).fill(x0 + .3f, x1 - .3f, ay, ay + 1.4f) { x, y ->
                    val stripe = ((x - x0) / .7f).toInt() % 2 == 0
                    stripe || hash(s, 9, 250) < .4f
                }
                r.ink(tone(aw, 1.5f)).fill(x0 + .3f, x1 - .3f, ay, ay + 1.4f) { x, y ->
                    ((x - x0) / .7f).toInt() % 2 == 1 && hash(s, 9, 250) >= .4f
                }
                r.ink(tone(aw, .7f)).rect(x0 + .3f, x1 - .3f, ay + 1.2f, ay + 1.5f)
                r.ink(-1, a = SNOW).rect(x0 + .3f, x1 - .3f, ay - .1f, ay + .3f)
            }
            // a billboard on some roofs
            if (hash(s, 10, 250) < .3f && each > 7f)
                billboard(r, s, x0 + .8f, top - .6f, each - 1.6f, 3.6f, 1f)
            else if (hash(s, 11, 250) < .3f) pigeons(r, s, x0 + .5f, x1 - .5f, top - .6f)
        }
    }

    /** An old warehouse: brick, big gridded windows, a painted sign fading on its side, a tank. */
    private fun warehouse(r: Raster, p: Parcel) {
        val s = p.seed
        val x0 = p.x + .3f
        val x1 = p.x + p.w - .3f
        val top = BLOCK_FOOT - p.h
        val col = tone(pick(hash(s, 1, 260), *BRICK), .9f + .15f * hash(s, 2, 260))
        r.ink(col).rect(x0, x1, top, BLOCK_FOOT)
        r.ink(tone(col, .74f)).rect(x1 - .5f, x1, top, BLOCK_FOOT)
        r.ink(tone(col, 1.15f), a = TOP).rect(x0 - .2f, x1 + .2f, top - .7f, top)
        r.ink(-1, a = SNOW).rect(x0 - .2f, x1 + .2f, top - .9f, top - .3f)
        // brick courses between the floors
        val floors = ((p.h - 1.5f) / 3.6f).toInt()
        for (f in 1..floors) r.ink(tone(col, .82f))
            .rect(x0, x1, BLOCK_FOOT - f * 3.6f, BLOCK_FOOT - f * 3.6f + .3f)
        // the windows: wide, many-paned
        val cols = ((x1 - x0 - 1f) / 3.6f).toInt()
        val pitch = (x1 - x0) / cols
        for (f in 0 until floors) for (c in 0 until cols) {
            val wx = x0 + c * pitch + (pitch - 2.4f) / 2f
            val wy = BLOCK_FOOT - f * 3.6f - .7f
            val ws = s * 31 + f * 7 + c
            if (f == 0 && c % 3 == 1) {
                // a loading door
                r.ink(0x3E3A36).rect(wx - .2f, wx + 2.6f, wy - 2.6f, BLOCK_FOOT)
                r.ink(0x5C5650).rect(wx - .2f, wx + 2.6f, wy - 2.6f, wy - 2.3f)
                continue
            }
            window(r, ws, wx, wx + 2.4f, wy - 2.4f, wy, col)
            r.ink(tone(col, 1.1f), alpha = .8f, erase = .4f)
                .rect(wx + 1.1f, wx + 1.3f, wy - 2.4f, wy)
            r.ink(tone(col, 1.1f), alpha = .8f, erase = .4f)
                .rect(wx, wx + 2.4f, wy - 1.3f, wy - 1.1f)
        }
        // the ghost sign: a pale panel with faded letters near the top
        if (hash(s, 3, 260) < .7f) {
            val gx = x0 + (x1 - x0) * .12f
            val gw = (x1 - x0) * .6f
            val gy = top + 1f
            r.ink(0xE6D9BC, alpha = .55f, erase = 0f).rect(gx, gx + gw, gy, gy + 2.2f)
            var lx = gx + .5f
            var k = 0
            while (lx < gx + gw - 1f) {
                val lw = .5f + .5f * hash(s, k, 261)
                r.ink(tone(col, .7f), alpha = .6f, erase = 0f)
                    .rect(lx, lx + lw, gy + .5f, gy + 1.7f)
                lx += lw + .35f
                k++
            }
        }
        waterTower(r, x0 + (x1 - x0) * (.55f + .25f * hash(s, 4, 260)), top - .7f, 1f)
        r.ink(0x8E9094, a = TOP, va = .6f).rect(x0 + 1.5f, x0 + 4.5f, top - 2.1f, top - .7f)
        r.ink(-1, a = SNOW).rect(x0 + 1.4f, x0 + 4.6f, top - 2.3f, top - 1.9f)
        if (hash(s, 5, 260) < .5f) pigeons(r, s, x0 + 1f, x1 - 1f, top - .7f)
    }

    /** A mid-rise of flats: concrete, balconies, plant on the roof. */
    private fun apartment(r: Raster, p: Parcel) {
        val s = p.seed
        val x0 = p.x + .4f
        val x1 = p.x + p.w - .4f
        val top = BLOCK_FOOT - p.h
        val col =
            tone(pick(hash(s, 1, 270), *CONCRETE, *STONE, *PAINT), .92f + .12f * hash(s, 2, 270))
        r.ink(col).rect(x0, x1, top, BLOCK_FOOT)
        r.ink(tone(col, .76f)).rect(x1 - .6f, x1, top, BLOCK_FOOT)
        r.ink(tone(col, 1.15f), a = TOP).rect(x0 - .2f, x1 + .2f, top - .6f, top)
        r.ink(-1, a = SNOW).rect(x0 - .2f, x1 + .2f, top - .8f, top - .2f)
        val floors = ((p.h - 3.4f) / 3.1f).toInt()
        val cols = ((x1 - x0 - 1f) / 2.6f).toInt()
        val pitch = (x1 - x0) / cols
        // the lobby
        r.ink(0x2E3138)
            .rect(x0 + (x1 - x0) * .4f, x0 + (x1 - x0) * .6f, BLOCK_FOOT - 2.6f, BLOCK_FOOT)
        r.ink(0x4E5A68, a = WIN, b = ORDER, vb = .97f)
            .rect(x0 + (x1 - x0) * .42f, x0 + (x1 - x0) * .58f, BLOCK_FOOT - 2.3f, BLOCK_FOOT - .2f)
        for (f in 0 until floors) {
            val wy = BLOCK_FOOT - 3.4f - f * 3.1f - .5f
            for (c in 0 until cols) {
                val wx = x0 + c * pitch + (pitch - 1.4f) / 2f
                window(r, s * 41 + f * 9 + c, wx, wx + 1.4f, wy - 1.9f, wy, col)
            }
            // the balconies on every other column
            if (hash(s, 3, 270) < .6f)
                for (c in 0 until cols step 2) {
                    val bx = x0 + c * pitch + .2f
                    r.ink(tone(col, .6f), alpha = .9f)
                        .rect(bx, bx + pitch - .4f, wy - .7f, wy + .2f)
                    r.ink(tone(col, 1.25f), a = TOP, va = .5f)
                        .rect(bx, bx + pitch - .4f, wy - .8f, wy - .6f)
                }
        }
        // the plant on the roof, an aerial
        r.ink(0x8E9094, a = TOP, va = .7f).rect(x0 + 1f, x0 + 3.6f, top - 1.8f, top - .6f)
        r.ink(0x7C7E82).rect(x1 - 4f, x1 - 2.2f, top - 2.6f, top - .6f)
        r.ink(-1, a = SNOW).rect(x0 + .9f, x0 + 3.7f, top - 2f, top - 1.6f)
        if (hash(s, 4, 270) < .5f) r.ink(IRON).rect(x1 - 1.4f, x1 - 1.2f, top - 6f, top - .6f)
        if (hash(s, 5, 270) < .4f) billboard(r, s, x0 + 4.5f, top - .6f, 7f, 3f, 1f)
    }

    /** A stone church: a nave with a gable, a rose window, a tower and its spire. */
    private fun church(r: Raster, p: Parcel) {
        val stone = 0xA9A08E
        val x0 = p.x + 1f
        val x1 = p.x + p.w - 1f
        val tower0 = x0
        val tower1 = x0 + 5.2f
        val nave0 = tower1
        val nave1 = x1
        val eave = BLOCK_FOOT - 9f
        val ridge = eave - 5f
        val mid = (nave0 + nave1) / 2f
        val half = (nave1 - nave0) / 2f
        // the steps and the yard railing
        r.ink(stone).rect(nave0, nave1, eave, BLOCK_FOOT)
        r.ink(0x5A5A60).fill(nave0 - .3f, nave1 + .3f, ridge, eave + .1f) { x, y ->
            abs(x - mid) < (half + .3f) * (y - ridge) / (eave - ridge)
        }
        r.ink(-1, a = SNOW).fill(nave0 - .3f, nave1 + .3f, ridge, eave + .1f) { x, y ->
            abs(x - mid) < (half + .3f) * (y - ridge) / (eave - ridge) &&
                abs(x - mid) > (half + .3f) * (y - ridge) / (eave - ridge) - .5f
        }
        r.ink(tone(stone, 1.2f), a = TOP).fill(nave0 - .3f, nave1 + .3f, ridge, eave + .1f) { x, y
            ->
            val e = (half + .3f) * (y - ridge) / (eave - ridge)
            abs(x - mid) < e && abs(x - mid) > e - .35f
        }
        // the rose window, and lancets either side
        r.ink(0x3A3E58, a = WIN, b = ORDER, vb = .55f).ellipse(mid, eave + 2f, 1.6f, 1.6f)
        r.ink(-1, a = KIND, va = .6f, erase = 0f).ellipse(mid, eave + 2f, 1.6f, 1.6f)
        r.ink(stone, alpha = .8f, erase = .5f).rect(mid - .12f, mid + .12f, eave + .4f, eave + 3.6f)
        r.ink(stone, alpha = .8f, erase = .5f)
            .rect(mid - 1.6f, mid + 1.6f, eave + 1.88f, eave + 2.12f)
        for (k in listOf(-1f, 1f)) {
            val lx = mid + k * half * .62f
            r.ink(0x3A3E58, a = WIN, b = ORDER, vb = .5f).fill(
                lx - .6f,
                lx + .6f,
                eave + .6f,
                eave + 6f,
            ) { x, y ->
                y > eave + 1.2f || abs(x - lx) < .6f * (y - eave - .6f) / .6f
            }
        }
        r.ink(0x3E2E26).fill(mid - 1f, mid + 1f, BLOCK_FOOT - 3.2f, BLOCK_FOOT) { x, y ->
            y > BLOCK_FOOT - 2.6f || abs(x - mid) < (y - (BLOCK_FOOT - 3.2f)) / .6f
        }
        // the tower, its belfry, the spire
        val tTop = BLOCK_FOOT - 20f
        r.ink(tone(stone, 1.04f)).rect(tower0, tower1, tTop, BLOCK_FOOT)
        r.ink(tone(stone, .8f)).rect(tower1 - .7f, tower1, tTop, BLOCK_FOOT)
        r.ink(tone(stone, 1.2f), a = TOP).rect(tower0 - .3f, tower1 + .3f, tTop - .6f, tTop)
        for (k in 0 until 2) {
            val lx = tower0 + 1f + k * 1.9f
            r.ink(0x2A2C34).fill(lx, lx + 1.1f, tTop + 1.2f, tTop + 4.4f) { x, y ->
                y > tTop + 1.8f || abs(x - (lx + .55f)) < .55f * (y - tTop - 1.2f) / .6f
            }
        }
        // the clock
        r.ink(0xE8E2D2, a = SIGN, va = .7f).ellipse((tower0 + tower1) / 2f, tTop + 7f, 1.2f, 1.2f)
        r.ink(IRON)
            .line(
                (tower0 + tower1) / 2f,
                tTop + 7f,
                (tower0 + tower1) / 2f + .6f,
                tTop + 6.5f,
                .18f,
            )
        val cx = (tower0 + tower1) / 2f
        val sh = 12f
        r.ink(0x5E6670).fill(tower0 - .2f, tower1 + .2f, tTop - .6f - sh, tTop - .5f) { x, y ->
            abs(x - cx) < 2.8f * (y - (tTop - .6f - sh)) / sh
        }
        r.ink(-1, a = TOP).line(cx, tTop - .6f - sh, cx - 2.6f, tTop - .8f, .35f)
        r.ink(IRON).rect(cx - .1f, cx + .1f, tTop - sh - 2.6f, tTop - sh)
        r.ink(IRON).rect(cx - .6f, cx + .6f, tTop - sh - 2f, tTop - sh - 1.8f)
    }

    /** A tree: a trunk and bare limbs in the body, the canopy in the foliage channel. */
    private fun tree(r: Raster, s: Int, x: Float, foot: Float, size: Float) {
        val tall = size * (.95f + .25f * hash(s, 1, 280))
        val cw = size * (.48f + .12f * hash(s, 2, 280))
        val cy = foot - tall * .62f
        val ch = tall * .42f
        r.ink(TRUNK).rect(x - size * .045f, x + size * .045f, cy, foot)
        // the bare limbs, seen when the leaves are off
        for (k in 0 until 5) {
            val a = -1f + 2f * hash(s, k + 3, 280)
            val ex = x + a * cw * .85f
            val ey = cy - ch * (.2f + .7f * hash(s, k + 9, 280))
            r.ink(TRUNK, alpha = .9f).line(x, cy + ch * .3f, ex, ey, size * .03f)
        }
        // the canopy: lumps of leaf, lit on the upper side
        val lumps = 6
        for (k in 0 until lumps) {
            val lx = x + (-1f + 2f * hash(s, k + 20, 280)) * cw * .55f
            val ly = cy + (-1f + 2f * hash(s, k + 30, 280)) * ch * .45f
            val lr = cw * (.45f + .2f * hash(s, k + 40, 280))
            r.ink(-1, a = LEAF, va = 1f, erase = .9f).fill(
                lx - lr,
                lx + lr,
                ly - lr * .85f,
                ly + lr * .85f,
                shade = { xx, yy ->
                    val u = (xx - lx) / lr
                    val v = (yy - ly) / (lr * .85f)
                    (.62f - .3f * v - .12f * u).coerceIn(.34f, 1f)
                },
            ) { xx, yy ->
                val u = (xx - lx) / lr
                val v = (yy - ly) / (lr * .85f)
                u * u + v * v <= 1f
            }
        }
        r.ink(-1, a = SNOW, va = .8f).fill(x - cw, x + cw, cy - ch, cy) { xx, yy ->
            val u = (xx - x) / cw
            val v = (yy - cy) / ch
            u * u + v * v <= 1f && u * u + (v + .25f) * (v + .25f) > .9f
        }
    }

    /** A street lamp: its post, its arm, its lantern lit at night, its pool of light. */
    private fun lamp(r: Raster, x: Float, foot: Float, tall: Float, toward: Float, pool: Float) {
        val t = tall * .028f
        r.ink(IRON).rect(x - t, x + t, foot - tall, foot)
        r.ink(IRON).rect(x - t * 2f, x + t * 2f, foot - tall * .08f, foot)
        r.ink(IRON)
            .line(x, foot - tall, x + toward * tall * .22f, foot - tall - tall * .03f, t * 1.6f)
        val hx = x + toward * tall * .22f
        val hy = foot - tall
        r.ink(0x3A3E44).rect(hx - tall * .07f, hx + tall * .07f, hy - tall * .02f, hy + tall * .04f)
        r.ink(0xE8E2C8, a = LAMP, va = 1f)
            .rect(hx - tall * .05f, hx + tall * .05f, hy + tall * .03f, hy + tall * .065f)
        r.ink(-1, a = LAMP, glow = true)
            .spot(hx, hy + tall * .05f, tall * .14f, tall * .1f, .7f, LAMP)
        r.ink(-1, a = SNOW)
            .rect(hx - tall * .07f, hx + tall * .07f, hy - tall * .045f, hy - tall * .015f)
        // its pool on the ground under it, wide and flat
        r.spot(hx, foot + pool * .2f, pool, pool * .28f, .55f, LAMP)
    }

    private fun park(r: Raster, p: Parcel) {
        val s = p.seed
        val x0 = p.x
        val x1 = p.x + p.w
        r.ink(LAWN).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        r.ink(-1, a = SNOW, va = .9f).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        r.ink(tone(LAWN, 1.15f), a = TOP, va = .4f).rect(x0, x1, FAR_FOOT + 1.2f, FAR_FOOT + 3f)
        // a path winding back, pale gravel
        r.ink(0xC9BFA8).fill(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f) { x, y ->
            val c = x0 + p.w * .5f + (y - BLOCK_FOOT) * 1.6f
            abs(x - c) < .5f + (y - FAR_FOOT) * .1f
        }
        // the trees at several depths: the further the smaller
        for (k in 0 until 9) {
            val depth = hash(s, k, 290)
            val foot = FAR_FOOT + 2f + depth * 6f
            val tx = x0 + 2f + (p.w - 4f) * hash(s, k + 50, 290)
            if (abs(tx - (x0 + p.w * .5f + (foot - BLOCK_FOOT) * 1.6f)) < 1.5f) continue
            tree(r, s * 17 + k, tx, foot, 6f + 4f * depth)
        }
        // benches, a lamp, a statue on its plinth
        for (k in 0 until 3) {
            val bx = x0 + 3f + (p.w - 6f) * hash(s, k + 70, 290)
            val by = BLOCK_FOOT - 1f - 2f * hash(s, k + 80, 290)
            r.ink(0x5A4636).rect(bx, bx + 2f, by - .7f, by - .45f)
            r.ink(IRON).rect(bx + .2f, bx + .35f, by - .5f, by)
            r.ink(IRON).rect(bx + 1.65f, bx + 1.8f, by - .5f, by)
            r.ink(0x5A4636).rect(bx, bx + 2f, by - 1.3f, by - 1.1f)
        }
        val sx = x0 + p.w * .5f + 4f
        r.ink(0xB8B2A6).rect(sx - 1f, sx + 1f, BLOCK_FOOT - 4.2f, BLOCK_FOOT - 1.6f)
        r.ink(0x5E7468).ellipse(sx, BLOCK_FOOT - 5.6f, .55f, 1.5f)
        r.ink(0x5E7468).ellipse(sx, BLOCK_FOOT - 7.2f, .4f, .4f)
        lamp(r, x0 + p.w * .25f, BLOCK_FOOT - .6f, 5.5f, 1f, 2.4f)
        lamp(r, x0 + p.w * .78f, BLOCK_FOOT - 2.4f, 4.6f, -1f, 2f)
        // the low railing along the pavement
        r.ink(IRON).rect(x0, x1, BLOCK_FOOT - 1f, BLOCK_FOOT - .85f)
        var px = x0 + .4f
        while (px < x1) {
            r.ink(IRON).rect(px, px + .12f, BLOCK_FOOT - 1.1f, BLOCK_FOOT + .2f)
            px += .7f
        }
    }

    private fun plaza(r: Raster, p: Parcel) {
        val x0 = p.x
        val x1 = p.x + p.w
        r.ink(0xC4BCAE).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        r.ink(-1, a = SNOW, va = .7f).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        // paving lines toward the back
        for (k in 0 until 6) {
            val y = FAR_FOOT + 1.6f + k * 1.3f
            r.ink(0xA89F92, alpha = .6f, erase = 0f).rect(x0, x1, y, y + .12f)
        }
        // the fountain: a basin and its spray
        val cx = (x0 + x1) / 2f
        r.ink(0x9C958A).rect(cx - 4f, cx + 4f, BLOCK_FOOT - 3f, BLOCK_FOOT - 1.8f)
        r.ink(tone(0x9C958A, 1.3f), a = TOP)
            .rect(cx - 4f, cx + 4f, BLOCK_FOOT - 3.2f, BLOCK_FOOT - 2.9f)
        r.ink(0x8EA2B4, alpha = .85f)
            .rect(cx - 3.6f, cx + 3.6f, BLOCK_FOOT - 3.15f, BLOCK_FOOT - 2.95f)
        r.ink(0xDDE8F0, alpha = .7f, a = SIGN, va = .3f).fill(
            cx - 2f,
            cx + 2f,
            BLOCK_FOOT - 7.5f,
            BLOCK_FOOT - 3f,
        ) { x, y ->
            val t = (BLOCK_FOOT - 3f - y) / 4.5f
            abs(x - cx) < .25f + 1.6f * t * (1f - t) * 2f &&
                abs(x - cx) > 1.3f * t * (1f - t) * 2f - .2f
        }
        // a kiosk and its umbrellas
        for (k in 0 until 3) {
            val ux = x0 + 2.5f + k * 3.2f
            val col = pick(hash(p.seed, k, 300), 0xE9E4D8, 0xB83A32, 0x2F6FB5)
            r.ink(IRON).rect(ux - .08f, ux + .08f, BLOCK_FOOT - 3.4f, BLOCK_FOOT - 1f)
            r.ink(col).fill(ux - 1.4f, ux + 1.4f, BLOCK_FOOT - 4.2f, BLOCK_FOOT - 3.3f) { x, y ->
                abs(x - ux) < 1.4f * (y - (BLOCK_FOOT - 4.2f)) / .9f
            }
            r.ink(-1, a = SNOW).fill(ux - 1.4f, ux + 1.4f, BLOCK_FOOT - 4.2f, BLOCK_FOOT - 3.8f) {
                x,
                y ->
                abs(x - ux) < 1.4f * (y - (BLOCK_FOOT - 4.2f)) / .9f
            }
            r.ink(0x5A4636).rect(ux - .8f, ux + .8f, BLOCK_FOOT - 1.6f, BLOCK_FOOT - 1.4f)
        }
        tree(r, p.seed * 3 + 1, x1 - 3f, BLOCK_FOOT - .4f, 8f)
        lamp(r, x1 - 6.5f, BLOCK_FOOT - .4f, 5f, -1f, 2.2f)
    }

    /** A small car seen from the side ([len] dp long), its wheels on the road at [y]. */
    private fun car(r: Raster, s: Int, x: Float, y: Float, len: Float) {
        val col = pick(hash(s, 1, 310), *CARS)
        val hgt = len * .3f
        val body0 = y - hgt * .78f
        r.ink(col).fill(x, x + len, body0, y - hgt * .2f) { xx, yy ->
            val u = (xx - x) / len
            !(u < .06f && yy < body0 + hgt * .2f) && !(u > .94f && yy < body0 + hgt * .2f)
        }
        // the cabin with its glass
        val c0 = x + len * (.22f + .06f * hash(s, 2, 310))
        val c1 = x + len * (.72f + .06f * hash(s, 3, 310))
        r.ink(col).fill(c0 - len * .06f, c1 + len * .08f, y - hgt, body0 + .05f) { xx, yy ->
            val t = (yy - (y - hgt)) / (body0 - (y - hgt))
            xx > c0 - len * .06f * t && xx < c1 + len * .08f * t
        }
        r.ink(0x2C3644).fill(c0, c1, y - hgt * .93f, body0) { xx, yy ->
            val t = (yy - (y - hgt * .93f)) / (body0 - (y - hgt * .93f))
            xx > c0 - len * .04f * t + len * .01f && xx < c1 + len * .06f * t - len * .01f
        }
        r.ink(col, alpha = .9f)
            .rect((c0 + c1) / 2f - len * .02f, (c0 + c1) / 2f + len * .02f, y - hgt * .93f, body0)
        r.ink(tone(col, 1.3f), a = TOP, va = .6f)
            .rect(c0, c1, y - hgt - len * .02f, y - hgt + len * .015f)
        r.ink(-1, a = SNOW).rect(c0, c1, y - hgt - len * .03f, y - hgt + len * .02f)
        r.ink(tone(col, .6f)).rect(x, x + len, y - hgt * .3f, y - hgt * .2f)
        // the wheels
        for (wx in floatArrayOf(x + len * .2f, x + len * .8f)) r.ink(0x1C1D21)
            .ellipse(wx, y - hgt * .16f, len * .09f, hgt * .17f)
    }

    private fun parking(r: Raster, p: Parcel) {
        val x0 = p.x
        val x1 = p.x + p.w
        r.ink(0x67696E).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        r.ink(-1, a = SNOW, va = .6f).rect(x0, x1, FAR_FOOT + 1.2f, BLOCK_FOOT + .2f)
        // three rows of cars, the further the smaller
        for (row in 0 until 3) {
            val y = FAR_FOOT + 3f + row * 2.6f
            val len = 3f + row * .5f
            var cx = x0 + .5f + hash(p.seed, row, 320)
            var k = 0
            while (cx + len < x1 - .3f) {
                if (hash(p.seed * 5 + row, k, 321) < .78f)
                    car(r, p.seed * 101 + row * 17 + k, cx, y, len)
                cx += len + .4f
                k++
            }
        }
        lamp(r, x0 + p.w * .5f, BLOCK_FOOT - 2f, 6f, 1f, 3f)
        // the chain-link fence along the pavement
        r.ink(0x7A7E84, alpha = .45f, erase = 0f).rect(x0, x1, BLOCK_FOOT - 1.6f, BLOCK_FOOT)
        var px = x0 + .3f
        while (px < x1) {
            r.ink(0x55595F).rect(px, px + .15f, BLOCK_FOOT - 1.8f, BLOCK_FOOT)
            px += 2.4f
        }
    }

    /** A side street going back between the blocks, with its signals on the corner. */
    private fun crossing(r: Raster, p: Parcel) {
        val x0 = p.x
        val x1 = p.x + p.w
        r.ink(ASPHALT).rect(x0 + .8f, x1 - .8f, FAR_FOOT + .6f, BLOCK_FOOT + .4f)
        r.ink(-1, a = SNOW, va = .4f).rect(x0 + .8f, x1 - .8f, FAR_FOOT + .6f, BLOCK_FOOT + .4f)
        r.ink(PAVING).rect(x0, x0 + .8f, FAR_FOOT + .6f, BLOCK_FOOT + .4f)
        r.ink(PAVING).rect(x1 - .8f, x1, FAR_FOOT + .6f, BLOCK_FOOT + .4f)
        // its centre line, receding
        var y = FAR_FOOT + 1f
        while (y < BLOCK_FOOT) {
            r.ink(0xE8E2CC, alpha = .7f, erase = 0f)
                .rect((x0 + x1) / 2f - .08f, (x0 + x1) / 2f + .08f, y, y + .5f)
            y += 1.1f
        }
        // the signal on the far corner, hanging out over the boulevard
        val sx = x1 - .3f
        r.ink(IRON).rect(sx - .14f, sx + .14f, BLOCK_FOOT - 7.5f, BLOCK_FOOT + .8f)
        r.ink(IRON).rect(sx - 4f, sx + .1f, BLOCK_FOOT - 7.5f, BLOCK_FOOT - 7.25f)
        signal(r, sx - 3.6f, BLOCK_FOOT - 7.25f)
        signal(r, sx, BLOCK_FOOT - 5.6f)
    }

    private fun signal(r: Raster, x: Float, y: Float) {
        r.ink(0x24272C).rect(x - .4f, x + .4f, y, y + 2.3f)
        for (k in 0 until 3) r.ink(0x3A3C40, a = SIGNAL, va = 1f - k * .5f)
            .ellipse(x, y + .45f + k * .7f, .26f, .26f)
        r.ink(-1, a = SNOW).rect(x - .45f, x + .45f, y - .15f, y + .1f)
    }

    /**
     * The boulevard: the far pavement and its trees and lamps, the road, its markings, parked cars.
     */
    private fun boulevard(r: Raster, parcels: List<Parcel>) {
        r.ink(PAVING).rect(0f, SPAN, BLOCK_FOOT - .2f, FAR_KERB + .2f)
        r.ink(-1, a = SNOW, va = .8f).rect(0f, SPAN, BLOCK_FOOT, FAR_KERB)
        // the slabs' joints
        var jx = 0f
        while (jx < SPAN) {
            r.ink(0x9A978F, alpha = .5f, erase = 0f).rect(jx, jx + .1f, BLOCK_FOOT + .1f, FAR_KERB)
            jx += 1.8f
        }
        r.ink(KERBSTONE, a = TOP, va = .7f).rect(0f, SPAN, FAR_KERB, FAR_KERB + .3f)
        r.ink(ASPHALT).rect(0f, SPAN, ROAD_TOP - .2f, ROAD_BOTTOM + .6f)
        r.ink(0x2E3036, alpha = .4f, erase = 0f).rect(0f, SPAN, ROAD_TOP, ROAD_TOP + .5f)
        // the lane's dashes, the edge lines
        var dx = 0f
        while (dx < SPAN) {
            r.ink(0xE6E2D6, alpha = .85f, erase = 0f)
                .rect(
                    dx,
                    dx + 2.6f,
                    (LANE_LEFT + LANE_RIGHT) / 2f - .9f,
                    (LANE_LEFT + LANE_RIGHT) / 2f - .7f,
                )
            dx += 6.4f
        }
        r.ink(0xD8B040, alpha = .7f, erase = 0f).rect(0f, SPAN, ROAD_TOP + .7f, ROAD_TOP + .85f)
        // the crossings: zebra stripes over the road at each side street, and the stop lines
        for (p in parcels) {
            if (p.kind != Kind.CROSSING) continue
            val cx = p.x + p.w / 2f
            var y = ROAD_TOP + .5f
            var k = 0
            while (y < ROAD_BOTTOM - .3f) {
                val half = 2.6f + (y - ROAD_TOP) * .08f
                r.ink(0xEDEAE0, alpha = .9f, erase = 0f).rect(cx - half, cx + half, y, y + .45f)
                y += .9f + k * .05f
                k++
            }
        }
        // the trees and the lamps along the far pavement
        var tx = 4f
        var k = 0
        while (tx < SPAN - 2f) {
            val p = parcels.first { tx >= it.x && tx < it.x + it.w }
            val clear = p.kind != Kind.CROSSING && tx - p.x > 1f && p.x + p.w - tx > 1f
            if (clear) {
                if (k % 3 == 1) lamp(r, tx, FAR_KERB - .3f, 7.5f, 1f, 3.4f)
                else if (p.kind != Kind.PARK && hash(k, 1, 330) < .55f)
                    tree(r, 900 + k, tx, FAR_KERB - .5f, 6.4f + 1.6f * hash(k, 4, 330))
                if (hash(k, 2, 330) < .15f) {
                    // a hydrant, a bin
                    r.ink(0xB83A2E).rect(tx + 2f, tx + 2.5f, FAR_KERB - 1f, FAR_KERB - .3f)
                    r.ink(0x3E4A44).rect(tx - 2.6f, tx - 1.8f, FAR_KERB - 1.4f, FAR_KERB - .3f)
                }
            }
            tx += 7.2f + 2f * hash(k, 3, 330)
            k++
        }
        // the cars parked along the far kerb, with gaps
        var cx = 1f
        k = 0
        while (cx < SPAN - 6f) {
            val p = parcels.first { cx + 2.5f >= it.x && cx + 2.5f < it.x + it.w }
            val len = 4.4f + .8f * hash(k, 1, 340)
            val nearCross =
                parcels.any {
                    it.kind == Kind.CROSSING && cx + len > it.x - 4f && cx < it.x + it.w + 4f
                }
            if (!nearCross && hash(k, 2, 340) < .62f && p.kind != Kind.PLAZA)
                car(r, 2000 + k, cx, ROAD_TOP + 1.3f, len)
            cx += len + .7f + (if (hash(k, 3, 340) < .3f) 3f else 0f)
            k++
        }
    }

    // ------------------------------------------------------------------ the near kerb

    private fun kerb(r: Raster) {
        // the cars parked along the near kerb
        var cx = 2f
        var k = 0
        while (cx < SPAN - 9f) {
            val len = 6.6f + 1.2f * hash(k, 1, 400)
            if (hash(k, 2, 400) < .55f) car(r, 3000 + k, cx, ROAD_BOTTOM - .1f, len)
            cx += len + 1f + (if (hash(k, 3, 400) < .35f) 6f + 6f * hash(k, 4, 400) else 0f)
            k++
        }
        // the kerb and the near pavement
        r.ink(0x8E8C86).rect(0f, SPAN, ROAD_BOTTOM, ROAD_BOTTOM + .45f)
        r.ink(KERBSTONE, a = TOP, va = .8f).rect(0f, SPAN, ROAD_BOTTOM - .12f, ROAD_BOTTOM + .12f)
        r.ink(PAVING).rect(0f, SPAN, ROAD_BOTTOM + .35f, 24.8f)
        r.ink(-1, a = SNOW, va = .85f).rect(0f, SPAN, ROAD_BOTTOM - .2f, 24.6f)
        var jx = 0f
        while (jx < SPAN) {
            r.ink(0x9A978F, alpha = .55f, erase = 0f).rect(jx, jx + .12f, ROAD_BOTTOM + .5f, 24.6f)
            jx += 2.4f
        }
        r.ink(0x9A978F, alpha = .5f, erase = 0f).rect(0f, SPAN, 22.8f, 22.92f)
        // what stands along it, spaced out: lamps, trees, a bus stop, benches, a kiosk
        val busStops = floatArrayOf(118f, 452f)
        val kiosk = 286f
        var x = 6f
        k = 0
        while (x < SPAN - 4f) {
            val busy = busStops.any { abs(x - it) < 7f } || abs(x - kiosk) < 5f
            if (!busy) {
                when {
                    k % 4 == 0 -> lamp(r, x, 22.4f, 13f, if (hash(k, 1, 410) < .5f) -1f else 1f, 6f)
                    hash(k, 2, 410) < .5f -> {
                        r.ink(0x4C4036).rect(x - 1.3f, x + 1.3f, 22.6f, 23f)
                        tree(r, 4000 + k, x, 22.8f, 9.5f + 3f * hash(k, 3, 410))
                    }
                    hash(k, 2, 410) < .68f -> bench(r, x, 23.2f)
                    else -> {}
                }
                val h = hash(k, 4, 410)
                if (h < .12f) bin(r, x + 2.4f, 23.4f)
                else if (h < .2f) hydrant(r, x + 2.2f, 22.6f)
                else if (h < .26f) bikes(r, x + 2f, 23.5f)
                else if (h < .3f) mailbox(r, x + 2.4f, 23.3f)
            }
            x += 7.5f + 3f * hash(k, 5, 410)
            k++
        }
        for (b in busStops) busStop(r, b, 23.6f)
        kioskAt(r, kiosk, 23.6f)
        // the wall and railing along the pavement's near edge, and the gardens behind it
        r.ink(0x9C978C).rect(0f, SPAN, 24.4f, 25.3f)
        r.ink(0xC8C2B6, a = TOP).rect(0f, SPAN, 24.3f, 24.6f)
        r.ink(-1, a = SNOW).rect(0f, SPAN, 24.1f, 24.6f)
        // the hedge, bumpy along its top over the shade behind it, and the dark gardens nearer
        r.ink(tone(HEDGE, .55f)).rect(0f, SPAN, 24.9f, 31f)
        r.ink(HEDGE).fill(0f, SPAN, 24.6f, 31f) { xx, y ->
            y > 26.2f - 1.2f * around(xx, 3f, .7f, 77, 3)
        }
        r.ink(-1, a = SNOW, va = .9f).fill(0f, SPAN, 24.6f, 27f) { xx, y ->
            val edge = 26.2f - 1.2f * around(xx, 3f, .7f, 77, 3)
            y > edge && y < edge + .6f
        }
        r.ink(tone(HEDGE, .62f)).fill(0f, SPAN, 27f, 31f) { _, y -> y > 27.4f }
        r.ink(tone(HEDGE, .45f), alpha = .8f).rect(0f, SPAN, 29f, 31f)
    }

    private fun bench(r: Raster, x: Float, foot: Float) {
        r.ink(0x5A4636).rect(x - 1.8f, x + 1.8f, foot - 1.1f, foot - .75f)
        r.ink(0x5A4636).rect(x - 1.8f, x + 1.8f, foot - 2f, foot - 1.6f)
        r.ink(IRON).rect(x - 1.5f, x - 1.25f, foot - 1.6f, foot)
        r.ink(IRON).rect(x + 1.25f, x + 1.5f, foot - 1.6f, foot)
        r.ink(-1, a = SNOW).rect(x - 1.8f, x + 1.8f, foot - 1.2f, foot - .95f)
    }

    private fun bin(r: Raster, x: Float, foot: Float) {
        r.ink(0x3E4A44).rect(x - .55f, x + .55f, foot - 1.6f, foot)
        r.ink(0x56635C, a = TOP, va = .5f).rect(x - .65f, x + .65f, foot - 1.8f, foot - 1.5f)
    }

    private fun hydrant(r: Raster, x: Float, foot: Float) {
        r.ink(0xB83A2E).rect(x - .35f, x + .35f, foot - 1.2f, foot)
        r.ink(0xB83A2E).ellipse(x, foot - 1.2f, .38f, .3f)
        r.ink(0x9A2E24).rect(x - .55f, x + .55f, foot - .8f, foot - .55f)
    }

    private fun mailbox(r: Raster, x: Float, foot: Float) {
        r.ink(0x2B4C8C).rect(x - .7f, x + .7f, foot - 2f, foot - .4f)
        r.ink(0x2B4C8C).ellipse(x, foot - 2f, .7f, .45f)
        r.ink(IRON).rect(x - .6f, x - .4f, foot - .5f, foot)
        r.ink(IRON).rect(x + .4f, x + .6f, foot - .5f, foot)
    }

    private fun bikes(r: Raster, x: Float, foot: Float) {
        for (k in 0 until 2) {
            val bx = x + k * 1.1f
            val col = pick(hash(k, x.toInt(), 420), 0x2F6FB5, 0xB83A32, 0x2C2E33, 0x3E7A50)
            r.ink(IRON, alpha = .9f).ellipse(bx - .7f, foot - .55f, .55f, .55f)
            r.ink(PAVING, alpha = 1f, erase = 0f).ellipse(bx - .7f, foot - .55f, .42f, .42f)
            r.ink(IRON, alpha = .9f).ellipse(bx + .7f, foot - .55f, .55f, .55f)
            r.ink(PAVING, alpha = 1f, erase = 0f).ellipse(bx + .7f, foot - .55f, .42f, .42f)
            r.ink(col).line(bx - .7f, foot - .55f, bx, foot - 1.2f, .14f)
            r.ink(col).line(bx, foot - 1.2f, bx + .7f, foot - .55f, .14f)
            r.ink(col).line(bx - .2f, foot - 1.35f, bx + .5f, foot - 1.25f, .14f)
        }
    }

    private fun busStop(r: Raster, x: Float, foot: Float) {
        // the shelter: a roof, glass walls (faint), an advert panel lit at night, a bench
        r.ink(0x3A3F48).rect(x - 4f, x + 4f, foot - 5.2f, foot - 4.8f)
        r.ink(0xC0CCD6, a = TOP, va = .6f).rect(x - 4f, x + 4f, foot - 5.35f, foot - 5.15f)
        r.ink(-1, a = SNOW).rect(x - 4.1f, x + 4.1f, foot - 5.5f, foot - 5.1f)
        r.ink(0xAEC4D6, alpha = .28f, erase = .2f).rect(x - 3.8f, x + 2.2f, foot - 4.8f, foot - .2f)
        r.ink(IRON).rect(x - 4f, x - 3.75f, foot - 4.8f, foot)
        r.ink(IRON).rect(x + 3.75f, x + 4f, foot - 4.8f, foot)
        r.ink(0xEDE8DA, a = SIGN, va = 1f).rect(x + 2.2f, x + 3.75f, foot - 4.4f, foot - .9f)
        r.ink(0xD8433A, a = SIGN, va = .8f).rect(x + 2.4f, x + 3.55f, foot - 3.8f, foot - 2.6f)
        r.ink(0x5A4636).rect(x - 3f, x + 1f, foot - 1.3f, foot - 1f)
        // the stop's sign on its pole
        r.ink(IRON).rect(x - 5.6f, x - 5.4f, foot - 6f, foot)
        r.ink(0x2B5BA8, a = SIGN, va = .6f).ellipse(x - 5.5f, foot - 6.2f, .7f, .7f)
    }

    private fun kioskAt(r: Raster, x: Float, foot: Float) {
        r.ink(0x2E5A48).rect(x - 2.6f, x + 2.6f, foot - 4f, foot)
        r.ink(0x24483A).rect(x - 3f, x + 3f, foot - 4.6f, foot - 4f)
        r.ink(-1, a = SNOW).rect(x - 3.1f, x + 3.1f, foot - 4.8f, foot - 4.3f)
        r.ink(0x6E5A3A, a = SIGN, va = .9f).rect(x - 2.2f, x + 2.2f, foot - 3.6f, foot - 1.8f)
        // the papers and magazines on show
        for (k in 0 until 6) {
            val mx = x - 2f + k * .7f
            r.ink(
                    pick(hash(k, 1, 430), 0xE8E0D0, 0xD8433A, 0xF2C440, 0x2F6FB5),
                    alpha = .9f,
                    erase = 0f,
                )
                .rect(mx, mx + .5f, foot - 3.3f, foot - 2.4f)
        }
        r.ink(0xE9DDBE, a = SIGN, va = .8f).rect(x - 2.4f, x + 2.4f, foot - 4.55f, foot - 4.1f)
    }

    /** How far each layer slides for a street unit scrolled, as a share of the street's own. */
    fun drift(l: Int): Float = LAYERS[l].foot / BAND
}
