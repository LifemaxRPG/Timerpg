package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.domain.Trees
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/**
 * What the seasons let fall over the street, drawn in street units after everything that stands on
 * it: autumn leaves tumbling from the crowns (turning as they fall, fluttering side to side,
 * drifting with the wind the trees sway in, sliding a little where they land on the pavement and
 * fading there), blossom petals in spring, and snow in winter at two depths, slow, leaning with the
 * wind. Every particle is a function of its seed and of [seconds] ([Ambient.hash]), so nothing is
 * kept from frame to frame and nothing is made per frame; at 0 seconds (motion reduced) it is a
 * still scatter, with nothing moving.
 */
internal class SeasonFall {
    private var key: SkyColors? = null
    private var leafTint: ColorFilter? = null
    private var flakeTint: ColorFilter? = null

    /** The leaves and the petals take the hour's light; the snow its colour. */
    private fun tints(sky: SkyColors) {
        if (key === sky) return
        val dark = sky.dark
        val light = lerp(Color.White, Color(0xFF5A6E94), Px.smooth(.1f, .95f, dark))
        leafTint =
            ColorFilter.tint(
                lerp(light, Color(0xFFFFD0A0), Px.smooth(.2f, .5f, dark) * .35f),
                BlendMode.Modulate,
            )
        flakeTint =
            ColorFilter.tint(
                lerp(Color(0xFFF8FAFF), Color(0xFFA8B8D8), Px.smooth(.15f, .9f, dark)),
                BlendMode.SrcIn,
            )
        key = sky
    }

    /**
     * The season's fall over the street from [from] to [to] (street units), the view reaching up to
     * [top] (negative: above the pavement line) and down to [depth]. The leaves and petals come
     * from the trees in the parks of [layouts] (at [street]'s centres) and on the pavement.
     */
    fun DrawScope.particles(
        season: SeasonState,
        layouts: List<BuildingLayout>,
        street: StreetLayout,
        from: Float,
        to: Float,
        top: Float,
        depth: Float,
        seconds: Float,
        sky: SkyColors,
    ) {
        if (season.quiet) return
        tints(sky)
        val t = if (seconds > 0f) seconds else STILL
        if (season.autumn > .01f || season.blossom > .01f) {
            for (i in layouts.indices) {
                val cx = street.centers[i]
                val trees = layouts[i].trees
                for (k in trees.indices) {
                    val x = cx + trees[k].x
                    if (x < from - 40f || x > to + 40f) continue
                    fromTree(x, trees[k].id.hashCode(), .8f, season, t)
                }
            }
            for (k in street.furniture.indices) {
                val f = street.furniture[k]
                if (f.kind != FurnitureKind.TREE || f.x < from - 40f || f.x > to + 40f) continue
                fromTree(f.x, f.seed, 1f, season, t)
            }
        }
        if (season.snowfall > .01f) snowfall(season.snowfall, from, to, top, depth, t)
    }

    /** The leaves or the petals of the tree at [x] (seeded [seed], drawn at [size]). */
    private fun DrawScope.fromTree(x: Float, seed: Int, size: Float, s: SeasonState, t: Float) {
        val variant = Trees.variant(seed)
        if (Trees.evergreen(variant)) return
        val shape = Trees.shapes[variant]
        val wind = Ambient.sway(seed, t)
        val red = if (Trees.turnsRed(variant)) 1 else 0
        // leaves fall while there are leaves to fall and the tree has turned
        val leaves = ceil(LEAVES * s.autumn * minOf(1f, s.leaf * 2.5f) * size).toInt()
        for (k in 0 until leaves) {
            val img = SceneSprites.leaves[(k % SeasonSprites.LEAF_SHAPES) * 2 + red]
            particle(
                x,
                seed,
                k,
                shape.halfWidth * size,
                shape.height * size,
                wind,
                t,
                3.4f,
                2.6f,
                4f,
                img,
                SeasonSprites.LEAF,
                size / SeasonSprites.LEAF_PPU,
                leafTint,
                1f,
            )
        }
        if (s.blossom > .01f && Trees.blossoms(variant)) {
            val petals = ceil(PETALS * s.blossom * size).toInt()
            val img = SceneSprites.petals[if (variant == 7) 1 else 0]
            for (k in 0 until petals) particle(
                x,
                seed + 977,
                k,
                shape.halfWidth * size,
                shape.height * size,
                wind,
                t,
                5f,
                3f,
                6f,
                img,
                SeasonSprites.PETAL,
                size / SeasonSprites.PETAL_PPU,
                leafTint,
                .9f,
            )
        }
    }

    /**
     * One falling thing, slot [k] of the tree at [x]: it starts somewhere in the crown ([halfWidth]
     * by [height]), falls for [fall] seconds and a little more, fluttering [flutter] units to each
     * side, lies on the pavement sliding with the wind and fades, then starts again; every slot in
     * its own phase, so the fall is continuous.
     */
    private fun DrawScope.particle(
        x: Float,
        seed: Int,
        k: Int,
        halfWidth: Float,
        height: Float,
        wind: Float,
        t: Float,
        fall: Float,
        spread: Float,
        flutter: Float,
        image: ImageBitmap,
        px: Int,
        scale: Float,
        tint: ColorFilter?,
        alpha: Float,
    ) {
        fun h(c: Int) = Ambient.hash(seed, k, 200 + c)
        val period = fall + spread * h(1)
        val cycle = period + REST
        val local = (t + h(2) * cycle) % cycle
        val x0 = x + (h(3) - .5f) * 2f * halfWidth * .75f
        val y0 = -height * (.45f + .5f * h(4))
        val land = 2f + 11f * h(5)
        val side = (h(8) - .5f) * 8f
        val rate = 7f + 4f * h(6)
        val phase = h(7) * 6.28f
        val size = scale * (.8f + .4f * h(10))
        val spin = (h(9) - .5f) * 2f * 480f
        val px0: Float
        val py: Float
        val rot: Float
        val a: Float
        if (local < period) {
            val u = local / period
            val flap = sin(u * rate + phase)
            px0 = x0 + side * u + wind * 3f * u * u + flap * flutter
            py = y0 + (land - y0) * u
            rot = u * spin + flap * 28f
            a = alpha
        } else {
            val v = (local - period) / (cycle - period)
            val flap = sin(rate + phase)
            px0 = x0 + side + wind * 3f + flap * flutter + 4f * Px.smooth(0f, .6f, v) * wind / 2f
            py = land
            rot = spin + flap * 28f
            a = alpha * (1f - Px.smooth(.55f, 1f, v))
        }
        if (a <= .01f) return
        withTransform({
            translate(px0, py)
            rotate(rot, Offset.Zero)
            scale(size, size, Offset.Zero)
        }) {
            drawImage(image, Offset(-px / 2f, -px / 2f), alpha = a, colorFilter = tint)
        }
    }

    /**
     * Snow falling over the whole view at [intensity]: two depths of flakes, the near ones larger,
     * faster and brighter; each flake falls its own speed, wavers, and leans with the wind.
     */
    private fun DrawScope.snowfall(
        intensity: Float,
        from: Float,
        to: Float,
        top: Float,
        depth: Float,
        t: Float,
    ) {
        val wind = Ambient.sway(0, t)
        val reach = depth - top
        // not over the zoomed-out street: it would be a haze of thousands
        if (reach <= 0f || to - from > 1500f) return
        val image = SceneSprites.flake
        var tile = floor(from / TILE).toInt()
        val last = ceil(to / TILE).toInt()
        while (tile <= last) {
            for (layer in 0..1) {
                val near = layer == 1
                val n = ceil((if (near) NEAR_FLAKES else FAR_FLAKES) * intensity).toInt()
                val speed = if (near) 13f else 7f
                val size = (if (near) 2.6f else 1.5f) / SeasonSprites.FLAKE
                val alpha = if (near) .88f else .6f
                for (j in 0 until n) {
                    val hx = Ambient.hash(tile, j, 301 + layer)
                    val hy = Ambient.hash(tile, j, 303 + layer)
                    val hs = Ambient.hash(tile, j, 305 + layer)
                    val y = top + (t * speed * (.8f + .4f * hs) + hy * reach) % reach
                    val x =
                        tile * TILE +
                            hx * TILE +
                            sin(t * (1.1f + hs) + j) * (1.5f + layer) +
                            wind * (y - top) * .04f
                    if (x < from - 4f || x > to + 4f) continue
                    withTransform({
                        translate(x, y)
                        scale(size * (.8f + .4f * hx), size * (.8f + .4f * hx), Offset.Zero)
                    }) {
                        drawImage(
                            image,
                            Offset(-SeasonSprites.FLAKE / 2f, -SeasonSprites.FLAKE / 2f),
                            alpha = alpha * (.7f + .3f * abs(sin(t * .7f + j))),
                            colorFilter = flakeTint,
                        )
                    }
                }
            }
            tile++
        }
    }

    companion object {
        /** The moment the still picture shows (motion reduced): a scatter, nothing moving. */
        const val STILL = 11.3f

        /** The most leaves a full-size tree lets fall at once, and petals. */
        const val LEAVES = 11f
        const val PETALS = 9f

        /** How long a fallen leaf lies before its slot falls again, in seconds. */
        const val REST = 2.6f

        /** The snow falls in tiles this wide (street units), each with its own flakes. */
        const val TILE = 96f
        const val NEAR_FLAKES = 18f
        const val FAR_FLAKES = 32f
    }
}
