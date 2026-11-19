package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownPlace
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A hand-made landmark model (docs/LANDMARKS_GLB.md) in [FilamentTown], as [TownCinema] does the theater: the .glb read
 * and built ([LandmarkKit]) for the town's [year] on a worker, its parts packed there in its slot's frame (the lot's
 * front edge on the row's building line, [place], [setback]), uploaded on the engine's thread into its own culled
 * [PropLayer], lit by [CinemaLayer] and [CinemaLights]; once up, its collision boxes and doors go into the walk's
 * [collision] (as a store's: [TownCollision.setExtra], [TownCollision.setExtraDoors]).
 *
 * The town does not place these yet (the generator's landmark kinds `landmark_glb_*` come later): this is the hook a
 * town with one calls, [bytes] the .glb and [info] its model-info.json, read by the caller (the app's assets, the
 * desktop's files).
 */
internal class TownLandmark(
    engine: Engine, scene: Scene, lit: Material, proxy: Material, textures: StreetTextures,
    val spec: LandmarkSpec, private val bytes: () -> ByteArray, private val info: () -> String?,
    private val sprite: String, private val row: String, private val place: TownPlace, private val setback: Float,
    private val year: Int, private val seed: Int, private val collision: TownCollision?,
) {
    private val layer = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val glow = CinemaLayer(layer)
    private val lamps = CinemaLights(engine, scene)
    private class Built(val parts: List<CinemaLayer.Packed>, val at: FloatArray, val box: FloatArray, val lights: List<CinemaLight>, val solids: FloatArray, val doors: List<LandmarkDoor>)
    private var job: java.util.concurrent.Future<Built>? = null
    private var at: FloatArray = frameAt(0f)
    var done = false; private set
    var shown = false; private set
    var cx = 0f; var cy = 60f; var cz = 0f; var r = 0f

    init {
        val c = place.world(0f, setback + spec.depth / 2f)
        cx = c.x; cz = c.z; r = hypot(spec.width / 2f, spec.depth / 2f) + 60f
    }

    /** The model's frame with its lot's front edge ([front], the model's z) on the slot's building line. */
    private fun frameAt(front: Float): FloatArray = place.world(0f, setback + front).let { p -> floatArrayOf(p.x, p.z, place.rx, place.rz, -place.nx, -place.nz, 0f) }

    fun start(pool: java.util.concurrent.ExecutorService) {
        if (job != null || done) return
        job = pool.submit(java.util.concurrent.Callable {
            val lm = LandmarkKit.build(GlbFile.read(bytes()), spec, year, seed, info = info())
            val a = frameAt(lm.front)
            val m = lm.model
            Built(CinemaLayer.pack(m, a), a, floatArrayOf(m.footprint[0], m.footprint[1], m.footprint[2], m.footprint[3], m.height), m.lights,
                StoreSolids.world(lm.solids, a), lm.doors)
        })
    }

    fun step(): Boolean {
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
        lamps.add(b.lights, at)
        job = null
        val fp = b.box
        val mx = (fp[0] + fp[2]) / 2f; val mz = (fp[1] + fp[3]) / 2f
        cx = at[0] + at[2] * mx + at[4] * mz; cz = at[1] + at[3] * mx + at[5] * mz; cy = fp[4] / 2f
        r = sqrt((fp[2] - fp[0]) * (fp[2] - fp[0]) + (fp[3] - fp[1]) * (fp[3] - fp[1]) + fp[4] * fp[4]) / 2f
        collision?.let { col ->
            col.setExtra(KEY + sprite, b.solids, SolidKind.BUILDING, sprite)
            col.setExtraDoors(KEY + sprite, b.doors.map { d ->
                val wx = at[0] + at[2] * d.x + at[4] * d.z; val wz = at[1] + at[3] * d.x + at[5] * d.z
                val ox = at[2] * d.outX + at[4] * d.outZ; val oz = at[3] * d.outX + at[5] * d.outZ
                TownDoors.standing(sprite, row, PlacementKind.LANDMARK, wx, wz, ox, oz, col)
            })
        }
        done = true
        return true
    }

    fun frame(pose: TownPose, look: FloatArray, dark: Float, seconds: Double, cut: (Float, Float) -> Float, out: IntArray): IntArray {
        out.fill(0)
        if (!done) return out
        val fade = kotlin.math.min(cut(cx, cz), pose.rangeFade(cx, cy, cz, r))
        val on = fade > .02f && pose.sees(cx, cy, cz, r)
        val near = fade > .5f && (on || pose.sees(cx, cy, cz, r + 300f))
        layer.update(look, dark, on, fade, near)
        if (on) glow.update(dark, seconds)
        lamps.update(dark, on && near)
        shown = on
        if (on) { out[0] = layer.draws; out[1] = layer.triangles }
        if (near) out[2] = layer.casters
        return out
    }

    fun destroy() {
        job?.cancel(true); layer.destroy(); lamps.destroy()
        collision?.setExtra(KEY + sprite, null); collision?.setExtraDoors(KEY + sprite, null)
    }

    companion object {
        const val KEY = "landmark:"

    }
}
