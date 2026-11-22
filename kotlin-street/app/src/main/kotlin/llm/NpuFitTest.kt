package com.timerpg.street.llm

import android.content.Context
import android.os.SystemClock
import com.timerpg.street.dialogue.DialogueEngine
import com.timerpg.street.dialogue.ModelFiles
import com.timerpg.street.shot.DeviceFacts
import com.timerpg.street.shot.Reports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.TimeZone

/**
 * The NPU fit test (the AI panel's and Writer mode's button): does each model's copy on the NPU (and the GPU) load on
 * this phone, in this condition, and how fast does it run? It measures what the device gate ([NpuGate]) can only guess:
 * the free memory by several measures before, during and after each load, what each of the game's models holds, whether
 * each copy loads and the runtime's own error if not, the load time, prefill and generation speed on a fixed prompt of
 * its own, the heat, and which device really ran it. The report goes to the repository's `reports` branch
 * (npu/<date>/<time>.json, [FitReport]), in plain words too.
 *
 * Conditions, in one tap: in the game, with the town loaded, then with it unloaded (the caller's [Hooks.prepare] switches
 * to the old crossroads, and back at the end); in Writer mode, there. The report is written to disk before every step,
 * naming the step under way, so a run the low-memory killer ends is still a report (sent on the next start). Every load
 * is watched by [LoadValve]; the main model's copy is tried only where the light one's numbers say it can fit.
 * What Android found for each copy calibrates the gate ([NpuGate.record] with `measure`).
 */
object NpuFitTest {
    data class Status(val running: Boolean = false, val progress: String? = null, val last: String? = null)

    /** The caller's part: [prepare] puts the phone in a condition (blocking; returns a note), [finish] restores the game. */
    class Hooks(val prepare: (String) -> String = { "" }, val finish: () -> Unit = {})

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    @Volatile private var current: File? = null

    /** [f] is the report of a test still under way (not to be sent yet). */
    fun writing(f: File) = _status.value.running && current?.name == f.name

    /**
     * The fixed prompts (two, different from their first token, so the second is a full prefill too: the warm number).
     * A person of our own making and an ordinary question: nothing from the player.
     */
    private fun prompt(n: Int) = "<|im_start|>system\n" +
        (if (n == 0) "You are Mara Lindqvist, 46, who runs the bakery on Elm Street." else "You are Tom Okafor, 38, who drives the morning bus.") +
        " You speak to a neighbour in a small town. Speak in one or two short sentences, plainly, in the first person. " +
        "Never invent facts beyond these. Facts: you were born in this town; your mother taught at the school; you open at six " +
        "every morning; the river flooded the lower streets two springs ago and the town rebuilt the footbridge; you like the " +
        "summer fair and dislike the new parking rules; your brother moved to the capital last year and you miss him.\n" +
        "<|im_end|>\n<|im_start|>user\nHow have things been for you lately?<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    fun start(ctx: Context, conditions: List<String>, hooks: Hooks = Hooks()) {
        if (_status.value.running) return
        val app = ctx.applicationContext
        _status.value = Status(true, "Starting…", _status.value.last)
        Thread({
            val text = try { run(app, conditions, hooks) } catch (t: Throwable) {
                com.timerpg.street.shot.Crumbs.add("npu fit test failed: ${t.javaClass.simpleName}: ${t.message}")
                "The fit test stopped with an error: ${t.javaClass.simpleName}: ${t.message}"
            }
            _status.value = Status(false, null, text)
            com.timerpg.street.shot.ShotUploader.sendNight(app)
        }, "npu-fit-test").apply { isDaemon = true }.start()
    }

    private fun say(s: String) { _status.value = _status.value.copy(progress = s); com.timerpg.street.shot.Crumbs.add("npu fit test: $s") }

    private fun run(app: Context, conditions: List<String>, hooks: Hooks): String {
        val tz = TimeZone.getDefault()
        val t0 = System.currentTimeMillis()
        val path = FitReport.path(t0, tz)
        current = File(Reports.dir(app), path.replace('/', '~'))
        val engine = if (DialogueEngine.sharedExists) DialogueEngine.shared(app) else null
        val conds = ArrayList<FitReport.Condition>()
        var lastFree = -1L
        var gateLine: String? = null
        fun report(running: String?) = FitReport.Report(t0, System.currentTimeMillis(), conds.toList(), running, lastFree, gateLine,
            MemProbe.Trims.seen.filter { it.first >= t0 }, settings(app, engine), log())
        fun save(running: String?): FitReport.Report {
            val r = report(running)
            runCatching { Reports.putNpu(app, path, FitReport.json(r, DeviceFacts.app(), tz)) }
            return r
        }
        // (the NPU's code path and settings before the runtime loads, as every start of the app does)
        com.timerpg.street.AppInit.native(app)
        val probe = NativeLlm()
        probe.unavailable?.let { why ->
            conds += FitReport.Condition("none", note = "the model runtime did not load: $why")
            return FitReport.explain(save(null)).also { current = null }
        }
        val acc = probe.accelerators
        probe.close()
        var held = false
        try {
            for ((ci, c) in conditions.withIndex()) {
                say("${FitReport.conditionName(c)}: getting ready")
                save("getting ready for the condition \"$c\"")
                val note = runCatching { hooks.prepare(c) }.getOrElse { "could not prepare: ${it.message}" }
                val before = MemProbe.snap(app, withPss = true)
                lastFree = before.availMb
                val residents = ModelRoom.residents()
                // the game's dialogue model let go (once: it stays held for the rest of the test)
                var dialogueFreed = -1L
                if (!held && engine != null) {
                    held = engine.fitTestHold()
                    Thread.sleep(1500)
                    dialogueFreed = MemProbe.snap(app).availMb - before.availMb
                }
                // and the models that may go for it (speech, memory, voices), as the game does when a copy is short of room
                val beforeDrop = MemProbe.snap(app).availMb
                val (_, went) = ModelRoom.dropFor("the NPU fit test")
                if (went.isNotEmpty()) Thread.sleep(1500)
                val ready = MemProbe.snap(app)
                var cond = FitReport.Condition(c, note, before.map(), before.availMb, before.thresholdMb, residents, dialogueFreed,
                    ready.availMb - beforeDrop, went, ready.availMb)
                conds += cond
                fun put(s: FitReport.Step) { cond = cond.copy(steps = cond.steps + s); conds[conds.size - 1] = cond }

                val light = ModelFiles.live(app)
                val small = ModelFiles.liveSmall(app)
                val bank = engine?.bankFileForNpu() ?: ModelFiles.file(app)
                val first = step(app, engine, acc, "light-npu", "light", light, NativeLlm.NPU, 512, ::say, ::save)
                put(first)
                gateLine = NpuGate.verdict(app, ready.availMb, (light.length() shr 20) + 150 + Ram.MARGIN_MB, swapFree()).let { "${it.reclaimMb} MB: ${it.why}" }
                if (first.ok) put(step(app, engine, acc, "light-npu-batch256", "light", light, NativeLlm.NPU, 256, ::say, ::save))
                if (small.isFile) put(step(app, engine, acc, "small-npu", "small", small, NativeLlm.NPU, 512, ::say, ::save))
                put(step(app, engine, acc, "light-gpu", "light", light, NativeLlm.GPU, 512, ::say, ::save))
                if (ci == 0) put(step(app, engine, acc, "light-cpu", "light", light, NativeLlm.CPU, 512, ::say, ::save))
                // the main model's copy: tried where it can fit by the light one's numbers (Writer mode: always, it is its job)
                val found = cond.steps.filter { it.ok && it.device != "CPU" }.maxOfOrNull { it.foundMb }?.coerceAtLeast(0) ?: 0L
                val own = first.heldMb.takeIf { it > 0 }?.let { it - (light.length() shr 20) }?.coerceAtLeast(100) ?: 300L
                val needMain = (bank.length() shr 20) + own + 300
                val freeNow = MemProbe.snap(app).availMb
                if (c != "writer" && freeNow + found < needMain)
                    put(FitReport.Step("main-npu", "main", bank.length() shr 20, "NPU",
                        skipped = "would not fit: about $needMain MB needed, $freeNow MB free plus the $found MB Android found for the light model's copy"))
                else put(step(app, engine, acc, "main-npu", "main", bank, NativeLlm.NPU, 512, ::say, ::save))
                lastFree = MemProbe.snap(app).availMb
            }
        } finally {
            say("Putting the game back")
            save("putting the game back")
            runCatching { hooks.finish() }
            if (held) engine?.fitTestRelease()
        }
        val r = save(null)
        current = null
        return FitReport.explain(r)
    }

    private fun step(app: Context, engine: DialogueEngine?, acc: Int, name: String, model: String, file: File, dev: Int, batch: Int,
                     say: (String) -> Unit, save: (String?) -> FitReport.Report): FitReport.Step {
        val devName = listOf("CPU", "GPU", "NPU")[dev]
        val base = FitReport.Step(name, model, file.length() shr 20, devName, batch)
        if (!file.isFile) return base.copy(skipped = "the file is not on the phone (${file.name})")
        if (dev == NativeLlm.NPU && acc and 2 == 0) return base.copy(skipped = "this build or phone has no NPU backend")
        if (dev == NativeLlm.GPU && acc and 1 == 0) return base.copy(skipped = "this build or phone has no GPU backend")
        say("loading the $model model on the $devName" + if (batch != 512) " (batch $batch)" else "")
        save("loading the $model model's $devName copy ($name)")
        val llm = NativeLlm()
        llm.memoryCheck = false
        llm.device = dev
        llm.kvQ8 = engine?.kvQ8Wanted ?: true
        llm.batch = batch
        llm.mtp = 0
        val sb = MemProbe.snap(app, withPss = true)
        val valve = LoadValve.start(app) { llm.cancel() }
        val t = SystemClock.elapsedRealtime()
        val ok = AiLane.run("fit test") { runCatching { llm.load(file.path, 0, 1024) }.getOrDefault(false) }
        val loadMs = SystemClock.elapsedRealtime() - t
        valve.stop()
        val sl = MemProbe.snap(app, withPss = true)
        var s = base.copy(ok = ok, error = if (ok) null else llm.error, runsOn = if (ok) listOf("CPU", "GPU", "NPU").getOrElse(llm.runsOn) { "?" } else "",
            loadMs = if (ok) llm.stats.loadMs.takeIf { it > 0 } ?: loadMs else loadMs, mem = llm.loadMem.toList(), graphSplits = llm.loadInfo,
            kv8 = ok && llm.kvQ8Used, freeBeforeMb = sb.availMb, freeMinMb = valve.minFreeMb.takeIf { it != Long.MAX_VALUE } ?: sl.availMb,
            freeLoadedMb = sl.availMb, valve = valve.hit, sawLow = valve.sawLow, watchLevel = valve.maxLevel,
            thermalBefore = sb.thermal, snapBefore = sb.map(), snapLoaded = sl.map())
        if (ok && dev != NativeLlm.CPU) s = s.copy(foundMb = NpuGate.found(sb.availMb, sl.availMb, s.heldMb))
        if (ok) {
            say("the $model model on the ${s.runsOn}: the fixed prompt")
            save("running the fixed prompt on the $model model's $devName copy ($name)")
            s = s.copy(cold = gen(llm, prompt(0)), warm = gen(llm, prompt(1)))
        }
        AiLane.run("fit test") { llm.close() }
        Thread.sleep(2000)
        s = s.copy(freeAfterUnloadMb = MemProbe.snap(app).availMb, thermalAfter = MemProbe.snap(app).thermal)
        // the gate learns what Android found for a copy on the device (and that it failed, with how much free)
        if (dev != NativeLlm.CPU && batch == 512)
            NpuGate.record(app, NpuGate.Try(System.currentTimeMillis(), s.freeBeforeMb, (file.length() shr 20) + 150 + Ram.MARGIN_MB, ok,
                s.foundMb, if (ok) "" else (s.valve?.let { "stopped by the safety valve" } ?: s.error ?: "load failed"), measure = true))
        return s
    }

    private fun gen(llm: NativeLlm, prompt: String): FitReport.Speed {
        val t = SystemClock.elapsedRealtime()
        // (a generation that hangs is cut after two minutes)
        val guard = Thread({ try { Thread.sleep(120_000); llm.cancel() } catch (e: InterruptedException) { } }, "fit-guard").apply { isDaemon = true; start() }
        val text = AiLane.run("fit test") { llm.generate(prompt, 48, .3f) { true } }
        guard.interrupt()
        val st = llm.stats
        return FitReport.Speed(st.promptTokens - st.reusedTokens, st.prefillPerSec.toDouble(), st.genTokens, st.genPerSec.toDouble(),
            st.firstTokenMs, SystemClock.elapsedRealtime() - t, text.take(240).ifEmpty { llm.lastGenerateError?.let { "($it)" } ?: "" })
    }

    private fun swapFree(): Long = runCatching { MemProbe.parseMeminfo(File("/proc/meminfo").readText())["SwapFree"] }.getOrNull() ?: 0L

    private fun settings(app: Context, engine: DialogueEngine?): Map<String, Any?> = linkedMapOf(
        "npuSetting" to com.timerpg.street.NpuTune.chosen(app),
        "env" to System.getenv().filterKeys { it.startsWith("GGML_") }.toSortedMap(),
        "kv8bit" to (engine?.kvQ8Wanted ?: true),
        "batch" to (engine?.batchWanted ?: 512),
        "gpuFallback" to (engine?.gpuFallbackWanted ?: true),
        "gateOn" to NpuGate.enabled(app),
        "gateTries" to NpuGate.tries(app).takeLast(6).map { "${if (it.ok) "held" else "failed"} ${it.freeMb} MB free, ${it.needMb} needed, found ${it.foundMb}${if (it.measure) " (fit test)" else ""}${if (it.why.isNotEmpty()) ": ${it.why}" else ""}" },
        "process" to runCatching { android.app.Application.getProcessName() }.getOrNull(),
    )

    /** The model runtime's own log lines from this process (never anything else: the player's words stay out). */
    private fun log(): List<String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "--pid=${android.os.Process.myPid()}"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        val keep = listOf("ggml", "hexagon", "htp", "llama", "llm_load", "NativeLlm", "load valve", "opencl", "rpcmem", "fastrpc")
        lines.filter { l -> keep.any { l.contains(it, ignoreCase = true) } }.takeLast(150)
    } catch (t: Throwable) { emptyList() }
}
