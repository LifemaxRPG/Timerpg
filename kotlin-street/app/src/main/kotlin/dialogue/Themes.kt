package com.timerpg.street.dialogue

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the player talks about, learned from what they said (the people's memories, [NpcMemory], are the log: every
 * exchange said or typed, with the question's vector from the memory model when it is installed) and from the topics
 * they tap ([PlayerStats]). Deterministic and cheap: a keyword reading of the kind of question ([kindOf]), questions
 * grouped into themes by their vectors (or, without the memory model, by the words they share), and the themes and the
 * people the player returns to turned into the overnight writer's order ([Steer]). Pure Kotlin: tested on the JVM.
 *
 * Privacy: all of this stays on the phone. What leaves it (the night report) is counts and theme labels only, unless the
 * owner switches on sending their words ([Steer.summary]).
 */
object Themes {
    /** The kinds of question, in the order they are tried (the first that fits). */
    val KINDS = listOf(
        "time-travel" to listOf("time travel", "time-travel", "from the future", "the future", "what year is", "another time", "time machine"),
        "flirting" to listOf("date with", "on a date", "beautiful", "pretty", "handsome", "are you single", "dinner with me", "cute", "love you", "kiss"),
        "directions" to listOf("where is", "where's", "how do i get", "the way to", "directions", "where can i find", "how far", "which way"),
        "prices" to listOf("price", "cost", "how much", "expensive", "cheap", "dollars", "afford"),
        "the past" to listOf("used to", "back then", "years ago", "back in", "history", "old days", "remember when", "was it like", "were you young", "when you were"),
        "gossip" to listOf("rumor", "rumors", "rumour", "rumours", "gossip", "heard anything", "heard about", "news", "going on", "scandal", "secret"),
        "family" to listOf("family", "wife", "husband", "kids", "children", "son", "daughter", "married", "parents", "mother", "father", "brother", "sister"),
        "work" to listOf("work", "job", "shop", "store", "business", "living", "sell", "trade", "customers", "boss"),
        "opinion" to listOf("what do you think", "your opinion", "how do you feel about", "do you like", "what's your view", "thoughts on"),
        "the town" to listOf("town", "mayor", "neighbourhood", "neighborhood", "around here", "this place", "the city"),
        "personal" to listOf("how are you", "your name", "how old", "do you live", "hobby", "hobbies", "favorite", "favourite", "about yourself", "are you happy"),
        "greeting" to listOf("hello", "hi", "hey", "good morning", "good evening", "good afternoon", "bye", "goodbye", "see you"),
    )

    /** The kind of question [said] is ("other" when nothing fits). Whole words and phrases, case aside. */
    fun kindOf(said: String): String {
        val s = " " + said.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (DECADE.containsMatchIn(s)) return "the past"
        for ((kind, words) in KINDS) if (words.any { s.contains(" $it ") }) return kind
        return "other"
    }
    private val DECADE = Regex("""\b(1[89]|20)?\d0'?s\b""")

    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "you", "your",
        "yours", "i", "me", "my", "we", "our", "it", "its", "this", "that", "these", "those", "of", "to", "in", "on", "at", "for",
        "with", "about", "from", "by", "what", "who", "whom", "how", "why", "when", "where", "which", "there", "here", "have", "has",
        "had", "can", "could", "would", "should", "will", "think", "tell", "know", "like", "so", "any", "some", "much", "many",
        "really", "just", "please", "well", "oh", "hey", "hi", "hello", "s", "t", "d", "ll", "re", "ve", "m", "not", "no", "yes",
        "they", "them", "their", "he", "she", "him", "her", "his", "if", "then", "than", "too", "very", "all", "get", "got", "say",
        "said", "go", "going", "feel", "thoughts", "opinion", "view", "whats", "one", "us", "around",
    )

    /** The words that carry what [said] is about (no small words). */
    fun words(said: String): Set<String> =
        said.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ').filter { it.length > 1 && it !in STOP }.toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Float =
        if (a.isEmpty() && b.isEmpty()) 0f else a.intersect(b).size.toFloat() / a.union(b).size

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na <= 0f || nb <= 0f) 0f else d / kotlin.math.sqrt(na * nb)
    }

    /** One thing the player said: to whom, the words, its vector (null without the memory model), and whether it went live. */
    class Said(val npc: String, val time: Long, val text: String, val vec: FloatArray?, val live: Boolean) {
        val kind = kindOf(text)
        val words = words(text)
    }

    /**
     * A theme: questions of one [kind] that say the same thing. [exemplar]: the first of them (stable from night to
     * night, so the lines written for it keep their keys), the player's own phrasing, asked of each person overnight.
     * [weight]: how often, a question that had to go live counting [LIVE_WEIGHT] times. [npcs]: who it was asked.
     */
    class Theme(val id: String, val kind: String, val label: String, val exemplar: String, val count: Int, val live: Int,
                val weight: Int, val npcs: Set<String>, val centroid: FloatArray?, val words: Set<String>)

    /** Alike enough to be one theme: by the vectors, or (without them) the words shared. Estimates, not measured. */
    const val CLUSTER_COSINE = .72f
    const val CLUSTER_WORDS = .5f
    /** Alike enough to answer with a theme's ready line (stricter: a wrong-topic answer is worse than a live one). */
    const val MATCH_COSINE = .80f
    const val MATCH_WORDS = .6f
    const val LIVE_WEIGHT = 3

    private fun alike(a: Said, centroid: FloatArray?, words: Set<String>, vec: Boolean): Float =
        if (vec && a.vec != null && centroid != null) cosine(a.vec, centroid) else jaccard(a.words, words)

    /** The things said, grouped into themes (heaviest first). Greetings and empty words are left out. */
    fun cluster(said: List<Said>): List<Theme> {
        class C(val first: Said) {
            val members = ArrayList<Said>()
            var sum: FloatArray? = first.vec?.copyOf()
            val words = HashSet(first.words)
            fun centroid() = sum
        }
        val cs = ArrayList<C>()
        for (s in said.sortedBy { it.time }) {
            if (s.kind == "greeting" || s.words.isEmpty()) continue
            var best: C? = null; var bestSim = 0f
            for (c in cs) {
                if (c.first.kind != s.kind) continue
                val useVec = s.vec != null && c.sum != null
                val sim = alike(s, c.centroid(), c.first.words, useVec)
                val need = if (useVec) CLUSTER_COSINE else CLUSTER_WORDS
                // (the same subject, by vectors too: "the mayor" and "the baker" are both opinions, not one theme)
                if (sim >= need && sim > bestSim && sharesSubject(s.words, c.first.words)) { best = c; bestSim = sim }
            }
            val c = best ?: C(s).also { cs += it }
            c.members += s
            // (the vectors' sum points where their mean does: the centroid for a cosine)
            val v = s.vec; val sum = c.sum
            if (c.members.size > 1 && v != null && sum != null) for (i in 0 until minOf(v.size, sum.size)) sum[i] += v[i]
            c.words += s.words
        }
        return cs.map { c ->
            val live = c.members.count { it.live }
            val top = c.members.flatMap { it.words }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(2).map { it.key }
            Theme(
                id = ReplyCache.keyOf(c.first.kind + "\n" + c.first.text.trim().lowercase(), "theme").take(12),
                kind = c.first.kind,
                label = c.first.kind + if (top.isEmpty()) "" else ": " + top.joinToString(", "),
                exemplar = c.first.text.trim(),
                count = c.members.size, live = live, weight = c.members.size + live * (LIVE_WEIGHT - 1),
                npcs = c.members.map { it.npc }.toSet(), centroid = c.sum, words = c.first.words,
            )
        }.sortedWith(compareByDescending<Theme> { it.weight }.thenBy { it.id })
    }

    /** No subject words on one side, or at least one shared. */
    private fun sharesSubject(a: Set<String>, b: Set<String>): Boolean {
        val sa = a - KIND_WORDS; val sb = b - KIND_WORDS
        return sa.isEmpty() || sb.isEmpty() || sa.any { it in sb }
    }
    /** Words that say what kind of question it is, not what it is about ("how much does bread cost": bread). */
    private val KIND_WORDS = setOf(
        "cost", "price", "prices", "expensive", "cheap", "dollars", "afford", "rumor", "rumors", "rumour", "rumours", "gossip", "news",
        "heard", "history", "used", "back", "then", "years", "ago", "old", "days", "remember", "young", "way", "far", "find",
        "directions", "favorite", "favourite", "hobby", "hobbies", "name", "happy", "yourself", "future", "travel", "machine",
        "year", "another", "date", "single", "dinner", "beautiful", "pretty", "handsome", "cute", "love", "kiss", "lately",
    )

    /**
     * The theme whose ready line can answer [said] (its vector [vec], null without the memory model), or null: the same
     * kind of question, the same subject, and alike past the match threshold. Null is the safe answer: the model then
     * answers live.
     */
    fun match(said: String, vec: FloatArray?, themes: List<Theme>): Theme? {
        val s = Said("", 0, said, vec, false)
        if (s.kind == "greeting" || s.words.isEmpty()) return null
        var best: Theme? = null; var bestSim = 0f
        for (t in themes) {
            if (t.kind != s.kind || !sharesSubject(s.words, t.words)) continue
            val useVec = vec != null && t.centroid != null
            val sim = if (useVec) cosine(vec!!, t.centroid!!) else jaccard(s.words, t.words)
            if (sim >= (if (useVec) MATCH_COSINE else MATCH_WORDS) && sim > bestSim) { best = t; bestSim = sim }
        }
        return best
    }
}

/**
 * The player's taps and how each question was answered (ready line, live, or the template), by day: counts only (no
 * words), kept in filesDir/player-stats.json. Not thread-safe: the engine guards it.
 */
class PlayerStats {
    /** "npc|intent" to times asked. */
    val asked = HashMap<String, Int>()
    /** Day (yyyy-MM-dd) to [ready, live, template] answers. */
    val days = java.util.TreeMap<String, IntArray>()
    /** Follow-up lines already said (each once). */
    val followUpsUsed = HashSet<String>()

    fun record(npc: String, intent: String, how: String, day: String) {
        asked.merge("$npc|$intent", 1, Int::plus)
        val d = days.getOrPut(day) { IntArray(3) }
        when (how) { "ready" -> d[0]++; "live" -> d[1]++; else -> d[2]++ }
        while (days.size > 60) days.remove(days.firstKey())
    }

    fun byNpc(): Map<String, Int> = asked.entries.groupBy({ it.key.substringBefore('|') }, { it.value }).mapValues { it.value.sum() }
    fun byIntent(): Map<String, Int> = asked.entries.groupBy({ it.key.substringAfter('|') }, { it.value }).mapValues { it.value.sum() }

    fun toJson(): String = JSONObject()
        .put("asked", JSONObject(asked as Map<*, *>))
        .put("days", JSONObject().also { o -> for ((d, v) in days) o.put(d, JSONArray(v.toList())) })
        .put("followUpsUsed", JSONArray(followUpsUsed.sorted()))
        .toString()

    fun load(text: String) {
        val j = JSONObject(text)
        j.optJSONObject("asked")?.let { o -> for (k in o.keys()) asked[k] = o.optInt(k) }
        j.optJSONObject("days")?.let { o -> for (k in o.keys()) o.optJSONArray(k)?.let { a -> days[k] = IntArray(3) { a.optInt(it) } } }
        j.optJSONArray("followUpsUsed")?.let { a -> for (i in 0 until a.length()) followUpsUsed += a.optString(i) }
    }
}

/**
 * The overnight writer's steering, from the log: who the player talks to (and their neighbours: the same building, the
 * next ones along the row), which topics they ask, the themes of what they say, and each person's last exchange (for a
 * follow-up: what they would say next time).
 */
class Steer(
    val themes: List<Themes.Theme>,
    val npcNeed: Map<String, Double>,
    val intentNeed: Map<String, Double>,
    val recent: Map<String, NpcMemory.Entry>,
) {
    fun need(npc: String, intent: String?): Double = (npcNeed[npc] ?: 0.0) + .5 * (intent?.let { intentNeed[it] } ?: 0.0)

    /** [items] most needed first (ties keep their order: nearest the player first). */
    fun <T> order(items: List<T>, npcOf: (T) -> String, intentOf: (T) -> String?): List<T> =
        items.withIndex().sortedWith(compareByDescending<IndexedValue<T>> { need(npcOf(it.value), intentOf(it.value)) }.thenBy { it.index }).map { it.value }

    /** The themes asked often enough to write for every person ahead of time (at least [MIN_WEIGHT], the top [TOP]). */
    val frequent: List<Themes.Theme> get() = themes.filter { it.weight >= MIN_WEIGHT }.take(TOP)

    /** For the AI panel and the night report: counts and labels; the player's own words only with [withText]. */
    fun summary(withText: Boolean): List<Map<String, Any?>> = themes.take(10).map { t ->
        linkedMapOf<String, Any?>("label" to t.label, "kind" to t.kind, "asked" to t.count, "live" to t.live, "people" to t.npcs.size)
            .also { if (withText) it["example"] = t.exemplar }
    }

    companion object {
        val EMPTY = Steer(emptyList(), emptyMap(), emptyMap(), emptyMap())
        const val MIN_WEIGHT = 2
        const val TOP = 8
        /** A follow-up is written for exchanges this recent. */
        const val RECENT_MS = 14L * 24 * 3600 * 1000

        /**
         * From the town's people [npcs], what they remember the player said ([memories], all towns: only [town]'s used),
         * and the [stats] of taps. [now]: for which exchanges are recent.
         */
        fun build(npcs: List<Npc>, town: String, memories: List<NpcMemory.Entry>, stats: PlayerStats, now: Long): Steer {
            val mine = memories.filter { it.town.isEmpty() || it.town == town }
            val talk = HashMap<String, Double>()
            for ((npc, n) in stats.byNpc()) talk.merge(npc, n.toDouble(), Double::plus)
            for (e in mine) talk.merge(e.npc, if (e.how == "live") Themes.LIVE_WEIGHT.toDouble() else 1.0, Double::plus)
            // neighbours: the same building (half), the next building along the row or street (a quarter)
            val need = HashMap<String, Double>(talk)
            val byId = npcs.associateBy { it.id }
            for ((id, s) in talk) {
                val a = byId[id] ?: continue
                for (b in npcs) {
                    if (b.id == id) continue
                    val share = when {
                        b.building == a.building -> .5
                        a.row != null && a.row == b.row && kotlin.math.abs(a.rowIndex - b.rowIndex) <= 1 -> .25
                        a.row == null && a.street == b.street && a.index >= 0 && kotlin.math.abs(a.index - b.index) <= 1 -> .25
                        else -> 0.0
                    }
                    if (share > 0) need.merge(b.id, s * share, Double::plus)
                }
            }
            val intents = HashMap<String, Double>()
            for ((i, n) in stats.byIntent()) intents.merge(i, n.toDouble(), Double::plus)
            for (e in mine) if (e.intent.isNotEmpty()) intents.merge(e.intent, 1.0, Double::plus)
            val themes = Themes.cluster(mine.map { Themes.Said(it.npc, it.time, it.said, it.q, it.how == "live") })
            val recent = mine.filter { now - it.time <= RECENT_MS && it.said.isNotBlank() }.groupBy { it.npc }.mapValues { (_, l) -> l.maxByOrNull { it.time }!! }
            return Steer(themes, need, intents, recent)
        }
    }
}
