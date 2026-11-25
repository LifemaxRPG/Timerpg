package com.timerpg.street.play

import com.timerpg.street.shot.Json

/**
 * One exchange with an NPC for the conversation log (CONVERSATION.md "The conversation log"): what the player said
 * (typed, spoken, or a topic tapped), who answered where and when, the reply and how it was made. Filled by the
 * conversation as it goes ([com.timerpg.street.GameActivity]'s Talk), written as one `talk/` JSONL line once the reply is
 * final or the player walks away ([PlayLog.talk]). Only recorded with "Record my conversations" on.
 */
class TalkTurn(
    val npcId: String,
    val npcName: String,
    /** "spoken", "typed" or "topic" (a topic chip tapped). */
    val input: String,
    /** The player's words (the recognizer's transcript, the typed text, or the topic's label). */
    val said: String,
) {
    val startedAt = System.currentTimeMillis()
    private val t0 = System.nanoTime()
    var town: String? = null
    var building: String? = null
    var room: String? = null
    var year = 0
    var hour = Float.NaN
    var intent: String? = null
    var topic: String? = null
    /** The fact sheet used: "<npc id>/<intent>" (the sheets have no per-fact ids yet) and the memories added to it. */
    var sheet: String? = null
    var memories = 0
    // the speech recognizer's part (spoken only)
    var asrBy: String? = null
    var asrMs = -1L
    var asrAudioMs = -1L
    var asrConfidence = Float.NaN
    // the reply
    var reply: String? = null
    /** "bank" (written ahead), "template" or "live". */
    var source: String? = null
    var model: String? = null
    var unit: String? = null
    var waitMs = -1L
    var firstTokenMs = -1L
    var lastTokenMs = -1L
    var tokens = 0
    var tries = 0
    var valid: Boolean? = null
    var why: String? = null
    var walkedAway = false
    /** The turn gave the player nothing in time and the watchdog ended it: where each stage was ([com.timerpg.street.dialogue.TurnWatch]). */
    var watchdog: String? = null
    /** Question to the final line (ms). */
    var totalMs = -1L
    @Volatile var written = false

    fun finish() { if (totalMs < 0) totalMs = (System.nanoTime() - t0) / 1_000_000L }

    fun json(session: String): String {
        val gen = if (lastTokenMs > 0 && firstTokenMs >= 0 && tokens > 0) (lastTokenMs - firstTokenMs).coerceAtLeast(1) else 0L
        return Json.write(linkedMapOf(
            "k" to "turn",
            "t" to iso(startedAt),
            "session" to session,
            "npc" to linkedMapOf("id" to npcId, "name" to npcName),
            "place" to linkedMapOf("town" to town, "building" to building, "room" to room),
            "year" to year.takeIf { it != 0 },
            "hour" to hour.takeIf { !it.isNaN() }?.let { Math.round(it * 100) / 100.0 },
            "input" to input,
            "said" to said,
            "intent" to intent,
            "topic" to topic,
            "facts" to linkedMapOf("sheet" to sheet, "memories" to memories),
            "asr" to if (asrBy == null) null else linkedMapOf("by" to asrBy, "latencyMs" to asrMs.takeIf { it >= 0 },
                "audioMs" to asrAudioMs.takeIf { it > 0 }, "confidence" to asrConfidence.takeIf { !it.isNaN() }?.let { Math.round(it * 1000) / 1000.0 }),
            "reply" to reply,
            "source" to source,
            "model" to model,
            "unit" to unit,
            "waitMs" to waitMs.takeIf { it >= 0 },
            "firstTokenMs" to firstTokenMs.takeIf { it >= 0 },
            "lastTokenMs" to lastTokenMs.takeIf { it >= 0 },
            "tokens" to tokens.takeIf { it > 0 },
            "tokPerSec" to if (gen > 0) Math.round(tokens * 10000.0 / gen) / 10.0 else null,
            "valid" to valid,
            "why" to why,
            "tries" to tries.takeIf { it > 0 },
            "totalMs" to totalMs.takeIf { it >= 0 },
            "walkedAway" to walkedAway,
            "watchdog" to watchdog,
        ).filterValues { it != null })
    }

    companion object {
        fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(java.util.Date(ms))

        /** The engine's source ("ready", "live", "template") as the log names it ("bank", "live", "template"). */
        fun sourceOf(engineSource: String?): String? = when (engineSource) { "ready" -> "bank"; null -> null; else -> engineSource }
    }
}
