package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.lifemax.rpg.feature.habits.vehicle.CarPose
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.util.concurrent.Future
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The town's cars drawn ([TownExtra], docs/VEHICLES.md): each car of [cars] (the [VehicleWorld] the game steps) built from
 * its .glb on the town's workers ([VehicleKit]), uploaded into a layer of its own and stood every frame where the physics
 * puts it: the body by its pose, each wheel spun and steered and risen with its suspension, the doors swung, the steering
 * wheel turned; the lamps lit (head and tail lights after dusk on the car being driven, with two real spot lights on
 * the road; brake, reverse and turn signals as it is driven). Past the delivery's suggested distances the mid and far
 * models stand in (static). [file] reads a car's file (its folder's, relative to assets/vehicles).
 */
internal class TownCars(
    private val kit: TownExtra.Kit,
    private val cars: () -> VehicleWorld?,
    private val file: (String) -> ByteArray?,
) : TownExtra {
    private class Built(val near: VehicleModel, val nearPacked: List<List<CinemaLayer.Packed>>, val mid: List<CinemaLayer.Packed>?, val far: List<CinemaLayer.Packed>?)

    private inner class Draw(val car: VehicleWorld.Car) {
        val spec = car.spec
        var job: Future<Built>? = null
        var model: VehicleModel? = null
        val near = PropLayer(kit.engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true).also { it.seeThrough = false }
        val mid = PropLayer(kit.engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true).also { it.seeThrough = false }
        val far = PropLayer(kit.engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true).also { it.seeThrough = false }
        /** Per part: its first batch in [near] and how many. */
        var partStart = IntArray(0); var partCount = IntArray(0)
        /** The lamps' batches: their material instances and roles. */
        val lampMi = ArrayList<MaterialInstance>(); val lampRole = ArrayList<String>(); val lampSide = ArrayList<Int>()
        val pose = CarPose(spec)
        // node matrices this frame (street units), and which frame each was made in
        var nodeM: Array<FloatArray> = emptyArray()
        var nodeStamp = IntArray(0)
        val carM = FloatArray(16)
        var lights = IntArray(0)
        var ready = false
        // the node indices of the moving parts, by kind
        var wheelSlot = IntArray(0)
        var doorSlot = IntArray(0)
    }

    private val draws = ArrayList<Draw>()
    private var stamp = 0
    private val a = FloatArray(16)
    private val b = FloatArray(16)
    private val tmp = FloatArray(16)
    private var seen: VehicleWorld? = null

    override val pending: Boolean get() = cars()?.cars?.any { c -> draws.none { it.car === c && it.ready } } == true
    override val report: String get() = "cars: ${draws.count { it.ready }} of ${draws.size} built, " +
        "${draws.sumOf { it.model?.triangles ?: 0 }} triangles near; physics step ${"%.3f".format(java.util.Locale.ROOT, cars()?.stepMs ?: 0f)} ms, ${cars()?.staticCount ?: 0} streamed statics"

    override fun step() {
        val w = cars()
        if (w !== seen) { for (d in draws) destroy(d); draws.clear(); seen = w }
        if (w == null) return
        // (a car the world parked since: its draw made; one gone: freed)
        for (c in w.cars) if (draws.none { it.car === c }) draws += Draw(c)
        val it = draws.iterator()
        while (it.hasNext()) { val d = it.next(); if (d.car !in w.cars) { destroy(d); it.remove() } }
        for (d in draws) {
            if (d.ready) continue
            val j = d.job
            if (j == null) { d.job = kit.pool.submit(java.util.concurrent.Callable { build(d) }); continue }
            if (!j.isDone) continue
            val built = runCatching { j.get() }.getOrNull()
            if (built == null) { d.ready = true; continue }
            upload(d, built)
        }
    }

    private fun build(d: Draw): Built {
        val s = d.spec
        val glb = GlbFile.read(file("${s.folder}/${s.near}") ?: error("no ${s.near}"))
        val m = VehicleKit.build(glb, s, d.car.colour)
        val packed = m.parts.map { p -> p.meshes.mapNotNull { (_, mesh) -> PropLayer.Packing.packLocal(mesh)?.let { CinemaLayer.Packed(it, true, true, CinemaPart.Role.BODY) } } }
        fun whole(f: String?): List<CinemaLayer.Packed>? = f?.let { file("${s.folder}/$it") }?.let { bytes ->
            VehicleKit.build(GlbFile.read(bytes), s, d.car.colour, animated = false).parts.flatMap { p -> p.meshes.mapNotNull { (_, mesh) -> PropLayer.Packing.packLocal(mesh)?.let { CinemaLayer.Packed(it, true, true, CinemaPart.Role.BODY) } } }
        }
        return Built(m, packed, whole(s.mid), whole(s.far))
    }

    private fun upload(d: Draw, b: Built) {
        val m = b.near
        d.model = m
        d.partStart = IntArray(m.parts.size); d.partCount = IntArray(m.parts.size)
        for ((i, p) in m.parts.withIndex()) {
            d.partStart[i] = d.near.batchCount
            for (pk in b.nearPacked[i]) {
                val mi = d.near.upload(pk.packed, pk.casts, pk.tiled)
                if (p.lamp != null) { d.lampMi += mi; d.lampRole += p.lamp; d.lampSide += if (p.name.endsWith("_l")) -1 else 1 }
            }
            d.partCount[i] = d.near.batchCount - d.partStart[i]
        }
        b.mid?.forEach { d.mid.upload(it.packed, it.casts, it.tiled) }
        b.far?.forEach { d.far.upload(it.packed, it.casts, it.tiled) }
        d.nodeM = Array(m.local.size) { FloatArray(16) }
        d.nodeStamp = IntArray(m.local.size)
        d.wheelSlot = IntArray(m.local.size) { i -> WHEELS.indexOfFirst { m.names[i].endsWith(it) } }
        d.doorSlot = IntArray(m.local.size) { i -> d.spec.doors.indexOf(m.names[i]) }
        // the headlamps' real lights: two spots, off until the car's head lights are
        d.lights = IntArray(2) {
            val e = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.FOCUSED_SPOT).color(1f, .93f, .78f).intensity(0f).falloff(HEAD_REACH).castShadows(false)
                .spotLightCone(.18f, .42f).position(0f, 0f, 0f).direction(0f, 0f, 1f).build(kit.engine, e)
            kit.scene.addEntity(e)
            e
        }
        d.ready = true
    }

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out.fill(0)
        val w = cars() ?: return out
        w.lights(f.dark)
        stamp++
        val blink = (f.seconds * 1.5 % 1.0) < .5
        val lm = kit.engine.lightManager
        for (d in draws) {
            if (!d.ready || d.model == null) continue
            d.car.pose.read(d.pose)
            val p = d.pose
            // (the head and tail lights by this frame's darkness, set just now: [VehicleWorld.lights])
            p.head = d.car.headlights
            val r = d.spec.footprint[0] * VehicleWorld.U + 20f
            val dist = f.pose.distance(p.x, p.y + 30f, p.z)
            val seen = f.pose.sees(p.x, p.y + 30f, p.z, r)
            val midAt = d.spec.midM * VehicleWorld.U; val farAt = d.spec.farM * VehicleWorld.U
            val useNear = seen && dist < midAt
            val useMid = seen && dist >= midAt && dist < farAt && d.mid.batchCount > 0
            val useFar = seen && dist < FAR_SHOWN && !useNear && !useMid && d.far.batchCount > 0
            carMatrix(p, d.carM)
            d.near.update(f.look, f.dark, useNear, 1f, useNear && dist < SHADOWS)
            d.mid.update(f.look, f.dark, useMid, 1f, useMid && dist < SHADOWS)
            d.far.update(f.look, f.dark, useFar, 1f, false)
            if (useMid) d.mid.setTransform(d.carM)
            if (useFar) d.far.setTransform(d.carM)
            if (useNear) { stand(d); out[0] += d.near.draws; out[1] += d.near.triangles; out[2] += d.near.casters }
            else if (useMid) { out[0] += d.mid.draws; out[1] += d.mid.triangles }
            else if (useFar) { out[0] += d.far.draws; out[1] += d.far.triangles }
            // the lamps over the layer's own night glow
            if (useNear) for (k in d.lampMi.indices) {
                val on = when (d.lampRole[k]) {
                    "head" -> if (p.head) 1f else 0f
                    "tail" -> if (p.head) .55f else 0f
                    "brake" -> if (p.brake) 1f else if (p.head) .3f else 0f
                    "reverse" -> if (p.reverse) 1f else 0f
                    "turn" -> if (p.signal != 0 && p.signal == d.lampSide[k] && blink) 1f else 0f
                    else -> 0f
                }
                d.lampMi[k].setParameter("glow", LAMP_GLOW, LAMP_GLOW, LAMP_GLOW, on)
            }
            // the headlamps' light on the road
            val headOn = p.head && seen && dist < LIGHT_SHOWN
            for ((k, e) in d.lights.withIndex()) {
                val li = lm.getInstance(e)
                if (li == 0) continue
                lm.setIntensity(li, if (headOn) HEAD_LUMENS else 0f)
                if (!headOn) continue
                val n = d.spec.node(if (k == 0) "light_head_l" else "light_head_r")
                val mx = n?.x ?: (if (k == 0) .7f else -.7f); val my = n?.y ?: .8f; val mz = n?.z ?: 2.4f
                GlbFile.point(d.carM, mx * VehicleWorld.U, my * VehicleWorld.U, (mz + .15f) * VehicleWorld.U, tmp, 0)
                lm.setPosition(li, tmp[0], tmp[1], tmp[2])
                // forward and a little down (the beam meets the road some 20 m ahead)
                GlbFile.dir(d.carM, 0f, -.09f, 1f, tmp, 3)
                val l = sqrt(tmp[3] * tmp[3] + tmp[4] * tmp[4] + tmp[5] * tmp[5]).coerceAtLeast(1e-6f)
                lm.setDirection(li, tmp[3] / l, tmp[4] / l, tmp[5] / l)
            }
        }
        return out
    }

    /** Stands every part of the near model where its node is this frame. */
    private fun stand(d: Draw) {
        val m = d.model ?: return
        for ((i, part) in m.parts.withIndex()) {
            val mat = if (part.node < 0) d.carM else node(d, part.node)
            val s = d.partStart[i]
            for (k in 0 until d.partCount[i]) d.near.setBatchTransform(s + k, mat)
        }
    }

    /** Node [i]'s matrix this frame: its parent's, its rest, its own motion (memoised a frame). */
    private fun node(d: Draw, i: Int): FloatArray {
        val out = d.nodeM[i]
        if (d.nodeStamp[i] == stamp) return out
        val m = d.model!!
        val par = m.parent[i]
        val pm = if (par < 0) d.carM else node(d, par)
        // the motion in the node's own frame
        motion(d, i, a)
        GlbFile.mulInto(m.local[i], a, b)
        GlbFile.mulInto(pm, b, out)
        d.nodeStamp[i] = stamp
        return out
    }

    /** Node [i]'s own motion (column-major, its frame): a wheel's spin and rise, a pivot's steer, a door's swing, the steering wheel's turn. */
    private fun motion(d: Draw, i: Int, o: FloatArray) {
        identity(o)
        val m = d.model!!
        val name = m.names[i]
        val p = d.pose
        val u = VehicleWorld.U
        val w = d.wheelSlot[i]
        when {
            name.startsWith("steer_") && w >= 0 -> { rotY(o, p.steer[w]); o[13] = p.rise[w] * u }
            name.startsWith("wheel_") && w >= 0 -> {
                rotX(o, p.spin[w])
                // (a rear wheel rises itself; a front one with its steering pivot)
                val par = m.parent[i]
                if (par < 0 || !m.names[par].startsWith("steer_")) o[13] = p.rise[w] * u
            }
            name.startsWith("door_") || name == "tailgate" -> {
                val k = d.doorSlot[i]
                val open = if (k >= 0 && k < p.doors.size) p.doors[k] else 0f
                if (open > 0f) {
                    val deg = d.spec.doorOpen[name] ?: (if (name.endsWith("_l")) -66f else 66f)
                    val ax = d.spec.node(name)?.axis ?: floatArrayOf(0f, 1f, 0f)
                    rotAxis(o, ax[0], ax[1], ax[2], Math.toRadians((deg * open).toDouble()).toFloat())
                }
            }
            name == "steering_wheel" -> {
                val ax = d.spec.node(name)?.axis ?: floatArrayOf(0f, 0f, 1f)
                rotAxis(o, ax[0], ax[1], ax[2], p.wheel)
            }
        }
    }

    private fun carMatrix(p: CarPose, o: FloatArray) {
        val x = p.qx; val y = p.qy; val z = p.qz; val w = p.qw
        o[0] = 1f - 2f * (y * y + z * z); o[1] = 2f * (x * y + w * z); o[2] = 2f * (x * z - w * y); o[3] = 0f
        o[4] = 2f * (x * y - w * z); o[5] = 1f - 2f * (x * x + z * z); o[6] = 2f * (y * z + w * x); o[7] = 0f
        o[8] = 2f * (x * z + w * y); o[9] = 2f * (y * z - w * x); o[10] = 1f - 2f * (x * x + y * y); o[11] = 0f
        o[12] = p.x; o[13] = p.y; o[14] = p.z; o[15] = 1f
    }

    private fun identity(o: FloatArray) { o.fill(0f); o[0] = 1f; o[5] = 1f; o[10] = 1f; o[15] = 1f }
    private fun rotX(o: FloatArray, t: Float) { val c = cos(t); val s = sin(t); o[5] = c; o[6] = s; o[9] = -s; o[10] = c }
    private fun rotY(o: FloatArray, t: Float) { val c = cos(t); val s = sin(t); o[0] = c; o[2] = -s; o[8] = s; o[10] = c }
    private fun rotAxis(o: FloatArray, ax: Float, ay: Float, az: Float, t: Float) {
        val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
        val x = ax / l; val y = ay / l; val z = az / l
        val c = cos(t); val s = sin(t); val k = 1f - c
        o[0] = c + x * x * k; o[1] = y * x * k + z * s; o[2] = z * x * k - y * s
        o[4] = x * y * k - z * s; o[5] = c + y * y * k; o[6] = z * y * k + x * s
        o[8] = x * z * k + y * s; o[9] = y * z * k - x * s; o[10] = c + z * z * k
    }

    private fun destroy(d: Draw) {
        d.job?.cancel(true)
        d.near.destroy(); d.mid.destroy(); d.far.destroy()
        val em = EntityManager.get()
        for (e in d.lights) { kit.scene.remove(e); kit.engine.destroyEntity(e); em.destroy(e) }
        d.lights = IntArray(0)
    }

    override fun destroy() { for (d in draws) destroy(d); draws.clear() }

    companion object {
        private val WHEELS = arrayOf("_fl", "_fr", "_rl", "_rr")
        /** Shadows within this (street units), the far model to [FAR_SHOWN], the headlamps' light within [LIGHT_SHOWN]. */
        const val SHADOWS = 2400f
        const val FAR_SHOWN = 9000f
        const val LIGHT_SHOWN = 4000f
        /** A lit lens's glow, and a headlamp's light: lumens (the town's scale: a street lamp's is 52,000) and reach (units). */
        const val LAMP_GLOW = 2.4f
        const val HEAD_LUMENS = 90000f
        const val HEAD_REACH = 900f

        /** Adds the cars [cars] gives (TownPlay's) to [town], and what they break ([TownWreck]); [file] reads assets/vehicles/<path>. */
        fun attach(town: FilamentTown, cars: () -> VehicleWorld?, file: (String) -> ByteArray?): TownCars =
            town.addExtra { TownCars(it, cars, file) }.also { TownWreck.attach(town, cars) }
    }
}
