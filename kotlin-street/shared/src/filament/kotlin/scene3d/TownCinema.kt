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
    private var job: java.util.concurrent.Future<Pair<List<CinemaLayer.Packed>, FloatArray>>? = null
    /** It is up (uploaded), or it failed (the town goes on without it). */
    var done = false; private set
    /** A sphere round it in the world, its height's middle, once built (a guess before). */
    var cx = 0f; var cy = 60f; var cz = 0f; var r = 0f
    /** Whether it was drawn last frame. */
    var shown = false; private set

    /** Where the model's frame stands: its front's middle, along the street (the art's left-to-right), out towards the road. */
    private val at: FloatArray = place.world(0f, setback).let { p -> floatArrayOf(p.x, p.z, place.rx, place.rz, -place.nx, -place.nz, 0f) }

    init {
        val c = place.world(0f, setback + max(site.info.depth, 300f) / 2f)
        cx = c.x; cz = c.z; r = hypot(max(site.info.width, 300f) / 2f, max(site.info.depth, 300f) / 2f) + 60f
    }

    /** Starts building it on [pool] (once). */
    fun start(pool: java.util.concurrent.ExecutorService) {
        if (job != null || done) return
        val a = at
        job = pool.submit(java.util.concurrent.Callable {
            val m = CinemaKit.build(site.info, seed)
            CinemaLayer.pack(m, a) to floatArrayOf(m.footprint[0], m.footprint[1], m.footprint[2], m.footprint[3], m.height)
        })
    }

    /** Uploads it if its worker is done (the engine's thread); true once it is up. */
    fun step(): Boolean {
        if (done) return true
        val f = job ?: return false
        if (!f.isDone) return false
        val (parts, fp) = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is VirtualMachineError) throw BuildFailure("building the cinema on a worker", c)
            done = true; job = null; return true
        }
        glow.upload(parts)
        job = null
        // its sphere from what was built (the footprint in the model's frame: x along, z out; the height)
        val mx = (fp[0] + fp[2]) / 2f; val mz = (fp[1] + fp[3]) / 2f
        cx = at[0] + at[2] * mx + at[4] * mz; cz = at[1] + at[3] * mx + at[5] * mz; cy = fp[4] / 2f
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
        val fade = kotlin.math.min(cut(cx, cz), pose.rangeFade(cx, cy, cz, r))
        val on = fade > .02f && pose.sees(cx, cy, cz, r)
        val near = fade > .5f && (on || pose.sees(cx, cy, cz, r + 300f))
        layer.update(look, dark, on, fade, near)
        if (on) glow.update(dark, seconds)
        shown = on
        if (on) { out[0] = layer.draws; out[1] = layer.triangles }
        if (near) out[2] = layer.casters
        return out
    }

    fun destroy() { job?.cancel(true); layer.destroy() }
}
