package com.timerpg.street.llm

import com.timerpg.street.shot.Json
import com.timerpg.street.shot.ShotRequests
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The NPU fit test's report ([NpuFitTest]): sent to the repository's `reports` branch as npu/<date>/<time>.json, beside
 * night/ and crashes/. Pure (unit tested in FitReportTest). Nothing the player said is in it: the test runs a fixed prompt
 * of its own, and its log lines are only the model runtime's.
 */
object FitReport {
    /** One run of the fixed prompt. */
    data class Speed(val promptTokens: Int, val prefillPerSec: Double, val genTokens: Int, val genPerSec: Double,
                     val firstTokenMs: Long, val totalMs: Long, val reply: String)

    /** One model copy tried on one device. Memory in MB (-1 not measured). */
    data class Step(
        val name: String,
        val model: String,
        val fileMb: Long,
        val device: String,
        val batch: Int = 512,
        /** Why it was not tried (null: it was). */
        val skipped: String? = null,
        val ok: Boolean = false,
        val error: String? = null,
        val runsOn: String = "",
        val loadMs: Long = 0,
        /** llama.cpp's own count (MB): weights mapped from the file, copied to the device, KV cache, recurrent state, compute. */
        val mem: List<Double> = emptyList(),
        val graphSplits: String? = null,
        val kv8: Boolean = false,
        val freeBeforeMb: Long = -1,
        val freeMinMb: Long = -1,
        val freeLoadedMb: Long = -1,
        val freeAfterUnloadMb: Long = -1,
        /** What Android found beyond its free count for the copy ([NpuGate.found]). */
        val foundMb: Long = -1,
        val valve: String? = null,
        val sawLow: Boolean = false,
        val watchLevel: Int = 0,
        val cold: Speed? = null,
        val warm: Speed? = null,
        val thermalBefore: Int = -1,
        val thermalAfter: Int = -1,
        val snapBefore: Map<String, Any?>? = null,
        val snapLoaded: Map<String, Any?>? = null,
    ) {
        /** Copied to the device plus its own buffers (MB). */
        val heldMb: Long get() = if (mem.size == 5) (mem[1] + mem[2] + mem[3] + mem[4]).toLong() else -1
    }

    /** One condition: the game with its town, the game without it, or Writer mode. */
    data class Condition(
        val name: String,
        val note: String = "",
        val before: Map<String, Any?> = emptyMap(),
        val freeBeforeMb: Long = -1,
        val thresholdMb: Long = -1,
        val residents: List<Triple<String, Long, String>> = emptyList(),
        /** Free memory gained by letting the dialogue model go, then the others ([dropped]). */
        val dialogueFreedMb: Long = -1,
        val droppedFreedMb: Long = -1,
        val dropped: List<String> = emptyList(),
        val freeReadyMb: Long = -1,
        val steps: List<Step> = emptyList(),
    )

    data class Report(
        val start: Long,
        val end: Long,
        val conditions: List<Condition>,
        /** The step under way when this was written: a report on disk with it set means the game ended during it. */
        val running: String? = null,
        val lastFreeMb: Long = -1,
        val gate: String? = null,
        val trims: List<Pair<Long, Int>> = emptyList(),
        val settings: Map<String, Any?> = emptyMap(),
        val log: List<String> = emptyList(),
    )

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun r1(d: Double) = Math.round(d * 10) / 10.0

    fun conditionName(n: String) = when (n) {
        "town" -> "With the town loaded"
        "no town" -> "With the town unloaded"
        "writer" -> "In Writer mode"
        else -> n
    }

    /** One step in words. */
    fun explainStep(s: Step): String {
        val what = "the ${s.model} model's ${s.device} copy" + (if (s.batch != 512) " (batch ${s.batch})" else "")
        if (s.skipped != null) return "$what was not tried: ${s.skipped}"
        if (!s.ok) return "$what did not load (${s.error ?: "no error given"}${s.valve?.let { "; the safety valve stopped it at $it" } ?: ""}; " +
            "${s.freeBeforeMb} MB free before, lowest ${s.freeMinMb} MB)"
        val ran = if (s.runsOn.isNotEmpty() && s.runsOn != s.device) " but ran on the ${s.runsOn}" else ""
        return buildString {
            append("$what loaded$ran in ${f1(s.loadMs / 1000.0)} s")
            if (s.heldMb > 0) append(", holding ${s.heldMb} MB")
            append(" (${s.freeBeforeMb} MB free before, ${s.freeLoadedMb} after, lowest ${s.freeMinMb}")
            if (s.foundMb > 0) append("; Android found ${s.foundMb} MB beyond its free count")
            append(")")
            (s.warm ?: s.cold)?.let { append(": prefill ${f1(it.prefillPerSec)} tok/s, generation ${f1(it.genPerSec)} tok/s, first token ${it.firstTokenMs} ms") }
        }
    }

    /**
     * The report in plain words: each condition, each copy and what came of it, then what it means for play. "With the
     * town loaded (2900 MB free): the light model's NPU copy loaded in 6.1 s ... So in play the light model can answer
     * on the NPU."
     */
    fun explain(r: Report): String {
        val parts = ArrayList<String>()
        for (c in r.conditions) {
            val head = "${conditionName(c.name)} (${c.freeBeforeMb} MB free, Android's threshold ${c.thresholdMb} MB" +
                (if (c.freeReadyMb >= 0 && c.freeReadyMb != c.freeBeforeMb) ", ${c.freeReadyMb} MB once the game's other models were let go" else "") + ")"
            val steps = c.steps.map { explainStep(it) }
            parts += head + ": " + (if (steps.isEmpty()) "nothing was tried" else steps.joinToString("; ")) + "."
        }
        r.running?.let { parts += "The test did not finish: the game ended while $it (the last reading: ${r.lastFreeMb} MB free). Android's own record of how it ended is in crashes/ if it kept one." }
        val town = r.conditions.firstOrNull { it.name == "town" }
        val light = town?.steps?.firstOrNull { it.model == "light" && it.device == "NPU" && it.batch == 512 }
        if (light != null) parts += when {
            light.ok && light.runsOn == "NPU" -> "So in play the light model can answer on the NPU beside the town."
            light.skipped != null -> "So it is not known yet whether the light model fits on the NPU beside the town."
            else -> "So beside the town the light model's NPU copy did not fit as things stand" +
                (town.steps.firstOrNull { it.model == "small" && it.device == "NPU" && it.ok }?.let { "; the small model's did" } ?: "") + "."
        }
        return parts.joinToString(" ")
    }

    fun path(ms: Long, tz: TimeZone): String = "npu/${fmt("yyyy-MM-dd", ms, tz)}/${fmt("HHmmss", ms, tz)}.json"
    private fun fmt(p: String, ms: Long, tz: TimeZone) = SimpleDateFormat(p, Locale.US).apply { timeZone = tz }.format(Date(ms))

    private fun speed(s: Speed?) = s?.let {
        linkedMapOf("promptTokens" to it.promptTokens, "prefillTokPerSec" to r1(it.prefillPerSec), "genTokens" to it.genTokens,
            "genTokPerSec" to r1(it.genPerSec), "firstTokenMs" to it.firstTokenMs, "totalMs" to it.totalMs, "reply" to it.reply)
    }

    fun json(r: Report, app: Map<String, Any?>, tz: TimeZone): String = Json.write(linkedMapOf(
        "kind" to "npu-fit",
        "start" to ShotRequests.isoTime(r.start, tz),
        "end" to ShotRequests.isoTime(r.end, tz),
        "complete" to (r.running == null),
        "runningWhenWritten" to r.running,
        "inPlainWords" to explain(r),
        "app" to app,
        "gate" to r.gate,
        "settings" to r.settings,
        "conditions" to r.conditions.map { c -> linkedMapOf(
            "name" to c.name, "note" to c.note, "freeBeforeMb" to c.freeBeforeMb, "androidThresholdMb" to c.thresholdMb,
            "memoryBefore" to c.before,
            "residents" to c.residents.map { (n, mb, note) -> linkedMapOf("name" to n, "mb" to mb, "note" to note) },
            "dialogueModelFreedMb" to c.dialogueFreedMb, "othersFreedMb" to c.droppedFreedMb, "othersLetGo" to c.dropped,
            "freeReadyMb" to c.freeReadyMb,
            "steps" to c.steps.map { s -> linkedMapOf(
                "name" to s.name, "model" to s.model, "fileMb" to s.fileMb, "device" to s.device, "batch" to s.batch,
                "skipped" to s.skipped, "loaded" to s.ok, "error" to s.error, "runsOn" to s.runsOn, "loadMs" to s.loadMs,
                "llamaMemMb" to if (s.mem.size == 5) linkedMapOf("weightsMapped" to r1(s.mem[0]), "weightsCopied" to r1(s.mem[1]),
                    "kv" to r1(s.mem[2]), "recurrentState" to r1(s.mem[3]), "compute" to r1(s.mem[4])) else null,
                "heldMb" to s.heldMb, "kv8bit" to s.kv8, "graphSplits" to s.graphSplits,
                "freeBeforeMb" to s.freeBeforeMb, "freeLowestMb" to s.freeMinMb, "freeLoadedMb" to s.freeLoadedMb,
                "freeAfterUnloadMb" to s.freeAfterUnloadMb, "androidFoundMb" to s.foundMb, "valve" to s.valve,
                "androidSaidLow" to s.sawLow, "memoryWatchLevelMax" to s.watchLevel,
                "cold" to speed(s.cold), "warm" to speed(s.warm),
                "thermalBefore" to s.thermalBefore, "thermalAfter" to s.thermalAfter,
                "memoryBefore" to s.snapBefore, "memoryLoaded" to s.snapLoaded,
                "inPlainWords" to explainStep(s),
            ) },
        ) },
        "trimMemory" to r.trims.map { (t, l) -> linkedMapOf("at" to ShotRequests.isoTime(t, tz), "level" to l) },
        "log" to r.log,
    ), pretty = true)
}
