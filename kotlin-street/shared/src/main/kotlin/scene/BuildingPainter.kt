package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.drawText
import com.lifemax.rpg.feature.habits.domain.Activity
import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Dice
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.domain.Furniture
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.Gait
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.HouseView
import com.lifemax.rpg.feature.habits.domain.KeyLight
import com.lifemax.rpg.feature.habits.domain.Looks
import com.lifemax.rpg.feature.habits.domain.PeopleActivities
import com.lifemax.rpg.feature.habits.domain.PeopleLod
import com.lifemax.rpg.feature.habits.domain.Perspective
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.Presence
import com.lifemax.rpg.feature.habits.domain.RoomKind
import com.lifemax.rpg.feature.habits.domain.RuinWindow
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.Silhouettes
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.domain.Stroll
import com.lifemax.rpg.feature.habits.domain.Trees
import com.lifemax.rpg.feature.habits.domain.Visit
import com.lifemax.rpg.feature.habits.domain.WeatherState
import com.lifemax.rpg.feature.habits.domain.WindowCast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * What changes from frame to frame on a building, kept in one place the stage refreshes (nothing is
 * made per frame). [seconds] runs while the screen shows and motion is on (0 otherwise: a still
 * picture). [landing] is the floor being finished today: [lights] 0 as its first window switches on
 * to 1 when all that will are on, [squash] its settle (1 at the start, back to 0), [burst] 0 to 1
 * for the glow that floods it, [sweep] 0 to 1 for the light that crosses a floor that changed
 * material. [felled] is the storey knocked off a habit being broken, [fell] 0 to 1 as it tumbles.
 * [adding] is a habit just stacked, [drop] 0 to 1 as its floor drops into place.
 */
internal class BuildingMotion {
    var seconds = 0f
    var landing: String? = null

    /** The floor laid a moment ago that people crowd to the windows of, and the seconds since. */
    var cheer: String? = null
    var cheerSince = 0f
    var lights = 1f
    var squash = 0f
    var burst = 1f
    var sweep = 1f
    var swept = false
    var felled: String? = null
    var fell = 1f
    var adding: String? = null
    var drop = 1f

    fun still(seconds: Float): BuildingMotion {
        this.seconds = seconds
        landing = null
        cheer = null
        felled = null
        adding = null
        return this
    }
}

/**
 * Draws the street in building units: x along the street (a building's from its middle), y down
 * from the pavement line (0), so what stands is at negative y.
 */
/**
 * Where a floor stands in its building's crowd: the building's number, the floor's place among the
 * lit floors from the bottom (-1 if it is not lit), how many are lit, and whether the building's
 * walker is on it just now.
 */
private class Crowd(val seed: Int, val ordinal: Int, val litFloors: Int, val walker: Boolean)

private fun isLit(fa: FloorArt, live: Boolean) =
    fa.piece.kind != PieceKind.RUIN && (!live || fa.block.done)

internal class BuildingPainter {
    private val path = Path()
    private val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 5f))
    private val hairline = Stroke(1.2f)
    private val glowWide = Stroke(9f, join = StrokeJoin.Round)
    private val glowMid = Stroke(4.5f, join = StrokeJoin.Round)
    private val glowEdge = Stroke(1.8f, join = StrokeJoin.Round)
    /**
     * A missed floor's decay ([DecayInk]): each ink's colour and, for the stroked ones, its line.
     */
    private val decayInk = Array(DECAY_INKS.size) { Color(DECAY_INKS[it].argb) }
    private val decayLine =
        Array(DECAY_INKS.size) {
            val ink = DECAY_INKS[it]
            if (ink.width <= 0f) null
            else
                Stroke(
                    ink.width,
                    cap = if (ink == DecayInk.TAPE) StrokeCap.Butt else StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
        }
    private val rustFilter = ColorFilter.tint(Color(FloorDecay.RUST), BlendMode.SrcIn)
    private val ghostLine = Stroke(1.4f, pathEffect = dash)
    private val ring = Stroke(2f)
    private val selection = Stroke(2.6f, join = StrokeJoin.Round)

    /** A lamp's light, drawn as a unit circle under a transform so one brush serves them all. */
    private val halo =
        Brush.radialGradient(
            0f to SceneInk.LampLight.copy(alpha = .5f),
            .15f to SceneInk.LampLight.copy(alpha = .36f),
            .35f to SceneInk.LampLight.copy(alpha = .17f),
            .6f to SceneInk.LampLight.copy(alpha = .055f),
            .82f to SceneInk.LampLight.copy(alpha = .012f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = 1f,
        )

    /**
     * The bloom round a light: a unit circle under a transform, drawn additively (a soft falloff
     * that brightens what is behind it instead of covering it), as a camera sees a lit window.
     */
    private val bloom =
        Brush.radialGradient(
            0f to SceneInk.WindowLit.copy(alpha = .55f),
            .22f to SceneInk.WindowLit.copy(alpha = .28f),
            .48f to SceneInk.WindowLit.copy(alpha = .1f),
            .78f to SceneInk.WindowLit.copy(alpha = .025f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = 1f,
        )

    /** Ambient occlusion where a building meets the pavement, and the foot of its wall. */
    private val aoGround =
        Brush.verticalGradient(
            0f to Color(0x6B000000),
            1f to Color.Transparent,
            startY = 0f,
            endY = 8f,
        )
    private val aoWall =
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color(0x59000000),
            startY = -14f,
            endY = 0f,
        )

    /** Glass: sky reflected at the top of a pane, deepening towards its sill (a unit square). */
    private val glassSheen =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = .26f),
            .4f to Color.White.copy(alpha = .05f),
            1f to Color.Black.copy(alpha = .16f),
            startY = 0f,
            endY = 1f,
        )

    /** The sign board's face, a little lighter at its top. */
    private val signFace =
        Brush.verticalGradient(
            0f to Color(0xFF2A3042),
            1f to Color(0xFF151925),
            startY = 0f,
            endY = 15f,
        )

    /** The shadow the awning throws on the shopfront under it. */
    private val awningDrop =
        Brush.verticalGradient(
            0f to Color(0x66000000),
            1f to Color.Transparent,
            startY = 0f,
            endY = 10f,
        )

    /**
     * A wall's grade over its tile: a touch lighter at a floor's top, darker at its foot (a unit
     * square); the cornice's shadow down the wall under it; the sign's lamps' light down its board.
     */
    private val wallGrade =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = .07f),
            .5f to Color.Transparent,
            1f to Color.Black.copy(alpha = .09f),
            startY = 0f,
            endY = 1f,
        )
    private val corniceDrop =
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .36f),
            1f to Color.Transparent,
            startY = 0f,
            endY = 1f,
        )
    private val lampWash =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = .26f),
            1f to Color.Transparent,
            startY = 0f,
            endY = 1f,
        )

    /** The awning's slope: the sky on its top, the hem in its own shade (awning units). */
    private val awningSlope =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = .16f),
            .45f to Color.Transparent,
            1f to Color.Black.copy(alpha = .2f),
            startY = 0f,
            endY = 16.4f,
        )

    /**
     * The walls darker towards the pavement over the lowest floors (building units, absolute: one
     * brush for every floor it reaches).
     */
    private val groundGrade =
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = .17f),
            startY = -GROUND_GRADE,
            endY = -B.LOBBY_H,
        )

    /** A soft band of the sun's reflection across polished metal or glass (a unit, centred). */
    private val polishBand =
        Brush.horizontalGradient(
            0f to Color.Transparent,
            .5f to Color.White.copy(alpha = .55f),
            1f to Color.Transparent,
            startX = -.5f,
            endX = .5f,
        )

    /** A side face's wall tile, skewed to the face (one matrix, set per floor). */
    private val sideSkew = Matrix()

    /** The ironwork's strokes (a pipe hanging loose, a tag) and a board's frame. */
    private val inkStroke = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val frameStroke = Stroke(1.2f)

    /**
     * The awning's outline, made once for the building's width from the cloth's own shape
     * ([AwningArt]): the canopy's cheeks and its scalloped hem, in units from the awning's top. Its
     * shadow is cast in it and its washes of light are clipped to it.
     */
    private val awningFace =
        Path().apply {
            val half = B.W / 2f
            moveTo(-half + AwningArt.INSET, 0f)
            lineTo(half - AwningArt.INSET, 0f)
            lineTo(half - 1f, AwningArt.front(half - 1f))
            var x = half - 1f
            while (x > -half + 1f) {
                lineTo(x, AwningArt.hem(x))
                x -= .5f
            }
            lineTo(-half + 1f, AwningArt.hem(-half + 1f))
            lineTo(-half + 1f, AwningArt.front(-half + 1f))
            close()
        }

    /** The cone of light under a lamp's lens (lamp units: lens at y -91.5, pavement at 0). */
    private val cone =
        Path().apply {
            moveTo(6f, -91.5f)
            lineTo(16f, -91.5f)
            lineTo(39f, 0f)
            lineTo(-17f, 0f)
            close()
        }

    /**
     * A lamp post: a stepped plinth (boxes, [box]), a tapering round pole, a curved arm, a hood.
     * The pole's silhouette (for its shadows) is [lampPole]; it is painted as [poleBands], strips
     * down its length each lit as its part of a cylinder ([StreetVolume.cylinder]).
     */
    private val lampPole =
        Path().apply {
            moveTo(POLE_X - 2.2f, -8f)
            lineTo(POLE_X + 2.2f, -8f)
            lineTo(POLE_X + 1.25f, -88f)
            lineTo(POLE_X - 1.25f, -88f)
            close()
        }
    private val poleBands =
        Array(POLE_U.size - 1) { k ->
            // each strip a hair over the one before it, so no seam shows between them
            val u0 = POLE_U[k] - if (k > 0) .06f else 0f
            val u1 = POLE_U[k + 1]
            Path().apply {
                moveTo(POLE_X + u0 * 2.2f, -8f)
                lineTo(POLE_X + u1 * 2.2f, -8f)
                lineTo(POLE_X + u1 * 1.25f, -88f)
                lineTo(POLE_X + u0 * 1.25f, -88f)
                close()
            }
        }

    /**
     * The arm's silhouette for the lamp's shadows: up from the pole and into the head, which hangs
     * over the pole as the arm reaches out over the street ([lampPost] draws the arm live).
     */
    private val lampArm =
        Path().apply {
            moveTo(POLE_X, -86f)
            quadraticBezierTo(POLE_X, -97f, POLE_X, -97.4f)
        }

    /** The arm as [lampPost] draws it this frame, refilled (nothing is made per frame). */
    private val armPath = Path()

    /** How far the lamp being drawn holds its head to the side ([lampPost]), for its lens. */
    private var headDx = 0f
    private val lampHood =
        Path().apply {
            moveTo(3.5f, -94.6f)
            quadraticBezierTo(11.5f, -101f, 20f, -94.6f)
            lineTo(18.6f, -91.8f)
            lineTo(5f, -91.8f)
            close()
        }
    private val armStroke = Stroke(2f, cap = StrokeCap.Round)
    private val armLit = Stroke(.7f, cap = StrokeCap.Round)
    private val rim = Stroke(.8f)

    /** The furniture's depth ([StreetVolume]): its place, its boxes' faces, its round posts. */
    private val volume = com.lifemax.rpg.feature.habits.domain.StreetVolume

    /** One path, refilled for each face of a box as it is drawn (nothing is made per frame). */
    private val facePath = Path()

    /**
     * The frame's light on the furniture's iron and wood: the side it comes from (-1 to 1), how
     * hard it is (0 to 1, softened by cloud), and the lit colours (bluer under the moon). Set as
     * each piece is drawn ([furnitureLight]).
     */
    private var keyFrom = -1f
    private var keyLight = 0f
    private var ironLit = IronLit
    private var woodLit = WoodLit

    /** The side the piece of furniture being drawn shows ([Perspective.side]) and its skew. */
    private var furnSide = 0f
    private var furnSkew = 0f

    /** The wind's lean and the view's turn on a tree's back leaves (kept, like [shear]). */
    private val backShear = Matrix()
    private val backLeaves = ColorFilter.tint(BackLeaves, BlendMode.Modulate)

    /** Which side the light falls from: -1 the left (the trees are lit on it), 1 the right. */
    var lightFrom = -1f

    // the pavement's and the road's brushes, remade when the sky or the window changes
    private var roadSky: SkyColors? = null
    private var roadDepth = -1f
    private var pavementBrush: Brush? = null
    private var roadBrush: Brush? = null

    /** The wind's lean on a tree, as a shear: one matrix, kept (nothing is made per frame). */
    private val shear = Matrix()

    /**
     * The time of year ([Season]): which looks the trees wear and how much snow lies on the street.
     * Set by the stage with the day.
     */
    var season: SeasonState = Season.SUMMER

    /** The alpha of each of a tree's season looks for this frame ([TreeDress.plan]), reused. */
    private val dress = FloatArray(TreeSeason.entries.size)

    /** The snow's colour for the sky (white by day, the moon's blue at night) and its filter. */
    private var snowKey: SkyColors? = null
    private var snowInk = Color.White
    private var snowFilter: ColorFilter? = null

    /** The hood of a lamp with snow along it, in lamp units: the hood's curve, a little up. */
    private val lampSnow =
        Path().apply {
            moveTo(4.6f, -95.4f)
            quadraticBezierTo(11.5f, -101.8f, 19f, -95.4f)
        }
    private val snowStroke = Stroke(2.2f, cap = StrokeCap.Round)

    /**
     * The key light's ground projection ([KeyLight]): what stands up falls flat along it, down the
     * pavement and leaning away from the light. One matrix, remade when the light changes.
     */
    private val fall = Matrix()

    /**
     * Where the key light stands along the street (street units) and how wide the view is in them:
     * a caster's shadow leans by how far to the side of the light it stands ([SceneLight.lean]), so
     * shadows swing as the street scrolls under the sun. Set by the stage each frame, with the
     * light itself and the street's lamps.
     */
    var lightX = 0f
    var lightSpan = 1000f
    var light: KeyLight? = null
    var lamps: List<Furniture> = emptyList()

    /** The street's people and car, whose shadows go in the same layer as everything else's. */
    var streetLife: StreetLifePainter? = null

    /**
     * The hour the street is drawn at, with its fraction ([Dusk]): every light has its own moment
     * in it, and the golden and the blue hour tint the walls by it. Set by the stage each frame
     * with the scene's seconds ([clock]).
     */
    var hour = 12f
        private set

    /** The golden hour's and the blue hour's strength at [hour], 0 to 1. */
    private var gold = 0f
    private var blue = 0f

    /** The hour the frame before was drawn at, and when the lamps' run along the street began. */
    private var lastHour = Float.NaN
    private var lampRunStart = -1f

    /**
     * When each building's pigeons were last sent up (seconds; long ago at first), by seed slot.
     */
    private val scare = FloatArray(PIGEON_SLOTS) { -1e9f }

    /** Sets the hour (and the seconds) the next frame is drawn at. */
    fun clock(hour: Float, seconds: Float) {
        if (!lastHour.isNaN() && lastHour < Dusk.LAMP_AT && hour >= Dusk.LAMP_AT && seconds > 0f)
            lampRunStart = seconds
        if (hour < Dusk.LAMP_AT || hour >= 20f) lampRunStart = -1f
        lastHour = hour
        this.hour = hour
        gold = Dusk.gold(hour)
        blue = Dusk.blue(hour)
    }

    /** The golden hour's wash from a wall's lit edge, over the white one (a unit brush). */
    private val goldWash =
        Brush.horizontalGradient(
            0f to GoldLight.copy(alpha = .3f),
            .55f to GoldLight.copy(alpha = .1f),
            1f to Color.Transparent,
            startX = 0f,
            endX = 1f,
        )

    /** The bloom of a television's light, cool where a lamp's is warm. */
    private val tvBloom =
        Brush.radialGradient(
            0f to TvLight.copy(alpha = .5f),
            .25f to TvLight.copy(alpha = .22f),
            .55f to TvLight.copy(alpha = .07f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = 1f,
        )

    /** The steam's tint for the sky, and the pigeons' at night (kept per sky). */
    private var duskSky: SkyColors? = null
    private var steamFilter: ColorFilter? = null
    private val pigeonNight = ColorFilter.tint(Color(0xFF6A7490), BlendMode.Modulate)

    /**
     * The side of the building being drawn that the light finds, -1 left to 1 right, times the
     * light's strength (0 face on or in the dark): set per building, read by its parts.
     */
    private var sunSide = 0f

    /** Where along the street the building being drawn stands (its front's middle). */
    private var buildingX = 0f

    /**
     * Where along the street the middle of the view stands, and how fast a building's side face
     * comes round with its place across the view ([Perspective.k], gentler zoomed out): which side
     * each building shows, as a camera in the street would see it. Set by the stage each frame; the
     * view's width is [lightSpan].
     */
    var viewX = 0f
    var perspectiveK = Perspective.K_FOCUS

    /**
     * The side face the building being drawn shows ([Perspective.shift]): how far its back edge is
     * drawn to the side of the front, positive for the right face, negative for the left. Set per
     * building, read by its parts.
     */
    private var sideShift = B.DX

    /** The shown side's shift for a building at [x] along the street. */
    internal fun shiftAt(x: Float): Float =
        Perspective.shift(Perspective.sideAt(x, viewX, lightSpan, perspectiveK))

    /**
     * Where the furniture at [x] along the street is drawn: where it stands. The pavement it stands
     * on moves with the facades, so a lamp or a tree must too; a parallax of its own made it glide
     * over the paving as the street scrolled. Its depth shows in its own faces and shading instead.
     */
    internal fun standAt(x: Float): Float = x

    /** A facade's light and shade across it, unit brushes drawn under a transform. */
    private val litWash =
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = .2f),
            .55f to Color.White.copy(alpha = .06f),
            1f to Color.Transparent,
            startX = 0f,
            endX = 1f,
        )
    private val shadeWash =
        Brush.horizontalGradient(
            0f to Color(0xFF0A1024).copy(alpha = .22f),
            .6f to Color(0xFF0A1024).copy(alpha = .05f),
            1f to Color.Transparent,
            startX = 0f,
            endX = 1f,
        )

    /** The layer a frame's shadows are drawn in, so two shadows over each other are no deeper. */
    private val shadowPaint = Paint()

    /** Sprites drawn as shadows take the shade's colour through this filter (kept per colour). */
    private var shadeColor = Color.Unspecified
    private var shadeFilter: ColorFilter? = null

    /** The figures' colour filter while a shadow of one is drawn (null: the figure itself). */
    private var personFilter: ColorFilter? = null

    /**
     * How much of the day's flat look the figures in the windows take (1 by day, 0 at night): set
     * per building from the sky. [dayFilter] flattens a figure to one cool dark tone and cuts the
     * faint glow round it (alpha below about .4 goes to nothing).
     */
    private var dayMix = 0f
    private val dayFilter =
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0f,
                    0f,
                    0f,
                    0f,
                    40f,
                    0f,
                    0f,
                    0f,
                    0f,
                    33f,
                    0f,
                    0f,
                    0f,
                    0f,
                    40f,
                    0f,
                    0f,
                    0f,
                    1.7f,
                    -178f,
                )
            )
        )
    private val spillFilter = ColorFilter.tint(SpillShade, BlendMode.SrcIn)
    private val blackFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn)

    /** The light a lobby window throws on the pavement at night, and the visitor's shadow in it. */
    private val spillPath = Path()
    private val spillFall = Matrix()
    private val lampTint = ColorFilter.tint(SceneInk.LampLight, BlendMode.SrcIn)
    private val spillTint = ColorFilter.tint(SceneInk.WindowLit, BlendMode.SrcIn)

    /** Asphalt and paving specks, a tile of [SPECK_TILE] street units, made once. */
    private val roadSpecks: List<Offset> =
        List(46) {
            Offset(
                SPECK_TILE * Ambient.hash(11, it, 1),
                PAVEMENT + 4f + 46f * Ambient.hash(11, it, 2),
            )
        }
    private val roadGlints: List<Offset> =
        List(18) {
            Offset(
                SPECK_TILE * Ambient.hash(12, it, 1),
                PAVEMENT + 4f + 46f * Ambient.hash(12, it, 2),
            )
        }
    private val paveSpecks: List<Offset> =
        List(26) {
            Offset(
                SPECK_TILE * Ambient.hash(13, it, 1),
                1.5f + (PAVEMENT - 3f) * Ambient.hash(13, it, 2),
            )
        }

    // ------------------------------------------------------------------ the street itself

    /**
     * The pavement in front of the buildings from [from] to [to] along the street, its kerb, and
     * the road down to [depth].
     */
    fun DrawScope.pavement(sky: SkyColors, from: Float, to: Float, depth: Float) {
        val width = to - from
        if (roadSky !== sky || roadDepth != depth) {
            // the slabs a little lighter at the wall, the road falling away darker towards the
            // viewer
            pavementBrush =
                Brush.verticalGradient(
                    0f to lerp(sky.pavement, Color.White, .07f),
                    1f to lerp(sky.pavement, Color.Black, .12f),
                    startY = 0f,
                    endY = PAVEMENT,
                )
            roadBrush =
                Brush.verticalGradient(
                    0f to lerp(sky.road, Color.White, .04f),
                    1f to lerp(sky.road, Color.Black, .3f),
                    startY = PAVEMENT + 2.5f,
                    endY = PAVEMENT + 2.5f + min(depth, 160f),
                )
            roadSky = sky
            roadDepth = depth
        }
        drawRect(pavementBrush!!, Offset(from, 0f), Size(width, PAVEMENT))
        drawRect(sky.kerb, Offset(from, PAVEMENT), Size(width, 2.5f))
        drawRect(roadBrush!!, Offset(from, PAVEMENT + 2.5f), Size(width, depth))
        // the kerb catches the light on its top edge and throws a shadow on the road under it
        drawRect(SceneInk.Highlight, Offset(from, PAVEMENT), Size(width, .9f), alpha = .8f)
        drawRect(SceneInk.Occlusion, Offset(from, PAVEMENT + 2.5f), Size(width, 2.2f), alpha = .6f)
        // paving joints, and the lane's dashes
        var x = (kotlin.math.floor(from / 24f)) * 24f
        while (x < to) {
            drawLine(sky.line, Offset(x, 0f), Offset(x - 5f, PAVEMENT), .8f)
            x += 24f
        }
        x = kotlin.math.floor(from / 46f) * 46f
        while (x < to) {
            drawRect(sky.kerb, Offset(x, PAVEMENT + 30f), Size(22f, 2f), alpha = .6f)
            x += 46f
        }
        // the texture of the asphalt and the slabs, a few deterministic specks (not when the street
        // is drawn small: it would be a haze of dots)
        if (width < 1500f) {
            var tile = kotlin.math.floor(from / SPECK_TILE) * SPECK_TILE
            while (tile < to) {
                translate(tile, 0f) {
                    drawPoints(
                        roadSpecks,
                        PointMode.Points,
                        SceneInk.Occlusion,
                        1.2f,
                        StrokeCap.Round,
                        alpha = .55f,
                    )
                    drawPoints(
                        roadGlints,
                        PointMode.Points,
                        SceneInk.Highlight,
                        1f,
                        StrokeCap.Round,
                        alpha = .35f,
                    )
                    drawPoints(
                        paveSpecks,
                        PointMode.Points,
                        SceneInk.Occlusion,
                        1f,
                        StrokeCap.Round,
                        alpha = .22f,
                    )
                }
                tile += SPECK_TILE
            }
        }
        drawRect(SceneInk.Occlusion, Offset(from, 0f), Size(width, 1.2f))
        if (season.snow > .01f) snowGround(sky, from, to)
        if (blue > .01f) blueHour(from, 0f, width, depth, .36f)
    }

    /** The pools of light the lamps throw on the pavement at night. */
    fun DrawScope.pools(items: List<Furniture>, from: Float, to: Float, dark: Float) {
        if (dark < .05f) return
        for (i in items.indices) {
            val f = items[i]
            if (f.kind != FurnitureKind.LAMP || f.x < from - 60f || f.x > to + 60f) continue
            val on = lampOn(f, dark)
            if (on <= 0f) continue
            // a bright middle under the lens and a long soft tail along the pavement
            withTransform({
                translate(standAt(f.x) + LightSprites.LENS_X - 64f, PAVEMENT * .55f - 15f)
                scale(128f / LightSprites.POOL_W, 30f / LightSprites.POOL_H, Offset.Zero)
            }) {
                drawImage(
                    SceneSprites.light.pool,
                    Offset.Zero,
                    .62f * on,
                    colorFilter = lampTint,
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    /**
     * How far on the lamp [f] is in the dark of [dark]: nothing by day, then its own moment across
     * the dusk hour (the lamps come on first, in a run along the street, [Dusk.lampRun]), at least
     * a third once lit, and the night's full strength.
     */
    private fun lampOn(f: Furniture, dark: Float): Float {
        if (dark <= .05f) return 0f
        var k = 0
        for (i in lamps.indices) {
            val o = lamps[i]
            if (o.kind == FurnitureKind.LAMP && o.x < f.x) k++
        }
        return max(dark, .3f) * Dusk.lampRun(k, f.seed, hour, lampSeconds, lampRunStart)
    }

    /** The seconds the furniture is drawn at (set as each lamp is drawn, read by [lampOn]). */
    private var lampSeconds = 0f

    /** A lamp, a tree or a bench on the pavement. */
    /**
     * The air under each street lamp's lens lit, fading out with no edge: drawn before the people
     * on the pavement, so someone walking under a lamp is lit by it ([StreetLifePainter]'s lamp
     * light) rather than washed over by the glow of the air in front of the post.
     */
    /**
     * How far a lamp's arm reaches across the picture: 1 as our lamp is drawn (its arm out along its street, which runs
     * across the picture), less as its street turns away from the camera, down to 0 for a street running straight away
     * (the arm points along it, its head seen just by the pole). Set by the crossroads for the other street's lamps.
     */
    var lampReach = 1f

    /** The lamp's head and lens shifted towards its pole as its arm turns away ([lampReach]), street units. */
    private val lampHeadDx: Float get() = -(1f - lampReach) * (LAMP_LENS_X - LAMP_FOOT_X) * LAMP_K

    fun DrawScope.lampAir(
        items: List<Furniture>,
        sky: SkyColors,
        from: Float,
        to: Float,
        seconds: Float,
    ) {
        val dark = sky.dark
        if (dark < .05f) return
        lampSeconds = seconds
        val L = LightSprites
        for (i in items.indices) {
            val f = items[i]
            if (f.kind != FurnitureKind.LAMP || f.x < from - 60f || f.x > to + 60f) continue
            val on = lampOn(f, dark)
            if (on <= 0f) continue
            translate(standAt(f.x) + lampHeadDx, 0f) {
                withTransform({
                    translate(L.AIR_X0, L.AIR_Y0)
                    scale((L.AIR_X1 - L.AIR_X0) / L.AIR_W, -L.AIR_Y0 / L.AIR_H, Offset.Zero)
                }) {
                    drawImage(
                        SceneSprites.light.air,
                        Offset.Zero,
                        .75f * on,
                        colorFilter = lampTint,
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }

    fun DrawScope.furniture(f: Furniture, sky: SkyColors, seconds: Float) {
        val dark = sky.dark
        lampSeconds = seconds
        furnitureLight(f.x)
        if (SpriteArt.lamp != null) {
            // OUR lamp, tree or bench, lit by the engine
            translate(standAt(f.x), 0f) {
                propPicture(f, seconds, if (f.kind == FurnitureKind.TREE) treeColors() else nightFilter)
                if (f.kind == FurnitureKind.LAMP) {
                    val on = lampOn(f, dark)
                    if (on > 0f) {
                        val k = LAMP_K
                        translate((LAMP_LENS_X - LAMP_FOOT_X) * k + lampHeadDx, -(LAMP_FOOT_Y - LAMP_LENS_Y) * k) {
                            scale(26f, 26f, Offset.Zero) { drawCircle(halo, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus) }
                            scale(58f, 58f, Offset.Zero) { drawCircle(bloom, 1f, Offset.Zero, alpha = on * .38f, blendMode = BlendMode.Plus) }
                        }
                    }
                }
            }
            return
        }
        translate(standAt(f.x), 0f) {
            when (f.kind) {
                FurnitureKind.LAMP -> {
                    val on = lampOn(f, dark)
                    // (the air it lights is drawn behind the people: [lampAir])
                    lampPost(on)
                    // the glass lens under the hood, seen a little round with the view: dull by
                    // day with a glint, lit at night; the head where the arm holds it out
                    val hx = headDx
                    drawRoundRect(
                        lerp(Color(0xFF8A90A0), SceneInk.LampLight, on),
                        Offset(6f + hx + furnSide * .6f, -91.8f),
                        Size(11f, 2.6f),
                        CornerRadius(1.2f, 1.2f),
                    )
                    if (season.snow > .01f) translate(hx, 0f) { snowLamp(sky) }
                    if (on == 0f)
                        drawRect(Color.White, Offset(7.2f + hx, -91.4f), Size(4f, .7f), alpha = .6f)
                    else
                        translate(11.5f + hx, -89.5f) {
                            scale(26f, 26f, Offset.Zero) {
                                drawCircle(
                                    halo,
                                    1f,
                                    Offset.Zero,
                                    alpha = on,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                            scale(58f, 58f, Offset.Zero) {
                                drawCircle(
                                    bloom,
                                    1f,
                                    Offset.Zero,
                                    alpha = on * .38f,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                }
                FurnitureKind.TREE -> tree(0f, f.seed, seconds, 1f, sky, f.x)
                FurnitureKind.BENCH -> bench(sky)
            }
        }
    }

    /**
     * Sets the light and the view for the piece of furniture at [x]: which side of it shows
     * ([furnSide], [furnSkew]), where the key light comes from and how hard, and the lit colours of
     * iron and wood under it.
     */
    private fun furnitureLight(x: Float) {
        furnSide = Perspective.sideAt(x, viewX, lightSpan, perspectiveK)
        furnSkew = volume.skew(furnSide)
        keyFrom = lightFrom
        val l = light
        keyLight = if (l == null) 0f else l.strength * (1f - .7f * l.flat)
        val cool = l?.cool ?: 0f
        ironLit = lerp(IronLit, IronMoon, cool)
        woodLit = lerp(WoodLit, WoodMoon, cool)
    }

    private fun iron(l: Float): Color = lerp(IronDeep, ironLit, l)

    private fun wood(l: Float): Color = lerp(WoodDeep, woodLit, l)

    /** The light on an upright face turned [theta] from the street, as [StreetVolume.face]. */
    private fun faceLight(theta: Float): Float = volume.face(theta, keyFrom, keyLight)

    /**
     * Fills [facePath] with the quadrilateral through four points, the street's way up (y down).
     */
    private fun quad(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
    ): Path {
        facePath.reset()
        facePath.moveTo(ax, ay)
        facePath.lineTo(bx, by)
        facePath.lineTo(cx, cy)
        facePath.lineTo(dx, dy)
        facePath.close()
        return facePath
    }

    /**
     * A box of the furniture: [x0] to [x1] across, [y0] to [y1] up from the pavement (up positive)
     * and [z0] to [z1] back (back positive, 0 the piece's own middle), seen with the frame's skew
     * ([furnSkew]): the side away from the middle of the view, the top, the front, each in its own
     * light. [wooden] picks the wood's colours, otherwise iron.
     */
    private fun DrawScope.box(
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        z0: Float,
        z1: Float,
        wooden: Boolean,
    ) {
        val sx = furnSkew
        val r = volume.RISE
        if (abs(sx) > .02f) {
            val x = if (sx > 0f) x1 else x0
            val l = faceLight(if (sx > 0f) volume.RIGHT else -volume.RIGHT)
            drawPath(
                quad(
                    x + sx * z0,
                    -y0 - r * z0,
                    x + sx * z0,
                    -y1 - r * z0,
                    x + sx * z1,
                    -y1 - r * z1,
                    x + sx * z1,
                    -y0 - r * z1,
                ),
                if (wooden) wood(l) else iron(l),
            )
        }
        val top = volume.top(keyLight)
        drawPath(topFace(x0, x1, y1, z0, z1), if (wooden) wood(top) else iron(top))
        val front = faceLight(volume.FRONT)
        drawRect(
            if (wooden) wood(front) else iron(front),
            Offset(x0 + sx * z0, -y1 - r * z0),
            Size(x1 - x0, y1 - y0),
        )
    }

    /** The top of a box ([box]) as [facePath]: [y] up, [x0] to [x1], [z0] to [z1] back. */
    private fun topFace(x0: Float, x1: Float, y: Float, z0: Float, z1: Float): Path {
        val sx = furnSkew
        val r = volume.RISE
        return quad(
            x0 + sx * z0,
            -y - r * z0,
            x1 + sx * z0,
            -y - r * z0,
            x1 + sx * z1,
            -y - r * z1,
            x0 + sx * z1,
            -y - r * z1,
        )
    }

    /**
     * A lamp post with [on] its light: a stepped plinth with its top and a side, a round pole lit
     * as a cylinder turning with the view, a collar where the arm leaves it, a tubular arm lit
     * along its top, and a domed hood whose highlight sits on the light's side and slides round
     * with the view, its dark lip underneath.
     */
    private fun DrawScope.lampPost(on: Float) {
        val px = POLE_X
        box(px - 4.4f, px + 4.4f, 0f, 4.2f, -4.4f, 4.4f, false)
        box(px - 3.2f, px + 3.2f, 4.2f, 7.6f, -3.2f, 3.2f, false)
        for (k in poleBands.indices) {
            val u = (POLE_U[k] + POLE_U[k + 1]) / 2f
            drawPath(poleBands[k], iron(volume.cylinder(u, furnSide, keyFrom, keyLight)))
        }
        box(px - 1.75f, px + 1.75f, 84.4f, 86.4f, -1.75f, 1.75f, false)
        // the arm reaches out over the street, towards us: seen from the front it rises from the
        // pole and bends forward into the head, which leans a little to the side of the view the
        // street is seen from (as the buildings show a side), so the lamp faces the road
        val hx = -furnSkew * HEAD_REACH
        headDx = hx
        armPath.rewind()
        armPath.moveTo(px, -86f)
        armPath.cubicTo(px, -95f, px + hx * .5f, -99.5f, px + hx, -97.4f)
        drawPath(armPath, iron(.12f), style = armStroke)
        translate(0f, -.55f) {
            drawPath(armPath, iron(volume.top(keyLight)), alpha = .8f, style = armLit)
        }
        translate(hx, 0f) { lampHead() }
    }

    /** The lamp's hood, as [lampPost] holds it out: its dome, the dome's light, its lip. */
    private fun DrawScope.lampHead() {
        drawPath(lampHood, iron(faceLight(volume.FRONT) * .7f))
        clipPath(lampHood) {
            // the dome's lit crown towards the light, the far side in shade; both turn with the
            // view
            val turn = furnSide * 1.8f
            drawOval(
                iron(volume.top(keyLight) + .15f),
                Offset(6.5f + keyFrom * 3f + turn, -101f),
                Size(10f, 4.6f),
                alpha = .9f,
            )
            drawOval(
                iron(0f),
                Offset(13.5f - keyFrom * 8f + turn, -98.5f),
                Size(8f, 7f),
                alpha = .6f,
            )
            // the lip's underside, out of the light
            drawRect(iron(0f), Offset(3f, -92.9f), Size(18f, 1.1f), alpha = .8f)
        }
    }

    /**
     * A bench: cast-iron ends with their thickness, a seat of three slats showing their tops, a
     * back of three slats and the arms, each a box seen from the frame's side, with a soft shadow
     * under it.
     */
    private fun DrawScope.bench(sky: SkyColors) {
        val sx = furnSkew
        val r = volume.RISE
        drawOval(
            SceneInk.Shadow,
            Offset(-15f + sx * 2f, -1.6f - r * 2f),
            Size(30f, 4.4f),
            alpha = .5f,
        )
        // the back's slats, furthest back, then the ends, the seat over them, the arms on top
        for (k in 0 until 3) {
            val y = 12f + k * 2.5f
            box(-14f, 14f, y, y + 1.9f, 4.6f, 5.3f, true)
        }
        box(-13f, -11.2f, 0f, 12.2f, 0f, 5.3f, false)
        box(11.2f, 13f, 0f, 12.2f, 0f, 5.3f, false)
        for (k in 2 downTo 0) {
            val z = k * 1.8f
            box(-14f, 14f, 7.4f, 9f, z, z + 1.55f, true)
        }
        box(-14.6f, -10.8f, 12.2f, 13.2f, -.4f, 5.3f, false)
        box(10.8f, 14.6f, 12.2f, 13.2f, -.4f, 5.3f, false)
        if (season.snow > .01f) snowBench(sky)
    }

    /**
     * A street tree at [x]: a painted sprite ([TreeSprites]: crown in several values, rim light,
     * bark, soft foot shadow), the look of the hour crossfaded (day, dusk, night, the street lamps
     * rimming it warm at night), mirrored for a light from the right. With motion on it leans in
     * the wind by a vertical shear (the foot stays put), so nothing is redrawn. Which variant it is
     * comes from [seed].
     *
     * It has depth as the street scrolls: standing at [at] along the street, its crown slides a
     * little out from the middle of the view with the trunk (the leaves in front) while a darker
     * copy of it behind (the leaves at the back, [backLeaves]) slides a little the other way, so
     * the far side of the crown shows as a band of shade on the side the buildings show theirs.
     */
    private fun DrawScope.tree(
        x: Float,
        seed: Int,
        seconds: Float,
        size: Float,
        sky: SkyColors,
        at: Float = buildingX + x,
    ) {
        val variant = Trees.variant(seed)
        val sp = SceneSprites.trees[variant].sprite
        val lean = if (seconds > 0f) Ambient.sway(seed, seconds) else 0f
        // two looks, the one under and the other over it by how far the hour has gone on
        val dark = sky.dark
        val under = if (dark <= .55f) 0 else 1
        val over = under + 1
        val t = if (dark <= .55f) dark / .55f else (dark - .55f) / .45f
        // and the looks of the season, the bare tree under the canopy ([TreeDress])
        TreeDress.plan(season, variant, dress)
        val snow = season.snow
        if (snow > .01f) snowInk(sky)
        // the view's turn as a shear about the foot (in the mirrored picture's own sense): the
        // crown's middle slides by its share of the side face, the foot stays where it stands
        val shift = shiftAt(at)
        val turn = -lightFrom * shift / (Trees.shapes[variant].height * .7f)
        val wind = -tan(lean * (PI.toFloat() / 180f))
        shear.reset()
        shear[0, 1] = wind + turn * TREE_FRONT
        val u = size / sp.ppu
        if (abs(shift) > .5f) {
            backShear.reset()
            backShear[0, 1] = wind - turn * TREE_BACK
            withTransform({
                translate(x, 0f)
                scale(u * -lightFrom, u, Offset.Zero)
                transform(backShear)
            }) {
                val foot = Offset(-sp.footX, -sp.footY)
                for (s in TreeDress.ORDER) {
                    val a = dress[s.ordinal]
                    if (a <= .01f) continue
                    drawImage(
                        SceneSprites.tree(variant, s).looks[under],
                        foot,
                        // a soft darker mass, not an outline: the back leaves are in shade, not
                        // black
                        alpha = a * .6f,
                        colorFilter = backLeaves,
                    )
                }
            }
        }
        withTransform({
            translate(x, 0f)
            scale(u * -lightFrom, u, Offset.Zero)
            transform(shear)
        }) {
            val at = Offset(-sp.footX, -sp.footY)
            for (s in TreeDress.ORDER) {
                val a = dress[s.ordinal]
                if (a <= .01f) continue
                val images = SceneSprites.tree(variant, s)
                drawImage(images.looks[under], at, alpha = a)
                if (t > .01f) drawImage(images.looks[over], at, alpha = a * t)
            }
            if (snow > .01f)
                drawImage(
                    SceneSprites.treeSnow(variant),
                    at,
                    alpha = snow,
                    colorFilter = snowFilter,
                )
        }
    }

    // ------------------------------------------------------------------ snow

    /** The snow's colour under this sky, kept with its filter until the sky changes. */
    private fun snowInk(sky: SkyColors): Color {
        if (snowKey !== sky) {
            snowInk = lerp(SnowDay, SnowNight, Px.smooth(.15f, .9f, sky.dark))
            snowFilter = ColorFilter.tint(snowInk, BlendMode.SrcIn)
            snowKey = sky
        }
        return snowInk
    }

    /** A soft cap of snow lying on a ledge: a rounded strip, [w] by [h] from ([x], [y]). */
    private fun DrawScope.cap(x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        drawRoundRect(
            snowInk,
            Offset(x, y),
            Size(w, h),
            CornerRadius(h * .5f, h * .5f),
            alpha = alpha,
        )
    }

    /**
     * Fresh snow on the pavement from [from] to [to]: the slabs whitened (the joints showing
     * faintly through), a sparkle on it by day, the kerb's top piled, and the road's edge gone to
     * grey slush.
     */
    private fun DrawScope.snowGround(sky: SkyColors, from: Float, to: Float) {
        val a = season.snow
        val ink = snowInk(sky)
        val width = to - from
        drawRect(ink, Offset(from, 0f), Size(width, PAVEMENT), alpha = .84f * a)
        drawRoundRect(
            ink,
            Offset(from, PAVEMENT - .6f),
            Size(width, 1.9f),
            CornerRadius(.9f, .9f),
            alpha = a,
        )
        drawRect(ink, Offset(from, PAVEMENT + 2.5f), Size(width, 7f), alpha = .2f * a)
        drawRect(ink, Offset(from, PAVEMENT + 9.5f), Size(width, 14f), alpha = .08f * a)
        val sparkle = a * (1f - sky.dark) * .7f
        if (width < 1500f && sparkle > .03f) {
            var tile = kotlin.math.floor(from / SPECK_TILE) * SPECK_TILE
            while (tile < to) {
                translate(tile, 0f) {
                    drawPoints(
                        paveSpecks,
                        PointMode.Points,
                        Color.White,
                        1.1f,
                        StrokeCap.Round,
                        alpha = sparkle,
                    )
                }
                tile += SPECK_TILE
            }
        }
    }

    /** Snow along a lamp's hood and on the tops of its plinth (as [lampPost] lays them). */
    private fun DrawScope.snowLamp(sky: SkyColors) {
        val a = season.snow
        snowInk(sky)
        drawPath(lampSnow, snowInk, alpha = a, style = snowStroke)
        drawPath(topFace(-4.4f, 4.4f, 4.6f, -4.4f, 4.4f), snowInk, alpha = a)
        drawPath(topFace(-3.2f, 3.2f, 8f, -3.2f, 3.2f), snowInk, alpha = a)
    }

    /** Snow on a bench's seat, along its back and on its arms (as [bench] lays them). */
    private fun DrawScope.snowBench(sky: SkyColors) {
        val a = season.snow
        snowInk(sky)
        drawPath(topFace(-14.2f, 14.2f, 9.4f, 0f, 5.15f), snowInk, alpha = a)
        drawPath(topFace(-14.2f, 14.2f, 19.3f, 4.5f, 5.3f), snowInk, alpha = a)
        drawPath(topFace(-14.7f, -10.7f, 13.6f, -.4f, 5.3f), snowInk, alpha = a)
        drawPath(topFace(10.7f, 14.7f, 13.6f, -.4f, 5.3f), snowInk, alpha = a)
    }

    /** The park's grass under snow and its fence rail capped. */
    private fun DrawScope.snowPark(sky: SkyColors, from: Float) {
        val a = season.snow
        snowInk(sky)
        drawRect(snowInk, Offset(from, -4f), Size(B.PARK_W - 10f, 4f), alpha = .9f * a)
        cap(from - .3f, -7.5f, B.PARK_W - 9.4f, 1.5f, a)
    }

    /**
     * Snow on the lobby's sign and along the awning's top, with a little hanging at the scallops.
     */
    private fun DrawScope.snowLobby(sky: SkyColors, top: Float, a0: Float) {
        val a = season.snow
        val half = B.W / 2f
        snowInk(sky)
        cap(-half + 9.5f, top + 4f - 1.1f, B.W - 19f, 1.7f, a)
        cap(-half + 5.5f, a0 - 1.2f, B.W - 11f, 2f, a)
        var x = half - 1f - AwningArt.SCALLOP / 2f
        while (x >= -half + 1f) {
            val y = a0 + AwningArt.front(x) - 1f
            drawOval(snowInk, Offset(x - 2.4f, y), Size(4.8f, 1.5f), alpha = .65f * a)
            x -= AwningArt.SCALLOP
        }
    }

    /** Snow on a floor's sills and along its cornice. */
    private fun DrawScope.snowFloor(fa: FloorArt, alpha: Float, sky: SkyColors) {
        val a = season.snow * alpha
        val half = B.W / 2f
        snowInk(sky)
        val w = fa.windows
        for (k in 0 until fa.windowCount) {
            val x = w[k * 4]
            val y = fa.top + w[k * 4 + 1]
            cap(x - .7f, y + w[k * 4 + 3] - .5f, w[k * 4 + 2] + 1.4f, 1.6f, a)
        }
        cap(-half - 1.2f, fa.top - 1.1f, B.W + 2.4f, 2f, a)
    }

    /** Snow on the roof: the cornice capped, the flat whitened, the tank's lid. */
    private fun DrawScope.snowRoof(sky: SkyColors, y: Float, tank: Boolean) {
        val a = season.snow
        val half = B.W / 2f
        val ink = snowInk(sky)
        roofFace(y - B.CORNICE, ink.copy(alpha = .86f * a), half + 3f)
        cap(-half - 3.3f, y - B.CORNICE - 1.3f, B.W + 6.6f, 2.3f, a)
        if (tank) {
            val tx = -half + 26f
            val base = y - B.CORNICE - B.DY / 2f
            cap(tx - 8.9f, base - 24.6f, 17.8f, 1.6f, a)
            cap(tx - 3.4f, base - 30.4f, 6.8f, 1.6f, a)
        }
    }

    // ------------------------------------------------------------------ shadows

    /**
     * The shade the key light leaves: the sky's blue in the sun's shadows, violet at dusk, near
     * black under the moon.
     */
    private fun shade(light: KeyLight, sky: SkyColors): Color =
        lerp(
            if (light.cool > .5f) MoonShade
            else lerp(SunShade, DuskShade, (sky.dark / .55f).coerceIn(0f, 1f)),
            FlatShade,
            light.flat * (1f - light.cool),
        )

    /** The ground projection for a caster at [x] along the street (the one matrix, set again). */
    private fun fallMatrix(light: KeyLight, x: Float): Matrix {
        fall.reset()
        // x' = x - lean * y and y' = -reach * y: a point up a caster (y under 0) lands down the
        // pavement, to the side the light leans to for a caster standing where this one does
        fall[1, 0] = -SceneLight.lean(light, (x - lightX) / lightSpan)
        fall[1, 1] = -light.reach
        return fall
    }

    /** How far to the side of the light the building at [x] stands, in view widths. */
    private fun offset(x: Float): Float = (x - lightX) / lightSpan

    private fun shadeFilter(c: Color): ColorFilter {
        val f = shadeFilter
        if (f != null && c == shadeColor) return f
        shadeColor = c
        return ColorFilter.tint(c, BlendMode.SrcIn).also { shadeFilter = it }
    }

    /**
     * Every shadow the key light throws on the street from [from] to [to], in one layer at the
     * shade's depth: each building's block of shade (the light falls through the gaps between
     * buildings), the trees in the parks (swaying as the trees do), and the lamps, trees and
     * benches on the pavement. Drawn after the pavement and before anything that stands on it.
     */
    /** [houseShade]'s answer, reused: left, right (from the building's middle), height, and sideways shift. */
    private val houseBlock = FloatArray(4)

    /**
     * A house's shade on the pavement, as the block a shop's front would throw ([shadows], [shadeAt]): the wall's span,
     * and the height and sideways shift of a front at the pavement line whose shade falls where the house's does. The
     * house stands back in its yard, so the yard takes the first of its shade (the house draws that itself) and only what
     * reaches past it lands on the pavement: the ridge's, standing further back, or the eave's. False when none does.
     */
    private fun houseShade(sh: com.lifemax.rpg.feature.habits.domain.HouseShape, light: KeyLight, lean: Float): Boolean =
        setBackShade(sh.frontX + sh.wall0, sh.frontX + sh.wall1, sh.h, sh.ridge, sh.depth / 2f, sh.setback, light, lean)

    /** A villa's or a landmark's shade on the pavement, as a house's ([houseShade]): its walls, and its highest ridge. */
    private fun modelShade(m: com.lifemax.rpg.feature.habits.domain.ModelShape, light: KeyLight, lean: Float): Boolean =
        setBackShade(m.frontX + m.wall0, m.frontX + m.wall1, m.wallTop, m.ridge, m.ridgeD, m.setback, light, lean)

    /**
     * The block of shade on the pavement of something standing [setback] back from it, across [x0] to [x1] of the lot's
     * 224-pixel frame: its wall [wall] high and its ridge [ridge] high [ridgeD] behind the wall, each lowered by how far
     * back it stands (the yard takes that shade). Into [houseBlock]; false when none reaches the pavement.
     */
    private fun setBackShade(x0: Float, x1: Float, wall: Float, ridge: Float, ridgeD: Float, setback: Float, light: KeyLight, lean: Float): Boolean {
        if (light.reach < .05f) return false
        val k = B.W / SpriteArt.SRC_W
        val d = com.lifemax.rpg.feature.habits.domain.HouseView.DEPTH
        val footLift = B.DY * setback / d
        val w = wall * k - footLift / light.reach
        val r = ridge * k - B.DY * (setback + ridgeD) / d / light.reach
        val h = max(w, r)
        if (h <= 1f) return false
        houseBlock[0] = -B.W / 2f + x0 * k
        houseBlock[1] = -B.W / 2f + x1 * k
        houseBlock[2] = h
        houseBlock[3] = (lean * footLift / light.reach).coerceIn(-60f, 60f)
        return true
    }

    /** What [shadows] last drew from, for [shadeAt]: the key light, the buildings, the street. */
    private var shadeLight: KeyLight? = null
    private var shadeLayouts: List<BuildingLayout> = emptyList()
    private var shadeStreet: StreetLayout? = null

    /**
     * How deep in the buildings' shade the pavement is at [x] along the street, [y] out from the
     * facades (the street's units, as [shadows] drew this frame): 0 in the sun to the shade's own
     * strength under it, with the same soft edges (the penumbra widening with the height that
     * throws it). The inverse of each building's fall: the point of the facade whose shadow lands
     * here, and whether the building is there. For the people on the pavement, who darken as they
     * walk into a building's shade.
     */
    fun shadeAt(x: Float, y: Float): Float {
        val light = shadeLight ?: return 0f
        val street = shadeStreet ?: return 0f
        if (light.strength < .02f || light.reach <= .01f || y <= 0f) return 0f
        val up = y / light.reach
        val soft = 1f + 1.6f * (1f - Px.clamp01(light.strength))
        var deepest = 0f
        val layouts = shadeLayouts
        for (i in layouts.indices) {
            val l = layouts[i]
            var h = l.roof
            for (p in l.pieces) if (p.kind == PieceKind.FLOOR || p.kind == PieceKind.RUIN) {
                h += B.CORNICE
                break
            }
            var cx = street.centers[i]
            val lean = SceneLight.lean(light, (cx - lightX) / lightSpan)
            var halfW = B.W / 2f
            val sbl = SpriteArt.buildings[l.stackId]
            val house = sbl?.house
            val model = sbl?.model
            if (house != null || model != null) {
                // a house, villa or landmark back in its yard: its narrower, lower block of shade ([houseShade])
                if (!(if (house != null) houseShade(house.shape, light, lean) else modelShade(model!!.shape, light, lean))) continue
                h = houseBlock[2]; cx += houseBlock[3] + (houseBlock[0] + houseBlock[1]) / 2f; halfW = (houseBlock[1] - houseBlock[0]) / 2f
            }
            if (up > h + 20f) continue
            // the facade point at height `up` whose shadow falls at x: x = its x + lean * up
            val dx = abs(x - lean * up - cx)
            val pen = (PEN_BASE + PEN_RISE * up) * soft
            val across = Px.smooth(halfW + pen, halfW - pen, dx)
            if (across <= 0f) continue
            val along = Px.smooth(h + pen, h - pen, up)
            deepest = max(deepest, across * along)
        }
        return deepest * (if (light.cool > .5f) MOON_SHADE else SUN_SHADE) * light.strength
    }

    fun DrawScope.shadows(
        light: KeyLight,
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        street: StreetLayout,
        from: Float,
        to: Float,
        depth: Float,
        seconds: Float,
    ) {
        shadeLight = light
        shadeLayouts = layouts
        shadeStreet = street
        if (light.strength < .02f) return
        val tint = shade(light, sky)
        shadowPaint.alpha = (if (light.cool > .5f) MOON_SHADE else SUN_SHADE) * light.strength
        drawIntoCanvas { it.saveLayer(Rect(from, 0f, to, depth), shadowPaint) }
        val half = B.W / 2f
        // how far a shadow leans from what throws it (per unit of height), to know what is in view
        val lean = abs(light.lean) + SceneLight.SPLAY * 1.5f
        for (i in layouts.indices) {
            val l = layouts[i]
            val left = street.lefts[i]
            var h = l.roof
            for (p in l.pieces) if (p.kind == PieceKind.FLOOR || p.kind == PieceKind.RUIN) {
                h += B.CORNICE
                break
            }
            val spread = lean * h + 80f
            if (left + l.lot + spread < from || left - spread > to) continue
            val cx = street.centers[i]
            val sbl = SpriteArt.buildings[l.stackId]
            val house = sbl?.house
            val model = sbl?.model
            if (house != null || model != null) {
                // a house, villa or landmark back in its yard: the shade that reaches past its yard ([houseShade])
                val lean = SceneLight.lean(light, (cx - lightX) / lightSpan)
                if (if (house != null) houseShade(house.shape, light, lean) else modelShade(model!!.shape, light, lean)) {
                    val l0 = houseBlock[0]; val r0 = houseBlock[1]; val hh = houseBlock[2]
                    withTransform({
                        translate(cx + houseBlock[3], 0f)
                        transform(fallMatrix(light, cx))
                    }) {
                        softBlock(tint, l0, r0, hh, light.strength)
                    }
                }
                continue
            }
            // the block of shade is the building's own width, its front's: the side face shows only
            // because of where the camera stands (it runs back from the street, so its shade falls
            // in the gap behind, not on the pavement), and shading it too made the light between
            // two buildings narrower than the gap
            withTransform({
                translate(cx, 0f)
                transform(fallMatrix(light, cx))
            }) {
                softBlock(tint, -B.W / 2f, B.W / 2f, h, light.strength)
            }
            for (t in l.trees) treeSilhouette(cx + t.x, t.id.hashCode(), seconds, .8f, tint, light)
        }
        val near = lean * 100f + 60f
        for (k in street.furniture.indices) {
            val f = street.furniture[k]
            if (f.x + near < from || f.x - near > to) continue
            // from the foot where the piece is drawn ([standAt]), so the shadow stays on it
            val fx = standAt(f.x)
            if (SpriteArt.lamp != null) {
                withTransform({
                    translate(fx, 0f)
                    transform(fallMatrix(light, f.x))
                }) { propPicture(f, seconds, shadeFilter(tint)) }
                continue
            }
            when (f.kind) {
                FurnitureKind.TREE -> treeSilhouette(fx, f.seed, seconds, 1f, tint, light)
                FurnitureKind.LAMP ->
                    withTransform({
                        translate(fx, 0f)
                        transform(fallMatrix(light, f.x))
                    }) {
                        drawRect(tint, Offset(-4.4f, -7.6f), Size(8.8f, 7.6f))
                        drawPath(lampPole, tint)
                        drawPath(lampArm, tint, style = armStroke)
                        drawPath(lampHood, tint)
                        drawRect(tint, Offset(6f, -91.8f), Size(11f, 2.6f))
                    }
                FurnitureKind.BENCH ->
                    withTransform({
                        translate(fx, 0f)
                        transform(fallMatrix(light, f.x))
                    }) {
                        drawRect(tint, Offset(-13f, -12.2f), Size(1.8f, 12.2f))
                        drawRect(tint, Offset(11.2f, -12.2f), Size(1.8f, 12.2f))
                        drawRect(tint, Offset(-14f, -9f), Size(28f, 1.6f))
                        drawRect(tint, Offset(-14f, -18.9f), Size(28f, 6.9f))
                    }
            }
        }
        streetLife?.let { with(it) { keyShadows(light, shadeFilter(tint), from, to) } }
        drawIntoCanvas { it.restore() }
    }

    /**
     * A building's block of shade from [l] to [r], [h] high, with soft sides: the sun is a disc,
     * not a point, so a shadow's edge is a blur (its penumbra) that widens the further it falls
     * from what throws it, and wider still as haze or cloud soften the light ([strength] 1 for a
     * clear sky). Drawn in bands up the caster, each its own width of blur, so the blur grows along
     * the shadow; the far end fades out the same way.
     */
    private fun DrawScope.softBlock(tint: Color, l: Float, r: Float, h: Float, strength: Float) {
        val ramp = SceneSprites.light.ramp
        val filter = shadeFilter(tint)
        val n = LightSprites.RAMP.toFloat()
        val soft = 1f + 1.6f * (1f - Px.clamp01(strength))
        val bands = SHADE_BANDS
        for (k in 0 until bands) {
            val y1 = -h * k / bands
            val y0 = -h * (k + 1) / bands
            val pen =
                min(
                    (PEN_BASE + PEN_RISE * h * (k + .5f) / bands) * soft,
                    min((r - l) / 2f, (y1 - y0) * .45f),
                )
            // the far end of the shadow fades as its sides do
            val top = k == bands - 1
            val y0s = if (top) y0 + pen else y0
            drawRect(tint, Offset(l + pen, y0s), Size(r - l - 2f * pen, y1 - y0s))
            for (side in 0..1) {
                val x = if (side == 0) l - pen else r + pen
                withTransform({
                    translate(x, y0s)
                    scale((if (side == 0) 2f else -2f) * pen / n, y1 - y0s, Offset.Zero)
                }) {
                    drawImage(ramp, Offset.Zero, colorFilter = filter)
                }
            }
            if (top)
                withTransform({
                    translate(l + pen, y0 - pen)
                    rotate(90f, Offset.Zero)
                    scale(2f * pen / n, -(r - l - 2f * pen), Offset.Zero)
                }) {
                    drawImage(ramp, Offset.Zero, colorFilter = filter)
                }
        }
    }

    /**
     * A piece of furniture's shape at its own place as a flat silhouette in [color], [alpha]
     * strong, with [blend]: what the street's other lights (a passing car's headlights) throw as
     * its shadow under their own projection. A lamp is its post, arm, hood and lens; a tree its
     * sprite under the wind's lean; a bench nothing.
     */
    fun DrawScope.caster(
        f: Furniture,
        seconds: Float,
        color: Color,
        blend: BlendMode,
        alpha: Float,
    ) {
        when (f.kind) {
            FurnitureKind.LAMP ->
                translate(standAt(f.x), 0f) {
                    drawPath(lampPole, color, alpha = alpha, blendMode = blend)
                    drawPath(lampArm, color, alpha = alpha, style = armStroke, blendMode = blend)
                    drawPath(lampHood, color, alpha = alpha, blendMode = blend)
                    drawRect(
                        color,
                        Offset(6f, -91.8f),
                        Size(11f, 2.6f),
                        alpha = alpha,
                        blendMode = blend,
                    )
                }
            FurnitureKind.TREE -> {
                val images = SceneSprites.trees[Trees.variant(f.seed)]
                val sp = images.sprite
                val lean = if (seconds > 0f) Ambient.sway(f.seed, seconds) else 0f
                shear.reset()
                shear[0, 1] = -tan(lean * (PI.toFloat() / 180f))
                val u = 1f / sp.ppu
                withTransform({
                    translate(standAt(f.x), 0f)
                    scale(u * -lightFrom, u, Offset.Zero)
                    transform(shear)
                }) {
                    drawImage(
                        images.looks[0],
                        Offset(-sp.footX, -sp.footY),
                        alpha = alpha,
                        colorFilter = if (color == Color.Black) blackFilter else shadeFilter(color),
                        blendMode = blend,
                    )
                }
            }
            FurnitureKind.BENCH -> {}
        }
    }

    /**
     * A tree's shape in the shade, fallen along the light from its foot at [x]: the same sprite
     * under the same lean, so the shadow sways with the tree. Drawn three times, each a little
     * larger about the foot and fainter, so the shadow is sharp at the trunk and soft at the crown,
     * as a shadow is further from what throws it (the penumbra).
     */
    private fun DrawScope.treeSilhouette(
        x: Float,
        seed: Int,
        seconds: Float,
        size: Float,
        tint: Color,
        light: KeyLight,
    ) {
        val variant = Trees.variant(seed)
        val images = SceneSprites.trees[variant]
        val sp = images.sprite
        val lean = if (seconds > 0f) Ambient.sway(seed, seconds) else 0f
        shear.reset()
        shear[0, 1] = -tan(lean * (PI.toFloat() / 180f))
        val u = size / sp.ppu
        val filter = shadeFilter(tint)
        val at = Offset(-sp.footX, -sp.footY)
        // the season's shape: the bare tree, and the canopy as full as its leaves are
        TreeDress.plan(season, variant, dress)
        val bare = dress[TreeSeason.BARE.ordinal]
        val canopy = TreeDress.canopy(dress)
        val skeleton =
            if (bare > .01f) SceneSprites.tree(variant, TreeSeason.BARE).looks[0] else null
        withTransform({
            translate(x, 0f)
            transform(fallMatrix(light, x))
            scale(u * -lightFrom, u, Offset.Zero)
            transform(shear)
        }) {
            for (k in PENUMBRA.indices) {
                val grow = 1f + .045f * k
                scale(grow, grow, Offset.Zero) {
                    if (skeleton != null)
                        drawImage(skeleton, at, alpha = PENUMBRA[k] * bare, colorFilter = filter)
                    if (canopy > .01f)
                        drawImage(
                            images.looks[0],
                            at,
                            alpha = PENUMBRA[k] * canopy,
                            colorFilter = filter,
                        )
                }
            }
        }
    }

    // ------------------------------------------------------------------ the weather on the street

    /** The weather of the frame ([WeatherState]), set by the stage with the light. */
    var weather: WeatherState = WeatherState.CLEAR

    /** The layer the wet street's reflections are drawn in, at the wet's own strength. */
    private val reflectPaint = Paint()

    /**
     * How much of the reflection is left down the pavement and the road: all of it at the wall,
     * most at the kerb, a quarter well out on the road (the lamps' light runs long there), none by
     * [REFLECT_DEPTH] (a mask, kept in the layer's alpha).
     */
    private val reflectMask =
        Brush.verticalGradient(
            0f to Color.Black,
            PAVEMENT / REFLECT_DEPTH to Color.Black.copy(alpha = .6f),
            .6f to Color.Black.copy(alpha = .25f),
            1f to Color.Transparent,
            startY = 0f,
            endY = REFLECT_DEPTH,
        )

    /** A lamp's light drawn long down the wet road: a unit-tall streak, soft at both ends. */
    private val lampStreak =
        Brush.verticalGradient(
            0f to SceneInk.LampLight.copy(alpha = .15f),
            .3f to SceneInk.LampLight.copy(alpha = .8f),
            1f to Color.Transparent,
            startY = 0f,
            endY = 1f,
        )

    /** The trees' reflections are a dark shape in the wet, through this. */
    private val reflectFilter = ColorFilter.tint(TreeReflect, BlendMode.SrcIn)
    private val ringStroke = Stroke(.55f)

    // the rain's and the puddles' tints follow the sky; made when it changes
    private var weatherSky: SkyColors? = null
    private var rainFilter: ColorFilter? = null
    private var rainLampFilter = ColorFilter.tint(SceneInk.LampLight, BlendMode.SrcIn)
    private var puddleFilter: ColorFilter? = null
    private var splashFilter: ColorFilter? = null
    private var rimFilter: ColorFilter? = null

    private fun weatherTints(sky: SkyColors) {
        if (weatherSky === sky) return
        val pale = lerp(sky.sky[2], Color.White, .55f)
        rainFilter = ColorFilter.tint(lerp(pale, sky.sky[1], .25f * sky.dark), BlendMode.SrcIn)
        // a puddle holds the sky over it: the zenith's colour, deepened a little
        puddleFilter =
            ColorFilter.tint(lerp(sky.sky[1], sky.sky[0], .3f + .3f * sky.dark), BlendMode.SrcIn)
        splashFilter = ColorFilter.tint(lerp(pale, Color.White, .4f), BlendMode.SrcIn)
        rimFilter = ColorFilter.tint(lerp(Color.White, sky.sky[2], .3f), BlendMode.SrcIn)
        weatherSky = sky
    }

    /**
     * The weather on the street from [from] to [to] (the view reaches [top] above the pavement line
     * and [depth] below it, street units): a wet street (darker, glossier, mirroring what stands
     * over it, with puddles), and rain (streaks at two depths, brighter in the lamps' light,
     * splashing on the pavement and the awnings, dripping off the awnings' edges). Drawn last, over
     * everything on the street. Nothing is made per frame.
     */
    fun DrawScope.weather(
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        arts: List<BuildingArt>,
        street: StreetLayout,
        live: Boolean,
        from: Float,
        to: Float,
        top: Float,
        depth: Float,
        seconds: Float,
    ) {
        val w = weather
        if (w.wet < .01f && w.rain < .01f) return
        weatherTints(sky)
        if (w.wet >= .01f) {
            wet(sky, layouts, arts, street, live, from, to, depth, w.wet, w.rain, seconds)
        }
        if (w.rain >= .01f) rain(sky, arts, street, from, to, top, depth, w.rain, seconds)
    }

    /**
     * The wet street: the pavement and the road darkened and cooled (water in the slabs), a layer
     * of reflections mirrored about the wall line and squashed, fading down the ground (drawn twice
     * a little apart, so the mirror is broken as a wet street breaks it), the slab joints over it,
     * and puddles that hold the sky, ring under the rain and catch the lamps at night.
     */
    private fun DrawScope.wet(
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        arts: List<BuildingArt>,
        street: StreetLayout,
        live: Boolean,
        from: Float,
        to: Float,
        depth: Float,
        wet: Float,
        rain: Float,
        seconds: Float,
    ) {
        val width = to - from
        val dark = sky.dark
        val band = PAVEMENT + 2.5f + min(depth, 160f)
        drawRect(
            WetTint,
            Offset(from, 0f),
            Size(width, band),
            alpha = .6f * wet,
            blendMode = BlendMode.Multiply,
        )
        // the reflections, in one layer at the wet's strength
        reflectPaint.alpha = wet * (.5f + .3f * dark) * mirrorShare
        drawIntoCanvas { it.saveLayer(Rect(from, 0f, to, REFLECT_DEPTH), reflectPaint) }
        // the sky in the wet, everywhere; what stands on the street goes over it
        drawRect(
            lerp(sky.sky[2], sky.sky[1], .4f),
            Offset(from, 0f),
            Size(width, REFLECT_DEPTH),
            alpha = .5f,
        )
        reflections(sky, layouts, arts, street, live, from, to, -RIPPLE, .55f)
        reflections(sky, layouts, arts, street, live, from, to, RIPPLE, .55f)
        drawRect(
            reflectMask,
            Offset(from, 0f),
            Size(width, REFLECT_DEPTH),
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
        // the slab joints break the mirror
        var x = kotlin.math.floor(from / 24f) * 24f
        while (x < to) {
            drawLine(sky.line, Offset(x, 0f), Offset(x - 5f, PAVEMENT), .8f, alpha = wet)
            x += 24f
        }
        puddles(sky, from, to, wet, rain, seconds)
        sheenLamps = street.furniture.filter { it.kind == FurnitureKind.LAMP }
        groundFx(sky, from, to, depth, wet, rain, seconds)
    }

    /** On the GPU ([RainFx.GROUND]): the puddles that hold the sky and the rain's rings across the whole wet ground. */
    private fun DrawScope.groundFx(sky: SkyColors, from: Float, to: Float, depth: Float, wet: Float, rain: Float, seconds: Float) {
        val fx = StageFx.get(RainFx.GROUND) ?: return
        val c = sky.sky.getOrElse(1) { sky.sky.last() }
        fx.set("time", seconds); fx.set("rain", rain); fx.set("wet", wet); fx.set("px", SpriteArt.PIXEL)
        fx.set("sky", c.red, c.green, c.blue)
        drawRect(fx.brush(), Offset(from, 1f), Size(to - from, depth))
        // at night the lamps' light lies in long broken streaks on the wet ground
        val night = sky.dark
        val sheen = StageFx.get(RainFx.SHEEN)
        if (sheen != null && night > .15f) {
            val xs = FloatArray(8) { -1e5f }
            var n = 0
            for (f in sheenLamps) if (n < 8 && f.x > from - 80f && f.x < to + 80f) xs[n++] = f.x + com.lifemax.rpg.feature.habits.domain.NearLight.LAMP_LENS
            if (n > 0) {
                sheen.set("time", seconds); sheen.set("night", night); sheen.set("wet", wet); sheen.set("rain", rain); sheen.set("px", SpriteArt.PIXEL)
                sheen.set("lampsA", xs[0], xs[1], xs[2], xs[3]); sheen.set("lampsB", xs[4], xs[5], xs[6], xs[7])
                sheen.set("warm", 1f, .78f, .45f)
                drawRect(sheen.brush(), Offset(from, 1f), Size(to - from, depth), blendMode = BlendMode.Plus)
            }
        }
    }

    /** The street lamps, for their reflections on the wet ground (set with the street's furniture). */
    private var sheenLamps: List<Furniture> = emptyList()

    /**
     * How much of building i is left to mirror in the wet (null: all of every one): a building fading out between the
     * camera and the corner mid-turn takes its reflection with it.
     */
    var mirrored: ((Int) -> Float)? = null

    /**
     * How much of the wet street's mirror is left (1 at rest): the reflections are laid in the street's own flat frame,
     * so as a turn round the corner takes the street out of that look they fade with it rather than slide under the
     * wrong buildings.
     */
    var mirrorShare = 1f

    /** On the GPU ([RainFx.GLASS]): drops on the window glass in [x], [y], [w], [h] while it rains. */
    private fun DrawScope.glassRain(x: Float, y: Float, w: Float, h: Float, seconds: Float, sky: SkyColors) {
        val rain = weather.rain
        if (rain < .02f) return
        val fx = StageFx.get(RainFx.GLASS) ?: return
        val c = sky.sky.getOrElse(1) { sky.sky.last() }
        fx.set("time", seconds); fx.set("rain", rain); fx.set("px", SpriteArt.PIXEL)
        fx.set("sky", c.red, c.green, c.blue)
        drawRect(fx.brush(), Offset(x, y), Size(w, h))
    }

    /** What stands on the street, upside down in the wet, [ox] to the side at [a]. */
    /** Our buildings' colours as the wet pavement mirrors them: darker and cooler. */
    private val wetMirror = ColorFilter.lighting(Color(0xFF6E7890), Color.Black)

    private fun DrawScope.reflections(
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        arts: List<BuildingArt>,
        street: StreetLayout,
        live: Boolean,
        from: Float,
        to: Float,
        ox: Float,
        strength: Float,
    ) {
        val dark = sky.dark
        val half = B.W / 2f
        val lit = lerp(SceneInk.WindowLitDay, SceneInk.WindowLit, dark)
        val shop = lerp(SceneInk.Shop, SceneInk.ShopLit, .15f + .8f * dark)
        val lobbyTop = -B.LOBBY_H
        val shown = mirrored
        for (i in layouts.indices) {
            val cx = street.centers[i]
            // (a building wider than a shop, the school, by its own width)
            val reach = max(half, layouts[i].wide / 2f)
            if (cx + reach + B.DX + B.PARK_W < from || cx - reach - B.DX > to) continue
            val left = shown?.invoke(i) ?: 1f
            if (left <= 0f) continue
            val a = strength * left
            val art = arts[i]
            val s = shiftAt(cx)
            val l0 = Perspective.left(s)
            val sbr = SpriteArt.buildings[art.stack.id]
            if (sbr != null) {
                // one of OUR buildings, mirrored in the wet: its own front, darkened and cooled, and its lit glass at night
                val house = sbr.house
                withTransform({
                    translate(cx + ox, 0f)
                    scale(1f, -SQUASH, Offset.Zero)
                }) {
                    val k = B.W / SpriteArt.SRC_W
                    val hf = sbr.front.height * k
                    if (house != null) {
                        // a house: its own front at its own width, where it stands back in its yard, and its roof's
                        // near slope over it. The wet mirrors it about its own foot, which stands a yard's depth up the
                        // screen from the pavement line (a shop's), so its image starts that much higher: undo this
                        // frame's squash for that lift
                        val sh = house.shape
                        val x0 = -half + sh.frontX * k + s * sh.setback / HouseView.DEPTH
                        val w = sbr.front.width * k
                        val y0 = -hf + B.DY * sh.setback / HouseView.DEPTH / SQUASH
                        val rh = house.slope.height * k
                        picture2(house.slope, x0, y0 - rh, w, rh, FilterQuality.None, alpha = a * .7f, filter = wetMirror)
                        picture2(sbr.front, x0, y0, w, hf, FilterQuality.None, alpha = a * .8f, filter = wetMirror)
                        if (dark > .2f) picture2(sbr.emis, x0, y0, w, hf, FilterQuality.None, alpha = a * .45f * dark, blend = BlendMode.Plus)
                        return@withTransform
                    }
                    val model = sbr.model
                    if (model != null) {
                        // a villa or a landmark: its front where it stands back in its yard, mirrored about its foot
                        val sh = model.shape
                        val x0 = -half + (sh.frontX + sh.frontPicX) * k + s * sh.setback / HouseView.DEPTH
                        val w = sbr.front.width * k
                        val y0 = -hf + B.DY * sh.setback / HouseView.DEPTH / SQUASH
                        picture2(sbr.front, x0, y0, w, hf, FilterQuality.None, alpha = a * .8f, filter = wetMirror)
                        if (dark > .2f) picture2(sbr.emis, x0, y0, w, hf, FilterQuality.None, alpha = a * .45f * dark, blend = BlendMode.Plus)
                        return@withTransform
                    }
                    picture2(sbr.front, -half, -hf, B.W, hf, FilterQuality.None, alpha = a * .8f, filter = wetMirror)
                    if (dark > .2f) picture2(sbr.emis, -half, -hf, B.W, hf, FilterQuality.None, alpha = a * .45f * dark, blend = BlendMode.Plus)
                }
                continue
            }
            withTransform({
                translate(cx + ox, 0f)
                scale(1f, -SQUASH, Offset.Zero)
            }) {
                // the lobby's dark wall (with its shown side), its lit shopfront, the door, the
                // awning, the sign
                drawRect(
                    SceneInk.LobbyWallLow,
                    Offset(l0, lobbyTop),
                    Size(Perspective.right(s) - l0, B.LOBBY_H),
                    alpha = a * .85f,
                )
                val w0 = lobbyTop + 35f
                drawRect(shop, Offset(-half + 9f, w0), Size(half - 22f, -4f - w0), alpha = a)
                drawRect(shop, Offset(13f, w0), Size(half - 22f, -4f - w0), alpha = a)
                drawRect(SceneInk.Door, Offset(-9f, w0 - 2f), Size(18f, -w0 + 2f), alpha = a)
                drawRect(
                    art.awning,
                    Offset(-half + 6f, lobbyTop + 21f),
                    Size(B.W - 12f, 9f),
                    alpha = a * .8f,
                )
                drawRect(
                    SignReflect,
                    Offset(-half + 10f, lobbyTop + 4f),
                    Size(B.W - 20f, 15f),
                    alpha = a * .7f,
                )
                if (dark > .2f) {
                    // the sign's glow and the shopfront's light spill into the wet too
                    translate(0f, lobbyTop + 11.5f) {
                        scale(B.W * .5f, 16f, Offset.Zero) {
                            drawCircle(
                                bloom,
                                1f,
                                Offset.Zero,
                                alpha = a * .6f * dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    translate(0f, w0 + 8f) {
                        scale(half * .9f, 14f, Offset.Zero) {
                            drawCircle(
                                bloom,
                                1f,
                                Offset.Zero,
                                alpha = a * .5f * dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                }
                // the first storey's lit windows, if it is lit
                val fa = art.floors.firstOrNull()
                if (fa != null && isLit(fa, live)) {
                    val on = if (live) fa.occupancy.lit else fa.windowCount
                    val win = fa.windows
                    for (k in 0 until min(on, fa.windowCount)) {
                        drawRect(
                            lit,
                            Offset(win[k * 4], fa.top + win[k * 4 + 1]),
                            Size(win[k * 4 + 2], win[k * 4 + 3]),
                            alpha = a * .9f,
                        )
                    }
                }
            }
            // the park's trees, dark in the wet
            for (t in art.layout.trees) treeReflection(cx + t.x + ox, t.id.hashCode(), .8f, a)
        }
        val a = strength
        for (k in street.furniture.indices) {
            val f = street.furniture[k]
            if (f.x < from - 40f || f.x > to + 40f) continue
            val fx = standAt(f.x)
            when (f.kind) {
                FurnitureKind.TREE -> treeReflection(fx + ox, f.seed, 1f, a)
                FurnitureKind.LAMP -> {
                    withTransform({
                        translate(fx + ox, 0f)
                        scale(1f, -SQUASH, Offset.Zero)
                    }) {
                        drawPath(lampPole, SceneInk.Lamp, alpha = a * .6f)
                        if (dark > .2f) {
                            drawRect(
                                SceneInk.LampLight,
                                Offset(6f, -91.8f),
                                Size(11f, 2.6f),
                                alpha = a * dark,
                            )
                            translate(11.5f, -89.5f) {
                                scale(26f, 26f, Offset.Zero) {
                                    drawCircle(
                                        halo,
                                        1f,
                                        Offset.Zero,
                                        alpha = a * dark,
                                        blendMode = BlendMode.Plus,
                                    )
                                }
                            }
                        }
                    }
                    if (dark > .2f)
                    // the lens's light runs long down the road
                    withTransform({
                            translate(fx + 11.5f + ox, 0f)
                            scale(6f, 91.8f * SQUASH, Offset.Zero)
                        }) {
                            drawRect(
                                lampStreak,
                                Offset(-1f, 0f),
                                Size(2f, 1f),
                                alpha = a * dark * .9f,
                                blendMode = BlendMode.Plus,
                            )
                        }
                }
                FurnitureKind.BENCH ->
                    withTransform({
                        translate(fx + ox, 0f)
                        scale(1f, -SQUASH, Offset.Zero)
                    }) {
                        drawRect(
                            SceneInk.Lamp,
                            Offset(-14f, -18.6f),
                            Size(28f, 6.4f),
                            alpha = a * .5f,
                        )
                        drawRect(
                            SceneInk.Bench,
                            Offset(-14f, -9.6f),
                            Size(28f, 4.3f),
                            alpha = a * .5f,
                        )
                    }
            }
        }
    }

    /**
     * A tree's dark shape in the wet under its foot at [x] (no sway: the water is still enough).
     */
    private fun DrawScope.treeReflection(x: Float, seed: Int, size: Float, a: Float) {
        val images = SceneSprites.trees[Trees.variant(seed)]
        val sp = images.sprite
        val u = size / sp.ppu
        withTransform({
            translate(x, 0f)
            scale(u * -lightFrom, -u * SQUASH, Offset.Zero)
        }) {
            drawImage(
                images.looks[0],
                Offset(-sp.footX, -sp.footY),
                alpha = a * .55f,
                colorFilter = reflectFilter,
            )
        }
    }

    /**
     * The puddles: three to a [PUDDLE_TILE] of the street, where the slabs dip (the same places
     * every day), each a painted pool holding the sky with a bright rim on its far edge; they come
     * as the street wets and go as it dries. Under the rain rings spread on them; at night the
     * lamps shine in them.
     */
    private fun DrawScope.puddles(
        sky: SkyColors,
        from: Float,
        to: Float,
        wet: Float,
        rain: Float,
        seconds: Float,
    ) {
        val show = Px.smooth(.12f, .6f, wet)
        if (show <= 0f) return
        val images = SceneSprites.weather.puddles
        val dark = sky.dark
        val px = 1f / WeatherSprites.PX_PER_UNIT
        var tile = kotlin.math.floor(from / PUDDLE_TILE).toInt()
        val last = kotlin.math.floor(to / PUDDLE_TILE).toInt()
        while (tile <= last) {
            for (k in 0 until 3) {
                val onRoad = k == 2
                val x = tile * PUDDLE_TILE + PUDDLE_TILE * Ambient.hash(tile, k, 21)
                val y =
                    if (onRoad) PAVEMENT + 7f + 16f * Ambient.hash(tile, k, 22)
                    else 3.5f + 9f * Ambient.hash(tile, k, 22)
                val sx = (.55f + .6f * Ambient.hash(tile, k, 23)) * (if (onRoad) 1.3f else 1f)
                val sy = (.4f + .25f * Ambient.hash(tile, k, 24)) * (if (onRoad) 1.2f else 1f)
                val variant =
                    (Ambient.hash(tile, k, 25) * WeatherSprites.PUDDLES)
                        .toInt()
                        .coerceIn(0, WeatherSprites.PUDDLES - 1)
                val img = images[variant]
                val pw = WeatherSprites.PUDDLE_W * px * sx
                val ph = WeatherSprites.PUDDLE_H * px * sy
                val alpha = show * (if (onRoad) .75f else .9f)
                withTransform({
                    translate(x, y)
                    scale(px * sx, px * sy, Offset.Zero)
                }) {
                    val at = Offset(-WeatherSprites.PUDDLE_W / 2f, -WeatherSprites.PUDDLE_H / 2f)
                    drawImage(img.fill, at, alpha = alpha, colorFilter = puddleFilter)
                    drawImage(
                        img.rim,
                        at,
                        alpha = alpha * (.45f + .3f * (1f - dark)),
                        colorFilter = rimFilter,
                    )
                }
                // the nearest lamp shines in it at night
                if (dark > .2f) {
                    for (i in lamps.indices) {
                        val f = lamps[i]
                        if (f.kind != FurnitureKind.LAMP) continue
                        val dist = abs(f.x + 11.5f - x)
                        if (dist > 60f) continue
                        translate(x, y) {
                            scale(pw * .32f, ph * .45f, Offset.Zero) {
                                drawCircle(
                                    halo,
                                    1f,
                                    Offset.Zero,
                                    alpha = alpha * dark * (1f - dist / 60f),
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                    }
                }
                // the rain's rings
                if (rain > .02f) {
                    for (r in 0 until 2) {
                        val t = frac(seconds * (1.1f + .3f * r) + Ambient.hash(tile, k, 30 + r))
                        val rx = pw * .5f * t
                        val ry = ph * .5f * t
                        drawOval(
                            Color.White,
                            Offset(
                                x - rx + pw * (Ambient.hash(tile, k, 40 + r) - .5f) * .4f,
                                y - ry,
                            ),
                            Size(rx * 2f, ry * 2f),
                            alpha = (1f - t) * rain * .55f * alpha,
                            style = ringStroke,
                        )
                    }
                }
            }
            tile++
        }
    }

    /**
     * The rain: two tiles of streaks ([WeatherSprites.streaks]) scrolled down the picture, a far
     * one small and faint and a near one large and bright (the canvas is moved under a repeating
     * brush, nothing is made); brighter again in the lamps' cones at night; splashes on the
     * pavement and the awnings, a few at a time; drips off the awnings' edges.
     */
    private fun DrawScope.rain(
        sky: SkyColors,
        arts: List<BuildingArt>,
        street: StreetLayout,
        from: Float,
        to: Float,
        top: Float,
        depth: Float,
        rain: Float,
        seconds: Float,
    ) {
        val dark = sky.dark
        val images = SceneSprites.weather
        val half = B.W / 2f
        // the awnings: splashes along their tops and drips off their edges
        val a0 = -B.LOBBY_H + 21f
        val edge = a0 + 16.4f
        val px = 1f / WeatherSprites.PX_PER_UNIT
        val drops = (2 + (rain * 3f).toInt())
        for (i in arts.indices) {
            val cx = street.centers[i]
            if (cx + half < from || cx - half > to) continue
            val seed = arts[i].stack.id.hashCode()
            for (k in 0 until drops) {
                val t =
                    frac(
                        seconds * (.9f + .25f * Ambient.hash(seed, k, 50)) +
                            Ambient.hash(seed, k, 51)
                    )
                val x = cx - half + 10f + (B.W - 20f) * Ambient.hash(seed, k, 52)
                val fall = t * t
                val y = edge + (0f - edge) * fall
                drawLine(
                    Color.White,
                    Offset(x, y - 1.4f - 2.5f * fall),
                    Offset(x, y),
                    .7f,
                    cap = StrokeCap.Round,
                    alpha = rain * (.7f - .3f * t),
                )
            }
            splashes(
                images,
                seed + 7,
                cx - half + 8f,
                cx + half - 8f,
                a0 + .5f,
                a0 + 2f,
                rain,
                seconds,
                3,
                px,
            )
        }
        // splashes on the pavement, by stretch of street
        var tile = kotlin.math.floor(from / PUDDLE_TILE).toInt()
        val last = kotlin.math.floor(to / PUDDLE_TILE).toInt()
        while (tile <= last) {
            splashes(
                images,
                tile * 31 + 5,
                tile * PUDDLE_TILE,
                (tile + 1) * PUDDLE_TILE,
                1.5f,
                PAVEMENT - 1f,
                rain,
                seconds,
                6,
                px,
            )
            tile++
        }
        // the streaks: far, then near, over the whole view
        streaks(
            images,
            from,
            to,
            top,
            depth,
            .5f,
            150f,
            rain * .4f,
            seconds,
            rainFilter,
            BlendMode.SrcOver,
        )
        streaks(
            images,
            from,
            to,
            top,
            depth,
            1f,
            240f,
            rain * .65f,
            seconds,
            rainFilter,
            BlendMode.SrcOver,
        )
        // and brighter where a lamp lights them
        if (dark > .2f)
            for (i in lamps.indices) {
                val f = lamps[i]
                if (f.kind != FurnitureKind.LAMP || f.x + 40f < from || f.x - 20f > to) continue
                translate(standAt(f.x), 0f) {
                    clipPath(cone) {
                        streaks(
                            images,
                            -20f,
                            40f,
                            -92f,
                            0f,
                            1f,
                            240f,
                            rain * .8f * dark,
                            seconds,
                            rainLampFilter,
                            BlendMode.Plus,
                        )
                    }
                }
            }
    }

    /** One tile of streaks over the view at [scale], falling [speed] units a second. */
    private fun DrawScope.streaks(
        images: WeatherImages,
        from: Float,
        to: Float,
        top: Float,
        depth: Float,
        scale: Float,
        speed: Float,
        alpha: Float,
        seconds: Float,
        filter: ColorFilter?,
        blend: BlendMode,
    ) {
        val s = scale / WeatherSprites.PX_PER_UNIT
        val tileH = WeatherSprites.STREAK_H * s
        val tileW = WeatherSprites.STREAK_W * s
        val phase = frac(seconds * speed / tileH) * tileH
        val x0 = kotlin.math.floor(from / tileW) * tileW
        withTransform({
            translate(x0 + phase * WeatherSprites.SLANT, top - tileH + phase)
            scale(s, s, Offset.Zero)
        }) {
            drawRect(
                images.rain,
                Offset.Zero,
                Size((to - x0 + tileW) / s, (depth - top + tileH) / s),
                alpha = alpha.coerceIn(0f, 1f),
                colorFilter = filter,
                blendMode = blend,
            )
        }
    }

    /**
     * [slots] splashes between [x0] and [x1], [y0] to [y1] (a stretch of pavement, an awning's
     * top): each slot lives [SPLASH_SECONDS], then lands somewhere else, out of step with the
     * others; how many of the slots show follows the rain.
     */
    private fun DrawScope.splashes(
        images: WeatherImages,
        seed: Int,
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        rain: Float,
        seconds: Float,
        slots: Int,
        px: Float,
    ) {
        val n = (slots * (.3f + .7f * rain) + .99f).toInt().coerceAtMost(slots)
        for (k in 0 until n) {
            val life = seconds / SPLASH_SECONDS + Ambient.hash(seed, k, 60)
            val turn = kotlin.math.floor(life).toInt()
            val t = life - turn
            val x = x0 + (x1 - x0) * Ambient.hash(seed + turn * 13, k, 61)
            val y = y0 + (y1 - y0) * Ambient.hash(seed + turn * 13, k, 62)
            val frame =
                (t * WeatherSprites.SPLASH_FRAMES)
                    .toInt()
                    .coerceIn(0, WeatherSprites.SPLASH_FRAMES - 1)
            val size = px * (.7f + .5f * Ambient.hash(seed, k, 63))
            withTransform({
                translate(x, y)
                scale(size, size, Offset.Zero)
            }) {
                drawImage(
                    images.splashes[frame],
                    Offset(-WeatherSprites.SPLASH_W / 2f, 3f - WeatherSprites.SPLASH_H),
                    alpha = rain * .85f,
                    colorFilter = splashFilter,
                )
            }
        }
    }

    private fun frac(v: Float): Float = v - kotlin.math.floor(v)

    // ------------------------------------------------------------------ a building

    private var treeFilterKey = Long.MIN_VALUE
    private var treeFilterCache: ColorFilter? = null

    /**
     * Our trees are one summer green; the season dresses them: orange as the leaves turn, pale and frosted under
     * snow, a blush of pink at blossom time, then the night's darkening over it, all in one colour matrix.
     */
    private fun treeColors(): ColorFilter? {
        val a = season.autumn
        val sn = season.snow
        val bl = season.blossom
        val night = if (nightOn) nightInk else Color.White
        val key = ((a * 20).toInt().toLong() shl 40) or ((sn * 20).toInt().toLong() shl 32) or ((bl * 20).toInt().toLong() shl 24) or
            (night.red * 15).toInt().toLong().shl(16) or (night.green * 15).toInt().toLong().shl(8) or (night.blue * 15).toInt().toLong()
        if (key == treeFilterKey) return treeFilterCache
        treeFilterKey = key
        if (a <= .02f && sn <= .02f && bl <= .02f && !nightOn) { treeFilterCache = null; return null }
        val m = ColorMatrix(seasonTreeMatrix(a, sn, bl, night.red, night.green, night.blue))
        treeFilterCache = ColorFilter.colorMatrix(m)
        return treeFilterCache
    }

    /** One of OUR props at the current origin (its foot): a lamp, a tree under the wind, or a bench. */
    private fun DrawScope.propPicture(f: Furniture, seconds: Float, filter: ColorFilter?) {
        when (f.kind) {
            FurnitureKind.LAMP -> SpriteArt.lamp?.let {
                val x0 = -LAMP_FOOT_X * LAMP_K
                val y0 = -LAMP_FOOT_Y * LAMP_K
                val w = it.width * LAMP_K
                val h = it.height * LAMP_K
                val dx = lampHeadDx
                if (dx == 0f) picture2(it, x0, y0, w, h, FilterQuality.None, filter = filter)
                else {
                    // its arm turned away: the arm and the head (the picture right of the pole's bracket) slid in behind
                    // the pole, then the pole and its bracket over them
                    val cut = x0 + LAMP_ARM_X * LAMP_K
                    clipRect(cut + dx, y0 - 1f, x0 + w + dx, 1f) { picture2(it, x0 + dx, y0, w, h, FilterQuality.None, filter = filter) }
                    clipRect(x0 - 1f, y0 - 1f, cut, 1f) { picture2(it, x0, y0, w, h, FilterQuality.None, filter = filter) }
                }
            }
            FurnitureKind.BENCH -> SpriteArt.bench?.let { picture2(it, -BENCH_FOOT_X * BENCH_K, -BENCH_FOOT_Y * BENCH_K, it.width * BENCH_K, it.height * BENCH_K, FilterQuality.None, filter = filter) }
            FurnitureKind.TREE -> {
                val ts = SpriteArt.trees
                if (ts.isEmpty()) return
                val img = ts[((f.seed % ts.size) + ts.size) % ts.size]
                val lean = if (seconds > 0f) Ambient.sway(f.seed, seconds) else 0f
                withTransform({ rotate(lean, Offset.Zero) }) {
                    picture2(img, -TREE_FOOT_X * TREE_K, -TREE_FOOT_Y * TREE_K, img.width * TREE_K, img.height * TREE_K, FilterQuality.None, filter = filter)
                }
            }
        }
    }

    /** The people of one of our buildings' windows: which windows are lit rooms, who is in each, and when. */
    private class SpriteWindows(val lit: IntArray, val cast: Array<WindowCast>, val here: FloatArray)

    private val spriteWindows = HashMap<String, SpriteWindows>()

    private fun windowsOf(id: String, ws: List<FloatArray>): SpriteWindows = spriteWindows.getOrPut(id) {
        val seed = id.hashCode()
        val n = ws.size
        // about seven in ten windows are lit rooms (homes), taken in a stable order of their own
        val on = if (n == 0) 0 else maxOf(1, kotlin.math.ceil(n * .7f).toInt())
        val order = (0 until n).sortedBy { Ambient.hash(seed, it, 301) }.take(on)
        // where each lit window sits among the others, so neighbours are not the same kind of room
        fun rank(v: List<Float>, x: Float): Int = v.distinctBy { (it / 6f).toInt() }.sorted().indexOfFirst { kotlin.math.abs(it - x) < 6f }.coerceAtLeast(0)
        val xs = ws.map { it[0] + it[2] / 2f }
        val ys = ws.map { it[1] + it[3] / 2f }
        val cols = IntArray(on) { rank(xs, xs[order[it]]) }
        val rows = IntArray(on) { rank(ys, ys[order[it]]) }
        val groups = IntArray(on) { (cols[it] + rows[it]) and 1 }
        SpriteWindows(order.toIntArray(), if (on == 0) emptyArray() else Silhouettes.cast("", id, on, groups, cols, rows), FloatArray(on))
    }

    private val spritePath = Path()

    /** A picture drawn at ([x], [y]) [w] by [h] (street units). */
    private fun DrawScope.picture2(img: ImageBitmap, x: Float, y: Float, w: Float, h: Float, quality: FilterQuality, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver, filter: ColorFilter? = null) {
        withTransform({
            translate(x, y)
            scale(w / img.width, h / img.height, Offset.Zero)
        }) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), alpha, Fill, filter, blend, quality)
        }
    }

    /**
     * A side face: the picture laid on a parallelogram hung from ([ox], [oy]) (the front's top corner), running back
     * along ([ux], [uy]) and [h] tall. Drawn as [n] thin vertical strips, each a plain scaled and moved rectangle, so
     * the lean is exact with no skew transform.
     */
    private fun DrawScope.sideStrips(img: ImageBitmap, ox: Float, oy: Float, ux: Float, uy: Float, h: Float, n: Int = 14, alpha: Float = 1f) {
        for (i in 0 until n) {
            // proportional source columns: every column of the picture is used, whatever n divides
            val sx0 = i * img.width / n
            val sw = ((i + 1) * img.width / n - sx0).coerceAtLeast(1)
            val x0 = ox + ux * i / n
            // the strip reaches up to the top edge at its higher end (the edge rises across it), so no sky shows through
            // in the notch between the side and the roof; the roof is drawn after and covers the overlap
            val y0 = oy + uy * i / n + minOf(0f, uy / n)
            val dx = if (ux >= 0f) x0 else x0 + ux / n
            // overlap the neighbour to hide seams, but never past the back edge
            val dw = abs(ux) / n + if (ux < 0f || i < n - 1) .6f else 0f
            val dh = h + abs(uy) / n
            withTransform({
                translate(dx, y0)
                scale(dw / sw, dh / img.height, Offset.Zero)
            }) {
                drawImage(img, IntOffset(sx0, 0), IntSize(sw, img.height), IntOffset.Zero, IntSize(sw, img.height), alpha, Fill, null, BlendMode.SrcOver, FilterQuality.None)
            }
        }
    }

    /** A side picture's windows at night: its glass gone dark ([dark]) and about half its windows lit ([lit]). */
    private class SideNight(val dark: ImageBitmap, val lit: ImageBitmap)

    private val sideNights = HashMap<ImageBitmap, SideNight>()

    /**
     * The night pictures of side picture [img], made from its pixels the first time it is seen at night: its panes are
     * the runs of pale sky-blue glass, each pane its run's bounding box (runs that touch, a white glint splitting one
     * pane, are one pane). The fronts have their windows mapped by the art's tools; the
     * sides only have their pictures, which by day show glass and at night would show day glass in a dark wall.
     */
    private fun sideNight(img: ImageBitmap): SideNight = sideNights.getOrPut(img) {
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.readPixels(px)
        fun glass(c: Int): Boolean {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return b >= 150 && b >= r + 12 && b >= g && r + g + b >= 420
        }
        // the runs of glass, 8-connected, each as a box (x0, y0, x1, y1 inclusive)
        val seen = BooleanArray(w * h)
        val boxes = ArrayList<IntArray>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || !glass(px[start])) continue
            var top = 0
            stack[top++] = start; seen[start] = true
            val bb = intArrayOf(w, h, -1, -1)
            var n = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w; val y = i / w
                n++
                bb[0] = min(bb[0], x); bb[1] = min(bb[1], y); bb[2] = max(bb[2], x); bb[3] = max(bb[3], y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!seen[j] && glass(px[j])) { seen[j] = true; stack[top++] = j }
                }
            }
            if (n >= 3) boxes += bb
        }
        // runs whose boxes touch are one pane
        var merged = true
        while (merged) {
            merged = false
            loop@ for (a in boxes.indices) for (b in a + 1 until boxes.size) {
                val p = boxes[a]; val q = boxes[b]
                if (p[0] <= q[2] + 1 && q[0] <= p[2] + 1 && p[1] <= q[3] + 1 && q[1] <= p[3] + 1) {
                    p[0] = min(p[0], q[0]); p[1] = min(p[1], q[1]); p[2] = max(p[2], q[2]); p[3] = max(p[3], q[3])
                    boxes.removeAt(b); merged = true; break@loop
                }
            }
        }
        val dark = IntArray(w * h)
        val lit = IntArray(w * h)
        val glassNight = SceneInk.GlassNight.toArgb()
        for ((k, bb) in boxes.withIndex()) {
            val bw = bb[2] - bb[0] + 1
            val bh = bb[3] - bb[1] + 1
            // (a stray pixel or a thin line of blue paint is not a window)
            if (bw < 3 || bh < 3 || bw * bh < 12) continue
            val hash = Ambient.hash(w * 7 + h, bb[0] * 31 + bb[1], 523)
            val on = hash < .55f
            val bright = .6f + .3f * Ambient.hash(bb[0], bb[1] * 17 + w, 524)
            // (the whole pane, glints and the odd half-blue pixel at its edge too)
            for (y in bb[1]..bb[3]) for (x in bb[0]..bb[2]) {
                val i = y * w + x
                dark[i] = glassNight
                if (on) {
                    // a room's warm light seen from the street at a slant: dimmer under the lintel, brightest low down
                    val v = bright * (.62f + .38f * (y - bb[1]) / max(1, bh - 1).toFloat())
                    lit[i] = Px.argbF(1f, v, v * .78f, v * .46f)
                }
            }
        }
        SideNight(PlatformImages.create(dark, w, h, false), PlatformImages.create(lit, w, h, false))
    }

    /** The building [prepare]d last: how far its side windows are lit (the night's dark and its lights' hour). */
    private fun sideLights(): Float = (inkDark * Dusk.lightsOn(sideSeed, hour)).coerceIn(0f, 1f)

    /**
     * A roof face: the picture (seen from above, its top the far edge) on the parallelogram whose front edge runs
     * from ([x], [y]) across [w] and whose back edge is [dy] higher and [shift] to the side. Drawn as [n] thin
     * horizontal strips, each a plain scaled and moved rectangle.
     */
    private fun DrawScope.roofStrips(img: ImageBitmap, x: Float, y: Float, w: Float, dy: Float, shift: Float, n: Int = 8) {
        for (j in 0 until n) {
            val sy0 = j * img.height / n
            val sh = ((j + 1) * img.height / n - sy0).coerceAtLeast(1)
            // j = 0 is the far (back) strip
            val t = (j + 1f) / n
            val yy = y - dy * (1f - (j + 1f) / n)
            // each strip covers the whole step to the next one's offset, so the roof's sloped edge has no staircase gaps
            val xx = x + minOf(shift * (1f - t), shift * (1f - j.toFloat() / n))
            val ww = w + abs(shift) / n
            withTransform({
                translate(xx, yy - dy / n)
                scale(ww / img.width, (dy / n + .6f) / sh, Offset.Zero)
            }) {
                drawImage(img, IntOffset(0, sy0), IntSize(img.width, sh), IntOffset.Zero, IntSize(img.width, sh), 1f, Fill, null, BlendMode.SrcOver, FilterQuality.None)
            }
        }
    }

    /**
     * A lighting map's channel [ch] as the alpha of [tint], times [k]. (The strength has to be in the filter: a paint's
     * alpha is applied before its colour filter, and this filter makes its alpha from the map alone.)
     */
    private fun channel(ch: Int, tint: Color, k: Float): ColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, tint.red * 255f,
        0f, 0f, 0f, 0f, tint.green * 255f,
        0f, 0f, 0f, 0f, tint.blue * 255f,
        if (ch == 0) k else 0f, if (ch == 1) k else 0f, if (ch == 2) k else 0f, if (ch == 3) k else 0f, 0f,
    )))
    private val reliefCache = HashMap<Long, ColorFilter>()
    private fun channelCached(ch: Int, tint: Color, k: Float): ColorFilter {
        // tints change slowly with the hour, strengths with the light: keyed by the channel, the tint to 1/32, the strength to 1/64
        val q = (k.coerceIn(0f, 1f) * 63).toLong()
        val key = (ch.toLong() shl 28) or (q shl 16) or ((tint.red * 31).toLong() shl 10) or ((tint.green * 31).toLong() shl 5) or (tint.blue * 31).toLong()
        return reliefCache.getOrPut(key) { if (reliefCache.size > 2048) reliefCache.clear(); channel(ch, tint, q / 63f) }
    }

    /**
     * The front's own relief in the light ([SpriteBuilding.lit], [SpriteBuilding.shade]): what stands out catches the sun
     * on its top and its sunny side and is dark below and on the far side, by the sun's side ([sunSide]) and how direct it
     * is; recesses (window reveals, doorways, under ledges) are darker at every hour.
     */
    private fun DrawScope.relief(sb: SpriteBuilding, half: Float, hf: Float) {
        val lit = sb.lit; val shade = sb.shade
        if (lit == null || shade == null) return
        val from = sunSide.coerceIn(-1f, 1f)
        val w = floatArrayOf(maxOf(0f, -from), maxOf(0f, from), .25f + .55f * (1f - kotlin.math.abs(from)))
        val sun = lerp(Color.White, faceLit, .7f)
        val dark = lerp(castInk, Color.Black, .25f)
        for (i in 0..2) {
            val a = (direct * w[i]).coerceIn(0f, 1f)
            if (a < .02f) continue
            picture2(lit, -half, -hf, B.W, hf, FilterQuality.None, filter = channelCached(i, sun, LIT_K * a))
            picture2(shade, -half, -hf, B.W, hf, FilterQuality.None, filter = channelCached(i, dark, SHADE_K * a))
        }
        // the recesses: under the sky's light by day, a little at night
        picture2(shade, -half, -hf, B.W, hf, FilterQuality.None, filter = channelCached(3, dark, AO_K * (.6f + .4f * direct)))
    }

    private val balconyPath = Path()

    /**
     * A balcony ([SpriteBuilding.balcony]: x, y, w, h of where the art had it on the front, how far it stands out, its
     * stone and floor colours), built new as boxes on the engine's view: the floor slab, a parapet down each side and one
     * across the front. Every box shows its front, its top (the view looks a little down, as on the roofs) and the side
     * the building shows ([s], [dy]: the lean of the building's whole depth, taken backwards, since the balcony comes
     * out towards the viewer), each lit as the building's own faces are, and the whole throws its shadow on the wall.
     */
    private fun DrawScope.balcony(b: FloatArray, k: Float, half: Float, hf: Float, s: Float, dy: Float) {
        val d = b[4] * BALCONY_GAIN
        val stone = Color(0xFF000000.toInt() or b[5].toInt())
        val tiles = Color(0xFF000000.toInt() or b[6].toInt())
        val x0 = b[0]; val x1 = b[0] + b[2]
        val yb = b[1] + b[3]                         // the slab's underside, where it meets the wall
        val yf = yb - SLAB                           // the floor
        val yt = yf - PARAPET                        // the parapets' tops
        fun px(x: Float, z: Float) = -half + x * k - s * z / SIDE_SRC_DEPTH
        fun py(y: Float, z: Float) = -hf + y * k + dy * z / SIDE_SRC_DEPTH
        val lit = .5f + .5f * direct
        val top = lerp(stone, Color.White, .18f * lit)
        val sideInk = lerp(stone, Color(0xFF1A1830), .38f)
        val front = lerp(stone, Color(0xFF1A1830), .06f * (1f - lit))
        val edge = lerp(stone, Color(0xFF15142A), .62f)
        fun quad(a: Offset, b2: Offset, c: Offset, e: Offset, color: Color, alpha: Float = 1f) {
            balconyPath.rewind(); balconyPath.moveTo(a.x, a.y); balconyPath.lineTo(b2.x, b2.y); balconyPath.lineTo(c.x, c.y); balconyPath.lineTo(e.x, e.y); balconyPath.close()
            drawPath(balconyPath, color, alpha = alpha)
        }
        fun p(x: Float, y: Float, z: Float) = Offset(px(x, z), py(y, z))
        // its shadow on the wall: always some (the sky's light is shut out under it), more in sun, away from the light
        val drop = d * k * (.22f + .2f * direct)
        val lean = -sunSide * d * k * .25f
        quad(p(x0, yb, 0f), p(x1, yb, 0f), Offset(px(x1, 0f) + lean, py(yb, 0f) + drop), Offset(px(x0, 0f) + lean, py(yb, 0f) + drop), castInk, .3f + .3f * direct)
        quad(p(x0, yt, 0f), p(x0 + T, yt, 0f), Offset(px(x0 + T, 0f) + lean, py(yb, 0f) + drop), Offset(px(x0, 0f) + lean, py(yb, 0f) + drop), castInk, .12f + .14f * direct)
        quad(p(x1 - T, yt, 0f), p(x1, yt, 0f), Offset(px(x1, 0f) + lean, py(yb, 0f) + drop), Offset(px(x1 - T, 0f) + lean, py(yb, 0f) + drop), castInk, .12f + .14f * direct)
        /** One box from xa to xb, ya (top) to yb2, z from za (back) to zb (front). */
        fun box(xa: Float, xb: Float, ya: Float, yb2: Float, za: Float, zb: Float, topInk: Color, panel: Boolean = false) {
            // the side the building shows: its right face when the back leans right ([s] > 0), so the left one here
            val xs = if (s > 0f) xa else xb
            if (abs(s) > .05f) quad(p(xs, ya, za), p(xs, ya, zb), p(xs, yb2, zb), p(xs, yb2, za), sideInk)
            quad(p(xa, ya, za), p(xb, ya, za), p(xb, ya, zb), p(xa, ya, zb), topInk)
            quad(p(xa, ya, zb), p(xb, ya, zb), p(xb, yb2, zb), p(xa, yb2, zb), front)
            // the pixel art's dark line round the front, and a light one along its top edge
            val o = p(xa, ya, zb); val e = p(xb, yb2, zb)
            drawRect(edge, Offset(o.x, e.y - k), Size(e.x - o.x, k))
            drawRect(edge, Offset(o.x, o.y), Size(k * .8f, e.y - o.y))
            drawRect(edge, Offset(e.x - k * .8f, o.y), Size(k * .8f, e.y - o.y))
            drawRect(lerp(top, Color.White, .25f), Offset(o.x, o.y), Size(e.x - o.x, k * .7f))
            if (panel && e.x - o.x > 20f * k) {
                // dressed stone: a cap along the top that throws a line of shade, and sunk panels between short piers
                drawRect(edge, Offset(o.x, o.y + 3f * k), Size(e.x - o.x, k * .6f), alpha = .55f)
                val n = ((e.x - o.x) / (34f * k)).toInt().coerceAtLeast(1)
                val pw = (e.x - o.x - 4f * k) / n
                for (i in 0 until n) {
                    val qx = o.x + 2f * k + i * pw + 2.5f * k
                    drawRect(sideInk, Offset(qx, o.y + 6f * k), Size(pw - 5f * k, e.y - o.y - 9f * k), alpha = .35f)
                    drawRect(edge, Offset(qx, o.y + 6f * k), Size(pw - 5f * k, k * .6f), alpha = .5f)
                }
            }
        }
        // back to front: the slab (its top is the tiled floor), the side parapets, the front parapet
        box(x0, x1, yf, yb, 0f, d, lerp(tiles, Color.White, .1f * lit))
        if (s > 0f) { box(x1 - T, x1, yt, yf, 0f, d - T, top); box(x0, x0 + T, yt, yf, 0f, d - T, top) }
        else { box(x0, x0 + T, yt, yf, 0f, d - T, top); box(x1 - T, x1, yt, yf, 0f, d - T, top) }
        box(x0, x1, yt, yf, d - T, d, top, panel = true)
    }

    /**
     * In the crossroads' camera ([StageCamera]) a building's side faces, roof and rooftop billboard stand on planes of
     * their own, each drawn through its own projective transform ([sideFace], [roofFace], [roofBoard]): the front pass
     * then leaves them out. Off on the stage at rest, which draws them as its cabinet parallelograms.
     */
    var planeFaces = false

    /** How the building pictures are sampled: crisp pixels at rest, smoothed where a face is seen small or at a slant. */
    var faceQuality = FilterQuality.None

    /**
     * A side face of the building [prepare]d last, in its own picture's pixels ([SpriteBuilding.side]: 0 at the front's
     * edge to its width at the back, 0 at the top to its height at the pavement), lit as the stage lights its shown side:
     * [right] the face on the front's right, [back] the wall at the back (darker, the light never full on it).
     */
    fun DrawScope.sideFace(sb: SpriteBuilding, right: Boolean, back: Boolean = false) {
        val img = sb.side
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), 1f, Fill, null, BlendMode.SrcOver, faceQuality)
        // at night its glass goes dark and some of its windows are lit, as the front's
        val sn = if (inkDark > .02f) sideNight(img) else null
        if (sn != null) drawImage(sn.dark, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), inkDark, Fill, null, BlendMode.SrcOver, faceQuality)
        drawRect(Color(0xFF0A1024), Offset.Zero, Size(w, h), alpha = if (back) .38f else .2f)
        val lit = (if (right) sunSide else -sunSide) * (if (back) .4f else 1f)
        if (lit > .02f) drawRect(Color.White, Offset.Zero, Size(w, h), alpha = .26f * lit)
        else if (lit < -.02f) drawRect(Color.Black, Offset.Zero, Size(w, h), alpha = .18f * -lit)
        if (nightOn) drawRect(nightInk, Offset.Zero, Size(w, h), blendMode = BlendMode.Multiply)
        if (sn != null) sideLights().let { on ->
            if (on > .01f) drawImage(sn.lit, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), on, Fill, null, BlendMode.SrcOver, faceQuality)
        }
    }

    /** The roof of the building [prepare]d last, in its picture's pixels ([SpriteBuilding.roof]: its top row the back). */
    fun DrawScope.roofFace(sb: SpriteBuilding, sky: SkyColors) {
        val img = sb.roof ?: return
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), 1f, Fill, null, BlendMode.SrcOver, faceQuality)
        drawRect(Color(0xFFFFF2D0), Offset.Zero, Size(w, h), alpha = .1f * direct)
        if (season.snow > .01f) {
            drawRect(snowInk(sky), Offset.Zero, Size(w, h), alpha = .85f * season.snow)
            drawRect(snowInk(sky), Offset(0f, h * .9f), Size(w, h * .1f), alpha = .8f * season.snow)
        }
        if (nightOn) drawRect(nightInk, Offset.Zero, Size(w, h), blendMode = BlendMode.Multiply)
    }

    /** A pitched roof's slope ([SpriteBuilding.pitch]) in its own pixels, for a plane of the corner turn's: lit, snowed on, at night. */
    fun DrawScope.pitchFace(sb: SpriteBuilding, sky: SkyColors) {
        val img = sb.pitch ?: return
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), 1f, Fill, null, BlendMode.SrcOver, faceQuality)
        drawRect(Color(0xFFFFF2D0), Offset.Zero, Size(w, h), alpha = .08f * direct)
        if (season.snow > .01f) drawRect(snowInk(sky), Offset.Zero, Size(w, h), alpha = .85f * season.snow)
        if (nightOn) drawRect(nightInk, Offset.Zero, Size(w, h), blendMode = BlendMode.Multiply)
    }

    /** A pitched roof's gable end, [depth] long and [rise] tall (its apex halfway), in the gable's colour, for a plane of the corner turn's. */
    fun DrawScope.gableFace(sb: SpriteBuilding, depth: Float, rise: Float) {
        val gc = sb.pitchInfo?.getOrNull(1)?.toInt() ?: 0x808080
        spritePath.rewind()
        spritePath.moveTo(0f, rise); spritePath.lineTo(depth / 2f, 0f); spritePath.lineTo(depth, rise); spritePath.close()
        val gp = sb.gablePic
        if (gp != null) clipPath(spritePath) {
            drawImage(gp, IntOffset.Zero, IntSize(gp.width, gp.height), IntOffset.Zero, IntSize(kotlin.math.ceil(depth).toInt(), kotlin.math.ceil(rise).toInt()), 1f, Fill, null, BlendMode.SrcOver, faceQuality)
        } else drawPath(spritePath, Color(0xFF000000.toInt() or gc))
        drawPath(spritePath, Color(0xFF0A1024), alpha = .2f)
        if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
    }

    /** One of a pitched roof's stone end walls' fronts ([img]), lit as the sign is, for a plane of the corner turn's. */
    fun DrawScope.postFace(img: ImageBitmap) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), 1f, Fill, signTint(), BlendMode.SrcOver, faceQuality)
    }

    /** The rooftop billboard ([SpriteBuilding.prop]) in its own pixels, lit as its sign is; false if it has none. */
    fun DrawScope.roofBoard(sb: SpriteBuilding): Boolean {
        if (sb.sign != null) return false
        val img = sb.prop ?: return false
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), 1f, Fill, signTint(), BlendMode.SrcOver, faceQuality)
        return true
    }

    /**
     * One of OUR buildings ([SpriteArt]): its pixel-art front, a side face and a roof built from our sprites, put on
     * the engine's box (the side face and roof lean with where it stands in the view, [sideShift]) and lit by the
     * engine: the sun's wash and shade across the front and the side, the blue hour, the night, the contact shadow at
     * its foot, and its windows lit at dusk.
     */
    private fun DrawScope.spriteBuilding(art: BuildingArt, sb: SpriteBuilding, sky: SkyColors, seconds: Float) {
        val half = B.W / 2f
        val k = B.W / SpriteArt.SRC_W
        val hf = sb.front.height * k
        val s = sideShift
        val dy = B.DY
        // the side face the camera sees: a parallelogram hung from the front's edge
        if (!planeFaces && abs(s) > .02f && art.stack.id !in StageTuning.cornerIds) {
            val xe = if (s > 0f) half else -half
            sideStrips(sb.side, xe, -hf, s, -dy, hf)
            // at night its glass goes dark and some of its windows are lit, as the front's
            val sn = if (inkDark > .02f) sideNight(sb.side) else null
            if (sn != null) sideStrips(sn.dark, xe, -hf, s, -dy, hf, alpha = inkDark)
            spritePath.rewind()
            spritePath.moveTo(xe, 0f); spritePath.lineTo(xe, -hf); spritePath.lineTo(xe + s, -hf - dy); spritePath.lineTo(xe + s, -dy); spritePath.close()
            drawPath(spritePath, Color(0xFF0A1024), alpha = .2f)
            sideLight(spritePath, 1f)
            if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
            if (sn != null) sideLights().let { on -> if (on > .01f) sideStrips(sn.lit, xe, -hf, s, -dy, hf, alpha = on) }
        }
        // a pitched roof (its ridge along the street): the near slope rising from the front's top edge to the ridge
        // halfway back, its gable end on the shown side under it, and its stone end walls if it has them
        val roof = sb.roof
        val pitch = sb.pitch
        if (pitch != null && !planeFaces) {
            val info = sb.pitchInfo
            val band = pitch.height * k
            if (abs(s) > .02f) {
                // the gable: the end wall up to the ridge, in the front's top wall colour, lit as the side face is
                val xe = if (s > 0f) half else -half
                val gc = info?.getOrNull(1)?.toInt() ?: 0x808080
                spritePath.rewind()
                spritePath.moveTo(xe, -hf); spritePath.lineTo(xe + s / 2f, -hf - band); spritePath.lineTo(xe + s, -hf - dy); spritePath.close()
                val gp = sb.gablePic
                if (gp != null) clipPath(spritePath) { sideStrips(gp, xe, -hf - band, s, -dy, band + 1f) }
                else drawPath(spritePath, Color(0xFF000000.toInt() or gc))
                drawPath(spritePath, Color(0xFF0A1024), alpha = .2f)
                sideLight(spritePath, 1f)
                if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
            }
            roofStrips(pitch, -half, -hf, B.W, band, s / 2f, pitch.height)
            spritePath.rewind()
            spritePath.moveTo(-half, -hf); spritePath.lineTo(-half + s / 2f, -hf - band); spritePath.lineTo(half + s / 2f, -hf - band); spritePath.lineTo(half, -hf); spritePath.close()
            // the slope faces up and towards the street: the light from behind the houses grazes it
            drawPath(spritePath, Color(0xFFFFF2D0), alpha = .08f * direct)
            if (season.snow > .01f) {
                drawPath(spritePath, snowInk(sky), alpha = .85f * season.snow)
                drawRect(snowInk(sky), Offset(-half, -hf - 1.4f), Size(B.W, 1.6f), alpha = .8f * season.snow)
            }
            if (blue > .01f) drawPath(spritePath, BlueCast, alpha = .3f * blue, blendMode = BlendMode.Multiply)
            if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
            // the rake along the shown gable's slope
            if (abs(s) > .02f && info != null) {
                val xe = if (s > 0f) half else -half
                drawLine(Color(0xFF000000.toInt() or info[0].toInt()), Offset(xe, -hf), Offset(xe + s / 2f, -hf - band), strokeWidth = 1.2f * k)
            }
            // the stone end walls standing up at either end, their caps over the ridge
            val pw = (info?.getOrNull(2) ?: 0f) * k
            val pl = sb.postL; val pr = sb.postR
            if (pw > 0f && pl != null && pr != null) {
                val ph = pl.height * k
                part(pl, 0, 0, pl.width, pl.height, -half, -hf - ph + 3f * k, pw, ph, filter = signTint())
                part(pr, 0, 0, pr.width, pr.height, half - pw, -hf - ph + 3f * k, pw, ph, filter = signTint())
            }
        } else if (roof != null && !planeFaces) {
            roofStrips(roof, -half, -hf, B.W, dy, s)
            spritePath.rewind()
            spritePath.moveTo(-half, -hf); spritePath.lineTo(-half + s, -hf - dy); spritePath.lineTo(half + s, -hf - dy); spritePath.lineTo(half, -hf); spritePath.close()
            drawPath(spritePath, Color(0xFFFFF2D0), alpha = .1f * direct)
            // snow lying on the roof, and a cap along the front's top edge
            if (season.snow > .01f) {
                drawPath(spritePath, snowInk(sky), alpha = .85f * season.snow)
                drawRect(snowInk(sky), Offset(-half, -hf - 1.4f), Size(B.W, 1.6f), alpha = .8f * season.snow)
            }
            if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
        }
        // what sticks up above the roofline: the part of the storefront's sign that does not fit over the front (it
        // continues the front exactly, so the sign reads as one piece), or a rooftop billboard standing halfway back
        val over = sb.sign
        if (over != null) {
            val h = over.height * k
            picture2(over, -half, -hf - h, B.W, h, faceQuality, filter = signTint())
        } else {
            val bill = sb.prop
            if (bill != null && !planeFaces) {
                val w = 160f * k
                val h = bill.height * k
                picture2(bill, -w / 2f + s * .5f, -hf - dy * .5f - h, w, h, faceQuality, filter = signTint())
            }
        }
        // the windows are real openings: the room behind each (dark glass and a dim room by day, a warm lit room once
        // the lamp is on), with the front's glass cut out in front of it
        val wseed = art.stack.id.hashCode()
        val glassDark = lerp(SceneInk.GlassDay, SceneInk.GlassNight, sky.dark)
        val litRoom = lerp(SceneInk.WindowLitDay, SceneInk.WindowLit, sky.dark)
        val dayRoom = lerp(SceneInk.WindowLitDay, glassDark, sky.dark)
        val sw = windowsOf(art.stack.id, sb.windows)
        if (sw.cast.isNotEmpty()) Presence.floorPresence(sw.here, sw.cast, wseed, wseed + 1, 0, 1, seconds)
        val litIndex = IntArray(sb.windows.size) { -1 }
        for (j in sw.lit.indices) litIndex[sw.lit[j]] = j
        for (i in sb.windows.indices) {
            val w = sb.windows[i]
            val x = -half + w[0] * k
            val y = -hf + w[1] * k
            val ww = w[2] * k
            val hh = w[3] * k
            val pane = wseed * 31 + i
            val lampOn = Dusk.lightsOn(pane, hour)
            drawRect(glassDark, Offset(x, y), Size(ww, hh))
            val j = litIndex[i]
            if (!StageTuning.interiors) {
                // plain glass: the reflection below is all it shows
            } else if (j >= 0) {
                // a lit home: a furnished room, someone doing something in it now and then, its lamp at dusk
                val c = sw.cast[j]
                val bright = if (sw.here[j] <= 0f && seconds > 0f) .12f + .88f * Silhouettes.lamp(c.seed, seconds) else 1f
                val tv = Dusk.tv(pane, hour, seconds)
                drawRect(lerp(dayRoom, litRoom, lampOn), Offset(x, y), Size(ww, hh), alpha = bright)
                windowLife(c, x, y, ww, hh, seconds, 1f, PeopleLod.FULL, sky, sw.here[j], lampOn, tv)
                drawRect(SceneInk.WindowLitLow, Offset(x, y + hh * .55f), Size(ww, hh * .45f), alpha = lampOn * bright * .45f)
            } else {
                // a dark window: dim glass and a blind or curtain behind it
                val room = GlassMap.interior(pane)
                if (room >= 0) interior(x, y, ww, hh, room, .9f * (1f - .75f * sky.dark))
            }
        }
        // the front never moves
        picture2(sb.front, -half, -hf, B.W, hf, faceQuality)
        relief(sb, half, hf)
        sb.balcony?.let {
            balcony(it, k, half, hf, s, dy)
            sb.overBalcony?.let { o -> picture2(o, -half, -hf, B.W, hf, faceQuality) }
        }
        facadeLight(-hf, 0f, 1f)
        blueHour(-half, -hf, B.W, hf, .35f)
        drawRect(aoWall, Offset(-half, -14f), Size(B.W, 14f))
        if (nightOn) drawRect(nightInk, Offset(-half, -hf), Size(B.W, hf), blendMode = BlendMode.Multiply)
        // over the glass: what it mirrors (the sky, the street, the sun's glint), fainter the brighter the room, and
        // the lit room's light on the frame
        for (i in sb.windows.indices) {
            val w = sb.windows[i]
            val x = -half + w[0] * k
            val y = -hf + w[1] * k
            val ww = w[2] * k
            val hh = w[3] * k
            val pane = wseed * 31 + i
            val lampOn = Dusk.lightsOn(pane, hour)
            val mirror = fresnel * (1f + PANE_FRESNEL * GlassMap.tilt(pane))
            mirrorSky(x, y, ww, hh, min(1f, FacadeLook.reflection(if (StageTuning.interiors) lampOn else 0f, sky.dark) * mirror * (if (StageTuning.interiors) 1f else 1.35f)) * (1f - .5f * (if (StageTuning.interiors) lampOn else 0f)), pane, 1f - .7f * (if (StageTuning.interiors) lampOn else 0f))
            if (StageTuning.interiors && lampOn > .02f && sky.dark > .2f) roomOnReveals(x, y, ww, hh, .3f * lampOn * sky.dark)
            glassRain(x, y, ww, hh, glassSeconds, sky)
        }
        // the shopfront glass: the display shows through, with the sky and the sun's glint faintly over the real panes only
        val gm = sb.glass
        if (gm != null && sb.shop.isNotEmpty()) {
            val band = sb.shop[0]
            drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(-half, -hf, half, 0f), Paint()) }
            val pane = wseed * 17 + 500
            val mirror = fresnel * (1f + PANE_FRESNEL * GlassMap.tilt(pane))
            mirrorSky(-half + band[0] * k, -hf + band[1] * k, band[2] * k, band[3] * k, min(1f, FacadeLook.reflection(0f, sky.dark) * mirror * .75f), pane, 1f)
            glassRain(-half + band[0] * k, -hf + band[1] * k, band[2] * k, band[3] * k, glassSeconds, sky)
            picture2(gm, -half, -hf, B.W, hf, faceQuality, blend = BlendMode.DstIn)
            drawIntoCanvas { it.restore() }
        }
        // lit at dusk: the glass glows
        val on = (sky.dark * Dusk.lightsOn(art.stack.id.hashCode(), hour, Dusk.SHOP)).coerceIn(0f, 1f)
        if (on > .01f) {
            // lit inside: the shop's own display brightened and warmed (overlay keeps what is in the window), and a little
            // light added on top so the glass glows
            picture2(sb.emis, -half, -hf, B.W, hf, faceQuality, alpha = on * .9f, blend = BlendMode.Overlay)
            picture2(sb.emis, -half, -hf, B.W, hf, faceQuality, alpha = on * .22f, blend = BlendMode.Plus)
        }
        drawRect(SceneInk.Highlight, Offset(-half, -hf), Size(B.W, 1f))
    }

    // ------------------------------------------------------------------ our pitched-roof houses

    /** Screen pixels per street unit, set by the stage each frame (0 unknown): a house's place back in its yard snaps to it. */
    var unitPx = 0f

    private val housePath = Path()
    private var castFilterKey = Color.Unspecified
    private var castFilterNow: ColorFilter? = null

    /** The cast shadow's colour as a filter (a picture's own shape in it), kept while the colour holds. */
    private fun castFilter(): ColorFilter {
        val f = castFilterNow
        if (f != null && castFilterKey == castInk) return f
        castFilterKey = castInk
        return ColorFilter.tint(castInk, BlendMode.SrcIn).also { castFilterNow = it }
    }
    /** One view of a house, set again for each house drawn ([HouseView.set]). */
    private var houseView: HouseView? = null

    /** The porch light's glow on the wall, and its pool on the ground: unit circles under a transform, as [halo]. */
    private val porchGlow = Brush.radialGradient(listOf(SceneInk.LampLight.copy(alpha = .55f), Color.Transparent), Offset.Zero, 1f)
    private val porchPool = Brush.radialGradient(listOf(SceneInk.LampLight.copy(alpha = .4f), Color.Transparent), Offset.Zero, 1f)

    private fun quadPath(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Path {
        housePath.rewind(); housePath.moveTo(ax, ay); housePath.lineTo(bx, by); housePath.lineTo(cx, cy); housePath.lineTo(dx, dy); housePath.close()
        return housePath
    }

    /** Shapes filled row by row of the screen's pixels ([rowQuad]), gathered for one colour and drawn at once. */
    private val rowsPath = Path()

    /**
     * Adds the convex quad (a, b, c, d) to [rowsPath] as whole-pixel spans, one per screen row ([PixelRows]): a thin
     * steep band comes out as a stepped pixel line instead of a blended sliver. As a plain quad when the screen's
     * scale is not known.
     */
    private fun rowQuad(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        val p = rowsPath
        if (unitPx <= 0f) { p.moveTo(ax, ay); p.lineTo(bx, by); p.lineTo(cx, cy); p.lineTo(dx, dy); p.close(); return }
        com.lifemax.rpg.feature.habits.domain.PixelRows.quad(ax, ay, bx, by, cx, cy, dx, dy, unitPx, buildingX) { l, t, r, b ->
            p.moveTo(l, t); p.lineTo(r, t); p.lineTo(r, b); p.lineTo(l, b); p.close()
        }
    }

    /**
     * A band along a house's gable (in the plane [xp] across its front picture) from depth [a0] to [a1] behind the
     * wall, between [lo] and [hi] above the roof slab's top there ([HouseShape.roofTop]), added to [rowsPath]. [a0]
     * and [a1] on the same side of the ridge, so the band is straight.
     */
    private fun rakeRows(v: com.lifemax.rpg.feature.habits.domain.HouseView, xp: Float, a0: Float, a1: Float, lo: Float, hi: Float) {
        val sh = v.shape
        val t0 = sh.roofTop(a0); val t1 = sh.roofTop(a1)
        rowQuad(v.x(xp, a0), v.y(t0 + hi, a0), v.x(xp, a1), v.y(t1 + hi, a1), v.x(xp, a1), v.y(t1 + lo, a1), v.x(xp, a0), v.y(t0 + lo, a0))
    }

    /** Part of a picture ([sx], [sy], [sw], [sh] of its pixels) drawn at ([x], [y]), [w] by [h]. */
    private fun DrawScope.part(img: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f, filter: ColorFilter? = null, blend: BlendMode = BlendMode.SrcOver) {
        if (sw <= 0 || sh <= 0) return
        withTransform({
            translate(x, y)
            scale(w / sw, h / sh, Offset.Zero)
        }) {
            drawImage(img, IntOffset(sx, sy), IntSize(sw, sh), IntOffset.Zero, IntSize(sw, sh), alpha, Fill, filter, blend, FilterQuality.None)
        }
    }

    /**
     * Part of a picture laid on a level face seen from a little above (the roof's slope, a deck, the yard): its front edge
     * from ([x], [y]) across [w], its back edge [dy] higher and [shift] to the side; one axis-aligned strip per picture
     * row (the picture's top row the far edge), so its pixels stay crisp.
     */
    private fun DrawScope.levelStrips(img: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Float, y: Float, w: Float, dy: Float, shift: Float, filter: ColorFilter? = null, alpha: Float = 1f) {
        if (sh <= 0) return
        for (j in 0 until sh) {
            val t = (j + 1f) / sh
            val yy = y - dy * (1f - t)
            val xx = x + minOf(shift * (1f - t), shift * (1f - j.toFloat() / sh))
            val ww = w + abs(shift) / sh
            withTransform({
                translate(xx, yy - dy / sh)
                scale(ww / sw, (dy / sh + .6f), Offset.Zero)
            }) {
                drawImage(img, IntOffset(sx, sy + j), IntSize(sw, 1), IntOffset.Zero, IntSize(sw, 1), alpha, Fill, filter, BlendMode.SrcOver, FilterQuality.None)
            }
        }
    }

    /** A colour as the night and the blue hour leave it on a wall. */
    private fun houseInk(c: Color): Color {
        var r = c.red; var g = c.green; var b = c.blue
        if (blue > .01f) { val t = .35f * blue; r *= 1f - t + t * BlueCast.red; g *= 1f - t + t * BlueCast.green; b *= 1f - t + t * BlueCast.blue }
        if (nightOn) { r *= nightInk.red; g *= nightInk.green; b *= nightInk.blue }
        return Color(r, g, b)
    }

    private fun rgb(c: Int): Color = Color(0xFF000000.toInt() or c)

    /**
     * One of a house's boxes (its porch's deck, rails and legs, its steps, its flue): its front, its top and the side the
     * view shows, each lit as the house's own faces are (the top by the sky and the sun, the shown side by which side the
     * light is on, the front a little darker in the sun, as the fronts face away from it), with the pixel art's dark
     * outline and a light top edge. Lengths in the house's pixels; [dn] and [df] its near and far depth behind the wall.
     */
    private fun DrawScope.houseBox(
        v: com.lifemax.rpg.feature.habits.domain.SetBackView, x0: Float, x1: Float, y0: Float, y1: Float, dn: Float, df: Float,
        top: Color, front: Color, side: Color, sky: SkyColors, tex: ImageBitmap? = null, frontTex: IntArray? = null, topTex: IntArray? = null, outline: Boolean = true,
    ) {
        val k = v.k
        val lit = .5f + .5f * direct
        val toSun = if (sideShift < 0f) -sunSide else sunSide
        val snowy = season.snow
        var topC = lerp(top, Color.White, .16f * lit)
        if (snowy > .01f) topC = lerp(topC, snowInk(sky), .85f * snowy)
        val sideC = if (toSun > .02f) lerp(side, Color.White, .22f * toSun) else lerp(side, Color(0xFF1A1830), .2f + .2f * -toSun)
        val frontC = lerp(front, castInk, .1f * direct)
        val edge = houseInk(lerp(front, Color(0xFF15142A), .62f))
        // the shown side
        if (v.showsSide && df > dn) {
            val xs = if (v.rightSide) x1 else x0
            drawPath(quadPath(v.x(xs, dn), v.y(y1, dn), v.x(xs, df), v.y(y1, df), v.x(xs, df), v.y(y0, df), v.x(xs, dn), v.y(y0, dn)), houseInk(sideC))
        }
        // the top
        if (df > dn) {
            val tt = topTex
            if (tex != null && tt != null && snowy < .5f) {
                levelStrips(tex, tt[0], tt[1], tt[2], tt[3], v.x(x0, dn), v.y(y1, dn), (x1 - x0) * k, v.lift(df - dn), v.lean(df - dn), signTint())
                if (snowy > .01f) drawPath(quadPath(v.x(x0, dn), v.y(y1, dn), v.x(x1, dn), v.y(y1, dn), v.x(x1, df), v.y(y1, df), v.x(x0, df), v.y(y1, df)), houseInk(snowInk(sky)), alpha = .85f * snowy)
            } else drawPath(quadPath(v.x(x0, dn), v.y(y1, dn), v.x(x1, dn), v.y(y1, dn), v.x(x1, df), v.y(y1, df), v.x(x0, df), v.y(y1, df)), houseInk(topC))
        }
        // the front
        val fx = v.x(x0, dn); val fy = v.y(y1, dn); val fw = (x1 - x0) * k; val fh = (y1 - y0) * k
        val ft = frontTex
        if (tex != null && ft != null) part(tex, ft[0], ft[1], ft[2], ft[3], fx, fy, fw, fh, filter = signTint())
        else drawRect(houseInk(frontC), Offset(fx, fy), Size(fw, fh))
        if (direct > .02f && tex != null && ft != null) drawRect(castInk, Offset(fx, fy), Size(fw, fh), alpha = .1f * direct)
        if (outline && fw > 2f * k) {
            drawRect(edge, Offset(fx, fy + fh - k * .7f), Size(fw, k * .7f))
            drawRect(edge, Offset(fx, fy), Size(k * .6f, fh))
            drawRect(edge, Offset(fx + fw - k * .6f, fy), Size(k * .6f, fh))
            drawRect(houseInk(lerp(topC, Color.White, .25f)), Offset(fx, fy), Size(fw, k * .6f))
        }
    }

    /**
     * One of OUR mobile homes ([HouseArt]) as a house, not a box: it stands back in its yard (so it moves against the
     * shops and lamps as the street scrolls), its wall the art's own front, a pitched roof whose ridge runs along the
     * street (the art's slope, one screen row per row of its pixels), a gable end on the side the view shows, the flue
     * standing up behind the ridge, the hitch beside it, and its porch or steps built as boxes in front. Lit as the shops
     * are (the sun's wash and shade on the wall, the slope catching a high sun and going into shade when it is low, the
     * gable by its side, the night and the blue hour), with snow on its roof and its yard, and its windows lit at dusk on
     * a home's hours.
     */
    private fun DrawScope.houseBuilding(art: BuildingArt, sb: SpriteBuilding, ha: HouseArt, sky: SkyColors, seconds: Float) {
        val sh = ha.shape
        val s = sideShift
        val v = (houseView ?: HouseView(sh, s).also { houseView = it }).set(sh, s, unitPx, buildingX)
        val k = v.k
        val half = B.W / 2f
        val picW = sb.front.width.toFloat()
        val eo = sh.eaveOver
        val snow = season.snow
        val key = light
        // --- the yard: its ground from the pavement back past the house, and the house's shade on it
        val deep = sh.setback + sh.depth
        val lawn = ha.lawn
        val backY = -B.DY * deep / com.lifemax.rpg.feature.habits.domain.HouseView.DEPTH
        val backX = s * deep / com.lifemax.rpg.feature.habits.domain.HouseView.DEPTH
        if (lawn != null) levelStrips(lawn, 0, 0, lawn.width, lawn.height, -half, 0f, B.W, -backY, backX)
        val yard = quadPath(-half, 0f, half, 0f, half + backX, backY, -half + backX, backY)
        if (snow > .01f) drawPath(yard, snowInk(sky), alpha = .82f * snow)
        if (blue > .01f) drawPath(yard, BlueCast, alpha = .35f * blue, blendMode = BlendMode.Multiply)
        if (nightOn) drawPath(yard, nightInk, blendMode = BlendMode.Multiply)
        // the house's own shade over its yard (its shadow on the pavement is drawn with the other buildings'): the light
        // is behind the houses, so the yard in front of the wall is in shade, its edges leaning away from the light
        val wl = v.x(sh.wall0, 0f); val wr = v.x(sh.wall1, 0f); val foot = v.y(0f, 0f)
        var yardShade = 0f
        if (key != null && key.strength > .02f && key.reach > .05f) {
            val lean = (SceneLight.lean(key, offset(buildingX)) / key.reach * -foot).coerceIn(-40f, 40f)
            val a = (if (key.cool > .5f) MOON_SHADE else SUN_SHADE) * key.strength * (1f - .6f * key.flat)
            drawPath(quadPath(wl, foot, wr, foot, wr + lean, 0f, wl + lean, 0f), shade(key, sky), alpha = a)
            yardShade = a
        }
        // the contact shadow along the house's foot
        withTransform({ translate(0f, foot); scale(1f, .45f, Offset.Zero) }) { drawRect(aoGround, Offset(wl - k, 0f), Size(wr - wl + 2f * k, 8f)) }

        // --- the flue, standing on the far slope (the near slope hides its foot)
        val flueBase = sh.roofTop(sh.flueD1) - 2f
        val flueC = rgb(sh.flueBody)
        houseBox(v, sh.flueX0, sh.flueX1, flueBase, sh.flueTop, sh.flueD0, sh.flueD1, rgb(sh.flueCap), flueC, lerp(flueC, Color.Black, .2f), sky)

        // --- the gable end the view shows, under its rake
        val showGable = v.showsSide && art.stack.id !in StageTuning.cornerIds
        if (showGable) {
            val xe = if (v.rightSide) sh.wall1 else sh.wall0
            val sideH = sb.side.height.toFloat()
            sideStrips(sb.side, v.x(xe, 0f), v.y(sideH, 0f), v.lean(sh.depth), -v.lift(sh.depth), sideH * k, 20)
            housePath.rewind()
            housePath.moveTo(v.x(xe, 0f), v.y(0f, 0f))
            housePath.lineTo(v.x(xe, 0f), v.y(sh.gableTop(0f), 0f))
            housePath.lineTo(v.x(xe, sh.depth / 2f), v.y(sh.gableTop(sh.depth / 2f), sh.depth / 2f))
            housePath.lineTo(v.x(xe, sh.depth), v.y(sh.gableTop(sh.depth), sh.depth))
            housePath.lineTo(v.x(xe, sh.depth), v.y(0f, sh.depth))
            housePath.close()
            drawPath(housePath, Color(0xFF0A1024), alpha = .2f)
            sideLight(housePath, 1f)
            if (blue > .01f) drawPath(housePath, BlueCast, alpha = .35f * blue, blendMode = BlendMode.Multiply)
            if (nightOn) drawPath(housePath, nightInk, blendMode = BlendMode.Multiply)
            // the verge's shadow on the gable when the light is on this side: a band under the soffit, wider in a low sun
            val toSun = if (s < 0f) -sunSide else sunSide
            val d2 = sh.depth / 2f
            if (toSun > .02f) {
                val band = 2f + 4f * gold
                rowsPath.rewind()
                rakeRows(v, xe, 0f, d2, -sh.slab - band, -sh.slab); rakeRows(v, xe, d2, sh.depth, -sh.slab - band, -sh.slab)
                drawPath(rowsPath, castInk, alpha = .28f * toSun)
            }
            // the roof slab's end stands out past the wall by the verge. Seen from a little above, the near half's
            // underside is hidden under the near slope, but the back half's faces the camera (the far slope is
            // steeper than the view): its soffit, in shade, between the wall's top and the rake boards. Each is a steep
            // band a pixel or two wide, so it is filled row by row of the screen's pixels (stepped like the art's own
            // lines, never thinner than a pixel, no sky between them)
            val xv = if (v.rightSide) sh.wall1 + sh.verge else sh.wall0 - sh.verge
            val dEnd = sh.depth + eo
            rowsPath.rewind()
            rowQuad(v.x(xe, d2), v.y(sh.gableTop(d2), d2), v.x(xv, d2), v.y(sh.gableTop(d2), d2),
                v.x(xv, dEnd), v.y(sh.gableTop(dEnd), dEnd), v.x(xe, dEnd), v.y(sh.gableTop(dEnd), dEnd))
            drawPath(rowsPath, houseInk(lerp(rgb(sh.rakeEdge), Color(0xFF15142A), .45f)))
            // the rake boards, from the front eave up to the ridge and down to the back eave: lit by the side's light,
            // and under snow white with the slope (snow caked on the verge, and a lip of it along the top)
            var rakeC = if (toSun > .02f) lerp(rgb(sh.rake), Color.White, .2f * toSun) else lerp(rgb(sh.rake), Color.Black, .15f)
            if (snow > .01f) rakeC = lerp(rakeC, snowInk(sky), .8f * snow)
            rowsPath.rewind()
            rakeRows(v, xv, -eo, d2, -sh.slab, 0f); rakeRows(v, xv, d2, dEnd, -sh.slab, 0f)
            drawPath(rowsPath, houseInk(rakeC))
            // its top edge against the sky behind the ridge (the near half's is the slope's own edge)
            rowsPath.rewind()
            rakeRows(v, xv, d2, dEnd, -.8f, 0f)
            drawPath(rowsPath, houseInk(lerp(rgb(sh.rakeEdge), snowInk(sky), .8f * snow)))
            if (snow > .01f) {
                rowsPath.rewind()
                rakeRows(v, xv, -eo, d2, 0f, 1.6f); rakeRows(v, xv, d2, dEnd, 0f, 1.6f)
                drawPath(rowsPath, snowInk(sky), alpha = .9f * snow)
            }
        }

        // --- the hitch: a card at mid-depth beside the wall (behind the front when the other side shows)
        ha.hitch?.let { h ->
            val hw = h.width * k; val hh = h.height * k
            // its foot stays on the yard: beside a house wider than its lot (the medium homes) the yard's ground ends
            // before the wall's far side, and a hitch past it would hang over the sky; it tucks in behind the wall instead
            val fy = v.y(0f, sh.depth / 2f)
            val edge = if (backY != 0f) half + backX * (fy / backY) else half
            part(h, 0, 0, h.width, h.height, minOf(v.x(sh.wall1, sh.depth / 2f), edge - hw), fy - hh, hw, hh, filter = signTint())
        }

        // --- the roof's near slope, from the eave's edge up to the ridge. The eave overhangs the wall, which puts it off
        // the screen's pixel grid: it is snapped back to it, and the ridge is exactly the slope's rows above it, so every
        // row of the art's slope lands on whole screen pixels (none blended with the next)
        val rows = ha.slope.height
        // (across, the building's own middle need not be on a whole pixel: its place along the street is added first)
        fun snap(a: Float, at: Float = 0f) = if (unitPx > 0f) kotlin.math.round((a + at) * unitPx) / unitPx - at else a
        val ex = snap(v.x(0f, -eo), buildingX); val ey = snap(v.y(sh.h, -eo))
        val rx = ex + v.lean(sh.run); val ry = ey - rows * k
        levelStrips(ha.slope, 0, 0, ha.slope.width, rows, ex, ey, picW * k, ey - ry, rx - ex)
        val slope = quadPath(ex, ey, ex + picW * k, ey, rx + picW * k, ry, rx, ry)
        if (key != null) {
            // the slope faces up and towards the viewer; the light comes from behind the houses
            val p = sh.pitch
            val sun = key.strength * (1f - key.flat) * (key.down * kotlin.math.cos(p) - key.out * kotlin.math.sin(p)) * (1f - .6f * key.cool)
            if (sun > .01f) drawPath(slope, faceLit, alpha = (.3f * sun).coerceAtMost(.35f))
            else if (sun < -.01f) drawPath(slope, castInk, alpha = (.25f * -sun).coerceAtMost(.3f))
        }
        if (snow > .01f) drawPath(slope, snowInk(sky), alpha = .85f * snow)
        if (blue > .01f) drawPath(slope, BlueCast, alpha = .35f * blue, blendMode = BlendMode.Multiply)
        if (nightOn) drawPath(slope, nightInk, blendMode = BlendMode.Multiply)
        // the ridge cap, astride the ridge; under snow, white with the slope (the cap's own shape) and heaped on top
        part(ha.ridge, 0, 0, ha.ridge.width, ha.ridge.height, rx, ry - 2f * k, picW * k, ha.ridge.height * k, filter = signTint())
        if (snow > .01f) {
            snowInk(sky)
            part(ha.ridge, 0, 0, ha.ridge.width, ha.ridge.height, rx, ry - 2f * k, picW * k, ha.ridge.height * k, alpha = .85f * snow, filter = snowFilter)
            cap(rx, ry - 2.6f * k, picW * k, 2.2f * k, .95f * snow)
        }

        // --- the front: the wall, and the eave's edge (the roof slab's front, overhanging the wall)
        val fasc = sh.fascia.toInt()
        val hWall = sh.h - sh.fascia
        part(sb.front, 0, fasc, sb.front.width, sb.front.height - fasc, v.x(0f, 0f), v.y(hWall, 0f), picW * k, hWall * k)
        part(sb.front, 0, 0, sb.front.width, fasc, ex, ey, picW * k, sh.fascia * k)
        val wy = v.y(hWall, 0f)
        // under the eaves: the sky's light shut out, more of it in the sun
        drawRect(castInk, Offset(wl, wy), Size(wr - wl, (2f + 3f * direct) * k), alpha = .22f + .22f * direct)
        facadeLight(wy, foot, 1f, wl, wr - wl)
        blueHour(wl, wy, wr - wl, foot - wy, .35f)
        blueHour(ex, ey, picW * k, sh.fascia * k, .35f)
        withTransform({ translate(0f, foot) }) { drawRect(aoWall, Offset(wl, -14f), Size(wr - wl, 14f)) }
        if (nightOn) {
            drawRect(nightInk, Offset(wl, wy), Size(wr - wl, foot - wy), blendMode = BlendMode.Multiply)
            drawRect(nightInk, Offset(ex, ey), Size(picW * k, sh.fascia * k), blendMode = BlendMode.Multiply)
        }
        if (snow > .01f) {
            // a lip of snow over the eave's edge, and icicles along it in a hard winter
            cap(ex - .4f * k, ey - 1.6f * k, picW * k + .8f * k, 2.4f * k, .9f * snow)
            if (snow > .5f) {
                val seed = art.stack.id.hashCode()
                var x = 4f
                var i = 0
                while (x < picW - 4f) {
                    val len = (1.5f + 4f * Ambient.hash(seed, i, 77)) * k * (snow - .5f) * 2f
                    val ix = ex + x * k
                    housePath.rewind(); housePath.moveTo(ix - .7f * k, ey + sh.fascia * k); housePath.lineTo(ix + .7f * k, ey + sh.fascia * k); housePath.lineTo(ix, ey + sh.fascia * k + len); housePath.close()
                    drawPath(housePath, snowInk, alpha = .85f)
                    x += 5f + 9f * Ambient.hash(seed, i, 78); i++
                }
            }
        }

        // --- the porch or the steps, back to front
        val order = sh.boxesInOrder(s)
        for (i in order.indices) {
            val b = order[i]
            houseBox(v, b.x0, b.x1, b.y0, b.y1, -b.z1, if (b.card) -b.z1 + .01f else -b.z0, rgb(b.top), rgb(b.front), rgb(b.side), sky,
                ha.porch, b.frontTex, b.topTex, outline = b.frontTex == null)
        }

        // --- the yard's fence or hedge along the street, open at the gate: in front of the house's foot
        ha.fence?.let { fe ->
            val dd = sh.fenceD - sh.setback
            val fx = v.x(-sh.frontX, dd); val fy = v.y(sh.fenceFoot, dd)
            part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, filter = signTint())
            // it stands in the house's shade as the yard does (the light is behind the houses: deepest in a low sun),
            // so it goes with the wall behind it instead of staying a bright green in the evening
            val cast = max(.12f * direct, .85f * yardShade)
            if (cast > .01f) part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, alpha = cast, filter = castFilter())
            if (snow > .01f) {
                // snow along every top it has (a picket's point, a rail, a bush's crown), and a dusting over the rest
                snowInk(sky)
                ha.fenceSnow?.let { fs -> part(fs, 0, 0, fs.width, fs.height, fx, fy, fe.width * k, fe.height * k, alpha = .92f * snow, filter = snowFilter) }
                part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, alpha = .22f * snow, filter = snowFilter)
            }
        }

        // --- the windows: the sky and the street mirrored in the glass, the rain on it, and the lamp lit inside at dusk
        val wseed = art.stack.id.hashCode()
        for (i in sh.panes.indices) {
            val p = sh.panes[i]
            val x = v.x(p[0], 0f); val y = v.y(sh.h - p[1], 0f); val ww = p[2] * k; val hh = p[3] * k
            val pane = wseed * 31 + i
            val lampOn = Dusk.lightsOn(pane, hour)
            val mirror = fresnel * (1f + PANE_FRESNEL * GlassMap.tilt(pane))
            val on = (sky.dark * lampOn).coerceIn(0f, 1f)
            // (faint: the art's own curtains and blinds are behind the glass, and should show)
            mirrorSky(x, y, ww, hh, min(1f, FacadeLook.reflection(on, sky.dark) * mirror * (if (sh.glass) .45f else .25f)), pane, 1f - .7f * on)
            glassRain(x, y, ww, hh, glassSeconds, sky)
            if (on > .01f) {
                // the lamp's warmth through the curtains (blue cloth), then the glow over what the window shows
                if (sh.glass) drawRect(SceneInk.WindowLit, Offset(x, y), Size(ww, hh), alpha = .4f * on, blendMode = BlendMode.Multiply)
                part(sb.emis, p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), x, y, ww, hh, alpha = on * .9f, blend = BlendMode.Overlay)
                part(sb.emis, p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), x, y, ww, hh, alpha = on * .3f, blend = BlendMode.Plus)
            }
        }
        // --- the porch light beside the door: a small lamp on the wall, lit from dusk with a warm pool on the wall and
        // the ground below it (the porch's deck or the steps)
        run {
            val lx = 106f; val ly = 32f                                 // front-picture pixels: right of the door, at head height
            val x = v.x(lx, 0f); val y = v.y(sh.h - ly, 0f)
            drawRect(houseInk(Color(0xFF2A2633)), Offset(x - .5f * k, y - 1f * k), Size(4f * k, 5f * k))
            val on = (sky.dark * Dusk.lightsOn(wseed + 7, hour, Dusk.LAMP)).coerceIn(0f, 1f)
            drawRect(lerp(houseInk(Color(0xFFBFB59A)), SceneInk.WindowLit, on), Offset(x + .5f * k, y), Size(2f * k, 3f * k))
            if (on > .02f) {
                val c = Offset(x + 1.5f * k, y + 1.5f * k)
                val r = 16f * k
                withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) {
                    drawCircle(porchGlow, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus)
                }
                // its pool on the ground in front of the door
                val gy = v.y(0f, -10f)
                withTransform({ translate(c.x, gy); scale(22f * k, .28f * 22f * k, Offset.Zero) }) {
                    drawCircle(porchPool, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus)
                }
            }
        }
        drawRect(SceneInk.Highlight, Offset(ex, ey), Size(picW * k, .6f * k))
        // smoke from the flue on cold days, most at night and in the morning
        houseSmoke(wseed, v.x((sh.flueX0 + sh.flueX1) / 2f, (sh.flueD0 + sh.flueD1) / 2f), v.y(sh.flueTop, (sh.flueD0 + sh.flueD1) / 2f), k, seconds, sky)
    }

    /**
     * The smoke off a house's flue (its top at ([x], [y])): soft puffs rising, growing and thinning, drifting with the
     * wind, from the heating in the cold months ([Dusk.steam]: thickest at night and in the morning); none in summer.
     */
    private fun DrawScope.houseSmoke(seed: Int, x: Float, y: Float, k: Float, seconds: Float, sky: SkyColors) {
        val day = season.dayOfYear
        val cold = if (day < 90 || day > 300) 1f else if (day < 120 || day > 275) .4f else 0f
        val density = cold * Dusk.steam(hour, day)
        if (density <= .01f) return
        duskTints(sky)
        val wind = Ambient.sway(seed, seconds)
        val puffs = SceneSprites.dusk.puffs
        val half = DuskSprites.PUFF / 2f
        for (j in 0 until Dusk.PUFFS) {
            val t = Dusk.puff(seed, j, seconds * .8f)
            val size = (.5f + 1.9f * t) * 9f * k / DuskSprites.PUFF
            val px = x + t * (4f + 3f * wind) * k + 4f * t * t * k
            val py = y - t * 30f * k
            val fade = (1f - t) * (1f - t)
            val a = density * .5f * fade * min(1f, t * 10f)
            if (a <= .01f) continue
            withTransform({
                translate(px, py)
                scale(size, size, Offset.Zero)
            }) {
                drawImage(puffs[j % DuskSprites.PUFFS], Offset(-half, -half), alpha = a, colorFilter = steamFilter)
            }
        }
    }

    // ------------------------------------------------------------------ our villas and landmarks

    /** One view of a villa or a landmark, set again for each one drawn ([ModelView.set]). */
    private val modelView = com.lifemax.rpg.feature.habits.domain.ModelView()
    /** A roof piece's map onto the screen, and its scratch (drawing happens on one thread). */
    private val pieceM = Matrix()
    private val pieceFrom = FloatArray(8)
    private val pieceTo = FloatArray(8)
    private val pieceRep = FloatArray(8)
    private val pieceMap = DoubleArray(9)
    private val pieceA = DoubleArray(9)
    private val pieceB = DoubleArray(9)

    /**
     * Atlas piece [r] (x, y, w, h) laid on the screen: its points [from] (four, in its own pixels) onto [to] (the
     * screen's), by one projective map ([QuadMap]), its pixels crisp. A roof's slope seen from the street is its picture
     * from above squeezed into a band; a parallelogram onto a parallelogram is a plain skew.
     */
    private fun DrawScope.pieceOnto(img: ImageBitmap, r: IntArray, from: FloatArray, to: FloatArray, filter: ColorFilter?, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver) {
        if (!pieceMatrix(from, to)) return
        withTransform({ transform(pieceM) }) {
            drawImage(img, IntOffset(r[0], r[1]), IntSize(r[2], r[3]), IntOffset.Zero, IntSize(r[2], r[3]), alpha, Fill, filter, blend, FilterQuality.None)
        }
    }

    /** [pieceM] set to the projective map from quad [from] (a piece's own pixels) to quad [to] (the screen); false if none. */
    private fun pieceMatrix(from: FloatArray, to: FloatArray): Boolean {
        if (!com.lifemax.rpg.feature.habits.domain.QuadMap.quadToQuad(from, to, pieceMap, pieceA, pieceB)) return false
        val m = pieceMap
        val v = pieceM.values
        v.fill(0f)
        v[0] = m[0].toFloat(); v[4] = m[1].toFloat(); v[12] = m[2].toFloat()
        v[1] = m[3].toFloat(); v[5] = m[4].toFloat(); v[13] = m[5].toFloat()
        v[3] = m[6].toFloat(); v[7] = m[7].toFloat(); v[15] = m[8].toFloat()
        v[10] = 1f
        return true
    }

    /**
     * Each clock's hands at this hour: one small picture per clock (its [ModelPart.clock] array, by identity), painted
     * again only when the minute changes; a few slots, so two clocks in one frame do not repaint each other's. Each
     * picture is made blank and mutable (ImageBitmap(n, n)) and then written: on Android a picture made from pixels is
     * immutable and cannot be written again.
     */
    private val clockOf = arrayOfNulls<IntArray>(CLOCK_SLOTS)
    private val clockImgs = arrayOfNulls<ImageBitmap>(CLOCK_SLOTS)
    private val clockMinute = IntArray(CLOCK_SLOTS) { Int.MIN_VALUE }
    private var clockNext = 0
    private var clockPx = IntArray(0)
    private var clockShaft = BooleanArray(0)

    /**
     * A modelled clock's hands ([ModelPart.clock]) at the hour, in its front piece's pixels (the transform maps those to
     * the screen): pixel art in the dial's own cells, lit as the front is. [mirror]: the face shows its picture the
     * other way round (a side seen from the left, its front edge on the right), so the hands go round the other way
     * in it, to read clockwise.
     */
    private fun DrawScope.clockHands(c: IntArray, mirror: Boolean) {
        val n = com.lifemax.rpg.feature.habits.domain.ClockHands.size(c[3])
        val minute = kotlin.math.floor(hour * 60f).toInt()
        var slot = -1
        for (i in 0 until CLOCK_SLOTS) if (clockOf[i] === c) { slot = i; break }
        if (slot < 0) { slot = clockNext; clockNext = (clockNext + 1) % CLOCK_SLOTS; clockOf[slot] = c; clockMinute[slot] = Int.MIN_VALUE }
        var img = clockImgs[slot]
        if (img == null || img.width != n) { img = ImageBitmap(n, n); clockImgs[slot] = img; clockMinute[slot] = Int.MIN_VALUE }
        if (clockMinute[slot] != minute) {
            if (clockPx.size != n * n) { clockPx = IntArray(n * n); clockShaft = BooleanArray(n * n) }
            com.lifemax.rpg.feature.habits.domain.ClockHands.paint(clockPx, n, minute / 60f, c[3], c[4], c[5], c[6], c[7], clockShaft)
            PlatformImages.update(img, clockPx, n, n)
            clockMinute[slot] = minute
        }
        if (mirror) withTransform({ scale(-1f, 1f, Offset(c[0] + c[2] / 2f, c[1] + c[2] / 2f)) }) { clockFace(img, n, c) }
        else clockFace(img, n, c)
    }

    /** The hands' picture [img] ([n] cells square) over the dial whose hub's cell [c] gives. */
    private fun DrawScope.clockFace(img: ImageBitmap, n: Int, c: IntArray) {
        val cell = c[2]; val mid = n / 2
        drawImage(img, IntOffset.Zero, IntSize(n, n), IntOffset(c[0] - mid * cell, c[1] - mid * cell), IntSize(n * cell, n * cell),
            colorFilter = signTint(), filterQuality = FilterQuality.None)
    }

    /**
     * The light on a face turned to one side ([toSun] how much it faces the sun's side) and, with [snowToo], the snow on
     * it, over [path].
     */
    private fun DrawScope.roofLight(path: Path, toSun: Float, sky: SkyColors, snowToo: Boolean = true) {
        if (toSun > .02f) drawPath(path, faceLit, alpha = (.28f * toSun).coerceAtMost(.3f))
        else if (toSun < -.02f) drawPath(path, castInk, alpha = (.22f * -toSun).coerceAtMost(.25f))
        val snow = season.snow
        if (snowToo && snow > .01f) drawPath(path, snowInk(sky), alpha = ROOF_SNOW * snow)
    }

    /** How lit a modelled building's window [seed] is now, on its own hours: a home's, a shop's, or all night (a clock's dial). */
    private fun modelLit(m: com.lifemax.rpg.feature.habits.domain.ModelShape, seed: Int, sky: SkyColors): Float {
        val kind = when (m.lights) { "shop" -> Dusk.SHOP; "lamp" -> Dusk.LAMP; else -> Dusk.HOME }
        return (sky.dark * Dusk.lightsOn(seed, hour, kind)).coerceIn(0f, 1f)
    }

    /**
     * The windows of a front laid at [fx] across, its foot at [y0], its piece [r] (its panes in that piece's pixels): the
     * sky and the street mirrored in the glass, the rain on it, and its light at night on the building's hours.
     */
    private fun DrawScope.modelPanes(v: com.lifemax.rpg.feature.habits.domain.ModelView, ma: ModelArt, panes: List<FloatArray>, r: IntArray, fx: Float, y0: Float, d: Float, seed: Int, sky: SkyColors) {
        val k = v.k
        val top = y0 + r[3]
        for (i in panes.indices) {
            val p = panes[i]
            val x = v.x(fx + p[0], d); val y = v.y(top - p[1], d); val ww = p[2] * k; val hh = p[3] * k
            val pane = seed * 31 + i
            val on = modelLit(ma.shape, pane, sky)
            if (p.size < 5 || p[4] > .5f) {
                // a square pane mirrors the sky and gathers the rain (a round one, a clock's dial, would show the square)
                val mirror = fresnel * (1f + PANE_FRESNEL * GlassMap.tilt(pane))
                mirrorSky(x, y, ww, hh, min(1f, FacadeLook.reflection(on, sky.dark) * mirror * .4f), pane, 1f - .7f * on)
                glassRain(x, y, ww, hh, glassSeconds, sky)
            }
            val glow = ma.glow
            if (on > .01f && glow != null) {
                part(glow, r[0] + p[0].toInt(), r[1] + p[1].toInt(), p[2].toInt(), p[3].toInt(), x, y, ww, hh, alpha = on * .9f, blend = BlendMode.Overlay)
                part(glow, r[0] + p[0].toInt(), r[1] + p[1].toInt(), p[2].toInt(), p[3].toInt(), x, y, ww, hh, alpha = on * .35f, blend = BlendMode.Plus)
            }
        }
    }

    /**
     * One box of a villa or a landmark: its shown side (the art's side wall laid on it, its windows lit at night, or a
     * plain face), its top (the art's boards, tiles or a colour; snow on it), its front (the art's own, which may reach
     * above the box: a gable's face over a wall) lit as the houses' fronts are, and its windows.
     */
    private fun DrawScope.modelBox(v: com.lifemax.rpg.feature.habits.domain.ModelView, p: com.lifemax.rpg.feature.habits.domain.ModelPart, ma: ModelArt, sky: SkyColors, seed: Int) {
        val ft = p.ft
        if (ft == null && p.st == null && p.tt == null) {
            // a plain box (a rail, a post, a step): as a house's porch boxes are drawn
            houseBox(v, p.x0, p.x1, p.y0, p.y1, p.d0, if (p.card) p.d0 + .01f else p.d1, rgb(if (p.top >= 0) p.top else p.front), rgb(p.front), rgb(p.side), sky, outline = p.outline)
            return
        }
        val k = v.k
        val img = ma.atlas
        val d0 = p.d0
        val d1 = if (p.card) p.d0 + .01f else p.d1
        val snowy = season.snow
        val toSun = if (sideShift < 0f) -sunSide else sunSide
        // --- the shown side
        if (v.showsSide && d1 > d0 + .5f) {
            val xs = if (v.rightSide) p.x1 else p.x0
            val path = quadPath(v.x(xs, d0), v.y(p.y1, d0), v.x(xs, d1), v.y(p.y1, d1), v.x(xs, d1), v.y(p.y0, d1), v.x(xs, d0), v.y(p.y0, d0))
            val st = p.st
            if (st != null) {
                val t = pieceTo
                t[0] = v.x(xs, d0); t[1] = v.y(p.y1, d0); t[2] = v.x(xs, d1); t[3] = v.y(p.y1, d1)
                t[4] = v.x(xs, d1); t[5] = v.y(p.y0, d1); t[6] = v.x(xs, d0); t[7] = v.y(p.y0, d0)
                val f = pieceFrom
                // the side picture's columns run from the front back; its rows from the box's top down (a short piece,
                // a railing's, repeats along a long side)
                val w = st[2].toFloat(); val h = st[3].toFloat()
                val reps = if (p.y1 - p.y0 < h - .5f || st[2] >= (d1 - d0) - 1f) 1 else max(1, kotlin.math.round((d1 - d0) / st[2]).toInt())
                val q = pieceRep
                f[0] = 0f; f[1] = 0f; f[2] = w; f[3] = 0f; f[4] = w; f[5] = h; f[6] = 0f; f[7] = h
                for (j in 0 until reps) {
                    val a0 = j.toFloat() / reps; val a1 = (j + 1f) / reps
                    q[0] = t[0] + (t[2] - t[0]) * a0; q[1] = t[1] + (t[3] - t[1]) * a0
                    q[2] = t[0] + (t[2] - t[0]) * a1; q[3] = t[1] + (t[3] - t[1]) * a1
                    q[4] = t[6] + (t[4] - t[6]) * a1; q[5] = t[7] + (t[5] - t[7]) * a1
                    q[6] = t[6] + (t[4] - t[6]) * a0; q[7] = t[7] + (t[5] - t[7]) * a0
                    pieceOnto(img, st, f, q, null)
                }
                drawPath(path, Color(0xFF0A1024), alpha = .2f)
                sideLight(path, 1f)
                if (blue > .01f) drawPath(path, BlueCast, alpha = .35f * blue, blendMode = BlendMode.Multiply)
                if (nightOn) drawPath(path, nightInk, blendMode = BlendMode.Multiply)
                val glow = ma.glow
                if (glow != null && p.sideGlow) {
                    val on = modelLit(ma.shape, seed * 17 + 5, sky)
                    if (on > .01f) {
                        f[0] = 0f; f[1] = 0f; f[2] = w; f[3] = 0f; f[4] = w; f[5] = h; f[6] = 0f; f[7] = h
                        pieceOnto(glow, st, f, t, null, alpha = on * .9f, blend = BlendMode.Overlay)
                        pieceOnto(glow, st, f, t, null, alpha = on * .3f, blend = BlendMode.Plus)
                    }
                }
                // the clock on this side too (a side wearing the front's picture: the clock tower's)
                val ck = p.clock
                if (ck != null && reps == 1 && st.contentEquals(p.ft)) {
                    f[0] = 0f; f[1] = 0f; f[2] = w; f[3] = 0f; f[4] = w; f[5] = h; f[6] = 0f; f[7] = h
                    if (pieceMatrix(f, t)) withTransform({ transform(pieceM) }) { clockHands(ck, !v.rightSide) }
                }
            } else {
                val sideC = if (toSun > .02f) lerp(rgb(p.side), Color.White, .22f * toSun) else lerp(rgb(p.side), Color(0xFF1A1830), .2f + .2f * -toSun)
                drawPath(path, houseInk(sideC))
            }
        }
        // --- the top
        val tt = p.tt
        if ((p.top >= 0 || tt != null) && d1 > d0 + .5f) {
            if (tt != null) levelStrips(img, tt[0], tt[1], tt[2], tt[3], v.x(p.x0, d0), v.y(p.y1, d0), (p.x1 - p.x0) * k, v.lift(d1 - d0), v.lean(d1 - d0), signTint())
            val path = quadPath(v.x(p.x0, d0), v.y(p.y1, d0), v.x(p.x1, d0), v.y(p.y1, d0), v.x(p.x1, d1), v.y(p.y1, d1), v.x(p.x0, d1), v.y(p.y1, d1))
            if (tt == null) drawPath(path, houseInk(lerp(rgb(p.top), Color.White, .16f * (.5f + .5f * direct))))
            if (snowy > .01f) drawPath(path, houseInk(snowInk(sky)), alpha = ROOF_SNOW * snowy)
        }
        // --- the front
        if (ft != null) {
            val x = v.x(p.fx, d0); val top = v.y(p.y0 + ft[3], d0); val w = ft[2] * k; val h = ft[3] * k
            part(img, ft[0], ft[1], ft[2], ft[3], x, top, w, h, filter = signTint())
            // the fronts face away from the sun (it is behind the street's buildings): a little of its shade on the art
            if (direct > .02f) part(img, ft[0], ft[1], ft[2], ft[3], x, top, w, h, alpha = .1f * direct, filter = castFilter())
            if (!p.card && p.y1 - p.y0 >= 30f) {
                // a wall: the sun's wash from its lit edge, the blue hour, and the ground's shade at its foot (over the
                // wall's own rectangle only, not the sky round a gable over it)
                val wl = v.x(p.x0, d0); val wt = v.y(p.y1, d0); val wb = v.y(p.y0, d0); val ww = (p.x1 - p.x0) * k
                facadeLight(wt, wb, 1f, wl, ww)
                withTransform({ translate(0f, wb) }) { drawRect(aoWall, Offset(wl, -14f), Size(ww, 14f)) }
            }
            if (p.panes.isNotEmpty()) modelPanes(v, ma, p.panes, ft, p.fx, p.y0, d0, seed + p.x0.toInt(), sky)
            // the clock's hands, over its dial (and its light at night: they stand dark against it)
            val ck = p.clock
            if (ck != null) withTransform({ translate(x, top); scale(k, k, Offset.Zero) }) { clockHands(ck, false) }
        } else {
            val fx = v.x(p.x0, d0); val fy = v.y(p.y1, d0)
            drawRect(houseInk(lerp(rgb(p.front), castInk, .1f * direct)), Offset(fx, fy), Size((p.x1 - p.x0) * k, (p.y1 - p.y0) * k))
        }
    }

    /** A rounded wall's or a turned wall's plan points (x, d along it) and its strip's pieces, reused frame to frame. */
    private var stripX = FloatArray(0)
    private var stripD = FloatArray(0)
    private val stripPiece = IntArray(4)
    private val stripFrom = FloatArray(8)

    /**
     * A rounded wall ([ModelPart.CYL]) or a wall turned off the street's line ([ModelPart.WALL]): its strip laid on it a
     * column at a time, each column a flat facet between two points of its curve (a turned wall is one facet), drawn
     * where the facet faces the view (a rail's, both sides: its far side first); each lit by which way it faces, as a box's
     * front and its side are, its windows lit at night; then a rounded wall's top (a ring's: the band between its two
     * curves), snow on it.
     */
    private fun DrawScope.stripPart(v: com.lifemax.rpg.feature.habits.domain.ModelView, p: com.lifemax.rpg.feature.habits.domain.ModelPart, ma: ModelArt, sky: SkyColors, seed: Int) {
        val ft = p.ft
        val arc = p.arc
        val n = if (arc == null) 1 else ft?.get(2) ?: 24
        if (stripX.size < n + 1) { stripX = FloatArray(n + 1); stripD = FloatArray(n + 1) }
        val xs = stripX; val ds = stripD
        if (arc == null) { xs[0] = p.x0; ds[0] = p.d0; xs[1] = p.x1; ds[1] = p.d1 }
        else for (j in 0..n) {
            val t = Math.toRadians((arc[4] + (arc[5] - arc[4]) * j / n).toDouble())
            xs[j] = arc[0] + arc[2] * kotlin.math.sin(t).toFloat(); ds[j] = arc[1] - arc[3] * kotlin.math.cos(t).toFloat()
        }
        val img = ma.atlas
        val glow = ma.glow
        val on = if (glow != null && p.sideGlow) modelLit(ma.shape, seed * 17 + 5, sky) else 0f
        val cw = if (ft == null) 0 else ft[2] / n
        val toSun = sunSide
        // the far side of a rail first (it shows through between its posts), then the side facing the view
        for (pass in (if (p.card) 0 else 1)..1) {
            for (j in 0 until n) {
                val ax = v.x(xs[j], ds[j]); val bx = v.x(xs[j + 1], ds[j + 1])
                if ((bx > ax) != (pass == 1)) continue
                // (each facet reaches a little into the next, which covers it: no hairline of sky between them)
                val e = if (j < n - 1) .35f else 0f
                val x1 = xs[j + 1] + (xs[j + 1] - xs[j]) * e; val d1 = ds[j + 1] + (ds[j + 1] - ds[j]) * e
                val t = pieceTo
                t[0] = ax; t[1] = v.y(p.y1, ds[j]); t[2] = v.x(x1, d1); t[3] = v.y(p.y1, d1)
                t[4] = t[2]; t[5] = v.y(p.y0, d1); t[6] = ax; t[7] = v.y(p.y0, ds[j])
                val path = quadPath(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
                if (ft != null) {
                    val r = stripPiece
                    r[0] = ft[0] + j * cw; r[1] = ft[1]; r[2] = cw; r[3] = ft[3]
                    val f = stripFrom
                    f[0] = 0f; f[1] = 0f; f[2] = cw.toFloat(); f[3] = 0f; f[4] = f[2]; f[5] = ft[3].toFloat(); f[6] = 0f; f[7] = ft[3].toFloat()
                    pieceOnto(img, r, f, t, signTint())
                } else drawPath(path, houseInk(rgb(p.front)))
                // which way the facet faces: its outward normal in plan (across, back)
                val ex = xs[j + 1] - xs[j]; val ed = ds[j + 1] - ds[j]
                val len = kotlin.math.hypot(ex, ed).coerceAtLeast(1e-4f)
                val nx = (if (pass == 1) ed else -ed) / len; val nd = (if (pass == 1) -ex else ex) / len
                if (p.card) continue
                val lit = toSun * nx
                if (lit > .02f) drawPath(path, Color.White, alpha = .26f * lit)
                else if (lit < -.02f) drawPath(path, Color.Black, alpha = .18f * -lit)
                if (abs(nx) > .05f) drawPath(path, Color(0xFF0A1024), alpha = .2f * abs(nx))
                if (direct > .02f && nd < 0f) drawPath(path, castInk, alpha = .1f * direct * -nd)
                if (on > .01f && glow != null && ft != null) {
                    pieceOnto(glow, stripPiece, stripFrom, t, null, alpha = on * .9f, blend = BlendMode.Overlay)
                    pieceOnto(glow, stripPiece, stripFrom, t, null, alpha = on * .35f, blend = BlendMode.Plus)
                }
            }
        }
        // --- a rounded wall's top: to its centre, or a ring's band between its curves
        if (arc == null || p.card || p.top < 0) return
        val path = housePath
        path.rewind()
        path.moveTo(v.x(xs[0], ds[0]), v.y(p.y1, ds[0]))
        for (j in 1..n) path.lineTo(v.x(xs[j], ds[j]), v.y(p.y1, ds[j]))
        if (arc[6].isNaN()) path.lineTo(v.x(arc[0], arc[1]), v.y(p.y1, arc[1]))
        else for (j in n downTo 0) {
            val t = Math.toRadians((arc[4] + (arc[5] - arc[4]) * j / n).toDouble())
            val x = arc[0] + arc[6] * kotlin.math.sin(t).toFloat(); val d = arc[1] - arc[7] * kotlin.math.cos(t).toFloat()
            path.lineTo(v.x(x, d), v.y(p.y1, d))
        }
        path.close()
        drawPath(path, houseInk(lerp(rgb(p.top), Color.White, .16f * (.5f + .5f * direct))))
        val snow = season.snow
        if (snow > .01f) drawPath(path, houseInk(snowInk(sky)), alpha = ROOF_SNOW * snow)
    }

    /**
     * A front-facing gable: the triangle end faces the street and its two slopes run back from it. Seen from the street,
     * a little above, each slope is its picture from above squeezed into a band over its rake (one projective map from
     * the art's quad to the slope's), where the view sees it from above: lit by the side it faces, snow on it, a lip of
     * snow along the rake. Under its straight front edge, the roof's own edge colour down to the face's stepped rake
     * (the face, drawn after it, hides the rest). On the shown side the eave's board runs back along the wall's top, its
     * shade under it ([gableEave]; [eaves] false when the box under it is still to come and draws them). Then its face,
     * if it stands on its own (a box's front carries the main one), and the face's window.
     */
    private fun DrawScope.frontGable(v: com.lifemax.rpg.feature.habits.domain.ModelView, p: com.lifemax.rpg.feature.habits.domain.ModelPart, ma: ModelArt, sky: SkyColors, seed: Int, eaves: Boolean = true) {
        val k = v.k
        val ax = p.apex
        val snow = season.snow
        for (side in 0..1) {
            val left = side == 0
            val r = (if (left) p.left else p.right) ?: continue
            val pts = (if (left) p.leftP else p.rightP) ?: continue
            val xe = if (left) p.x0 else p.x1
            // the front eave, the apex, the ridge's back end, the back eave
            val t = pieceTo
            t[0] = v.x(xe, p.d0); t[1] = v.y(p.y0, p.d0)
            t[2] = v.x(ax, p.d0); t[3] = v.y(p.y1, p.d0)
            t[4] = v.x(ax, p.d1); t[5] = v.y(p.y1, p.d1)
            t[6] = v.x(xe, p.eaveBack); t[7] = v.y(p.y0, p.eaveBack)
            // its verge, the roof's edge seen edge on along the rake, in the roof's edge colour: from a little above the
            // slope's straight front edge down past the face's stepped rake (its steps sit up to two and a half pixels
            // below the line; the face, drawn later, covers the rest). It shows whether or not the slope does: a slope
            // the view sees only as a sliver, or not at all, still has its edge there (without it the art's checker of
            // rake ink, as over the villa's door, reads as a dotted outline against what is behind)
            val lipC = houseInk(if (p.lip >= 0) rgb(p.lip) else lerp(rgb(p.front), Color.Black, .35f))
            rowsPath.rewind()
            rowQuad(t[0], t[1] - VERGE * k, t[2], t[3] - VERGE * k, t[2], t[3] + GABLE_LIP * k, t[0], t[1] + GABLE_LIP * k)
            drawPath(rowsPath, lipC)
            // the view sees the slope from above when its eave runs back on its outer side of the rake; else the other
            // slope and the ridge hide it, and only the snow lying along its rake shows
            if (!com.lifemax.rpg.feature.habits.domain.RoofFaces.gableSlopeSeen(left, t)) {
                if (snow > .01f) {
                    rowsPath.rewind()
                    rowQuad(t[0], t[1] - (VERGE + .2f) * k, t[2], t[3] - (VERGE + .2f) * k, t[2], t[3] + GABLE_LIP * k, t[0], t[1] + GABLE_LIP * k)
                    drawPath(rowsPath, snowInk(sky), alpha = ROOF_SNOW * snow)
                }
                continue
            }
            for (i in 0 until 8) pieceFrom[i] = pts[i]
            // (within its quad: where the slope runs far back to a short eave, as the cross gable's into the main roof,
            // the map squeezes the piece's edge pixels near its far corner out past the quad)
            clipPath(quadPath(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])) { pieceOnto(ma.atlas, r, pieceFrom, t, signTint()) }
            // the light and the snow on the slope and its edge as one face
            val path = housePath
            path.rewind()
            path.moveTo(t[0], t[1] + GABLE_LIP * k); path.lineTo(t[2], t[3] + GABLE_LIP * k); path.lineTo(t[2], t[3])
            path.lineTo(t[4], t[5]); path.lineTo(t[6], t[7]); path.lineTo(t[0], t[1]); path.close()
            roofLight(path, if (left) -sunSide else sunSide, sky)
            if (snow > .01f) {
                rowsPath.rewind()
                rowQuad(t[0], t[1] - 1.6f * k, t[2], t[3] - 1.6f * k, t[2], t[3], t[0], t[1])
                drawPath(rowsPath, snowInk(sky), alpha = ROOF_SNOW * snow)
            }
        }
        if (eaves) gableEave(v, p, sky)
        val face = p.face ?: return
        val x = v.x(p.fx, p.d0); val top = v.y(p.faceY + face[3], p.d0)
        part(ma.atlas, face[0], face[1], face[2], face[3], x, top, face[2] * k, face[3] * k, filter = signTint())
        if (direct > .02f) part(ma.atlas, face[0], face[1], face[2], face[3], x, top, face[2] * k, face[3] * k, alpha = .1f * direct, filter = castFilter())
        if (snow > .01f) {
            // the snow over the apex, where the face's own ridge board end stands a pixel or two above the slopes' lips
            val axs = v.x(ax, p.d0); val ays = v.y(p.y1, p.d0)
            rowsPath.rewind()
            rowQuad(axs - 3f * k, ays - 1.6f * k, axs + 3f * k, ays - 1.6f * k, axs + 3f * k, ays + 3.5f * k, axs - 3f * k, ays + 3.5f * k)
            drawPath(rowsPath, snowInk(sky), alpha = ROOF_SNOW * snow)
        }
        if (p.panes.isNotEmpty()) modelPanes(v, ma, p.panes, face, p.fx, p.faceY, p.d0, seed + 77, sky)
    }

    /**
     * A front gable's eave along the side the view shows: its board in the rake's colour running back from the front
     * eave, and the shade it throws on the top of the wall under it. Drawn after that wall's box, which would paint over
     * both.
     */
    private fun DrawScope.gableEave(v: com.lifemax.rpg.feature.habits.domain.ModelView, p: com.lifemax.rpg.feature.habits.domain.ModelPart, sky: SkyColors) {
        if (!v.showsSide || p.eaveBack <= p.d0 + 1f) return
        val k = v.k
        val snow = season.snow
        val left = !v.rightSide
        val xe = if (left) p.x0 else p.x1
        // (a single-pitch roof's high side has no eave: its edge there is the ridge)
        val ye = if (p.monopitch && kotlin.math.abs(xe - p.apex) < 1f) p.y1 else p.y0
        val fx = v.x(xe, p.d0); val fy = v.y(ye, p.d0); val bx = v.x(xe, p.eaveBack); val by = v.y(ye, p.eaveBack)
        var c = lerp(rgb(p.rake), Color.Black, .1f)
        if (snow > .01f) c = lerp(c, snowInk(sky), .7f * snow)
        rowsPath.rewind()
        rowQuad(fx, fy, bx, by, bx, by + 2.5f * k, fx, fy + 2.5f * k)
        drawPath(rowsPath, houseInk(c))
        rowsPath.rewind()
        val inX = if (left) 2f else -2f
        rowQuad(v.x(xe + inX, p.d0), fy + 2.5f * k, v.x(xe + inX, p.eaveBack), by + 2.5f * k,
            v.x(xe + inX, p.eaveBack), by + (5f + 3f * direct) * k, v.x(xe + inX, p.d0), fy + (5f + 3f * direct) * k)
        drawPath(rowsPath, castInk, alpha = .25f + .15f * direct)
    }

    /**
     * A hipped roof: four sloped faces and a flat top. The side faces first, where the view sees them from above (wedges
     * over the hips, each its tiles laid on its quad: their joints where they fall far enough apart on the screen, each
     * course one colour where they would not), each with a band of the hip's crease along it; then the flat top, then
     * the front face over both: the art's rows one screen row each (the rise was chosen so), its eave put on the
     * screen's pixels, the hips' cap tiles on it. Lit as the houses' slopes are (the front faces the street and the sun
     * is behind it), the sides by their side; snow on all, and a lip of it along the eave.
     */
    private fun DrawScope.hippedRoof(v: com.lifemax.rpg.feature.habits.domain.ModelView, p: com.lifemax.rpg.feature.habits.domain.ModelPart, ma: ModelArt, sky: SkyColors) {
        val k = v.k
        val snow = season.snow
        // the front face's place: its eave on the screen's pixels, its top edge exactly its picture's rows above
        val fp = p.frontPic
        fun snap(a: Float, at: Float = 0f) = if (unitPx > 0f) kotlin.math.round((a + at) * unitPx) / unitPx - at else a
        val ex = snap(v.x(p.x0, p.d0), buildingX); val ey = snap(v.y(p.y0, p.d0))
        val w = (p.x1 - p.x0) * k
        val dy = (fp?.get(3) ?: 0) * k
        val shift = v.lean(p.td0 - p.d0)
        val tl = ex + (p.tx0 - p.x0) * k + shift; val tr = ex + (p.tx1 - p.x0) * k + shift
        val hip = p.hip
        for (side in 0..1) {
            val left = side == 0
            val r = (if (left) p.left else p.right) ?: continue
            val pts = (if (left) p.leftP else p.rightP) ?: continue
            val xe = if (left) p.x0 else p.x1; val xt = if (left) p.tx0 else p.tx1
            // the front eave, the back eave, the top's back end, its front end
            val t = pieceTo
            t[0] = v.x(xe, p.d0); t[1] = v.y(p.y0, p.d0)
            t[2] = v.x(xe, p.d1); t[3] = v.y(p.y0, p.d1)
            t[4] = v.x(xt, p.td1); t[5] = v.y(p.y1, p.td1)
            t[6] = v.x(xt, p.td0); t[7] = v.y(p.y1, p.td0)
            if (!com.lifemax.rpg.feature.habits.domain.RoofFaces.hipSideSeen(left, t)) continue
            for (i in 0 until 8) pieceFrom[i] = pts[i]
            // its tiles, the joints along each course where they fall far enough apart on the screen to be drawn, each
            // course one colour where the view sees the face so nearly edge on that they would scatter into hatching
            // (between, the one fades into the other, so nothing jumps as the street scrolls)
            val flush = if (left) p.leftFlush else p.rightFlush
            val joints = if (flush == null) 1f else ((kotlin.math.abs(v.lean(HIP_TILE)) * (if (unitPx > 0f) unitPx else 1f) - 2f) / 1.5f).coerceIn(0f, 1f)
            if (flush != null && joints < 1f) pieceOnto(ma.atlas, flush, pieceFrom, t, signTint())
            if (joints > 0f) pieceOnto(ma.atlas, r, pieceFrom, t, signTint(), alpha = joints)
            // (on the sunny side half the gable slopes' wash: the full wash of white over these dark red tiles, seen
            // nearly edge on, reads as a pale pink film rather than tiles in the sun)
            val toSun = if (left) -sunSide else sunSide
            roofLight(quadPath(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]), if (toSun > 0f) .5f * toSun else toSun, sky, snowToo = false)
            if (snow > .01f) {
                // the snow on the piece's own pixels, not its quad: its tiles reach a pixel past the quad's straight edges
                snowInk(sky)
                pieceOnto(ma.atlas, r, pieceFrom, t, snowFilter, alpha = ROOF_SNOW * snow)
            }
            // the hip between it and the front face: the front face's rows end in steps either side of the straight
            // line, and so do this face's tiles; a band of the hip's crease under the front face fills between them
            // (no sky through the hip) and shows as the art's dark line along it
            if (hip != null && fp != null) {
                val hx = if (left) ex else ex + w; val htx = if (left) tl else tr
                val b = 1.5f * k
                rowsPath.rewind()
                rowQuad(hx - b, ey, htx - b, ey - dy, htx + b, ey - dy, hx + b, ey)
                drawPath(rowsPath, houseInk(lerp(rgb(hip[1]), Color.White, if (snow > .01f) .85f * snow else 0f)))
            }
        }
        if (p.td1 > p.td0 && p.tx1 > p.tx0) {
            val fl = p.flat
            if (fl != null) levelStrips(ma.atlas, fl[0], fl[1], fl[2], fl[3], v.x(p.tx0, p.td0), v.y(p.y1, p.td0), (p.tx1 - p.tx0) * k, v.lift(p.td1 - p.td0), v.lean(p.td1 - p.td0), signTint())
            val path = quadPath(v.x(p.tx0, p.td0), v.y(p.y1, p.td0), v.x(p.tx1, p.td0), v.y(p.y1, p.td0), v.x(p.tx1, p.td1), v.y(p.y1, p.td1), v.x(p.tx0, p.td1), v.y(p.y1, p.td1))
            if (fl == null) drawPath(path, houseInk(rgb(p.front)))
            if (snow > .01f) {
                drawPath(path, snowInk(sky), alpha = ROOF_SNOW * snow)
                // (its rows' strips reach a little past the quad's slanted ends: snow on those pixels too)
                if (fl != null) clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) {
                    levelStrips(ma.atlas, fl[0], fl[1], fl[2], fl[3], v.x(p.tx0, p.td0), v.y(p.y1, p.td0), (p.tx1 - p.tx0) * k, v.lift(p.td1 - p.td0), v.lean(p.td1 - p.td0), snowFilter, alpha = ROOF_SNOW * snow)
                }
            }
        }
        if (fp == null) return
        levelStrips(ma.atlas, fp[0], fp[1], fp[2], fp[3], ex, ey, w, dy, shift, signTint())
        // the cap tiles along each hip, where the art draws them lighter than the crease (the school's): a pixel's row
        // just inside the front face's edge
        if (hip != null && hip[0] != hip[1]) {
            val b = .5f * k
            rowsPath.rewind()
            rowQuad(ex + b, ey, tl + b, ey - dy, tl + 3f * b, ey - dy, ex + 3f * b, ey)
            rowQuad(ex + w - 3f * b, ey, tr - 3f * b, ey - dy, tr - b, ey - dy, ex + w - b, ey)
            drawPath(rowsPath, houseInk(rgb(hip[0])))
        }
        val face = quadPath(ex, ey, ex + w, ey, tr, ey - dy, tl, ey - dy)
        val key = light
        if (key != null) {
            val pitch = kotlin.math.atan2(p.y1 - p.y0, p.td0 - p.d0)
            val sun = key.strength * (1f - key.flat) * (key.down * kotlin.math.cos(pitch) - key.out * kotlin.math.sin(pitch)) * (1f - .6f * key.cool)
            if (sun > .01f) drawPath(face, faceLit, alpha = (.3f * sun).coerceAtMost(.35f))
            else if (sun < -.01f) drawPath(face, castInk, alpha = (.25f * -sun).coerceAtMost(.3f))
        }
        if (snow > .01f) {
            drawPath(face, snowInk(sky), alpha = ROOF_SNOW * snow)
            // and on the face's own pixels where they reach a little past the quad (at its top corners)
            clipPath(face, androidx.compose.ui.graphics.ClipOp.Difference) {
                levelStrips(ma.atlas, fp[0], fp[1], fp[2], fp[3], ex, ey, w, dy, shift, snowFilter, alpha = ROOF_SNOW * snow)
            }
            // the hips: the face's rows end in steps either side of the straight line, the side faces' tiles too (on
            // to the flat top's corner, which stands a pixel or so off the face's top row: its eave is on the screen's
            // pixels)
            val hip = 2.4f * k
            val run = kotlin.math.hypot(tl - ex, dy).coerceAtLeast(1f); val on = 2f * k / run
            drawLine(snowInk, Offset(ex, ey), Offset(tl + (tl - ex) * on, ey - dy - dy * on), hip, alpha = ROOF_SNOW * snow)
            drawLine(snowInk, Offset(ex + w, ey), Offset(tr + (tr - ex - w) * on, ey - dy - dy * on), hip, alpha = ROOF_SNOW * snow)
            // (hanging a little over the eave: the art draws the tiles' lowest ends on the wall's top rows)
            cap(ex - .4f * k, ey - 1.6f * k, w + .8f * k, 3.8f * k, .9f * snow)
        }
    }

    /**
     * One of OUR villas or landmarks ([ModelArt]): it stands back in its yard (so it moves against the shops and lamps as
     * the street scrolls), built from its parts back to front: boxes wearing the art's own fronts, sides, decks and
     * roofs, front-facing gables and hipped roofs; its yard's ground, its shade on it, its fence or hedge; its lamp by
     * the door at dusk and its chimneys' smoke in the cold months.
     */
    private fun DrawScope.modelBuilding(art: BuildingArt, ma: ModelArt, sky: SkyColors, seconds: Float) {
        val sh = ma.shape
        val s = sideShift
        // (at the mouth of the side street no side face shows: the stage draws the side street there)
        val v = modelView.set(sh, s, unitPx, buildingX, side = art.stack.id !in StageTuning.cornerIds)
        val k = v.k
        val snow = season.snow
        val key = light
        val seed = art.stack.id.hashCode()
        // --- the yard: its ground from the pavement back past the building, and the building's shade on it
        val deep = sh.setback + sh.depth
        val backY = -B.DY * deep / com.lifemax.rpg.feature.habits.domain.HouseView.DEPTH
        val backX = s * deep / com.lifemax.rpg.feature.habits.domain.HouseView.DEPTH
        val lx = -B.W / 2f + sh.frontX * k; val lw = sh.width * k
        ma.lawn?.let { levelStrips(it, 0, 0, it.width, it.height, lx, 0f, lw, -backY, backX) }
        val yard = quadPath(lx, 0f, lx + lw, 0f, lx + lw + backX, backY, lx + backX, backY)
        if (snow > .01f) drawPath(yard, snowInk(sky), alpha = .82f * snow)
        if (blue > .01f) drawPath(yard, BlueCast, alpha = .35f * blue, blendMode = BlendMode.Multiply)
        if (nightOn) drawPath(yard, nightInk, blendMode = BlendMode.Multiply)
        val wl = v.x(sh.wall0, 0f); val wr = v.x(sh.wall1, 0f); val foot = v.y(0f, 0f)
        var yardShade = 0f
        if (key != null && key.strength > .02f && key.reach > .05f) {
            // the light is behind the street's buildings: the yard in front of the walls is in their shade
            val lean = (SceneLight.lean(key, offset(buildingX)) / key.reach * -foot).coerceIn(-40f, 40f)
            val a = (if (key.cool > .5f) MOON_SHADE else SUN_SHADE) * key.strength * (1f - .6f * key.flat)
            drawPath(quadPath(wl, foot, wr, foot, wr + lean, 0f, wl + lean, 0f), shade(key, sky), alpha = a)
            yardShade = a
        }
        withTransform({ translate(0f, foot); scale(1f, .45f, Offset.Zero) }) { drawRect(aoGround, Offset(wl - k, 0f), Size(wr - wl + 2f * k, 8f)) }

        // --- its parts, back to front
        for (i in sh.parts.indices) {
            val p = sh.parts[i]
            when (p.type) {
                com.lifemax.rpg.feature.habits.domain.ModelPart.BOX -> modelBox(v, p, ma, sky, seed + i * 7)
                com.lifemax.rpg.feature.habits.domain.ModelPart.GABLE -> frontGable(v, p, ma, sky, seed + i * 7, eaves = !sh.eaveLater[i])
                com.lifemax.rpg.feature.habits.domain.ModelPart.CYL, com.lifemax.rpg.feature.habits.domain.ModelPart.WALL -> stripPart(v, p, ma, sky, seed + i * 7)
                else -> hippedRoof(v, p, ma, sky)
            }
            // the eave boards of the gable this box carries, over its side wall
            val e = sh.eaveOn[i]
            if (e >= 0) gableEave(v, sh.parts[e], sky)
        }

        // --- the yard's fence or hedge along the street, open at the gate
        ma.fence?.let { fe ->
            val dd = sh.fenceD - sh.setback
            val fx = v.x(0f, dd); val fy = v.y(sh.fenceFoot, dd)
            part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, filter = signTint())
            val cast = max(.12f * direct, .85f * yardShade)
            if (cast > .01f) part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, alpha = cast, filter = castFilter())
            if (snow > .01f) {
                snowInk(sky)
                ma.fenceSnow?.let { fs -> part(fs, 0, 0, fs.width, fs.height, fx, fy, fe.width * k, fe.height * k, alpha = .92f * snow, filter = snowFilter) }
                part(fe, 0, 0, fe.width, fe.height, fx, fy, fe.width * k, fe.height * k, alpha = .22f * snow, filter = snowFilter)
            }
        }
        // --- the lamp by the door: lit from dusk, a warm pool on the wall and the deck below it
        sh.lamp?.let { lp ->
            val x = v.x(lp[0], lp[2]); val y = v.y(lp[1], lp[2])
            drawRect(houseInk(Color(0xFF2A2633)), Offset(x - .5f * k, y - 1f * k), Size(4f * k, 5f * k))
            val on = (sky.dark * Dusk.lightsOn(seed + 7, hour, Dusk.LAMP)).coerceIn(0f, 1f)
            drawRect(lerp(houseInk(Color(0xFFBFB59A)), SceneInk.WindowLit, on), Offset(x + .5f * k, y), Size(2f * k, 3f * k))
            if (on > .02f) {
                val c = Offset(x + 1.5f * k, y + 1.5f * k)
                val r = 18f * k
                withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) {
                    drawCircle(porchGlow, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus)
                }
                val gy = v.y(12f, lp[2] - 20f)
                withTransform({ translate(c.x, gy); scale(26f * k, .28f * 26f * k, Offset.Zero) }) {
                    drawCircle(porchPool, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus)
                }
            }
        }
        // --- smoke from its chimneys on cold days
        for (i in sh.smoke.indices) {
            val c = sh.smoke[i]
            houseSmoke(seed + i, v.x(c[0], c[1]), v.y(c[2], c[1]), k, seconds, sky)
        }
    }

    /**
     * Sets the light and view state for building [art] standing at [x] along its street (the sun's side on it, which
     * side face it shows, its glass), which [building] and the crossroads' own faces ([sideFace], [roofFace],
     * [roofBoard]) draw by.
     */
    fun prepare(art: BuildingArt, x: Float, sky: SkyColors, seconds: Float) {
        // which side of it the light finds, and how hard (the moon lights a little, the sun a lot)
        val key = light
        buildingX = x
        // the people seen through the windows are flat dark figures at every hour; only the lobby's
        // keep the warm, back-lit look of a lit shopfront at night ([lobbyLife])
        dayMix = 1f
        sunSide =
            if (key == null) 0f
            else SceneLight.sunSide(offset(x)) * key.strength * (1f - .6f * key.cool)
        direct = if (key == null) 0f else key.strength * (1f - key.flat) * (1f - .6f * key.cool)
        inkDark = sky.dark
        faceLit = lerp(Color.White, GoldLight, .6f * gold)
        castInk = if (key == null) Color.Black else lerp(shade(key, sky), Color.Black, .3f)
        glassTints(sky)
        // and which of its side faces the camera sees from where it stands in the view
        sideShift = shiftAt(x)
        fresnel = FRESNEL_FACE + (1f - FRESNEL_FACE) * abs(Perspective.sideAt(x, viewX, lightSpan))
        mirrorPhase = (art.stack.id.hashCode() and 0xFFFF) * .37f
        glassSeconds = seconds
        sideSeed = art.stack.id.hashCode()
    }

    /** The building [prepare]d last, for when its side windows' lights come on. */
    private var sideSeed = 0

    /**
     * One building: its park, its lobby, every storey, its roof and what stands on it, and what is
     * playing on it. [live] is today (a past week of the time-lapse has every floor done and lit).
     * [detailed] draws words, people and plaques (a building drawn small has none).
     */
    fun DrawScope.building(
        art: BuildingArt,
        motion: BuildingMotion,
        selected: String?,
        accent: Color,
        live: Boolean,
        detailed: Boolean,
        sky: SkyColors,
        lod: PeopleLod = if (detailed) PeopleLod.FULL else PeopleLod.NONE,
        x: Float = 0f,
    ) {
        // the mouth of the side street: no building, no lot (the stage draws the side street there)
        if (art.stack.id == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID) return
        val seconds = motion.seconds
        val half = B.W / 2f
        prepare(art, x, sky, seconds)
        park(art, selected, accent, seconds, sky)
        val sprite = SpriteArt.buildings[art.stack.id]
        val house = sprite?.house
        if (sprite != null && house != null) {
            // one of OUR houses: back in its yard, so nothing of it stands on the pavement
            houseBuilding(art, sprite, house, sky, seconds)
            return
        }
        val model = sprite?.model
        if (sprite != null && model != null) {
            // a villa or a landmark: back in its yard too, built from its boxes and roofs
            modelBuilding(art, model, sky, seconds)
            return
        }
        // the building's shadow on the pavement, under its front and its shown side
        val l0 = Perspective.left(sideShift)
        drawRect(aoGround, Offset(l0 + 1f, 0f), Size(Perspective.right(sideShift) - l0 + 6f, 8f))
        sprite?.let {
            // one of OUR buildings: its art on the engine's box, light and shade
            spriteBuilding(art, it, sky, seconds)
            return
        }
        lobby(art, sky, detailed, seconds, lod)
        val lit = lerp(SceneInk.WindowLitDay, SceneInk.WindowLit, sky.dark)
        val glass = lerp(SceneInk.GlassDay, SceneInk.GlassNight, sky.dark)
        val step = Ambient.step(seconds)
        // the lit floors take turns being staffed, and one of them has a walker now and then
        val crowd = art.stack.id.hashCode()
        var litFloors = 0
        var walkable = 0
        for (fa in art.floors) {
            if (!isLit(fa, live)) continue
            litFloors++
            if (fa.cast.size >= 3) walkable++
        }
        val walkerAt = Silhouettes.commuterFloor(seconds, crowd, walkable)
        var ordinal = 0
        var walkOrdinal = 0
        for (i in art.floors.indices) {
            val fa = art.floors[i]
            val b = fa.block
            val onToday = isLit(fa, live)
            val thisOrdinal = if (onToday) ordinal++ else -1
            val walker = onToday && fa.cast.size >= 3 && walkOrdinal++ == walkerAt
            val landing = motion.landing == b.id && fa.piece.kind == PieceKind.FLOOR
            val adding = motion.adding == b.id
            val drop = if (adding) (1f - motion.drop) * DROP else 0f
            val alpha = if (adding) min(1f, motion.drop * 3f) else 1f
            val squash = if (landing) motion.squash else 0f
            translate(0f, -drop) {
                scale(1f + .025f * squash, 1f - .06f * squash, Offset(0f, -fa.piece.bottom)) {
                    floor(
                        art,
                        fa,
                        motion,
                        landing,
                        alpha,
                        live,
                        detailed,
                        lit,
                        glass,
                        step,
                        sky,
                        lod,
                        Crowd(crowd, thisOrdinal, litFloors, walker),
                    )
                    if (selected == b.id && fa.piece.labelled) outline(fa.top, fa.bottom, accent)
                }
            }
            if (adding && motion.drop >= .8f) dust(fa.piece.bottom, (motion.drop - .8f) / .2f)
        }
        roof(art, live, detailed, sky, seconds)
        lampLight(art, sky, x)
        val gone = motion.felled
        if (gone != null && motion.fell < 1f) {
            val top = art.layout.labelled(gone)?.top ?: art.layout.roof
            fallen(top, gone, motion.fell)
        }
    }

    /**
     * Shooting into a low sun the street is exposed for the sky and goes dark ([Backlight.expose]);
     * a light source does not: drawn after that, over the building as [building] drew it (its
     * floors moved as they were), its lit windows given back the light the exposure took from them
     * where they are ([back]'s [Backlight.shadeAt], the building's middle at screen x [sx], the
     * pavement at [ground], [unit] px to a unit), and what is read and what is lit laid again over
     * the dark: the floors' name plaques, the selected floor's outline, the next floor's glow, the
     * lobby sign's letters and its tube, the roof's sign and the "add a floor" outline.
     */
    fun DrawScope.relight(
        art: BuildingArt,
        motion: BuildingMotion,
        selected: String?,
        accent: Color,
        live: Boolean,
        detailed: Boolean,
        sky: SkyColors,
        back: Backlight,
        sx: Float,
        ground: Float,
        unit: Float,
    ) {
        val k = back.amount
        if (k <= 0f) return
        if (SpriteArt.buildings.containsKey(art.stack.id)) return
        // the side street's mouth: no building, so no lobby name or sign tube (they floated over its road)
        if (art.stack.id == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID) return
        val seconds = motion.seconds
        val half = B.W / 2f
        val lit = lerp(SceneInk.WindowLitDay, SceneInk.WindowLit, sky.dark)
        // the lobby's sign: its letters, and its tube when it is lit
        val top = -B.LOBBY_H
        if (detailed) {
            val n = art.lobbyName
            drawText(
                n,
                SceneInk.SignText,
                Offset(-n.size.width / 2f, top + 11.5f - n.size.height / 2f),
            )
        }
        val tube = Dusk.lightsOn(art.stack.id.hashCode(), hour, Dusk.SIGN) * sky.dark
        if (tube > .01f)
            drawRect(
                SceneInk.Tube,
                Offset(-half + 13f, top + 16.6f),
                Size(B.W - 26f, .8f),
                alpha = tube,
            )
        for (fa in art.floors) {
            val b = fa.block
            val landing = motion.landing == b.id && fa.piece.kind == PieceKind.FLOOR
            val adding = motion.adding == b.id
            val drop = if (adding) (1f - motion.drop) * DROP else 0f
            val alpha = if (adding) min(1f, motion.drop * 3f) else 1f
            val squash = if (landing) motion.squash else 0f
            translate(0f, -drop) {
                scale(1f + .025f * squash, 1f - .06f * squash, Offset(0f, -fa.piece.bottom)) {
                    if (fa.piece.kind != PieceKind.RUIN) {
                        val w = fa.windows
                        for (j in 0 until fa.windowCount) {
                            val g = fa.glow[j]
                            if (g <= 0f) continue
                            val x = w[j * 4]
                            val y = fa.top + w[j * 4 + 1]
                            val ww = w[j * 4 + 2]
                            val h = w[j * 4 + 3]
                            val lost =
                                1f -
                                    back.shadeAt(
                                        sx + (x + ww / 2f) * unit,
                                        ground + (y + h / 2f) * unit,
                                    )
                            val a = alpha * g * lost * RELIGHT
                            if (a < .01f) continue
                            drawRect(
                                lit,
                                Offset(x, y),
                                Size(ww, h),
                                alpha = a,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    if (detailed && fa.name != null) plaque(fa, alpha)
                    if (selected == b.id && fa.piece.labelled) outline(fa.top, fa.bottom, accent)
                    if (live && art.stack.next == b.id && fa.piece.labelled)
                        glow(fa.top, fa.bottom, fa.facade.tint, seconds)
                }
            }
        }
        val s = art.sign
        if (s != null) {
            val open = art.stack.claimable || art.stack.claimed
            val y = -s.top
            val w = s.width
            val panel = 24f
            // a lit board is a light: laid again whole; an unlit one keeps only its words
            if (open) {
                drawRect(SceneInk.SignLit, Offset(-w / 2f, y), Size(w, panel))
                drawRect(
                    SceneInk.SignFrame,
                    Offset(-w / 2f, y),
                    Size(w, panel),
                    style = frameStroke,
                )
            }
            val words = art.treat
            if (words != null && detailed)
                drawText(
                    words,
                    if (open) SceneInk.SignLitText else SceneInk.SignOffText,
                    Offset(-words.size.width / 2f, y + panel / 2f - words.size.height / 2f),
                )
        }
        val g = art.ghost
        if (g != null && live) {
            val gh = g.width / 2f
            drawRoundRect(
                SceneInk.PlanLine,
                Offset(-gh, -g.top),
                Size(g.width, g.height),
                CornerRadius(6f),
                style = ghostLine,
            )
            val words = art.ghostWords
            if (words != null && detailed)
                drawText(
                    words,
                    SceneInk.PlanText,
                    Offset(-words.size.width / 2f, -g.middle - words.size.height / 2f),
                )
        }
    }

    /**
     * At night the street lamps near a building throw their warm light on its walls: a soft pool
     * round each lamp's lens, over the building's front and side only.
     */
    private fun DrawScope.lampLight(art: BuildingArt, sky: SkyColors, x: Float) {
        val dark = sky.dark
        if (dark <= .2f) return
        val l0 = Perspective.left(sideShift)
        val r0 = Perspective.right(sideShift)
        val height = art.layout.height
        for (i in lamps.indices) {
            val f = lamps[i]
            if (f.kind != FurnitureKind.LAMP) continue
            val lx = f.x + 11.5f - x
            if (lx < l0 - LAMP_REACH || lx > r0 + LAMP_REACH) continue
            clipRect(l0, -height, r0, 0f) {
                translate(lx, -89.5f) {
                    scale(LAMP_REACH, LAMP_REACH * .8f, Offset.Zero) {
                        drawCircle(
                            halo,
                            1f,
                            Offset.Zero,
                            alpha = .34f * dark,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the facades' light

    /**
     * How hard and sharp the key light is on the building being drawn (0 under a full overcast and
     * in the dark), the sky's dark, the colour its lit faces take (white, warmed in the golden
     * hour) and its cast shadows' (the sky's shade): set per building, read by its parts.
     */
    private var direct = 0f
    private var inkDark = 0f
    private var faceLit = Color.White
    private var castInk = Color.Black

    /**
     * The hour's sky down a curtain wall's spandrels ([glassTints], kept per sky): a unit brush,
     * the zenith at its top, the horizon and the far city low in it, the street at its foot; the
     * haze up a tall building.
     */
    private var glassSky: SkyColors? = null
    private var paneBrush: Brush = glassSheen
    private var hazeInk = Color.White

    /**
     * What the glass mirrors ([GlassSprites], [GlassMap]): the strips' weights turned into the
     * hour's colours (kept per sky), the place in them of the pane being drawn (reused), how much
     * more the glass of the building being drawn mirrors for standing to the side of the view (a
     * Fresnel term: glass seen at a slant is a better mirror), its own slide along the mirrored
     * street, and the clock the clouds in it drift by.
     */
    private var skyFilter: ColorFilter? = null
    private var streetFilter: ColorFilter? = null
    private val glassColours = FloatArray(24)
    private val glassAt = FloatArray(5)
    private var fresnel = 1f
    private var mirrorPhase = 0f
    private var glassSeconds = 0f

    /**
     * The awnings' colour filters by [AWNINGS] colour, and the night's tint over the roof's
     * furniture and the planters: kept per sky, remade when it changes.
     */
    private val awningFilters = arrayOfNulls<ColorFilter>(AWNINGS.size)
    private val awningMatrix = FloatArray(20)
    private var signFilter: ColorFilter? = null
    private var signKey = Long.MIN_VALUE

    /** The front's night and blue hour in one Modulate tint, for the partly transparent pictures above the roofline. */
    private fun signTint(): ColorFilter? {
        val b = (blue * 32f).toInt()
        val key = (b.toLong() shl 32) or (nightInk.toArgb().toLong() and 0xFFFFFFFFL)
        if (key == signKey) return signFilter
        signKey = key
        val cast = lerp(Color.White, BlueCast, .35f * b / 32f)   // what blueHour(..., .35f) multiplies over the front
        val t = Color(nightInk.red * cast.red, nightInk.green * cast.green, nightInk.blue * cast.blue)
        signFilter = if (t.red > .995f && t.green > .995f && t.blue > .995f) null else ColorFilter.tint(t, BlendMode.Modulate)
        return signFilter
    }

    private var nightFilter: ColorFilter? = null

    /**
     * The night's ambient light on the walls (the sky's, cool and low), multiplied over them so a
     * facade at night is lit by its windows, its sign and the street's lamps, not by day's light.
     */
    private var nightInk = Color.White
    private var nightOn = false

    private fun glassTints(sky: SkyColors) {
        if (glassSky === sky) return
        val inside = SceneInk.GlassNight
        paneBrush =
            Brush.verticalGradient(
                0f to lerp(lerp(sky.sky[1], sky.sky[0], .45f), inside, .3f),
                .42f to lerp(lerp(sky.sky[2], sky.sky[1], .35f), inside, .2f),
                .6f to lerp(lerp(sky.far[2], sky.sky[2], .45f), inside, .26f),
                .63f to lerp(sky.far[2], inside, .4f),
                1f to lerp(lerp(sky.ground[1], sky.far[2], .3f), inside, .5f),
                startY = 0f,
                endY = 1f,
            )
        hazeInk = lerp(sky.sky[2], sky.far[0], .4f)
        GlassMap.colours(sky, glassColours)
        skyFilter = weights(glassColours, 0)
        streetFilter = weights(glassColours, 12)
        val night = NIGHT_DEPTH * Px.smooth(.25f, 1f, sky.dark)
        nightInk = lerp(Color.White, NightAmbient, night)
        nightOn = night > .01f
        nightFilter = if (nightOn) ColorFilter.tint(nightInk, BlendMode.Modulate) else null
        java.util.Arrays.fill(awningFilters, null)
        glassSky = sky
    }

    /**
     * A colour matrix that turns a [GlassSprites] strip's weights into colours: its red, green and
     * blue channels each a share of [c]'s colours from [o] on, the rest of the pixel the fourth.
     */
    private fun weights(c: FloatArray, o: Int): ColorFilter {
        val m = FloatArray(20)
        for (ch in 0..2) {
            val rest = c[o + 9 + ch]
            m[ch * 5] = c[o + ch] - rest
            m[ch * 5 + 1] = c[o + 3 + ch] - rest
            m[ch * 5 + 2] = c[o + 6 + ch] - rest
            m[ch * 5 + 4] = rest * 255f
        }
        m[18] = 1f
        return ColorFilter.colorMatrix(ColorMatrix(m))
    }

    /**
     * What the glass at ([x], [y]), [w] by [h] mirrors, at [a]: the hour's sky with its clouds
     * drifting through it, and the buildings, windows and lamps across the street, placed for the
     * pane ([GlassMap.place]: [seed] its own tilt, [z] its height over the pavement, [sx] where
     * along the street it stands), then the sun's glint on it at [spec]. [unit] is how many of the
     * drawing's lengths a scene unit is (a side face draws in its tile's pixels).
     */
    private fun DrawScope.mirrorSky(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        a: Float,
        seed: Int,
        spec: Float = 1f,
        z: Float = -(y + h / 2f),
        sx: Float = buildingX + x + w / 2f,
        unit: Float = 1f,
    ) {
        if (a < .01f) return
        val g = SceneSprites.glass
        val at = glassAt
        GlassMap.place(sx, z, w / unit, h / unit, seed, viewX, mirrorPhase, glassSeconds, at)
        val k = at[0] * unit
        val sw = w / k
        val sh = h / k
        val us = at[2]
        val v0 = at[3]
        withTransform({
            translate(x, y)
            scale(k, k, Offset.Zero)
            translate(-us, -v0)
        }) {
            drawRect(g.sky, Offset(us, v0), Size(sw, sh), alpha = a, colorFilter = skyFilter)
        }
        val u0 = at[1]
        val vs = v0 - GlassSprites.STREET_AT
        withTransform({
            translate(x, y)
            scale(k, k, Offset.Zero)
            translate(-u0, -vs)
        }) {
            drawRect(g.street, Offset(u0, vs), Size(sw, sh), alpha = a, colorFilter = streetFilter)
        }
        if (spec > 0f) glint(x, y, w, h, a * spec, at[4], GlassMap.lift(seed))
    }

    /**
     * The blinds or curtains of interior [kind] ([GlassSprites.interior]) behind a pane, at [a].
     */
    private fun DrawScope.interior(x: Float, y: Float, w: Float, h: Float, kind: Int, a: Float) {
        if (a < .01f) return
        withTransform({
            translate(x, y)
            scale(w / GlassSprites.INTERIOR_W, h / GlassSprites.INTERIOR_H, Offset.Zero)
        }) {
            drawImage(SceneSprites.glass.interiors[kind], alpha = a)
        }
    }

    /**
     * The room's light on an opening's reveals at night ([BuildingDetail.REVEAL]): the jambs and
     * the head lit from inside, the sill's top catching it, at [a], additively.
     */
    private fun DrawScope.roomOnReveals(x: Float, y: Float, w: Float, h: Float, a: Float) {
        if (a < .01f) return
        val r = BuildingDetail.REVEAL
        val f = BuildingDetail.FRAME
        val d = r - f
        val c = SceneInk.WindowLit
        drawRect(c, Offset(x - r, y - f), Size(d, h + f), alpha = a, blendMode = BlendMode.Plus)
        drawRect(c, Offset(x + w + f, y - f), Size(d, h + f), alpha = a, blendMode = BlendMode.Plus)
        drawRect(
            c,
            Offset(x - r, y - r),
            Size(w + 2f * r, d),
            alpha = a * .6f,
            blendMode = BlendMode.Plus,
        )
        val reach = r + BuildingDetail.SILL_REACH
        drawRect(
            SceneInk.WindowLitLow,
            Offset(x - reach, y + h),
            Size(w + 2f * reach, .7f),
            alpha = a,
            blendMode = BlendMode.Plus,
        )
    }

    /**
     * A floor's side face: its wall's own tile on the receding face (its courses rising to the back
     * edge), deepened to the side's value, two narrow windows seen at a slant, all inside the face.
     * Drawn in the unit side's space ([onSide]).
     */
    private fun DrawScope.sideWall(fa: FloorArt, glass: Color, alpha: Float, detailed: Boolean) {
        val f = fa.facade
        if (!detailed) {
            drawPath(fa.side, f.side, alpha = alpha)
            return
        }
        val ppu = FacadeSprites.PPU
        val h = fa.bottom - fa.top
        sideSkew.reset()
        sideSkew[0, 0] = 1f / (SIDE_DEPTH * ppu)
        sideSkew[0, 1] = -B.DY / (SIDE_DEPTH * ppu)
        sideSkew[1, 1] = 1f / ppu
        sideSkew[3, 1] = fa.top
        clipPath(fa.side) {
            withTransform({ transform(sideSkew) }) {
                drawRect(
                    SceneSprites.facade(fa.material).brush,
                    Offset.Zero,
                    Size(SIDE_DEPTH * ppu, h * ppu),
                    alpha = alpha,
                )
                drawRect(f.side, Offset.Zero, Size(SIDE_DEPTH * ppu, h * ppu), alpha = alpha * .62f)
                if (fa.windowCount > 0 && fa.piece.kind != PieceKind.RUIN) {
                    val wy = fa.windows[1] * ppu
                    val wh = fa.windows[3] * ppu
                    for (u in SIDE_SLITS) {
                        val sx = u * SIDE_DEPTH * ppu
                        val sw = SIDE_SLIT * SIDE_DEPTH * ppu
                        drawRect(
                            Color.Black,
                            Offset(sx - ppu, wy - ppu),
                            Size(sw + 2f * ppu, wh + 2f * ppu),
                            alpha = alpha * .25f,
                        )
                        drawRect(glass, Offset(sx, wy), Size(sw, wh), alpha = alpha * .9f)
                        // seen at a slant, the side's glass is nearly a mirror
                        mirrorSky(
                            sx,
                            wy,
                            sw,
                            wh,
                            alpha * min(1f, FacadeLook.reflection(0f, inkDark) * 1.08f),
                            fa.seed * 31 + 40 + (u * 16f).toInt(),
                            0f,
                            -(fa.top + fa.windows[1] + fa.windows[3] / 2f),
                            buildingX + (if (sideShift > 0f) 1f else -1f) * (B.W / 2f + 20f * u),
                            ppu,
                        )
                        drawRect(
                            f.trim,
                            Offset(sx, wy + wh),
                            Size(sw + ppu, ppu * 1.4f),
                            alpha = alpha * .7f,
                        )
                    }
                }
            }
        }
    }

    /**
     * A floor's atmosphere over its wall: darker towards the pavement at the foot of the building,
     * darker still when the wall is wet.
     */
    private fun DrawScope.grade(fa: FloorArt, alpha: Float) {
        val half = B.W / 2f
        val h = fa.bottom - fa.top
        if (nightOn)
            drawRect(
                nightInk,
                Offset(-half - 1f, fa.top),
                Size(B.W + 2f, h),
                alpha = alpha,
                blendMode = BlendMode.Multiply,
            )
        if (fa.bottom > -GROUND_GRADE)
            drawRect(groundGrade, Offset(-half, fa.top), Size(B.W, h), alpha = alpha)
        if (weather.wet > .01f)
            drawRect(
                WetWall,
                Offset(-half, fa.top),
                Size(B.W, h),
                alpha = .22f * weather.wet * alpha,
                blendMode = BlendMode.Multiply,
            )
    }

    /**
     * Aerial perspective: the higher a floor stands the more of the air's haze lies over it, as on
     * the far city (paler and bluer, less at night).
     */
    private fun DrawScope.haze(fa: FloorArt, alpha: Float) {
        val up = (-fa.bottom - B.LOBBY_H).coerceAtLeast(0f)
        val a = HAZE * Px.smooth(0f, HAZE_HEIGHT, up) * (1f - .55f * inkDark) * alpha
        if (a < .005f) return
        drawRect(hazeInk, Offset(-B.W / 2f, fa.top), Size(B.W, fa.bottom - fa.top), alpha = a)
    }

    /**
     * Polished gold panels and a curtain wall's glass catch the sun in a soft band that stands
     * where the reflection falls for a building this far to the side of the light, so it slides
     * across the face as the street scrolls ([wide] units wide); by day only.
     */
    private fun DrawScope.polish(top: Float, bottom: Float, alpha: Float, wide: Float) {
        val a = alpha * direct * (1f - inkDark) * .5f
        if (a < .02f) return
        val u = (.5f - offset(buildingX) * 1.8f).coerceIn(-.1f, 1.1f)
        clipRect(-B.W / 2f, top, B.W / 2f, bottom) {
            withTransform({
                translate(-B.W / 2f + u * B.W, top)
                scale(wide, bottom - top, Offset.Zero)
            }) {
                drawRect(polishBand, Offset(-.5f, 0f), Size(1f, 1f), alpha = a)
            }
        }
    }

    /**
     * The light and the shade across a face of the building from [top] to [bottom] (the light finds
     * [sunSide] of it): a wash of light from the lit edge, a wash of shade from the other, over the
     * wall only (windows and trim are drawn after it).
     */
    private fun DrawScope.facadeLight(top: Float, bottom: Float, alpha: Float, x0: Float = -B.W / 2f, w: Float = B.W) {
        val s = sunSide
        if (abs(s) < .02f) return
        val h = bottom - top
        val a = abs(s) * alpha
        // from the lit edge inward, and from the far edge inward ([x0] across [w]: the front's span)
        val lit = if (s < 0f) x0 else x0 + w
        val far = if (s < 0f) x0 + w else x0
        val sign = if (s < 0f) 1f else -1f
        withTransform({
            translate(lit, top)
            scale(sign * w * .7f, h, Offset.Zero)
        }) {
            drawRect(litWash, Offset.Zero, Size(1f, 1f), alpha = a * (1f - .4f * gold))
            if (gold > .01f) drawRect(goldWash, Offset.Zero, Size(1f, 1f), alpha = a * gold)
        }
        withTransform({
            translate(far, top)
            scale(-sign * w * .6f, h, Offset.Zero)
        }) {
            drawRect(shadeWash, Offset.Zero, Size(1f, 1f), alpha = a * .9f)
        }
    }

    /**
     * The blue hour's cool cast over a wall or the ground from ([x], [y]), [w] by [h]: a blue
     * multiplied over it, [depth] deep at the hour's full, so the lit windows' warmth stands out
     * against it. Subtle: brick still reads as brick.
     */
    private fun DrawScope.blueHour(x: Float, y: Float, w: Float, h: Float, depth: Float) {
        if (blue <= .01f) return
        drawRect(
            BlueCast,
            Offset(x, y),
            Size(w, h),
            alpha = depth * blue,
            blendMode = BlendMode.Multiply,
        )
    }

    /**
     * The weathering of a floor's wall ([FacadeSprites.grime]): soot under its string course and
     * the streaks the rain has washed down from it, laid from the floor's top at a place along the
     * strip of the floor's own, over the wall and its trim (under the windows, which are drawn
     * after). Heaviest on stone, which shows dirt, lighter on brick, faint on gold, none on glass.
     */
    private fun DrawScope.grime(fa: FloorArt, alpha: Float) {
        val k =
            when (fa.material) {
                HabitMaterial.STONE -> .9f
                HabitMaterial.CLAY -> .75f
                HabitMaterial.FADE -> 1f
                HabitMaterial.GOLD -> .4f
                HabitMaterial.GLASS -> 0f
            }
        if (k <= 0f) return
        val ppu = FacadeSprites.GRIME_PPU
        val off = Ambient.hash(fa.seed, 0, 640) * FacadeSprites.GRIME_W * ppu
        withTransform({
            translate(-B.W / 2f, fa.top)
            scale(1f / ppu, 1f / ppu, Offset.Zero)
            translate(-off, 0f)
        }) {
            drawRect(
                SceneSprites.grime,
                Offset(off, 0f),
                Size(B.W * ppu, (fa.bottom - fa.top) * ppu),
                alpha = alpha * k * (1f + .25f * weather.wet).coerceAtMost(1f),
            )
        }
    }

    /**
     * Rain streaks run off the ends of most of a floor's sills ([FacadeSprites.drip]), each its own
     * length, down the wall under them to no lower than the floor's foot; deeper while the street
     * is wet. Faint on gold.
     */
    private fun DrawScope.stains(fa: FloorArt, top: Float, alpha: Float) {
        val w = fa.windows
        val gold = if (fa.material == HabitMaterial.GOLD) .45f else 1f
        val a = alpha * gold * (.6f + .4f * weather.wet)
        val image = SceneSprites.drip
        val ppu = FacadeSprites.PPU
        val reach = BuildingDetail.REVEAL + BuildingDetail.SILL_REACH
        for (k in 0 until fa.windowCount) {
            val x = w[k * 4]
            val ww = w[k * 4 + 2]
            val y = top + w[k * 4 + 1] + w[k * 4 + 3] + BuildingDetail.SILL_H + .3f
            val room = fa.bottom - y - .5f
            if (room < 3f) continue
            for (end in 0..1) {
                val n = k * 2 + end
                if (Ambient.hash(fa.seed, n, 404) >= .68f) continue
                val len =
                    minOf(room, FacadeSprites.DRIP_H * (.3f + .7f * Ambient.hash(fa.seed, n, 405)))
                val cx = if (end == 0) x - reach + 1.4f else x + ww + reach - 1.4f
                val depth = .55f + .45f * Ambient.hash(fa.seed, n, 406)
                withTransform({
                    translate(cx - FacadeSprites.DRIP_W / 2f, y)
                    scale(1f / ppu, len / FacadeSprites.DRIP_H / ppu, Offset.Zero)
                }) {
                    drawImage(image, Offset.Zero, alpha = a * depth)
                }
            }
        }
    }

    private fun duskTints(sky: SkyColors) {
        if (duskSky === sky) return
        // steam: white by day, the sky's pale grey-blue at night with a little of the lamps' warmth
        steamFilter =
            ColorFilter.tint(
                lerp(Color.White, lerp(sky.sky[2], SceneInk.LampLight, .25f), .6f * sky.dark),
                BlendMode.SrcIn,
            )
        duskSky = sky
    }

    /**
     * A steam vent beside the water tank at ([tx], [base]) on the roof of the building with [seed]:
     * a short pipe and a plume of soft puffs rising from it, growing and thinning as they go,
     * drifting with the wind the trees sway in; denser at night, in the morning and in the cold
     * months ([Dusk.steam]).
     */
    private fun DrawScope.vent(seed: Int, tx: Float, base: Float, seconds: Float, sky: SkyColors) {
        if (!Dusk.ventOn(seed)) return
        val px = tx + 13f
        val top = base - 6.5f
        drawRect(SceneInk.Lamp, Offset(px - 1.1f, top), Size(2.2f, 6.5f))
        drawRect(SceneInk.Highlight, Offset(px - 1.1f, top), Size(.7f, 6.5f), alpha = .4f)
        val density = Dusk.steam(hour, season.dayOfYear)
        if (density <= 0f) return
        duskTints(sky)
        val wind = Ambient.sway(seed, seconds)
        val puffs = SceneSprites.dusk.puffs
        val half = DuskSprites.PUFF / 2f
        for (j in 0 until Dusk.PUFFS) {
            val t = Dusk.puff(seed, j, seconds)
            val size = (.4f + 1.7f * t) * 7f / DuskSprites.PUFF
            val x = px + t * (2.5f + 1.8f * wind) + 2.5f * t * t
            val y = top - t * 21f
            val fade = (1f - t) * (1f - t)
            val a = density * .45f * fade * min(1f, t * 10f)
            if (a <= .01f) continue
            withTransform({
                translate(x, y)
                scale(size, size, Offset.Zero)
            }) {
                drawImage(
                    puffs[j % DuskSprites.PUFFS],
                    Offset(-half, -half),
                    alpha = a,
                    colorFilter = steamFilter,
                )
            }
        }
    }

    /**
     * [count] pigeons standing along a perch from [x0] (building units) [span] wide, their feet at
     * [y], pecking and looking round in a slow idle ([Dusk.pigeonFrame]). On the pavement
     * ([ground]) a street walker passing within [Dusk.SCARE_REACH] sends them up in a short arc,
     * wings beating, and they come back a while later. When they went is kept per building (nothing
     * is made per frame).
     */
    private fun DrawScope.pigeons(
        seed: Int,
        count: Int,
        x0: Float,
        span: Float,
        y: Float,
        seconds: Float,
        sky: SkyColors,
        ground: Boolean,
    ) {
        if (count == 0) return
        val slot = seed and (PIGEON_SLOTS - 1)
        var since = seconds - scare[slot]
        val life = streetLife
        if (ground && life != null && Dusk.pigeonPhase(since) == Dusk.IDLE) {
            val middle = buildingX + x0 + span / 2f
            for (i in 0 until life.count) {
                if (!Dusk.scared(abs(life.passers[i].x - middle))) continue
                scare[slot] = seconds
                since = 0f
                break
            }
        }
        val phase = Dusk.pigeonPhase(since)
        if (phase == Dusk.GONE) return
        val frames = SceneSprites.dusk.pigeon
        val u = 1f / DuskSprites.PIGEON_PPU
        val filter = if (sky.dark > .5f) pigeonNight else null
        for (i in 0 until count) {
            val lift = Dusk.pigeonLift(since, i)
            if (lift >= 1f) continue
            val facing = Dusk.pigeonFacing(seed, i, seconds)
            val frame =
                if (phase == Dusk.IDLE) Dusk.pigeonFrame(seed, i, seconds)
                else Dusk.wingFrame(seconds, i)
            val px = x0 + span * Dusk.pigeonAt(seed, i) + facing * lift * (22f + 8f * i)
            val py = y - lift * (26f + 6f * i) * (1.6f - .6f * lift)
            val a = 1f - ((lift - .7f) / .3f).coerceIn(0f, 1f)
            withTransform({
                translate(px, py)
                scale(-facing * u, u, Offset.Zero)
            }) {
                drawImage(
                    frames[frame],
                    Offset(-DuskSprites.PIGEON_W / 2f, -DuskSprites.PIGEON_H.toFloat()),
                    alpha = a,
                    colorFilter = filter,
                )
            }
        }
    }

    /**
     * The building's shown side face: lit when the light is on that side (the right face with the
     * sun to the right, the left face with it to the left), deeper in shade otherwise.
     */
    private fun DrawScope.sideLight(side: Path, alpha: Float) {
        val s = if (sideShift < 0f) -sunSide else sunSide
        if (s > .02f) drawPath(side, Color.White, alpha = .26f * s * alpha)
        else if (s < -.02f) drawPath(side, Color.Black, alpha = .18f * -s * alpha)
    }

    /**
     * Draws [block], whose paths are unit side faces hinged on x 0 ([FloorArt.side]), as the shown
     * side of the building being drawn: from the front's edge the side hangs from, scaled out to
     * the side's width, mirrored for the left face. Nothing when no side shows.
     */
    private inline fun DrawScope.onSide(block: DrawScope.() -> Unit) {
        val s = sideShift
        if (abs(s) < .02f) return
        withTransform({
            translate(if (s > 0f) B.W / 2f else -B.W / 2f, 0f)
            scale(s, 1f, Offset.Zero)
        }) {
            block()
        }
    }

    /**
     * The sun's glint on a pane of glass at ([x], [y]), [w] by [h]: a hot spot where the sun's
     * reflection falls for a pane this far to the side of the light, turned a hair by the pane's
     * [tilt] (so it jumps from pane to pane as the street scrolls and travels across each) and
     * raised by its [lift]; clipped to the pane by the brush itself. By day, in a clear sky only.
     */
    private fun DrawScope.glint(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        alpha: Float,
        tilt: Float,
        lift: Float,
    ) {
        val key = light ?: return
        val o = offset(buildingX + x + w / 2f) + tilt * GLINT_TILT
        val a =
            alpha *
                key.strength *
                (1f - key.cool) *
                (1f - .7f * key.flat) *
                .9f *
                exp(-(o / GLINT_SPREAD) * (o / GLINT_SPREAD))
        if (a < .02f) return
        val gx = x + w * (.5f - o * 5f).coerceIn(.1f, .9f)
        val gy = y + h * (.22f + .1f * lift)
        val r = min(w, 16f) * .8f
        val k = 2f * r / GlassSprites.GLINT
        withTransform({
            translate(gx - r, gy - r)
            scale(k, k, Offset.Zero)
        }) {
            drawRect(
                SceneSprites.glass.glint,
                Offset((x - gx + r) / k, (y - gy + r) / k),
                Size(w / k, h / k),
                alpha = min(1f, a),
                blendMode = BlendMode.Plus,
            )
        }
    }

    private fun DrawScope.park(
        art: BuildingArt,
        selected: String?,
        accent: Color,
        seconds: Float,
        sky: SkyColors,
    ) {
        val trees = art.layout.trees
        if (trees.isEmpty()) return
        val from = B.W / 2f + B.DX + 4f
        drawRect(SceneInk.Grass, Offset(from, -4f), Size(B.PARK_W - 10f, 4f))
        for (i in trees.indices) {
            val t = trees[i]
            if (selected == t.id)
                drawOval(accent, Offset(t.x - 17f, -5f), Size(34f, 10f), style = ring)
            tree(t.x, t.id.hashCode(), seconds, .8f, sky)
        }
        // a low fence along its front
        var x = from
        while (x <= from + B.PARK_W - 10f) {
            drawRect(SceneInk.Bench, Offset(x, -8f), Size(1.4f, 8f))
            x += 8f
        }
        drawRect(SceneInk.Bench, Offset(from, -6.5f), Size(B.PARK_W - 10f, 1.2f))
        if (season.snow > .01f) snowPark(sky, from)
        pigeons(
            art.stack.id.hashCode(),
            Dusk.pigeons(0, true),
            from + 4f,
            B.PARK_W - 18f,
            6f,
            seconds,
            sky,
            true,
        )
    }

    private fun DrawScope.lobby(
        art: BuildingArt,
        sky: SkyColors,
        detailed: Boolean,
        seconds: Float,
        lod: PeopleLod,
    ) {
        val half = B.W / 2f
        val top = -B.LOBBY_H
        val ppu = FacadeSprites.PPU
        // the wall: polished dark granite slabs, from the lobby's top-left corner
        withTransform({
            translate(-half, top)
            scale(1f / ppu, 1f / ppu, Offset.Zero)
        }) {
            drawRect(SceneSprites.lobbyWall.brush, Offset.Zero, Size(B.W * ppu, B.LOBBY_H * ppu))
        }
        facadeLight(top, 0f, 1f)
        blueHour(-half, top, B.W, B.LOBBY_H, .42f)
        val lobbySeed = art.stack.id.hashCode()
        val signOn = Dusk.lightsOn(lobbySeed, hour, Dusk.SIGN)
        val shopOn = Dusk.lightsOn(lobbySeed, hour, Dusk.SHOP)
        // the base course: a darker stone plinth, its foot in the pavement's shade
        drawRect(SceneInk.LobbyWallLow, Offset(-half, -10f), Size(B.W, 10f), alpha = .82f)
        drawRect(aoWall, Offset(-half, -14f), Size(B.W, 14f))
        if (weather.wet > .01f)
            drawRect(
                WetWall,
                Offset(-half, top),
                Size(B.W, B.LOBBY_H),
                alpha = .22f * weather.wet,
                blendMode = BlendMode.Multiply,
            )
        onSide {
            drawPath(art.lobbySide, SceneInk.LobbySide)
            sideLight(art.lobbySide, 1f)
        }
        if (art.floors.isEmpty()) roofFace(-art.layout.roof, SceneInk.LobbyWall, half)
        // the plinth's joints, the pipe's foot, the awning's brackets, the door's reveals
        val lobbyFacade = art.floors.firstOrNull()?.facade ?: facade(HabitMaterial.STONE)
        inks(art.lobbyDetail, lobbyFacade, 1f, detailed, SceneInk.LobbyReveal, SceneInk.ShopFrame)
        if (nightOn) {
            drawRect(
                nightInk,
                Offset(-half, top),
                Size(B.W, B.LOBBY_H),
                blendMode = BlendMode.Multiply,
            )
            onSide { drawPath(art.lobbySide, nightInk, blendMode = BlendMode.Multiply) }
        }
        signBoard(art, top, sky, signOn, detailed)
        // the shopfront: a door between two windows, lit at night
        val lit = sky.dark * shopOn
        val shop = lerp(SceneInk.Shop, SceneInk.ShopLit, .15f + .8f * lit)
        val w0 = top + BuildingDetail.SHOP_TOP
        val shopW = BuildingDetail.SHOP_W
        val shopH = BuildingDetail.SHOP_FOOT - w0
        drawRect(shop, Offset(-half + 9f, w0), Size(shopW, shopH))
        drawRect(shop, Offset(BuildingDetail.SHOP_X, w0), Size(shopW, shopH))
        door(w0, sky, lit)
        // the lobby: a reception desk with someone at it, a plant, and a visitor who comes and goes
        lobbyLife(art, -half + 9f, w0, shopW, shopH, seconds, sky, lod)
        // over the glass: the street across it and the sky it mirrors (faint once the shop is lit
        // inside), the sun's glint, its reveals and the shadows on it, its glazing bars
        val reflect = FacadeLook.reflection(lit, sky.dark) * .62f
        for (side in 0..1) {
            val x0 = if (side == 0) -half + 9f else BuildingDetail.SHOP_X
            mirrorSky(x0, w0, shopW, shopH, reflect * fresnel, lobbySeed + side * 13, .5f)
        }
        inks(art.lobbyOver, lobbyFacade, 1f, detailed, SceneInk.LobbyReveal, SceneInk.ShopFrame)
        awning(art, top, sky, seconds)
        if (season.snow > .01f) snowLobby(sky, top, top + 21f)
        wallLamps(top, sky, shopOn)
        // the step at the door
        drawRect(SceneInk.Step, Offset(-11f, -1.8f), Size(22f, 1.8f))
        drawRect(faceLit, Offset(-11f, -1.8f), Size(22f, .45f), alpha = .3f + .2f * direct)
        drawRect(Color.Black, Offset(-11f, -.5f), Size(22f, .5f), alpha = .25f)
        // a planter of clipped box at each end of the shopfront
        if (detailed)
            for (side in 0..1) {
                val px = if (side == 0) -half + PLANTER_IN else half - PLANTER_IN
                footShadow(px, 0f, 4.6f)
                picture(SceneSprites.roof.planter, px, 0f)
                shaded(px - 4.6f, -5.7f, 9.2f, 5f)
            }
        spill(
            -half + 9f,
            shopW,
            sky,
            lod,
            .52f * PeopleActivities.fitted(Activity.WALKING),
            ((art.stack.id.hashCode() and 0x7fffffff) + 3) % Looks.COUNT,
        )
        drawRect(SceneInk.Highlight, Offset(-half, top), Size(B.W, 1f))
    }

    /**
     * The sign over the awning: a board in a raised frame with depth (its underside, the shadow it
     * throws down the wall away from the light), the lobby's name in raised letters (their shadow
     * on the board by day, backlit at night), its tube lit at night and glowing.
     */
    private fun DrawScope.signBoard(
        art: BuildingArt,
        top: Float,
        sky: SkyColors,
        signOn: Float,
        detailed: Boolean,
    ) {
        val half = B.W / 2f
        translate(0f, top + 4f) {
            val dx = -sunSide * 2f
            drawRect(
                castInk,
                Offset(-half + 10f + dx, 15f),
                Size(B.W - 20f, 1.4f + 1.4f * direct),
                alpha = .2f + .26f * direct,
            )
            drawRect(signFace, Offset(-half + 10f, 0f), Size(B.W - 20f, 15f))
            drawRect(faceLit, Offset(-half + 10f, 0f), Size(B.W - 20f, .7f), alpha = .22f)
            drawRect(Color.Black, Offset(-half + 10f, 14.3f), Size(B.W - 20f, .7f), alpha = .3f)
            drawRect(
                SceneInk.SignFrame,
                Offset(-half + 10f, 0f),
                Size(B.W - 20f, 15f),
                style = frameStroke,
            )
            drawRect(faceLit, Offset(-half + 9.4f, -.6f), Size(B.W - 18.8f, .5f), alpha = .3f)
            drawRect(Color.Black, Offset(-half + 9.4f, 15.1f), Size(B.W - 18.8f, 1f), alpha = .45f)
            // the frame's ends, turned to the light or away from it
            val lit = if (sunSide < 0f) -half + 9.4f else half - 10f
            val dim = if (sunSide < 0f) half - 10f else -half + 9.4f
            drawRect(faceLit, Offset(lit, -.6f), Size(.6f, 16.2f), alpha = .3f * abs(sunSide))
            drawRect(castInk, Offset(dim, -.6f), Size(.6f, 16.2f), alpha = .3f * abs(sunSide))
            // the tube along the board's foot: a glass line by day, lit at night
            drawRect(
                SceneInk.IronLight,
                Offset(-half + 13f, 12.6f),
                Size(B.W - 26f, .8f),
                alpha = .4f,
            )
            val tube = signOn * sky.dark
            if (tube > 0f) {
                drawRect(
                    SceneInk.Tube,
                    Offset(-half + 13f, 11.8f),
                    Size(B.W - 26f, 2.4f),
                    alpha = .3f * tube,
                )
                drawRect(
                    SceneInk.Tube,
                    Offset(-half + 13f, 12.6f),
                    Size(B.W - 26f, .8f),
                    alpha = tube,
                )
            }
        }
        if (sky.dark > .2f && signOn > 0f)
            translate(0f, top + 11.5f) {
                scale(B.W * .56f, 20f, Offset.Zero) {
                    drawCircle(
                        bloom,
                        1f,
                        Offset.Zero,
                        alpha = .5f * sky.dark * signOn,
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        if (detailed) {
            val n = art.lobbyName
            val tx = -n.size.width / 2f
            val ty = top + 11.5f - n.size.height / 2f
            // raised letters: their shadow on the board, away from the light, and their own depth
            // (the letters' sides and undersides, a darker tone of their face)
            drawText(n, Color.Black, Offset(tx + .35f - .5f * sunSide, ty + .6f), alpha = .6f)
            drawText(n, LetterSide, Offset(tx + .25f, ty + .4f))
            drawText(n, LetterSide, Offset(tx + .12f, ty + .2f))
            // and lit from behind at night, a halo round each
            val halo = .26f * signOn * sky.dark
            if (halo > .01f)
                for (d in LETTER_HALO) drawText(n, SceneInk.Tube, Offset(tx, ty) + d, alpha = halo)
            drawText(n, SceneInk.SignText, Offset(tx, ty))
        }
    }

    /**
     * The lobby's door, standing back in its opening ([BuildingDetail.lobby] draws the reveals):
     * two leaves with a glass panel in each mirroring the sky (lit from inside at night), kick
     * plates, brass pulls either side of the meeting stiles, and the transom light over it, warm
     * and glowing once the lobby is lit ([lit]).
     */
    private fun DrawScope.door(w0: Float, sky: SkyColors, lit: Float) {
        val dx = BuildingDetail.DOOR - 1.3f
        val d0 = w0 - 2f + 1.3f
        drawRect(SceneInk.Door, Offset(-dx, d0), Size(2f * dx, -d0))
        val reflect = FacadeLook.reflection(lit, sky.dark) * .6f
        val glassLit = lerp(SceneInk.Shop, SceneInk.ShopLit, .2f + .8f * lit)
        // the transom light
        drawRect(glassLit, Offset(-6.5f, w0 + 1f), Size(13f, 5.4f))
        mirrorSky(-6.5f, w0 + 1f, 13f, 5.4f, reflect, 71, 0f)
        drawRect(Color.Black, Offset(-6.5f, w0 + 1f), Size(13f, .6f), alpha = .3f)
        drawRect(SceneInk.ShopFrame, Offset(-dx, w0 + 6.4f), Size(2f * dx, 1f))
        drawRect(faceLit, Offset(-dx, w0 + 6.4f), Size(2f * dx, .3f), alpha = .25f)
        // the leaves' glass, kick plates, the meeting stiles and the pulls
        val pw = dx - 2.2f
        for (lx in floatArrayOf(-dx + 1.5f, .7f)) {
            drawRect(glassLit, Offset(lx, w0 + 8.6f), Size(pw, 5.4f))
            mirrorSky(lx, w0 + 8.6f, pw, 5.4f, reflect, 72 + lx.toInt(), 0f)
            drawRect(Color.Black, Offset(lx, w0 + 8.6f), Size(pw, .6f), alpha = .35f)
            drawRect(Color.Black, Offset(lx, w0 + 8.6f), Size(.5f, 5.4f), alpha = .25f)
            drawRect(SceneInk.Brass, Offset(lx, -4.8f), Size(pw, 2.4f), alpha = .35f)
            drawRect(faceLit, Offset(lx, -4.8f), Size(pw, .35f), alpha = .3f)
        }
        drawRect(Color.Black, Offset(-.3f, w0 + 7.4f), Size(.6f, -w0 - 7.4f), alpha = .55f)
        drawRect(SceneInk.Brass, Offset(-2.3f, w0 + 9.4f), Size(.8f, 3.6f))
        drawRect(SceneInk.Brass, Offset(1.5f, w0 + 9.4f), Size(.8f, 3.6f))
        drawRect(faceLit, Offset(-2.3f, w0 + 9.4f), Size(.3f, 3.6f), alpha = .5f)
        drawRect(faceLit, Offset(1.5f, w0 + 9.4f), Size(.3f, 3.6f), alpha = .5f)
        if (lit > .05f)
            translate(0f, w0 + 3.7f) {
                scale(13f, 7f, Offset.Zero) {
                    drawCircle(
                        bloom,
                        1f,
                        Offset.Zero,
                        alpha = .5f * lit,
                        blendMode = BlendMode.Plus,
                    )
                }
            }
    }

    /**
     * The awning: its shadow first, on the wall and the shopfront under it (the scalloped hem's
     * shape, dropped and leaning away from the light, deeper in the sun, a fainter one further off
     * for its soft edge), the cloth's colour the sun shines through laid faintly over that shade;
     * then the canvas itself ([AwningArt]: its folds, stripes, binding, stitched valance and its
     * weathering, coloured for the shop and for the night), the valance stirring in the air, and
     * the light across it from the lit side.
     */
    private fun DrawScope.awning(art: BuildingArt, top: Float, sky: SkyColors, seconds: Float) {
        val half = B.W / 2f
        val a0 = top + 21f
        val deep = (.26f + .3f * direct) * (1f - .55f * sky.dark)
        translate(-sunSide * 4.6f, a0 + 4.4f + 3.2f * direct) {
            drawPath(awningFace, castInk, alpha = deep * .4f)
        }
        translate(-sunSide * 3.5f, a0 + 3.4f + 2.4f * direct) {
            drawPath(awningFace, castInk, alpha = deep * .75f)
        }
        val ppu = AwningArt.PPU
        translate(0f, a0) {
            drawRect(
                awningDrop,
                Offset(-half + 2f, 9f),
                Size(B.W - 4f, 10f),
                alpha = .4f + .6f * direct,
            )
            // the sun through the canvas: the shade under it takes the cloth's colour
            val through = .16f * direct * (1f - sky.dark)
            if (through > .01f) {
                drawRect(art.awning, Offset(-half + 2f, 10f), Size(B.W - 4f, 6f), alpha = through)
                drawRect(
                    art.awning,
                    Offset(-half + 2f, 16f),
                    Size(B.W - 4f, 5f),
                    alpha = .5f * through,
                )
            }
            val cloth = awningFilter(art.awning)
            val img = SceneSprites.awning
            val stir = .018f * sin(seconds * 1.9f + (art.stack.id.hashCode() and 63) * .4f)
            withTransform({
                translate(-half, AwningArt.VALANCE_AT)
                scale(1f / ppu, (1f + stir) / ppu, Offset.Zero)
            }) {
                drawImage(img.valance, colorFilter = cloth)
            }
            withTransform({
                translate(-half, 0f)
                scale(1f / ppu, 1f / ppu, Offset.Zero)
            }) {
                drawImage(img.canopy, colorFilter = cloth)
            }
            clipPath(awningFace) {
                // the slope catching the sky, the hem in its own shade
                drawRect(
                    awningSlope,
                    Offset(-half, 0f),
                    Size(B.W, 17f),
                    alpha = .6f * (1f - .5f * sky.dark),
                )
                // the end towards the light brighter, the other end deeper
                val s = sunSide
                if (abs(s) > .02f) {
                    val lit = if (s < 0f) -half else half
                    val sign = if (s < 0f) 1f else -1f
                    withTransform({
                        translate(lit, 0f)
                        scale(sign * B.W * .6f, 18f, Offset.Zero)
                    }) {
                        drawRect(litWash, Offset.Zero, Size(1f, 1f), alpha = abs(s))
                    }
                    withTransform({
                        translate(-lit, 0f)
                        scale(-sign * B.W * .5f, 18f, Offset.Zero)
                    }) {
                        drawRect(shadeWash, Offset.Zero, Size(1f, 1f), alpha = abs(s))
                    }
                }
            }
        }
    }

    /**
     * The colour filter that paints the awning's canvas in [c] under the night's light
     * ([AwningArt.matrix]): one per awning colour, kept until the sky changes.
     */
    private fun awningFilter(c: Color): ColorFilter {
        var i = 0
        while (i < AWNINGS.size && AWNINGS[i] != c) i++
        if (i < AWNINGS.size)
            awningFilters[i]?.let {
                return it
            }
        val n = nightInk
        AwningArt.matrix(c.red, c.green, c.blue, n.red, n.green, n.blue, awningMatrix)
        val f = ColorFilter.colorMatrix(ColorMatrix(awningMatrix.copyOf()))
        if (i < AWNINGS.size) awningFilters[i] = f
        return f
    }

    /**
     * The lobby's wall lamps either side of the door: a lantern on a bracket, its glass lit with
     * the shop's lights at dusk, a warm pool of its light on the wall round it.
     */
    private fun DrawScope.wallLamps(top: Float, sky: SkyColors, shopOn: Float) {
        val y = top + BuildingDetail.LAMP_Y
        val on = shopOn * Px.smooth(.15f, .6f, sky.dark)
        for (side in LAMP_SIDES) {
            val x = side * BuildingDetail.LAMP_X
            if (on > .01f)
                translate(x, y + 2.4f) {
                    scale(10f, 12f, Offset.Zero) {
                        drawCircle(
                            halo,
                            1f,
                            Offset.Zero,
                            alpha = .8f * on,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
            drawRect(SceneInk.Iron, Offset(x - 1.6f, y - .8f), Size(3.2f, .9f))
            drawRect(SceneInk.Iron, Offset(x - 1.3f, y), Size(2.6f, 4.2f))
            drawRect(
                lerp(SceneInk.IronLight, SceneInk.LampLight, on),
                Offset(x - .8f, y + .5f),
                Size(1.6f, 3.1f),
                alpha = .55f + .45f * on,
            )
            drawRect(SceneInk.Iron, Offset(x - .6f, y + 4.2f), Size(1.2f, .7f))
            val litEdge = if (sunSide < 0f) x - 1.3f else x + 1f
            drawRect(faceLit, Offset(litEdge, y), Size(.3f, 4.2f), alpha = .5f * abs(sunSide))
            if (on > .01f)
                translate(x, y + 2f) {
                    scale(3.2f, 3.6f, Offset.Zero) {
                        drawCircle(bloom, 1f, Offset.Zero, alpha = on, blendMode = BlendMode.Plus)
                    }
                }
        }
    }

    /**
     * At night the lobby's two lit windows (at [x] and to the right of the door, [w] wide) throw
     * their light out onto the pavement, and the visitor walking past the glass throws a shadow
     * across it: feet at the glass, long from the low light inside, spreading from the window's
     * middle as that light spreads. The visitor's state is [stroll], as [lobbyLife] just left it.
     */
    private fun DrawScope.spill(
        x: Float,
        w: Float,
        sky: SkyColors,
        lod: PeopleLod,
        size: Float,
        look: Int,
    ) {
        val dark = sky.dark
        if (dark <= .2f || lod == PeopleLod.NONE) return
        val flare = w * .14f
        for (side in 0..1) {
            val ox = if (side == 0) x else 13f
            spillPath.rewind()
            spillPath.moveTo(ox, 0f)
            spillPath.lineTo(ox + w, 0f)
            spillPath.lineTo(ox + w + flare, SPILL)
            spillPath.lineTo(ox - flare, SPILL)
            spillPath.close()
            // the light itself: soft-sided, spreading and fading, no edge where it ends
            val span = w / 2f * LightSprites.SPILL_SPAN
            withTransform({
                translate(ox + w / 2f - span, 0f)
                scale(
                    span * 2f / LightSprites.SPILL_W,
                    SPILL * 1.25f / LightSprites.SPILL_H,
                    Offset.Zero,
                )
            }) {
                drawImage(
                    SceneSprites.light.spill,
                    Offset.Zero,
                    .95f * dark,
                    colorFilter = spillTint,
                    blendMode = BlendMode.Plus,
                )
            }
            if (side == 1 && lod == PeopleLod.FULL && stroll.here) {
                val xv = ox + 4f + stroll.x + stroll.sway * size
                val spread = ((xv - (ox + w / 2f)) / (w / 2f)).coerceIn(-1f, 1f)
                spillFall.reset()
                spillFall[1, 0] = -spread * .45f
                spillFall[1, 1] = -1.15f
                val act =
                    if (stroll.mode == Stroll.STAND) Activity.LOOKING_OUT else Activity.WALKING
                val a = .8f * dark
                personFilter = spillFilter
                clipPath(spillPath) {
                    withTransform({
                        translate(xv, -stroll.lift * size)
                        transform(spillFall)
                        scale(stroll.dir * size * frame, size * frame, Offset.Zero)
                    }) {
                        if (stroll.mode == Stroll.STAND) {
                            drawPerson(SceneSprites.person(look, act, stroll.frame), a)
                            if (stroll.mix > .01f)
                                drawPerson(
                                    SceneSprites.person(look, act, stroll.next),
                                    a * stroll.mix,
                                )
                        } else
                            smoothFrame(
                                look,
                                act,
                                stroll.frame,
                                stroll.next,
                                stroll.mix,
                                a,
                                stroll.lean,
                            )
                    }
                }
                personFilter = null
            }
        }
    }

    private fun DrawScope.floor(
        art: BuildingArt,
        fa: FloorArt,
        motion: BuildingMotion,
        landing: Boolean,
        alpha: Float,
        live: Boolean,
        detailed: Boolean,
        lit: Color,
        glass: Color,
        step: Int,
        sky: SkyColors,
        lod: PeopleLod,
        crowd: Crowd,
    ) {
        val half = B.W / 2f
        val b = fa.block
        val f = fa.facade
        val ruin = fa.piece.kind == PieceKind.RUIN
        val seconds = motion.seconds
        val top = fa.top
        wall(fa.material, top, fa.bottom, alpha)
        if (fa.material == HabitMaterial.GLASS)
        // a curtain wall: its spandrels are glass too, the sky in them darker than in the panes
        withTransform({
                translate(-half, top)
                scale(B.W, fa.bottom - top, Offset.Zero)
            }) {
                drawRect(paneBrush, Offset.Zero, Size(1f, 1f), alpha = alpha * .55f)
            }
        facadeLight(top, fa.bottom, alpha)
        if (fa.material == HabitMaterial.GOLD || fa.material == HabitMaterial.GLASS)
            polish(top, fa.bottom, alpha, if (fa.material == HabitMaterial.GLASS) 90f else 70f)
        onSide {
            sideWall(fa, glass, alpha, detailed)
            sideLight(fa.side, alpha)
            if (nightOn) drawPath(fa.side, nightInk, alpha = alpha, blendMode = BlendMode.Multiply)
        }
        inks(fa.detail, f, alpha, detailed)
        if (!ruin) grime(fa, alpha)
        decayWall(fa, alpha, detailed)
        grade(fa, alpha)
        blueHour(-half, top, B.W, fa.bottom - top, .42f * alpha)
        if (!ruin && detailed && b.material != HabitMaterial.GLASS) stains(fa, top, alpha)
        val done = !live || b.done
        val count = fa.windowCount
        val w = fa.windows
        if (ruin) {
            for (k in 0 until count) {
                val x = w[k * 4]
                val y = top + w[k * 4 + 1]
                val ww = w[k * 4 + 2]
                val h = w[k * 4 + 3]
                when (fa.ruin[k]) {
                    RuinWindow.BOARDED -> {
                        drawRect(SceneInk.Boards, Offset(x, y), Size(ww, h), alpha = alpha)
                        drawLine(
                            SceneInk.BoardLine,
                            Offset(x, y + h * .3f),
                            Offset(x + ww, y + h * .45f),
                            1f,
                            alpha = alpha,
                        )
                        drawLine(
                            SceneInk.BoardLine,
                            Offset(x, y + h * .7f),
                            Offset(x + ww, y + h * .6f),
                            1f,
                            alpha = alpha,
                        )
                        // the boards take the night like the wall around them
                        if (nightOn)
                            drawRect(
                                nightInk,
                                Offset(x, y),
                                Size(ww, h),
                                alpha = alpha,
                                blendMode = BlendMode.Multiply,
                            )
                    }
                    RuinWindow.DIM ->
                        drawRect(
                            lerp(glass, SceneInk.Dim, .4f + .5f * sky.dark),
                            Offset(x, y),
                            Size(ww, h),
                            alpha = alpha,
                        )
                    RuinWindow.DARK -> {
                        drawRect(glass, Offset(x, y), Size(ww, h), alpha = alpha)
                        mirrorSky(
                            x,
                            y,
                            ww,
                            h,
                            alpha * .4f * FacadeLook.reflection(0f, sky.dark),
                            fa.seed * 31 + k,
                            .4f,
                        )
                    }
                }
            }
            // scaffolding up the sides while it comes down
            drawLine(
                SceneInk.Scaffold,
                Offset(-half - 3f, top),
                Offset(-half - 3f, fa.bottom),
                1.2f,
                alpha = .8f * alpha,
            )
            drawLine(
                SceneInk.Scaffold,
                Offset(-half - 3f, top + 2f),
                Offset(-half, fa.bottom - 2f),
                .8f,
                alpha = .6f * alpha,
            )
        } else {
            // how many windows are lit: the occupancy once the floor is done today; while it is
            // being finished they switch on one by one
            val on =
                when {
                    !done -> 0
                    landing -> ceil(fa.occupancy.lit * motion.lights).toInt()
                    else -> fa.occupancy.lit
                }
            // a missed floor's windows stay dark (its crack and its sign say it was missed): a
            // light that flickers on and off reads as a fault in the picture, not in the habit
            val failing = 0
            val sputter = false
            val seed = b.id.hashCode()
            // who is in which lit window now: only a few, taking turns
            val here = fa.here
            val cheer = if (motion.cheer == b.id) motion.cheerSince else -1f
            if (on > 0 && lod != PeopleLod.NONE && crowd.ordinal >= 0)
                Presence.floorPresence(
                    here,
                    fa.cast,
                    crowd.seed,
                    fa.seed,
                    crowd.ordinal,
                    crowd.litFloors,
                    seconds,
                    cheer,
                )
            else java.util.Arrays.fill(here, 0f)
            val glow = fa.glow
            java.util.Arrays.fill(glow, -1f)
            for (k in 0 until count) {
                val x = w[k * 4]
                val y = top + w[k * 4 + 1]
                val ww = w[k * 4 + 2]
                val h = w[k * 4 + 3]
                val bright =
                    when {
                        k < on ->
                            // an empty room's lamp goes off for a moment now and then (up close)
                            if (here[k] <= 0f && lod == PeopleLod.FULL && seconds > 0f)
                                .12f + .88f * Silhouettes.lamp(fa.cast[k].seed, seconds)
                            else 1f
                        k < failing ->
                            if (seconds <= 0f) (if (k == 0) .55f else 0f)
                            else .75f * Ambient.flickerLevel(seed + k * 7, seconds, sputter)
                        else -> 0f
                    }
                if (bright > 0f) {
                    // the room's lamp comes on at its own moment of the dusk hour (a failing light
                    // is on already); late in the evening a few rooms watch television instead
                    val wseed = seed + k * 7
                    val lampOn = if (k < on) Dusk.lightsOn(wseed, hour) else 1f
                    val tv = if (k < on && !landing) Dusk.tv(wseed, hour, seconds) else 0f
                    val lamp = bright * lampOn * (1f - tv)
                    glow[k] = min(1f, lamp + .4f * tv * bright)
                    // the bloom of a lit window at night: light spilling past its frame
                    if (sky.dark > .25f && lamp > 0f)
                        translate(x + ww / 2f, y + h / 2f) {
                            scale(ww * 1.5f, h * 1.55f, Offset.Zero) {
                                drawCircle(
                                    bloom,
                                    1f,
                                    Offset.Zero,
                                    alpha = alpha * lamp * sky.dark * .75f,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                    if (tv > 0f) {
                        // the set's cool light in a dark room, its glow stepping with the picture
                        translate(x + ww / 2f, y + h / 2f) {
                            scale(ww * 1.4f, h * 1.4f, Offset.Zero) {
                                drawCircle(
                                    tvBloom,
                                    1f,
                                    Offset.Zero,
                                    alpha = alpha * bright * sky.dark * .7f * tv,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                        drawRect(glass, Offset(x, y), Size(ww, h), alpha = alpha * bright)
                        drawRect(
                            TvLight,
                            Offset(x, y),
                            Size(ww, h),
                            alpha = alpha * bright * (.25f + .6f * tv),
                        )
                    } else {
                        // the room by the sky's light until its lamp is on
                        val room = lerp(SceneInk.WindowLitDay, glass, sky.dark)
                        drawRect(
                            lerp(room, lit, lampOn),
                            Offset(x, y),
                            Size(ww, h),
                            alpha = alpha * bright,
                        )
                    }
                    drawRect(
                        SceneInk.WindowLitLow,
                        Offset(x, y + h * .55f),
                        Size(ww, h * .45f),
                        alpha = alpha * lamp * .45f,
                    )
                } else {
                    // an unlit pane: dark glass, a dim room behind some (blinds, curtains), and
                    // over it all what the glass mirrors, more of it the more the pane is seen at
                    // a slant, each pane a hair differently turned
                    val pane = fa.seed * 31 + k
                    drawRect(glass, Offset(x, y), Size(ww, h), alpha = alpha)
                    val room = if (detailed) GlassMap.interior(pane) else -1
                    if (room >= 0) interior(x, y, ww, h, room, alpha * .9f * (1f - .75f * sky.dark))
                    if (k == fa.hall && !ruin && lod != PeopleLod.NONE)
                        drawRect(
                            SceneInk.WindowLitLow,
                            Offset(x + ww * .3f, y + h * .1f),
                            Size(ww * .4f, h * .45f),
                            alpha = alpha * (.1f + .14f * sky.dark),
                        )
                    val mirror = fresnel * (1f + PANE_FRESNEL * GlassMap.tilt(pane))
                    mirrorSky(
                        x,
                        y,
                        ww,
                        h,
                        alpha * min(1f, FacadeLook.reflection(0f, sky.dark) * mirror),
                        pane,
                    )
                }
            }
            if (on > 0 && lod != PeopleLod.NONE) {
                // the dot of the zoomed-out street: one person, on every other floor
                var dots = if (lod == PeopleLod.DOT && (crowd.ordinal and 1) == 1) 0 else 1
                for (k in 0 until on) {
                    val c = fa.cast[k]
                    val appear =
                        if (landing) ((motion.lights * fa.occupancy.lit - k) * 2f).coerceIn(0f, 1f)
                        else 1f
                    if (appear <= 0f) continue
                    // a little hop as they arrive
                    val hop = if (landing && appear < 1f) (1f - appear) * 3f else 0f
                    val x = w[k * 4]
                    val y = top + w[k * 4 + 1] - hop
                    val ww = w[k * 4 + 2]
                    val hh = w[k * 4 + 3]
                    val vis = here[k]
                    if (lod == PeopleLod.DOT) {
                        if (vis <= 0f || dots == 0) continue
                        dots--
                    }
                    if (
                        vis <= 0f &&
                            lod == PeopleLod.FULL &&
                            seconds > 0f &&
                            Silhouettes.lamp(c.seed, seconds) <= 0f
                    )
                        continue
                    val wseed = seed + k * 7
                    windowLife(
                        c,
                        x,
                        y,
                        ww,
                        hh,
                        seconds,
                        alpha * appear,
                        lod,
                        sky,
                        vis,
                        Dusk.lightsOn(wseed, hour),
                        if (landing) 0f else Dusk.tv(wseed, hour, seconds),
                    )
                }
                if (lod == PeopleLod.FULL && crowd.walker && seconds > 0f) {
                    val at = Silhouettes.commuterX(seconds, crowd.seed, B.W)
                    if (!at.isNaN()) {
                        val travel = Silhouettes.commuterTravel(seconds, crowd.seed, B.W)
                        val dir = Silhouettes.commuterDir(seconds, crowd.seed)
                        for (k in 0 until on) {
                            val rel = -half + at - w[k * 4]
                            if (rel < -3f || rel > w[k * 4 + 2] + 3f) continue
                            walkerIn(
                                w[k * 4],
                                top + w[k * 4 + 1],
                                w[k * 4 + 2],
                                w[k * 4 + 3],
                                rel,
                                dir,
                                fa.seed,
                                travel,
                                alpha,
                            )
                        }
                    }
                }
            }
            // over the rooms: the sky on lit glass, fainter the brighter the room; the shadows the
            // opening throws on the glass and the glazing bars; the room's light on the reveals
            val glassFloor = b.material == HabitMaterial.GLASS
            for (k in 0 until count) {
                val g = glow[k]
                if (g < 0f) continue
                val x = w[k * 4]
                val y = top + w[k * 4 + 1]
                val ww = w[k * 4 + 2]
                val h = w[k * 4 + 3]
                mirrorSky(
                    x,
                    y,
                    ww,
                    h,
                    alpha * .5f * fresnel * FacadeLook.reflection(g, sky.dark),
                    fa.seed * 31 + k,
                    .3f,
                )
                if (!glassFloor && sky.dark > .2f && detailed)
                    roomOnReveals(x, y, ww, h, alpha * .3f * g * sky.dark)
            }
            inks(fa.over, f, alpha, detailed)
            if (b.material == HabitMaterial.GLASS) {
                // the frame is still going up: scaffold poles and a brace
                drawLine(
                    SceneInk.Scaffold,
                    Offset(-half - 3f, top),
                    Offset(-half - 3f, fa.bottom),
                    1.2f,
                    alpha = alpha,
                )
                drawLine(
                    SceneInk.Scaffold,
                    Offset(half + 3f, top),
                    Offset(half + 3f, fa.bottom),
                    1.2f,
                    alpha = alpha * .7f,
                )
                drawLine(
                    SceneInk.Scaffold,
                    Offset(-half - 3f, top),
                    Offset(-half + 4f, fa.bottom),
                    .8f,
                    alpha = alpha * .7f,
                )
            }
        }
        decayWindows(fa, alpha, detailed, sky)
        // what stands in front of the windows: an air conditioner, a balcony, a tag on the boards
        if (detailed) inks(fa.front, f, alpha, true, night = true)
        drawLine(
            SceneInk.Occlusion,
            Offset(-half, fa.bottom - 1f),
            Offset(half, fa.bottom - 1f),
            1.5f,
            alpha = alpha,
        )
        if (season.snow > .01f) snowFloor(fa, alpha, sky)
        haze(fa, alpha)
        if (detailed && fa.name != null) plaque(fa, alpha)
        val isNext = live && art.stack.next == b.id && fa.piece.labelled
        if (isNext) glow(top, if (ruin) fa.bottom else fa.bottom, f.tint, seconds)
        if (landing && motion.burst < 1f)
            drawRect(
                SceneInk.Glow,
                Offset(-half, top),
                Size(B.W, fa.bottom - top),
                alpha = (1f - motion.burst) * .55f,
            )
        if (landing && motion.swept && motion.sweep < 1f) sheen(top, fa.bottom, motion.sweep)
        if (ruin && art.craneOn === fa && fa.storey == fa.storeys - 1) crane(top, seconds)
    }

    /**
     * A wall of [m] from [top] to [bottom]: its baked tile ([FacadeSprites]) repeated from the
     * floor's top-left corner, pixels scaled to units, and a faint grade down it. The sun's wash
     * and the lamps' pools go over it as over a flat wall.
     */
    private fun DrawScope.wall(m: HabitMaterial, top: Float, bottom: Float, alpha: Float) {
        val half = B.W / 2f
        val h = bottom - top
        val ppu = FacadeSprites.PPU
        val tile = SceneSprites.facade(m)
        withTransform({
            translate(-half, top)
            scale(1f / ppu, 1f / ppu, Offset.Zero)
        }) {
            drawRect(tile.brush, Offset.Zero, Size(B.W * ppu, h * ppu), alpha = alpha)
        }
        withTransform({
            translate(-half, top)
            scale(B.W, h, Offset.Zero)
        }) {
            drawRect(wallGrade, Offset.Zero, Size(1f, 1f), alpha = alpha)
        }
    }

    /**
     * The architecture's paths in their inks ([DetailInk]): the facade's trim, the sky's light and
     * the shade's dark at their alphas, the ironwork. Zoomed out ([detailed] false) only the trim,
     * its shade and the iron are drawn.
     */
    private fun DrawScope.inks(
        p: InkPaths,
        f: Facade,
        alpha: Float,
        detailed: Boolean,
        reveal: Color = lerp(f.wallBottom, Color.Black, .24f),
        bar: Color = f.trim,
        night: Boolean = false,
    ) {
        for (i in 0 until INK_COUNT) {
            val fill = p.fill[i]
            val stroke = p.stroke[i]
            if (fill == null && stroke == null) continue
            val ink = INKS[i]
            if (
                !detailed &&
                    ink != DetailInk.TRIM &&
                    ink != DetailInk.SHADE &&
                    ink != DetailInk.IRON &&
                    ink != DetailInk.REVEAL
            )
                continue
            val v = FacadeLook.alpha(ink, sunSide, direct, inkDark)
            if (abs(v) < .01f) continue
            val c =
                if (INK_SIGNED[i]) {
                    if (v > 0f) faceLit else if (ink == DetailInk.AO) Color.Black else castInk
                } else
                    when (ink) {
                        DetailInk.TRIM -> f.trim
                        DetailInk.LIGHT,
                        DetailInk.RAISED -> faceLit
                        DetailInk.SHADE -> Color.Black
                        DetailInk.IRON -> SceneInk.Iron
                        DetailInk.IRON_LIGHT -> SceneInk.IronLight
                        DetailInk.BOX -> SceneInk.Box
                        DetailInk.BOX_DARK -> SceneInk.BoxDark
                        DetailInk.SPRAY -> SceneInk.Spray
                        DetailInk.REVEAL -> reveal
                        DetailInk.BAR -> bar
                        else -> Color.Black
                    }
            val a = alpha * abs(v)
            if (fill != null) drawPath(fill, c, alpha = a)
            if (stroke != null && detailed) drawPath(stroke, c, alpha = a, style = inkStroke)
            // things standing proud of the wall (an air conditioner, a balcony) are lit by the
            // same night as the wall behind them
            if (
                night &&
                    nightOn &&
                    !INK_SIGNED[i] &&
                    ink != DetailInk.LIGHT &&
                    ink != DetailInk.RAISED &&
                    ink != DetailInk.SHADE
            ) {
                if (fill != null)
                    drawPath(fill, nightInk, alpha = a, blendMode = BlendMode.Multiply)
                if (stroke != null)
                    drawPath(
                        stroke,
                        nightInk,
                        alpha = a,
                        style = inkStroke,
                        blendMode = BlendMode.Multiply,
                    )
            }
        }
    }

    /**
     * The habit's name on a small glass label at the left of its floor, its count (in small caps)
     * beside it: a dark translucent panel with a soft shadow under it, a lighter sheen across its
     * top and a fine bright edge, in light type, so it reads on a pale stone wall at noon and on a
     * dark one at night.
     */
    private fun DrawScope.plaque(fa: FloorArt, alpha: Float) {
        val name = fa.name ?: return
        val x = -B.W / 2f + B.PLAQUE_X
        // set in the band under the windows; a run-down storey's hangs over its boarded windows
        val band = if (fa.piece.kind == PieceKind.RUIN) fa.bottom - fa.top else B.PLAQUE_BAND
        val y = fa.bottom - band / 2f - fa.plaqueH / 2f - if (band == B.PLAQUE_BAND) 1f else 0f
        val w = fa.plaqueW
        val h = fa.plaqueH
        val corner = CornerRadius(min(h / 2f, 5f))
        // a soft shadow lifts it off the wall
        drawRoundRect(
            Color.Black,
            Offset(x - 1f, y + .6f),
            Size(w + 2f, h + 2.2f),
            CornerRadius(corner.x + 1f),
            alpha = alpha * .2f,
        )
        drawRoundRect(SceneInk.GlassPanel, Offset(x, y), Size(w, h), corner, alpha = alpha * .84f)
        // the sheen across its top half, and the fine rim: bright along the top, faint below
        drawRoundRect(
            Color.White,
            Offset(x + .5f, y + .5f),
            Size(w - 1f, h * .46f),
            CornerRadius(corner.x - .5f),
            alpha = alpha * .09f,
        )
        drawRoundRect(
            SceneInk.GlassRim,
            Offset(x + .4f, y + .4f),
            Size(w - .8f, h - .8f),
            CornerRadius(corner.x - .4f),
            alpha = alpha * .5f,
            style = rim,
        )
        drawRect(
            Color.White,
            Offset(x + corner.x, y + .5f),
            Size(w - corner.x * 2f, .6f),
            alpha = alpha * .3f,
        )
        val mid = y + h / 2f
        drawText(
            name,
            SceneInk.GlassInk,
            Offset(x + 5f, mid - name.size.height / 2f),
            alpha = alpha,
        )
        val count = fa.count ?: return
        drawText(
            count,
            SceneInk.GlassInkSoft,
            Offset(x + 5f + name.size.width + 7f, mid - count.size.height / 2f + .3f),
            alpha = alpha,
        )
    }

    /**
     * A missed floor's decay on its wall ([FloorDecay]): the cracks in its joints, the pieces of
     * its face come away, the rubble at its foot, the damp and the rust run down from them; or the
     * pale gold mortar of a mend. Drawn before the night and the ground's grade go over the wall,
     * so it takes the scene's light as the wall does; the hairlines and streaks only up close.
     */
    private fun DrawScope.decayWall(fa: FloorArt, alpha: Float, detailed: Boolean) {
        val d = fa.decay ?: return
        clipRect(-B.W / 2f, fa.top, B.W / 2f, fa.bottom) {
            for (i in DECAY_INKS.indices) {
                val ink = DECAY_INKS[i]
                if (!ink.wall || (ink.fine && !detailed)) continue
                val p = d.paths[i] ?: continue
                val c = if (ink.argb == 0L) fa.facade.wallBottom else decayInk[i]
                val line = decayLine[i]
                if (line != null) drawPath(p, c, alpha = alpha * ink.alpha, style = line)
                else drawPath(p, c, alpha = alpha * ink.alpha)
            }
            if (!detailed) return@clipRect
            val ppu = FacadeSprites.PPU
            val wet = .75f + .25f * weather.wet
            for (s in d.stains) {
                val len = min(s[2], fa.bottom - s[1] - .5f)
                if (len < 2f) continue
                val rust = s[3] > 0f
                withTransform({
                    translate(s[0] - FacadeSprites.DRIP_W / 2f, s[1])
                    scale(1f / ppu, len / FacadeSprites.DRIP_H / ppu, Offset.Zero)
                }) {
                    drawImage(
                        SceneSprites.drip,
                        Offset.Zero,
                        alpha =
                            alpha *
                                wet *
                                if (rust) FloorDecay.RUST_ALPHA else FloorDecay.DAMP_ALPHA,
                        colorFilter = if (rust) rustFilter else null,
                    )
                }
            }
        }
    }

    /**
     * A missed floor's decay over its windows: a cracked pane, one broken and taped across, one
     * boarded with a notice on it, a curtain wall's new glazing bead. The cracks in the glass catch
     * less light at night; the boards, the tape and the paper take the night as the wall does.
     */
    private fun DrawScope.decayWindows(
        fa: FloorArt,
        alpha: Float,
        detailed: Boolean,
        sky: SkyColors,
    ) {
        val d = fa.decay ?: return
        clipRect(-B.W / 2f, fa.top, B.W / 2f, fa.bottom) {
            for (i in DECAY_INKS.indices) {
                val ink = DECAY_INKS[i]
                if (ink.wall || (ink.fine && !detailed)) continue
                val p = d.paths[i] ?: continue
                val a =
                    alpha * ink.alpha * if (ink == DecayInk.PANE_CRACK) 1f - .5f * sky.dark else 1f
                val line = decayLine[i]
                if (line != null) drawPath(p, decayInk[i], alpha = a, style = line)
                else drawPath(p, decayInk[i], alpha = a)
                if (ink.night && nightOn) {
                    if (line != null)
                        drawPath(
                            p,
                            nightInk,
                            alpha = a,
                            style = line,
                            blendMode = BlendMode.Multiply,
                        )
                    else drawPath(p, nightInk, alpha = a, blendMode = BlendMode.Multiply)
                }
            }
        }
    }

    /** A tower crane on the roof of a building being taken down. */
    private fun DrawScope.crane(top: Float, seconds: Float) {
        val x = B.W / 2f - 26f
        val swing = if (seconds > 0f) sin(seconds * .5f) * 4f else 0f
        drawRect(SceneInk.Crane, Offset(x - 1.5f, top - 54f), Size(3f, 54f))
        var y = top - 50f
        while (y < top) {
            drawLine(SceneInk.Crane, Offset(x - 1.5f, y), Offset(x + 1.5f, y + 6f), .7f)
            y += 6f
        }
        drawRect(SceneInk.Crane, Offset(x - 74f, top - 56f), Size(90f, 2.4f))
        drawRect(SceneInk.Lamp, Offset(x + 8f, top - 58f), Size(8f, 6f))
        val hook = x - 58f + swing
        drawLine(SceneInk.Lamp, Offset(x - 58f, top - 54f), Offset(hook, top - 26f), .8f)
        drawRect(SceneInk.Boards, Offset(hook - 5f, top - 26f), Size(10f, 5f))
    }

    /**
     * The roof face over a front [half] wide with its front edge at [y]: [B.DY] deep, its back edge
     * skewed to the shown side ([sideShift]), square on when none shows.
     */
    private fun DrawScope.roofFace(
        y: Float,
        color: Color,
        half: Float,
        blend: BlendMode = BlendMode.SrcOver,
    ) {
        val s = sideShift
        path.rewind()
        path.moveTo(-half, y)
        path.lineTo(-half + s, y - B.DY)
        path.lineTo(half + s, y - B.DY)
        path.lineTo(half, y)
        path.close()
        drawPath(path, color, blendMode = blend)
    }

    /** The parapet, the roof, and what stands on it: a water tank, the reward's sign, the ghost. */
    private fun DrawScope.roof(
        art: BuildingArt,
        live: Boolean,
        detailed: Boolean,
        sky: SkyColors,
        seconds: Float,
    ) {
        val half = B.W / 2f
        val y = -art.layout.roof
        if (art.floors.isNotEmpty()) {
            val f = facade(art.crown ?: HabitMaterial.STONE)
            val gold = art.crown == HabitMaterial.GOLD
            val c = if (gold) f.trim else f.roof
            drawRect(c, Offset(-half - 3f, y - B.CORNICE), Size(B.W + 6f, B.CORNICE))
            // its coping, its dentils or brackets, and its shadow down the wall under it
            inks(art.cornice, f, 1f, detailed)
            // its shadow down the wall: longer and leaning away from the light in the sun
            clipRect(-half, y, half, y + 12f) {
                withTransform({
                    translate(-half - sunSide * 2.5f, y)
                    scale(B.W, 4.5f + 5f * direct, Offset.Zero)
                }) {
                    drawRect(corniceDrop, Offset.Zero, Size(1f, 1f), alpha = .75f + .25f * direct)
                }
            }
            drawRect(
                faceLit,
                Offset(-half - 3f, y - B.CORNICE),
                Size(B.W + 6f, .4f),
                alpha = .3f * direct,
            )
            rimLight(sky, -half - 3f, y - B.CORNICE, B.W + 6f)
            roofFace(y - B.CORNICE, f.roof, half + 3f)
            if (nightOn) {
                drawRect(
                    nightInk,
                    Offset(-half - 3f, y - B.CORNICE),
                    Size(B.W + 6f, B.CORNICE),
                    blendMode = BlendMode.Multiply,
                )
                roofFace(y - B.CORNICE, nightInk, half + 3f, BlendMode.Multiply)
            }
            // the cornice's own side slab, on the edge the shown side hangs from
            val s = sideShift
            if (abs(s) >= .02f) {
                val edge = if (s > 0f) half + 3f else -half - 3f
                path.rewind()
                path.moveTo(edge, y - B.CORNICE)
                path.lineTo(edge + s, y - B.CORNICE - B.DY)
                path.lineTo(edge + s, y - B.DY)
                path.lineTo(edge, y)
                path.close()
                drawPath(path, f.side)
                sideLight(path, 1f)
                if (nightOn) drawPath(path, nightInk, blendMode = BlendMode.Multiply)
            }
            if (gold) {
                // gold finials along the cornice
                var x = -half
                while (x <= half) {
                    drawCircle(f.trim, 2.2f, Offset(x, y - B.CORNICE - 2f))
                    x += B.W / 6f
                }
            }
            if (art.craneOn == null) {
                // a water tower on its stand
                val tx = -half + 26f
                val base = y - B.CORNICE - B.DY / 2f
                tank(tx, base, sky)
                vent(art.stack.id.hashCode(), tx, base, seconds, sky)
            }
            if (detailed) clutter(art, y, sky)
            if (season.snow > .01f) snowRoof(sky, y, art.craneOn == null)
            cornicePigeons(art, y, seconds, sky)
        }
        val s = art.sign
        if (s != null) sign(art, s.top, s.width, detailed, sky, seconds)
        val g = art.ghost
        if (g != null && live) {
            val gh = g.width / 2f
            drawRoundRect(
                SceneInk.PlanFill,
                Offset(-gh, -g.top),
                Size(g.width, g.height),
                CornerRadius(6f),
            )
            drawRoundRect(
                SceneInk.PlanLine,
                Offset(-gh, -g.top),
                Size(g.width, g.height),
                CornerRadius(6f),
                style = ghostLine,
            )
            val words = art.ghostWords
            if (words != null && detailed)
                drawText(
                    words,
                    SceneInk.PlanText,
                    Offset(-words.size.width / 2f, -g.middle - words.size.height / 2f),
                )
        }
    }

    /**
     * What else stands on the roof ([RoofKit], by the building's seed): a vent stack and a dish on
     * the right of the roof (clear of the tank and the sign's legs), a skylight in the middle and a
     * railing along the back edge when no sign stands there.
     */
    private fun DrawScope.clutter(art: BuildingArt, y: Float, sky: SkyColors) {
        val kit = art.choices.roof
        val half = B.W / 2f
        val base = y - B.CORNICE - B.DY / 2f
        val glass = lerp(SceneInk.GlassDay, SceneInk.GlassNight, sky.dark)
        val roof = SceneSprites.roof
        if (kit.stack) {
            val x = half - 38f
            footShadow(x, base, 3.4f)
            picture(roof.stack, x, base)
            shaded(x - 1.6f, base - 11.6f, 3.2f, 10f)
            rimLight(sky, x - 3.2f, base - 13.8f, 6.4f)
        }
        if (kit.dish) {
            val x = half - 20f
            footShadow(x, base, 3f)
            picture(roof.dish, x, base)
            rimLight(sky, x - 4.4f, base - 14.3f, 7f)
        }
        if (art.sign == null) {
            // an air-handling unit on its sleepers, on some roofs
            if ((art.stack.id.hashCode() ushr 3) and 1 == 0) {
                val x = -half + 58f
                footShadow(x, base, 9.5f)
                picture(roof.hvac, x, base)
                shaded(x - 8f, base - 10.4f, 16f, 9.5f)
                rimLight(sky, x - 8f, base - 10.4f, 16f)
            }
            if (kit.skylight) {
                drawRect(SceneInk.Iron, Offset(-10f, base - 4.4f), Size(20f, 4.4f))
                drawRect(glass, Offset(-9f, base - 3.8f), Size(18f, 2.6f))
                mirrorSky(-9f, base - 3.8f, 18f, 2.6f, .8f * (1f - .3f * sky.dark), 91, .6f)
                drawRect(SceneInk.IronLight, Offset(-10f, base - 4.4f), Size(20f, .4f), alpha = .5f)
                drawRect(SceneInk.Iron, Offset(-.3f, base - 3.8f), Size(.6f, 2.6f))
            }
            if (kit.railing) railing(y)
        }
    }

    /**
     * The railing along the roof's back edge: square posts on base plates, a top rail lit along its
     * top and a mid rail, its shadow on the roof in front of it.
     */
    private fun DrawScope.railing(y: Float) {
        val half = B.W / 2f
        val s = sideShift
        val back = y - B.CORNICE - B.DY
        val x0 = -half - 3f + s + 2f
        val x1 = half + 3f + s - 2f
        drawRect(
            castInk,
            Offset(x0 - sunSide, back - .2f),
            Size(x1 - x0, 1.6f),
            alpha = .3f * direct,
        )
        var x = x0
        while (x <= x1 + .01f) {
            drawRect(SceneInk.Iron, Offset(x - .45f, back - 5.6f), Size(.9f, 5.6f))
            drawRect(
                SceneInk.IronLight,
                Offset(x - .45f, back - 5.6f),
                Size(.3f, 5.6f),
                alpha = .35f,
            )
            drawRect(SceneInk.Iron, Offset(x - .9f, back - .5f), Size(1.8f, .5f))
            x += 10f
        }
        drawRect(SceneInk.Iron, Offset(x0, back - 2.9f), Size(x1 - x0, .5f))
        drawRect(SceneInk.Iron, Offset(x0 - .3f, back - 5.9f), Size(x1 - x0 + .6f, 1f))
        drawRect(faceLit, Offset(x0 - .3f, back - 5.9f), Size(x1 - x0 + .6f, .35f), alpha = .45f)
        drawRect(
            Color.Black,
            Offset(x0 - .3f, back - 4.95f),
            Size(x1 - x0 + .6f, .25f),
            alpha = .3f,
        )
    }

    /**
     * The water tower ([RoofArt.tank]) with its foot's middle at ([x], [y]): its shadow on the
     * roof, thrown away from the light; the tower by the sky's light and, over it, as lit by the
     * sun from its side as the sun is strong on that side; the dusk's rim along its roof.
     */
    private fun DrawScope.tank(x: Float, y: Float, sky: SkyColors) {
        val roof = SceneSprites.roof
        drawOval(
            castInk,
            Offset(x - 10.5f - sunSide * 5f - 3f * abs(sunSide), y - 2f),
            Size(21f + 6f * abs(sunSide), 3.6f),
            alpha = .16f + .24f * direct,
        )
        picture(roof.tank, x, y)
        val sun = min(1f, abs(sunSide) * 1.4f)
        if (sun > .02f) picture(if (sunSide < 0f) roof.tankLeft else roof.tankRight, x, y, sun)
        rimLight(sky, x - 8.9f, y - 24.2f, 17.8f)
    }

    /** A soft shadow round the foot of a thing on the roof at [x], [y], [r] each side. */
    private fun DrawScope.footShadow(x: Float, y: Float, r: Float) {
        drawOval(
            castInk,
            Offset(x - r - 1f - sunSide * 2f, y - 1.2f),
            Size(2f * r + 2f + 2f * abs(sunSide), 2.4f),
            alpha = .14f + .2f * direct,
        )
    }

    /**
     * A [RoofArt] picture with its foot's middle at ([x], [y]), at [alpha], under the night's
     * light.
     */
    private fun DrawScope.picture(p: RoofImage, x: Float, y: Float, alpha: Float = 1f) {
        val r = p.picture
        withTransform({
            translate(x - r.ax, y - r.ay)
            scale(1f / r.ppu, 1f / r.ppu, Offset.Zero)
        }) {
            drawImage(p.image, alpha = alpha, colorFilter = nightFilter)
        }
    }

    /**
     * Light on the side of a thing on the roof ([x], [y], [w] by [h]) that faces the light, and
     * shade on the other side, by the light's side and strength.
     */
    private fun DrawScope.shaded(x: Float, y: Float, w: Float, h: Float, oval: Boolean = false) {
        val s = sunSide
        if (abs(s) < .03f) return
        val litX = if (s < 0f) x else x + w / 2f
        val dimX = if (s < 0f) x + w / 2f else x
        if (oval) {
            drawOval(faceLit, Offset(litX, y), Size(w / 2f, h), alpha = .22f * abs(s))
            drawOval(castInk, Offset(dimX, y), Size(w / 2f, h), alpha = .3f * abs(s))
        } else {
            drawRect(faceLit, Offset(litX, y), Size(w / 2f, h), alpha = .22f * abs(s))
            drawRect(castInk, Offset(dimX, y), Size(w / 2f, h), alpha = .3f * abs(s))
        }
    }

    /**
     * A thing on the roof against the sky at dusk and in the blue hour: a faint rim of the glow
     * along its top edge from ([x], [y]), [w] long.
     */
    private fun DrawScope.rimLight(sky: SkyColors, x: Float, y: Float, w: Float) {
        val a = .6f * max(gold, .55f * blue)
        if (a < .02f) return
        drawRect(lerp(sky.sky[2], Color.White, .35f), Offset(x, y - .2f), Size(w, .55f), alpha = a)
    }

    /** Two pigeons along the cornice of some buildings (the park's pigeons are on the pavement). */
    private fun DrawScope.cornicePigeons(
        art: BuildingArt,
        y: Float,
        seconds: Float,
        sky: SkyColors,
    ) {
        val seed = art.stack.id.hashCode()
        if (art.layout.trees.isNotEmpty() || art.sign != null) return
        val half = B.W / 2f
        pigeons(
            seed,
            Dusk.pigeons(seed, false),
            -half + 40f,
            B.W - 80f,
            y - B.CORNICE,
            seconds,
            sky,
            false,
        )
    }

    /**
     * The rooftop reward's sign: dark while it is locked, lit with running bulbs once the building
     * is done today, a flag beside it once it has been claimed.
     */
    private fun DrawScope.sign(
        art: BuildingArt,
        top: Float,
        width: Float,
        detailed: Boolean,
        sky: SkyColors,
        seconds: Float,
    ) {
        val st = art.stack
        val open = st.claimable || st.claimed
        val half = width / 2f
        val y = -top
        val panel = 24f
        drawLine(
            SceneInk.Lamp,
            Offset(-half + 14f, y + panel),
            Offset(-half + 14f, y + B.SIGN_H),
            2.4f,
        )
        drawLine(
            SceneInk.Lamp,
            Offset(half - 14f, y + panel),
            Offset(half - 14f, y + B.SIGN_H),
            2.4f,
        )
        // the steel frame: the legs cross-braced, a rail along the top carrying three lamps
        drawLine(
            SceneInk.Lamp,
            Offset(-half + 14f, y + panel + 2f),
            Offset(half - 14f, y + B.SIGN_H - 1f),
            1f,
        )
        drawLine(
            SceneInk.Lamp,
            Offset(half - 14f, y + panel + 2f),
            Offset(-half + 14f, y + B.SIGN_H - 1f),
            1f,
        )
        drawRect(SceneInk.Iron, Offset(-half + 4f, y - 2.6f), Size(width - 8f, 1.2f))
        for (i in 0..2) {
            val lx = -half + width * (.2f + .3f * i)
            drawRect(SceneInk.Iron, Offset(lx - .5f, y - 2.6f), Size(1f, 1.6f))
            path.rewind()
            path.moveTo(lx - 3.2f, y - 1.2f)
            path.lineTo(lx + 3.2f, y - 1.2f)
            path.lineTo(lx + 2.2f, y + 1.2f)
            path.lineTo(lx - 2.2f, y + 1.2f)
            path.close()
            drawPath(path, SceneInk.Iron)
            drawRect(
                if (open) SceneInk.Bulb else SceneInk.IronLight,
                Offset(lx - 2f, y + .6f),
                Size(4f, .8f),
                alpha = if (open) 1f else .5f,
            )
        }
        if (open) {
            val pulse = if (st.claimable && seconds > 0f) .65f + .35f * sin(seconds * 3.1f) else 1f
            drawRoundRect(
                SceneInk.Glow,
                Offset(-half - 6f, y - 6f),
                Size(width + 12f, panel + 12f),
                CornerRadius(10f),
                alpha = .25f * pulse,
            )
        }
        drawRect(
            if (open) SceneInk.SignLit else SceneInk.SignOff,
            Offset(-half, y),
            Size(width, panel),
        )
        drawRect(SceneInk.Occlusion, Offset(-half, y + panel - 2f), Size(width, 2f))
        drawRect(SceneInk.SignFrame, Offset(-half, y), Size(width, panel), style = frameStroke)
        if (open)
            withTransform({
                translate(-half, y)
                scale(width, panel * .7f, Offset.Zero)
            }) {
                drawRect(lampWash, Offset.Zero, Size(1f, 1f))
            }
        if (open) {
            // bulbs round the edge: chasing while it waits to be claimed, steady once it has been
            val chase = Ambient.step(seconds) / 3
            var i = 0
            var x = -half + 4f
            while (x < half) {
                val on = st.claimed || seconds <= 0f || (i + chase) % 3 != 0
                drawCircle(SceneInk.Bulb, 1.3f, Offset(x, y + 2.2f), if (on) 1f else .3f)
                drawCircle(SceneInk.Bulb, 1.3f, Offset(x, y + panel - 3.6f), if (on) 1f else .3f)
                x += 9f
                i++
            }
        }
        val words = art.treat
        if (words != null && detailed)
            drawText(
                words,
                if (open) SceneInk.SignLitText else SceneInk.SignOffText,
                Offset(-words.size.width / 2f, y + panel / 2f - words.size.height / 2f),
            )
        if (st.claimed) {
            val x = half + 8f
            drawLine(SceneInk.FlagPole, Offset(x, y + B.SIGN_H), Offset(x, y - 14f), 1.6f)
            // the flag ripples in the wind the trees sway in, not a wind of its own
            val wave = if (seconds > 0f) Ambient.sway(st.id.hashCode(), seconds) * .95f else 0f
            path.rewind()
            path.moveTo(x, y - 14f)
            path.quadraticTo(x + 8f, y - 12f - wave, x + 16f, y - 9.5f + wave)
            path.lineTo(x, y - 5f)
            path.close()
            drawPath(path, SceneInk.Flag)
        }
    }

    private val curtainColors = arrayOf(Color(0xFF8A4B3A), Color(0xFF4E6B6B), Color(0xFF8A7A4A))
    private val soil = Color(0xFF6B3A24)
    private val leaf = Color(0xFF3A4A22)
    private val furniture = Color(0xFF3B2415)
    private val room = 1f / RoomSprites.PPU
    private val roomLight = 1f / RoomSprites.LIGHT_PPU
    private val frame = 1f / PeopleSprites.PPU

    /** The sun falling into a room through its window: a warm patch on the floor and back wall. */
    private val sunPatch =
        Brush.verticalGradient(
            0f to Color(0x00FFE2B0),
            .45f to Color(0x66FFE2B0),
            1f to Color(0x99FFE2B0),
            startY = 0f,
            endY = 1f,
        )
    private val sunSkew = Matrix()

    /** The venetian blind's slats and the roller blind's cloth. */
    private val slat = Color(0xFFE8E2D4)
    private val roller = Color(0xFFD8CCB4)

    /**
     * The lobby visitor's state this frame (filled in place: nothing is allocated while drawing).
     */
    private val stroll = Stroll()

    /**
     * One lit window: at full detail its room (the overlay behind, the person in it, the overlay in
     * front), curtains and what sits on its sill; smaller, a simple shape of a person; smaller
     * still, a moving dot. The room is always there; [here] says how much of a person is in it (0
     * for nobody, 1 settled in, between while they walk in or out). The whole room faces one way or
     * the other, so no two neighbours are alike.
     */
    private fun DrawScope.windowLife(
        c: WindowCast,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        seconds: Float,
        alpha: Float,
        lod: PeopleLod,
        sky: SkyColors,
        here: Float,
        lampOn: Float,
        tv: Float,
    ) {
        val act = c.activity
        if (lod != PeopleLod.FULL) {
            if (here <= 0f) return
            val bob = if (seconds > 0f) kotlin.math.sin(seconds * 7f + c.seed) * .6f else 0f
            val cx = x + w / 2f
            if (lod == PeopleLod.BLOB)
                clipRect(x, y, x + w, y + h) {
                    drawCircle(SceneInk.Person, 2.1f, Offset(cx, y + h - 10.5f + bob), alpha * here)
                    drawRoundRect(
                        SceneInk.Person,
                        Offset(cx - 2.8f, y + h - 8.4f + bob),
                        Size(5.6f, 9f),
                        CornerRadius(2.6f),
                        alpha = alpha * here,
                    )
                }
            else
                drawCircle(SceneInk.Person, 1.5f, Offset(cx, y + h - 3f + bob), alpha * here * .85f)
            return
        }
        val rm = SceneSprites.room(c.room, c.variant, h)
        val dir = if (c.flip) -1f else 1f
        val s = RoomSprites.personScale(h) * PeopleActivities.roomFit(c.room)
        val overlay = alpha * (.9f + .1f * sky.dark)
        val day = 1f - sky.dark
        // the lamp lights the room when it is on and the set is off; a monitor is on whenever the
        // room is lit, a set as much as its picture flickers
        val lampK = overlay * lampOn * (1f - tv)
        val screenK =
            if (rm.screen == null) 0f
            else
                alpha *
                    max(tv, if (c.room == RoomKind.OFFICE) .35f + .3f * here else 0f) *
                    (.35f + .65f * sky.dark)
        clipRect(x, y, x + w, y + h) {
            translate(x, y) {
                scale(dir, 1f, Offset(w / 2f, 0f)) {
                    scale(room, room, Offset.Zero) { drawImage(rm.base, Offset.Zero, overlay) }
                    if (lampK > .01f)
                        scale(roomLight, roomLight, Offset.Zero) {
                            drawImage(
                                rm.lamp,
                                Offset(rm.lampDx, rm.lampDy),
                                lampK,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    if (day > .01f) {
                        // the day's light through the glass, and the sun's patch on the sun's side
                        drawRect(
                            SceneInk.WindowLitDay,
                            Offset.Zero,
                            Size(w, h),
                            alpha = alpha * day * .16f,
                        )
                        if (sunSide != 0f) sunlight(w, h, sunSide * dir, alpha * day)
                    }
                    if (tv > 0f && rm.screen == null)
                        drawRect(TvLight, Offset.Zero, Size(w, h), alpha = alpha * tv * .25f)
                    if (here > 0f) person(act, c, w, h, s, seconds, alpha, dir, here)
                    if (screenK > .01f)
                        scale(roomLight, roomLight, Offset.Zero) {
                            drawImage(
                                rm.screen!!,
                                Offset(rm.screenDx, rm.screenDy),
                                screenK,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    scale(room, room, Offset.Zero) {
                        drawImage(rm.front, Offset(rm.frontDx, rm.frontDy), overlay)
                    }
                    if (act == Activity.COOKING && here > 0f && seconds > 0f)
                        steam(c, w, h, s, seconds, alpha * here, sky)
                }
                if (c.curtain != 0) curtains(c, w, h, alpha)
                if (c.sill != 0 && here < 1f) sillItem(c, w, h, alpha * (1f - here))
                // the glass: the sky in the top of the pane by day
                if (day > .01f)
                    scale(w, h, Offset.Zero) {
                        drawRect(glassSheen, Offset.Zero, Size(1f, 1f), alpha = alpha * day * .55f)
                    }
            }
        }
    }

    /**
     * The sun falling into the room: a warm patch on the floor and the foot of the back wall,
     * leaning away from the side the sun is on ([side], -1 to 1 times the light's strength).
     */
    private fun DrawScope.sunlight(w: Float, h: Float, side: Float, alpha: Float) {
        val k = abs(side)
        if (k < .02f) return
        val lean = if (side > 0f) -.55f else .55f
        val pw = w * .5f
        val x0 = if (side > 0f) w * .05f + w * .25f else w * .45f
        sunSkew.reset()
        sunSkew[1, 0] = lean
        withTransform({
            translate(x0, h * .4f)
            transform(sunSkew)
            scale(pw, h * .6f, Offset.Zero)
        }) {
            drawRect(
                sunPatch,
                Offset.Zero,
                Size(1f, 1f),
                alpha = alpha * k * .5f,
                blendMode = BlendMode.Plus,
            )
        }
    }

    /**
     * The steam off the pan on the hob of a kitchen someone is cooking in: a few soft puffs rising
     * and thinning, the same puffs as the roof vents'.
     */
    private fun DrawScope.steam(
        c: WindowCast,
        w: Float,
        h: Float,
        s: Float,
        seconds: Float,
        alpha: Float,
        sky: SkyColors,
    ) {
        duskTints(sky)
        val puffs = SceneSprites.dusk.puffs
        val half = DuskSprites.PUFF / 2f
        val px = w / 2f + s * (9f + PeopleActivities.shift(RoomKind.KITCHEN))
        val top = h - s * 10.6f
        for (j in 0 until Dusk.PUFFS) {
            val t = Dusk.puff(c.seed, j, seconds * 1.4f)
            val size = (.3f + 1.1f * t) * 3.2f * s / DuskSprites.PUFF
            val x = px + t * 1.2f + .8f * t * t
            val y = top - t * 7f * s
            val a = .4f * (1f - t) * (1f - t) * min(1f, t * 10f)
            if (a <= .01f) continue
            withTransform({
                translate(x, y)
                scale(size, size, Offset.Zero)
            }) {
                drawImage(
                    puffs[j % DuskSprites.PUFFS],
                    Offset(-half, -half),
                    alpha = alpha * a,
                    colorFilter = steamFilter,
                )
            }
        }
    }

    /**
     * The person of a window at the window's own origin: their frame of this moment, and the next
     * one blended in. [here] below 1 is them walking in from (or out to) the side of the window.
     */
    private fun DrawScope.person(
        act: Activity,
        c: WindowCast,
        w: Float,
        h: Float,
        s: Float,
        seconds: Float,
        alpha: Float,
        dir: Float,
        here: Float,
    ) {
        val spec = PeopleActivities.of(act)
        var ax = s * PeopleActivities.shift(c.room) + w / 2f + (1f - here) * 7f * dir
        var face = 1f
        var size = s * PeopleActivities.fitted(act)
        var phase = 0f
        var lift = 0f
        var lean = 0f
        if (act == Activity.WALKING) {
            // walking past: across the window and out of it, at the pace of the stride
            size = RoomSprites.personScale(h) * PeopleActivities.fitted(act)
            // a taller person takes longer strides: the legs are as long as the body is tall, and
            // the look has its own stride and the person their own tempo
            val pace = Gait.pace(size, c.look)
            val steady = Silhouettes.walkTravel(seconds, c.seed, w, pace, Gait.tempo(c.seed))
            if (steady.isNaN()) return
            // the live distance places them AND picks the frame: so the feet never slide
            val travel = if (seconds > 0f) Gait.liveTravel(steady, pace, c.seed) else steady
            val at = Silhouettes.walkAt(travel, c.seed, w)
            ax = if (dir > 0f) at else w - at
            face = Silhouettes.walkDir(c.seed) * dir
            if (seconds > 0f) {
                val strides = Gait.strides(travel, pace)
                phase = strides - floor(strides)
                lift = Gait.bob(strides, c.seed) * size
                lean = Gait.lean(c.seed)
            }
        } else if (spec.smooth) {
            phase = Silhouettes.phase(spec, seconds, c.seed)
        }
        translate(ax, h - lift) {
            scale(face * size * frame, size * frame, Offset.Zero) {
                if (spec.smooth) {
                    smoothFrame(
                        c.look,
                        act,
                        Gait.nearest(phase, spec.frames),
                        Gait.neighbour(phase, spec.frames),
                        abs(Gait.offset(phase, spec.frames)),
                        alpha * here,
                        lean,
                    )
                } else {
                    val look = PeopleActivities.lookFor(act, c.look)
                    val f = Silhouettes.frame(spec, seconds, c.seed)
                    drawPerson(SceneSprites.person(look, act, f), alpha * here)
                    val mix = Silhouettes.blend(spec, seconds, c.seed)
                    if (mix > .01f)
                        drawPerson(
                            SceneSprites.person(look, act, (f + 1) % spec.frames),
                            alpha * here * mix,
                        )
                }
            }
        }
        if (spec.pair && act != Activity.WALKING) {
            // the second of a pair: mirrored about the room's middle, another look, out of step
            val look = PeopleActivities.lookFor(act, (c.look + 3) % Looks.COUNT)
            val f = Silhouettes.frame(spec, seconds, c.seed + 977)
            val mix = Silhouettes.blend(spec, seconds, c.seed + 977)
            translate(ax, h) {
                scale(-face * size * frame, size * frame, Offset.Zero) {
                    drawPerson(SceneSprites.person(look, act, f), alpha * here)
                    if (mix > .01f)
                        drawPerson(
                            SceneSprites.person(look, act, (f + 1) % spec.frames),
                            alpha * here * mix,
                        )
                }
            }
        }
    }

    /**
     * A frame of a smooth activity and the frame beside it (behind, as much as the picture is
     * between the two), at the current origin: the nearest baked frame to where the loop is now, so
     * that it follows the display's own refresh, softened by its neighbour.
     */
    private fun DrawScope.smoothFrame(
        look: Int,
        act: Activity,
        frame: Int,
        next: Int,
        mix: Float,
        alpha: Float,
        lean: Float = 0f,
    ) {
        if (lean != 0f) {
            // the person's own posture: a tiny lean of the whole figure, as a skew about the feet
            leanSkew.reset()
            leanSkew[1, 0] = -tan(lean * (PI.toFloat() / 180f))
            withTransform({ transform(leanSkew) }) { pair(look, act, frame, next, mix, alpha) }
        } else pair(look, act, frame, next, mix, alpha)
    }

    private val leanSkew = Matrix()

    private fun DrawScope.pair(
        look: Int,
        act: Activity,
        frame: Int,
        next: Int,
        mix: Float,
        alpha: Float,
    ) {
        if (next != frame && mix > .03f)
            drawPerson(SceneSprites.person(look, act, next), alpha * mix)
        drawPerson(SceneSprites.person(look, act, frame), alpha)
    }

    /** A baked figure at the current origin (its feet's middle), in the sprite's own pixels. */
    private fun DrawScope.drawPerson(p: PersonImage, alpha: Float) {
        val f = personFilter
        if (f != null || dayMix < .01f) {
            drawImage(p.image, Offset(p.dx, p.dy), alpha, colorFilter = f)
            return
        }
        // by day a figure behind glass is a flat dark shape: the warm rim and the glow baked in for
        // a lit room at night are flattened away, and the two looks cross-fade through dusk
        if (dayMix < .99f)
            drawImage(p.image, Offset(p.dx, p.dy), alpha * (1f - dayMix), colorFilter = null)
        drawImage(p.image, Offset(p.dx, p.dy), alpha * dayMix, colorFilter = dayFilter)
    }

    /** Someone walking along the floor, seen in the window [x], [y] ([rel] from its left edge). */
    private fun DrawScope.walkerIn(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rel: Float,
        dir: Float,
        seed: Int,
        travel: Float,
        alpha: Float,
    ) {
        val spec = PeopleActivities.of(Activity.WALKING)
        val size = RoomSprites.personScale(h) * PeopleActivities.fitted(Activity.WALKING)
        val look = (seed and 0x7fffffff) % Looks.COUNT
        // the steps follow the ground covered: nobody skates, whatever their size; the live
        // distance moves them a little ahead of or behind the steady one, and picks the frame
        val pace = Gait.pace(size, look)
        val live = Gait.liveTravel(travel, pace, seed)
        val strides = Gait.strides(live, pace, Ambient.hash(seed, 0, 62))
        val phase = strides - floor(strides)
        clipRect(x, y, x + w, y + h) {
            translate(x + rel + (live - travel) * dir, y + h - Gait.bob(strides, seed) * size) {
                scale(dir * size * frame, size * frame, Offset.Zero) {
                    smoothFrame(
                        look,
                        Activity.WALKING,
                        Gait.nearest(phase, spec.frames),
                        Gait.neighbour(phase, spec.frames),
                        abs(Gait.offset(phase, spec.frames)),
                        alpha * .92f,
                        Gait.lean(seed),
                    )
                }
            }
        }
    }

    /**
     * The window's curtains or blind ([WindowCast.curtain]): drapes to one side or both, a pair
     * half drawn, venetian blinds half or all the way down (their slats open), a roller blind half
     * down.
     */
    private fun DrawScope.curtains(c: WindowCast, w: Float, h: Float, alpha: Float) {
        val col = curtainColors[c.curtainTone]
        when (c.curtain) {
            3,
            7 -> {
                val down = if (c.curtain == 3) h * .38f else h
                drawRect(slat, Offset.Zero, Size(w, down), alpha = alpha * .5f)
                val n = (down / 2.2f).toInt()
                for (i in 1..n) drawRect(
                    Color.Black,
                    Offset(0f, down * i / (n + 1f)),
                    Size(w, .55f),
                    alpha = alpha * .22f,
                )
                drawRect(slat, Offset(0f, down - 1f), Size(w, 1f), alpha = alpha * .85f)
                drawRect(Color.Black, Offset(0f, down), Size(w, .7f), alpha = alpha * .25f)
                drawRect(slat, Offset(w * .78f, 0f), Size(.5f, down + 2f), alpha = alpha * .5f)
            }
            5 -> {
                drawRect(roller, Offset.Zero, Size(w, h * .42f), alpha = alpha * .92f)
                drawRect(Color.Black, Offset.Zero, Size(w, 1.2f), alpha = alpha * .2f)
                drawRect(Color.Black, Offset(0f, h * .42f - 1f), Size(w, 1f), alpha = alpha * .25f)
                drawRect(roller, Offset(w / 2f - .6f, h * .42f), Size(1.2f, 1.6f), alpha = alpha)
            }
            6 -> {
                drawDrape(col, 0f, w * .18f, h, alpha)
                drawDrape(col, w * .82f, w * .18f, h, alpha)
            }
            else -> {
                if (c.curtain == 1 || c.curtain == 4) drawDrape(col, 0f, w * .28f, h, alpha)
                if (c.curtain == 2 || c.curtain == 4) drawDrape(col, w * .72f, w * .28f, h, alpha)
            }
        }
    }

    /** One drape from [x], [dw] wide: its cloth with a fold's shadow and a lit edge. */
    private fun DrawScope.drawDrape(col: Color, x: Float, dw: Float, h: Float, alpha: Float) {
        drawRect(col, Offset(x, 0f), Size(dw, h), alpha = alpha * .72f)
        drawRect(Color.Black, Offset(x + dw * .3f, 0f), Size(dw * .22f, h), alpha = alpha * .2f)
        drawRect(Color.Black, Offset(x + dw * .7f, 0f), Size(dw * .14f, h), alpha = alpha * .14f)
        drawRect(Color.White, Offset(x + dw * .52f, 0f), Size(dw * .1f, h), alpha = alpha * .1f)
        drawRect(Color.Black, Offset(x, h - 1.2f), Size(dw, 1.2f), alpha = alpha * .2f)
    }

    /** A pot plant or a cat on the sill of an empty room. */
    private fun DrawScope.sillItem(c: WindowCast, w: Float, h: Float, alpha: Float) {
        val left = c.seed and 1 == 0
        val x = if (left) w * .14f else w * .6f
        if (c.sill == 1) {
            drawRect(soil, Offset(x, h - 4.2f), Size(3.4f, 3.4f), alpha = alpha * .9f)
            drawCircle(leaf, 1.5f, Offset(x + 1.7f, h - 5.2f), alpha * .9f)
            drawCircle(leaf, 1.3f, Offset(x + .2f, h - 6.2f), alpha * .9f)
            drawCircle(leaf, 1.3f, Offset(x + 3.2f, h - 6.4f), alpha * .9f)
        } else {
            val d = if (left) 1f else -1f
            val bx = if (left) x else x + 5f
            drawOval(furniture, Offset(bx - 2.6f, h - 5.2f), Size(5.2f, 3.6f), alpha = alpha * .92f)
            drawCircle(furniture, 1.6f, Offset(bx + d * 2.6f, h - 5.8f), alpha * .92f)
            drawLine(
                furniture,
                Offset(bx - d * 2.4f, h - 2.6f),
                Offset(bx - d * 4.4f, h - 5.4f),
                .9f,
                alpha = alpha * .9f,
            )
        }
    }

    /**
     * The ground floor's shopfront as a small scene: both windows warm and lit, in the left one a
     * reception desk with someone at it, a plant in each corner, and in the right one a bench and a
     * visitor who walks out from the door, stands looking at the street and goes back in. [x], [y],
     * [w], [h] are the left window; the right is its mirror.
     */
    private fun DrawScope.lobbyLife(
        art: BuildingArt,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        seconds: Float,
        sky: SkyColors,
        lod: PeopleLod,
    ) {
        if (lod == PeopleLod.NONE) return
        val seed = art.stack.id.hashCode()
        val kept = dayMix
        dayMix = (1f - sky.dark * 1.6f).coerceIn(0f, 1f)
        lobbyRooms(art, x, y, w, h, seconds, sky, lod, seed)
        dayMix = kept
    }

    private fun DrawScope.lobbyRooms(
        art: BuildingArt,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        seconds: Float,
        sky: SkyColors,
        lod: PeopleLod,
        seed: Int,
    ) {
        for (side in 0..1) {
            val ox = if (side == 0) x else 13f
            clipRect(ox, y, ox + w, y + h) {
                drawRect(
                    SceneInk.WindowLit,
                    Offset(ox, y),
                    Size(w, h),
                    alpha = .14f + .4f * sky.dark,
                )
                translate(ox + w / 2f, y) {
                    scale(w * .5f, h * 1.3f, Offset.Zero) {
                        drawCircle(
                            bloom,
                            1f,
                            Offset.Zero,
                            alpha = .35f + .35f * sky.dark,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
                if (lod != PeopleLod.FULL) return@clipRect
                val floor = y + h
                // a plant in the far corner
                val px = if (side == 0) ox + 4f else ox + w - 8f
                drawRect(soil, Offset(px, floor - 3.6f), Size(3.4f, 3.6f), alpha = .92f)
                for (i in 0 until 5) {
                    drawCircle(
                        leaf,
                        1.7f,
                        Offset(
                            px + 1.7f + (i - 2) * 1.5f,
                            floor - 5.4f - (if (i % 2 == 0) 1.5f else 0f),
                        ),
                        .92f,
                    )
                }
                val s = .52f
                if (side == 0) {
                    // the receptionist at the desk, about half the time
                    val ax = ox + 28f
                    val at = Silhouettes.receptionist(seed, seconds)
                    val shift = PeopleActivities.shift(RoomKind.OFFICE)
                    val spec = PeopleActivities.of(Activity.TYPING)
                    val look = (seed and 0x7fffffff) % Looks.COUNT
                    val size = s * PeopleActivities.fitted(Activity.TYPING)
                    if (at > 0f)
                        translate(ax + shift * s, floor) {
                            scale(size * frame, size * frame, Offset.Zero) {
                                drawPerson(
                                    SceneSprites.person(
                                        look,
                                        Activity.TYPING,
                                        Silhouettes.frame(spec, seconds, seed),
                                    ),
                                    at,
                                )
                            }
                        }
                    val dx0 = ax + (shift + 2.2f) * s
                    // the lit sign on the wall behind the desk
                    drawRect(signFace, Offset(dx0 - 1f, y + 3f), Size(11f * s + 8f, 4.6f))
                    drawRect(
                        SceneInk.WindowLit,
                        Offset(dx0 + .2f, y + 4.2f),
                        Size(11f * s + 5.6f, 2.2f),
                        alpha = .45f + .45f * sky.dark,
                    )
                    translate(dx0 + 5.5f * s + 3f, y + 5.3f) {
                        scale(14f, 5f, Offset.Zero) {
                            drawCircle(
                                bloom,
                                1f,
                                Offset.Zero,
                                alpha = .25f + .35f * sky.dark,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    drawRect(
                        furniture,
                        Offset(dx0, floor - 9.2f * s),
                        Size(11f * s + 6f, 9.2f * s),
                        alpha = .96f,
                    )
                    // the desk's top, lit, and the shade of its front panel
                    drawRect(
                        SceneInk.WindowLit,
                        Offset(dx0, floor - 9.2f * s),
                        Size(11f * s + 6f, .8f),
                        alpha = .55f,
                    )
                    drawRect(
                        Color.Black,
                        Offset(dx0 + .6f, floor - 7.4f * s),
                        Size(11f * s + 4.8f, 5.6f * s),
                        alpha = .22f,
                    )
                } else {
                    // a bench along the wall and the visitor
                    drawRect(
                        furniture,
                        Offset(ox + w - 30f, floor - 4.6f),
                        Size(18f, 1.2f),
                        alpha = .9f,
                    )
                    drawRect(
                        furniture,
                        Offset(ox + w - 29f, floor - 3.4f),
                        Size(1f, 3.4f),
                        alpha = .9f,
                    )
                    drawRect(
                        furniture,
                        Offset(ox + w - 14f, floor - 3.4f),
                        Size(1f, 3.4f),
                        alpha = .9f,
                    )
                    // the visitor: out of the door, a gentle stop, a turn to the street, a look, a
                    // turn back and in again; the feet follow the ground they cover
                    val look = ((seed and 0x7fffffff) + 3) % Looks.COUNT
                    val size = s * PeopleActivities.fitted(Activity.WALKING)
                    Visit.at(seconds, w - 14f, seed, Gait.pace(size, look), stroll)
                    if (stroll.here) {
                        val act =
                            if (stroll.mode == Stroll.STAND) Activity.LOOKING_OUT
                            else Activity.WALKING
                        translate(
                            ox + 4f + stroll.x + stroll.sway * size,
                            floor - stroll.lift * size,
                        ) {
                            scale(stroll.dir * size * frame, size * frame, Offset.Zero) {
                                if (stroll.mode == Stroll.STAND) {
                                    drawPerson(SceneSprites.person(look, act, stroll.frame), 1f)
                                    if (stroll.mix > .01f)
                                        drawPerson(
                                            SceneSprites.person(look, act, stroll.next),
                                            stroll.mix,
                                        )
                                } else
                                    smoothFrame(
                                        look,
                                        act,
                                        stroll.frame,
                                        stroll.next,
                                        stroll.mix,
                                        1f,
                                        stroll.lean,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    /** A storey's outline: its front, its roof face and whichever side face shows. */
    private fun silhouette(top: Float, bottom: Float) {
        val half = B.W / 2f
        val s = sideShift
        path.rewind()
        path.moveTo(-half, bottom)
        if (s >= 0f) {
            path.lineTo(-half, top)
            path.lineTo(-half + s, top - B.DY)
            path.lineTo(half + s, top - B.DY)
            path.lineTo(half + s, bottom - B.DY)
        } else {
            path.lineTo(-half + s, bottom - B.DY)
            path.lineTo(-half + s, top - B.DY)
            path.lineTo(half + s, top - B.DY)
            path.lineTo(half, top)
        }
        path.lineTo(half, bottom)
        path.close()
    }

    /** The selected floor: its outline in the accent. */
    private fun DrawScope.outline(top: Float, bottom: Float, color: Color) {
        silhouette(top, bottom)
        drawPath(path, color, style = selection)
    }

    /**
     * The next floor to finish: its outline lit in its material's colour, breathing with motion on.
     */
    private fun DrawScope.glow(top: Float, bottom: Float, tint: Color, seconds: Float) {
        val pulse = if (seconds > 0f) .6f + .4f * sin(seconds * 2.6f) else 1f
        silhouette(top, bottom)
        drawPath(path, tint, alpha = .2f * pulse, style = glowWide)
        drawPath(path, tint, alpha = .35f * pulse, style = glowMid)
        drawPath(path, tint, alpha = .5f + .45f * pulse, style = glowEdge)
    }

    /** A band of light across the front, [at] 0 as it enters at the left to 1 as it leaves. */
    private fun DrawScope.sheen(top: Float, bottom: Float, at: Float) {
        val half = B.W / 2f
        val band = 34f
        val x = -half - band + (B.W + band * 2f) * at
        clipRect(-half, top, half, bottom) {
            val lean = (bottom - top) * .5f
            path.rewind()
            path.moveTo(x, bottom)
            path.lineTo(x + lean, top)
            path.lineTo(x + lean + band, top)
            path.lineTo(x + band, bottom)
            path.close()
            drawPath(path, SceneInk.Sheen, alpha = .7f)
        }
    }

    /** Scaffolding dust thrown out along a floor's base as it drops into place. */
    private fun DrawScope.dust(bottom: Float, burst: Float) {
        val half = B.W / 2f
        val alpha = (1f - burst) * .7f
        for (i in 0 until 10) {
            val left = i < 5
            val u = (i % 5 + .5f) / 5f
            val x = (if (left) -1f else 1f) * (half - 18f * u + burst * (26f + 9f * (i % 3)))
            drawCircle(
                SceneInk.Dust,
                2.5f + i % 3 + burst * 5f,
                Offset(x, -bottom - 2f - burst * 9f * u),
                alpha,
            )
        }
    }

    /** The storey that has just been knocked off, tumbling away to the right, and its rubble. */
    private fun DrawScope.fallen(from: Float, key: String, at: Float) {
        val f = facade(HabitMaterial.FADE)
        val half = B.W / 2f
        val ease = at * at
        translate(70f * at, 210f * ease) {
            rotate(46f * at, Offset(0f, -from - B.RUIN / 2f)) {
                drawRect(
                    f.wallTop,
                    Offset(-half, -from - B.RUIN),
                    Size(B.W, B.RUIN),
                    alpha = 1f - ease,
                )
            }
        }
        val dice = Dice(key + "f")
        for (i in 0 until 7) {
            val dx = (dice.next() - .5f) * 150f
            val up = dice.next() * 60f
            drawRect(
                if (i % 2 == 0) f.roof else f.side,
                Offset(
                    (dice.next() * 2f - 1f) * half * .8f + dx * at,
                    -from - B.RUIN / 2f - up * sin(at * PI.toFloat()) + 120f * ease,
                ),
                Size(4f + i % 3 * 2f, 3f + i % 2 * 2f),
                alpha = 1f - ease,
            )
        }
    }

    companion object {
        const val DROP = 160f

        /** How far the pavement runs in front of the buildings before the kerb. */
        const val PAVEMENT = 16f

        /** The tile the asphalt's specks repeat in, in street units. */
        const val SPECK_TILE = 120f

        /** How far a lobby window's light reaches down the pavement at night. */
        const val SPILL = 26f

        /** How deep the sun's shadows are at full strength, and the moon's. */
        const val SUN_SHADE = .46f
        const val MOON_SHADE = .28f

        /**
         * How far (sprite pixels) a front gable's roof edge reaches down under its slope's straight front edge: past the
         * face's stepped rake, whose steps sit up to two and a half pixels below the line.
         */
        const val GABLE_LIP = 3f

        /** How opaque the snow lying on a villa's or a landmark's roof is (white, not the roof's colour through it). */
        const val ROOF_SNOW = .96f

        /** How far (sprite pixels) a gable slope's verge shows above its rake's straight line, seen edge on. */
        const val VERGE = 2f

        /** A hipped roof's tile along its courses (sprite pixels): its side faces' joints are this far apart along their depth. */
        const val HIP_TILE = 12f

        /** How many clocks' hands are kept painted at once (a tower shows its front and one side: one clock each). */
        const val CLOCK_SLOTS = 4

        /** A tree's shadow in three copies, trunk-sharp to crown-soft: each copy's share. */
        private val PENUMBRA = floatArrayOf(.55f, .4f, .35f)

        /** The bands a building's shade is drawn in, and its blur at the foot and per unit up. */
        const val SHADE_BANDS = 10
        const val PEN_BASE = 1.2f
        const val PEN_RISE = .03f

        /** How far a street lamp's light reaches over a wall at night, in street units. */
        const val LAMP_REACH = 64f

        /** How many buildings' pigeon scares are remembered (a power of two; seeds share slots). */
        const val PIGEON_SLOTS = 64

        /** The golden hour's light on a wall, the blue hour's cast, a television's light. */
        private val GoldLight = Color(0xFFFFB55C)
        private val BlueCast = Color(0xFFA8BCEE)
        private val TvLight = Color(0xFFB8D4FF)

        /**
         * The shade's colours: the sun's (the sky's blue), dusk's violet, the moon's, a window's.
         */
        private val SunShade = Color(0xFF1C2746)
        private val DuskShade = Color(0xFF2E1F42)
        private val MoonShade = Color(0xFF070B1E)
        private val SpillShade = Color(0xFF14100C)

        /** The shade under an overcast sky: a flat, cool grey (the light comes from everywhere). */
        private val FlatShade = Color(0xFF3A4254)

        /** How deep the wet street's reflections reach from the wall line, in street units. */
        const val REFLECT_DEPTH = 64f

        /** A reflection is this much as tall as what it mirrors (the ground is seen at a slant). */
        const val SQUASH = .55f

        /** The reflections are drawn twice this far apart: the wet's ripple. */
        const val RIPPLE = .6f

        /** The street's puddles repeat in a tile this long (three to a tile). */
        const val PUDDLE_TILE = 200f

        /** How long a splash lasts, in seconds. */
        const val SPLASH_SECONDS = .4f

        /** The wet slabs: what the dry colour is multiplied by (darker, cooler, more saturated). */
        private val WetTint = Color(0xFF6E7A9A)
        private val SignReflect = Color(0xFF20263A)
        private val TreeReflect = Color(0xFF0E1420)

        /** Lying snow: white by day, the moon's blue-grey at night. */
        private val SnowDay = Color(0xFFF6F8FF)
        private val SnowNight = Color(0xFF8FA4C8)

        /**
         * The furniture's iron and wood ([box]): the deep shade, the lit colour by day and under
         * the moon. A tree's back leaves are its own picture through [BackLeaves] (multiplied).
         */
        private val IronDeep = Color(0xFF11141B)
        private val IronLit = Color(0xFF9AA6BC)
        private val IronMoon = Color(0xFF46557A)
        private val WoodDeep = Color(0xFF2E1C10)
        private val WoodLit = Color(0xFFB88A5C)
        private val WoodMoon = Color(0xFF5E5A6E)
        private val BackLeaves = Color(0xFF7E8C80)

        /** Where a lamp pole's lit strips part, across it from its left edge (-1) to its right. */
        private val POLE_U = floatArrayOf(-1f, -.74f, -.44f, -.12f, .2f, .52f, .8f, 1f)

        /**
         * How far a tree's front leaves (with the trunk) and its back leaves slide about its middle
         * as the view turns, as shares of a building's side face at its place.
         */
        const val TREE_FRONT = .03f
        const val TREE_BACK = .05f

        /**
         * A lamp's pole stands under its head (the lens, where its light is worked out from: the
         * pools, the lit air, the light on walkers), and its arm reaches this far out over the
         * street, towards the view, so the head leans that much times the skew to the side.
         */
        const val POLE_X = LightSprites.LENS_X
        const val HEAD_REACH = 9f

        /**
         * The architecture's inks ([DetailInk]) and which of them are white or shade by the light
         * ([FacadeLook]).
         */
        private val INKS = DetailInk.entries.toTypedArray()
        private val DECAY_INKS = DecayInk.entries.toTypedArray()
        private val INK_COUNT = INKS.size
        private val INK_SIGNED = BooleanArray(INK_COUNT) { FacadeLook.signed(INKS[it]) }

        /**
         * The night's ambient light on a wall, and how far towards it a wall goes at full night.
         */
        private val NightAmbient = Color(0xFF55629A)
        const val NIGHT_DEPTH = .86f

        /**
         * Glass seen face on mirrors this much of what glass at a full slant does ([fresnel]), and
         * each pane's tilt moves its share by up to this much either way.
         */
        const val FRESNEL_FACE = .74f

        /** The planters by the shopfront stand this far in from the lobby's ends. */
        const val PLANTER_IN = 16f

        /** The side of a sign's raised letters: their face's cream in shade. */
        private val LetterSide = Color(0xFF8C7A5E)
        const val PANE_FRESNEL = .24f

        /** The sun's glint: how far a pane's tilt turns it and how narrow its reach across. */
        const val GLINT_TILT = .05f
        const val GLINT_SPREAD = .07f

        /** A wet wall: what it is multiplied by. */
        private val WetWall = Color(0xFF8590AA)

        /** How high up the walls the pavement's darkening reaches (building units). */
        const val GROUND_GRADE = 190f

        /** The haze over the highest floors, and the height it is full at (building units). */
        const val HAZE = .11f
        const val HAZE_HEIGHT = 560f

        /** A side face's wall as deep as this (its tile's scale across it), and its windows. */
        const val SIDE_DEPTH = 64f
        private val SIDE_SLITS = floatArrayOf(.2f, .6f)
        const val SIDE_SLIT = .16f

        /** The lobby's lamps either side of the door; the halo behind a backlit letter. */
        private val LAMP_SIDES = floatArrayOf(-1f, 1f)
        private val LETTER_HALO =
            arrayOf(Offset(.6f, 0f), Offset(-.6f, 0f), Offset(0f, .6f), Offset(0f, -.6f))

        /**
         * How much of the light the exposure took from a lit window it gives back, added (a little
         * over all of it: a lit room against a dark silhouette reads brighter).
         */
        const val RELIGHT = .85f
    }
}

// where our prop sprites stand, and how big they are drawn (street units per sprite pixel)
private val LAMP_K get() = SpriteArt.PIXEL
/** The side face's width in sprite pixels (tools/make_buildings.py D): the box's depth in the front's pixels. */
private const val SIDE_SRC_DEPTH = 120f
/** How strong the relief's light, shade and recesses are at full sun ([BuildingPainter.relief]). */
private const val LIT_K = .3f
private const val SHADE_K = .7f
private const val AO_K = .45f
/** A balcony's depth against the art's (the engine's lean is gentle; a balcony has to read as standing out). */
private const val BALCONY_GAIN = 1.6f
/** Its slab's thickness, its parapets' height and thickness, in sprite pixels. */
private const val SLAB = 6f
private const val PARAPET = 24f
private const val T = 7f
private const val LAMP_FOOT_X = 15f
private const val LAMP_FOOT_Y = 127f
// the lantern glass under the lamp's head (props/lamp_1.png is 64x128; the glass is the pale band at about 44-56, 46-50)
private const val LAMP_LENS_X = 50f
private const val LAMP_LENS_Y = 48f
/** Where the lamp's arm leaves its bracket (sprite pixels): the picture right of it is the arm and the head. */
private const val LAMP_ARM_X = 32f
private val TREE_K get() = SpriteArt.PIXEL   // the same grid as the buildings
private const val TREE_FOOT_X = 31f
private const val TREE_FOOT_Y = 95f
private val BENCH_K get() = SpriteArt.PIXEL
private const val BENCH_FOOT_X = 31f
private const val BENCH_FOOT_Y = 61f
