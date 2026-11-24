package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownPlace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A hand-made landmark model (docs/LANDMARKS_GLB.md) in [FilamentTown], as [TownCinema] does the theater: the .glb read
 * and built ([LandmarkKit]) for the town's [year] on a worker, its parts packed there in its frame ([frameOf]: a row slot's,
 * the lot's front edge on the building line and its middle on the slot's, or the railroad depot's beside the track),
 * uploaded on the engine's thread into its own culled [PropLayer], lit by [CinemaLayer] and [CinemaLights] (dark when the
 * business has [closed]); once up, its collision boxes and doors go into the walk's [collision] (as a store's:
 * [TownCollision.setExtra], [TownCollision.setExtraDoors]). [hide]: nodes not drawn in its state (a repair garage's pumps).
 * In the open world camera's way its building (walls, roofs and glass, not the flat lot) is cut away like a building
 * ([inWay], [occFade]); past the view's range it stays drawn to [FAR] (a landmark is seen from across town: it is its own
 * far box).
 */
internal class TownLandmark(
    engine: Engine, scene: Scene, lit: Material, proxy: Material, textures: StreetTextures,
    val spec: LandmarkSpec, private val bytes: () -> ByteArray, private val info: () -> String?,
    val sprite: String, private val row: String,
    /** The model's frame (x, z of its origin; its x axis; its z axis, out to the road) for its lot's middle [xMid] and front edge [front] (model units). */
    private val frameOf: (xMid: Float, front: Float) -> FloatArray,
    /** A first guess of its middle and reach in the world (x, z, radius), until built. */
    guess: FloatArray,
    private val year: Int, private val seed: Int, private val collision: TownCollision?,
    val closed: Boolean = false, private val hide: Set<String> = emptySet(),
    private val kind: PlacementKind = PlacementKind.LANDMARK,
    /** Its distant silhouette's file ([LandmarkSpec.far]), drawn instead of it past [LOD]; null: none. */
    private val farBytes: (() -> ByteArray?)? = null,
    /** The game's time of day (hours) a working clock's hands are set to when it is built; null: as modelled. */
    private val clockHours: () -> Float? = { null },
) {
    private val layer = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val glow = CinemaLayer(layer)
    private val farLayer = farBytes?.let { PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true) }
    private val farGlow = farLayer?.let { CinemaLayer(it) }
    private val lamps = CinemaLights(engine, scene)
    private var animator: LandmarkAnimator? = null
    private var lastSeconds = 0.0
    private class Built(val far: List<CinemaLayer.Packed>?, val front: Float, val parts: List<CinemaLayer.Packed>, val animated: LandmarkAnimator.PackedSet?, val at: FloatArray, val box: FloatArray, val body: FloatArray, val lights: List<CinemaLight>, val solids: FloatArray, val doors: List<LandmarkDoor>, val triangles: Int,
                        val levels: com.lifemax.rpg.feature.habits.domain.LevelField.Builder?)
    private var job: java.util.concurrent.Future<Built>? = null
    private var at: FloatArray = frameOf(0f, 0f)
    var done = false; private set
    var shown = false; private set
    var cx = guess[0]; var cy = 60f; var cz = guess[1]; var r = guess[2]
    /** The building's box in the model's frame (x0, z0, x1, z1, height), once built: the camera's cut. */
    private var body: FloatArray? = null
    /** How far it is cut away for the open world's camera (1 whole), eased by [FilamentTown]. */
    var occFade = 1f
    var occluded = false
    var occT = Float.MAX_VALUE
    var triangles = 0; private set
    var solidCount = 0; private set
    var doorCount = 0; private set
    /** The bytes its height field holds (WalkLevels.kt), once built. */
    var levelBytes = 0L; private set
    /** The lot's front edge (model z) and its footprint (x0, z0, x1, z1, height), once built. */
    var front = 0f; private set
    var lot: FloatArray? = null; private set

    /** Starts building it on [pool] (once), at [priority] there ([TownWorkers]: after the first view's buildings). */
    fun start(pool: TownWorkers, priority: Float) {
        if (job != null || done) return
        job = pool.submit(priority, "landmark", spec.kind, java.util.concurrent.Callable {
            val lm = LandmarkKit.build(GlbFile.read(bytes()), spec, year, seed, hide = hide, info = info(), clockHours = clockHours())
            val m = lm.model
            val a = frameOf(spec.centre?.let { it * LandmarkKit.UNITS_PER_METRE } ?: ((m.footprint[0] + m.footprint[2]) / 2f), lm.front)
            // the building: its walls, roofs and glass (not the lot's ground, the signs on poles or the props, nor the lot
            // group's fences and stands: drawn in the same materials, they would stretch it over the whole lot, and the
            // camera would cut the whole landmark away, fences and all, while the player walks its grounds)
            val b = lm.body ?: floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, 0f).also { b ->
                for ((n, p) in lm.names.zip(m.parts)) if (n.startsWith("wall_") || n.startsWith("roof_") || n.startsWith("glass_")) {
                    val q = p.mesh.bounds
                    b[0] = min(b[0], q[0]); b[1] = min(b[1], q[2]); b[2] = max(b[2], q[3]); b[3] = max(b[3], q[5]); b[4] = max(b[4], q[4])
                }
            }
            val fp = floatArrayOf(m.footprint[0], m.footprint[1], m.footprint[2], m.footprint[3], m.height)
            // (the distant silhouette in the same frame: it shares the model's origin)
            val far = farBytes?.invoke()?.takeIf { it.isNotEmpty() }?.let { fb -> CinemaLayer.pack(LandmarkKit.build(GlbFile.read(fb), spec, year, seed, hide = hide).model, a) }
            val totalTris = m.triangles + lm.animated.sumOf { it.triangles }
            Built(far, lm.front, CinemaLayer.pack(m, a), LandmarkAnimator.pack(lm, a), a, fp, if (b[0] < b[2]) b else fp, m.lights, StoreSolids.world(lm.solids, a), lm.doors, totalTris,
                // (its steps, terraces and plaza, to be made heights in the town's frame for the walk to climb: not
                // here, but once the player comes near ([stepLevels]), so the far landmarks' fields keep no worker from
                // the first view)
                lm.levels?.takeUnless { it.isEmpty })
        })
    }

    /** Uploads the model once its worker made it ([start]); true once done. (Its GPU memory counted as "GLB landmarks".) */
    fun step(): Boolean = GpuAccount.scoped("landmarks") { stepNow() }

    private fun stepNow(): Boolean {
        if (done) return true
        val f = job ?: return false
        if (!f.isDone) return false
        val b = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is VirtualMachineError) throw BuildFailure("building a landmark model on a worker", c)
            done = true; job = null; return true
        }
        at = b.at
        glow.upload(b.parts)
        b.far?.let { farGlow?.upload(it) }
        animator = LandmarkAnimator.upload(layer, glow, b.animated)
        lamps.add(b.lights, at)
        job = null
        val fp = b.box
        val mx = (fp[0] + fp[2]) / 2f; val mz = (fp[1] + fp[3]) / 2f
        cx = at[0] + at[2] * mx + at[4] * mz; cz = at[1] + at[3] * mx + at[5] * mz; cy = fp[4] / 2f
        r = sqrt((fp[2] - fp[0]) * (fp[2] - fp[0]) + (fp[3] - fp[1]) * (fp[3] - fp[1]) + fp[4] * fp[4]) / 2f
        body = b.body; front = b.front; lot = b.box
        triangles = b.triangles; solidCount = b.solids.size / 6; doorCount = b.doors.size
        solids = b.solids
        levelsSrc = b.levels
        collision?.let { col ->
            // (until its heights are made ([stepLevels]) its outlines stand whole for the walk on foot too)
            col.setExtra(KEY + sprite, b.solids, SolidKind.BUILDING, sprite, null)
            col.setExtraDoors(KEY + sprite, b.doors.map { d ->
                val wx = at[0] + at[2] * d.x + at[4] * d.z; val wz = at[1] + at[3] * d.x + at[5] * d.z
                val ox = at[2] * d.outX + at[4] * d.outZ; val oz = at[3] * d.outX + at[5] * d.outZ
                TownDoors.standing(sprite, row, kind, wx, wz, ox, oz, col)
            })
        }
        done = true
        return true
    }

    private var solids: FloatArray? = null
    /** Its steps', terraces' and plaza's triangles and boxes, until made a height field ([stepLevels]). */
    @Volatile private var levelsSrc: com.lifemax.rpg.feature.habits.domain.LevelField.Builder? = null
    private var levelsJob: java.util.concurrent.Future<com.lifemax.rpg.feature.habits.domain.LevelField?>? = null
    private var levelsIdle: java.util.concurrent.Future<com.lifemax.rpg.feature.habits.domain.LevelField?>? = null
    private var levelsMade: com.lifemax.rpg.feature.habits.domain.LevelField? = null
    private var levelsTried = false
    /** Its height field is still to be made ([stepLevels]). */
    val levelsPending: Boolean get() = levelsSrc != null
    /** How long making its height field took on a worker (ms; 0 until made). */
    var levelsMs = 0f; private set

    // (made once, by whichever of its two jobs runs first)
    private val levelsLock = Any()
    private fun makeLevels(src: com.lifemax.rpg.feature.habits.domain.LevelField.Builder, a: FloatArray): com.lifemax.rpg.feature.habits.domain.LevelField? = synchronized(levelsLock) {
        if (levelsTried) return levelsMade
        val t0 = System.nanoTime()
        try { levelsMade = src.build(a) } finally { levelsTried = true; levelsMs = (System.nanoTime() - t0) / 1e6f }
        levelsMade
    }

    /**
     * Makes its height field (its steps, terraces and plaza, for the walk on foot to climb) on [pool] once the player at
     * ([px], [pz]) comes within [LEVELS_NEAR] of it (ahead of any other work there), or, before that, once the town is
     * built ([idle]) when the workers have nothing else to do ([TownWorkers.IDLE]); hands it to the walk once made (the engine's thread, a frame). [now]: made
     * here and now, waiting (a preview's, a test's).
     */
    fun stepLevels(pool: TownWorkers, px: Float, pz: Float, idle: Boolean, now: Boolean = false) {
        val src = levelsSrc ?: return
        val a = at
        if (levelsIdle == null && idle) levelsIdle = pool.submit(TownWorkers.IDLE, "landmark-heights", spec.kind, java.util.concurrent.Callable { makeLevels(src, a) })
        val near = now || kotlin.math.hypot(px - cx, pz - cz) - r < LEVELS_NEAR
        val idleJob = levelsIdle
        if (near && levelsJob == null && idleJob?.isDone != true) levelsJob = pool.submit(TownWorkers.NOW, "landmark-heights-near", spec.kind, java.util.concurrent.Callable { makeLevels(src, a) })
        val f = if (idleJob?.isDone == true) idleJob else levelsJob ?: return
        if (now) runCatching { f.get() }
        if (!f.isDone) return
        val field = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is VirtualMachineError) throw BuildFailure("making a landmark's height field on a worker", c)
            null
        } catch (e: java.util.concurrent.CancellationException) { null }
        levelsSrc = null; levelsJob = null; levelsIdle = null
        if (field == null) return
        levelBytes = field.bytes
        collision?.let { col ->
            // (with its heights in a field the walk on foot takes them from there: the outlines are for the cars and people)
            col.setLevels(KEY + sprite, field)
            col.setExtra(KEY + sprite, solids, SolidKind.BUILDING, sprite, TownCollision.STEPPED)
        }
    }

    /**
     * The underside of its ceiling over a walker at ([x], [z]) with feet at [feet] (a floor or roof no more than
     * [INDOORS_CEILING] over the head), or NaN: in the open (its plaza, its steps, under a lofty arch) or no field yet.
     */
    fun ceilingOver(x: Float, z: Float, feet: Float): Float {
        val f = levelsMade ?: return Float.NaN
        val c = f.bottomAbove(x, z, feet + com.lifemax.rpg.feature.habits.domain.WalkHeight.HEAD)
        if (c.isNaN() || c - feet >= INDOORS_CEILING) return Float.NaN
        // (and walls round: a room or a hall, not a gazebo's or a porch's roof on posts, where the cutaway would take
        // everything of the landmark above that roof with it: the Night Keeper's giant figure): of eight ways out at chest
        // height, most meet a wall within [INDOORS_REACH]
        var walls = 0
        for (k in 0 until 8) {
            val dx = RING_X[k]; val dz = RING_Z[k]
            var d = 6f
            while (d <= INDOORS_REACH) {
                if (f.hits(x + dx * d, z + dz * d, feet + 26f, feet + 32f)) { walls++; break }
                d += 6f
            }
            if (walls + (7 - k) < INDOORS_WALLS) return Float.NaN
        }
        return c
    }

    /** The cutaway of the player inside it ([SeeThrough.cutaway]), or null; set by [FilamentTown.occlude]. */
    var cutaway: FloatArray?
        get() = layer.cutaway
        set(v) { layer.cutaway = v }
    /** (Its cutaway's own floats, filled in place.) */
    val cutawayBuf = FloatArray(8)

    /** The model's frame: x, z of its origin, its x axis, its z axis (for previews). */
    val frame: FloatArray get() = at

    /**
     * Where the line from ([ox], [oy], [oz]) to the eye ([ex], [ey], [ez]) enters its building (a share of the way;
     * 0: the eye within [clear] of it; MAX_VALUE: not in the way).
     */
    fun inWay(ox: Float, oy: Float, oz: Float, ex: Float, ey: Float, ez: Float, clear: Float): Float {
        val b = body ?: return Float.MAX_VALUE
        val a = at
        fun lx(x: Float, z: Float) = (x - a[0]) * a[2] + (z - a[1]) * a[3]
        fun lz(x: Float, z: Float) = (x - a[0]) * a[4] + (z - a[1]) * a[5]
        val exl = lx(ex, ez); val ezl = lz(ex, ez)
        val dx = maxOf(b[0] - exl, 0f, exl - b[2]); val dz = maxOf(b[1] - ezl, 0f, ezl - b[3]); val dy = maxOf(-ey, 0f, ey - b[4])
        if (dx * dx + dy * dy + dz * dz < clear * clear) return 0f
        val oxl = lx(ox, oz); val ozl = lz(ox, oz)
        var t0 = 0f; var t1 = 1f
        for (k in 0..2) {
            val o = when (k) { 0 -> oxl; 1 -> oy; else -> ozl }
            val d = when (k) { 0 -> exl - oxl; 1 -> ey - oy; else -> ezl - ozl }
            val lo = when (k) { 0 -> b[0]; 1 -> 0f; else -> b[1] }
            val hi = when (k) { 0 -> b[2]; 1 -> b[4]; else -> b[3] }
            if (kotlin.math.abs(d) < 1e-6f) { if (o < lo || o > hi) return Float.MAX_VALUE; continue }
            var u = (lo - o) / d; var v = (hi - o) / d
            if (u > v) { val t = u; u = v; v = t }
            if (u > t0) t0 = u
            if (v < t1) t1 = v
            if (t0 > t1) return Float.MAX_VALUE
        }
        return t0
    }

    /** Whether world ([x], [z]) is on its lot (its footprint), once built. */
    fun under(x: Float, z: Float): Boolean {
        val l = lot ?: return false
        val a = at
        val lx = (x - a[0]) * a[2] + (z - a[1]) * a[3]; val lz = (x - a[0]) * a[4] + (z - a[1]) * a[5]
        return lx >= l[0] && lx <= l[2] && lz >= l[1] && lz <= l[3]
    }

    /** Triggers any swinging bells on this landmark for [swings] cycles, easing to rest afterwards. */
    fun ringBells(nowSeconds: Double = lastSeconds, swings: Int = 5) {
        animator?.ringBells(nowSeconds, swings)
    }

    /**
     * This frame through [pose] ([look] its matrix), [dark] how dark, [seconds] the clock; [cut] the street view's cut at
     * a world point (1: not cut). Returns its draws, triangles and shadow casters this frame (0s when not drawn).
     */
    fun frame(pose: TownPose, look: FloatArray, dark: Float, hour: Float, seconds: Double, cut: (Float, Float) -> Float, out: IntArray): IntArray {
        out.fill(0)
        lastSeconds = seconds
        if (!done) return out
        val kept = min(cut(cx, cz), if (pose.distance(cx, cy, cz) - r < FAR) 1f else 0f)
        val fade = min(kept, occFade)
        val seen = fade > .02f && pose.sees(cx, cy, cz, r)
        // (past LOD its distant silhouette stands in for it, where it has one)
        val far = farLayer != null && pose.distance(cx, cy, cz) - r > LOD
        val on = seen && !far
        // (cut away for the camera, it keeps its shadow, as a building does; shadows only within the view's range)
        val near = kept > .5f && pose.rangeFade(cx, cy, cz, r) > .5f && (on || pose.sees(cx, cy, cz, r + 300f)) && !far
        // (a shut business is dark: no signs, no lit windows, no lamps, no night beacon)
        val lit = if (closed) 0f else dark
        animator?.update(hour, seconds, lit, active = on && near)
        layer.update(look, dark, on, fade, near, castFade = 1f)
        farLayer?.update(look, dark, seen && far, fade, false, castFade = 1f)
        if (seen && far) farGlow?.update(lit, seconds)
        if (on) glow.update(lit, seconds)
        lamps.update(lit, on && near)
        shown = on
        if (on) { out[0] = layer.draws; out[1] = layer.triangles }
        if (seen && far) { out[0] = farLayer!!.draws; out[1] = farLayer.triangles }
        if (near) out[2] = layer.casters
        return out
    }

    fun destroy() {
        job?.cancel(true); levelsJob?.cancel(true); levelsIdle?.cancel(true); layer.destroy(); farLayer?.destroy(); lamps.destroy()
        collision?.setExtra(KEY + sprite, null); collision?.setExtraDoors(KEY + sprite, null); collision?.setLevels(KEY + sprite, null)
    }

    companion object {
        const val KEY = "landmark:"
        /** How far a landmark model stays drawn (street units past its reach): a water tower is seen from across town. */
        const val FAR = 12000f
        /** Past this (street units beyond its reach) a model with a distant silhouette ([LandmarkSpec.far]) draws that instead. */
        const val LOD = 3000f
        /**
         * Its height field is made once the player comes this near its reach (street units: 150 m), ahead of any other
         * work: a few hundred ms on a worker at most, long before anyone on foot (or on the bike) reaches its steps.
         */
        val LEVELS_NEAR = 150f * LandmarkKit.UNITS_PER_METRE
        /** A floor or roof this near over the feet (street units: 6 m) is a ceiling: the player is inside ([ceilingOver]). */
        const val INDOORS_CEILING = 128f
        /** Inside: at least this many of eight ways out meet a wall within [INDOORS_REACH] (street units: 14 m). */
        const val INDOORS_WALLS = 5
        const val INDOORS_REACH = 300f
        private val RING_X = FloatArray(8) { kotlin.math.cos(it * Math.PI / 4).toFloat() }
        private val RING_Z = FloatArray(8) { kotlin.math.sin(it * Math.PI / 4).toFloat() }

        /** A row slot's frame: the lot's front edge on the building line [setback] back, its middle on the slot's. */
        fun rowFrame(place: TownPlace, setback: Float): (Float, Float) -> FloatArray = { xMid, front ->
            val p = place.world(0f, setback + front)
            floatArrayOf(p.x - place.rx * xMid, p.z - place.rz * xMid, place.rx, place.rz, -place.nx, -place.nz, 0f)
        }

        /** The model's origin at ([x], [z]), its front (+z) along ([fx], [fz]) (a unit vector), its x axis to its right as seen from the front. */
        fun facing(x: Float, z: Float, fx: Float, fz: Float): (Float, Float) -> FloatArray = { _, _ -> floatArrayOf(x, z, fz, -fx, fx, fz, 0f) }
    }
}

/**
 * What the town needs to build its hand-made landmark models: the catalogue (assets/landmarks/landmarks.json),
 * a reader for the catalogue's files ([file]: a path relative to its folder; the app's assets, the desktop's files), and
 * the models the town places ([placed]: street.json's graph.glbLandmarks).
 */
class LandmarkAssets(val catalogue: List<LandmarkSpec>, val file: (String) -> ByteArray?, val placed: List<com.lifemax.rpg.feature.habits.domain.GlbPlaced>)
