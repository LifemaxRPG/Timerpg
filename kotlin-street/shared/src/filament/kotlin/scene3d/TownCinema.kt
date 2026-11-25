package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.TownPlace
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The town's movie theater as street.json v2 exports it (docs/CINEMA.md): the building of a row it stands as ([sprite],
 * the cinema landmark's), and what it is and plays this year ([info]: its name, era, bill, marquee and the plot's size).
 */
class CinemaSite(val sprite: String, val info: CinemaInfo) {
    companion object {
        /** The cinema in a street.json's root (its `graph`), or null for a town without one (or a v1 file). */
        fun parse(root: org.json.JSONObject): CinemaSite? {
            val g = root.optJSONObject("graph") ?: return null
            val info = CinemaInfo.fromGraph(g) ?: return null
            val id = g.optJSONObject("cinema")?.optString("landmark") ?: return null
            val lms = g.optJSONArray("landmarks") ?: return null
            val sprite = (0 until lms.length()).map { lms.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.optString("sprite")
            return sprite?.takeIf { it.isNotBlank() }?.let { CinemaSite(it, info) }
        }

        fun parse(streetJson: String): CinemaSite? = runCatching { parse(org.json.JSONObject(streetJson)) }.getOrNull()
    }
}

/**
 * The cinema in [FilamentTown]: [CinemaKit] built for [site] on a worker (its pictures painted, its parts packed), then
 * uploaded on the engine's thread into its own [PropLayer] standing at the cinema's slot ([place], [setback]: the same
 * rigid frame the walk and the yards stand that building in), lit and chased by [CinemaLayer], culled and cut with the
 * street view like a building. The model and its packed bytes are let go once uploaded (some 3 MB of pictures).
 */
internal class TownCinema(
    engine: Engine, scene: Scene, lit: Material, proxy: Material, textures: StreetTextures,
    val site: CinemaSite, private val place: TownPlace, private val setback: Float, private val seed: Int,
) {
    private val layer = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val glow = CinemaLayer(layer)
    private val lamps = CinemaLights(engine, scene)
    /** The car park's cars as our real cars ([LotCars]): their mid models near, their far ones further (none: boxes in [layer]). */
    private val carsMid = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val carsFar = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private var hasCars = false
    private var job: java.util.concurrent.Future<Built>? = null
    /** It is up (uploaded), or it failed (the town goes on without it). */
    var done = false; private set
    /** A sphere round it in the world, its height's middle, once built (a guess before). */
    var cx = 0f; var cy = 60f; var cz = 0f; var r = 0f
    /** Whether it was drawn last frame. */
    var shown = false; private set
    /** Its footprint in its own frame (x along, z out: x0, z0, x1, z1) and its height, once built: the open world's cutaway. */
    private var box: FloatArray? = null
    /** The open world: it stands between the camera and the player, or by the eye ([FilamentTown.occlude]); eased out. */
    var occluded = false
    var occFade = 1f

    /**
     * Where along the line from ([ox], [oy], [oz]) to the eye ([ex], [ey], [ez]) it begins (0..1), 0 with the eye within
     * [clear] of it (or in it), MAX_VALUE if it is not in the way (not built yet: never).
     */
    fun inWay(ox: Float, oy: Float, oz: Float, ex: Float, ey: Float, ez: Float, clear: Float): Float {
        val b = box ?: return Float.MAX_VALUE
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

    /** Where the model's frame stands: its front's middle, along the street (the art's left-to-right), out towards the road. */
    private val at: FloatArray = place.world(0f, setback).let { p -> floatArrayOf(p.x, p.z, place.rx, place.rz, -place.nx, -place.nz, 0f) }

    init {
        val c = place.world(0f, setback + max(site.info.depth, 300f) / 2f)
        cx = c.x; cz = c.z; r = hypot(max(site.info.width, 300f) / 2f, max(site.info.depth, 300f) / 2f) + 60f
    }

    /** Starts building it on [pool] (once), at [priority] there ([TownWorkers]). */
    fun start(pool: TownWorkers, priority: Float) {
        if (job != null || done) return
        val a = at
        job = pool.submit(priority, "cinema", null, java.util.concurrent.Callable {
            // (the car park's cars as real cars where they can be read, else the boxes as before)
            val real = LotCars.specs().isNotEmpty()
            val m = CinemaKit.build(site.info, seed, realCars = real)
            val mid = LotCars.pack(m.parked, site.info.year, a, LotCars.Lod.MID)?.batches.orEmpty()
            val far = LotCars.pack(m.parked, site.info.year, a, LotCars.Lod.FAR)?.batches.orEmpty()
            Built(CinemaLayer.pack(m, a), floatArrayOf(m.footprint[0], m.footprint[1], m.footprint[2], m.footprint[3], m.height), m.lights, mid, far)
        })
    }

    /** Uploads it if its worker is done (the engine's thread); true once it is up. */
    fun step(): Boolean {
        if (done) return true
        val f = job ?: return false
        if (!f.isDone) return false
        val built = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is VirtualMachineError) throw BuildFailure("building the cinema on a worker", c)
            done = true; job = null; return true
        }
        val parts = built.parts; val fp = built.box; val ls = built.lights
        glow.upload(parts)
        lamps.add(ls, at)
        for (p in built.mid) carsMid.upload(p, true)
        for (p in built.far) carsFar.upload(p, true)
        hasCars = built.mid.isNotEmpty()
        job = null
        // its sphere from what was built (the footprint in the model's frame: x along, z out; the height)
        val mx = (fp[0] + fp[2]) / 2f; val mz = (fp[1] + fp[3]) / 2f
        cx = at[0] + at[2] * mx + at[4] * mz; cz = at[1] + at[3] * mx + at[5] * mz; cy = fp[4] / 2f
        box = floatArrayOf(minOf(fp[0], fp[2]), minOf(fp[1], fp[3]), maxOf(fp[0], fp[2]), maxOf(fp[1], fp[3]), fp[4])
        r = sqrt((fp[2] - fp[0]) * (fp[2] - fp[0]) + (fp[3] - fp[1]) * (fp[3] - fp[1]) + fp[4] * fp[4]) / 2f
        done = true
        return true
    }

    /**
     * This frame through [pose] ([look] its matrix), [dark] how dark, [seconds] the clock; [cut] the street view's cut at
     * a world point (1: not cut). Returns its draws, triangles and shadow casters this frame (0s when not drawn).
     */
    fun frame(pose: TownPose, look: FloatArray, dark: Float, seconds: Double, cut: (Float, Float) -> Float, out: IntArray): IntArray {
        out.fill(0)
        if (!done) return out
        val kept = kotlin.math.min(cut(cx, cz), pose.rangeFade(cx, cy, cz, r))
        val fade = kotlin.math.min(kept, occFade)
        val on = fade > .02f && pose.sees(cx, cy, cz, r)
        // (cut away for the camera, it keeps its shadow, as a building does)
        val near = kept > .5f && (on || pose.sees(cx, cy, cz, r + 300f))
        layer.update(look, dark, on, fade, near, castFade = 1f)
        if (on) glow.update(dark, seconds)
        lamps.update(dark, on && near)
        shown = on
        if (on) { out[0] = layer.draws; out[1] = layer.triangles }
        if (near) out[2] = layer.casters
        if (hasCars) {
            val cd = pose.distance(cx, 20f, cz) - r
            val carsOn = on && cd < TownStore.CAR_RANGE
            val midOn = carsOn && cd < TownStore.CAR_MID
            val farOn = carsOn && !midOn
            carsMid.update(look, dark, midOn, fade, midOn && cd < TownStore.CAR_SHADOWS)
            carsFar.update(look, dark, farOn, fade, farOn && cd < TownStore.CAR_SHADOWS)
            val l = if (midOn) carsMid else if (farOn) carsFar else null
            if (l != null) { out[0] += l.draws; out[1] += l.triangles; if (cd < TownStore.CAR_SHADOWS) out[2] += l.casters }
        }
        return out
    }

    fun destroy() { job?.cancel(true); layer.destroy(); carsMid.destroy(); carsFar.destroy(); lamps.destroy() }

    private class Built(val parts: List<CinemaLayer.Packed>, val box: FloatArray, val lights: List<CinemaLight>, val mid: List<PropLayer.Packed>, val far: List<PropLayer.Packed>)
}
