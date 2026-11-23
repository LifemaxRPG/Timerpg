package com.timerpg.street.night

import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.dialogue.NightCount

/**
 * Why the writer is or is not writing, in plain words, every tick (pure: tested on the JVM). Writer mode's screen, the
 * notification, the AI panel and the screenshot JSON show it, so a run that writes nothing never looks like one that
 * writes: the owner's run of 2026-11-23 sat for minutes with no line and nothing on screen said why.
 *
 * [state] is a short code ("writing", "waiting-charge", "resting", "paused", "loading", "model-failed", "no-model",
 * "held", "planning", "complete", "rejecting", "timing-out", "failing", "no-tokens", "starting", "stopped");
 * [reason] the words.
 */
data class WriterWhy(val state: String, val reason: String) {
    /** True when this is a reason it is not writing (anything but "writing"). */
    val stuck: Boolean get() = state != "writing"

    companion object {
        /** Minutes of writing time with the model ready and nothing generated before it says so. */
        const val SILENT_MS = 3 * 60_000L

        fun of(
            step: NightStep,
            state: ModelState,
            detail: String,
            held: String?,
            count: NightCount,
            left: Int,
            writingMs: Long,
            lastRejected: String? = null,
        ): WriterWhy {
            when (step) {
                is NightStep.Stop -> return WriterWhy("stopped", step.reason)
                is NightStep.Rest -> return when (step.kind) {
                    NightStep.Kind.BATTERY -> WriterWhy("waiting-charge", step.reason)
                    NightStep.Kind.USER -> WriterWhy("paused", "paused in Writer mode")
                    NightStep.Kind.GAME -> WriterWhy("starting", step.reason)
                    else -> WriterWhy("resting", step.reason)
                }
                NightStep.Write -> {}
            }
            val d = detail.take(200)
            when (state) {
                ModelState.NO_MODEL, ModelState.NO_ENGINE -> return WriterWhy("no-model", "no dialogue model to write with" + d.ifEmpty { null }?.let { ": $it" }.orEmpty())
                ModelState.ERROR -> return WriterWhy("model-failed", "the model failed to load" + d.ifEmpty { null }?.let { ": $it" }.orEmpty())
                ModelState.UNLOADED -> return WriterWhy("model-failed", "the model was let go and is not back yet" + d.ifEmpty { null }?.let { ": $it" }.orEmpty())
                ModelState.READY -> {}
                else -> return WriterWhy("loading", d.ifEmpty { "loading the model" })
            }
            held?.let { return WriterWhy("held", "the model is ready but the engine holds the writing ($it)") }
            if (left == 0) return WriterWhy("complete", "the bank is complete")
            if (left < 0) return WriterWhy("planning", "counting the lines to write")
            val written = count.lines + count.variants
            if (written == 0) {
                // the order matters: each says what the model did with the lines it tried
                if (count.timedOut > 0 && count.timedOut >= count.rejected)
                    return WriterWhy("timing-out", "every line so far took too long and was given up (${count.timedOut}" +
                        (if (count.rejected > 0) ", ${count.rejected} more turned down by the check" else "") + ")" +
                        (if (count.genTokens > 0) "; %.1f tok/s".format(java.util.Locale.US, count.genPerSec) else "; no token generated"))
                if (count.failed > 0 && count.failed >= count.rejected)
                    return WriterWhy("failing", "the model failed on every line so far (${count.failed}): ${count.lastFailure ?: "unknown"}")
                if (count.rejected > 0)
                    return WriterWhy("rejecting", "every line so far was turned down by the check (${count.rejected})" + lastRejected?.let { "; the last: ${it.take(160)}" }.orEmpty())
                if (count.genTokens == 0L && writingMs >= SILENT_MS)
                    return WriterWhy("no-tokens", "the model is ready but has generated no token in ${WriterLoop.duration(writingMs)} of writing time")
            }
            return WriterWhy("writing", "writing")
        }
    }
}

/**
 * The writer's last word on itself, kept in the "night" preferences by the writer's process and read by the game's (the
 * AI panel, the screenshot JSON): when ([at]), which session ([how]), whether it runs, its [WriterWhy], and its counts.
 * A session that ended stays as "stopped" with its reason; one whose process was ended without a word goes stale
 * ([read] says so after [STALE_MS]).
 */
data class WriterStatus(
    val at: Long,
    val how: String,
    val running: Boolean,
    val state: String,
    val reason: String,
    val lines: Int = 0,
    val variants: Int = 0,
    val rejected: Int = 0,
    val timedOut: Int = 0,
    val failed: Int = 0,
    val model: String = "",
) {
    fun json(): String = org.json.JSONObject().apply {
        put("at", at); put("how", how); put("running", running); put("state", state); put("reason", reason)
        put("lines", lines); put("variants", variants); put("rejected", rejected); put("timedOut", timedOut); put("failed", failed); put("model", model)
    }.toString()

    /** For the screenshot JSON: the fields, the age, and whether a "running" status has gone stale. */
    fun map(now: Long): Map<String, Any?> = linkedMapOf(
        "how" to how, "running" to running, "state" to state, "reason" to reason, "secondsAgo" to (now - at) / 1000,
        "stale" to (running && now - at > STALE_MS), "lines" to lines, "variants" to variants, "rejected" to rejected,
        "timedOut" to timedOut, "failed" to failed, "model" to model,
    )

    /** One line for the AI panel. */
    fun line(now: Long): String {
        val ago = WriterLoop.duration(now - at)
        return when {
            running && now - at > STALE_MS -> "Writer: no word from it for $ago (its process was ended?); last: $reason"
            running -> "Writer ($how): ${if (state == "writing") "writing" else reason} · $lines lines" +
                (if (rejected + timedOut + failed > 0) " · $rejected turned down, $timedOut too slow, $failed failed" else "")
            else -> "Writer: $state ($ago ago): $reason"
        }
    }

    companion object {
        const val STALE_MS = 2 * 60_000L

        fun parse(text: String?): WriterStatus? = text?.let { t ->
            runCatching {
                val o = org.json.JSONObject(t)
                WriterStatus(o.getLong("at"), o.optString("how"), o.optBoolean("running"), o.optString("state"), o.optString("reason"),
                    o.optInt("lines"), o.optInt("variants"), o.optInt("rejected"), o.optInt("timedOut"), o.optInt("failed"), o.optString("model"))
            }.getOrNull()
        }
    }
}
