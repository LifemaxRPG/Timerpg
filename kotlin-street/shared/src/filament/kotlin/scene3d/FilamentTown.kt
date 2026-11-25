package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.presentation.scene.FrameWork
import com.lifemax.rpg.feature.habits.presentation.scene.SceneInk
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalkView
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A pinhole camera for the town: standing at ([ex], [ey], [ez]) looking along the unit ([fx], [fy], [fz]) (never straight
 * up or down), a lens of [fl] pixels with its centre at ([cx], [cy]) on a [w] x [h] screen. The world is the town's: x
 * east, y up, z south (right-handed), so the screen's right is the look crossed with up.
 */
class TownPose(
    val ex: Float, val ey: Float, val ez: Float,
    val fx: Float, val fy: Float, val fz: Float,
    val fl: Float, val cx: Float, val cy: Float, val w: Float, val h: Float,
    /** The nearest the shadows need to start (street units) and how far they reach. */
    val near: Float, val far: Float,
    /** Haze: where it starts (from the eye) and how thick. */
    val hazeFrom: Float, val haze: Float,
    /**
     * The street view's cut: a building whose middle is nearer the eye than this (along the look on the ground) stands
     * between the camera and the street in view (the row across the road) and fades out over [CUT_FADE]; 0: no cut.
     */
    val cut: Float = 0f,
    /**
     * How far from the eye anything is drawn: past it the haze has the town (buildings, yards, furniture and greenery fade
     * out over [TownFrame.RANGE_FADE] short of it, and are not drawn beyond).
     */
    val range: Float = Float.MAX_VALUE,
    /**
     * The open world's camera ([TownFrame.orbit]): the sun stands still in the world (its azimuth by the hour) instead of
     * turning with the view, and far things are drawn simpler ([lodFar]).
     */
    val open: Boolean = false,
    /** Past this distance from the eye the yards are drawn in their far version (simpler fences, no small things). */
    val lodFar: Float = Float.MAX_VALUE,
    /** How far from the eye the sun's shadows reach (their cascades spread over this); 0: as far as the view ([far]). */
    val shadowFar: Float = 0f,
) {
    /** Right (unit), up (unit). */
    val rx: Float; val ry: Float; val rz: Float
    val ux: Float; val uy: Float; val uz: Float

    init {
        // right = look x up(0,1,0) = (-fz, 0, fx), normalised
        val l = hypot(fx, fz).coerceAtLeast(1e-6f)
        rx = -fz / l; ry = 0f; rz = fx / l
        // up = right x look
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx
    }

    /** The pinhole's 3x4 screen matrix (world to (x w, y w, w), screen y down; row-major), as [LookMatrix.pinhole] takes it. */
    fun screen(out: FloatArray = FloatArray(12)): FloatArray {
        val r = floatArrayOf(rx, ry, rz); val u = floatArrayOf(ux, uy, uz); val f = floatArrayOf(fx, fy, fz); val e = floatArrayOf(ex, ey, ez)
        for (i in 0..2) { out[i] = fl * r[i] + cx * f[i]; out[4 + i] = -fl * u[i] + cy * f[i]; out[8 + i] = f[i] }
        out[3] = -(out[0] * e[0] + out[1] * e[1] + out[2] * e[2])
        out[7] = -(out[4] * e[0] + out[5] * e[1] + out[6] * e[2])
        out[11] = -(f[0] * e[0] + f[1] * e[1] + f[2] * e[2])
        return out
    }

    /**
     * True if a sphere at ([x], [y], [z]) of radius [r] may be on screen (the four side planes of the view; nothing in
     * front of the eye is cut, nothing far either: the haze takes the far).
     */
    fun sees(x: Float, y: Float, z: Float, r: Float): Boolean {
        val dx = x - ex; val dy = y - ey; val dz = z - ez
        val xc = rx * dx + ry * dy + rz * dz
        val yc = ux * dx + uy * dy + uz * dz
        val zc = fx * dx + fy * dy + fz * dz
        if (zc < -r) return false
        // screen x = cx + fl xc / zc >= 0, <= w; screen y = cy - fl yc / zc >= 0, <= h
        if ((fl * xc + cx * zc) / hypot(fl, cx) < -r) return false
        if (((w - cx) * zc - fl * xc) / hypot(fl, w - cx) < -r) return false
        if ((cy * zc - fl * yc) / hypot(fl, cy) < -r) return false
        if (((h - cy) * zc + fl * yc) / hypot(fl, h - cy) < -r) return false
        return true
    }

    /** How far ([x], [y], [z]) is from the eye. */
    fun distance(x: Float, y: Float, z: Float): Float {
        val dx = x - ex; val dy = y - ey; val dz = z - ez
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 1 well inside [range], fading to 0 at it, for something whose middle is at ([x], [y], [z]) of radius [r]. */
    fun rangeFade(x: Float, y: Float, z: Float, r: Float): Float {
        if (range == Float.MAX_VALUE) return 1f
        val t = ((range - (distance(x, y, z) - r * .5f)) / TownFrame.RANGE_FADE).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The look's direction on the ground (unit x, z). */
    val groundX: Float get() = fx / hypot(fx, fz).coerceAtLeast(1e-6f)
    val groundZ: Float get() = fz / hypot(fx, fz).coerceAtLeast(1e-6f)
}

/**
 * The town's cameras: the street view following the walk ([street], the true camera of [CameraRig]: level, at a
 * person's or a first floor's height, standing back across the road from the point the walk looks at), and a free
 * aerial camera for previews ([aerial]).
 */
object TownFrame {
    /** Street units shown per screen height at the building line (the crossroads' framing: 2.45 px a unit at 1080). */
    const val SPAN = 440f
    /** Where the building line's foot falls on the screen (a share of its height from the top). */
    const val GROUND = .82f

    /**
     * The street view for [walk] on a [w] x [h] screen: [CameraRig.eye] up, [CameraRig.back] behind the point the walk's
     * camera looks at (backing off to the stage camera's distance through a turn), level, the lens shifted so the building
     * line's foot sits at [GROUND] of the screen's height.
     */
    fun street(walk: TownWalkView, w: Float, h: Float, eye: Float = CameraRig.eye, back: Float = CameraRig.back): TownPose {
        val c = walk.camera
        val t = walk.transit
        val turning = if (t != null && t.kind == TransitionKind.TURN_IN) {
            val e = t.ease.coerceIn(0f, 1f)
            val u = (min(e, 1f - e) * 90f / CameraRig.EASE).coerceIn(0f, 1f)
            u * u * (3f - 2f * u)
        } else 0f
        val b = back + (max(back, com.lifemax.rpg.feature.habits.presentation.scene.TurnTuning.R) - back) * turning
        val unit = h / SPAN
        val fl = unit * b
        val groundY = h * GROUND
        val cy = groundY - eye * unit
        val l = hypot(c.fx, c.fz).coerceAtLeast(1e-6f)
        val fx = c.fx / l; val fz = c.fz / l
        val below = (h - cy).coerceAtLeast(1f)
        val near = (fl * eye / below * .7f).coerceAtLeast(CameraRig.NEAR)
        val hazeFrom = b + HAZE_FROM
        return TownPose(c.tx - fx * b, eye, c.tz - fz * b, fx, 0f, fz, fl, w / 2f, cy, w, h, near, near + 3200f, hazeFrom, HAZE, cut = b - CUT_BACK,
            range = hazeFrom + STREET_RANGE)
    }

    /**
     * An aerial camera over ([tx], [tz]): from [yaw] degrees (0: looking north, clockwise), [pitch] degrees down, [dist]
     * street units from that point, [fovY] degrees tall.
     */
    fun aerial(tx: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, fovY: Float, w: Float, h: Float): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val gx = sin(ya).toFloat(); val gz = -cos(ya).toFloat()
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = gx * cp; val fy = -sp; val fz = gz * cp
        val fl = (h / 2f) / tan(Math.toRadians(fovY / 2.0)).toFloat()
        val ex = tx - fx * dist; val ey = -fy * dist; val ez = tz - fz * dist
        // (a free camera sees the whole town: no range, its haze thin)
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w, h, max(20f, dist * .2f), dist * 2.2f, dist * 1.1f, .00025f * 2000f / max(dist, 2000f))
    }

    /**
     * The open world's camera (the player's [com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera]): looking at
     * ([tx], [ty], [tz]) (the player's chest) from [dist] street units away, turned [yaw] degrees (0 north, clockwise),
     * [pitch] degrees down, [fovY] degrees tall; a true perspective with a centred lens. It draws out to [OPEN_REACH]
     * past the player whatever the zoom (things fading out over [RANGE_FADE] short of it, the haze thickening into it from
     * [OPEN_HAZE] past the player), the shadows reach [OPEN_SHADOWS] past the player, and the yards are in their far
     * version from [OPEN_YARDS_FAR] past the player.
     */
    fun orbit(tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, w: Float, h: Float, fovY: Float = 50f, lod: Boolean = true): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fovY / 2.0)).toFloat()
        val ex = tx - fx * dist; val ey = (ty - fy * dist).coerceAtLeast(6f); val ez = tz - fz * dist
        // (the near plane close in: a fence or a hedge between the camera and the player is still drawn whole)
        val near = (dist * .04f).coerceIn(4f, 16f)
        val hazeFrom = dist + OPEN_HAZE
        val range = dist + OPEN_REACH
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w, h, near, range + 20f, hazeFrom, OPEN_HAZE_DENSITY,
            range = range, open = true, lodFar = if (lod) dist + OPEN_YARDS_FAR else Float.MAX_VALUE, shadowFar = dist + OPEN_SHADOWS)
    }

    /**
     * How far past the player the open world draws (street units), the same at every zoom: the camera's far plane is just
     * past it, and things fade out short of it, so nothing is cut off hard and a wide view costs no more depth than a close one.
     */
    const val OPEN_REACH = 2600f
    /**
     * Where the open world's haze starts past the player (1200 until 0.1.385, whose towers and trees 1500-2500 off were
     * washed pale: "ghosts").
     */
    const val OPEN_HAZE = 1600f
    /**
     * The open world's haze's thickness on the ground (per street unit, as seen along the ground from the camera's height):
     * about 50% at the view's range (58% before), about 90% where the trees' cards end ([FilamentTown.GREEN_PAST_RANGE] past it),
     * so what is left out past the range fades in thick haze and the woods dissolve into the sky's horizon colour. It
     * thins with height ([OPEN_HAZE_FALLOFF]): tall things (towers, the far boxes' tops, crowns) stand clearer of it.
     */
    const val OPEN_HAZE_DENSITY = .00074f
    /**
     * How fast the open world's haze thins with height (per street unit): a ground haze, a third as thick 150 up, so a
     * tower's upper floors and the far skyline keep their colours while the far ground and the edge's woods still melt.
     */
    const val OPEN_HAZE_FALLOFF = 1f / 140f
    /** The yards are drawn in their far version from this far past the player (street units; where the haze began before). */
    const val OPEN_YARDS_FAR = 1800f
    /** How far past the player the open world's shadows reach (street units): the cascades over this depth, so the one at the player stays fine. */
    const val OPEN_SHADOWS = 1400f
    /** The yards are drawn in their far version from this far past where the haze starts (street units), so the switch is in the haze. */
    const val LOD_YARDS = 600f
    /** The switch to and from a yard's far version is this far either side of [TownPose.lodFar] (no flicker at the line). */
    const val LOD_SLACK = 80f

    /**
     * The haze's thickness at street level (per street unit) and where it starts past the building line looked at: thin
     * enough that the next blocks read solid, thick (80%) by [STREET_RANGE] further, where the street view stops drawing.
     */
    const val HAZE = .00045f
    const val HAZE_FROM = 1400f
    const val STREET_RANGE = 3600f
    /** Over how many street units things fade out short of the view's range. */
    const val RANGE_FADE = 500f
    /** Closer than this (street units from the eye to a chunk's edge) the greenery is in full 3D, further as cards. */
    const val LOD_NEAR = 1100f
    /** Closer than this (street units from the eye to a tree cell's edge) the trees and bushes are in full detail ([TreeCells]). */
    const val LOD_DETAIL = 800f
    /** The street view cuts away what stands nearer than this short of the building line it looks at (past the road's middle). */
    const val CUT_BACK = 110f
    /** Over how many street units a cut building fades. */
    const val CUT_FADE = 70f
}

/**
 * A step of the town's build that failed: [what] it was doing, why (its cause), and the Java heap at that moment (used of
 * the limit, MB), so the report and the screen say what and why without a debugger.
 */
class BuildFailure(what: String, cause: Throwable) : RuntimeException(
    "$what failed: ${cause.javaClass.simpleName}${cause.message?.let { ": $it" } ?: ""} (Java heap ${TownMemory.mb(TownMemory.used())} of ${TownMemory.mb(TownMemory.limit)} MB used, the build's peak ${TownMemory.mb(TownMemory.peakUsed)} MB)",
    cause,
)

/** What the town drew last frame: of [buildings] built, how many in view; renderables and their draws (primitives), shadow casters. */
data class TownStats(
    val buildings: Int, val built: Int, val visible: Int,
    val renderables: Int, val draws: Int, val triangles: Int, val casters: Int,
    /** Chunks of furniture, props and greenery in view, of them in full 3D; street lamps lit. */
    val chunks: Int = 0, val chunksNear: Int = 0, val lamps: Int = 0,
)

/**
 * The whole town drawn by Filament (TOWN.md T8): every building of every row built once in its own frame
 * ([BuildingMeshes]) and placed by one transform ([TownPlace]: the same rigid box on its front's middle that the walk and
 * the yards use), the ground, river and bridges, greenery and furniture ([TownGround]), the street props by
 * [com.lifemax.rpg.feature.habits.domain.PropRules] along every row ([PropLayer]) and the yards ([yardBuilder]).
 *
 * Built in two steps so a phone never stalls: [buildGround] (the ground and everything static but the buildings, at once),
 * then [buildNearest] each frame with a time budget, nearest the player first. Each frame [frame] aims the true camera
 * ([TownPose]: [TownFrame.street] from the walk, or an aerial one), lights the hour, culls the buildings to the view (a
 * sphere against its four sides) and brings the materials to it. [stats] says what was drawn.
 *
 * Only `com.google.android.filament` is used, so the same code runs on the phone and the desktop.
 */
class FilamentTown(
    val engine: Engine,
    materialBytes: (String) -> ByteArray,
    val town: Town,
    val yards: TownYards,
    private val art: TownArt,
    /** The building drawn as a sprite (bN), or null if its art is missing (it is then left out). */
    private val building: (String) -> SpriteBuilding?,
    /** The sun's shadow map: each cascade's size (texels square) and how many cascades ([TownQuality]). */
    val shadowMap: Int = TownQuality.shadowMap,
    val shadowCascades: Int = TownQuality.cascades,
    /** The town's movie theater (street.json's graph.cinema), drawn by [CinemaKit] in its building's place; null: none. */
    cinema: CinemaSite? = null,
    /** The town's store sites (street.json's graph.storeSites / stores: [StoreSite.parse]), each drawn by [StoreKit] in its slot's place. */
    stores: List<StoreSite> = emptyList(),
    /** The year the stores are built for (each site as it stands then: [StoreSite.at]); 0: the export's own. */
    storeYear: Int = 0,
    /** The open world's collision: the stores' solid shapes and doors go into it once built (null: none). */
    private val collision: com.lifemax.rpg.feature.habits.domain.TownCollision? = null,
    /** The town's railroad (rail.json: docs/RAIL.md), drawn for [railYear] (0: the town's year) by [TownRailDraw]; null: none. */
    rail: com.lifemax.rpg.feature.habits.domain.RailNet? = null,
    railYear: Int = 0,
    /** The hand-made landmark models (docs/LANDMARKS_GLB.md): the catalogue, its files and what the town places; null: none. */
    landmarks: LandmarkAssets? = null,
) {
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val lit: Material = load("street_lit", materialBytes)
    private val proxyMat: Material = load("shadow_proxy", materialBytes)
    // the opaque twins (docs/NOVEL_OPTIMIZATIONS.md idea 1, [Cutouts]): what has no cut-out texels draws with these; an app
    // or preview without them (an older asset set) draws everything masked as before
    private val litOpaque: Material? = runCatching { load("street_lit_opaque", materialBytes) }.getOrNull()?.also { Cutouts.register(lit, it) }
    private val proxyOpaque: Material? = runCatching { load("shadow_proxy_opaque", materialBytes) }.getOrNull()?.also { Cutouts.register(proxyMat, it) }
    private val textures = StreetTextures(engine, srgb = true)
    private val meshes = BuildingMeshes(engine, lit, proxyMat, textures)
    private val sun: Int = EntityManager.get().create()
    /** The sun's shadow options (kept: the open world sets how far its shadows reach, [TownPose.shadowFar], as the zoom changes). */
    private val shadowOptions = LightManager.ShadowOptions()
    private var shadowFarSet = 0f
    private var cascadesSet = -1
    /** The multisampling applied to [view] ([TownQuality.msaa]; -1: not yet). */
    private var msaaSet = -1
    private var ibl: IndirectLight? = null
    private val iblColor = FloatArray(3) { -1f }
    private val iblRot = FloatArray(9)
    private val sh = FloatArray(12)
    private val fog = View.FogOptions()
    private val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)

    // ------------------------------------------------------------------ the sky (Jeff's G016: sky.mat, SkyState, SkySurface)
    /**
     * The sky dome drawn by the town itself, behind everything (one far-plane triangle), on an opaque view: the stage's 2D
     * Backdrop no longer shows under it (docs/CLEANUP_PLAN.md step 2). Null: no sky material (an old asset set) or
     * [sky3d] off; then the view stays see-through over the stage's sky, as before.
     */
    private val skyMat: Material? = if (!sky3d) null else runCatching { load("sky", materialBytes) }.getOrNull()
    private val skySurface: SkySurface? = skyMat?.let { SkySurface(engine, scene, it) }
    /** The sky's state this frame: its colours feed the haze too, so the far ground meets the horizon with no line. */
    val skyState = SkyState()
    /** The town draws its own sky. */
    val drawsSky: Boolean get() = skySurface != null
    /** The weather, season and date (yyyy-MM-dd, for the moon's phase; empty: a full moon) the sky shows, set each frame by the game. */
    var weather: com.lifemax.rpg.feature.habits.domain.WeatherState = com.lifemax.rpg.feature.habits.domain.WeatherState.CLEAR
    var season: com.lifemax.rpg.feature.habits.domain.SeasonState = com.lifemax.rpg.feature.habits.domain.Season.SUMMER
    var day: String = ""

    /** Where the yards come from: the yard kit ([YardKitBuilder]), or [PlaceholderYards] (flat colours) to check the layout. */
    var yardBuilder: YardBuilder = YardKitBuilder
    private val ticks = ArrayList<(Double) -> Unit>()

    private val ground = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true).also { it.seeThrough = false }
    private val yardLayer = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
    /** The yards a lot or a lane at a time ([YardPatch]): culled with the view, cut with their house in the street view. */
    private inner class Patch(val p: YardPatch) {
        val layer = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
        val slot: Slot? = p.sprite?.let { sp -> slots.firstOrNull { it.b.sprite == sp } }
        /** Its far version's key (a near patch's; made once, not a frame). */
        val farKey: String = p.id + YardPatch.FAR
        /** How far its tall things are kept while cut away for the open world's camera (1: whole). */
        var occFade = 1f
        /** The lot's near version's key (its own for a near patch; a far one's twin's). */
        val nearKey: String = p.nearId
    }
    private val patches = LinkedHashMap<String, Patch>()
    /** The lots drawn in their far version now (by their near patch's key). */
    private val farLots = HashSet<String>()

    /**
     * The furniture, street props and greenery a piece of the town at a time: a row side's in stretches of [CHUNK] along
     * it, the edge's greenery in squares of [CELL]. Each is culled by its sphere and the view's range, and in the street
     * view cut with its row (a row across the road fades as its buildings do, judged at its building line); its trees are
     * cards ([cards]) far off and the yard kit's 3D trees ([near], made the first time it comes close) near.
     */
    private inner class Chunk(val refX: Float, val refZ: Float, val byRow: Boolean) {
        val base = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
        val cards = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
        var near: PropLayer? = null
        /**
         * Its 3D trees waiting to come near: their mesh (one per tree look, shared by the whole town) and places, not yet
         * packed. They were once packed with the ground and held until the chunk came near (or, after, for good): the
         * whole town's 3D trees, some 230 MB of direct buffers, which on the phone are its Java heap. Now a worker packs a
         * chunk's as it comes within [TownFrame.LOD_NEAR] + 600 ([startNear]) and the engine's thread uploads them
         * ([buildNear]) and lets the bytes go, so only the few chunks about to come near hold any.
         */
        private val nearSrc = ArrayList<Triple<PropMesh, List<FloatArray>, Boolean>>()
        private var packing: java.util.concurrent.Future<List<Pair<PropLayer.Packed, Boolean>>>? = null
        private var packedCount = 0
        /** It has 3D trees (made or waiting). */
        var hasNear = false
        /** Its 3D greenery is wanted (with [TownFrame.LOD_SLACK] of hysteresis), and how far the crossover from its cards has come (0 cards, 1 3D). */
        var is3d = false
        var t3 = 0f
        /** Its 3D trees are packed (by [startNear]) and wait for [buildNear]. */
        val nearReady: Boolean get() = packing?.isDone == true

        /** Takes in 3D trees ([mesh] at [at]) for near by: kept to pack later, or (the chunk near already) put in now. */
        fun addNear(mesh: PropMesh, at: List<FloatArray>, casts: Boolean) {
            hasNear = true
            val n = near
            // (a piece arriving after the chunk's 3D greenery was made goes straight in, or it would be lost)
            if (n != null) PropLayer.Packing.pack(mesh, at)?.let { n.upload(it, casts) }
            // (kept even once made: streaming lets the 3D greenery go when the chunk is far, and makes it again from these;
            // the meshes are the town's shared ones, the places a few floats each)
            if (n == null || stream) nearSrc += Triple(mesh, at, casts)
        }

        /** Its trees and bushes in 3D, in cells at two levels of detail ([TreeCells]; null: none). */
        var trees: TreeCells? = null
        /** The same trees far off as impostors ([TreeImpostors]): their looks and places, the card batches they stand in for. */
        val treeList = ArrayList<Pair<TreeKit.Spec, FloatArray>>()
        val treeCards = ArrayList<Int>()
        var imp: PropLayer? = null
        var impJob: java.util.concurrent.Future<PropLayer.Packed?>? = null
        var impEx = Float.NaN; var impEz = Float.NaN
        var cardsHidden = false
        fun addTrees(list: List<Pair<TreeKit.Spec, FloatArray>>) {
            hasNear = true
            treeList.addAll(list)
            val t = trees ?: TreeCells(engine, scene, lit, proxyMat, textures, pool).also { trees = it }
            for ((s, a) in list) t.add(s, a)
        }

        /** Starts packing its 3D trees on a worker (nothing if made, under way, or none). */
        fun startNear() {
            if (near != null || packing != null || (nearSrc.isEmpty() && trees == null)) return
            val src = nearSrc.toList()
            packedCount = src.size
            val t = trees
            packing = pool.submit(java.util.concurrent.Callable { t?.pack(); src.mapNotNull { (m, at, casts) -> PropLayer.Packing.pack(m, at)?.let { it to casts } } })
        }
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var pad = 0f; var top = 0f
        var cx = 0f; var cz = 0f; var r = 0f
        /** Takes in an [extent] (x0, z0, x1, z1, reach, height) and works out its sphere again. */
        fun take(e: FloatArray) {
            x0 = min(x0, e[0]); z0 = min(z0, e[1]); x1 = max(x1, e[2]); z1 = max(z1, e[3])
            pad = max(pad, e[4]); top = max(top, e[5])
            cx = (x0 + x1) / 2f; cz = (z0 + z1) / 2f
            r = hypot(x1 - x0, z1 - z0) / 2f + pad + top / 2f
        }
        /** Makes its 3D trees: what [startNear] packed, and (unbudgeted: a preview's, or none started) the rest packed here. */
        fun buildNear() = GpuAccount.scoped("trees") { buildNearNow() }
        private fun buildNearNow() {
            if (near != null) return
            val got = packing?.let { f ->
                try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("packing a chunk's 3D greenery on a worker", e.cause ?: e) }
            } ?: emptyList()
            val from = if (packing != null) packedCount else 0
            val l = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
            fun put() {
                for ((p, casts) in got) l.upload(p, casts)
                for (i in from until nearSrc.size) { val (m, at, casts) = nearSrc[i]; PropLayer.Packing.pack(m, at)?.let { l.upload(it, casts) } }
                trees?.build()
            }
            if (stream) BufferArena.streamed { put() } else put()
            if (!stream) { nearSrc.clear(); nearSrc.trimToSize() }
            packing = null
            near = l
        }
        /** Lets go of its 3D greenery (streaming: far again, its cards stand in); made again as it comes near. */
        fun dropNear() {
            near?.destroy(later = true); near = null
            trees?.drop()
            packing?.cancel(false); packing = null
            is3d = false; t3 = 0f
        }
        fun destroy() { base.destroy(); cards.destroy(); near?.destroy(); trees?.destroy(); imp?.destroy(); impJob?.cancel(true) }
    }
    private val chunks = LinkedHashMap<String, Chunk>()

    /** The rows' lanterns (x, y, z) and the point lights the nearest of them get at night. */
    private var lanterns: FloatArray = FloatArray(0)
    private val lampLights = IntArray(LAMP_LIGHTS) { EntityManager.get().create() }
    private var groundBuilt = false

    /** The people (TOWN.md T10): cards of our character sheets, stood by [people] after each [frame]. */
    private val crowd = TownPeople(engine, scene, lit, proxyMat, textures)
    /** The pose and look of the last [frame] (the people and the screen projection follow it). */
    private var lastPose: TownPose? = null
    /** The clock at the last [frame] (what is cut away for the camera eases by it). */
    private var lastFrameSeconds = Double.NaN
    /** The hour of the last [frame] (the stores' cars are parked for it). */
    private var lastHour = 13f
    private val sunDir = FloatArray(3)

    /** One building of the town: where it stands and, once built, its renderables. */
    inner class Slot(val row: Row, val b: Placement, val place: TownPlace) {
        internal var built: BuiltBuilding? = null
        val isBuilt: Boolean get() = built != null
        /** A sphere round it in the world (its footprint's middle, a guess at its height until built). */
        var cx = 0f; var cy = 40f; var cz = 0f; var r = 0f
        // what its instances were last given ([paramGen], its fade, which build, masked or not): unchanged, nothing is sent
        var sentGen = -1; var sentFade = Float.NaN; internal var sentTo: BuiltBuilding? = null; var sentMasked = false
        var missing = false
        var shown = false
        /** Between the open world's camera and the player ([occlude]), and how far faded for it now (eased a frame at a time). */
        var occluded = false
        var occFade = 1f
        /** Where the open world camera's line enters it (a share of the way from the player; MAX_VALUE: not in the way). */
        var occT = Float.MAX_VALUE
        /** The far cell its cheap box goes in ([TownFar]); null: none (drawn by code, or no art). */
        internal var farCell: TownFar.Cell? = null
        /**
         * Streaming ([TownStream], docs/STREAMING.md): its bounds in its own frame once made ready the first time (kept
         * while it is let go: the camera's cut and [near] still see it), whether its far box is made ([known]), whether it
         * should be resident in full now ([want]), and the textures its full version took ([held], let go with it).
         */
        var bounds: FloatArray? = null
        var known = false
        var want = true
        internal val held = ArrayList<com.google.android.filament.Texture>()
        /** Times it was streamed in and out (reports). */
        var streamedIn = 0; var streamedOut = 0

        init {
            val c = place.world(0f, b.setback + b.depth / 2f)
            cx = c.x; cz = c.z
            r = hypot(b.width / 2f, b.depth / 2f + b.setback / 2f) + 40f
        }
    }

    val slots: List<Slot> = town.rows.flatMap { row -> row.buildings.map { Slot(row, it, TownPlace.of(row, it)) } }

    /** What the town holds on the GPU (MB by kind) for a [w] x [h] view: [TownQuality.estimate]. */
    fun gpuEstimate(w: Int, h: Int): GpuEstimate = TownQuality.estimate(textures.gpu, shadowMap, shadowCascades, w, h)

    /** The cinema, built by code in its slot's place (the slot itself has no art: it is left to it). */
    private val cinemaDraw: TownCinema? = cinema?.let { c -> slots.firstOrNull { it.b.sprite == c.sprite }?.let { s ->
        s.missing = true; TownCinema(engine, scene, lit, proxyMat, textures, c, s.place, s.b.setback, town.seed.hashCode()) } }
    private val cinemaOut = IntArray(3)

    /** The railroad: track, crossings, depot and trains (docs/RAIL.md). */
    private val railNet = rail
    private var railDraw: TownRailDraw? = rail?.let { r ->
        // (the forest and the woods' edge keep off the track's bed: set before the ground is made)
        if (town.keepClear == null) town.keepClear = { x, z -> com.lifemax.rpg.feature.habits.domain.RailScene(r, town.year).onBed(x, z) }
        TownRailDraw(engine, scene, lit, proxyMat, textures, town, r, if (railYear > 0) railYear else town.year, collision,
            modelDepot = r.rail.depot?.premier != null && landmarks?.catalogue?.any { it.kind == DEPOT_KIND } == true)
    }

    // ------------------------------------------------------------------ riding the train (docs/RAIL.md "Riding")

    private var rideLayer: RideLayer? = null
    private var rideClock = Double.NaN
    /** Seconds into the ride under way (its length: [rideSeconds]). */
    var rideT = 0f; private set
    val riding: Boolean get() = rideLayer != null
    val rideSeconds: Float get() = rideLayer?.seconds ?: 0f
    /** The ride is at its end (the train stands at the last station). */
    val rideDone: Boolean get() = rideLayer?.let { rideT >= it.seconds } ?: false
    private val rideFocus = FloatArray(4)
    private var rideS = 0f

    /** Starts [ride]: its world (fields, track, wilderness, the towns on the way) and its train added to the scene. */
    fun startRide(ride: com.lifemax.rpg.feature.habits.domain.RailRide) {
        rideLayer?.destroy()
        val consist = com.lifemax.rpg.feature.habits.domain.RailScene(ride.net, ride.year).consist(passenger = true, main = true, seed = 0)
        rideLayer = RideLayer(engine, scene, lit, proxyMat, textures, ride, consist)
        rideT = 0f; rideClock = Double.NaN
    }

    /** Ends the ride and takes its world out of the scene. */
    fun stopRide() { rideLayer?.destroy(); rideLayer = null }

    /**
     * The ride's camera at the clock's [seconds] (null when not riding): an orbit round the train's middle from beside the
     * track, a little ahead, [yaw] degrees off its way; the ride's time runs on by the clock (or set it with [at]).
     */
    fun ridePose(seconds: Float, w: Float, h: Float, yaw: Float = 70f, pitch: Float = 14f, dist: Float = 620f, at: Float? = null): TownPose? {
        val r = rideLayer ?: return null
        if (at != null) rideT = at else {
            val dt = if (rideClock.isNaN()) 0.0 else (seconds - rideClock).coerceIn(0.0, .25)
            rideClock = seconds.toDouble()
            rideT = (rideT + dt.toFloat()).coerceAtMost(r.seconds)
        }
        rideS = r.ride.at(rideT, r.seconds)
        r.focus(rideS, rideFocus)
        val head = Math.toDegrees(kotlin.math.atan2(rideFocus[2].toDouble(), -rideFocus[3].toDouble())).toFloat()
        return TownFrame.orbit(rideFocus[0], 40f, rideFocus[1], head + yaw, pitch, dist, w, h)
    }

    /** The railroad's gates and lamps stand as they should at the next frame (a preview's jump in time). */
    fun snapRail() { railDraw?.snap() }

    /** Draws the railroad as it stands in [year] instead (its tracks, crossings' gear, depot and trains of that year). */
    fun setRailYear(year: Int) {
        val r = railNet ?: return
        if (railDraw?.year == year) return
        railDraw?.destroy()
        rideLayer?.destroy()
        railDraw = TownRailDraw(engine, scene, lit, proxyMat, textures, town, r, year, collision, modelDepot = depotSpec != null)
        depotDraw?.destroy(); depotDraw = makeDepot()
    }
    /** The railroad as drawn (its year's scene, the trains of the last frame), for previews and the ticket window. */
    val railScene: com.lifemax.rpg.feature.habits.domain.RailScene? get() = railDraw?.rail
    val trainsNow: List<com.lifemax.rpg.feature.habits.domain.TrainNow> get() = railDraw?.trains ?: emptyList()

    /** The stores, built by code in their slots' places (the slots have no art), cars by the hour of [weekday]. */
    private val storeSites: List<StoreSite> = stores
    private var storeDraws: List<TownStore> = makeStores(storeYear)

    private fun makeStores(year: Int): List<TownStore> = storeSites.mapNotNull { st -> slots.firstOrNull { it.b.sprite == st.sprite }?.let { s ->
        s.missing = true
        TownStore(engine, scene, lit, proxyMat, textures, st, s.place, s.b.setback, s.row.id, if (year > 0) year else st.year.takeIf { it > 0 } ?: town.year, collision)
    } }

    // ------------------------------------------------------------------ the hand-made landmark models (TownLandmark)
    private val landmarkAssets = landmarks
    /** The models standing in the rows' slots (built by code: the slots have no art). */
    /** The game's time of day (hours, 0-24) a landmark's working clock (the courthouse's) is set to as it is built; null: as modelled. */
    var clockHours: Float? = null
    private val landmarkDraws: List<TownLandmark> = landmarks?.let { a -> a.placed.mapNotNull { p ->
        val spec = a.catalogue.firstOrNull { it.kind == p.kind } ?: return@mapNotNull null
        val s = slots.firstOrNull { it.b.sprite == p.sprite } ?: return@mapNotNull null
        s.missing = true
        val c = s.place.world(0f, s.b.setback + s.b.depth / 2f)
        TownLandmark(engine, scene, lit, proxyMat, textures, spec, { a.file(spec.fileOf(p.variant)) ?: ByteArray(0) }, { spec.info?.let { a.file(it)?.decodeToString() } },
            p.sprite, s.row.id, TownLandmark.rowFrame(s.place, s.b.setback), floatArrayOf(c.x, c.z, hypot(s.b.width, s.b.depth) / 2f + 60f),
            town.year, town.seed.hashCode(), collision, closed = p.state == "closed", hide = p.hide,
            farBytes = spec.far?.let { f -> { a.file(f) } }, clockHours = { clockHours })
    } } ?: emptyList()
    /** The railroad's hand-made depot, in a town that has it ([RailDepot.premier]), as the railroad stands in its year. */
    private val depotSpec: LandmarkSpec? get() = landmarkAssets?.catalogue?.firstOrNull { it.kind == DEPOT_KIND }?.takeIf { railNet?.rail?.depot?.premier != null }
    private var depotDraw: TownLandmark? = null
    private var depotTried = false
    private fun makeDepot(): TownLandmark? {
        val spec = depotSpec ?: return null
        val a = landmarkAssets ?: return null
        val d = railNet?.rail?.depot ?: return null
        val rd = railDraw ?: return null
        val state = rd.rail.depotState
        if (state == "none") return null
        // its front away from the track, the platform's edge (16.14 m behind its origin) 1.75 m from the track's centre
        val l = hypot(d.dx, d.dz).coerceAtLeast(1e-6f)
        val fx = d.dz / l * d.side; val fz = -d.dx / l * d.side
        val off = (DEPOT_PLATFORM_M + DEPOT_EDGE_M) * LandmarkKit.UNITS_PER_METRE
        val ox = d.x + fx * off; val oz = d.z + fz * off
        return TownLandmark(engine, scene, lit, proxyMat, textures, spec, { a.file(spec.file) ?: ByteArray(0) }, { spec.info?.let { a.file(it)?.decodeToString() } },
            "rail-depot", "", TownLandmark.facing(ox, oz, fx, fz), floatArrayOf(ox, oz, spec.width / 2f + 60f),
            rd.year, town.seed.hashCode(), collision, closed = state == "closed")
    }
    private val landmarkOut = IntArray(3)
    /** The landmark models as built (for previews and tests): sprite, kind, triangles, solid shapes, doors, whether drawn. */
    val landmarkReport: List<String> get() = (landmarkDraws + listOfNotNull(depotDraw)).map { "${it.sprite} ${it.spec.kind}: ${if (it.done) "${it.triangles} triangles, ${it.solidCount} solid shapes, ${it.doorCount} doors, ${if (it.levelsPending) "heights to make" else "heights %.1f MB".format(it.levelBytes / 1048576.0)}" else "building"}${if (it.closed) ", closed" else ""}${if (it.shown) ", shown" else ""}" }
    /** The frame (origin x, z; x axis; z axis) of the landmark model in slot [sprite] ("rail-depot": the depot's), once built. */
    fun landmarkFrame(sprite: String): FloatArray? = (landmarkDraws + listOfNotNull(depotDraw)).firstOrNull { it.sprite == sprite && it.done }?.frame
    /** The lot of the landmark model in slot [sprite] in its frame (x0, z0, x1, z1, height), once built. */
    fun landmarkLot(sprite: String): FloatArray? = (landmarkDraws + listOfNotNull(depotDraw)).firstOrNull { it.sprite == sprite && it.done }?.lot

    /** The year the stores stand as (0: the export's own); set it to another and they are built again for that year. */
    var storeYear: Int = storeYear
        set(v) {
            if (v == field) return
            field = v
            for (st in storeDraws) st.destroy()
            storeDraws = makeStores(v)
        }
    private val storeOut = IntArray(3)
    /** The main block of the store at [sprite] in the world (x0, z0, x1, z1, height), once built; null otherwise. */
    fun storeBlock(sprite: String): FloatArray? = storeDraws.firstOrNull { it.site.sprite == sprite }?.block

    /** Parks the stores' cars for [hour] (a preview's, waiting on the workers: call until true); true once every store's are up. */
    fun parkStores(hour: Float): Boolean { for (st in storeDraws) st.stepCars(pool, hour, weekday); return storeDraws.all { it.carsFor(hour, weekday) } }
    /** The day of the week the stores' car parks are filled for (0 Sunday .. 6 Saturday). */
    var weekday = 3
    /** The stores as built (for previews and tests): sprite, triangles, solid shapes, cars standing, whether drawn. */
    val storeReport: List<String> get() = storeDraws.map { "${it.site.sprite} ${it.site.landmark}: ${it.triangles} triangles, ${it.solidCount} solid shapes, ${it.carCount} cars${if (it.shown) ", shown" else ""}" }

    /** The town's pictures: textures made, their texels, and how many asks were answered by one already made. */
    val textureStats: Triple<Int, Long, Int> get() = Triple(textures.madeCount, textures.texels, textures.shared)
    /** The texture sizes holding the most texels (for the breadcrumbs: what GPU memory goes to). */
    fun biggestTextures(n: Int = 8): String = textures.biggest(n)

    /** How long the ground took (ms), the buildings so far (ms), and how many are left to build. */
    var groundMs = 0f; private set
    var buildingsMs = 0f; private set
    val pending: Int get() = slots.count { !it.missing && (!it.known || (it.want && it.built == null)) } + (if (cinemaDraw?.done == false) 1 else 0) + storeDraws.count { !it.done } + landmarkDraws.count { !it.done } + (if (depotDraw?.done == false) 1 else 0) +
        (if (farCounted && !far.allReady) 1 else 0) + extras.count { it.pending }
    /** The far cells have been counted (each building's cell, at the first [buildNearest]). */
    private var farCounted = false
    /** The far boxes: cells, cells up, their triangles (for reports). */
    val farReport: String get() = "far: ${far.readyCount} of ${far.cellCount} cells up, ${far.triangles} triangles; coarser level: ${far.districtReport}; lights far off: $farLightCount"

    // ------------------------------------------------------------------ more the town draws (the farms: TownFarms)
    private val extras = ArrayList<TownExtra>()
    /** Adds something more the town draws ([TownExtra]: the farms), made with the town's engine, scene, materials and workers. */
    internal fun <T : TownExtra> addExtra(make: (TownExtra.Kit) -> T): T =
        make(TownExtra.Kit(engine, scene, lit, proxyMat, textures, later, ::extraMaterial, art)).also { extras += it }
    private val extraMaterials = HashMap<String, Material>()
    private val materialBytesOf = materialBytes
    private fun extraMaterial(name: String): Material = extraMaterials.getOrPut(name) { load(name, materialBytesOf) }
    /** The extras' report lines (for previews). */
    val extraReport: List<String> get() = extras.map { it.report }
    var stats = TownStats(slots.size, 0, 0, 0, 0, 0, 0); private set
    /** Each batch [buildNearest] made: how many it built and how long it took (ms). */
    val batches = ArrayList<Pair<Int, Float>>()

    private val xf = FloatArray(16)
    private val look = FloatArray(16)
    private val m12 = FloatArray(12)
    private val tmp = FloatArray(16)

    init {
        // (Filament 1.81 checks every renderable's every primitive at each MaterialInstance destroyed, a debug assertion on
        // by default: with the town's thousands of renderables each destroy cost ~0.1-1 ms, and streaming destroys tens of
        // thousands on a drive (the route's 40-250 ms frames: docs/HEAT.md). Our destroys always follow their renderable's.)
        if (System.getProperty("miCheck") != "1") runCatching { engine.setFeatureFlag("engine.debug.assert_material_instance_in_use", false) }
        view.scene = scene
        view.camera = camera
        view.dithering = View.Dithering.NONE
        view.antiAliasing = View.AntiAliasing.NONE
        view.sampleCount = 1
        TownQuality.applyMsaa(view); msaaSet = TownQuality.msaa
        // (opaque over its own sky; see-through over the stage's when it has none)
        view.blendMode = if (skySurface != null) View.BlendMode.OPAQUE else View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true
        view.shadowType = View.ShadowType.PCF
        camera.setExposure(1f)
        val shadow = shadowOptions.apply {
            mapSize = shadowMap
            shadowCascades = this@FilamentTown.shadowCascades
            cascadeSplitPositions = TownQuality.splits(this@FilamentTown.shadowCascades)
            stable = true
            lispsm = false
            constantBias = .05f
            normalBias = .4f
            polygonOffsetConstant = .5f
            polygonOffsetSlope = .2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f).castShadows(true).shadowOptions(shadow)
            .build(engine, sun)
        scene.addEntity(sun)
    }

    private fun load(name: String, bytes: (String) -> ByteArray): Material {
        val b = bytes(name)
        val buf = TownMemory.direct(b.size).put(b).flip()
        return Material.Builder().payload(buf, b.size).build(engine)
    }

    // ------------------------------------------------------------------ building

    // ------------------------------------------------------------------ the ground, off the engine's thread

    /** Where a piece of chunked furniture or greenery goes: the chunk's key, its cut's reference point, by row or by square. */
    private class ChunkSpec(val key: String, val refX: Float, val refZ: Float, val byRow: Boolean)

    /** One batch made ready on a worker, to upload on the engine's thread ([uploadGround]). */
    private class Job(
        val packed: PropLayer.Packed, val casts: Boolean, val tiled: Boolean,
        /** null: the town's ground (or, with [yard], the yards' always-drawn layer). */
        val chunk: ChunkSpec? = null, val part: Int = 0,
        val patch: YardPatch? = null, val yard: Boolean = false,
        /** Water to shimmer: its picture's width and height (the shimmer steps a texel at a time; not the mesh, which would be kept for good). */
        val water: IntArray? = null,
        /** The extent of what it places (x0, z0, x1, z1), its mesh's reach and height, for a chunk's sphere. */
        val extent: FloatArray? = null,
        /** With a chunk's cards: the same trees in 3D for near by, at [nearAt] (packed later: [Chunk.startNear]). */
        val near: PropMesh? = null, val nearAt: List<FloatArray>? = null,
        /** With a chunk's cards: the trees and bushes they stand for near by ([TreeCells]), each with its place. */
        val trees: List<Pair<TreeKit.Spec, FloatArray>>? = null,
        /** Street things in it the cars can take away one at a time ([hideProp]): what it was packed from. */
        val hide: HideSrc? = null,
    )

    // ------------------------------------------------------------------ what the cars broke (docs/VEHICLES.md, "Destruction")

    /** A batch's street things that can be taken away one at a time: [tag] (lamp, streetBench, plazaBench, prop), the mesh and its places, or the props. */
    private class HideSrc(val tag: String, val mesh: PropMesh, val at: List<FloatArray>, val props: List<WorldProp>?)
    private class Hideable(val layer: PropLayer, var batch: Int, val casts: Boolean, var src: HideSrc)
    private val hideables = ArrayList<Hideable>()

    /** A street thing as it was drawn: its mesh and place (x, z, along x, z, out x, z, turn). */
    internal class Drawn(val mesh: PropMesh, val at: FloatArray)

    /**
     * Takes the street thing [detail] ("lamp", "streetBench", "plazaBench", or a [com.lifemax.rpg.feature.habits.domain.PropKind]'s
     * name) nearest ([x], [z]) out of the drawing (its batch packed again without it; a lamp's lantern goes dark too), on
     * the engine's thread. Returns how it was drawn (for its pieces), or null if none stands there.
     */
    internal fun hideProp(x: Float, z: Float, detail: String): Drawn? {
        val tag = when (detail) { "lamp", "streetBench", "plazaBench" -> detail; else -> "prop" }
        var best: Hideable? = null; var bi = -1; var bd = HIDE_REACH
        for (h in hideables) {
            if (h.src.tag != tag) continue
            for ((i, a) in h.src.at.withIndex()) {
                if (tag == "prop" && h.src.props!![i].spot.kind.name != detail) continue
                val d = hypot(a[0] - x, a[1] - z)
                if (d < bd) { bd = d; best = h; bi = i }
            }
        }
        val h = best ?: return null
        val src = h.src
        val at = src.at[bi]
        // (the old batch freed, not just hidden: its buffers were kept on the GPU until the town went)
        h.layer.release(h.batch)
        val rest = src.at.filterIndexed { i, _ -> i != bi }
        h.src = HideSrc(src.tag, src.mesh, rest, src.props?.filterIndexed { i, _ -> i != bi })
        if (rest.isNotEmpty()) PropLayer.Packing.pack(src.mesh, rest)?.let { h.layer.upload(it, h.casts); h.batch = h.layer.batchCount - 1 }
        if (tag == "lamp") {
            // (its lantern's light goes out: the nearest lantern to the post)
            var li = -1; var ld = 60f
            for (i in 0 until lanterns.size / 3) { val d = hypot(lanterns[i * 3] - at[0], lanterns[i * 3 + 2] - at[1]); if (d < ld) { ld = d; li = i } }
            if (li >= 0) { lanterns[li * 3 + 1] = -1e5f; farLightsDirty = true }
        }
        return Drawn(src.mesh, at)
    }

    private class YardSwap(val f: java.util.concurrent.Future<Pair<String?, List<Job>>>)
    private val yardSwaps = ArrayList<YardSwap>()
    /** Yards being baked again ([replaceYard]) not swapped in yet. */
    internal val yardsPending: Boolean get() = yardSwaps.isNotEmpty()
    /** The water shimmers of each yard patch (a patch swapped out takes its own with it). */
    private val patchTicks = HashMap<String, MutableList<(Double) -> Unit>>()

    /**
     * Bakes the yard of [owner] (a lot's building, or a lane) again with [items] on the workers (what the cars left of
     * it); [pollYards] swaps its patches in once ready. Only the yard kit's yards ([YardKitBuilder]) can be.
     */
    internal fun replaceYard(owner: String, items: List<com.lifemax.rpg.feature.habits.domain.TownYardItem>) {
        val kit = yardBuilder as? YardKitBuilder ?: return
        yardNow[owner] = items
        yardSwaps += YardSwap(pool.submit(java.util.concurrent.Callable {
            val out = ArrayList<Job>()
            val sink = object : YardSink {
                override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?, water: Boolean): MaterialInstance? {
                    PropLayer.Packing.pack(mesh, at, YardKit.sharedAtlas?.shareFor(mesh))?.let { out += Job(it, casts, tiled, patch = patch, yard = true, water = if (water) intArrayOf(mesh.texW, mesh.texH) else null) }
                    return null
                }
                override fun props(list: List<WorldProp>) {}
            }
            kit.rebuild(owner, yards, items, sink) to out
        }))
    }

    /** Swaps in the yards [replaceYard] has baked (the engine's thread): the lot's old patches (near, far, its tall things') go. Returns how many. */
    internal fun pollYards(): Int {
        var n = 0
        val it = yardSwaps.iterator()
        while (it.hasNext()) {
            val sw = it.next()
            if (!sw.f.isDone) continue
            it.remove()
            val (id, jobs) = runCatching { sw.f.get() }.getOrNull() ?: continue
            if (id == null) continue
            yardOut.remove(id); lotBytes[id] = 0L
            val gone = patches.keys.filter { k -> k == id || k.startsWith("$id#") }
            for (k in gone) {
                patches.remove(k)?.layer?.destroy()
                patchTicks.remove(k)?.let { t -> synchronized(ticks) { ticks.removeAll(t.toSet()) } }
            }
            for (j in jobs) upload(j)
            n++
        }
        if (n > 0) engine.flush()
        return n
    }

    private val jobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
    /**
     * The bytes made ready and waiting in [jobs], and the most there have been. The workers outrun the engine's thread
     * (it uploads a few milliseconds a frame), and once every lot's yard, every chunk's furniture and greenery waited
     * here at once, hundreds of MB of them on the phone's Java heap (a direct buffer is a Java array on Android). Now a
     * worker with more to add waits while [QUEUE_BYTES] are queued ([offer]): the queue stays a frame's work deep, the
     * workers bake no further ahead than the uploads go, and the ground comes in as fast as before (the uploads, not the
     * baking, were already what it waited for once the workers had a head start).
     */
    private val queued = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var queuedPeak = 0L; private set
    val queuedBytes: Long get() = queued.get()
    private val queueLock = Object()
    @Volatile private var closed = false

    /** Adds [j] to [jobs] (a worker), first waiting while the queue holds [QUEUE_BYTES] (unless it is empty). */
    private fun offer(j: Job) {
        val b = j.packed.heldBytes
        val w0 = System.nanoTime()
        synchronized(queueLock) {
            while (!closed && queued.get() > 0 && queued.get() + b > QUEUE_BYTES) queueLock.wait(50)
            offerWaitNs.addAndGet(System.nanoTime() - w0)
            val now = queued.addAndGet(b)
            if (now > queuedPeak) queuedPeak = now
        }
        jobs += j
    }
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    /** How long the ground's workers waited, all told, for the engine's thread to take what they had made (ns). */
    val offerWaitNs = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var failure: Throwable? = null
    @Volatile private var lampSpots: List<FloatArray>? = null
    private var groundStarted = 0L
    /**
     * The workers: every core but one (the engine's thread keeps its own); daemon threads, so a quit is never held. Their
     * queue goes by priority ([TownWorkers]): the ground and the buildings nearest the player first, the landmarks, stores,
     * cinema, farms and the world round the town after the first view's buildings, the landmarks' height fields last.
     */
    private val pool = TownWorkers(max(2, Runtime.getRuntime().availableProcessors() - 1))
    /** The workers for what the first view does not wait for (the stores' cars, the far boxes, the extras). */
    private val later by lazy { pool.lane(TownWorkers.LATER, "later") }
    /** How long the workers took to make the ground ready (ms), and the engine's thread to upload it (ms, all batches). */
    var groundPrepareMs = 0f; private set
    var groundUploadMs = 0f; private set
    /** The longest single upload step [uploadGround] took (ms). */
    var groundUploadWorst = 0f; private set
    /** The buildings far off: a cheap box each, merged by cells ([TownFar]), out to the far end of the world. */
    private val far by lazy { TownFar(engine, scene, lit, textures, pool.lane(TownWorkers.LATER, "far")) }
    /** Each building's far pictures, made on the worker that made it ready. */
    private val farPics = java.util.concurrent.ConcurrentHashMap<Slot, TownFar.Pics>()
    private val farOut = IntArray(2)

    // ------------------------------------------------------------------ streaming (docs/STREAMING.md)

    private val streamer = TownStream()
    /** The streaming's counts in one line (the preview's report, the play log). */
    val streamReport: String get() = streamer.report() + "; resident: %d of %d buildings (%.1f MB est.), %d of %d lots near (%.1f MB est.), margin x%.2f / x%.2f".format(
        java.util.Locale.US, slots.count { it.built != null }, slots.count { !it.missing }, buildingBytes / 1048576.0, lotInfo.size - yardOut.size, lotInfo.size, lotBytesNow() / 1048576.0, bScale, yScale)
    /** Where the player was told to be ([streamFocus], [buildNearest]); NaN before. */
    private var focusX = Float.NaN; private var focusZ = Float.NaN
    /** Each lot's (and lane's) near patch, kept while its near version is let go: what streaming walks. */
    private val lotInfo = LinkedHashMap<String, YardPatch>()
    /** The lots whose near version is let go now (only their far version is resident), and those being baked to come back. */
    private val yardOut = HashSet<String>()
    private val yardIn = HashMap<String, java.util.concurrent.Future<Pair<String?, List<Job>>>>()
    /** Lots baked and coming in, their batches still to upload (a few a frame), and those begun (their old version let go). */
    private val yardUp = java.util.ArrayDeque<Pair<String, java.util.ArrayDeque<Job>>>()
    private val yardStarted = HashSet<String>()
    private fun yardComing(id: String): Boolean = id in yardIn || yardUp.any { it.first == id }
    /** Each lot's yard items as last baked (what the cars left of it: [replaceYard]), by the owner the kit bakes it as. */
    private val yardNow = HashMap<String, List<com.lifemax.rpg.feature.habits.domain.TownYardItem>>()
    /** Each near lot's buffer bytes (its near patch and its tall things'), for the yards' pool. */
    private val lotBytes = HashMap<String, Long>()
    private fun lotBytesNow(): Long { var b = 0L; for ((k, v) in lotBytes) if (k !in yardOut) b += v; return b }
    /** The resident buildings' bytes (textures they took, counted whole, and their buffers): the buildings' pool. */
    private var buildingBytes = 0L
    private val slotBytes = HashMap<Slot, Long>()
    /** Each tier's margin scale: below 1 while its pool is over its budget ([TownBudget.streamBuildingsMb], [TownBudget.streamYardsMb]). */
    private var bScale = 1f; private var yScale = 1f

    /** Lots wanted near but not resident yet (as last counted). */
    private var yardsBehind = 0
    /** [s]'s far cell's crossing (0 its building, 1 its far box) and how many of its buildings it waits for (a preview's debug). */
    fun cellT(s: Slot): String = s.farCell?.let { "%.2f far %s waiting %d".format(it.t, it.far, it.waiting) } ?: "-"
    /** The sun's light as Filament has it (a preview's debug). */
    fun sunInfo(): String { val lm = engine.lightManager; val li = lm.getInstance(sun); return if (li == 0) "sun: no light (entity $sun)" else "sun: entity $sun intensity %.2f shadows %s".format(lm.getIntensity(li), lm.isShadowCaster(li)) }
    /** What streaming still waits for (a preview's report when a jump does not settle). */
    val streamWhy: String get() = "pending %d (slots unknown %d, wanted not in %d, far ready %s, extras %s), yards baking %d, behind %d".format(pending,
        slots.count { !it.missing && !it.known }, slots.count { !it.missing && it.want && it.built == null }, far.allReady,
        extras.filter { it.pending }.joinToString { it.javaClass.simpleName }, yardIn.size, yardsBehind)
    /** Everything streaming wants resident is (a preview's jump waits for it, as a loading screen would). */
    val streamSettled: Boolean get() = pending == 0 && (!stream || (yardIn.isEmpty() && yardUp.isEmpty() && yardsBehind == 0 &&
        chunks.values.none { it.hasNear && it.near == null && !focusX.isNaN() && hypot(it.cx - focusX, it.cz - focusZ) - it.r < TownFrame.LOD_NEAR + 600f })) &&
        (!TreeImpostors.on || (impAtlas != null || chunks.values.none { it.treeList.isNotEmpty() }) && chunks.values.none { it.impJob != null })

    /** Where the player is, before the first [buildNearest] (the yards coming in with the ground are kept only near it). */
    fun streamFocus(x: Float, z: Float) { focusX = x; focusZ = z }

    /** The owner the yard kit bakes lot [p] as (its building's sprite, else the lane's id). */
    private fun ownerOf(p: YardPatch): String = p.sprite?.takeIf { it.isNotEmpty() && yards.of(it) != null } ?: p.id
    private fun itemsOf(owner: String): List<com.lifemax.rpg.feature.habits.domain.TownYardItem>? =
        yardNow[owner] ?: yards.of(owner)?.items ?: yards.lanes.firstOrNull { it.id == owner }?.items

    /** How far past the eye the buildings are needed in full now: the far boxes' line and its slack. */
    private fun buildingNeed(pose: TownPose?): Float = when {
        pose == null -> TownRun.READY_RADIUS + 600f
        pose.range == Float.MAX_VALUE -> Float.MAX_VALUE
        else -> pose.hazeFrom + FAR_PAST_HAZE + TownFar.SLACK
    }
    /** How far the yards' near versions are needed now: their switch to the far version and its slack. */
    private fun yardNeed(pose: TownPose?): Float = when {
        pose == null -> TownFrame.OPEN_YARDS_FAR + 600f
        pose.lodFar < Float.MAX_VALUE -> pose.lodFar + TownFrame.LOD_SLACK
        pose.range < Float.MAX_VALUE -> pose.range
        else -> Float.MAX_VALUE
    }

    /** Lets go of [s]'s full version (its far box stays): its entities, material instances, buffers and the textures only it held. */
    private fun unbuild(s: Slot) {
        val b = s.built ?: return
        val em = EntityManager.get()
        for (e in listOf(b.entity, b.proxy)) if (e != 0) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
        // (its material instances, then the textures only it held, over the next frames: [Graveyard])
        for (mi in b.mis) Graveyard.bury(engine, mi)
        b.twins?.idle?.forEach { Graveyard.bury(engine, it) }
        for (mi in b.proxyMis) Graveyard.bury(engine, mi)
        b.geo.free(engine)
        val held = ArrayList(s.held)
        Graveyard.later { textures.release(held) }
        s.held.clear()
        s.built = null; s.shown = false; s.streamedOut++
        buildingBytes -= slotBytes.remove(s) ?: 0L
        streamer.buildingsOut++
    }

    /** Lets go of lot [lot]'s near version and its tall things' (its far version stays). */
    private fun unbuildLot(lot: String) {
        val gone = patches.keys.filter { k -> k == lot || k.startsWith("$lot#t") }
        for (k in gone) {
            patches.remove(k)?.layer?.destroy(later = true)
            patchTicks.remove(k)?.let { t -> synchronized(ticks) { ticks.removeAll(t.toSet()) } }
        }
        yardOut += lot
        streamer.yardsOut++
    }

    /**
     * One frame's streaming (in [buildNearest], before the buildings are built): which buildings and lots should be
     * resident, from the player at ([x], [z]), the last frame's eye and both moved ahead by the player's speed
     * ([TownStream]); what is no longer wanted let go, a few milliseconds of it a frame; the lots wanted back baked again
     * on the workers and swapped in within [TownStream.YARD_UPLOAD_MS]; each cell's buildings not yet in counted, so the
     * cell keeps its far boxes until they are ([TownFar.Cell.waiting]).
     */
    private fun streamStep(x: Float, z: Float, @Suppress("UNUSED_PARAMETER") budgetMs: Float) {
        focusX = x; focusZ = z
        if (!stream) return
        val t0 = System.nanoTime()
        val pose = lastPose
        streamer.focus(x, z, if (lastFrameSeconds.isNaN()) 0.0 else lastFrameSeconds, pose)
        val need = buildingNeed(pose)
        val m = streamer.margin
        // the pools: past its budget a tier's margin shrinks (what is only ahead or beside goes first), back once under
        bScale = if (buildingBytes > TownBudget.streamBuildingsMb * 1048576.0) max(0f, bScale - .05f) else min(1f, bScale + .01f)
        val bNeed = if (need == Float.MAX_VALUE) need else need - m * (1f - bScale)
        if (farLod) for (c in far.all) {
            // (the cell's square, and its far boxes' own rectangle once they are in: a big building reaches past its square,
            // and the cell switches by that rectangle, so its buildings must be wanted by it too)
            val h = TownFar.CELL / 2f + 150f
            c.streamD = streamer.rect(min(c.cx - h, c.x0), min(c.cz - h, c.z0), max(c.cx + h, c.x1), max(c.cz + h, c.z1))
            c.waiting = 0
        }
        for (s in slots) {
            if (s.missing) continue
            val c = s.farCell
            val d = if (farLod && c != null) c.streamD else streamer.circle(s.cx, s.cz, s.r)
            s.want = streamer.wants(d, bNeed, s.want)
            if (s.want && s.built == null && s.known && c != null) c.waiting++
            // (something wanted again: the build loop looks once more)
            if (s.want && s.built == null) todo = Int.MAX_VALUE
        }
        var sec = streamer.section(0, t0)
        var did = false
        for (s in slots) {
            if (s.built == null || s.want) continue
            if (did && (System.nanoTime() - t0) / 1e6f > TownStream.EVICT_MS) break
            // (not while its cell is still crossing over to its boxes and it shows: the crossing ends first)
            val c = s.farCell
            if (c != null && c.layer != null && c.t < 1f && s.shown) continue
            if ('b' in TownStream.OFF) continue
            val u0 = System.nanoTime()
            unbuild(s); did = true
            streamer.worstUnbuildMs = max(streamer.worstUnbuildMs, (System.nanoTime() - u0) / 1e6f)
        }
        sec = streamer.section(1, sec)
        // the yards' near versions
        if (groundBuilt && yardBuilder is YardKitBuilder) {
            if (lotInfo.isEmpty()) for (pt in patches.values) if (!pt.p.far && pt.p.follows == null) lotInfo[pt.p.id] = pt.p
            val yn = yardNeed(pose)
            yScale = if (lotBytesNow() > TownBudget.streamYardsMb * 1048576.0) max(0f, yScale - .05f) else min(1f, yScale + .01f)
            val yNeed = if (yn == Float.MAX_VALUE) yn else yn - m * (1f - yScale)
            val wantBack = ArrayList<Pair<String, Float>>()
            for ((id, p) in lotInfo) {
                val d = streamer.circle(p.x, p.z, 0f)
                val out = id in yardOut
                val want = streamer.wants(d, yNeed, !out)
                if (!want && !out && !yardComing(id) && 'y' !in TownStream.OFF) {
                    if (!did || (System.nanoTime() - t0) / 1e6f < TownStream.EVICT_MS) {
                        val u0 = System.nanoTime()
                        unbuildLot(id); did = true
                        streamer.worstLotOutMs = max(streamer.worstLotOutMs, (System.nanoTime() - u0) / 1e6f)
                    }
                } else if (want && out && !yardComing(id)) wantBack += id to d
            }
            yardsBehind = wantBack.size + yardIn.size + yardUp.size
            // (nearest first, a few at a time on the workers)
            if (wantBack.isNotEmpty() && yardIn.size + yardUp.size < TownStream.YARDS_IN_FLIGHT) {
                wantBack.sortBy { it.second }
                val kit = yardBuilder as YardKitBuilder
                for ((id, d) in wantBack) {
                    if (yardIn.size + yardUp.size >= TownStream.YARDS_IN_FLIGHT) break
                    val p = lotInfo[id] ?: continue
                    val owner = ownerOf(p)
                    val items = itemsOf(owner) ?: continue
                    yardIn[id] = pool.submit(d, "yard-in", id, java.util.concurrent.Callable {
                        val out = ArrayList<Job>()
                        val sink = object : YardSink {
                            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?, water: Boolean): MaterialInstance? {
                                PropLayer.Packing.pack(mesh, at, YardKit.sharedAtlas?.shareFor(mesh))?.let { out += Job(it, casts, tiled, patch = patch, yard = true, water = if (water) intArrayOf(mesh.texW, mesh.texH) else null) }
                                return null
                            }
                            override fun props(list: List<WorldProp>) {}
                        }
                        kit.rebuild(owner, yards, items, sink, nearOnly = true) to out
                    })
                }
            }
            sec = streamer.section(2, sec)
            // those baked: their batches uploaded within the frame's share, a lot over as many frames as that takes (a
            // lot's whole yard at once was up to 9-22 ms on the route), its old near version let go as its first goes up
            if (yardIn.isNotEmpty() || yardUp.isNotEmpty()) {
                val u0 = System.nanoTime()
                val it = yardIn.entries.iterator()
                while (it.hasNext()) {
                    val (id, f) = it.next()
                    if (!f.isDone) continue
                    it.remove()
                    val jobs = runCatching { f.get() }.getOrNull()?.second ?: continue
                    // (a lot the cars rebaked whole meanwhile is back already)
                    if (id !in yardOut) continue
                    yardOut.remove(id)
                    yardUp.addLast(id to java.util.ArrayDeque(jobs))
                }
                var n = 0
                while (yardUp.isNotEmpty()) {
                    // (within the yards' share, and the stream step's whole budget: [TownStream.STREAM_MS] a frame)
                    if (n > 0 && ((System.nanoTime() - u0) / 1e6f > TownStream.YARD_UPLOAD_MS || (System.nanoTime() - t0) / 1e6f > TownStream.STREAM_MS)) break
                    val (id, q) = yardUp.first()
                    if (!yardStarted.contains(id)) {
                        yardStarted += id
                        for (k in patches.keys.filter { k -> k == id || k.startsWith("$id#t") }) patches.remove(k)?.layer?.destroy(later = true)
                        lotBytes[id] = 0L
                    }
                    val l0 = System.nanoTime()
                    q.pollFirst()?.let { upload(it) }
                    streamer.worstLotInMs = max(streamer.worstLotInMs, (System.nanoTime() - l0) / 1e6f)
                    n++
                    if (q.isEmpty()) { yardUp.removeFirst(); yardStarted.remove(id); streamer.yardsIn++ }
                }
                if (n > 0) { engine.flush(); did = true; streamer.worstYardUpMs = max(streamer.worstYardUpMs, (System.nanoTime() - u0) / 1e6f) }
            }
        }
        streamer.section(3, sec)
        val ms = (System.nanoTime() - t0) / 1e6f
        if (did) { streamer.busyFrames++; streamer.worstMs = max(streamer.worstMs, ms) }
    }

    /**
     * Starts making the ground, river, bridges, greenery, furniture, street props and yards on worker threads (meshes
     * built, vertices packed, pictures packed and keyed: no engine call). Returns at once; then call [uploadGround] each
     * frame until it returns true.
     */
    fun startGround() {
        if (groundStarted != 0L || groundBuilt) return
        groundStarted = System.nanoTime()
        running.set(2)
        pool.execute(TownWorkers.NOW, "ground", "ground") { work("the ground, greenery, furniture and props") { prepareGround() } }
        pool.execute(TownWorkers.NOW, "ground", "yards") { work("the yards") { prepareYards() } }
    }

    private fun work(what: String, f: () -> Unit) {
        try { f() } catch (t: Throwable) { failure = BuildFailure("making $what ready on a worker", t) } finally {
            if (running.decrementAndGet() == 0) groundPrepareMs = (System.nanoTime() - groundStarted) / 1e6f
        }
    }

    /** (A worker) the ground's pieces, the rows' furniture, greenery and props, chunked and packed. */
    private fun prepareGround() {
        val tg = TownGround(town, art, farFields = farFields)
        for (p in tg.pieces()) {
            // (one packed picture per mesh, shared by all its chunks' batches while they wait for their upload; a piece's
            // own, so it is let go with the piece's batches, not held to the end of the whole ground)
            val share = java.util.IdentityHashMap<IntArray, StreetTextures.Prepared>()
            val an = p.anchors
            // (the street lamps and benches can be broken by the cars: their batches keep what they were packed from)
            val tag = when (p.name) { "lamps" -> "lamp"; "benches" -> "streetBench"; "plaza-benches" -> "plazaBench"; else -> null }
            if (an == null) { PropLayer.Packing.pack(p.mesh, p.at, share)?.let { offer(Job(it, p.casts, p.tiled, hide = tag?.let { t -> HideSrc(t, p.mesh, p.at, null) })) }; continue }
            val by = LinkedHashMap<String, Pair<ChunkSpec, MutableList<Int>>>()
            for ((i, a) in p.at.withIndex()) { val sp = specOf(an[i], a[0], a[1]); by.getOrPut(sp.key) { sp to ArrayList() }.second += i }
            for ((sp, ids) in by.values) {
                val at = ids.map { p.at[it] }
                val ext = extent(at, p.near ?: p.mesh)
                // (the 3D trees go with their cards, to be packed only as the chunk comes near: Chunk.startNear)
                val trees = p.trees?.let { t -> ids.map { t[it] to p.at[it] } }
                val cards = p.near != null || trees != null
                PropLayer.Packing.pack(p.mesh, at, share)?.let { offer(Job(it, p.casts, p.tiled, sp, part = if (cards) 1 else 0, extent = ext, near = p.near, nearAt = at, trees = trees,
                    hide = if (cards) null else tag?.let { t -> HideSrc(t, p.mesh, at, null) })) }
            }
        }
        // (the street props' meshes repeat along every row: one packed picture each for them all; small pictures)
        val share = java.util.IdentityHashMap<IntArray, StreetTextures.Prepared>()
        val by = LinkedHashMap<String, Pair<ChunkSpec, MutableList<WorldProp>>>()
        for ((a, wp) in tg.anchoredProps()) { val sp = specOf(a, wp.x, wp.z); by.getOrPut(sp.key) { sp to ArrayList() }.second += wp }
        for ((sp, list) in by.values) {
            var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (wp in list) { x0 = min(x0, wp.x); z0 = min(z0, wp.z); x1 = max(x1, wp.x); z1 = max(z1, wp.z) }
            val ext = floatArrayOf(x0, z0, x1, z1, 40f, 70f)
            // (by look, as PropLayer.Packing.packProps packs them, each batch keeping its props: the cars can break them)
            val looks = LinkedHashMap<PropMesh, MutableList<WorldProp>>()
            for (wp in list) looks.getOrPut(PropKit.mesh(wp.spot.kind, wp.spot.variant, wp.spot.text, wp.spot.seed)) { ArrayList() } += wp
            for ((mesh, g) in looks) {
                val at = g.map { wp -> floatArrayOf(wp.x, wp.z, wp.ax, wp.az, wp.ox, wp.oz, wp.spot.turn) }
                PropLayer.Packing.pack(mesh, at, share)?.let { offer(Job(it, g.first().spot.kind.shadow, false, sp, part = 0, extent = ext, hide = HideSrc("prop", mesh, at, g))) }
            }
        }
        lampSpots = tg.lampSpots.toList()
    }

    /** (A worker) the yards, baked lot by lot on every core and packed. */
    private fun prepareYards() {
        val sink = object : YardSink {
            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?, water: Boolean): MaterialInstance? {
                PropLayer.Packing.pack(mesh, at, YardKit.sharedAtlas?.shareFor(mesh))?.let { offer(Job(it, casts, tiled, patch = patch, yard = true, water = if (water) intArrayOf(mesh.texW, mesh.texH) else null)) }
                return null
            }
            override fun props(list: List<WorldProp>) {
                for ((packed, casts, _) in PropLayer.Packing.packProps(list)) offer(Job(packed, casts, false, yard = true))
            }
            override fun animate(tick: (seconds: Double) -> Unit) { synchronized(ticks) { ticks += tick } }
        }
        yardBuilder.build(town, yards, sink)
    }

    private fun extent(at: List<FloatArray>, mesh: PropMesh): FloatArray {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (a in at) { x0 = min(x0, a[0]); z0 = min(z0, a[1]); x1 = max(x1, a[0]); z1 = max(z1, a[1]) }
        val b = mesh.bounds
        return floatArrayOf(x0, z0, x1, z1, max(max(abs(b[0]), abs(b[3])), max(abs(b[2]), abs(b[5]))), b[4])
    }

    /**
     * Uploads what the workers have made ready, until [budgetMs] is spent (the engine's thread, once a frame). Returns
     * true once the whole ground is in.
     */
    fun uploadGround(budgetMs: Float): Boolean {
        if (groundBuilt) return true
        if (groundStarted == 0L) startGround()
        failure?.let { throw IllegalStateException("building the town's ground failed: ${it.message}", it) }
        val t0 = System.nanoTime()
        var sent = 0
        while (true) {
            val j = jobs.poll() ?: break
            upload(j)
            sent++
            queued.addAndGet(-j.packed.heldBytes)
            synchronized(queueLock) { queueLock.notifyAll() }
            if ((System.nanoTime() - t0) / 1e6f >= budgetMs) break
        }
        // (the uploads' buffers are the driver's until it has run them; kicked now, it lets them go this frame, not only
        // when the frame is drawn: a frame the GPU is behind on is skipped, and the preview's cold start draws none)
        if (sent > 0) engine.flush()
        val ms = (System.nanoTime() - t0) / 1e6f
        groundUploadMs += ms
        groundUploadWorst = max(groundUploadWorst, ms)
        if (running.get() == 0 && jobs.isEmpty()) {
            failure?.let { throw IllegalStateException("building the town's ground failed: ${it.message}", it) }
            // the lanterns, and the point lights that will light the nearest of them
            val spots = lampSpots ?: emptyList()
            lanterns = FloatArray(spots.size * 3).also { a ->
                for ((i, l) in spots.withIndex()) {
                    a[i * 3] = l[0] + l[2] * TownGround.LANTERN_OUT; a[i * 3 + 1] = TownGround.LANTERN_Y; a[i * 3 + 2] = l[1] + l[3] * TownGround.LANTERN_OUT
                }
            }
            for (e in lampLights) {
                LightManager.Builder(LightManager.Type.POINT).color(1f, .7f, .42f).intensity(0f).falloff(LAMP_REACH).castShadows(false)
                    .position(0f, -1000f, 0f).build(engine, e)
                scene.addEntity(e)
            }
            groundBuilt = true
            buildSkirt()
            groundMs = (System.nanoTime() - groundStarted) / 1e6f
        }
        return groundBuilt
    }

    private fun upload(j: Job) {
        // (what it is for, for the GPU's breakdown by part: GpuAccount.categories)
        GpuAccount.scoped(when { j.chunk != null && j.part != 0 -> "trees"; j.chunk != null -> "chunks"; j.patch != null || j.yard -> "yards"; else -> "ground" }) { uploadJob(j) }
    }

    private fun uploadJob(j: Job) {
        val sp = j.chunk
        if (sp != null) {
            val c = chunks.getOrPut(sp.key) { Chunk(sp.refX, sp.refZ, sp.byRow) }
            j.extent?.let { c.take(it) }
            when (j.part) {
                0 -> { c.base.upload(j.packed, j.casts, j.tiled); j.hide?.let { hideables += Hideable(c.base, c.base.batchCount - 1, j.casts, it) } }
                else -> { c.cards.upload(j.packed, j.casts, j.tiled); if (j.trees != null) c.treeCards += c.cards.batchCount - 1 }
            }
            if (j.near != null && j.nearAt != null) c.addNear(j.near, j.nearAt, j.casts)
            j.trees?.let { c.addTrees(it) }
            return
        }
        val jp = j.patch
        // (streaming: a lot's near version comes in with the ground only near the player; past that only its far version
        // does, and the lot is marked let go, to come back as the player nears it)
        if (jp != null && !jp.far) {
            val lot = jp.follows ?: jp.id
            if (jp.follows == null) lotInfo[jp.id] = jp
            if (stream && !focusX.isNaN() && (lot in yardOut ||
                    lastPose == null && hypot(jp.x - focusX, jp.z - focusZ) > yardNeed(null) + TownStream.MARGIN + TownStream.HYSTERESIS)) {
                yardOut += lot
                return
            }
            lotBytes[lot] = (lotBytes[lot] ?: 0L) + j.packed.bytes
        }
        val layer = when {
            j.patch != null -> patches.getOrPut(j.patch.id) { Patch(j.patch) }.layer
            j.yard -> yardLayer
            else -> ground
        }
        // (a pool's water scrolls its picture on the instance returned: that batch stays masked)
        val own = j.water != null
        val mi = if (stream && jp != null && !jp.far) BufferArena.streamed { layer.upload(j.packed, j.casts, j.tiled, own) } else layer.upload(j.packed, j.casts, j.tiled, own)
        j.hide?.let { hideables += Hideable(layer, layer.batchCount - 1, j.casts, it) }
        j.water?.let { (w, h) ->
            val tick: (Double) -> Unit = { s -> YardLayer.shimmer(mi, w, h, s) }
            synchronized(ticks) { ticks += tick }
            j.patch?.let { patchTicks.getOrPut(it.id) { ArrayList() } += tick }
        }
    }

    /**
     * Builds the ground, river, bridges, greenery, furniture, the street props and the yards (once), waiting for it: the
     * workers make it ready, this thread uploads it ([startGround] and [uploadGround] without the wait).
     */
    fun buildGround() {
        startGround()
        while (!uploadGround(Float.MAX_VALUE)) Thread.sleep(1)
    }

    /** The chunk [a] (at world [x], [z]) belongs in. */
    private fun specOf(a: Anchor, x: Float, z: Float): ChunkSpec {
        val row = a.row
        if (row != null) {
            val k = kotlin.math.floor(a.x / CHUNK).toInt()
            val mid = ((k + .5f) * CHUNK).coerceIn(0f, row.length)
            val p = row.pointAt(mid, 0f)
            return ChunkSpec("r${row.id}/$k", p.x, p.z, byRow = true)
        }
        val i = kotlin.math.floor(x / CELL).toInt(); val j = kotlin.math.floor(z / CELL).toInt()
        return ChunkSpec("c$i/$j", (i + .5f) * CELL, (j + .5f) * CELL, byRow = false)
    }

    /**
     * Makes the full-3D greenery of every chunk [pose] would show near (unbudgeted: a preview's or a teleport's; the
     * game's frames make it in [buildNearest]'s budget).
     */
    fun prepare(pose: TownPose) {
        for (c in chunks.values) if (c.hasNear && c.near == null && pose.distance(c.cx, 20f, c.cz) - c.r < TownFrame.LOD_NEAR && pose.sees(c.cx, 20f, c.cz, c.r)) c.buildNear()
        for (c in chunks.values) if (c.near != null) c.trees?.prepare(pose)
    }

    /**
     * Builds buildings not yet built, nearest ([x], [z]) first, until [budgetMs] is spent; returns how many. Each building
     * is made ready on the workers ([BuildingMeshes.prepare]: its art decoded, its geometry and pictures packed), so here
     * only its buffers, textures and renderables are made; the first ready one is finished even past the budget (so the
     * town always comes in), the rest only while the budget lasts. Call each frame until [pending] is 0.
     */
    fun buildNearest(x: Float, z: Float, budgetMs: Float): Int {
        java.util.Arrays.fill(buildParts, 0L)
        var pt = System.nanoTime()
        fun part(i: Int) { val n = System.nanoTime(); buildParts[i] += n - pt; pt = n }
        if (!farCounted) { farCounted = true; if (farLod) for (s in slots) if (!s.missing && s.built == null) s.farCell = far.expect(s.cx, s.cz) }
        if (far.upload()) engine.flush()
        part(BP_FAR)
        // (streaming: what should be resident now, what may go, the yards' near versions in and out: TownStream)
        streamStep(x, z, budgetMs)
        part(BP_STREAM)
        impostorStep()
        // (the cinema, the stores and the landmark models within the first view's reach are its work, by their distance
        // like the buildings; those further off wait while [holdLater] holds, then go after every building the first view
        // waits for, nearest first: TownWorkers.LATER plus their distance)
        cinemaDraw?.let { c -> if (!c.done) { laterPriority(c.cx, c.cz, x, z)?.let { c.start(pool, it) }; if (c.step()) engine.flush() } }
        for (st in storeDraws) if (!st.done) { laterPriority(st.cx, st.cz, x, z)?.let { st.start(pool, it, lastHour, weekday) }; if (st.step()) engine.flush() }
        if (depotDraw == null && depotSpec != null && !depotTried) { depotTried = true; depotDraw = makeDepot() }
        // (a landmark's height field: once the player comes near it, or the workers are idle after the whole town; none
        // while the first view is built: the player stands still behind the loading screen)
        val idle = todo == 0 && groundBuilt
        for (lm in landmarkDraws) { if (!lm.done) { laterPriority(lm.cx, lm.cz, x, z)?.let { lm.start(pool, it) }; if (lm.step()) engine.flush() } else if (!holdLater) lm.stepLevels(pool, x, z, idle) }
        depotDraw?.let { lm -> if (!lm.done) { laterPriority(lm.cx, lm.cz, x, z)?.let { lm.start(pool, it) }; if (lm.step()) engine.flush() } else if (!holdLater) lm.stepLevels(pool, x, z, idle) }
        // (the extras' work (the farms, the world round the town) waits for the ground and the first view: the first view comes first)
        part(BP_SITES)
        if (groundBuilt && !holdLater) for (x in extras) x.step()
        part(BP_EXTRAS)
        if (todo == 0 && chunksTodo == 0 && groundBuilt) return 0
        val t0 = System.nanoTime()
        var n = 0
        if (todo > 0) {
            order.clear()
            for (s in slots) if (s.built == null && !s.missing && (s.want || !s.known)) order += s
            todo = order.size
            order.sortBy { hypot(it.cx - x, it.cz - z) }
            // made ready on the workers, nearest first, ahead of being finished here: at most [readyAhead] at a time (each
            // holds its packed geometry and pictures, up to tens of MB for a large model, until it is finished here; once
            // all 98 were started at once and the finished-but-waiting ones piled up on the phone's Java heap)
            for (s in order) if (s !in readyOf) {
                if (readyOf.size >= readyAhead) break
                // (while the first view is built only its own buildings: the rest would take the cores from its ground and yards)
                if (holdLater && hypot(s.cx - x, s.cz - z) >= TownRun.READY_RADIUS) break
                val yarded = s.b.kind != PlacementKind.LANDMARK && yards.of(s.b.sprite) != null
                val sprite = s.b.sprite; val setback = s.b.setback
                val needPics = !s.known
                readyOf[s] = pool.submit(hypot(s.cx - x, s.cz - z), "building", sprite, java.util.concurrent.Callable {
                    val r0 = System.nanoTime()
                    val sb = building(sprite)
                    val r = sb?.let { meshes.prepare(it, setback, yarded, sprite.hashCode()) }
                    if (needPics && sb != null && r != null) TownFar.pics(sb)?.let { farPics[s] = it }
                    val ms = (System.nanoTime() - r0) / 1e6f
                    synchronized(this) { prepareMs += ms; if (ms > slowestPrepare.second) slowestPrepare = sprite to ms }
                    r
                })
            }
            for (s in order) {
                if (n > 0 && (System.nanoTime() - t0) / 1e6f >= budgetMs) break
                val f = readyOf[s] ?: continue
                if (!f.isDone) continue
                // (a building whose art will not load is left out; one that ran out of memory stops the town, saying so,
                // rather than going missing without a word)
                val r = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
                    val c = e.cause ?: e
                    if (c is VirtualMachineError) throw BuildFailure("making building ${s.b.sprite} ready on a worker", c)
                    null
                }
                buildSlot(s, r)
                FrameWork.count(FrameWork.UP_BUILDINGS)
                readyOf.remove(s)
                todo--
                n++
            }
            order.clear()
        }
        if (n > 0) engine.flush()
        part(BP_BUILDINGS)
        val ms = (System.nanoTime() - t0) / 1e6f
        buildingsMs += ms
        if (n > 0) batches += n to ms
        // then, with what is left of the budget, the 3D greenery of the chunks coming near
        if (n == 0 && chunksTodo > 0 && groundBuilt) {
            // (no list made: the nearest packed chunk in reach, again and again while the budget lasts; those in reach not
            // packed yet start packing on a worker)
            var left = 0
            for (c in chunks.values) if (c.near == null && c.hasNear) {
                left++
                if (hypot(c.cx - x, c.cz - z) - c.r < TownFrame.LOD_NEAR + 600f) c.startNear()
            }
            chunksTodo = left
            while (left > 0 && (System.nanoTime() - t0) / 1e6f < budgetMs) {
                var best: Chunk? = null; var bd = Float.MAX_VALUE
                for (c in chunks.values) {
                    if (c.near != null || !c.hasNear || !c.nearReady) continue
                    val d = hypot(c.cx - x, c.cz - z) - c.r
                    if (d < TownFrame.LOD_NEAR + 600f && d < bd) { bd = d; best = c }
                }
                val b = best ?: break
                b.buildNear()
                FrameWork.count(FrameWork.UP_GROUND)
                left--
                chunksTodo = left
            }
        }
        part(BP_CHUNKS)
        return n
    }

    /**
     * The last [buildNearest]'s time by part (ns; [BUILD_PARTS] names them): the far cells' uploads, streaming, the sites
     * (cinema, stores, landmarks), the extras (the farms, the world round the town), the buildings, the chunks' greenery.
     */
    val buildParts = LongArray(6)

    /**
     * The first view is being built ([TownRun] sets it until its view is ready): only its own work goes to the workers
     * (the ground, the yards, and the buildings, cinema, stores and landmark models within [TownRun.READY_RADIUS]); the
     * rest (those further off, the landmarks' height fields, the farms and the world round the town) waits, so it takes
     * no worker or core from it. Off by default (previews and tests that build the whole town at once).
     */
    var holdLater = false

    /** The workers' priority for a cinema, store or landmark at ([cx], [cz]) with the player at ([x], [z]); null: not yet ([holdLater]). */
    private fun laterPriority(cx: Float, cz: Float, x: Float, z: Float): Float? {
        val d = hypot(cx - x, cz - z)
        return if (d < TownRun.READY_RADIUS) d else if (holdLater) null else TownWorkers.LATER + d
    }

    /** The cinema, stores and landmark models within [radius] of ([x], [z]) not yet built (the first view waits for them too). */
    fun nearPending(x: Float, z: Float, radius: Float): Boolean =
        cinemaDraw?.let { !it.done && hypot(it.cx - x, it.cz - z) < radius } == true ||
            storeDraws.any { !it.done && hypot(it.cx - x, it.cz - z) < radius } ||
            landmarkDraws.any { !it.done && hypot(it.cx - x, it.cz - z) < radius } ||
            depotDraw?.let { !it.done && hypot(it.cx - x, it.cz - z) < radius } == true

    /** Buildings not yet built (a count kept by [buildNearest], so a frame with nothing left does no work). */
    private var todo = Int.MAX_VALUE
    /** Chunks whose 3D greenery is not made yet (as last counted; once the ground is in). */
    private var chunksTodo = Int.MAX_VALUE
    private val order = ArrayList<Slot>()

    /**
     * Makes every landmark's height field now, waiting (previews and tests that walk a landmark's steps straight after
     * the build; the game makes each as the player comes near: [TownLandmark.stepLevels]).
     */
    fun finishLevels() {
        for (lm in landmarkDraws + listOfNotNull(depotDraw)) if (lm.done) lm.stepLevels(pool, lm.cx, lm.cz, idle = true, now = true)
    }

    /** Nothing is left to build: every building, and every chunk's 3D greenery. */
    val allBuilt: Boolean get() = groundBuilt && pending == 0 && (stream || chunks.values.none { it.near == null && it.hasNear })

    /** Reads and packs [images] on a worker ahead of their first use here ([StreetTextures.warm]). */
    fun warm(images: List<androidx.compose.ui.graphics.ImageBitmap>) {
        if (images.isNotEmpty()) pool.execute { runCatching { for (i in images) textures.warm(i) } }
    }

    /** The slowest single building so far: its sprite and how long finishing it took here (ms; made ready on a worker). */
    var slowest: Pair<String, Float> = "" to 0f; private set
    /** The workers' time making buildings ready (ms, all of them), and the slowest one there. */
    @Volatile var prepareMs = 0f; private set
    @Volatile var slowestPrepare: Pair<String, Float> = "" to 0f; private set

    /**
     * How many buildings may be made ready (or wait, ready) ahead of being finished here: one for each worker and two
     * more, so no worker waits while the engine's thread finishes the last ones (a frame later at most). Each holds its
     * pictures packed (a large model's atlas is 7 MB) until finished: twice as many held some 55 MB at once to no gain.
     */
    private val readyAhead = max(2, Runtime.getRuntime().availableProcessors() - 1) + 2

    /** Each building being made ready on a worker. */
    private val readyOf = HashMap<Slot, java.util.concurrent.Future<BuildingMeshes.Ready?>>()

    private fun buildSlot(s: Slot, r: BuildingMeshes.Ready?) {
        val t0 = System.nanoTime()
        try { buildOne(s, r) } finally {
            val ms = (System.nanoTime() - t0) / 1e6f
            if (ms > slowest.second) slowest = s.b.sprite to ms
        }
    }

    private fun buildOne(s: Slot, r: BuildingMeshes.Ready?) {
        if (r == null) { s.missing = true; if (!s.known) s.farCell?.let { far.skip(it) }; s.known = true; farPics.remove(s); return }
        // (streaming: a building first made ready while it is wanted only far off gives its far box and bounds, then is let
        // go without anything made on the GPU: only what is near is ever resident in full)
        if (!s.want && !s.known) {
            val bb = meshes.boundsOf(r)
            meshes.discard(r)
            if (bb == null) { s.missing = true; s.farCell?.let { far.skip(it) }; s.known = true; farPics.remove(s); return }
            placeBounds(s, bb)
            addFar(s, bb)
            return
        }
        // (the yard kit draws a lot's ground and fences: the house's and the model's own were left off there when made
        // ready, not the landmarks', whose grounds the yards do not cover yet)
        val built = if (stream) BufferArena.streamed { textures.owned(s.held) { meshes.finish(r) } } else meshes.finish(r)
        if (built == null) { s.missing = true; if (!s.known) s.farCell?.let { far.skip(it) }; s.known = true; textures.release(s.held); s.held.clear(); farPics.remove(s); return }
        if (s.known) { s.streamedIn++; streamer.buildingsIn++; s.farCell?.let { if (it.waiting > 0) it.waiting-- } }
        val tm = engine.transformManager
        s.place.matrix(xf)
        for (e in listOf(built.entity, built.proxy)) {
            if (e == 0) continue
            tm.create(e)
            tm.setTransform(tm.getInstance(e), xf)
            scene.addEntity(e)
        }
        s.built = built
        if (stream) { val by = textures.sizeOf(s.held) + built.geo.vLen * built.geo.stride.toLong() + built.geo.iLen * 2L; slotBytes[s] = by; buildingBytes += by }
        val bb = built.bounds
        placeBounds(s, bb)
        if (!s.known) addFar(s, bb)
    }

    /** [s]'s sphere from its real bounds [bb] (its own frame), kept for the camera's cut while it is let go. */
    private fun placeBounds(s: Slot, bb: FloatArray) {
        s.bounds = bb
        val lx = (bb[0] + bb[3]) / 2f; val lz = (bb[2] + bb[5]) / 2f
        val c = s.place.world(lx, lz)
        s.cx = c.x; s.cz = c.z; s.cy = (bb[1] + bb[4]) / 2f
        s.r = sqrt((bb[3] - bb[0]) * (bb[3] - bb[0]) + (bb[4] - bb[1]) * (bb[4] - bb[1]) + (bb[5] - bb[2]) * (bb[5] - bb[2])) / 2f
    }

    /** [s]'s far box into its cell, once ([Slot.known]). */
    private fun addFar(s: Slot, bb: FloatArray) {
        s.known = true
        // its far box: the model's own width, from its front wall back, as tall as it (a pitched house to below its ridge)
        s.farCell?.let { cell ->
            val pics = farPics.remove(s)
            val zf = max(bb[2], s.b.setback); val zb = max(bb[5], zf + 4f)
            // (no wider nor taller than its front picture: the model's bounds take in its lawn, fences, flagpoles and aerials)
            val kk = com.lifemax.rpg.feature.habits.domain.BuildingSpec.W / 224f
            val pw = pics?.let { it.front.w * TownFar.SHRINK * kk } ?: (bb[3] - bb[0])
            val ph = pics?.let { it.front.h * TownFar.SHRINK * kk } ?: bb[4]
            val mx = (bb[0] + bb[3]) / 2f; val hw = min((bb[3] - bb[0]) / 2f, pw / 2f)
            // (seen from the street the art runs along local -x: its left is the larger x)
            val c = FloatArray(8)
            fun put(i: Int, lx: Float, lz: Float) { val w = s.place.world(lx, lz); c[i * 2] = w.x; c[i * 2 + 1] = w.z }
            put(0, mx + hw, zf); put(1, mx - hw, zf); put(2, mx - hw, zb); put(3, mx + hw, zb)
            far.add(cell, c, min(bb[4], ph), pics)
        }
    }

    // ------------------------------------------------------------------ each frame

    /** The windows' night light's time a frame ([NIGHT_BUDGET_NS]; a still picture, as a preview's, wants it unbounded). */
    var nightBudgetNs = NIGHT_BUDGET_NS
    private val nightQueue = arrayOfNulls<Slot>(slots.size)
    private var nightCount = 0
    private var nightNext = 0

    // the buildings' per-frame parameters last sent ([frame]): the look, glow, glass and see-through generation
    private val sentLook = FloatArray(16) { Float.NaN }
    private val sentKey = FloatArray(8) { Float.NaN }
    private var paramGen = 0
    private var sentSee = -1

    /** Brings the scene to camera [pose] at [hour]; [seconds] is the clock (the yards' water shimmers by it). */
    fun frame(pose: TownPose, hour: Float, seconds: Double = 0.0) {
        // (what streaming let go of, destroyed a little a frame: docs/HEAT.md)
        Graveyard.drain(TownStream.GRAVE_MS)
        if (TownQuality.msaa != msaaSet) { msaaSet = TownQuality.msaa; TownQuality.applyMsaa(view) }
        lastPose = pose
        lastHour = hour
        view.viewport = Viewport(0, 0, pose.w.toInt(), pose.h.toInt())
        // (the look's depth reaches 1 at the camera's own near: Filament's lights are sorted into the view's depth slices
        // by the camera's projection, so the two must agree or the street lamps' light lands in the wrong slices)
        LookMatrix.pinhole(pose.screen(m12), pose.w, pose.h, pose.near, look)
        aim(pose)
        val sky = skyColors(hour)
        light(pose, hour, sky)
        skySurface?.let { ss ->
            if (day.isEmpty()) skyState.update(hour, .5f, weather, season, seconds.toFloat()) else skyState.update(hour, day, weather, season, seconds.toFloat())
            // (the pinhole's ray through each pixel, as LookMatrix.pinhole projects: the principal point off the middle)
            val fl = pose.fl.coerceAtLeast(1f)
            ss.update(skyState, pose.rx, pose.ry, pose.rz, pose.ux, pose.uy, pose.uz, pose.fx, pose.fy, pose.fz,
                pose.w / (2f * fl), pose.h / (2f * fl), (pose.w / 2f - pose.cx) / fl, (pose.cy - pose.h / 2f) / fl)
            // (the horizon ring's air: the world's haze, as [haze] sets the fog for the far view, from the eye's height)
            worldBaseM?.let { base -> ss.horizonAir(air.extinction.toFloat(), (1.0 / air.scaleHeightM).toFloat(), (base + pose.ey / worldUnitsPerMetre).toFloat()) }
        }
        haze(pose, sky)
        val dark = sky.dark
        crowd.dark = dark
        val wl = SceneInk.WindowLit
        val g0 = lin(wl.red); val g1 = lin(wl.green) * .9f; val g2 = lin(wl.blue) * .7f
        val ga = .75f * smooth((dark - .35f) / .5f)
        val tone = sky.sky.lastOrNull() ?: Color.White
        val m = max(tone.red, max(tone.green, tone.blue)).coerceAtLeast(.05f)
        val gl0 = mix(1f, tone.red / m * 1.15f, .7f); val gl1 = mix(1f, tone.green / m * 1.15f, .7f); val gl2 = mix(1f, tone.blue / m * 1.15f, .7f)
        // (the buildings' shared parameters this frame: a new generation when any of them changed)
        val pk = sentKey
        if (!look.contentEquals(sentLook) || pk[0] != g0 || pk[1] != g1 || pk[2] != g2 || pk[3] != ga || pk[4] != gl0 || pk[5] != gl1 || pk[6] != gl2 || sentSee != SeeThrough.gen) {
            System.arraycopy(look, 0, sentLook, 0, 16)
            pk[0] = g0; pk[1] = g1; pk[2] = g2; pk[3] = ga; pk[4] = gl0; pk[5] = gl1; pk[6] = gl2; sentSee = SeeThrough.gen
            paramGen++
        }
        val rm = engine.renderableManager
        var visible = 0; var draws = 0; var tris = 0; var renderables = 0; var casters = 0
        // (what is cut away for the open world's camera eases by the clock, the same at 60 and 120 Hz)
        val dt = if (lastFrameSeconds.isNaN()) 1f else (seconds - lastFrameSeconds).toFloat().coerceIn(0f, .25f)
        lastFrameSeconds = seconds
        val occStep = if (dt <= 0f) 0f else dt / OCCLUDE_SECONDS
        var lap = System.nanoTime()
        // the buildings far off: past the detail line (in the haze) a cell's cheap boxes stand in for its buildings
        far.upload()
        val detail = if (pose.range == Float.MAX_VALUE) Float.MAX_VALUE else pose.hazeFrom + FAR_PAST_HAZE
        // (a frame with no clock, as a still preview's, takes the whole switch at once)
        far.frame(pose, look, dark, detail, if (dt <= 0f) 1f else dt / TownFar.SWITCH_SECONDS, farOut, hide = debugHide and HIDE_FAR != 0)
        renderables += farOut[0]; draws += farOut[0]; tris += farOut[1]
        lap = lap(FrameWork.FAR, lap)
        for (s in slots) {
            val b = s.built ?: continue
            // the street view: what stands between the camera and the street in view fades out
            // (the open world: a building between the camera and the player fades to a screen door, eased in and out)
            // (the open world: a building in the way of the player or by the eye is cut away, eased out and back)
            s.occFade = if (pose.open && s.occluded) max(OCCLUDED, s.occFade - occStep) else min(1f, s.occFade + occStep)
            // (a building whose far box is up is not faded out at the draw distance: it crosses over to the box instead)
            val farFade = far.nearFade(s.farCell)
            val kept = min(if (pose.cut > 0f) fadeOf(pose, s.cx, s.cz) else 1f, if (far.covers(s.farCell)) farFade else pose.rangeFade(s.cx, s.cy, s.cz, s.r))
            val fade = min(kept, s.occFade)
            val on = fade > .02f && pose.sees(s.cx, s.cy, s.cz, s.r) && debugHide and HIDE_BUILDINGS == 0
            s.shown = on
            // (no takeIf here: its nullable Int is a boxed Integer a building a frame)
            val ri = rm.getInstance(b.entity)
            if (ri != 0) rm.setLayerMask(ri, 0xff, if (on) 1 else 0)
            // (its shadow may fall into view from beside it: the twin stays while it is near the view, and while it is cut
            // away for the camera: the street keeps its shade)
            val near = on || (kept > .02f && pose.sees(s.cx, s.cy, s.cz, s.r + 300f))
            if (b.proxy != 0) { val pi = rm.getInstance(b.proxy); if (pi != 0) rm.setLayerMask(pi, 0xff, if (near) 1 else 0) }
            if (near && b.proxy != 0) casters++
            if (!on) continue
            visible++; renderables++; draws += b.primitives; tris += b.triangles
            // (its opaque parts wear their masked twins while it fades or the see-through may reach it: [Cutouts])
            b.twins?.wear(engine, b.entity, fade < 1f || (SeeThrough.cuts && SeeThrough.reaches(s.cx - s.r, s.cy - s.r, s.cz - s.r, s.cx + s.r, s.cy + s.r, s.cz + s.r)))
            // (only when something it shows changed: standing still, its instances are not touched, nor committed again)
            val masked = b.twins?.isMasked == true
            if (s.sentGen != paramGen || s.sentFade != fade || s.sentTo !== b || s.sentMasked != masked) {
                s.sentGen = paramGen; s.sentFade = fade; s.sentTo = b; s.sentMasked = masked
                for (mi in b.mis) {
                    mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                    SeeThrough.apply(mi)
                    mi.setParameter("cell", 2f)
                    mi.setParameter("fade", fade)
                    mi.setParameter("glow", g0, g1, g2, ga)
                    mi.setParameter("glass", gl0, gl1, gl2, 1f)
                }
            }
            if (b.night != null) nightQueue[nightCount++] = s
        }
        lap = lap(FrameWork.BUILDINGS, lap)
        // the windows' night light (and a model's clock): a building's first is a whole picture worked out and uploaded,
        // so a few milliseconds of them a frame, starting each frame where the last stopped (the rest come next frame)
        if (nightCount > 0) {
            val n0 = System.nanoTime()
            for (k in 0 until nightCount) {
                val sl = nightQueue[(k + nightNext) % nightCount] ?: continue
                sl.built?.night?.invoke(hour)
                if (System.nanoTime() - n0 > nightBudgetNs) { nightNext = (k + nightNext + 1) % nightCount; break }
            }
            java.util.Arrays.fill(nightQueue, 0, nightCount, null)
            nightCount = 0
        }
        lap = lap(FrameWork.NIGHT, lap)
        if (skirtUp) skirt.update(look, dark, pose.open && pose.range < Float.MAX_VALUE && debugHide and HIDE_GROUND == 0, 1f, false)
        for (layer in flatLayers) {
            layer.update(look, dark, debugHide and (if (layer === ground) HIDE_GROUND else HIDE_YARDS) == 0)
            renderables += layer.draws; draws += layer.draws
            casters += layer.casters
            tris += layer.triangles
        }
        // the yards a lot at a time: culled to the view (their shadows while near it), faded with their house; in the open
        // world, past [TownPose.lodFar] each lot's far version is drawn in its place
        for (pt in patches.values) {
            val p = pt.p
            // (a lot's tall thing goes with its lot: drawn while the lot is near, left to the lot's far version past it)
            if (p.follows != null) { if (p.follows in farLots) { pt.layer.update(look, dark, false, 0f, false); continue } }
            else if (p.far || pose.lodFar < Float.MAX_VALUE || pt.nearKey in yardOut) {
                // (a lot switches to its far version past the line and back only well inside it: no flicker at the line)
                val d = pose.distance(p.x, 15f, p.z)
                val wasFar = pt.nearKey in farLots
                val far = pt.nearKey in yardOut || if (wasFar) d > pose.lodFar - TownFrame.LOD_SLACK else d > pose.lodFar + TownFrame.LOD_SLACK
                if (far != wasFar) { if (far) farLots.add(pt.nearKey) else farLots.remove(pt.nearKey) }
                val off = if (p.far) !far else far && patches.containsKey(pt.farKey)
                if (off) { pt.layer.update(look, dark, false, 0f, false); continue }
            }
            val fade = min(if (pose.cut <= 0f) 1f else pt.slot?.let { fadeOf(pose, it.cx, it.cz) } ?: fadeOf(pose, p.x, p.z), pose.rangeFade(p.x, 15f, p.z, p.r))
            val on = fade > .02f && pose.sees(p.x, 15f, p.z, p.r + 30f) && debugHide and HIDE_YARDS == 0
            // (a cut lot throws no shadow: a fence's shadow without its fence reads wrong)
            val near = fade > .5f && (on || pose.sees(p.x, 15f, p.z, p.r + 150f))
            // (the open world: a lot whose shed, garage, tree or tall hedge is in the camera's way has its tall things cut
            // away, eased; its lawn and paths stay, and the shadows too)
            pt.occFade = if (pose.open && pt.nearKey in occludedLots) max(OCCLUDED, pt.occFade - occStep) else min(1f, pt.occFade + occStep)
            // (a tall thing's own patch is cut whole, its flat parts too)
            pt.layer.update(look, dark, on, if (p.follows != null) min(fade, pt.occFade) else fade, near, castFade = pt.occFade)
            if (on) { renderables += pt.layer.draws; draws += pt.layer.draws; tris += pt.layer.triangles }
            if (near) casters += pt.layer.casters
        }
        lap = lap(FrameWork.YARDS, lap)
        // the furniture, props and greenery a chunk at a time: culled, cut with their row, cards far and 3D near
        var nChunks = 0; var nNear = 0
        val farOn = far.readyCount > 0 && pose.range < Float.MAX_VALUE
        // (one tree cell's detail uploaded a frame: two made the greenery's worst frames 8-28 ms on the build server)
        TreeCells.newFrame(1)
        for (c in chunks.values) {
            val cut = if (pose.cut <= 0f) 1f else if (c.byRow) fadeOf(pose, c.refX, c.refZ) else fadeOf(pose, c.cx, c.cz)
            val fade = min(cut, pose.rangeFade(c.cx, c.top / 2f, c.cz, c.r))
            val on = fade > .02f && pose.sees(c.cx, c.top / 2f, c.cz, c.r) && debugHide and HIDE_GREEN == 0
            val shadows = fade > .5f && (on || pose.sees(c.cx, c.top / 2f, c.cz, c.r + 150f))
            // (the cards and the 3D greenery cross over in [GREEN_SWITCH_SECONDS] by the screen door and its inverse, so a
            // tree line passing the line no longer swaps all at once: 0.1.402's "trees changing at distance"; and with
            // [TownFrame.LOD_SLACK] either side of the line, so it does not flick to and fro as the camera moves)
            val dNear = pose.distance(c.cx, c.top / 2f, c.cz) - c.r
            c.is3d = on && c.hasNear && c.near != null &&
                (if (c.is3d) dNear < TownFrame.LOD_NEAR + TownFrame.LOD_SLACK else dNear < TownFrame.LOD_NEAR - TownFrame.LOD_SLACK)
            // (a still picture, or a frame after a long pause: at once)
            c.t3 = if (dt <= 0f || dt >= .25f) (if (c.is3d) 1f else 0f) else if (c.is3d) min(1f, c.t3 + dt / GREEN_SWITCH_SECONDS) else max(0f, c.t3 - dt / GREEN_SWITCH_SECONDS)
            // (streaming: a chunk far past its 3D line lets its 3D greenery go; it is made again as it comes near)
            if (stream && 'c' !in TownStream.OFF && c.near != null && !c.is3d && c.t3 <= 0f && !focusX.isNaN() && hypot(c.cx - focusX, c.cz - focusZ) - c.r > TownFrame.LOD_NEAR + 600f + TownStream.HYSTERESIS) { c.dropNear(); chunksTodo = Int.MAX_VALUE; streamer.chunksOut++ }
            val near3d = on && c.near != null && c.t3 > 0f
            val fade3 = min(fade, c.t3)
            c.base.update(look, dark, on, fade, shadows)
            // (with the far boxes up, the trees' cards are drawn on past the view's range, into the haze: the woods at the
            // world's edge dissolve into it instead of standing as a screen door against the sky)
            val cardFade = if (farOn) min(cut, farFade(pose, c.cx, c.top / 2f, c.cz, c.r)) else fade
            val cardsOn = cardFade > .02f && pose.sees(c.cx, c.top / 2f, c.cz, c.r) && debugHide and HIDE_GREEN == 0
            val crossing = c.t3 > 0f && c.t3 < 1f
            // (mid-crossover the cards keep exactly the pixels the 3D trees drop)
            c.cards.inverseFade = crossing
            c.cards.update(look, dark, cardsOn && c.t3 < 1f, if (crossing) 1f - c.t3 else cardFade, shadows && c.t3 < .5f)
            // the trees far off as impostors of their own 3D selves (TreeImpostors), in the cards' place, packed again as
            // the camera goes round the chunk; the cards they stand in for hidden once they are up
            val ia = impAtlas
            if (ia != null && c.treeList.isNotEmpty()) {
                val want = cardsOn && c.t3 < 1f
                val job = c.impJob
                if (job != null && job.isDone) {
                    c.impJob = null
                    val pk = runCatching { job.get() }.getOrNull()
                    c.imp?.destroy(later = true); c.imp = null
                    if (pk != null) {
                        val l = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
                        if (stream) BufferArena.streamed { GpuAccount.scoped("trees") { l.upload(pk, casts = true) } } else GpuAccount.scoped("trees") { l.upload(pk, casts = true) }
                        c.imp = l
                        if (!c.cardsHidden) { for (i in c.treeCards) c.cards.setBatchVisible(i, false); c.cardsHidden = true }
                    }
                }
                if (want && c.impJob == null && TreeImpostors.needsRebuild(c.cx, c.cz, c.impEx, c.impEz, pose.ex, pose.ez)) {
                    c.impEx = pose.ex; c.impEz = pose.ez
                    val ex = pose.ex; val ez = pose.ez; val list = c.treeList
                    c.impJob = pool.submit(java.util.concurrent.Callable { TreeImpostors.mesh(ia, list, ex, ez)?.let { PropLayer.Packing.pack(it, IMP_AT, impShare) } })
                }
                c.imp?.let { l ->
                    l.inverseFade = crossing
                    l.update(look, dark, want, if (crossing) 1f - c.t3 else cardFade, shadows && c.t3 < .5f)
                    if (want) { renderables += l.draws; draws += l.draws; tris += l.triangles }
                    if (shadows && c.t3 < .5f) casters += l.casters
                }
            }
            c.near?.update(look, dark, near3d, fade3, shadows && near3d && c.t3 >= .5f)
            val tc = c.trees
            tc?.update(pose, look, dark, near3d, fade3, shadows && near3d && c.t3 >= .5f)
            if (on) {
                nChunks++
                renderables += c.base.draws; draws += c.base.draws; tris += c.base.triangles
                if (near3d) c.near?.let { renderables += it.draws; draws += it.draws; tris += it.triangles }
            }
            if (cardsOn && c.t3 < 1f) { renderables += c.cards.draws; draws += c.cards.draws; tris += c.cards.triangles }
            if (tc != null) { renderables += tc.draws; draws += tc.draws; tris += tc.triangles; casters += tc.casters }
            if (near3d) nNear++
            if (shadows) casters += c.base.casters + (if (near3d && c.t3 >= .5f) c.near!!.casters else c.cards.casters)
        }
        lap = lap(FrameWork.GREEN, lap)
        cinemaDraw?.let { cd -> cd.occFade = if (pose.open && cd.occluded) max(OCCLUDED, cd.occFade - occStep) else min(1f, cd.occFade + occStep) }
        cinemaDraw?.frame(pose, look, dark, seconds, { x, z -> if (pose.cut > 0f) fadeOf(pose, x, z) else 1f }, cinemaOut)?.let { o ->
            if (o[0] > 0) { visible++; renderables += o[0]; draws += o[0]; tris += o[1] }; casters += o[2] }
        for (st in storeDraws) {
            st.stepCars(later, hour, weekday)
            st.occFade = if (pose.open && st.occluded) max(OCCLUDED, st.occFade - occStep) else min(1f, st.occFade + occStep)
            val o = st.frame(pose, look, dark, seconds, { x, z -> if (pose.cut > 0f) fadeOf(pose, x, z) else 1f }, storeOut)
            if (o[0] > 0) { visible++; renderables += o[0]; draws += o[0]; tris += o[1] }; casters += o[2]
        }
        for (i in 0 until landmarkDraws.size) {
            val lm = landmarkDraws[i]
            lm.occFade = if (pose.open && lm.occluded) max(OCCLUDED, lm.occFade - occStep) else min(1f, lm.occFade + occStep)
            val o = lm.frame(pose, look, dark, hour, seconds, { x, z -> if (pose.cut > 0f) fadeOf(pose, x, z) else 1f }, landmarkOut)
            if (o[0] > 0) { visible++; renderables += o[0]; draws += o[0]; tris += o[1] }; casters += o[2]
        }
        depotDraw?.let { lm ->
            lm.occFade = if (pose.open && lm.occluded) max(OCCLUDED, lm.occFade - occStep) else min(1f, lm.occFade + occStep)
            val o = lm.frame(pose, look, dark, hour, seconds, { x, z -> if (pose.cut > 0f) fadeOf(pose, x, z) else 1f }, landmarkOut)
            if (o[0] > 0) { visible++; renderables += o[0]; draws += o[0]; tris += o[1] }; casters += o[2]
        }
        if (extras.isNotEmpty()) {
            val fc = TownExtra.Frame(pose, look, dark, hour, seconds, floatArrayOf(g0, g1, g2, ga), floatArrayOf(gl0, gl1, gl2), occStep)
            for (x in extras) { val o = x.frame(fc, storeOut); if (o[0] > 0) { visible++; renderables += o[0]; draws += o[0]; tris += o[1] }; casters += o[2] }
        }
        railDraw?.let { r -> r.frame(look, dark, hour, seconds); renderables += r.draws; draws += r.draws; tris += r.triangles }
        rideLayer?.let { r -> r.frame(look, dark, rideS); tris += r.triangles }
        val lampsLit = lamps(pose, dark)
        farLightsFrame(pose, dark)
        // (copied out under the lock into a list kept for it: the yards' water may add one from a worker meanwhile)
        synchronized(ticks) { tickCopy.clear(); tickCopy.addAll(ticks) }
        for (i in tickCopy.indices) tickCopy[i](seconds)
        lap(FrameWork.STORES, lap)
        stats = TownStats(slots.size, slots.count { it.built != null }, visible, renderables, draws, tris, casters, nChunks, nNear, lampsLit)
    }

    /**
     * At night the [LAMP_LIGHTS] lanterns nearest the point the view looks at (in its range) light the pavement and the
     * fronts round them: real point lights, warm, reaching [LAMP_REACH]; by day they are off. Returns how many are on.
     */
    private val flatLayers by lazy { arrayOf(ground, yardLayer) }

    /**
     * The land past the world's edge: a plain grass-coloured apron round the town's ground out to the far boxes' reach, a
     * little below it, so the far ground runs on into the haze to the horizon instead of ending short of it, where the
     * sky's bright horizon band showed through as a line (0.1.385 to 0.1.402: "still line on the horizon"). One draw, no
     * shadow; its far side is all haze.
     */
    private val skirt = PropLayer(engine, scene, lit, null, sharedTextures = textures, cull = false).also { it.seeThrough = false }
    private var skirtUp = false

    /**
     * How far the world's far view reaches (street units; [WorldFar]): the camera's far plane goes out to it, and the
     * grass apron is left out (the world's own ground runs on from the town's edge). 0: no world, the town as before.
     */
    internal var worldReach = 0f

    /**
     * Whether the town's grass runs on as plain fields to the horizon round its ground (TownGround); the world's near
     * terrain (W5a) turns it off and meets the town's grass at its edge instead. Set before the ground is built.
     */
    internal var farFields = true

    private fun buildSkirt() {
        val c = collision ?: return
        if (skirtUp || worldReach > 0f) return
        val x0 = c.minX; val z0 = c.minZ; val x1 = c.maxX; val z1 = c.maxZ
        val o = TownFar.REACH + 6000f; val y = -SKIRT_DROP
        val img = PixImg(2, 2).also { it.fill(SKIRT_GRASS) }
        val mb = MeshBuilder()
        fun q(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, dx: Float, dz: Float) =
            mb.quad(floatArrayOf(ax, y, az), floatArrayOf(bx, y, bz), floatArrayOf(cx, y, cz), floatArrayOf(dx, y, dz), img)
        // (four bands round the world's rectangle: north and south whole, east and west between them)
        q(x0 - o, z0 - o, x1 + o, z0 - o, x1 + o, z0, x0 - o, z0)
        q(x0 - o, z1, x1 + o, z1, x1 + o, z1 + o, x0 - o, z1 + o)
        q(x0 - o, z0, x0, z0, x0, z1, x0 - o, z1)
        q(x1, z0, x1 + o, z0, x1 + o, z1, x1, z1)
        val packed = PropLayer.Packing.pack(mb.build(), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))) ?: return
        skirt.upload(packed, casts = false)
        skirtUp = true
    }
    private val tickCopy = ArrayList<(Double) -> Unit>()
    /** Adds the time since [t0] to [part] of [FrameWork]; the time now. */
    private fun lap(part: Int, t0: Long): Long { val n = System.nanoTime(); FrameWork.add(part, n - t0); return n }

    // ------------------------------------------------------------------ the trees far off (docs/STREAMING.md)

    /** Every tree look's views ([TreeImpostors]), baked on a worker once the ground is in. */
    @Volatile private var impAtlas: TreeImpostors.Atlas? = null
    private var impAtlasJob: java.util.concurrent.Future<*>? = null
    /** The atlas's packed picture, shared by every chunk's impostors (packed once). */
    private val impShare: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    private fun impostorStep() {
        if (!TreeImpostors.on || !groundBuilt || impAtlasJob != null) return
        val specs = chunks.values.flatMap { c -> c.treeList.map { it.first } }
        if (specs.isEmpty()) return
        // (when the workers have nothing else to do: the bake holds ~30 MB of pixels and their encoding while it runs, kept
        // off the build's busiest moments; the far trees wear their cards until then)
        impAtlasJob = pool.submit(TownWorkers.IDLE, "impostors", null, java.util.concurrent.Callable {
            val a = TreeImpostors.bake(specs)
            // packed for the GPU once, here, before any chunk's quads are packed (every one of them then finds it in
            // [impShare]: not several workers encoding the same 2048 x ~4000 picture at once); then its pixels go, a
            // one-int token taking their place as the packed picture's key (~30 MB of Java heap not kept for the town's life)
            val (packed, _) = StreetTextures.prepareShared(a.px, a.w, a.h, linear = false, impShare)
            impAtlas = if (packed.key?.mips == true) {
                val token = IntArray(1)
                impShare[token] = packed; impShare.remove(a.px)
                TreeImpostors.Atlas(a.w, a.h, token, a.at, a.half, a.tall, a.ref, a.texel, a.trees)
            } else a
        })
    }

    // ------------------------------------------------------------------ the lights far off (docs/STREAMING.md)

    /** The dots of light far off at night ([FarLights]): made once the far boxes and the lanterns are all in. */
    private var farLights: FarLights? = null
    private var farLightsDirty = true
    /** How many lights far off are drawn (reports). */
    val farLightCount: Int get() = farLights?.count ?: 0

    private fun farLightsFrame(pose: TownPose, dark: Float) {
        if (!farLightsOn || !groundBuilt || (farLod && !far.allReady)) return
        if (farLightsDirty) {
            farLightsDirty = false
            val fl = farLights ?: runCatching { FarLights(engine, scene, extraMaterial("far_lights")) }.getOrNull()?.also { farLights = it } ?: return
            val nw = if (farLod) far.lightCount else 0
            val nl = lanterns.size / 3
            val xyz = FloatArray((nw + nl) * 3); val rgb = IntArray(nw + nl); val lamp = BooleanArray(nw + nl)
            if (nw > 0) System.arraycopy(far.lights, 0, xyz, 0, nw * 3)
            for (i in 0 until nw) rgb[i] = WINDOW_LIGHT
            var n = nw
            for (i in 0 until nl) {
                if (lanterns[i * 3 + 1] < -1e4f) continue
                xyz[n * 3] = lanterns[i * 3]; xyz[n * 3 + 1] = lanterns[i * 3 + 1]; xyz[n * 3 + 2] = lanterns[i * 3 + 2]
                rgb[n] = LAMP_LIGHT; lamp[n] = true; n++
            }
            GpuAccount.scoped("far lights") { fl.set(xyz.copyOf(n * 3), rgb.copyOf(n), lamp.copyOf(n)) }
        }
        val fl = farLights ?: return
        val on = smooth((dark - .35f) / .5f)
        val detail = if (pose.range == Float.MAX_VALUE) Float.MAX_VALUE / 4f else pose.hazeFrom + FAR_PAST_HAZE
        fl.frame(pose, look, on, detail - 200f, FAR_LAMPS_FROM, debugHide and HIDE_FAR == 0)
    }

    private fun lamps(pose: TownPose, dark: Float): Int {
        val on = smooth((dark - .35f) / .35f)
        val lm = engine.lightManager
        if (!groundBuilt) return 0
        if (on <= 0f || lanterns.isEmpty()) {
            for (e in lampLights) lm.getInstance(e).takeIf { it != 0 }?.let { lm.setIntensity(it, 0f) }
            return 0
        }
        // the point looked at: on the ground along the look (the street view's building line; an aerial's centre)
        val t = if (pose.fy < -.05f) (pose.ey / -pose.fy) else (pose.cut + TownFrame.CUT_BACK)
        val tx = pose.ex + pose.fx * t; val tz = pose.ez + pose.fz * t
        val n = lanterns.size / 3
        val order = (0 until n).filter { i ->
            pose.sees(lanterns[i * 3], lanterns[i * 3 + 1], lanterns[i * 3 + 2], LAMP_REACH)
        }.sortedBy { i -> hypot(lanterns[i * 3] - tx, lanterns[i * 3 + 2] - tz) }
        var lit = 0
        for ((k, e) in lampLights.withIndex()) {
            val li = lm.getInstance(e).takeIf { it != 0 } ?: continue
            val i = order.getOrNull(k)
            if (i == null) { lm.setIntensity(li, 0f); continue }
            lm.setPosition(li, lanterns[i * 3], lanterns[i * 3 + 1] - 4f, lanterns[i * 3 + 2])
            // (the farthest of them dim, so one switching to another as the walk goes on does not pop)
            val rank = (1f - (k - (LAMP_LIGHTS - 4)).coerceAtLeast(0) / 4f).coerceIn(0f, 1f)
            lm.setIntensity(li, LAMP_LUMENS * on * rank)
            lit++
        }
        return lit
    }

    /**
     * The open world's camera against what could hide the player: the buildings and the yards' tall things (sheds, garages,
     * greenhouses, carports, play sets, tall hedges, trees: [tallYards]). Not one line but a cone of five, from the player's
     * chest, feet, head and either side (camera's right) to the eye at ([ex], [ey], [ez]): whatever really hides the player
     * (both the chest's and the head's lines through it; since 0.1.402's "buildings disappear too easily", not something
     * that only covers their feet or a side) is cut away next [frame] ([fadeBetween]), and so is anything within
     * [EYE_CLEAR] of the eye itself (a wall or a
     * sign beside the camera that the near plane would cut through). Something the player stands in (a porch's box, within
     * its bounds) does not count for the ray starting inside it. Returns how far along the chest's line the first building
     * in the way begins (a share of the way, 1: nothing); [eyeInside] is where that line enters a building the camera
     * itself would stand in (kept [pad] off its walls and roof; 1: none). Allocates nothing.
     */
    fun occlude(tx: Float, ty: Float, tz: Float, ex: Float, ey: Float, ez: Float, pad: Float = 12f, feet: Float = 0f): Float {
        var first = 1f
        eyeInside = 1f
        var blocked = false
        // the cone's five starts (the eye is the end of each): chest, feet, head, left and right of the chest
        val sdx = ex - tx; val sdz = ez - tz
        val gl = hypot(sdx, sdz).coerceAtLeast(1e-3f)
        val rx = -sdz / gl; val rz = sdx / gl
        val st = rayStarts
        st[0] = tx; st[1] = ty; st[2] = tz
        st[3] = tx; st[4] = (ty - 24f).coerceAtLeast(4f); st[5] = tz
        st[6] = tx; st[7] = ty + 8f; st[8] = tz
        st[9] = tx - rx * CONE_SIDE; st[10] = ty; st[11] = tz - rz * CONE_SIDE
        st[12] = tx + rx * CONE_SIDE; st[13] = ty; st[14] = tz + rz * CONE_SIDE
        val sx = ex - tx; val sy = ey - ty; val sz = ez - tz
        val len2 = (sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-6f)
        val reach = CONE_SIDE + EYE_CLEAR
        val lo = scratchLo; val hi = scratchHi; val o = scratchO; val d = scratchD
        for (s in slots) {
            s.occT = Float.MAX_VALUE
            val bb = s.bounds ?: continue
            // (quick: the slot's sphere against the segment, widened by the cone and the eye's clearance)
            val u = (((s.cx - tx) * sx + (s.cy - ty) * sy + (s.cz - tz) * sz) / len2).coerceIn(0f, 1f)
            val qx = tx + sx * u - s.cx; val qy = ty + sy * u - s.cy; val qz = tz + sz * u - s.cz
            val rr = s.r + pad + reach
            if (qx * qx + qy * qy + qz * qz > rr * rr) continue
            val pl = s.place
            // the eye in the slot's own frame: too near the box (or in it) cuts it away
            val elx = (ex - pl.ox) * pl.nz - (ez - pl.oz) * pl.nx
            val elz = (ex - pl.ox) * pl.nx + (ez - pl.oz) * pl.nz
            if (boxDistance(elx, ey, elz, bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]) < EYE_CLEAR) s.occT = .999f
            // (cut only when it hides the player: both the chest's and the head's lines through it; a building that only
            // covers their feet or a side, or one line of the two, stays: 075528 "buildings disappear too easily")
            var tc = Float.MAX_VALUE; var th = Float.MAX_VALUE
            for (j in 0..1) {
                val k = j * 2
                val px = st[k * 3]; val py = st[k * 3 + 1]; val pz = st[k * 3 + 2]
                o[0] = (px - pl.ox) * pl.nz - (pz - pl.oz) * pl.nx; o[1] = py; o[2] = (px - pl.ox) * pl.nx + (pz - pl.oz) * pl.nz
                d[0] = elx - o[0]; d[1] = ey - py; d[2] = elz - o[2]
                lo[0] = bb[0]; lo[1] = bb[1]; lo[2] = bb[2]; hi[0] = bb[3]; hi[1] = bb[4]; hi[2] = bb[5]
                val t0 = slab(o, d, lo, hi)
                if (j == 0) tc = t0 else th = t0
            }
            if (tc in 0f..1f && th in 0f..1f) {
                val t0 = max(tc, th)
                if (t0 < s.occT) s.occT = t0
                blocked = true
                if (tc < first) first = tc
            }
            // the camera inside it (kept the pad off): where the chest's line enters it, for a camera pulled in to the wall
            o[0] = (tx - pl.ox) * pl.nz - (tz - pl.oz) * pl.nx; o[1] = ty; o[2] = (tx - pl.ox) * pl.nx + (tz - pl.oz) * pl.nz
            d[0] = elx - o[0]; d[1] = sy; d[2] = elz - o[2]
            if (boxDistance(elx, ey, elz, bb[0] - pad, bb[1], bb[2] - pad, bb[3] + pad, bb[4] + pad, bb[5] + pad) <= 0f) {
                lo[0] = bb[0] - pad; lo[1] = bb[1]; lo[2] = bb[2] - pad; hi[0] = bb[3] + pad; hi[1] = bb[4] + pad; hi[2] = bb[5] + pad
                val t0 = slab(o, d, lo, hi)
                if (t0 >= 0f && t0 < eyeInside) eyeInside = t0
            }
        }
        // the stores' main blocks (world boxes, from the ground): as a building
        for (stD in storeDraws) {
            stD.occT = Float.MAX_VALUE
            val bk = stD.block ?: continue
            lo[0] = bk[0]; lo[1] = 0f; lo[2] = bk[1]; hi[0] = bk[2]; hi[1] = bk[4]; hi[2] = bk[3]
            if (boxDistance(ex, ey, ez, bk[0], 0f, bk[1], bk[2], bk[4], bk[3]) < EYE_CLEAR) stD.occT = .999f
            var tc = Float.MAX_VALUE; var th = Float.MAX_VALUE
            for (j in 0..1) {
                val k = j * 2
                o[0] = st[k * 3]; o[1] = st[k * 3 + 1]; o[2] = st[k * 3 + 2]
                d[0] = ex - o[0]; d[1] = ey - o[1]; d[2] = ez - o[2]
                val t0 = slab(o, d, lo, hi)
                if (j == 0) tc = t0 else th = t0
            }
            if (tc in 0f..1f && th in 0f..1f) {
                val t0 = max(tc, th)
                if (t0 < stD.occT) stD.occT = t0
                blocked = true
                if (tc < first) first = tc
            }
        }
        // the cinema (drawn by its own kit, not a slot's building): as a building, the eye in or by it cutting it whole
        cinemaOccT = Float.MAX_VALUE
        cinemaDraw?.let { cd ->
            val tc = cd.inWay(st[0], st[1], st[2], ex, ey, ez, EYE_CLEAR)
            val th = cd.inWay(st[6], st[7], st[8], ex, ey, ez, 0f)
            if (tc <= 0f) cinemaOccT = .999f
            else if (tc <= 1f && th <= 1f) { cinemaOccT = max(tc, th); blocked = true; if (tc < first) first = tc }
        }
        // the landmark models (their buildings, not their flat lots): as the cinema
        for (i in 0..landmarkDraws.size) {
            val lm = if (i < landmarkDraws.size) landmarkDraws[i] else depotDraw ?: continue
            lm.occT = Float.MAX_VALUE
            // the player inside it (a ceiling over them: the school's halls): cut away, Sims-style, only what of it stands
            // on the camera's side of them and above the ceiling; its floors and far walls stay, the rooms in view (the
            // owner's 100800, 0.1.848: "going inside just makes building disappear")
            val ceil = if (lm.under(tx, tz)) lm.ceilingOver(tx, tz, feet) else Float.NaN
            if (!ceil.isNaN()) {
                SeeThrough.cutaway(tx, tz, feet, ex, ez, ceil, lm.cutawayBuf)
                lm.cutaway = lm.cutawayBuf
                continue
            }
            lm.cutaway = null
            val tc = lm.inWay(st[0], st[1], st[2], ex, ey, ez, EYE_CLEAR)
            val th = lm.inWay(st[6], st[7], st[8], ex, ey, ez, 0f)
            // (the player on its grounds (its steps, its terrace, its plaza): it is the ground they stand on and all round
            // them, never cut away whole: the Night Keeper's plaza, gazebo and giant figure all went at once, its shadow
            // left on bare grass (the owner's 100614/100624, 0.1.848); what of it hides them, the see-through thins)
            if (lm.under(tx, tz)) { if (tc <= 1f && th <= 1f) blocked = true; continue }
            if (tc <= 0f) lm.occT = .999f
            else if (tc <= 1f && th <= 1f) { lm.occT = max(tc, th); blocked = true; if (tc < first) first = tc }
        }
        // the yards' tall things (world boxes): any of the cone's lines through one, or the eye near it, cuts its lot's
        val tb = tallBoxes
        tallHit.fill(false)
        var i = 0
        val n = tb.size / 5
        while (i < n) {
            val x0 = tb[i * 5]; val z0 = tb[i * 5 + 1]; val x1 = tb[i * 5 + 2]; val z1 = tb[i * 5 + 3]; val h = tb[i * 5 + 4]
            val li = tallLot[i]
            i++
            if (tallHit[li]) continue
            // (quick: the box's ground rect against the segment's, widened)
            if (max(x0, x1) < min(tx, ex) - reach || min(x0, x1) > max(tx, ex) + reach || z1 < min(tz, ez) - reach || z0 > max(tz, ez) + reach) continue
            if (boxDistance(ex, ey, ez, x0, 0f, z0, x1, h, z1) < EYE_CLEAR * .75f) { tallHit[li] = true; continue }
            lo[0] = x0; lo[1] = 0f; lo[2] = z0; hi[0] = x1; hi[1] = h; hi[2] = z1
            // (only the chest's and the head's lines, and both through it: a hedge, a shed or a play set that only covers
            // the player's feet or a side stays; a line that starts inside it (the player under a tree's crown or a
            // carport's roof) counts as through it when it has a roof)
            var through = 0
            for (j in 0..1) {
                val k = j * 2
                o[0] = st[k * 3]; o[1] = st[k * 3 + 1]; o[2] = st[k * 3 + 2]
                d[0] = ex - o[0]; d[1] = ey - o[1]; d[2] = ez - o[2]
                val t0 = slab(o, d, lo, hi)
                if (t0 < 0f) { if (tallRoof[i - 1]) through++ }
                else if (t0 <= 1f) through++
            }
            if (through == 2) { tallHit[li] = true; blocked = true }
        }
        // the extras (the farms' buildings)
        for (x in extras) if (x.occlude(st, ex, ey, ez)) blocked = true
        // the woods' merged trees (no box of their own to cut): a crown across the chest's or the head's line hides the
        // player, and only then does the see-through open ([hidden]); the feet's line behind a bush does not count. Each
        // such crown is faded whole ([SeeThrough.crown])
        SeeThrough.beginCrowns()
        for (c in chunks.values) {
            val tc = c.trees ?: continue
            val a = tc.blocks(st[0], st[1], st[2], ex, ey, ez, crowns = true)
            val b = tc.blocks(st[6], st[7], st[8], ex, ey, ez, crowns = true)
            if (a || b) blocked = true
        }
        hidden = blocked
        return first.coerceIn(0f, 1f)
    }

    /** Where the ray from [o] along [d] (0..1 the segment) enters the box [lo]..[hi]: -1 if it starts inside, MAX_VALUE if it misses. */
    private fun slab(o: FloatArray, d: FloatArray, lo: FloatArray, hi: FloatArray): Float {
        var t0 = -Float.MAX_VALUE; var t1 = Float.MAX_VALUE
        for (k in 0..2) {
            if (abs(d[k]) < 1e-6f) { if (o[k] < lo[k] || o[k] > hi[k]) return Float.MAX_VALUE; continue }
            var a = (lo[k] - o[k]) / d[k]; var b = (hi[k] - o[k]) / d[k]
            if (a > b) { val t = a; a = b; b = t }
            if (a > t0) t0 = a
            if (b < t1) t1 = b
        }
        if (t1 < max(t0, 0f) || t0 > 1f) return Float.MAX_VALUE
        return if (t0 < 0f) -1f else t0
    }

    /** How far ([x], [y], [z]) is from the box ([x0], [y0], [z0])..([x1], [y1], [z1]) (0 inside). */
    private fun boxDistance(x: Float, y: Float, z: Float, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Float {
        val dx = max(max(x0 - x, 0f), x - x1); val dy = max(max(y0 - y, 0f), y - y1); val dz = max(max(z0 - z, 0f), z - z1)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private val rayStarts = FloatArray(15)
    private val scratchLo = FloatArray(3); private val scratchHi = FloatArray(3)
    private val scratchO = FloatArray(3); private val scratchD = FloatArray(3)

    /**
     * The yards' things tall enough to hide the player (world boxes: x0, z0, x1, z1, height, five floats each), the yard
     * patch each is ([tallLot]: an index into [tallKeys]; every tall thing is a patch of its own, [YardPatch.tallId]) and
     * whether it has a roof or a crown over head height ([tallRoof]). [occlude] cuts a tall thing when it is in the way;
     * the rest of its lot (its fences, its lawn, its other sheds and trees) stays.
     */
    private val tallKeys: Array<String>
    private val tallLot: IntArray
    private val tallBoxes: FloatArray
    private val tallRoof: BooleanArray
    private val tallHit: BooleanArray
    init {
        val keys = ArrayList<String>(); val lots = ArrayList<Int>(); val boxes = ArrayList<Float>(); val roofs = ArrayList<Boolean>()
        fun add(lot: String, items: List<com.lifemax.rpg.feature.habits.domain.TownYardItem>) {
            for ((k, it) in items.withIndex()) {
                val h = tallHeight(it.kind, it.zone)
                if (h <= 0f) continue
                val r = it.rect
                boxes += r[0]; boxes += r[1]; boxes += r[2]; boxes += r[3]; boxes += h
                lots += keys.size; keys += YardPatch.tallId(lot, k)
                roofs += it.kind in ROOFED
            }
        }
        for (y in yards.yards) add(y.lot, y.items)
        for (l in yards.lanes) add(l.id, l.items)
        tallKeys = keys.toTypedArray(); tallLot = lots.toIntArray(); tallBoxes = boxes.toFloatArray(); tallRoof = roofs.toBooleanArray(); tallHit = BooleanArray(keys.size)
    }

    /** (Debug) the buildings and stores within [r] of the eye ([ex], [ey], [ez]): which, how near, whether cut. */
    fun near(ex: Float, ey: Float, ez: Float, r: Float = 80f): String {
        val out = ArrayList<String>()
        for (s in slots) {
            val bb = s.bounds ?: continue
            val pl = s.place
            val elx = (ex - pl.ox) * pl.nz - (ez - pl.oz) * pl.nx
            val elz = (ex - pl.ox) * pl.nx + (ez - pl.oz) * pl.nz
            val d = boxDistance(elx, ey, elz, bb[0], bb[1], bb[2], bb[3], bb[4], bb[5])
            if (d < r) out += "${s.b.sprite} (${s.b.kind}) %.0f off, occT %.3f, occluded ${s.occluded}, fade %.2f, shown ${s.shown}".format(d, s.occT, s.occFade)
        }
        for (st in storeDraws) { val bk = st.block ?: continue
            val d = boxDistance(ex, ey, ez, bk[0], 0f, bk[1], bk[2], bk[4], bk[3]); if (d < r) out += "store ${st.site.sprite} %.0f off".format(d) }
        return out.joinToString("; ").ifEmpty { "nothing" }
    }

    /**
     * Whether the last [occlude] found the player hidden from the eye: a building, a store, a yard's tall thing or a tree's
     * crown across the cone's lines (the feet's line alone does not count). The see-through cone ([SeeThrough]) opens only then.
     */
    var hidden = false; private set

    /** Where the line of the last [occlude] enters a building the camera would stand in (a share of the way; 1: none). */
    var eyeInside = 1f; private set

    /** Rings swinging bells on the landmark with [sprite] (or every landmark with bells when [sprite] is null). */
    fun ringLandmarkBells(sprite: String? = null, swings: Int = 5) {
        for (i in 0 until landmarkDraws.size) {
            val lm = landmarkDraws[i]
            if (sprite == null || lm.sprite == sprite) lm.ringBells(swings = swings)
        }
        depotDraw?.let { lm -> if (sprite == null || lm.sprite == sprite) lm.ringBells(swings = swings) }
    }

    /**
     * The buildings the last [occlude]'s cone passes through nearer the player than [t] (a share of the way), those near
     * the eye, and the yards' tall things it passes, are cut away next [frame] (eased out over [OCCLUDE_SECONDS]).
     */
    fun fadeBetween(t: Float) {
        for (s in slots) s.occluded = s.occT < t
        for (s in storeDraws) s.occluded = s.occT < t
        cinemaDraw?.occluded = cinemaOccT < t
        for (lm in landmarkDraws) lm.occluded = lm.occT < t
        depotDraw?.let { it.occluded = it.occT < t }
        for (x in extras) x.fadeBetween(t)
        occludedLots.clear()
        for (k in tallKeys.indices) if (tallHit[k]) occludedLots.add(tallKeys[k])
    }
    private val occludedLots = HashSet<String>()
    private var cinemaOccT = Float.MAX_VALUE

    /** The street view's cut for something whose middle is at ([x], [z]): 1 beyond the cut, fading to 0 nearer the eye. */
    private fun fadeOf(pose: TownPose, x: Float, z: Float): Float =
        smooth((pose.groundX * (x - pose.ex) + pose.groundZ * (z - pose.ez) - pose.cut + TownFrame.CUT_FADE) / TownFrame.CUT_FADE)

    /** As [TownPose.rangeFade], to [GREEN_PAST_RANGE] past the view's range: the trees' cards with the far boxes up. */
    private fun farFade(pose: TownPose, x: Float, y: Float, z: Float, r: Float): Float {
        val t = ((pose.range + GREEN_PAST_RANGE - (pose.distance(x, y, z) - r * .5f)) / TownFrame.RANGE_FADE).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun aim(p: TownPose) {
        val k = (p.near / p.fl).toDouble()
        // (the far boxes are drawn to the far end of the world: the far plane reaches them once any are up)
        val farPlane = max(if (p.range < Float.MAX_VALUE && far.readyCount > 0) max(p.far, TownFar.REACH) else p.far, worldReach)
        camera.setProjection(Camera.Projection.PERSPECTIVE, -p.cx * k, (p.w - p.cx) * k, -(p.h - p.cy) * k, p.cy * k, p.near.toDouble(), farPlane.toDouble())
        camera.lookAt(p.ex.toDouble(), p.ey.toDouble(), p.ez.toDouble(), (p.ex + p.fx).toDouble(), (p.ey + p.fy).toDouble(), (p.ez + p.fz).toDouble(),
            p.ux.toDouble(), p.uy.toDouble(), p.uz.toDouble())
    }

    /**
     * The sun (or moon) as the street lights its view (the classic's key: across the view, down, out of the fronts
     * towards the viewer, turned with the camera), and the sky light from above and the camera's side.
     */
    private fun light(p: TownPose, hour: Float, sky: com.lifemax.rpg.feature.habits.presentation.scene.SkyColors) {
        val key = SceneLight.key(hour)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        val gx = p.groundX; val gz = p.groundZ
        // the view's right on the ground (-gz, gx); out of the fronts towards the viewer is -look
        var dx = key.across * -gz - key.out * gx
        val dy = -key.down
        var dz = key.across * gx - key.out * gz
        if (p.open) {
            // the open world: the sun (the moon at night) stands still in the world whichever way the camera looks, at its
            // azimuth for the hour (east at six, south at noon, west at six), as high as the street's key puts it
            val az = Math.toRadians(com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.sunAzimuth(hour, key.cool > .5f).toDouble())
            val across = hypot(key.across, key.out)
            dx = -sin(az).toFloat() * across; dz = cos(az).toFloat() * across
        }
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        lm.setDirection(li, dx / dl, dy / dl, dz / dl)
        val warm = (1f - key.down).coerceIn(0f, 1f)
        val sc = if (key.cool > .5f) Color(0xFF9DB4FF) else lerp(Color(0xFFFFF6E8), Color(0xFFFFB070), warm * warm)
        lm.setColor(li, lin(sc.red), lin(sc.green), lin(sc.blue))
        lm.setIntensity(li, SUN * key.strength * Math.PI.toFloat())
        lm.setShadowCaster(li, key.cool < .5f && key.strength > .05f)
        // (the open world's shadows reach a fixed way past the player: their cascades are not stretched over a wide view)
        // (0: as far as the view's own reach, not the far boxes' far plane)
        val shadowFar = if (p.shadowFar > 0f) p.shadowFar else p.far
        // (the thermal governor's cap on the cascades, docs/HEAT.md: fewer only while the phone is warm, back when cool)
        val cascadesNow = min(shadowCascades, TownQuality.heatCascades.coerceIn(1, 4))
        if (abs(shadowFar - shadowFarSet) > 10f || cascadesNow != cascadesSet) {
            shadowFarSet = shadowFar
            cascadesSet = cascadesNow
            shadowOptions.shadowFar = shadowFar
            shadowOptions.shadowCascades = cascadesNow
            shadowOptions.cascadeSplitPositions = TownQuality.splits(cascadesNow)
            lm.setShadowOptions(li, shadowOptions)
        }
        val tone = sky.sky.lastOrNull() ?: Color.White
        val night = sqrt(sky.dark.coerceIn(0f, 1f))
        var level = lerp(Color(AMB, AMB, AMB), Color(.16f, .19f, .30f), night)
        val low = smooth((.75f - key.down) / .35f)
        val back = (low * (1f - key.cool) * (key.out * 2.5f).coerceIn(0f, 1f) * (key.strength * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        level = lerp(level, Color(level.red * .42f, level.green * .36f, level.blue * .44f), back)
        val dusk = 1f - abs(sky.dark - .5f) * 2f
        val t = lerp(level, Color(tone.red * level.red * 1.1f, tone.green * level.green * 1.1f, tone.blue * level.blue * 1.1f), .25f + .3f * dusk)
        val r = t.red; val g = t.green; val b = t.blue
        if (ibl == null || abs(r - iblColor[0]) + abs(g - iblColor[1]) + abs(b - iblColor[2]) > 2e-3f) {
            val toward = .18f; val up = -.06f
            sh[0] = r; sh[1] = g; sh[2] = b
            sh[3] = r * up; sh[4] = g * up; sh[5] = b * up
            sh[6] = -toward * r; sh[7] = -toward * g; sh[8] = -toward * b
            sh[9] = 0f; sh[10] = 0f; sh[11] = 0f
            ibl?.let { engine.destroyIndirectLight(it) }
            ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
            iblColor[0] = r; iblColor[1] = g; iblColor[2] = b
        }
        iblRot[0] = gz; iblRot[1] = 0f; iblRot[2] = -gx
        iblRot[3] = 0f; iblRot[4] = 1f; iblRot[5] = 0f
        iblRot[6] = gx; iblRot[7] = 0f; iblRot[8] = gz
        ibl?.setRotation(iblRot)
    }

    private fun haze(p: TownPose, sky: com.lifemax.rpg.feature.habits.presentation.scene.SkyColors) {
        // (the open world's sky, as the stage lays it, ends at the horizon in its glow (Backdrop's dome and glow band): the
        // haze takes that colour, so the far ground, woods and far boxes meet the sky with no line; the street view's sky
        // ends in its lowest band)
        // (the glow is drawn over the sky's lowest band at its own alpha: at night a faint orange of the town's lights over
        // a dark sky, not the orange itself, which made midnight's haze a sunset)
        val low = sky.sky.lastOrNull() ?: return
        val tone = if (p.open) lerp(low, sky.glow.copy(alpha = 1f), sky.glow.alpha) else low
        // (with the town's own sky: its colour under the horizon, the weather's greys and mist in it, already linear; the
        // street view's, its horizon band)
        val skyTone = if (skySurface == null) null else if (p.open) skyState.belowHorizonLin else skyState.horizonLin
        fog.enabled = !hazeOff
        fog.distance = p.hazeFrom
        fog.height = 0f
        if (p.open && worldReach > 0f && worldBaseM != null) {
            // the world round the town (W5a): the air's own haze, by the distance through it and thinning with height over
            // the aerosols' scale height ([air]: the weather's), from sea level; nothing of the town's thick ground haze, so a
            // clear day shows the land to the horizon and a far mountain is hazed as the ground at its distance is
            val a = air
            val u = worldUnitsPerMetre
            fog.distance = 0f
            fog.height = (-(worldBaseM ?: 0.0) * u).toFloat()
            fog.density = (a.extinction / u).toFloat() * (1f - .3f * sky.dark)
            fog.heightFalloff = (1.0 / (a.scaleHeightM * u)).toFloat()
            fog.maximumOpacity = 1f
            fog.inScatteringStart = 0f
            fog.inScatteringSize = -1f
            fog.fogColorFromIbl = false
            fog.color = skyTone?.copyOf() ?: floatArrayOf(lin(tone.red), lin(tone.green), lin(tone.blue))
            view.fogOptions = fog
            return
        }
        if (p.open) {
            // a ground haze, thinning with height; its density at the ground set so that a line from the eye down to the
            // far ground meets [TownPose.haze] on average whatever the camera's height (a camera up high does not see the
            // ground's edge through a thinner haze), while what stands tall above that line meets less of it
            val f = TownFrame.OPEN_HAZE_FALLOFF
            val fe = (f * p.ey).coerceAtLeast(1e-3f)
            val mean = (1f - kotlin.math.exp(-fe)) / fe
            fog.density = p.haze / mean * (1f - .4f * sky.dark)
            fog.heightFalloff = f
        } else {
            fog.density = p.haze * (1f - .4f * sky.dark)
            fog.heightFalloff = 1e-4f
        }
        // (the street view stops drawing where the haze is thick: there it takes everything, the ground too)
        fog.maximumOpacity = if (p.range < Float.MAX_VALUE) 1f else .85f
        fog.inScatteringStart = 0f
        fog.inScatteringSize = -1f
        fog.fogColorFromIbl = false
        fog.color = skyTone?.copyOf() ?: floatArrayOf(lin(tone.red), lin(tone.green), lin(tone.blue))
        view.fogOptions = fog
    }

    private fun lin(c: Float): Float = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    // ------------------------------------------------------------------ people

    /**
     * Stands [n] of [figures] ([com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.people]) in the last [frame]'s
     * view, wearing [sheets] (our character sheets); returns how many were drawn.
     */
    /** (Measuring) no haze. */
    var hazeOff = false

    /** The air's haze with the world round the town (WORLD_GEN.md W5a; the weather sets it): a clear day by default. */
    var air = com.lifemax.rpg.feature.habits.domain.WorldAir.CLEAR
    /** The town's ground height above sea level (m) and street units a metre, once the world is attached: the haze's sea level. */
    internal var worldBaseM: Double? = null

    /**
     * The horizon ring past the far view (docs/STREAMING.md step 4): the sky draws [p] where nothing nearer is (the engine's
     * thread; WorldFar bakes it on a worker and hands it over); null takes it away.
     */
    internal fun horizon(p: com.lifemax.rpg.feature.habits.domain.HorizonPanorama?) {
        val ss = skySurface ?: return
        if (p == null) { ss.setHorizon(null); return }
        ss.setHorizon(p.pixels, com.lifemax.rpg.feature.habits.domain.HorizonPanorama.W, com.lifemax.rpg.feature.habits.domain.HorizonPanorama.H,
            p.r0, p.r1, com.lifemax.rpg.feature.habits.domain.HorizonPanorama.ELEV0, com.lifemax.rpg.feature.habits.domain.HorizonPanorama.STEP)
    }

    /** The horizon ring's texture (bytes; 0 without one). */
    internal val horizonBytes: Long get() = skySurface?.horizonBytes ?: 0L
    internal var worldUnitsPerMetre = 1.0
    /** (Measuring) what is left out of the frame: [HIDE_FAR] and the rest, or-ed (the preview's GPU cost breakdown). */
    var debugHide = 0

    /** A townsperson's sheet seen for the first time is read on a worker (they show a frame or two later); false: in the frame (a still preview). */
    var sheetsOnWorker = true

    fun people(figures: List<com.lifemax.rpg.feature.habits.presentation.scene.TownFigure>, n: Int, sheets: List<androidx.compose.ui.graphics.ImageBitmap>): Int {
        val pose = lastPose ?: return 0
        val lm = engine.lightManager
        lm.getDirection(lm.getInstance(sun), sunDir)
        crowd.warmPool = if (sheetsOnWorker) pool else null
        crowd.update(pose, look, figures, n, sheets, sunDir) { x, z -> if (pose.cut > 0f) fadeOf(pose, x, z) else 1f }
        return crowd.drawn
    }

    /** Where world point ([x], [y], [z]) falls on the screen of the last [frame] (pixels from its top left), or null behind the eye. */
    fun project(x: Float, y: Float, z: Float): androidx.compose.ui.geometry.Offset? {
        val p = lastPose ?: return null
        val dx = x - p.ex; val dy = y - p.ey; val dz = z - p.ez
        val zc = p.fx * dx + p.fy * dy + p.fz * dz
        if (zc < 1f) return null
        val xc = p.rx * dx + p.ry * dy + p.rz * dz
        val yc = p.ux * dx + p.uy * dy + p.uz * dz
        return androidx.compose.ui.geometry.Offset(p.cx + p.fl * xc / zc, p.cy - p.fl * yc / zc)
    }

    // ------------------------------------------------------------------ teardown

    fun destroy() {
        closed = true
        synchronized(queueLock) { queueLock.notifyAll() }
        Graveyard.drainAll()
        val em = EntityManager.get()
        crowd.destroy()
        cinemaDraw?.destroy()
        skirt.destroy()
        for (st in storeDraws) st.destroy()
        for (lm in landmarkDraws) lm.destroy()
        depotDraw?.destroy()
        for (x in extras) x.destroy()
        railDraw?.destroy()
        farLights?.destroy(); farLights = null
        for (s in slots) {
            val b = s.built ?: continue
            for (e in listOf(b.entity, b.proxy)) if (e != 0) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            for (mi in b.mis) engine.destroyMaterialInstance(mi)
            b.twins?.idle?.forEach { engine.destroyMaterialInstance(it) }
            for (mi in b.proxyMis) engine.destroyMaterialInstance(mi)
            b.geo.free(engine)
            s.built = null
            s.held.clear()
        }
        for (f in yardIn.values) f.cancel(true)
        yardIn.clear()
        far.destroy()
        pool.shutdownNow()
        ground.destroy(); yardLayer.destroy(); ticks.clear()
        for (c in chunks.values) c.destroy()
        chunks.clear()
        for (e in lampLights) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
        for (pt in patches.values) pt.layer.destroy()
        patches.clear()
        textures.destroy(all = true)
        scene.remove(sun); engine.destroyEntity(sun); em.destroy(sun)
        ibl?.let { engine.destroyIndirectLight(it) }; ibl = null
        Cutouts.unregister(lit); Cutouts.unregister(proxyMat)
        engine.destroyMaterial(lit); engine.destroyMaterial(proxyMat)
        litOpaque?.let { engine.destroyMaterial(it) }; proxyOpaque?.let { engine.destroyMaterial(it) }
        for (m in extraMaterials.values) engine.destroyMaterial(m)
        extraMaterials.clear()
        engine.destroyColorGrading(grading)
        skySurface?.destroy(); skyMat?.let { engine.destroyMaterial(it) }
        val ce = camera.entity
        engine.destroyCameraComponent(ce); em.destroy(ce)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }

    companion object {
        /** The town draws its own sky ([SkySurface]); off: see-through over the stage's 2D Backdrop, as before 2026-11-24. */
        @Volatile var sky3d = true
        /** The railroad's hand-made depot model (its catalogue kind), and where it stands: its platform's edge [DEPOT_PLATFORM_M]
         * behind its origin, [DEPOT_EDGE_M] from the track's centre (metres). */
        const val DEPOT_KIND = "landmark_glb_premier_railroad_depot"
        const val DEPOT_PLATFORM_M = 16.14f
        const val DEPOT_EDGE_M = 1.75f
        /**
         * What is in the open world camera's way is cut away (kept: 0, not a screen-door haze over the view), over this many
         * seconds out and back.
         */
        const val OCCLUDED = 0f
        /** The buildings switch to their far boxes this far past where the haze starts (street units from the eye to the cell). */
        const val FAR_PAST_HAZE = 200f
        /** The apron past the world's edge: how far below the ground it lies, and its colour (the grass's mean). */
        const val SKIRT_DROP = .6f
        const val SKIRT_GRASS = 0xFF468E52.toInt()
        /** [debugHide]'s parts. */
        const val HIDE_FAR = 1; const val HIDE_BUILDINGS = 2; const val HIDE_YARDS = 4; const val HIDE_GREEN = 8; const val HIDE_GROUND = 16
        /** With the far boxes up, the trees' cards are drawn this far past the view's range (street units), into thick haze. */
        const val GREEN_PAST_RANGE = 2000f
        /** How long a chunk's greenery takes to cross over between its cards and its 3D trees (s). */
        const val GREEN_SWITCH_SECONDS = .35f
        /**
         * Streaming (docs/STREAMING.md, [TownStream]): only what is near the camera and the player is resident in full,
         * everything else as its far form. Off (a preview's -Dstream=0, the whole-capital budget test): the town is held
         * whole, as before.
         */
        @JvmStatic var stream = true
        /** A tree impostor's place (the quads are packed in the world already). */
        private val IMP_AT = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
        /** The lights far off at night ([FarLights]); a preview turns them off to compare (-DfarLights=0). */
        @JvmStatic var farLightsOn = true
        /** The lit windows' and the street lamps' colour far off (sRGB). */
        const val WINDOW_LIGHT = 0xFFC36A
        const val LAMP_LIGHT = 0xFFE0A8
        /** The street lamps show as dots from this far from the eye (street units): the nearest have real lights, the lanterns glow near. */
        const val FAR_LAMPS_FROM = 700f
        /** Whether towns made from now on draw their far boxes ([TownFar]); a preview turns it off to compare (-DtownFar=0). */
        @JvmStatic var farLod = true
        const val OCCLUDE_SECONDS = .2f
        /** The camera's cone round the player (street units either side of the chest), and how near the eye something is cut away. */
        const val CONE_SIDE = 12f
        const val EYE_CLEAR = 60f

        /** The tall things a player can stand under (a crown, a roof on posts): hidden by them from inside too. */
        private val ROOFED = setOf("tree", "fruitTree", "carport", "gazebo")
        /** How tall a yard's thing is that can hide the player from the open world's camera (street units; 0: too low to). */
        fun tallHeight(kind: String, zone: String): Float = when (kind) {
            "garage" -> 42f
            "carport" -> 34f
            "shed", "bikeShed", "greenhouse", "gazebo" -> 32f
            "tree", "fruitTree" -> 60f
            "playSet" -> 28f
            "swing" -> 26f
            "hedge" -> if (zone == "front") 0f else 30f
            else -> 0f
        }
        /** The sun's and the sky light's strength by day (the street's). */
        private const val SUN = .45f
        private const val AMB = .8f
        /** A row's furniture and props are chunked in stretches this long; the edge's greenery in squares this big. */
        const val CHUNK = 800f
        const val CELL = 2000f
        /** How many lanterns light at night (the nearest), how far their light reaches and how strong (lumens). */
        const val LAMP_LIGHTS = 12
        /** How near (street units) a broken street thing's drawn place must be to the shape the cars broke ([hideProp]). */
        const val HIDE_REACH = 40f
        /**
         * How many bytes of made-ready ground the workers may queue ahead of the engine's thread's uploads ([offer]): a
         * few frames' uploads (the 14 ms loading budget takes some 10-20 MB a frame on the desktop, the 4 ms playing budget less), and a small part of
         * the phone's Java heap however many cores bake.
         */
        const val QUEUE_BYTES = 32L shl 20
        const val BP_FAR = 0; const val BP_STREAM = 1; const val BP_SITES = 2; const val BP_EXTRAS = 3; const val BP_BUILDINGS = 4; const val BP_CHUNKS = 5
        val BUILD_PARTS = arrayOf("far cells", "streaming", "sites", "extras", "buildings", "chunks")
        const val LAMP_REACH = 260f
        const val LAMP_LUMENS = 60000f
        /** How long the windows' night light may take a frame (ns): a building's first is a whole picture. */
        const val NIGHT_BUDGET_NS = 2_000_000L
    }
}
