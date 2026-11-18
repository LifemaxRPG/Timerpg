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
    /** The street view cuts away what stands nearer than this short of the building line it looks at (past the road's middle). */
    const val CUT_BACK = 110f
    /** Over how many street units a cut building fades. */
    const val CUT_FADE = 70f
}

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
    shadowMap: Int = 4096,
) {
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val lit: Material = load("street_lit", materialBytes)
    private val proxyMat: Material = load("shadow_proxy", materialBytes)
    private val textures = StreetTextures(engine, srgb = true)
    private val meshes = BuildingMeshes(engine, lit, proxyMat, textures)
    private val sun: Int = EntityManager.get().create()
    private var ibl: IndirectLight? = null
    private val iblColor = FloatArray(3) { -1f }
    private val iblRot = FloatArray(9)
    private val sh = FloatArray(12)
    private val fog = View.FogOptions()
    private val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)

    /** Where the yards come from: the yard kit ([YardKitBuilder]), or [PlaceholderYards] (flat colours) to check the layout. */
    var yardBuilder: YardBuilder = YardKitBuilder
    private val ticks = ArrayList<(Double) -> Unit>()

    private val ground = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
    private val yardLayer = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
    /** The yards a lot or a lane at a time ([YardPatch]): culled with the view, cut with their house in the street view. */
    private inner class Patch(val p: YardPatch) {
        val layer = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
        val slot: Slot? = p.sprite?.let { sp -> slots.firstOrNull { it.b.sprite == sp } }
    }
    private val patches = LinkedHashMap<String, Patch>()

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
        /** Its 3D trees packed and waiting ([buildNear] uploads them the first time it comes close). */
        val nearTodo = ArrayList<Pair<PropLayer.Packed, Boolean>>()
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
        fun buildNear() {
            if (near != null) return
            val l = PropLayer(engine, scene, lit, proxyMat, sharedTextures = textures, cull = true)
            for ((p, casts) in nearTodo) l.upload(p, casts)
            near = l
        }
        fun destroy() { base.destroy(); cards.destroy(); near?.destroy() }
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
    private val sunDir = FloatArray(3)

    /** One building of the town: where it stands and, once built, its renderables. */
    inner class Slot(val row: Row, val b: Placement, val place: TownPlace) {
        internal var built: BuiltBuilding? = null
        val isBuilt: Boolean get() = built != null
        /** A sphere round it in the world (its footprint's middle, a guess at its height until built). */
        var cx = 0f; var cy = 40f; var cz = 0f; var r = 0f
        var missing = false
        var shown = false

        init {
            val c = place.world(0f, b.setback + b.depth / 2f)
            cx = c.x; cz = c.z
            r = hypot(b.width / 2f, b.depth / 2f + b.setback / 2f) + 40f
        }
    }

    val slots: List<Slot> = town.rows.flatMap { row -> row.buildings.map { Slot(row, it, TownPlace.of(row, it)) } }

    /** The town's pictures: textures made, their texels, and how many asks were answered by one already made. */
    val textureStats: Triple<Int, Long, Int> get() = Triple(textures.madeCount, textures.texels, textures.shared)

    /** How long the ground took (ms), the buildings so far (ms), and how many are left to build. */
    var groundMs = 0f; private set
    var buildingsMs = 0f; private set
    val pending: Int get() = slots.count { it.built == null && !it.missing }
    var stats = TownStats(slots.size, 0, 0, 0, 0, 0, 0); private set
    /** Each batch [buildNearest] made: how many it built and how long it took (ms). */
    val batches = ArrayList<Pair<Int, Float>>()

    private val xf = FloatArray(16)
    private val look = FloatArray(16)
    private val m12 = FloatArray(12)
    private val tmp = FloatArray(16)

    init {
        view.scene = scene
        view.camera = camera
        view.dithering = View.Dithering.NONE
        view.antiAliasing = View.AntiAliasing.NONE
        view.sampleCount = 1
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true
        view.shadowType = View.ShadowType.PCF
        camera.setExposure(1f)
        val shadow = LightManager.ShadowOptions().apply {
            mapSize = shadowMap
            shadowCascades = 4
            cascadeSplitPositions = floatArrayOf(.08f, .2f, .45f)
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
        val buf = ByteBuffer.allocateDirect(b.size).put(b).flip()
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
        /** Water to shimmer: its mesh (the shimmer steps by its picture's size). */
        val water: PropMesh? = null,
        /** The extent of what it places (x0, z0, x1, z1), its mesh's reach and height, for a chunk's sphere. */
        val extent: FloatArray? = null,
    )

    private val jobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var failure: Throwable? = null
    @Volatile private var lampSpots: List<FloatArray>? = null
    private var groundStarted = 0L
    /** The workers: every core but one (the engine's thread keeps its own); daemon threads, so a quit is never held. */
    private val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(
        max(2, Runtime.getRuntime().availableProcessors() - 1),
    ) { r -> Thread(r, "town-build").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 } }
    /** How long the workers took to make the ground ready (ms), and the engine's thread to upload it (ms, all batches). */
    var groundPrepareMs = 0f; private set
    var groundUploadMs = 0f; private set
    /** The longest single upload step [uploadGround] took (ms). */
    var groundUploadWorst = 0f; private set

    /**
     * Starts making the ground, river, bridges, greenery, furniture, street props and yards on worker threads (meshes
     * built, vertices packed, pictures packed and keyed: no engine call). Returns at once; then call [uploadGround] each
     * frame until it returns true.
     */
    fun startGround() {
        if (groundStarted != 0L || groundBuilt) return
        groundStarted = System.nanoTime()
        running.set(2)
        pool.execute { work { prepareGround() } }
        pool.execute { work { prepareYards() } }
    }

    private fun work(f: () -> Unit) {
        try { f() } catch (t: Throwable) { failure = t } finally {
            if (running.decrementAndGet() == 0) groundPrepareMs = (System.nanoTime() - groundStarted) / 1e6f
        }
    }

    /** (A worker) the ground's pieces, the rows' furniture, greenery and props, chunked and packed. */
    private fun prepareGround() {
        val tg = TownGround(town, art)
        for (p in tg.pieces()) {
            val an = p.anchors
            if (an == null) { PropLayer.Packing.pack(p.mesh, p.at)?.let { jobs += Job(it, p.casts, p.tiled) }; continue }
            val by = LinkedHashMap<String, Pair<ChunkSpec, MutableList<FloatArray>>>()
            for ((i, a) in p.at.withIndex()) { val sp = specOf(an[i], a[0], a[1]); by.getOrPut(sp.key) { sp to ArrayList() }.second += a }
            for ((sp, at) in by.values) {
                val ext = extent(at, p.near ?: p.mesh)
                PropLayer.Packing.pack(p.mesh, at)?.let { jobs += Job(it, p.casts, p.tiled, sp, part = if (p.near != null) 1 else 0, extent = ext) }
                if (p.near != null) PropLayer.Packing.pack(p.near, at)?.let { jobs += Job(it, p.casts, false, sp, part = 2, extent = ext) }
            }
        }
        val by = LinkedHashMap<String, Pair<ChunkSpec, MutableList<WorldProp>>>()
        for ((a, wp) in tg.anchoredProps()) { val sp = specOf(a, wp.x, wp.z); by.getOrPut(sp.key) { sp to ArrayList() }.second += wp }
        for ((sp, list) in by.values) {
            var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (wp in list) { x0 = min(x0, wp.x); z0 = min(z0, wp.z); x1 = max(x1, wp.x); z1 = max(z1, wp.z) }
            val ext = floatArrayOf(x0, z0, x1, z1, 40f, 70f)
            for ((packed, casts, _) in PropLayer.Packing.packProps(list)) jobs += Job(packed, casts, false, sp, part = 0, extent = ext)
        }
        lampSpots = tg.lampSpots.toList()
    }

    /** (A worker) the yards, baked lot by lot on every core and packed. */
    private fun prepareYards() {
        val sink = object : YardSink {
            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?, water: Boolean): MaterialInstance? {
                PropLayer.Packing.pack(mesh, at)?.let { jobs += Job(it, casts, tiled, patch = patch, yard = true, water = if (water) mesh else null) }
                return null
            }
            override fun props(list: List<WorldProp>) {
                for ((packed, casts, _) in PropLayer.Packing.packProps(list)) jobs += Job(packed, casts, false, yard = true)
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
        failure?.let { throw IllegalStateException("building the town's ground failed", it) }
        val t0 = System.nanoTime()
        while (true) {
            val j = jobs.poll() ?: break
            upload(j)
            if ((System.nanoTime() - t0) / 1e6f >= budgetMs) break
        }
        val ms = (System.nanoTime() - t0) / 1e6f
        groundUploadMs += ms
        groundUploadWorst = max(groundUploadWorst, ms)
        if (running.get() == 0 && jobs.isEmpty()) {
            failure?.let { throw IllegalStateException("building the town's ground failed", it) }
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
            groundMs = (System.nanoTime() - groundStarted) / 1e6f
        }
        return groundBuilt
    }

    private fun upload(j: Job) {
        val sp = j.chunk
        if (sp != null) {
            val c = chunks.getOrPut(sp.key) { Chunk(sp.refX, sp.refZ, sp.byRow) }
            j.extent?.let { c.take(it) }
            when (j.part) {
                0 -> c.base.upload(j.packed, j.casts, j.tiled)
                1 -> c.cards.upload(j.packed, j.casts, j.tiled)
                // (a piece arriving after the chunk's 3D greenery was made goes straight in, or it would be lost)
                else -> { c.nearTodo += j.packed to j.casts; c.near?.upload(j.packed, j.casts) }
            }
            return
        }
        val layer = when {
            j.patch != null -> patches.getOrPut(j.patch.id) { Patch(j.patch) }.layer
            j.yard -> yardLayer
            else -> ground
        }
        val mi = layer.upload(j.packed, j.casts, j.tiled)
        j.water?.let { m -> synchronized(ticks) { ticks += { s -> YardLayer.shimmer(mi, m, s) } } }
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
        for (c in chunks.values) if (c.nearTodo.isNotEmpty() && c.near == null && pose.distance(c.cx, 20f, c.cz) - c.r < TownFrame.LOD_NEAR && pose.sees(c.cx, 20f, c.cz, c.r)) c.buildNear()
    }

    /**
     * Builds buildings not yet built, nearest ([x], [z]) first, until [budgetMs] is spent; returns how many. Each building
     * is made ready on the workers ([BuildingMeshes.prepare]: its art decoded, its geometry and pictures packed), so here
     * only its buffers, textures and renderables are made; the first ready one is finished even past the budget (so the
     * town always comes in), the rest only while the budget lasts. Call each frame until [pending] is 0.
     */
    fun buildNearest(x: Float, z: Float, budgetMs: Float): Int {
        if (todo == 0 && chunksTodo == 0 && groundBuilt) return 0
        val t0 = System.nanoTime()
        var n = 0
        if (todo > 0) {
            order.clear()
            for (s in slots) if (s.built == null && !s.missing) order += s
            todo = order.size
            order.sortBy { hypot(it.cx - x, it.cz - z) }
            // made ready on the workers, nearest first, ahead of being finished here
            for (s in order) if (s !in readyOf) {
                val yarded = s.b.kind != PlacementKind.LANDMARK && yards.of(s.b.sprite) != null
                val sprite = s.b.sprite; val setback = s.b.setback
                readyOf[s] = pool.submit(java.util.concurrent.Callable {
                    val r0 = System.nanoTime()
                    val r = building(sprite)?.let { meshes.prepare(it, setback, yarded, sprite.hashCode()) }
                    val ms = (System.nanoTime() - r0) / 1e6f
                    synchronized(this) { prepareMs += ms; if (ms > slowestPrepare.second) slowestPrepare = sprite to ms }
                    r
                })
            }
            for (s in order) {
                if (n > 0 && (System.nanoTime() - t0) / 1e6f >= budgetMs) break
                val f = readyOf[s] ?: continue
                if (!f.isDone) continue
                buildSlot(s, runCatching { f.get() }.getOrNull())
                readyOf.remove(s)
                todo--
                n++
            }
            order.clear()
        }
        val ms = (System.nanoTime() - t0) / 1e6f
        buildingsMs += ms
        if (n > 0) batches += n to ms
        // then, with what is left of the budget, the 3D greenery of the chunks coming near
        if (n == 0 && chunksTodo > 0 && groundBuilt) {
            // (no list made: the nearest waiting chunk in reach, again and again while the budget lasts)
            var left = 0
            for (c in chunks.values) if (c.near == null && c.nearTodo.isNotEmpty()) left++
            chunksTodo = left
            while (left > 0 && (System.nanoTime() - t0) / 1e6f < budgetMs) {
                var best: Chunk? = null; var bd = Float.MAX_VALUE
                for (c in chunks.values) {
                    if (c.near != null || c.nearTodo.isEmpty()) continue
                    val d = hypot(c.cx - x, c.cz - z) - c.r
                    if (d < TownFrame.LOD_NEAR + 600f && d < bd) { bd = d; best = c }
                }
                val b = best ?: break
                b.buildNear()
                left--
                chunksTodo = left
            }
        }
        return n
    }

    /** Buildings not yet built (a count kept by [buildNearest], so a frame with nothing left does no work). */
    private var todo = Int.MAX_VALUE
    /** Chunks whose 3D greenery is not made yet (as last counted; once the ground is in). */
    private var chunksTodo = Int.MAX_VALUE
    private val order = ArrayList<Slot>()

    /** Nothing is left to build: every building, and every chunk's 3D greenery. */
    val allBuilt: Boolean get() = groundBuilt && pending == 0 && chunks.values.none { it.near == null && it.nearTodo.isNotEmpty() }

    /** Reads and packs [images] on a worker ahead of their first use here ([StreetTextures.warm]). */
    fun warm(images: List<androidx.compose.ui.graphics.ImageBitmap>) {
        if (images.isNotEmpty()) pool.execute { runCatching { for (i in images) textures.warm(i) } }
    }

    /** The slowest single building so far: its sprite and how long finishing it took here (ms; made ready on a worker). */
    var slowest: Pair<String, Float> = "" to 0f; private set
    /** The workers' time making buildings ready (ms, all of them), and the slowest one there. */
    @Volatile var prepareMs = 0f; private set
    @Volatile var slowestPrepare: Pair<String, Float> = "" to 0f; private set

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
        if (r == null) { s.missing = true; return }
        // (the yard kit draws a lot's ground and fences: the house's and the model's own were left off there when made
        // ready, not the landmarks', whose grounds the yards do not cover yet)
        val built = meshes.finish(r)
        if (built == null) { s.missing = true; return }
        val tm = engine.transformManager
        s.place.matrix(xf)
        for (e in listOf(built.entity, built.proxy)) {
            if (e == 0) continue
            tm.create(e)
            tm.setTransform(tm.getInstance(e), xf)
            scene.addEntity(e)
        }
        s.built = built
        // its sphere from its real bounds
        val bb = built.bounds
        val lx = (bb[0] + bb[3]) / 2f; val lz = (bb[2] + bb[5]) / 2f
        val c = s.place.world(lx, lz)
        s.cx = c.x; s.cz = c.z; s.cy = (bb[1] + bb[4]) / 2f
        s.r = sqrt((bb[3] - bb[0]) * (bb[3] - bb[0]) + (bb[4] - bb[1]) * (bb[4] - bb[1]) + (bb[5] - bb[2]) * (bb[5] - bb[2])) / 2f
    }

    // ------------------------------------------------------------------ each frame

    /** The windows' night light's time a frame ([NIGHT_BUDGET_NS]; a still picture, as a preview's, wants it unbounded). */
    var nightBudgetNs = NIGHT_BUDGET_NS
    private val nightQueue = arrayOfNulls<Slot>(slots.size)
    private var nightCount = 0
    private var nightNext = 0

    /** Brings the scene to camera [pose] at [hour]; [seconds] is the clock (the yards' water shimmers by it). */
    fun frame(pose: TownPose, hour: Float, seconds: Double = 0.0) {
        lastPose = pose
        view.viewport = Viewport(0, 0, pose.w.toInt(), pose.h.toInt())
        // (the look's depth reaches 1 at the camera's own near: Filament's lights are sorted into the view's depth slices
        // by the camera's projection, so the two must agree or the street lamps' light lands in the wrong slices)
        LookMatrix.pinhole(pose.screen(m12), pose.w, pose.h, pose.near, look)
        aim(pose)
        val sky = skyColors(hour)
        light(pose, hour, sky)
        haze(pose, sky)
        val dark = sky.dark
        val wl = SceneInk.WindowLit
        val g0 = lin(wl.red); val g1 = lin(wl.green) * .9f; val g2 = lin(wl.blue) * .7f
        val ga = .75f * smooth((dark - .35f) / .5f)
        val tone = sky.sky.lastOrNull() ?: Color.White
        val m = max(tone.red, max(tone.green, tone.blue)).coerceAtLeast(.05f)
        val gl0 = mix(1f, tone.red / m * 1.15f, .7f); val gl1 = mix(1f, tone.green / m * 1.15f, .7f); val gl2 = mix(1f, tone.blue / m * 1.15f, .7f)
        val rm = engine.renderableManager
        var visible = 0; var draws = 0; var tris = 0; var renderables = 0; var casters = 0
        for (s in slots) {
            val b = s.built ?: continue
            // the street view: what stands between the camera and the street in view fades out
            val fade = min(if (pose.cut > 0f) fadeOf(pose, s.cx, s.cz) else 1f, pose.rangeFade(s.cx, s.cy, s.cz, s.r))
            val on = fade > .02f && pose.sees(s.cx, s.cy, s.cz, s.r)
            s.shown = on
            rm.getInstance(b.entity).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (on) 1 else 0) }
            // (its shadow may fall into view from beside it: the twin stays while it is near the view)
            val near = on || (fade > .02f && pose.sees(s.cx, s.cy, s.cz, s.r + 300f))
            if (b.proxy != 0) rm.getInstance(b.proxy).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (near) 1 else 0) }
            if (near && b.proxy != 0) casters++
            if (!on) continue
            visible++; renderables++; draws += b.primitives; tris += b.triangles
            for (mi in b.mis) {
                mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                mi.setParameter("cell", 2f)
                mi.setParameter("fade", fade)
                mi.setParameter("glow", g0, g1, g2, ga)
                mi.setParameter("glass", gl0, gl1, gl2, 1f)
            }
            if (b.night != null) nightQueue[nightCount++] = s
        }
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
        for (layer in listOf(ground, yardLayer)) {
            layer.update(look, dark)
            renderables += layer.draws; draws += layer.draws
            casters += layer.casters
            tris += layer.triangles
        }
        // the yards a lot at a time: culled to the view (their shadows while near it), faded with their house
        for (pt in patches.values) {
            val p = pt.p
            val fade = min(if (pose.cut <= 0f) 1f else pt.slot?.let { fadeOf(pose, it.cx, it.cz) } ?: fadeOf(pose, p.x, p.z), pose.rangeFade(p.x, 15f, p.z, p.r))
            val on = fade > .02f && pose.sees(p.x, 15f, p.z, p.r + 30f)
            // (a cut lot throws no shadow: a fence's shadow without its fence reads wrong)
            val near = fade > .5f && (on || pose.sees(p.x, 15f, p.z, p.r + 150f))
            pt.layer.update(look, dark, on, fade, near)
            if (on) { renderables += pt.layer.draws; draws += pt.layer.draws; tris += pt.layer.triangles }
            if (near) casters += pt.layer.casters
        }
        // the furniture, props and greenery a chunk at a time: culled, cut with their row, cards far and 3D near
        var nChunks = 0; var nNear = 0
        for (c in chunks.values) {
            val cut = if (pose.cut <= 0f) 1f else if (c.byRow) fadeOf(pose, c.refX, c.refZ) else fadeOf(pose, c.cx, c.cz)
            val fade = min(cut, pose.rangeFade(c.cx, c.top / 2f, c.cz, c.r))
            val on = fade > .02f && pose.sees(c.cx, c.top / 2f, c.cz, c.r)
            val shadows = fade > .5f && (on || pose.sees(c.cx, c.top / 2f, c.cz, c.r + 150f))
            val close = on && c.nearTodo.isNotEmpty() && pose.distance(c.cx, c.top / 2f, c.cz) - c.r < TownFrame.LOD_NEAR
            val near3d = close && c.near != null
            c.base.update(look, dark, on, fade, shadows)
            c.cards.update(look, dark, on && !near3d, fade, shadows && !near3d)
            c.near?.update(look, dark, near3d, fade, shadows && near3d)
            if (on) {
                nChunks++
                for (l in listOfNotNull(c.base, if (near3d) c.near else c.cards)) { renderables += l.draws; draws += l.draws; tris += l.triangles }
            }
            if (near3d) nNear++
            if (shadows) casters += c.base.casters + (if (near3d) c.near!!.casters else c.cards.casters)
        }
        val lampsLit = lamps(pose, dark)
        for (t in synchronized(ticks) { ticks.toList() }) t(seconds)
        stats = TownStats(slots.size, slots.count { it.built != null }, visible, renderables, draws, tris, casters, nChunks, nNear, lampsLit)
    }

    /**
     * At night the [LAMP_LIGHTS] lanterns nearest the point the view looks at (in its range) light the pavement and the
     * fronts round them: real point lights, warm, reaching [LAMP_REACH]; by day they are off. Returns how many are on.
     */
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

    /** The street view's cut for something whose middle is at ([x], [z]): 1 beyond the cut, fading to 0 nearer the eye. */
    private fun fadeOf(pose: TownPose, x: Float, z: Float): Float =
        smooth((pose.groundX * (x - pose.ex) + pose.groundZ * (z - pose.ez) - pose.cut + TownFrame.CUT_FADE) / TownFrame.CUT_FADE)

    private fun aim(p: TownPose) {
        val k = (p.near / p.fl).toDouble()
        camera.setProjection(Camera.Projection.PERSPECTIVE, -p.cx * k, (p.w - p.cx) * k, -(p.h - p.cy) * k, p.cy * k, p.near.toDouble(), p.far.toDouble())
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
        val dx = key.across * -gz - key.out * gx
        val dy = -key.down
        val dz = key.across * gx - key.out * gz
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        lm.setDirection(li, dx / dl, dy / dl, dz / dl)
        val warm = (1f - key.down).coerceIn(0f, 1f)
        val sc = if (key.cool > .5f) Color(0xFF9DB4FF) else lerp(Color(0xFFFFF6E8), Color(0xFFFFB070), warm * warm)
        lm.setColor(li, lin(sc.red), lin(sc.green), lin(sc.blue))
        lm.setIntensity(li, SUN * key.strength * Math.PI.toFloat())
        lm.setShadowCaster(li, key.cool < .5f && key.strength > .05f)
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
        val tone = sky.sky.lastOrNull() ?: return
        fog.enabled = true
        fog.distance = p.hazeFrom
        fog.density = p.haze * (1f - .4f * sky.dark)
        fog.height = 0f
        fog.heightFalloff = 1e-4f
        // (the street view stops drawing where the haze is thick: there it takes everything, the ground too)
        fog.maximumOpacity = if (p.range < Float.MAX_VALUE) 1f else .85f
        fog.inScatteringStart = 0f
        fog.inScatteringSize = -1f
        fog.fogColorFromIbl = false
        fog.color = floatArrayOf(lin(tone.red), lin(tone.green), lin(tone.blue))
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
    fun people(figures: List<com.lifemax.rpg.feature.habits.presentation.scene.TownFigure>, n: Int, sheets: List<androidx.compose.ui.graphics.ImageBitmap>): Int {
        val pose = lastPose ?: return 0
        val lm = engine.lightManager
        lm.getDirection(lm.getInstance(sun), sunDir)
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
        val em = EntityManager.get()
        crowd.destroy()
        for (s in slots) {
            val b = s.built ?: continue
            for (e in listOf(b.entity, b.proxy)) if (e != 0) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            for (mi in b.mis) engine.destroyMaterialInstance(mi)
            for (mi in b.proxyMis) engine.destroyMaterialInstance(mi)
            engine.destroyVertexBuffer(b.vb); engine.destroyIndexBuffer(b.ib)
            s.built = null
        }
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
        engine.destroyMaterial(lit); engine.destroyMaterial(proxyMat)
        engine.destroyColorGrading(grading)
        val ce = camera.entity
        engine.destroyCameraComponent(ce); em.destroy(ce)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }

    companion object {
        /** The sun's and the sky light's strength by day (the street's). */
        private const val SUN = .45f
        private const val AMB = .8f
        /** A row's furniture and props are chunked in stretches this long; the edge's greenery in squares this big. */
        const val CHUNK = 800f
        const val CELL = 2000f
        /** How many lanterns light at night (the nearest), how far their light reaches and how strong (lumens). */
        const val LAMP_LIGHTS = 12
        const val LAMP_REACH = 260f
        const val LAMP_LUMENS = 60000f
        /** How long the windows' night light may take a frame (ns): a building's first is a whole picture. */
        const val NIGHT_BUDGET_NS = 2_000_000L
    }
}
