package com.timerpg.street.dialogue

/**
 * What a person says straight away while they think, so a spoken question gets an answer at once and the model and the
 * voice have a second or two to make the real one: "Born here? Well...", "Oh, now that's a question." Chosen by what was
 * asked (an echo of its key word where there is one, else by its kind: where, when, who, why, how, yes or no) and by the
 * person (warm or curt). Never an answer itself (no "I don't know"): the real line follows it. Every filler a person can
 * say is voiced ahead of time ([all]), so it plays without waiting.
 */
object Fillers {
    private val WARM_ANY = listOf(
        "Oh, now that's a good question.", "Hmm, let me think.", "Well now, let me see.", "Oh! Well...",
        "Ha, you want to know that?", "Oh, where do I even start.",
    )
    private val CURT_ANY = listOf("Hm.", "Well.", "Let me think.", "Why do you ask?", "Huh.", "Hm. Well.")

    /** Kinds of question, by their first words, with what a warm and a curt person says. */
    private class Kind(val starts: Regex, val warm: List<String>, val curt: List<String>)
    private val KINDS = listOf(
        Kind(Regex("""^(where|whereabouts)\b"""), listOf("Where? Oh, well...", "Hmm, where..."), listOf("Where? Hm.", "Where.")),
        Kind(Regex("""^(when|how long|what year)\b"""), listOf("Oh, let's see, when was that...", "Goodness, how long has it been..."), listOf("When? Hm.", "Let me think.")),
        Kind(Regex("""^(who|whose)\b"""), listOf("Who? Oh, well...", "Hmm, who..."), listOf("Who? Hm.", "Who.")),
        Kind(Regex("""^why\b"""), listOf("Why? Oh, well...", "Now why would that be..."), listOf("Why? Hm.", "Why.")),
        Kind(Regex("""^how\b"""), listOf("How? Oh, well...", "Hmm, how to put it..."), listOf("How? Hm.", "Well.")),
        Kind(Regex("""^what\b"""), listOf("Oh, what indeed...", "Hmm, well..."), listOf("What? Hm.", "Well.")),
        Kind(Regex("""^(are|is|do|does|did|have|has|was|were|can|could|will|would)\b"""), listOf("Oh, well...", "Hmm, well, let me see..."), listOf("Hm. Well.", "Huh.")),
    )

    /** Key words worth echoing back ("Born here? Well..."). */
    private val ECHO = listOf(
        "born" to "Born here", "married" to "Married", "wife" to "My wife", "husband" to "My husband",
        "kids" to "The kids", "children" to "The children", "family" to "Family", "work" to "Work", "job" to "My job",
        "business" to "Business", "store" to "The store", "shop" to "The shop", "town" to "This town",
        "secret" to "A secret", "rumor" to "Rumors", "money" to "Money", "weather" to "The weather",
    )

    /** What [warmth] (0 gruff .. 1 warm) says first to [question]; [seed] varies it (never the same twice in a row). */
    fun pick(question: String, warmth: Float, seed: Int): String {
        val q = question.lowercase().trim().trimStart('"', '\'')
        val warm = warmth >= .45f
        val echo = ECHO.firstOrNull { (w, _) -> Regex("""\b$w\b""").containsMatchIn(q) }
        val pool = buildList {
            KINDS.firstOrNull { it.starts.containsMatchIn(q) }?.let { addAll(if (warm) it.warm else it.curt) }
            addAll(if (warm) WARM_ANY else CURT_ANY)
            if (echo != null) { add(echo.second + (if (warm) "? Oh, well..." else "? Hm.")); add(echo.second + "? Well...") }
        }
        return pool[Math.floorMod(seed, pool.size)]
    }

    private val QUESTION_START = Regex("""^(who|whom|whose|what|where|when|why|how|which|are|is|am|do|does|did|have|has|had|was|were|can|could|will|would|should|shall)\b""", RegexOption.IGNORE_CASE)
    /** Words after which "you" is the subject ("are you", "where do you") and becomes "I"; elsewhere it becomes "me". */
    private val BEFORE_SUBJECT = setOf("are", "were", "do", "did", "does", "can", "could", "will", "would", "should", "have", "has", "had",
        "shall", "what", "who", "where", "when", "why", "how", "if", "that", "and", "but", "or", "so", "because")
    private val PAIRS = listOf("are you" to "am I", "were you" to "was I", "aren't you" to "aren't I", "weren't you" to "wasn't I",
        "you are" to "I am", "you were" to "I was", "you're" to "I'm", "you've" to "I've", "you'll" to "I'll", "you'd" to "I'd",
        "am i" to "are you", "was i" to "were you", "i am" to "you are", "i was" to "you were", "i'm" to "you're", "i've" to "you've")
    private val SWAP = mapOf("your" to "my", "yours" to "mine", "yourself" to "myself", "my" to "your", "mine" to "yours",
        "myself" to "yourself", "me" to "you", "i" to "you", "us" to "you")

    /**
     * The player's question said back as the person would, to think out loud while the answer is made: "Who's the
     * wealthiest person in this town? Well...", "How old am I? Ha, well..." (you and I swapped). Null when it is not a
     * question or too long to say back naturally (a filler is used then).
     */
    fun echo(question: String, warmth: Float, seed: Int): String? {
        var t = question.trim().trimEnd('?', '.', '!', ' ').trimStart('"', '\'')
        val words = t.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size !in 2..11 || !QUESTION_START.containsMatchIn(t)) return null
        // you and I swapped: two-word forms first ("are you" -> "am I"), then single words
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val pair = if (i + 1 < words.size) PAIRS.firstOrNull { (a, _) -> a == "${words[i]} ${words[i + 1]}".lowercase() } else null
            if (pair != null) { out += pair.second.split(' '); i += 2; continue }
            val w = words[i]; val low = w.lowercase()
            out += when {
                low == "you" -> if (i > 0 && words[i - 1].lowercase() in BEFORE_SUBJECT) "I" else "me"
                low in SWAP -> SWAP.getValue(low)
                else -> w
            }
            i++
        }
        t = out.joinToString(" ")
        t = t.replaceFirstChar { it.uppercase() }
        val tails = if (warmth >= .45f) listOf("Oh, well...", "Hmm, well...", "Well now...", "Ha, well...") else listOf("Hm.", "Well.", "Hm. Well...")
        return "$t? " + tails[Math.floorMod(seed, tails.size)]
    }

    /** Every filler a person of [warmth] might say (to voice them all ahead of time). */
    fun all(warmth: Float): List<String> {
        val warm = warmth >= .45f
        return buildList {
            addAll(if (warm) WARM_ANY else CURT_ANY)
            for (k in KINDS) addAll(if (warm) k.warm else k.curt)
            for ((_, e) in ECHO) { add(e + (if (warm) "? Oh, well..." else "? Hm.")); add("$e? Well...") }
        }.distinct()
    }
}
