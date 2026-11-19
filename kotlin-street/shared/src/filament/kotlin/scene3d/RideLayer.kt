package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.RailCar
import com.lifemax.rpg.feature.habits.domain.RailRide
import com.lifemax.rpg.feature.habits.domain.WildChunks
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The world a ride passes through, added to the town's own scene ([FilamentTown.startRide]; docs/RAIL.md "Riding"): the
 * fields under the whole route, the track at world scale, the wilderness between the towns grown from the world's own
 * seed ([WildChunks], src/world/wild.ts), the towns near the home town on the way as their streets and plots (low boxes,
 * a far view of them) with their depots, and the train itself, its cars stood on the track each frame. The town's light,
 * sky and haze are the town's (the pose is an orbit round the train).
 */
internal class RideLayer(
    private val engine: Engine, private val scene: Scene, private val lit: Material, private val proxy: Material, private val textures: StreetTextures,
    val ride: RailRide, private val consist: List<RailCar>,
) {
    private val parts = ArrayList<PropLayer>()
    /** Each part's ground circle (x, z, radius; radius 0: always drawn), for the ride's own draw distance. */
    private val spots = ArrayList<FloatArray>()
    private val cars = ArrayList<PropLayer>()
    private val m4 = FloatArray(16)
    private val pa = FloatArray(4); private val pb = FloatArray(4)
    /** The ride's length in seconds (about 1200 street units a second, between 20 and 75 s). */
    val seconds: Float = (ride.path.length / 1200f).coerceIn(20f, 75f)
    var triangles = 0; private set

    private fun layer(mesh: PropMesh? = null): PropLayer = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true).also {
        parts += it
        val b = mesh?.bounds
        spots += if (b == null) floatArrayOf(0f, 0f, 0f) else floatArrayOf((b[0] + b[3]) / 2f, (b[2] + b[5]) / 2f, hypot(b[3] - b[0], b[5] - b[2]) / 2f)
    }
    private fun add(mesh: PropMesh, casts: Boolean) { layer(mesh).addMesh(mesh, listOf(ID), casts = casts) }

    init {
        val net = ride.net
        val p = ride.path.pts
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) { x0 = min(x0, p[i * 2]); x1 = max(x1, p[i * 2]); z0 = min(z0, p[i * 2 + 1]); z1 = max(z1, p[i * 2 + 1]) }
        // the fields: one tiled quad under the whole route, a hair under the town's own ground
        layer().addMesh(fields(x0 - 6000f, z0 - 6000f, x1 + 6000f, z1 + 6000f), listOf(ID), casts = false, tiled = true)
        // the track, in pieces (each culled on its own)
        val chunk = 120
        val n = p.size / 2
        var i = 0
        while (i < n - 1) {
            val j = min(n - 1, i + chunk)
            val m = MeshBuilder()
            RailKit.track(m, p.copyOfRange(i * 2, j * 2 + 2), "track", ride.year >= 1985)
            add(m.build(), true)
            i = j
        }
        // the towns near enough to have their plans: streets and plots as a far view, the depot real
        for (t in net.towns) {
            val plan = t.plan ?: continue
            if (t.id == "0,0") continue
            val r = plan.rect
            if (ride.path.project((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f).second > 9000f) continue
            val m = MeshBuilder()
            val road = Paint.swatch(Paint.c(0x4C4C58)); val pave = Paint.swatch(Paint.c(0xB8B0A0))
            for (q in plan.roads) m.box(q[0] - 20f, 0f, q[1] - 20f, q[2] + 20f, .4f, q[3] + 20f) { s, _, _ -> if (s == MeshBuilder.Side.TOP) pave else null }
            for (q in plan.roads) m.box(q[0], 0f, q[1], q[2], .6f, q[3]) { s, _, _ -> if (s == MeshBuilder.Side.TOP) road else null }
            // (a few shared pictures, stretched over the boxes: a far view; one picture each keeps the atlas small)
            val walls = listOf(Paint.WHITE, Paint.BEIGE, Paint.WOOD, Paint.CONCRETE).map { r -> Paint.panel(24, 16, r, 3).also { im -> for (y in 4 until 12 step 6) for (x in 3 until 21 step 6) { im.rect(x, y, x + 2, y + 3, Paint.c(0x7FB8C8)); im.glow(x, y, x + 2, y + 3, 150) } } }
            val roofs = listOf(Paint.top(16, 16, Paint.TERRACOTTA), Paint.top(16, 16, Paint.GREY))
            for ((k, q) in plan.lots.withIndex()) {
                // a house, a shop or a block of flats on its plot: a box of its kind, set in from the plot's edges
                val kind = q.getOrElse(4) { 0f }.toInt()
                val inset = 30f
                val h = when (kind) { 1 -> 70f; 2 -> 120f; else -> 55f }
                val wall = when (kind) { 1 -> walls[1]; 2 -> walls[3]; else -> walls[k % 3] }
                val bx0 = q[0] + inset; val bz0 = q[1] + inset; val bx1 = q[2] - inset; val bz1 = q[3] - inset
                if (bx1 - bx0 < 20f || bz1 - bz0 < 20f) continue
                // (the ride's camera runs beside the track: what stands by it is left out, as a rail yard's open ground)
                if (ride.path.project((bx0 + bx1) / 2f, (bz0 + bz1) / 2f).second < CLEAR) continue
                m.box(bx0, 0f, bz0, bx1, h, bz1) { s, _, _ -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> roofs[if (kind == 0) 0 else 1]; else -> wall } }
            }
            val lmWall = Paint.panel(24, 16, Paint.TERRACOTTA, 5)
            // (the big lots, a superstore's, are car parks and fields from afar: only the landmarks' buildings)
            for (q in plan.landmarks.filter { it[2] - it[0] < 1400f && it[3] - it[1] < 1400f && ride.path.project((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f).second > CLEAR + (it[2] - it[0]) / 2f }) m.box(q[0] + 20f, 0f, q[1] + 20f, q[2] - 20f, 90f, q[3] - 20f) { s, _, _ -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> roofs[1]; else -> lmWall } }
            add(m.build(), true)
            t.rail?.depot?.let { d ->
                val cx = (d.rect[0] + d.rect[2]) / 2f; val cz = (d.rect[1] + d.rect[3]) / 2f
                var ox = d.x - cx; var oz = d.z - cz
                val al = ox * d.dx + oz * d.dz; ox -= al * d.dx; oz -= al * d.dz
                val l = hypot(ox, oz).coerceAtLeast(1f); ox /= l; oz /= l
                val st = net.status(t.id, ride.year)
                if (st != 'n') layer(PropMesh(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0), 1, 1, IntArray(1), null, floatArrayOf(cx - 400f, 0f, cz - 400f, cx + 400f, 100f, cz + 400f))).addMesh(RailKit.depot(t.name, if (st == 'p') "open" else if (st == 'f') "closed" else "reused", l), listOf(floatArrayOf(cx, cz, oz, -ox, ox, oz, 0f)), casts = true)
            }
        }
        // the wilderness along the way: the world's own trees (src/world/wild.ts), off the track's bed and out of the
        // towns' plans, a cell of trees as one batch
        val chunkTiles = WildChunks.CHUNK
        val unit = net.scale
        val reach = 2200f
        val cells = HashSet<Long>()
        for (k in 0 until n) {
            val tx = p[k * 2] / unit + net.originX; val ty = p[k * 2 + 1] / unit + net.originY
            val r = (reach / unit / chunkTiles).toInt() + 1
            val cx = floor(tx / chunkTiles).toInt(); val cy = floor(ty / chunkTiles).toInt()
            for (dy in -r..r) for (dx in -r..r) cells += ((cx + dx).toLong() shl 32) or ((cy + dy).toLong() and 0xffffffffL)
        }
        val plans = net.towns.mapNotNull { it.plan?.rect }
        val group = HashMap<Long, MutableList<Pair<PropMesh, FloatArray>>>()
        for (key in cells) {
            val cx = (key shr 32).toInt(); val cy = key.toInt()
            for (w in WildChunks.chunk(net.worldSeed, cx, cy)) {
                val x = ((w.x / 32.0 - net.originX) * unit).toFloat(); val z = ((w.y / 32.0 - net.originY) * unit).toFloat()
                if (plans.any { r -> x > r[0] && x < r[2] && z > r[1] && z < r[3] }) continue
                val (_, off) = ride.path.project(x, z)
                if (off > reach || off < 110f) continue
                val spec = if (w.kind == "tree") TreeKit.forSprite(((w.look * 6).toInt()).coerceIn(0, 5), w.look.toFloat(), 120f + (w.look * 60).toFloat())
                    else TreeKit.forBush((w.look * 5).toInt(), w.look.toFloat(), 30f)
                if (w.kind == "flowers") continue
                val turn = (w.look * 6.28).toFloat()
                val c = kotlin.math.cos(turn); val s = kotlin.math.sin(turn)
                val cell = (floor(x / 3000f).toLong() shl 32) or (floor(z / 3000f).toLong() and 0xffffffffL)
                group.getOrPut(cell) { ArrayList() } += TreeKit.mesh(spec, false) to floatArrayOf(x, z, c, s, -s, c, 0f)
            }
        }
        for (list in group.values) TreeKit.merge(list)?.let { add(it, true) }
        for (car in consist) cars += PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true).also { it.addMesh(RailKit.car(car), listOf(ID), casts = true) }
    }

    /** A tiled grass quad from ([x0], [z0]) to ([x1], [z1]), its picture repeating every 64 texels. */
    private fun fields(x0: Float, z0: Float, x1: Float, z1: Float): PropMesh {
        val s = 64
        val im = PixImg(s, s)
        im.fill(Paint.c(0x5CA84A))
        val rnd = java.util.Random(7)
        for (k in 0 until 260) { val x = rnd.nextInt(s); val y = rnd.nextInt(s); im[x, y] = if (k % 3 == 0) Paint.c(0x4A9240) else if (k % 3 == 1) Paint.c(0x6CB656) else Paint.c(0x3E8236) }
        val rep = s * TEXEL * 2f
        val y = -.8f
        val pos = floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)
        val uv = floatArrayOf(x0 / rep, z0 / rep, x1 / rep, z0 / rep, x1 / rep, z1 / rep, x0 / rep, z1 / rep)
        val nr = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val tn = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        return PropMesh(pos, uv, nr, tn, intArrayOf(0, 2, 1, 0, 3, 2), s, s, im.px, null, floatArrayOf(x0, y, z0, x1, y, z1))
    }

    /** The train's front [s] along the path this frame, and everything with [look] and [dark]. */
    fun frame(look: FloatArray, dark: Float, s: Float) {
        triangles = 0
        // (the ride's own draw distance round the train: the town's haze hides what is past it)
        ride.path.at(s, pa)
        for ((i, l) in parts.withIndex()) {
            val sp = spots[i]
            val on = sp[2] == 0f || hypot(sp[0] - pa[0], sp[1] - pa[1]) - sp[2] < REACH
            l.update(look, dark, shown = on)
            if (on) triangles += l.triangles
        }
        var off = 0f
        for ((i, car) in consist.withIndex()) {
            val mid = s - off - car.length / 2f
            off += car.length + 6f
            val half = car.length / 2f - 34f
            ride.path.at(mid + half, pa); ride.path.at(mid - half, pb)
            var hx = pa[0] - pb[0]; var hz = pa[1] - pb[1]
            val l = hypot(hx, hz)
            if (l < 1e-3f) { hx = pa[2]; hz = pa[3] } else { hx /= l; hz /= l }
            m4.fill(0f)
            m4[0] = hx; m4[2] = hz; m4[5] = 1f; m4[8] = -hz; m4[10] = hx; m4[15] = 1f
            m4[12] = (pa[0] + pb[0]) / 2f; m4[13] = RailKit.RAIL_TOP; m4[14] = (pa[1] + pb[1]) / 2f
            cars[i].setTransform(m4)
            cars[i].update(look, dark)
            triangles += cars[i].triangles
        }
    }

    /** Where the camera looks: the train's middle at [s], and the way the track runs there (x, z, dx, dz). */
    fun focus(s: Float, out: FloatArray) {
        val len = consist.sumOf { (it.length + 6.0) }.toFloat()
        ride.path.at(s - len / 2f, out)
    }

    fun destroy() { for (l in parts) l.destroy(); for (l in cars) l.destroy(); parts.clear(); cars.clear() }

    companion object {
        private val ID = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)
        /** What of the ride's world is drawn round the train (street units). */
        const val REACH = 4500f
        /** Buildings of the towns on the way are left out this near the track (the camera's side of it). */
        const val CLEAR = 900f
    }
}
