import com.lifemax.rpg.feature.habits.domain.LevelField
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.WalkHeight
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Walkable heights (domain/WalkLevels.kt): a landmark's steps and terraces as a [LevelField] in the shipped gamma town's
 * collision, walked by the open world's [OpenWalk]: a step up to [WalkHeight.RISE] is climbed, a taller face blocks,
 * walking back goes down the steps, walking off a terrace's edge falls (over frames, not at once) to the ground; and the
 * field made from triangles has its tops, walls and the room under an arch.
 */
class WalkLevelsTest {
    companion object {
        lateinit var map: TownCollision
        /** An open spot in the town (nothing solid within [CLEAR]), where the test's monument stands. */
        var ox = 0f; var oz = 0f
        const val CLEAR = 330f

        @BeforeClass @JvmStatic fun setUp() {
            val root = org.json.JSONObject(java.io.File("../app/src/main/assets/town/gamma/street.json").readText())
            map = TownCollision.build(OpenWalkTest.town, OpenWalkTest.yards, OpenWalkTest.models, OpenWalkTest.cinema, StoreLots.parse(root).map { it.sprite }.toSet())
            var found = false
            var z = map.minZ + 1500f
            while (!found && z < map.maxZ - 1500f) {
                var x = map.minX + 1500f
                while (x < map.maxX - 1500f) { if (map.clearance(x, z, CLEAR + 10f) >= CLEAR) { ox = x; oz = z; found = true; break }; x += 100f }
                z += 100f
            }
            assertTrue("an open spot for the test's monument", found)
            // a monument in its own frame (x along, z across): two broad steps (8 and 16 up), then a wall 60 up; a pillar
            // standing alone on the ground in front of it
            val b = LevelField.Builder()
            b.addBox(20f, -60f, 200f, 60f, 0f, 8f)
            b.addBox(40f, -60f, 200f, 60f, 8f, 16f)
            b.addBox(90f, -60f, 200f, 60f, 16f, 76f)
            b.addBox(-60f, 80f, -40f, 100f, 0f, 80f)
            map.setLevels("test:monument", b.build(floatArrayOf(ox, oz, 1f, 0f, 0f, 1f)))
        }
    }

    private fun walker(lx: Float, lz: Float): OpenWalk {
        GameState.motion.vx = 0f; GameState.motion.vy = 0f
        val w = OpenWalk(OpenWalkTest.town, map)
        w.cameraYaw = 0f
        w.placeAt(ox + lx, oz + lz, 1f, 0f)
        return w
    }

    /** [frames] frames at 60 Hz pushing the stick ([mx] right = +x, [my] up = -z with the camera looking north). */
    private fun OpenWalk.walk(frames: Int, mx: Float, my: Float, each: (OpenWalk) -> Unit = {}) { repeat(frames) { step(1f / 60f, mx, my); each(this) } }

    @Test fun climbsStepsAndStopsAtATallerFace() {
        val w = walker(-20f, 0f)
        assertEquals(0f, w.wy, .01f)
        var highest = 0f
        w.walk(240, 1f, 0f) { highest = maxOf(highest, it.wy) }
        assertEquals("up both steps", 16f, w.wy, .01f)
        assertEquals("never higher than the top step", 16f, highest, .01f)
        // (blocked by the wall 60 up: its face at x 90 less the walker's half width)
        val lx = w.wx - ox
        assertTrue("stopped at the wall, at $lx", lx < 90f - OpenWalk.RADIUS * .8f && lx > 70f)
        // the figure followed: eased, not jumped
        assertEquals(16f, w.shownY, .5f)
    }

    @Test fun walksBackDownTheSteps() {
        val w = walker(60f, 0f)
        assertEquals("placed on the top step", 16f, w.wy, .01f)
        val seen = HashSet<Float>()
        w.walk(240, -1f, 0f) { seen += it.wy }
        assertEquals("down on the ground", 0f, w.wy, .01f)
        assertTrue("by the middle step: $seen", 8f in seen)
        assertTrue("walked off the steps", w.wx - ox < 20f)
    }

    @Test fun fallsOffAnEdgeOverFrames() {
        // on the top step (16 up) walking out over its side edge (z -60): 16 is more than a step down
        val w = walker(60f, -40f)
        assertEquals(16f, w.wy, .01f)
        val heights = ArrayList<Float>()
        w.walk(120, 0f, -1f) { heights += it.wy }
        assertEquals("on the ground at last", 0f, w.wy, .01f)
        val between = heights.count { it > .5f && it < 15.5f }
        assertTrue("fell over several frames, not at once: $between frames in the air", between >= 3)
        assertTrue("walked on past the edge", w.wz - oz < -60f)
    }

    @Test fun aWallFromTheGroundBlocks() {
        // the pillar (80 tall) stands at x -60..-40, z 80..100: walking +z into it from z 40
        val w = walker(-50f, 40f)
        w.walk(180, 0f, 1f)
        assertTrue("stopped before the pillar at ${w.wz - oz}", w.wz - oz < 80f && w.wz - oz > 60f)
        assertEquals(0f, w.wy, .01f)
        // from the side it slides along, never inside
        assertFalse(map.levelBlocked(w.wx, w.wz, w.wy, OpenWalk.RADIUS))
    }

    @Test fun theFieldFromTrianglesHasTopsWallsAndRoomUnderAnArch() {
        val b = LevelField.Builder()
        // a closed box 0..10 up over x 0..20, z 0..20 (two triangles a face)
        fun quad(p: List<FloatArray>) {
            val pos = FloatArray(12); for (i in 0..3) { pos[i * 3] = p[i][0]; pos[i * 3 + 1] = p[i][1]; pos[i * 3 + 2] = p[i][2] }
            b.addMesh(pos, intArrayOf(0, 1, 2, 0, 2, 3))
        }
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            quad(listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0))) // top, up
            quad(listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1))) // bottom, down
            quad(listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0))) // sides
            quad(listOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)))
            quad(listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0)))
            quad(listOf(floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1)))
        }
        box(0f, 0f, 0f, 20f, 10f, 20f)
        // an arch's lintel: a block 60..80 up over x 40..60 (room under it)
        box(40f, 60f, 0f, 60f, 80f, 20f)
        // a plaza's ground plane (up only), 0.3 up, round them
        quad(listOf(floatArrayOf(-40f, .3f, -40f), floatArrayOf(-40f, .3f, 60f), floatArrayOf(100f, .3f, 60f), floatArrayOf(100f, .3f, -40f)))
        val f = b.build(floatArrayOf(1000f, 2000f, 1f, 0f, 0f, 1f))!!
        assertEquals("the box's top", 10f, f.top(1010f, 2010f, 20f), .01f)
        // (its walls are what stop a walker: the ground plane through it leaves its inside hollow, which nobody reaches)
        assertTrue("its side walls are solid at knee height", f.hits(1000.2f, 2010f, 2f, 8f) && f.hits(1019.8f, 2010f, 2f, 8f))
        assertEquals("the plaza beside it (heights kept to a quarter unit)", .3f, f.top(1030f, 2010f, 20f), .13f)
        assertFalse("nothing at head height under the lintel", f.hits(1050f, 2010f, 1f, 40f))
        assertTrue("the lintel itself", f.hits(1050f, 2010f, 61f, 79f))
        assertEquals("the lintel's underside over a walker under it (the ceiling a cutaway goes by)", 60f, f.bottomAbove(1050f, 2010f, 34f), .13f)
        assertTrue("no ceiling over the open plaza", f.bottomAbove(1030f, 2010f, 34f).isNaN())
        assertEquals("the ground under the arch is what a walker stands on", .3f, f.top(1050f, 2010f, 12f), .13f)
        assertTrue("outside the field: nothing", f.top(900f, 2010f, 100f).isNaN())
    }
}
