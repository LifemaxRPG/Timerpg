package com.timerpg.street

import com.timerpg.street.upgrade.AutoFetch
import com.timerpg.street.upgrade.Upgrades
import com.timerpg.street.upgrade.Upgrades.State
import com.timerpg.street.upgrade.Upgrades.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upgrade checks' list logic ([Upgrades.Board]) with fake checks, and the automatic download's rule ([AutoFetch]). */
class UpgradesTest {
    private class Fake(override val id: String, var st: Status) : Upgrades.Check {
        var acted = 0
        override fun status() = st
        override fun act() { acted++ }
    }

    private fun board(store: Upgrades.Store = Upgrades.MemoryStore(), vararg cs: Fake) = Upgrades.Board(store).also { b -> cs.forEach { b.register(it) } }

    @Test fun everythingInPlaceOrRunningShowsNoCard() {
        val b = board(Upgrades.MemoryStore(), Fake("a", Status(State.DONE)), Fake("b", Status(State.RUNNING, "downloading")), Fake("c", Status(State.NONE)))
        assertTrue(b.refresh().isEmpty())
        assertFalse(b.open)
        assertTrue(b.items.isEmpty())
        assertEquals(3, b.all.size)
    }

    @Test fun bootCardListsWhatAsksOrFailed() {
        val ask = Fake("dl", Status(State.ASK, "on mobile data", askKey = "metered"))
        val fail = Fake("prep", Status(State.FAILED, "failed", askKey = "failed"))
        val b = board(Upgrades.MemoryStore(), ask, Fake("ok", Status(State.DONE)), fail)
        b.refresh()
        assertTrue(b.open)
        assertEquals(listOf("dl/metered", "prep/failed"), b.items.map { it.key })
    }

    @Test fun doItRunsTheActionAndHidesTheItem() {
        val ask = Fake("dl", Status(State.ASK, "x", askKey = "metered"))
        val b = board(Upgrades.MemoryStore(), ask)
        b.refresh()
        b.doIt(b.items.single())
        assertEquals(1, ask.acted)
        assertTrue(b.items.isEmpty()); assertFalse(b.open)
        b.refresh()
        assertTrue("answered this session: not again", b.items.isEmpty())
    }

    @Test fun laterHidesForTheSessionOnly() {
        val store = Upgrades.MemoryStore()
        val ask = Fake("dl", Status(State.ASK, "x", askKey = "metered"))
        val b = board(store, ask)
        b.refresh(); b.later(b.items.single()); b.refresh()
        assertTrue(b.items.isEmpty())
        assertEquals(0, ask.acted)
        // the next start (a new board over the same store) asks again
        val next = board(store, ask)
        next.refresh()
        assertTrue(next.open); assertEquals(1, next.items.size)
    }

    @Test fun neverIsRememberedPerQuestion() {
        val store = Upgrades.MemoryStore()
        val ask = Fake("dl", Status(State.ASK, "x", askKey = "metered"))
        board(store, ask).apply { refresh(); never(items.single()) }
        val next = board(store, ask)
        next.refresh()
        assertFalse(next.open); assertTrue(next.items.isEmpty())
        // a different question from the same check still asks
        ask.st = Status(State.ASK, "no space", askKey = "space")
        val third = board(store, ask)
        third.refresh()
        assertEquals(listOf("dl/space"), third.items.map { it.key })
        third.forgetNever()
        ask.st = Status(State.ASK, "x", askKey = "metered")
        val fourth = board(store, ask); fourth.refresh()
        assertEquals(1, fourth.items.size)
    }

    @Test fun afterBootOnlyANewFailureOpensTheCard() {
        val c = Fake("dl", Status(State.RUNNING))
        val b = board(Upgrades.MemoryStore(), c)
        b.refresh()
        assertFalse(b.open)
        // a question that comes up in play waits for the next start (or the AI panel)
        c.st = Status(State.ASK, "left Wi-Fi", askKey = "metered")
        b.refresh()
        assertFalse(b.open)
        assertEquals(1, b.items.size)
        c.st = Status(State.FAILED, "stopped", askKey = "failed")
        b.refresh()
        assertTrue(b.open)
        // closing it counts as later
        b.close(); b.refresh()
        assertFalse(b.open); assertTrue(b.items.isEmpty())
    }

    @Test fun toastOncePerFinishedThing() {
        val store = Upgrades.MemoryStore()
        val prep = Fake("prep", Status(State.RUNNING))
        val b = board(store, prep)
        assertTrue(b.refresh().isEmpty())
        prep.st = Status(State.DONE, doneKey = "old-en:49152", doneNote = "AI model prepared: English-first, 3.9 s")
        assertEquals(listOf("AI model prepared: English-first, 3.9 s"), b.refresh())
        assertTrue(b.refresh().isEmpty())
        assertTrue("not again at the next start", board(store, prep).refresh().isEmpty())
        // a new file prepared later: its own toast
        prep.st = Status(State.DONE, doneKey = "mtp-en:49152", doneNote = "AI model prepared: English-first (4B with MTP), 4.1 s")
        assertEquals(1, b.refresh().size)
        // done without a note (a setting already on): no toast
        assertTrue(board(Upgrades.MemoryStore(), Fake("mtp", Status(State.DONE, "on"))).refresh().isEmpty())
    }

    @Test fun aCheckThatThrowsDoesNotStopTheOthers() {
        val bad = object : Upgrades.Check { override val id = "bad"; override fun status(): Status = error("boom"); override fun act() {} }
        val b = Upgrades.Board(Upgrades.MemoryStore()).apply { register(bad); register(Fake("dl", Status(State.ASK, "x"))) }
        b.refresh()
        assertEquals(listOf("dl"), b.items.map { it.key })
        assertEquals(State.NONE, b.all.first().second.state)
    }

    @Test fun registeringTwiceKeepsOne() {
        val c = Fake("dl", Status(State.ASK, "x"))
        val b = board(Upgrades.MemoryStore(), c, c)
        b.refresh()
        assertEquals(1, b.items.size)
    }

    // ---- the automatic download

    private val gb = 1L shl 30
    private fun input(here: Boolean = false, downloading: Boolean = false, connected: Boolean = true, unmetered: Boolean = true,
                      free: Long = 10 * gb, remaining: Long = 2_669_209_920L, paused: Boolean = false, meteredOk: Boolean = false, failures: Int = 0) =
        AutoFetch.Input(here, downloading, connected, unmetered, free, remaining, paused, meteredOk, failures)

    @Test fun autoFetchStartsOnWifiWithRoom() {
        assertEquals(AutoFetch.Go.START, AutoFetch.decide(input()))
        assertEquals(AutoFetch.Go.HERE, AutoFetch.decide(input(here = true)))
        assertEquals(AutoFetch.Go.RUNNING, AutoFetch.decide(input(downloading = true)))
    }

    @Test fun autoFetchAsksOnMobileDataUnlessAllowed() {
        assertEquals(AutoFetch.Go.ASK_METERED, AutoFetch.decide(input(unmetered = false)))
        assertEquals(AutoFetch.Go.START, AutoFetch.decide(input(unmetered = false, meteredOk = true)))
        assertEquals(AutoFetch.Go.WAIT_NETWORK, AutoFetch.decide(input(connected = false, unmetered = false)))
    }

    @Test fun autoFetchNeedsRoomForWhatIsLeft() {
        // a half-done part: only the rest needs room
        val half = 1_334_604_960L
        assertEquals(AutoFetch.Go.START, AutoFetch.decide(input(free = half + AutoFetch.MARGIN_BYTES, remaining = half)))
        assertEquals(AutoFetch.Go.ASK_SPACE, AutoFetch.decide(input(free = half + AutoFetch.MARGIN_BYTES - 1, remaining = half)))
    }

    @Test fun autoFetchRespectsThePauseAndGivesUpAfterFailures() {
        assertEquals(AutoFetch.Go.PAUSED, AutoFetch.decide(input(paused = true)))
        assertEquals(AutoFetch.Go.START, AutoFetch.decide(input(failures = AutoFetch.MAX_FAILURES - 1)))
        assertEquals(AutoFetch.Go.FAILED, AutoFetch.decide(input(failures = AutoFetch.MAX_FAILURES)))
    }
}
