package com.timerpg.street.llm

/**
 * The check every model reply passes before a player sees it: an exact port of `validate()` in src/llm/factsheet.ts (the
 * same steps and rules, in the same order). JavaScript's regex semantics are kept where Java's (or Android's ICU) differ:
 * its whitespace class `\s` and `trim()`, ASCII-only `\b` word boundaries, ASCII-only case folding for `/i`, and `$` as the
 * very end of the text. So a reply passes here exactly when it passes in the TypeScript game
 * (test vectors: tools/validate-vectors.ts -> app/src/test/kotlin/ValidateVectors.kt).
 */
object Dialogue {
    /** JavaScript's `\s`: WhiteSpace and LineTerminator, which is also what `String.prototype.trim` removes. */
    private const val WS = "\t\n\u000B\u000C\r    -     　﻿"

    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    private fun jsTrim(s: String): String = s.trim(::isJsSpace)

    /** `/.../i` on an ASCII pattern: each letter matches its two ASCII cases only (as JavaScript does without the u flag). */
    private fun ci(p: String): String = buildString {
        for (c in p) if (c in 'a'..'z') append('[').append(c).append(c.uppercaseChar()).append(']') else append(c)
    }

    /** `\b` before and after a run that starts and ends with a word character, with JavaScript's ASCII word characters. */
    private const val B0 = "(?<![A-Za-z0-9_])"
    private const val B1 = "(?![A-Za-z0-9_])"

    private val THINK = Regex("<think>[\\s\\S]*?</think>")
    private val SPECIAL = Regex("<\\|[^|]*\\|>")
    private val QUOTES = Regex("^[\"“'‘]+|[\"”'’]+\\z")
    private val ACTION = Regex("\\*[^*]*\\*")
    private val SPACES = Regex("[$WS]+")
    private val SENTENCES = Regex("[^.!?]+[.!?]+(?:[$WS]|\\z)|[^.!?]+\\z")
    private val AI_START = Regex("^(?:" + ci("as an ai|i am an ai|i'm an ai|sorry, i") + ")")
    private val AI_WORDS = Regex(B0 + "(?:" + ci("language model|assistant") + ")" + B1)
    private val STAGE = Regex(
        B0 + "(?:" + ci("i say|i respond|i reply|i smile|smiles?|nods?|my eyes|flickers?|let me know|how can i (?:help|assist)|anything else") + ")" + B1,
    )

    // the fact sheet read back: its personality words, its "<town>, <year>" tag, or its way of speaking as a stage
    // direction ("A curt, measured response: ...")
    private val SHEET_WORDS = Regex(B0 + "(?:" + ci("warm and chatty|gruff and guarded|talk warmly|talk curtly") + ")" + B1)
    private val SHEET_YEAR = Regex(",[$WS]*(?:1[89]|20)[0-9][0-9]" + B1)
    private val SHEET_STYLE = Regex("^" + ci("an?") + " [a-zA-Z ,]*" + B0 + "(?:" + ci("response|reply|answer|tone|voice") + ")" + B1)
    private val CAPITALIZED = Regex(B0 + "[A-Z][a-z]+" + B1)
    /** Capitalized words a reply may use without the facts naming them. */
    private val COMMON = setOf("Mr", "Mrs", "Ms", "Miss", "Mister", "Dr", "Sir", "Madam", "God", "Lord", "Monday", "Tuesday", "Wednesday",
        "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December", "Christmas", "Easter", "English", "American")

    /**
     * Cleans the model's reply, or returns null if it must not be used (the template line is shown instead).
     * [must]: words the reply has to contain (a name from the fact sheet); [self]: the speaker's full name, which a
     * reply may not contain (no self-introductions); [known]: the prompt, so a name it never mentions is rejected
     * (empty: not checked).
     */
    fun validate(raw: String, must: List<String>, self: String, known: String = ""): String? = check(raw, must, self, known).first

    /** Why [validate] turns [raw] down (null: it passes); for the AI panel. */
    fun whyRejected(raw: String, must: List<String>, self: String, known: String = ""): String? = check(raw, must, self, known).second

    /** The line as [validate] keeps it, or null and why not. */
    private fun check(raw: String, must: List<String>, self: String, known: String): Pair<String?, String?> {
        var t = jsTrim(SPECIAL.replace(THINK.replace(raw, ""), ""))
        t = jsTrim(SPACES.replace(ACTION.replace(QUOTES.replace(t, ""), ""), " "))
        if (t.length < 3) return null to "empty"
        if (t.length > 320) return null to "too long"
        // Keep at most the first two sentences.
        val sentences = SENTENCES.findAll(t).map { it.value }.toList()
        if (sentences.size > 2) t = jsTrim(sentences.take(2).joinToString(""))
        if (AI_START.containsMatchIn(t) || AI_WORDS.containsMatchIn(t)) return null to "sounded like an AI assistant"
        // Stage directions, assistant-speak and self-introductions are not how a neighbor talks.
        if (STAGE.containsMatchIn(t)) return null to "stage directions"
        if (self.isNotEmpty() && t.lowercase().contains(self.lowercase())) return null to "said their own full name"
        // the fact sheet read back: its personality words, or its "<town>, <year>" tag
        if (SHEET_WORDS.containsMatchIn(t) || SHEET_YEAR.containsMatchIn(t) || SHEET_STYLE.containsMatchIn(t)) return null to "read the fact sheet back"
        val low = t.lowercase()
        must.firstOrNull { !low.contains(it.lowercase()) }?.let { return null to "left out \"$it\"" }
        // a name the facts never mention ("We have two kids, Jake and Lisa."): a capitalized word in mid-sentence the prompt lacks
        if (known.isNotEmpty()) for (m in CAPITALIZED.findAll(t)) {
            val before = t.substring(0, m.range.first).trimEnd(::isJsSpace)
            if (before.isEmpty() || before.last() in ".!?\"“‘(" || m.value in COMMON) continue
            if (!Regex(B0 + m.value + B1).containsMatchIn(known)) return null to "made up a name (\"${m.value}\")"
        }
        return t to null
    }

    /** The reply so far, tidied for showing while it streams in (not a check: [validate] decides at the end). */
    fun preview(raw: String): String {
        var t = SPECIAL.replace(THINK.replace(raw, ""), "")
        // a special token or a think block still being written
        val cut = listOf(t.indexOf("<think>"), t.indexOf("<|")).filter { it >= 0 }.minOrNull()
        if (cut != null) t = t.substring(0, cut)
        t = ACTION.replace(t, "")
        return jsTrim(SPACES.replace(t, " ")).trimStart('"', '“', '\'', '‘')
    }
}
