package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's conversation log: kinds of question, themes, matching to ready lines, the writer's order, privacy. */
class ThemesTest {
    @Test
    fun kindsOfQuestion() {
        assertEquals("the past", Themes.kindOf("What was this town like in the 60s?"))
        assertEquals("the past", Themes.kindOf("What was it like back then?"))
        assertEquals("directions", Themes.kindOf("Where is the bakery?"))
        assertEquals("opinion", Themes.kindOf("What do you think of the mayor?"))
        assertEquals("prices", Themes.kindOf("How much does bread cost here?"))
        assertEquals("family", Themes.kindOf("Do you have kids?"))
        assertEquals("gossip", Themes.kindOf("Heard any rumors lately?"))
        assertEquals("time-travel", Themes.kindOf("I'm from the future, you know."))
        assertEquals("flirting", Themes.kindOf("Would you go on a date with me?"))
        assertEquals("greeting", Themes.kindOf("Hello!"))
        assertEquals("other", Themes.kindOf("Purple elephants."))
    }

    private fun said(npc: String, t: Long, text: String, live: Boolean = false, vec: FloatArray? = null) = Themes.Said(npc, t, text, vec, live)

    @Test
    fun clustersByWordsWithoutTheMemoryModel() {
        val log = listOf(
            said("a", 1, "What do you think of the mayor?", live = true),
            said("b", 2, "What do you think about the mayor?", live = true),
            said("c", 3, "what do you think of the mayor"),
            said("a", 4, "What do you think of the baker?"),
            said("d", 5, "What was this town like in the 60s?", live = true),
            said("e", 6, "What was the town like in the 60s?"),
            said("e", 7, "Hello there"),
        )
        val th = Themes.cluster(log)
        assertEquals(3, th.size)   // the mayor, the 60s, the baker (the greeting is left out)
        val mayor = th[0]
        assertEquals("opinion", mayor.kind)
        assertEquals(3, mayor.count)
        assertEquals("What do you think of the mayor?", mayor.exemplar)   // the first phrasing: stable night to night
        assertEquals(3 + 2 * (Themes.LIVE_WEIGHT - 1), mayor.weight)
        assertEquals(setOf("a", "b", "c"), mayor.npcs)
        assertTrue(mayor.label.contains("mayor"))
        assertEquals("the past", th[1].kind)
        assertEquals(1, th.first { it.label.contains("baker") }.count)
    }

    @Test
    fun clustersByVectorsButNotAcrossSubjects() {
        fun v(vararg x: Float) = x
        val log = listOf(
            said("a", 1, "What do you think of the mayor?", vec = v(1f, 0f, 0f)),
            said("b", 2, "What do you think about our mayor?", vec = v(.95f, .1f, 0f)),
            // close by its vector (the model hears another opinion) but about someone else: its own theme
            said("c", 3, "What do you think of the baker?", vec = v(.97f, .05f, 0f)),
        )
        val th = Themes.cluster(log)
        assertEquals(2, th.size)
        assertEquals(2, th[0].count)
    }

    @Test
    fun matchesOnlyTheRightTheme() {
        val th = Themes.cluster(listOf(
            said("a", 1, "What do you think of the mayor?"), said("b", 2, "What do you think about the mayor?"),
            said("d", 5, "What was this town like in the 60s?"), said("e", 6, "What was the town like in the 60s?"),
        ))
        assertEquals("opinion", Themes.match("what do you think of the mayor", null, th)?.kind)
        assertEquals("the past", Themes.match("What was this town like in the 60s", null, th)?.kind)
        // no wrong-topic answers: another subject, another kind, or too far
        assertNull(Themes.match("What do you think of the baker?", null, th))
        assertNull(Themes.match("Where is the mayor's office?", null, th))
        assertNull(Themes.match("Do you like the mayor's new statue in the park by the river?", null, th))
        assertNull(Themes.match("Hello", null, th))
        // by vectors: past the threshold and the same subject
        val tv = Themes.cluster(listOf(said("a", 1, "What do you think of the mayor?", vec = floatArrayOf(1f, 0f))))
        assertNotNull(Themes.match("What do you think about the mayor?", floatArrayOf(.9f, .1f), tv))
        assertNull(Themes.match("What do you think about the mayor?", floatArrayOf(.5f, .8f), tv))
        assertNull(Themes.match("What do you think about the baker?", floatArrayOf(1f, 0f), tv))
    }

    private fun npc(id: String, building: String, row: String?, idx: Int) =
        Npc(id, "N $id", id, 30, building, building, 0, idx, "head", "job", emptyList(), row = row, rowIndex = idx)

    @Test
    fun theWriterOrderFollowsTheLog() {
        val npcs = listOf(npc("a", "b1", "1", 0), npc("b", "b2", "1", 1), npc("c", "b2", "1", 1), npc("d", "b9", "2", 5), npc("e", "b8", "3", 9))
        val stats = PlayerStats().apply {
            repeat(4) { record("d", "family", "ready", "2026-11-18") }
            record("a", "work", "ready", "2026-11-18")
        }
        val mem = listOf(NpcMemory.Entry("e", 10, "Do you have kids?", "Two.", FloatArray(0), town = "gamma", intent = "family", how = "live"))
        val s = Steer.build(npcs, "gamma", mem, stats, now = 20)
        // d (asked 4 times) first, then e (one live question counts 3), then a; b's neighbour a gives it a quarter
        val order = s.order(npcs, { it.id }, { null }).map { it.id }
        assertEquals(listOf("d", "e", "a", "b", "c"), order)
        assertTrue(s.need("b", null) > 0.0)
        // the topics they ask come first within a person's lines
        val jobs = listOf("a" to "chat", "a" to "work", "a" to "family")
        assertEquals("family", s.order(jobs, { it.first }, { it.second }).first().second)
        // a follow-up for e's recent exchange
        assertEquals("Do you have kids?", s.recent["e"]?.said)
        // another town's memories are not this town's
        assertTrue(Steer.build(npcs, "eta", mem, stats, 20).recent.isEmpty())
    }

    @Test
    fun reportsCarryNoWordsUnlessAllowed() {
        val secret = "My cat is called Whiskers and I live at 12 Oak Lane"
        val mem = listOf(
            NpcMemory.Entry("a", 1, secret, "Nice.", FloatArray(0), town = "gamma", how = "live"),
            NpcMemory.Entry("b", 2, secret, "Oh.", FloatArray(0), town = "gamma", how = "live"),
        )
        val s = Steer.build(emptyList(), "gamma", mem, PlayerStats(), 3)
        val counts = com.timerpg.street.shot.Json.write(s.summary(withText = false))
        assertFalse(counts.contains("Whiskers live at"))
        assertFalse(counts.contains(secret))
        assertTrue(counts.contains("\"asked\":2"))
        assertTrue(com.timerpg.street.shot.Json.write(s.summary(withText = true)).contains(secret))
        // the stats file is counts only
        val st = PlayerStats().apply { record("a", "chat", "live", "2026-11-18") }
        assertFalse(st.toJson().contains(secret))
        val back = PlayerStats().apply { load(st.toJson()) }
        assertEquals(1, back.byNpc()["a"])
        assertEquals(listOf(0, 1, 0), back.days["2026-11-18"]!!.toList())
    }
}
