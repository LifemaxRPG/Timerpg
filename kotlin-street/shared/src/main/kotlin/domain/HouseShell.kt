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
