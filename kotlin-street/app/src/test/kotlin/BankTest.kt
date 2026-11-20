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
    fun twoProcessesSavingTheSameBankLoseNothing() {
        val f = tmp()
        val game = ReplyCache(f); val writer = ReplyCache(f)
        game.putLine("a", "From the game.", "gamma/2025/a/chat"); game.save()
        writer.load()
        writer.putLine("b", "From the writer.", "gamma/2025/b/chat")
        writer.putLine("light", "A light line.", "gamma/2025/c/work", light = true)
        writer.save()
        // the game saves after: the writer's lines are merged in, not overwritten
        game.putLine("c", "Another from the game.", "gamma/2025/d/chat"); game.save()
        val back = ReplyCache(f).apply { load() }
        assertEquals("From the writer.", back.get("b")?.line)
        assertEquals("Another from the game.", back.get("c")?.line)
        assertTrue(back.get("light")!!.light)
        assertEquals(1, back.light)
        // the main model's rewrite replaces the light line and its variants, and wins a merge
        back.replaceLine("light", "The main model's line.")
        assertFalse(back.get("light")!!.light)
        back.save()
        writer.addVariant("light", "Another light line."); writer.save()
        val again = ReplyCache(f).apply { load() }
        assertEquals(listOf("The main model's line."), again.get("light")!!.lines)
        // a key one side let go of on purpose is not brought back by the merge
        again.retain(setOf("a", "b", "light"))
        again.save()
        assertEquals(null, ReplyCache(f).apply { load() }.get("c"))
    }

    @Test
    fun theQueueByYearPersonAndTopic() {
        val q = BankPlan.queue(listOf("gamma/2025/ann/work", "gamma/2025/ann/chat", "gamma/2026/bo/theme:t1", "gamma/2025/bo/next"), { mapOf("ann" to "Ann").get(it) }, people = 1)
        assertEquals(listOf("2025" to 3, "2026" to 1), q.byYear)
        assertEquals(listOf("Ann" to 2, "others (1)" to 2), q.byPerson)
        assertEquals(4, q.total)
        assertTrue(q.byTopic.contains("your themes" to 1) && q.byTopic.contains("follow-ups" to 1))
    }

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
        // (every prompted topic of the town's people, most of them distinct: the same person at their shop and at home says the same)
        val n = plan.size
        val total = d.npcs.sumOf { it.topics.count { t -> t.prompt != null } }
        assertTrue("$n of $total", n in total * 8 / 10..total && n >= 300)
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
