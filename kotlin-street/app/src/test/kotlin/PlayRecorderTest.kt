package com.timerpg.street

import com.timerpg.street.play.Activity
import com.timerpg.street.play.FrameAgg
import com.timerpg.street.play.GzipJsonl
import com.timerpg.street.play.JsonLine
import com.timerpg.street.play.Patterns
import com.timerpg.street.play.Pct
import com.timerpg.street.play.PlayState
import com.timerpg.street.play.PlayUploads
import com.timerpg.street.play.Second
import com.timerpg.street.play.TalkTurn
import com.timerpg.street.play.Timeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** The playtest recorder's aggregation and files (play/): percentiles, the frame arrays, the lines, the size per hour. */
class PlayRecorderTest {
    private val parts = LongArray(12)
    private val ups = IntArray(5)

    private fun agg120(agg: FrameAgg, start: Long, n: Int, cpu: Float = 4f, gpu: Float = 6f): Long {
        var t = start
        for (k in 0 until n) {
            t += 8_333_333L
            agg.frame(t, t - 5_000_000L, cpu, gpu, k.toLong() + start / 1000, true, 1200, 250_000, 40, parts, ups, 0L)
        }
        return t
    }

    @Test fun percentilesByNearestRank() {
        val s = FloatArray(100) { it + 1f }
        assertEquals(50f, Pct.at(s, 100, 50))
        assertEquals(95f, Pct.at(s, 100, 95))
        assertEquals(99f, Pct.at(s, 100, 99))
        assertEquals(1f, Pct.at(floatArrayOf(1f), 1, 99))
        assertEquals(-1f, Pct.at(s, 0, 50))
        // 3 values: p50 is the 2nd, p95 and p99 the 3rd
        val t = floatArrayOf(1f, 2f, 3f)
        assertEquals(2f, Pct.at(t, 3, 50)); assertEquals(3f, Pct.at(t, 3, 95))
    }

    @Test fun aSecondOfFramesWithASpike() {
        val agg = FrameAgg()
        val out = Second()
        var t = agg120(agg, 1_000_000_000L, 100)
        // one late frame (50 ms) while moving, then frames again
        t += 50_000_000L
        agg.frame(t, t - 5_000_000L, 9f, 30f, 5_000_000_000L, true, 1200, 250_000, 40, parts, ups, 0L)
        agg120(agg, t, 19)
        agg.close(out)
        assertEquals(120, out.frames)
        assertEquals(1, out.spikes)
        assertEquals(8.33f, out.frame[0], 0.01f)
        assertEquals(50f, out.frame[3], 0.01f)
        assertEquals(9f, out.cpu[3], 0.001f)
        assertEquals(30f, out.gpu[3], 0.001f)
        assertEquals(1200, out.draws)
        assertEquals(250_000, out.trianglesMax)
        // the next second starts empty
        agg.close(out)
        assertEquals(0, out.frames)
        assertEquals(-1f, out.frame[0])
        assertEquals(-1, out.draws)
    }

    @Test fun idleGapsAreNotFramesAndGpuFramesCountOnce() {
        val agg = FrameAgg()
        val out = Second()
        var t = 1_000_000_000L
        // standing still: a frame now and then (nothing changed in between), the GPU telling of the same frame twice
        for (k in 0 until 4) {
            t += 400_000_000L
            agg.frame(t, t - 1_000_000L, 3f, 5f, 7L, false, -1, -1, -1, null, null, 0L)
        }
        agg.close(out)
        assertEquals(4, out.frames)
        assertEquals(0, out.flowing)
        assertEquals(0, out.spikes)
        assertEquals(1, out.gpuFrames)
        assertEquals(-1f, out.frame[0])
    }

    @Test fun touchToFrameIsSampled() {
        val agg = FrameAgg()
        val out = Second()
        val t = 10_000_000_000L
        agg.touch(t)
        // a frame begun before the touch does not count; the next one does
        agg.frame(t + 4_000_000L, t - 1_000_000L, 3f, -1f, -1L, true, -1, -1, -1, null, null, 0L)
        agg.frame(t + 12_000_000L, t + 5_000_000L, 3f, -1f, -1L, true, -1, -1, -1, null, null, 0L)
        agg.close(out)
        assertEquals(12f, out.touchMs, 0.01f)
        // another touch within 2 s is not sampled
        agg.touch(t + 500_000_000L)
        agg.frame(t + 520_000_000L, t + 510_000_000L, 3f, -1f, -1L, true, -1, -1, -1, null, null, 0L)
        agg.close(out)
        assertEquals(-1f, out.touchMs)
    }

    @Test fun moreFramesThanTheArraysHoldAreCountedNotStored() {
        val agg = FrameAgg(capacity = 16)
        val out = Second()
        agg120(agg, 0L, 40)
        agg.close(out)
        assertEquals(40, out.frames)
        assertEquals(16, out.flowing)
    }

    private fun allocated(): Long {
        // (the test JVM's com.sun.management.ThreadMXBean, by reflection: the tests compile against android.jar)
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        return m.invoke(bean, Thread.currentThread().id) as Long
    }

    @Test fun thePerFramePathAllocatesNothing() {
        val agg = FrameAgg()
        val out = Second()
        // warm up (class loading, the JIT)
        var t = agg120(agg, 0L, 5000)
        agg.close(out)
        val noise = -(allocated() - allocated())
        val before = allocated()
        for (s in 0 until 100) { t = agg120(agg, t, 120) }
        val after = allocated()
        val bytes = after - before - noise
        println("per-frame path: $bytes bytes allocated in 12,000 frames (measuring itself: $noise)")
        assertTrue("allocated $bytes bytes in 12,000 frames", bytes < 256)
    }

    @Test fun theSecondsLineAllocatesAlmostNothing() {
        val agg = FrameAgg(); val out = Second(); val st = state(); val j = JsonLine(2048)
        val dir = kotlin.io.path.createTempDirectory("play").toFile()
        val g = GzipJsonl(File(dir, "t.jsonl.gz"))
        var t = 0L
        repeat(200) { t = agg120(agg, t, 120); agg.close(out); g.add(Timeline.second(j, it * 1000L, out, st)) }
        // (the waiting buffer keeps its size after a flush: the 30 s flush in play)
        g.flush()
        val noise = -(allocated() - allocated())
        val before = allocated() + noise
        repeat(100) { t = agg120(agg, t, 120); agg.close(out); g.add(Timeline.second(j, it * 1000L, out, st)) }
        val per = (allocated() - before) / 100
        println("timeline second (close + line + add): $per bytes allocated a second")
        assertTrue("allocated $per bytes a second", per < 256)
        dir.deleteRecursively()
    }

    @Test fun perFrameCostIsTiny() {
        val agg = FrameAgg()
        val out = Second()
        var t = agg120(agg, 0L, 20_000)
        agg.close(out)
        val n = 1_200_000
        val t0 = System.nanoTime()
        for (s in 0 until n / 120) { t = agg120(agg, t, 120); agg.close(out) }
        val ns = (System.nanoTime() - t0).toDouble() / n
        println("per frame: %.0f ns (with the 1 Hz close)".format(ns))
        // the budget is 0.3 ms a frame; this is three orders under it
        assertTrue("$ns ns a frame", ns < 30_000)
    }

    @Test fun fixedDecimalsAndQuotes() {
        fun f(v: Double, d: Int) = StringBuilder().also { JsonLine.fixed(it, v, d) }.toString()
        assertEquals("8.33", f(8.3333, 2))
        assertEquals("1.5", f(1.50, 2))
        assertEquals("2", f(2.0, 1))
        assertEquals("-0.25", f(-0.25, 2))
        assertEquals("0", f(-0.0001, 2))
        assertEquals("1.05", f(1.05, 2))
        assertEquals("100", f(99.999, 2))
        assertEquals("null", f(Double.NaN, 1))
        val q = StringBuilder().also { JsonLine.quote(it, "a \"b\"\n\u0001é") }.toString()
        assertEquals("\"a \\\"b\\\"\\n\\u0001é\"", q)
    }

    private fun state() = PlayState().apply {
        town = "gamma"; street = "Civic Avenue"; hood = "Old Town"; wx = 1234.5f; wz = -88.25f; worldX = 512_345.0; worldZ = 233_100.0
        year = 1964; hour = 17.6f; camDist = 22f; camPitch = 38f; camYaw = 140f; camHeight = 13.5f
        activity = Activity.WALK; people = 14; ai = "dialogue"; aiQueue = 3
    }

    @Test fun theSecondsLineIsJson() {
        val agg = FrameAgg(); val out = Second()
        parts[1] = 2_000_000L; ups[2] = 1
        agg120(agg, 0L, 120)
        parts.fill(0); ups.fill(0)
        agg.close(out)
        val text = Timeline.second(JsonLine(), 5000L, out, state()).toString()
        assertTrue(text.endsWith("}\n"))
        val o = JSONObject(text)
        assertEquals("s", o.getString("k"))
        assertEquals(5000L, o.getLong("t"))
        assertEquals(120, o.getInt("f"))
        assertEquals(4, o.getJSONArray("ft").length())
        assertEquals(8.33, o.getJSONArray("ft").getDouble(0), 0.01)
        assertEquals("walk", o.getString("act"))
        assertEquals("gamma", o.getString("town"))
        assertEquals(1964, o.getInt("yr"))
        assertEquals(4, o.getJSONArray("cam").length())
        assertEquals(2.0, o.getJSONObject("parts").getDouble("build"), 0.01)
        assertEquals(120, o.getJSONObject("up").getInt("buildings"))
        assertEquals(1200, o.getInt("dr"))
        println("a second's line (${text.length} bytes): $text")
    }

    @Test fun gzipMembersReadBackAsOneStream() {
        val dir = kotlin.io.path.createTempDirectory("play").toFile()
        val f = File(dir, "s.jsonl.gz")
        val g = GzipJsonl(f)
        g.add("{\"a\":1}\n"); g.add("{\"b\":\"é ☃ 😀\"}\n")
        assertTrue(g.flush() > 0)
        assertEquals(0, g.flush())
        g.add("{\"c\":3}\n")
        g.flush()
        val lines = GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).readLines()
        assertEquals(listOf("{\"a\":1}", "{\"b\":\"é ☃ 😀\"}", "{\"c\":3}"), lines)
        dir.deleteRecursively()
    }

    /** A synthetic hour: a line a second (frames varying, walking about), heat and memory every 5 s, a flush every 30 s. */
    @Test fun anHourIsWellUnderTheBudget() {
        val dir = kotlin.io.path.createTempDirectory("play").toFile()
        val f = File(dir, "hour.jsonl.gz")
        val g = GzipJsonl(f)
        val agg = FrameAgg(); val out = Second(); val st = state(); val j = JsonLine(2048)
        val r = java.util.Random(7)
        var t = 0L
        val acts = arrayOf(Activity.WALK, Activity.STAND, Activity.RIDE, Activity.TALK, Activity.INSIDE)
        for (s in 0 until 3600) {
            for (k in 0 until 120) {
                t += (8_333_333L + r.nextInt(2_000_000) - 1_000_000)
                if (r.nextInt(500) == 0) t += 30_000_000L
                parts[0] = 300_000L + r.nextInt(200_000); parts[4] = 800_000L + r.nextInt(600_000); parts[10] = 2_000_000L + r.nextInt(1_000_000)
                parts[1] = if (r.nextInt(20) == 0) 3_000_000L else 0L
                ups[0] = if (r.nextInt(30) == 0) 1 else 0
                agg.frame(t, t - 6_000_000L, 4f + r.nextFloat() * 3f, 7f + r.nextFloat() * 5f, (s * 120 + k).toLong(), r.nextInt(4) != 0,
                    1100 + r.nextInt(300), 220_000 + r.nextInt(80_000), 30 + r.nextInt(30), parts, ups, if (ups[0] > 0) 200_000L else 0L)
            }
            st.wx += r.nextFloat() * 4f - 2f; st.wz += r.nextFloat() * 4f - 2f; st.worldX = 512_000.0 + st.wx; st.worldZ = 233_000.0 + st.wz
            st.hour = 8f + s / 3600f * 2f; st.camYaw = (st.camYaw + r.nextFloat() * 6f) % 360f; st.people = 8 + r.nextInt(10)
            if (s % 120 == 0) st.activity = acts[r.nextInt(acts.size)]
            agg.close(out)
            g.add(Timeline.second(j, s * 1000L, out, st))
            if (s % 5 == 1) g.add(j.begin().str("k", "pow").long("t", s * 1000L).int("th", 1).num("head", 0.4 + r.nextDouble() * 0.2, 3)
                .int("bat", 80 - s / 120).int("mA", -900 - r.nextInt(400)).int("mV", 4100 - s / 60).num("degC", 31 + r.nextDouble() * 4, 1)
                .bool("chg", false).end())
            if (s % 5 == 3) g.add(j.begin().str("k", "mem").long("t", s * 1000L).long("pss", 3100L + r.nextInt(200)).long("java", 400L + r.nextInt(50))
                .long("native", 1800L + r.nextInt(100)).long("gfx", 900L + r.nextInt(50)).long("code", 120).long("free", 4200L + r.nextInt(300))
                .long("lmkAt", 600).int("lvl", 0).num("psiSome", r.nextDouble(), 2).end())
            if (s % 30 == 29) g.flush()
        }
        g.flush()
        val perSecond = f.length() / 3600.0
        println("a synthetic hour: ${f.length() / 1024} KB compressed, %.0f bytes a second (%d lines)".format(perSecond, g.lines))
        assertTrue("$perSecond bytes a second", perSecond < 1024)
        assertEquals(3600L + 720 + 720, g.lines)
        dir.deleteRecursively()
    }

    @Test fun patternsCountActivitiesAndCells() {
        val p = Patterns(cell = 16f)
        val s = state()
        s.wx = 5f; s.wz = -3f
        repeat(3) { p.second(s) }
        s.wx = 40f; s.activity = Activity.RIDE
        p.second(s)
        p.count("talk:spoken"); p.count("talk:spoken")
        val o = JSONObject(p.json())
        assertEquals(3, o.getJSONObject("act").getInt("walk"))
        assertEquals(1, o.getJSONObject("act").getInt("ride"))
        val cells = o.getJSONObject("heat").getJSONArray("gamma")
        assertEquals(2, cells.length())
        // the busiest first: cell (0, -1) for 3 s
        assertEquals(0, cells.getJSONArray(0).getInt(0)); assertEquals(-1, cells.getJSONArray(0).getInt(1)); assertEquals(3, cells.getJSONArray(0).getInt(2))
        assertEquals(2, o.getJSONObject("use").getInt("talk:spoken"))
        assertEquals(-7, Patterns.cz(Patterns.key(3, -7))); assertEquals(-3, Patterns.cx(Patterns.key(-3, 7)))
    }

    @Test fun uploadRules() {
        val P = PlayUploads.Kind.PLAY; val T = PlayUploads.Kind.TALK; val X = PlayUploads.Kind.TRACE
        assertTrue(PlayUploads.allowed(P, wifi = true, charging = false))
        assertTrue(PlayUploads.allowed(T, wifi = false, charging = true))
        assertFalse(PlayUploads.allowed(P, wifi = false, charging = false))
        assertFalse(PlayUploads.allowed(X, wifi = true, charging = false))
        assertFalse(PlayUploads.allowed(X, wifi = false, charging = true))
        assertTrue(PlayUploads.allowed(X, wifi = true, charging = true))
        // the network state unreadable (0.1.638 and before: no ACCESS_NETWORK_STATE): the small files go, traces do not
        assertTrue(PlayUploads.allowed(P, wifi = null, charging = false))
        assertTrue(PlayUploads.allowed(T, wifi = null, charging = false))
        assertFalse(PlayUploads.allowed(X, wifi = null, charging = true))
        assertEquals(X, PlayUploads.kindOf("trace~2026-11-23~101500-mark.perfetto-trace.gz"))
        assertEquals(null, PlayUploads.kindOf("open-play~2026-11-23~101500-900.jsonl.gz"))
    }

    @Test fun aTurnsLine() {
        val t = TalkTurn("n12", "Ada Lovell", "spoken", "Where's the station?").apply {
            town = "gamma"; building = "Bakery"; year = 1964; hour = 9.25f; intent = "directions"; topic = "The town"; sheet = "n12/directions"
            memories = 2; asrBy = "the phone's recognizer"; asrMs = 420; asrAudioMs = 1300; asrConfidence = 0.91f
            reply = "Two blocks down, past the bank."; source = TalkTurn.sourceOf("live"); model = "main model on the NPU"; unit = "NPU"
            waitMs = 10; firstTokenMs = 900; lastTokenMs = 4900; tokens = 20; tries = 1; valid = true
            finish()
        }
        val o = JSONObject(t.json("101500-900"))
        assertEquals("turn", o.getString("k"))
        assertEquals("Where's the station?", o.getString("said"))
        assertEquals("live", o.getString("source"))
        assertEquals(5.0, o.getDouble("tokPerSec"), 0.01)
        assertEquals(0.91, o.getJSONObject("asr").getDouble("confidence"), 0.001)
        assertEquals("n12/directions", o.getJSONObject("facts").getString("sheet"))
        assertFalse(o.getBoolean("walkedAway"))
        assertNotNull(o.optString("t"))
        assertEquals("bank", TalkTurn.sourceOf("ready"))
        assertFalse(t.json("x").contains('\n'))
    }
}
