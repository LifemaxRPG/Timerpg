package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs

/**
 * Something offered while a sheet's first field is typed: [title] is what the chip says, [fields]
 * what picking it puts in the sheet's fields, in their order (a foundation: its cue; a habit: its
 * normal, two-minute and stretch names; a habit to break: its name, its clean days, what to do
 * instead).
 */
data class Suggestion(val title: String, val fields: List<String>)

/**
 * Suggestions as you type, from the catalogs ([AnchorCatalog], [HabitCatalog], [BreakCatalog]).
 * What is typed is compared without regard to case, hyphens or spaces ("pushups", "push ups" and
 * "press-ups" all find push-ups) with the start of any word of an entry, then with its other words
 * ([rank] 1), then, from four letters on, allowing one slip of the finger (2). The best come first,
 * the catalog's order within each, at most [MAX]; what the owner already has is never offered.
 */
object HabitsSuggest {
    const val MAX = 6

    /** Typed at the start of a word of the entry itself. */
    const val PREFIX = 0

    /** Typed at the start of one of its other words. */
    const val KEYWORD = 1

    /** One letter off either of those. */
    const val FUZZY = 2

    /** Lower case, letters and digits only, one space between words: "Push-ups!" is "push ups". */
    fun norm(text: String): String =
        buildString {
                for (c in text.lowercase()) {
                    when {
                        c.isLetterOrDigit() -> append(c)
                        c == '\'' || c == '’' -> Unit
                        else -> append(' ')
                    }
                }
            }
            .trim()
            .replace(SPACES, " ")

    /** Every way [phrase] can be begun at a word, its spaces taken out: "pushups", "ups". */
    private fun starts(phrase: String): List<String> {
        val words = norm(phrase).split(' ').filter { it.isNotEmpty() }
        return words.indices.map { words.subList(it, words.size).joinToString("") }
    }

    /** An entry ready to be compared; [common] ones lead among equals. */
    private class Index(phrase: String, keys: List<String>, val common: Boolean = false) {
        val own = starts(phrase)
        val other = keys.flatMap(::starts)
    }

    private val anchors by lazy { AnchorCatalog.ALL.map { Index(it.cue, it.keys) } }
    private val habits by lazy {
        HabitCatalog.ALL.mapIndexed { i, it -> Index(it.name, it.keys, i in commonHabits) }
    }
    private val breaks by lazy { BreakCatalog.ALL.map { Index(it.name, it.keys) } }

    /** Whether [a] and [b] are the same but for one letter changed, missing, added or swapped. */
    fun oneOff(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (i == a.length && i == b.length) return true
        val restA = a.substring(i)
        val restB = b.substring(i)
        return restA.drop(1) == restB.drop(1) || // one changed
            restA.drop(1) == restB || // one more in a
            restA == restB.drop(1) || // one more in b
            (restA.length >= 2 &&
                restB.length >= 2 &&
                restA[0] == restB[1] &&
                restA[1] == restB[0] &&
                restA.drop(2) == restB.drop(2)) // two swapped
    }

    private fun near(typed: String, start: String): Boolean =
        (typed.length - 1..typed.length + 1).any { n ->
            n in 1..start.length && oneOff(typed, start.take(n))
        }

    private fun rankOne(ix: Index, typed: String): Int? =
        when {
            ix.own.any { it.startsWith(typed) } -> PREFIX
            ix.other.any { it.startsWith(typed) } -> KEYWORD
            typed.length >= 4 &&
                (ix.own.any { near(typed, it) } || ix.other.any { near(typed, it) }) -> FUZZY
            else -> null
        }

    /**
     * How well [query] (already [norm]ed, not empty) finds the entry: as one run of letters, or,
     * typed as several words, each word by itself (the worst of them counts; little words such as
     * "my" and "the" are passed over). Null: not at all.
     */
    private fun rank(ix: Index, query: String): Int? {
        val whole = rankOne(ix, query.replace(" ", ""))
        val words = query.split(' ').filter { it !in LITTLE }
        if (words.size < 2 || !query.contains(' ')) return whole
        val each = words.map { rankOne(ix, it) }
        val apart = if (each.any { it == null }) null else each.maxOf { it!! }
        return listOfNotNull(whole, apart).minOrNull()
    }

    /** How well [query] finds [phrase] with its other words [keys]; null: not at all. */
    fun rank(query: String, phrase: String, keys: List<String> = emptyList()): Int? =
        norm(query).takeIf { it.isNotEmpty() }?.let { rank(Index(phrase, keys), it) }

    /**
     * The entries [query] finds, best first; among equals the well-worn ones, then by their place
     * in the catalog. One letter off is only offered while fewer than [FEW] entries are found
     * outright.
     */
    private fun find(indexes: List<Index>, query: String): List<Pair<Int, Int>> {
        val found =
            indexes
                .mapIndexedNotNull { i, ix -> rank(ix, query)?.let { i to it } }
                .sortedWith(
                    compareBy(
                        { it.second },
                        { if (indexes[it.first].common) 0 else 1 },
                        { it.first },
                    )
                )
        val sure = found.count { it.second < FUZZY }
        return if (sure >= FEW) found.filter { it.second < FUZZY } else found
    }

    /** The part of the day at [hour] (0 to 24). */
    fun dayPart(hour: Float): DayPart =
        when {
            hour >= 4f && hour < 11f -> DayPart.MORNING
            hour >= 11f && hour < 17f -> DayPart.DAY
            hour >= 17f && hour < 21f -> DayPart.EVENING
            else -> DayPart.NIGHT
        }

    /**
     * Foundations for what is typed in [query]. Nothing typed: the anchors of this part of the day
     * ([hour]) first, then those of the parts that follow. An activity the catalog does not know
     * ("yoga class") is offered as "After yoga class" and "Before yoga class". [taken] are the
     * foundations there already; [limit] is how long a cue may be.
     */
    fun anchors(query: String, hour: Float, taken: List<String>, limit: Int): List<Suggestion> {
        val has = taken.map(::norm).toSet()
        val q = norm(query)
        fun offer(cue: String) = Suggestion(cue, listOf(cue))
        if (q.isEmpty()) {
            val parts = DayPart.entries
            val now = dayPart(hour).ordinal
            return parts.indices
                .flatMap { step ->
                    val part = parts[(now + step) % parts.size]
                    // one of each kind of moment in turn, so the first few are not all alike
                    val kinds =
                        AnchorCatalog.ALL.filter { it.part == part }.groupBy { it.spot }.values
                    (0 until (kinds.maxOfOrNull { it.size } ?: 0)).flatMap { round ->
                        kinds.mapNotNull { it.getOrNull(round) }
                    }
                }
                .map { it.cue }
                .filter { norm(it) !in has }
                .take(MAX)
                .map(::offer)
        }
        val found = find(anchors, q)
        val out =
            found
                .map { AnchorCatalog.ALL[it.first].cue }
                .filter { norm(it) !in has }
                .toMutableList()
        // an activity of the owner's own, typed bare: say when
        val bare = HabitsRules.clean(query, limit)
        if (found.none { it.second < FUZZY } && q.length >= 3 && q.split(' ').first() !in WHEN) {
            val activity = bare.replaceFirstChar { it.lowercase() }
            val made =
                listOf("After $activity", "Before $activity").filter {
                    it.length <= limit && norm(it) !in has
                }
            out.addAll(0, made)
        }
        return out.distinctBy(::norm).take(MAX).map(::offer)
    }

    /** The moment the foundation [cue] is, by the catalog: its own entry, else one it mentions. */
    fun spotOf(cue: String): Spot? {
        val q = norm(cue)
        if (q.isEmpty()) return null
        AnchorCatalog.ALL.firstOrNull { norm(it.cue) == q }
            ?.let {
                return it.spot
            }
        return find(anchors, q)
            .firstOrNull { it.second < FUZZY }
            ?.let { AnchorCatalog.ALL[it.first].spot }
    }

    /**
     * Habits to build for what is typed in [query], on the foundation [cue]. Nothing typed: those
     * that suit that kind of moment first. [taken] are the names of the habits on it already.
     */
    fun habits(query: String, cue: String, taken: List<String>): List<Suggestion> {
        val has = taken.map(::norm).toSet()
        val q = norm(query)
        val order =
            if (q.isEmpty()) starters(HabitCatalog.ALL.map { it.fits }, spotOf(cue), commonHabits)
            else find(habits, q).map { it.first }
        return order
            .map { HabitCatalog.ALL[it] }
            .filter { h -> listOf(h.name, h.small, h.stretch).none { norm(it) in has } }
            .take(MAX)
            .map { Suggestion(it.name, listOf(it.name, it.small, it.stretch)) }
    }

    /** Habits to break, as [habits]. */
    fun breaks(query: String, cue: String, taken: List<String>): List<Suggestion> {
        val has = taken.map(::norm).toSet()
        val q = norm(query)
        val order =
            if (q.isEmpty()) starters(BreakCatalog.ALL.map { it.fits }, spotOf(cue), emptyList())
            else find(breaks, q).map { it.first }
        return order
            .map { BreakCatalog.ALL[it] }
            .filter { norm(it.name) !in has }
            .take(MAX)
            .map { Suggestion(it.name, listOf(it.name, it.days.toString(), it.instead)) }
    }

    /**
     * What to offer before anything is typed: the entries that fit [spot], those it is the first
     * moment of before the others, then the rest, [first] of them (the well-worn ones) leading.
     */
    private fun starters(fits: List<List<Spot>>, spot: Spot?, first: List<Int>): List<Int> {
        val fitting =
            if (spot == null) emptyList()
            else
                fits.indices
                    .filter { spot in fits[it] }
                    .sortedWith(compareBy({ fits[it].indexOf(spot) }, { it }))
        return (fitting + first + fits.indices).distinct()
    }

    /** The habits most people start with, offered where the foundation says nothing. */
    private val commonHabits by lazy {
        listOf(
                "Read 10 pages",
                "Drink a glass of water",
                "Meditate 10 minutes",
                "Do 10 push-ups",
                "Journal 5 minutes",
                "Stretch 10 minutes",
            )
            .map { name -> HabitCatalog.ALL.indexOfFirst { it.name == name } }
            .filter { it >= 0 }
    }

    private const val FEW = 3
    private val SPACES = Regex(" +")
    private val LITTLE = setOf("my", "the", "a", "an", "i", "to", "of", "im")
    private val WHEN = setOf("after", "before", "when", "at", "on", "while", "during", "in")
}
