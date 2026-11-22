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

    @Test fun whatWasMeasuredCountsTheLeastOfTheLastThree() {
        val t = listOf(Try(now - 4 * day, 3000, 3630, true, 200), Try(now - 3 * day, 3000, 3630, true, 1800),
            Try(now - 2 * day, 3000, 3630, true, 1200, measure = true), Try(now - day, 3000, 3630, true, 2500))
        assertEquals(1200L, NpuGate.reclaim(2900, 3630, 0, t, now).reclaimMb)
        assertEquals(NpuGate.CAP_MB, NpuGate.reclaim(2900, 3630, 0, listOf(Try(now, 1, 1, true, 9000)), now).reclaimMb)
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
