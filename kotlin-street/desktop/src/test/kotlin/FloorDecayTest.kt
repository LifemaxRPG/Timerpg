package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.presentation.scene.DecayInk
import com.lifemax.rpg.feature.habits.presentation.scene.DecayPlan
import com.lifemax.rpg.feature.habits.presentation.scene.FacadeSprites
import com.lifemax.rpg.feature.habits.presentation.scene.FloorDecay
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A missed floor's decay ([FloorDecay]): none on a floor never missed, the same every time for a
 * floor, worse the more days it was missed, inside its front, its cracks on the wall and not over a
 * window, along the joints on brick; and a mend after a come-back.
 */
class FloorDecayTest {
    private val heights = BuildingSpec.HEIGHTS.toList()
    private val walls = listOf(HabitMaterial.CLAY, HabitMaterial.STONE, HabitMaterial.GOLD)
    private val keys = List(60) { "h$it/0" }

    private fun plan(m: HabitMaterial, key: String, miss: Int, scar: Boolean, h: Float) =
        FloorDecay.plan(m, key, miss, scar, h, floorWindows(h))

    private fun DecayPlan.has(ink: DecayInk) = of(ink).isNotEmpty()

    @Test
    fun aFloorNeverMissedHasNone() {
        for (m in HabitMaterial.entries) for (h in heights) assertNull(plan(m, "a/0", 0, false, h))
        assertEquals(0, FloorDecay.stage(0))
        assertEquals(1, FloorDecay.stage(1))
        assertEquals(2, FloorDecay.stage(2))
        assertEquals(3, FloorDecay.stage(3))
        assertEquals(3, FloorDecay.stage(30))
    }

    @Test
    fun aFloorsDecayIsTheSameEveryTime() {
        for (m in HabitMaterial.entries) for (miss in 0..3) {
            val a = plan(m, "s1h4/0", miss, true, 60f)!!
            val b = plan(m, "s1h4/0", miss, true, 60f)!!
            for (ink in DecayInk.entries) {
                assertEquals(a.of(ink).size, b.of(ink).size)
                for (i in a.of(ink).indices) assertTrue(a.of(ink)[i].contentEquals(b.of(ink)[i]))
            }
            assertEquals(a.stains.size, b.stains.size)
        }
        // and not the same on every floor
        val starts =
            keys.map { plan(HabitMaterial.CLAY, it, 1, false, 60f)!!.of(DecayInk.CRACK)[0][0] }
        assertTrue(starts.toSet().size >= 4)
    }

    @Test
    fun itGetsWorseTheMoreDaysAreMissed() {
        for (m in walls) for (h in heights) for (key in keys) {
            val one = plan(m, key, 1, false, h)!!
            val two = plan(m, key, 2, false, h)!!
            val three = plan(m, key, 5, false, h)!!
            // once: a hairline and a cracked pane, nothing come away, nothing taped or boarded
            assertTrue(one.has(DecayInk.CRACK) && one.has(DecayInk.PANE_CRACK))
            for (ink in
                listOf(DecayInk.SPALL, DecayInk.TAPE, DecayInk.BOARD, DecayInk.RUBBLE)) assertTrue(
                "$m $h $key once $ink",
                !one.has(ink),
            )
            // twice: the crack opened, bits gone, a pane broken and taped
            assertTrue(two.has(DecayInk.CRACK_WIDE) && two.has(DecayInk.SPALL))
            assertTrue(two.has(DecayInk.HOLE) && two.has(DecayInk.TAPE) && !two.has(DecayInk.BOARD))
            // three times or more: boarded with a notice, rubble at its foot
            assertTrue(three.has(DecayInk.BOARD) && three.has(DecayInk.NOTICE))
            assertTrue(three.has(DecayInk.RUBBLE) && three.has(DecayInk.TAPE))
            assertTrue(
                three.of(DecayInk.SPALL).size >= two.of(DecayInk.SPALL).size &&
                    three.of(DecayInk.CRACK_WIDE).size > two.of(DecayInk.CRACK_WIDE).size - 1
            )
        }
        for (h in heights) for (key in keys) {
            val g1 = plan(HabitMaterial.GLASS, key, 1, false, h)!!
            val g3 = plan(HabitMaterial.GLASS, key, 3, false, h)!!
            assertTrue(g1.has(DecayInk.PANE_CRACK) && !g1.has(DecayInk.TAPE))
            assertTrue(g3.has(DecayInk.BOARD) && g3.has(DecayInk.SHARD))
        }
    }

    @Test
    fun aComeBackLeavesAMendNotACrack() {
        for (m in HabitMaterial.entries) for (key in keys.take(10)) {
            val p = plan(m, key, 0, true, 60f)
            assertNotNull(p)
            p!!
            assertTrue(p.mended)
            assertTrue(p.has(DecayInk.MEND) || p.has(DecayInk.BEAD))
            for (ink in
                listOf(
                    DecayInk.CRACK,
                    DecayInk.CRACK_WIDE,
                    DecayInk.TAPE,
                    DecayInk.BOARD,
                )) assertTrue(!p.has(ink))
            assertTrue(p.stains.isEmpty())
        }
    }

    @Test
    fun itStaysInsideTheFloorAndItsCracksOffTheWindows() {
        val half = BuildingSpec.W / 2f
        for (m in HabitMaterial.entries) for (h in heights) for (key in keys) for (miss in 0..3) {
            val p = plan(m, key, miss, true, h)!!
            val spots = floorWindows(h)
            for (ink in DecayInk.entries) for (pts in p.of(ink)) for (i in 0 until pts.size / 2) {
                val x = pts[i * 2]
                val y = pts[i * 2 + 1]
                assertTrue("$m $ink x $x", x >= -half && x <= half)
                assertTrue("$m $ink y $y", y >= -1f && y <= h)
                if (ink == DecayInk.CRACK || ink == DecayInk.CRACK_WIDE || ink == DecayInk.MEND)
                    for (s in spots) assertTrue(
                        "$m $key $ink on a window",
                        x <= s.x - .5f || x >= s.x + s.w + .5f || y <= s.y - .5f || y >= s.y + s.h,
                    )
            }
            // it starts on the right of the front, clear of the name plaque on the left
            val first =
                p.of(DecayInk.CRACK_WIDE).firstOrNull() ?: p.of(DecayInk.CRACK).firstOrNull()
            if (first != null) assertTrue("$m $key starts at ${first[0]}", first[0] > 0f)
        }
    }

    @Test
    fun theDecayPictureHasEachStage() {
        val blocks = HabitsScenarios.projection("decay").stacks[0].blocks
        assertEquals(
            listOf(
                Triple(HabitMaterial.CLAY, 0, true),
                Triple(HabitMaterial.CLAY, 1, false),
                Triple(HabitMaterial.STONE, 2, false),
                Triple(HabitMaterial.CLAY, 4, false),
                Triple(HabitMaterial.GLASS, 3, false),
            ),
            blocks.map { Triple(it.material, it.miss, it.scar) },
        )
    }

    @Test
    fun brickCracksRunInTheJoints() {
        val c = FacadeSprites.COURSE
        val half = BuildingSpec.W / 2f
        for (h in heights) for (key in keys) {
            val p = plan(HabitMaterial.CLAY, key, 1, false, h)!!
            val crack = p.of(DecayInk.CRACK)[0]
            for (i in 1 until crack.size / 2) {
                val u = crack[i * 2] + half
                val v = crack[i * 2 + 1]
                val bed = abs((v - .5f) - floor((v - .5f) / c + .5f) * c)
                val head = abs((u - .5f) - floor((u - .5f) / c + .5f) * c)
                assertTrue("$key point $i ($u, $v)", bed < .6f || head < .6f)
            }
        }
    }
}
