package com.timerpg.street.dialogue

import com.timerpg.street.llm.AiLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 0.1.930 hang, on the JVM: the memory model's native call never returned while it held the AI lane, and the next
 * spoken turn waited behind it for good ("thinking" for 43 s, no reply, no log). A fake memory worker wedges the lane the
 * same way; the dialogue's bounded wait gives up and says who holds it, the memory lookup's deadline answers without
 * memories once, and the turn's watchdog ends the turn once whoever comes first.
 */
class TurnWatchTest {
    @Test fun aWedgedLaneDoesNotTakeTheDialogueWithIt() {
        val wedged = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val memory = Thread({ AiLane.run("memory") { inside.countDown(); wedged.await() } }, "npc-memory").apply { isDaemon = true; start() }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // what the breadcrumbs say about it
        val d = AiLane.describe()
        assertTrue(d, d.startsWith("memory (npc-memory) for "))
        // the reply someone waits for: not forever
        val t0 = System.nanoTime()
        val busy = try { AiLane.run("dialogue", 300) { "a reply" }; null } catch (b: AiLane.Busy) { b }
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("the dialogue must not run while the lane is held", busy)
        assertTrue("waited $waited ms", waited in 250..3000)
        assertTrue(busy!!.message!!, busy.message!!.contains("memory (npc-memory)"))
        // once the lane is given back, the dialogue runs again
        wedged.countDown(); memory.join(5000)
        assertEquals("a reply", AiLane.run("dialogue", 1000) { "a reply" })
        assertEquals("free", AiLane.describe())
    }

    @Test fun aLookupAnswersOnceByItsResultOrItsDeadline() {
        val got = ArrayList<String>()
        val once = Once<String> { got += it }
        assertTrue(once.offer("deadline: no memories"))
        assertFalse(once.offer("the memories, late"))
        assertEquals(listOf("deadline: no memories"), got)
    }

    @Test fun theWatchdogOrTheReplyEndsTheTurnNotBoth() {
        var now = 1000L
        val w = TurnWatch { now }
        w.stage("looking up memories")
        now += 12_000
        assertEquals("looking up memories for 12000 ms (turn 12000 ms)", w.note())
        val ends = AtomicInteger()
        // the watchdog and a late reply race: one wins
        val a = Thread { if (w.end()) ends.incrementAndGet() }
        val b = Thread { if (w.end()) ends.incrementAndGet() }
        a.start(); b.start(); a.join(); b.join()
        assertEquals(1, ends.get())
        assertTrue(w.isEnded)
    }
}
