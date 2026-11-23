package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.CloudKind
import com.lifemax.rpg.feature.habits.domain.TreeKind
import com.lifemax.rpg.feature.habits.domain.Trees
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky's clouds and the street's trees are pure layouts: the same for the same seed, each cloud
 * in a lane of its own, every tree inside the room it is given, and the page header's band is left
 * to the app's surface.
 */
class SkyAndTreesTest {
    private val seed = HabitsScenarios.TODAY.hashCode()

    @Test
    fun cloudLayoutsAreDeterministic() {
        val a = Ambient.clouds(seed)
        val b = Ambient.clouds(seed)
        assertEquals(Ambient.CLOUDS, a.size)
        for (i in a.indices) {
            assertEquals(a[i].kind, b[i].kind)
            assertEquals(a[i].x0, b[i].x0, 0f)
            assertEquals(a[i].scale, b[i].scale, 0f)
            assertEquals(a[i].variant, b[i].variant)
            assertEquals(a[i].haze, b[i].haze, 0f)
            assertEquals(a[i].x0 - .25f, Ambient.cloudAt(a[i], 0f), 1.5f)
        }
        // another day, another sky
        val other = Ambient.clouds(seed + 1)
        assertNotEquals(a.map { it.x0 }, other.map { it.x0 })
    }

    @Test
    fun everyCloudHasALaneOfItsOwnClearOfTheTowers() {
        for (s in listOf(seed, 1, 99, -7)) {
            val clouds = Ambient.clouds(s)
            assertEquals(clouds.size, clouds.map { it.lane }.toSet().size)
            // the lowest lane is well above the far city (the horizon is at 1.0 of the band)
            assertTrue(clouds.all { it.y <= .7f })
            for (i in clouds.indices) for (j in i + 1 until clouds.size) assertTrue(
                abs(clouds[i].y - clouds[j].y) >= .1f
            )
            // the shapes vary: a heap, a streak, small puffs
            assertTrue(clouds.map { it.kind }.toSet().size == CloudKind.entries.size)
            // the far ones (the low lanes) are hazier and smaller than the near
            val byLane = clouds.sortedBy { it.lane }
            assertTrue(byLane.zipWithNext().all { (p, q) -> p.haze <= q.haze })
            assertTrue(clouds.all { it.variant in 0 until Ambient.CLOUD_VARIANTS })
        }
    }

    @Test
    fun theNearAndBigDriftFasterAndAllComeRound() {
        val clouds = Ambient.clouds(seed)
        val sorted = clouds.sortedBy { it.depth }
        assertTrue(sorted.zipWithNext().all { (p, q) -> p.speed <= q.speed })
        assertTrue(clouds.all { it.speed > 0f })
        for (c in clouds) for (t in 0 until 4000 step 13) assertTrue(
            Ambient.cloudAt(c, t.toFloat()) in -.26f..1.26f
        )
    }

    @Test
    fun treeVariantsComeFromTheSeed() {
        for (s in -50..200) {
            assertEquals(Trees.variant(s), Trees.variant(s))
            assertTrue(Trees.variant(s) in 0 until Trees.VARIANTS)
        }
        val kinds = (0 until 300).map { Trees.shape(it).kind }
        assertTrue(kinds.toSet().size == TreeKind.entries.size)
        // round trees are the common ones
        assertTrue(kinds.count { it == TreeKind.ROUND } > kinds.count { it == TreeKind.PINE })
        // the same seed is the same tree, another seed (often) another
        assertTrue((0 until 40).map { Trees.variant(it) }.toSet().size > 3)
    }

    @Test
    fun everyTreeStaysInTheRoomItIsGiven() {
        for (shape in Trees.shapes) {
            assertTrue(shape.halfWidth <= 22f)
            assertTrue(shape.clumps.size >= 16 && shape.clumps.size % 4 == 0)
            when (shape.kind) {
                TreeKind.SHRUB -> assertTrue(shape.height in 18f..30f)
                TreeKind.PINE -> assertTrue(shape.height in 55f..68f)
                else -> assertTrue(shape.height in 46f..68f)
            }
            assertTrue(shape.crown < -5f)
            // a park tree (drawn at .8) clears the building beside it, a street tree the lots
            assertTrue(.8f * shape.halfWidth <= 18f)
            assertTrue(shape.halfWidth <= B.GAP / 2f)
        }
    }
}
