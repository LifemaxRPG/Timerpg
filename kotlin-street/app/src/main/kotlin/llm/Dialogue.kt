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
     * Places and events a reply may only speak of when the facts do ("the old mill", "the new library", "the fire"):
     * after "the", "a", "our"... and at most two words (src/llm/factsheet.ts `PLACES`).
     */
    val PLACES = listOf("library", "mill", "church", "chapel", "college", "bakery", "factory", "museum", "hospital", "station",
        "depot", "bridge", "hotel", "inn", "tavern", "pub", "saloon", "diner", "cafe", "restaurant", "theater", "theatre", "cinema",
        "market", "mall", "plaza", "courthouse", "jail", "prison", "cemetery", "graveyard", "lighthouse", "harbor", "harbour", "pier",
        "dock", "quarry", "warehouse", "festival", "parade", "carnival", "circus", "pharmacy", "gym", "stadium", "arena", "zoo",
        "castle", "tower", "monument", "statue", "fountain", "lake", "pond", "river", "creek", "farm", "ranch", "orchard", "barn",
        "dam", "canal", "reservoir", "mansion", "manor", "flood", "fire", "storm", "robbery", "murder", "scandal", "election")
    private val PLACE = Regex(B0 + "(?:" + ci("the|a|an|our|that|this|its") + ")[$WS]+(?:[a-zA-Z'-]+[$WS]+){0,2}?(" +
        ci(PLACES.joinToString("|")) + ")(?:" + ci("e?s") + ")?" + B1)
    /** Saying you are from here when the facts say you moved here (or the other way round). */
    private val NATIVE = Regex(B0 + "(?:" + ci("all my life|my whole life|born here|grew up here|always lived here|born and raised here|i am from here|i'm from here|from around here") + ")" + B1)
    private val MOVED = Regex(B0 + "(?:" + ci("moved here|came here from|not from here|not from around here") + ")" + B1)
    private val TOWN = Regex("moved to (.+?) from ")
    /** Changing the subject: only for a topic the facts mark as one they would rather not talk about. */
    private val DEFLECT = Regex(B0 + "(?:" + ci("talk about something else|chat about something else|change the subject|perhaps we (?:can|could) (?:talk|chat) about|let's (?:talk|chat) about|(?:talk|chat) about ") +
        "[a-zA-Z' ]+" + ci(" instead") + ")" + B1)
    /** A sentence that doubts or denies: the player's own word in it is not a claim ("A library? I haven't heard of one."). */
    private val HEDGE = Regex("\\?|" + B0 + "(?:" + ci("no|not|never|nothing|nobody") + ")" + B1 + "|" + ci("n't") + B1)
    /** The fact sheet's mark for a topic the person would rather not talk about (factsheet.ts `AVOID`). */
    const val AVOID = "You would rather not talk about it."
    private const val USER = "<|im_start|>user\n"
    private val SAID_BEFORE = Regex("You already said: [^\n]*")

    /**
     * Cleans the model's reply, or returns null if it must not be used (the template line is shown instead).
     * [must]: words the reply has to contain (a name from the fact sheet); [self]: the speaker's full name, which a
     * reply may not contain (no self-introductions); [known]: the prompt, so a name, place or event it never mentions is
     * rejected (empty: not checked; the player's own words in its user turn count only when the reply doubts or denies
     * them); [recent]: their last replies in this conversation, which it may not say again.
     */
    fun validate(raw: String, must: List<String>, self: String, known: String = "", recent: List<String> = emptyList()): String? =
        check(raw, must, self, known, recent).first

    /** Why [validate] turns [raw] down (null: it passes); for the AI panel. */
    fun whyRejected(raw: String, must: List<String>, self: String, known: String = "", recent: List<String> = emptyList()): String? =
        check(raw, must, self, known, recent).second

    /** The sentence of [t] around index [i] (with its closing mark). */
    private fun sentenceAt(t: String, i: Int): String {
        var s = i
        while (s > 0 && t[s - 1] !in ".!?") s--
        var e = i
        while (e < t.length && t[e] !in ".!?") e++
        return t.substring(s, minOf(t.length, e + 1))
    }

    private val NOT_WORD = Regex("[^a-z0-9']+")
    private fun wordsOf(s: String): List<String> = s.lowercase().replace(NOT_WORD, " ").trim().split(' ').filter { it.isNotEmpty() }

    /** [t] says again what one of [recent] said: four words in a row the same ("a lovely day for"), or a sentence of mostly the same words. */
    fun repeats(t: String, recent: List<String>): Boolean {
        fun grams(ws: List<String>): Set<String> = (0..ws.size - 4).map { ws.subList(it, it + 4).joinToString(" ") }.toSet()
        fun sents(s: String): List<Set<String>> = s.split(Regex("[.!?]+")).map(::wordsOf).filter { it.size >= 3 }.map { it.toSet() }
        val mine = grams(wordsOf(t)); val mySents = sents(t)
        for (r in recent) {
            val theirs = grams(wordsOf(r))
            if (mine.any { it in theirs }) return true
            for (a in mySents) for (b in sents(r)) {
                val both = a.count { it in b }
                if (both.toDouble() / (a.size + b.size - both) >= .75) return true
            }
        }
        return false
    }

    /** The line as [validate] keeps it, or null and why not. */
    private fun check(raw: String, must: List<String>, self: String, known: String, recent: List<String>): Pair<String?, String?> {
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
        // Everything named comes from the facts. The player's own words (the prompt's user turn) may be said back only to
        // doubt or deny them ("A new library? I haven't heard of one."), not as a claim ("The new library is lovely.").
        val u = known.indexOf(USER)
        val uEnd = if (u < 0) -1 else known.indexOf("<|im_end|>", u + USER.length).let { if (it < 0) known.length else it }
        // (their own last replies, quoted in the facts so they say something new, are not facts)
        val sheet = (if (u < 0) known else known.substring(0, u) + known.substring(uEnd)).replace(SAID_BEFORE, "")
        val said = if (u < 0) "" else known.substring(u + USER.length, uEnd)
        fun echoed(i: Int) = HEDGE.containsMatchIn(sentenceAt(t, i))
        // a name the facts never mention ("We have two kids, Jake and Lisa."): a capitalized word in mid-sentence the facts lack
        if (known.isNotEmpty()) for (m in CAPITALIZED.findAll(t)) {
            val before = t.substring(0, m.range.first).trimEnd(::isJsSpace)
            if (before.isEmpty() || before.last() in ".!?\"“‘(" || m.value in COMMON) continue
            val name = Regex(B0 + m.value + B1)
            if (name.containsMatchIn(sheet) || (name.containsMatchIn(said) && echoed(m.range.first))) continue
            return null to "made up a name (\"${m.value}\")"
        }
        if (known.isNotEmpty()) {
            val sheetLow = sheet.lowercase(); val saidLow = said.lowercase()
            for (m in PLACE.findAll(t)) {
                val noun = m.groupValues[1].lowercase()
                if (sheetLow.contains(noun) || (saidLow.contains(noun) && echoed(m.range.first))) continue
                return null to "made up a place or event (\"${m.value}\")"
            }
            // where they are from: the facts say it
            val town = TOWN.find(sheet)?.groupValues?.get(1)?.lowercase()
            val native = NATIVE.find(t) // (not "I wasn't born here", nor "Born here? No.")
            if (town != null && ((native != null && !echoed(native.range.first)) || listOf("i am from $town", "i'm from $town", "born in $town", "grew up in $town").any { low.contains(it) }))
                return null to "said they are from here (they moved here)"
            if (sheet.contains("born and raised in") && MOVED.containsMatchIn(t)) return null to "said they moved here (they were born here)"
            // "Perhaps we can talk about something else": only about a topic they would rather not talk about
            if (DEFLECT.containsMatchIn(t) && !sheet.contains(AVOID)) return null to "changed the subject"
        }
        // the same words as one of their last replies ("It is a lovely day for it, isn't it?" three times)
        if (recent.isNotEmpty() && repeats(t, recent)) return null to "said it again"
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
