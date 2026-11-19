package com.timerpg.street.night

import com.timerpg.street.dialogue.ReplyCache
import com.timerpg.street.shot.Json
import com.timerpg.street.shot.ShotRequests
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The overnight writer's summary: kept for the AI panel ("last night") and sent as a report to the repository's
 * `reports` branch (night/<date>/<time>.json, beside crashes/) so Claude can see the phone's real overnight throughput.
 * Pure (tested on the JVM). Privacy: the player's words never go in, only counts and theme labels, unless the owner
 * switched on sending them ([Run.themes] is built with them then).
 */
object NightReport {
    /** One night's run, from start to stop. Times in ms; [writingMs] the time it was allowed to write. */
    data class Run(
        val start: Long,
        val end: Long,
        /** "service" (a foreground service) or "job" (inside a background job: Android did not allow the service). */
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
    ) {
        val written: Int get() = lines + variants
        val genPerSec: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
        val prefillPerSec: Double get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
        /** Lines per hour of the whole run, and per hour of writing (the pauses left out). */
        val linesPerHour: Double get() = if (end > start) written * 3_600_000.0 / (end - start) else 0.0
        val linesPerWritingHour: Double get() = if (writingMs > 0) written * 3_600_000.0 / writingMs else 0.0
    }

    fun path(ms: Long, tz: TimeZone): String =
        "night/${fmt("yyyy-MM-dd", ms, tz)}/${fmt("HHmmss", ms, tz)}.json"

    private fun fmt(p: String, ms: Long, tz: TimeZone) = SimpleDateFormat(p, Locale.US).apply { timeZone = tz }.format(Date(ms))

    private fun r1(d: Double) = Math.round(d * 10) / 10.0
    private fun min(ms: Long) = r1(ms / 60_000.0)

    /** The report: [app] the build and phone ([com.timerpg.street.shot.DeviceFacts.app]). */
    fun json(r: Run, app: Map<String, Any?>, tz: TimeZone): String = Json.write(linkedMapOf(
        "kind" to "night",
        "start" to ShotRequests.isoTime(r.start, tz),
        "end" to ShotRequests.isoTime(r.end, tz),
        "minutes" to min(r.end - r.start),
        "how" to r.how,
        "stopReason" to r.stopReason,
        "app" to app,
        "written" to linkedMapOf(
            "lines" to r.lines, "variants" to r.variants, "rejected" to r.rejected,
            "linesPerHour" to r1(r.linesPerHour), "linesPerWritingHour" to r1(r.linesPerWritingHour),
        ),
        "speed" to linkedMapOf(
            "runsOn" to r.runsOn, "genTokens" to r.genTokens, "genTokPerSec" to r1(r.genPerSec),
            "prefillTokens" to r.prefillTokens, "prefillTokPerSec" to r1(r.prefillPerSec),
        ),
        "time" to linkedMapOf(
            "writingMin" to min(r.writingMs), "coolingMin" to min(r.coolMs), "hotPausedMin" to min(r.hotMs),
            "burstRestMin" to min(r.burstRestMs), "batteryWaitMin" to min(r.batteryWaitMs),
        ),
        "heat" to linkedMapOf("coolDowns" to r.coolDowns, "hotPauses" to r.hotPauses, "maxThermalStatus" to r.maxThermal),
        "battery" to linkedMapOf("start" to r.batteryStart, "end" to r.batteryEnd),
        "bank" to linkedMapOf(
            "prompts" to r.bank.prompts, "ready" to r.bank.ready, "templates" to r.bank.templates, "missing" to r.bank.missing,
            "lines" to r.bank.variants, "variantsDone" to r.bank.variantsDone, "stale" to r.bank.stale, "years" to r.bank.years,
        ),
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
        "${fmt("EEE HH:mm", r.start, tz)}-${fmt("HH:mm", r.end, tz)}: ${r.lines} lines + ${r.variants} variants " +
            "(${r1(r.linesPerHour)}/h), ${r1(r.genPerSec)} tok/s, ${r.coolDowns} cool-downs, ${r.hotPauses} hot pauses; ${r.stopReason}"
}
