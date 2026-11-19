package com.lifemax.rpg.feature.habits.domain

import kotlin.math.sqrt

/*
 * A pitched-roof house ([HouseShape]: one of our mobile homes) as closed 3D geometry for the Filament engines: its walls,
 * its gable ends, and its roof as a slab with a thickness, not a sheet. The gable pictures (tools/make_buildings.py) are
 * cut to the slab's UNDERSIDE (the roof's top less [HouseShape.slab]) and stand at the wall's ends ([HouseShape.wall0],
 * [HouseShape.wall1]); the roof overhangs them by the verge and the walls by the eave. So the roof is built with its
 * underside (the soffits under the eaves and the verges), its edges (the eave boards, the rake boards along the slopes)
 * and the walls' tops inside it: from every side the roof meets the walls, with nothing showing between them.
 */

/** Which picture a [ShellQuad] wears. */
enum class ShellPart {
    /** The front wall (the front picture, its wall columns). */
    FRONT,
    /** The back wall (the back picture, mirrored: its first column at the front's last). */
    BACK,
    /** The eave's edge at the front: the front picture's top rows (the art's fascia), [HouseShape.slab] high. */
    EAVE_FRONT,
    /** The eave's edge at the back: the back picture's top rows. */
    EAVE_BACK,
    /** A gable end (the side picture: the wall up into the triangle under the roof slab). */
    GABLE,
    /** A roof slope (the slope picture). */
    SLOPE,
    /** A rake board: the roof slab's end along a slope, over a gable ([HouseShape.rake]). */
    RAKE,
    /** The roof slab's underside, under an eave or a verge ([HouseShape.rakeEdge]). */
    SOFFIT,
}

/**
 * One quad of a house's shell, in the house's own frame and sprite pixels: x across the front picture (0 at its left
 * edge), y up from the ground, d back from the front wall. Its corners are [o], o + [u], o + [u] + [v], o + [v], wearing
 * the picture's (u0, v0) .. (u1, v1) in [uv] (v down the picture). [n] is its outward normal (x, y, d).
 */
class ShellQuad(val part: ShellPart, val o: FloatArray, val u: FloatArray, val v: FloatArray, val uv: FloatArray, val n: FloatArray)

object HouseShell {
    /**
     * The shell of [sh]: [pw] is the front picture's width (pixels; the back's is the same), [sideH] the side picture's
     * height (pixels). Every quad meets its neighbours edge to edge.
     */
    fun quads(sh: HouseShape, pw: Float, sideH: Float): List<ShellQuad> {
        val out = ArrayList<ShellQuad>(24)
        fun q(p: ShellPart, o: FloatArray, u: FloatArray, v: FloatArray, uv: FloatArray = FULL, n: FloatArray) { out += ShellQuad(p, o, u, v, uv, n) }
        val h = sh.h; val over = sh.eaveOver; val slab = sh.slab; val d = sh.depth
        val w0 = sh.wall0; val w1 = sh.wall1
        val rise = sh.rise; val run = sh.run; val ridge = sh.ridge
        val len = sqrt(run * run + rise * rise); val cy = run / len; val sy = rise / len
        val eaveV = slab / h
        // the walls: the front and the back across the wall's span (the pictures' columns beside it are the verges' fascia)
        q(ShellPart.FRONT, f(w0, h, 0f), f(w1 - w0, 0f, 0f), f(0f, -h, 0f), f(w0 / pw, 0f, w1 / pw, 1f), f(0f, 0f, -1f))
        q(ShellPart.BACK, f(w1, h, d), f(w0 - w1, 0f, 0f), f(0f, -h, 0f), f((pw - w1) / pw, 0f, (pw - w0) / pw, 1f), f(0f, 0f, 1f))
        // the eaves' edges, the slab's thickness at the front and the back, the whole roof's width
        q(ShellPart.EAVE_FRONT, f(0f, h, -over), f(pw, 0f, 0f), f(0f, -slab, 0f), f(0f, 0f, 1f, eaveV), f(0f, 0f, -1f))
        q(ShellPart.EAVE_BACK, f(pw, h, d + over), f(-pw, 0f, 0f), f(0f, -slab, 0f), f(0f, 0f, 1f, eaveV), f(0f, 0f, 1f))
        // under the eaves, from the eave's edge back to the wall (where the wall meets it, under the roof's top)
        val under0 = sh.gableTop(0f)
        q(ShellPart.SOFFIT, f(w0, h - slab, -over), f(w1 - w0, 0f, 0f), f(0f, under0 - (h - slab), over), n = f(0f, -cy, sy))
        q(ShellPart.SOFFIT, f(w1, h - slab, d + over), f(w0 - w1, 0f, 0f), f(0f, under0 - (h - slab), -over), n = f(0f, -cy, -sy))
        // the gable ends at the wall's ends: their pictures reach up to the slab's underside
        for (x in floatArrayOf(w0, w1))
            q(ShellPart.GABLE, f(x, sideH, 0f), f(0f, 0f, d), f(0f, -sideH, 0f), n = f(if (x == w0) -1f else 1f, 0f, 0f))
        // the slopes, from the ridge at mid-depth down to each eave
        q(ShellPart.SLOPE, f(0f, ridge, d / 2f), f(pw, 0f, 0f), f(0f, -rise, -run), n = f(0f, cy, -sy))
        q(ShellPart.SLOPE, f(0f, ridge, d / 2f), f(pw, 0f, 0f), f(0f, -rise, run), n = f(0f, cy, sy))
        for (way in floatArrayOf(-1f, 1f)) {
            // the rake boards: the slab's ends past the gables, from the ridge down to each eave
            q(ShellPart.RAKE, f(0f, ridge, d / 2f), f(0f, -rise, way * run), f(0f, -slab, 0f), n = f(-1f, 0f, 0f))
            q(ShellPart.RAKE, f(pw, ridge, d / 2f), f(0f, -rise, way * run), f(0f, -slab, 0f), n = f(1f, 0f, 0f))
            // under the verges, between the rake boards and the gables
            q(ShellPart.SOFFIT, f(0f, ridge - slab, d / 2f), f(w0, 0f, 0f), f(0f, -rise, way * run), n = f(0f, -cy, -way * sy))
            q(ShellPart.SOFFIT, f(w1, ridge - slab, d / 2f), f(pw - w1, 0f, 0f), f(0f, -rise, way * run), n = f(0f, -cy, -way * sy))
        }
        return out
    }

    private val FULL = floatArrayOf(0f, 0f, 1f, 1f)
    private fun f(vararg v: Float) = v
}

/** Which picture a [BoxPoly] of a shop's or a flat's built parts wears ([BoxShell]). */
enum class BoxPart {
    /** An end wall's face, outside and over the roof: the gable picture (the side wall's top storey carried up). */
    GABLE,
    /** An end wall's coping, from above: the art's capstone (`wcap`). */
    CAP_TOP,
    /** The coping's edges and its underside over the wall: the capstone's own colour, one column of it. */
    CAP_EDGE,
    /** An end wall's pier, at the front and the back: the art's pier (`pier`), its capstone band at its head. */
    PIER,
    /** The top of a pier: the art's capstone (`wcap`). */
    PIER_TOP,
    /** A rake board's top along a gable's slope (the roof's edge colour). */
    RAKE_TOP,
    /** A rake board's faces (its outer and inner sides, its ends at the eaves). */
    RAKE_EDGE,
    /** A rake board's underside, over the gable. */
    RAKE_UNDER,
    /** A balcony's parapet, its faces (`balp`). */
    BAL_PARAPET,
    /** A balcony slab's edge (`bals`). */
    BAL_SLAB,
    /** A balcony's floor, from above (`balf`: the art's tiles). */
    BAL_FLOOR,
    /** The top of a balcony's parapet (its capstone's colour). */
    BAL_CAP,
    /** A balcony slab's underside. */
    BAL_UNDER,
    /** A balcony's brackets under its slab. */
    BAL_BRACKET,
    /** A stone ledge across the front (a sill course, the cornice): the front picture's own rows ([BoxPoly.uv] on it). */
    LEDGE,
}

/**
 * One flat face of a box's built parts, in the box's own frame and sprite pixels (as [ShellQuad]): x across the front
 * picture (0 at its left edge), y up from the ground, d back from the front wall (a balcony stands at d < 0). [p] holds
 * its 3 or 4 corners (x, y, d) in order round it, [uv] their places on the picture (v down it), [n] its outward normal.
 */
class BoxPoly(val part: BoxPart, val p: FloatArray, val uv: FloatArray, val n: FloatArray)

/**
 * What the Filament engines build on a shop's or a flat's box beyond its walls and roof, as closed geometry rather than
 * flat pictures: the stone end walls of the roofs drawn between them (roof_8/9/11), the rake boards along the gables of
 * the roofs without them (roof_2/4/6), and the balconies the art paints flat on a middle floor (middle_2/8/14).
 */
object BoxShell {
    /** How far an end wall stands over the roof, its coping's thickness and overhang, its piers' depth, how far they rise over the coping and stand proud of the wall. */
    const val WALL_UP = 6f
    const val CAP = 4f
    const val CAP_OVER = 2f
    const val PIER_D = 20f
    const val PIER_UP = 8f
    const val PIER_OUT = 1f
    /** The pier picture's height (tools/make_buildings.py: the art's rows 152..224). */
    const val PIER_ROWS = 72f
    /** A rake board's overhang past the gable, its width over the roof, its depth under the slope and its rise over it. */
    const val RAKE_OVER = 2f
    const val RAKE_IN = 3f
    const val RAKE_T = 4f
    const val RAKE_UP = 1f

    /**
     * The roof's height over the front wall's top at [d] back: its ridge at mid-depth [rise] over it, falling to both
     * eaves (the slopes [com.lifemax.rpg.feature.habits.domain.BuildingSpec]'s box wears: no overhang).
     */
    fun slopeAt(d: Float, depth: Float, rise: Float): Float = rise * (1f - kotlin.math.abs(d - depth / 2f) / (depth / 2f))

    /**
     * The two stone end walls of a pitched roof drawn between them (roof_8/9/11): each [w] pixels thick at the box's ends
     * ([pw] wide, its front [h] high, [depth] deep, the ridge [rise] over the front's top), following the roof's slope the
     * whole depth [WALL_UP] over it, capped with an overhanging coping, ending at the front and the back in a square pier
     * that rises over the coping (where the art's posts stand). Every face closes on its neighbours.
     */
    fun endWalls(pw: Float, h: Float, depth: Float, rise: Float, w: Float): List<BoxPoly> {
        val out = ArrayList<BoxPoly>(64)
        val dm = depth / 2f
        val len = sqrt(dm * dm + rise * rise); val cy = dm / len; val sy = rise / len
        fun top(d: Float) = h + slopeAt(d, depth, rise) + WALL_UP
        val pt = slopeAt(PIER_D, depth, rise) + WALL_UP + CAP + PIER_UP
        for (side in 0..1) {
            val xa = if (side == 0) 0f else pw - w; val xb = xa + w
            val xo = if (side == 0) xa else xb; val xi = if (side == 0) xb else xa
            val sgn = if (side == 0) -1f else 1f
            // the wall's faces outside (over the side wall) and inside (over the roof): the gable picture, its rows
            // where the gable's would be (the apex row over it)
            fun gv(y: Float) = (h + rise - y) / rise
            for (x in floatArrayOf(xo, xi)) {
                val nx = if (x == xo) sgn else -sgn
                out += poly(BoxPart.GABLE, f(x, h, 0f, x, h, depth, x, top(depth), depth, x, top(0f), 0f),
                    f(0f, gv(h), 1f, gv(h), 1f, gv(top(depth)), 0f, gv(top(0f))), f(nx, 0f, 0f))
                out += poly(BoxPart.GABLE, f(x, top(0f), 0f, x, top(depth), depth, x, top(dm), dm),
                    f(0f, gv(top(0f)), 1f, gv(top(depth)), .5f, gv(top(dm))), f(nx, 0f, 0f))
            }
            // the coping, from each pier back to the ridge: its top (the art's capstone, its head at the pier), its
            // edges and the underside of its overhang
            val c0 = xa - CAP_OVER; val c1 = xb + CAP_OVER
            for (half in 0..1) {
                val da = if (half == 0) PIER_D else depth - PIER_D
                val ya = top(da); val yr = top(dm)
                val ny = cy; val nd = if (half == 0) -sy else sy
                out += poly(BoxPart.CAP_TOP, f(c0, ya + CAP, da, c1, ya + CAP, da, c1, yr + CAP, dm, c0, yr + CAP, dm),
                    f(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), f(0f, ny, nd))
                for (x in floatArrayOf(c0, c1)) out += poly(BoxPart.CAP_EDGE,
                    f(x, ya + CAP, da, x, yr + CAP, dm, x, yr, dm, x, ya, da), PROFILE, f(if (x == c0) -1f else 1f, 0f, 0f))
                for ((u0, u1) in listOf(c0 to xa, xb to c1)) out += poly(BoxPart.CAP_EDGE,
                    f(u0, ya, da, u1, ya, da, u1, yr, dm, u0, yr, dm), PROFILE, f(0f, -ny, -nd))
            }
            // the piers at the front and the back, standing a pixel proud of the wall and the front, over the coping
            for (end in 0..1) {
                val d0 = if (end == 0) -PIER_OUT else depth - PIER_D
                val d1 = if (end == 0) PIER_D else depth + PIER_OUT
                val v1 = (pt / PIER_ROWS).coerceAtMost(1f)
                box(out, xa - PIER_OUT, xb + PIER_OUT, h, h + pt, d0, d1, BoxPart.PIER, f(0f, 0f, 1f, v1), BoxPart.PIER_TOP, null, true)
            }
        }
        return out
    }

    /**
     * The rake boards along a pitched roof's gables (roof_2/4/6, no end walls): at each end of the box ([pw] wide, its
     * front [h] high, [depth] deep, the ridge [rise] over the front's top) the roof's edge as a board standing out past
     * the gable by [RAKE_OVER], over the roof's edge by [RAKE_IN], from the eave to the ridge on both slopes.
     */
    fun verges(pw: Float, h: Float, depth: Float, rise: Float): List<BoxPoly> {
        val out = ArrayList<BoxPoly>(40)
        val dm = depth / 2f
        val len = sqrt(dm * dm + rise * rise); val cy = dm / len; val sy = rise / len
        fun s(d: Float) = h + slopeAt(d, depth, rise)
        for (side in 0..1) {
            val x0 = if (side == 0) -RAKE_OVER else pw - RAKE_IN
            val x1 = if (side == 0) RAKE_IN else pw + RAKE_OVER
            val xo = if (side == 0) x0 else x1; val xi = if (side == 0) x1 else x0
            val sgn = if (side == 0) -1f else 1f
            for (half in 0..1) {
                val da = if (half == 0) 0f else depth
                val nd = if (half == 0) -sy else sy
                val ya = s(da); val yr = s(dm)
                out += poly(BoxPart.RAKE_TOP, f(x0, ya + RAKE_UP, da, x1, ya + RAKE_UP, da, x1, yr + RAKE_UP, dm, x0, yr + RAKE_UP, dm), PROFILE, f(0f, cy, nd))
                out += poly(BoxPart.RAKE_UNDER, f(x0, ya - RAKE_T, da, x1, ya - RAKE_T, da, x1, yr - RAKE_T, dm, x0, yr - RAKE_T, dm), PROFILE, f(0f, -cy, -nd))
                for (x in floatArrayOf(xo, xi)) out += poly(BoxPart.RAKE_EDGE,
                    f(x, ya + RAKE_UP, da, x, yr + RAKE_UP, dm, x, yr - RAKE_T, dm, x, ya - RAKE_T, da), PROFILE, f(if (x == xo) sgn else -sgn, 0f, 0f))
                // its end at the eave
                out += poly(BoxPart.RAKE_EDGE, f(x0, ya + RAKE_UP, da, x1, ya + RAKE_UP, da, x1, ya - RAKE_T, da, x0, ya - RAKE_T, da), PROFILE,
                    f(0f, 0f, if (half == 0) -1f else 1f))
            }
        }
        return out
    }

    /**
     * A balcony ([b]: tools/make_buildings.py's `_balc`: its x span, its parapet's top, its floor and its slab's foot as
     * rows of the front picture, how far it stands out, its parapet's thickness, three colours, its brackets' x spans,
     * how far down and out they reach) on a front [h] pixels high: the slab with its floor, the parapet round its three
     * open sides, the brackets under it.
     */
    fun balcony(b: FloatArray, h: Float): List<BoxPoly> {
        val out = ArrayList<BoxPoly>(48)
        val x0 = b[0]; val x1 = b[1]; val yTop = h - b[2]; val yFloor = h - b[3]; val yFoot = h - b[4]
        val dep = b[5]; val th = b[6]
        val span = x1 - x0
        // the slab: its edge a pixel past the parapet, its floor the art's tiles, its underside
        box(out, x0, x1, yFoot, yFloor, -dep - 1f, 0f, BoxPart.BAL_SLAB, FULL4, BoxPart.BAL_FLOOR, BoxPart.BAL_UNDER, false, sideUv = PROFILE4)
        // the parapet: along the front, then down each side to the wall
        box(out, x0, x1, yFloor, yTop, -dep, -dep + th, BoxPart.BAL_PARAPET, FULL4, BoxPart.BAL_CAP, null, true,
            sideUv = f(0f, 0f, th / span, 1f))
        for (xs in floatArrayOf(x0, x1 - th))
            box(out, xs, xs + th, yFloor, yTop, -dep + th, 0f, BoxPart.BAL_PARAPET, f(0f, 0f, th / span, 1f), BoxPart.BAL_CAP, null, false,
                sideUv = f(0f, 0f, (dep - th) / span, 1f), front = false)
        // the brackets under the slab
        for ((a, c) in listOf(b[10] to b[11], b[12] to b[13]))
            box(out, a, c, yFoot - b[14], yFoot, -b[15], 0f, BoxPart.BAL_BRACKET, PROFILE4, null, BoxPart.BAL_BRACKET, false)
        return out
    }

    /**
     * The stone ledges the art draws flat across a front [pw] x [h] pixels ([l]: tools/make_buildings.py's `_ledge`, each
     * its x span, its rows of the front picture and how far it stands out): each a long box out of the wall wearing those
     * rows of the front picture on its face, the stone's light row along its top, its shade along its underside and the
     * rows' end columns on its ends.
     */
    fun ledges(l: List<FloatArray>, pw: Float, h: Float): List<BoxPoly> {
        val out = ArrayList<BoxPoly>(l.size * 5)
        for (e in l) {
            val x0 = e[0]; val x1 = e[1]; val r0 = e[2]; val r1 = e[3]; val o = e[4]
            val y0 = h - r1; val y1 = h - r0
            val u0 = x0 / pw; val u1 = x1 / pw; val v0 = r0 / h; val v1 = r1 / h
            val vt = (r0 + 2.5f) / h; val vb = (r1 - 1.5f) / h
            out += poly(BoxPart.LEDGE, f(x0, y1, -o, x1, y1, -o, x1, y0, -o, x0, y0, -o), f(u0, v0, u1, v0, u1, v1, u0, v1), f(0f, 0f, -1f))
            out += poly(BoxPart.LEDGE, f(x0, y1, 0f, x1, y1, 0f, x1, y1, -o, x0, y1, -o), f(u0, vt, u1, vt, u1, vt, u0, vt), f(0f, 1f, 0f))
            out += poly(BoxPart.LEDGE, f(x0, y0, -o, x1, y0, -o, x1, y0, 0f, x0, y0, 0f), f(u0, vb, u1, vb, u1, vb, u0, vb), f(0f, -1f, 0f))
            val ue0 = (x0 + .5f) / pw; val ue1 = (x1 - .5f) / pw
            out += poly(BoxPart.LEDGE, f(x0, y1, 0f, x0, y1, -o, x0, y0, -o, x0, y0, 0f), f(ue0, v0, ue0, v0, ue0, v1, ue0, v1), f(-1f, 0f, 0f))
            out += poly(BoxPart.LEDGE, f(x1, y1, -o, x1, y1, 0f, x1, y0, 0f, x1, y0, -o), f(ue1, v0, ue1, v0, ue1, v1, ue1, v1), f(1f, 0f, 0f))
        }
        return out
    }

    /**
     * An upright box's faces between x [x0]..[x1], y [y0]..[y1], d [d0]..[d1]: its front (d0) and its sides wearing
     * [part] (the front [uv]: u0, v0, u1, v1; the sides [sideUv], the same if null), its back too if [back], its top
     * [top] and its bottom [bottom] where given.
     */
    private fun box(
        out: MutableList<BoxPoly>, x0: Float, x1: Float, y0: Float, y1: Float, d0: Float, d1: Float, part: BoxPart, uv: FloatArray,
        top: BoxPart?, bottom: BoxPart?, back: Boolean, sideUv: FloatArray? = null, front: Boolean = true,
    ) {
        val su = sideUv ?: uv
        fun r(q: FloatArray) = f(q[0], q[1], q[2], q[1], q[2], q[3], q[0], q[3])
        if (front) out += poly(part, f(x0, y1, d0, x1, y1, d0, x1, y0, d0, x0, y0, d0), r(uv), f(0f, 0f, -1f))
        if (back) out += poly(part, f(x1, y1, d1, x0, y1, d1, x0, y0, d1, x1, y0, d1), r(uv), f(0f, 0f, 1f))
        out += poly(part, f(x0, y1, d1, x0, y1, d0, x0, y0, d0, x0, y0, d1), r(su), f(-1f, 0f, 0f))
        out += poly(part, f(x1, y1, d0, x1, y1, d1, x1, y0, d1, x1, y0, d0), r(su), f(1f, 0f, 0f))
        if (top != null) out += poly(top, f(x0, y1, d1, x1, y1, d1, x1, y1, d0, x0, y1, d0), r(FULL4), f(0f, 1f, 0f))
        if (bottom != null) out += poly(bottom, f(x0, y0, d0, x1, y0, d0, x1, y0, d1, x0, y0, d1), r(FULL4), f(0f, -1f, 0f))
    }

    /** [rgb] (0xRRGGBB) darkened to [f] of itself. */
    fun shade(rgb: Int, f: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * f).toInt().coerceIn(0, 255) shl s
        return ch(16) or ch(8) or ch(0)
    }

    private fun poly(part: BoxPart, p: FloatArray, uv: FloatArray, n: FloatArray) = BoxPoly(part, p, if (uv.size * 3 == p.size * 2) uv else uv.copyOf(p.size / 3 * 2), n)

    private val FULL4 = floatArrayOf(0f, 0f, 1f, 1f)
    /** One column of a picture, top to bottom (its rows as bands along the face). */
    private val PROFILE4 = floatArrayOf(.5f, 0f, .5f, 1f)
    private val PROFILE = floatArrayOf(.5f, 0f, .5f, 0f, .5f, 1f, .5f, 1f)
    private fun f(vararg v: Float) = v
}
