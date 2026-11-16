package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What stands in a slot of the near layer. */
internal enum class ForegroundKind {
    RAIL,
    BOLLARDS,
    POST,
}

/**
 * Where the near things go: pure arithmetic, so the layer is the same every frame and every run.
 * The street is cut into slots of [SLOT] units; a slot holds one thing or nothing, chosen by hash,
 * standing somewhere in its middle three fifths. The layer is nearer than the street, so it slides
 * [PARALLAX] times as far as the street for a unit scrolled. A thing at the top or at a side shows
 * only in the outer part of the view ([edge]) and never over the building in front ([clearance]): a
 * post or a branch that would cross it is faded out before it gets there.
 */
internal object ForegroundLayout {
    const val SLOT = 600f
    const val PARALLAX = 1.3f

    /** Side things are whole within this share of the width from an edge, gone past the other. */
    const val EDGE_IN = .26f
    const val EDGE_OUT = .38f

    /** The soft margin either side of the front building's face, dp. */
    const val SOFT = 12f

    /** Air kept between the pavement line and the top of a railing or a bollard, dp. */
    const val OVER_GROUND = 4f

    /** What slot [slot] of the street with [seed] holds, null for nothing. */
    fun kind(seed: Int, slot: Int): ForegroundKind? {
        val h = Ambient.hash(seed, slot, 21)
        return when {
            h < .36f -> ForegroundKind.RAIL
            h < .62f -> ForegroundKind.BOLLARDS
            h < .8f -> ForegroundKind.POST
            else -> null
        }
    }

    /** Where in the street slot [slot]'s thing stands (street units). */
    fun at(seed: Int, slot: Int): Float =
        slot * SLOT + SLOT * (.2f + .6f * Ambient.hash(seed, slot, 22))

    /** The slot street unit [x] is in. */
    fun slot(x: Float): Int = floor(x / SLOT).toInt()

    /** Where street unit [x] is drawn on the near layer, px: the street's own place, pulled out. */
    fun screenX(x: Float, middle: Float, unit: Float, width: Float): Float =
        width / 2f + (x - middle) * unit * PARALLAX

    /** The slots whose things could show, with [margin] px past either edge for their width. */
    fun slots(middle: Float, unit: Float, width: Float, margin: Float): IntRange {
        val k = unit * PARALLAX
        val lo = middle + (-margin - width / 2f) / k
        val hi = middle + (width + margin - width / 2f) / k
        return slot(lo)..slot(hi)
    }

    /** Whether the tree [treeSeed] on the street with [seed] has a branch hanging into the view. */
    fun hasBranch(seed: Int, treeSeed: Int): Boolean =
        Ambient.hash(seed, treeSeed * 7 + 3, 41) < .6f

    /** How much of a side thing at [x] shows in a view [width] wide: whole at the edges only. */
    fun edge(x: Float, width: Float): Float =
        Px.smooth(EDGE_OUT * width, EDGE_IN * width, min(x, width - x))

    /**
     * How clear the span [x0]..[x1] is of the front building's face [ex0]..[ex1] (px): 1 clear by
     * [soft] or more, 0 touching it, between over the margin.
     */
    fun clearance(x0: Float, x1: Float, ex0: Float, ex1: Float, soft: Float): Float {
        if (ex1 < ex0) return 1f
        val gap = max(ex0 - x1, x0 - ex1)
        return Px.clamp01(gap / soft)
    }

    /**
     * The front building's face from its middle at [centerPx] drawn at [unit] px a street unit: the
     * span its plaques and its lobby lie in, with a little over, as (left, right) px.
     */
    fun frontLeft(centerPx: Float, unit: Float): Float =
        centerPx - (BuildingSpec.W / 2f + OVER_GROUND) * unit

    fun frontRight(centerPx: Float, unit: Float): Float =
        centerPx + (BuildingSpec.W / 2f + BuildingSpec.DX + OVER_GROUND) * unit

    /**
     * Where the top of a thing [h] px tall standing on the bottom band's edge at [band] goes: on
     * the band's edge, but never above the pavement line at [ground] (by [OVER_GROUND] dp at [d]),
     * so the lobby stays clear however far the camera tilts.
     */
    fun railTop(band: Float, ground: Float, h: Float, d: Float): Float =
        max(band - h, ground + OVER_GROUND * d)
}

/**
 * The near layer, drawn over the street and under the vignette: a few dark, soft things between the
 * camera and the street, sliding faster than it ([ForegroundLayout]). Along the bottom edge of the
 * street band, the top of a railing or a line of bollards; at a side, a sign post; in a top corner,
 * where a street tree stands near that edge, a branch of it hanging into the view, swaying a
 * little. They are clipped to the street band (the page's controls are under it, the heading over
 * it) and fade away as the street zooms out to the whole city.
 */
internal class ForegroundPainter {
    private var tintKey: Any? = null
    private var tint: ColorFilter? = null

    fun DrawScope.foreground(
        sky: SkyColors,
        street: StreetLayout,
        seed: Int,
        middle: Float,
        unit: Float,
        zoom: Float,
        skyTop: Float,
        top: Float,
        ground: Float,
        band: Float,
        frontX: Float,
        hasFront: Boolean,
        seconds: Float,
    ) {
        val fade = 1f - zoom
        if (fade < .02f || band <= top) return
        val d = density
        val w = size.width
        if (tintKey !== sky || tint == null) {
            // the near dark: a touch of the horizon's colour by day, nearly black at night
            val near = lerp(Color(0xFF070910), sky.sky[2], .14f * (1f - .5f * sky.dark))
            tint = ColorFilter.tint(near, BlendMode.SrcIn)
            tintKey = sky
        }
        val cf = tint
        val images = SceneSprites.foreground
        val s = d / ForegroundSprites.PX_PER_DP
        val ex0 = if (hasFront) ForegroundLayout.frontLeft(frontX, unit) else 1f
        val ex1 = if (hasFront) ForegroundLayout.frontRight(frontX, unit) else 0f
        val soft = ForegroundLayout.SOFT * d
        clipRect(0f, top, w, band) {
            fun thing(image: ImageBitmap, x: Float, y: Float, alpha: Float, mirror: Boolean) {
                if (alpha < .01f) return
                withTransform({
                    if (mirror) {
                        translate(x + image.width * s, y)
                        scale(-s, s, Offset.Zero)
                    } else {
                        translate(x, y)
                        scale(s, s, Offset.Zero)
                    }
                }) {
                    drawImage(image, Offset.Zero, alpha = alpha, colorFilter = cf)
                }
            }
            // along the bottom edge and at the sides, slot by slot
            val margin = ForegroundSprites.RAIL_W * d
            for (k in ForegroundLayout.slots(middle, unit, w, margin)) {
                val kind = ForegroundLayout.kind(seed, k) ?: continue
                val xs = ForegroundLayout.screenX(ForegroundLayout.at(seed, k), middle, unit, w)
                when (kind) {
                    ForegroundKind.RAIL -> {
                        val h = ForegroundSprites.RAIL_H * d
                        val x = xs - ForegroundSprites.RAIL_W * d / 2f
                        val y = ForegroundLayout.railTop(band, ground, h, d)
                        thing(images.rail, x, y, ALPHA * fade, false)
                    }
                    ForegroundKind.BOLLARDS -> {
                        val h = ForegroundSprites.BOLLARDS_H * d
                        val x = xs - ForegroundSprites.BOLLARDS_W * d / 2f
                        val y = ForegroundLayout.railTop(band, ground, h, d)
                        thing(images.bollards, x, y, ALPHA * fade, false)
                    }
                    ForegroundKind.POST -> {
                        val pw = ForegroundSprites.POST_W * d
                        val x = xs - pw / 2f
                        val a =
                            ALPHA *
                                fade *
                                ForegroundLayout.edge(xs, w) *
                                ForegroundLayout.clearance(x, x + pw, ex0, ex1, soft)
                        val y = band - ForegroundSprites.POST_H * d
                        thing(images.post, x, y, a, xs > w / 2f)
                    }
                }
            }
            // (no branch hanging in from a top corner: at phone size it read as a dark smudge)
        }
    }

    private companion object {
        /** How solid the near things are drawn. */
        const val ALPHA = .9f
    }
}
