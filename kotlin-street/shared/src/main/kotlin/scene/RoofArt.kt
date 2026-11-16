package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The things on a roof and in front of a shop, painted once as small pictures in full colour
 * ([RoofArt]), each in units at its own [ppu] with an anchor (its foot's middle) at ([ax], [ay]).
 */
internal class RoofPicture(
    val w: Int,
    val h: Int,
    val ppu: Float,
    val ax: Float,
    val ay: Float,
    val pixels: IntArray,
)

/**
 * The roof's furniture and the shop's planters, painted in light and shade the way the scene's
 * sprites are (pure Kotlin, deterministic, looked at on a plain JVM):
 * - [tank]: a New York water tower: a cylinder of cedar staves (each its own tone, its grain, its
 *   seams, weathered silver up top and stained dark by the water low down) bound by steel hoops
 *   that curve round it, under a conical roof of boards with a finial, standing on a timber deck on
 *   a steel stand (legs, cross-bracing, a ladder up its side, the outlet pipe down). Painted three
 *   times: by the sky alone ([tank] 0) and with the sun on it from the left (-1) and from the right
 *   (1), the painter mixing them by the sun's side and strength.
 * - [hvac]: a rooftop air-handling unit: a steel cabinet with louvred sides and a fan grille on
 *   top; [dish]: a satellite dish on its mast, its arm and its feed; [stack]: a vent stack with its
 *   rain cap and collar; [planter]: a timber planter of clipped box by a shop's door.
 */
internal object RoofArt {
    const val PPU = 4f

    /** The tank's picture, in units: its width and height, its foot's middle from its top-left. */
    const val TANK_W = 26f
    const val TANK_H = 37f

    fun bytes(): Int {
        fun n(w: Float, h: Float) = (w * PPU).toInt() * (h * PPU).toInt() * 4
        return 3 * n(TANK_W, TANK_H) +
            n(HVAC_W, HVAC_H) +
            n(DISH_W, DISH_H) +
            n(STACK_W, STACK_H) +
            n(PLANTER_W, PLANTER_H)
    }

    const val HVAC_W = 18f
    const val HVAC_H = 11f
    const val DISH_W = 12f
    const val DISH_H = 15f
    const val STACK_W = 7f
    const val STACK_H = 14f
    const val PLANTER_W = 11f
    const val PLANTER_H = 12f

    /** A canvas of float RGBA, painted over (premultiplied), turned into a [RoofPicture]. */
    private class Canvas(wu: Float, hu: Float, val ax: Float, val ay: Float) {
        val w = (wu * PPU).toInt()
        val h = (hu * PPU).toInt()
        val c = FloatArray(w * h * 4)

        /** Units of the pixel (i, j)'s middle from the anchor. */
        fun ux(i: Int) = (i + .5f) / PPU - ax

        fun uy(j: Int) = (j + .5f) / PPU - ay

        fun over(i: Int, j: Int, a: Float, r: Float, g: Float, b: Float) {
            if (a <= 0f || i < 0 || j < 0 || i >= w || j >= h) return
            val k = (j * w + i) * 4
            val aa = min(1f, a)
            c[k] = c[k] * (1f - aa) + aa
            c[k + 1] = c[k + 1] * (1f - aa) + r * aa
            c[k + 2] = c[k + 2] * (1f - aa) + g * aa
            c[k + 3] = c[k + 3] * (1f - aa) + b * aa
        }

        /** Every pixel whose middle is in units [x0]..[x1], [y0]..[y1] (from the anchor). */
        inline fun each(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            f: (Int, Int, Float, Float) -> Unit,
        ) {
            val i0 = max(0, floor((x0 + ax) * PPU).toInt() - 1)
            val i1 = min(w - 1, floor((x1 + ax) * PPU).toInt() + 1)
            val j0 = max(0, floor((y0 + ay) * PPU).toInt() - 1)
            val j1 = min(h - 1, floor((y1 + ay) * PPU).toInt() + 1)
            for (j in j0..j1) for (i in i0..i1) f(i, j, ux(i), uy(j))
        }

        /** A box with antialiased edges, in one colour times [l]. */
        fun box(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            col: FloatArray,
            l: Float,
            a: Float = 1f,
        ) {
            each(x0, y0, x1, y1) { i, j, x, y ->
                val cov = edge(x - x0) * edge(x1 - x) * edge(y - y0) * edge(y1 - y) * a
                over(i, j, cov, col[0] * l, col[1] * l, col[2] * l)
            }
        }

        /** A line [wd] wide from (x0, y0) to (x1, y1). */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, wd: Float, col: FloatArray, l: Float) {
            val dx = x1 - x0
            val dy = y1 - y0
            val len2 = dx * dx + dy * dy
            each(min(x0, x1) - wd, min(y0, y1) - wd, max(x0, x1) + wd, max(y0, y1) + wd) {
                i,
                j,
                x,
                y ->
                val t = (((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0f, 1f)
                val px = x0 + t * dx - x
                val py = y0 + t * dy - y
                val d = sqrt(px * px + py * py)
                over(i, j, edge(wd / 2f - d), col[0] * l, col[1] * l, col[2] * l)
            }
        }

        fun picture(): RoofPicture {
            val px = IntArray(w * h)
            for (k in 0 until w * h) {
                val a = c[k * 4]
                px[k] =
                    if (a < .004f) 0
                    else Px.argbF(a, c[k * 4 + 1] / a, c[k * 4 + 2] / a, c[k * 4 + 3] / a)
            }
            return RoofPicture(w, h, PPU, ax, ay, px)
        }

        /** Coverage of a pixel whose middle is [d] units inside an edge. */
        fun edge(d: Float): Float = Px.clamp01(d * PPU + .5f)
    }

    private val IRON = floatArrayOf(.16f, .17f, .19f)
    private val STEEL = floatArrayOf(.36f, .38f, .41f)
    private val CEDAR = floatArrayOf(.5f, .36f, .25f)
    private val SILVER = floatArrayOf(.55f, .5f, .45f)
    private val BOARDS = floatArrayOf(.36f, .28f, .22f)
    private val TIMBER = floatArrayOf(.33f, .26f, .2f)

    /**
     * The light on a surface whose normal is (nx, ny, nz) (x right, y up, z to the viewer): the
     * sky's from above and in front, and the sun's from the upper left ([sun] -1) or the upper
     * right (1), or none (0).
     */
    private fun shade(nx: Float, ny: Float, nz: Float, sun: Int): Float {
        val sky = .5f + .28f * ny + .22f * nz
        if (sun == 0) return .82f * sky + .1f
        val d = max(0f, .78f * sun * nx + .45f * ny + .45f * nz)
        return .36f * sky + 1.02f * d
    }

    private fun hash(a: Int, b: Int): Float {
        var n = a * 374761393 + b * 668265263 + 4111 * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777216f
    }

    /** The tank, by the sky alone ([lit] 0) or with the sun from the left (-1) or the right (1). */
    fun tank(lit: Int): RoofPicture {
        val cv = Canvas(TANK_W, TANK_H, TANK_W / 2f, TANK_H)
        // the stand: legs (the back pair darker), cross-bracing, under a deck of timber
        val legTop = -8.2f
        for (lx in floatArrayOf(-2.6f, 2.6f)) cv.box(lx - .45f, legTop, lx + .45f, 0f, IRON, .7f)
        for (lx in floatArrayOf(-7.6f, 7.6f)) {
            cv.box(
                lx - .6f,
                legTop,
                lx + .6f,
                0f,
                STEEL,
                shade(if (lx < 0) -.7f else .7f, 0f, .7f, lit),
            )
            cv.box(lx - .55f, legTop, lx - .25f, 0f, STEEL, shade(-1f, 0f, .2f, lit))
            // the foot plate on its sleeper
            cv.box(lx - 1.3f, -.7f, lx + 1.3f, 0f, TIMBER, .8f)
        }
        val braceL = shade(0f, 0f, 1f, lit) * .85f
        cv.line(-7.3f, legTop + .6f, 7.3f, -1.2f, .45f, STEEL, braceL)
        cv.line(7.3f, legTop + .6f, -7.3f, -1.2f, .45f, STEEL, braceL * .9f)
        cv.box(-7.6f, -4.4f, 7.6f, -3.9f, STEEL, braceL)
        // the outlet pipe down through the deck
        cv.box(-1.4f, legTop, -.5f, 0f, IRON, shade(-.3f, 0f, 1f, lit))
        cv.box(-1.4f, legTop, -1.1f, 0f, STEEL, shade(-1f, 0f, .3f, lit))
        // the deck: the beams' ends, the joists under the tank
        cv.box(-9.6f, legTop - 1.1f, 9.6f, legTop, TIMBER, shade(0f, -.3f, 1f, lit))
        cv.box(-9.6f, legTop - 1.1f, 9.6f, legTop - .8f, TIMBER, shade(0f, 1f, .4f, lit))
        var jx = -9.2f
        while (jx < 9.4f) {
            cv.box(jx, legTop - .9f, jx + .35f, legTop, IRON, .5f, .5f)
            jx += 1.6f
        }
        // the barrel: staves round a cylinder, wider at its foot
        val top = -24.2f
        val bottom = legTop - 1.1f
        val rTop = 7.5f
        val rFoot = 8.1f
        val ell = .9f
        cv.each(-rFoot - 1f, top - ell, rFoot + 1f, bottom + ell) { i, j, x, y ->
            val v = ((y - top) / (bottom - top)).coerceIn(0f, 1f)
            val r = rTop + (rFoot - rTop) * v
            val q = x / r
            if (abs(q) >= 1f) return@each
            val c = sqrt(1f - q * q)
            // the ring's ends seen from a little above: its top and foot curve down to the middle
            val yTop = top + ell * c
            val yFoot = bottom + ell * c
            val cov = cv.edge(r - abs(x)) * cv.edge(y - yTop) * cv.edge(yFoot - y)
            if (cov <= 0f) return@each
            val theta = asin(q)
            // the stave: its own tone, a dark seam each side, the grain running down it
            val sw = .95f
            val sf = (theta * r / sw) + 40f
            val stave = floor(sf).toInt()
            val f = sf - floor(sf)
            val tone = .9f + .2f * hash(stave, 1)
            val seam = if (f < .12f || f > .9f) .62f else 1f
            val grain =
                1f +
                    (Noise.fbm(f * 2f + stave * 3.1f, y * .9f, 4121, 3) - .5f) * .22f +
                    (Noise.value(stave * 7f, y * 6f, 4123) - .5f) * .08f
            var l = shade(q, .08f, c, lit) * tone * seam * grain
            // the wood silvered by the weather up top, darkened by the water low down
            val silver = Px.smooth(.55f, 0f, v) * .55f
            val wet = Px.smooth(.55f, 1f, v) * (.25f + .2f * Noise.value(stave * 1.7f, 2f, 4125))
            l *= 1f - wet
            // the roof's eave throws its shadow on the barrel's top
            l *= 1f - .45f * Px.smooth(1.6f, .2f, y - yTop)
            // the occlusion round the edges of the cylinder (the sky reaches it less)
            l *= .78f + .22f * c
            val col = FloatArray(3) { CEDAR[it] * (1f - silver) + SILVER[it] * silver }
            cv.over(i, j, cov, col[0] * l, col[1] * l, col[2] * l)
        }
        // the hoops, closer together toward the foot where the water presses hardest
        for (hy in floatArrayOf(-22.4f, -19.6f, -17.2f, -15.1f, -13.2f, -11.5f, -10.1f)) {
            cv.each(-rFoot - .5f, hy - .5f, rFoot + .5f, hy + 1.6f) { i, j, x, y ->
                val v = ((hy - top) / (bottom - top)).coerceIn(0f, 1f)
                val r = rTop + (rFoot - rTop) * v + .12f
                val q = x / r
                if (abs(q) >= 1f) return@each
                val c = sqrt(1f - q * q)
                val yc = hy + ell * c
                val d = y - yc
                val cov = cv.edge(r - abs(x)) * cv.edge(.22f - abs(d))
                if (cov <= 0f) return@each
                val l = shade(q, if (d < 0f) .7f else -.4f, c, lit) * (if (d < -.1f) .95f else .62f)
                cv.over(i, j, cov, STEEL[0] * l, STEEL[1] * l, STEEL[2] * l)
                // and the rust it bleeds down the staves under it
            }
            cv.each(-rFoot, hy, rFoot, hy + 2f) { i, j, x, y ->
                val v = ((hy - top) / (bottom - top)).coerceIn(0f, 1f)
                val r = rTop + (rFoot - rTop) * v
                val q = x / r
                if (abs(q) >= .97f) return@each
                val c = sqrt(1f - q * q)
                val d = y - (hy + ell * c) - .25f
                if (d < 0f) return@each
                val streak = Px.smooth(.6f, .9f, Noise.value(x * 2.4f, 1.3f, 4127))
                cv.over(i, j, .3f * streak * Px.smooth(1.4f, 0f, d), .3f, .17f, .1f)
            }
        }
        // the conical roof: boards running up to the apex, its eave overhanging the barrel
        val eave = top + ell * .4f
        val apex = -31.6f
        val reach = rTop + 1.4f
        cv.each(-reach - 1f, apex, reach + 1f, eave + ell + 1f) { i, j, x, y ->
            val t = ((y - apex) / (eave - apex))
            if (t <= 0f) return@each
            val half = reach * min(1f, t)
            val q = (x / half).coerceIn(-1f, 1f)
            val c = sqrt(max(0f, 1f - q * q))
            val foot = eave + ell * 1.1f * c
            val cov = cv.edge(half - abs(x)) * cv.edge(foot - y)
            if (cov <= 0f) return@each
            // the cone's normal: tilted up by its pitch
            var l = shade(q * .8f, .55f, c * .8f, lit)
            val board = asin(q) * 5f
            val bf = board - floor(board)
            if (bf < .08f) l *= 1f - .26f * Px.smooth(.15f, .45f, t)
            l *= .92f + .14f * hash(floor(board).toInt(), 7)
            l *= 1f + (Noise.value(floor(board) * 3.1f, t * 5f, 4131) - .5f) * .08f
            // the tar's weathering low on the cone, where the rain runs off
            l *= 1f - .12f * Px.smooth(.6f, 1f, t)
            // the eave's edge: a fascia band, lit along its top
            val fascia = foot - y
            if (fascia < .55f) l *= .72f
            cv.over(i, j, cov, BOARDS[0] * l, BOARDS[1] * l, BOARDS[2] * l)
        }
        // the hatch on the cone and the finial at its apex
        cv.box(1.6f, -27.6f, 3.6f, -26.2f, BOARDS, shade(.3f, .5f, .8f, lit) * .82f)
        cv.box(1.6f, -26.5f, 3.6f, -26.2f, IRON, .5f)
        cv.line(0f, apex + .3f, 0f, apex - 1.6f, .4f, IRON, .9f)
        cv.each(-1f, apex - 3.1f, 1f, apex - 1.1f) { i, j, x, y ->
            val dy = y - (apex - 2.1f)
            val d = sqrt(x * x + dy * dy)
            val cov = cv.edge(.75f - d)
            if (cov <= 0f) return@each
            val nz = sqrt(max(0f, 1f - (d / .75f) * (d / .75f)))
            val l = shade(x / .75f, -dy / .75f, nz, lit) * 1.1f
            cv.over(i, j, cov, STEEL[0] * l, STEEL[1] * l, STEEL[2] * l)
        }
        // the ladder up the side, from the deck to the eave, standing off the barrel
        val lx0 = rFoot + .5f
        val lx1 = rFoot + 2.1f
        val railL = shade(.8f, 0f, .6f, lit)
        cv.box(lx0, eave - .4f, lx0 + .35f, legTop - 1.1f, IRON, railL)
        cv.box(lx1, eave - 1.4f, lx1 + .35f, legTop - 1.1f, IRON, railL)
        var ry = legTop - 2f
        while (ry > eave + .2f) {
            cv.box(lx0, ry - .22f, lx1 + .35f, ry + .1f, STEEL, railL * .85f)
            ry -= 1.25f
        }
        // the ladder's hoops round its upper reach
        for (k in 0..2) {
            val hy = eave + 1.2f + k * 2.4f
            cv.box(lx1 + .35f, hy, lx1 + 1.2f, hy + .3f, IRON, railL)
            cv.box(lx1 + 1f, hy, lx1 + 1.25f, hy + 2.4f, IRON, railL * .8f)
        }
        return cv.picture()
    }

    /** A rooftop air-handling cabinet: louvred front, fan grille on its top, plinth under it. */
    fun hvac(): RoofPicture {
        val cv = Canvas(HVAC_W, HVAC_H, HVAC_W / 2f, HVAC_H)
        val body = floatArrayOf(.62f, .64f, .66f)
        cv.box(-8.6f, -.9f, 8.6f, 0f, TIMBER, .55f)
        // the cabinet's top, seen from above, with the fan's round grille
        cv.box(-8f, -10.4f, 8f, -8.2f, body, 1.08f)
        cv.each(-3.6f, -10.3f, 3.6f, -8.3f) { i, j, x, y ->
            val dy = (y + 9.3f) / .95f
            val d = sqrt(x * x / (3.2f * 3.2f) + dy * dy)
            if (d > 1f) return@each
            val ring = (d * 5f - floor(d * 5f)) < .45f || abs(x) < .25f
            val l = if (ring) .32f else .6f
            cv.over(i, j, cv.edge((1f - d) * 3f), body[0] * l, body[1] * l, body[2] * l)
        }
        // the front: panels, the louvres in shade under their lips, a service door's latch
        cv.box(-8f, -8.2f, 8f, -.9f, body, .88f)
        cv.box(-8f, -8.2f, 8f, -7.9f, body, 1.15f)
        var ly = -7.4f
        while (ly < -1.6f) {
            cv.box(-7.2f, ly, -.6f, ly + .35f, IRON, .9f, .8f)
            cv.box(-7.2f, ly + .35f, -.6f, ly + .55f, body, 1.1f)
            ly += .9f
        }
        cv.box(.4f, -7.4f, 7.2f, -1.6f, body, .82f)
        cv.box(.4f, -7.4f, .6f, -1.6f, IRON, .9f, .6f)
        cv.box(6.2f, -4.8f, 6.6f, -3.6f, IRON, 1f)
        cv.box(-8f, -1.4f, 8f, -.9f, IRON, 1f, .35f)
        // the duct out of its side, turned down into the roof
        cv.box(8f, -6.4f, 9f, -3.2f, body, .7f)
        return cv.picture()
    }

    /** A satellite dish on a short mast with its feed arm, its face turned up and to the left. */
    fun dish(): RoofPicture {
        val cv = Canvas(DISH_W, DISH_H, DISH_W / 2f, DISH_H)
        val white = floatArrayOf(.8f, .81f, .82f)
        cv.box(-2.2f, -.6f, 2.2f, 0f, IRON, .9f)
        cv.box(-.4f, -7.4f, .4f, -.6f, STEEL, 1f)
        cv.box(-.4f, -7.4f, -.15f, -.6f, white, .9f)
        cv.line(.2f, -5f, 2.6f, -1f, .3f, STEEL, .8f)
        // the dish: an ellipse, lighter at its upper rim, the bowl's hollow in shade
        cv.each(-5.2f, -14.5f, 4.2f, -5.5f) { i, j, x, y ->
            val dx = (x + .5f) / 4.4f
            val dy = (y + 10f) / 4.3f
            val d = sqrt(dx * dx + dy * dy)
            if (d > 1.05f) return@each
            val cov = cv.edge((1f - d) * 4.3f)
            val bowl = 1f - .35f * (1f - d) * Px.smooth(-1f, .6f, dx - dy)
            val rim = if (d > .9f) 1.12f else 1f
            val l = (.78f + .2f * (-dy)) * bowl * rim
            cv.over(i, j, cov, white[0] * l, white[1] * l, white[2] * l)
        }
        // its feed on an arm from the rim
        cv.line(-.5f, -6f, 2.6f, -9.8f, .3f, IRON, .9f)
        cv.box(2.2f, -10.6f, 3.4f, -9.4f, IRON, 1f)
        return cv.picture()
    }

    /** A vent stack with its rain cap and a collar flashed into the roof. */
    fun stack(): RoofPicture {
        val cv = Canvas(STACK_W, STACK_H, STACK_W / 2f, STACK_H)
        cv.box(-2.6f, -1.6f, 2.6f, 0f, TIMBER, .6f)
        cv.each(-1.6f, -11.6f, 1.6f, -1.6f) { i, j, x, _ ->
            val q = x / 1.6f
            val c = sqrt(max(0f, 1f - q * q))
            cv.over(
                i,
                j,
                cv.edge(1.6f - abs(x)),
                STEEL[0] * (.5f + .8f * c - .3f * q),
                STEEL[1] * (.5f + .8f * c - .3f * q),
                STEEL[2] * (.5f + .8f * c - .3f * q),
            )
        }
        cv.box(-1.9f, -6f, 1.9f, -5.3f, IRON, 1f)
        // the cap: a cone over a gap
        cv.each(-3.2f, -13.8f, 3.2f, -11.6f) { i, j, x, y ->
            val t = (y + 13.8f) / 1.8f
            val half = 3.2f * t
            cv.over(
                i,
                j,
                cv.edge(half - abs(x)) * cv.edge(-11.9f - y),
                STEEL[0] * (1.2f - .4f * t - .2f * x / 3f),
                STEEL[1] * (1.2f - .4f * t - .2f * x / 3f),
                STEEL[2] * (1.2f - .4f * t - .2f * x / 3f),
            )
        }
        cv.box(-1.6f, -11.9f, 1.6f, -11.3f, IRON, .7f)
        return cv.picture()
    }

    /** A timber planter of clipped box by the shop's door. */
    fun planter(): RoofPicture {
        val cv = Canvas(PLANTER_W, PLANTER_H, PLANTER_W / 2f, PLANTER_H)
        // the shrub: clipped into a dome, leaves in clusters lit from above
        cv.each(-5f, -11.8f, 5f, -4.6f) { i, j, x, y ->
            val dx = x / 4.6f
            val dy = (y + 6f) / 5.6f
            if (dy > .2f) return@each
            val d = sqrt(dx * dx + dy * dy)
            val leaf = Noise.fbm(x * 1.4f, y * 1.4f, 4141, 3)
            val edge = 1f - d + (leaf - .5f) * .35f
            if (edge <= 0f) return@each
            val l = (.55f + .5f * (-dy) - .2f * dx) * (.75f + .5f * leaf)
            cv.over(i, j, Px.clamp01(edge * 6f), .2f * l, .38f * l, .2f * l)
        }
        // the box: planks, a capping rail lit on top, the feet
        val wood = floatArrayOf(.42f, .3f, .2f)
        cv.box(-4.2f, -5.2f, 4.2f, -.7f, wood, .8f)
        var px = -4.2f
        while (px < 4.1f) {
            cv.box(px, -5.2f, px + .2f, -.7f, IRON, .5f, .6f)
            px += 1.4f
        }
        cv.box(-4.6f, -5.7f, 4.6f, -5.1f, wood, 1.15f)
        cv.box(-4.6f, -5.2f, 4.6f, -4.9f, IRON, .5f, .5f)
        cv.box(-4f, -.7f, -3.1f, 0f, IRON, .8f)
        cv.box(3.1f, -.7f, 4f, 0f, IRON, .8f)
        return cv.picture()
    }
}
