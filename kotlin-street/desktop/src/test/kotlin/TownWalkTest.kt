import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.RowEnd
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPoint
import com.lifemax.rpg.feature.habits.domain.Transition
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalk
import com.lifemax.rpg.feature.habits.presentation.scene.locate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** The town of street.json v2 (the fixture tools/town-graph.test.ts keeps current: town gamma). */
private val WALK_TOWN: Town = Town.parse(TownWalkTest::class.java.getResource("/town-gamma.json")!!.readText())!!

/**
 * The town walked by a scripted bot through [TownWalk]: every row reached, every transition there and back, both hands of
 * every turn alike, nothing stuck, the world position continuous and never inside a building.
 */
class TownWalkTest {
    private val dt = 1f / 60f
    private lateinit var walk: TownWalk
    private var lastW: TownPoint? = null
    private var lastCam: Pair<TownPoint, Float>? = null
    private var frames = 0
    private var maxJump = 0f
    /** Where the last turn arrived (row x), before the walk on. */
    private var arrivedX = 0f

    /** Stands the walker somewhere new (the continuity checks start again from there). */
    private fun place(r: Row, x: Float, lane: Float) { walk.placeOn(r, x, lane); lastW = null; lastCam = null }

    /** Every building of the town (world quads) with its bounding box, for the inside check after every frame. */
    private val all by lazy {
        val w = TownWalk(WALK_TOWN)
        WALK_TOWN.rows.flatMap { w.footprints(it) }.map { f ->
            Triple(f, floatArrayOf(f.corners.minOf { it.x }, f.corners.minOf { it.z }), floatArrayOf(f.corners.maxOf { it.x }, f.corners.maxOf { it.z }))
        }
    }

    @Before fun setUp() {
        clearKeys()
        CornerWalk.reset(); CornerWalk.blocked = false; CornerWalk.holdEnd = false
        GameState.dir = 1f; GameState.facing = 0; GameState.vx = 0f; GameState.walkOnDir = 1f
        walk = TownWalk(WALK_TOWN)
        lastW = null; lastCam = null
    }

    @After fun tearDown() {
        clearKeys()
        CornerWalk.reset()
        GameState.street = 0; GameState.vx = 0f; GameState.walkOnDir = 1f
    }

    private fun clearKeys() {
        GameState.moveX = 0f; GameState.moveLane = 0f; GameState.upWalks = false; GameState.walkOn = 0f
    }

    /** One frame, with the checks every frame must pass. */
    private fun frame(): TownWalk.TownStepResult? {
        val r = walk.step(dt)
        frames++
        val w = TownPoint(walk.wx, walk.wz)
        lastW?.let { p ->
            val d = hypot(w.x - p.x, w.z - p.z)
            maxJump = maxOf(maxJump, d)
            assertTrue("jumped $d at ${walk.row.id} x ${walk.x} lane ${walk.lane} (${walk.transit?.kind})", d < 4f)
        }
        lastW = w
        // the derived street view agrees with the world (off a transit)
        if (walk.transit == null) {
            val q = walk.row.pointAt(walk.x, walk.lane)
            assertTrue("${walk.row.id} ${walk.x}", hypot(q.x - w.x, q.z - w.z) < .01f)
            assertTrue(walk.lane >= GameState.LANE_MIN - 1e-3f && walk.lane <= TownWalk.LANE_MAX + 1e-3f)
        }
        // the camera turns and slides smoothly
        val c = walk.camera
        assertEquals(1f, hypot(c.fx, c.fz), 1e-3f)
        lastCam?.let { (t, yaw) ->
            assertTrue("camera target jumped at ${walk.row.id}", hypot(c.tx - t.x, c.tz - t.z) < 20f)
            assertTrue("camera turned ${angle(c.yaw - yaw)} at ${walk.row.id} x ${walk.x} lane ${walk.lane} transit ${walk.transit?.kind} ${walk.transit?.let { it.from.id + "->" + it.to.id }} vx ${GameState.vx}", abs(angle(c.yaw - yaw)) < .25f)
        }
        lastCam = TownPoint(c.tx, c.tz) to c.yaw
        // never inside a building
        for ((f, lo, hi) in all) {
            if (w.x < lo[0] || w.x > hi[0] || w.z < lo[1] || w.z > hi[1]) continue
            assertFalse("inside ${f.building.sprite} of ${f.row} at ${walk.row.id} x ${walk.x} lane ${walk.lane}", f.near(w.x, w.z, 0f))
        }
        return r
    }

    private fun angle(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }

    /** Runs frames until [done] (failing as stuck after [max]); returns how many. */
    private fun until(max: Int = 4000, what: String, done: () -> Boolean): Int {
        var n = 0
        while (!done()) {
            frame()
            n++
            assertTrue("stuck: $what (row ${walk.row.id} x ${walk.x} lane ${walk.lane} transit ${walk.transit?.kind})", n < max)
        }
        return n
    }

    private fun settle() { clearKeys(); until(what = "stopping") { abs(GameState.vx) < 1e-3f && walk.transit == null } }

    /** Walks along the row to x [target] (within a few units) at the lane it is in. */
    private fun walkTo(target: Float) {
        clearKeys()
        frame()
        if (abs(walk.x - target) < 6f) return
        val s = if (target > walk.x) 1f else -1f
        GameState.moveX = s * .7f
        until(what = "walking to $target") { (target - walk.x) * s < 4f }
        settle()
    }

    /** Moves across the pavement to lane [target] with gentle pushes (never the up or down key). */
    private fun laneTo(target: Float) {
        clearKeys()
        frame()
        val s = if (target > walk.lane) 1f else -1f
        GameState.moveLane = .45f * s
        until(what = "lane $target") { (target - walk.lane) * s < .3f || (s > 0 && walk.lane >= TownWalk.LANE_MAX - 1e-3f) || (s < 0 && walk.lane <= GameState.LANE_MIN + 1e-3f) }
        settle()
    }

    /** Takes transition [t] off the row the walker is on; returns frames the transition itself took. */
    private fun take(t: Transition): Int {
        assertEquals(t.from, walk.row.id)
        val r = walk.row
        val before = walk.rowChanges
        return when (t.kind) {
            TransitionKind.STRAIGHT -> {
                if (walk.lane < 7f || walk.lane > 10f) laneTo(8f)
                GameState.moveX = if (t.end == RowEnd.END) 1f else -1f
                val n = until(what = "straight ${t.from}->${t.to}") { walk.rowChanges != before }
                assertEquals(t.to, walk.row.id)
                settle()
                n
            }
            TransitionKind.TURN_IN -> {
                if (abs(walk.lane - 7f) > 1f) laneTo(7f)
                walkTo(if (t.end == RowEnd.END) r.length - 45f else 45f)
                clearKeys(); frame()
                GameState.moveLane = -1f
                frame()
                val tr = walk.transit
                assertNotNull("no turn at ${t.from} ${t.end} x ${walk.x}", tr)
                assertEquals(TransitionKind.TURN_IN, tr!!.kind)
                val n = until(what = "turn ${t.from}->${t.to}") { walk.transit == null }
                arrivedX = walk.x
                assertEquals(t.to, walk.row.id)
                settle()
                n
            }
            TransitionKind.CROSS -> {
                laneTo(TownWalk.LANE_MAX)
                clearKeys(); frame()
                GameState.moveLane = 1f
                frame()
                assertEquals(TransitionKind.CROSS, walk.transit?.kind)
                val n = until(what = "cross ${t.from}->${t.to}") { walk.transit == null }
                assertEquals(t.to, walk.row.id)
                settle()
                n
            }
        }
    }

    /** The way back from [t]'s target to its row. */
    private fun reverse(t: Transition): Transition {
        val to = WALK_TOWN.row(t.to)
        return to.transitions.first { it.kind == t.kind && it.to == t.from && (t.kind == TransitionKind.CROSS || to.node(it.end) == WALK_TOWN.row(t.from).node(t.end)) }
    }

    @Test fun spawnsOnTheCivicAvenueWithTheWorldAsTheTruth() {
        assertEquals(WALK_TOWN.spawn.row, walk.row.id)
        val p = walk.row.pointAt(WALK_TOWN.spawn.x, WALK_TOWN.spawn.lane)
        assertEquals(p.x, walk.wx, 1e-3f); assertEquals(p.z, walk.wz, 1e-3f)
        // placed by a world point: the row, x and lane are derived from it
        val r = WALK_TOWN.row("3R")
        val q = r.pointAt(900f, 9f)
        walk.placeAt(q.x, q.z); lastW = null; lastCam = null
        assertEquals("3R", walk.row.id)
        assertEquals(900f, walk.x, .05f); assertEquals(9f, walk.lane, .05f)
        // locate inverts pointAt on every row, bends included, and past the ends
        for (row in WALK_TOWN.rows) for (x in floatArrayOf(-30f, 10f, row.length / 3f, row.length / 2f, row.length - 5f, row.length + 30f))
            for (l in floatArrayOf(3f, 8f, 14f)) {
                val w = row.pointAt(x, l)
                val (lx, ll) = row.locate(w.x, w.z)
                val back = row.pointAt(lx, ll)
                assertTrue("${row.id} $x $l", hypot(back.x - w.x, back.z - w.z) < .05f)
            }
        // the rows to keep live: the row itself first, then its neighbours
        val live = walk.liveRows()
        assertEquals("3R", live.first().id)
        assertTrue(live.any { it.id == "3L" })
        // the camera at rest looks square at the row's buildings
        assertEquals(walk.row.normalAt(walk.x).x, walk.camera.fx, 1e-3f)
        assertEquals(walk.row.normalAt(walk.x).z, walk.camera.fz, 1e-3f)
    }

    @Test fun everyTransitionWorksBothWays() {
        var n = 0
        for (r in WALK_TOWN.rows) for (t in r.transitions) {
            setUp()
            place(r, r.length / 2f, 8f)
            frame()
            take(t)
            val back = reverse(t)
            take(back)
            assertEquals("${t.kind} ${t.from}->${t.to} and back", r.id, walk.row.id)
            n++
        }
        assertEquals(WALK_TOWN.rows.sumOf { it.transitions.size }, n)
        println("TownWalkTest: $n transitions there and back, $frames frames, largest step ${"%.2f".format(maxJump)}")
    }

    @Test fun theBotReachesEveryRowFromTheSpawnByWalking() {
        val seen = HashSet<String>()
        var taken = 0
        fun visit(r: Row) {
            seen += r.id
            for (t in r.transitions) {
                if (t.to in seen) continue
                take(t)
                taken++
                visit(WALK_TOWN.row(t.to))
                take(reverse(t))
                taken++
                assertEquals(r.id, walk.row.id)
            }
        }
        frame()
        visit(walk.row)
        assertEquals(WALK_TOWN.rows.map { it.id }.toSet(), seen)
        println("TownWalkTest: walked all ${seen.size} rows from the spawn in one go: $taken transitions, $frames frames (${"%.0f".format(frames / 60f)} s of play)")
    }

    @Test fun leftAndRightTurnsAreMirrorImages() {
        var pairs = 0
        for (a in WALK_TOWN.rows) {
            val bId = a.end.turnIn ?: continue
            val b = WALK_TOWN.row(bId)
            val node = WALK_TOWN.node(a.to).let { TownPoint(it.x, it.z) }
            // right: a walked to its end, round the corner into b
            setUp()
            place(a, a.length - 45f, 7f); frame()
            val yaw0 = walk.camera.yaw
            val nRight = take(Transition(a.id, RowEnd.END, TransitionKind.TURN_IN, b.id))
            val lenRight = CornerWalk.pathLength
            val swingRight = angle(walk.camera.yaw - yaw0)
            val arriveRight = hypot(b.pointAt(arrivedX, 0f).x - node.x, b.pointAt(arrivedX, 0f).z - node.z)
            assertEquals(109f, arrivedX, .5f)
            // left: b walked back to its start, round the same corner into a
            setUp()
            place(b, 45f, 7f); frame()
            val yaw1 = walk.camera.yaw
            val nLeft = take(Transition(b.id, RowEnd.START, TransitionKind.TURN_IN, a.id))
            val lenLeft = CornerWalk.pathLength
            val swingLeft = angle(walk.camera.yaw - yaw1)
            val arriveLeft = hypot(a.pointAt(arrivedX, 0f).x - node.x, a.pointAt(arrivedX, 0f).z - node.z)
            assertEquals(a.length - 109f, arrivedX, .5f)
            // the same walk, mirrored: as long, as many frames, arriving as far from the node; the camera a quarter each way
            assertEquals("${a.id}/${b.id}", lenRight, lenLeft, .05f)
            assertTrue("${a.id}/${b.id} $nRight $nLeft", abs(nRight - nLeft) <= 1)
            assertEquals(arriveRight, arriveLeft, .5f)
            assertEquals(Math.PI.toFloat() / 2f, abs(swingRight), .01f)
            assertEquals(-swingRight, swingLeft, .01f)
            // walking on: away from the corner (right: +x on b; left: -x on a)
            assertTrue(GameState.dir < 0f)
            pairs++
        }
        assertEquals(84, pairs)
    }

    @Test fun walkingTheLengthOfEveryRowNeverSticks() {
        for (r in WALK_TOWN.rows) for (lane in floatArrayOf(4f, 8f)) for (s in floatArrayOf(1f, -1f)) {
            setUp()
            place(r, r.length / 2f, lane); frame()
            val before = walk.rowChanges
            GameState.moveX = s
            val end = if (s > 0) RowEnd.END else RowEnd.START
            val straight = r.ends(end).straight
            until(what = "the length of ${r.id} lane $lane dir $s") {
                if (straight != null) walk.rowChanges != before
                else GameState.edge != 0 || (s > 0 && walk.x >= r.length - 20.01f) || (s < 0 && walk.x <= 20.01f)
            }
            if (straight != null) assertEquals(straight, walk.row.id)
        }
    }

    @Test fun theBuildingsKeepThePlayerOut() {
        // pushed hard into the fronts all along a row of shops (no yard: setback 0): held on the pavement
        val r = WALK_TOWN.row("9L")
        place(r, 300f, 8f); frame()
        GameState.moveLane = -.45f
        until(what = "to the wall") { walk.lane <= GameState.LANE_MIN + 1e-3f }
        GameState.moveX = .7f; GameState.moveLane = -.45f
        repeat(1200) { frame() }
        assertTrue(walk.lane >= GameState.LANE_MIN - 1e-3f)
        // the doors are on the front walls, the footprints solid behind them
        for (row in WALK_TOWN.rows) for (b in row.buildings) {
            val d = walk.door(row, b)
            assertTrue("${b.sprite} door", walk.footprints(row).first { it.building === b }.near(d.x, d.z, .01f))
            val n = row.normalAt(b.mid)
            val inside = TownPoint(d.x + n.x * b.depth / 2f, d.z + n.z * b.depth / 2f)
            assertTrue(walk.blockedAt(inside.x, inside.z, row))
            // the pavement before it is clear
            val pave = row.pointAt(b.mid, GameState.LANE_MIN)
            assertFalse("${b.sprite} pavement", walk.blockedAt(pave.x, pave.z, row))
        }
        // the building in front: the one whose front covers x
        place(r, r.buildings.first().mid, 8f)
        assertEquals(r.buildings.first().sprite, walk.front!!.sprite)
    }

    @Test fun crossingOnlyAtTheZebrasWhenMidBlockIsOff() {
        val r = WALK_TOWN.row("3R")
        walk.midBlock = false
        place(r, r.length / 2f, TownWalk.LANE_MAX); frame()
        GameState.moveLane = 1f
        repeat(30) { frame() }
        assertNull(walk.transit)
        assertEquals("3R", walk.row.id)
        clearKeys(); frame()
        assertTrue(walk.mayCross(r, 100f))
        assertFalse(walk.mayCross(r, r.length / 2f))
        walk.midBlock = true
        GameState.moveLane = 1f
        frame()
        assertEquals(TransitionKind.CROSS, walk.transit?.kind)
        until(what = "crossing") { walk.transit == null }
        assertEquals("3L", walk.row.id)
        // arrived facing the other way: the camera half round, looking at the new row's buildings
        val n = walk.row.normalAt(walk.x)
        assertEquals(n.x, walk.camera.fx, 1e-3f); assertEquals(n.z, walk.camera.fz, 1e-3f)
    }

    @Test fun anUpPressAwayFromAMouthOnlyStepsIn() {
        val r = WALK_TOWN.row("3R")
        place(r, r.length / 2f, 10f); frame()
        GameState.moveLane = -1f
        repeat(60) { frame() }
        assertNull(walk.transit)
        assertEquals("3R", walk.row.id)
        assertTrue(walk.lane < 10f)
        // the heading follows the walk: in towards the buildings
        val n = r.normalAt(walk.x)
        assertTrue(walk.hx * n.x + walk.hz * n.z > .99f)
        assertEquals(atan2(walk.hx, -walk.hz), walk.heading, 1e-5f)
    }

    @Test fun theCornerWalkOfTheCrossroadsStillHandsOverAsBefore() {
        // the crossroads' own path keeps its defaults: the walk on after a turn goes +x
        assertEquals(1f, GameState.walkOnDir)
    }
}
