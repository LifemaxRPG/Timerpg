package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.TreeKind
import com.lifemax.rpg.feature.habits.domain.TreeShape
import com.lifemax.rpg.feature.habits.domain.Trees
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The three looks of a tree: by day, at dusk and at night. */
internal enum class TreeLook {
    DAY,
    DUSK,
    NIGHT,
}

/**
 * The dress of a deciduous tree through the year: summer's green, the yellow-green of the leaves
 * turning, autumn's amber or red (by variant), spring's thin light green, the blossom the flowering
 * variants put on over it, and the bare winter tree (its trunk and branches, which the leafy looks
 * sit on). A pine is [SUMMER] all year. The snow that lies on the branches is an overlay of its own
 * ([TreeSprites.snow]).
 */
internal enum class TreeSeason {
    SUMMER,
    TURNING,
    AUTUMN,
    SPRING,
    BLOSSOM,
    BARE,
}

/**
 * One tree variant, painted once in each [TreeLook]: [pixels] hold an ARGB image of [w] by [h]
 * pixels, [ppu] pixels to a street unit, with the foot of the trunk at ([footX], [footY]) pixels.
 * The sun (or the moon) is on the left; the sprite is drawn mirrored for a light from the right.
 * The soft contact shadow is in it.
 */
internal class TreeSprite(
    val w: Int,
    val h: Int,
    val ppu: Float,
    val footX: Float,
    val footY: Float,
    val pixels: Array<IntArray>,
)

/**
 * The tree sprite generator. A tree is a few masses of leaves (a pine's tiers) and a tapering
 * trunk. Each mass is lit as a lump: its surface normal, roughened by noise into smaller clusters
 * of leaves, is lit from the upper left, and each mass casts a soft shadow on the ones behind it,
 * so the crown has depth in three or four values instead of a flat blob. Leaf texture is fine
 * noise, the edge is ragged and feathered, the lit rim catches a highlight, the foot has a soft
 * shadow. At night the moonlight is dim and blue and the street lamps rim the side facing the
 * street warm. Everything is a function of the variant, so the same tree is painted every time.
 */
internal object TreeSprites {
    const val PPU = 2.5f

    /** Space round the tree, in units. */
    private const val PAD = 4f

    private const val LX = -.5f
    private const val LY = -.62f
    private const val LZ = .6f

    /** The planes a tree is painted into before it is coloured. */
    private class Planes(val w: Int, val h: Int) {
        val alpha = FloatArray(w * h)

        /** Tone, 0 (deep shade) to 1 (full light). */
        val tone = FloatArray(w * h)

        /** The warm street lamps' light on the side away from the sun, 0 to 1. */
        val lamp = FloatArray(w * h)

        /** 1 for bark, 0 for leaves. */
        val bark = FloatArray(w * h)
        val shadow = FloatArray(w * h)
    }

    fun generate(variant: Int, season: TreeSeason = TreeSeason.SUMMER): TreeSprite {
        val shape = Trees.shapes[variant]
        val dress = if (shape.kind == TreeKind.PINE) TreeSeason.SUMMER else season
        val w = width(shape)
        val h = height(shape)
        val footX = w / 2f
        val footY = h - 4f * PPU
        val p = paint(variant, shape, dress, w, h, footX, footY)
        val pixels =
            Array(TreeLook.entries.size) { colour(p, shape, TreeLook.entries[it], dress, variant) }
        return TreeSprite(w, h, PPU, footX, footY, pixels)
    }

    /**
     * The snow that lies on a tree of [variant] in winter, white at every alpha so the hour tints
     * it: on the upper sides of the bare tree's branches and twigs, or, for a pine, along its
     * boughs and the lit ridges of its tiers. The same size as the tree's sprite, drawn over it.
     */
    fun snow(variant: Int): IntArray {
        val shape = Trees.shapes[variant]
        val pine = shape.kind == TreeKind.PINE
        val w = width(shape)
        val h = height(shape)
        val footX = w / 2f
        val footY = h - 4f * PPU
        val p =
            paint(
                variant,
                shape,
                if (pine) TreeSeason.SUMMER else TreeSeason.BARE,
                w,
                h,
                footX,
                footY,
            )
        val cap = FloatArray(w * h)
        for (y in 3 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val a = p.alpha[i]
            if (a < .3f) continue
            // how open the sky is over this pixel: snow settles where nothing is above
            var open = 0f
            for (k in 1..3) open += max(0f, 1f - p.alpha[i - k * w]) * (if (k == 1) 1f else .5f)
            var c = Px.smooth(.5f, 1.7f, open)
            // a pine's boughs hold snow along their lit ridges too
            if (pine) c = max(c, Px.smooth(.7f, .92f, p.tone[i]) * (1f - p.bark[i]) * .6f)
            // the trunk below the crown is too steep to hold any
            val up = Px.smooth(footY - 1f * PPU, footY + (shape.crown + 2f) * PPU, y.toFloat())
            c *= min(1f, a * 1.6f) * up
            // not every branch top is covered alike
            c *= .7f + .3f * Noise.fbm(x * .3f, y * .3f, 700 + variant, 2)
            if (c <= 0f) continue
            cap[i] = max(cap[i], c)
            // it lies a little proud of the branch
            cap[i - w] = max(cap[i - w], c * .85f)
            if (c > .5f) cap[i - 2 * w] = max(cap[i - 2 * w], (c - .5f) * .8f)
        }
        val out = IntArray(w * h)
        for (i in out.indices) if (cap[i] > .01f)
            out[i] = Px.argbF(cap[i].coerceIn(0f, 1f), 1f, 1f, 1f)
        return out
    }

    private fun width(shape: TreeShape) = ((shape.halfWidth + PAD) * 2f * PPU).toInt() + 1

    private fun height(shape: TreeShape) = ((shape.height + PAD + 4f) * PPU).toInt() + 1

    /** What the pool of summer trees takes, in bytes (the three looks of every variant). */
    fun bytes(): Int =
        (0 until Trees.VARIANTS).sumOf {
            val s = Trees.shapes[it]
            width(s) * height(s) * 4 * TreeLook.entries.size
        }

    /**
     * What one more [season] of trees takes once baked, in bytes: the deciduous variants only (a
     * pine is the same sprite all year).
     */
    fun bytes(season: TreeSeason): Int =
        if (season == TreeSeason.SUMMER) bytes()
        else
            (0 until Trees.VARIANTS).sumOf {
                val s = Trees.shapes[it]
                if (s.kind == TreeKind.PINE) 0 else width(s) * height(s) * 4 * TreeLook.entries.size
            }

    /** What the snow overlays of every variant take, in bytes. */
    fun snowBytes(): Int =
        (0 until Trees.VARIANTS).sumOf {
            val s = Trees.shapes[it]
            width(s) * height(s) * 4
        }

    private fun paint(
        variant: Int,
        shape: TreeShape,
        season: TreeSeason,
        w: Int,
        h: Int,
        footX: Float,
        footY: Float,
    ): Planes {
        val p = Planes(w, h)
        shadow(p, shape, footX, footY, if (season == TreeSeason.BARE) .55f else 1f)
        trunk(p, shape, variant, footX, footY)
        when {
            shape.kind == TreeKind.PINE -> tiers(p, shape, variant, footX, footY)
            season == TreeSeason.BARE -> skeleton(p, shape, variant, footX, footY)
            season == TreeSeason.AUTUMN -> {
                // the branches show through the thinning canopy
                skeleton(p, shape, variant, footX, footY)
                masses(p, shape, variant, footX, footY, .3f)
            }
            else -> masses(p, shape, variant, footX, footY, 0f)
        }
        if (season != TreeSeason.BARE) volume(p, shape, footX, footY)
        return p
    }

    /**
     * The crown as one rounded mass: over the lumps' own light, the whole crown is lit as a ball
     * (or a pine as a cone) from the light's side, falling darker on the far side and underneath,
     * so the tree reads as a volume and not a flat cut-out. Leaves only (the bark keeps its own).
     */
    private fun volume(p: Planes, shape: TreeShape, footX: Float, footY: Float) {
        val c = shape.clumps
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        val pine = shape.kind == TreeKind.PINE
        for (m in 0 until c.size / 4) {
            val cx = c[m * 4]
            val cy = c[m * 4 + 1]
            val rx = c[m * 4 + 2]
            val ry = c[m * 4 + 3]
            x0 = min(x0, cx - rx)
            x1 = max(x1, cx + rx)
            // a pine's tier stands up from its foot; a mass is about its middle
            y0 = min(y0, cy - ry)
            y1 = max(y1, if (pine) cy else cy + ry)
        }
        val ccx = footX + (x0 + x1) / 2f * PPU
        val ccy = footY + (y0 + y1) / 2f * PPU
        val crx = (x1 - x0) / 2f * PPU
        val cry = (y1 - y0) / 2f * PPU
        for (i in 0 until p.w * p.h) {
            if (p.alpha[i] <= 0f || p.bark[i] > .5f) continue
            val gx = ((i % p.w) - ccx) / crx
            val gy = ((i / p.w) - ccy) / cry
            // a pine is a cone: round across, not up
            val ry = if (pine) 0f else gy
            val r2 = min(.96f, gx * gx + ry * ry)
            val nz = sqrt(1f - r2)
            val lam = gx * LX + ry * LY + nz * LZ
            var k = .66f + .46f * Px.smooth(-.55f, .85f, lam)
            // the underside, out of the sky's light
            k *= 1f - (if (pine) .1f else .22f) * Px.smooth(.35f, 1f, gy)
            p.tone[i] = (p.tone[i] * k).coerceIn(0f, 1f)
        }
    }

    // ---- painting the planes

    private fun shadow(p: Planes, shape: TreeShape, footX: Float, footY: Float, amount: Float) {
        val rx = (shape.halfWidth * .78f + 2f) * PPU * (.6f + .4f * amount)
        val ry = (if (shape.kind == TreeKind.SHRUB) 2.4f else 3.1f) * PPU
        val cx = footX + 3.2f * PPU
        val cy = footY + .5f * PPU
        for (y in 0 until p.h) for (x in 0 until p.w) {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            val r = sqrt(dx * dx + dy * dy)
            if (r < 1f) {
                val s = 1f - r
                p.shadow[y * p.w + x] = s * s * (3f - 2f * s) * .46f * amount
            }
        }
    }

    private fun trunk(p: Planes, shape: TreeShape, variant: Int, footX: Float, footY: Float) {
        val top = if (shape.kind == TreeKind.PINE) -26f else shape.crown - 4f
        val baseHalf = if (shape.kind == TreeKind.SHRUB) 1.1f else 2.5f
        val topHalf = if (shape.kind == TreeKind.PINE) 1.3f else 1.45f
        val lean = (com.lifemax.rpg.feature.habits.domain.Ambient.hash(variant, 3, 130) - .5f) * 3f
        for (y in 0 until p.h) {
            val u = (footY - y) / PPU // units above the ground
            if (u < -.5f || u > -top) continue
            val t = (u / -top).coerceIn(0f, 1f)
            val flare = 1f + .5f * Px.smooth(.14f, 0f, t)
            val half = (baseHalf + (topHalf - baseHalf) * Px.smooth(0f, 1f, t)) * flare * PPU
            val cx = footX + lean * t * t * PPU
            val lo = (cx - half - 1f).toInt()
            val hi = (cx + half + 1f).toInt() + 1
            for (x in max(0, lo)..min(p.w - 1, hi)) {
                val nx = (x - cx) / half
                val edge = 1f - abs(nx)
                val cover = Px.smooth(0f, .18f, edge)
                if (cover <= 0f) continue
                val nz = sqrt(max(0f, 1f - nx * nx))
                val lam = nx * LX + nz * LZ + (-.3f) * LY * .2f
                val streak = Noise.fbm(x * .55f, y * .09f, 140 + variant, 3)
                var v = Px.smooth(-.25f, .85f, lam) * .8f + (streak - .5f) * .35f + .05f
                // round: the core shadow just past the middle on the far side, the light the
                // ground throws back along the far edge, and the crown's shade on its top
                v *= 1f - .32f * Px.smooth(.05f, .45f, nx) * Px.smooth(.95f, .6f, nx)
                v += .12f * Px.smooth(.6f, .92f, nx)
                v *= 1f - .3f * Px.smooth(.6f, 1f, t)
                val i = y * p.w + x
                p.alpha[i] = max(p.alpha[i], cover)
                p.tone[i] = v.coerceIn(0f, 1f)
                p.bark[i] = 1f
                p.lamp[i] = Px.smooth(.3f, .95f, nx) * .8f
            }
        }
        // a limb into each side of the crown (not for a pine, whose tiers hide the trunk)
        if (shape.kind != TreeKind.PINE && shape.kind != TreeKind.SHRUB) {
            val y0 = footY + (shape.crown + 2f) * PPU
            for (s in intArrayOf(-1, 1)) {
                val len =
                    min(shape.halfWidth * .6f, 6.5f) +
                        2f * com.lifemax.rpg.feature.habits.domain.Ambient.hash(variant, s, 131)
                limb(
                    p,
                    footX + lean * PPU,
                    y0,
                    footX + s * len * PPU,
                    y0 - 9f * PPU,
                    1.3f * PPU,
                    .7f * PPU,
                )
            }
        }
    }

    private fun limb(p: Planes, x0: Float, y0: Float, x1: Float, y1: Float, r0: Float, r1: Float) {
        val steps = 28
        for (k in 0..steps) {
            val t = k / steps.toFloat()
            val cx = x0 + (x1 - x0) * t
            val cy = y0 + (y1 - y0) * t
            val r = r0 + (r1 - r0) * t
            for (y in (cy - r - 1).toInt()..(cy + r + 1).toInt()) for (x in
                (cx - r - 1).toInt()..(cx + r + 1).toInt()) {
                if (x < 0 || y < 0 || x >= p.w || y >= p.h) continue
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / r
                val cover = Px.smooth(1f, .6f, d)
                if (cover <= 0f) continue
                val i = y * p.w + x
                if (cover > p.alpha[i]) {
                    p.alpha[i] = cover
                    p.tone[i] = (.35f + .25f * (-(x - cx) / r)).coerceIn(0f, 1f)
                    p.bark[i] = 1f
                }
            }
        }
    }

    /**
     * The bare tree: the branches the summer's masses sit on. From the top of the trunk a bough
     * bows out to the middle of each mass, fans there into limbs that reach towards its edge, each
     * forking into twigs, all kept inside the mass so the bare tree stands inside the leafy one's
     * outline; and over each mass the haze of its fine twigs, which is what a winter crown is from
     * across a street.
     */
    private fun skeleton(p: Planes, shape: TreeShape, variant: Int, footX: Float, footY: Float) {
        val c = shape.clumps
        val lean = (com.lifemax.rpg.feature.habits.domain.Ambient.hash(variant, 3, 130) - .5f) * 3f
        val top = if (shape.kind == TreeKind.SHRUB) shape.crown - 4f else shape.crown - 4f
        val tx = footX + lean * PPU
        val ty = footY + top * PPU
        val order = (0 until c.size / 4).sortedByDescending { c[it * 4 + 1] }
        for (m in order) {
            val cx = footX + c[m * 4] * PPU
            val cy = footY + c[m * 4 + 1] * PPU
            val rx = c[m * 4 + 2] * PPU
            val ry = c[m * 4 + 3] * PPU
            val seed = 300 + variant * 29 + m * 7
            fun h(a: Int, b: Int) = com.lifemax.rpg.feature.habits.domain.Ambient.hash(seed, a, b)
            // the bough: thicker for a bigger mass, bowing a little to one side
            val r0 = (.75f + .3f * (rx / (9f * PPU))).coerceIn(.7f, 1.3f) * PPU
            bough(p, tx, ty, cx, cy, r0, .5f * PPU, (h(0, 1) - .5f) * .5f, seed)
            val limbs = 3 + (h(0, 2) * 2f).toInt()
            for (k in 0 until limbs) {
                // the limbs fan upward and outward from the middle of the mass
                val ang =
                    (-PI.toFloat() / 2f +
                        (k - (limbs - 1) / 2f) * (PI.toFloat() / (limbs + .3f)) +
                        (h(k, 3) - .5f) * .5f)
                val len = .66f + .2f * h(k, 4)
                var ex = cx + cos(ang) * rx * len
                var ey = cy + sin(ang) * ry * len
                ex = insideX(cx, rx, ex, ey, cy, ry, .82f)
                ey = insideY(cx, rx, ex, ey, cy, ry, .82f)
                bough(p, cx, cy, ex, ey, .5f * PPU, .3f * PPU, (h(k, 5) - .5f) * .4f, seed + k + 1)
                for (j in 0 until 2) {
                    val a2 = ang + (if (j == 0) -.65f else .65f) + (h(k * 2 + j, 6) - .5f) * .4f
                    var fx = ex + cos(a2) * rx * .4f
                    var fy = ey + sin(a2) * ry * .4f
                    fx = insideX(cx, rx, fx, fy, cy, ry, .9f)
                    fy = insideY(cx, rx, fx, fy, cy, ry, .9f)
                    bough(p, ex, ey, fx, fy, .3f * PPU, .16f * PPU, 0f, seed + 20 + k * 3 + j)
                }
            }
            haze(p, cx, cy, rx, ry, seed)
        }
    }

    /** ([x], [y]) pulled back towards the ellipse's middle until it is within [r] of its radius. */
    private fun insideX(
        cx: Float,
        rx: Float,
        x: Float,
        y: Float,
        cy: Float,
        ry: Float,
        r: Float,
    ): Float {
        val d = sqrt(((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it })
        return if (d <= r) x else cx + (x - cx) * r / d
    }

    private fun insideY(
        cx: Float,
        rx: Float,
        x: Float,
        y: Float,
        cy: Float,
        ry: Float,
        r: Float,
    ): Float {
        val d = sqrt(((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it })
        return if (d <= r) y else cy + (y - cy) * r / d
    }

    /**
     * A branch from ([x0], [y0]) to ([x1], [y1]), [r0] thick at its root and [r1] at its tip, bowed
     * sideways by [bend] of its length: a run of discs along a curve, lit from the upper left, with
     * the bark's streaks along it.
     */
    private fun bough(
        p: Planes,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r0: Float,
        r1: Float,
        bend: Float,
        seed: Int,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < .5f) return
        // the control point stands off the chord, so the branch bows
        val mx = (x0 + x1) / 2f - dy / len * bend * len
        val my = (y0 + y1) / 2f + dx / len * bend * len
        val steps = max(4, (len / 1.2f).toInt())
        for (k in 0..steps) {
            val t = k / steps.toFloat()
            val u = 1f - t
            val cx = u * u * x0 + 2f * u * t * mx + t * t * x1
            val cy = u * u * y0 + 2f * u * t * my + t * t * y1
            val r = r0 + (r1 - r0) * t
            for (y in (cy - r - 1).toInt()..(cy + r + 1).toInt()) for (x in
                (cx - r - 1).toInt()..(cx + r + 1).toInt()) {
                if (x < 0 || y < 0 || x >= p.w || y >= p.h) continue
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / max(r, .6f)
                val cover = Px.smooth(1.15f, .55f, d)
                if (cover <= 0f) continue
                val i = y * p.w + x
                if (cover > p.alpha[i] || p.bark[i] < .5f) {
                    val nx = ((x - cx) / max(r, .6f)).coerceIn(-1f, 1f)
                    val ny = ((y - cy) / max(r, .6f)).coerceIn(-1f, 1f)
                    val streak = Noise.fbm(x * .5f, y * .5f, seed, 2)
                    val v = .36f - .22f * nx - .14f * ny + (streak - .5f) * .2f
                    p.tone[i] = v.coerceIn(0f, 1f)
                    p.bark[i] = 1f
                    p.lamp[i] = Px.smooth(.2f, .95f, nx) * .7f
                    p.alpha[i] = max(p.alpha[i], cover)
                }
            }
        }
    }

    /**
     * The fine twigs of a bare crown over the mass at ([cx], [cy]): a thin, broken haze the bark's
     * colour, denser in the middle of the mass, so the winter tree reads as a crown and not a few
     * sticks.
     */
    private fun haze(p: Planes, cx: Float, cy: Float, rx: Float, ry: Float, seed: Int) {
        for (y in max(0, (cy - ry - 2f).toInt())..min(p.h - 1, (cy + ry + 2f).toInt())) {
            for (x in max(0, (cx - rx - 2f).toInt())..min(p.w - 1, (cx + rx + 2f).toInt())) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                val r = sqrt(dx * dx + dy * dy)
                if (r >= 1f) continue
                // twigs: fine ridges of noise, more of them towards the middle
                val fine = Noise.fbm(x * .55f, y * .55f, seed + 5, 3)
                val wisp = Noise.fbm(x * .2f, y * .2f, seed + 6, 2)
                val a =
                    Px.smooth(.52f, .72f, fine) *
                        .62f *
                        Px.smooth(1f, .4f, r) *
                        (.55f + .45f * wisp)
                if (a <= .01f) continue
                val i = y * p.w + x
                if (a > p.alpha[i]) {
                    p.tone[i] = Px.mix(p.tone[i], .3f + .15f * (fine - .5f) - .1f * dx, .8f)
                    p.bark[i] = 1f
                    p.lamp[i] = Px.smooth(.1f, .9f, dx) * .5f
                    p.alpha[i] = a
                }
            }
        }
    }

    /** Broadleaf, tall and shrub trees: masses of leaves, lowest first, each lit as a lump. */
    private fun masses(
        p: Planes,
        shape: TreeShape,
        variant: Int,
        footX: Float,
        footY: Float,
        patchy: Float,
    ) {
        val c = shape.clumps
        // paint the lowest first; the higher masses sit in front, nearest the light
        val order = (0 until c.size / 4).sortedByDescending { c[it * 4 + 1] }
        for ((rank, m) in order.withIndex()) {
            val cx = footX + c[m * 4] * PPU
            val cy = footY + c[m * 4 + 1] * PPU
            val rx = c[m * 4 + 2] * PPU
            val ry = c[m * 4 + 3] * PPU
            val seed = 150 + variant * 17 + m * 3
            cast(p, cx, cy, rx, ry, seed)
            // the masses behind (painted first) a little darker than those in front of them
            mass(p, cx, cy, rx, ry, seed, patchy, .84f + .16f * rank / max(1, order.size - 1))
        }
    }

    private fun maskAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        seed: Int,
    ): Float {
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        val r = sqrt(dx * dx + dy * dy)
        // the edge of a mass of leaves is clustered and ragged
        val rag =
            (Noise.fbm(x * .16f, y * .16f, seed, 3) - .5f) * .5f +
                (Noise.fbm(x * .45f, y * .45f, seed + 1, 2) - .5f) * .16f
        return 1f - r + rag
    }

    /** The mass's shadow on what is already painted behind it: soft, thrown away from the light. */
    private fun cast(p: Planes, cx: Float, cy: Float, rx: Float, ry: Float, seed: Int) {
        val ox = 3.2f * PPU
        val oy = 4.2f * PPU
        for (y in max(0, (cy - ry - 3f * PPU).toInt())..min(p.h - 1, (cy + ry + oy + 6f).toInt())) {
            for (x in
                max(0, (cx - rx - 3f * PPU).toInt())..min(p.w - 1, (cx + rx + ox + 6f).toInt())) {
                val i = y * p.w + x
                if (p.alpha[i] <= 0f) continue
                val m = maskAt(x - ox, y - oy, cx, cy, rx, ry, seed)
                val s = Px.smooth(.05f, .42f, m) * .5f
                if (s > 0f) {
                    p.tone[i] *= 1f - s
                    p.lamp[i] *= 1f - s * .8f
                }
            }
        }
    }

    /**
     * One mass of leaves; [patchy] over 0 opens holes in it (an autumn canopy, half its leaves
     * down, the branches showing through).
     */
    private fun mass(
        p: Planes,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        seed: Int,
        patchy: Float = 0f,
        depth: Float = 1f,
    ) {
        for (y in
            max(0, (cy - ry - 4f * PPU).toInt())..min(p.h - 1, (cy + ry + 4f * PPU).toInt())) {
            for (x in
                max(0, (cx - rx - 4f * PPU).toInt())..min(p.w - 1, (cx + rx + 4f * PPU).toInt())) {
                val m = maskAt(x.toFloat(), y.toFloat(), cx, cy, rx, ry, seed)
                var cover = Px.smooth(0f, .16f, m)
                if (patchy > 0f)
                    cover *=
                        Px.smooth(
                            patchy - .12f,
                            patchy + .2f,
                            Noise.fbm(x * .24f, y * .24f, seed + 7, 2),
                        )
                if (cover <= 0f) continue
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                val r2 = min(.98f, dx * dx + dy * dy)
                // the surface of the lump, roughened by noise into smaller clusters of leaves
                val bx = (Noise.fbm(x * .17f, y * .17f, seed + 2, 3) - .5f) * .9f
                val by = (Noise.fbm(x * .17f + 9f, y * .17f + 4f, seed + 3, 3) - .5f) * .9f
                var nx = dx * .85f + bx
                var ny = dy * .85f + by
                val nl = sqrt(nx * nx + ny * ny)
                if (nl > .98f) {
                    nx *= .98f / nl
                    ny *= .98f / nl
                }
                val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
                val lam = nx * LX + ny * LY + nz * LZ
                var v = Px.smooth(-.45f, .8f, lam)
                // the crowns of the clusters catch a little more light, the creases between fall
                // dark
                v += (Noise.fbm(x * .5f, y * .5f, seed + 4, 3) - .5f) * .26f
                // a shade under the mass, lit rim on the sun's side
                v *= .84f + .16f * Px.smooth(.9f, -.4f, dy)
                if (dx < -.45f && dy < .1f)
                    v += .16f * Px.smooth(.55f, .98f, sqrt(r2)) * Px.smooth(-.45f, -.8f, dx)
                val lampSide =
                    Px.smooth(.25f, .95f, dx * .8f - dy * .1f + (1f - nz) * .3f) *
                        Px.smooth(.35f, .9f, sqrt(r2) + bx * .2f)
                val i = y * p.w + x
                p.tone[i] = Px.mix(p.tone[i], (v * depth).coerceIn(0f, 1f), cover)
                p.lamp[i] = Px.mix(p.lamp[i], lampSide, cover)
                p.bark[i] = Px.mix(p.bark[i], 0f, cover)
                p.alpha[i] = p.alpha[i] + (1f - p.alpha[i]) * cover
            }
        }
    }

    /** A pine: tiers from the lowest up, each a ragged triangle, each shading the one below it. */
    private fun tiers(p: Planes, shape: TreeShape, variant: Int, footX: Float, footY: Float) {
        val c = shape.clumps
        var m = 0
        while (m < c.size / 4) {
            val seed = 170 + variant * 13 + m
            val cx = footX + c[m * 4] * PPU
            val baseY = footY + c[m * 4 + 1] * PPU
            val hw = c[m * 4 + 2] * PPU
            val ht = c[m * 4 + 3] * PPU
            tierShadow(p, cx, baseY, hw, ht, seed)
            tier(p, cx, baseY, hw, ht, seed)
            m++
        }
    }

    private fun tierMask(
        x: Float,
        y: Float,
        cx: Float,
        baseY: Float,
        hw: Float,
        ht: Float,
        seed: Int,
    ): Float {
        val t = (baseY - y) / ht // 0 at the foot, 1 at the apex
        val half = hw * (1f - t)
        val dx = abs(x - cx)
        val rag = (Noise.fbm(x * .2f, y * .2f, seed, 3) - .5f) * 3.4f * PPU * .5f
        // the skirt hangs in drooping scallops
        val skirt = (Noise.fbm(x * .1f, 4f, seed + 1, 2) - .5f) * 2.2f * PPU
        val side = (half - dx + rag) / (hw * .5f)
        val foot = (baseY + skirt - y) / (ht * .22f) + 1f
        val head = (1f - t) * 4f
        return min(side, min(foot, head))
    }

    private fun tierShadow(p: Planes, cx: Float, baseY: Float, hw: Float, ht: Float, seed: Int) {
        val ox = 1.5f * PPU
        val oy = 3.4f * PPU
        for (y in max(0, (baseY - ht - 2f).toInt())..min(p.h - 1, (baseY + oy + 4f).toInt())) {
            for (x in max(0, (cx - hw - 4f).toInt())..min(p.w - 1, (cx + hw + ox + 4f).toInt())) {
                val i = y * p.w + x
                if (p.alpha[i] <= 0f) continue
                val m = tierMask(x - ox, y - oy, cx, baseY, hw, ht, seed)
                val s = Px.smooth(0f, 1.2f, m) * .5f
                if (s > 0f) {
                    p.tone[i] *= 1f - s
                    p.lamp[i] *= 1f - s * .8f
                }
            }
        }
    }

    private fun tier(p: Planes, cx: Float, baseY: Float, hw: Float, ht: Float, seed: Int) {
        for (y in max(0, (baseY - ht - 3f).toInt())..min(p.h - 1, (baseY + 4f * PPU).toInt())) {
            for (x in max(0, (cx - hw - 4f).toInt())..min(p.w - 1, (cx + hw + 4f).toInt())) {
                val m = tierMask(x.toFloat(), y.toFloat(), cx, baseY, hw, ht, seed)
                val cover = Px.smooth(0f, .55f, m)
                if (cover <= 0f) continue
                val t = ((baseY - y) / ht).coerceIn(0f, 1f)
                val half = max(1f, hw * (1f - t))
                val dx = (x - cx) / half
                // a cone: lit on the sun's side, with boughs breaking it into clusters
                val bx = (Noise.fbm(x * .2f, y * .24f, seed + 2, 3) - .5f) * .8f
                val nx = (dx * .9f + bx).coerceIn(-.98f, .98f)
                val nz = sqrt(max(0f, 1f - nx * nx))
                val lam = nx * LX + nz * (LZ + .1f) - .3f * LY * (1f - t) * .3f
                var v = Px.smooth(-.4f, .8f, lam) * .82f
                // boughs: horizontal ridges, light on top, dark underneath
                val ridge = Noise.fbm(x * .06f, y * .3f, seed + 3, 3)
                v += (ridge - .5f) * .34f
                v += (Noise.fbm(x * .55f, y * .55f, seed + 4, 3) - .5f) * .24f
                // under each tier's skirt it is darker
                v *= .72f + .28f * Px.smooth(0f, .5f, t + .12f)
                val lampSide = Px.smooth(.2f, .95f, dx) * Px.smooth(.25f, .8f, 1f - t * .5f)
                val i = y * p.w + x
                p.tone[i] = Px.mix(p.tone[i], v.coerceIn(0f, 1f), cover)
                p.lamp[i] = Px.mix(p.lamp[i], lampSide, cover)
                p.bark[i] = Px.mix(p.bark[i], 0f, cover)
                p.alpha[i] = p.alpha[i] + (1f - p.alpha[i]) * cover
            }
        }
    }

    // ---- colouring

    private class Ramp(
        val deep: Int,
        val shade: Int,
        val mid: Int,
        val light: Int,
        val glint: Int,
        val barkDark: Int,
        val barkLit: Int,
        val lamp: Int,
        val lampAmount: Float,
        val shadow: Int,
    )

    private val DAY =
        Ramp(
            0xFF1B4638.toInt(),
            0xFF2A6548.toInt(),
            0xFF418959.toInt(),
            0xFF86B96C.toInt(),
            0xFFD2E69A.toInt(),
            0xFF4A3524.toInt(),
            0xFF86644A.toInt(),
            0xFFFFE0A0.toInt(),
            0f,
            0xFF15202B.toInt(),
        )
    private val DUSK =
        Ramp(
            0xFF2A2D4A.toInt(),
            0xFF3F4A5A.toInt(),
            0xFF5C7A58.toInt(),
            0xFFDDA672.toInt(),
            0xFFFFD09A.toInt(),
            0xFF3A2B3A.toInt(),
            0xFF9A6C58.toInt(),
            0xFFFFC27A.toInt(),
            .22f,
            0xFF1E1830.toInt(),
        )
    private val NIGHT =
        Ramp(
            0xFF050E16.toInt(),
            0xFF0B1D26.toInt(),
            0xFF15343A.toInt(),
            0xFF3E6A78.toInt(),
            0xFF8EB0C4.toInt(),
            0xFF140F1A.toInt(),
            0xFF3A3448.toInt(),
            0xFFFFC070.toInt(),
            .62f,
            0xFF050810.toInt(),
        )

    private fun ch(c: Int, s: Int) = (c shr s) and 255

    private fun mixc(a: Int, b: Int, t: Float): Int =
        Px.argb(
            255,
            (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t).toInt(),
            (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t).toInt(),
            (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t).toInt(),
        )

    /**
     * What a season does to the leaf colours: the hue the ramp is turned to, how much more (or
     * less) saturated and lighter it is, and how much the flowers speckle the canopy.
     */
    private class Dye(
        val hue: Float,
        val sat: Float,
        val light: Float,
        val speckle: Float,
        /**
         * How the ramp's own spread of hue is laid about the new hue: negative turns the light
         * leaves towards yellow and the shaded ones towards red, as turning leaves go.
         */
        val spread: Float,
    )

    private val AMBER = Dye(36f, 1.2f, .04f, 0f, -.7f)
    private val RED = Dye(8f, 1.1f, -.01f, 0f, -.6f)
    private val GOLD = Dye(78f, 1.1f, .05f, 0f, -.6f)
    private val BRASS = Dye(52f, 1.1f, .03f, 0f, -.6f)
    private val SPRING_DYE = Dye(86f, 1.05f, .1f, 0f, -.5f)
    private val PINK = Dye(338f, .62f, .3f, .5f, .3f)
    private val WHITE = Dye(48f, .2f, .42f, .5f, .2f)

    private fun dyeOf(season: TreeSeason, variant: Int): Dye? =
        when (season) {
            TreeSeason.SUMMER,
            TreeSeason.BARE -> null
            TreeSeason.TURNING -> if (Trees.turnsRed(variant)) BRASS else GOLD
            TreeSeason.AUTUMN -> if (Trees.turnsRed(variant)) RED else AMBER
            TreeSeason.SPRING -> SPRING_DYE
            TreeSeason.BLOSSOM -> if (variant == 7) WHITE else PINK
        }

    private fun hsl(c: Int): FloatArray {
        val r = ch(c, 16) / 255f
        val g = ch(c, 8) / 255f
        val b = ch(c, 0) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-4f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        var h =
            when (mx) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r1, g1, b1) =
            when (hh.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
        val m = l - c / 2f
        return Px.argbF(1f, r1 + m, g1 + m, b1 + m)
    }

    /**
     * A leaf colour of a ramp (whose middle hue is [midHue]) turned by [dye]: the ramp's own spread
     * of hue is kept about the new hue, so the deep shade and the glint still differ; at dusk and
     * at night the colours change less (the light, not the leaves, is what colours them then).
     */
    private fun dyed(c: Int, midHue: Float, dye: Dye, look: TreeLook): Int {
        val x = hsl(c)
        val k =
            when (look) {
                TreeLook.DAY -> 1f
                TreeLook.DUSK -> .7f
                TreeLook.NIGHT -> .45f
            }
        val spread = ((x[0] - midHue + 540f) % 360f) - 180f
        val h = dye.hue + spread * dye.spread
        val s = (x[1] * (1f + (dye.sat - 1f) * k)).coerceIn(0f, 1f)
        val l = (x[2] + dye.light * k).coerceIn(0f, 1f) * (if (look == TreeLook.NIGHT) .8f else 1f)
        // the moon and the dusk colour the leaves more than their own colour does: the dyed colour
        // leans back to the hour's
        return mixc(fromHsl(h, s, l), c, 1f - k)
    }

    private fun colour(
        p: Planes,
        shape: TreeShape,
        look: TreeLook,
        season: TreeSeason,
        variant: Int,
    ): IntArray {
        val r =
            when (look) {
                TreeLook.DAY -> DAY
                TreeLook.DUSK -> DUSK
                TreeLook.NIGHT -> NIGHT
            }
        val olive = 0xFF7C9C3E.toInt()
        val dye = dyeOf(season, variant)
        val warm =
            if (dye != null) 0f else shape.warmth * (if (look == TreeLook.NIGHT) .25f else 1f)
        var deep = mixc(r.deep, olive, warm * .5f)
        var shade = mixc(r.shade, olive, warm * .6f)
        var mid = mixc(r.mid, olive, warm)
        var light = mixc(r.light, olive, warm * .8f)
        var glint = r.glint
        if (dye != null) {
            val midHue = hsl(r.mid)[0]
            deep = dyed(deep, midHue, dye, look)
            shade = dyed(shade, midHue, dye, look)
            mid = dyed(mid, midHue, dye, look)
            light = dyed(light, midHue, dye, look)
            glint = dyed(glint, midHue, dye, look)
        }
        val speckle = dye?.speckle ?: 0f
        val out = IntArray(p.w * p.h)
        for (i in out.indices) {
            val a = p.alpha[i]
            val s = p.shadow[i]
            if (a <= 0.004f && s <= 0.004f) continue
            var v = p.tone[i].coerceIn(0f, 1f)
            var col: Int
            if (p.bark[i] > .5f) {
                col = mixc(r.barkDark, r.barkLit, v)
            } else {
                // blossom: the flowers cluster, so the canopy is speckled light and dark
                if (speckle > 0f)
                    v =
                        (v +
                                (Noise.value((i % p.w) * .8f, (i / p.w) * .8f, 900 + variant) -
                                    .5f) * speckle)
                            .coerceIn(0f, 1f)
                // four values: deep, shade, mid, light, with a glint on the brightest leaves
                col =
                    if (v < .34f) mixc(deep, shade, v / .34f)
                    else if (v < .62f) mixc(shade, mid, (v - .34f) / .28f)
                    else mixc(mid, light, (v - .62f) / .38f)
                if (v > .86f) col = mixc(col, glint, (v - .86f) / .14f * .55f)
            }
            if (r.lampAmount > 0f)
                col = mixc(col, r.lamp, (p.lamp[i] * r.lampAmount).coerceIn(0f, .75f))
            // the soft shadow under it, behind the tree
            val total = a + s * (1f - a)
            val tr = (ch(col, 16) * a + ch(r.shadow, 16) * s * (1f - a)) / total
            val tg = (ch(col, 8) * a + ch(r.shadow, 8) * s * (1f - a)) / total
            val tb = (ch(col, 0) * a + ch(r.shadow, 0) * s * (1f - a)) / total
            out[i] = Px.argb((total * 255f + .5f).toInt(), tr.toInt(), tg.toInt(), tb.toInt())
        }
        return out
    }
}

/**
 * How a tree of the moment is put together from its season looks: which looks are drawn, in what
 * order, and how strongly, for a [SeasonState]. The bare tree goes under, the canopy looks over it
 * at the leaf's fullness, two of them blended where the year is between looks (summer into the
 * turning yellow-green into autumn's colour; spring's thin green into summer's; blossom over
 * spring), so the seasons cross over a couple of weeks and nothing pops. Pure, so it is tested.
 */
internal object TreeDress {
    /** The order the looks are drawn in, from the one underneath. */
    val ORDER =
        arrayOf(
            TreeSeason.BARE,
            TreeSeason.SPRING,
            TreeSeason.SUMMER,
            TreeSeason.TURNING,
            TreeSeason.AUTUMN,
            TreeSeason.BLOSSOM,
        )

    /**
     * Writes into [out] (one slot per [TreeSeason], by ordinal) the alpha each look is drawn with
     * for a tree of [variant] in [s]; 0 for a look not drawn.
     */
    fun plan(s: SeasonState, variant: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        if (Trees.evergreen(variant)) {
            out[TreeSeason.SUMMER.ordinal] = 1f
            return
        }
        // in autumn the canopy holds its colour and then drops quickly (a thin canopy over the sky
        // goes grey, so it is not held thin for long)
        val canopy = if (s.autumn > .01f) Px.smooth(0f, .7f, s.leaf) else s.leaf
        // the bare tree under the canopy, fading out as the canopy fills (it is covered by then)
        out[TreeSeason.BARE.ordinal] = 1f - Px.smooth(.6f, 1f, canopy)
        if (canopy <= .01f) return
        if (s.autumn > .01f) {
            if (s.autumn < .5f) {
                out[TreeSeason.SUMMER.ordinal] = canopy
                out[TreeSeason.TURNING.ordinal] = canopy * s.autumn * 2f
            } else {
                if (s.autumn < .99f) out[TreeSeason.TURNING.ordinal] = canopy
                out[TreeSeason.AUTUMN.ordinal] = canopy * (s.autumn - .5f) * 2f
            }
        } else {
            if (s.mature < .99f) out[TreeSeason.SPRING.ordinal] = canopy
            if (s.mature > .01f) out[TreeSeason.SUMMER.ordinal] = canopy * s.mature
            if (s.blossom > .01f && Trees.blossoms(variant))
                out[TreeSeason.BLOSSOM.ordinal] = canopy * s.blossom
        }
    }

    /** The canopy's alpha in [plan]'s [out]: what the tree's shadow is cast with. */
    fun canopy(out: FloatArray): Float =
        max(
            max(out[TreeSeason.SPRING.ordinal], out[TreeSeason.SUMMER.ordinal]),
            max(out[TreeSeason.TURNING.ordinal], out[TreeSeason.AUTUMN.ordinal]),
        )
}
