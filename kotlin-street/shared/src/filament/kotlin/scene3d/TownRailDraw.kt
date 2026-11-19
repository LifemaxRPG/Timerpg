package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.RailCar
import com.lifemax.rpg.feature.habits.domain.RailCrossing
import com.lifemax.rpg.feature.habits.domain.RailNet
import com.lifemax.rpg.feature.habits.domain.RailScene
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TrainNow
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The railroad in [FilamentTown] (docs/RAIL.md): what [RailScene] says stands in [year] drawn with [RailKit]'s meshes,
 * and its trains run by the day's clock. Static (built once on the engine's thread: a few dozen draws): every track,
 * old bed and trail, the crossings' decks, signs and gate bases, the depot (open, boarded up or reused). Moving: each
 * crossing's lamps (flashing while a train is near) and gate arms (down while it passes), and the trains (a layer per
 * car, stood on the track each frame by its two trucks). The depot is solid for the open world's walk.
 */
internal class TownRailDraw(
    private val engine: Engine, private val scene: Scene, private val lit: Material, private val proxy: Material, private val textures: StreetTextures,
    private val town: Town, val net: RailNet, val year: Int, collision: TownCollision?,
) {
    val rail = RailScene(net, year)
    private val static = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)

    private inner class Crossing(val c: RailCrossing, val gear: String) {
        val lampL = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
        val lampR = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
        val arms = ArrayList<Pair<PropLayer, FloatArray>>() // the layer, its pivot x, y, z and the arm's down direction x, z
        var angle = 90f
    }
    private val crossings = ArrayList<Crossing>()
    /** The crossing's signs stand this far from the track's centre line (clear of a train and its bank). */
    private val SIGN_OFF = 105f
    private val cars = HashMap<String, PropLayer>()
    private val used = HashSet<String>()
    private val m4 = FloatArray(16)
    private val pa = FloatArray(4); private val pb = FloatArray(4)
    private var lastSeconds = Double.NaN
    /** The trains in town at the last frame (for previews and the report). */
    var trains: List<TrainNow> = emptyList(); private set
    /** Draws and triangles of the last frame. */
    var draws = 0; private set
    var triangles = 0; private set

    init {
        val m = MeshBuilder()
        // the modern line has concrete ties (relaid in the 1980s); the old lines wood
        val concrete = year >= 1985
        // the roads out of town: the main street on into the country (asphalt, a dashed middle, gravel shoulders)
        for (r in net.rail.roadsOut) RailKit.countryRoad(m, r)
        for (d in rail.drawn) RailKit.track(m, d.pts, d.state, concrete && d.kind == "main", siding = d.kind == "siding", wet = ::overWater)
        // the crossings: a deck over the road, a crossbuck on the road's right before the track each way, lights and gates
        val posts = ArrayList<FloatArray>()
        val signs = HashMap<Boolean, MutableList<FloatArray>>()
        val bases = ArrayList<FloatArray>()
        for (c in rail.crossings) {
            val gear = c.gear(year)
            val road = nearestRoad(c.x, c.z)
            val half = (road?.second ?: 89f)
            val rx = road?.first?.get(0) ?: -c.dz; val rz = road?.first?.get(1) ?: c.dx
            // (the deck runs along the track over the road's width)
            RailKit.crossingDeck(m, c.x, c.z, c.dx, c.dz, rx, rz, half)
            val cr = Crossing(c, gear)
            // the track's left (its normal) and the road's: each sign stands on the road's right edge for the traffic
            // coming to the track, SIGN_OFF short of the track's centre line
            val nx = -c.dz; val nz = c.dx
            val rnx = -rz; val rnz = rx
            val rn = rx * nx + rz * nz
            for (s in intArrayOf(1, -1)) {
                // the side of the track the traffic comes from: s along the track's normal; it heads across, -s
                val dirIn = if (rn * s >= 0f) -1f else 1f
                val hx = dirIn * rx; val hz = dirIn * rz
                val rightX = -hz; val rightZ = hx
                val v = half + 14f
                val off = (rightX * rnx + rightZ * rnz) * v
                val u = if (kotlin.math.abs(rn) < .2f) s * 120f else (s * SIGN_OFF - v * (rightX * nx + rightZ * nz)) / rn
                val px = c.x + rx * u + rightX * v; val pz = c.z + rz * u + rightZ * v
                if (off.isNaN()) continue
                // faces the oncoming traffic: local +z against its heading
                val ox = -hx; val oz = -hz
                val at = floatArrayOf(px, pz, oz, -ox, ox, oz, 0f)
                signs.getOrPut(gear != "crossbuck") { ArrayList() } += at
                if (gear != "crossbuck") {
                    cr.lampL.addMesh(RailKit.lamp(true, true), listOf(at), casts = false)
                    cr.lampR.addMesh(RailKit.lamp(false, true), listOf(at), casts = false)
                }
                if (gear == "gates") {
                    // the gate's base just before the sign, its arm across the traffic's lane (to its left when down)
                    val bx = px + ox * 16f; val bz = pz + oz * 16f
                    bases += floatArrayOf(bx, bz, oz, -ox, ox, oz, 0f)
                    val arm = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
                    arm.addMesh(RailKit.gateArm(half - 6f), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
                    cr.arms += arm to floatArrayOf(bx, 40f, bz, -rightX, -rightZ)
                }
                posts += floatArrayOf(px, pz)
            }
            crossings += cr
        }
        val sm = m.build()
        if (sm.vertexCount > 0) static.addMesh(sm, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
        for ((lights, at) in signs) static.addMesh(RailKit.crossbuck(lights), at, casts = true)
        if (bases.isNotEmpty()) static.addMesh(RailKit.gateBase(), bases, casts = true)
        // the depot, in its rect, its front (the platform) to the track
        val d = net.rail.depot
        val ds = rail.depotState
        if (d != null && ds != "none") {
            val cx = (d.rect[0] + d.rect[2]) / 2f; val cz = (d.rect[1] + d.rect[3]) / 2f
            var ox = d.x - cx; var oz = d.z - cz
            val along = ox * d.dx + oz * d.dz
            ox -= along * d.dx; oz -= along * d.dz
            val toTrack = hypot(ox, oz).coerceAtLeast(1f)
            ox /= toTrack; oz /= toTrack
            // (the building stands back from the track by its depth's half and the platform)
            val reuse = if (net.linksAt("0,0").any { it.trail != null && it.trail <= year }) "TRAIL MUSEUM" else "ANTIQUES"
            static.addMesh(RailKit.depot(net.home.name, ds, toTrack, reuse), listOf(floatArrayOf(cx, cz, oz, -ox, ox, oz, 0f)), casts = true)
            collision?.setExtra("rail-depot", floatArrayOf(cx, cz, oz, -ox, RailKit.DEPOT_L / 2f + 2f, RailKit.DEPOT_D / 2f + 2f), SolidKind.BUILDING, "depot")
        }
        if (collision != null && posts.isNotEmpty()) {
            val b = FloatArray(posts.size * 6)
            for ((i, p) in posts.withIndex()) { b[i * 6] = p[0]; b[i * 6 + 1] = p[1]; b[i * 6 + 2] = 1f; b[i * 6 + 3] = 0f; b[i * 6 + 4] = 3f; b[i * 6 + 5] = 3f }
            collision.setExtra("rail-signs", b, SolidKind.PROP, "crossing")
        }
    }

    /** Is ([x], [z]) over the river (or a little way in from its bank)? */
    private fun overWater(x: Float, z: Float): Boolean {
        for (poly in town.water) {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[j]
                if ((a.z > z) != (b.z > z) && x < (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }

    /** The road nearest ([x], [z]): its way (unit x, z) and half width. */
    private fun nearestRoad(x: Float, z: Float): Pair<FloatArray, Float>? {
        var best = Float.MAX_VALUE; var out: Pair<FloatArray, Float>? = null
        for (r in town.roads) for (i in 0 until r.pts.size - 1) {
            val a = r.pts[i]; val b = r.pts[i + 1]
            val dx = b.x - a.x; val dz = b.z - a.z; val l2 = dx * dx + dz * dz
            if (l2 < 1e-6f) continue
            val t = (((x - a.x) * dx + (z - a.z) * dz) / l2).coerceIn(0f, 1f)
            val d = hypot(a.x + dx * t - x, a.z + dz * t - z)
            if (d < best) { best = d; val l = kotlin.math.sqrt(l2); out = floatArrayOf(dx / l, dz / l) to r.width / 2f }
        }
        for (r in net.rail.roadsOut) {
            val cz = (r[1] + r[3]) / 2f
            if (x < r[0] || x > r[2]) continue
            val d = kotlin.math.abs(z - cz)
            if (d < best) { best = d; out = floatArrayOf(1f, 0f) to RailKit.COUNTRY_HALF + 20f }
        }
        return out
    }

    /** The train cars' layers, made as they are first needed (a car kind's mesh is shared through [RailKit.car]'s cache). */
    private fun carLayer(key: String, kind: RailCar, livery: String): PropLayer = cars.getOrPut(key) {
        PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true).also { it.addMesh(RailKit.car(kind, livery), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true) }
    }

    private fun place(x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        // columns: X = a, Y = b, Z = a x b, then the place
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        m4[0] = ax; m4[1] = ay; m4[2] = az; m4[3] = 0f
        m4[4] = bx; m4[5] = by; m4[6] = bz; m4[7] = 0f
        m4[8] = cx; m4[9] = cy; m4[10] = cz; m4[11] = 0f
        m4[12] = x; m4[13] = y; m4[14] = z; m4[15] = 1f
    }

    /** This frame: the trains of [hour] (the day's clock), the crossings' lamps and gates, everything with [look] and [dark]. */
    fun frame(look: FloatArray, dark: Float, hour: Float, seconds: Double) {
        // (the first frame: the gates stand as they should at once)
        val first = lastSeconds.isNaN()
        val dt = if (first) 1f else (seconds - lastSeconds).toFloat().coerceIn(0f, .25f)
        lastSeconds = seconds
        draws = 0; triangles = 0
        static.update(look, dark)
        draws += static.draws; triangles += static.triangles
        val now = rail.trains(hour * 60.0)
        trains = now
        // the trains: each car stood on its two trucks
        used.clear()
        for ((ti, tr) in now.withIndex()) {
            for ((ci, car) in tr.cars.withIndex()) {
                val s = tr.carAt(ci)
                val half = car.length / 2f - 34f
                tr.path.at(s + tr.dir * half, pa); tr.path.at(s - tr.dir * half, pb)
                var hx = pa[0] - pb[0]; var hz = pa[1] - pb[1]
                val l = hypot(hx, hz)
                if (l < 1e-3f) { hx = pa[2] * tr.dir; hz = pa[3] * tr.dir } else { hx /= l; hz /= l }
                val livery = if (!tr.passenger && car == RailCar.DIESEL) "freight" else if (car == RailCar.BOXCAR) (if ((ti + ci) % 3 == 0) "b" else "") else ""
                val key = "$ti/$ci/$car/$livery"
                val layer = carLayer(key, car, livery)
                place((pa[0] + pb[0]) / 2f, RailKit.RAIL_TOP, (pa[1] + pb[1]) / 2f, hx, 0f, hz, 0f, 1f, 0f)
                layer.setTransform(m4)
                layer.update(look, dark)
                used += key
                draws += layer.draws; triangles += layer.triangles
            }
        }
        for ((k, layer) in cars) if (k !in used) layer.update(look, dark, shown = false)
        // the crossings: lamps flash in turn (about once a second each) and the gates come down while a train is near
        val flash = ((seconds * 1.6).toLong() and 1L) == 0L
        for (cr in crossings) {
            val active = rail.crossingActive(cr.c, now)
            cr.lampL.update(look, if (active && flash) 1f else 0f)
            cr.lampR.update(look, if (active && !flash) 1f else 0f)
            val want = if (active) 0f else 88f
            cr.angle = if (first) want else cr.angle + (want - cr.angle).coerceIn(-dt * 12f, dt * 12f)
            val a = Math.toRadians(cr.angle.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            for ((layer, p) in cr.arms) {
                // local x (the arm) towards the lane when down, up when raised; local y square to it in the same plane
                val ax = c * p[3]; val ay = s; val az = c * p[4]
                val bx = -s * p[3]; val by = c; val bz = -s * p[4]
                place(p[0], p[1], p[2], ax, ay, az, bx, by, bz)
                layer.setTransform(m4)
                layer.update(look, dark)
                draws += layer.draws
            }
            draws += cr.lampL.draws + cr.lampR.draws
        }
    }

    fun destroy() {
        static.destroy()
        for (c in crossings) { c.lampL.destroy(); c.lampR.destroy(); for ((l, _) in c.arms) l.destroy() }
        for (l in cars.values) l.destroy()
        cars.clear()
    }
}
