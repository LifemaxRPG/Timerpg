package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BirdPose
import com.lifemax.rpg.feature.habits.domain.Building
import com.lifemax.rpg.feature.habits.domain.CloudKind
import com.lifemax.rpg.feature.habits.domain.Flight
import com.lifemax.rpg.feature.habits.domain.FlockPose
import com.lifemax.rpg.feature.habits.domain.LensFlare
import com.lifemax.rpg.feature.habits.domain.MoonPhase
import com.lifemax.rpg.feature.habits.domain.Pass
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.Star
import com.lifemax.rpg.feature.habits.domain.orbAt
import com.lifemax.rpg.feature.habits.domain.skyTone
import com.lifemax.rpg.feature.habits.domain.starsShown
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything behind the street, and alive: the sky of the hour with its twinkling stars (the bright
 * ones with cross-shaped glints, and now and then a shooting one), its sun (a painted disc, a
 * corona of streamers, slow rays, a wide glow) or moon (its seas and ray craters, in the phase of
 * the [day], with earthshine on the dark side), its soft volumetric clouds (lit tops, shaded
 * undersides, graded by the hour); an airplane with a contrail, birds, a blimp; three rows of far
 * city, hazier at their feet, that slide at their own speeds as the street scrolls (depth), with
 * windows of several kinds going on and off at night, antennas, and red warning lights on the
 * tallest; the glow over the horizon; a car on the far road (a painted vehicle with its lights).
 * Every moving thing is a function of [seconds] and the [seed] ([Ambient]); at 0 seconds (motion
 * reduced, or the tab hidden) it is a still picture with nothing passing. Nothing is made per
 * frame: the passes, paths, strokes and brushes are kept here, drawn under transforms (shapes are
 * in dp, brushes in the shape's own units).
 */
internal class Backdrop(private val seed: Int, day: String = "") {
    private val starField: Array<Star> = Ambient.stars(72, seed).toTypedArray()
    private val rows: Array<Array<Building>> =
        Array(SkylineSprites.ROWS.size) { SkylineSprites.buildings(it).toTypedArray() }
    private val clouds = Ambient.clouds(seed).toTypedArray()

    /**
     * Each star's colour (most blue-white or white, some pale yellow, a few gold and orange, as the
     * real ones are), and the tint of the soft glow round the bright ones.
     */
    private val starKind = IntArray(starField.size) { starKindOf(Ambient.hash(seed, it, 31)) }
    private val starGlow = Array(STAR_INK.size) { ColorFilter.tint(STAR_INK[it], BlendMode.SrcIn) }

    /**
     * The dust of faint stars, made once in units of the view's width (drawn scaled to it): three
     * groups by colour, more than half of them thick along the Milky Way's band.
     */
    private val dust: Array<List<Offset>> = dust(seed)

    /**
     * Where the latest frame's clouds are, for the shadows they throw on the street: the middle and
     * the width of each in px, and how solid it is (a near cloud more than a far one).
     */
    val shadeX = FloatArray(Ambient.CLOUDS)
    val shadeW = FloatArray(Ambient.CLOUDS)
    val shadeA = FloatArray(Ambient.CLOUDS)
    var shadeN = 0
        private set

    /** How far each row slides for a street unit scrolled: the furthest least. */
    private val drift = floatArrayOf(.05f, .1f, .18f)

    /**
     * Each far building's few windows ([SkylineSprites.windows]: across, down, kind), for which of
     * its baked lights are on at a moment.
     */
    private val windows: Array<Array<FloatArray>> =
        Array(rows.size) { r -> Array(rows[r].size) { i -> SkylineSprites.windows(r, i) } }

    /** The tallest of the furthest two rows carry the red lights. */
    private val warned: Array<BooleanArray> = Array(rows.size) { SkylineSprites.warned(it) }

    /** The height of an antenna on a far building (dp), 0 for none (baked into the row's strip). */
    private val antennas: Array<FloatArray> =
        Array(rows.size) { r ->
            FloatArray(rows[r].size) { i -> SkylineSprites.antenna(r, i, warned[r][i]) }
        }

    /** A building's windows by colour, as the row is drawn: how many, how many on. */
    private val winCount = IntArray(3)
    private val winLit = IntArray(3)

    /**
     * The window strips hold each light in one colour channel; these draw one channel as the light
     * of that kind (warm, pale, cool).
     */
    private val windowTint: Array<ColorFilter> =
        Array(3) {
            val c = arrayOf(SceneInk.FarLight, SceneInk.FarLightPale, SceneInk.FarLightCool)[it]
            val m = FloatArray(20)
            m[4] = c.red * 255f
            m[9] = c.green * 255f
            m[14] = c.blue * 255f
            m[15 + it] = 1f
            ColorFilter.colorMatrix(ColorMatrix(m))
        }

    private val pass = Pass()

    private val path = Path()

    // ---- shapes made once (in dp, in their own local units)

    /**
     * The blimp's lit panel, in dp on the envelope's side (its edges curving with the hull, read
     * from the baker), to clip the light that sweeps along it at night.
     */
    private val bandPath =
        Path().apply {
            val steps = 12
            val l = AirSprites.BAND_L + .5f
            val r = AirSprites.BAND_R - .5f
            for (i in 0..steps) {
                val x = l + (r - l) * i / steps
                val y = AirSprites.bandEdge(x, top = true) + .2f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            for (i in steps downTo 0) {
                val x = l + (r - l) * i / steps
                lineTo(x, AirSprites.bandEdge(x, top = false) - .2f)
            }
            close()
        }

    /** The light that sweeps along the blimp's panel: a soft spot, a unit circle. */
    private val sheen =
        Brush.radialGradient(
            0f to Color.White.copy(alpha = .5f),
            .5f to Color.White.copy(alpha = .16f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = 1f,
        )

    /** A bird's outline, written by [BirdShape] and read into [path] each frame. */
    private val birdCmd = FloatArray(BirdShape.CAPACITY)

    /** Where the flock and the bird being drawn are, written by [Flight] each frame. */
    private val flock = FlockPose()
    private val bird = BirdPose()

    // ---- brushes made once per sky (units of their own: unit circles, dp for the airships)

    private var skyKey: Any? = null
    private val tintShade = arrayOfNulls<ColorFilter>(Ambient.CLOUDS)
    private val tintMid = arrayOfNulls<ColorFilter>(Ambient.CLOUDS)
    private val tintLit = arrayOfNulls<ColorFilter>(Ambient.CLOUDS)
    private val tintUnder = arrayOfNulls<ColorFilter>(Ambient.CLOUDS)

    /** How strongly the clouds' bellies are lit from below this hour (0 by day). */
    private var underAlpha = 0f
    private var airGlow: Brush? = null

    // the tints of the hour for the plane's, the contrail's and the blimp's layers, and how
    // strongly each is drawn
    private val planeTint = arrayOfNulls<ColorFilter>(AirSprites.LAYERS)
    private val planeAlpha = FloatArray(AirSprites.LAYERS)
    private val blimpTint = arrayOfNulls<ColorFilter>(AirSprites.BLIMP_LAYERS)
    private val blimpAlpha = FloatArray(AirSprites.BLIMP_LAYERS)

    /** The blur of the blimp's turning propellers, in the colour of its shaded side. */
    private var blimpProp = Color.Transparent
    private val trailTint = arrayOfNulls<ColorFilter>(AirSprites.TRAIL_LAYERS)
    private val trailAlpha = FloatArray(AirSprites.TRAIL_LAYERS)

    // the birds' ink by how far they are, and the warm rim the low sun gives them
    private val birdInk = Array(3) { SceneInk.Bird }
    private var birdRim = Color.Transparent
    private var birdRimAlpha = 0f

    // ---- the sun and the moon: sprites ([SceneSprites]), coloured once per sky ([SunLook],
    // [MoonLook]); their alphas follow the weather each frame, written in place
    private val moonStep = MoonPhase.step(MoonPhase.fraction(day))
    private val moonLit = MoonPhase.illumination(MoonPhase.fraction(day))
    private val sunLook = SunLook()
    private val moonLook = MoonLook()
    private var orbKey: Any? = null
    private val sunTint = arrayOfNulls<ColorFilter>(SunLook.LAYERS)
    private var moonWarm = 0f
    private var moonAt = -1f
    private var moonFilter: ColorFilter? = null
    private var moonHalo: ColorFilter? = null

    // ---- the sky itself ([SkyDome]): a small picture painted again in place when the hour or the
    // sun's side changes, stretched over the view
    private val domePx = IntArray(SkyDome.W * SkyDome.H)
    private var dome: ImageBitmap? = null
    private var domeKey: Any? = null
    private var domeRight = false

    // ---- brushes that follow the horizon, the orb and the density
    private var glowBrush: Brush? = null
    private var brushCity = true
    /** How tall the horizon's glow is (dp): taller in the open world, where it is the air over the town's own far edge. */
    private val glowDp: Float get() = if (city) 60f else 150f
    private var haloBrush: Brush? = null
    private var haloWide: Brush? = null
    private val rowBrush = arrayOfNulls<Brush>(3)
    private val farTint = arrayOfNulls<ColorFilter>(3)
    private var brushKey: Any? = null
    private var brushH = 0f
    private var brushOrb = Offset.Zero
    private var brushD = 0f

    /**
     * [horizon] is the y of the horizon in pixels, [top] the y under the heading's band, [orb]
     * where the sun or the moon stands, [ceiling] the y above which the clouds hang (the tallest
     * roof in view), [shift] how far along the street the view stands (street units), [seconds] the
     * running time (0: a still picture).
     */
    fun DrawScope.backdrop(
        hour: Float,
        sky: SkyColors,
        horizon: Float,
        top: Float,
        orb: Offset,
        ceiling: Float,
        shift: Float,
        seconds: Float,
    ) {
        val d = density
        val w = size.width
        brushes(sky, horizon, orb)
        unitBrushes(sky)
        dome(sky, horizon, orb.x > w / 2f)
        val tone = skyTone(hour)
        val starAlpha = starsShown(tone)
        val band = (horizon - top).coerceAtLeast(40f * d)
        if (starAlpha > .01f) {
            if (starAlpha > .3f) milkyWay(w, horizon, starAlpha)
            val g = RoadSkySprites.GLOW.toFloat()
            for (i in starField.indices) {
                val s = starField[i]
                val down = s.y * .9f
                val at = Offset(if (yaw == 0f) s.x * w else ((s.x * w + yaw) % w + w) % w, down * horizon)
                // the low sky is washed by the city's light: fewer, fainter stars near the horizon
                val low = 1f - .72f * Px.smooth(.42f, .83f, down)
                val a = s.alpha * starAlpha * Ambient.twinkle(s, seconds) * low
                val ink = STAR_INK[starKind[i]]
                drawCircle(ink, s.r * d, at, a)
                if (s.r >= 1.3f) {
                    // the bright ones: a soft glow in their colour, and a cross-shaped glint
                    val k = (5f + s.r * 3f) * d / g
                    withTransform({
                        translate(at.x, at.y)
                        scale(k, k, Offset.Zero)
                    }) {
                        drawImage(
                            SceneSprites.glow,
                            Offset(-g / 2f, -g / 2f),
                            a * .5f,
                            colorFilter = starGlow[starKind[i]],
                            blendMode = BlendMode.Plus,
                        )
                    }
                    val arm = (3.4f + s.r * 2.2f) * d
                    drawLine(
                        ink,
                        Offset(at.x - arm, at.y),
                        Offset(at.x + arm, at.y),
                        .8f * d,
                        cap = StrokeCap.Round,
                        alpha = a * .6f,
                    )
                    drawLine(
                        ink,
                        Offset(at.x, at.y - arm),
                        Offset(at.x, at.y + arm),
                        .8f * d,
                        cap = StrokeCap.Round,
                        alpha = a * .6f,
                    )
                }
            }
            if (starAlpha > .6f && Ambient.shootingStar(seconds, seed, pass))
                shootingStar(pass, w, top, band, starAlpha)
        }
        moonCover =
            cover(sky, orb, w, top, horizon, ceiling, orb.x > w / 2f, shift, seconds, density)
        heavenlyBody(sky, hour, orb, horizon, seconds)
        lensFlare(sky, orb, top, shift)
        clouds(sky, w, top, horizon, ceiling, orb.x > w / 2f, shift, seconds)
        occultation(sky, orb, w, top, horizon, ceiling, orb.x > w / 2f, shift, seconds)
        if (Ambient.blimp(seconds, seed, pass)) blimp(pass, sky, w, top, horizon, ceiling, seconds)
        if (Ambient.plane(seconds, seed, pass)) {
            // a plane flies into the clouds in its way and out again: hidden as thick as the cloud
            // in front of it is, its contrail with it
            val inCloud =
                cover(
                    sky,
                    Offset(pass.x * w, top + pass.y * band),
                    w,
                    top,
                    horizon,
                    ceiling,
                    orb.x > w / 2f,
                    shift,
                    seconds,
                    density,
                    PLANE_HIDE_R * density,
                )
            val shows = (1f - inCloud * 1.15f).coerceIn(0f, 1f)
            if (shows > .01f) {
                if (shows < .99f) {
                    planePaint.alpha = shows
                    drawIntoCanvas { it.saveLayer(Rect(0f, 0f, w, horizon), planePaint) }
                }
                plane(pass, sky, w, top, band, seconds, tone)
                if (shows < .99f) drawIntoCanvas { it.restore() }
            }
        }
        if (city) for (r in rows.indices) row(r, sky, horizon, shift * drift[r] - yaw / d, seconds)
        if (tone >= .7f && Ambient.birds(seconds, seed, pass))
            birds(pass, sky, w, top, horizon, ceiling, seconds)
        // the glow over the horizon: the city's light at night, haze by day
        drawRect(glowBrush!!, Offset(0f, horizon - glowDp * d), Size(w, glowDp * d))
        if (city) {
            drawRect(sky.ground[0], Offset(0f, horizon), Size(w, size.height - horizon))
            drawLine(sky.line, Offset(0f, horizon), Offset(w, horizon), d)
        } else {
            // the open world (Filament draws the land over this): below the horizon the town's haze colour, the glow over
            // the sky's lowest band (FilamentTown's haze), and no horizon line: the few pixels between the far end of the
            // drawn land and the true horizon showed that line and the stage's ground as a stripe ("still line on the horizon")
            val low = sky.sky.lastOrNull() ?: sky.ground[0]
            drawRect(lerp(low, sky.glow.copy(alpha = 1f), sky.glow.alpha), Offset(0f, horizon), Size(w, size.height - horizon))
        }
        weather(sky, w, horizon, orb, shift, seconds)
        // the city between the street and the far skyline, the passing car on its boulevard
        if (!city) return
        val pave = if (street > horizon) street else horizon + MidCitySprites.BAND * d
        midCity.yaw = yaw
        with(midCity) {
            back(sky, hour, horizon, pave, shift, seconds, season, cloudiness, mist, orb.x > w / 2f)
        }
        if (Ambient.car(seconds, seed, pass)) car(pass, sky, w, horizon, seconds)
        with(midCity) { front(horizon, shift, seconds) }
    }

    // ---- the weather over the sky and the far city
    // ([com.lifemax.rpg.feature.habits.domain.StreetWeather])

    /** How much of the sky is one cloud, and how thick the morning mist is: set by the stage. */
    var cloudiness = 0f
    var mist = 0f

    /**
     * How far the camera has turned, as screen pixels the sky slides right (the corner turn: its lens times the angle).
     * Every far layer slides by it together, the clouds, the stars and the far city alike, as a turn moves them; the
     * stage passes the sun or the moon already turned. 0 on the main street's own view.
     */
    var yaw = 0f

    /**
     * Whether the painted city stands under the sky: the far skyline's rows, the middle distance and its passing car.
     * Off in the open world, where the horizon is the town's own (the world's edge of woods in the haze): only the sky,
     * its clouds, stars, sun or moon, the horizon's glow and the weather's veil are painted there.
     */
    var city = true

    /**
     * The y of the street's pavement line (px), and the season, set by the stage: the middle
     * distance ([MidCity]) fills the band between the horizon and that line, dressed for the
     * season.
     */
    var street = 0f
    var season: SeasonState = Season.SUMMER

    private val midCity = MidCity(seed)

    private var veilKey: Any? = null
    private var veilH = 0f
    private var veilBrush: Brush? = null
    private var veilTint: ColorFilter? = null
    private var fogTint: ColorFilter? = null

    /**
     * An overcast sky is one cloud: a grey veil over everything above the horizon (the sun a pale
     * disc through it, the far city and the horizon's glow flattened), darker overhead and paler at
     * the horizon, with a few low grey stratus dragging across the far city's tops. A misty morning
     * is a bright band of fog over the far city's feet and the lower sky, in two layers drifting
     * apart, which the far rows fade into; the stage lets it clear by mid-morning.
     */
    private fun DrawScope.weather(
        sky: SkyColors,
        w: Float,
        horizon: Float,
        orb: Offset,
        shift: Float,
        seconds: Float,
    ) {
        val cloud = cloudiness
        val fog = mist
        if (cloud < .01f && fog < .01f) return
        val d = density
        if (veilKey !== sky || veilH != horizon || veilBrush == null) {
            // by day the veil is the grey of a rain sky; at night it is the sky's own dark
            val grey = lerp(Color(0xFF9CA3AF), lerp(sky.sky[1], sky.sky[0], .5f), sky.dark * .85f)
            veilBrush =
                Brush.verticalGradient(
                    0f to lerp(grey, Color.Black, .28f),
                    .55f to grey,
                    1f to lerp(grey, Color.White, .12f * (1f - sky.dark)),
                    startY = 0f,
                    endY = horizon,
                )
            veilTint = ColorFilter.tint(lerp(grey, Color.Black, .18f), BlendMode.SrcIn)
            fogTint = ColorFilter.tint(lerp(sky.sky[2], Color.White, .55f), BlendMode.SrcIn)
            veilKey = sky
            veilH = horizon
        }
        if (cloud >= .01f) {
            drawRect(veilBrush!!, Offset.Zero, Size(w, horizon + d), alpha = .82f * cloud)
            throughVeil(sky, orb)
            // the low ragged ceiling: stratus over the far city, slow, wide, grey
            val pool = SceneSprites.clouds
            val which = CloudSprites.of(CloudKind.STRATUS)
            val span = w * 1.5f
            for (i in 0 until 3) {
                val images = pool[which[i % which.size]]
                val sp = images.sprite
                val s = d * 1.5f / CloudSprites.PX_PER_DP
                val travel = seconds * (3f + i) * d - shift * .02f * d + i * span * .37f + yaw
                val x = ((travel % span) + span) % span - span * .2f
                val base = horizon - (96f - i * 14f) * d
                withTransform({
                    translate(x, base)
                    scale(if (i == 1) -s else s, s * .8f, Offset.Zero)
                }) {
                    val at = Offset(-sp.w / 2f, -sp.h * .86f)
                    drawImage(images.shade, at, .5f * cloud, colorFilter = veilTint)
                }
            }
        }
        if (fog >= .01f) {
            val brush = SceneSprites.weather.fog
            val px = d / WeatherSprites.PX_PER_UNIT
            for (layer in 0 until 2) {
                val s = px * (if (layer == 0) 1.5f else 2.4f)
                val tall = WeatherSprites.FOG_H * s
                val drift = seconds * (if (layer == 0) 2.5f else -1.5f) * d - shift * .04f * d + yaw
                val a = fog * (if (layer == 0) .85f else .4f)
                withTransform({
                    translate(drift, horizon - tall * .78f)
                    scale(s, s, Offset.Zero)
                }) {
                    // the band, and (the brush holds its last row) on down over the ground's strip
                    val x0 = -drift / s - WeatherSprites.FOG_W
                    drawRect(
                        brush,
                        Offset(x0, 0f),
                        Size(w / s - x0 + WeatherSprites.FOG_W, WeatherSprites.FOG_H + 60f * d / s),
                        alpha = a,
                        colorFilter = fogTint,
                    )
                }
            }
        }
    }

    private fun DrawScope.brushes(sky: SkyColors, horizon: Float, orb: Offset) {
        if (
            brushKey === sky &&
                brushH == horizon &&
                brushOrb == orb &&
                brushD == density &&
                brushCity == city &&
                glowBrush != null
        )
            return
        brushCity = city
        glowBrush =
            Brush.verticalGradient(
                listOf(Color.Transparent, sky.glow),
                startY = horizon - glowDp * density,
                endY = horizon,
            )
        // the sun's corona is wide and warm, the moon's halo small and pale
        val reach = (if (sky.phase == SkyPhase.DAY) 74f else 52f) * density
        haloBrush =
            Brush.radialGradient(
                0f to sky.halo,
                .28f to sky.halo.copy(alpha = sky.halo.alpha * .55f),
                1f to Color.Transparent,
                center = orb,
                radius = reach,
            )
        // the sun's wide glow: the whole sky near it warms, very faintly
        haloWide =
            Brush.radialGradient(
                0f to sky.halo.copy(alpha = sky.halo.alpha * .42f),
                .45f to sky.halo.copy(alpha = sky.halo.alpha * .13f),
                1f to Color.Transparent,
                center = orb,
                radius = (if (sky.phase == SkyPhase.DAY) 230f else 130f) * density,
            )
        for (r in 0 until 3) {
            // aerial perspective: the further the row, the more of the horizon's air it is seen
            // through, and every one thins into it at its feet. The air is the sky behind showing
            // through (alpha), on the same curve the row's baked strip carries
            // ([SkylineSprites.haze]); this brush paints the solid feet under the strip
            val far = sky.far[r]
            fun at(dp: Float) = far.copy(alpha = 1f - SkylineSprites.haze(r, dp))
            val span = SkylineSprites.HAZE_SPAN
            rowBrush[r] =
                Brush.verticalGradient(
                    0f to at(span),
                    .5f to at(span / 2f),
                    1f to at(0f),
                    startY = horizon - span * density,
                    endY = horizon,
                )
            farTint[r] = ColorFilter.tint(far, BlendMode.SrcIn)
        }
        brushKey = sky
        brushH = horizon
        brushOrb = orb
        brushD = density
    }

    private fun unitBrushes(sky: SkyColors) {
        if (skyKey === sky) return
        // each cloud's four tints: the hour's, thinned into the horizon's haze the further it is.
        // At night its moonlit rims are as bright as the moon is; its belly is lit from below by
        // the sun gone under the horizon at dusk (coral and gold) and by the city's glow at night
        // (a dusty warm grey)
        val dusk = Px.smooth(.04f, .42f, sky.dark) * (1f - Px.smooth(.7f, .97f, sky.dark))
        val night = Px.smooth(.6f, 1f, sky.dark)
        val under = lerp(lerp(sky.glow.copy(alpha = 1f), UNDER_DUSK, .45f), UNDER_CITY, night)
        underAlpha = (.85f * dusk + .6f * night).coerceAtMost(.85f)
        val lit = lerp(sky.cloudLit, sky.cloudBody, night * (1f - moonLit) * .7f)
        for (i in clouds.indices) {
            val c = clouds[i]
            val haze = c.haze * .55f
            val horizon = sky.sky[2]
            fun tint(col: Color, k: Float) =
                ColorFilter.tint(lerp(col, horizon, (haze * k).coerceIn(0f, 1f)), BlendMode.SrcIn)
            tintShade[i] = tint(sky.cloudShade, 1f)
            tintMid[i] = tint(sky.cloudBody, .8f)
            tintLit[i] = tint(lit, .55f)
            tintUnder[i] = tint(under, .6f)
        }
        airTints(sky)
        airGlow =
            Brush.radialGradient(
                listOf(sky.cloudLit.copy(alpha = .4f), Color.Transparent),
                Offset.Zero,
                1f,
            )
        skyKey = sky
    }

    /**
     * What the hour does to the things of the air. By day the plane is bright metal, lit from the
     * upper left, its belly taking the sky's light; at dusk the belly warms with the horizon; at
     * night it is a silhouette a shade darker than the sky with its windows lit. The blimp the
     * same, and its panel goes from a painted band to a lit one. The birds are dark against the
     * day, warm-rimmed at dusk; the far ones thin into the air.
     */
    private fun airTints(sky: SkyColors) {
        fun tint(c: Color) = ColorFilter.tint(c, BlendMode.SrcIn)
        val dark = sky.dark
        val dusk = Px.smooth(.2f, .7f, dark)
        val horizonGlow = sky.glow.copy(alpha = 1f)
        run {
            val night = Px.smooth(.6f, 1f, dark)
            val litWindows = Px.smooth(.3f, .75f, dark)
            planeTint[AirSprites.SHADE] =
                tint(
                    lerp(
                        lerp(sky.cloudShade, SceneInk.Plane, .3f),
                        lerp(sky.far[0], Color.Black, .55f),
                        night,
                    )
                )
            planeAlpha[AirSprites.SHADE] = 1f
            planeTint[AirSprites.LIT] = tint(sky.cloudLit)
            planeAlpha[AirSprites.LIT] = 1f - .72f * night
            planeTint[AirSprites.GLINT] = tint(Color.White)
            planeAlpha[AirSprites.GLINT] = 1f - .75f * night
            planeTint[AirSprites.BOUNCE] = tint(lerp(sky.sky[2], horizonGlow, .35f * dusk))
            planeAlpha[AirSprites.BOUNCE] = .62f - .3f * night
            planeTint[AirSprites.GLOW] = tint(lerp(SceneInk.Plane, SceneInk.WindowLit, litWindows))
            planeAlpha[AirSprites.GLOW] = .7f + .25f * litWindows
        }
        trailTint[AirSprites.TRAIL_BODY] = tint(lerp(sky.cloudBody, sky.sky[2], .25f))
        trailAlpha[AirSprites.TRAIL_BODY] = .55f
        trailTint[AirSprites.TRAIL_CORE] = tint(sky.cloudLit)
        trailAlpha[AirSprites.TRAIL_CORE] = .9f
        run {
            // the blimp is far off: every tint of it leans a little into the air between
            val night = Px.smooth(.5f, 1f, dark)
            val lit = Px.smooth(.35f, .8f, dark)
            val air = lerp(sky.sky[1], sky.sky[2], .5f)
            val haze = .16f
            val shade =
                lerp(
                    lerp(lerp(sky.cloudShade, SceneInk.BlimpShade, .3f), air, haze),
                    lerp(sky.cloudShade, sky.far[0], .55f),
                    night,
                )
            blimpTint[AirSprites.SHADE] = tint(shade)
            blimpAlpha[AirSprites.SHADE] = 1f
            blimpTint[AirSprites.LIT] = tint(lerp(sky.cloudLit, air, haze * .6f))
            blimpAlpha[AirSprites.LIT] = 1f - .7f * night
            blimpTint[AirSprites.BOUNCE] =
                tint(lerp(lerp(sky.sky[1], sky.sky[2], .55f), horizonGlow, .4f * dusk))
            blimpAlpha[AirSprites.BOUNCE] = .55f - .25f * night
            blimpTint[AirSprites.LIVERY] =
                tint(
                    lerp(
                        lerp(SceneInk.BlimpLivery, air, haze),
                        lerp(SceneInk.BlimpLivery, sky.far[0], .7f),
                        night,
                    )
                )
            blimpAlpha[AirSprites.LIVERY] = 1f - .55f * night
            blimpTint[AirSprites.GLINT] = tint(Color.White)
            blimpAlpha[AirSprites.GLINT] = .85f - .75f * night
            blimpTint[AirSprites.GLOW] = tint(lerp(SceneInk.BlimpLivery, SceneInk.WindowLit, lit))
            blimpAlpha[AirSprites.GLOW] = lit
            blimpProp = lerp(shade, sky.cloudLit, .35f * (1f - night))
        }
        val duskInk = lerp(SceneInk.Bird, SceneInk.BirdDusk, dusk)
        for (tier in 0 until 3) {
            val fade = 1f - .22f * tier
            birdInk[tier] = lerp(duskInk, sky.sky[1], (1f - fade) * .6f)
        }
        birdRim = sky.halo.copy(alpha = 1f)
        birdRimAlpha = Px.smooth(.15f, .5f, dark) * Px.smooth(.95f, .6f, dark)
    }

    /**
     * The sun or the moon, from sprites painted once ([SunMoonSprites]) and coloured for the hour
     * ([SunLook], [MoonLook]). The sun by day is light, not a thing: an overexposed core blooming
     * smoothly into the sky it brightens, with a few long soft rays turning slowly (still in a
     * still picture); through the dusk hour its body comes out of the bloom, low, large, orange,
     * flattened, its glow hugging it and spreading along the horizon, and after sunset it fades
     * into the haze. The moon: the picture of its phase (its seas and ray craters, the relief along
     * the terminator, the earthshine on its dark side), fading in as the sun goes, under the air's
     * soft halo, as strong as it is bright, wider in mist.
     */
    private fun DrawScope.heavenlyBody(
        sky: SkyColors,
        hour: Float,
        orb: Offset,
        horizon: Float,
        seconds: Float,
    ) {
        orbTints(sky, hour, orb, horizon)
        val d = density
        if (sky.phase == SkyPhase.NIGHT) {
            val m = SceneSprites.moon
            val l = moonLook
            l.alphas(moonLit, MoonLook.up(sky.dark), cloudiness, mist)
            // behind a cloud the moon is hidden as thick as the cloud is: its disc first, its
            // halo less (the cloud's edge glows a little where the moon is behind it)
            val clear = 1f - moonCover
            val body = l.body * clear * clear
            if (body < .004f && l.halo * clear < .004f) return
            val pic = SceneSprites.moonPicture(moonStep)
            translate(orb.x, orb.y) {
                if (body >= .004f)
                    orbLayer(pic.image, pic.layer, d, 1f, 1f, body, moonFilter, BlendMode.SrcOver)
                // the air in front of it, over its dark side too
                orbLayer(m.halo, m.sprite.halo, d, 1f, 1f, l.halo * clear, moonHalo, BlendMode.Plus)
                val haze = l.haze * (.35f + .65f * clear)
                if (haze > .004f)
                    orbLayer(m.halo, m.sprite.halo, d, 2.4f, 2.4f, haze, moonHalo, BlendMode.Plus)
            }
            return
        }
        val k = sunLook
        k.alphas(
            SunLook.dusk(sky.dark),
            SunLook.up(sky.dark),
            SunLook.low((horizon - orb.y) / d),
            cloudiness,
            mist,
        )
        val s = SceneSprites.sun
        val p = s.sprite
        translate(orb.x, orb.y) {
            sunLayer(s.sky, p.sky, SunLook.WIDE)
            // the band of glow along the horizon under a low sun
            if (k.alpha[SunLook.BAND] > .004f)
                translate(0f, (horizon - orb.y) * k.bandDrop) {
                    orbLayer(
                        s.sky,
                        p.sky,
                        d,
                        SunLook.BAND_SX,
                        SunLook.BAND_SY,
                        k.alpha[SunLook.BAND],
                        sunTint[SunLook.BAND],
                        BlendMode.Plus,
                    )
                }
            sunLayer(s.bloom, p.bloom, SunLook.GLOW)
            rotate(seconds * SunLook.RAY_TURN, Offset.Zero) {
                sunLayer(s.rays, p.rays, SunLook.RAYS)
            }
            sunLayer(s.core, p.core, SunLook.HUG)
            sunLayer(s.disc, p.disc, SunLook.DISC)
            sunLayer(s.discLight, p.discLight, SunLook.DISC_LIGHT)
            sunLayer(s.bloom, p.bloom, SunLook.BLOOM)
            sunLayer(s.core, p.core, SunLook.CORE)
        }
    }

    /** One of the sun's layers, as [sunLook] has it now. */
    private fun DrawScope.sunLayer(image: ImageBitmap, l: SkyLayer, layer: Int) {
        // behind a cloud the disc goes first, its bloom and rays less; the wide glow stays
        val clear = 1f - moonCover
        val hide =
            when (layer) {
                SunLook.DISC,
                SunLook.DISC_LIGHT,
                SunLook.CORE,
                SunLook.HUG -> clear * clear
                SunLook.BLOOM,
                SunLook.RAYS -> .25f + .75f * clear
                else -> 1f
            }
        val a = sunLook.alpha[layer] * hide
        if (a < .004f) return
        val g = sunLook.grow[layer]
        val blend = if (SunLook.added(layer)) BlendMode.Plus else BlendMode.SrcOver
        orbLayer(image, l, density, g, g, a, sunTint[layer], blend)
    }

    /**
     * The sun or the moon through the overcast's veil (drawn over it): a pale diffuse brightness
     * where it is, not a disc.
     */
    private fun DrawScope.throughVeil(sky: SkyColors, orb: Offset) {
        if (orbKey !== sky) return
        val d = density
        if (sky.phase == SkyPhase.NIGHT) {
            val a = moonLook.veil
            if (a < .004f) return
            val m = SceneSprites.moon
            translate(orb.x, orb.y) {
                orbLayer(m.halo, m.sprite.halo, d, 1.6f, 1.6f, a, moonHalo, BlendMode.Plus)
            }
        } else {
            val s = SceneSprites.sun
            translate(orb.x, orb.y) { sunLayer(s.bloom, s.sprite.bloom, SunLook.VEIL) }
        }
    }

    // ---- the lens flare

    /**
     * How clear the sun is of the buildings in front of it, 0 behind one to 1 in the open: set by
     * the stage each frame from the layouts, so the flare fades as the sun goes behind a roof.
     */
    var sunClear = 1f

    /**
     * How far the camera looks into the sun ([Backlight.amount]), set by the stage: the flare
     * stronger, its streak longer.
     */
    var backlit = 0f

    /** How far a cloud is across the sun now (0 clear to 1), for its glare at a building's edge. */
    val sunCover: Float
        get() = moonCover

    private var flareKey: Any? = null
    private var streakTint: ColorFilter? = null
    private val ghostTint = arrayOfNulls<ColorFilter>(LensFlare.GHOSTS)

    /**
     * The camera's flare when the sun is in frame by day: an anamorphic streak through the sun, and
     * a few ghosts of the iris (soft discs, rings, hexagons, each a little its own colour) on the
     * line from the sun through the middle of the picture, on the far side, so they travel across
     * the picture as the sun's place changes and wander as the street pans ([LensFlare]). All added
     * over the sky, faint; dimmer at dusk, gone at night and behind a building; never over the
     * heading (clipped under [top]).
     */
    private fun DrawScope.lensFlare(sky: SkyColors, orb: Offset, top: Float, shift: Float) {
        if (sky.phase == SkyPhase.NIGHT) return
        // (gone with the sun after sunset, and behind the overcast)
        val k =
            LensFlare.strength(sky.dark, sunClear) *
                SunLook.up(sky.dark) *
                (1f - cloudiness.coerceIn(0f, 1f)) *
                (1f - moonCover) *
                (1f - moonCover) *
                (1f + 1.2f * backlit.coerceIn(0f, 1f))
        if (k < .01f) return
        if (flareKey !== sky) {
            val base = lerp(sky.halo.copy(alpha = 1f), Color.White, .35f)
            streakTint = ColorFilter.tint(lerp(base, Color.White, .3f), BlendMode.SrcIn)
            for (i in 0 until LensFlare.GHOSTS) ghostTint[i] =
                ColorFilter.tint(lerp(base, GHOST_HUES[i], .45f), BlendMode.SrcIn)
            flareKey = sky
        }
        val d = density
        val w = size.width
        val h = size.height
        val f = SceneSprites.flare
        val sp = f.sprite
        clipRect(0f, top, w, h) {
            val s = d / sp.streakPpd * 1.4f * (1f + .5f * backlit.coerceIn(0f, 1f))
            withTransform({
                translate(orb.x, orb.y)
                scale(s, s * .85f, Offset.Zero)
            }) {
                drawImage(
                    f.streak,
                    Offset(-sp.streakW / 2f, -sp.streakH / 2f),
                    alpha = .16f * k,
                    colorFilter = streakTint,
                    blendMode = BlendMode.Plus,
                )
            }
            val cx = w / 2f
            val cy = h / 2f
            for (i in 0 until LensFlare.GHOSTS) {
                val gx = LensFlare.ghostX(orb.x, cx, i, shift, d)
                val gy = LensFlare.ghostY(orb.y, cy, i, shift, d)
                val l = sp.layer(LensFlare.KIND[i])
                val g = d / l.pxPerDp * LensFlare.SIZE[i]
                withTransform({
                    translate(gx, gy)
                    scale(g, g, Offset.Zero)
                }) {
                    drawImage(
                        f.ghost(LensFlare.KIND[i]),
                        Offset(-l.size / 2f, -l.size / 2f),
                        alpha = LensFlare.ALPHA[i] * k,
                        colorFilter = ghostTint[i],
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }

    /**
     * One layer of the sun or the moon, centred on the origin, [gx] by [gy] times its size in dp.
     */
    private fun DrawScope.orbLayer(
        image: ImageBitmap,
        l: SkyLayer,
        d: Float,
        gx: Float,
        gy: Float,
        alpha: Float,
        tint: ColorFilter?,
        blend: BlendMode,
    ) {
        val s = d / l.pxPerDp
        scale(s * gx, s * gy, Offset.Zero) {
            drawImage(
                image,
                Offset(-l.size / 2f, -l.size / 2f),
                alpha = alpha.coerceIn(0f, 1f),
                colorFilter = tint,
                blendMode = blend,
            )
        }
    }

    /**
     * The sun's and the moon's colours for this sky (kept until the sky changes), and the moon's
     * for the sky behind it where it stands (kept until it moves).
     */
    private fun orbTints(sky: SkyColors, hour: Float, orb: Offset, horizon: Float) {
        if (orbKey !== sky) {
            sunLook.tints(SunLook.dusk(sky.dark), sky.halo.toArgb(), sky.orb.toArgb())
            for (i in 0 until SunLook.LAYERS) sunTint[i] =
                ColorFilter.tint(Color(sunLook.tint[i]), BlendMode.SrcIn)
            moonWarm = MoonLook.warm(orbAt(hour).second)
            moonAt = -1f
            orbKey = sky
        }
        if (sky.phase != SkyPhase.NIGHT) return
        val at = (orb.y / horizon.coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (moonFilter != null && abs(at - moonAt) < .02f) return
        moonLook.colours(moonLit, moonWarm, skyAt(at).toArgb(), sky.halo.toArgb())
        moonFilter = ColorFilter.colorMatrix(ColorMatrix(moonLook.matrix.copyOf()))
        moonHalo = ColorFilter.tint(Color(moonLook.haloTint), BlendMode.SrcIn)
        moonAt = at
    }

    /** The sky's colour [at] a share of the way down to the horizon, as [dome] paints it. */
    private fun skyAt(at: Float): Color = Color(SkyDome.at(domePx, at))

    /**
     * The sky ([SkyDome]): painted again only when the sky of the hour or the sun's side changes,
     * into the same small picture, and stretched (smoothly) from the top of the view to the
     * horizon.
     */
    private fun DrawScope.dome(sky: SkyColors, horizon: Float, right: Boolean) {
        val image = dome ?: ImageBitmap(SkyDome.W, SkyDome.H).also { dome = it }
        if (domeKey !== sky || domeRight != right) {
            SkyDome.paint(
                domePx,
                sky.sky[0].toArgb(),
                sky.sky[1].toArgb(),
                sky.sky[2].toArgb(),
                sky.glow.toArgb(),
                sky.halo.toArgb(),
                sky.dark,
                right,
            )
            PlatformImages.update(image, domePx, SkyDome.W, SkyDome.H)
            domeKey = sky
            domeRight = right
        }
        drawImage(
            image,
            IntOffset.Zero,
            IntSize(SkyDome.W, SkyDome.H),
            IntOffset.Zero,
            IntSize(ceil(size.width).toInt(), ceil(horizon).toInt() + 1),
            filterQuality = FilterQuality.Low,
        )
    }

    /**
     * The Milky Way on a dark clear night: its band ([MilkyWay]) turned across the upper sky, added
     * faintly over it, and the dust of faint stars thick along it, all of it less under a bright
     * moon, gone in cloud or mist; the dust is kept off the low sky the city's light washes out.
     */
    private fun DrawScope.milkyWay(w: Float, horizon: Float, starAlpha: Float) {
        val k =
            starAlpha *
                starAlpha *
                (1f - cloudiness).coerceIn(0f, 1f) *
                (1f - mist).coerceIn(0f, 1f) *
                (1f - .55f * moonLit)
        if (k < .02f) return
        val d = density
        val s = MW_SPAN * w / MilkyWay.W
        withTransform({
            translate(w * MW_X, min(w * MW_Y, horizon * .38f))
            rotate(MW_TURN, Offset.Zero)
            scale(s, s * 1.1f, Offset.Zero)
        }) {
            drawImage(
                SceneSprites.milkyWay,
                Offset(-MilkyWay.W / 2f, -MilkyWay.H / 2f),
                alpha = .5f * k,
                blendMode = BlendMode.Plus,
            )
        }
        clipRect(0f, 0f, w, horizon * .8f) {
            scale(w, w, Offset.Zero) {
                for (g in dust.indices) drawPoints(
                    dust[g],
                    PointMode.Points,
                    DUST_INK[g],
                    strokeWidth = 1.15f * d / w,
                    cap = StrokeCap.Round,
                    alpha = DUST_ALPHA[g] * k,
                )
            }
        }
    }

    /** Each ghost's own cast: the lens's coatings colour them a little apart from the sun. */
    private val GHOST_HUES =
        arrayOf(
            Color(0xFFFFE2A8),
            Color(0xFFA8E0FF),
            Color(0xFFFFD090),
            Color(0xFFB8FFD8),
            Color(0xFFFFC0E0),
            Color(0xFFC8D8FF),
        )

    // ---- the meteor and the far road: tints for their baked sprites ([RoadSkySprites]), made once

    private val meteorCore = ColorFilter.tint(Color.White, BlendMode.SrcIn)
    private val meteorCoreFire = ColorFilter.tint(Color(0xFFFFEBC2), BlendMode.SrcIn)
    private val meteorHalo = ColorFilter.tint(Color(0xFFA9C2FF), BlendMode.SrcIn)
    private val meteorHaloFire = ColorFilter.tint(Color(0xFF96F0BE), BlendMode.SrcIn)
    private val meteorHead = ColorFilter.tint(Color(0xFFEAF0FF), BlendMode.SrcIn)
    private val meteorHeadFire = ColorFilter.tint(Color(0xFFFFD39A), BlendMode.SrcIn)
    private val sparkColor = Color(0xFFFFF6DC)
    private val sparkTint = ColorFilter.tint(sparkColor, BlendMode.SrcIn)
    private val headlightTint = ColorFilter.tint(SceneInk.Headlight, BlendMode.SrcIn)
    private val taillightTint = ColorFilter.tint(SceneInk.Taillight, BlendMode.SrcIn)

    /** The haze of distance over a vehicle by day: the far row's colour, a little, per sky. */
    private var hazeKey: Any? = null
    private var hazeTint: ColorFilter? = null

    /**
     * A shooting star as a meteor: a bright head with a soft bloom that is brightest in the middle
     * of its run, a streak behind it (a hot core in a cooler, wider halo, slightly curved, baked in
     * [RoadSkySprites.streak] and drawn turned along the way it flies), a few sparkles shed behind
     * the head that drift off the line and fade, and, after the head has gone out, its train
     * lingering a moment where it went. Each pass has its own length, brightness and slope; rarely
     * one is a fireball, longer and brighter, with green and orange in it ([Ambient.fireball]).
     */
    private fun DrawScope.shootingStar(p: Pass, w: Float, top: Float, band: Float, alpha: Float) {
        val d = density
        val t = p.t
        val k = p.pass
        val hx = p.x * w
        val hy = top + p.y * band
        // the head's way across the sky, in px for a whole pass
        val vx = p.dir * Ambient.METEOR_RUN * w
        val vy = Ambient.meteorSlope(seed, k) * band
        val angle = (atan2(vy, vx) * 180f / PI).toFloat()
        val size = Ambient.meteorSize(seed, k)
        val fire = Ambient.fireball(seed, k)
        val bright = Ambient.meteorBright(seed, k) * alpha
        val train = Ambient.meteorTrain(t) * bright
        val head = Ambient.meteorHead(t) * bright
        val len = (40f + 36f * size) * d * Ambient.meteorReach(t)
        val streak = SceneSprites.streak
        val sp = streak.sprite
        val g = RoadSkySprites.GLOW.toFloat()
        val glow = SceneSprites.glow
        if (train > 0f) {
            // along the streak, px per sprite px; across, mirrored for a meteor flying left so the
            // curve still hangs down
            val sx = len / (sp.headX - 10f)
            val sy =
                d * (.55f + .45f * size) / RoadSkySprites.PX_PER_DP * (if (p.dir < 0f) -1f else 1f)
            withTransform({
                translate(hx, hy)
                rotate(angle, Offset.Zero)
                scale(sx, sy, Offset.Zero)
            }) {
                val at = Offset(-sp.headX, -sp.h * .42f)
                drawImage(
                    streak.halo,
                    at,
                    train * .95f,
                    colorFilter = if (fire) meteorHaloFire else meteorHalo,
                )
                drawImage(
                    streak.core,
                    at,
                    train,
                    colorFilter = if (fire) meteorCoreFire else meteorCore,
                )
            }
        }
        if (head > 0f) {
            val r = (5f + 3.5f * size) * d
            val tint = if (fire) meteorHeadFire else meteorHead
            withTransform({
                translate(hx, hy)
                scale(2f * r / g, 2f * r / g, Offset.Zero)
            }) {
                drawImage(glow, Offset(-g / 2f, -g / 2f), head * .85f, colorFilter = tint)
            }
            withTransform({
                translate(hx, hy)
                scale(.9f * r / g, .9f * r / g, Offset.Zero)
            }) {
                drawImage(glow, Offset(-g / 2f, -g / 2f), head, colorFilter = tint)
            }
            drawCircle(SceneInk.Strobe, (1f + .45f * size) * d, Offset(hx, hy), head)
        }
        // the sparkles: each shed where the head was when it was born, drifting off the line
        val tc = min(t, Ambient.METEOR_HEAD_END)
        val vl = sqrt(vx * vx + vy * vy)
        val nx = -vy / vl
        val ny = vx / vl
        for (i in 0 until Ambient.SPARKS) {
            val b = Ambient.sparkBirth(seed, k, i)
            val a = Ambient.sparkAlpha(b, t) * bright
            if (a <= 0f) continue
            val age = (t - b) / Ambient.SPARK_LIFE
            val off = Ambient.sparkSide(seed, k, i) * (1.5f + 5f * age) * d
            val sx = hx - vx * (tc - b) + nx * off
            val sy = hy - vy * (tc - b) + ny * off + age * age * 7f * d
            withTransform({
                translate(sx, sy)
                scale(4.5f * d / g, 4.5f * d / g, Offset.Zero)
            }) {
                drawImage(glow, Offset(-g / 2f, -g / 2f), a * .55f, colorFilter = sparkTint)
            }
            drawCircle(sparkColor, (.3f + .4f * (1f - age)) * d, Offset(sx, sy), a)
        }
    }

    /**
     * Clouds: painted sprites ([CloudSprites]), each drawn as three tinted layers (its body in the
     * shade of the hour, the part the light finds, and its brightest rim), so a cloud costs three
     * image draws. They hang in lanes of the sky above [ceiling] (the roof of the tallest building
     * in view, with the "add a floor" ghost on it), so none can sit behind the hint; the near and
     * big ones drift faster (parallax) and the street's scrolling nudges them; each breathes a very
     * little. The further a cloud (the lower its lane), the smaller, bluer and fainter. The light
     * comes from the sun's side: [mirror] draws the sprites flipped for a sun on the right.
     */
    private fun DrawScope.clouds(
        sky: SkyColors,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        mirror: Boolean,
        shift: Float,
        seconds: Float,
    ) {
        val d = density
        val night = sky.dark > .8f
        val count = if (night) 3 else if (sky.dark > .3f) 4 else Ambient.CLOUDS
        val strength = if (sky.phase == SkyPhase.DAY) 1f else if (night) .8f else .94f
        val room = CloudLayout.room(top, horizon, ceiling, d)
        shadeN = 0
        if (room <= 0f) return
        val pool = SceneSprites.clouds
        for (i in 0 until count) {
            val c = clouds[i]
            val images = pool[CloudSprites.of(c.kind)[c.variant]]
            val sp = images.sprite
            place(i, w, top, horizon, ceiling, shift, seconds, strength, room, d)
            val s = placeS
            val x = placeX
            val base = placeBase
            val a = placeA
            shadeX[i] = x
            shadeW[i] = sp.w * s
            shadeA[i] = a * c.depth
            shadeN = i + 1
            val sx = if (mirror) -s else s
            withTransform({
                translate(x, base)
                scale(sx, s, Offset.Zero)
            }) {
                val at = Offset(-sp.w / 2f, -sp.h * .86f)
                drawImage(images.shade, at, a, colorFilter = tintShade[i])
                drawImage(images.mid, at, a, colorFilter = tintMid[i])
                drawImage(images.lit, at, a, colorFilter = tintLit[i])
                if (underAlpha > .01f)
                    drawImage(images.under, at, a * underAlpha, colorFilter = tintUnder[i])
            }
        }
    }

    /** Where [place] put cloud i: its middle, its foot, its scale and its alpha. */
    private var placeX = 0f
    private var placeBase = 0f
    private var placeS = 0f
    private var placeA = 0f

    /** Places cloud [i] as [clouds] draws it, into [placeX], [placeBase], [placeS], [placeA]. */
    private fun place(
        i: Int,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        shift: Float,
        seconds: Float,
        strength: Float,
        room: Float,
        d: Float,
    ) {
        val c = clouds[i]
        placeS = d * c.scale / CloudSprites.PX_PER_DP
        placeX = Ambient.cloudAt(c, seconds) * w - shift * .03f * c.depth * d
        // turned: slid with the sky, coming round again past the edges as the clouds' own drift does
        if (yaw != 0f) placeX = (((placeX + yaw + .25f * w) % (1.5f * w)) + 1.5f * w) % (1.5f * w) - .25f * w
        placeBase = CloudLayout.base(c.y, top, horizon, ceiling, d)
        // breathing: the sky never quite stands still, a few percent over half a minute
        val breathe = 1f + .04f * sin(seconds * .21f + i * 2.1f)
        placeA = (strength * breathe * room * (1f - .28f * c.haze)).coerceIn(0f, 1f)
    }

    /** The silver lining's layer: added, its alpha raised (the edge field peaks at a quarter). */
    private val liningPaint =
        Paint().apply {
            blendMode = BlendMode.Plus
            colorFilter =
                ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            1f,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            1f,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            1f,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            3.2f,
                            0f,
                        )
                    )
                )
        }
    private val whiteTint = ColorFilter.tint(Color.White, BlendMode.SrcIn)
    private val sunLiningTint = ColorFilter.tint(Color(0xFFFFF3D6), BlendMode.SrcIn)
    private val moonLiningTint = ColorFilter.tint(Color(0xFFDDE6FF), BlendMode.SrcIn)
    private val coronaTint = ColorFilter.tint(Color(0xFFE9E2FF), BlendMode.SrcIn)

    /**
     * The light of the sun or the moon round the clouds passing over it, drawn over the clouds: the
     * cloud's thin edges near the light glow (its silver lining: the light scatters through the
     * thin cloud and bends round it, while the thick middle stays dark); with the sun partly hidden
     * its rays fan out through the gaps; with the moon behind thin cloud its light spreads into a
     * soft corona.
     */
    private fun DrawScope.occultation(
        sky: SkyColors,
        orb: Offset,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        mirror: Boolean,
        shift: Float,
        seconds: Float,
    ) {
        val night = sky.phase == SkyPhase.NIGHT
        val presence = if (night) moonLook.body else SunLook.up(sky.dark)
        if (presence < .02f) return
        val d = density
        val reach = (if (night) 70f else 110f) * d
        val dark = sky.dark > .8f
        val count = if (dark) 3 else if (sky.dark > .3f) 4 else Ambient.CLOUDS
        val strength = if (sky.phase == SkyPhase.DAY) 1f else if (dark) .8f else .94f
        val room = CloudLayout.room(top, horizon, ceiling, d)
        if (room <= 0f) return
        val pool = SceneSprites.clouds
        // which clouds come near the light
        var near = false
        for (i in 0 until count) {
            val sp = pool[CloudSprites.of(clouds[i].kind)[clouds[i].variant]].sprite
            place(i, w, top, horizon, ceiling, shift, seconds, strength, room, d)
            val hw = sp.w * placeS / 2f + reach
            val cy = placeBase - sp.h * placeS * .36f
            if (abs(orb.x - placeX) < hw && abs(orb.y - cy) < sp.h * placeS / 2f + reach) {
                near = true
                break
            }
        }
        if (!near) return
        val tint = if (night) moonLiningTint else sunLiningTint
        liningPaint.alpha = (presence * (if (night) .9f else .75f)).coerceIn(0f, 1f)
        drawIntoCanvas {
            it.saveLayer(
                Rect(orb.x - reach, orb.y - reach, orb.x + reach, orb.y + reach),
                liningPaint,
            )
        }
        // the clouds' coverage A, then A taken away as much as it covers: A(1 - A), the edges
        for (pass in 0..1) {
            val blend = if (pass == 0) BlendMode.SrcOver else BlendMode.DstOut
            for (i in 0 until count) {
                val c = clouds[i]
                val images = pool[CloudSprites.of(c.kind)[c.variant]]
                val sp = images.sprite
                place(i, w, top, horizon, ceiling, shift, seconds, strength, room, d)
                val sx = if (mirror) -placeS else placeS
                withTransform({
                    translate(placeX, placeBase)
                    scale(sx, placeS, Offset.Zero)
                }) {
                    val at = Offset(-sp.w / 2f, -sp.h * .86f)
                    val f = if (pass == 0) tint else whiteTint
                    drawImage(images.shade, at, placeA, colorFilter = f, blendMode = blend)
                    drawImage(images.mid, at, placeA, colorFilter = f, blendMode = blend)
                    drawImage(images.lit, at, placeA, colorFilter = f, blendMode = blend)
                }
            }
        }
        // only near the light: brightest round it, gone by [reach]
        val n = LightSprites.POINT.toFloat()
        val k = 2f * reach / n
        withTransform({
            translate(orb.x, orb.y)
            scale(k, k, Offset.Zero)
        }) {
            drawImage(
                SceneSprites.light.point,
                Offset(-n / 2f, -n / 2f),
                blendMode = BlendMode.DstIn,
            )
        }
        drawIntoCanvas { it.restore() }
        // partly hidden: most of all half way
        val part = 4f * moonCover * (1f - moonCover)
        if (part < .02f) return
        if (night) {
            // the moon's corona in thin cloud: its light spread into a soft ring round it
            val m = SceneSprites.moon
            translate(orb.x, orb.y) {
                orbLayer(
                    m.halo,
                    m.sprite.halo,
                    d,
                    3.2f,
                    3.2f,
                    .35f * part * presence,
                    coronaTint,
                    BlendMode.Plus,
                )
            }
        } else {
            // the sun's rays through the gaps, fanning wide over the clouds
            val sun = SceneSprites.sun
            translate(orb.x, orb.y) {
                rotate(seconds * SunLook.RAY_TURN * .5f, Offset.Zero) {
                    orbLayer(
                        sun.rays,
                        sun.sprite.rays,
                        d,
                        2.2f,
                        2.2f,
                        .55f * part * presence,
                        sunLiningTint,
                        BlendMode.Plus,
                    )
                }
            }
        }
    }

    /** The layer a plane half inside a cloud is faded in. */
    private val planePaint = Paint()

    /** How much of the moon the clouds in front of it hide this frame, 0 to 1 ([cover]). */
    private var moonCover = 0f

    /**
     * How much of a disc at [orb] the clouds hide, 0 (clear) to 1: each cloud's own opacity where
     * the disc is (its sprite sampled at the middle and four points round the rim), times the alpha
     * it is drawn at, stacked as layers of cloud stack.
     */
    private fun cover(
        sky: SkyColors,
        orb: Offset,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        mirror: Boolean,
        shift: Float,
        seconds: Float,
        d: Float,
        radius: Float = -1f,
    ): Float {
        val night = sky.dark > .8f
        val count = if (night) 3 else if (sky.dark > .3f) 4 else Ambient.CLOUDS
        val strength = if (sky.phase == SkyPhase.DAY) 1f else if (night) .8f else .94f
        val room = CloudLayout.room(top, horizon, ceiling, d)
        if (room <= 0f) return 0f
        val pool = SceneSprites.clouds
        val r =
            if (radius > 0f) radius
            else
                (if (sky.phase == SkyPhase.NIGHT) SunMoonSprites.MOON_R else SunMoonSprites.SUN_R) *
                    d *
                    .7f
        var through = 1f
        for (i in 0 until count) {
            val c = clouds[i]
            val sp = pool[CloudSprites.of(c.kind)[c.variant]].sprite
            place(i, w, top, horizon, ceiling, shift, seconds, strength, room, d)
            val sx = if (mirror) -placeS else placeS
            var sum = 0f
            for (k in 0 until 5) {
                val ox = if (k == 1) r else if (k == 2) -r else 0f
                val oy = if (k == 3) r else if (k == 4) -r else 0f
                val px = ((orb.x + ox - placeX) / sx + sp.w / 2f).toInt()
                val py = ((orb.y + oy - placeBase) / placeS + sp.h * .86f).toInt()
                sum += cloudAlpha(sp, px, py)
            }
            // a cloud's body is thicker than its sprite's alpha looks against the dark sky
            val a = (sum / 5f * placeA * 1.25f).coerceIn(0f, 1f)
            through *= 1f - a
        }
        return 1f - through
    }

    /** The opacity of cloud sprite [sp] at pixel ([x], [y]): its three layers over each other. */
    private fun cloudAlpha(sp: CloudSprite, x: Int, y: Int): Float {
        if (x < 0 || y < 0 || x >= sp.w || y >= sp.h) return 0f
        val i = y * sp.w + x
        val a = (sp.shade[i] ushr 24) / 255f
        val b = (sp.mid[i] ushr 24) / 255f
        val c = (sp.lit[i] ushr 24) / 255f
        return 1f - (1f - a) * (1f - b) * (1f - c)
    }

    /**
     * An airship drifting across, bobbing and pitching a little: a painted sprite
     * ([AirSprites.blimp]) in the tints of the hour, as small as an aircraft far off, its
     * propellers turning; from dusk its panel lit with a light sweeping along it, a soft glow under
     * it and its navigation lights. It keeps to the open sky between the heading's band and the
     * roofs (the clouds' floor, so it never crosses the "add a floor" ghost); where a tall building
     * leaves no room for it, it stays away.
     */
    private fun DrawScope.blimp(
        p: Pass,
        sky: SkyColors,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        seconds: Float,
    ) {
        val d = density
        val k = d * BLIMP_SIZE
        // its lane: the upper fin clear of the heading's band, the cabin clear of the roofs
        val high = top + 13f * k
        // and clear of the "add a floor" ghost that stands on the tallest roof, not only the roof
        val low = CloudLayout.floor(horizon, ceiling, d) - 11f * k - GHOST_CLEAR * d
        if (low < high - 16f * d) return
        val y = if (low > high) high + (low - high) * p.y else high
        val bob = sin(seconds * .9f + p.pass) * 1.4f
        val pitch = sin(seconds * .6f + p.pass * .7f) * 1.1f * p.dir
        val dark = sky.dark
        val night = ((dark - .3f) / .7f).coerceIn(0f, 1f)
        val images = SceneSprites.blimp
        val sp = images.sprite
        val turn = seconds * 7.3f + p.pass
        withTransform({
            translate(p.x * w, y + bob * d)
            rotate(pitch, Offset.Zero)
            scale(k * p.dir, k, Offset.Zero)
        }) {
            if (night > 0f)
                translate(2f, 4.5f) {
                    scale(31f, 11f, Offset.Zero) {
                        drawCircle(airGlow!!, 1f, Offset.Zero, alpha = night * .8f)
                    }
                }
            // the far propeller, behind the cabin
            blimpProp(AirSprites.PROP_FAR_X, AirSprites.PROP_FAR_Y, turn + 1.3f, .6f)
            withTransform({ scale(1f / sp.ppd, 1f / sp.ppd, Offset.Zero) }) {
                val at = Offset(-sp.ox, -sp.oy)
                for (l in AirSprites.BLIMP_ORDER) {
                    val a = blimpAlpha[l]
                    if (a > 0f) drawImage(images.layers[l], at, a, colorFilter = blimpTint[l])
                }
            }
            blimpProp(AirSprites.PROP_NEAR_X, AirSprites.PROP_NEAR_Y, turn, 1f)
            if (night > 0f) {
                // the light that sweeps along the lit panel
                val span = AirSprites.BAND_R - AirSprites.BAND_L + 14f
                val sx =
                    AirSprites.BAND_L - 7f + ((seconds * 4.2f + p.pass * 7f) % span + span) % span
                clipPath(bandPath) {
                    translate(sx, (AirSprites.BAND_T + AirSprites.BAND_B) / 2f) {
                        scale(5.5f, 3f, Offset.Zero) {
                            drawCircle(sheen, 1f, Offset.Zero, alpha = .55f * night)
                        }
                    }
                }
                // the navigation light on the side seen (green on the ship's right, which faces
                // the street when it flies to the right; red on its left), white at the tail, the
                // strobe under the cabin
                val nav = if (p.dir > 0f) SceneInk.NavGreen else SceneInk.Warning
                val navAt = Offset(AirSprites.NAV_X, AirSprites.NAV_Y)
                drawCircle(nav, 2.6f, navAt, alpha = .22f * night)
                drawCircle(nav, .8f, navAt, alpha = night)
                val stern = Offset(AirSprites.STERN_X, AirSprites.STERN_Y)
                drawCircle(Color.White, 2f, stern, alpha = .16f * night)
                drawCircle(Color.White, .65f, stern, alpha = night)
                val strobe = Ambient.warning(11, seconds * 1.3f)
                if (strobe > 0f)
                    drawCircle(
                        SceneInk.Strobe,
                        1.1f,
                        Offset(AirSprites.STROBE_X, AirSprites.STROBE_Y),
                        alpha = strobe * night,
                    )
            }
        }
    }

    /**
     * One of the blimp's propellers, seen edge on from the side: the faint blur of its disc and its
     * three blades turning through it ([turn] radians), drawn [alpha] strong.
     */
    private fun DrawScope.blimpProp(x: Float, y: Float, turn: Float, alpha: Float) {
        val r = AirSprites.PROP_R
        drawOval(blimpProp, Offset(x - .32f, y - r), Size(.64f, 2f * r), alpha = .28f * alpha)
        for (b in 0 until 3) {
            val c = cos(turn + b * 2.0944f)
            drawLine(
                blimpProp,
                Offset(x, y),
                Offset(x, y + c * r),
                .36f,
                cap = StrokeCap.Round,
                alpha = .55f * alpha,
            )
        }
    }

    /**
     * The airplane: a painted airliner seen from below (a shaded fuselage with a gleam along it and
     * a row of windows, swept wings with engines under them, a fin), in the tints of the hour; by
     * day with a contrail behind it, two cores of exhaust merging into one soft torn trail; at
     * night a silhouette with its windows lit, red and green at the wingtips, a red beacon under
     * its belly and a white strobe at its tail.
     */
    private fun DrawScope.plane(
        p: Pass,
        sky: SkyColors,
        w: Float,
        top: Float,
        band: Float,
        seconds: Float,
        tone: Float,
    ) {
        val d = density
        val x = p.x * w
        val y = top + p.y * band
        val images = SceneSprites.plane
        val sp = images.sprite
        withTransform({
            translate(x, y)
            // drawn a little smaller than its sprite, so it reads as further away, high up
            scale(d * p.dir * PLANE_SIZE, d * PLANE_SIZE, Offset.Zero)
        }) {
            if (tone > 1.3f) {
                val trail = SceneSprites.contrail
                val ts = trail.sprite
                withTransform({
                    translate(AirSprites.TRAIL_X, AirSprites.TRAIL_Y)
                    scale(1f / ts.ppd, 1f / ts.ppd, Offset.Zero)
                }) {
                    val at = Offset(-ts.ox, -ts.oy)
                    for (l in 0 until AirSprites.TRAIL_LAYERS) drawImage(
                        trail.layers[l],
                        at,
                        trailAlpha[l],
                        colorFilter = trailTint[l],
                    )
                }
            }
            withTransform({ scale(1f / sp.ppd, 1f / sp.ppd, Offset.Zero) }) {
                val at = Offset(-sp.ox, -sp.oy)
                for (l in 0 until AirSprites.LAYERS) {
                    val a = planeAlpha[l]
                    if (a > 0f) drawImage(images.layers[l], at, a, colorFilter = planeTint[l])
                }
            }
            val dark = sky.dark
            if (dark > .3f) {
                val k = (dark - .3f) / .7f
                // red at the near wingtip, green where the far one peeks over the back, each
                // with a little glow round it
                val near = Offset(AirSprites.TIP_NEAR_X, AirSprites.TIP_NEAR_Y)
                val far = Offset(AirSprites.TIP_FAR_X, AirSprites.TIP_FAR_Y)
                drawCircle(SceneInk.Warning, 2f, near, alpha = .3f * k)
                drawCircle(SceneInk.Warning, .55f, near, alpha = k)
                drawCircle(SceneInk.NavGreen, 1.6f, far, alpha = .22f * k)
                drawCircle(SceneInk.NavGreen, .45f, far, alpha = .9f * k)
                // the beacon under the belly, a slow red pulse with a faint glow
                val beacon = Ambient.warning(3, seconds * .8f)
                val belly = Offset(AirSprites.BEACON_X, AirSprites.BEACON_Y)
                drawCircle(SceneInk.Warning, 2.4f, belly, alpha = (.06f + .16f * beacon) * k)
                drawCircle(SceneInk.Warning, .45f, belly, alpha = (.3f + .6f * beacon) * k)
                // and the white strobe at the tail
                val strobe = Ambient.warning(7, seconds * 1.4f)
                if (strobe > 0f) {
                    val tail = Offset(AirSprites.TAIL_X, AirSprites.TAIL_Y)
                    drawCircle(SceneInk.Strobe, 3.4f, tail, alpha = .2f * strobe * k)
                    drawCircle(SceneInk.Strobe, .65f, tail, alpha = strobe)
                }
            }
        }
    }

    /**
     * A flock in a V on its [Flight]: in at an edge at mid-sky, near, banking in a wide arc until
     * it flies away towards the far city, shrinking, sinking towards the vanishing height and
     * thinning into the air until it is gone (or the same the other way round, out of the
     * distance). Each bird keeps its place in the V, laid along the heading, so the near arm and
     * the followers are the bigger birds and the V turns with the flock; each on its own beat
     * (smaller seen end-on), rising and falling on its own path, one now and then gliding. A bird
     * with a body, a head and a fanned tail and wings that bend at the wrist. Dark against the
     * day's sky, warm-rimmed at dusk, the far ones the colour of the far city's air.
     */
    private fun DrawScope.birds(
        p: Pass,
        sky: SkyColors,
        w: Float,
        top: Float,
        horizon: Float,
        ceiling: Float,
        seconds: Float,
    ) {
        val d = density
        val variant = Flight.variant(seed, p.pass)
        Flight.flock(p.t, variant, flock)
        if (flock.fade <= 0f) return
        // its height: in at a share of the sky's band, sinking as it recedes towards the vanishing
        // height, which stays over the far city's tops and clear of the "add a floor" ghost
        val band = (horizon - top).coerceAtLeast(40f * d)
        val vanish = minOf(horizon - 44f * d, CloudLayout.floor(horizon, ceiling, d) - 4f * d)
        if (vanish <= top + 24f * d) return
        val start = minOf(top + Ambient.birdsHeight(seed, p.pass) * band, vanish - 40f * d)
        val endOn = Flight.endOn(flock)
        val headX = if (flock.ux < 0f) -1f else 1f
        val airInk = lerp(sky.far[0], sky.sky[2], .5f)
        for (i in 0 until Ambient.FLOCK) {
            Flight.bird(flock, i, bird)
            if (bird.fade <= .01f) continue
            val near = bird.near
            val size = Ambient.birdSize(i) * minOf(near * 1.45f, 1.6f)
            val fade = Ambient.birdFade(i)
            val x = bird.x * w
            val y =
                vanish - (vanish - start) * near +
                    (bird.dy + Ambient.birdBob(i, seconds)) * near * d
            val flap = Ambient.flap(i, seconds) * (1f - .4f * endOn)
            val n = BirdShape.build(0f, 0f, flap, size, headX * (1f - endOn), birdCmd)
            path.rewind()
            var k = 0
            while (k < n) {
                when (birdCmd[k]) {
                    BirdShape.MOVE -> {
                        path.moveTo(birdCmd[k + 1], birdCmd[k + 2])
                        k += 3
                    }
                    BirdShape.LINE -> {
                        path.lineTo(birdCmd[k + 1], birdCmd[k + 2])
                        k += 3
                    }
                    BirdShape.CUBIC -> {
                        path.cubicTo(
                            birdCmd[k + 1],
                            birdCmd[k + 2],
                            birdCmd[k + 3],
                            birdCmd[k + 4],
                            birdCmd[k + 5],
                            birdCmd[k + 6],
                        )
                        k += 7
                    }
                    else -> {
                        path.close()
                        k += 1
                    }
                }
            }
            // aerial perspective: the further, the more it takes the colour of the air
            val haze = ((1f - near) * .7f + mist * .5f).coerceIn(0f, 1f)
            val ink = lerp(birdInk[Ambient.birdTier(i)], airInk, haze)
            val alpha = (.5f + .5f * fade) * bird.fade
            withTransform({
                translate(x, y)
                scale(d, d, Offset.Zero)
            }) {
                if (birdRimAlpha > 0f)
                    translate(-.35f * size, -.4f * size) {
                        drawPath(
                            path,
                            birdRim,
                            alpha = birdRimAlpha * .8f * fade * bird.fade * near,
                        )
                    }
                drawPath(path, ink, alpha = alpha)
            }
        }
    }

    /**
     * How clear the air before row [r] is, 0 to 1: thicker on an overcast or a misty day and at
     * dusk and dawn, the furthest row most; the whole row is drawn that much fainter.
     */
    private fun rowAir(r: Int, sky: SkyColors): Float {
        val dusk = Px.smooth(.15f, .5f, sky.dark) * Px.smooth(.95f, .65f, sky.dark)
        val thick = (cloudiness * .2f + mist * .45f + dusk * .15f) * (1f - .3f * r)
        return (1f - thick).coerceIn(.3f, 1f)
    }

    /**
     * Far row [r] slid along by [slide] dp: its baked strip ([SkylineSprites], soft with distance,
     * its roofline broken by tanks, spires, a crane, the antennas) repeated along the street over
     * the solid feet under it, each building's baked windows drawn by colour with the share of them
     * that is on now ([Ambient.farWindowLit]), and the aircraft-warning lights, still crisp points,
     * on the tallest.
     */
    private fun DrawScope.row(r: Int, sky: SkyColors, base: Float, slide: Float, seconds: Float) {
        val d = density
        val w = size.width
        val images = SceneSprites.skyline[r]
        val body = images.body
        val win = images.windows
        val buildings = rows[r]
        val lights = win != null && sky.dark > .3f
        val share = if (sky.dark > .8f) .42f else .25f
        val air = rowAir(r, sky)
        // the seam between the baked band and the solid feet sits on a whole pixel, so neither
        // shows through at it
        val seam = (base - body.footDp * d).roundToInt().toFloat()
        drawRect(rowBrush[r]!!, Offset(0f, seam), Size(w, base - seam + 1f), alpha = air)
        val s = d / body.ppd
        val span = Ambient.SKYLINE_SPAN * d
        var x = Ambient.wrap(0f, slide) * d
        while (x > 0f) x -= span
        while (x < w) {
            withTransform({
                translate(x, seam - body.h * s)
                scale(s, s, Offset.Zero)
            }) {
                drawImage(body.image, Offset.Zero, alpha = air, colorFilter = farTint[r])
            }
            if (lights) {
                val ws = d / win!!.ppd
                val bright = (.5f + .3f * sky.dark) * air
                withTransform({
                    translate(x, base - win.topDp * d)
                    scale(ws, ws, Offset.Zero)
                }) {
                    for (i in buildings.indices) {
                        val b = buildings[i]
                        val px = x + b.x * d
                        if (px + b.w * d < 0f || px > w) continue
                        val list = windows[r][i]
                        winCount.fill(0)
                        winLit.fill(0)
                        for (k in 0 until list.size / 3) {
                            val c = SkylineSprites.channel(list[k * 3 + 2])
                            winCount[c]++
                            if (Ambient.farWindowLit(r * 1000 + i, k, seconds, share)) winLit[c]++
                        }
                        val x0 = (b.x * win.ppd).roundToInt()
                        val x1 = (min(b.x + b.w, Ambient.SKYLINE_SPAN) * win.ppd).roundToInt()
                        if (x1 <= x0) continue
                        for (c in 0 until 3) {
                            if (winLit[c] == 0) continue
                            drawImage(
                                win.image,
                                IntOffset(x0, 0),
                                IntSize(x1 - x0, win.h),
                                IntOffset(x0, 0),
                                IntSize(x1 - x0, win.h),
                                alpha = bright * winLit[c] / winCount[c],
                                colorFilter = windowTint[c],
                            )
                        }
                    }
                }
            }
            if (sky.dark > .3f)
                for (i in buildings.indices) {
                    if (!warned[r][i]) continue
                    val b = buildings[i]
                    val px = x + b.x * d
                    if (px + b.w * d < 0f || px > w) continue
                    val on = if (seconds > 0f) Ambient.warning(r * 100 + i, seconds) else .7f
                    if (on <= 0f) continue
                    val top = base - b.h * d
                    val at = Offset(px + b.w * d * .6f, top - antennas[r][i] * d - 1.5f * d)
                    drawCircle(SceneInk.Warning, 4f * d, at, .18f * on)
                    drawCircle(SceneInk.Warning, 1.4f * d, at, on)
                }
            x += span
        }
    }

    /**
     * A vehicle on the far road (one of the painted pool, [RoadSkySprites.VEHICLES], a different
     * one each pass, mirrored for the other way; now and then a second follows it): by day its own
     * colour under a little of the horizon's haze; at dusk and night a dark body, a headlight (a
     * hot core in a soft bloom) throwing a beam forward and down the road and a pool of light on
     * it, a red tail-light with its glow and a faint red on the road. It rides with a very small
     * bob.
     */
    private fun DrawScope.car(p: Pass, sky: SkyColors, w: Float, horizon: Float, seconds: Float) {
        val d = density
        if (hazeKey !== sky) {
            hazeTint = ColorFilter.tint(sky.far[2].copy(alpha = .35f), BlendMode.SrcAtop)
            hazeKey = sky
        }
        val pool = SceneSprites.vehicles
        val dark = sky.dark
        val lights = Px.smooth(.3f, .8f, dark)
        // on the boulevard's lane for its way, as small as that far off
        val k = midCity.k
        val roadY =
            horizon +
                (if (p.dir > 0f) MidCitySprites.LANE_RIGHT else MidCitySprites.LANE_LEFT) * d * k
        val follow = Ambient.carFollow(seed, p.pass)
        val s = d / RoadSkySprites.CAR_PX_PER_DP * CAR_SIZE * k
        val g = RoadSkySprites.GLOW.toFloat()
        val glow = SceneSprites.glow
        for (n in 0 until 2) {
            if (n == 1 && follow <= 0f) break
            val v = pool[Ambient.vehicle(seed, p.pass, n, pool.size)]
            val sp = v.sprite
            val x = (p.x - p.dir * follow * n) * w
            val bob =
                if (seconds > 0f) sin(seconds * 9f + n * 2.3f + p.pass) * .14f * d * CAR_SIZE * k
                else 0f
            withTransform({
                translate(x, roadY + bob)
                scale(s * p.dir, s, Offset.Zero)
            }) {
                // in the sprite's pixels, the origin under the vehicle's middle on the road
                val ax = -sp.w / 2f
                val ay = -sp.roadY
                val fx = ax + sp.frontX
                val bx = ax + sp.backX
                val ly = ay + sp.lightY
                if (lights > 0f) {
                    // the beam thrown forward and a little down the road, and its pool on it
                    val bs = 124f / RoadSkySprites.BEAM_W
                    withTransform({
                        translate(fx - 1f, ly + 1f)
                        rotate(5f, Offset.Zero)
                        scale(bs, bs * .75f, Offset.Zero)
                    }) {
                        drawImage(
                            SceneSprites.beam,
                            Offset(-6f, -RoadSkySprites.BEAM_H / 2f),
                            .62f * lights,
                            colorFilter = headlightTint,
                        )
                    }
                    withTransform({
                        translate(fx + 24f, 1.5f)
                        scale(64f / g, 9f / g, Offset.Zero)
                    }) {
                        drawImage(
                            glow,
                            Offset(-g / 2f, -g / 2f),
                            .38f * lights,
                            colorFilter = headlightTint,
                        )
                    }
                    // the tail-light's red on the road
                    withTransform({
                        translate(bx + 2f, 1.5f)
                        scale(40f / g, 7f / g, Offset.Zero)
                    }) {
                        drawImage(
                            glow,
                            Offset(-g / 2f, -g / 2f),
                            .3f * lights,
                            colorFilter = taillightTint,
                        )
                    }
                }
                val at = Offset(ax, ay)
                if (dark < 1f) drawImage(v.day, at, colorFilter = hazeTint)
                if (dark > 0f) drawImage(v.night, at, dark)
                if (lights > 0f) {
                    // the headlight: a wide soft bloom and a hot core; the tail-light small and red
                    withTransform({
                        translate(fx, ly)
                        scale(42f / g, 42f / g, Offset.Zero)
                    }) {
                        drawImage(
                            glow,
                            Offset(-g / 2f, -g / 2f),
                            .8f * lights,
                            colorFilter = headlightTint,
                        )
                    }
                    withTransform({
                        translate(fx, ly)
                        scale(14f / g, 14f / g, Offset.Zero)
                    }) {
                        drawImage(
                            glow,
                            Offset(-g / 2f, -g / 2f),
                            lights,
                            colorFilter = headlightTint,
                        )
                    }
                    drawCircle(SceneInk.Strobe, 1.5f, Offset(fx, ly), lights)
                    withTransform({
                        translate(bx, ly)
                        scale(22f / g, 22f / g, Offset.Zero)
                    }) {
                        drawImage(
                            glow,
                            Offset(-g / 2f, -g / 2f),
                            .7f * lights,
                            colorFilter = taillightTint,
                        )
                    }
                    drawCircle(SceneInk.Taillight, 1.4f, Offset(bx, ly), lights)
                }
            }
        }
    }
}

/** How big the passing car is drawn against its sprite, on the boulevard at full size. */
private const val CAR_SIZE = .5f

/** How big the airliner is drawn against its sprite: a little smaller, so it reads as far off. */
/** How far round a plane's middle the clouds are sampled to hide it, in dp. */
private const val PLANE_HIDE_R = 7f

private const val PLANE_SIZE = .55f

/** The blimp is drawn this much smaller than its sprite's dp: an airship far off. */
private const val BLIMP_SIZE = .82f

/** How far above the roofs the blimp keeps, for the "add a floor" ghost standing on them, in dp. */
private const val GHOST_CLEAR = 52f

/** The stars' colours: blue-white, white, pale yellow, gold, orange. */
private val STAR_INK =
    arrayOf(
        Color(0xFFBCCBFF),
        Color(0xFFE6ECFF),
        Color(0xFFFFF3DC),
        Color(0xFFFFE0B0),
        Color(0xFFFFC49A),
    )

/** Which colour a star is, from a hash 0 to 1: most cool or white, a few warm. */
private fun starKindOf(h: Float): Int =
    when {
        h < .2f -> 0
        h < .64f -> 1
        h < .85f -> 2
        h < .95f -> 3
        else -> 4
    }

/** The faint dust of stars: cool, white and warm, and how bright each group is drawn. */
private val DUST_INK = arrayOf(Color(0xFFC4D2FF), Color(0xFFF2F4FF), Color(0xFFFFE9CC))
private val DUST_ALPHA = floatArrayOf(.42f, .5f, .34f)
private const val DUST = 180

/**
 * The Milky Way's place: its middle (shares of the view's width across and down), its length
 * (shares of the width) and its slope (degrees: rising to the right).
 */
private const val MW_X = .56f
private const val MW_Y = .42f
private const val MW_SPAN = 1.7f
private const val MW_TURN = -34f

/** The bellies of clouds lit from below: by the sun under the horizon, by the city at night. */
private val UNDER_DUSK = Color(0xFFFF6E4E)
private val UNDER_CITY = Color(0xFF5E4A4E)

/**
 * The faint stars' places, in widths of the view: more than half scattered along the Milky Way's
 * band (normally about its line), the rest anywhere in the sky.
 */
private fun dust(seed: Int): Array<List<Offset>> {
    val groups = Array(3) { ArrayList<Offset>() }
    val turn = MW_TURN * PI.toFloat() / 180f
    val ax = cos(turn)
    val ay = sin(turn)
    for (i in 0 until DUST) {
        val h = Ambient.hash(seed, i, 41)
        val p =
            if (h < .58f) {
                val t = (Ambient.hash(seed, i, 42) - .5f) * MW_SPAN * .8f
                // a normal spread across the band (Box-Muller)
                val u1 = Ambient.hash(seed, i, 43).coerceAtLeast(1e-4f)
                val u2 = Ambient.hash(seed, i, 44)
                val n = sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2) * .07f
                Offset(MW_X + ax * t - ay * n, MW_Y + ay * t + ax * n)
            } else Offset(Ambient.hash(seed, i, 45), Ambient.hash(seed, i, 46) * 1.3f)
        val c = Ambient.hash(seed, i, 47)
        groups[if (c < .3f) 0 else if (c < .82f) 1 else 2].add(p)
    }
    return Array(3) { groups[it] }
}
