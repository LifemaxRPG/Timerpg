import com.lifemax.rpg.feature.habits.domain.RailNet
import com.lifemax.rpg.feature.habits.domain.RailRide
import com.lifemax.rpg.feature.habits.domain.RailScene
import com.lifemax.rpg.feature.habits.domain.WildChunks
import com.lifemax.rpg.feature.habits.domain.WorldRand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The railroad as the app reads it (docs/RAIL.md): the shipped rail.json of the town, the route planner answering exactly
 * as the game's src/world/rail.ts did for the routes tools/export-rail.ts wrote (rail-checks.json), and the world's keyed
 * randomness and wilderness grown bit for bit as the TypeScript grows them.
 */
class RailNetTest {
    private val net = RailNet.parse(File("../app/src/main/assets/town/gamma/rail.json").readText())!!
    private val checks = JSONObject(RailNetTest::class.java.getResource("/rail-checks.json")!!.readText())

    @Test fun readsTheTownsRailroad() {
        assertEquals("gamma", net.town)
        assertEquals((2 * net.radius + 1) * (2 * net.radius + 1), net.towns.size)
        val r = net.rail
        assertNotNull(r.depot)
        assertTrue(r.tracks.any { it.kind == "main" } && r.tracks.any { it.kind == "siding" })
        assertTrue(r.crossings.isNotEmpty())
        // every link's two towns are in the export, and its route runs from one to the other
        for (l in net.links) if (net.townOf(l.a) != null && net.townOf(l.b) != null) assertTrue(l.pts.size >= 4)
        // the home town is cell 0,0 and has a station from its first line
        val first = net.linksAt("0,0").minOf { it.built }
        if (first > net.from) assertEquals('n', net.status("0,0", first - 1))
        assertTrue(net.status("0,0", maxOf(first, net.from)) != 'n')
    }

    @Test fun plansTheSameRoutesAsTheGame() {
        val routes = checks.getJSONArray("routes")
        var n = 0
        for (i in 0 until routes.length()) {
            val c = routes.getJSONObject(i)
            val want = c.getJSONObject("route")
            val got = net.planRoute(c.getString("from"), c.getString("to"), c.getInt("year"), c.getDouble("time"))
            val what = "${c.getString("from")} -> ${c.getString("to")} in ${c.getInt("year")} at ${c.getDouble("time")}"
            if (want.has("reason")) { assertEquals(what, want.getString("reason"), got.reason); continue }
            assertTrue("$what: ${got.reason}", got.ok)
            val legs = want.getJSONArray("legs")
            assertEquals(what, legs.length(), got.legs!!.size)
            for (k in 0 until legs.length()) {
                val w = legs.getJSONObject(k); val g = got.legs!![k]
                assertEquals("$what leg $k", w.getString("mode"), g.mode)
                assertEquals("$what leg $k", w.getString("to"), g.to)
                assertEquals("$what leg $k depart", w.getDouble("depart"), g.depart, 1e-6)
                assertEquals("$what leg $k arrive", w.getDouble("arrive"), g.arrive, 1e-6)
                assertEquals("$what leg $k km", w.getDouble("km"), g.km, 1e-6)
                if (w.has("operator")) assertEquals(w.getString("operator"), g.operator)
                if (w.has("stops")) assertEquals(w.getJSONArray("stops").toList(), g.stops)
            }
            assertEquals(what, want.getDouble("fare"), got.fare, 1e-9)
            assertEquals(what, want.getInt("changes"), got.changes)
            assertEquals(what, want.getJSONArray("notes").toList(), got.notes)
            n++
        }
        assertTrue("routes checked: $n", n > 40)
    }

    @Test fun findsTownsByName() {
        val t = net.towns.first { it.cx == 2 && it.cy == -1 }
        assertEquals(t.id, net.find(t.name.uppercase(), 2, -1)!!.id)
        assertTrue(net.search(t.name.take(3)).any { it.id == t.id })
        assertTrue(net.planRoute(net.home.name, t.name, 1955).ok)
    }

    @Test fun growsTheWorldAsTheGameDoes() {
        val rands = checks.getJSONArray("rands")
        for (i in 0 until rands.length()) {
            val r = rands.getJSONObject(i)
            val k = r.getJSONArray("keys").toList()
            val v = WorldRand.rand(k[0] as String, *k.drop(1).map { if (it is Number) it.toInt() else it!! }.toTypedArray())
            assertEquals("rand $k", r.getDouble("v"), v, 0.0)
        }
        val wild = checks.getJSONArray("wild")
        for (i in 0 until wild.length()) {
            val c = wild.getJSONObject(i)
            val want = c.getJSONArray("props")
            val got = WildChunks.chunk(checks.getString("world"), c.getInt("cx"), c.getInt("cy"))
            assertEquals(want.length(), got.size)
            for (j in 0 until want.length()) {
                val p = want.getJSONObject(j)
                assertEquals(p.getString("kind"), got[j].kind); assertEquals(p.getInt("x"), got[j].x); assertEquals(p.getInt("y"), got[j].y)
                assertTrue(abs(p.getDouble("look") - got[j].look) < 1e-12)
            }
        }
    }

    @Test fun runsTrainsThroughTheTownByTheTimetable() {
        for (year in listOf(1948, 1960, 1985, 2025)) {
            val sc = RailScene(net, year)
            assertTrue(sc.paths.isNotEmpty())
            var seen = 0; var stopped = 0
            var m = 0.0
            while (m < 1440.0) { val ts = sc.trains(m); seen += ts.size; stopped += ts.count { it.stopping }; m += 2.0 }
            assertTrue("$year: trains seen $seen", seen > 0)
            // passenger trains stop only while the station is open to passengers
            if (sc.station != 'p') assertEquals("$year", 0, stopped)
            // the track's bed is clear of the woods, the town's middle is not on it
            val p = sc.paths.first()
            val at = FloatArray(4); p.at(p.length / 3f, at)
            assertTrue(sc.onBed(at[0], at[1]))
            assertTrue(!sc.onBed(net.home.x, net.home.z))
        }
    }

    @Test fun crossingsWorkWhileATrainIsNear() {
        val sc = RailScene(net, 2025)
        val c = sc.crossings.first()
        var on = 0; var off = 0
        var m = 0.0
        while (m < 1440.0) { if (sc.crossingActive(c, sc.trains(m))) on++ else off++; m += .25 }
        assertTrue("on $on off $off", on > 0 && off > on)
    }

    @Test fun ridesFollowTheTrackAtWorldScale() {
        for ((year, dest) in listOf(2025 to "2,0", 1955 to "-2,0", 1960 to "0,2")) {
            val route = net.planRoute("0,0", dest, year, 9 * 60.0)
            if (route.legs?.firstOrNull()?.mode != "train") continue
            val ride = RailRide(net, route)
            val p = ride.path.pts
            // no gap between its pieces (a straight in a town's plan can be long) and never doubling back
            for (i in 1 until p.size / 2) assertTrue("$dest $year: a jump at $i", kotlin.math.hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1]) < 12000f)
            // (a change of trains may go back the way it came: only one train's ride must run on)
            if (route.changes == 0) for (i in 2 until p.size / 2) {
                val ax = p[i * 2 - 2] - p[i * 2 - 4]; val az = p[i * 2 - 1] - p[i * 2 - 3]; val bx = p[i * 2] - p[i * 2 - 2]; val bz = p[i * 2 + 1] - p[i * 2 - 1]
                val cos = (ax * bx + az * bz) / (kotlin.math.hypot(ax, az) * kotlin.math.hypot(bx, bz)).coerceAtLeast(1e-6f)
                assertTrue("$dest $year: doubles back at $i ($cos)", cos > -0.5f)
            }
            // from the home depot to the last stop, which is the destination's station
            val d = net.rail.depot!!
            assertTrue(kotlin.math.hypot(p[0] - d.x, p[1] - d.z) < 1f)
            assertEquals(net.townOf(ride.to)!!.name, ride.stops.last().second)
            assertTrue(ride.at(0f, 30f) == 0f && ride.at(30f, 30f) == ride.stops.last().first)
        }
    }
}
