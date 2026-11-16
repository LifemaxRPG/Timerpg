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
            translate(standAt(f.x), 0f) {
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
                propPicture(f, seconds, nightFilter)
                if (f.kind == FurnitureKind.LAMP) {
                    val on = lampOn(f, dark)
                    if (on > 0f) {
                        val k = LAMP_K
                        translate((LAMP_LENS_X - LAMP_FOOT_X) * k, -(LAMP_FOOT_Y - LAMP_LENS_Y) * k) {
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
            if (up > h + 20f) continue
            val cx = street.centers[i]
            val lean = SceneLight.lean(light, (cx - lightX) / lightSpan)
            // the facade point at height `up` whose shadow falls at x: x = its x + lean * up
            val dx = abs(x - lean * up - cx)
            val pen = (PEN_BASE + PEN_RISE * up) * soft
            val across = Px.smooth(B.W / 2f + pen, B.W / 2f - pen, dx)
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
        reflectPaint.alpha = wet * (.5f + .3f * dark)
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
    }

    /** What stands on the street, upside down in the wet, [ox] to the side at [a]. */
    private fun DrawScope.reflections(
        sky: SkyColors,
        layouts: List<BuildingLayout>,
        arts: List<BuildingArt>,
        street: StreetLayout,
        live: Boolean,
        from: Float,
        to: Float,
        ox: Float,
        a: Float,
    ) {
        val dark = sky.dark
        val half = B.W / 2f
        val lit = lerp(SceneInk.WindowLitDay, SceneInk.WindowLit, dark)
        val shop = lerp(SceneInk.Shop, SceneInk.ShopLit, .15f + .8f * dark)
        val lobbyTop = -B.LOBBY_H
        for (i in layouts.indices) {
            val cx = street.centers[i]
            if (cx + half + B.DX + B.PARK_W < from || cx - half - B.DX > to) continue
            val art = arts[i]
            val s = shiftAt(cx)
            val l0 = Perspective.left(s)
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

    /** One of OUR props at the current origin (its foot): a lamp, a tree under the wind, or a bench. */
    private fun DrawScope.propPicture(f: Furniture, seconds: Float, filter: ColorFilter?) {
        when (f.kind) {
            FurnitureKind.LAMP -> SpriteArt.lamp?.let { picture2(it, -LAMP_FOOT_X * LAMP_K, -LAMP_FOOT_Y * LAMP_K, it.width * LAMP_K, it.height * LAMP_K, FilterQuality.None, filter = filter) }
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
    private fun DrawScope.sideStrips(img: ImageBitmap, ox: Float, oy: Float, ux: Float, uy: Float, h: Float, n: Int = 14) {
        val sw = (img.width / n).coerceAtLeast(1)
        for (i in 0 until n) {
            val x0 = ox + ux * i / n
            val y0 = oy + uy * i / n
            val dx = if (ux >= 0f) x0 else x0 + ux / n
            val dw = abs(ux) / n + .6f
            withTransform({
                translate(dx, y0)
                scale(dw / sw, h / img.height, Offset.Zero)
            }) {
                drawImage(img, IntOffset(i * sw, 0), IntSize(sw, img.height), IntOffset.Zero, IntSize(sw, img.height), 1f, Fill, null, BlendMode.SrcOver, FilterQuality.Low)
            }
        }
    }

    /**
     * A roof face: the picture (seen from above, its top the far edge) on the parallelogram whose front edge runs
     * from ([x], [y]) across [w] and whose back edge is [dy] higher and [shift] to the side. Drawn as [n] thin
     * horizontal strips, each a plain scaled and moved rectangle.
     */
    private fun DrawScope.roofStrips(img: ImageBitmap, x: Float, y: Float, w: Float, dy: Float, shift: Float, n: Int = 8) {
        val sh = (img.height / n).coerceAtLeast(1)
        for (j in 0 until n) {
            // j = 0 is the far (back) strip
            val t = (j + 1f) / n
            val yy = y - dy * (1f - (j + 1f) / n)
            val xx = x + shift * (1f - t)
            withTransform({
                translate(xx, yy - dy / n)
                scale(w / img.width, (dy / n + .6f) / sh, Offset.Zero)
            }) {
                drawImage(img, IntOffset(0, j * sh), IntSize(img.width, sh), IntOffset.Zero, IntSize(img.width, sh), 1f, Fill, null, BlendMode.SrcOver, FilterQuality.Low)
            }
        }
    }

    /**
     * One of OUR buildings ([SpriteArt]): its pixel-art front, a side face and a roof built from our sprites, put on
     * the engine's box (the side face and roof lean with where it stands in the view, [sideShift]) and lit by the
     * engine: the sun's wash and shade across the front and the side, the blue hour, the night, the contact shadow at
     * its foot, and its windows lit at dusk.
     */
    private fun DrawScope.spriteBuilding(art: BuildingArt, sb: SpriteBuilding, sky: SkyColors) {
        val half = B.W / 2f
        val k = B.W / SpriteArt.SRC_W
        val hf = sb.front.height * k
        val s = sideShift
        val dy = B.DY
        // the side face the camera sees: a parallelogram hung from the front's edge
        if (abs(s) > .02f) {
            val xe = if (s > 0f) half else -half
            sideStrips(sb.side, xe, -hf, s, -dy, hf)
            spritePath.rewind()
            spritePath.moveTo(xe, 0f); spritePath.lineTo(xe, -hf); spritePath.lineTo(xe + s, -hf - dy); spritePath.lineTo(xe + s, -dy); spritePath.close()
            drawPath(spritePath, Color(0xFF0A1024), alpha = .2f)
            sideLight(spritePath, 1f)
            if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
        }
        // the roof face, seen from a little above: our flat roof, leaning to the shown side
        val roof = sb.roof
        if (roof != null) {
            roofStrips(roof, -half, -hf, B.W, dy, s)
            spritePath.rewind()
            spritePath.moveTo(-half, -hf); spritePath.lineTo(-half + s, -hf - dy); spritePath.lineTo(half + s, -hf - dy); spritePath.lineTo(half, -hf); spritePath.close()
            drawPath(spritePath, Color(0xFFFFF2D0), alpha = .1f * direct)
            if (nightOn) drawPath(spritePath, nightInk, blendMode = BlendMode.Multiply)
        }
        // the rooftop sign (or billboard) stands on the roof, halfway back
        val sign = sb.sign ?: sb.prop
        if (sign != null) {
            val w = if (sb.sign != null) B.W else 160f * k
            val h = sign.height * k
            picture2(sign, -w / 2f + s * .5f, -hf - dy * .5f - h, w, h, FilterQuality.None)
        }
        // the front never moves
        picture2(sb.front, -half, -hf, B.W, hf, FilterQuality.None)
        facadeLight(-hf, 0f, 1f)
        blueHour(-half, -hf, B.W, hf, .35f)
        drawRect(aoWall, Offset(-half, -14f), Size(B.W, 14f))
        if (nightOn) drawRect(nightInk, Offset(-half, -hf), Size(B.W, hf), blendMode = BlendMode.Multiply)
        // lit at dusk: the glass glows
        val on = (sky.dark * Dusk.lightsOn(art.stack.id.hashCode(), hour, Dusk.SHOP)).coerceIn(0f, 1f)
        if (on > .01f) picture2(sb.emis, -half, -hf, B.W, hf, FilterQuality.None, alpha = on * .5f, blend = BlendMode.Plus)
        drawRect(SceneInk.Highlight, Offset(-half, -hf), Size(B.W, 1f))
    }

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
        val seconds = motion.seconds
        val half = B.W / 2f
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
        park(art, selected, accent, seconds, sky)
        // the building's shadow on the pavement, under its front and its shown side
        val l0 = Perspective.left(sideShift)
        drawRect(aoGround, Offset(l0 + 1f, 0f), Size(Perspective.right(sideShift) - l0 + 6f, 8f))
        SpriteArt.buildings[art.stack.id]?.let {
            // one of OUR buildings: its art on the engine's box, light and shade
            spriteBuilding(art, it, sky)
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
    private fun DrawScope.facadeLight(top: Float, bottom: Float, alpha: Float) {
        val s = sunSide
        if (abs(s) < .02f) return
        val half = B.W / 2f
        val h = bottom - top
        val a = abs(s) * alpha
        // from the lit edge inward, and from the far edge inward
        val lit = if (s < 0f) -half else half
        val sign = if (s < 0f) 1f else -1f
        withTransform({
            translate(lit, top)
            scale(sign * B.W * .7f, h, Offset.Zero)
        }) {
            drawRect(litWash, Offset.Zero, Size(1f, 1f), alpha = a * (1f - .4f * gold))
            if (gold > .01f) drawRect(goldWash, Offset.Zero, Size(1f, 1f), alpha = a * gold)
        }
        withTransform({
            translate(-lit, top)
            scale(-sign * B.W * .6f, h, Offset.Zero)
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
private const val LAMP_K = .8f
private const val LAMP_FOOT_X = 15f
private const val LAMP_FOOT_Y = 127f
private const val LAMP_LENS_X = 36f
private const val LAMP_LENS_Y = 16f
private const val TREE_K = 1.25f
private const val TREE_FOOT_X = 31f
private const val TREE_FOOT_Y = 95f
private const val BENCH_K = .8f
private const val BENCH_FOOT_X = 31f
private const val BENCH_FOOT_Y = 61f
