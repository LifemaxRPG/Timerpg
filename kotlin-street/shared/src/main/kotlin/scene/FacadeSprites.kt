package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A wall's surface, painted once per material as a seamless tile ([FacadeSprites]): [w] by [h]
 * pixels at [FacadeSprites.PPU] pixels a unit, opaque, repeated over a floor's front with its
 * top-left corner at the floor's. The tile has no light of its own but the small shade of its
 * joints and its grain, so the sun's wash and the lamps' pools drawn over it read the same as they
 * do on a flat wall; its mean colour is the material's wall colour of the palette.
 */
internal class WallTile(val w: Int, val h: Int, val pixels: IntArray) {
    val unitsW: Float
        get() = w / FacadeSprites.PPU

    val unitsH: Float
        get() = h / FacadeSprites.PPU
}

/**
 * A strip laid over a wall ([FacadeSprites.grime], [FacadeSprites.drip]): a colour at an alpha,
 * kept as two layers (here the same picture).
 */
internal class ClothTile(val w: Int, val h: Int, val shade: IntArray, val light: IntArray)

/**
 * The facades' baked surfaces. Each material is a function of fixed seeds, so the pixels are the
 * same on every run and every device; all of it is pure Kotlin and is tested and looked at on a
 * plain JVM.
 */
internal object FacadeSprites {
    /** Pixels in a unit (dp at scale 1): three, as the rooms behind the windows. */
    const val PPU = 3f

    /** A brick's course and length, a stone's, a gold panel's, in units. */
    const val BRICK_W = 14f
    const val COURSE = 7f
    const val STONE_W = 30f
    const val STONE_H = 14f
    const val PANEL_W = 46f
    const val PANEL_H = 21f

    /** The tile each material repeats in, in units: whole bricks, stones or panels. */
    fun tileUnits(m: HabitMaterial): Pair<Float, Float> =
        when (m) {
            HabitMaterial.CLAY -> 56f to 42f
            HabitMaterial.STONE -> 60f to 56f
            HabitMaterial.GOLD -> 46f to 42f
            HabitMaterial.GLASS -> 32f to 32f
            HabitMaterial.FADE -> 64f to 52f
        }

    /** The lobby's granite tile, in units: two slabs across, two courses down. */
    const val LOBBY_W = 46f
    const val LOBBY_H = 27f

    /**
     * The weathering hung under every floor's string course ([grime]): a strip this long and as
     * deep as the tallest floor, in units, at [GRIME_PPU] pixels a unit (it is all soft).
     */
    const val GRIME_W = 128f
    const val GRIME_H = 84f
    const val GRIME_PPU = 1.5f

    /** One rain streak run off a sill's end ([drip]), in units, at [PPU]. */
    const val DRIP_W = 6f
    const val DRIP_H = 30f

    /** The soot and the rain streaks' colour: a dark warm grey-brown. */
    private val GRIME_RGB = floatArrayOf(.13f, .11f, .085f)

    /** Bytes all five tiles, the lobby's, the weathering and the drip take once made. */
    fun bytes(): Int =
        HabitMaterial.entries.sumOf {
            val (uw, uh) = tileUnits(it)
            (uw * PPU).toInt() * (uh * PPU).toInt() * 4
        } +
            (LOBBY_W * PPU).toInt() * (LOBBY_H * PPU).toInt() * 4 +
            (GRIME_W * GRIME_PPU).toInt() * (GRIME_H * GRIME_PPU).toInt() * 4 +
            (DRIP_W * PPU).toInt() * (DRIP_H * PPU).toInt() * 4

    fun tile(m: HabitMaterial): WallTile {
        val (uw, uh) = tileUnits(m)
        val w = (uw * PPU).toInt()
        val h = (uh * PPU).toInt()
        val px = IntArray(w * h)
        val f = facade(m)
        val base = rgb(f.wallTop, f.wallBottom)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / PPU
            val v = (y + .5f) / PPU
            val c =
                when (m) {
                    HabitMaterial.CLAY -> brick(u, v, uw, uh, base)
                    HabitMaterial.STONE -> ashlar(u, v, uw, uh, base, false)
                    HabitMaterial.GOLD -> gold(u, v, uw, uh, base)
                    HabitMaterial.GLASS -> spandrel(u, v, uw, uh, base)
                    HabitMaterial.FADE -> render(u, v, uw, uh, base)
                }
            px[y * w + x] = Px.argbF(1f, c[0], c[1], c[2])
        }
        return WallTile(w, h, px)
    }

    /**
     * The lobby's wall: polished dark granite slabs, two across and two courses down a tile, each
     * its own tone with a fine speckle of feldspar and mica, a soft sheen down its face, a hairline
     * joint between them. Seamless, opaque, its mean close to the old flat lobby wall's colour.
     */
    fun lobbyTile(): WallTile {
        val w = (LOBBY_W * PPU).toInt()
        val h = (LOBBY_H * PPU).toInt()
        val px = IntArray(w * h)
        val c = SceneInk.LobbyWall
        val base = floatArrayOf(c.red, c.green, c.blue)
        val sw = LOBBY_W / 2f
        val sh = LOBBY_H / 2f
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / PPU
            val v = (y + .5f) / PPU
            val row = floor(v / sh).toInt()
            val bu = wrap(u + if (row % 2 == 0) 0f else sw / 2f, LOBBY_W)
            val col = floor(bu / sw).toInt()
            val fx = bu - col * sw
            val fy = v - row * sh
            val out: FloatArray
            if (fx < .55f || fy < .55f) {
                out = scaled(base, .62f)
            } else {
                val tone = .95f + .1f * hash(col, row, 201)
                val cool = (hash(col, row, 203) - .5f) * .06f
                val speck = Noise.value(x * .9f, y * .9f, 207)
                val fine = seamless(u * 3f, v * 3f, LOBBY_W * 3f, LOBBY_H * 3f, 209, 1f, 2)
                var k = tone * (.95f + .1f * fine)
                if (speck > .86f) k *= 1.22f else if (speck < .1f) k *= .82f
                // the polish: the sky's light down the slab's face, its top edge catching it
                k *= 1.05f - .08f * (fy / sh)
                if (fy < 1.1f) k *= 1.06f
                out =
                    floatArrayOf(
                        minOf(1f, base[0] * k * (1f - cool)),
                        minOf(1f, base[1] * k),
                        minOf(1f, base[2] * k * (1f + cool)),
                    )
            }
            px[y * w + x] = Px.argbF(1f, out[0], out[1], out[2])
        }
        return WallTile(w, h, px)
    }

    /**
     * The weathering of a wall, laid over a floor's front from its top (under its string course):
     * soot gathered under the ledge, uneven along it, and the streaks the rain has washed down from
     * it over the years, each its own width, length and depth, wandering a little as water does,
     * fading out down the wall; a fine vertical striation of grime between them. A colour at an
     * alpha (the same colour throughout), seamless across, clear on its last row so it can be
     * clamped below.
     */
    fun grime(): ClothTile {
        val w = (GRIME_W * GRIME_PPU).toInt()
        val h = (GRIME_H * GRIME_PPU).toInt()
        val keep = FloatArray(w * h) { 1f }
        // the streaks, combined as layers of a stain (each takes a share of what light is left)
        for (i in 0 until GRIME_STREAKS) {
            val x0 = hash(i, 0, 801) * GRIME_W
            val wide = .7f + 3.4f * hash(i, 1, 801).sq()
            val len = 7f + (GRIME_H - 12f) * Math.pow(hash(i, 2, 801).toDouble(), 1.15).toFloat()
            val depth = .06f + .2f * hash(i, 3, 801)
            val phase = hash(i, 4, 801) * 6.283f
            for (y in 0 until h) {
                val v = (y + .5f) / GRIME_PPU
                if (v >= len) break
                val fade = Math.pow((1f - v / len).toDouble(), 1.4).toFloat()
                val cx =
                    x0 +
                        .3f * kotlin.math.sin(v * .12f + phase) +
                        .12f * kotlin.math.sin(v * .43f + phase * 1.7f)
                val ww = wide * (1f - .45f * v / len)
                val stutter = .7f + .3f * Noise.value(i * 5.3f, v * .45f, 811)
                val reach = ((ww + 1f) * GRIME_PPU).toInt() + 1
                val px = (cx * GRIME_PPU).toInt()
                for (dx in -reach..reach) {
                    val x = ((px + dx) % w + w) % w
                    val u = (x + .5f) / GRIME_PPU
                    var d = abs(u - cx)
                    if (d > GRIME_W / 2f) d = GRIME_W - d
                    val prof = Px.smooth(ww, 0f, d)
                    if (prof <= 0f) continue
                    keep[y * w + x] *= 1f - depth * fade * prof * stutter
                }
            }
        }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / GRIME_PPU
            val v = (y + .5f) / GRIME_PPU
            // the soot under the ledge, heavier in places along it
            val along = seamless(u * .3f, 0f, GRIME_W * .3f, 1f, 821, 1f, 2)
            val soot = .26f * kotlin.math.exp(-v / 4.5f) * (.45f + 1.1f * along)
            // the fine striation, fading down the strip
            val fine = seamless(u * 1.4f, v * .06f, GRIME_W * 1.4f, GRIME_H * .06f, 823, 1f)
            val stria = .07f * Px.smooth(.52f, .85f, fine) * (1f - .7f * v / GRIME_H)
            var a = 1f - keep[y * w + x] * (1f - soot.coerceIn(0f, .5f)) * (1f - stria)
            a *= Px.smooth(GRIME_H, GRIME_H - 8f, v)
            if (y == h - 1) a = 0f
            px[y * w + x] = Px.argbF(a.coerceIn(0f, .6f), GRIME_RGB[0], GRIME_RGB[1], GRIME_RGB[2])
        }
        return ClothTile(w, h, px, px)
    }

    /**
     * A rain streak run off the end of a sill: a bead of damp at its top, a thin wandering trail
     * narrowing and fading down the wall, a faint wider halo round it. A colour at an alpha, clear
     * at its sides and on its last row.
     */
    fun drip(): ClothTile {
        val w = (DRIP_W * PPU).toInt()
        val h = (DRIP_H * PPU).toInt()
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / PPU
            val v = (y + .5f) / PPU
            val t = v / DRIP_H
            val cx = DRIP_W / 2f + .45f * kotlin.math.sin(v * .33f) + .2f * kotlin.math.sin(v * .9f)
            val ww = if (v < 1.4f) 1.3f else 1.05f * (1f - .55f * t)
            val d = abs(u - cx)
            val core =
                .55f * Math.pow((1f - t).toDouble(), 1.2).toFloat() * Px.smooth(ww, ww * .3f, d)
            val halo = .14f * (1f - t).sq() * Px.smooth(minOf(ww * 2.6f, 2.4f), ww, d)
            var a = 1f - (1f - core) * (1f - halo)
            if (y == h - 1 || x == 0 || x == w - 1) a = 0f
            px[y * w + x] = Px.argbF(a, GRIME_RGB[0], GRIME_RGB[1], GRIME_RGB[2])
        }
        return ClothTile(w, h, px, px)
    }

    /** How many rain streaks hang in the weathering strip. */
    private const val GRIME_STREAKS = 54

    // ------------------------------------------------------------------ the materials

    private fun brick(u: Float, v: Float, tw: Float, th: Float, base: FloatArray): FloatArray {
        val row = floor(v / COURSE).toInt()
        val shift = if (row % 2 == 0) 0f else BRICK_W / 2f
        val bu = wrap(u + shift, tw)
        val col = floor(bu / BRICK_W).toInt()
        val fx = bu - col * BRICK_W
        val fy = v - row * COURSE
        val id = hash(col, row, 3)
        val mortar = fx < MORTAR || fy < MORTAR
        val grain = seamless(u, v, tw, th, 11, 1.1f)
        if (mortar) {
            // mortar: a pale warm grey, grained; a shadow row under the brick above
            val m = mortarColour(base)
            val shadow = if (fy < MORTAR && fy >= MORTAR * .5f) .86f else 1f
            val under = if (fy < MORTAR * .5f) .9f else 1f
            return scaled(m, (.84f + .14f * grain) * shadow * under)
        }
        // each brick its own: a little redder or browner, a few burnt, a few sandy
        val tone = .88f + .22f * hash(col, row, 5)
        val warm = (hash(col, row, 7) - .5f) * .12f
        val kind = hash(col, row, 9)
        var r = base[0] * (1f + warm)
        var g = base[1] * (1f - warm * .3f)
        var b = base[2] * (1f - warm * .8f)
        var k = tone
        if (kind < .07f) k *= .72f
        else if (kind < .11f) {
            k *= 1.1f
            r = r * .9f + .1f * .85f
            g = g * .8f + .2f * .7f
            b = b * .7f + .3f * .5f
        }
        // the face: fine grain, a shade into the joint on every edge (ambient occlusion), the
        // top edge a touch lighter where the sky reaches it
        val face = seamless(u * 2.3f, v * 2.3f, tw * 2.3f, th * 2.3f, 23, 1f)
        k *= .95f + .1f * face + .06f * (grain - .5f)
        val ex = minOf(fx - MORTAR, BRICK_W - fx)
        val ey = minOf(fy - MORTAR, COURSE - fy)
        val edge = minOf(ex, ey)
        k *= .84f + .16f * Px.smooth(0f, 1.2f, edge)
        if (fy - MORTAR < .45f) k *= 1.07f
        // a chipped corner on a few
        if (hash(col, row, 13) < .1f) {
            val cx = if (hash(col, row, 15) < .5f) fx - MORTAR else BRICK_W - fx
            val cy = if (hash(col, row, 17) < .5f) fy - MORTAR else COURSE - fy
            if (cx + cy * 1.6f < 2.2f) k *= .78f
        }
        // weathering: faint vertical streaks of soot
        val streak = seamless(u * .35f, v * .05f, tw * .35f, th * .05f, 31, 1f)
        k *= 1f - .08f * Px.smooth(.55f, .9f, streak)
        return floatArrayOf(r * k, g * k, b * k)
    }

    private fun ashlar(
        u: Float,
        v: Float,
        tw: Float,
        th: Float,
        base: FloatArray,
        warm: Boolean,
    ): FloatArray {
        val row = floor(v / STONE_H).toInt()
        val shift = if (row % 2 == 0) 0f else STONE_W / 2f
        val bu = wrap(u + shift, tw)
        val col = floor(bu / STONE_W).toInt()
        val fx = bu - col * STONE_W
        val fy = v - row * STONE_H
        if (fx < JOINT || fy < JOINT) {
            // a recessed joint: dark in its depth, the sky catching the block's top edge above it
            val m = scaled(base, .7f)
            val depth = if (fx < JOINT) fx / JOINT else fy / JOINT
            return scaled(m, .9f + .25f * depth)
        }
        val tone = 1.01f + .06f * hash(col, row, 41)
        // veins: a few thin lines, faint, running a little off the horizontal, and a soft cloud
        // of tone across the block
        val vein = ridged(u * .12f, v * .55f, tw * .12f, th * .55f, 47)
        val veins = Px.smooth(.9f, .99f, vein) * .07f
        val cloud = seamless(u * .3f, v * .3f, tw * .3f, th * .3f, 49, 1f, 2)
        val grain = seamless(u * 2.7f, v * 2.7f, tw * 2.7f, th * 2.7f, 53, 1f)
        var k = tone * (.985f + .03f * grain + .05f * (cloud - .5f)) * (1f - veins)
        // the bevel: the top and left edges lit, the bottom and right in shade, over a unit
        val top = Px.smooth(1.1f, 0f, fy - JOINT)
        val left = Px.smooth(1.1f, 0f, fx - JOINT)
        val bottom = Px.smooth(1.1f, 0f, STONE_H - fy)
        val right = Px.smooth(1.1f, 0f, STONE_W - fx)
        k *= 1f + .09f * maxOf(top, left * .6f) - .1f * maxOf(bottom, right * .6f)
        // a tooled margin round each block, slightly lighter, the middle a touch rougher
        val margin = minOf(fx - JOINT, fy - JOINT, STONE_W - fx, STONE_H - fy)
        if (margin in 1.2f..2.4f) k *= 1.025f
        // each block a little warmer or cooler than the next, as cut stone is
        val hue = (hash(col, row, 43) - .5f) * if (warm) .07f else .045f
        val c = scaled(base, k)
        return floatArrayOf(minOf(1f, c[0] * (1f + hue)), c[1], minOf(1f, c[2] * (1f - hue)))
    }

    private fun gold(u: Float, v: Float, tw: Float, th: Float, base: FloatArray): FloatArray {
        val col = floor(u / PANEL_W).toInt()
        val row = floor(v / PANEL_H).toInt()
        val fx = u - col * PANEL_W
        val fy = v - row * PANEL_H
        // the seams between panels: a dark hairline, the next panel's edge catching light
        if (fx < SEAM || fy < SEAM) {
            val d = if (fx < SEAM) fx / SEAM else fy / SEAM
            return scaled(base, .52f + .28f * abs(2f * d - 1f))
        }
        // polished metal: each panel mirrors a pale sky above a soft horizon and the warm ground
        // under it, the horizon at its own height on each; brushed sideways, a rivet in each
        // corner
        val py = (fy - SEAM) / (PANEL_H - SEAM)
        val px = (fx - SEAM) / (PANEL_W - SEAM)
        val horizon = .42f + .2f * hash(col, row, 57)
        val sky = Px.smooth(horizon + .12f, horizon - .18f, py)
        val brushed = seamless(u * .6f, v * 9f, tw * .6f, th * 9f, 61, 1f)
        val haze = seamless(u * .2f, v * .3f, tw * .2f, th * .3f, 67, 1f, 2)
        var k = .9f + .1f * sky + .05f * (brushed - .5f) + .1f * (haze - .5f)
        // a soft slanting band of brighter reflection, where this panel happens to catch it
        val at = .25f + .5f * hash(col, row, 59)
        val band = Px.smooth(.2f, 0f, abs(px * .35f + py * .65f - at))
        k += .07f * band
        // the panel's edges: a thin bevel all round, the top edge the brightest
        if (fy - SEAM < .8f) k *= 1.12f else if (fx - SEAM < .8f) k *= 1.06f
        if (PANEL_W - fx < .8f || PANEL_H - fy < .8f) k *= .95f
        // the sky's reflection pale and a little cool, the ground's warm gold
        val r = Px.mix(base[0] * .98f, minOf(1f, base[0] * 1.03f + .02f), sky)
        val g = Px.mix(base[1] * .9f, minOf(1f, base[1] * 1.02f + .02f), sky)
        val b = Px.mix(base[2] * .62f, minOf(1f, base[2] * 1.02f + .04f), sky)
        var out = floatArrayOf(r * k, g * k, b * k)
        for (cx in floatArrayOf(SEAM + 2f, PANEL_W - 2f)) for (cy in
            floatArrayOf(SEAM + 2f, PANEL_H - 2f)) {
            val d = sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy))
            if (d < .9f) {
                val dome = Px.smooth(.9f, .3f, d)
                val lit = Px.smooth(.9f, 0f, sqrt((fx - cx + .25f).sq() + (fy - cy + .25f).sq()))
                out = scaled(out, .84f + .3f * lit * dome + .06f * dome)
            }
        }
        return out
    }

    private fun spandrel(u: Float, v: Float, tw: Float, th: Float, base: FloatArray): FloatArray {
        // steel spandrel panels, brushed, a seam and a row of bolts every half tile
        val sy = wrap(v, th / 2f)
        val sx = wrap(u, tw / 2f)
        if (sy < .7f) return scaled(base, .62f + .3f * (sy / .7f))
        val brushed = seamless(u * .5f, v * 10f, tw * .5f, th * 10f, 71, 1f)
        val haze = seamless(u * .3f, v * .3f, tw * .3f, th * .3f, 73, 1f)
        var k = .94f + .08f * (brushed - .5f) + .1f * (haze - .5f)
        if (sy < 1.3f) k *= 1.08f
        // a bolt head
        val bx = sx - tw / 4f
        val by = sy - th / 4f
        val d = sqrt(bx * bx + by * by)
        if (d < 1.1f) k *= .8f + .4f * Px.smooth(1.1f, .2f, sqrt((bx + .3f).sq() + (by + .3f).sq()))
        // a rust streak under a few bolts
        val rust =
            Px.smooth(.5f, 0f, abs(bx)) *
                Px.smooth(0f, 4f, by) *
                .35f *
                hash(floor(u / (tw / 2f)).toInt(), floor(v / (th / 2f)).toInt(), 75)
        val c = scaled(base, k)
        return floatArrayOf(
            Px.mix(c[0], .45f, rust * (1f - by / 8f).coerceIn(0f, 1f)),
            Px.mix(c[1], .3f, rust * (1f - by / 8f).coerceIn(0f, 1f)),
            Px.mix(c[2], .2f, rust * (1f - by / 8f).coerceIn(0f, 1f)),
        )
    }

    private fun render(u: Float, v: Float, tw: Float, th: Float, base: FloatArray): FloatArray {
        // run-down render: stucco grain, damp patches, flaking down to the brick, hairline
        // cracks, grime running down
        val stucco = seamless(u * 2f, v * 2f, tw * 2f, th * 2f, 81, 1f)
        val damp = seamless(u * .12f, v * .1f, tw * .12f, th * .1f, 83, 1f, 2)
        val flake = seamless(u * .07f, v * .09f, tw * .07f, th * .09f, 89, 1f, 2)
        val grime = seamless(u * .4f, v * .04f, tw * .4f, th * .04f, 97, 1f)
        val crack = ridged(u * .1f, v * .09f, tw * .1f, th * .09f, 101)
        var k = 1.04f + .08f * (stucco - .5f)
        k *= 1f - .12f * Px.smooth(.5f, .85f, damp)
        k *= 1f - .08f * Px.smooth(.5f, .9f, grime)
        var c = scaled(base, k)
        val gone = Px.smooth(.69f, .73f, flake)
        if (gone > 0f) {
            // the render has fallen off: a rim of broken edge, old brick behind it
            val brickBase = floatArrayOf(base[0] * 1.35f, base[1] * .95f, base[2] * .75f)
            val b = brick(u, v, tw, th, brickBase)
            val shaded = scaled(b, .9f)
            c =
                floatArrayOf(
                    Px.mix(c[0], shaded[0], gone),
                    Px.mix(c[1], shaded[1], gone),
                    Px.mix(c[2], shaded[2], gone),
                )
        } else if (flake > .6f) {
            // the edge of the patch, a touch lighter where the render is thin
            c = scaled(c, 1f + .08f * Px.smooth(.6f, .69f, flake))
        }
        if (crack > .982f && gone <= 0f) c = scaled(c, .7f + .3f * Px.smooth(.995f, .982f, crack))
        return c
    }

    // ------------------------------------------------------------------ the toolbox

    /** A unit of mortar between bricks, the joint between stones, the seam between panels. */
    const val MORTAR = 1f
    const val JOINT = 1f
    const val SEAM = .6f

    private fun wrap(x: Float, period: Float): Float {
        val r = x - floor(x / period) * period
        return if (r < 0f) r + period else r
    }

    private fun Float.sq() = this * this

    private fun hash(a: Int, b: Int, salt: Int): Float {
        var n = a * 374761393 + b * 668265263 + salt * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777216f
    }

    /**
     * Value noise over a torus [tw] by [th]: the four copies of [Noise.value] a tile apart, blended
     * by where in the tile the point is, so the tile's edges meet without a seam.
     */
    private fun seamless(
        x: Float,
        y: Float,
        tw: Float,
        th: Float,
        seed: Int,
        s: Float,
        octaves: Int = 3,
    ): Float {
        val fx = wrap(x, tw) / tw
        val fy = wrap(y, th) / th
        val px = wrap(x, tw) * s
        val py = wrap(y, th) * s
        val a = Noise.fbm(px, py, seed, octaves)
        val b = Noise.fbm(px - tw * s, py, seed, octaves)
        val c = Noise.fbm(px, py - th * s, seed, octaves)
        val d = Noise.fbm(px - tw * s, py - th * s, seed, octaves)
        val top = a * (1f - fx) + b * fx
        val bottom = c * (1f - fx) + d * fx
        return top * (1f - fy) + bottom * fy
    }

    /** Ridged noise over the same torus: thin bright lines where the noise crosses its middle. */
    private fun ridged(x: Float, y: Float, tw: Float, th: Float, seed: Int): Float {
        val n = seamless(x, y, tw, th, seed, 1f, 1)
        return 1f - abs(n * 2f - 1f)
    }

    private fun rgb(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
        floatArrayOf((a.red + b.red) / 2f, (a.green + b.green) / 2f, (a.blue + b.blue) / 2f)

    private fun scaled(c: FloatArray, k: Float) =
        floatArrayOf(minOf(1f, c[0] * k), minOf(1f, c[1] * k), minOf(1f, c[2] * k))

    private fun mortarColour(base: FloatArray) =
        floatArrayOf(
            Px.mix(base[0], .86f, .55f),
            Px.mix(base[1], .78f, .55f),
            Px.mix(base[2], .68f, .55f),
        )
}
