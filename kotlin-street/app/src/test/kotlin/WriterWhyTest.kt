import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.dialogue.NightCount
import com.timerpg.street.night.NightInputs
import com.timerpg.street.night.NightReport
import com.timerpg.street.night.NightRules
import com.timerpg.street.night.NightStep
import com.timerpg.street.night.WriterEngine
import com.timerpg.street.night.WriterLoop
import com.timerpg.street.night.WriterStatus
import com.timerpg.street.night.WriterWhy
import com.timerpg.street.shot.Diagnostics
import com.timerpg.street.shot.UploadLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The writer says why it is not writing (the owner's run of 2026-11-23: minutes, no line, nothing on screen said why),
 * and the screenshot JSON's diagnostics. The writer's loop runs with a stub model, as on the phone ([WriterLoop] is the
 * session's own loop; the stub stands in for the dialogue engine).
 */
class WriterWhyTest {
    /** A stub engine: ready after [loadMs], then each line takes [msPerLine] and ends as [outcome] ("ok", "timeout",
     *  "rejected", "failed", "silent": the engine never starts a line). */
    private class Stub(val outcome: String, val loadMs: Long = 20_000, val msPerLine: Long = 120_000, val heldBy: String? = null) : WriterEngine {
        var now = 0L
        private var held = true
        private var c = NightCount()
        private var progress = 0L
        override var state = ModelState.LOADING
        override var detail = "Loading the model (5417 MB)…"
        override fun left() = if (state == ModelState.READY) 600 - c.lines else -1
        override fun count() = c
        override fun hold(on: Boolean, stopRunning: Boolean) { held = on }
        override fun reload() {}
        override fun held() = heldBy
        fun advance(dt: Long) {
            now += dt
            if (state != ModelState.READY) { if (now >= loadMs) { state = ModelState.READY; detail = "Ready." }; return }
            if (held || heldBy != null || outcome == "silent") return
            progress += dt
            while (progress >= msPerLine) {
                progress -= msPerLine
                c = when (outcome) {
                    "ok" -> c.copy(lines = c.lines + 1, genTokens = c.genTokens + 40, genMs = c.genMs + 10_000)
                    "timeout" -> c.copy(timedOut = c.timedOut + 1, genTokens = c.genTokens + 12, genMs = c.genMs + 110_000,
                        lastFailure = "a line took longer than 120 s (12 tokens in 120 s) and was given up")
                    "rejected" -> c.copy(rejected = c.rejected + 1, genTokens = c.genTokens + 40, genMs = c.genMs + 10_000)
                    else -> c.copy(failed = c.failed + 1, lastFailure = "the model failed on a line: decode failed")
                }
            }
        }
    }

    private fun run(e: Stub, minutes: Int): Pair<WriterLoop, List<WriterWhy>> {
        val loop = WriterLoop(NightRules(), e, start = 0)
        val seen = ArrayList<WriterWhy>()
        var t = 0L
        while (t < minutes * 60_000L) {
            val s = loop.tick(NightInputs(thermal = 0, headroom = .4f), t)
            if (s is NightStep.Stop) break
            loop.why("Ann: names no fact: “Hello there”").let { if (seen.lastOrNull() != it) seen += it }
            e.advance(5_000); t += 5_000
        }
        return loop to seen
    }

    @Test
    fun everyLineTimingOutIsNamedNotShownAsWriting() {
        // the silent failure: each line given up after 2 minutes overnight was counted nowhere (0 lines, 0 turned down),
        // and the screen said "Writing"
        val e = Stub("timeout")
        val (loop, seen) = run(e, 7)
        assertEquals("loading", seen.first().state)
        assertTrue(seen.first().reason, seen.first().reason.contains("Loading the model"))
        val last = loop.why()
        assertEquals("timing-out", last.state)
        assertTrue(last.reason, last.reason.contains("took too long"))
        assertTrue(e.count().timedOut >= 3)
        val r = NightReport.Run(start = 0, end = 7 * 60_000L, how = "writer", stopReason = "stopped in Writer mode", writingMs = loop.writingMs,
            timedOut = e.count().timedOut, genTokens = e.count().genTokens, lastFailure = e.count().lastFailure)
        val words = NightReport.explain(r)
        assertTrue(words, words.contains("${e.count().timedOut} took too long and were given up"))
        val j = org.json.JSONObject(NightReport.json(r, mapOf(), TimeZone.getTimeZone("UTC")))
        assertEquals(e.count().timedOut, j.getJSONObject("written").getInt("timedOut"))
    }

    @Test
    fun theOtherWaysToWriteNothing() {
        assertEquals("rejecting", run(Stub("rejected"), 6).first.why("Ann: names no fact").state)
        assertTrue(run(Stub("rejected"), 6).first.why("Ann: names no fact").reason.contains("the last: Ann: names no fact"))
        assertEquals("failing", run(Stub("failed"), 6).first.why().state)
        // ready, allowed to write, and no token at all (the engine never starts a line): said after 3 minutes
        val (silent, seen) = run(Stub("silent"), 6)
        assertEquals("no-tokens", silent.why().state)
        assertTrue(seen.any { it.state == "writing" })   // (before 3 minutes it may just be the first line)
        // held by the engine (a "memory" hold)
        assertEquals("held", run(Stub("ok", heldBy = "memory"), 3).first.why().state)
        // a model that never gets ready: its own words
        val never = Stub("ok", loadMs = Long.MAX_VALUE).apply { state = ModelState.ERROR; detail = "Could not load the model: alloc failed" }
        val w = run(never, 2).first.why()
        assertEquals("model-failed", w.state)
        assertTrue(w.reason.contains("alloc failed"))
        // and the one that writes is "writing", not stuck
        val good = run(Stub("ok", msPerLine = 30_000), 5).first.why()
        assertEquals("writing", good.state)
        assertFalse(good.stuck)
    }

    @Test
    fun restsAndStopsSayWhy() {
        val c = NightCount()
        assertEquals("waiting-charge", WriterWhy.of(NightStep.Rest("battery at 9% and not charging: charging to 15% first", NightStep.Kind.BATTERY),
            ModelState.READY, "", null, c, 10, 0).state)
        assertEquals("resting", WriterWhy.of(NightStep.Rest("too hot", NightStep.Kind.HOT), ModelState.READY, "", null, c, 10, 0).state)
        assertEquals("paused", WriterWhy.of(NightStep.Rest("paused", NightStep.Kind.USER), ModelState.READY, "", null, c, 10, 0).state)
        assertEquals(WriterWhy("stopped", "unplugged"), WriterWhy.of(NightStep.Stop("unplugged", true), ModelState.READY, "", null, c, 10, 0))
        assertEquals("planning", WriterWhy.of(NightStep.Write, ModelState.READY, "", null, c, -1, 0).state)
        assertEquals("complete", WriterWhy.of(NightStep.Write, ModelState.READY, "", null, c, 0, 0).state)
    }

    @Test
    fun theStatusTheGameReadsBack() {
        val s = WriterStatus(at = 1_000_000L, how = "writer", running = true, state = "timing-out", reason = "every line so far took too long",
            lines = 0, timedOut = 3, model = "main model on the NPU")
        assertEquals(s, WriterStatus.parse(s.json()))
        assertEquals(null, WriterStatus.parse("not json"))
        // fresh, then stale (the writer's process ended without a word)
        assertEquals(false, s.map(1_000_000L + 30_000)["stale"])
        assertEquals(true, s.map(1_000_000L + 3 * 60_000L)["stale"])
        assertTrue(s.line(1_000_000L + 3 * 60_000L).contains("no word from it"))
        assertTrue(s.line(1_000_000L + 10_000).contains("every line so far took too long"))
    }

    @Test
    fun screenshotDiagnostics() {
        val p = Diagnostics.pendingByKind(listOf("crashes~2026-11-23~021000-native.json", "night~2026-11-23~020100.json",
            "play~2026-11-23~013000-598.jsonl.gz", "play~2026-11-23~013000-598-p2.jsonl.gz", "talk~2026-11-23~013000-598.jsonl",
            "open-play~2026-11-23~021500-598.jsonl.gz", "crumbs.txt", "state.json", "trace~2026-11-23~020000-mark.perfetto-trace.gz"))
        assertEquals(mapOf("crashes" to 1, "night" to 1, "npu" to 0, "play" to 2, "talk" to 1, "trace" to 1, "recording" to 1), p)
        assertEquals("wireless", Diagnostics.plugName(4))
        assertEquals("cable (AC)", Diagnostics.plugName(1))
        assertEquals("cable (USB)", Diagnostics.plugName(2))
        assertEquals("none", Diagnostics.plugName(0))
        val utc = TimeZone.getTimeZone("UTC")
        val crumbs = (1..100).map { it * 1000L to "event $it" }
        val last = Diagnostics.lastCrumbs(crumbs, utc)
        assertEquals(40, last.size)
        assertEquals("01-01 00:01:40.000 event 100", last.last())
        assertEquals("night", UploadLog.kindOf("night/2026-11-23/020100.json"))
        assertEquals("play", UploadLog.kindOf("play/2026-11-23/013000-598.jsonl.gz"))
        val e = UploadLog.Entry(5L, "play", "play/2026-11-23/x.jsonl.gz", 422, "GitHub refused the file")
        assertEquals(e, UploadLog.Entry.parse(e.json()))
    }

    @Test
    fun playtestPartsAndOrphans() {
        assertEquals("play~2026-11-23~013000-598.jsonl.gz", com.timerpg.street.play.PlayLog.partName("play", "2026-11-23", "013000-598", 1, ".jsonl.gz"))
        assertEquals("talk~2026-11-23~013000-598-p3.jsonl", com.timerpg.street.play.PlayLog.partName("talk", "2026-11-23", "013000-598", 3, ".jsonl"))
        // a part's name goes up as play/<date>/<session>-p<n>.jsonl.gz
        val f = java.io.File("play~2026-11-23~013000-598-p2.jsonl.gz")
        assertEquals("play/2026-11-23/013000-598-p2.jsonl.gz", f.name.replace('~', '/'))
        val d = java.nio.file.Files.createTempDirectory("orphans").toFile()
        java.io.File(d, "open-play~2026-11-23~013000-598.jsonl.gz").writeText("x")
        java.io.File(d, "open-talk~2026-11-23~013000-598.jsonl").writeText("x")
        java.io.File(d, "night~2026-11-23~020100.json").writeText("x")
        assertEquals(2, com.timerpg.street.play.PlayLog.closeOrphans(d))
        assertEquals(setOf("play~2026-11-23~013000-598.jsonl.gz", "talk~2026-11-23~013000-598.jsonl", "night~2026-11-23~020100.json"), d.list()!!.toSet())
        assertEquals(1, com.timerpg.street.play.PlayUploads.pending(d, com.timerpg.street.play.PlayUploads.Kind.PLAY).size)
        d.deleteRecursively()
    }
}
