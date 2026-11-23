import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import com.lifemax.rpg.feature.habits.domain.WorldTerrain
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * W5a (docs/WORLD_GEN.md): the near terrain. Its heights are the TypeScript's (src/world/gen/near.ts, through
 * tools/world-fixture.ts's fixture) bit for bit; a node's heights are the same however often and in whatever order it is
 * built, and a vertex shared by two nodes or two levels is one height; the nodes chosen for a camera cover their root
 * blocks exactly once; and wherever two nodes meet, or a node meets the far view, the skirt of the higher one reaches
 * below the lower one's edge, so no crack can show (the geometric seam test).
 */
class WorldTerrainTest {
    private val fixture = JSONObject(javaClass.classLoader.getResource("world/fixture.json")!!.readText())

    companion object {
        private val sk: WorldSkeleton by lazy { File("../../content/world/alpha/skeleton.twsk.gz").inputStream().use { WorldSkeleton.read(it) } }
        private val town: Town by lazy { Town.parse(File("../app/src/main/assets/town/gamma/street.json").readText())!! }
        private val site: WorldSite by lazy { WorldSite.of(sk, "gamma", town.scale.toDouble())!! }
        private val plan: WorldFarPlan by lazy { site.plan(town.year) }
        private val terrain: WorldTerrain by lazy { WorldTerrain(sk, plan) }
    }

    @Test fun heightsAreTheTypeScriptsBitForBit() {
        val near = fixture.getJSONArray("near")
        var cliffs = 0
        for (i in 0 until near.length()) {
            val p = near.getJSONObject(i)
            val cx = p.getLong("gx") * Planet.TILE_M / Planet.CELL_M; val cz = p.getLong("gz") * Planet.TILE_M / Planet.CELL_M
            assertEquals("smooth at $p", p.getDouble("smooth"), terrain.smooth(cx, cz), 0.0)
            assertEquals("slope at $p", p.getDouble("slope"), terrain.slope(cx, cz), 0.0)
            assertEquals("raw at $p", p.getDouble("raw"), terrain.raw(cx, cz), 0.0)
            if (p.getDouble("raw") != p.getDouble("smooth")) cliffs++
        }
        // (the fixture holds a cliff: its terracing is tested too)
        assertTrue("no terraced sample in the fixture", cliffs > 0)
    }

    @Test fun chunkHeightsAreTheFixturesWhereNothingShapesThem() {
        val near = fixture.getJSONArray("near")
        var checked = 0
        for (i in 0 until near.length()) {
            val o = near.getJSONObject(i)
            val gx = o.getLong("gx"); val gz = o.getLong("gz")
            val p = terrain.point(gx, gz, WorldTerrain.Point())
            // (only where neither the town's ground nor a river or road shapes the ground)
            if (p.outside < WorldFarPlan.FLAT_M + WorldFarPlan.BLEND_M || minOf(p.river, p.road, p.county, p.rail) < WorldTerrain.CALM_M) continue
            val node = WorldTerrain.Node(0, Math.floorDiv(gx, WorldTerrain.QUADS.toLong()).toInt(), Math.floorDiv(gz, WorldTerrain.QUADS.toLong()).toInt())
            val d = terrain.build(node)
            val y = d.h((gx - node.gx0).toInt(), (gz - node.gz0).toInt())
            assertEquals("vertex at $gx, $gz", o.getDouble("raw"), y + plan.baseElev, 2e-3)
            checked++
        }
        assertTrue("only $checked fixture points were away from the town and the lines", checked >= 3)
    }

    @Test fun nodesAreDeterministicAndShareTheirVertices() {
        val f = site.frame
        val p = f.world((f.ground[2] + 1600 * f.unitsPerMetre), (f.ground[1] + f.ground[3]) / 2.0)
        val gx = Math.floorDiv(Math.round(p.x / Planet.TILE_M), 64L * 4) * 4; val gz = Math.floorDiv(Math.round(p.z / Planet.TILE_M), 64L * 4) * 4
        val a = WorldTerrain.Node(2, gx.toInt(), gz.toInt())
        val one = terrain.build(a)
        // the same node again, by another terrain made from the same skeleton, after other nodes: the same bits
        val other = WorldTerrain(sk, plan)
        other.build(WorldTerrain.Node(1, a.i * 2 + 1, a.j * 2))
        val two = other.build(a)
        assertArrayEquals(one.heights, two.heights, 0f)
        assertArrayEquals(one.ids, two.ids)
        assertArrayEquals(one.skirt, two.skirt, 0f)
        // a vertex shared with the east neighbour, and with a child one level finer, is the same height
        val east = terrain.build(WorldTerrain.Node(2, a.i + 1, a.j))
        for (k in 0..WorldTerrain.QUADS) assertEquals(one.h(WorldTerrain.QUADS, k), east.h(0, k), 0f)
        val child = terrain.build(a.child(3))
        for (k in 0..WorldTerrain.QUADS / 2) assertEquals(one.h(WorldTerrain.QUADS / 2 + k, WorldTerrain.QUADS / 2), child.h(k * 2, 0), 0f)
    }

    /** A node's square in tiles: x0, z0, x1, z1. */
    private fun square(n: WorldTerrain.Node) = longArrayOf(n.gx0, n.gz0, n.gx0 + n.tiles, n.gz0 + n.tiles)

    /** The height of [d]'s edge polyline at tile ([gx], [gz]) on its rim (linear between its vertices). */
    private fun edgeAt(d: WorldTerrain.NodeData, gx: Long, gz: Long): Float {
        val s = d.node.step.toLong()
        val a = (gx - d.node.gx0).toDouble() / s; val b = (gz - d.node.gz0).toDouble() / s
        val a0 = kotlin.math.floor(a).toInt().coerceIn(0, WorldTerrain.QUADS); val b0 = kotlin.math.floor(b).toInt().coerceIn(0, WorldTerrain.QUADS)
        val a1 = min(a0 + 1, WorldTerrain.QUADS); val b1 = min(b0 + 1, WorldTerrain.QUADS)
        val ta = (a - a0).toFloat(); val tb = (b - b0).toFloat()
        // (on a vertical edge only b varies, on a horizontal one only a)
        return if (ta == 0f) d.h(a0, b0) + (d.h(a0, b1) - d.h(a0, b0)) * tb else d.h(a0, b0) + (d.h(a1, b0) - d.h(a0, b0)) * ta
    }

    /** The skirt of [d] along its edge facing ([dx], [dz]) (north, east, south, west). */
    private fun skirtOf(d: WorldTerrain.NodeData, dx: Int, dz: Int): Float = d.skirt[when { dz < 0 -> 0; dx > 0 -> 1; dz > 0 -> 2; else -> 3 }]

    @Test fun theChosenNodesCoverTheirBlocksOnceAndNoCrackShows() {
        val f = site.frame
        val u = f.unitsPerMetre
        val mid = f.world((f.ground[0] + f.ground[2]) / 2.0, (f.ground[1] + f.ground[3]) / 2.0)
        val eyes = listOf(
            // a person at the town's east edge; on the bike 2 km out; 200 m up 3 km out; high over the town
            WorldTerrain.Eye(f.world(f.ground[2] - 25 * u, (f.ground[1] + f.ground[3]) / 2.0).x, mid.z, 1.7),
            WorldTerrain.Eye(mid.x + 2600.0, mid.z + 900.0, 3.0),
            WorldTerrain.Eye(mid.x - 1800.0, mid.z - 2900.0, 230.0),
            WorldTerrain.Eye(mid.x + 400.0, mid.z + 300.0, 900.0),
        )
        val cache = HashMap<WorldTerrain.Node, WorldTerrain.NodeData>()
        fun data(n: WorldTerrain.Node) = cache.getOrPut(n) { terrain.build(n) }
        var pairs = 0; var farEdges = 0; var worstNear = 0f; var worstFar = 0f; var worstAt = ""
        for (e in eyes) {
            val g = terrain.heightLocal(terrain.localX(Math.round(e.x / Planet.TILE_M)), terrain.localZ(Math.round(e.z / Planet.TILE_M)))
            val eye = WorldTerrain.Eye(e.x, e.z, g + e.y)
            val roots = terrain.roots(eye)
            val leaves = terrain.select(eye, g)
            assertTrue(leaves.isNotEmpty())
            // the cover: every leaf in a root, the leaves' areas summing to the roots', no two overlapping
            val rootArea = roots.sumOf { it.tiles.toLong() * it.tiles }
            assertEquals(rootArea, leaves.sumOf { it.tiles.toLong() * it.tiles })
            val sq = leaves.map { square(it) }
            for (i in sq.indices) for (j in i + 1 until sq.size) {
                val a = sq[i]; val b = sq[j]
                assertTrue("${leaves[i]} overlaps ${leaves[j]}", a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1])
            }
            // the seams: wherever two leaves share an edge, at every vertex of the finer one along it, the higher side's
            // skirt reaches below the lower side's edge
            for (i in leaves.indices) for (j in leaves.indices) {
                if (i == j) continue
                val a = sq[i]; val b = sq[j]
                // (a's east edge on b's west edge, or a's south on b's north: each pair once each way round)
                val vertical = a[2] == b[0] && a[1] < b[3] && b[1] < a[3]
                val horizontal = a[3] == b[1] && a[0] < b[2] && b[0] < a[2]
                if (!vertical && !horizontal) continue
                val da = data(leaves[i]); val db = data(leaves[j])
                val step = min(leaves[i].step, leaves[j].step).toLong()
                val from = if (vertical) max(a[1], b[1]) else max(a[0], b[0]); val to = if (vertical) min(a[3], b[3]) else min(a[2], b[2])
                var t = from
                while (t <= to) {
                    val gx = if (vertical) a[2] else t; val gz = if (vertical) t else a[3]
                    val ha = edgeAt(da, gx, gz); val hb = edgeAt(db, gx, gz)
                    val skirt = if (ha > hb) skirtOf(da, if (vertical) 1 else 0, if (vertical) 0 else 1) else skirtOf(db, if (vertical) -1 else 0, if (vertical) 0 else -1)
                    val gap = abs(ha - hb)
                    worstNear = max(worstNear, gap)
                    assertTrue("a crack of %.2f m between ${leaves[i]} and ${leaves[j]} at $gx, $gz (skirt %.2f)".format(gap, skirt), gap <= skirt)
                    t += step
                }
                pairs++
            }
            // the hand-over: a root's edge with no root beyond it meets the far view's finest level, which hides its own
            // part there; where the far view is higher its own skirt (160 m) covers the step, where the near is higher, the near's
            val rootSet = roots.toSet()
            for (r in roots) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                if (WorldTerrain.Node(r.level, r.i + dx, r.j + dz) in rootSet) continue
                // the leaves along this edge of the root
                val s = square(r)
                for ((k, n) in leaves.withIndex()) {
                    val q = sq[k]
                    val on = when { dx > 0 -> q[2] == s[2]; dx < 0 -> q[0] == s[0]; dz > 0 -> q[3] == s[3]; else -> q[1] == s[1] } && q[0] >= s[0] && q[2] <= s[2] && q[1] >= s[1] && q[3] <= s[3]
                    if (!on) continue
                    val d = data(n)
                    val from = if (dx != 0) q[1] else q[0]; val to = if (dx != 0) q[3] else q[2]
                    var t = from
                    while (t <= to) {
                        val gx = if (dx > 0) q[2] else if (dx < 0) q[0] else t
                        val gz = if (dz > 0) q[3] else if (dz < 0) q[1] else t
                        val hn = edgeAt(d, gx, gz)
                        val hf = plan.surface(terrain.localX(gx), terrain.localZ(gz))
                        val gap = abs(hn - hf)
                        if (gap > worstFar) { val pp = terrain.point(gx, gz, WorldTerrain.Point()); worstAt = "there the near ground is %.1f m and the far view's %.1f m, %.0f m out of town, cliff %.2f".format(hn, hf, pp.outside, pp.cliff) }
                        worstFar = max(worstFar, gap)
                        val skirt = if (hn > hf) skirtOf(d, dx, dz) else 160f
                        assertTrue("a crack of %.2f m between $n and the far view at $gx, $gz (skirt %.2f)".format(gap, skirt), gap <= skirt)
                        t += n.step
                    }
                    farEdges++
                }
            }
        }
        assertTrue(pairs > 50)
        assertTrue(farEdges > 4)
        println("W5a seams: $pairs shared edges and $farEdges edges onto the far view checked over ${eyes.size} cameras; the largest step %.2f m between nodes, %.2f m onto the far view (each covered by a skirt: $worstAt); ${cache.size} nodes built".format(worstNear, worstFar))
    }

    @Test fun nodesBuildInTime() {
        // (desktop numbers for the record, not a pass mark: one worker's time for a node of 65 x 65 vertices)
        val f = site.frame
        val p: WorldPos = f.world(f.ground[2] + 3000 * f.unitsPerMetre, f.ground[3] + 2000 * f.unitsPerMetre)
        val ms = (0 until 12).map { k ->
            val n = WorldTerrain.Node(0, (Math.round(p.x / Planet.TILE_M) / 64).toInt() + k, (Math.round(p.z / Planet.TILE_M) / 64).toInt())
            terrain.build(n).ms
        }.drop(2).sorted()
        println("W5a node build (desktop, one thread): median %.1f ms, max %.1f ms".format(ms[ms.size / 2], ms.last()))
        assertTrue(ms[ms.size / 2] < 500f)
    }
}
