package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.lifemax.rpg.feature.habits.domain.Backlit
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.SunEdge
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A shaft of light, painted once: a soft beam from its root (the left end, on the middle row)
 * widening out to the right and fading along it, [W] by [H] pixels, white at every alpha so the
 * hour tints it; dithered so its long fade never bands. Pure Kotlin.
 */
internal object ShaftSprites {
    const val W = 160
    const val H = 40

    fun shaft(): IntArray {
        val px = IntArray(W * H)
        val my = (H - 1) / 2f
        for (j in 0 until H) for (i in 0 until W) {
            val u = (i + .5f) / W
            // its half-width, a share of the sprite's: a narrow root, a wide end
            val half = (.1f + .9f * u) * (H / 2f)
            val y = (j - my) / half
            val across = exp(-2.2f * y * y)
            val along = Px.smooth(0f, .1f, u) * (1f - u).let { it * sqrt(it) }
            var a = Px.clamp01(across * along)
            if (a <= 0f) continue
            a = Px.clamp01(a + (Backlit.hash(i * 31 + j, 41) - .5f) / 255f)
            if (a > .002f) px[j * W + i] = Px.argbF(a, 1f, 1f, 1f)
        }
        return px
    }

    fun bytes(): Int = W * H * 4

    val image: ImageBitmap by lazy {
        PlatformImages.create(shaft(), W, H, false)
    }
}

/**
 * Shooting into a low sun ([Backlit]): the camera exposed for the sky. Drawn in three passes around
 * the street:
 * - [sky], over the backdrop: the sky warmed to gold and orange through the frame, blown out to
 *   white-gold round the sun (a white-hot core in a wide gold bloom), and the far city washed into
 *   a bright haze along the horizon;
 * - [expose], over the street's own layer: everything in front of the sky (the buildings, the
 *   furniture, the people, the cars, the pavement) multiplied towards a warm dark, darkest round
 *   the sun, never black; the stage then gives the lit windows and signs their light back
 *   ([shadeAt], [BuildingPainter.relight]);
 * - [air], over the whole picture under the heading: the gold bloom wrapping over the silhouettes'
 *   edges near the sun, a warm haze centred on it, soft shafts of light fanning out from it through
 *   the gaps between the buildings and over their roofs (each as strong as it is open), and a few
 *   motes of dust drifting in the light.
 *
 * Everything follows [amount] smoothly; nothing at night or with the sun high. Allocation-free per
 * frame: the brushes are made in unit space once per sky and drawn under transforms, the strength
 * of the exposure is a colour filter picked from a few kept steps, and the shafts' openness lives
 * in arrays written in place.
 */
internal class Backlight {
    /** How far the camera looks into the sun this frame ([Backlit.factor]). */
    var amount = 0f
        private set

    private var orbX = 0f
    private var orbY = 0f
    private var diag = 1f

    /** How much of the sun shows past the buildings, eased (as the glare's). */
    private var shown = Float.NaN

    private val open = FloatArray(Backlit.RAYS)
    private var openSet = false
    private val probe = BooleanArray(Backlit.PROBE.size)
    private var mean = 0f
    private var turn = 0f

    /**
     * How far the camera looks into the sun at ([orb], radius [r], px) this frame: [up] how high it
     * is in its arc, [cloud], [cover] and [mist] the weather's, [edge] the buildings round it
     * (measured), [zoom] how far the street is zoomed out, on a picture [w] by [h].
     */
    fun update(
        sky: SkyColors,
        up: Float,
        cloud: Float,
        cover: Float,
        mist: Float,
        orb: Offset,
        r: Float,
        edge: SunEdge,
        zoom: Float,
        w: Float,
        h: Float,
        seconds: Float,
        reach: Float = r * 3f,
    ) {
        orbX = orb.x
        orbY = orb.y
        diag = max(1f, hypot(w, h))
        val sun = if (sky.phase == SkyPhase.NIGHT) 0f else SunLook.up(sky.dark)
        val gap = edge.gap(orb.x, orb.y) / max(1f, r)
        amount =
            Backlit.factor(
                up,
                sun,
                cloud,
                cover,
                mist,
                gap,
                zoom,
                Backlit.inView(orb.x, orb.y, r, w, h, reach),
            ) * STRENGTH
        if (amount < .005f) {
            amount = 0f
            openSet = false
            shown = Float.NaN
            return
        }
        // eased as it comes out, followed at once as it goes behind a wall (as the glare's share)
        shown =
            if (shown.isNaN() || edge.visible < shown) edge.visible
            else shown + (edge.visible - shown) * EASE
        turn = Backlit.shaftTurn(seconds, orb.x, w)
        // the shafts' openness moves in steps as the street scrolls: eased like the glare's share
        var sum = 0f
        Backlit.shafts(edge, orb.x, orb.y, r, turn, scratch, probe)
        for (i in 0 until Backlit.RAYS) {
            val now = scratch[i]
            open[i] = if (!openSet) now else open[i] + (now - open[i]) * EASE
            sum += open[i]
        }
        openSet = true
        mean = sum / Backlit.RAYS
    }

    private val scratch = FloatArray(Backlit.RAYS)

    /**
     * How bright the exposure leaves the point ([x], [y]) (px), 0 to 1 as the eye weighs it: what a
     * light there has lost, for the stage to give back.
     */
    fun shadeAt(x: Float, y: Float): Float {
        if (amount <= 0f) return 1f
        val t = hypot(x - orbX, y - orbY) / (Backlit.REACH * diag)
        return Backlit.shade(t, amount)
    }

    // ---- the tints and brushes of a sky, made once per sky

    private var skyKey: Any? = null
    private var expose: Brush? = null
    private var wash: Brush? = null
    private var horizonHaze: Brush? = null
    private var haze: Brush? = null
    private var gold: ColorFilter? = null
    private var whiteGold: ColorFilter? = null
    private var white: ColorFilter? = null
    private var shaftTint: ColorFilter? = null
    private var mote = Color.White

    /** The exposure's strength, in [LEVELS] steps: a colour filter each, made once when needed. */
    private val levels = arrayOfNulls<ColorFilter>(LEVELS + 1)

    private fun tints(sky: SkyColors) {
        if (skyKey === sky) return
        val w = SunLook.dusk(sky.dark)
        fun c(day: Long, dusk: Long) = Color(mixArgb(day.toInt(), dusk.toInt(), w))
        fun ink(i: Int) = Color(Backlit.INK_R[i], Backlit.INK_G[i], Backlit.INK_B[i])
        expose =
            Brush.radialGradient(
                Backlit.STOPS[0] to ink(0),
                Backlit.STOPS[1] to ink(1),
                Backlit.STOPS[2] to ink(2),
                center = Offset.Zero,
                radius = 1f,
            )
        // the sky warmed through the frame: amber overhead, orange, gold low down
        wash =
            Brush.verticalGradient(
                0f to c(0x44D08850, 0x52C0603C),
                .5f to c(0x70F2B060, 0x80F29A48),
                1f to c(0x9EFFD890, 0xA6FFC870),
                startY = 0f,
                endY = 1f,
            )
        // the far city washed into the bright haze along the horizon, and the far ground under it
        horizonHaze =
            Brush.verticalGradient(
                0f to Color.Transparent,
                .45f to c(0x55FFE2B0, 0x5CFFC07A),
                .8f to c(0xB0FFE8C0, 0xB8FFCB8C),
                1f to c(0x90FFE0B0, 0x98FFC07A),
                startY = 0f,
                endY = 1f,
            )
        // the warm haze over the whole picture, centred on the sun
        val hz = c(0xFFFFDDA6, 0xFFFFB066)
        haze =
            Brush.radialGradient(
                0f to hz.copy(alpha = .26f),
                .16f to hz.copy(alpha = .18f),
                .45f to hz.copy(alpha = .09f),
                1f to hz.copy(alpha = .04f),
                center = Offset.Zero,
                radius = 1f,
            )
        fun tint(day: Long, dusk: Long) = ColorFilter.tint(c(day, dusk), BlendMode.SrcIn)
        gold = tint(0xFFFFD49A, 0xFFFFAE5C)
        whiteGold = tint(0xFFFFF4DC, 0xFFFFE2B0)
        white = tint(0xFFFFFFFF, 0xFFFFF8E6)
        shaftTint = tint(0xFFFFEBC4, 0xFFFFC27E)
        mote = c(0xFFFFF0D0, 0xFFFFD49A)
        skyKey = sky
    }

    private fun level(k: Float): ColorFilter {
        val q = (Px.clamp01(k) * LEVELS + .5f).toInt()
        levels[q]?.let {
            return it
        }
        val s = q / LEVELS.toFloat()
        val o = (1f - s) * 255f
        return ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        s,
                        0f,
                        0f,
                        0f,
                        o,
                        0f,
                        s,
                        0f,
                        0f,
                        o,
                        0f,
                        0f,
                        s,
                        0f,
                        o,
                        0f,
                        0f,
                        0f,
                        1f,
                        0f,
                    )
                )
            )
            .also { levels[q] = it }
    }

    // ---- the passes

    /**
     * Over the backdrop, under the street: the sky warmed and blown out round the sun at [orb], the
     * far city washed into the haze along the [horizon] (px).
     */
    fun DrawScope.sky(sky: SkyColors, orb: Offset, horizon: Float) {
        val k = amount
        if (k <= 0f) return
        tints(sky)
        val w = size.width
        val d = density
        withTransform({ scale(w, horizon, Offset.Zero) }) {
            drawRect(wash!!, Offset.Zero, Size(1f, 1f), alpha = k)
        }
        val band = HORIZON_BAND * d
        withTransform({
            translate(0f, horizon - band)
            scale(w, band * 1.25f, Offset.Zero)
        }) {
            drawRect(horizonHaze!!, Offset.Zero, Size(1f, 1f), alpha = k)
        }
        val s = SceneSprites.sun
        val p = s.sprite
        // the sun overpowering the sky: its light spread wide in gold, a white-gold bloom, a
        // white-hot core
        layer(s.sky, p.sky, orb, 1.6f + .5f * k, .6f * k, gold, BlendMode.Plus)
        layer(s.bloom, p.bloom, orb, 2.4f + 1.4f * k, .75f * k, whiteGold, BlendMode.Plus)
        layer(s.core, p.core, orb, 1.5f + .6f * k, .95f * k, white, BlendMode.SrcOver)
    }

    /**
     * Over the street's layer (only the street is in it): everything multiplied towards the warm
     * dark of a silhouette against the sky, as dark as the camera looks into the sun, darkest round
     * it. Transparent where nothing was drawn, so the sky behind is not touched.
     */
    fun DrawScope.expose(sky: SkyColors) {
        val k = amount
        if (k <= 0f) return
        tints(sky)
        val reach = Backlit.REACH * diag
        withTransform({
            translate(orbX, orbY)
            scale(reach, reach, Offset.Zero)
        }) {
            drawRect(
                expose!!,
                Offset(-orbX / reach, -orbY / reach),
                Size(size.width / reach, size.height / reach),
                colorFilter = level(k),
                blendMode = BlendMode.Modulate,
            )
        }
    }

    /**
     * Over the whole picture: the bloom over the silhouettes' edges near the sun at [orb] (px;
     * [edge] where it shows past the buildings), the warm haze, the shafts and the motes, at
     * [seconds]. Not clipped under the heading as the sun's own things are: the haze and the shafts
     * are air the whole picture is seen through, and a clip would cut them along a line; they are
     * faint that far from a low sun, and the heading's words are drawn over them.
     */
    fun DrawScope.air(sky: SkyColors, orb: Offset, edge: SunEdge, seconds: Float) {
        val k = amount
        if (k <= 0f) return
        tints(sky)
        val w = size.width
        val h = size.height
        val d = density
        val reach = HAZE_REACH * diag
        withTransform({
            translate(orb.x, orb.y)
            scale(reach, reach, Offset.Zero)
        }) {
            drawRect(
                haze!!,
                Offset(-orb.x / reach, -orb.y / reach),
                Size(w / reach, h / reach),
                alpha = k,
                blendMode = BlendMode.Screen,
            )
        }
        // the light wrapping over the silhouettes' edges: centred where the sun shows, and,
        // with it hidden, small, at the edge it is behind
        val v = if (shown.isNaN()) 1f else Px.clamp01(shown)
        val at =
            Offset(
                edge.nearX + (edge.peekX - edge.nearX) * v,
                edge.nearY + (edge.peekY - edge.nearY) * v,
            )
        val s = SceneSprites.sun
        val p = s.sprite
        val wrap = k * (.22f + .78f * v)
        layer(s.bloom, p.bloom, at, 2.2f + 2.2f * v, .55f * wrap, gold, BlendMode.Plus)
        layer(s.sky, p.sky, at, .8f + .5f * v, .32f * wrap, gold, BlendMode.Plus)
        // the shafts
        val len = Backlit.SHAFT_LENGTH * diag
        val img = ShaftSprites.image
        for (i in 0 until Backlit.RAYS) {
            val a = SHAFT_ALPHA * k * Backlit.shaftStrength(i, open[i], mean)
            if (a < .004f) continue
            val wide = len * (SHAFT_SPREAD + .05f * Backlit.hash(i, 23))
            val angle = Math.toDegrees(Backlit.shaftAngle(i, turn).toDouble()).toFloat()
            withTransform({
                translate(orb.x, orb.y)
                rotate(angle, Offset.Zero)
                scale(len / ShaftSprites.W, wide / ShaftSprites.H, Offset.Zero)
            }) {
                drawImage(
                    img,
                    Offset(0f, -ShaftSprites.H / 2f),
                    alpha = a.coerceAtMost(1f),
                    colorFilter = shaftTint,
                    blendMode = BlendMode.Plus,
                )
            }
        }
        // the dust in the light: brightest near the sun
        val reachMotes = Backlit.MOTE_REACH * d
        for (i in 0 until Backlit.MOTES) {
            val x = orb.x + Backlit.moteX(i, seconds) * d
            val y = orb.y + Backlit.moteY(i, seconds) * d
            if (y < 0f || y > h || x < 0f || x > w) continue
            val q = hypot(x - orb.x, y - orb.y) / reachMotes
            val a = k * MOTE_ALPHA * Backlit.moteGlint(i, seconds) * exp(-2.2f * q * q)
            if (a < .01f || abs(q) < .08f) continue
            drawCircle(
                mote,
                Backlit.moteSize(i) * d,
                Offset(x, y),
                alpha = a,
                blendMode = BlendMode.Plus,
            )
        }
    }

    /** One of the sun's soft layers at [at], [g] times its size, at [alpha]. */
    private fun DrawScope.layer(
        image: ImageBitmap,
        l: SkyLayer,
        at: Offset,
        g: Float,
        alpha: Float,
        tint: ColorFilter?,
        blend: BlendMode,
    ) {
        if (alpha < .004f) return
        val s = density / l.pxPerDp * g
        withTransform({
            translate(at.x, at.y)
            scale(s, s, Offset.Zero)
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

    companion object {
        /** The exposure's strength is kept in this many steps (each a colour filter). */
        const val LEVELS = 64

        /** How far up from the horizon the far city is washed into the haze (dp). */
        const val HORIZON_BAND = 150f

        /** How far the warm haze reaches from the sun, a share of the picture's diagonal. */
        const val HAZE_REACH = 1f

        /** The shafts' strength at full, and how wide each spreads (a share of its length). */
        const val SHAFT_ALPHA = .26f
        const val SHAFT_SPREAD = .16f

        /** The motes' strength at full. */
        const val MOTE_ALPHA = .75f

        /** How much of the way to the measured value the eased values move each frame. */
        const val EASE = .3f

        /**
         * The whole look's strength at its fullest: the dark, the haze, the shafts and the glare's
         * boost all scale with it. Held under full so the sky round the sun still has colour.
         */
        const val STRENGTH = .65f
    }
}
