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
    fun waitsForTheBatteryWhileCharging() {
        val r = NightRules(minBattery = 30)
        assertEquals(NightStep.Kind.BATTERY, (r.step(ok.copy(battery = 20), 0) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(battery = 30), 1))
    }

    @Test
    fun writesInBursts() {
        val r = NightRules(burstMs = 1000, restMs = 500)
        assertEquals(NightStep.Write, r.step(ok, 0))
        assertEquals(NightStep.Write, r.step(ok, 999))
        assertEquals(NightStep.Kind.BURST, (r.step(ok, 1000) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.BURST, (r.step(ok, 1400) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok, 1500))
    }

    @Test
    fun coolsDownWhenWarmAndPausesWhenHot() {
        val r = NightRules()
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 1, headroom = .5f), 0))
        // moderate: cool down until light again and the headroom is back under RESUME_AT
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 2), 1) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 1, headroom = .8f), 2) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 1, headroom = .7f), 3))
        // headroom near throttling alone is enough
        assertEquals(NightStep.Kind.COOL, (r.step(ok.copy(thermal = 0, headroom = .95f), 4) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 0, headroom = .5f), 5))
        // severe: paused, and stays paused through moderate until light
        assertEquals(NightStep.Kind.HOT, (r.step(ok.copy(thermal = 3), 6) as NightStep.Rest).kind)
        assertEquals(NightStep.Kind.HOT, (r.step(ok.copy(thermal = 2), 7) as NightStep.Rest).kind)
        assertEquals(NightStep.Write, r.step(ok.copy(thermal = 1, headroom = .5f), 8))
        assertEquals(2, r.coolDowns)
        assertEquals(1, r.hotPauses)
        assertEquals(3, r.maxThermal)
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
