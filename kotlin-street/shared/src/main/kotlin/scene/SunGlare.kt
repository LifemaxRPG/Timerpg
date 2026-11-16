package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.SunEdge
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The starburst's ray, painted once: a thin line of light from its root (the left end, on the
 * middle row) out to nothing at the right, [W] by [H] pixels at [PPD] to a dp, white at every alpha
 * so the hour tints it. A hot hairline down its middle in a faint soft wing, both narrowing and
 * fading along it, dithered so the long fade never bands. Pure Kotlin.
 */
internal object GlareSprites {
    const val W = 192
    const val H = 13
    const val PPD = 2f

    /** The ray's length in dp. */
    const val LENGTH = W / PPD

    fun spike(): IntArray {
        val px = IntArray(W * H)
        val my = (H - 1) / 2f
        for (j in 0 until H) for (i in 0 until W) {
            val x = i / PPD
            val y = (j - my) / PPD
            val t = x / LENGTH
            // narrowing to a point, and falling off faster than linearly, as a blade's spike does
            val core = .42f * (1f - .65f * t)
            val wing = 1.5f * (1f - .5f * t)
            val across = exp(-(y / core) * (y / core)) * .85f + exp(-(y / wing) * (y / wing)) * .22f
            val along = (1f - t).let { it * it * (.4f + .6f * it) } * Px.smooth(0f, 1.5f, x)
            val border = min(min(W - 1 - i, j), H - 1 - j).toFloat()
            var a = Px.clamp01(across * along) * Px.smooth(0f, 1.5f, border)
            if (a <= 0f) continue
            a = Px.clamp01(a + (hash(i, j) - .5f) / 255f)
            if (a > .002f) px[j * W + i] = Px.argbF(a, 1f, 1f, 1f)
        }
        return px
    }

    fun bytes(): Int = W * H * 4

    private fun hash(i: Int, j: Int): Float {
        var n = i * 73856093 xor j * 19349663 xor 4099
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }

    val image: ImageBitmap by lazy {
        PlatformImages.create(spike(), W, H, false)
    }
}

/**
 * The sun at a building's edge ([SunEdge]): drawn over the street, after the buildings, so its
 * light eats the edge of the silhouette in front of it. While a sliver of the sun shows past a
 * building: a hot white-gold point hugging the edge, a bloom added over it that spills across the
 * dark silhouette, a starburst of sharp thin rays (eight of uneven length and finer ones between
 * them, the fan turning a little with the sun's place on the screen) and a warm haze round it all.
 * The edges and the roofline nearest the sun take a thin backlit rim, fading with distance from it;
 * with the sun hidden behind a building only that rim and a faint glow at the edge are left. In the
 * open the camera's lens flare is the sun's ([Backdrop]), and none of this is drawn. All of it is
 * as strong as the sun is up, weaker under clouds and in mist, warmer at dusk, gone at night.
 */
internal class SunGlare {
    val edge = SunEdge()

    private var tintKey: Any? = null

    /** The share of the sun shown, eased frame to frame ([glare]); NaN until the first frame. */
    private var smoothVisible = Float.NaN
    private var coreTint: ColorFilter? = null
    private var bloomTint: ColorFilter? = null
    private var hazeTint: ColorFilter? = null
    private var spikeTint: ColorFilter? = null
    private var rimHot = Color.White
    private var rimWarm = Color.White

    private fun tints(sky: SkyColors) {
        if (tintKey === sky) return
        val w = SunLook.dusk(sky.dark)
        fun tint(day: Long, dusk: Long) =
            ColorFilter.tint(Color(mixArgb(day.toInt(), dusk.toInt(), w)), BlendMode.SrcIn)
        coreTint = tint(0xFFFFFFFF, 0xFFFFF0CC)
        bloomTint = tint(0xFFFFE9C0, 0xFFFFAA62)
        hazeTint = tint(0xFFFFCF98, 0xFFFF8A4C)
        spikeTint = tint(0xFFFFF8E8, 0xFFFFDDA8)
        rimHot = Color(mixArgb(0xFFFFF6E2.toInt(), 0xFFFFD49A.toInt(), w))
        rimWarm = Color(mixArgb(0xFFFFE0B0.toInt(), 0xFFFF9E58.toInt(), w))
        tintKey = sky
    }

    /**
     * The glare for the sun at [orb] (px), radius [r] (px), as [edge] measured it; [cloud] the
     * overcast's share, [cover] how far a cloud is across the sun, [mist] the morning fog's share,
     * never over the heading (clipped under [top]). [boost] is how far the camera looks into the
     * sun ([Backlight.amount]): the bloom fuller, the rays longer, the rim hotter and reaching
     * further along the edges.
     */
    fun DrawScope.glare(
        sky: SkyColors,
        orb: Offset,
        r: Float,
        cloud: Float,
        cover: Float,
        mist: Float,
        top: Float,
        boost: Float = 0f,
    ) {
        if (sky.phase == SkyPhase.NIGHT) return
        // the share shown moves in steps of one sample of the disc as the street scrolls; eased
        // over a few frames so the rays swell and fade instead of switching on with a jump
        // over a few frames so the rays swell instead of switching on with a jump; but as the sun
        // goes behind a building it follows at once, or a fast swipe leaves the burst lagging over
        // the wall that now hides it
        smoothVisible =
            if (smoothVisible.isNaN() || edge.visible < smoothVisible) edge.visible
            else smoothVisible + (edge.visible - smoothVisible) * VISIBLE_EASE
        if (kotlin.math.abs(edge.visible - smoothVisible) < .002f) smoothVisible = edge.visible
        val v = smoothVisible
        val up = SunLook.up(sky.dark)
        val c = cloud.coerceIn(0f, 1f)
        val open = (1f - cover.coerceIn(0f, 1f))
        val m = mist.coerceIn(0f, 1f)
        val sharp = up * (1f - c) * open * open
        val soft = up * (1f - .8f * c) * open
        val b = boost.coerceIn(0f, 1f)
        val spikes = SunEdge.spikes(v) * sharp * (1f - .7f * m)
        val bloom = SunEdge.bloom(v) * soft * (1f + .7f * b)
        val rim = SunEdge.rim(v, edge.near) * soft * (1f - .4f * m) * (1f + 1.4f * b)
        rimReach = 1f + .7f * b
        if (spikes < .01f && bloom < .01f && rim < .01f) return
        tints(sky)
        val d = density
        clipRect(0f, top, size.width, size.height) {
            if (rim >= .01f) rim(orb, r, rim)
            val sun = SceneSprites.sun
            val p = sun.sprite
            // the hidden sun's light round the edge it is behind: a faint glow where the sky opens
            val behind = rim * (1f - v)
            if (behind >= .01f)
                layer(
                    sun.bloom,
                    p.bloom,
                    Offset(edge.nearX, edge.nearY),
                    1.1f,
                    .3f * behind,
                    bloomTint,
                )
            if (bloom < .01f && spikes < .01f) return@clipRect
            val at = Offset(edge.peekX, edge.peekY)
            // the warm haze, then the bloom added over the silhouette's edge (eating it)
            layer(sun.sky, p.sky, at, .42f, .2f * bloom * (1f + .6f * m), hazeTint)
            layer(sun.bloom, p.bloom, at, 1f + .35f * bloom + .8f * b, .62f * bloom, bloomTint)
            layer(sun.core, p.core, at, 1.05f, .45f * max(bloom, spikes), coreTint)
            if (spikes >= .01f) {
                val turn = SunEdge.turn(at.x, at.y, size.width, size.height)
                val reach = (40f + 64f * spikes) * (1f + .75f * b) * d
                val img = GlareSprites.image
                for (k in SunEdge.RAY_ANGLE.indices) {
                    val len = reach * SunEdge.RAY_LENGTH[k]
                    val sx = len / GlareSprites.W
                    val sy = d / GlareSprites.PPD * SunEdge.RAY_WIDTH[k]
                    withTransform({
                        translate(at.x, at.y)
                        rotate(SunEdge.RAY_ANGLE[k] + turn, Offset.Zero)
                        scale(sx, sy, Offset.Zero)
                    }) {
                        drawImage(
                            img,
                            Offset(0f, -GlareSprites.H / 2f),
                            alpha = (.9f * spikes * SunEdge.RAY_ALPHA[k]).coerceAtMost(1f),
                            colorFilter = spikeTint,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
                // the hot point the rays leave from: white, laid over the edge
                layer(sun.core, p.core, at, .42f, .95f * spikes, coreTint, BlendMode.SrcOver)
            }
        }
    }

    /** One of the sun's soft layers at [at], [g] times its size, added. */
    private fun DrawScope.layer(
        image: ImageBitmap,
        l: SkyLayer,
        at: Offset,
        g: Float,
        alpha: Float,
        tint: ColorFilter?,
        blend: BlendMode = BlendMode.Plus,
    ) {
        if (alpha < .004f) return
        val k = density / l.pxPerDp * g
        withTransform({
            translate(at.x, at.y)
            scale(k, k, Offset.Zero)
        }) {
            drawImage(
                image,
                Offset(-l.size / 2f, -l.size / 2f),
                alpha = alpha.coerceAtMost(1f),
                colorFilter = tint,
                blendMode = blend,
            )
        }
    }

    /**
     * The backlit rim: along each building edge near the sun, outward of the silhouette and facing
     * the sky, a thin hot line in a soft warm one, as strong as the edge is near the sun and turned
     * to it. Each edge is drawn in short pieces, each its own strength, so nothing is allocated.
     */
    private fun DrawScope.rim(orb: Offset, r: Float, k: Float) {
        val e = edge
        val d = density
        val reach = r * RIM_REACH * rimReach
        val fall = r * RIM_FALL * rimReach
        val piece = max(2f, r * .45f)
        for (i in 0 until e.count) {
            if (!e.nearBox(i, orb.x, orb.y, reach)) continue
            // the outline of the swept box: the box, the box moved by the sweep, and the edges
            // between them
            val l = e.left[i]
            val t = e.top[i]
            val rr = e.right[i]
            val b = e.bottom[i]
            val sx = e.sweepX[i]
            val sy = e.sweepY[i]
            val n =
                if (sx >= 0f) {
                    hull(0, l, b)
                    hull(1, l, t)
                    hull(2, l + sx, t - sy)
                    hull(3, rr + sx, t - sy)
                    hull(4, rr + sx, b - sy)
                    hull(5, rr, b)
                    6
                } else {
                    hull(0, l + sx, b - sy)
                    hull(1, l + sx, t - sy)
                    hull(2, rr + sx, t - sy)
                    hull(3, rr, t)
                    hull(4, rr, b)
                    hull(5, l, b)
                    6
                }
            val cx = (l + rr + sx) / 2f
            val cy = (t + b - sy) / 2f
            for (j in 0 until n) {
                val ax = hx[j]
                val ay = hy[j]
                val bx = hx[(j + 1) % n]
                val by = hy[(j + 1) % n]
                val dx = bx - ax
                val dy = by - ay
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1f) continue
                var nx = dy / len
                var ny = -dx / len
                val mx = (ax + bx) / 2f
                val my = (ay + by) / 2f
                if ((mx - cx) * nx + (my - cy) * ny < 0f) {
                    nx = -nx
                    ny = -ny
                }
                // the ground's edges are not lit from behind
                if (ny > .5f) continue
                val steps = min(MAX_PIECES, max(1, (len / piece).toInt()))
                for (s in 0 until steps) {
                    val p0x = ax + dx * s / steps
                    val p0y = ay + dy * s / steps
                    val p1x = ax + dx * (s + 1) / steps
                    val p1y = ay + dy * (s + 1) / steps
                    val qx = (p0x + p1x) / 2f
                    val qy = (p0y + p1y) / 2f
                    if (qy < e.ceiling) continue
                    val ox = orb.x - qx
                    val oy = orb.y - qy
                    val dist = sqrt(ox * ox + oy * oy)
                    val near = exp(-(dist / fall) * (dist / fall))
                    if (near < .02f) continue
                    // only where the edge has the sky outside it
                    if (e.inside(qx + nx * 2f * d, qy + ny * 2f * d)) continue
                    val facing = if (dist > 0f) max(0f, (ox * nx + oy * ny) / dist) else 1f
                    val a = k * near * (.3f + .7f * facing)
                    if (a < .01f) continue
                    val p0 = Offset(p0x, p0y)
                    val p1 = Offset(p1x, p1y)
                    drawLine(
                        rimWarm,
                        p0,
                        p1,
                        3.2f * d,
                        StrokeCap.Butt,
                        alpha = (.4f * a).coerceAtMost(1f),
                        blendMode = BlendMode.Plus,
                    )
                    // the hot line just inside the silhouette: the edge itself lit from behind
                    val inX = -nx * .6f * d
                    val inY = -ny * .6f * d
                    drawLine(
                        rimHot,
                        Offset(p0x + inX, p0y + inY),
                        Offset(p1x + inX, p1y + inY),
                        1.1f * d,
                        StrokeCap.Butt,
                        alpha = a.coerceAtMost(1f),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }

    /** How much further than [RIM_REACH] the rim reaches this frame (shooting into the sun). */
    private var rimReach = 1f

    private val hx = FloatArray(6)
    private val hy = FloatArray(6)

    private fun hull(k: Int, x: Float, y: Float) {
        hx[k] = x
        hy[k] = y
    }

    companion object {
        /** How far from the sun (in its radii) an edge can take the rim, and its falloff. */
        const val RIM_REACH = 6f
        const val RIM_FALL = 3.5f
        const val MAX_PIECES = 24
    }
}

/** How much of the way to the measured share the glare's share moves each frame. */
private const val VISIBLE_EASE = .3f
