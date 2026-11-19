package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The line bank: variants and their rotation, the file, and staleness by the facts' fingerprint. */
class BankTest {
    private fun tmp() = File.createTempFile("bank", ".json").apply { delete(); deleteOnExit() }

    @Test
    fun variantsRotateAndNeverRepeatInARow() {
        val c = ReplyCache(tmp())
        c.putLine("k", "Nice day for it.", "gamma/2025/a/chat")
        assertEquals("Nice day for it.", c.next("k"))
        assertEquals("Nice day for it.", c.next("k"))   // one line: that one
        assertTrue(c.addVariant("k", "Lovely weather, isn't it?"))
        assertFalse("the same words again are not a variant", c.addVariant("k", "nice day, for it!"))
        assertTrue(c.addVariant("k", "The sun's out at last."))
        val seen = (1..9).map { c.next("k") }
        for (i in 1 until seen.size) assertNotEquals("never the same twice in a row", seen[i - 1], seen[i])
        assertEquals(3, seen.toSet().size)
        // a template entry rotates nothing
        c.putTemplate("t")
        assertNull(c.next("t"))
        assertFalse(c.addVariant("t", "x"))
    }

    @Test
    fun pickCyclesAndSkipsTheLastOne() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2), (0 until 6).map { ReplyCache.pick(3, it, -1) })
        assertEquals(1, ReplyCache.pick(3, 3, 0))   // 3 % 3 = 0 was the last: the next one
        assertEquals(0, ReplyCache.pick(1, 5, 0))
    }

    @Test
    fun variantsStopWhenFullOrGivenUp() {
        val c = ReplyCache(tmp())
        c.putLine("k", "One.")
        assertTrue(c.wantsVariant("k", cap = 2, maxTries = 2))
        c.variantFailed("k"); c.variantFailed("k")
        assertFalse(c.wantsVariant("k", cap = 2, maxTries = 2))
        assertTrue(c.variantsDone("k", cap = 2, maxTries = 2))
        c.putLine("j", "Two.")
        c.addVariant("j", "Three.", cap = 2)
        assertFalse(c.wantsVariant("j", cap = 2))
    }

    @Test
    fun savesAndLoadsVariantsSlotsAndRotation() {
        val f = tmp()
        val c = ReplyCache(f)
        c.putLine("k", "A.", "gamma/2025/a/chat"); c.addVariant("k", "B."); c.next("k")
        c.putTemplate("t", "gamma/2025/a/work")
        c.save()
        val d = ReplyCache(f).apply { load() }
        assertEquals(listOf("A.", "B."), d.get("k")!!.lines)
        assertEquals("gamma/2025/a/chat", d.get("k")!!.slot)
        assertEquals("B.", d.next("k"))   // the rotation goes on where it was
        assertTrue(d.get("t")!!.lines.isEmpty())
    }

    @Test
    fun readsTheFirstCacheFormat() {
        val f = tmp()
        f.writeText("""{"v":1,"lines":{"k":"Old line."},"templates":["t"]}""")
        val c = ReplyCache(f).apply { load() }
        assertEquals("Old line.", c.get("k")!!.line)
        assertTrue(c.get("t")!!.lines.isEmpty())
        c.setSlot("k", "gamma/2025/a/chat")
        assertEquals("gamma/2025/a/chat", c.get("k")!!.slot)
    }

    private fun gamma() = DialogueData.parse(File("src/main/assets/town/gamma/dialogue.json").readText())

    /** The same file with one person's facts changed (their age, which is in every one of their fact sheets). */
    private fun withOneChanged(d: DialogueData, who: Npc): DialogueData = DialogueData(d.town, d.year, d.townName, d.streets, d.rules,
        d.npcs.map { n -> if (n.id != who.id) n else n.copy(topics = n.topics.map { t -> t.copy(prompt = t.prompt?.replace("${n.name}, ${n.age},", "${n.name}, ${n.age + 1},")) }) },
        d.version)

    @Test
    fun theKeyCatchesAChangedFact() {
        val d = gamma()
        val who = d.npcs.first { n -> n.topics.any { it.prompt != null } }
        val t = who.topics.first { it.prompt != null }
        val changed = t.prompt!!.replace("${who.name}, ${who.age},", "${who.name}, ${who.age + 1},")
        assertNotEquals("the fact is in the prompt", t.prompt, changed)
        assertNotEquals(DialogueEngine.keyOf(t.prompt!!), DialogueEngine.keyOf(changed))
    }

    @Test
    fun aChangedPersonsLinesGoStaleAndOnlyTheirs() {
        val d = gamma()
        val plan = BankPlan.of(d, DialogueEngine::keyOf)
        // (650 prompts, 580 of them distinct: the same person at their shop and at home says the same)
        val n = plan.size
        assertTrue(n in 500..650)
        val c = ReplyCache(tmp())
        for ((k, slot) in plan) c.putLine(k, "A line for $slot.", slot)
        val before = c.bank(plan)
        assertEquals(n, before.ready); assertEquals(0, before.stale); assertEquals(listOf(2025), before.years)

        val who = d.npcs.first { n -> n.topics.count { it.prompt != null } >= 3 }
        val mine = d.npcs.filter { it.id == who.id }.flatMap { it.topics.mapNotNull { t -> t.prompt } }.distinct().size
        val plan2 = BankPlan.of(withOneChanged(d, who), DialogueEngine::keyOf)
        val after = c.bank(plan2)
        assertEquals("their lines are not used", n - mine, after.ready)
        assertEquals("their prompts are written again", mine, after.missing)
        assertEquals(mine, after.stale)
        assertTrue("only their slots", after.staleSlots.all { it.startsWith("gamma/2025/${who.id}/") })
        // the stale lines are kept (the facts may come back), the rest stay valid
        c.retain(plan2.keys, plan2.values.toSet())
        assertEquals(n, c.lines)
        assertEquals(n, c.bank(plan).ready)
    }
}
