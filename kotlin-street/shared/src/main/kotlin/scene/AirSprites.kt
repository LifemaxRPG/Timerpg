package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A thing of the air, painted once: white ARGB layers whose alpha is the thing's own light, each
 * drawn with a tint of the hour (the way the clouds are). [w] and [h] are pixels; [ox] and [oy]
 * where the thing's own origin (the plane's fuselage centre, the blimp's envelope centre, the
 * contrail's near end) lies in the picture, in pixels; [ppd] how many pixels make a dp.
 */
internal class AirSprite(
    val w: Int,
    val h: Int,
    val ox: Float,
    val oy: Float,
    val ppd: Float,
    val layers: Array<IntArray>,
) {
    val bytes: Int
        get() = w * h * 4 * layers.size
}

/**
 * The sprite painter for the plane, its contrail and the blimp. Every picture is a signed-distance
 * field of a few simple parts (a fuselage with a radius profile, swept plates, capsules) sampled a
 * few times a pixel, lit from the upper left as the clouds are, and split into layers by what the
 * light does: the whole body (shade), what the sun reaches (lit), the sharp gleam of the sun on
 * metal or fabric (glint), the sky's light on the belly (bounce), and what glows of its own at
 * night (glow). Pure Kotlin, deterministic; nothing here runs per frame.
 */
internal object AirSprites {
    /** Pixels in a dp for the plane: it is small and far, and must hold at a 3x screen. */
    const val PLANE_PPD = 5f
    const val BLIMP_PPD = 4f
    const val TRAIL_PPD = 2.5f

    /** The layers of the plane and the blimp, in order. */
    const val SHADE = 0
    const val LIT = 1
    const val GLINT = 2
    const val BOUNCE = 3
    const val GLOW = 4
    const val LAYERS = 5

    /** The contrail's two layers: its whole soft body, and its bright core. */
    const val TRAIL_BODY = 0
    const val TRAIL_CORE = 1
    const val TRAIL_LAYERS = 2

    /** How long the contrail is, in dp, from the engines back to nothing. */
    const val TRAIL_DP = 216f
    const val TRAIL_HALF_DP = 15f

    /**
     * Seen from the street the plane's two engines hang under its near wing nearly in line, one a
     * little lower than the other: the contrail's two cores start this far either side of its
     * centre line, in dp.
     */
    const val ENGINE_Y = .7f

    /**
     * Where the contrail's origin lies from the plane's origin, in dp: behind and below the
     * engines.
     */
    const val TRAIL_X = -1.8f
    const val TRAIL_Y = 1.3f

    /**
     * How far behind the engines (dp) the exhaust condenses into a visible trail: past the tail,
     * with a short clear gap, as a real one does, so it never pokes out from under the plane.
     */
    const val TRAIL_GAP = 11.5f

    /**
     * The engines' and the wingtips' places, for the lights: near wingtip, far wingtip, belly,
     * tail.
     */
    const val TIP_NEAR_X = -6.9f
    const val TIP_NEAR_Y = 4.1f
    const val TIP_FAR_X = -5.6f
    const val TIP_FAR_Y = -2.0f
    const val BEACON_X = -.4f
    const val BEACON_Y = 1.15f
    const val TAIL_X = -11.0f
    const val TAIL_Y = -.45f

    /** Bytes the three sprites hold. */
    fun bytes(): Int = planeBytes() + blimpBytes() + trailBytes()

    fun planeBytes(): Int = PLANE_W * PLANE_H * 4 * LAYERS

    fun blimpBytes(): Int = BLIMP_W * BLIMP_H * 4 * BLIMP_LAYERS

    fun trailBytes(): Int = TRAIL_W * TRAIL_H * 4 * TRAIL_LAYERS

    const val PLANE_W = 130
    const val PLANE_H = 66
    const val BLIMP_W = 216
    const val BLIMP_H = 104
    const val TRAIL_W = 544
    const val TRAIL_H = 76

    // the light: up and to the left and towards the viewer; the half vector for the gleam
    private const val LX = -.42f
    private const val LY = -.66f
    private const val LZ = .62f
    private val HX: Float
    private val HY: Float
    private val HZ: Float

    init {
        val n = sqrt(LX * LX + LY * LY + (LZ + 1f) * (LZ + 1f))
        HX = LX / n
        HY = LY / n
        HZ = (LZ + 1f) / n
    }

    // ---- a tiny toolbox of fields

    /**
     * A sample of the picture: the coverage of a part and the light on it. Parts are laid down back
     * to front, each over what is there.
     */
    private class Sample {
        var cover = 0f
        var lit = 0f
        var glint = 0f
        var bounce = 0f
        var glow = 0f
        var paint = 0f

        fun clear() {
            cover = 0f
            lit = 0f
            glint = 0f
            bounce = 0f
            glow = 0f
            paint = 0f
        }

        /**
         * Lays a part over what is here: [c] of it, lit [l], gleaming [g], bounced [b], glowing
         * [w], painted [p].
         */
        fun put(c: Float, l: Float, g: Float, b: Float, w: Float = 0f, p: Float = 0f) {
            if (c <= 0f) return
            lit = lit * (1f - c) + l * c
            paint = paint * (1f - c) + p * c
            glint = glint * (1f - c) + g * c
            bounce = bounce * (1f - c) + b * c
            glow = glow * (1f - c) + w * c
            cover += c * (1f - cover)
        }
    }

    private fun cov(sdf: Float, soft: Float = .35f): Float = Px.smooth(soft, -soft, sdf)

    private fun lambert(nx: Float, ny: Float, nz: Float): Float =
        max(0f, nx * LX + ny * LY + nz * LZ)

    private fun gleam(nx: Float, ny: Float, nz: Float, power: Float): Float =
        max(0f, nx * HX + ny * HY + nz * HZ).pow(power)

    /** Distance to a convex polygon ([xs], [ys]), wound either way, negative inside. */
    private fun polygon(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Float {
        var d = Float.MAX_VALUE
        var inside = true
        val n = xs.size
        // which way round it goes: the sign of its area
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += xs[i] * ys[j] - xs[j] * ys[i]
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = xs[j] - xs[i]
            val ey = ys[j] - ys[i]
            val px = x - xs[i]
            val py = y - ys[i]
            val t = ((px * ex + py * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
            val qx = px - ex * t
            val qy = py - ey * t
            d = min(d, sqrt(qx * qx + qy * qy))
            if ((ex * py - ey * px) * area < 0f) inside = false
        }
        return if (inside) -d else d
    }

    /** Distance to a capsule from ([ax],[ay]) to ([bx],[by]) of radius [r]; negative inside. */
    private fun capsule(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        r: Float,
        x: Float,
        y: Float,
    ): Float {
        val ex = bx - ax
        val ey = by - ay
        val t = (((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
        val qx = x - ax - ex * t
        val qy = y - ay - ey * t
        return sqrt(qx * qx + qy * qy) - r
    }

    /** Distance to a rounded box centred on ([cx],[cy]), [hw] by [hh] half-sizes, corners [r]. */
    private fun roundBox(
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        r: Float,
        x: Float,
        y: Float,
    ): Float {
        val qx = abs(x - cx) - hw + r
        val qy = abs(y - cy) - hh + r
        return min(max(qx, qy), 0f) + sqrt(max(qx, 0f).pow(2f) + max(qy, 0f).pow(2f)) - r
    }

    private fun pack(w: Int, h: Int, field: Array<FloatArray>): Array<IntArray> =
        Array(field.size) { l ->
            val f = field[l]
            IntArray(w * h) { i ->
                val a = f[i]
                if (a <= .002f) 0 else Px.argbF(min(1f, a), 1f, 1f, 1f)
            }
        }

    /** 1 inside the picture, falling to 0 over the last few pixels, so nothing touches its edge. */
    private fun border(x: Int, y: Int, w: Int, h: Int): Float =
        Px.smooth(0f, 3f, min(min(x, w - 1 - x), min(y, h - 1 - y)).toFloat())

    // ---- the plane

    /** The fuselage's half height at [x] (dp, nose to the right), 0 where there is none. */
    private fun hullR(x: Float): Float {
        if (x > 11.2f || x < -11.1f) return 0f
        return when {
            x > 7.6f -> 1.02f * sqrt(max(0f, 1f - ((x - 7.6f) / 3.6f).pow(2f)))
            x > -4.2f -> 1.02f
            else -> {
                val t = (-4.2f - x) / 6.9f
                1.02f * (1f - .62f * t * t) * (1f - Px.smooth(.88f, 1f, t) * .5f)
            }
        }
    }

    /**
     * The fuselage's centre line: the nose droops a little, and the tail cone sweeps up (the belly
     * rises to the tail while the back stays nearly straight), as an airliner's does.
     */
    private fun hullC(x: Float): Float {
        if (x > 7.6f) return .16f * ((x - 7.6f) / 3.6f).pow(2f)
        if (x > -4.2f) return 0f
        val t = (-4.2f - x) / 6.9f
        return -.68f * t * t
    }

    // the near wing, swept back and coming towards the viewer (so it drops below the hull and is
    // short), the far wing's sliver above the back, the fin, the tailplanes
    private val WING_X = floatArrayOf(2.7f, -5.9f, -7.4f, -2.9f)
    private val WING_Y = floatArrayOf(.45f, 4.0f, 4.05f, .6f)
    private val FAR_WING_X = floatArrayOf(1.6f, -5.0f, -6.3f, -2.9f)
    private val FAR_WING_Y = floatArrayOf(-.9f, -2.05f, -2.05f, -.9f)
    private val FIN_X = floatArrayOf(-5.7f, -10.4f, -11.7f, -10.3f)
    private val FIN_Y = floatArrayOf(-.85f, -6.3f, -6.3f, -.7f)
    private val TAIL_NEAR_X = floatArrayOf(-7.4f, -9.7f, -10.4f, -11.0f)
    private val TAIL_NEAR_Y = floatArrayOf(.25f, -.1f, 1.85f, 1.85f)
    private val TAIL_FAR_X = floatArrayOf(-7.6f, -10.2f, -10.6f, -9.9f)
    private val TAIL_FAR_Y = floatArrayOf(-.85f, -.75f, -2.0f, -2.0f)

    /**
     * The airliner as the street sees it: in profile from a little below, nose to the right, 22 dp
     * long. A long thin fuselage with its cockpit glazing and a row of windows, lit from the upper
     * left along its back with a gleam, its belly taking the sky's light; the fin rising over the
     * tail with the tailplanes; the near wing swept back towards the viewer, foreshortened and
     * drooping under the hull with its two engines hanging below it; a sliver of the far wing over
     * the back. The windows are a layer of their own (dark by day, lit at night).
     */
    fun plane(): AirSprite {
        val w = PLANE_W
        val h = PLANE_H
        val ppd = PLANE_PPD
        val ox = 64f
        val oy = 37f
        val out = Array(LAYERS) { FloatArray(w * h) }
        val s = Sample()
        val sub = 3
        val acc = FloatArray(LAYERS)
        for (py in 0 until h) for (px in 0 until w) {
            acc.fill(0f)
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val x = (px + (sx + .5f) / sub - ox) / ppd
                val y = (py + (sy + .5f) / sub - oy) / ppd
                s.clear()
                planeSample(x, y, s)
                acc[SHADE] += s.cover
                acc[LIT] += s.cover * s.lit
                acc[GLINT] += s.cover * s.glint
                acc[BOUNCE] += s.cover * s.bounce
                acc[GLOW] += s.cover * s.glow
            }
            val n = (sub * sub).toFloat()
            val edge = border(px, py, w, h)
            val i = py * w + px
            for (l in 0 until LAYERS) out[l][i] = acc[l] / n * edge
        }
        return AirSprite(w, h, ox, oy, ppd, pack(w, h, out))
    }

    /** A flat plate of the plane: [c] of it here, facing the viewer, lit [l], its edge gleaming. */
    private fun plate(s: Sample, d: Float, l: Float, edgeGlint: Float, bounce: Float) {
        val c = cov(d, .3f)
        if (c <= 0f) return
        val rim = Px.smooth(.4f, 0f, abs(d)) * edgeGlint
        s.put(c, l, rim, bounce, 0f)
    }

    private fun planeSample(x: Float, y: Float, s: Sample) {
        // behind everything: the far wing's sliver and the far tailplane (their undersides, dim)
        plate(s, polygon(FAR_WING_X, FAR_WING_Y, x, y), .3f, .2f, .3f)
        plate(s, polygon(TAIL_FAR_X, TAIL_FAR_Y, x, y), .32f, .2f, .25f)
        // the fin: a blade facing us, the light coming round its leading edge
        run {
            val d = polygon(FIN_X, FIN_Y, x, y)
            val c = cov(d, .3f)
            if (c > 0f) {
                val up = Px.smooth(-.8f, -6.3f, y)
                val l = .5f + .12f * up
                val rim = Px.smooth(.45f, 0f, abs(d)) * .45f
                s.put(c, l, rim, .08f, 0f)
            }
        }
        // the near wing: its underside towards us, in the hull's shade, lit by the sky below
        run {
            val d = polygon(WING_X, WING_Y, x, y)
            val c = cov(d, .3f)
            if (c > 0f) {
                val out = Px.smooth(.5f, 4f, y)
                val l = .34f - .08f * out
                val leading = x > 2.7f - (y - .45f) * 2.46f - .55f
                val rim = Px.smooth(.45f, 0f, abs(d)) * (if (leading) .5f else .15f)
                s.put(c, l, rim, .6f - .1f * out, 0f)
            }
        }
        // the near tailplane, the same
        plate(s, polygon(TAIL_NEAR_X, TAIL_NEAR_Y, x, y), .34f, .3f, .5f)
        // the fuselage: a cylinder side on, its back lit, a gleam below the back, the belly in
        // shade with the sky's light under it
        run {
            val r = hullR(x)
            if (r > 0f) {
                val cy = hullC(x)
                val d = abs(y - cy) - r
                val c = cov(d, .3f)
                if (c > 0f) {
                    val t = ((y - cy) / r).coerceIn(-1f, 1f)
                    val nz = sqrt(max(0f, 1f - t * t))
                    val slope = (hullR(x + .1f) - hullR(x - .1f)) / .2f
                    val nx = (slope * .9f).coerceIn(-.95f, .95f)
                    val k = sqrt(max(0f, 1f - nx * nx))
                    val ny = t * k
                    val nzz = nz * k
                    val l = .22f + .78f * lambert(nx, ny, nzz)
                    val g = gleam(nx, ny, nzz, 36f) * 1.1f + gleam(nx, ny, nzz, 6f) * .18f
                    val b = .62f * Px.smooth(-.2f, .95f, t)
                    // the windows: a row along the upper half; the cockpit's glazing at the nose
                    var win = 0f
                    if (x > -5.4f && x < 6.6f) {
                        val k2 = (x + 5.4f) / .6f
                        val fx = k2 - kotlin.math.floor(k2) - .5f
                        val wy = (y - cy) / r + .28f
                        val dd = sqrt((fx * .6f).pow(2f) + (wy * r).pow(2f))
                        win = Px.smooth(.19f, .09f, dd)
                    }
                    if (x > 8.2f && x < 9.5f) {
                        // a slanted pane: higher at its front
                        val wy = (y - cy) / r + .42f + (x - 8.2f) * .25f
                        val wd = max(abs(wy * r) - .2f, max(8.2f - x, x - 9.5f))
                        win = max(win, Px.smooth(.12f, 0f, wd) * .95f)
                    }
                    s.put(c, l * (1f - .55f * win), g * (1f - win), b * (1f - win), win)
                }
            }
        }
        // the engines under the near wing: short fat cylinders, the outer one lower and further
        // back, each with a dark intake at its front and a bright lip
        for (e in 0 until 2) {
            val ex0 = if (e == 0) 2.9f else 1.1f
            val ey = if (e == 0) 1.7f else 3.1f
            val er = if (e == 0) .86f else .76f
            // the pylon up to the wing
            val pd = capsule(ex0 - 1.6f, ey - er, ex0 - 2.1f, ey - er - 1f, .28f, x, y)
            plate(s, pd, .3f, 0f, .35f)
            val d = capsule(ex0, ey, ex0 - 3.4f, ey, er, x, y)
            val c = cov(d, .3f)
            if (c > 0f) {
                val t = ((y - ey) / er).coerceIn(-1f, 1f)
                val nz = sqrt(max(0f, 1f - t * t))
                val nx = Px.smooth(ex0 - .6f, ex0 + .4f, x) * .9f
                val k = sqrt(max(0f, 1f - nx * nx))
                val l = .22f + .78f * lambert(nx, t * k, nz * k)
                val g = gleam(nx, t * k, nz * k, 28f) * .9f
                val lip = Px.smooth(ex0 - .55f, ex0 - .2f, x)
                val b = .45f * Px.smooth(-.2f, 1f, t) * (1f - lip)
                s.put(c, l * (1f - .72f * lip), g * (1f - lip) + lip * .3f, b, 0f)
            }
        }
    }

    // ---- the contrail

    /**
     * The contrail: two cores of exhaust right behind the engines that the wake pulls together into
     * one, widening and thinning with distance, broken into lumps towards its far end and gone by
     * [TRAIL_DP]. Its origin is the engines' x, the fuselage's centre line; it runs to the left
     * (behind a plane flying right). Two layers: the soft body and the bright core.
     */
    fun contrail(): AirSprite {
        val w = TRAIL_W
        val h = TRAIL_H
        val ppd = TRAIL_PPD
        val ox = w - 2f
        val oy = h / 2f
        val body = FloatArray(w * h)
        val core = FloatArray(w * h)
        val sub = 2
        for (py in 0 until h) for (px in 0 until w) {
            var bSum = 0f
            var cSum = 0f
            for (sy in 0 until sub) for (sx in 0 until sub) {
                // s: dp behind the engines; y: dp off the centre line
                val back = (ox - (px + (sx + .5f) / sub)) / ppd
                val y = (py + (sy + .5f) / sub - oy) / ppd
                // the trail's own shape is laid out from where it condenses, past the tail
                val sdp = back - TRAIL_GAP
                if (sdp < 0f) continue
                // the trail wanders a little as the air moves it
                val wander =
                    (Noise.fbm(sdp * .022f, 3.1f, 401, 3) - .5f) * 7f * Px.smooth(0f, 90f, sdp)
                // further back the air has torn at it: its edge wavers
                val tear =
                    (Noise.fbm(sdp * .09f, y * .07f, 402, 3) - .5f) * 6f * Px.smooth(40f, 160f, sdp)
                val yy = y - wander - tear
                // the two cores converge (the wake vortices roll them together), and widen
                val pull = 1f - .8f * Px.smooth(8f, 90f, sdp)
                val sigma = .32f + .024f * sdp + .00005f * sdp * sdp
                var dens = 0f
                for (side in 0 until 2) {
                    val sign = if (side == 0) 1f else -1f
                    val yc = sign * ENGINE_Y * pull
                    val dy = (yy - yc) / sigma
                    dens += exp(-.5f * dy * dy)
                }
                // the exhaust condenses a little way behind the engine
                val onset = Px.smooth(.3f, 2.2f, sdp)
                // thins out with distance; and the far part breaks into lumps
                val fade = exp(-sdp / 85f) * Px.smooth(TRAIL_DP, TRAIL_DP - 90f, back)
                val lumpN = Noise.fbm(sdp * .1f, yy * .15f, 403, 4)
                val fineN = Noise.fbm(sdp * .3f, yy * .45f, 404, 3)
                val broken = Px.smooth(25f, 140f, sdp)
                val lumps = Px.mix(1f, Px.smooth(.4f, .7f, lumpN), broken)
                val fine = 1f + (fineN - .5f) * (.2f + .5f * broken)
                val amount = dens * onset * fade * lumps * fine
                // the body is soft and wide, the core is what is dense
                val b = Px.clamp01(amount).pow(.9f)
                bSum += b
                cSum += min(b, Px.clamp01((amount - .15f) * 1.6f).pow(1.5f) * exp(-sdp / 120f))
            }
            val n = (sub * sub).toFloat()
            val edge = border(px, py, w, h)
            val i = py * w + px
            body[i] = bSum / n * edge
            core[i] = cSum / n * edge
        }
        return AirSprite(w, h, ox, oy, ppd, pack(w, h, arrayOf(body, core)))
    }

    // ---- the blimp

    /**
     * The blimp's own sixth layer: the livery painted on it (the panel with its mark, the
     * cheatline, the rudders and elevators, the cabin's belly), drawn over the light so it keeps
     * the shading under it, and under the gleam, which lies on top of the paint as on any glossy
     * finish.
     */
    const val LIVERY = 5
    const val BLIMP_LAYERS = 6

    /** The order the blimp's layers are drawn in: the paint over the light, the gleam over it. */
    val BLIMP_ORDER = intArrayOf(SHADE, LIT, BOUNCE, LIVERY, GLINT, GLOW)

    /** The hull: its nose [HULL_HALF] dp ahead of the origin, its tail as far behind. */
    const val HULL_HALF = 25f

    /** The hull's radius where it is fullest, in dp. */
    const val HULL_R = 6.3f

    /** Where the hull is fullest: this share of its length back from the nose. */
    private const val FULL_AT = .31f

    /**
     * The street looks up at the ship: this many radians from straight side on. The belly shows a
     * little more than the back, the near tailplane rises over the axis and the far one dips under
     * it, the far engine hangs a little lower than the near one.
     */
    private const val VIEW_UP = .13f
    private val SIN_UP = sin(VIEW_UP)
    private val COS_UP = cos(VIEW_UP)

    // the key light on the ship: high, a little behind the viewer and to the left; the half vector
    // between it and the viewer, for the gleam
    private const val BLX = -.3f
    private const val BLY = -.78f
    private const val BLZ = .55f
    private val BHX: Float
    private val BHY: Float
    private val BHZ: Float

    init {
        val hx = BLX
        val hy = BLY + SIN_UP
        val hz = BLZ + COS_UP
        val n = sqrt(hx * hx + hy * hy + hz * hz)
        BHX = hx / n
        BHY = hy / n
        BHZ = hz / n
    }

    /** Angles between the gores (the fabric's long seams) and between the nose battens. */
    private val GORE = (Math.PI / 9.0).toFloat()
    private val BATTEN = (Math.PI / 6.0).toFloat()

    /**
     * The lit panel on the hull's side: from [BAND_L] to [BAND_R] dp along it, between the angles
     * [BAND_TH_T] and [BAND_TH_B] round it (radians below the side facing level, so it curves with
     * the hull). [BAND_T] and [BAND_B] are its top and bottom in dp at the hull's fullest.
     */
    const val BAND_L = -9f
    const val BAND_R = 14f
    const val BAND_TH_T = -.18f
    const val BAND_TH_B = .62f
    const val BAND_T = -1.84f
    const val BAND_B = 2.83f
    private const val PANEL_ROUND = 1.3f

    /** The cheatline's angle round the hull, under the panel; it sweeps up towards the tail. */
    private const val CHEAT_TH = 1.02f

    /** The cabin: from [GOND_REAR] to [GOND_FRONT] dp, [GOND_H] dp deep at its deepest. */
    private const val GOND_REAR = 1.8f
    private const val GOND_FRONT = 13.2f
    private const val GOND_H = 3.9f

    /**
     * Where the lights are, in dp from the origin: the navigation light at the near tailplane's tip
     * (green on the ship's right, the side seen when it flies to the right), the white light at the
     * tail, the strobe under the cabin; the propellers' hubs (the near engine's, the far one's) and
     * their radius; the row of cabin windows.
     */
    const val NAV_X = -20.6f
    const val NAV_Y = -1.85f
    const val STERN_X = -25.4f
    const val STERN_Y = 0f
    const val STROBE_X = 8.2f
    val STROBE_Y: Float = gondolaKeel(STROBE_X) + .55f
    const val PROP_NEAR_X = 1.05f
    const val PROP_NEAR_Y = 6.95f
    const val PROP_FAR_X = 1.4f
    const val PROP_FAR_Y = 7.45f
    const val PROP_R = 1.25f
    val WINDOW_Y: Float = envelopeR(7f) - .5f * sqrt(gondolaShape(7f)) + 1.15f

    // the fins: root front, tip front, tip rear, root rear (convex); the hinge of each control
    // surface, from its root to its tip
    private val TOP_X = floatArrayOf(-13.2f, -19.9f, -23.3f, -25.1f)
    private val TOP_Y = floatArrayOf(-2.4f, -10.9f, -11.1f, -.3f)
    private val TOP_HINGE = floatArrayOf(-22.2f, -2f, -22.6f, -11f)
    private val LOW_X = floatArrayOf(-13.4f, -20f, -23.2f, -25.1f)
    private val LOW_Y = floatArrayOf(2.4f, 9.9f, 10.1f, .3f)
    private val LOW_HINGE = floatArrayOf(-22.2f, 2f, -22.6f, 10f)
    private val NEAR_X = floatArrayOf(-13.2f, -19.6f, -23.3f, -25.1f)
    private val NEAR_Y = floatArrayOf(-.45f, -1.95f, -2.05f, -.25f)
    private val NEAR_HINGE = floatArrayOf(-22.2f, -.2f, -22.6f, -2f)
    private val FAR_X = floatArrayOf(-12.6f, -19.6f, -23.5f, -25.3f)
    private val FAR_Y = floatArrayOf(.5f, 1.9f, 2f, .3f)
    private val FAR_HINGE = floatArrayOf(-22.2f, .3f, -22.7f, 1.9f)

    /**
     * The hull's radius at [x] (dp, nose to the right; 0 off its ends): a blunt rounded nose,
     * fullest a third of the way back, a long taper to the tail.
     */
    fun envelopeR(x: Float): Float {
        val u = (HULL_HALF - x) / (2f * HULL_HALF)
        if (u <= 0f || u >= 1f) return 0f
        val f =
            if (u < FULL_AT) {
                val s = (FULL_AT - u) / FULL_AT
                (1f - s.pow(2.3f)).pow(1f / 2.3f)
            } else {
                val s = (u - FULL_AT) / (1f - FULL_AT)
                (1f - s.pow(2.1f)).pow(.9f)
            }
        return HULL_R * f
    }

    /**
     * The panel's top ([top]) or bottom edge at [x], in dp below the axis as the street sees it.
     */
    fun bandEdge(x: Float, top: Boolean): Float =
        envelopeR(x) * sin((if (top) BAND_TH_T else BAND_TH_B) - VIEW_UP)

    /** How deep the cabin is at [x], 0 to 1 (0 off its ends): fuller at the front, tapered aft. */
    private fun gondolaShape(x: Float): Float {
        val v = (x - GOND_REAR) / (GOND_FRONT - GOND_REAR)
        if (v <= 0f || v >= 1f) return 0f
        return sqrt(max(0f, sin(Math.PI.toFloat() * v.pow(1.3f))))
    }

    /** The y of the cabin's keel at [x]. */
    private fun gondolaKeel(x: Float): Float = envelopeR(x) - .4f + GOND_H * gondolaShape(x)

    /** Distance to the cabin, negative inside: faired into the envelope's belly. */
    private fun gondolaD(x: Float, y: Float): Float {
        val k = gondolaShape(x)
        if (k <= 0f) return 9f
        val top = envelopeR(x) - .5f * sqrt(k)
        val bot = gondolaKeel(x)
        val sl = ((gondolaKeel(x + .1f) - gondolaKeel(x - .1f)) / .2f).coerceIn(-6f, 6f)
        return max(y - bot, top - y) / sqrt(1f + sl * sl)
    }

    /**
     * A modern airship seen side on from a little below, nose to the right, 50 dp long. The
     * envelope is a pressurised fabric hull (blunt nose, fullest a third back, a long taper),
     * shaded with a wrapped key light from above and the cool sky's light under its belly, a soft
     * terminator round it and a sheen along its upper flank; its gores run nose to tail and stiff
     * battens fan back from the mooring cap. Four fins in a cross at the tail: the upper and lower
     * in profile, the near tailplane foreshortened over the axis, the far one dipping under it,
     * each with its control surface hinged off (painted in the livery). A streamlined cabin is
     * faired under the front third with its window row and windscreen, a wheel under it, and two
     * engine pods on outriggers at its back (their propellers turn in [Backdrop]). The livery: a
     * rounded panel on the flank with a plain invented mark (a disc with a chevron, two bars) and a
     * cheatline sweeping up to the tail. By night the panel and the windows glow.
     */
    fun blimp(): AirSprite {
        val w = BLIMP_W
        val h = BLIMP_H
        val ppd = BLIMP_PPD
        val ox = 108f
        val oy = 54f
        val out = Array(BLIMP_LAYERS) { FloatArray(w * h) }
        val s = Sample()
        val sub = 3
        val acc = FloatArray(BLIMP_LAYERS)
        for (py in 0 until h) for (px in 0 until w) {
            acc.fill(0f)
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val x = (px + (sx + .5f) / sub - ox) / ppd
                val y = (py + (sy + .5f) / sub - oy) / ppd
                s.clear()
                blimpSample(x, y, s)
                acc[SHADE] += s.cover
                acc[LIT] += s.cover * s.lit
                acc[GLINT] += s.cover * s.glint
                acc[BOUNCE] += s.cover * s.bounce
                acc[GLOW] += s.cover * s.glow
                acc[LIVERY] += s.cover * s.paint
            }
            val n = (sub * sub).toFloat()
            val edge = border(px, py, w, h)
            val i = py * w + px
            for (l in 0 until BLIMP_LAYERS) out[l][i] = acc[l] / n * edge
        }
        return AirSprite(w, h, ox, oy, ppd, pack(w, h, out))
    }

    /** A soft wrapped light: the terminator rolls a little past the side facing away. */
    private fun wrapLight(nx: Float, ny: Float, nz: Float): Float {
        val k = Px.clamp01((nx * BLX + ny * BLY + nz * BLZ + .18f) / 1.18f)
        return k * k * (3f - 2f * k)
    }

    private fun sheen(nx: Float, ny: Float, nz: Float, power: Float): Float =
        max(0f, nx * BHX + ny * BHY + nz * BHZ).pow(power)

    /**
     * A fin, a flat plate: lit [lit] at its root to [tipLit] at its tip, its rounded leading edge
     * catching the light, the control surface behind the hinge [hinge] turned a little and painted
     * [paint] of the livery. [ao]: the root lies in the hull's shade.
     */
    private fun fin(
        s: Sample,
        xs: FloatArray,
        ys: FloatArray,
        hinge: FloatArray,
        x: Float,
        y: Float,
        lit: Float,
        tipLit: Float,
        bounce: Float,
        paint: Float,
        ao: Boolean,
    ) {
        val d = polygon(xs, ys, x, y)
        val c = cov(d, .28f)
        if (c <= 0f) return
        val out = ((y - ys[0]) / (ys[1] - ys[0])).coerceIn(0f, 1f)
        var l = Px.mix(lit, tipLit, out)
        if (ao) {
            val r = envelopeR(x)
            l *= .6f + .4f * Px.smooth(r, r + 2.4f, abs(y))
        }
        val hx = hinge[0] + (hinge[2] - hinge[0]) * ((y - hinge[1]) / (hinge[3] - hinge[1]))
        val aft = Px.smooth(hx + .06f, hx - .06f, x)
        val line = Px.smooth(.2f, .05f, abs(x - hx))
        l *= (1f - .1f * aft) * (1f - .5f * line)
        val lead = capsule(xs[0], ys[0], xs[1], ys[1], 0f, x, y)
        val g = Px.smooth(.45f, 0f, lead) * .38f + Px.smooth(.3f, 0f, abs(d)) * .08f
        val p = paint * aft * (1f - line)
        s.put(
            c,
            l * (1f - .9f * p),
            g * (1f - line),
            bounce * (1f - .5f * line) * (1f - .9f * p),
            0f,
            p * (.7f + .3f * l),
        )
    }

    /** An engine pod, a short cylinder from [x0] (its back) to [x1] (its intake) at [cy]. */
    private fun pod(
        s: Sample,
        x0: Float,
        x1: Float,
        cy: Float,
        r: Float,
        dim: Float,
        x: Float,
        y: Float,
    ) {
        val d = capsule(x0, cy, x1, cy, r, x, y)
        val c = cov(d, .25f)
        if (c <= 0f) return
        val t = ((y - cy) / r).coerceIn(-1f, 1f)
        val nx = .8f * (Px.smooth(x1 - .2f, x1 + r, x) - Px.smooth(x0 + .2f, x0 - r, x))
        val k = sqrt(max(0f, 1f - nx * nx))
        val ny = t * k
        val nz = sqrt(max(0f, 1f - t * t)) * k
        val intake = Px.smooth(x1 + .15f, x1 + .45f, x)
        val l = (.1f + .72f * wrapLight(nx, ny, nz)) * dim * (1f - .7f * intake)
        val g = sheen(nx, ny, nz, 30f) * .7f * dim * (1f - intake)
        val b = .45f * Px.smooth(-.2f, .9f, t)
        s.put(c, l, g, b, 0f, 0f)
    }

    private fun blimpSample(x: Float, y: Float, s: Sample) {
        // behind the hull: the far tailplane (its tip shows under the tail), the upper and lower
        // fins in profile, their roots under the envelope
        fin(s, FAR_X, FAR_Y, FAR_HINGE, x, y, .14f, .2f, .32f, 0f, false)
        fin(s, TOP_X, TOP_Y, TOP_HINGE, x, y, .5f, .66f, .1f, 1f, true)
        fin(s, LOW_X, LOW_Y, LOW_HINGE, x, y, .3f, .44f, .38f, 1f, true)
        // the far engine, behind the cabin and a little lower
        pod(s, 1.8f, 4.3f, PROP_FAR_Y, .55f, .4f, x, y)
        envelope(x, y, s)
        // the wheel under the cabin, its top in the cabin
        run {
            val wy = gondolaKeel(8.2f) - .05f
            val d = sqrt((x - 8.2f).pow(2f) + (y - wy).pow(2f)) - .45f
            val c = cov(d, .2f)
            if (c > 0f) s.put(c, .1f, Px.smooth(.25f, 0f, abs(d + .15f)) * .2f, .2f, 0f, 0f)
        }
        cabin(x, y, s)
        // the near engine on its outrigger
        run {
            val d = capsule(3.1f, PROP_NEAR_Y, 4.7f, PROP_NEAR_Y - 1f, .17f, x, y)
            val c = cov(d, .2f)
            if (c > 0f) s.put(c, .22f, 0f, .3f, 0f, 0f)
        }
        pod(s, 1.5f, 4.1f, PROP_NEAR_Y, .62f, .62f, x, y)
        // the near tailplane, over the hull: its underside seen, lit by the sky
        fin(s, NEAR_X, NEAR_Y, NEAR_HINGE, x, y, .3f, .36f, .55f, 1f, false)
    }

    private fun envelope(x: Float, y: Float, s: Sample) {
        val r = envelopeR(x)
        if (r <= 0f) return
        val slope = ((envelopeR(x + .12f) - envelopeR(x - .12f)) / .24f).coerceIn(-5f, 5f)
        val d = (abs(y) - r) / sqrt(1f + slope * slope)
        val c = cov(d, .3f)
        if (c <= 0f) return
        val t = (y / r).coerceIn(-1f, 1f)
        val cosT = sqrt(max(0f, 1f - t * t))
        // the angle round the hull of the point seen here (0 facing level, down positive), and its
        // normal: the hull's round section tilted by its taper
        val th = asin(t) + VIEW_UP
        var nx = -slope * .9f
        var ny = sin(th)
        var nz = cos(th)
        val nn = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= nn
        ny /= nn
        nz /= nn
        var l = .03f + .97f * wrapLight(nx, ny, nz).pow(1.25f)
        // the sky's cool light under the belly, and the air's at the rim where the hull turns away
        val facing = max(0f, ny * SIN_UP + nz * COS_UP)
        val b = .5f * Px.smooth(-.15f, .85f, ny) + .32f * (1f - facing).pow(3f)
        // a broad soft sheen along the upper flank, a tighter gleam in it
        var g = sheen(nx, ny, nz, 12f) * .16f + sheen(nx, ny, nz, 70f) * .42f
        // the gores, nose to tail, and the battens fanning back from the nose cap
        val nose = Px.smooth(HULL_HALF - 6.4f, HULL_HALF - 5.4f, x)
        val gp = th / GORE
        val gd = abs(gp - kotlin.math.round(gp)) * GORE * r * max(.2f, cosT)
        val seam =
            Px.smooth(.14f, .03f, gd) * (1f - nose) * Px.smooth(-HULL_HALF + 2f, -HULL_HALF + 6f, x)
        val bp = th / BATTEN
        val bd = abs(bp - kotlin.math.round(bp)) * BATTEN * r * max(.2f, cosT)
        val batten = Px.smooth(.22f, .07f, bd) * nose
        l *= 1f - .1f * seam - .14f * batten
        g = g * (1f - .5f * seam) + batten * .1f * l
        // the mooring cap at the nose, the cone at the tail
        val cap = Px.smooth(HULL_HALF - .8f, HULL_HALF - .55f, x)
        val cone = Px.smooth(-HULL_HALF + 1f, -HULL_HALF + .75f, x)
        l *= (1f - .5f * cap) * (1f - .45f * cone)
        g += cap * .3f * l
        // the cabin's shade on the belly where it is faired in
        val ao = Px.smooth(1.5f, 0f, gondolaD(x, y))
        l *= 1f - .35f * ao
        g *= 1f - ao
        // the livery: the panel with its mark, and the cheatline
        val pcx = (BAND_L + BAND_R) / 2f
        val phw = (BAND_R - BAND_L) / 2f
        val thc = (BAND_TH_T + BAND_TH_B) / 2f
        val thw = (BAND_TH_B - BAND_TH_T) / 2f
        val qx = abs(x - pcx) - phw + PANEL_ROUND
        val qy = (abs(th - thc) - thw) * r + PANEL_ROUND
        val sd =
            min(max(qx, qy), 0f) + sqrt(max(qx, 0f).pow(2f) + max(qy, 0f).pow(2f)) - PANEL_ROUND
        val panel = Px.smooth(.1f, -.1f, sd)
        var mark = 0f
        if (panel > 0f) {
            val my = r * sin(thc - VIEW_UP)
            // the emblem at the panel's front: a disc with an upward chevron cut out of it
            val ex = BAND_R - 2.7f
            val ey = envelopeR(ex) * sin(thc - VIEW_UP)
            val disc = sqrt((x - ex).pow(2f) + (y - ey).pow(2f)) - 1.5f
            val chev =
                min(
                    capsule(ex - .78f, ey + .45f, ex, ey - .38f, .22f, x, y),
                    capsule(ex, ey - .38f, ex + .78f, ey + .45f, .22f, x, y),
                )
            val emblem = Px.smooth(.12f, -.12f, max(disc, -chev))
            // two bars as a wordmark would sit, curving with the hull
            val b1 = bar(x, y, BAND_L + 2.6f, BAND_R - 5.6f, my - .3f, .42f)
            val b2 = bar(x, y, BAND_L + 2.6f, BAND_L + 9.5f, my + .9f, .2f)
            mark = max(emblem, Px.smooth(.12f, -.12f, min(b1, b2)))
        }
        val thl = CHEAT_TH - .52f * Px.smooth(4f, -21f, x)
        val yl = r * sin(thl - VIEW_UP)
        val cheat =
            Px.smooth(.32f, .14f, abs(y - yl)) *
                Px.smooth(-22f, -18f, x) *
                Px.smooth(22.6f, 19.6f, x)
        val paint = max(panel * (1f - mark), cheat)
        val glow = panel * (.58f + .42f * mark)
        s.put(c, l * (1f - .9f * paint), g, b * (1f - .9f * paint), glow, paint * (.72f + .28f * l))
    }

    /** Distance to a bar from [x0] to [x1] at height [cy] and half thickness [r], ends rounded. */
    private fun bar(x: Float, y: Float, x0: Float, x1: Float, cy: Float, r: Float): Float {
        val dx = max(0f, max(x0 - x, x - x1))
        val dy = y - cy
        return sqrt(dx * dx + dy * dy) - r
    }

    private fun cabin(x: Float, y: Float, s: Sample) {
        val d = gondolaD(x, y)
        val c = cov(d, .26f)
        if (c <= 0f) return
        val k = gondolaShape(x)
        val top = envelopeR(x) - .5f * sqrt(k)
        val bot = gondolaKeel(x)
        val mid = (top + bot) / 2f + .35f
        val hh = max(.2f, (bot - top) / 2f)
        val t = ((y - mid) / hh).coerceIn(-1f, 1f)
        val sl = ((gondolaKeel(x + .1f) - gondolaKeel(x - .1f)) / .2f).coerceIn(-4f, 4f)
        var nx = -sl * .35f * Px.smooth(-.6f, .6f, t)
        var ny = t
        var nz = sqrt(max(0f, 1f - t * t))
        val nn = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= nn
        ny /= nn
        nz /= nn
        // under the envelope: in its shade, deepest right up under it
        var l =
            (.12f + .78f * wrapLight(nx, ny, nz)) *
                (.5f + .5f * Px.smooth(top + .2f, top + 2.2f, y))
        var g = sheen(nx, ny, nz, 40f) * .45f
        val b = .55f * Px.smooth(-.2f, .9f, t)
        // the window row, panes between mullions, and the raked windscreen at the front
        val wy0 = top + .72f
        val wy1 = top + 1.58f
        val row =
            Px.smooth(.06f, -.06f, max(wy0 - y, y - wy1)) *
                Px.smooth(3.1f, 3.3f, x) *
                Px.smooth(11.1f, 10.9f, x)
        val kk = (x - 3.3f) / 1.1f
        val fk = kk - kotlin.math.floor(kk)
        val pane = Px.smooth(.07f, .15f, min(fk, 1f - fk))
        val screen =
            Px.smooth(11.25f, 11.45f, x) *
                Px.smooth(-.28f, -.42f, d) *
                Px.smooth(.06f, -.06f, max(wy0 - .15f - y, y - (wy1 + .3f - (x - 11.3f) * .45f)))
        val win = max(row * pane, screen)
        // the sky caught in the glass, brightest at the top of it
        val glass = win * (.12f + .3f * Px.smooth(wy1, wy0, y))
        // a painted stripe under the windows
        val paint =
            Px.smooth(.32f, .16f, abs(y - wy1 - .62f)) * Px.smooth(2.6f, 3.6f, x) * (1f - win)
        l *= 1f - .85f * win
        g = g * (1f - win) + glass
        s.put(
            c,
            l * (1f - .9f * paint),
            g,
            b * (1f - .7f * win) * (1f - .9f * paint),
            win,
            paint * (.72f + .28f * l),
        )
    }
}
