package com.timerpg.street.night

import com.timerpg.street.dialogue.BankPlan
import com.timerpg.street.dialogue.ReplyCache
import com.timerpg.street.shot.Json
import com.timerpg.street.shot.ShotRequests
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The overnight writer's and Writer mode's summary: kept for the AI panel ("last run") and sent as a report to the repository's
 * `reports` branch (night/<date>/<time>.json, beside crashes/) so Claude can see the phone's real overnight throughput.
 * Pure (tested on the JVM). Privacy: the player's words never go in, only counts and theme labels, unless the owner
 * switched on sending them ([Run.themes] is built with them then).
 */
object NightReport {
    /** What the phone's memory allowed (MB; -1 not measured): [MemoryWatch][com.timerpg.street.llm.MemoryWatch]'s
     *  reading at the start, what closing the game's process freed, the free memory just before the load and Android's
     *  threshold, what each model's copy on its device needs, and the free memory once loaded. */
    data class Memory(
        val freeAtStartMb: Long = -1,
        val freedByGameMb: Long = -1,
        val freeBeforeLoadMb: Long = -1,
        val thresholdMb: Long = -1,
        val mainNeedMb: Long = -1,
        val lightNeedMb: Long = -1,
        val freeAfterLoadMb: Long = -1,
    )

    /** A line written this session: who says it, about what, the line ([text] null when it stays on the phone: lines
     *  steered by the player's own words), and how ("line", "variant", "rewrite"). */
    data class Written(val who: String, val topic: String, val text: String?, val how: String)

    /** One run, from start to stop. Times in ms; [writingMs] the time it was allowed to write with the model ready. */
    data class Run(
        val start: Long,
        val end: Long,
        /** "service" (a foreground service), "job" (inside a background job: Android did not allow the service) or
         *  "writer" (Writer mode, started from its own screen). */
        val how: String,
        val stopReason: String,
        val lines: Int = 0,
        val variants: Int = 0,
        val rejected: Int = 0,
        val genTokens: Long = 0,
        val genMs: Long = 0,
        val prefillTokens: Long = 0,
        val prefillMs: Long = 0,
        val writingMs: Long = 0,
        val coolMs: Long = 0,
        val hotMs: Long = 0,
        val burstRestMs: Long = 0,
        val batteryWaitMs: Long = 0,
        val coolDowns: Int = 0,
        val hotPauses: Int = 0,
        val maxThermal: Int = -1,
        val batteryStart: Int = -1,
        val batteryEnd: Int = -1,
        val runsOn: String = "",
        val bank: ReplyCache.Bank = ReplyCache.Bank(),
        /** The player's themes ([com.timerpg.street.dialogue.Steer.summary]): labels and counts. */
        val themes: List<Map<String, Any?>> = emptyList(),
        /** Questions answered by day: day to [ready, live, template]. */
        val answers: List<Pair<String, IntArray>> = emptyList(),
        val userPausedMs: Long = 0,
        /** Time the model was ready and the rules let it write but the engine held it (by [heldBy], e.g. "memory"). */
        val heldMs: Long = 0,
        val heldBy: String? = null,
        /** The engine at the end: its state and words, the last line the check turned down and why. */
        val engineAtEnd: String? = null,
        val lastRejected: String? = null,
        val gameWaitMs: Long = 0,
        val loadingMs: Long = 0,
        /** Which model wrote ("main model on the NPU"), empty if none loaded. */
        val model: String = "",
        /** Why the model was not ready, when it was not. */
        val modelProblem: String? = null,
        val memory: Memory = Memory(),
        val pluggedStart: Boolean? = null,
        val pluggedEnd: Boolean? = null,
        /** Lines still to write at the end, and the estimated time for them at this run's pace (ms; null: no pace yet). */
        val left: Int = -1,
        val etaMs: Long? = null,
        val queue: BankPlan.Queue? = null,
        val recent: List<Written> = emptyList(),
    ) {
        val written: Int get() = lines + variants
        val genPerSec: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
        val prefillPerSec: Double get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
        /** Lines per hour of the whole run, and per hour of writing (the pauses left out). */
        val linesPerHour: Double get() = if (end > start) written * 3_600_000.0 / (end - start) else 0.0
        val linesPerWritingHour: Double get() = if (writingMs > 0) written * 3_600_000.0 / writingMs else 0.0
        val kind: String get() = if (how == "writer") "writer" else "night"
    }

    /**
     * The run in plain words: what was written, where the time went when it was not writing (each wait of a minute or
     * more), and why it stopped. "Nothing was written: 75 min waiting for the battery (9% at the start, 21% at the
     * end). It stopped: unplugged."
     */
    fun explain(r: Run): String {
        fun d(ms: Long) = WriterLoop.duration(ms)
        val head = if (r.written > 0) "Wrote ${r.lines} lines and ${r.variants} variants with the ${r.model.ifEmpty { "model" }} in ${d(r.writingMs)} of writing (${d(r.end - r.start)} in all)"
            else "Nothing was written"
        val why = ArrayList<String>()
        val min = 60_000L
        if (r.gameWaitMs >= min) why += "${d(r.gameWaitMs)} closing the game to free its memory"
        if (r.loadingMs >= min) why += "${d(r.loadingMs)} waiting for the model to load" + (r.modelProblem?.let { " ($it)" } ?: "")
        else if (r.written == 0 && r.modelProblem != null) why += "the model was not ready (${r.modelProblem})"
        if (r.batteryWaitMs >= min) why += "${d(r.batteryWaitMs)} waiting for the battery (${r.batteryStart}% at the start, ${r.batteryEnd}% at the end" +
            (if (r.pluggedStart == false) ", on battery" else "") + ")"
        if (r.hotMs >= min) why += "${d(r.hotMs)} paused because the phone was too hot (thermal status up to ${r.maxThermal})"
        if (r.coolMs >= min) why += "${d(r.coolMs)} resting between short bursts because the phone was warm (thermal status up to ${r.maxThermal})"
        if (r.userPausedMs >= min) why += "${d(r.userPausedMs)} paused by you"
        if (r.heldMs >= min) why += "${d(r.heldMs)} with the model ready but held by the engine (${r.heldBy ?: "?"})"
        if (r.written == 0 && r.writingMs >= min) why += "${d(r.writingMs)} of writing time without finishing a line" +
            (if (r.genTokens == 0L) " (the model generated no token at all: the engine never started a line; at the end it was ${r.engineAtEnd ?: "not known"})" else "") +
            (if (r.rejected > 0) " (${r.rejected} turned down by the check${r.lastRejected?.let { ", the last: $it" } ?: ""})" else "") + (if (r.runsOn == "CPU") ", on the CPU" else "")
        return buildString {
            append(head)
            if (why.isNotEmpty()) append(if (r.written > 0) "; " else ": ").append(why.joinToString("; "))
            append(". It stopped: ").append(r.stopReason).append('.')
        }
    }

    fun path(ms: Long, tz: TimeZone): String =
        "night/${fmt("yyyy-MM-dd", ms, tz)}/${fmt("HHmmss", ms, tz)}.json"

    private fun fmt(p: String, ms: Long, tz: TimeZone) = SimpleDateFormat(p, Locale.US).apply { timeZone = tz }.format(Date(ms))

    private fun r1(d: Double) = Math.round(d * 10) / 10.0
    private fun min(ms: Long) = r1(ms / 60_000.0)

    /** The report: [app] the build and phone ([com.timerpg.street.shot.DeviceFacts.app]). */
    fun json(r: Run, app: Map<String, Any?>, tz: TimeZone): String = Json.write(linkedMapOf(
        "kind" to r.kind,
        "start" to ShotRequests.isoTime(r.start, tz),
        "end" to ShotRequests.isoTime(r.end, tz),
        "minutes" to min(r.end - r.start),
        "how" to r.how,
        "stopReason" to r.stopReason,
        "inPlainWords" to explain(r),
        "app" to app,
        "written" to linkedMapOf(
            "lines" to r.lines, "variants" to r.variants, "rejected" to r.rejected,
            "linesPerHour" to r1(r.linesPerHour), "linesPerWritingHour" to r1(r.linesPerWritingHour),
        ),
        "speed" to linkedMapOf(
            "model" to r.model, "runsOn" to r.runsOn, "genTokens" to r.genTokens, "genTokPerSec" to r1(r.genPerSec),
            "prefillTokens" to r.prefillTokens, "prefillTokPerSec" to r1(r.prefillPerSec),
        ),
        "time" to linkedMapOf(
            "writingMin" to min(r.writingMs), "coolingMin" to min(r.coolMs), "hotPausedMin" to min(r.hotMs),
            "burstRestMin" to min(r.burstRestMs), "batteryWaitMin" to min(r.batteryWaitMs),
            "pausedByYouMin" to min(r.userPausedMs), "heldMin" to min(r.heldMs), "heldBy" to r.heldBy, "closingGameMin" to min(r.gameWaitMs), "modelLoadMin" to min(r.loadingMs),
        ),
        "model" to linkedMapOf(
            "loaded" to r.model, "problem" to r.modelProblem, "engineAtEnd" to r.engineAtEnd, "lastRejected" to r.lastRejected,
            "freeAtStartMb" to r.memory.freeAtStartMb, "freedByClosingGameMb" to r.memory.freedByGameMb,
            "freeBeforeLoadMb" to r.memory.freeBeforeLoadMb, "androidThresholdMb" to r.memory.thresholdMb,
            "mainCopyNeedsMb" to r.memory.mainNeedMb, "lightCopyNeedsMb" to r.memory.lightNeedMb, "freeAfterLoadMb" to r.memory.freeAfterLoadMb,
        ),
        "heat" to linkedMapOf("coolDowns" to r.coolDowns, "hotPauses" to r.hotPauses, "maxThermalStatus" to r.maxThermal),
        "battery" to linkedMapOf("start" to r.batteryStart, "end" to r.batteryEnd, "pluggedAtStart" to r.pluggedStart, "pluggedAtEnd" to r.pluggedEnd),
        "bank" to linkedMapOf(
            "prompts" to r.bank.prompts, "ready" to r.bank.ready, "templates" to r.bank.templates, "missing" to r.bank.missing,
            "lines" to r.bank.variants, "variantsDone" to r.bank.variantsDone, "stale" to r.bank.stale, "years" to r.bank.years,
            "light" to r.bank.light, "left" to r.left, "etaMin" to r.etaMs?.let { min(it) },
        ),
        "queue" to r.queue?.let { q -> linkedMapOf("byYear" to q.byYear.toMap(), "byPerson" to q.byPerson.toMap(), "byTopic" to q.byTopic.toMap()) },
        "recent" to r.recent.map { w -> linkedMapOf("who" to w.who, "topic" to w.topic, "how" to w.how, "text" to (w.text ?: "(kept on the phone: steered by your own words)")) },
        "player" to linkedMapOf(
            "themes" to r.themes,
            "answersByDay" to r.answers.map { (d, a) ->
                val n = a.sum()
                linkedMapOf("day" to d, "ready" to a[0], "live" to a[1], "template" to a[2], "readyShare" to if (n > 0) r1(a[0] * 100.0 / n) else null)
            },
        ),
    ), pretty = true)

    /** One line for the AI panel and the breadcrumbs. */
    fun summary(r: Run, tz: TimeZone = TimeZone.getDefault()): String =
        "${if (r.kind == "writer") "Writer mode " else ""}${fmt("EEE HH:mm", r.start, tz)}-${fmt("HH:mm", r.end, tz)}: ${r.lines} lines + ${r.variants} variants " +
            "(${r1(r.linesPerHour)}/h), ${r1(r.genPerSec)} tok/s" + (if (r.model.isNotEmpty()) " (${r.model})" else "") +
            ", ${r.coolDowns} warm spells, ${r.hotPauses} hot pauses. " + explain(r)
}
