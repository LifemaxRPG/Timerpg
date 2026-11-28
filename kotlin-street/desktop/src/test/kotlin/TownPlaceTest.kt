import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Where the Filament town stands each building ([TownPlace]): the same rigid box the walk blocks ([TownWalk.footprints])
 * and the yards keep clear (graph.yards' `house`), its frame a proper rotation (no mirrored art), its front to the street.
 */
class TownPlaceTest {
    private val raw = TownPlaceTest::class.java.getResource("/town-gamma.json")!!.readText()
    private val town = Town.parse(raw)!!

    @Test fun everyBuildingStandsOnTheWalksFootprint() {
        val walk = TownWalk(town)
        var n = 0
        for (row in town.rows) {
            val fps = walk.footprints(row)
            for ((b, fp) in row.buildings.zip(fps)) {
                val p = TownPlace.of(row, b)
                val h = b.width / 2f
                val mine = listOf(p.world(-h, b.setback), p.world(h, b.setback), p.world(h, b.setback + b.depth), p.world(-h, b.setback + b.depth))
                // the same four corners (in some order)
                for (c in mine) assertTrue("${b.sprite} on ${row.id}: corner $c not on the walk's footprint ${fp.corners}",
                    fp.corners.any { hypot(it.x - c.x, it.z - c.z) < .01f })
                n++
            }
        }
        assertEquals(322, n)
    }

    @Test fun theFrameIsARotationFacingTheStreet() {
        val m = FloatArray(16)
        for (row in town.rows) for (b in row.buildings) {
            val p = TownPlace.of(row, b)
            p.matrix(m)
            // det of the upper 3x3 (column-major) is +1: the art is not mirrored
            val det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
            assertEquals("${b.sprite}", 1f, det, 1e-4f)
            // local +z (into the lot) is the row's normal; local -z faces the road: the facing row's line is that way
            val into = p.world(0f, 1f)
            val n = row.normalAt(b.mid)
            assertEquals(n.x, into.x - p.ox, 1e-4f); assertEquals(n.z, into.z - p.oz, 1e-4f)
            // the art's left-to-right seen from the road is local -x: the viewer's right looking along the normal
            val right = p.world(-1f, 0f)
            assertEquals(-n.z, right.x - p.ox, 1e-4f); assertEquals(n.x, right.z - p.oz, 1e-4f)
        }
    }

    @Test fun theYardsHouseIsTheSameBox() {
        // the whole town's export (tools/build-town.sh gamma, or the shipped app asset); the fixture carries no yards
        val f = listOf("../build-town/gamma/street.json", "build-town/gamma/street.json", "../app/src/main/assets/town/gamma/street.json")
            .map(::File).firstOrNull { it.exists() }
        assumeTrue("no gamma street.json with yards (run tools/build-town.sh gamma)", f != null)
        val file = f!!
        val json = file.readText()
        val t = Town.parse(json)!!
        val yards = TownYards.parse(json)
        assertTrue(yards.yards.isNotEmpty())
        var n = 0
        for (row in t.rows) for (b in row.buildings) {
            val y = yards.of(b.sprite) ?: continue
            val p = TownPlace.of(row, b)
            val h = b.width / 2f
            val cs = listOf(p.world(-h, b.setback), p.world(h, b.setback + b.depth))
            val x0 = minOf(cs[0].x, cs[1].x); val x1 = maxOf(cs[0].x, cs[1].x); val z0 = minOf(cs[0].z, cs[1].z); val z1 = maxOf(cs[0].z, cs[1].z)
            val r = y.house
            assertEquals("${b.sprite} x0", r[0], x0, .05f); assertEquals("${b.sprite} z0", r[1], z0, .05f)
            assertEquals("${b.sprite} x1", r[2], x1, .05f); assertEquals("${b.sprite} z1", r[3], z1, .05f)
            n++
        }
        assertEquals(yards.yards.size, n)
    }
}
