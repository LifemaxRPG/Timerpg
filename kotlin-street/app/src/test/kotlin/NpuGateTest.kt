package com.timerpg.street

import com.timerpg.street.llm.FitReport
import com.timerpg.street.llm.MemProbe
import com.timerpg.street.llm.NpuGate
import com.timerpg.street.llm.NpuGate.Try
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The device gate's figure for what Android finds beyond its free count, the fit test's report, and the readers. */
class NpuGateTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day

    @Test fun aFirstGuessFromTheSwapUntilALoadMeasuresIt() {
        assertEquals(1024L, NpuGate.reclaim(2900, 3630, 2048, emptyList(), now).reclaimMb)
        assertEquals(NpuGate.GUESS_CAP_MB, NpuGate.reclaim(2900, 3630, 8000, emptyList(), now).reclaimMb)
        assertEquals(0L, NpuGate.reclaim(2900, 3630, 0, emptyList(), now).reclaimMb)
        assertEquals(0L, NpuGate.reclaim(2900, 3630, 8000, emptyList(), now, on = false).reclaimMb)
    }

    @Test fun whatWasMeasuredCountsTheMedianOfTheLastFive() {
        // four measured: the lower of the middle two
        val t = listOf(Try(now - 4 * day, 3000, 3630, true, 200), Try(now - 3 * day, 3000, 3630, true, 1800),
            Try(now - 2 * day, 3000, 3630, true, 1200, measure = true), Try(now - day, 3000, 3630, true, 2500))
        assertEquals(1200L, NpuGate.reclaim(2900, 3630, 0, t, now).reclaimMb)
        assertEquals(NpuGate.CAP_MB, NpuGate.reclaim(2900, 3630, 0, listOf(Try(now, 1, 2, true, 9000)), now).reclaimMb)
        // the S25's case (MEMORY_AUDIT.md, M5): one low load (691) no longer holds back the ~1.5 GB the others found;
        // the least of the last three counted 691
        val s25 = listOf(Try(now - 5 * day, 2660, 3513, true, 1514), Try(now - 4 * day, 6260, 6617, true, 1551),
            Try(now - 3 * day, 2900, 3751, true, 691), Try(now - 2 * day, 3000, 3751, true, 1600), Try(now - day, 3050, 3751, true, 1450))
        assertEquals(1514L, NpuGate.reclaim(3050, 3751, 0, s25, now).reclaimMb)
        // only the last five count: an old load is dropped
        assertEquals(1514L, NpuGate.reclaim(3050, 3751, 0, listOf(Try(now - 9 * day, 2000, 3751, true, 5)) + s25, now).reclaimMb)
        assertEquals(1550L, NpuGate.lowerMedian(listOf(1600, 1500, 9000, 1550, 100)))
        assertEquals(10L, NpuGate.lowerMedian(listOf(10, 20)))
    }

    @Test fun theMedianIsCrossCheckedWithThePhoneNow() {
        val t = listOf(Try(now - 2 * day, 2660, 3513, true, 1514), Try(now - day, 2900, 3751, true, 1600))
        // the kernel's reclaimable beyond Android's count (MemAvailable 3400 vs availMem 3000) plus the free swap (600)
        assertEquals(1000L, NpuGate.crossCap(3400, 3000, 600))
        val v = NpuGate.reclaim(3000, 3751, 600, t, now, capMb = 1000)
        assertEquals(1000L, v.reclaimMb)
        assertTrue(v.why, v.why.contains("at most 1000 MB"))
        // plenty of swap: the median stands; not readable: no cap
        assertEquals(1514L, NpuGate.reclaim(3000, 3751, 4000, t, now, capMb = NpuGate.crossCap(3100, 3000, 4000)).reclaimMb)
        assertEquals(-1L, NpuGate.crossCap(-1, 3000, 4000))
        assertEquals(1514L, NpuGate.reclaim(3000, 3751, 0, t, now, capMb = -1).reclaimMb)
        // Android counting more than the kernel: no negative part
        assertEquals(500L, NpuGate.crossCap(2900, 3000, 500))
    }

    @Test fun theMarginShrinksAfterLoadsThatHeldAndGrowsAfterTrouble() {
        // start: the old flat margin
        assertEquals(com.timerpg.street.llm.Ram.MARGIN_MB, NpuGate.MARGIN_START_MB)
        // held with 1500 MB free at the lowest, threshold 216: 784 MB above the critical line (716), half of it is more
        // than a step, so one step down
        assertEquals(800L, NpuGate.marginAfterHeld(900, 1500, 216))
        // a little room: half of it (800 - 716 = 84 -> 42)
        assertEquals(858L, NpuGate.marginAfterHeld(900, 800, 216))
        // came below the critical line: kept
        assertEquals(900L, NpuGate.marginAfterHeld(900, 700, 216))
        // never below the floor; unknown readings change nothing
        assertEquals(NpuGate.MARGIN_MIN_MB, NpuGate.marginAfterHeld(550, 3000, 216))
        assertEquals(NpuGate.MARGIN_MIN_MB, NpuGate.marginAfterHeld(NpuGate.MARGIN_MIN_MB, 3000, 216))
        assertEquals(900L, NpuGate.marginAfterHeld(900, -1, 216))
        // trouble: up a step, at most the ceiling
        assertEquals(1100L, NpuGate.marginAfterTrouble(900))
        assertEquals(NpuGate.MARGIN_MAX_MB, NpuGate.marginAfterTrouble(1400))
        // five loads that held with room: 900 -> 400 would be too far, it stops at the floor
        var m = NpuGate.MARGIN_START_MB
        repeat(5) { m = NpuGate.marginAfterHeld(m, 2000, 216) }
        assertEquals(NpuGate.MARGIN_MIN_MB, m)
        // then a failed try and it climbs again
        assertEquals(700L, NpuGate.marginAfterTrouble(m))
    }

    @Test fun aLoadWithRoomToSpareMeasuresNothing() {
        // the S25: the light model's copy beside the town found 1514 MB (2660 free, 3513 needed); later the writer's
        // main-model copy with 6260 free of 6617 needed... and one with plenty free that found about 0. Only loads that
        // needed Android's help count: the 0 from a load with room says nothing of what Android finds
        val t = listOf(Try(now - 3 * day, 2660, 3513, true, 1514), Try(now - 2 * day, 6260, 6617, true, 1551),
            Try(now - day, 7000, 6617, true, 0))
        assertEquals(1514L, NpuGate.reclaim(2804, 3751, 0, t, now).reclaimMb)
        // with only such loads: the first guess from the swap, as before any measure
        assertEquals(1024L, NpuGate.reclaim(2804, 3751, 2048, listOf(Try(now - day, 7000, 6617, true, 0)), now).reclaimMb)
    }

    @Test fun aFailedTryWithAsMuchFreeStopsTheNextForAWeek() {
        val failed = listOf(Try(now - day, 3000, 3630, false, why = "rpcmem_alloc failed"), Try(now - 2 * day, 3000, 3630, true, 1500))
        val v = NpuGate.reclaim(2900, 3630, 4000, failed, now)
        assertEquals(0L, v.reclaimMb)
        assertTrue(v.why.contains("rpcmem_alloc failed"))
        // with clearly more free than the failed try had, or a week later: counted again
        assertEquals(1500L, NpuGate.reclaim(3400, 3630, 4000, failed, now).reclaimMb)
        assertEquals(1500L, NpuGate.reclaim(2900, 3630, 4000, failed, now + 7 * day).reclaimMb)
        // a bigger copy failing (the main model's) says nothing about the light one's
        val big = listOf(Try(now - day, 3000, 6600, false, why = "valve"))
        assertEquals(1024L, NpuGate.reclaim(2900, 3630, 2048, big, now).reclaimMb)
    }

    @Test fun foundIsTheCopyLessWhatTheFreeCountFellBy() {
        // a 2.7 GB copy that took only 1.5 GB off the free count: Android found 1.2 GB (ended cached apps, swapped)
        assertEquals(1200L, NpuGate.found(3000, 1500, 2700))
        assertEquals(0L, NpuGate.found(3000, 200, 2700))
    }

    @Test fun triesRoundTrip() {
        val t = listOf(Try(5, 3000, 3630, false, -1, "a \"quote\"", true), Try(6, 2900, 3630, true, 1100))
        assertEquals(t, NpuGate.decode(NpuGate.encode(t)))
        assertEquals(emptyList<Try>(), NpuGate.decode("not json"))
    }

    @Test fun meminfoAndPressureParse() {
        val m = MemProbe.parseMeminfo("MemTotal:       11534336 kB\nMemFree:          512000 kB\nSwapFree:        4194304 kB\nHugePages_Total:       0\n")
        assertEquals(11264L, m["MemTotal"]); assertEquals(500L, m["MemFree"]); assertEquals(4096L, m["SwapFree"])
        val (some, full) = MemProbe.parsePsi("some avg10=1.25 avg60=0.50 avg300=0.10 total=123\nfull avg10=0.40 avg60=0.10 avg300=0.00 total=45\n")
        assertEquals(1.25, some!!, 1e-9); assertEquals(0.40, full!!, 1e-9)
        assertNull(MemProbe.parsePsi("").first)
    }

    private val light = FitReport.Step("light-npu", "light", 2463, "NPU", ok = true, runsOn = "NPU", loadMs = 6100,
        mem = listOf(410.0, 2380.0, 19.0, 50.0, 120.0), freeBeforeMb = 2900, freeMinMb = 650, freeLoadedMb = 1500, foundMb = 1169,
        warm = FitReport.Speed(300, 120.0, 40, 9.5, 900, 5200, "Busy as ever."))

    @Test fun thePlainWordsSayWhatLoadedAndWhatItMeansForPlay() {
        val r = FitReport.Report(0, 60_000, listOf(FitReport.Condition("town", freeBeforeMb = 2900, thresholdMb = 600, steps = listOf(light,
            FitReport.Step("main-npu", "main", 5417, "NPU", skipped = "would not fit")))))
        val t = FitReport.explain(r)
        assertTrue(t, t.startsWith("With the town loaded (2900 MB free, Android's threshold 600 MB): the light model's NPU copy loaded in 6.1 s, holding 2569 MB"))
        assertTrue(t, t.contains("Android found 1169 MB") && t.contains("generation 9.5 tok/s"))
        assertTrue(t, t.contains("the main model's NPU copy was not tried: would not fit"))
        assertTrue(t, t.endsWith("So in play the light model can answer on the NPU beside the town."))
    }

    @Test fun aReportCutShortSaysWhereTheGameEnded() {
        val failed = light.copy(ok = false, runsOn = "", error = "ggml-hex: rpcmem_alloc failed", valve = "500 MB free")
        val r = FitReport.Report(0, 1, listOf(FitReport.Condition("town", freeBeforeMb = 2900, thresholdMb = 600, steps = listOf(failed))),
            running = "loading the main model's NPU copy (main-npu)", lastFreeMb = 700)
        val t = FitReport.explain(r)
        assertTrue(t, t.contains("did not load (ggml-hex: rpcmem_alloc failed; the safety valve stopped it at 500 MB free"))
        assertTrue(t, t.contains("The test did not finish: the game ended while loading the main model's NPU copy (main-npu) (the last reading: 700 MB free)"))
        assertTrue(t, t.contains("did not fit as things stand"))
        val j = org.json.JSONObject(FitReport.json(r, mapOf("versionCode" to 1), TimeZone.getTimeZone("UTC")))
        assertEquals(false, j.getBoolean("complete"))
        assertEquals("npu-fit", j.getString("kind"))
        assertEquals(t, j.getString("inPlainWords"))
        assertEquals("npu/1970-01-01/000000.json", FitReport.path(0, TimeZone.getTimeZone("UTC")))
    }
}
