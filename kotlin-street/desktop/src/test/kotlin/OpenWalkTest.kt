import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.MoveTuning
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.presentation.scene.locate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.BitSet
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The open world's walk and collision on the shipped gamma town (app assets: street.json v2 with yards and the cinema,
 * buildings/models.json): what blocks, that the whole town is one walkable ground (streets, yards through their gates,
 * lanes, bridges) a scripted walker crosses without ever ending inside anything, sliding along walls, doors and the people
 * to talk to found from every side, and the cost of a query.
 */
class OpenWalkTest {
    companion object {
        private val dir = File("../app/src/main/assets/town/gamma")
        private val json by lazy { File(dir, "street.json").readText() }
        private val root by lazy { JSONObject(json) }
        val town: Town by lazy { Town.parse(root)!! }
        val yards: TownYards by lazy { TownYards.parse(root) }
        val models: Map<String, ModelShape> by lazy { ModelShape.parseAll(File(dir, "buildings/models.json").readText()) }
        val cinema: CinemaSiteShape? by lazy { CinemaSiteShape.parse(root) }
        val buildMs: Double
        val map: TownCollision
        init {
            val t0 = System.nanoTime()
            map = TownCollision.build(town, yards, models, cinema, com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
            buildMs = (System.nanoTime() - t0) / 1e6
        }
        val doors: TownDoors by lazy { TownDoors(town, yards, map, cinema) }
        const val R = OpenWalk.RADIUS
        /** A back yard counts as reached when this share of its free ground is. */
        const val BACK_REACHED = .8f

        /** The walkable grid (cells of [STEP]) reached from the spawn, 4-connected. */
        const val STEP = 6f
        /**
         * The paths keep this much more than a walker's radius clear (planning with a margin): a slot a cell's centre clears
         * by the radius alone can still be narrower than a walker between two neighbours' fences (b0 and b38's corner).
         */
        const val PLAN_MARGIN = 1.5f
        /** The grid's corner, on whole cells from the world's origin (so its cells do not move as the world's edge does). */
        val gx0 by lazy { kotlin.math.ceil(map.minX / STEP) * STEP }
        val gz0 by lazy { kotlin.math.ceil(map.minZ / STEP) * STEP }
        val cols by lazy { ((map.maxX - gx0) / STEP).toInt() }
        val rows by lazy { ((map.maxZ - gz0) / STEP).toInt() }
        val free: BitSet by lazy {
            val b = BitSet(cols * rows)
            for (j in 0 until rows) for (i in 0 until cols) if (!map.blocked(cx(i), cz(j), R)) b.set(j * cols + i)
            b
        }
        /** The cells a planned path may use: clear of anything by a walker's radius and [PLAN_MARGIN]. */
        val pathFree: BitSet by lazy {
            val b = BitSet(cols * rows)
            for (j in 0 until rows) for (i in 0 until cols) if (free[j * cols + i] && !map.blocked(cx(i), cz(j), R + PLAN_MARGIN)) b.set(j * cols + i)
            b
        }
        fun cx(i: Int) = gx0 + (i + .5f) * STEP
        fun cz(j: Int) = gz0 + (j + .5f) * STEP
        fun cellOf(x: Float, z: Float) = ((z - gz0) / STEP).toInt() * cols + ((x - gx0) / STEP).toInt()

        val spawn by lazy { OpenWalk(town, map, yards, doors).let { it.wx to it.wz } }

        /** BFS over [free] from [from] (to [stopAt], which may itself be the spawn cell a grid shift left within a walker of something): each reached cell's parent (-1 unreached; the start its own). */
        fun bfs(from: Int, stopAt: Int = -1, grid: BitSet = free): IntArray {
            val par = IntArray(cols * rows) { -1 }
            val q = IntArray(cols * rows)
            var h = 0; var t = 0
            par[from] = from; q[t++] = from
            while (h < t) {
                val c = q[h++]
                if (c == stopAt) break
                val i = c % cols; val j = c / cols
                fun go(n: Int) { if (par[n] < 0 && (grid[n] || n == stopAt)) { par[n] = c; q[t++] = n } }
                if (i > 0) go(c - 1); if (i < cols - 1) go(c + 1); if (j > 0) go(c - cols); if (j < rows - 1) go(c + cols)
            }
            return par
        }

        val reach: IntArray by lazy { bfs(cellOf(spawn.first, spawn.second)) }
        fun reached(x: Float, z: Float) = reach[cellOf(x, z)] >= 0
        /** Some free cell within [r] of ([x], [z]) is reached from the spawn. */
        fun reachedNear(x: Float, z: Float, r: Float): Boolean {
            val n = (r / STEP).toInt()
            val c = cellOf(x, z); val i0 = c % cols; val j0 = c / cols
            for (dj in -n..n) for (di in -n..n) { val i = i0 + di; val j = j0 + dj; if (i in 0 until cols && j in 0 until rows && reach[j * cols + i] >= 0) return true }
            return false
        }

        val out = File(System.getProperty("openWalkOut") ?: "build/open-walk").also { it.mkdirs() }
    }

    @Before fun reset() { GameState.motion.vx = 0f; GameState.motion.vy = 0f; GameState.moveX = 0f; GameState.moveLane = 0f }

    @Test fun mapCoversTheTown() {
        val byKind = SolidKind.values().associateWith { k -> (0 until map.size).count { map.kind(it) == k } }
        println("collision: ${map.size} shapes in ${"%.0f".format(buildMs)} ms, grid ${map.cols}x${map.rows}; $byKind")
        // every building is in it, and what its slot covers is solid at its middle (a model's parts or its box)
        // (a store's plot is left to its store as built: scene3d's TownStores adds its shapes, StoreCollisionTest checks them)
        // (the stores' plots and the hand-made landmark models: StoreLots; the capital's 38, b281-b284 and b288-b321, read off graph.landmarks)
        assertEquals((listOf(281, 282, 283, 284) + (288..321)).map { "b$it" }.toSet(), map.stores)
        for (r in town.rows) for (b in r.buildings) {
            if (b.sprite in map.stores) { assertTrue("${b.sprite} is a store", (0 until map.size).none { map.owner(it) == b.sprite }); continue }
            // (a car park is walked over: nothing of it is solid)
            if (b.flat) { assertTrue("${b.sprite} is flat", (0 until map.size).none { map.owner(it) == b.sprite }); continue }
            assertTrue("no shapes for ${b.sprite}", (0 until map.size).any { map.owner(it) == b.sprite && map.kind(it) == SolidKind.BUILDING })
        }
        assertTrue(byKind[SolidKind.FENCE]!! > 300); assertTrue(byKind[SolidKind.HEDGE]!! > 40); assertTrue(byKind[SolidKind.WATER]!! > 50)
        assertTrue(byKind[SolidKind.TREE]!! > 4000); assertTrue(byKind[SolidKind.PROP]!! > 500); assertEquals(2 * town.bridges.size, byKind[SolidKind.PARAPET])
        // the spawn is clear; the water is solid, the bridges' decks are not
        assertTrue(!map.blocked(spawn.first, spawn.second, R))
        for (w in town.water) {
            val x = w.map { it.x }.average().toFloat(); val z = w.map { it.z }.average().toFloat()
            if (town.bridges.none { x >= it.rect[0] && x <= it.rect[2] && z >= it.rect[1] && z <= it.rect[3] }) assertTrue("water at $x,$z", map.blocked(x, z, R))
        }
        for (b in town.bridges) assertTrue("bridge ${b.rect.toList()}", !map.blocked((b.rect[0] + b.rect[2]) / 2f, (b.rect[1] + b.rect[3]) / 2f, R))
        // a gate is a gap in its fence; a fence is solid
        var gates = 0
        for (y in yards.yards) for (it in y.items) {
            val x = (it.rect[0] + it.rect[2]) / 2f; val z = (it.rect[1] + it.rect[3]) / 2f
            if (it.kind == "gate" || it.kind == "sideGate") { gates++; assertTrue("${it.kind} of ${y.sprite} at $x,$z", map.clearance(x, z) > 0f) }
            if (it.kind == "sideFence" || it.kind == "backFence" || it.kind == "hedge") assertTrue("${it.kind} of ${y.sprite}", map.clearance(x, z) <= 0f)
        }
        assertTrue(gates > 100)
    }

    @Test fun theWholeTownIsOneGround() {
        val total = free.cardinality()
        val got = reach.count { it >= 0 }
        println("walkable cells ${total} (of ${cols * rows}), reached from the spawn ${got} (${"%.1f".format(100.0 * got / total)}%)")
        // every row's pavement, every bridge, every door and every person to talk to
        for (r in town.rows) {
            val p = r.pointAt(r.length / 2f, 7f)
            if (!map.blocked(p.x, p.z, R) && !reachedNear(p.x, p.z, 12f)) {
                drawMap("unreached_${r.id}", null, p.x to p.z)
                error("row ${r.id} pavement at ${p.x},${p.z} not reached")
            }
        }
        for (b in town.bridges) assertTrue("bridge", reached((b.rect[0] + b.rect[2]) / 2f, (b.rect[1] + b.rect[3]) / 2f))
        for (d in doors.doors) assertTrue("door of ${d.sprite} at ${d.x},${d.z}", reachedNear(d.x, d.z, 8f))
        for (i in doors.keeperSprites.indices) assertTrue("keeper ${doors.keeperSprites[i]}", reachedNear(doors.keeperX[i], doors.keeperZ[i], 10f))
        // the back yards: through a side gate, a back gate, or round the house where no fence closes the side; most of
        // each one's free ground reached (not just a cell near its middle)
        var backs = 0; var reachedBacks = 0; val shut = ArrayList<String>()
        for (y in yards.yards) {
            val b = y.back ?: continue
            backs++
            var fr = 0; var rc = 0
            var x = b[0] + 3f
            while (x < b[2]) { var z = b[1] + 3f; while (z < b[3]) { val c = cellOf(x, z); if (free[c]) { fr++; if (reach[c] >= 0) rc++ }; z += STEP }; x += STEP }
            val ok = fr == 0 || rc >= fr * BACK_REACHED
            if (ok) reachedBacks++ else shut += "${y.sprite} ($rc of $fr)"
        }
        println("back yards reached $reachedBacks of $backs; shut: $shut")
        assertTrue("back yards mostly shut: $shut", shut.isEmpty())
        // the cinema's car park, behind it
        cinema?.let { c ->
            val (row, b) = town.placement(c.sprite)!!
            val place = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b)
            val p = place.world(0f, b.setback + c.deep - 60f)
            assertTrue("cinema car park at ${p.x},${p.z}", reachedNear(p.x, p.z, 40f))
        }
        drawMap("town_reach", null)
    }

    /** Walks [w] along [path] (cell indices) with the stick, the camera turning slowly all the while; returns the frames. */
    fun follow(w: OpenWalk, path: IntArray, maxSeconds: Float, trail: MutableList<Pair<Float, Float>>, yaw0: Float): Int {
        val dt = 1f / 60f
        var k = 0
        var frames = 0
        val goalX = cx(path.last() % cols); val goalZ = cz(path.last() / cols)
        var worst = Float.MAX_VALUE
        var stuck = 0f
        while (frames < maxSeconds / dt) {
            if (hypot(w.wx - goalX, w.wz - goalZ) < 10f) break
            // the nearest point of the path ahead, then aim a few cells past it
            var best = k; var bd = Float.MAX_VALUE
            // (only points in plain sight: a cell of the path just across a thin fence is not where the walker is)
            for (i in k until minOf(path.size, k + 40)) {
                val px = cx(path[i] % cols); val pz = cz(path[i] / cols)
                val d = hypot(px - w.wx, pz - w.wz)
                if (d < bd && (i == k || inSight(w.wx, w.wz, px, pz))) { bd = d; best = i }
            }
            k = best
            // (aimed a few cells on, but never across something: the farthest of them in plain sight)
            var ahead = minOf(path.size - 1, k + 4)
            while (ahead > k + 1 && !inSight(w.wx, w.wz, cx(path[ahead] % cols), cz(path[ahead] / cols))) ahead--
            val tgt = path[ahead]
            val tx = cx(tgt % cols) - w.wx; val tz = cz(tgt / cols) - w.wz
            val tl = hypot(tx, tz).coerceAtLeast(1e-3f)
            // the camera swings round on its own clock: the push is turned into its frame
            val yaw = yaw0 + frames * dt * .7f + sin(frames * dt * .37f) * 2f
            w.cameraYaw = yaw
            val fx = sin(yaw); val fz = -cos(yaw); val rx = -fz; val rz = fx
            val dx = tx / tl; val dz = tz / tl
            val mx = dx * rx + dz * rz
            val my = -(dx * fx + dz * fz)
            val px = w.wx; val pz = w.wz
            w.step(dt, mx, my)
            frames++
            val c = map.clearance(w.wx, w.wz, R * 2f)
            worst = minOf(worst, c)
            assertTrue("inside something at ${w.wx},${w.wz} (clearance $c, shape ${map.nearest(w.wx, w.wz)}: ${map.nearest(w.wx, w.wz).let { if (it >= 0) map.kind(it).name + " " + map.owner(it) else "" }})", c >= R - .05f)
            val moved = hypot(w.wx - px, w.wz - pz)
            assertTrue("jumped $moved", moved <= MoveTuning.run * MoveTuning.dtMax + .5f)
            stuck = if (moved < .05f) stuck + dt else 0f
            if (frames % 6 == 0) trail += w.wx to w.wz
        }
        assertTrue("did not arrive at $goalX,$goalZ from ${w.wx},${w.wz} in $maxSeconds s (stuck ${stuck}s)", hypot(w.wx - goalX, w.wz - goalZ) < 10f)
        return frames
    }

    /** Nothing solid on the straight line from ([ax], [az]) to ([bx], [bz]) (looked at every 2 units). */
    private fun inSight(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        val l = hypot(bx - ax, bz - az)
        val n = (l / 2f).toInt() + 1
        for (i in 0..n) { val t = i.toFloat() / n; if (map.blocked(ax + (bx - ax) * t, az + (bz - az) * t, .5f)) return false }
        return true
    }

    private fun pathTo(fromX: Float, fromZ: Float, toCell: Int): IntArray? {
        // (a walker can stand closer to a trunk than the grid's clearance: then from the nearest reached cell by it)
        val start = cellOf(fromX, fromZ).let { c -> if (pathFree[c]) c else nearestPathCell(fromX, fromZ).takeIf { it >= 0 } ?: c }
        val par = bfs(start, toCell, pathFree).takeIf { it[toCell] >= 0 } ?: bfs(start, toCell)
        if (par[toCell] < 0) return null
        val p = ArrayList<Int>()
        var c = toCell
        while (c != start) { p += c; c = par[c] }
        p += start
        return p.reversed().toIntArray()
    }

    /** The nearest cell ([x], [z]) a planned path may use (within 30), or -1. */
    private fun nearestPathCell(x: Float, z: Float): Int {
        val c0 = cellOf(x, z); val i0 = c0 % cols; val j0 = c0 / cols
        var best = -1; var bd = Int.MAX_VALUE
        for (dj in -5..5) for (di in -5..5) {
            val i = i0 + di; val j = j0 + dj
            if (i !in 0 until cols || j !in 0 until rows) continue
            val c = j * cols + i
            if (pathFree[c] && reach[c] >= 0 && di * di + dj * dj < bd) { bd = di * di + dj * dj; best = c }
        }
        return best
    }

    /** The reached cell nearest ([x], [z]) (searched outward), or -1. */
    private fun nearestReached(x: Float, z: Float, r: Float = 80f): Int {
        val c0 = cellOf(x, z); val i0 = c0 % cols; val j0 = c0 / cols
        val n = (r / STEP).toInt()
        var best = -1; var bd = Int.MAX_VALUE
        for (dj in -n..n) for (di in -n..n) {
            val i = i0 + di; val j = j0 + dj
            if (i !in 0 until cols || j !in 0 until rows) continue
            val c = j * cols + i
            if (reach[c] >= 0 && di * di + dj * dj < bd) { bd = di * di + dj * dj; best = c }
        }
        return best
    }

    @Test fun aScriptedWalkerCrossesTheTown() {
        val w = OpenWalk(town, map, yards, doors)
        // the goals: back yards through their side gates, a back lane, every bridge and its far side, the plaza, the
        // cinema's car park, the forest, Main Street, and back to the spawn
        val goals = ArrayList<Pair<String, Pair<Float, Float>>>()
        val gated = yards.yards.filter { y -> y.back != null && y.items.any { it.kind == "sideGate" } }
        for (y in gated.filterIndexed { i, _ -> i % 6 == 0 }) { val b = y.back!!; goals += "back yard of ${y.sprite}" to ((b[0] + b[2]) / 2f to (b[1] + b[3]) / 2f) }
        yards.lanes.firstOrNull { it.kind == "lane" }?.let { l -> goals += "back lane ${l.id}" to ((l.rect[0] + l.rect[2]) / 2f to (l.rect[1] + l.rect[3]) / 2f) }
        for (b in town.bridges) {
            val r = b.rect
            if (b.horizontal) { goals += "bridge west" to (r[0] - 60f to (r[1] + r[3]) / 2f); goals += "bridge east" to (r[2] + 60f to (r[1] + r[3]) / 2f) }
            else { goals += "bridge north" to ((r[0] + r[2]) / 2f to r[1] - 60f); goals += "bridge south" to ((r[0] + r[2]) / 2f to r[3] + 60f) }
        }
        town.plaza?.let { p -> goals += "plaza" to ((p[0] + p[2]) / 2f to p[1] + 30f) }
        cinema?.let { c ->
            val (row, b) = town.placement(c.sprite)!!
            val p = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b).world(0f, b.setback + c.deep - 60f)
            goals += "cinema car park" to (p.x to p.z)
        }
        // (the town's own woods: not the farms' windbreaks out in the farmland, graph.farms)
        val nx0 = town.nodes.minOf { it.x }; val nx1 = town.nodes.maxOf { it.x }; val nz0 = town.nodes.minOf { it.z }; val nz1 = town.nodes.maxOf { it.z }
        val trees = town.props.filter { it.kind == "tree" && it.x in nx0..nx1 && it.z in nz0..nz1 }
        trees.getOrNull(trees.size / 2)?.let { goals += "forest" to (it.x + 20f to it.z + 20f) }
        goals += "spawn" to spawn
        val trail = ArrayList<Pair<Float, Float>>()
        var frames = 0
        var walked = 0f
        val done = ArrayList<String>()
        for ((i, g) in goals.withIndex()) {
            val cell = nearestReached(g.second.first, g.second.second)
            if (cell < 0) { println("goal ${g.first} not reachable: skipped"); continue }
            val path = pathTo(w.wx, w.wz, cell) ?: error("no path to ${g.first} from ${w.wx},${w.wz} (after ${done.lastOrNull()}))")
            val before = GameState.travel
            frames += follow(w, path, 30f + path.size * STEP / 60f, trail, i * 1.3f)
            walked += GameState.travel - before
            done += g.first
        }
        println("scripted walk: ${done.size} goals (${done.joinToString()}), ${frames / 60f} s of play, ${walked.toInt()} units walked")
        assertTrue(done.size >= goals.size - 2)
        drawMap("walker", trail)
    }

    @Test fun slidesAlongWalls() {
        val w = OpenWalk(town, map, yards, doors)
        // a fence running east-west with a clear strip 240 long beside it: stand off it, push half along it, half into it
        var pick: Triple<FloatArray, Float, Float>? = null
        loop@ for (y in yards.yards) for (it in y.items) {
            if (!(it.kind.endsWith("Fence")) || it.rect[2] - it.rect[0] < 300f) continue
            for (sd in floatArrayOf(1f, -1f)) {
                val zf = if (sd > 0) it.rect[3] + R + 1f else it.rect[1] - R - 1f
                var x = it.rect[0] + 20f
                while (x + 240f < it.rect[2]) {
                    if ((0..60).all { k -> !map.blocked(x + k * 4f, zf, R + .5f) && !map.blocked(x + k * 4f, zf + sd * 14f, R + .5f) }) { pick = Triple(it.rect, sd, x); break@loop }
                    x += 20f
                }
            }
        }
        val (r, side, sx) = pick ?: error("no clear fence")
        val mid = sx + 120f
        val z0 = if (side > 0) r[3] + 20f else r[1] - 20f
        w.placeAt(mid - 120f, z0)
        w.cameraYaw = 0f
        // world push (1, -side)/sqrt2: along +x and into the fence
        val dx = .7071f; val dz = -side * .7071f
        val x0 = w.wx
        repeat(90) { w.step(1f / 60f, dx, dz) }
        val along = w.wx - x0
        val gap = if (side > 0) w.wz - r[3] else r[1] - w.wz
        println("slide along a fence ${r.toList()} on side $side from ${mid - 120f},$z0: ${along.toInt()} units in 1.5 s, ${"%.2f".format(gap)} from it, at ${w.wx},${w.wz} by ${map.nearest(w.wx, w.wz).let { map.kind(it).name + " " + map.owner(it) }}")
        assertTrue("stuck against the fence (moved $along)", along > 1.5f * MoveTuning.run * .7071f * .8f)
        assertTrue("not against the fence ($gap)", abs(gap - R) < 1f)
        // straight into it: it stops there, still, and never inside
        w.placeAt(mid, z0)
        repeat(120) { w.step(1f / 60f, 0f, -side) }
        val g2 = if (side > 0) w.wz - r[3] else r[1] - w.wz
        assertTrue("into the fence: $g2", abs(g2 - R) < .5f && abs(w.wx - mid) < 1f)
        // into a building's corner and along its wall: round it, no sticking
        val (row, b) = town.placement("b96")!!
        val place = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b)
        val front = place.world(-b.width / 2f - 30f, b.setback - 30f)
        w.placeAt(front.x, front.z)
        // push towards the building's middle, diagonally (its front's left corner first)
        val c = place.world(0f, b.setback + b.depth / 2f)
        var moved = 0f
        repeat(240) {
            val tx = c.x - w.wx; val tz = c.z - w.wz; val tl = hypot(tx, tz)
            val px = w.wx; val pz = w.wz
            w.cameraYaw = 0f
            w.step(1f / 60f, tx / tl, tz / tl)
            moved += hypot(w.wx - px, w.wz - pz)
            assertTrue(map.clearance(w.wx, w.wz) >= R - .05f)
        }
        println("pushed at the hotel's middle from its corner: slid ${moved.toInt()} units")
    }

    @Test fun doorsAndPeopleFromEverySide() {
        val w = OpenWalk(town, map, yards, doors)
        var tried = 0; var found = 0
        for (d in doors.doors) {
            // in front of it, facing it: this door
            for ((k, ang) in listOf(0f, .6f, -.6f).withIndex()) {
                val ox = d.outX * cos(ang) - d.outZ * sin(ang); val oz = d.outX * sin(ang) + d.outZ * cos(ang)
                val px = d.x + ox * 20f; val pz = d.z + oz * 20f
                if (map.blocked(px, pz, R)) continue
                w.placeAt(px, pz, d.wallX - px, d.wallZ - pz)
                if (hypot(w.wx - px, w.wz - pz) > 1f) continue
                tried++
                val got = w.door
                assertNotNull("no door from in front of ${d.sprite} ($k)", got)
                if (got!!.sprite == d.sprite) found++
                else assertTrue("door ${got.sprite} for ${d.sprite}", hypot(got.x - w.wx, got.z - w.wz) <= hypot(d.x - w.wx, d.z - w.wz) + .01f)
                // facing away: none
                w.placeAt(px, pz, -(d.wallX - px), -(d.wallZ - pz))
                assertTrue("facing away from ${d.sprite} found ${w.door?.sprite}", w.door?.sprite != d.sprite)
            }
            // behind the building: not this door
            val (row, b) = town.placement(d.sprite)!!
            val place = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b)
            val back = place.world(0f, b.setback + b.depth + 25f)
            if (!map.blocked(back.x, back.z, R)) {
                w.placeAt(back.x, back.z, d.wallX - back.x, d.wallZ - back.z)
                assertTrue("door of ${d.sprite} from behind it", w.door?.sprite != d.sprite)
            }
        }
        println("doors: $found of $tried approaches found the door in front (the rest a nearer one)")
        assertTrue(found > tried * .9f)
        // the people to talk to: from 8 directions, the nearest one within range (any direction)
        val keepers = doors.keeperSprites.associateWith { 1 }
        var asks = 0
        for (i in doors.keeperSprites.indices) for (k in 0 until 8) {
            val a = k * PI.toFloat() / 4f
            val px = doors.keeperX[i] + cos(a) * 45f; val pz = doors.keeperZ[i] + sin(a) * 45f
            if (map.blocked(px, pz, R)) continue
            w.placeAt(px, pz)
            val want = doors.keeperSprites.indices.minByOrNull { hypot(doors.keeperX[it] - w.wx, doors.keeperZ[it] - w.wz) }!!
            val wd = hypot(doors.keeperX[want] - w.wx, doors.keeperZ[want] - w.wz)
            val got = w.nearestKeeper(keepers, 70f)
            if (wd <= 70f) assertEquals(doors.keeperSprites[want], got) else assertNull(got)
            asks++
        }
        println("people to talk to: $asks approaches from 8 sides")
    }

    @Test fun theStreetFrameFollows() {
        val w = OpenWalk(town, map, yards, doors)
        // at the spawn: the spawn's row, near its x and lane
        assertEquals(town.spawn.row, w.row.id)
        assertTrue(abs(w.x - town.spawn.x) < 4f)
        // on each row's pavement: that row (or the one on the same line), the street's name
        var same = 0; var n = 0
        for (r in town.rows) {
            val p = r.pointAt(r.length / 2f, 8f)
            if (map.blocked(p.x, p.z, R)) continue
            w.placeAt(p.x, p.z)
            n++
            if (w.row.id == r.id) same++
            else {
                // another row's building line as near (a stub's middle on a crossing street's line): fine
                val (lx, ll) = w.row.locate(p.x, p.z)
                val q = w.row.pointAt(lx.coerceIn(0f, w.row.length), 0f)
                assertTrue("row ${w.row.id} for ${r.id}", hypot(q.x - p.x, q.z - p.z) <= 8f * com.lifemax.rpg.feature.habits.domain.Crossroads.LANE + 2f)
            }
        }
        println("street frame: $same of $n pavements' own row")
        assertTrue(same > n * .9f)
    }

    @Test fun queriesAreFastAndAllocateNothing() {
        // points on reached ground
        val pts = ArrayList<Int>()
        val rnd = java.util.Random(7)
        while (pts.size < 4096) { val c = rnd.nextInt(cols * rows); if (reach[c] >= 0) pts += c }
        val xs = FloatArray(pts.size) { cx(pts[it] % cols) }; val zs = FloatArray(pts.size) { cz(pts[it] / cols) }
        val o = FloatArray(2)
        val dirs = FloatArray(64) { (it * 2.0 * PI / 64).toFloat() }
        fun run(n: Int): Long {
            val t0 = System.nanoTime()
            for (i in 0 until n) { val k = i and 4095; val a = dirs[i and 63]; map.move(xs[k], zs[k], cos(a) * 2.5f, sin(a) * 2.5f, R, o) }
            return System.nanoTime() - t0
        }
        run(200_000)
        val n = 1_000_000
        val ns = run(n).toDouble() / n
        val t1 = System.nanoTime()
        var b = 0
        for (i in 0 until n) if (map.blocked(xs[i and 4095], zs[i and 4095], R)) b++
        val nsB = (System.nanoTime() - t1).toDouble() / n
        // a frame's walk: the step (move, facing, legs, street frame, door, yard)
        val w = OpenWalk(town, map, yards, doors)
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        for (i in 0 until 2000) w.step(1f / 60f, sin(i * .01f), cos(i * .013f))
        val a0 = mx.getThreadAllocatedBytes(tid)
        val t2 = System.nanoTime()
        val frames = 20_000
        for (i in 0 until frames) { w.cameraYaw = i * .001f; w.step(1f / 60f, sin(i * .01f), cos(i * .013f)) }
        val nsStep = (System.nanoTime() - t2).toDouble() / frames
        val alloc = (mx.getThreadAllocatedBytes(tid) - a0).toDouble() / frames
        val a1 = mx.getThreadAllocatedBytes(tid)
        for (i in 0 until 100_000) { val k = i and 4095; map.move(xs[k], zs[k], 2f, 1f, R, o) }
        val allocMove = (mx.getThreadAllocatedBytes(tid) - a1).toDouble() / 100_000
        println("collision (build server JVM, not the S25): move ${"%.0f".format(ns)} ns, blocked ${"%.0f".format(nsB)} ns ($b of $n blocked), " +
            "a frame's step ${"%.0f".format(nsStep)} ns allocating ${"%.1f".format(alloc)} bytes; move allocates ${"%.2f".format(allocMove)} bytes")
        assertTrue("move $ns ns", ns < 20_000)
        assertTrue("move allocates $allocMove", allocMove < 1.0)
        assertTrue("a step allocates $alloc bytes", alloc < 8.0)
    }

    /** A top-down picture of the map near the spawn, the cinema and a yard row, with [trail] drawn over it. */
    private fun drawMap(name: String, trail: List<Pair<Float, Float>>?, at: Pair<Float, Float>? = null) {
        val views = listOf(
            "spawn" to (spawn.first to spawn.second),
            "cinema" to town.placement(cinema?.sprite ?: "b98")!!.let { (r, b) -> com.lifemax.rpg.feature.habits.domain.TownPlace.of(r, b).world(0f, b.setback + 400f).let { it.x to it.z } },
            "yards" to yards.yards.first { y -> y.items.any { it.kind == "pool" } }.parcel.let { (it[0] + it[2]) / 2f to (it[1] + it[3]) / 2f },
            "bridge" to town.bridges.first().rect.let { (it[0] + it[2]) / 2f to (it[1] + it[3]) / 2f },
        ).let { v -> if (at != null) listOf("at" to at) else v } + (System.getProperty("openWalkAt")?.split(",")?.map { it.toFloat() }?.let { listOf("at" to (it[0] to it[1])) } ?: emptyList())
        val s = FloatArray(6)
        for ((label, c) in views) {
            val size = 1100; val scale = 1.0f
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(40, 46, 40); g.fillRect(0, 0, size, size)
            val ox = c.first - size / 2f / scale; val oz = c.second - size / 2f / scale
            // reached ground
            for (py in 0 until size step 2) for (px in 0 until size step 2) {
                val x = ox + px / scale; val z = oz + py / scale
                if (x < map.minX || z < map.minZ || x >= map.maxX || z >= map.maxZ) continue
                if (reach[cellOf(x, z)] >= 0) { g.color = Color(92, 104, 92); g.fillRect(px, py, 2, 2) }
            }
            for (i in 0 until map.size) {
                map.shape(i, s)
                if (abs(s[0] - c.first) > size || abs(s[1] - c.second) > size) continue
                g.color = when (map.kind(i)) {
                    SolidKind.BUILDING -> Color(200, 120, 90); SolidKind.FENCE -> Color(240, 230, 200); SolidKind.HEDGE -> Color(60, 170, 60)
                    SolidKind.WALL -> Color(170, 170, 180); SolidKind.YARD_OBJECT -> Color(230, 190, 60); SolidKind.TREE -> Color(30, 110, 30)
                    SolidKind.PROP -> Color(220, 80, 220); SolidKind.WATER -> Color(50, 110, 220); SolidKind.PARAPET -> Color(255, 255, 255); SolidKind.EDGE -> Color.RED
                }
                if (map.isCircle(i)) {
                    val r = s[2] * scale
                    g.fillOval(((s[0] - ox) * scale - r).toInt(), ((s[1] - oz) * scale - r).toInt(), (2 * r).toInt().coerceAtLeast(2), (2 * r).toInt().coerceAtLeast(2))
                } else {
                    val ux = s[2]; val uz = s[3]; val hu = s[4]; val hv = s[5]
                    val xp = IntArray(4); val zp = IntArray(4)
                    for ((k, sg) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).withIndex()) {
                        val wx = s[0] + ux * hu * sg.first - uz * hv * sg.second
                        val wz = s[1] + uz * hu * sg.first + ux * hv * sg.second
                        xp[k] = ((wx - ox) * scale).toInt(); zp[k] = ((wz - oz) * scale).toInt()
                    }
                    g.fillPolygon(xp, zp, 4)
                }
            }
            // doors
            g.color = Color(255, 60, 60)
            for (d in doors.doors) { val px = ((d.x - ox) * scale).toInt(); val pz = ((d.z - oz) * scale).toInt(); g.fillRect(px - 3, pz - 3, 6, 6) }
            trail?.let { t ->
                g.color = Color(0, 255, 255); g.stroke = BasicStroke(2f)
                for (k in 1 until t.size) {
                    val a = t[k - 1]; val b = t[k]
                    if (hypot(a.first - b.first, a.second - b.second) > 200f) continue
                    g.drawLine(((a.first - ox) * scale).toInt(), ((a.second - oz) * scale).toInt(), ((b.first - ox) * scale).toInt(), ((b.second - oz) * scale).toInt())
                }
            }
            g.dispose()
            ImageIO.write(img, "png", File(out, "${name}_$label.png"))
        }
        // and the whole town, small
        val W = 1400
        val k = W / (map.maxX - map.minX)
        val H = ((map.maxZ - map.minZ) * k).toInt()
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        for (py in 0 until H) for (px in 0 until W) {
            val x = map.minX + px / k; val z = map.minZ + py / k
            val c = cellOf(x, z).coerceIn(0, cols * rows - 1)
            img.setRGB(px, py, if (reach[c] >= 0) 0x5C685C else if (free[c]) 0xA03030 else 0x202420)
        }
        trail?.let { t -> for ((x, z) in t) { val px = ((x - map.minX) * k).toInt(); val pz = ((z - map.minZ) * k).toInt(); if (px in 0 until W && pz in 0 until H) img.setRGB(px, pz, 0x00FFFF) } }
        ImageIO.write(img, "png", File(out, "${name}_town.png"))
    }
}
