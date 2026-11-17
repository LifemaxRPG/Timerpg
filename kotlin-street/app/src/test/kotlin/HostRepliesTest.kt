package com.timerpg.street.dialogue

import com.timerpg.street.llm.Dialogue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real model replies through the app's own checks. tools/llm-dialogue-check.sh runs every prompt the model may voice
 * (assets/dialogue.json) through the native engine on a computer (llm_host_test --dialogue --json-out), lets the game's
 * TypeScript validate() judge each reply (tools/check-replies.ts stores it as "ts"), then runs this test with
 * LLM_REPLIES=<that file>. Without LLM_REPLIES it is skipped. It checks that
 * - the harness used the app's settings (context, max tokens, temperature: DialogueEngine's);
 * - every prompt the native engine was given is exactly the one the app sends (DialogueData.parse of the same asset) and
 *   the engine found it to be one ChatML conversation (no second wrapping, no BOS, <|im_end|> ends the turn);
 * - no generation failed;
 * - Dialogue.validate (what the app runs) gives the same verdict as the TypeScript validate() on every real reply;
 * and writes a report (pass/fail, the line the player would see, timings) to LLM_REPORT, else build/reports/llm-replies.txt.
 * How many replies pass is reported, not asserted: that is the model's business, not the code's.
 */
class HostRepliesTest {
    private class Row(val ok: Boolean, val label: String, val shown: String, val note: String, val r: JSONObject)

    @Test
    fun realRepliesThroughTheAppsChecks() {
        val path = System.getenv("LLM_REPLIES")
        assumeTrue("LLM_REPLIES is not set (tools/llm-dialogue-check.sh sets it)", !path.isNullOrEmpty())
        val doc = JSONObject(File(path!!).readText())
        val data = DialogueData.parse(File("src/main/assets/dialogue.json").readText())
        val problems = ArrayList<String>()
        fun expect(ok: Boolean, what: () -> String) { if (!ok) problems += what() }

        expect(doc.optInt("context") == DialogueEngine.CONTEXT_TOKENS) { "context ${doc.optInt("context")}, the app uses ${DialogueEngine.CONTEXT_TOKENS}" }
        expect(doc.optInt("maxTokens") == DialogueEngine.MAX_TOKENS) { "max tokens ${doc.optInt("maxTokens")}, the app uses ${DialogueEngine.MAX_TOKENS}" }
        expect(Math.abs(doc.optDouble("temperature") - DialogueEngine.TEMPERATURE) < 1e-6) { "temperature ${doc.optDouble("temperature")}, the app uses ${DialogueEngine.TEMPERATURE}" }

        val replies = doc.getJSONArray("replies")
        val rows = ArrayList<Row>()
        var samePrompt = 0
        var tsChecked = 0
        var tsAgree = 0
        for (i in 0 until replies.length()) {
            val r = replies.getJSONObject(i)
            val label = r.optString("label")
            val topic = data.npcs.firstOrNull { it.id == r.optString("npc") && it.building == r.optString("building") }
                ?.topics?.firstOrNull { it.intent == r.optString("intent") }
            if (topic == null) { problems += "$label: no such person/topic in dialogue.json"; continue }
            // the same prompt the app would send, byte for byte (the native side gets it as UTF-8 either way)
            if (topic.prompt == r.optString("prompt")) samePrompt++ else problems += "$label: the prompt differs from the app's"
            expect(topic.must == strings(r.optJSONArray("must")) && topic.self == r.optString("self")) { "$label: must/self differ from the app's" }
            expect(r.optBoolean("promptTokensOk")) { "$label: the prompt is not tokenized as one ChatML conversation" }
            expect(r.optString("error").isEmpty()) { "$label: the engine failed: ${r.optString("error")}" }
            val raw = r.optString("raw")
            val line = Dialogue.validate(raw, topic.must, topic.self, topic.prompt ?: "")
            if (r.has("ts")) {
                tsChecked++
                val ts = if (r.isNull("ts")) null else r.optString("ts")
                if (ts == line) tsAgree++ else problems += "$label: Kotlin validate() = $line, TypeScript validate() = $ts (raw: $raw)"
            }
            rows += Row(line != null, label, line ?: topic.template, if (line != null) "" else why(raw, topic), r)
        }

        val report = report(doc, rows, samePrompt, tsChecked, tsAgree, problems)
        val out = System.getenv("LLM_REPORT")?.takeIf { it.isNotEmpty() }?.let(::File) ?: File("build/reports/llm-replies.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** A rough reason a reply was rejected, for the report only ([Dialogue.validate] decides). */
    private fun why(raw: String, t: Topic): String {
        val low = raw.lowercase()
        val missing = t.must.filter { !low.contains(it.lowercase()) }
        return when {
            raw.isBlank() -> "empty"
            missing.isNotEmpty() -> "missing ${missing.joinToString { "\"$it\"" }}"
            t.self.isNotEmpty() && low.contains(t.self.lowercase()) -> "says the speaker's own full name"
            raw.trim().length < 3 -> "too short"
            Dialogue.validate(raw, t.must, t.self) != null -> "names someone the facts never mention"
            else -> "stage direction or assistant talk"
        }
    }

    private fun report(doc: JSONObject, rows: List<Row>, samePrompt: Int, tsChecked: Int, tsAgree: Int, problems: List<String>): String = buildString {
        val n = rows.size
        appendLine("Real replies from the native engine (llm_host_test), judged by the app's Dialogue.validate()")
        appendLine("model ${doc.optString("model")}")
        appendLine("backend ${doc.optString("backend")}")
        appendLine("${doc.optInt("threads")} threads, context ${doc.optInt("context")}, max ${doc.optInt("maxTokens")} tokens, temperature ${doc.optDouble("temperature")}")
        appendLine()
        for (row in rows) {
            val r = row.r
            appendLine("${if (row.ok) "PASS" else "FAIL"}  ${row.label}")
            appendLine("      reply:  ${r.optString("raw").replace("\n", "\\n")}")
            if (!row.ok) appendLine("      why:    ${row.note}; the player gets the template: ${row.shown}")
            appendLine(String.format("      prompt %d tokens, %d reused; prefill %.0f tok/s, generation %.1f tok/s (%d tokens); first token %d ms, %d ms in all; %s",
                r.optInt("promptTokens"), r.optInt("reusedTokens"), r.optDouble("prefillPerSec"), r.optDouble("genPerSec"), r.optInt("genTokens"),
                r.optLong("firstTokenMs"), r.optLong("totalMs"), r.optString("end")))
        }
        appendLine()
        val passed = rows.count { it.ok }
        appendLine("$passed of $n replies pass validate() (${if (n > 0) passed * 100 / n else 0}%)")
        for ((intent, rs) in rows.groupBy { it.r.optString("intent") }) appendLine("  $intent: ${rs.count { it.ok }} of ${rs.size}")
        for ((why, rs) in rows.filter { !it.ok }.groupBy { it.note }) appendLine("  rejected, $why: ${rs.size}")
        val sum = { k: String -> rows.sumOf { it.r.optLong(k) } }
        val fresh = sum("promptTokens") - sum("reusedTokens")
        appendLine(String.format("prompt tokens %d, reused from the KV cache %d (%.0f%%); prefill %d tokens in %d ms (%.0f tok/s); generation %d tokens in %d ms (%.1f tok/s)",
            sum("promptTokens"), sum("reusedTokens"), 100.0 * sum("reusedTokens") / maxOf(1, sum("promptTokens")), fresh, sum("prefillMs"),
            fresh * 1000.0 / maxOf(1, sum("prefillMs")), sum("genTokens"), sum("genMs"), sum("genTokens") * 1000.0 / maxOf(1, sum("genMs"))))
        appendLine(String.format("per reply (mean): first token %.0f ms, %.0f ms in all; ended: %s",
            sum("firstTokenMs").toDouble() / maxOf(1, n), sum("totalMs").toDouble() / maxOf(1, n),
            rows.groupingBy { it.r.optString("end") }.eachCount().entries.joinToString { "${it.key} ${it.value}" }))
        appendLine("prompts identical to the app's: $samePrompt of $n; TypeScript validate() agrees: " +
            if (tsChecked == 0) "not run (no \"ts\" verdicts)" else "$tsAgree of $tsChecked")
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("PROBLEMS")
            problems.forEach { appendLine("  $it") }
        }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}
