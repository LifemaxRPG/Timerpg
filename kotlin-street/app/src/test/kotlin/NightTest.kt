package com.timerpg.street.night

import com.timerpg.street.dialogue.ReplyCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The overnight writer's decisions and its report. */
class NightTest {
    private val ok = NightInputs(left = 100)

    @Test
    fun stopsForTheRightReasons() {
        val r = NightRules()
        assertEquals(NightStep.Stop("unplugged", true), r.step(ok.copy(plugged = false), 0))
        assertFalse((r.step(ok.copy(gameOpen = true), 0) as NightStep.Stop).again)
        assertFalse((r.step(ok.copy(enabled = false), 0) as NightStep.Stop).again)
        assertTrue(r.step(ok.copy(tuning = true), 0) is NightStep.Stop)
        assertEquals("the bank is complete", (r.step(ok.copy(left = 0), 0) as NightStep.Stop).reason)
        assertTrue((r.step(ok.copy(thermal = 4), 0) as NightStep.Stop).again)
        // the game wins over everything else (it writes its own lines)
        assertTrue((r.step(ok.copy(gameOpen = true, plugged = false), 0) as NightStep.Stop).reason.startsWith("the game"))
        // not known yet (the model loading): it goes on
        assertEquals(NightStep.Write, NightRules().step(ok.copy(left = -1), 0))
    }

    @Test
    fun writesAtAnyBatteryWhileItCharges() {
        val r = NightRules()
        // plugged in nearly empty (the owner's phone at 9%): it writes as long as the battery charges
        assertEquals(NightStep.Write, r.step(ok.copy(battery = 9, charging = true), 0))
        assertEquals(NightStep.Write, r.step(ok.copy(battery = 10, charging = true), 1))
        // a weak charger losing to the writing: it waits until the floor
        assertEquals(NightStep.Kind.BATTERY, (r.step(ok.copy(battery = 8, charging = true), 2) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.BATTERY, (r.step(ok.copy(battery = 12, charging = true), 3) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(battery = 15, charging = true), 4))
        // plugged in but not charging under the floor: it waits
        assertEquals(NightStep.Kind.BATTERY, (NightRules().step(ok.copy(battery = 9, charging = false), 0) as NightStep.Rest).kind)
        // (Samsung's protection holds it at 85% without charging: fine)
        assertEquals(NightStep.Write, NightRules().step(ok.copy(battery = 85, charging = false), 0))
    }

    @Test
    fun writerModeOnBattery() {
        val r = NightRules()
        // unplugged: the overnight writer stops; Writer mode with "on battery" goes on, down to its floor
        assertTrue(r.step(ok.copy(plugged = false), 0) is NightStep.Stop)
        assertEquals(NightStep.Write, r.step(ok.copy(plugged = false, onBattery = true, battery = 40), 1))
        val low = r.step(ok.copy(plugged = false, onBattery = true, battery = 19), 2) as NightStep.Rest
        assertEquals(NightStep.Kind.BATTERY, low.kind)
        assertTrue(low.reason.contains("plug"))
        // Pause holds it; the game opening still stops it
        assertEquals(NightStep.Kind.USER, (r.step(ok.copy(paused = true), 3) as NightStep.Rest).kind)
        assertTrue(r.step(ok.copy(paused = true, gameOpen = true), 4) is NightStep.Stop)
    }

    @Test
    fun writesInBursts() {
        val r = NightRules(burstMs = 1000, restMs = 500)
        assertEquals(NightStep.Write, r.step(ok, 0))
        assertEquals(NightStep.Write, r.step(ok, 999))
        assertEquals(NightStep.Kind.BURST, (r.step(ok, 1000) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.BURST, (r.step(ok, 1400) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok, 1500))
        // with the headroom known and well clear, no rest is needed
        val q = NightRules(burstMs = 1000, restMs = 500)
        assertEquals(NightStep.Write, q.step(ok.copy(headroom = .4f), 0))
        assertEquals(NightStep.Write, q.step(ok.copy(headroom = .4f), 1000))
        assertEquals(NightStep.Write, q.step(ok.copy(headroom = .4f), 2500))
    }

    @Test
    fun startsAtOnceWhenCoolAndSlowsDownWhenWarm() {
        val r = NightRules(burstMs = 10_000, restMs = 1_000, warmBurstMs = 100, warmRestMs = 400)
        // light right after playing: it writes straight away, no cool-down first
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 1), 0))
        // moderate (the phone still warm after playing): it keeps writing, in short bursts with longer rests
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 2), 50))
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 2), 100) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 2), 400) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 2), 500))
        // a warm rest ends early once the phone is cool again
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 2), 600) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 1, headroom = .5f), 700))
        // headroom near throttling alone makes it warm
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 0, headroom = .95f), 750))
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 0, headroom = .95f), 900) as NightStep.Rest).kind)
        assertEquals(2, r.coolDowns)
    }

    @Test
    fun pausesWhenHotAndGoesOnWhenModerate() {
        val r = NightRules(warmBurstMs = 100, warmRestMs = 400)
        val hot = r.step(ok.copy(thermal = 3), 0) as NightStep.Rest
        assertEquals(NightStep.Kind.HOT, hot.kind)
        assertEquals(NightStep.Kind.HOT, (r.step(ok.copy(thermal = 3), 10) as NightStep.Rest).kind)
        // moderate again: on, in warm bursts
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 2), 20))
        assertEquals(1, r.hotPauses)
        assertEquals(3, r.maxThermal)
        assertTrue((r.step(ok.copy(thermal = 4), 30) as NightStep.Stop).again)
    }

    /** A dialogue engine that writes a line every [msPerLine] while it is let write, after [loadMs] of loading. */
    private class FakeEngine(var lines: Int = 100, val msPerLine: Long = 60_000, val loadMs: Long = 30_000) : WriterEngine {
        var now = 0L
        var held = true
        var heldHot = 0
        private var progress = 0L
        private var written = 0
        override var state = com.timerpg.street.dialogue.ModelState.LOADING
        override var detail = "Loading the model"
        override fun left() = if (state == com.timerpg.street.dialogue.ModelState.READY) lines - written else -1
        override fun count() = com.timerpg.street.dialogue.NightCount(lines = written, genTokens = written * 30L, genMs = written * 4_000L)
        override fun hold(on: Boolean, stopRunning: Boolean) { held = on; if (on && stopRunning) heldHot++ }
        override fun reload() {}
        fun advance(dt: Long) {
            now += dt
            if (state != com.timerpg.street.dialogue.ModelState.READY) { if (now >= loadMs) { state = com.timerpg.street.dialogue.ModelState.READY; detail = "" }; return }
            if (held) return
            progress += dt
            while (progress >= msPerLine && written < lines) { progress -= msPerLine; written++ }
        }
    }

    @Test
    fun theWriterLoopWithAFakeEngine() {
        val e = FakeEngine(lines = 80)
        val loop = WriterLoop(NightRules(burstMs = 8 * 60_000L, restMs = 2 * 60_000L, warmBurstMs = 90_000L, warmRestMs = 4 * 60_000L), e, start = 0)
        var t = 0L
        var stop: NightStep.Stop? = null
        // an hour cool, then an hour warm (moderate), then severe for 10 minutes, then cool until the bank is done
        while (t < 8 * 3_600_000L) {
            val thermal = when { t < 3_600_000L -> 1; t < 7_200_000L -> 2; t < 7_800_000L -> 3; else -> 0 }
            val s = loop.tick(NightInputs(thermal = thermal), t)
            if (s is NightStep.Stop) { stop = s; break }
            e.advance(5_000); t += 5_000
        }
        assertEquals("the bank is complete", stop?.reason)
        assertEquals(25_000L, loop.loadingMs)
        // the first hour: 8-minute bursts with 2-minute rests
        assertTrue(loop.spent(NightStep.Kind.BURST) > 0)
        // warm: it kept writing, slower (bursts of 90 s, rests of 4 min), never stopped
        assertTrue(loop.spent(NightStep.Kind.COOL) > 40 * 60_000L)
        assertEquals(10 * 60_000L, loop.spent(NightStep.Kind.HOT))
        assertTrue(e.heldHot > 0)
        assertEquals(1, loop.rules.coolDowns)
        // the estimate: no pace before a line, then the session's pace
        assertEquals(null, WriterLoop.etaMs(left = 10, written = 0, elapsedMs = 60_000, writingMs = 60_000))
        assertEquals(10 * 60_000L * 5 / 4, WriterLoop.etaMs(left = 10, written = 1, elapsedMs = 120_000, writingMs = 60_000))
        assertEquals(20 * 60_000L, WriterLoop.etaMs(left = 10, written = 6, elapsedMs = 12 * 60_000L, writingMs = 10 * 60_000L))
        assertEquals(0L, WriterLoop.etaMs(left = 0, written = 0, elapsedMs = 0, writingMs = 0))
        assertEquals("2 h 5 min", WriterLoop.duration(125 * 60_000L))
    }

    @Test
    fun theLoopGivesUpOnAModelThatNeverLoadsInPlainWords() {
        val e = FakeEngine(loadMs = Long.MAX_VALUE).apply { state = com.timerpg.street.dialogue.ModelState.WAITING; detail = "Not enough free memory to load the model (3000 MB free, about 3663 MB needed with room for the game)." }
        val loop = WriterLoop(NightRules(), e, start = 0, giveUpMs = 60_000)
        var t = 0L
        var stop: NightStep.Stop? = null
        while (t < 600_000) { val s = loop.tick(NightInputs(), t); if (s is NightStep.Stop) { stop = s; break }; t += 5_000 }
        assertTrue(stop!!.reason.startsWith("the model was not ready"))
        assertTrue(loop.modelProblem!!.contains("3000 MB free"))
        // an outside stop (the game opened) wins
        val l2 = WriterLoop(NightRules(), FakeEngine(), start = 0)
        assertEquals(NightStep.Stop("the game was opened (it writes its own lines)", false), l2.tick(NightInputs(), 0, "the game was opened (it writes its own lines)"))
    }

    @Test
    fun aHeldEngineIsNotCountedAsWritingAndTheReportSaysSo() {
        // the night of 2026-11-19 06:27: the model ready, the rules saying write, and a "memory" hold left in the engine
        val e = object : WriterEngine {
            override val state = com.timerpg.street.dialogue.ModelState.READY
            override val detail = "Ready."
            override fun left() = 600
            override fun count() = com.timerpg.street.dialogue.NightCount()
            override fun hold(on: Boolean, stopRunning: Boolean) {}
            override fun reload() {}
            override fun held() = "memory"
        }
        val loop = WriterLoop(NightRules(headroomFree()), e, start = 0)
        for (t in 0..(23 * 60_000L) step 5_000L) loop.tick(NightInputs(headroom = .4f), t)
        assertEquals(0L, loop.writingMs)
        assertEquals(23 * 60_000L, loop.heldMs)
        assertEquals("memory", loop.heldBy)
        val start = 0L
        val r = NightReport.Run(start = start, end = 23 * 60_000L, how = "service", stopReason = "the game was opened (it writes its own lines)",
            heldMs = loop.heldMs, heldBy = loop.heldBy, engineAtEnd = "ready: Ready. (held by: memory)")
        assertTrue(NightReport.explain(r), NightReport.explain(r).contains("23 min with the model ready but held by the engine (memory)"))
        // and writing time that generated no token says so
        val r2 = NightReport.Run(start = 0, end = 30 * 60_000L, how = "service", stopReason = "unplugged", writingMs = 22 * 60_000L, engineAtEnd = "ready: Ready.")
        assertTrue(NightReport.explain(r2), NightReport.explain(r2).contains("generated no token at all"))
    }

    private fun headroomFree() = 8 * 60_000L

    @Test
    fun theReportSaysWhyNothingWasWritten() {
        // the owner's night of 2026-11-19: plugged in at 9%, waited for the battery, then unplugged
        val start = 1_763_510_400_000L
        val r = NightReport.Run(start = start, end = start + 75 * 60_000L, how = "service", stopReason = "unplugged",
            batteryWaitMs = 75 * 60_000L, batteryStart = 9, batteryEnd = 21, pluggedStart = true)
        assertEquals("Nothing was written: 1 h 15 min waiting for the battery (9% at the start, 21% at the end). It stopped: unplugged.", NightReport.explain(r))
        val warm = NightReport.Run(start = start, end = start + 5 * 60_000L, how = "service", stopReason = "unplugged", coolMs = 4 * 60_000L, maxThermal = 2,
            loadingMs = 70_000L, modelProblem = "Not enough free memory", runsOn = "CPU")
        val w = NightReport.explain(warm)
        assertTrue(w, w.contains("4 min resting between short bursts because the phone was warm (thermal status up to 2)"))
        assertTrue(w, w.contains("waiting for the model to load (Not enough free memory)"))
        val good = NightReport.Run(start = start, end = start + 3_600_000L, how = "writer", stopReason = "stopped in Writer mode", lines = 40, variants = 2,
            writingMs = 50 * 60_000L, model = "main model on the NPU", userPausedMs = 5 * 60_000L)
        assertEquals("Wrote 40 lines and 2 variants with the main model on the NPU in 50 min of writing (1 h 0 min in all); 5 min paused by you. It stopped: stopped in Writer mode.",
            NightReport.explain(good))
        val j = org.json.JSONObject(NightReport.json(good.copy(queue = com.timerpg.street.dialogue.BankPlan.queue(listOf("gamma/2025/ann/work"), names = { "Ann" }),
            recent = listOf(NightReport.Written("Ann", "work", "I bake.", "line"), NightReport.Written("Bo", "next", null, "line"))), mapOf(), TimeZone.getTimeZone("UTC")))
        assertEquals("writer", j.getString("kind"))
        assertTrue(j.getString("inPlainWords").startsWith("Wrote 40 lines"))
        assertEquals(1, j.getJSONObject("queue").getJSONObject("byPerson").getInt("Ann"))
        assertTrue(j.getJSONArray("recent").getJSONObject(1).getString("text").startsWith("(kept on the phone"))
    }

    @Test
    fun theReport() {
        val tz = TimeZone.getTimeZone("UTC")
        val start = 1_763_510_400_000L   // 2025-11-19 00:00 UTC
        val r = NightReport.Run(
            start = start, end = start + 2 * 3_600_000L, how = "service", stopReason = "unplugged",
            lines = 300, variants = 100, rejected = 12, genTokens = 16_000, genMs = 2_000_000, prefillTokens = 90_000, prefillMs = 900_000,
            writingMs = 3_600_000L, coolMs = 600_000L, coolDowns = 2, hotPauses = 0, maxThermal = 2, batteryStart = 40, batteryEnd = 100,
            runsOn = "NPU", bank = ReplyCache.Bank(prompts = 650, ready = 640, templates = 10, variants = 740, stale = 3, years = listOf(2025)),
            themes = listOf(mapOf("label" to "the past: 60s", "asked" to 5)), answers = listOf("2025-11-18" to intArrayOf(3, 1, 0)),
        )
        assertEquals("night/2025-11-19/000000.json", NightReport.path(start, tz))
        assertEquals(200.0, r.linesPerHour, 1e-9)
        assertEquals(400.0, r.linesPerWritingHour, 1e-9)
        assertEquals(8.0, r.genPerSec, 1e-9)
        val j = org.json.JSONObject(NightReport.json(r, mapOf("versionName" to "test"), tz))
        assertEquals("night", j.getString("kind"))
        assertEquals(120.0, j.getDouble("minutes"), 1e-9)
        assertEquals(300, j.getJSONObject("written").getInt("lines"))
        assertEquals(8.0, j.getJSONObject("speed").getDouble("genTokPerSec"), 1e-9)
        assertEquals(10.0, j.getJSONObject("time").getDouble("coolingMin"), 1e-9)
        assertEquals(3, j.getJSONObject("bank").getInt("stale"))
        assertEquals(75.0, j.getJSONObject("player").getJSONArray("answersByDay").getJSONObject(0).getDouble("readyShare"), 1e-9)
        assertTrue(NightReport.summary(r, tz).contains("300 lines + 100 variants"))
    }
}
