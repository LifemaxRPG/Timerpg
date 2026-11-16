package com.lifemax.rpg.feature.habits.domain

import java.time.LocalDate

/*
 * Habits, the data and its rules: habit stacking, built literally (docs/specs/HABITS_TAB_BRIEF.md). A stack is
 * something the owner already does ("After coffee"); a habit is a block laid on it, in the order they are done.
 * A block's material shows how set the habit is (glass, clay, stone, gold, by total repetitions: a missed day
 * never takes any away), its height is the habit's size (two minutes, normal, stretch), a crack is one missed
 * day and a wobble is two. A habit being broken is a tower that comes down a floor for each clean day.
 * No XP, Sparks or streak are touched. Days are the phone's local days, as "YYYY-MM-DD", compared as text.
 * Pure Kotlin: nothing here reads a clock or a file.
 */

/** A foundation as saved. */
data class StackItem(val id: String, val cue: String, val treat: String = "")

/**
 * A habit as saved: the foundation it stands on, its [names] at two minutes, normal and stretch,
 * its own [size] (0..2), the day it was [born], what it is paired with, whether it carries the gold
 * seam of a come-back ([scar]), and, for one being broken, the [floors] it started with.
 */
data class HabitItem(
    val id: String,
    val stack: String,
    val kind: HabitKind,
    val names: List<String>,
    val size: Int,
    val born: String,
    val pair: String = "",
    val scar: Boolean = false,
    val floors: Int = 0,
)

/**
 * Everything Habits keeps (the habits file, schema 1). [n] numbers the ids given so far. [log] is,
 * by day, the size each habit was laid at plus one (2 for a clean day on one being broken); [rough]
 * the day that is a rough day (""); [claimed] the stacks whose rooftop reward was claimed, by day.
 */
data class HabitsSave(
    val who: String = "",
    val n: Int = 0,
    val stacks: List<StackItem> = emptyList(),
    val items: List<HabitItem> = emptyList(),
    val log: Map<String, Map<String, Int>> = emptyMap(),
    val rough: String = "",
    val claimed: Map<String, List<String>> = emptyMap(),
)

/** One day of the save, as the screen counts it. */
data class HabitsDay(
    val day: String,
    val live: Boolean,
    val who: String,
    val rough: Boolean,
    /** Blocks laid that day, of every kind. */
    val laid: Int,
    /**
     * That day's blocks laid, of the [due] habits being built (a clean day on one being broken is
     * not one).
     */
    val built: Int,
    val due: Int,
    val stacks: List<HabitStack>,
)

object HabitsRules {
    val LIMITS =
        HabitLimits(
            cue = 28,
            treat = 24,
            name = 32,
            pair = 28,
            who = 40,
            floorsMin = 3,
            floorsMax = 60,
            floors = 14,
        )
    val RULES = HabitRules(clay = 10, stone = 30, gold = 66, weeks = 12)
    private val SPACES = Regex("\\s+")

    /**
     * What is typed, tidied: runs of white space are one space, none at the ends, at most [max]
     * long.
     */
    fun clean(text: String, max: Int): String = text.replace(SPACES, " ").trim().take(max)

    fun addDays(day: String, n: Int): String = LocalDate.parse(day).plusDays(n.toLong()).toString()

    fun materialAt(reps: Int): HabitMaterial =
        when {
            reps >= RULES.gold -> HabitMaterial.GOLD
            reps >= RULES.stone -> HabitMaterial.STONE
            reps >= RULES.clay -> HabitMaterial.CLAY
            else -> HabitMaterial.GLASS
        }

    private fun doneOn(s: HabitsSave, id: String, day: String): Boolean =
        (s.log[day]?.get(id) ?: 0) > 0

    /** Every day up to [day] on which the habit was laid: a missed day never takes one away. */
    private fun repsAt(s: HabitsSave, id: String, day: String): Int =
        s.log.count { (d, laid) -> d <= day && (laid[id] ?: 0) > 0 }

    /**
     * Days in a row before [day], back to the habit's first day, with no block laid (30 at most).
     */
    private fun missed(s: HabitsSave, item: HabitItem, day: String): Int {
        var n = 0
        var d = addDays(day, -1)
        while (d >= item.born && !doneOn(s, item.id, d) && n < 30) {
            n++
            d = addDays(d, -1)
        }
        return n
    }

    private fun block(s: HabitsSave, item: HabitItem, day: String, live: Boolean): HabitBlock {
        val reps = repsAt(s, item.id, day)
        val done = live && doneOn(s, item.id, day)
        val fade = item.kind == HabitKind.FADE
        val size =
            when {
                fade -> 1
                done -> (s.log.getValue(day).getValue(item.id) - 1).coerceIn(0, 2)
                live && s.rough == day -> 0
                else -> item.size
            }
        val floors = if (fade) item.floors.takeIf { it > 0 } ?: LIMITS.floors else 0
        val left = if (fade) (floors - reps).coerceAtLeast(0) else 0
        return HabitBlock(
            id = item.id,
            kind = item.kind,
            name = item.names[size],
            names = item.names,
            size = size,
            set = item.size,
            reps = reps,
            done = done,
            miss = if (live && !fade && !done) missed(s, item, day) else 0,
            scar = live && item.scar,
            material = if (fade) HabitMaterial.FADE else materialAt(reps),
            left = left,
            floors = floors,
            cleared = fade && left == 0,
            pair = item.pair,
        )
    }

    /**
     * The save as it stood on [day] ([today] by default). Only today is live: it alone has blocks
     * laid or not, cracks, seams, a next block and a reward; a habit is not there before it was
     * born.
     */
    fun day(s: HabitsSave, today: String, day: String = today): HabitsDay {
        val live = day == today
        val stacks =
            s.stacks.map { st ->
                val blocks =
                    s.items
                        .filter { it.stack == st.id && it.born <= day }
                        .map { block(s, it, day, live) }
                val claimed = s.claimed[day]?.contains(st.id) == true
                val next = if (live) blocks.firstOrNull { !it.done && !it.cleared } else null
                HabitStack(
                    id = st.id,
                    cue = st.cue,
                    treat = st.treat,
                    claimed = claimed,
                    claimable =
                        live &&
                            st.treat.isNotEmpty() &&
                            next == null &&
                            !claimed &&
                            blocks.isNotEmpty(),
                    removable = s.items.none { it.stack == st.id },
                    next = next?.id.orEmpty(),
                    blocks = blocks,
                )
            }
        val build = stacks.flatMap { it.blocks }.filter { !it.fade }
        return HabitsDay(
            day,
            live,
            s.who,
            live && s.rough == day,
            s.log[day]?.size ?: 0,
            build.count { it.done },
            build.size,
            stacks,
        )
    }

    // ---- changes: each answers the save after it, or null when there is nothing to do or it is
    // not allowed ----

    fun addStack(s: HabitsSave, cue: String): Pair<HabitsSave, String>? {
        val tidy =
            clean(cue, LIMITS.cue).ifEmpty {
                return null
            }
        val id = "s${s.n + 1}"
        return s.copy(n = s.n + 1, stacks = s.stacks + StackItem(id, tidy)) to id
    }

    /**
     * A foundation's cue (an empty one is left as it is) and its rooftop reward (an empty one
     * clears it).
     */
    fun editStack(
        s: HabitsSave,
        id: String,
        cue: String? = null,
        treat: String? = null,
    ): HabitsSave? {
        if (s.stacks.none { it.id == id }) return null
        return s.copy(
            stacks =
                s.stacks.map {
                    if (it.id != id) it
                    else
                        it.copy(
                            cue =
                                cue?.let { c -> clean(c, LIMITS.cue) }?.ifEmpty { null } ?: it.cue,
                            treat = treat?.let { t -> clean(t, LIMITS.treat) } ?: it.treat,
                        )
                }
        )
    }

    /** Only a foundation nothing stands on goes. */
    fun removeStack(s: HabitsSave, id: String): HabitsSave? =
        if (s.stacks.none { it.id == id } || s.items.any { it.stack == id }) null
        else s.copy(stacks = s.stacks.filter { it.id != id })

    /**
     * A new habit on [stack], born [today]: one to build starts at its two-minute size ([small],
     * else its name) and may name its stretch size ([big]); one to break gets [floors] clean days
     * to come down (the default when 0, held to the limits). [pair] is what it is paired with, or
     * done instead.
     */
    fun addHabit(
        s: HabitsSave,
        today: String,
        stack: String,
        name: String,
        small: String = "",
        big: String = "",
        fade: Boolean = false,
        floors: Int = 0,
        pair: String = "",
    ): Pair<HabitsSave, String>? {
        val tidy =
            clean(name, LIMITS.name).ifEmpty {
                return null
            }
        if (s.stacks.none { it.id == stack }) return null
        val id = "h${s.n + 1}"
        val item =
            HabitItem(
                id = id,
                stack = stack,
                kind = if (fade) HabitKind.FADE else HabitKind.BUILD,
                names =
                    listOf(
                        clean(small, LIMITS.name).ifEmpty { tidy },
                        tidy,
                        clean(big, LIMITS.name).ifEmpty { tidy },
                    ),
                size = if (fade) 1 else 0,
                born = today,
                pair = clean(pair, LIMITS.pair),
                floors =
                    if (fade)
                        (if (floors == 0) LIMITS.floors else floors).coerceIn(
                            LIMITS.floorsMin,
                            LIMITS.floorsMax,
                        )
                    else 0,
            )
        return s.copy(n = s.n + 1, items = s.items + item) to id
    }

    private fun edit(s: HabitsSave, id: String, change: (HabitItem) -> HabitItem?): HabitsSave? {
        val item = s.items.firstOrNull { it.id == id } ?: return null
        val next = change(item) ?: return null
        return s.copy(items = s.items.map { if (it.id == id) next else it })
    }

    /** The names at each size; the normal one is needed, the others fall back to it. */
    fun rename(s: HabitsSave, id: String, names: List<String>): HabitsSave? =
        edit(s, id) { item ->
            val n = List(3) { clean(names.getOrElse(it) { "" }, LIMITS.name) }
            if (n[1].isEmpty()) null
            else item.copy(names = listOf(n[0].ifEmpty { n[1] }, n[1], n[2].ifEmpty { n[1] }))
        }

    /** The habit's own size; one being broken has none. */
    fun resize(s: HabitsSave, id: String, size: Int): HabitsSave? =
        edit(s, id) {
            if (it.kind == HabitKind.FADE || size !in 0..2) null else it.copy(size = size)
        }

    fun pair(s: HabitsSave, id: String, text: String): HabitsSave? =
        edit(s, id) { it.copy(pair = clean(text, LIMITS.pair)) }

    /** Onto another foundation, as its last block. */
    fun restack(s: HabitsSave, id: String, stack: String): HabitsSave? {
        val item = s.items.firstOrNull { it.id == id } ?: return null
        if (item.stack == stack || s.stacks.none { it.id == stack }) return null
        return s.copy(items = s.items.filter { it.id != id } + item.copy(stack = stack))
    }

    /** Earlier ([dir] below 0) or later in its stack: the order the habits are done in. */
    fun move(s: HabitsSave, id: String, dir: Int): HabitsSave? {
        val item = s.items.firstOrNull { it.id == id } ?: return null
        val mine = s.items.filter { it.stack == item.stack }
        val other = mine.getOrNull(mine.indexOf(item) + if (dir < 0) -1 else 1) ?: return null
        return s.copy(
            items =
                s.items.map {
                    when (it.id) {
                        item.id -> other
                        other.id -> item
                        else -> it
                    }
                }
        )
    }

    /** The habit and everything laid for it go. */
    fun retire(s: HabitsSave, id: String): HabitsSave? {
        if (s.items.none { it.id == id }) return null
        return s.copy(
            items = s.items.filter { it.id != id },
            log = s.log.mapValues { (_, laid) -> laid - id }.filterValues { it.isNotEmpty() },
        )
    }

    /**
     * Lay [today]'s block (a clean day, for a habit being broken), once a day. Coming back after a
     * missed day leaves a gold seam. A rough day lays the two-minute size and leaves the habit's
     * own size alone.
     */
    fun lay(s: HabitsSave, today: String, id: String): HabitsSave? {
        val item = s.items.firstOrNull { it.id == id } ?: return null
        if (doneOn(s, id, today)) return null
        val fade = item.kind == HabitKind.FADE
        val scarred = !fade && missed(s, item, today) >= 1
        val value = if (fade) 2 else (if (s.rough == today) 0 else item.size) + 1
        return s.copy(
            items =
                if (scarred) s.items.map { if (it.id == id) it.copy(scar = true) else it }
                else s.items,
            log = s.log + (today to (s.log[today].orEmpty() + (id to value))),
        )
    }

    /** Take [today]'s block back. */
    fun undo(s: HabitsSave, today: String, id: String): HabitsSave? {
        if (!doneOn(s, id, today)) return null
        val rest = s.log.getValue(today) - id
        return s.copy(log = if (rest.isEmpty()) s.log - today else s.log + (today to rest))
    }

    /**
     * The rooftop reward: only once the stack is done, once a day. Earlier days' claims are let go.
     */
    fun claim(s: HabitsSave, today: String, stack: String): HabitsSave? {
        val st = day(s, today).stacks.firstOrNull { it.id == stack } ?: return null
        if (!st.claimable) return null
        return s.copy(claimed = mapOf(today to (s.claimed[today].orEmpty() + stack)))
    }

    fun rough(s: HabitsSave, today: String, on: Boolean): HabitsSave? =
        if ((s.rough == today) == on) null else s.copy(rough = if (on) today else "")

    fun who(s: HabitsSave, text: String): HabitsSave? =
        clean(text, LIMITS.who).let { if (it == s.who) null else s.copy(who = it) }
}
