import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A restored position never traps the player (the owner's report from 0.1.598: a saved spot in Lincoln High School's
 * fenced field, the fence unseen): on the shipped gamma town's collision, a spot shut in (no way out over clear ground)
 * or inside something goes to the nearest street's pavement, else the spawn; a good spot is kept as it is; and solids
 * built round a player just placed (a landmark, a store or the farms coming up as the town loads) are looked at again.
 */
class SafeSpawnTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val root = JSONObject(File(dir, "street.json").readText())
    private val town = Town.parse(root)!!
    private val map = TownCollision.build(town, TownYards.parse(root), ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
        CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet())
    private val walk = OpenWalk(town, map, TownYards.parse(root))
    private val R = OpenWalk.RADIUS

    /** Open ground in the base map (Lincoln High School's field before its model's solids are in: the owner's spot). */
    private val OX = 7546.27f
    private val OZ = 7177.14f

    @Before fun reset() { GameState.motion.vx = 0f; GameState.motion.vy = 0f; GameState.moveX = 0f; GameState.moveLane = 0f }

    /** A closed square fence of half size [h] round ([cx], [cz]) (four thin boxes, as setExtra takes them). */
    private fun ring(cx: Float, cz: Float, h: Float) = floatArrayOf(
        cx, cz - h, 1f, 0f, h + 2f, 1.5f,
        cx, cz + h, 1f, 0f, h + 2f, 1.5f,
        cx - h, cz, 1f, 0f, 1.5f, h + 2f,
        cx + h, cz, 1f, 0f, 1.5f, h + 2f,
    )

    /** How far ([x], [z]) is from the line of its nearest row (the pavement is within a few lanes of it). */
    private fun offStreet(x: Float, z: Float): Float {
        val (r, along) = town.nearestRow(x, z)!!
        val p = r.pointAt(along, 0f)
        return hypot(p.x - x, p.z - z)
    }

    @Test fun aGoodSpotIsKept() {
        val r = town.row(town.spawn.row)
        val p = r.pointAt(town.spawn.x, town.spawn.lane)
        walk.placeAt(p.x, p.z)
        assertTrue(walk.standable(walk.wx, walk.wz))
        val x = walk.wx; val z = walk.wz
        assertNull(walk.rescue())
        assertEquals(x, walk.wx, 0f); assertEquals(z, walk.wz, 0f)
        // open ground off the streets is fine too (the player may stand anywhere they can walk away from)
        walk.placeAt(OX, OZ)
        assertNull(walk.rescue())
        assertEquals(OX, walk.wx, 0f); assertEquals(OZ, walk.wz, 0f)
    }

    @Test fun aSpotShutInGoesToTheNearestPavement() {
        try {
            map.setExtra("test:pen", ring(OX, OZ, 120f), SolidKind.FENCE, "test")
            walk.placeAt(OX, OZ)
            assertTrue("the pen shuts the player in", map.boxedIn(walk.wx, walk.wz, R))
            val what = walk.rescue()
            assertNotNull(what)
            println("rescue: $what")
            assertTrue(walk.standable(walk.wx, walk.wz))
            assertFalse(map.blocked(walk.wx, walk.wz, R))
            // out of the pen, on the pavement of the nearest street (Juniper Way, 129R, ~880 units south)
            assertTrue(hypot(walk.wx - OX, walk.wz - OZ) > 120f)
            assertTrue("on the pavement: ${offStreet(walk.wx, walk.wz)} off its row", offStreet(walk.wx, walk.wz) < 40f)
        } finally { map.setExtra("test:pen", null) }
    }

    @Test fun aSpotInsideABuildingIsNeverKept() {
        // the middle of a building's solid shape: a save from before the town was replanned may land in one
        val i = (0 until map.size).first { map.kind(it) == SolidKind.BUILDING && !map.isCircle(it) && map.shape(it, FloatArray(6))[4] > 60f }
        val s = map.shape(i, FloatArray(6))
        walk.placeAt(s[0], s[1])
        // (placing never leaves the player inside: the clear spot nearest)
        assertFalse(map.blocked(walk.wx, walk.wz, R))
        walk.rescue()
        assertTrue(walk.standable(walk.wx, walk.wz))
    }

    @Test fun withNoGoodPavementNearTheSpawnIsTaken() {
        val (r, along) = town.nearestRow(OX, OZ)!!
        val pens = ArrayList<Float>()
        pens.addAll(ring(OX, OZ, 60f).toList())
        for (off in OpenWalk.PAVEMENT_TRIES) {
            val q = r.pointAt((along + off).coerceIn(0f, r.length), OpenWalk.PAVEMENT_LANE)
            pens.addAll(ring(q.x, q.z, 40f).toList())
        }
        try {
            map.setExtra("test:pens", pens.toFloatArray(), SolidKind.FENCE, "test")
            walk.placeAt(OX, OZ)
            assertNotNull(walk.rescue())
            val sr = town.row(town.spawn.row)
            val sp = sr.pointAt(town.spawn.x, town.spawn.lane)
            assertTrue("at the spawn", hypot(walk.wx - sp.x, walk.wz - sp.z) < 20f)
            assertTrue(walk.standable(walk.wx, walk.wz))
        } finally { map.setExtra("test:pens", null) }
    }

    @Test fun solidsBuiltRoundAPlacedPlayerAreLookedAtAgain() {
        try {
            walk.placeAt(OX, OZ)
            assertNull(walk.rescue())
            // a landmark's fence comes up round the restored spot as the town loads: the next frame moves the player out
            map.setExtra("test:late", ring(OX, OZ, 90f), SolidKind.FENCE, "test")
            walk.step(1f / 60f, 0f, 0f)
            assertNotNull(walk.rescued)
            assertTrue(walk.standable(walk.wx, walk.wz))
            assertTrue(offStreet(walk.wx, walk.wz) < 40f)
            // something built right where the player stands (after they walked on): out to the nearest clear spot
            val x = walk.wx; val z = walk.wz
            map.setExtra("test:late", floatArrayOf(x, z, 1f, 0f, 12f, 12f), SolidKind.BUILDING, "test")
            walk.step(1f / 60f, 0f, 0f)
            assertFalse(map.blocked(walk.wx, walk.wz, R))
            assertTrue(abs(walk.wx - x) + abs(walk.wz - z) < 60f)
        } finally { map.setExtra("test:late", null) }
    }

    @Test fun theBoxedInCheckIsCheapInTheOpen() {
        val t0 = System.nanoTime()
        repeat(20) { assertFalse(map.boxedIn(OX, OZ, R)) }
        val ms = (System.nanoTime() - t0) / 1e6 / 20
        println("boxedIn in the open: %.2f ms".format(ms))
        assertTrue("boxedIn took $ms ms", ms < 50.0)
    }
}
