import androidx.compose.ui.graphics.Matrix
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.Furniture
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.StageCamera
import com.lifemax.rpg.feature.habits.presentation.scene.TurnTuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** A street of [n] sprite buildings laid as the stage lays them, the lot at [mouth] (if any) the side street's 60-unit mouth. */
private fun street(n: Int, mouth: Int = -1): StreetLayout {
    val lefts = ArrayList<Float>()
    val centers = ArrayList<Float>()
    val furniture = ArrayList<Furniture>()
    var left = -BuildingSpec.W / 2f
    for (i in 0 until n) {
        lefts += left; centers += left + BuildingSpec.W / 2f
        val lot = if (i == mouth) 164f - 2f * BuildingSpec.GAP else BuildingSpec.W + BuildingSpec.DX
        left += lot + BuildingSpec.GAP
        if (i < n - 1 && i != mouth && i + 1 != mouth) furniture += Furniture(FurnitureKind.LAMP, left - BuildingSpec.GAP / 2f, i)
    }
    val start = -BuildingSpec.W / 2f - BuildingSpec.GAP
    return StreetLayout(centers, lefts, furniture, start - BuildingSpec.GAP, left + BuildingSpec.GAP)
}

private val MAIN = street(10, mouth = 6)
private val CROSS = street(6)
private val CR = Crossroads(MAIN, 6, CROSS, street(6))

class CrossroadsTest {
    @Test fun theMouthIsTheRealOpeningBetweenTheCornerFronts() {
        // the west corner's front east edge to the east corner's front west edge
        assertEquals(178f, MAIN.lefts[7] - (MAIN.lefts[5] + BuildingSpec.W), .01f)
        assertEquals(CR.westEdge + 89f, CR.mouthMid, .01f)
    }

    @Test fun noTwoBuildingsStandInEachOther() {
        val b = CR.boxes
        for (i in b.indices) for (j in i + 1 until b.size) {
            val overlap = b[i].x0 < b[j].x1 - .01f && b[j].x0 < b[i].x1 - .01f && b[i].z0 < b[j].z1 - .01f && b[j].z0 < b[i].z1 - .01f
            assertFalse("boxes $i and $j overlap", overlap)
        }
    }

    @Test fun everyBuildingIsPlacedOnceAndTheMouthIsNone() {
        assertEquals(9 + 6 + 6, CR.boxes.size)
        assertEquals(CR.boxes.size, CR.boxes.map { it.street to it.index }.toSet().size)
        assertTrue(CR.boxes.none { it.street == 0 && it.index == 6 })
    }

    @Test fun bothRowsOfTheCrossStreetAreFull() {
        assertEquals(CR.boxes.count { it.street == 1 }, CR.boxes.count { it.street == 2 })
    }

    @Test fun noFurnitureStandsInTheOtherStreet() {
        // the main street's lamps stand in its gaps, never in the mouth; the cross street's north of the main street's fronts
        for (f in MAIN.furniture) assertFalse("lamp at ${f.x} in the mouth", CR.mainX(f.x) > 0f && CR.mainX(f.x) < Crossroads.MOUTH)
        for (f in CROSS.furniture) assertTrue(CR.crossZ(f.x) > 0f)
    }
}

class StageCameraTest {
    private val unit = 2.4364f
    private val w = 1560f
    private val ground = 612f
    private val horizon = 552f

    private fun cam(yaw: Float, tx: Float, tz: Float) = StageCamera().apply {
        cx = w / 2f; cy = horizon; this.unit = this@StageCameraTest.unit; eye = (ground - horizon) / this@StageCameraTest.unit
        r = TurnTuning.R; this.yaw = yaw; this.tx = tx; this.tz = tz; update()
    }

    private fun map(m: Matrix, x: Float, y: Float): Pair<Float, Float> {
        val v = m.values
        val z = v[3] * x + v[7] * y + v[15]
        return (v[0] * x + v[4] * y + v[12]) / z to (v[1] * x + v[5] * y + v[13]) / z
    }

    @Test fun atRestTheMainStreetsFrontsAreTheStagesOwnDrawing() {
        // the stage: x = W/2 + (street x - middle) u, y = ground - height u; the camera looks at middle - westEdge
        for (middle in listOf(1200f, 1700f, 1931.18f, 2400f, 2900f)) {
            val c = cam(0f, middle - CR.westEdge, 0f)
            val out = FloatArray(12)
            c.leaned(out, 1f, 9f * unit / Crossroads.DEPTH, -BuildingSpec.DY * unit / Crossroads.DEPTH, 0f, 0f, 0f)
            val m = Matrix()
            for (i in MAIN.centers.indices) {
                val cx = MAIN.centers[i]
                c.plane(out, CR.mainX(cx), 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, m)
                for ((lx, ly) in listOf(-92f to 0f, 92f to -224f, 0f to -100f)) {
                    val (sx, sy) = map(m, lx, ly)
                    assertEquals(w / 2f + (cx + lx - middle) * unit, sx, 1e-3f * 50)
                    assertEquals(ground + ly * unit, sy, 1e-3f * 50)
                }
                // the side face's back edge: the stage's cabinet shift (9 here) and DY up, at the full depth
                c.plane(out, CR.mainX(cx) + 92f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, m)
                val (bx, by) = map(m, Crossroads.DEPTH, 0f)
                assertEquals(w / 2f + (cx + 92f - middle) * unit + 9f * unit, bx, .05f)
                assertEquals(ground - BuildingSpec.DY * unit, by, .05f)
            }
        }
    }

    @Test fun theSideStreetVanishesOnTheStagesHorizon() {
        val c = cam(0f, 89f, 0f)
        val p = FloatArray(3)
        c.project(c.m, 89f, 0f, 1e7f, p)
        assertEquals(horizon, p[1], .5f)
        assertEquals(w / 2f, p[0], .5f)
    }

    @Test fun aPlanesTextureCornersLandOnTheProjectedQuad() {
        val c = cam(.7f, 40f, 180f)
        val m = Matrix()
        c.plane(c.m, -30f, 50f, 300f, 0f, 0f, 1.5f, 0f, -2f, 0f, m)
        val p = FloatArray(3)
        for ((u, v) in listOf(0f to 0f, 120f to 0f, 120f to 80f, 0f to 80f)) {
            val (sx, sy) = map(m, u, v)
            c.project(c.m, -30f, 50f - 2f * v, 300f + 1.5f * u, p)
            assertEquals(p[0], sx, .01f)
            assertEquals(p[1], sy, .01f)
        }
    }

    @Test fun theCrossStreetsOwnViewIsItsStageDrawingToo() {
        // a quarter turn, looking at the cross street's fronts on X = 0 from its own view's middle
        val middle = 124.36f
        val c = cam((Math.PI / 2).toFloat(), 0f, middle + CR.zOff)
        val m = Matrix()
        val out = FloatArray(12)
        c.leaned(out, 0f, 0f, 0f, 1f, 0f, 0f)
        for (i in CROSS.centers.indices) {
            val cx = CROSS.centers[i]
            c.plane(out, 0f, 0f, CR.crossZ(cx), 0f, 0f, 1f, 0f, -1f, 0f, m)
            val (sx, sy) = map(m, 50f, -100f)
            assertEquals(w / 2f + (cx + 50f - middle) * unit, sx, .05f)
            assertEquals(ground - 100f * unit, sy, .05f)
        }
    }
}

class CornerWalkTest {
    @After fun reset() {
        CornerWalk.reset(); CornerWalk.blocked = false; CornerWalk.holdEnd = false
        GameState.moveX = 0f; GameState.moveLane = 0f; GameState.street = 0; GameState.upWalks = false; GameState.vx = 0f
    }

    private fun stand(street: Int, x: Float, lane: Float = 7f) {
        GameState.street = street; GameState.x = x; GameState.lane = lane; GameState.edge = 0; GameState.vx = 0f
    }

    /** Holds the keys for [frames] 60ths of a second; true if the walk started. */
    private fun hold(frames: Int, street: Int): Boolean {
        repeat(frames) { if (CornerWalk.arm(1f / 60f, CR, street)) return true }
        return false
    }

    private fun walkToEnd(maxFrames: Int = 2000): Int {
        repeat(maxFrames) {
            val y = CornerWalk.yaw
            val r = CornerWalk.walk(1f / 60f, CR, kotlin.math.cos(y), kotlin.math.sin(y), -kotlin.math.sin(y), kotlin.math.cos(y))
            if (r != 0) return r
        }
        return 0
    }

    @Test fun upInTheMouthHeldAMomentStartsTheWalk() {
        stand(0, CR.mouthMid + 40f)
        GameState.moveLane = -1f
        assertFalse("a brushed key does not turn", hold(5, 0))
        assertTrue(hold(3, 0))
        assertTrue(CornerWalk.active && CornerWalk.forward)
    }

    @Test fun upOutsideTheMouthOnlySteppsAcross() {
        stand(0, CR.mouthMid + 200f)
        GameState.moveLane = -1f
        assertFalse(hold(30, 0))
    }

    @Test fun nothingTurnsWhileSomethingHoldsThePlayer() {
        stand(0, CR.mouthMid)
        CornerWalk.blocked = true
        GameState.moveLane = -1f
        assertFalse(hold(30, 0))
    }

    @Test fun theWalkEndsOnTheCrossStreetWhereItsViewWillHaveThePlayer() {
        stand(0, CR.mouthMid)
        GameState.moveLane = -1f
        assertTrue(hold(10, 0))
        GameState.moveLane = 0f
        assertEquals(1, walkToEnd())
        assertEquals(1, GameState.street)
        assertEquals(CR.arriveX, GameState.x, .01f)
        assertEquals(CornerWalk.ARRIVE_LANE, GameState.lane, .01f)
        assertFalse(CornerWalk.active)
    }

    @Test fun downWalksItBackToWhereItBegan() {
        stand(0, CR.mouthMid)
        GameState.moveLane = -1f
        assertTrue(hold(10, 0))
        repeat(40) { walkOne() }
        GameState.moveLane = 1f
        assertEquals(-1, walkToEnd())
        assertEquals(0, GameState.street)
        assertEquals(0f, CornerWalk.p, 0f)
    }

    @Test fun walkingOnAgainstTheCrossStreetsStartTurnsBack() {
        stand(1, CR.cross.start + 20f)
        GameState.edge = -1
        GameState.moveX = -1f
        assertTrue(hold(10, 1))
        assertFalse(CornerWalk.forward)
        GameState.moveX = 0f
        assertEquals(1, walkToEnd())
        assertEquals(0, GameState.street)
        assertEquals(CR.returnX, GameState.x, .01f)
    }

    @Test fun aHeldUpKeyWalksOnAlongTheNewStreetUntilLetGo() {
        stand(0, CR.mouthMid)
        GameState.moveLane = -1f
        assertTrue(hold(10, 0))
        assertEquals(1, walkToEnd())
        assertTrue(GameState.upWalks)
        val x0 = GameState.x
        GameState.step(.1f, CR.cross.start, CR.cross.end, 14f)
        assertTrue("walks on along the street", GameState.x > x0)
        assertEquals("not in across the pavement", CornerWalk.ARRIVE_LANE, GameState.lane, .01f)
        // and the same held key does not turn straight back
        assertFalse(hold(30, 1))
        GameState.moveLane = 0f
        GameState.step(.1f, CR.cross.start, CR.cross.end, 14f)
        assertFalse(GameState.upWalks)
    }

    @Test fun thePathIsSmooth() {
        stand(0, CR.mouthMid - 60f)
        GameState.moveLane = -1f
        assertTrue(hold(10, 0))
        var last = CornerWalk.pointAt(0f)
        for (i in 1..400) {
            val p = CornerWalk.pointAt(i / 400f)
            // no corner: the heading turns a little from one step to the next
            val a = Math.atan2(last[3].toDouble(), last[2].toDouble())
            val b = Math.atan2(p[3].toDouble(), p[2].toDouble())
            assertTrue(abs(b - a) < .05)
            assertTrue(hypot(p[0] - last[0], p[1] - last[1]) < 2f)
            last = p
        }
        // it leaves and arrives heading up the cross street
        assertEquals(0f, CornerWalk.pointAt(0f)[2], 1e-4f)
        assertEquals(0f, CornerWalk.pointAt(1f)[2], 1e-4f)
    }

    @Test fun theCameraTurnsAQuarterExactlyAndStartsAndEndsAtRest() {
        stand(0, CR.mouthMid)
        GameState.moveLane = -1f
        assertTrue(hold(10, 0))
        assertEquals(0f, CornerWalk.yaw, 0f)
        CornerWalk.holdEnd = true
        walkToEnd(400)
        assertEquals(1f, CornerWalk.p, 0f)
        assertEquals((Math.PI / 2).toFloat(), CornerWalk.yaw, 0f)
        assertEquals(1f, CornerWalk.ease, 0f)
    }

    private fun walkOne() {
        val y = CornerWalk.yaw
        CornerWalk.walk(1f / 60f, CR, kotlin.math.cos(y), kotlin.math.sin(y), -kotlin.math.sin(y), kotlin.math.cos(y))
    }
}
