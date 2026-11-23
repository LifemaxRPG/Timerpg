import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RailNet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldFbm
import com.lifemax.rpg.feature.habits.domain.WorldLattice
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldNoise
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSave
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * W4 (docs/WORLD_GEN.md): the Kotlin reader of the baked world skeleton against what the TypeScript bake wrote
 * (resources/world/fixture.json, from kotlin-street/tools/world-fixture.ts), the planet's coordinates and the render
 * origin, gamma placed on its hub site with its pins where the town's river, Main Street and main line leave it, and the
 * far view's plan round it.
 */
class WorldSkeletonTest {
    private val fixture = JSONObject(javaClass.classLoader.getResource("world/fixture.json")!!.readText())

    companion object {
        private val sk: WorldSkeleton by lazy {
            File("../../content/world/alpha/skeleton.twsk.gz").inputStream().use { WorldSkeleton.read(it, setOf("elev", "flags", "biome", "koppen", "crop", "flow", "steady", "mass", "lake", "snowline")) }
        }
        private val gammaDir = File("../app/src/main/assets/town/gamma")
        private val town: Town by lazy { Town.parse(File(gammaDir, "street.json").readText())!! }
        private val site: WorldSite by lazy { WorldSite.of(sk, "gamma", town.scale.toDouble())!! }
    }

    private fun arr(o: JSONObject, k: String): JSONArray = o.getJSONArray(k)

    @Test fun noiseIsTheTypeScriptsBitForBit() {
        val hs = arr(fixture, "hashStr")
        for (i in 0 until hs.length()) { val e = hs.getJSONArray(i); assertEquals(e.getString(0), e.getLong(1), WorldNoise.hashStr(e.getString(0))) }
        val h3 = arr(fixture, "hash3")
        for (i in 0 until h3.length()) {
            val e = h3.getJSONArray(i)
            assertEquals(e.getLong(4), WorldNoise.hash3(e.getLong(0), e.getInt(1), e.getInt(2), e.getInt(3)))
            assertEquals(e.getDouble(5), WorldNoise.hashU(e.getLong(0), e.getInt(1), e.getInt(2), e.getInt(3)), 0.0)
        }
        val seed = fixture.getString("seed")
        val lat = WorldLattice(seed, "test", 64); val hills = WorldFbm(seed, "hills", 16, 7); val detail = WorldFbm(seed, "w4-detail", WorldSkeleton.W / 2, 3)
        val ns = arr(fixture, "noise")
        for (i in 0 until ns.length()) {
            val p = ns.getJSONObject(i); val x = p.getDouble("x"); val z = p.getDouble("z")
            // (exactly: the same doubles in the same order)
            assertEquals("lattice at $x, $z", p.getDouble("lattice"), lat.at(x, z), 0.0)
            assertEquals("hills at $x, $z", p.getDouble("hills"), hills.at(x, z), 0.0)
            assertEquals("detail at $x, $z", p.getDouble("detail"), detail.at(x, z), 0.0)
        }
    }

    @Test fun theReaderGivesTheBakesFacts() {
        assertEquals(fixture.getString("seed"), sk.seed)
        assertEquals(fixture.getInt("version"), sk.version)
        val c = fixture.getJSONObject("counts")
        assertEquals(c.getInt("places"), sk.places.size); assertEquals(c.getInt("roads"), sk.roads.size)
        assertEquals(c.getInt("railLines"), sk.railLines.size); assertEquals(c.getInt("rivers"), sk.rivers.size); assertEquals(c.getInt("sea"), sk.sea.size)
        val ss = arr(fixture, "samples")
        for (i in 0 until ss.length()) {
            val s = ss.getJSONObject(i); val cell = s.getInt("cell")
            for (g in listOf("elev", "flags", "biome", "koppen", "crop", "flow", "steady", "mass", "lake", "snowline")) assertEquals("$g at $cell", s.getInt(g), sk.raw(g, cell))
            assertEquals(s.getInt("elev"), sk.elev(cell % WorldSkeleton.W, cell / WorldSkeleton.W))
        }
        val cap = fixture.getJSONObject("capital")
        val k = sk.capital
        assertEquals(cap.getString("id"), k.id); assertEquals(cap.getString("name"), k.name); assertEquals(cap.getString("plan"), k.plan)
        assertEquals(cap.getDouble("x"), k.x, 0.0); assertEquals(cap.getDouble("z"), k.z, 0.0)
        val pins = arr(cap, "pins")
        assertEquals(pins.length(), k.pins.size)
        for (i in 0 until pins.length()) { val p = pins.getJSONObject(i); assertEquals(p.getString("name"), k.pins[i].name); assertEquals(p.getDouble("x"), k.pins[i].x, 0.0); assertEquals(p.getDouble("z"), k.pins[i].z, 0.0) }
        fun same(expect: JSONArray, got: DoubleArray) {
            assertEquals(expect.length() * 2, got.size)
            for (i in 0 until expect.length()) { val e = expect.getJSONArray(i); assertEquals(e.getDouble(0), got[i * 2], 1e-12); assertEquals(e.getDouble(1), got[i * 2 + 1], 1e-12) }
        }
        val cr = fixture.getJSONObject("capRiver")
        same(arr(cr, "path"), sk.points(sk.rivers[cr.getInt("index")].path))
        val roads = arr(fixture, "roads")
        for (i in 0 until roads.length()) { val r = roads.getJSONObject(i); same(arr(r, "path"), sk.points(sk.roads.first { it.id == r.getString("id") }.path)) }
        val rail = arr(fixture, "rail")
        assertTrue(rail.length() > 0)
        for (i in 0 until rail.length()) { val r = rail.getJSONObject(i); same(arr(r, "path"), sk.points(sk.railLines.first { it.id == r.getString("id") }.path)) }
        // the grids by default: what the far view needs, about 12 MB
        val slim = File("../../content/world/alpha/skeleton.twsk.gz").inputStream().use { WorldSkeleton.read(it) }
        assertTrue(slim.has("elev") && slim.has("biome") && !slim.has("koppen"))
        assertTrue("skeleton held ${slim.gridBytes shr 20} MB", slim.gridBytes < 25L shl 20)
    }

    @Test fun worldPositionsWrapAndTheRenderOriginKeepsFloatsSmall() {
        // the wrapped difference: no seam east-west
        val a = WorldPos(Planet.C - 3.0, 100.0); val b = WorldPos(4.0, 100.0)
        assertEquals(7.0, a.dx(b), 1e-9); assertEquals(-7.0, b.dx(a), 1e-9)
        assertEquals(1.0, a.plus(4.0, 0.0).x, 1e-9)
        // out in the country, far from 0 and over the seam, the origin moves to the player's chunk once 2 km away
        val u = 44.5 / 2.1
        val o = RenderOrigin(u, WorldPos(Planet.C - 1000.0, 400_000.0))
        assertTrue(!o.update(WorldPos(Planet.C - 1000.0 + 1500.0 - Planet.C, 400_000.0)))
        assertTrue(o.update(WorldPos(Planet.C - 1000.0 + 2500.0 - Planet.C, 400_000.0)))
        assertEquals(0.0, o.origin.x % Planet.CHUNK_M, 1e-6); assertEquals(0.0, o.origin.z % Planet.CHUNK_M, 1e-6)
        assertEquals(1, o.generation)
        // no jitter: a thing stepping 1 cm at a time across the seam, 1,995 km from the planet's zero, moves a steady 0.21
        // street units a step in the render frame (floats near the origin); in raw world floats it would jump by whole units
        val o2 = RenderOrigin(u, WorldPos(Planet.C - 600.0, 300_000.0))
        val out = FloatArray(2)
        var last = Float.NaN; var worst = 0.0; var rawWorst = 0.0; var rawLast = Float.NaN
        for (k in 0..120_000) {
            val p = WorldPos(Planet.C - 600.0, 300_000.0).plus(k * .01, 0.0)
            o2.toRender(p, out)
            if (!last.isNaN()) worst = maxOf(worst, abs((out[0] - last) - .01 * u))
            last = out[0]
            val raw = ((Planet.C - 600.0 + k * .01) * u).toFloat()
            if (!rawLast.isNaN()) rawWorst = maxOf(rawWorst, abs((raw - rawLast) - .01 * u))
            rawLast = raw
        }
        assertTrue("render frame steps off by $worst units", worst < 2e-3)
        assertTrue("raw floats would step off by $rawWorst units", rawWorst > 1.0)
        // a save is the world position plus the hub's tile, and comes back the same
        val s = WorldSave(WorldPos(123456.789012, 98765.4321), "h:capital", 700.25, 140.5)
        assertEquals(s, WorldSave.decode(s.encode()))
    }

    @Test fun gammaStandsOnItsSiteWithItsPinsWhereTheTownLeaves() {
        val f = site.frame
        assertEquals("h:capital", site.hub.id)
        // the town's frame: tile (cx, cy) of the plan is the hub's centre
        val pt = site.hub.planTiles!!
        val centre = f.world(pt.cx * town.scale, pt.cy * town.scale)
        assertEquals(0.0, centre.distance(WorldPos.ofCells(site.hub.x, site.hub.z)), 1e-6)
        // inside the town the render origin is the town's own frame: street (0, 0) renders at (0, 0)
        assertEquals(f.origin, site.origin.origin)
        val r = site.origin.toRender(f.world(1234.5, 678.25))
        assertEquals(1234.5f, r[0], .01f); assertEquals(678.25f, r[1], .01f)
        fun street(p: WorldSkeleton.Pin) = WorldPos.ofCells(p.x, p.z).let { floatArrayOf(f.streetX(it).toFloat(), f.streetZ(it).toFloat()) }
        val tile = town.scale
        // the highways leave by Main Street's two ends
        val main = town.roads.filter { it.main && it.name == "Main Street" }.flatMap { it.pts }
        val west = main.minByOrNull { it.x }!!; val east = main.maxByOrNull { it.x }!!
        for (p in site.hub.pins.filter { it.kind == "highway" }) {
            val s = street(p); val end = if (p.name == "west") west else east
            assertTrue("highway ${p.name} at ${s.toList()} vs Main Street's end (${end.x}, ${end.z})", hypot(s[0] - end.x, s[1] - end.z) < tile * .6f)
        }
        // the main line leaves by the town's rail portals
        val rail = RailNet.parse(File(gammaDir, "rail.json").readText())!!
        val portals = rail.rail!!.portals
        for (p in site.hub.pins.filter { it.kind == "rail" }) {
            val s = street(p)
            val q = portals.minByOrNull { hypot(it.x - s[0], it.z - s[1]) }!!
            assertTrue("rail ${p.name} at ${s.toList()} vs portal (${q.x}, ${q.z})", hypot(q.x - s[0], q.z - s[1]) < tile * .6f)
        }
        // every highway and main line out of the capital starts exactly on its pin
        fun first(path: IntArray?, atStart: Boolean) = sk.points(path).let { if (atStart) doubleArrayOf(it[0], it[1]) else doubleArrayOf(it[it.size - 2], it[it.size - 1]) }
        var n = 0
        for (rd in sk.roads.filter { it.cls == "highway" && (it.a == site.hub.id || it.b == site.hub.id) }) {
            val e = first(rd.path, rd.a == site.hub.id)
            assertTrue(rd.id, site.hub.pins.any { it.kind == "highway" && hypot(Planet.wrapDx((e[0] - it.x) * Planet.CELL_M), (e[1] - it.z) * Planet.CELL_M) < .005 }); n++
        }
        for (l in sk.railLines.filter { site.hub.id in it.stations && (it.a == site.hub.id || it.b == site.hub.id) }) {
            val e = first(l.path, l.a == site.hub.id)
            assertTrue(l.id, site.hub.pins.any { it.kind == "rail" && hypot(Planet.wrapDx((e[0] - it.x) * Planet.CELL_M), (e[1] - it.z) * Planet.CELL_M) < .005 }); n++
        }
        assertTrue(n >= 3)
        // the world river comes in and goes out where the town's own river meets its ground's edge (north and south)
        val polys = town.water.flatten()
        val g = f.ground
        val river = sk.rivers.first { rv -> sk.points(rv.path).let { p -> (0 until p.size / 2).any { abs(p[it * 2] - site.hub.x) < .3 && abs(p[it * 2 + 1] - site.hub.z) < .3 } } }
        val p = sk.points(river.path)
        for (edge in listOf(g[1], g[3])) {
            val span = polys.filter { abs(it.z - edge) < 1f }.map { it.x }
            assertTrue("the town's river reaches its ground's edge at z $edge", span.size >= 2)
            var hit: Double? = null
            for (k in 0 until p.size / 2 - 1) {
                val z0 = f.streetZ(WorldPos.ofCells(p[k * 2], p[k * 2 + 1])); val z1 = f.streetZ(WorldPos.ofCells(p[k * 2 + 2], p[k * 2 + 3]))
                if ((z0 - edge) * (z1 - edge) > 0 || z0 == z1) continue
                val t = (edge - z0) / (z1 - z0)
                val x0 = f.streetX(WorldPos.ofCells(p[k * 2], p[k * 2 + 1])); val x1 = f.streetX(WorldPos.ofCells(p[k * 2 + 2], p[k * 2 + 3]))
                hit = x0 + (x1 - x0) * t
            }
            assertNotNull("the world river crosses the ground's edge at z $edge", hit)
            assertTrue("the world river crosses z $edge at x $hit, the town's river spans ${span.min()}..${span.max()}", hit!! > span.min() && hit < span.max())
        }
    }

    @Test fun theFarViewIsPlannedRoundTheTown() {
        val plan = site.plan(town.year)
        assertEquals(3, plan.levels.size)
        assertTrue(plan.reach > 150_000)
        // on the town's ground the terrain is just under it; well past it, the land's own
        val g = site.frame.ground; val u = site.frame.unitsPerMetre
        val mx = (g[0] + g[2]) / 2.0 / u; val mz = (g[1] + g[3]) / 2.0 / u
        assertTrue(abs(plan.surface(mx, mz)) < .1f)
        // the levels meet: across the finest level's rim the surface steps no more than the cells' own relief allows
        val l0 = plan.levels[0]
        var worst = 0f
        for (k in 0..l0.n) {
            val x = l0.ox + k * l0.spacing
            for (z in listOf(l0.oz, l0.oz + l0.n * l0.spacing)) worst = maxOf(worst, abs(plan.surface(x, z + 1e-3) - plan.surface(x, z - 1e-3)))
        }
        assertTrue("the levels step $worst m at the rim", worst < 60f)
        // rivers, roads and rail out to the horizon, none inside the town's ground
        for (kind in listOf(WorldFarPlan.RIVER, WorldFarPlan.HIGHWAY, WorldFarPlan.RAIL)) assertTrue("kind $kind", plan.lines.any { it.kind == kind })
        for (l in plan.lines) for (k in 0 until l.pts.size / 2) assertTrue(plan.outside(l.pts[k * 2], l.pts[k * 2 + 1]) > -0.01)
        assertTrue(plan.boxes.map { it.place }.toSet().size > 10)
        // the world's memory within WORLD_GEN.md's budget (the skeleton here holds every grid: the app keeps fewer)
        assertTrue(WorldMemory.line(), WorldMemory.ram < WorldMemory.RAM_BUDGET)
        println("far view: " + plan.report + "; " + WorldMemory.line() + "; " + site.report)
    }

}
