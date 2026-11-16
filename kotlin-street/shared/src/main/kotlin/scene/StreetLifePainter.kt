package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.CarPass
import com.lifemax.rpg.feature.habits.domain.CycleRig
import com.lifemax.rpg.feature.habits.domain.DogRig
import com.lifemax.rpg.feature.habits.domain.Furniture
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.Gesture
import com.lifemax.rpg.feature.habits.domain.KeyLight
import com.lifemax.rpg.feature.habits.domain.Layers
import com.lifemax.rpg.feature.habits.domain.NearLight
import com.lifemax.rpg.feature.habits.domain.Part
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.domain.PeopleLod
import com.lifemax.rpg.feature.habits.domain.Perspective
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.Halt
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.domain.StreetLayers
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.Stroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * The street's life on the canvas: the people on the pavement ([StreetLife.passers]), their shadows
 * (from the key light, in the painter's shadow layer, and from the street lamps at night), the near
 * car on the front road and, at night, its headlights' sweep over the facades. Everything drawn is
 * a baked image ([SceneSprites]) under a transform or a path; the per-frame state lives in fields
 * the schedule fills ([update]), so nothing is made while drawing. Street units, the pavement line
 * at y = 0, the kerb at [BuildingPainter.PAVEMENT].
 */
internal class StreetLifePainter {
    /** This frame's passers and cars (far lane first), filled by [update]. */
    val passers = Array(StreetLife.MOST + 2) { Passer() }
    var count = 0
    val cars = Array(StreetLife.CAR_MOST) { CarPass() }
    var carCount = 0

    /** The key light and where it stands, as [BuildingPainter] has them: set by the stage. */
    var light: KeyLight? = null
    var lightX = 0f
    var lightSpan = 1000f
    var lightFrom = -1f

    /** The painter whose lamp posts and trees throw the headlights' shadows up the walls. */
    var painter: BuildingPainter? = null

    private val fall = Matrix()
    private val unit = 1f / StreetSprites.PPU
    private val carUnit = 1f / CarArt.PPU

    /**
     * The cars' paints: their grey bodies multiplied by each paint, by day and by night (made
     * once).
     */
    private val dayPaints = Array(StreetLife.CAR_PAINTS) { tintBy(CarArt.PAINTS[it]) }
    private val nightPaints =
        Array(StreetLife.CAR_PAINTS) { tintBy(CarArt.nightPaint(CarArt.PAINTS[it])) }

    /** A wheel, a rack or the shadow side of things at night: towards the dark. */
    private val carNight = ColorFilter.tint(Color(0xFF06080D), BlendMode.SrcIn)

    /** The shade a walker throws under a street lamp, and the warmth the lamp lends them. */
    private val lampShadeFilter = ColorFilter.tint(Color(0xFF0A0806), BlendMode.SrcIn)

    /** A street lamp's light as it lands on a walker, and the layer it is added to them with. */
    private val lampLitFilter = ColorFilter.tint(Color(0xFFFFC994), BlendMode.SrcIn)

    /** The colour a building's shade multiplies a walker by: the cool of the sky's fill light. */
    private val figureShade = ColorFilter.tint(Color(0xFF6E7A94), BlendMode.SrcIn)
    private val lampPaint = Paint().apply { blendMode = BlendMode.Plus }

    /** How the figure's images are laid down ([party] sets it for one call). */
    private var blend = BlendMode.SrcOver
    private val dayRimFilter = ColorFilter.tint(Color(0xFFF2F5FA), BlendMode.SrcIn)

    /**
     * The people are baked in their own colours, lit from above ([StreetRaster]). By day they are
     * shown as they are, the faintest cool grade taking the edge off the paint against the daylit
     * street.
     */
    private val dayFigure =
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    .97f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    .98f,
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
                )
            )
        )

    /**
     * At night: the same people as the eye sees them out of the lamps' pools: about two fifths as
     * bright, cooler, their colours muted by about two fifths but still there, never warm (the
     * lamps lend their own warmth over them, [lampsOn]). Each row takes the colour towards its
     * brightness (Rec. 709 weights) and scales it, the blue the least; the offsets are the night
     * air's faint blue.
     */
    private val nightFigure =
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    .26f,
                    .109f,
                    .011f,
                    0f,
                    3f,
                    .036f,
                    .372f,
                    .012f,
                    0f,
                    5f,
                    .043f,
                    .143f,
                    .315f,
                    0f,
                    10f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        )

    private val moonRimFilter = ColorFilter.tint(Color(0xFFC9D6FF), BlendMode.SrcIn)

    /** A phone's screen in the dark: its cool light, added over it ([phoneGlow]). */
    private val phoneGlowFilter = ColorFilter.tint(Color(0xFFBFD8FF), BlendMode.SrcIn)

    /** How much a phone in someone's hand glows as the walkers are drawn (at night only). */
    private var phoneGlow = 0f

    /** A foot's soft contact shadow: a unit disc fading out. */
    private val contact =
        Brush.radialGradient(
            listOf(Color(0x8C000000), Color(0x33000000), Color.Transparent),
            Offset.Zero,
            1f,
        )

    /**
     * The headlights' pool on the road and the wash on the facades: unit discs, drawn additively.
     */
    private val pool =
        Brush.radialGradient(
            listOf(Color(0xFFFFE6B4).copy(alpha = .6f), Color.Transparent),
            Offset.Zero,
            1f,
        )
    private val wash =
        Brush.radialGradient(
            listOf(
                Color(0xFFFFD9A0).copy(alpha = .55f),
                Color(0xFFFFD9A0).copy(alpha = .18f),
                Color.Transparent,
            ),
            Offset.Zero,
            1f,
        )
    private val tail =
        Brush.radialGradient(
            listOf(Color(0xFFFF4A30).copy(alpha = .55f), Color.Transparent),
            Offset.Zero,
            1f,
        )

    /** A bicycle's head lamp: a cool white unit disc, drawn additively. */
    private val bikeLamp =
        Brush.radialGradient(
            listOf(Color(0xFFF2F6FF), Color(0xFFDDE6FF).copy(alpha = .35f), Color.Transparent),
            Offset.Zero,
            1f,
        )

    /** The headlights' beam along the road: a unit cone from its origin at 0, opening to x = 1. */
    private val beam =
        Path().apply {
            moveTo(0f, -.02f)
            lineTo(1f, -.22f)
            lineTo(1f, .22f)
            lineTo(0f, .04f)
            close()
        }
    private val beamBrush =
        Brush.horizontalGradient(
            0f to Color(0xFFFFE9C0).copy(alpha = .34f),
            .35f to Color(0xFFFFE9C0).copy(alpha = .1f),
            1f to Color.Transparent,
            startX = 0f,
            endX = 1f,
        )

    /** The layer [whole] lays a figure down in: its strength and how it meets what is under it. */
    private val wholePaint = Paint()

    /**
     * Passer [p] drawn solid in [filter] into a layer, and the layer laid over the street at
     * [alpha] with [mode]: the figure's pieces cover each other as they should and the whole is
     * faded (or multiplied) once, never piece by piece.
     */
    private fun DrawScope.whole(
        p: Passer,
        persp: Float,
        alpha: Float,
        filter: ColorFilter,
        mode: BlendMode,
        how: Int = PLAIN,
        reach: Float = 0f,
    ) {
        wholePaint.alpha = alpha
        wholePaint.blendMode = mode
        drawIntoCanvas {
            it.saveLayer(
                Rect(p.x - 70f - reach, p.lane - 70f, p.x + 70f + reach, p.lane + 14f + reach),
                wholePaint,
            )
        }
        party(p, persp, 1f, filter, how)
        drawIntoCanvas { it.restore() }
    }

    /** The layer the headlights' wash and the shadows in it are drawn in, added over the walls. */
    private val sweepPaint = Paint().apply { blendMode = BlendMode.Plus }
    private val lead = Path()
    private val leadStroke = Stroke(.45f, cap = StrokeCap.Round)
    private val wall = Matrix()

    /** Fills in who is on the street at [seconds]. */
    fun update(seconds: Float, seed: Int, street: StreetLayout) {
        count = StreetLife.passers(seconds, seed, street.start, street.end, passers, street.centers)
        if (GameState.enabled && count < passers.size) {
            // the player is one more person on the pavement, lit and shadowed like the rest (far lane first)
            val me = passers[count]
            me.on = true; me.slot = 9999; me.kind = Stroller.WALKER
            me.x = GameState.x; me.lane = GameState.lane; me.lane0 = GameState.lane; me.dir = GameState.dir
            me.look = GameState.look; me.size = 1f; me.lean = 0f; me.halt = Halt.NONE
            me.frame = ((GameState.travel * 2.4f).toInt() % StreetGait.FRAMES + StreetGait.FRAMES) % StreetGait.FRAMES
            me.next = (me.frame + 1) % StreetGait.FRAMES; me.mix = 0f
            me.still = if (GameState.walking) 0f else 1f
            count++
            var i = count - 1
            while (i > 0 && passers[i - 1].lane > passers[i].lane) { val t = passers[i]; passers[i] = passers[i - 1]; passers[i - 1] = t; i-- }
        }
        val busy = StreetLife.traffic(painter?.hour ?: 12f)
        carCount = if (StageTuning.cars) StreetLife.cars(seconds, seed, street.start, street.end, busy, cars) else 0
        if (!StageTuning.cars) for (i in 0 until count) if (passers[i].kind == Stroller.CYCLIST || passers[i].kind == Stroller.DOG_WALKER) passers[i].kind = Stroller.WALKER
    }

    /** How much bigger someone [lane] out on the pavement is drawn (nearer the kerb, nearer us). */
    private fun perspective(lane: Float): Float = (1f + (lane - StreetLife.LANE) / 10f * .06f) * StageTuning.personScale

    /** The road's surface under the near car's wheels. */
    private val roadY = BuildingPainter.PAVEMENT + 2.5f + 14f

    // ------------------------------------------------------------------ the figures

    /** The layers of the walker being drawn ([StreetLayers]), filled anew for each. */
    private val layers = Layers()

    /** Pixels in a walker's sprites to a unit of the rig. */
    private val partScale = StreetLife.SIZE * StreetSprites.PPU

    /** Our pixel-art character, in place of the procedural walker: the right-facing frame ([party] flips it to walk left). */
    private fun DrawScope.pixelWalker(p: Passer, look: Int, frame: Int, alpha: Float, filter: ColorFilter?) {
        val sheets = SpriteSheets.sheets
        val sheet = sheets[look % sheets.size]
        val standing = p.still > .5f
        val i = if (standing) 0 else (frame % StreetGait.FRAMES) * 6 / StreetGait.FRAMES
        // the same height as the procedural walker it replaces (its body is ~56 of our 64 rows)
        val ref = SceneSprites.walker(look, 0).image.height.toFloat()
        val k = ref / 56f
        val w = (32 * k).toInt().coerceAtLeast(1)
        val h = (64 * k).toInt().coerceAtLeast(1)
        drawImage(
            sheet,
            srcOffset = IntOffset(i * 32, (if (standing) 1 else 2) * 64),
            srcSize = IntSize(32, 64),
            dstOffset = IntOffset(-w / 2, -(61 * k).toInt()),
            dstSize = IntSize(w, h),
            alpha = alpha,
            colorFilter = filter,
            blendMode = blend,
            filterQuality = FilterQuality.None,
        )
    }

    /**
     * A walker at the current origin (the feet), [alpha] strong: the walk's frame and the one
     * beside it (or the settle to a stand), with the arms and the head doing what [g] says, each
     * layer turned about its joint: the far arm behind the body, the head, then the near arm and
     * what it holds in front.
     */
    private fun DrawScope.walkerFrames(
        p: Passer,
        look: Int,
        frame: Int,
        next: Int,
        mix: Float,
        alpha: Float,
        filter: ColorFilter?,
        g: Gesture,
    ) {
        if (StageTuning.pixelPeople && SpriteSheets.sheets.isNotEmpty()) {
            pixelWalker(p, look, frame, alpha, filter)
            return
        }
        val l = layers
        StreetLayers.lay(look, frame, next, mix, p.still, g, l)
        for (i in 0 until l.count) {
            val part = l.part[i]
            val a = alpha * l.alpha[i]
            if (part < 0) {
                val w = SceneSprites.walker(look, l.body(i))
                drawImage(w.image, Offset(w.dx, w.dy), a, colorFilter = filter, blendMode = blend)
                continue
            }
            val im = SceneSprites.walkerPart(look, part)
            withTransform({
                translate(l.x[i] * partScale, l.y[i] * partScale)
                rotate(l.deg[i], Offset.Zero)
            }) {
                drawImage(
                    im.image,
                    Offset(im.dx, im.dy),
                    a,
                    colorFilter = filter,
                    blendMode = blend,
                )
                // a lit screen shows in the dark
                if (part == Part.PHONE && phoneGlow > .01f)
                    drawImage(
                        im.image,
                        Offset(im.dx, im.dy),
                        a * phoneGlow,
                        colorFilter = phoneGlowFilter,
                        blendMode = BlendMode.Plus,
                    )
            }
        }
    }

    private fun DrawScope.dogFrames(p: Passer, alpha: Float, filter: ColorFilter?) {
        if (p.sniff > .01f) {
            val s = SceneSprites.dog(DogRig.SNIFF_FRAME)
            val a = SceneSprites.dog(p.dogFrame)
            drawImage(a.image, Offset(a.dx, a.dy), alpha, colorFilter = filter, blendMode = blend)
            drawImage(
                s.image,
                Offset(s.dx, s.dy),
                alpha * p.sniff,
                colorFilter = filter,
                blendMode = blend,
            )
            return
        }
        if (p.dogNext != p.dogFrame && p.dogMix > .03f) {
            val b = SceneSprites.dog(p.dogNext)
            drawImage(
                b.image,
                Offset(b.dx, b.dy),
                alpha * p.dogMix,
                colorFilter = filter,
                blendMode = blend,
            )
        }
        val a = SceneSprites.dog(p.dogFrame)
        drawImage(a.image, Offset(a.dx, a.dy), alpha, colorFilter = filter, blendMode = blend)
    }

    /** The bicycle: its wheels turning, then the frame and the rider. */
    private fun DrawScope.cycleFrames(p: Passer, alpha: Float, filter: ColorFilter?) {
        val r = StreetGait.WHEEL_R * StreetLife.SIZE
        val half = CycleRig.WHEELBASE / 2f * StreetLife.SIZE
        val deg = -p.wheel * 180f / Math.PI.toFloat()
        cycleWheel(-half, r, deg, alpha, filter)
        cycleWheel(half, r, deg, alpha, filter)
        // the crank's turn between two baked frames: the nearer one drawn whole, the next mixed in
        // by how far the turn has gone, so the pedalling flows instead of stepping
        val n = StreetGait.CRANK_FRAMES
        val turn = (p.crank / (2f * Math.PI.toFloat())).let { it - kotlin.math.floor(it) } * n
        val f0 = turn.toInt() % n
        val f1 = (f0 + 1) % n
        val t = turn - turn.toInt()
        val a = SceneSprites.cycle(if (t < .5f) f0 else f1)
        val b = SceneSprites.cycle(if (t < .5f) f1 else f0)
        val mix = if (t < .5f) t else 1f - t
        if (mix > .03f)
            drawImage(
                b.image,
                Offset(b.dx, b.dy),
                alpha * mix,
                colorFilter = filter,
                blendMode = blend,
            )
        drawImage(a.image, Offset(a.dx, a.dy), alpha, colorFilter = filter, blendMode = blend)
    }

    private fun DrawScope.cycleWheel(
        dx: Float,
        r: Float,
        deg: Float,
        alpha: Float,
        filter: ColorFilter?,
    ) {
        val wheel = SceneSprites.cycleWheel
        withTransform({
            // the figure is drawn in its sprite's own pixels: units times the pixels per unit
            translate(dx * StreetSprites.PPU, -r * StreetSprites.PPU)
            rotate(deg, Offset.Zero)
        }) {
            drawImage(
                SceneSprites.cycleWheelImage,
                Offset(-wheel.ox, -wheel.oy),
                alpha,
                colorFilter = filter,
                blendMode = blend,
            )
        }
    }

    /**
     * Everyone of passer [p] at the current origin (their feet), in the sprite's own pixels: the
     * walker (and a companion, a dog on its lead, or a bicycle under them).
     */
    private fun DrawScope.figure(p: Passer, alpha: Float, filter: ColorFilter?) {
        when (p.kind) {
            Stroller.CYCLIST -> cycleFrames(p, alpha, filter)
            else -> walkerFrames(p, p.look, p.frame, p.next, p.mix, alpha, filter, p.act)
        }
    }

    /**
     * How [party] places a figure: flat on the pavement, fallen along the key light, or along a
     * lamp's.
     */
    private val PLAIN = 0
    private val KEY = 1
    private val LAMP = 2

    /** The matrix [party] places a figure with, and a lamp's lens for [LAMP]. */
    private val place = Matrix()
    private var lensX = 0f
    private var nudgeX = 0f
    private var nudgeY = 0f

    /**
     * How far the figure [placed] puts leans into its walk: the top forward of the feet by this
     * much a unit up (the tangent of the lean, signed by the way they walk).
     */
    private var skew = 0f
    private val shear = Matrix()

    private fun placed(x: Float, y: Float, how: Int): Matrix {
        place.reset()
        place.translate(x + nudgeX, y + nudgeY)
        when (how) {
            KEY -> {
                val l = light ?: return place
                place *= fallMatrix(l, x)
            }
            LAMP -> {
                fall.reset()
                fall[1, 0] = -NearLight.lampLean(lensX, x)
                fall[1, 1] = -NearLight.lampReach(y * 2f)
                place *= fall
            }
        }
        if (skew != 0f) {
            shear.reset()
            shear[1, 0] = -skew
            place *= shear
        }
        return place
    }

    /**
     * The whole of passer [p], placed [how] about their feet: the figure and what goes with them (a
     * companion, a dog on its lead, the bicycle under them).
     */
    private fun DrawScope.party(
        p: Passer,
        persp: Float,
        alpha: Float,
        filter: ColorFilter?,
        how: Int,
        mode: BlendMode = BlendMode.SrcOver,
    ) {
        blend = mode
        val s = persp * unit
        // a brisk walker leans into it a little: the whole figure, sheared about the feet
        skew = p.dir * tan(p.lean * (Math.PI.toFloat() / 180f))
        withTransform({ transform(placed(p.x, p.lane, how)) }) {
            scale(p.dir * s, s, Offset.Zero) { figure(p, alpha, filter) }
        }
        val lean = skew
        skew = 0f
        when (p.kind) {
            Stroller.DOG_WALKER ->
                withTransform({ transform(placed(p.dogX, p.lane, how)) }) {
                    scale(p.dir * s, s, Offset.Zero) { dogFrames(p, alpha, filter) }
                }
            Stroller.PAIR -> {
                val s2 = perspective(p.lane + StreetLife.SIDE_OUT) * unit
                skew = lean
                withTransform({
                    transform(
                        placed(
                            p.x - p.dir * StreetLife.SIDE_BACK,
                            p.lane + StreetLife.SIDE_OUT,
                            how,
                        )
                    )
                }) {
                    scale(p.dir * s2, s2, Offset.Zero) {
                        walkerFrames(p, p.look2, p.frame2, p.next2, p.mix2, alpha, filter, p.act2)
                    }
                }
                skew = 0f
            }
            else -> {}
        }
        blend = BlendMode.SrcOver
    }

    // ------------------------------------------------------------------ shadows

    /**
     * The passers' and the car's shadows from the key light, in the painter's shadow layer: each
     * figure fallen along the light from its feet ([BuildingPainter.shadows] calls this).
     */
    fun DrawScope.keyShadows(light: KeyLight, filter: ColorFilter, from: Float, to: Float) {
        val lean = abs(light.lean) + SceneLight.SPLAY * 1.5f
        val near = lean * 30f + 40f
        for (i in 0 until count) {
            val p = passers[i]
            if (p.x + near < from || p.x - near > to) continue
            party(p, perspective(p.lane), 1f, filter, KEY)
        }
        for (i in 0 until carCount) {
            val car = cars[i]
            if (car.x + 120f < from || car.x - 120f > to) continue
            val c = SceneSprites.car(car.body)
            val sp = c.sprite
            val s = carSize(car)
            withTransform({
                translate(car.x, carGround(car))
                transform(fallMatrix(light, car.x))
                scale(car.dir * carUnit * s, carUnit * s, Offset.Zero)
            }) {
                drawImage(c.paint, Offset(-sp.w / 2f, -sp.groundY), colorFilter = filter)
                val wheel = SceneSprites.carWheel
                val ws = sp.wheelR / StreetLife.CAR_WHEEL
                for (k in 0..1) {
                    val wx = if (k == 0) sp.wheelX0 else sp.wheelX1
                    withTransform({
                        translate(wx - sp.w / 2f, sp.wheelY - sp.groundY)
                        scale(ws, ws, Offset.Zero)
                    }) {
                        drawImage(
                            SceneSprites.carWheelImages[sp.rim],
                            Offset(-wheel.ox, -wheel.oy),
                            colorFilter = filter,
                        )
                    }
                }
            }
        }
    }

    /** The road under a car's wheels: the far lane a little further up it. */
    private fun carGround(c: CarPass): Float =
        if (c.lane == 0) roadY else roadY - StreetLife.FAR_LANE_UP

    /** How big a car is drawn: the far lane's a little smaller. */
    private fun carSize(c: CarPass): Float = if (c.lane == 0) 1f else StreetLife.FAR_LANE_SCALE

    private fun tintBy(rgb: Int): ColorFilter {
        val r = (rgb shr 16 and 255) / 255f
        val g = (rgb shr 8 and 255) / 255f
        val b = (rgb and 255) / 255f
        return ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    r,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    g,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    b,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        )
    }

    private fun fallMatrix(light: KeyLight, x: Float): Matrix {
        fall.reset()
        fall[1, 0] = -SceneLight.lean(light, (x - lightX) / lightSpan)
        fall[1, 1] = -light.reach
        return fall
    }

    /**
     * At night, each passer's shadow from the street lamps near them: the lens is a point light, so
     * the shadow leans away from it (behind them as they come, in front once past), stretches with
     * the distance and fades with the lamp's pool. Drawn after the key light's shadows and before
     * anything that stands on the pavement.
     */
    fun DrawScope.lampShadows(dark: Float, lamps: List<Furniture>, from: Float, to: Float) {
        if (dark <= .2f || count == 0) return
        for (k in lamps.indices) {
            val f = lamps[k]
            if (f.kind != FurnitureKind.LAMP) continue
            val lensX = f.x + NearLight.LAMP_LENS
            if (
                lensX < from - NearLight.LAMP_REACH - 40f || lensX > to + NearLight.LAMP_REACH + 40f
            )
                continue
            for (i in 0 until count) {
                val p = passers[i]
                val strength = NearLight.lampFall(p.x - lensX)
                if (strength < .02f) continue
                this@StreetLifePainter.lensX = lensX
                // the shadow as one shape, faded once (its pieces would darken where they cross)
                whole(
                    p,
                    perspective(p.lane),
                    .6f * dark * strength,
                    lampShadeFilter,
                    BlendMode.SrcOver,
                    LAMP,
                    260f,
                )
            }
        }
    }

    // ------------------------------------------------------------------ the people themselves

    /**
     * The people on the pavement, in front of the lamp posts and trees: each with a soft contact
     * shadow under either foot, a rim of light on the side the key light is, and the warmth of the
     * nearest street lamp at night. Nobody when the street is drawn small ([lod]).
     */
    fun DrawScope.people(
        sky: SkyColors,
        lamps: List<Furniture>,
        from: Float,
        to: Float,
        lod: PeopleLod,
    ) {
        if (count == 0 || lod == PeopleLod.NONE || lod == PeopleLod.DOT) return
        val key = light
        val dark = sky.dark
        for (i in 0 until count) {
            val p = passers[i]
            if (p.x + 60f < from || p.x - 60f > to) continue
            val persp = perspective(p.lane)
            // the nearest lamp's light on them
            var lamp = 0f
            if (dark > .2f)
                for (k in lamps.indices) {
                    val f = lamps[k]
                    if (f.kind != FurnitureKind.LAMP) continue
                    val d = p.x - (f.x + NearLight.LAMP_LENS)
                    val s = NearLight.lampFall(d) * dark
                    if (s > lamp) lamp = s
                }
            // the feet's contact shadows, or the bicycle's under its tyres and frame
            if (p.kind == Stroller.CYCLIST) cycleContacts(p, persp)
            else
                contacts(p, p.look, StreetLayers.shown(p.frame, p.still, p.act), p.x, p.lane, persp)
            if (p.kind == Stroller.PAIR)
                contacts(
                    p,
                    p.look2,
                    StreetLayers.shown(p.frame2, p.still, p.act2),
                    p.x - p.dir * StreetLife.SIDE_BACK,
                    p.lane + StreetLife.SIDE_OUT,
                    perspective(p.lane + StreetLife.SIDE_OUT),
                )
            // how much of the day's look they take: all of it by day, none once it is dark
            val day = (1f - dark * 1.6f).coerceIn(0f, 1f)
            // the rim on the lit side: the figure once more, a sliver towards the light (by day a
            // faint cool edge, at night the lamp's warmth or the moon's blue)
            val rim =
                if (key != null)
                    key.strength * (1f - .5f * key.cool) * (1f - dark * .5f) * (1f - .55f * day)
                else 0f
            // (under a lamp, its light takes the place of the key light's rim, below)
            if (rim > .03f && lamp < rim) {
                val filter = if (key != null && key.cool > .5f) moonRimFilter else dayRimFilter
                nudgeX = lightFrom * .5f
                nudgeY = -.15f
                party(p, persp, rim * .55f, filter, PLAIN)
                nudgeX = 0f
                nudgeY = 0f
            }
            // a figure is drawn in pieces (body, arms, head, what they carry) laid over each other:
            // drawn at part strength the pieces would show through each other, a ghost. So each
            // look is laid down whole and solid, and only the whole is faded: the night look solid,
            // the day look over it in a layer faded by the hour
            if (day < .99f) {
                phoneGlow = (1f - day) * PHONE_GLOW
                party(p, persp, 1f, nightFigure, PLAIN)
                phoneGlow = 0f
            }
            if (day > .01f) {
                if (day < .99f) whole(p, persp, day, dayFigure, BlendMode.SrcOver)
                else party(p, persp, 1f, dayFigure, PLAIN)
            }
            // in a building's shade they are darker and a little cooler, as the pavement is: the
            // shade's depth where they stand, laid over the whole of them once by multiplying
            val shaded = painter?.shadeAt(p.x, p.lane) ?: 0f
            if (shaded > .01f)
                whole(
                    p,
                    persp,
                    (shaded * SHADE_ON_FIGURE).coerceAtMost(1f),
                    figureShade,
                    BlendMode.Multiply,
                )
            // the street lamps' light on them, falling off across them from each lens
            if (dark > .2f) lampsOn(p, persp, lamps, dark)
            if (dark > .2f && p.kind == Stroller.CYCLIST) cycleLamps(p, persp, dark)
            if (p.kind == Stroller.DOG_WALKER) lead(p, persp)
        }
    }

    /**
     * The light of each street lamp near passer [p] on them, at night: the figure in the lamp's
     * colour, a little brighter along the edge that faces the lens, seen through the lamp's
     * fall-off ([LightSprites.point], centred on the lens) and added over the figure. So the light
     * is strongest on the head and shoulders and the side nearest the lamp and fades down the body
     * and away from it, and it grows as they walk in under the lamp and fades as they walk on.
     */
    private fun DrawScope.lampsOn(p: Passer, persp: Float, lamps: List<Furniture>, dark: Float) {
        val n = LightSprites.POINT.toFloat()
        val s = 2f * LightSprites.POINT_R / n
        for (k in lamps.indices) {
            val f = lamps[k]
            if (f.kind != FurnitureKind.LAMP) continue
            val lx = f.x + NearLight.LAMP_LENS
            val d = p.x - lx
            if (abs(d) > LightSprites.POINT_R) continue
            lampPaint.alpha = (LAMP_ON_FIGURE * dark).coerceAtMost(1f)
            drawIntoCanvas {
                it.saveLayer(Rect(p.x - 60f, -90f, p.x + 60f, p.lane + 10f), lampPaint)
            }
            // the edge that faces the lens: the figure nudged towards it, the body held back
            nudgeX = (if (d > 0f) -1f else 1f) * .6f
            nudgeY = -.2f
            party(p, persp, 1f, lampLitFilter, PLAIN)
            nudgeX = 0f
            nudgeY = 0f
            party(p, persp, .5f, lampLitFilter, PLAIN, BlendMode.DstOut)
            withTransform({
                translate(lx, -NearLight.LAMP_HEIGHT)
                scale(s, s, Offset.Zero)
            }) {
                drawImage(
                    SceneSprites.light.point,
                    Offset(-n / 2f, -n / 2f),
                    blendMode = BlendMode.DstIn,
                )
            }
            drawIntoCanvas { it.restore() }
        }
    }

    /**
     * The soft shadow under each of a walker's feet: darkest with the foot down, gone as it lifts.
     */
    private fun DrawScope.contacts(
        p: Passer,
        look: Int,
        frame: Int,
        x: Float,
        y: Float,
        persp: Float,
    ) {
        val j = SceneSprites.walker(look, frame).joints
        val s = StreetLife.SIZE * persp
        foot(x + p.dir * j.footNX * s, y, j.footNH * s)
        foot(x + p.dir * j.footFX * s, y, j.footFH * s)
    }

    /**
     * The bicycle's shadow where it meets the pavement: a dark patch under each tyre and a soft
     * shade under the whole of it, deepest under the cranks.
     */
    private fun DrawScope.cycleContacts(p: Passer, persp: Float) {
        val s = StreetLife.SIZE * persp
        val half = CycleRig.WHEELBASE / 2f * s
        translate(p.x + p.dir * CycleRig.BB_X * s, p.lane) {
            scale(half + StreetGait.WHEEL_R * s * .7f, 1.5f, Offset.Zero) {
                drawCircle(contact, 1f, Offset.Zero, alpha = .55f)
            }
        }
        foot(p.x - half, p.lane, 0f)
        foot(p.x + half, p.lane, 0f)
    }

    /**
     * The bicycle's lamps at night: the head lamp's cool white glow and the little pool it throws
     * on the pavement ahead, and the tail lamp's red.
     */
    private fun DrawScope.cycleLamps(p: Passer, persp: Float, dark: Float) {
        val s = StreetLife.SIZE * persp
        val hx = p.x + p.dir * (CycleRig.LAMP_X + .5f) * s
        val hy = p.lane + CycleRig.LAMP_Y * s
        translate(hx, hy) {
            scale(2.2f * s, 2.2f * s, Offset.Zero) {
                drawCircle(bikeLamp, 1f, Offset.Zero, alpha = dark, blendMode = BlendMode.Plus)
            }
        }
        translate(hx + p.dir * 9f * s, p.lane) {
            scale(8f * s, 1.4f * s, Offset.Zero) {
                drawCircle(
                    bikeLamp,
                    1f,
                    Offset.Zero,
                    alpha = .3f * dark,
                    blendMode = BlendMode.Plus,
                )
            }
        }
        translate(p.x + p.dir * (CycleRig.TAIL_X - .3f) * s, p.lane + CycleRig.TAIL_Y * s) {
            scale(1.6f * s, 1.6f * s, Offset.Zero) {
                drawCircle(tail, 1f, Offset.Zero, alpha = dark, blendMode = BlendMode.Plus)
            }
        }
    }

    private fun DrawScope.foot(x: Float, y: Float, lift: Float) {
        val a = (1f - lift / 2.4f).coerceIn(0f, 1f)
        if (a <= .02f) return
        val w = 2.4f + lift * .6f
        translate(x, y) {
            scale(w, 1.1f, Offset.Zero) {
                drawCircle(contact, 1f, Offset.Zero, alpha = a * a * .9f)
            }
        }
    }

    /** The dog's lead, from the walker's near hand to the dog's collar, sagging a little. */
    private fun DrawScope.lead(p: Passer, persp: Float) {
        // the near hand where the layers put it this moment
        StreetLayers.lay(p.look, p.frame, p.next, p.mix, p.still, p.act, layers)
        val s = StreetLife.SIZE * persp
        val hx = p.x + p.dir * layers.handX * s
        val hy = p.lane + layers.handY * s
        val ds = StreetLife.DOG_SIZE * persp
        val cx = p.dogX + p.dir * 3.3f * ds
        val cy = p.lane - 7.2f * ds + (if (p.sniff > 0f) 2.5f * ds * p.sniff else 0f)
        lead.rewind()
        lead.moveTo(hx, hy)
        lead.quadraticBezierTo((hx + cx) / 2f, max(hy, cy) + 1.6f, cx, cy)
        drawPath(lead, Color(0xFF1C1612), style = leadStroke, alpha = .9f)
    }

    // ------------------------------------------------------------------ the front road's cars

    /** Where car [c]'s headlights are along the street. */
    private fun lampX(c: CarPass): Float =
        c.x + c.dir * (SceneSprites.car(c.body).sprite.length / 2f - 1.5f) * carSize(c)

    /**
     * The headlights' sweep over the facades at night: for each car on the road a warm wash that
     * slides along the building fronts ahead of it, and in it the shadows of the lamp posts and
     * trees thrown up the walls from its headlights (low, so they are tall and swing as the car
     * passes). Drawn after the buildings and before what stands in front of them.
     */
    fun DrawScope.sweep(
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        street: StreetLayout,
        from: Float,
        to: Float,
        seconds: Float,
    ) {
        val dark = sky.dark
        if (dark <= .2f || carCount == 0 || layouts.isEmpty()) return
        var any = false
        for (n in 0 until carCount) {
            val spot = NearLight.sweepSpot(lampX(cars[n]), cars[n].dir)
            if (spot + NearLight.SWEEP_REACH >= from && spot - NearLight.SWEEP_REACH <= to)
                any = true
        }
        if (!any) return
        var tallest = 0f
        for (i in layouts.indices) tallest = max(tallest, layouts[i].height)
        drawIntoCanvas { it.saveLayer(Rect(from, -tallest - 10f, to, 0f), sweepPaint) }
        val p = painter
        for (n in 0 until carCount) {
            val car = cars[n]
            val lampX = lampX(car)
            val spot = NearLight.sweepSpot(lampX, car.dir)
            if (spot + NearLight.SWEEP_REACH < from || spot - NearLight.SWEEP_REACH > to) continue
            // the far lane's lamps are a little further from the walls: a touch less light
            val near = if (car.lane == 0) 1f else .85f
            for (i in layouts.indices) {
                val cx = street.centers[i]
                // the wash covers the front and whichever side face the building shows
                val shift = p?.shiftAt(cx) ?: B.DX
                val left = cx + Perspective.left(shift)
                val right = cx + Perspective.right(shift)
                if (right < from || left > to) continue
                val strength = NearLight.sweep(lampX, car.dir, cx)
                if (strength < .02f) continue
                val h = layouts[i].height
                clipRect(left, -h, right, 0f) {
                    translate(spot, -6f) {
                        scale(NearLight.SWEEP_REACH * .8f, 70f, Offset.Zero) {
                            drawRect(
                                wash,
                                Offset(-1f, -1f),
                                Size(2f, 2f),
                                alpha = strength * dark * .9f * near,
                            )
                        }
                    }
                }
            }
            // the shadows of the pavement's furniture up the walls: each scaled about the
            // headlight, on the wall of each building in view
            if (p != null) {
                val scale = NearLight.wallScale()
                wall.reset()
                wall.translate(lampX, -NearLight.HEADLIGHT)
                wall.scale(scale, scale)
                wall.translate(-lampX, NearLight.HEADLIGHT)
                for (i in layouts.indices) {
                    val cx = street.centers[i]
                    val shift = p.shiftAt(cx)
                    val left = cx + Perspective.left(shift)
                    val right = cx + Perspective.right(shift)
                    if (right < from || left > to) continue
                    if (NearLight.sweep(lampX, car.dir, cx) < .02f) continue
                    clipRect(left, -layouts[i].height, right, 0f) {
                        withTransform({ transform(wall) }) {
                            for (k in street.furniture.indices) {
                                val f = street.furniture[k]
                                if (f.kind == FurnitureKind.BENCH) continue
                                val strength = NearLight.sweep(lampX, car.dir, f.x)
                                if (strength < .03f) continue
                                with(p) {
                                    caster(
                                        f,
                                        seconds,
                                        Color.Black,
                                        BlendMode.DstOut,
                                        strength * dark * near,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        drawIntoCanvas { it.restore() }
    }

    /**
     * A wheel at [wx] (the sprite's pixels, the car's frame in pixels about its middle on the
     * road), turned [deg]: the rim design's picture scaled to the body's wheel, the arch's shadow
     * over it unturned, and at night the dark over both.
     */
    private fun DrawScope.carWheel(
        sp: CarSprite,
        wx: Float,
        deg: Float,
        dark: Float,
        alpha: Float,
    ) {
        val wheel = SceneSprites.carWheel
        val ws = sp.wheelR / StreetLife.CAR_WHEEL
        withTransform({
            translate(wx - sp.w / 2f, sp.wheelY - sp.groundY)
            scale(ws, ws, Offset.Zero)
        }) {
            val at = Offset(-wheel.ox, -wheel.oy)
            rotate(deg, Offset.Zero) {
                drawImage(SceneSprites.carWheelImages[sp.rim], at, alpha = alpha)
            }
            drawImage(SceneSprites.carWheelShade, at, alpha = alpha)
            if (dark > .01f) {
                rotate(deg, Offset.Zero) {
                    drawImage(
                        SceneSprites.carWheelImages[sp.rim],
                        at,
                        alpha = .72f * dark * alpha,
                        colorFilter = carNight,
                    )
                }
            }
        }
    }

    /**
     * A car's body in the sprite's pixels about its middle on the road: its paint through the
     * paint's filter and what is not paint over it, by day and (over that, as strong as the dark)
     * by night; its rack and a taxi's sign.
     */
    private fun DrawScope.carBody(c: CarImages, car: CarPass, dark: Float, alpha: Float) {
        val sp = c.sprite
        val at = Offset(-sp.w / 2f, -sp.groundY)
        if (dark < .99f) {
            drawImage(c.paint, at, alpha = alpha, colorFilter = dayPaints[car.paint])
            drawImage(c.day, at, alpha = alpha)
        }
        if (dark > .01f) {
            drawImage(c.paint, at, alpha = dark * alpha, colorFilter = nightPaints[car.paint])
            drawImage(c.night, at, alpha = dark * alpha)
        }
        if (car.rack) {
            val r = Offset(at.x + sp.rackX, at.y + sp.rackY)
            drawImage(c.rack, r, alpha = alpha)
            if (dark > .01f)
                drawImage(c.rack, r, alpha = .6f * dark * alpha, colorFilter = carNight)
        }
        if (car.taxi) {
            val s = SceneSprites.taxiSign
            val o = Offset(at.x + (sp.roofX0 + sp.roofX1) / 2f - s.ox, at.y + sp.roofY - s.oy)
            val im = SceneSprites.taxiSignImages
            if (dark < .99f) drawImage(im[0], o, alpha = alpha)
            if (dark > .01f) drawImage(im[1], o, alpha = dark * alpha)
        }
    }

    /**
     * The cars on the front road, the far lane's first: each one's pool of light and beam on the
     * road at night, its reflection in a wet road, its body riding a little on its springs, its
     * wheels turning, and its lamps' bloom.
     */
    fun DrawScope.car(sky: SkyColors, from: Float, to: Float) {
        for (i in 0 until carCount) car(cars[i], sky, from, to)
    }

    private fun DrawScope.car(car: CarPass, sky: SkyColors, from: Float, to: Float) {
        val c = SceneSprites.car(car.body)
        val sp = c.sprite
        val len = sp.length
        if (car.x + len < from - 150f || car.x - len > to + 150f) return
        val dark = sky.dark
        val night = dark > .2f
        val s = carSize(car)
        val wet = painter?.weather?.wet ?: 0f
        // the wheels roll clockwise driving right: the picture is mirrored for the other way
        val deg = car.wheel * 180f / Math.PI.toFloat()
        translate(car.x, carGround(car)) {
            scale(car.dir * s, s, Offset.Zero) {
                val front = len / 2f - 1.5f
                val lampUp = (sp.groundY - sp.lampY) / sp.ppu
                val tailUp = (sp.groundY - sp.tailY) / sp.ppu
                if (night) {
                    // the pool ahead on the road, then the beam, and the tail lamp's red
                    translate(front + 34f, 1.5f) {
                        scale(46f, 9f, Offset.Zero) {
                            drawRect(
                                pool,
                                Offset(-1f, -1f),
                                Size(2f, 2f),
                                alpha = .8f * dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    translate(front, -lampUp) {
                        scale(150f, 60f, Offset.Zero) {
                            drawPath(
                                beam,
                                beamBrush,
                                alpha = .75f * dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    translate(-front, -tailUp) {
                        scale(9f, 4f, Offset.Zero) {
                            drawRect(
                                tail,
                                Offset(-1f, -1f),
                                Size(2f, 2f),
                                alpha = .8f * dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                }
                scale(carUnit, carUnit, Offset.Zero) {
                    if (wet > .05f) {
                        // the car upside down in the wet road, faint
                        scale(1f, -1f, Offset.Zero) {
                            val a = .3f * wet
                            carBody(c, car, dark, a)
                            carWheel(sp, sp.wheelX0, deg, dark, a)
                            carWheel(sp, sp.wheelX1, deg, dark, a)
                        }
                    }
                    // the body rides its springs over the wheels
                    translate(0f, -car.bob * sp.ppu) { carBody(c, car, dark, 1f) }
                    carWheel(sp, sp.wheelX0, deg, dark, 1f)
                    carWheel(sp, sp.wheelX1, deg, dark, 1f)
                    if (night) {
                        translate(0f, -car.bob * sp.ppu) {
                            drawImage(
                                c.lights,
                                Offset(-sp.w / 2f, -sp.groundY),
                                alpha = dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How strongly a street lamp lights a walker right under it, through its fall-off. */
private const val LAMP_ON_FIGURE = 1f

/** How strongly a phone's screen glows over its night grade, fully dark. */
private const val PHONE_GLOW = .45f

/** How much of a building's shade (its depth on the pavement) darkens a walker standing in it. */
private const val SHADE_ON_FIGURE = 1f
