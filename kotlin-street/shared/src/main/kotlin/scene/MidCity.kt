package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.domain.Pass
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.presentation.scene.MidCitySprites as M
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * How the middle distance looks at an hour, as colour matrices over its baked strips
 * ([MidCitySprites]): pure arithmetic on the sky's colours, so the picture tests and the previews
 * use the very numbers the scene draws with. Each matrix is Android's 4x5 (row by row: red, green,
 * blue, alpha; the fifth column an offset in 0..255).
 */
internal object MidLook {
    /** The light falling on the city: white by day, rosy at dusk, a dim blue at night. */
    fun light(sky: SkyColors, gold: Float, cloud: Float): FloatArray {
        val d = sky.dark
        val day = floatArrayOf(1f, .99f, .96f)
        val dusk = floatArrayOf(.7f, .54f, .6f)
        val night = floatArrayOf(.15f, .17f, .27f)
        val out = FloatArray(3)
        for (c in 0 until 3) {
            out[c] =
                if (d < .55f) lerp(day[c], dusk[c], Px.smooth(0f, .55f, d))
                else lerp(dusk[c], night[c], Px.smooth(.55f, 1f, d))
        }
        // the low sun's gold, and the flat grey of an overcast day
        val warm = floatArrayOf(1.08f, .9f, .72f)
        for (c in 0 until 3) out[c] =
            lerp(out[c], out[c] * warm[c], gold * .7f) * (1f - .16f * cloud)
        return out
    }

    /** How much of the air layer [l] is seen through, 0 to 1. */
    fun haze(l: Int, sky: SkyColors, cloud: Float, mist: Float): Float {
        val base = M.LAYERS[l].haze
        val far = 1f - l * .38f
        val dusk = Px.smooth(.15f, .5f, sky.dark) * Px.smooth(.95f, .65f, sky.dark)
        val extra = (mist * .6f + cloud * .14f + dusk * .1f) * far
        return (base + (1f - base) * extra).coerceIn(0f, .92f)
    }

    /** The air's own colour between here and the far city. */
    fun air(sky: SkyColors): FloatArray {
        val a = sky.sky[2]
        val b = sky.far[2]
        return floatArrayOf(
            lerp(a.red, b.red, .4f),
            lerp(a.green, b.green, .4f),
            lerp(a.blue, b.blue, .4f),
        )
    }

    /** [c] (0..1 RGB) under [light] and seen through [h] of the [air]. */
    fun seen(c: FloatArray, light: FloatArray, air: FloatArray, h: Float): FloatArray =
        FloatArray(3) { c[it] * light[it] * (1f - h) + air[it] * h }

    /** The body's matrix: its colours lit and hazed. */
    fun body(light: FloatArray, air: FloatArray, h: Float): FloatArray {
        val m = FloatArray(20)
        for (c in 0 until 3) {
            m[c * 5 + c] = light[c] * (1f - h)
            m[c * 5 + 4] = air[c] * h * 255f
        }
        m[18] = 1f
        return m
    }

    /**
     * The windows' matrix: lit where their order passes [share] (the brighter channel minus the
     * threshold, steeply), warm to cool by their kind.
     */
    fun windows(share: Float, warm: Color, cool: Color): FloatArray {
        val m = FloatArray(20)
        val w = floatArrayOf(warm.red, warm.green, warm.blue)
        val k = floatArrayOf(cool.red, cool.green, cool.blue)
        for (c in 0 until 3) {
            m[c * 5 + 2] = k[c] - w[c]
            m[c * 5 + 4] = w[c] * 255f
        }
        val g = 9f
        val t = 1f - share
        m[15] = -g * t
        m[16] = g
        return m
    }

    /** One channel ([channel] 0 red, 1 green, 2 blue) drawn as [col] at its own strength. */
    fun channel(channel: Int, col: FloatArray, gain: Float = 1f): FloatArray {
        val m = FloatArray(20)
        for (c in 0 until 3) m[c * 5 + 4] = col[c] * 255f
        m[15 + channel] = gain
        return m
    }

    /** The foliage: from [dark] to [lit] by the leaf's shade, solid a third of the way in. */
    fun leaves(dark: FloatArray, lit: FloatArray): FloatArray {
        val m = FloatArray(20)
        for (c in 0 until 3) {
            m[c * 5] = lit[c] - dark[c]
            m[c * 5 + 4] = dark[c] * 255f
        }
        m[15] = 3f
        return m
    }

    /** The leaves' colours in [season]: the shaded and the lit side, before the hour's light. */
    fun foliage(season: SeasonState): Pair<FloatArray, FloatArray> {
        var dark = rgb(0x2E5530)
        var lit = rgb(0x7FA552)
        val spring = 1f - season.mature
        dark = mix(dark, rgb(0x4E7A36), spring)
        lit = mix(lit, rgb(0xAACF6E), spring)
        dark = mix(dark, rgb(0x8E4424), season.autumn)
        lit = mix(lit, rgb(0xE3A13F), season.autumn)
        dark = mix(dark, rgb(0xB88AA0), season.blossom * .8f)
        lit = mix(lit, rgb(0xF6DCE6), season.blossom * .8f)
        return dark to lit
    }

    /** How much of a leaf is out in [season]. */
    fun leafAlpha(season: SeasonState): Float = max(season.leaf, season.blossom).coerceIn(0f, 1f)

    /**
     * The share of the middle distance's windows lit at [hour]: none by day, coming on through the
     * dusk with the street's own lights, fewer after midnight, the last few going out after dawn.
     */
    fun share(hour: Float): Float {
        val h = ((hour % 24f) + 24f) % 24f
        return if (h >= 12f)
            .64f * Px.smooth(17.6f, 19.4f, h) * (1f - .3f * Px.smooth(22.3f, 24f, h))
        else
            (.45f - .2f * Px.smooth(0f, 4f, h) + .1f * Px.smooth(5.2f, 6.4f, h)) *
                (1f - Px.smooth(7f, 8.6f, h))
    }

    fun rgb(c: Int) =
        floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { lerp(a[it], b[it], t) }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * The middle distance, drawn: the district, the blocks across the boulevard with the boulevard, and
 * the near kerb ([MidCitySprites]), each slid along the street at its own rate (the nearer, the
 * faster: its depth in the band under the horizon), scaled with the band (a street drawn small
 * draws its city small), lit by the hour and hazed by its distance and the weather through colour
 * filters made once per sky. Its windows come on through the dusk in their own order, its lamps
 * with the street's, its signs a little after; tiny people walk both pavements, the signals turn,
 * steam rises from a few roofs on a cold day. The passing car of the far road drives between the
 * blocks and the near kerb ([back], then the backdrop's car, then [front]). Nothing is made per
 * frame.
 */
internal class MidCity(private val seed: Int) {
    private var key: Any? = null
    private var keyCloud = -1f
    private var keyMist = -1f
    private var keySeason: SeasonState? = null
    private var keyHour = -1f

    private val bodyF = arrayOfNulls<ColorFilter>(3)
    private val winF = arrayOfNulls<ColorFilter>(3)
    private val leafF = arrayOfNulls<ColorFilter>(3)
    private val snowF = arrayOfNulls<ColorFilter>(3)
    private val topF = arrayOfNulls<ColorFilter>(3)
    private var lampF: ColorFilter? = null
    private var signF: ColorFilter? = null
    private val haze = FloatArray(3)
    private var share = 0f
    private var lamps = 0f
    private var signs = 0f
    private var leafA = 0f
    private var snowA = 0f
    private var topA = 0f
    private var gold = 0f
    private var dark = 0f
    private val walkerInk = Array(3) { Array(WALKER_INKS.size) { Color.Black } }
    private var steamInk: ColorFilter? = null
    private val pass = Pass()
    private val trainInk = Array(TRAIN_INKS.size) { Color.Black }
    private var trainLit = 0f
    private var washBrush: Brush? = null
    private var washRight = false
    private var washH = 0f

    /** The band's scale: how tall the street's band is against its full size. */
    var k = 1f
        private set

    private fun looks(sky: SkyColors, hour: Float, cloud: Float, mist: Float, season: SeasonState) {
        if (
            key === sky &&
                keyCloud == cloud &&
                keyMist == mist &&
                keySeason === season &&
                keyHour == hour
        )
            return
        gold = Dusk.gold(hour)
        dark = sky.dark
        val light = MidLook.light(sky, gold, cloud)
        val air = MidLook.air(sky)
        val (dark, lit) = MidLook.foliage(season)
        val snowCol = MidLook.rgb(0xF2F5FA)
        val top = MidLook.mix(floatArrayOf(1f, .98f, .92f), floatArrayOf(1f, .74f, .46f), gold)
        share = MidLook.share(hour)
        val warm = Color(0xFFFFCF86)
        val cool = Color(0xFFC4DAFF)
        for (l in 0 until 3) {
            val h = MidLook.haze(l, sky, cloud, mist)
            haze[l] = h
            bodyF[l] = filter(MidLook.body(light, air, h))
            winF[l] = filter(MidLook.windows(share, warm, cool))
            leafF[l] =
                filter(
                    MidLook.leaves(
                        MidLook.seen(dark, light, air, h),
                        MidLook.seen(lit, light, air, h),
                    )
                )
            snowF[l] = filter(MidLook.channel(1, MidLook.seen(snowCol, light, air, h)))
            topF[l] =
                filter(
                    MidLook.channel(
                        2,
                        MidLook.mix(top, MidLook.seen(top, light, air, 0f), sky.dark),
                    )
                )
            for (i in WALKER_INKS.indices) {
                val c = MidLook.seen(MidLook.rgb(WALKER_INKS[i]), light, air, h)
                walkerInk[l][i] =
                    Color(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f))
            }
        }
        lampF = filter(MidLook.channel(0, MidLook.rgb(0xFFDDA0)))
        signF = filter(MidLook.channel(1, MidLook.rgb(0xFFEBD0)))
        lamps = Dusk.lightsOn(seed, hour, Dusk.LAMP)
        signs = Dusk.lightsOn(seed + 7, hour, Dusk.SIGN)
        leafA = MidLook.leafAlpha(season)
        snowA = season.snow.coerceIn(0f, 1f)
        // the tops catch the sun: a little at noon, strongly in the golden hour, not at night
        topA = (.16f + .5f * gold) * (1f - Px.smooth(.3f, .7f, sky.dark)) * (1f - .7f * cloud)
        for (i in TRAIN_INKS.indices) {
            val c =
                MidLook.seen(
                    MidLook.rgb(TRAIN_INKS[i]),
                    if (i >= 3) WHITE else light,
                    air,
                    haze[M.FAR] * (if (i >= 3) .5f else 1f),
                )
            trainInk[i] = Color(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f))
        }
        trainLit = Px.smooth(.25f, .7f, sky.dark)
        val steam = MidLook.seen(floatArrayOf(.95f, .96f, .98f), light, air, haze[1])
        steamInk = ColorFilter.tint(Color(steam[0], steam[1], steam[2]), BlendMode.SrcIn)
        key = sky
        keyCloud = cloud
        keyMist = mist
        keySeason = season
        keyHour = hour
    }

    private fun filter(m: FloatArray) = ColorFilter.colorMatrix(ColorMatrix(m))

    /**
     * The district and the blocks with the boulevard. [horizon] and [street] are the y of the
     * horizon and of the pavement line (px), [shift] how far along the street the view stands.
     */
    fun DrawScope.back(
        sky: SkyColors,
        hour: Float,
        horizon: Float,
        street: Float,
        shift: Float,
        seconds: Float,
        season: SeasonState,
        cloud: Float,
        mist: Float,
        sunRight: Boolean,
    ) {
        k = ((street - horizon) / (M.BAND * density)).coerceIn(.15f, 1.6f)
        looks(sky, hour, cloud, mist, season)
        val images = SceneSprites.midCity
        layer(M.FAR, images[M.FAR], horizon, shift)
        if (Ambient.train(seconds, seed, pass)) train(pass, horizon)
        layer(M.BLOCKS, images[M.BLOCKS], horizon, shift)
        extras(sky, horizon, shift, seconds, season, hour)
        walkers(M.BLOCKS, horizon, shift, seconds)
        sunWash(sky, horizon, street, sunRight)
    }

    /** The near kerb, its people. */
    fun DrawScope.front(horizon: Float, shift: Float, seconds: Float) {
        layer(M.KERB, SceneSprites.midCity[M.KERB], horizon, shift)
        walkers(M.KERB, horizon, shift, seconds)
    }

    /** The camera's turn as pixels the whole district slides right ([Backdrop.yaw]). */
    var yaw = 0f

    /** Where layer [l]'s first period starts on screen (px): slid by its share of the street, and by the camera's turn. */
    private fun DrawScope.origin(l: Int, shift: Float): Float {
        val span = M.SPAN * density * k
        val slide = shift * density * k * DRIFT_UNIT * M.drift(l) - yaw
        var x = -(((slide % span) + span) % span)
        if (x > 0f) x -= span
        return x
    }

    private fun DrawScope.layer(l: Int, images: MidCityImages, horizon: Float, shift: Float) {
        val span = M.SPAN * density * k
        val x0 = origin(l, shift)
        var x = x0
        while (x < size.width) {
            strip(images.body, x, horizon, bodyF[l], 1f)
            if (leafA > .01f) strip(images.masks, x, horizon, leafF[l], leafA)
            if (snowA > .01f) strip(images.masks, x, horizon, snowF[l], snowA)
            if (topA > .01f) strip(images.masks, x, horizon, topF[l], topA * (1f - haze[l]))
            if (images.windows != null && share > .004f && dark > .05f)
                strip(
                    images.windows,
                    x,
                    horizon,
                    winF[l],
                    (1f - .55f * haze[l]) * Px.smooth(.05f, .45f, dark),
                )
            if (images.glow != null) {
                if (lamps > .01f)
                    strip(images.glow, x, horizon, lampF, lamps * (1f - .4f * haze[l]))
                if (signs > .01f)
                    strip(images.glow, x, horizon, signF, signs * (1f - .4f * haze[l]))
            }
            x += span
        }
    }

    /** One period of a strip at [x] (px), only the part in view. */
    private fun DrawScope.strip(
        s: MidCityImage,
        x: Float,
        horizon: Float,
        filter: ColorFilter?,
        alpha: Float,
    ) {
        val sc = density * k / s.strip.ppd
        val top = horizon + s.strip.topDp * density * k
        val from = floor(-x / sc).toInt().coerceIn(0, s.strip.w)
        val to = ceil((size.width - x) / sc).toInt().coerceIn(0, s.strip.w)
        if (to <= from) return
        withTransform({
            translate(x, top)
            scale(sc, sc, Offset.Zero)
        }) {
            drawImage(
                s.image,
                IntOffset(from, 0),
                IntSize(to - from, s.strip.h),
                IntOffset(from, 0),
                IntSize(to - from, s.strip.h),
                alpha = alpha.coerceIn(0f, 1f),
                colorFilter = filter,
            )
        }
    }

    /** The signals at the side streets, and the steam off the roofs on a cold day. */
    private fun DrawScope.extras(
        sky: SkyColors,
        horizon: Float,
        shift: Float,
        seconds: Float,
        season: SeasonState,
        hour: Float,
    ) {
        val u = density * k
        val span = M.SPAN * u
        val x0 = origin(M.BLOCKS, shift)
        val phase = ((seconds / SIGNAL_CYCLE) % 1f + 1f) % 1f
        val lens = if (phase < .46f) 2 else if (phase < .54f) 1 else 0
        val lensInk = SIGNAL_INK[lens]
        val signals = M.signals
        val cold = season.snow > .05f || season.dayOfYear < 75 || season.dayOfYear > 320
        val steam = if (cold) Dusk.steam(hour, season.dayOfYear) else 0f
        val vents = M.vents
        val puffs = SceneSprites.dusk.puffs
        var x = x0
        while (x < size.width) {
            for (i in 0 until signals.size / 2) {
                val sx = x + signals[i * 2] * u
                if (sx < -4f * u || sx > size.width + 4f * u) continue
                val sy = horizon + (signals[i * 2 + 1] + .45f + lens * .7f) * u
                drawCircle(lensInk, .9f * u, Offset(sx, sy), .25f * (1f - .5f * sky.dark) + .2f)
                drawCircle(lensInk, .26f * u, Offset(sx, sy))
            }
            if (steam > .02f && seconds >= 0f)
                for (i in 0 until vents.size / 2) {
                    val vx = x + vents[i * 2] * u
                    if (vx < -10f * u || vx > size.width + 10f * u) continue
                    val vy = horizon + vents[i * 2 + 1] * u
                    for (j in 0 until Dusk.PUFFS) {
                        val t = Dusk.puff(seed * 31 + i, j, seconds)
                        val img = puffs[j % puffs.size]
                        val r = (1.2f + 3.6f * t) * u
                        val a =
                            steam *
                                .5f *
                                (1f - t) *
                                (1f - haze[M.BLOCKS] * .5f) *
                                Px.smooth(0f, .15f, t)
                        if (a < .01f) continue
                        val s = 2f * r / img.width
                        withTransform({
                            translate(vx + 2.6f * t * t * u, vy - 7f * t * u)
                            scale(s, s, Offset.Zero)
                        }) {
                            drawImage(
                                img,
                                Offset(-img.width / 2f, -img.height / 2f),
                                a,
                                colorFilter = steamInk,
                            )
                        }
                    }
                }
            x += span
        }
    }

    /**
     * The tiny people on the pavement of layer [l]: each walks at its own pace one way or the other
     * round the span, legs swinging; at 0 seconds they stand where they always stand.
     */
    private fun DrawScope.walkers(l: Int, horizon: Float, shift: Float, seconds: Float) {
        val u = density * k
        val span = M.SPAN * u
        val near = l == M.KERB
        val n = if (near) 16 else 22
        val tall = (if (near) M.NEAR_PERSON else M.FAR_PERSON) * u
        val foot = horizon + (if (near) M.NEAR_WALK else M.FAR_WALK) * u
        val x0 = origin(l, shift)
        val inks = walkerInk[l]
        for (i in 0 until n) {
            val salt = l * 1000 + i
            val dir = if (Ambient.hash(seed, salt, 501) < .5f) -1f else 1f
            val speed = (if (near) 2.2f else 1.3f) * (.75f + .5f * Ambient.hash(seed, salt, 502))
            val pos = M.SPAN * Ambient.hash(seed, salt, 503) + dir * speed * seconds
            val at = ((pos % M.SPAN) + M.SPAN) % M.SPAN
            val coat = inks[(Ambient.hash(seed, salt, 504) * (inks.size - 1)).toInt()]
            val legs = inks[inks.size - 1]
            val h = tall * (.88f + .2f * Ambient.hash(seed, salt, 505))
            val swing = if (seconds > 0f) sin(seconds * speed * 3.2f + i) else 0f
            var x = x0 + at * u
            while (x < size.width + tall) {
                if (x > -tall) {
                    val hip = foot - h * .46f
                    val stroke = h * .13f
                    drawLine(
                        legs,
                        Offset(x, hip),
                        Offset(x + swing * h * .16f, foot),
                        stroke,
                        StrokeCap.Round,
                    )
                    drawLine(
                        legs,
                        Offset(x, hip),
                        Offset(x - swing * h * .16f, foot),
                        stroke,
                        StrokeCap.Round,
                    )
                    drawLine(
                        coat,
                        Offset(x, hip + h * .04f),
                        Offset(x, foot - h * .8f),
                        h * .24f,
                        StrokeCap.Round,
                    )
                    drawCircle(inks[0], h * .1f, Offset(x + dir * h * .02f, foot - h * .91f))
                }
                x += span
            }
        }
    }

    /**
     * The elevated train on its viaduct: its carriages (silver with a blue band, the glass dark by
     * day and lit at night), a headlight at its front after dark, as hazed as the district.
     */
    private fun DrawScope.train(p: Pass, horizon: Float) {
        val u = density * k
        val deck = horizon + (M.FAR_FOOT - M.VIADUCT) * u
        val len = M.CARRIAGE * u
        val tall = M.CARRIAGE_H * u
        val head = p.x * size.width
        val corner = CornerRadius(.7f * u, .7f * u)
        for (c in 0 until TRAIN_CARS) {
            val front = head - p.dir * c * (len + M.CARRIAGE_GAP * u)
            val left = if (p.dir > 0f) front - len else front
            if (left > size.width || left + len < 0f) continue
            drawRoundRect(
                trainInk[0],
                Offset(left, deck - tall - .15f * u),
                Size(len, tall),
                corner,
            )
            drawRect(trainInk[1], Offset(left, deck - tall * .3f), Size(len, tall * .14f))
            drawRect(
                trainInk[2],
                Offset(left + .5f * u, deck - tall * .78f),
                Size(len - u, tall * .32f),
            )
            if (trainLit > .01f)
                drawRect(
                    trainInk[3],
                    Offset(left + .5f * u, deck - tall * .78f),
                    Size(len - u, tall * .32f),
                    alpha = trainLit,
                )
            // the doors' seams
            drawRect(
                trainInk[1],
                Offset(left + len * .33f, deck - tall * .8f),
                Size(.25f * u, tall * .62f),
                alpha = .5f,
            )
            drawRect(
                trainInk[1],
                Offset(left + len * .66f, deck - tall * .8f),
                Size(.25f * u, tall * .62f),
                alpha = .5f,
            )
        }
        if (trainLit > .01f) {
            val at = Offset(head - p.dir * .3f * u, deck - tall * .35f)
            drawCircle(trainInk[3], 2.4f * u, at, .25f * trainLit)
            drawCircle(trainInk[4], .45f * u, at, trainLit)
        }
    }

    /** The low sun's warm wash over the side of the city it stands on. */
    private fun DrawScope.sunWash(sky: SkyColors, horizon: Float, street: Float, right: Boolean) {
        val a = gold * (1f - sky.dark) * .22f
        if (a < .01f) return
        if (washBrush == null || washRight != right || washH != size.width) {
            val warm = Color(0xFFFFB066)
            washBrush =
                Brush.horizontalGradient(
                    if (right) listOf(Color.Transparent, warm) else listOf(warm, Color.Transparent),
                    0f,
                    size.width,
                )
            washRight = right
            washH = size.width
        }
        val top = horizon - 34f * density * k
        drawRect(washBrush!!, Offset(0f, top), Size(size.width, street - top), alpha = a)
    }

    companion object {
        /** A street unit scrolled slides the band's pavement line this many dp (at full size). */
        const val DRIFT_UNIT = 1.1f

        /** How long the signals take to go round green, amber, red (s). */
        const val SIGNAL_CYCLE = 26f

        private const val TRAIN_CARS = 5

        /** The train's body, its band, its glass, its lit windows, its headlight. */
        private val TRAIN_INKS = intArrayOf(0xB9BFC7, 0x2F5FA8, 0x2C3644, 0xFFDFA0, 0xFFF6DA)
        private val WHITE = floatArrayOf(1f, 1f, 1f)

        private val SIGNAL_INK = arrayOf(Color(0xFFFF4A3A), Color(0xFFFFB534), Color(0xFF5DF0A0))

        /** The walkers' skin, their coats (several), and their legs last. */
        private val WALKER_INKS =
            intArrayOf(
                0xE0B8A0,
                0x2F3A52,
                0x8A3A34,
                0x3E5A48,
                0xD8C8A8,
                0x5A4A6A,
                0x2A2C30,
                0x23252B,
            )
    }
}

/** A baked strip of the middle distance, as an image. */
internal class MidCityImage(val strip: MidStrip, val image: ImageBitmap)

/** A layer of the middle distance, as images ([MidLayer]). */
internal class MidCityImages(
    val body: MidCityImage,
    val windows: MidCityImage?,
    val glow: MidCityImage?,
    val masks: MidCityImage,
)
