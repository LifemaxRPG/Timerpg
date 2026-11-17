package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PregenQueueTest {
    /** Main Street has 8 buildings (crossroads at its far end), the cross street 6 (crossroads at its start). */
    private fun queue(): PregenQueue {
        val q = PregenQueue(intArrayOf(8, 6), near = 2)
        var order = 0
        for ((s, n) in listOf(0 to 8, 1 to 6)) for (i in 0 until n) q.add(PregenQueue.Job("$s:$i", listOf(PregenQueue.Spot(s, i)), order++))
        return q
    }

    private fun PregenQueue.drain(): List<String> = generateSequence { poll()?.key }.toList()

    @Test
    fun nearestBuildingsFirstThenTheStreetThenTheOtherStreetFromItsCrossroads() {
        val q = queue()
        q.setPlayer(0, 4)
        val expected = listOf("0:4", "0:3", "0:5", "0:2", "0:6", "0:1", "0:7", "0:0", "1:0", "1:1", "1:2", "1:3", "1:4", "1:5")
        assertEquals(expected, q.ordered().map { it.key })
        assertEquals(expected, q.drain())
        assertNull(q.poll())
    }

    @Test
    fun reprioritisedWhenThePlayerTurnsTheCorner() {
        val q = queue()
        q.setPlayer(0, 0)
        assertEquals("0:0", q.ordered().first().key)
        // round the corner: the start of the cross street; the main street now counts from its crossroads (far) end
        assertTrue(q.setPlayer(1, -1))
        assertEquals(1, q.street)
        assertEquals(0, q.position)
        assertEquals(listOf("1:0", "1:1", "1:2", "1:3", "1:4", "1:5", "0:7", "0:6", "0:5", "0:4", "0:3", "0:2", "0:1", "0:0"), q.drain())
    }

    @Test
    fun betweenBuildingsKeepsTheLastPlace() {
        val q = queue()
        q.setPlayer(0, 5)
        assertFalse(q.setPlayer(0, -1))
        assertEquals(5, q.position)
        assertFalse(q.setPlayer(0, 5))
        assertTrue(q.setPlayer(0, 6))
    }

    @Test
    fun aLineSaidAtTwoBuildingsRanksByTheNearer() {
        val q = PregenQueue(intArrayOf(8, 6))
        q.add(PregenQueue.Job("far", listOf(PregenQueue.Spot(0, 0)), 0))
        // the same person owns a shop on Main Street and heads a home on the cross street
        q.add(PregenQueue.Job("both", listOf(PregenQueue.Spot(0, 7), PregenQueue.Spot(1, 3)), 1))
        q.setPlayer(1, 3)
        assertEquals(listOf("both", "far"), q.ordered().map { it.key })
        q.setPlayer(0, 0)
        assertEquals(listOf("far", "both"), q.ordered().map { it.key })
    }

    @Test
    fun removeAndDuplicates() {
        val q = queue()
        q.setPlayer(0, 0)
        assertTrue(q.remove("0:0"))
        assertFalse(q.remove("0:0"))
        q.add(PregenQueue.Job("0:1", listOf(PregenQueue.Spot(0, 1)), 99))
        assertEquals(13, q.size)
        assertEquals("0:1", q.poll()?.key)
        assertFalse("0:1" in q)
    }
}
