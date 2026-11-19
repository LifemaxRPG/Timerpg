import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.NodeKind
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.RowEnd
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPoint
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.domain.at
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The town of street.json v2 (the fixture tools/town-graph.test.ts keeps current: town gamma). */
private val RAW: String = TownMapTest::class.java.getResource("/town-gamma.json")!!.readText()
private val TOWN: Town = Town.parse(RAW)!!

private fun dist(a: TownPoint, b: TownPoint) = hypot(a.x - b.x, a.z - b.z)

class TownMapTest {
    @After fun reset() {
        CornerWalk.reset(); CornerWalk.blocked = false; CornerWalk.holdEnd = false
        GameState.moveX = 0f; GameState.moveLane = 0f; GameState.street = 0; GameState.upWalks = false; GameState.vx = 0f
        GameState.walkOn = 0f
    }

    @Test fun parsesAndTheCountsMatchTheExport() {
        val g = JSONObject(RAW).getJSONObject("graph")
        assertEquals("gamma", TOWN.seed)
        assertEquals(2025, TOWN.year)
        assertEquals(g.getJSONArray("nodes").length(), TOWN.nodes.size)
        assertEquals(g.getJSONArray("roads").length(), TOWN.roads.size)
        assertEquals(g.getJSONArray("rows").length(), TOWN.rows.size)
        assertEquals(g.getJSONArray("lots").length(), TOWN.lots.size)
        assertEquals(g.getJSONArray("props").length(), TOWN.props.size)
        // gamma as T2 exported it
        assertEquals(52, TOWN.nodes.size)
        assertEquals(mapOf(NodeKind.CROSS to 19, NodeKind.TEE to 4, NodeKind.BEND to 5, NodeKind.END to 24), TOWN.nodes.groupingBy { it.kind }.eachCount())
        assertEquals(56, TOWN.roads.size)
        assertEquals(112, TOWN.rows.size)
        assertEquals(58, TOWN.rows.count { it.buildings.isNotEmpty() })
        // with the cinema (b100, on Main Street), the four stores (b101-b104) and the pack's buildings with nobody in them
        // (b105-b112: two car parks, two hotels and two offices behind Main Street, the town hall in the plaza, the chapel)
        assertEquals(113, TOWN.buildings.size)
        assertEquals(mapOf(PlacementKind.SHOP to 22, PlacementKind.HOME to 72, PlacementKind.LANDMARK to 11, PlacementKind.EXTRA to 8), TOWN.buildings.groupingBy { it.kind }.eachCount())
        assertEquals(94, TOWN.lots.size)
        assertEquals(11, TOWN.landmarks.size)
        assertEquals(5, TOWN.neighbourhoods.size)
        assertEquals(6, TOWN.bridges.size)
        assertEquals(116, TOWN.water.size)
        assertEquals(11, TOWN.plazaProps.size) // (the town hall at the plaza's back stands where two of its trees were)
        assertEquals(1.6929f, TOWN.k, 1e-4f)
        // every sprite once
        assertEquals(113, TOWN.buildings.map { it.sprite }.toSet().size)
        // the spawn: on the civic avenue, the hotel on its row (the clock tower across)
        assertEquals("14L", TOWN.spawn.row)
        assertEquals(14, TOWN.civicRoad)
        assertEquals(TOWN.civicRoad, TOWN.row(TOWN.spawn.row).road)
        assertEquals("b98", TOWN.placement("b98")!!.second.sprite)
        assertEquals("14L", TOWN.placement("b98")!!.first.id)
    }

    @Test fun aV1StreetJsonHasNoTown() {
        assertNull(Town.parse("""{"town":"eta","streets":[],"shops":[]}"""))
        val asset = File("../app/src/main/assets/street.json")
        if (asset.exists() && !JSONObject(asset.readText()).has("graph")) assertNull(Town.parse(asset.readText()))
    }

    @Test fun everyRowsGeometryAgreesWithItsExport() {
        for (r in TOWN.rows) {
            assertTrue(r.id, dist(r.pointAt(0f, 0f), r.origin) < .01f)
            assertTrue(r.id, dist(r.pointAt(r.length, 0f), r.pts.last()) < .05f)
            // the lanes go out from the buildings, towards the road
            val p = r.pointAt(r.length / 2f, 10f)
            val q = r.pointAt(r.length / 2f, 0f)
            val n = r.normalAt(r.length / 2f)
            assertTrue(r.id, ((p.x - q.x) * n.x + (p.z - q.z) * n.z) < -20f)
            // the row's start and end are its nodes' places, a road half out
            val a = TOWN.node(r.from)
            assertEquals(r.id, 89f, dist(r.origin, TownPoint(a.x, a.z)), .05f)
            val b = TOWN.node(r.to)
            assertEquals(r.id, 89f, dist(r.pts.last(), TownPoint(b.x, b.z)), .05f)
            // buildings within the row
            for (bd in r.buildings) assertTrue("${r.id} ${bd.sprite}", bd.x1 > bd.x0)
        }
    }

    @Test fun pointAtIsContinuousRoundTheBends() {
        val bent = TOWN.rows.filter { it.bends.isNotEmpty() }
        assertTrue("the main street's steps", bent.size >= 2)
        for (r in bent) for (x in r.bends) {
            // exact on the building line at the corner itself
            val corner = r.pts[r.bends.indexOf(x) + 1]
            assertTrue(r.id, dist(r.pointAt(x, 0f), corner) < .05f)
            for (lane in floatArrayOf(0f, 7f, 16f)) {
                // walked in small steps across the corner: no step jumps
                var prev = r.pointAt(x - 60f, lane)
                var s = x - 60f
                while (s < x + 60f) {
                    s += .5f
                    val p = r.pointAt(s, lane)
                    assertTrue("${r.id} at $s lane $lane", dist(p, prev) < 1.5f)
                    prev = p
                }
            }
            // and away from the corner the lane is exactly its distance out from the line
            val far = r.pointAt(x - 50f, 7f)
            val line = r.pointAt(x - 50f, 0f)
            assertEquals(7f * Crossroads.LANE, dist(far, line), .01f)
        }
    }

    @Test fun everyTransitionLeadsSomewhereAndBack() {
        var n = 0
        for (r in TOWN.rows) for (t in r.transitions) {
            val to = TOWN.rowOrNull(t.to)
            assertNotNull("${r.id} -> ${t.to}", to)
            n++
            val node = r.node(t.end)
            assertTrue("${r.id} ${t.kind} ${t.to} meets at node $node", to!!.from == node || to.to == node || t.kind == TransitionKind.STRAIGHT)
            when (t.kind) {
                TransitionKind.CROSS -> assertEquals(r.road, to.road)
                TransitionKind.TURN_IN -> {
                    // the way back: round the same corner the other way
                    val back = if (t.end == RowEnd.END) to.start.turnIn else to.end.turnIn
                    assertEquals("${r.id} <- ${to.id}", r.id, back)
                }
                TransitionKind.STRAIGHT -> {}
            }
        }
        assertTrue(n > 200)
    }

    @Test fun neighbourhoodLookups() {
        val spawn = TOWN.row(TOWN.spawn.row)
        // the hotel's neighbourhood where it stands
        val hotel = spawn.buildings.first { it.sprite == "b98" }
        assertEquals("waterfront", TOWN.neighbourhoodAt(spawn, hotel.mid)!!.id)
        // a row with no buildings still has one: the nearest lot's
        val empty = TOWN.rows.first { it.buildings.isEmpty() }
        assertNotNull(TOWN.neighbourhoodAt(empty, empty.length / 2f))
        // the nearest row to a point on a row is that row (or its twin on the same line: never further than a hair)
        val p = spawn.pointAt(TOWN.spawn.x, 0f)
        val (near, x) = TOWN.nearestRow(p.x, p.z)!!
        assertTrue(dist(near.pointAt(x, 0f), p) < .01f)
        // around the spawn: itself first, then rows one step off
        val around = TOWN.rowsAround(spawn, 1)
        assertEquals(spawn.id, around.first().id)
        assertEquals(spawn.transitions.map { it.to }.toSet() + spawn.id, around.map { it.id }.toSet())
        assertEquals("14R", TOWN.facing(spawn)!!.id)
    }

    @Test fun theLayoutIsTheExportsAndMirrorsExactly() {
        val r = TOWN.row("8R")
        val l = r.layout()
        assertEquals(r.buildings.size, l.centers.size)
        assertEquals(0f, l.start, 0f); assertEquals(r.length, l.end, 0f)
        assertEquals(r.buildings.sortedBy { it.x0 }.map { it.x0 }, l.lefts)
        val m = r.layout(mirrored = true)
        assertEquals(l.centers.map { r.length - it }.sorted(), m.centers)
        for (i in m.lefts.indices) assertTrue(m.lefts[i] < m.centers[i])
        assertEquals(r.furniture.size, m.furniture.size)
    }

    /** A right turn (the shops of 9L walked to their end, round the corner into the civic avenue 14L) and its mirror
     *  (14L walked back to its start and round the same corner into 9L). */
    @Test fun aRightTurnPlansTheRealCorner() = checkTurn(TOWN.row("9L"), TOWN.row("14L"), mirrored = false)

    @Test fun aLeftTurnIsTheMirroredPlan() = checkTurn(TOWN.row("14L"), TOWN.row("9L"), mirrored = true)

    @Test fun everyTurnInTheTownMakesAPlan() {
        var n = 0
        for (r in TOWN.rows) for (e in RowEnd.values()) {
            val to = TOWN.next(r, e, TransitionKind.TURN_IN) ?: continue
            val cr = Crossroads.at(TOWN, r.node(e), r, to)
            val f = cr.frame!!
            assertEquals(e == RowEnd.START, f.mirrored)
            // the plan's axes are a rigid frame; mirrored, the other hand
            val det = f.ex.x * f.ez.z - f.ex.z * f.ez.x
            assertEquals(if (f.mirrored) 1f else -1f, det, 1e-4f)
            agree(cr, r, to)
            n++
        }
        assertEquals(168, n)
    }

    /** The plan and the rows say the same places: both streets' x and lanes, near the corner. */
    private fun agree(cr: Crossroads, main: Row, cross: Row) {
        val f = cr.frame!!
        for (lane in floatArrayOf(0f, 7f, 15f)) for (d in floatArrayOf(0f, 40f, 120f)) {
            // the main street, d before the mouth
            val lx = cr.westEdge - d
            val w = f.toWorld(cr.mainX(lx), cr.mainZ(lane))
            assertTrue("${main.id} main $lx lane $lane", dist(w, main.pointAt(f.mainRowX(lx), lane)) < .05f)
            // the cross street, d past its start
            val cx = cr.cross.start + d
            val c = f.toWorld(cr.crossX(lane), cr.crossZ(cx))
            assertTrue("${cross.id} cross $cx lane $lane", dist(c, cross.pointAt(f.crossRowX(cx), lane)) < .05f)
        }
        // the mouth's middle is the cross road's centre line; the plan maps back
        val node = TOWN.node(f.node)
        val m = f.toWorld(cr.mouthMid - cr.westEdge, -89f)
        assertTrue(dist(m, TownPoint(node.x, node.z)) < .05f)
        val back = f.toPlan(m.x, m.z)
        assertEquals(89f, back.x, .01f); assertEquals(-89f, back.z, .01f)
    }

    private fun checkTurn(from: Row, to: Row, mirrored: Boolean) {
        val node = if (mirrored) from.from else from.to
        val cr = Crossroads.at(TOWN, node, from, to)
        val f = cr.frame!!
        assertEquals(mirrored, f.mirrored)
        assertEquals(to.id, f.crossRow)
        agree(cr, from, to)
        // the mouth lies where the main row's x reaches the node: in row x, within a road half of it
        val mouthRowX = f.mainRowX(cr.mouthMid)
        assertEquals(if (mirrored) 0f else from.length, mouthRowX, .01f)
        assertTrue(cr.inMouth(cr.mouthMid - 30f))
        assertFalse(cr.inMouth(cr.westEdge - 60f))

        // walk it: up in the mouth, round the corner, onto the cross street
        GameState.street = 0; GameState.x = cr.mouthMid - 20f; GameState.lane = 7f; GameState.edge = 0; GameState.vx = 0f
        val startWorld = from.pointAt(f.mainRowX(GameState.x), 7f)
        GameState.moveLane = -1f
        assertTrue(CornerWalk.arm(cr, 0))
        assertTrue(dist(f.toWorld(CornerWalk.wx, CornerWalk.wz), startWorld) < .05f)
        GameState.moveLane = 0f
        val boxes = footprints(listOf(from, to))
        var r = 0
        var steps = 0
        while (r == 0 && steps < 2000) {
            val y = CornerWalk.yaw
            r = CornerWalk.walk(1f / 60f, cr, cos(y), sin(y), -sin(y), cos(y))
            val w = f.toWorld(CornerWalk.wx, CornerWalk.wz)
            for ((name, poly) in boxes) assertFalse("walked into $name at $w", inside(poly, w))
            steps++
        }
        assertEquals(1, r)
        assertEquals(1, GameState.street)
        // arrived on the cross street, its x and lane the row's own place there, past the corner
        val arrive = f.toWorld(cr.crossX(GameState.lane), cr.crossZ(GameState.x))
        val rowX = f.crossRowX(GameState.x)
        assertTrue(dist(arrive, to.pointAt(rowX, GameState.lane)) < .05f)
        val nodeP = TOWN.node(node).let { TownPoint(it.x, it.z) }
        // a road half (the corner) and a few steps along from the node, the way the cross row leads away from it
        assertEquals(89f + 20f, abs(rowX - (if (mirrored) to.length else 0f)), .01f)
        assertTrue(dist(to.pointAt(rowX, 0f), nodeP) > 89f)
        // the walk's heading at the end is up the cross street, away from the node (in the world)
        val h = f.dirToWorld(CornerWalk.hx, CornerWalk.hz)
        val away = if (mirrored) to.alongAt(to.length).let { TownPoint(-it.x, -it.z) } else to.alongAt(0f)
        assertTrue(h.x * away.x + h.z * away.z > .95f)
        // and the main street's world sense: the walk began going the way the turn is walked (+x right, -x left)
        val walkedDir = f.dirToWorld(1f, 0f)
        val mainAlong = from.alongAt(if (mirrored) 0f else from.length)
        assertEquals(if (mirrored) -1f else 1f, walkedDir.x * mainAlong.x + walkedDir.z * mainAlong.z, 1e-4f)
    }

    /** The buildings of [rows] as world quads (front at setback, back at setback + depth). */
    private fun footprints(rows: List<Row>): List<Pair<String, List<TownPoint>>> = rows.flatMap { r ->
        r.buildings.map { b ->
            val n0 = r.normalAt(b.mid)
            val a = r.pointAt(b.x0, 0f)
            val c = r.pointAt(b.x1, 0f)
            val f = b.setback
            val k = b.setback + b.depth
            b.sprite to listOf(
                TownPoint(a.x + n0.x * f, a.z + n0.z * f), TownPoint(c.x + n0.x * f, c.z + n0.z * f),
                TownPoint(c.x + n0.x * k, c.z + n0.z * k), TownPoint(a.x + n0.x * k, a.z + n0.z * k),
            )
        }
    }

    private fun inside(poly: List<TownPoint>, p: TownPoint): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val c = (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x)
            val s = if (c > 0f) 1 else if (c < 0f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}
