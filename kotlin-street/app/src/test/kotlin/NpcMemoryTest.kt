package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcMemoryTest {
    private fun unit(vararg x: Float): FloatArray { val n = kotlin.math.sqrt(x.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(x.size) { x[it] / n } }

    @Test
    fun recallsWhatBearsOnTheWordsAndTheLastExchange() {
        val m = NpcMemory()
        m.add(NpcMemory.Entry("ann", 1000, "I lost my dog", "I'll look out", unit(1f, 0f, 0f)))
        m.add(NpcMemory.Entry("ann", 2000, "My sister marries", "Congratulations", unit(0f, 1f, 0f)))
        m.add(NpcMemory.Entry("bob", 3000, "Hello", "Hi", unit(1f, 0f, 0f)))
        // only Ann's, the dog first; the last exchange (the sister) comes along as it is recent
        val r = m.recall("ann", unit(1f, .1f, 0f), now = 2000 + 60_000)
        assertEquals(listOf("I lost my dog", "My sister marries").sorted(), r.map { it.said }.sorted())
        // long after, only what bears on it
        assertEquals(listOf("I lost my dog"), m.recall("ann", unit(1f, .1f, 0f), now = 10_000_000).map { it.said })
    }

    @Test
    fun savesAndLoads() {
        val m = NpcMemory()
        m.add(NpcMemory.Entry("ann", 5, "a \"quoted\" word", "fine", unit(.3f, -.4f, .5f)))
        val n = NpcMemory().apply { load(m.toJson()) }
        val e = n.recall("ann", unit(.3f, -.4f, .5f), now = 6, recentMs = 0).single()
        assertEquals("a \"quoted\" word", e.said)
        assertTrue(kotlin.math.abs(NpcMemory.dot(e.vec, unit(.3f, -.4f, .5f)) - 1f) < 1e-5f)
    }

    @Test
    fun memoriesGoIntoTheFactSheet() {
        val p = "<|im_start|>system\nRULES\nFACTS\nYou are Ann.\nExample of the kind of answer: Hi.<|im_end|>\n<|im_start|>user\nHey<|im_end|>\n"
        val q = DialogueEngine.withMemories(p, listOf("Earlier the player told you: \"x\" You answered: \"y\""))
        assertTrue(q.contains("You are Ann.\nEarlier the player told you: \"x\" You answered: \"y\"\nExample of the kind of answer: Hi."))
    }
}
